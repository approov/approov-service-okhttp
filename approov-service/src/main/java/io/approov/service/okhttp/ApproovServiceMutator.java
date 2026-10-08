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
 * ApproovServiceMutator provides an interface for modifying the behavior of the ApproovService class by
 * overriding the default implementations of the defined callbacks. Opportunities to modify behavior are
 * offered at key points in the service and attestation flows. All the methods have default
 * implementations, so an implementing class need only override the methods it is interested in.
 * <p>
 * Three standard mutators are provided. CLOSE_FAILURE, whose decisions are also the interface defaults,
 * aborts a request on a token fetch failure other than NO_APPROOV_SERVICE. ALWAYS_PROCEED never aborts a
 * request and reports the token fetch status on the status header instead, leaving the backend to decide.
 * DEFAULT is the mutator in force until the app installs its own, which is CLOSE_FAILURE. Under both
 * standard mutators a secure string that cannot be obtained never aborts a request, and the placeholder is
 * replaced by the fetch status. Note that a mutator never enables or disables message signing.
 * <p>
 * An app that wants a request aborted for some further outcome overrides the relevant interceptor hook
 * and throws a standard network stack exception (an IOException such as java.net.ConnectException), not
 * an Approov specific type, so that the abort reaches the app as an ordinary network failure. Anything
 * else a hook throws is converted to an ApproovException naming the hook, with the original as its cause.
 * The direct methods (fetchToken, fetchSecureString, fetchCustomJWT and precheck) report their failures
 * with an ApproovException subclass.
 */
public interface ApproovServiceMutator {
    /**
     * Decisions that abort a request on a token fetch failure, which are also the interface defaults. A
     * request proceeds on SUCCESS and NO_APPROOV_SERVICE, UNKNOWN_URL and UNPROTECTED_URL get no Approov
     * header, and every other token fetch status throws an ApproovException subclass. A secure string is
     * substituted on SUCCESS, while any other secure string status is written in place of the placeholder
     * and the request proceeds.
     */
    public static final ApproovServiceMutator CLOSE_FAILURE = new ApproovServiceMutator() {
        @Override
        public String toString() {
            return "ApproovServiceMutator.CLOSE_FAILURE";
        }

        @Override
        public boolean supportsProtectionRefresh() {
            // the default handleInterceptorProcessedRequest makes no changes so it is trivially safe to
            // invoke again
            return true;
        }
    };

    /**
     * Decisions that never abort a request. Every token fetch status except UNKNOWN_URL and UNPROTECTED_URL
     * proceeds with the status header (and, on SUCCESS, the token and trace headers), and a secure string
     * is only substituted on SUCCESS, any other status being written in place of the placeholder.
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
                    // SUCCESS adds the token, while any failure proceeds with no token header and the status
                    // reported on the status header
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
            // the default handleInterceptorProcessedRequest makes no changes so it is trivially safe to
            // invoke again
            return true;
        }
    };

    /**
     * The decisions in force until the app installs its own mutator, which are reinstated by
     * ApproovService.setServiceMutator(null). This is CLOSE_FAILURE, and an app that wants a fixed
     * behaviour should name CLOSE_FAILURE or ALWAYS_PROCEED explicitly.
     */
    public static final ApproovServiceMutator DEFAULT = CLOSE_FAILURE;

    /**
     * Determines if a token fetch status is a network failure, meaning that the attestation could not be
     * performed because of the network rather than because of the app or device. These are NO_NETWORK,
     * POOR_NETWORK and UNTRUSTED_NETWORK, which reports a fundamental TLS trust failure.
     *
     * @param status is the token fetch status to classify
     * @return true if the status is a network failure, false otherwise
     */
    static boolean isNetworkFailure(Approov.TokenFetchStatus status) {
        switch (status) {
            case NO_NETWORK:
            case POOR_NETWORK:
                return true;
            default:
                // TODO replace with "case UNTRUSTED_NETWORK:" once the layer depends on the 3.8.0 SDK, as matching
                // by name compiles against the SDK enums both with and without it
                return "UNTRUSTED_NETWORK".equals(status.name());
        }
    }

