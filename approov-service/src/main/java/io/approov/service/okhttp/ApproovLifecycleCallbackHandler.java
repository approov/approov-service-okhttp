package io.approov.service.okhttp;

import okhttp3.Request;

/**
 * ApproovLifecycleCallbackHandler provides an opportunity to further modify requests after Approov has
 * made its modifications.
 */
public interface ApproovLifecycleCallbackHandler {
    Request processedRequest(Request request, ApproovRequestMutations changes) throws ApproovException;
}
