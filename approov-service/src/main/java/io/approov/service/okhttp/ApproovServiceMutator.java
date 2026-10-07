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

import com.criticalblue.approovsdk.Approov;
import okhttp3.Request;
import java.io.IOException;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * ApproovServiceMutator provides an interface for modifying the behavior of
 * the ApproovService class by overriding the default implementations of the
 * defined callbacks. Opportunities to modify behavior are offered at key
 * points in the service and attestation flows. It is the single place where an
 * app changes the request decisions of the layer.
 *
 * The interface provides default implementations for all methods, so
 * implementing classes can choose to override only the methods they are
 * interested in. Three standard decision sets are provided:
 *
 * - {@link #CLOSE_FAILURE}: the 3.5.x interceptor decisions on the token fetch,
 *   which are also the interface defaults. A token fetch proceeds on SUCCESS
 *   (token header) and NO_APPROOV_SERVICE (status header only, no token header),
 *   a request to an UNKNOWN_URL or UNPROTECTED_URL is sent with no Approov
 *   headers, NO_NETWORK, POOR_NETWORK and UNTRUSTED_NETWORK throw
 *   ApproovNetworkException (an app that wants them to proceed installs
 *   ALWAYS_PROCEED or its own mutator), and every other status throws
 *   ApproovFetchStatusException, REJECTED included. A secure string
 *   substitution is made on SUCCESS; for every other secure string status the
 *   placeholder is left and the request proceeds.
 * - {@link #ALWAYS_PROCEED}: never aborts a request. A token fetch outcome other
 *   than SUCCESS is an outcome, not a failure: the request is sent with no token
 *   header and the status on the status header, a failed secure string
 *   substitution leaves its placeholder in place, and the backend, which is the
 *   enforcement point, decides.
 * - {@link #DEFAULT}: the mutator in force until the app installs its own, and
 *   reinstated by ApproovService.setServiceMutator(null). It points to whatever
 *   the release considers default behaviour: in 3.8.0 that is CLOSE_FAILURE, and
 *   in 4.0.0 it is planned to become the ALWAYS_PROCEED behaviour, a breaking
 *   change kept for the major release. An app that wants a fixed behaviour names
 *   CLOSE_FAILURE or ALWAYS_PROCEED explicitly.
 *
 * The token fetch status of the request URL puts the request on a channel
 * (SPECIFICATION 1.5, decided 2026-10-07): SUCCESS is a token-protected API,
 * UNPROTECTED_URL a secrets-only API added with -noApproovToken (pinned, no
 * token required), UNKNOWN_URL a host not added to Approov, and every other
 * status a failure, which says nothing about the host. A failure for a host that
 * is not a key of the SDK pin set (the "*" key excluded; also when the pin set is
 * empty or cannot be read) is treated like UNKNOWN_URL: the request goes out
 * untouched whatever handleInterceptorFetchTokenResult returns, and an exception
 * a standard decision raises there (inherited or called) is ignored, so the
 * standard mutators never abort it; an exception the app's own hook code throws
 * aborts it, an opt-in for an app that only talks to its own protected hosts
 * (decided 2026-10-07). Otherwise the token fetch
 * decision (handleInterceptorFetchTokenResult) may abort a request on any status; when it
 * lets a request proceed it governs the Approov headers and the signatures: a
 * SUCCESS request gets the token, status and trace headers and is signed, a
 * failure status gets the status header only (no token header, no trace header,
 * no signatures), and UNKNOWN_URL and UNPROTECTED_URL get no Approov header
 * whatever it answers. Secure strings follow the channel, not that decision:
 * they are substituted, as the substitution decisions say, only on a SUCCESS or
 * UNPROTECTED_URL request over https, and never on UNKNOWN_URL or a failure
 * status. The status header always describes the token fetch (see
 * ApproovService.setStatusHeader). No mutator signs requests, and installing a
 * mutator never switches message signing on or off: signing is independent of
 * the decisions (see ApproovService.enableMessageSigning).
 *
 * An app that wants a request aborted for some further outcome opts in by
 * overriding the relevant interceptor hook and throwing. Such an exception must
 * be a standard network stack exception (java.io.IOException or one of its
 * platform subclasses such as java.net.ConnectException or
 * javax.net.ssl.SSLException), not an Approov specific type: the abort is the
 * app's own policy and must surface to the app's error handling and support as
 * an ordinary network failure. The interceptor hooks therefore declare
 * IOException, and an IOException a hook throws reaches the app unchanged.
 * Anything else a hook throws (a programming error such as a null pointer, or a
 * deliberate RuntimeException) is treated as a failed request, never as a crash:
 * the layer converts it to an ApproovException with the original as its cause and
 * logs it at error level naming the hook (SPECIFICATION 1.6.1). The aborts of
 * CLOSE_FAILURE keep the 3.5.x Approov exception types (subclasses of IOException)
 * so that 3.8.0 does not change what an existing app catches, in a custom mutator
 * too, whether it inherits those decisions or calls them (for example
 * CLOSE_FAILURE.handleInterceptorFetchTokenResult). The standard substitution
 * decisions never abort a request; a custom mutator may still throw from its
 * substitution hooks.
 * Any other Approov exception a hook throws, one the app's code built or one it
 * rethrows from a direct method such as fetchToken, is the hook's failure like a
 * RuntimeException: an ApproovException naming the hook with the original as its
 * cause (SPECIFICATION 1.6(b), 1.6.1). The direct fetch APIs (fetchToken, fetchSecureString, fetchCustomJWT,
 * precheck) return a value to the caller and continue to report failures with
 * an ApproovException subclass.
 */
public interface ApproovServiceMutator {
    /**
     * The 3.5.x interceptor decisions on the token fetch, which are the interface
     * defaults: proceed on SUCCESS and NO_APPROOV_SERVICE, add no Approov header to
     * UNKNOWN_URL and UNPROTECTED_URL, and throw the layer's Approov exception for
     * every other token fetch status. A secure string is substituted on SUCCESS;
     * every other secure string status leaves the placeholder and the request
     * proceeds. Never signs.
     */
    public static final ApproovServiceMutator CLOSE_FAILURE = new ApproovServiceMutator() {
        @Override
        public String toString() {
            return "ApproovServiceMutator.CLOSE_FAILURE";
        }

        @Override
        public boolean supportsProtectionRefresh() {
            // the default handleInterceptorProcessedRequest makes no changes so it
            // is trivially safe to invoke again
            return true;
        }
    };

    /**
     * Decisions that never abort a request: every token fetch status except
     * UNKNOWN_URL and UNPROTECTED_URL proceeds with the status header (and, on
     * SUCCESS, the token and trace headers), and a secure string is substituted
     * only on SUCCESS, the placeholder being left in place otherwise. Never signs.
     */
    public static final ApproovServiceMutator ALWAYS_PROCEED = new ApproovServiceMutator() {
        @Override
        public String toString() {
            return "ApproovServiceMutator.ALWAYS_PROCEED";
        }

        @Override
        public boolean handleInterceptorFetchTokenResult(Approov.TokenFetchResult approovResults, String url) {
            switch (approovResults.getStatus()) {
                case UNKNOWN_URL:
                case UNPROTECTED_URL:
                    // continue without any headers for unprotected URLs (anti-MitM)
                    return false;
                default:
                    // SUCCESS adds the token; any failure proceeds with no token
                    // header and the status reported on the status header
                    return true;
            }
        }

        @Override
        public boolean handleInterceptorHeaderSubstitutionResult(Approov.TokenFetchResult approovResults,
                String header) {
            return closeFailureSubstitution(approovResults, "Header substitution for " + header);
        }

        @Override
        public boolean handleInterceptorQueryParamSubstitutionResult(Approov.TokenFetchResult approovResults,
                String queryKey) {
            return closeFailureSubstitution(approovResults, "Query parameter substitution for " + queryKey);
        }

        @Override
        public boolean supportsProtectionRefresh() {
            // the default handleInterceptorProcessedRequest makes no changes so it
            // is trivially safe to invoke again
            return true;
        }
    };

    /**
     * The out-of-the-box decisions, in force until the app installs its own mutator
     * and reinstated by ApproovService.setServiceMutator(null). Declared as the
     * interface, it points to whatever the release considers default behaviour:
     * {@link #CLOSE_FAILURE} in 3.8.0; in 4.0.0 it is planned to become the
     * {@link #ALWAYS_PROCEED} behaviour. An app that wants a fixed behaviour names
     * CLOSE_FAILURE or ALWAYS_PROCEED explicitly.
     */
    public static final ApproovServiceMutator DEFAULT = CLOSE_FAILURE;

    /**
     * Indicates whether a token fetch status is a network failure, meaning that
     * the attestation could not be performed because of the network rather than
     * because of the app or device. Covers NO_NETWORK, POOR_NETWORK and the
     * UNTRUSTED_NETWORK status introduced by the 3.8.0 SDK (which replaces
     * MITM_DETECTED: the Approov channel no longer depends on pinning so an
     * intercepting proxy on the attestation path is no longer an outcome, and a
     * fundamental TLS trust failure is reported as UNTRUSTED_NETWORK instead).
     *
     * @param status the token fetch status to classify
     * @return true if the status is a network failure
     */
    static boolean isNetworkFailure(Approov.TokenFetchStatus status) {
        switch (status) {
            case NO_NETWORK:
            case POOR_NETWORK:
                return true;
            default:
                // TODO(3.8.0 SDK): replace with "case UNTRUSTED_NETWORK:" once the
                // approov-android-sdk 3.8.0 dependency is in place. Matching by name
                // keeps this layer compiling against both the 3.5.x and 3.8.x SDK
                // enums during the transition.
                return "UNTRUSTED_NETWORK".equals(status.name());
        }
    }

    /**
     * Decides how to handle the token fetch result from an
     * ApproovService.precheck() operation.
     *
     * @param approovResults the TokenFetchResult obtained by
     *                       ApproovService.precheck()
     * @throws ApproovException The implementation can either return, taking no
     *                          action or throw an ApproovException encoding
     *                          the cause of the failure.
     */
    @SuppressWarnings("deprecation")
    default void handlePrecheckResult(Approov.TokenFetchResult approovResults) throws ApproovException {
        Approov.TokenFetchStatus status = approovResults.getStatus();
        String arc = approovResults.getARC();
        String rejectionReasons = approovResults.getRejectionReasons();
        if (isNetworkFailure(status))
            throw new ApproovNetworkException(status, "precheck: " + status.toString());
        switch (status) {
            case REJECTED:
                throw new ApproovRejectionException(
                        "precheck: " + status.toString() + ": " + arc + " " + rejectionReasons, arc, rejectionReasons);
            case SUCCESS:
            case UNKNOWN_KEY:
                break;
            default:
                throw new ApproovFetchStatusException(status, "precheck: " + status.toString());
        }
    }

    /**
     * Decides how to handle the token fetch result from an
     * ApproovService.fetchToken() operation.
     *
     * @param approovResults the TokenFetchResult obtained by
     *                       ApproovService.fetchToken()
     * @throws ApproovException The implementation can either return, taking no
     *                          action or throw an ApproovException encoding
     *                          the cause of the failure.
     */
    @SuppressWarnings("deprecation")
    default void handleFetchTokenResult(Approov.TokenFetchResult approovResults) throws ApproovException {
        Approov.TokenFetchStatus status = approovResults.getStatus();
        if (isNetworkFailure(status))
            throw new ApproovNetworkException(status, "fetchToken: " + status.toString());
        switch (status) {
            case SUCCESS:
                break;
            default:
                throw new ApproovFetchStatusException(status, "fetchToken: " + status.toString());
        }
    }

    /**
     * Decides how to handle the token fetch result from an
     * ApproovService.fetchSecureString() operation.
     *
     * @param approovResults the TokenFetchResult obtained by
     *                       ApproovService.fetchSecureString()
     * @param operation      the operation type ("lookup" or "definition"); "lookup"
     *                       indicates that an existing value was requested, while
     *                       "definition" indicates that a new value was being added
     *                       or set
     * @param key            the secure string key
     * @throws ApproovException The implementation can either return, taking no
     *                          action or throw an ApproovException encoding
     *                          the cause of the failure
     */
    @SuppressWarnings("deprecation")
    default void handleFetchSecureStringResult(Approov.TokenFetchResult approovResults, String operation, String key)
            throws ApproovException {
        Approov.TokenFetchStatus status = approovResults.getStatus();
        String arc = approovResults.getARC();
        String rejectionReasons = approovResults.getRejectionReasons();
        if (isNetworkFailure(status))
            throw new ApproovNetworkException(status,
                    "fetchSecureString " + operation + " for " + key + ": " + status.toString());
        switch (status) {
            case REJECTED:
                throw new ApproovRejectionException("fetchSecureString " + operation + " for " + key + ": "
                        + status.toString() + ": " + arc + " " + rejectionReasons, arc, rejectionReasons);
            case SUCCESS:
            case UNKNOWN_KEY:
                break;
            default:
                throw new ApproovFetchStatusException(status,
                        "fetchSecureString " + operation + " for " + key + ": " + status.toString());
        }
    }

    /**
     * Decides how to handle the token fetch result from an
     * ApproovService.fetchCustomJWT() operation.
     *
     * @param approovResults the TokenFetchResult obtained by
     *                       ApproovService.fetchCustomJWT()
     * @throws ApproovException The implementation can either return, taking no
     *                          action or throw an ApproovException encoding
     *                          the cause of the failure
     */
    @SuppressWarnings("deprecation")
    default void handleFetchCustomJWTResult(Approov.TokenFetchResult approovResults) throws ApproovException {
        Approov.TokenFetchStatus status = approovResults.getStatus();
        String arc = approovResults.getARC();
        String rejectionReasons = approovResults.getRejectionReasons();
        if (isNetworkFailure(status))
            throw new ApproovNetworkException(status, "fetchCustomJWT: " + status.toString());
        switch (status) {
            case REJECTED:
                throw new ApproovRejectionException(
                        "fetchCustomJWT: " + status.toString() + ": " + arc + " " + rejectionReasons, arc,
                        rejectionReasons);
            case SUCCESS:
                break;
            default:
                throw new ApproovFetchStatusException(status, "fetchCustomJWT: " + status.toString());
        }
    }

    /**
     * Decides whether a request should be processed in the interceptor or not.
     * Called at the start of the ApproovService interceptor processing.
     *
     * @param request the request property extracted from the interceptor chain
     * @return true if the request should be processed by the Approov interceptor,
     *         false if it should be issued unchanged
     * @throws IOException an overriding implementation may throw a standard network
     *                     stack exception (never an Approov specific type) to abort
     *                     the request, which the default never does
     */
    default boolean handleInterceptorShouldProcessRequest(Request request) throws IOException {
        if (request == null)
            throw ApproovService.standardDecision(new ApproovException(
                    "handleInterceptorShouldProcessRequest method was passed a request that is null!"));

        // check if the URL matches one of the exclusion regexs and skip interceptor
        // processing in these cases
        String url = request.url().toString();
        for (Pattern pattern : ApproovService.getExclusionURLRegexs().values()) {
            Matcher matcher = pattern.matcher(url);
            if (matcher.find()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Decides how to handle the token fetch result from a call to
     * Approov.fetchApproovTokenAndWait() from within the interceptor.
     *
     * The default is the {@link #CLOSE_FAILURE} decision. SUCCESS adds the token
     * header and NO_APPROOV_SERVICE proceeds with no token header, with the status
     * reported on the status header in both cases. An UNKNOWN_URL or
     * UNPROTECTED_URL result sends the request with no Approov headers at all: an
     * UNKNOWN_URL host is not added to Approov, and an UNPROTECTED_URL host is a
     * secrets-only API that needs no token (it still gets its secure strings and
     * its pins, whatever this returns). NO_NETWORK, POOR_NETWORK and
     * UNTRUSTED_NETWORK throw ApproovNetworkException and every other status throws
     * ApproovFetchStatusException. {@link #ALWAYS_PROCEED} overrides this to never
     * throw.
     *
     * The answer governs the Approov headers and the signatures only, never the
     * secure strings, which follow the token fetch status (see the interface
     * description): true on SUCCESS adds the token, status and trace headers and
     * lets message signing sign the request; true on a failure status adds the
     * status header only; true on UNKNOWN_URL or UNPROTECTED_URL adds nothing. It
     * is also called for a failure status on a host that is not in the SDK pin set,
     * where the answer is ignored and the request goes out untouched: an exception
     * a standard decision raises is ignored too, and only the app's own exception
     * aborts it.
     * False sends the request with no Approov header (an UNPROTECTED_URL request,
     * and a SUCCESS one, still get their secure strings).
     *
     * @param approovResults the TokenFetchResult from Approov
     * @param url            the URL string for which the token was requested
     * @return true if the request should proceed with the Approov headers its
     *         status allows, false if it should proceed without any Approov
     *         headers
     * @throws IOException to abort the request: the default throws an
     *                     ApproovException subclass as described above, and an
     *                     overriding implementation opting in to an abort of its
     *                     own throws a standard network stack exception (never an
     *                     Approov specific type)
     */
    @SuppressWarnings("deprecation")
    default boolean handleInterceptorFetchTokenResult(Approov.TokenFetchResult approovResults, String url)
            throws IOException {
        Approov.TokenFetchStatus status = approovResults.getStatus();
        switch (status) {
            case SUCCESS:
            case NO_APPROOV_SERVICE:
                // NO_APPROOV_SERVICE proceeds with the status header, no token header,
                // as evidence that Approov processing occurred
                return true;
            case UNKNOWN_URL:
            case UNPROTECTED_URL:
                // no Approov headers: an UNKNOWN_URL host is not added to Approov, an
                // UNPROTECTED_URL host is a secrets-only API that needs no token
                return false;
            default:
                if (isNetworkFailure(status))
                    throw ApproovService.standardDecision(new ApproovNetworkException(status,
                            "Approov token fetch for " + url + ": " + status.toString()));
                throw ApproovService.standardDecision(new ApproovFetchStatusException(status,
                        "Approov token fetch for " + url + ": " + status.toString()));
        }
    }

    /**
     * Decides how to handle the token fetch result while substituting headers from
     * within the interceptor. The passed fetch result to process is associated with
     * a preceding call to Approov.fetchSecureStringAndWait which passed in the
     * current header value (minus a prefix) as the key. This method is called once
     * per header being processed for substitution.
     *
     * Consulted only for a request on a protected channel, a token fetch of
     * SUCCESS or UNPROTECTED_URL, over https. The default, the decision of both
     * {@link #CLOSE_FAILURE} and {@link #ALWAYS_PROCEED}, substitutes the header on
     * SUCCESS and leaves it unchanged, with the request proceeding, on every other
     * status (UNKNOWN_KEY, the value not being a secure string key, REJECTED,
     * NO_APPROOV_SERVICE, the network statuses and the rest): a standard mutator
     * never aborts a request because of a secure string. An overriding
     * implementation may still throw a standard network stack exception to abort.
     *
     * @param approovResults the TokenFetchResult from Approov
     * @param header         the header being substituted
     * @return true if substitution should proceed, false if it should be skipped
     * @throws IOException an overriding implementation opting in to an abort of
     *                     its own throws a standard network stack exception (never
     *                     an Approov specific type), which the default never does
     */
    default boolean handleInterceptorHeaderSubstitutionResult(Approov.TokenFetchResult approovResults, String header)
            throws IOException {
        return closeFailureSubstitution(approovResults, "Header substitution for " + header);
    }

    /**
     * Decides how to handle the token fetch result while substituting query params
     * from within the interceptor. The passed fetch result to process is associated
     * with a preceding call to Approov.fetchSecureStringAndWait which passed in the
     * query value of a matching query key. This method is called once for each
     * matched query parameter being processed for substitution.
     *
     * Consulted only for a request on a protected channel, as for
     * {@link #handleInterceptorHeaderSubstitutionResult}, with the same default:
     * substitute on SUCCESS and leave the placeholder, with the request proceeding,
     * on every other status.
     *
     * @param approovResults the TokenFetchResult from Approov
     * @param queryKey       the query parameter key being substituted
     * @return true if substitution should proceed, false if it should be skipped
     * @throws IOException an overriding implementation opting in to an abort of
     *                     its own throws a standard network stack exception (never
     *                     an Approov specific type), which the default never does
     */
    default boolean handleInterceptorQueryParamSubstitutionResult(Approov.TokenFetchResult approovResults,
            String queryKey) throws IOException {
        return closeFailureSubstitution(approovResults, "Query parameter substitution for " + queryKey);
    }

    /**
     * The substitution decision of both standard mutators, {@link #CLOSE_FAILURE}
     * and {@link #ALWAYS_PROCEED} (decided 2026-10-07): substitute on SUCCESS and
     * leave the placeholder for every other secure string status, UNKNOWN_KEY,
     * REJECTED, NO_APPROOV_SERVICE and the network statuses included. It never
     * throws: a standard mutator never aborts a request because of a secure
     * string, and the placeholder reaching the backend, which is the enforcement
     * point, is the evidence.
     *
     * @param approovResults the secure string fetch result
     * @param what           the substitution being decided; the decision does not
     *                       depend on it (the layer logs a placeholder it leaves)
     * @return true to substitute, false to leave the placeholder
     */
    static boolean closeFailureSubstitution(Approov.TokenFetchResult approovResults, String what) {
        return approovResults.getStatus() == Approov.TokenFetchStatus.SUCCESS;
    }

    /**
     * Called after Approov has processed a network request, allowing further
     * modifications.
     *
     * @param request the processed request
     * @param changes the mutations applied to the request by Approov
     * @return the final request to use to complete the Approov interceptor step.
     * @throws IOException an overriding implementation may throw a standard network
     *                     stack exception to abort the request
     */
    default Request handleInterceptorProcessedRequest(Request request, ApproovRequestMutations changes)
            throws IOException {
        // No further changes to the request are required
        return request;
    }

    /**
     * Indicates whether this mutator supports the stale protection refresh
     * performed at the network layer for requests that were held between having
     * their Approov protection applied and being actually transmitted (see
     * ApproovService.setStaleProtectionRefreshPeriod). A refresh removes the
     * headers previously added by handleInterceptorProcessedRequest, updates
     * the Approov token header and then invokes
     * handleInterceptorProcessedRequest again on the same request, so it must
     * only be enabled for implementations whose callback is safe to invoke
     * more than once per request (note that changes the callback makes other
     * than adding headers, such as modifying existing header values in place,
     * are not undone before the reinvocation). This returns false by default
     * so that custom implementations are never reinvoked unless they opt in
     * by overriding this method.
     *
     * @return true if the stale protection refresh may reinvoke
     *         handleInterceptorProcessedRequest, false to prevent any refresh
     */
    default boolean supportsProtectionRefresh() {
        return false;
    }

    /**
     * Decides whether certificate pinning should be applied to a request or not.
     * Called at the start of the ApproovService pinning processing.
     *
     * @param request the request being processed
     * @return true if pinning should be applied, false to skip it
     * @throws IOException an overriding implementation may throw a standard network
     *                     stack exception to abort the request, which the default
     *                     never does
     */
    default boolean handlePinningShouldProcessRequest(Request request) throws IOException {
        // By default do not skip pinning for any requests
        return true;
    }
}
