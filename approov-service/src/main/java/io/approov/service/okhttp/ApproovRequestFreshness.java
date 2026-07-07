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
import java.util.Set;

import okhttp3.Request;

/**
 * ApproovRequestFreshness is attached as a tag to requests that have been given
 * Approov protection (token and, optionally, a message signature) so that the
 * time the protection was applied can be checked again at the network layer,
 * immediately before the request is transmitted. A request may be held between
 * protection and transmission, most notably if the device enters a deep sleep
 * or doze state while the request is queued, or if the app holds requests in a
 * retry/backoff mechanism. In that case the Approov token, and any message
 * signature (which carries created/expires timestamps), may no longer be valid
 * by the time the request is sent and the protection must be refreshed.
 */
class ApproovRequestFreshness {
    // the URL string that was used for the Approov token fetch
    private final String fetchURL;

    // the mutations that were applied to the request by the Approov interceptor,
    // required to reapply protection to the request
    private final ApproovRequestMutations changes;

    // elapsed realtime (which advances during device sleep) at the point the
    // protection was applied, or -1 if protection has not yet been marked
    private volatile long protectedAtMillis;

    // names of the headers that were added by the mutator's processed request
    // callback (normally the message signature headers) which must be removed
    // before the protection can be reapplied
    private volatile List<String> mutatorAddedHeaders;

    /**
     * Constructs a freshness marker for a request being given Approov protection.
     *
     * @param fetchURL the URL string used for the Approov token fetch
     * @param changes  the mutations applied to the request by the interceptor
     */
    ApproovRequestFreshness(String fetchURL, ApproovRequestMutations changes) {
        this.fetchURL = fetchURL;
        this.changes = changes;
        this.protectedAtMillis = -1;
        this.mutatorAddedHeaders = Collections.emptyList();
    }

    /**
     * Gets the URL string that was used for the Approov token fetch.
     *
     * @return the token fetch URL string
     */
    String getFetchURL() {
        return fetchURL;
    }

    /**
     * Gets the mutations that were applied to the request by the interceptor.
     *
     * @return the request mutations
     */
    ApproovRequestMutations getChanges() {
        return changes;
    }

    /**
     * Gets the elapsed realtime at which the protection was last applied.
     *
     * @return the elapsed realtime in milliseconds, or -1 if not yet marked
     */
    long getProtectedAtMillis() {
        return protectedAtMillis;
    }

    /**
     * Gets the names of the headers that were added by the mutator's processed
     * request callback when the protection was last applied.
     *
     * @return the list of header names, which may be empty
     */
    List<String> getMutatorAddedHeaders() {
        return mutatorAddedHeaders;
    }

    /**
     * Marks the request as protected at the given time, recording the headers
     * added by the mutator's processed request callback so that they can be
     * removed if the protection needs to be reapplied later.
     *
     * @param protectedAtMillis   the elapsed realtime in milliseconds
     * @param mutatorAddedHeaders the names of headers added by the mutator
     */
    void markProtected(long protectedAtMillis, List<String> mutatorAddedHeaders) {
        this.protectedAtMillis = protectedAtMillis;
        this.mutatorAddedHeaders = mutatorAddedHeaders;
    }

    /**
     * Computes the names of the headers present in the after request that were
     * not present in the before request. Header name comparison is case
     * insensitive.
     *
     * @param before the request prior to the mutator's processed request callback
     * @param after  the request returned by the callback
     * @return the list of header names added by the callback, which may be empty
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
