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
 * ApproovInterceptorExtensions provides an interface for handling callbacks during
 * the processing of network requests by Approov. It allows further modifications
 * to requests after Approov has applied its changes.
 */
public interface ApproovInterceptorExtensions {

    /**
     * Called after Approov has processed a network request, allowing further modifications.
     *
     * @param request the processed request
     * @param changes the mutations applied to the request by Approov
     * @return the modified request
     * @throws ApproovException if there is an error during processing
     */
    Request processedRequest(Request request, ApproovRequestMutations changes) throws ApproovException;

    /**
     * Decides how to handle token fetch status in precheck operations.
     *
     * @param status the TokenFetchStatus from Approov
     * @param arc the ARC value if available
     * @param rejectionReasons the rejection reasons if available
     * @throws ApproovException if the status should result in an exception
     */
    default void handlePrecheckStatus(Approov.TokenFetchStatus status, String arc, String rejectionReasons) throws ApproovException {
        switch (status) {
            case REJECTED:
                throw new ApproovRejectionException("precheck: " + status.toString() + ": " + arc + " " + rejectionReasons, arc, rejectionReasons);
            case NO_NETWORK:
            case POOR_NETWORK:
            case MITM_DETECTED:
                throw new ApproovNetworkException("precheck: " + status.toString());
            case SUCCESS:
            case UNKNOWN_KEY:
                break;
            default:
                throw new ApproovException("precheck:" + status.toString());
        }
    }

    /**
     * Decides how to handle token fetch status in fetchToken operations.
     *
     * @param status the TokenFetchStatus from Approov
     * @throws ApproovException if the status should result in an exception
     */
    default void handleFetchTokenStatus(Approov.TokenFetchStatus status) throws ApproovException {
        switch (status) {
            case NO_NETWORK:
            case POOR_NETWORK:
            case MITM_DETECTED:
                throw new ApproovNetworkException("fetchToken: " + status.toString());
            case SUCCESS:
                break;
            default:
                throw new ApproovException("fetchToken: " + status.toString());
        }
    }

    /**
     * Decides how to handle token fetch status in fetchSecureString operations.
     *
     * @param status the TokenFetchStatus from Approov
     * @param operation the operation type ("lookup" or "definition")
     * @param key the secure string key
     * @param arc the ARC value if available
     * @param rejectionReasons the rejection reasons if available
     * @throws ApproovException if the status should result in an exception
     */
    default void handleSecureStringStatus(Approov.TokenFetchStatus status, String operation, String key, String arc, String rejectionReasons) throws ApproovException {
        switch (status) {
            case REJECTED:
                throw new ApproovRejectionException("fetchSecureString " + operation + " for " + key + ": " + status.toString() + ": " + arc + " " + rejectionReasons, arc, rejectionReasons);
            case NO_NETWORK:
            case POOR_NETWORK:
            case MITM_DETECTED:
                throw new ApproovNetworkException("fetchSecureString " + operation + " for " + key + ":" + status.toString());
            case SUCCESS:
            case UNKNOWN_KEY:
                break;
            default:
                throw new ApproovException("fetchSecureString " + operation + " for " + key + ":" + status.toString());
        }
    }

    /**
     * Decides how to handle token fetch status in fetchCustomJWT operations.
     *
     * @param approovResults the TokenFetchStatus from Approov
     * @return the fetched JWT token
     * @throws ApproovException if the status should result in an exception
     */
    default String handleCustomJWTStatus(Approov.TokenFetchResult approovResults) throws ApproovException {
        Approov.TokenFetchStatus status = approovResults.getStatus();
        String arc = approovResults.getArc();
        String rejectionReasons = approovResults.getRejectionReasons();
        switch (status) {
            case REJECTED:
                throw new ApproovRejectionException("fetchCustomJWT: " + status.toString() + ": " + arc + " " + rejectionReasons, arc, rejectionReasons);
            case NO_NETWORK:
            case POOR_NETWORK:
            case MITM_DETECTED:
                throw new ApproovNetworkException("fetchCustomJWT: " + status.toString());
            case SUCCESS:
                return approovResults.getToken();
            default:
                throw new ApproovException("fetchCustomJWT: " + status.toString());
        }
    }

