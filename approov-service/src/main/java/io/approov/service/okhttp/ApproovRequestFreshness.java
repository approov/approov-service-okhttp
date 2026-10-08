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

// ApproovRequestFreshness is attached as a tag to a request given Approov protection and describes exactly the
// protection applied, so that the network layer can strip and reapply it if the request was held too long
// before transmission (such as during a device doze) or was redirected to another URL
class ApproovRequestFreshness {
    // the URL string that was used for the Approov token fetch
    private final String fetchURL;

    // the mutations applied to the request by the Approov interceptor, needed to strip the protection
    // from the request; a private copy once the layer has applied them
    private volatile ApproovRequestMutations changes;

    // elapsed realtime (which advances during device sleep) when the protection was applied, or -1 if
    // not yet marked
    private volatile long protectedAtMillis;

    // names of the headers added by the processed request callback or message signing, which must be
    // removed before the protection is reapplied
    private volatile List<String> mutatorAddedHeaders;

    // the URL of the request as it left the Approov interceptor (after any query parameter substitution),
    // or null if unknown; an attempt with a different URL is a redirect that must be reclassified
    private volatile String appliedURL;

    // the HTTP method of the request as it left the Approov interceptor; an attempt with a different
    // method (a 303 turning a POST into a GET) must be reprotected
    private volatile String appliedMethod;

    // the headers of the request on its first network attempt (OkHttp adds its transport headers before
    // the network layer), or null until then; an attempt with different headers (an Authenticator
    // replacing Authorization on a 401) must be reprotected
    private volatile Headers appliedHeaders;

    // the complete ordered values each substituted header had before substitution, so that the
    // placeholders can be restored when the protection is stripped
    private volatile Map<String, List<String>> originalHeaderValues;

    // a SHA-256 digest of the value each substituted header was given, a secure string or a failure
    // status, so that a placeholder is only restored if the header still holds what we installed; a
    // digest is held so that we keep no copy of a secure string
    private volatile Map<String, String> installedHeaderDigests;

    // the URL before any query parameter was given a secure string or a failure status, or null if the
    // URL was not changed
    private volatile String originalURL;

    /**
     * Constructs a marker for protection applied to a request.
     *
     * @param fetchURL is the URL string used for the Approov token fetch
     * @param changes is the mutations applied to the request
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
        this.originalURL = null;
    }

    // getters and setters for the protection state
    String getFetchURL() {
        return fetchURL;
    }

    ApproovRequestMutations getChanges() {
        return changes;
    }

    /**
     * Replaces the mutations with a private copy, before the processed request callback is given the
     * original, so that the protection stripped and signed is what the layer applied.
     */
    void freezeChanges() {
        changes = changes.copy();
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
     * @param protectedAtMillis is the elapsed realtime at which protection was applied
     * @param mutatorAddedHeaders is the names of the headers added by the processed request callback
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

    String getOriginalURL() {
        return originalURL;
    }

    /**
     * Determines if a header still holds the value this layer installed by substitution.
     *
     * @param header is the header name
     * @param value is the current single value of the header
     * @return true if the value is the one installed, false otherwise
     */
    boolean isInstalledValue(String header, String value) {
        String digest = installedHeaderDigests.get(header);
        return (digest != null) && digest.equals(digestOf(value));
    }

    /**
     * Records the substitutions applied to the request, of secure strings and of failure statuses written in
     * place of placeholders.
     *
     * @param originalHeaderValues is the complete values each header had before substitution
     * @param installedHeaderValues is the value each header carries after substitution, as stored on the
     * built request (OkHttp trims header values), of which only a digest is kept
     * @param originalURL is the URL before any query parameter substitution, or null if the URL was not changed
     */
    void setSubstitutions(Map<String, List<String>> originalHeaderValues, Map<String, String> installedHeaderValues,
            String originalURL) {
        this.originalHeaderValues = originalHeaderValues;
        this.originalURL = originalURL;
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
     * Determines the names of the headers present on the after request that are not present on the before
     * request, compared case insensitively.
     *
     * @param before is the request before the processed request callback
     * @param after is the request after the processed request callback
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
