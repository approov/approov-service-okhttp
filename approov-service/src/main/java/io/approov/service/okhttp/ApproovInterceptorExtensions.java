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
 *
 * @deprecated Replace implementations of this interface with ApproovServiceMutator
 * while changing the name of the ApproovInterceptorExtensions.processedRequest
 * method to ApproovServiceMutator.handleInterceptorProcessedRequest.
 */
@Deprecated
public interface ApproovInterceptorExtensions extends ApproovServiceMutator{

    /**
     * Replace the default implementation of ApproovServiceMutator.handleInterceptorProcessedRequest
     * to call the now deprecated ApproovInterceptorExtensions.processedRequest method. 
     *
     * @param request the processed request
     * @param changes the mutations applied to the request by Approov
     * @return the final request to use to complete the Approov interceptor step.
     * @throws ApproovException if there is an error during processing
     */
    default Request handleInterceptorProcessedRequest(Request request, ApproovRequestMutations changes) throws ApproovException {
        // call the deprecated method to maintain backwards compatibility
        return processedRequest(request, changes);
    }

    /**
     * @deprecated Use ApproovServiceMutator.handleInterceptorProcessedRequest instead.
     */
    @Deprecated
    default Request processedRequest(Request request, ApproovRequestMutations changes) throws ApproovException {
        // No further changes to the request are required
        return request;
    }
}