    /**
     * Decides how to handle the token fetch result from an ApproovService.precheck() operation.
     *
     * @param approovResults is the TokenFetchResult obtained by ApproovService.precheck()
     * @throws ApproovException if the result is a failure, as the implementation may either return taking
     * no action or throw an ApproovException encoding the cause of the failure
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
     * Decides how to handle the token fetch result from an ApproovService.fetchToken() operation.
     *
     * @param approovResults is the TokenFetchResult obtained by ApproovService.fetchToken()
     * @throws ApproovException if the result is a failure, as the implementation may either return taking
     * no action or throw an ApproovException encoding the cause of the failure
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
     * Decides how to handle the token fetch result from an ApproovService.fetchSecureString() operation.
     *
     * @param approovResults is the TokenFetchResult obtained by ApproovService.fetchSecureString()
     * @param operation is the operation type, "lookup" if an existing value was requested or "definition"
     * if a new value was being set
     * @param key is the secure string key
     * @throws ApproovException if the result is a failure, as the implementation may either return taking
     * no action or throw an ApproovException encoding the cause of the failure
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
     * Decides how to handle the token fetch result from an ApproovService.fetchCustomJWT() operation.
     *
     * @param approovResults is the TokenFetchResult obtained by ApproovService.fetchCustomJWT()
     * @throws ApproovException if the result is a failure, as the implementation may either return taking
     * no action or throw an ApproovException encoding the cause of the failure
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
     * Decides whether a request should be processed in the interceptor or not. This is called at the start
     * of the ApproovService interceptor processing.
     *
     * @param request is the request extracted from the interceptor chain
     * @return true if the request should be processed by the Approov interceptor, false if it should be
     * issued unchanged
     * @throws IOException if an overriding implementation aborts the request with a standard network stack
     * exception, which the default never does
     */
    default boolean handleInterceptorShouldProcessRequest(Request request) throws IOException {
        if (request == null)
            throw ApproovService.standardDecision(new ApproovException(
                    "handleInterceptorShouldProcessRequest: null request"));

        // check if the URL matches one of the exclusion regexs and skip interceptor processing in these cases
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
     * Decides how to handle the token fetch result from a call to Approov.fetchApproovTokenAndWait() from
     * within the interceptor. The default proceeds on SUCCESS, adding the token header, and on
     * NO_APPROOV_SERVICE, with the status reported on the status header in both cases. UNKNOWN_URL and
     * UNPROTECTED_URL proceed with no Approov headers, NO_NETWORK, POOR_NETWORK and UNTRUSTED_NETWORK throw
     * ApproovNetworkException and every other status throws ApproovFetchStatusException.
     * <p>
     * The result decides the Approov headers and the message signing, never the secure strings, which are
     * only substituted for SUCCESS and UNPROTECTED_URL. True on SUCCESS adds the token, status and trace
     * headers, true on a failure status adds the status header only, and true on UNKNOWN_URL or
     * UNPROTECTED_URL adds nothing. Note that for a failure on a host that is not in the SDK pin set the
     * request is sent untouched whatever this returns, unless the implementation throws its own exception.
     *
     * @param approovResults is the TokenFetchResult from Approov
     * @param url is the URL string for which the token was requested
     * @return true if the request should proceed with the Approov headers its status allows, false if it
     * should proceed without any Approov headers
     * @throws IOException if the request is to be aborted, which the default does with an ApproovException
     * subclass and an overriding implementation with a standard network stack exception
     */
    @SuppressWarnings("deprecation")
    default boolean handleInterceptorFetchTokenResult(Approov.TokenFetchResult approovResults, String url)
            throws IOException {
        Approov.TokenFetchStatus status = approovResults.getStatus();
        switch (status) {
            case SUCCESS:
            case NO_APPROOV_SERVICE:
                // NO_APPROOV_SERVICE proceeds with the status header but no token header, as evidence that
                // Approov processing occurred
                return true;
            case UNKNOWN_URL:
            case UNPROTECTED_URL:
                // no Approov headers, as an UNKNOWN_URL host is not added to Approov and an UNPROTECTED_URL
                // host is a secrets-only API that needs no token
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
     * Decides how to handle the token fetch result while substituting headers from within the interceptor.
     * The passed fetch result to process is associated with a preceding call to
     * Approov.fetchSecureStringAndWait which passed in the current header value (minus a prefix) as the
     * key. This method is called once per header being processed for substitution, and only for a request
     * to a token-protected or secrets-only API over https. The default substitutes the header on SUCCESS
     * and skips it on any other status, with the request proceeding. When the request proceeds without a
     * secure string, the fetch status in lowercase (as on the status header) replaces the placeholder after
     * any required prefix, such as "Bearer rejected" or "Bearer unknown_key".
     *
     * @param approovResults is the TokenFetchResult from Approov
     * @param header is the header being substituted
     * @return true if substitution should proceed, false if it should be skipped, which is the same as true
     * when there is no secure string
     * @throws IOException if an overriding implementation aborts the request with a standard network stack
     * exception, which the default never does
     */
    default boolean handleInterceptorHeaderSubstitutionResult(Approov.TokenFetchResult approovResults, String header)
            throws IOException {
        return closeFailureSubstitution(approovResults, "Header substitution for " + header);
    }

    /**
     * Decides how to handle the token fetch result while substituting query params from within the
     * interceptor. The passed fetch result to process is associated with a preceding call to
     * Approov.fetchSecureStringAndWait which passed in the query value of a matching query key. This method
     * is called once for each matched query parameter being processed for substitution, and only for a
     * request to a token-protected or secrets-only API over https. The default substitutes the query
     * parameter on SUCCESS and skips it on any other status, with the request proceeding. When the request
     * proceeds without a secure string, the fetch status in lowercase (as on the status header) replaces the
     * placeholder, such as "rejected" or "unknown_key".
     *
     * @param approovResults is the TokenFetchResult from Approov
     * @param queryKey is the query parameter key being substituted
     * @return true if substitution should proceed, false if it should be skipped, which is the same as true
     * when there is no secure string
     * @throws IOException if an overriding implementation aborts the request with a standard network stack
     * exception, which the default never does
     */
    default boolean handleInterceptorQueryParamSubstitutionResult(Approov.TokenFetchResult approovResults,
            String queryKey) throws IOException {
        return closeFailureSubstitution(approovResults, "Query parameter substitution for " + queryKey);
    }

    /**
     * Decides a secure string substitution for both standard mutators, substituting on SUCCESS and skipping
     * it for any other status. It never throws, as a standard mutator never aborts a request because of a
     * secure string, and the status written in place of the placeholder is the evidence for the backend.
     *
     * @param approovResults is the secure string fetch result
     * @param what is the substitution being decided, which does not affect the decision
     * @return true to substitute, false to skip the substitution
     */
    static boolean closeFailureSubstitution(Approov.TokenFetchResult approovResults, String what) {
        return approovResults.getStatus() == Approov.TokenFetchStatus.SUCCESS;
    }

    /**
     * Called after Approov has processed a network request, allowing further modifications.
     *
     * @param request is the processed request
     * @param changes is the mutations applied to the request by Approov
     * @return the final request to use to complete the Approov interceptor step
     * @throws IOException if an overriding implementation aborts the request with a standard network stack
     * exception
     */
    default Request handleInterceptorProcessedRequest(Request request, ApproovRequestMutations changes)
            throws IOException {
        // no further changes to the request are required
        return request;
    }

    /**
     * Determines if this mutator supports the refresh of the protection of a request that was held between
     * having its Approov protection applied and being transmitted (see
     * ApproovService.setStaleProtectionRefreshPeriod). A refresh removes the headers previously added by
     * handleInterceptorProcessedRequest, updates the Approov headers and then invokes
     * handleInterceptorProcessedRequest again, so it must only be enabled if the callback is safe to invoke
     * more than once per request. Note that any other changes the callback makes, such as modifying
     * existing header values in place, are not undone. This returns false by default so that custom
     * implementations are never reinvoked unless they opt in.
     *
     * @return true if the refresh may reinvoke handleInterceptorProcessedRequest, false to prevent any
     * refresh
     */
    default boolean supportsProtectionRefresh() {
        return false;
    }

    /**
     * Decides whether certificate pinning should be applied to a request or not. This is called at the
     * start of the ApproovService pinning processing.
     *
     * @param request is the request being processed
     * @return true if pinning should be applied, false to skip it
     * @throws IOException if an overriding implementation aborts the request with a standard network stack
     * exception, which the default never does
     */
    default boolean handlePinningShouldProcessRequest(Request request) throws IOException {
        // by default do not skip pinning for any requests
        return true;
    }
}
