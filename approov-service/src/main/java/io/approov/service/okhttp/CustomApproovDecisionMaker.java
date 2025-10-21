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

import android.util.Log;
import com.criticalblue.approovsdk.Approov;
import okhttp3.Request;

/**
 * Example custom decision maker that demonstrates how to override default TokenFetchStatus handling behavior.
 * This example shows more lenient handling of network issues in certain scenarios.
 */
public class CustomApproovDecisionMaker implements ApproovInterceptorExtensions {
    private static final String TAG = "CustomApproovDecisionMaker";

    @Override
    public Request processedRequest(Request request, ApproovRequestMutations changes) throws ApproovException {
        return request;
    }

    @Override
    public void handlePrecheckResult(Approov.TokenFetchResult approovResults) throws ApproovException {
        Approov.TokenFetchStatus status = approovResults.getStatus();
        String arc = approovResults.getARC();
        String rejectionReasons = approovResults.getRejectionReasons();
        switch (status) {
            case REJECTED:
                throw new ApproovRejectionException("precheck: " + status.toString() + ": " + arc + " " + rejectionReasons, arc, rejectionReasons);
            case NO_NETWORK:
            case POOR_NETWORK:
                // Custom behavior: log warning but don't throw exception for network issues in precheck
                Log.w(TAG, "Network issue during precheck: " + status.toString());
                break;
            case MITM_DETECTED:
                throw new ApproovNetworkException(status, "precheck: " + status.toString());
            case SUCCESS:
            case UNKNOWN_KEY:
                break;
            default:
                throw new ApproovException(status, "precheck:" + status.toString());
        }
    }

    @Override
    public boolean handleInterceptorFetchTokenResult(Approov.TokenFetchResult approovResults, String url) throws ApproovException {
        Approov.TokenFetchStatus status = approovResults.getStatus();
        switch (status) {
            case SUCCESS:
                return true;
            case NO_NETWORK:
            case POOR_NETWORK:
                // Custom behavior: always proceed on network failures for this example
                Log.w(TAG, "Network issue during token fetch for " + url + ", proceeding without token");
                return false;
            case MITM_DETECTED:
                if (!ApproovService.getProceedOnNetworkFail())
                    throw new ApproovNetworkException(status, "Approov token fetch for " + url + ": " + status.toString());
                return false;
            case NO_APPROOV_SERVICE:
            case UNKNOWN_URL:
            case UNPROTECTED_URL:
                return false;
            default:
                throw new ApproovException(status, "Approov token fetch for " + url + ": " + status.toString());
        }
    }
}