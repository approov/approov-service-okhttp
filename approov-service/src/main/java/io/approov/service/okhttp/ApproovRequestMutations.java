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

import java.util.List;

// ApproovRequestMutations holds the changes made to a request by Approov, such as the token header and the
// substituted headers and query parameters
public class ApproovRequestMutations {
    // the header carrying the Approov token, or null if none was added
    private String tokenHeaderKey;

    // the prefix placed before the Approov token, or null if none
    private String tokenHeaderPrefix;

    // the header carrying the Approov TraceID, or null if none was added
    private String traceIDHeaderKey;

    // the header carrying the Approov fetch status, or null if none was added
    private String statusHeaderKey;

    // the headers substituted with secure strings, or null if none
    private List<String> substitutionHeaderKeys;

    // the URL before any query parameter substitution, or null if there was none
    private String originalURL;

    // the query parameters substituted with secure strings, or null if none
    private List<String> substitutionQueryParamKeys;

    /**
     * Gets a copy of these mutations that is not changed by later calls to the setters of this object. The
     * layer keeps such a copy of what it applied to a request, since the service mutator may change the
     * object it is given.
     *
     * @return the copy of the mutations
     */
    ApproovRequestMutations copy() {
        ApproovRequestMutations copy = new ApproovRequestMutations();
        copy.tokenHeaderKey = tokenHeaderKey;
        copy.tokenHeaderPrefix = tokenHeaderPrefix;
        copy.traceIDHeaderKey = traceIDHeaderKey;
        copy.statusHeaderKey = statusHeaderKey;
        copy.substitutionHeaderKeys = (substitutionHeaderKeys != null)
                ? new java.util.ArrayList<>(substitutionHeaderKeys) : null;
        copy.originalURL = originalURL;
        copy.substitutionQueryParamKeys = (substitutionQueryParamKeys != null)
                ? new java.util.ArrayList<>(substitutionQueryParamKeys) : null;
        return copy;
    }

    /**
     * Gets the header key used for the Approov token.
     *
     * @return the Approov token header key
     */
    public String getTokenHeaderKey() {
        return tokenHeaderKey;
    }

    /**
     * Sets the header key used for the Approov token.
     *
     * @param tokenHeaderKey is the Approov token header key
     */
    public void setTokenHeaderKey(String tokenHeaderKey) {
        this.tokenHeaderKey = tokenHeaderKey;
    }

    /**
     * Gets the prefix placed before the Approov token in the token header, as configured when the request
     * was processed.
     *
     * @return the token header prefix, or an empty string if there is none
     */
    public String getTokenHeaderPrefix() {
        return (tokenHeaderPrefix != null) ? tokenHeaderPrefix : "";
    }

    /**
     * Sets the prefix placed before the Approov token in the token header.
     *
     * @param tokenHeaderPrefix is the token header prefix
     */
    public void setTokenHeaderPrefix(String tokenHeaderPrefix) {
        this.tokenHeaderPrefix = tokenHeaderPrefix;
    }

    /**
     * Gets the header key used for the optional Approov TraceID debug header.
     *
     * @return the Approov TraceID header key, or null if the TraceID header was not used
     */
    public String getTraceIDHeaderKey() {
        return traceIDHeaderKey;
    }

    /**
     * Sets the header key used for the optional Approov TraceID debug header.
     *
     * @param traceIDHeaderKey is the Approov TraceID header key
     */
    public void setTraceIDHeaderKey(String traceIDHeaderKey) {
        this.traceIDHeaderKey = traceIDHeaderKey;
    }

    /**
     * Gets the header key used for the Approov status header, which reports the Approov token fetch status
     * in lowercase.
     *
     * @return the Approov status header key, or null if the status header was not used
     */
    public String getStatusHeaderKey() {
        return statusHeaderKey;
    }

    /**
     * Sets the header key used for the Approov status header.
     *
     * @param statusHeaderKey is the Approov status header key
     */
    public void setStatusHeaderKey(String statusHeaderKey) {
        this.statusHeaderKey = statusHeaderKey;
    }

    /**
     * Gets the list of headers that were substituted with secure strings. A header whose secure string
     * fetch failed, carrying the fetch status in place of its placeholder, is not listed.
     *
     * @return the list of substituted header keys
     */
    public List<String> getSubstitutionHeaderKeys() {
        return substitutionHeaderKeys;
    }

    /**
     * Sets the list of headers that were substituted with secure strings.
     *
     * @param substitutionHeaderKeys is the list of substituted header keys
     */
    public void setSubstitutionHeaderKeys(List<String> substitutionHeaderKeys) {
        this.substitutionHeaderKeys = substitutionHeaderKeys;
    }

    /**
     * Gets the original URL before any query parameter substitutions, if a query parameter was substituted
     * with a secure string.
     *
     * @return the original URL, or null if no query parameter was substituted with a secure string
     */
    public String getOriginalURL() {
        return originalURL;
    }

    /**
     * Gets the list of query parameter keys that were substituted with secure strings. A query parameter
     * whose secure string fetch failed, carrying the fetch status in place of its placeholder, is not listed.
     *
     * @return the list of substituted query parameter keys
     */
    public List<String> getSubstitutionQueryParamKeys() {
        return substitutionQueryParamKeys;
    }

    /**
     * Sets the results of query parameter substitutions.
     *
     * @param originalURL is the original URL before substitutions
     * @param substitutionQueryParamKeys is the list of substituted query parameter keys
     */
    public void setSubstitutionQueryParamResults(String originalURL, List<String> substitutionQueryParamKeys) {
        this.originalURL = originalURL;
        this.substitutionQueryParamKeys = substitutionQueryParamKeys;
    }
}
