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

// ApproovNetworkException indicates an exception caused by networking conditions which is likely to be
// temporary so a user initiated retry should be performed
public class ApproovNetworkException extends ApproovException {

    /**
     * Constructs an Approov networking exception.
     *
     * @param errorCode identifies the specific network failure scenario
     * @param message is the basic information about the exception cause
     */
    public ApproovNetworkException(int errorCode, String message) {
        super(errorCode, message);
    }

    /**
     * Constructs an Approov networking exception while wrapping the cause.
     *
     * @param errorCode identifies the specific network failure scenario
     * @param message is the basic information about the exception cause
     * @param cause is the underlying cause of the exception
     */
    public ApproovNetworkException(int errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }

    /**
     * Constructs an Approov networking exception (legacy signature).
     *
     * @param message is the basic information about the exception cause
     * @deprecated Use {@link #ApproovNetworkException(int, String)} instead to provide a structured error code.
     */
    @Deprecated
    public ApproovNetworkException(String message) {
        this(ApproovErrorCodes.LEGACY_NETWORK_ERROR, message);
    }

    /**
     * Constructs an Approov networking exception while wrapping the cause (legacy signature).
     *
     * @param message is the basic information about the exception cause
     * @param cause is the underlying cause of the exception
     * @deprecated Use {@link #ApproovNetworkException(int, String, Throwable)} instead to provide a structured error code.
     */
    @Deprecated
    public ApproovNetworkException(String message, Throwable cause) {
        this(ApproovErrorCodes.LEGACY_NETWORK_ERROR, message, cause);
    }
}