    /**
     * Decides how to handle token fetch status in interceptor token operations.
     *
     * @param status the TokenFetchStatus from Approov
     * @param host the host for which the token was requested
     * @return true if processing should continue, false if request should proceed without token
     * @throws ApproovException if the status should result in an exception
     */
    default boolean handleInterceptorTokenStatus(Approov.TokenFetchStatus status, String host) throws ApproovException {
        switch (status) {
            case SUCCESS:
                return true;
            case NO_NETWORK:
            case POOR_NETWORK:
            case MITM_DETECTED:
                if (!ApproovService.getProceedOnNetworkFail())
                    throw new ApproovNetworkException("Approov token fetch for " + host + ": " + status.toString());
                return false;
            case NO_APPROOV_SERVICE:
            case UNKNOWN_URL:
            case UNPROTECTED_URL: // Continue without token for unprotected URLs
                return false;
            default:
                throw new ApproovException("Approov token fetch for " + host + ": " + status.toString());
        }
    }

    /**
     * Decides how to handle token fetch status in interceptor header substitution operations.
     *
     * @param status the TokenFetchStatus from Approov
     * @param header the header being substituted
     * @param arc the ARC value if available
     * @param rejectionReasons the rejection reasons if available
     * @return true if substitution should proceed, false if it should be skipped
     * @throws ApproovException if the status should result in an exception
     */
    default boolean handleInterceptorHeaderSubstitutionStatus(Approov.TokenFetchStatus status, String header, String arc, String rejectionReasons) throws ApproovException {
        switch (status) {
            case SUCCESS:
                return true;
            case REJECTED:
                throw new ApproovRejectionException("Header substitution for " + header + ": " + status.toString() + ": " + arc + " " + rejectionReasons, arc, rejectionReasons);
            case NO_NETWORK:
            case POOR_NETWORK:
            case MITM_DETECTED:
                if (!ApproovService.getProceedOnNetworkFail())
                    throw new ApproovNetworkException("Header substitution for " + header + ": " + status.toString());
                return false;
            case UNKNOWN_KEY:
                return false;
            default:
                throw new ApproovException("Header substitution for " + header + ": " + status.toString());
        }
    }

    /**
     * Decides how to handle token fetch status in interceptor query parameter substitution operations.
     *
     * @param status the TokenFetchStatus from Approov
     * @param queryKey the query parameter key being substituted
     * @param arc the ARC value if available
     * @param rejectionReasons the rejection reasons if available
     * @return true if substitution should proceed, false if it should be skipped
     * @throws ApproovException if the status should result in an exception
     */
    default boolean handleInterceptorQueryParamSubstitutionStatus(Approov.TokenFetchStatus status, String queryKey, String arc, String rejectionReasons) throws ApproovException {
        switch (status) {
            case SUCCESS:
                return true;
            case REJECTED:
                throw new ApproovRejectionException("Query parameter substitution for " + queryKey + ": " + status.toString() + ": " + arc + " " + rejectionReasons, arc, rejectionReasons);
            case NO_NETWORK:
            case POOR_NETWORK:
            case MITM_DETECTED:
                if (!ApproovService.getProceedOnNetworkFail())
                    throw new ApproovNetworkException("Query parameter substitution for " + queryKey + ": " + status.toString());
                return false;
            case UNKNOWN_KEY:
                return false;
            default:
                throw new ApproovException("Query parameter substitution for " + queryKey + ": " + status.toString());
        }
    }
    /**
     * Decides how to handle whether to process the request in the interceptor.
     *
     * @param request the original request
     * @return true if the request should be processed by Approov based on request, false if it should proceed unchanged
     * @throws ApproovException if there is an error during processing
     */
    default boolean handleInterceptorActionBasedOnRequest(Request request) throws ApproovException {
        String url = request.url().toString();

            for (Pattern pattern: ApproovService.getExclusionURLRegexs().values()) {
            Matcher matcher = pattern.matcher(url);
            if (matcher.find()) {
                return true;
            }
        }
        return false;
    }
}
