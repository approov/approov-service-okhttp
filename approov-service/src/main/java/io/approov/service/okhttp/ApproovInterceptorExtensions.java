package io.approov.service.okhttp;

import okhttp3.Request;

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
}
