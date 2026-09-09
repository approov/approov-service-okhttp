//
// MIT License
// 
// Copyright (c) 2016-present, Approov Ltd.
//
// Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files
// (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge,
// publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so,
// subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
// 
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
// MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
// ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH
// THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.


package io.approov.service.okhttp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import okhttp3.Headers;
import okhttp3.Request;

/**
 * ApproovRequestFreshness is attached as a tag to requests that have been given
 * Approov protection (token, status header and, by default, message signatures)
 * so that the protection can be checked again at the network layer, immediately
 * before the request is transmitted. A request may be held between protection
 * and transmission, most notably if the device enters a deep sleep or doze state
 * while the request is queued, or if the app holds requests in a retry/backoff
 * mechanism; the token and any message signature (which carries created/expires
 * timestamps) may then no longer be valid and the protection must be refreshed.
 * A request may also be redirected by OkHttp to a different URL, possibly on a
 * host Approov does not protect, in which case the protection of the original
 * destination must be stripped and the new destination classified afresh. The
 * marker describes exactly the protection applied to the request it is attached
 * to, so that it can be removed and reapplied.
 */
class ApproovRequestFreshness {
    // the URL string that was used for the Approov token fetch
    private final String fetchURL;

    // the mutations that were applied to the request by the Approov interceptor,
    // required to strip the protection from the request
    private final ApproovRequestMutations changes;

    // elapsed realtime (which advances during device sleep) at the point the
    // protection was applied, or -1 if protection has not yet been marked
    private volatile long protectedAtMillis;

    // names of the headers that were added by the mutator's processed request
    // callback (normally the message signature headers) which must be removed
    // before the protection can be reapplied
    private volatile List<String> mutatorAddedHeaders;

    // the URL of the request as it left the Approov interceptor (after any query
    // parameter substitution), or null if unknown; a network attempt whose URL
    // differs is a redirect followup that must be reclassified
    private volatile String appliedURL;

    // the HTTP method of the request as it left the Approov interceptor; a network
    // attempt whose method differs (a 303 turning a POST into a GET) carries a
    // signature over the wrong method and must be reprotected
    private volatile String appliedMethod;

    // the headers of the request as seen by the network layer on the first attempt
    // after the protection was applied (OkHttp adds its own transport headers between
    // the application and the network interceptors, so the baseline is taken there);
    // a later attempt whose headers differ was rebuilt by the app or by OkHttp (an
    // Authenticator replacing Authorization on a 401) and any signature over the old
    // headers is invalid, so it must be reprotected. Null until the first attempt.
    private volatile Headers appliedHeaders;

    // the complete ordered values each substituted header had before substitution,
    // so that the placeholders can be restored when the protection is stripped
    private volatile Map<String, List<String>> originalHeaderValues;

    // a SHA-256 digest of the value each substituted header was given, as stored on
    // the built request, so that the placeholder is only restored if the header still
    // holds what this layer installed and not a value the app changed afterwards (for
    // example from an OkHttp authenticator). A digest rather than the value: the layer
    // keeps no copy of a secure string beyond the request that carries it.
    private volatile Map<String, String> installedHeaderDigests;

    /**
     * Constructs a marker for protection applied to a request.
     *
     * @param fetchURL the URL string used for the Approov token fetch
     * @param changes  the mutations applied to the request
     */
    ApproovRequestFreshness(String fetchURL, ApproovRequestMutations changes) {
        this.fetchURL = fetchURL;
        this.changes = changes;
        this.protectedAtMillis = -1;
        this.mutatorAddedHeaders = Collections.emptyList();
        this.appliedURL = null;
        this.appliedMethod = null;
        this.appliedHeaders = null;
        this.originalHeaderValues = Collections.emptyMap();
        this.installedHeaderDigests = Collections.emptyMap();
    }

    String getFetchURL() {
        return fetchURL;
    }

    ApproovRequestMutations getChanges() {
        return changes;
    }

    long getProtectedAtMillis() {
        return protectedAtMillis;
    }

    List<String> getMutatorAddedHeaders() {
        return mutatorAddedHeaders;
    }

    /**
     * Records that the protection has been applied.
     *
     * @param protectedAtMillis   elapsed realtime at which protection was applied
     * @param mutatorAddedHeaders names of headers added by the processed request
     *                            callback
     */
    void markProtected(long protectedAtMillis, List<String> mutatorAddedHeaders) {
        this.protectedAtMillis = protectedAtMillis;
        this.mutatorAddedHeaders = mutatorAddedHeaders;
    }

    String getAppliedURL() {
        return appliedURL;
    }

    void setAppliedURL(String appliedURL) {
        this.appliedURL = appliedURL;
    }

    String getAppliedMethod() {
        return appliedMethod;
    }

    void setAppliedMethod(String appliedMethod) {
        this.appliedMethod = appliedMethod;
    }

    Headers getAppliedHeaders() {
        return appliedHeaders;
    }

    void setAppliedHeaders(Headers appliedHeaders) {
        this.appliedHeaders = appliedHeaders;
    }

    Map<String, List<String>> getOriginalHeaderValues() {
        return originalHeaderValues;
    }

    /**
     * Indicates whether a header still holds the value this layer installed by
     * substitution.
     *
     * @param header the header name
     * @param value  the header's current single value
     * @return true if the value is the one installed
     */
    boolean isInstalledValue(String header, String value) {
        String digest = installedHeaderDigests.get(header);
        return (digest != null) && digest.equals(digestOf(value));
    }

    /**
     * Records the secure string substitutions applied to the request.
     *
     * @param originalHeaderValues  the complete values each header had before
     * @param installedHeaderValues the value each header carries after substitution,
     *                              as stored on the built request (OkHttp trims
     *                              header values when they are set); only a digest
     *                              is kept
     */
    void setSubstitutions(Map<String, List<String>> originalHeaderValues, Map<String, String> installedHeaderValues) {
        this.originalHeaderValues = originalHeaderValues;
        Map<String, String> digests = new java.util.LinkedHashMap<>(installedHeaderValues.size());
        for (Map.Entry<String, String> entry : installedHeaderValues.entrySet())
            digests.put(entry.getKey(), digestOf(entry.getValue()));
        this.installedHeaderDigests = digests;
    }

    // SHA-256 of a header value as lowercase hex, or "" for null
    private static String digestOf(String value) {
        if (value == null)
            return "";
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash)
                sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Determines the names of the headers present on the after request that are
     * not present on the before request, compared case insensitively.
     *
     * @param before the request before the processed request callback
     * @param after  the request after the processed request callback
     * @return the names of the added headers
     */
    static List<String> addedHeaderNames(Request before, Request after) {
        // names() provides a set ordered with a case insensitive comparator so
        // that the contains check is also case insensitive
        Set<String> beforeNames = before.headers().names();
        List<String> added = new ArrayList<>();
        for (String name : after.headers().names()) {
            if (!beforeNames.contains(name))
                added.add(name);
        }
        return added;
    }
}
