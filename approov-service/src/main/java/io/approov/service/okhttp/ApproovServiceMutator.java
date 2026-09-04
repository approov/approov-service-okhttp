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
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * ApproovServiceMutator provides an interface for modifying the behavior of
 * the ApproovService class by overriding the default implementations of the
 * defined callbacks. Opportunities to modify behavior are offered at key
 * points in the service and attestation flows.
 *
 * The interface provides default implementations for all methods, so
 * implementing classes can choose to override only the methods they are
 * interested in.
 *
 * From 3.7.0 the default interceptor decisions always proceed with the request:
 * no runtime condition (no network, no Approov service, rejection, failed
 * secure string substitution) aborts a request on the service layer's behalf.
 * Instead the request is sent with an empty Approov token header, a failed
 * secure string substitution leaves its placeholder in place, and the Approov
 * fetch status is reported on the status header (see
 * ApproovService.setStatusHeader) so that the backend, which is the enforcement
 * point, can decide. The direct fetch APIs (fetchToken, fetchSecureString,
 * fetchCustomJWT, precheck) return a value to the caller and therefore still
 * report failures by throwing an ApproovException subclass.
 */
public interface ApproovServiceMutator {
    /**
     * Mutator that provides the standard decisions with no message signing.
     * Note that this is not the mutator installed by ApproovService.initialize():
     * from 3.7.0 the out-of-the-box mutator is an ApproovDefaultMessageSigning
     * instance producing both install and account signatures (see
     * ApproovService.createDefaultServiceMutator). Install this instance with
     * ApproovService.setServiceMutator(ApproovServiceMutator.DEFAULT) to switch
     * message signing off while retaining every other default decision.
     */
    public static final ApproovServiceMutator DEFAULT = new ApproovServiceMutator() {
        @Override
        public String toString() {
            return "ApproovServiceMutator.DEFAULT";
        }

        @Override
        public boolean supportsProtectionRefresh() {
            // the default handleInterceptorProcessedRequest makes no changes so it
            // is trivially safe to invoke again
            return true;
        }
    };

    /**
     * Indicates whether a token fetch status is a network failure, meaning that
     * the attestation could not be performed because of the network rather than
     * because of the app or device. Covers NO_NETWORK, POOR_NETWORK and the
     * UNTRUSTED_NETWORK status introduced by the 3.7.0 SDK (which replaces
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
                // TODO(3.7.0 SDK): replace with "case UNTRUSTED_NETWORK:" once the
                // approov-android-sdk 3.7.0 dependency is in place. Matching by name
                // keeps this layer compiling against both the 3.5.x and 3.7.x SDK
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
     * @throws ApproovException The implementation can either return to indicate the
     *                          action described above or throw an ApproovException
     *                          encoding the cause of the failure
     */
    default boolean handleInterceptorShouldProcessRequest(Request request) throws ApproovException {
        if (request == null)
            throw new ApproovException(
                    "handleInterceptorShouldProcessRequest method was passed a request that is null!");

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
     * The default never throws. A SUCCESS result adds the token header. An
     * UNKNOWN_URL or UNPROTECTED_URL result sends the request with no Approov
     * headers at all, since the domain is not protected by Approov and headers
     * must not be leaked to it. Every other status proceeds with an empty token
     * header (evidence that Approov processing occurred); the interceptor reports
     * the status on the Approov status header in every case, SUCCESS included.
     *
     * @param approovResults the TokenFetchResult from Approov
     * @param url            the URL string for which the token was requested
     * @return true if the token header should be added (its value is empty if no
     *         token was obtained), false if the request should proceed without
     *         any Approov headers
     * @throws ApproovException The implementation can either return to indicate the
     *                          action described above or throw an ApproovException
     *                          to abort the request, which the default never does
     */
    default boolean handleInterceptorFetchTokenResult(Approov.TokenFetchResult approovResults, String url)
            throws ApproovException {
        switch (approovResults.getStatus()) {
            case UNKNOWN_URL:
            case UNPROTECTED_URL:
                // continue without any headers for unprotected URLs (anti-MitM)
                return false;
            default:
                // SUCCESS adds the token; any failure proceeds with an empty token header,
                // and the status is reported on the status header by the interceptor
                return true;
        }
    }

    /**
     * Decides how to handle the token fetch result while substituting headers from
     * within the interceptor. The passed fetch result to process is associated with
     * a preceding call to Approov.fetchSecureStringAndWait which passed in the
     * current header value (minus a prefix) as the key. This method is called once
     * per header being processed for substitution.
     *
     * The default never throws: the header is substituted on SUCCESS and left
     * unchanged (the placeholder value is sent) otherwise, for the backend to
     * decide.
     *
     * @param approovResults the TokenFetchResult from Approov
     * @param header         the header being substituted
     * @return true if substitution should proceed, false if it should be skipped
     * @throws ApproovException The implementation can either return to indicate the
     *                          action described above or throw an ApproovException
     *                          to abort the request, which the default never does
     */
    default boolean handleInterceptorHeaderSubstitutionResult(Approov.TokenFetchResult approovResults, String header)
            throws ApproovException {
        return approovResults.getStatus() == Approov.TokenFetchStatus.SUCCESS;
    }

    /**
     * Decides how to handle the token fetch result while substituting query params
     * from within the interceptor. The passed fetch result to process is associated
     * with a preceding call to Approov.fetchSecureStringAndWait which passed in the
     * query value of a matching query key. This method is called once for each
     * matched query parameter being processed for substitution.
     *
     * The default never throws: the parameter is substituted on SUCCESS and left
     * unchanged (the placeholder value is sent) otherwise, for the backend to
     * decide.
     *
     * @param approovResults the TokenFetchResult from Approov
     * @param queryKey       the query parameter key being substituted
     * @return true if substitution should proceed, false if it should be skipped
     * @throws ApproovException The implementation can either return to indicate the
     *                          action described above or throw an ApproovException
     *                          to abort the request, which the default never does
     */
    default boolean handleInterceptorQueryParamSubstitutionResult(Approov.TokenFetchResult approovResults,
            String queryKey) throws ApproovException {
        return approovResults.getStatus() == Approov.TokenFetchStatus.SUCCESS;
    }

    /**
     * Called after Approov has processed a network request, allowing further
     * modifications.
     *
     * @param request the processed request
     * @param changes the mutations applied to the request by Approov
     * @return the final request to use to complete the Approov interceptor step.
     * @throws ApproovException The implementation can either return as described
     *                          above or throw an ApproovException encoding the
     *                          cause of the failure
     */
    default Request handleInterceptorProcessedRequest(Request request, ApproovRequestMutations changes)
            throws ApproovException {
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
     */
    default boolean handlePinningShouldProcessRequest(Request request) {
        // By default do not skip pinning for any requests
        return true;
    }
}
