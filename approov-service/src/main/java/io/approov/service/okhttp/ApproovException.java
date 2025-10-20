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

import java.io.IOException;

// ApproovException is thrown if there is an error from Approov.
public class ApproovException extends IOException {
    private final int errorCode;

    /**
     * Constructs an exception due to an Approov error.
     *
     * @param errorCode identifies the specific Approov error condition
     * @param message is the basic information about the exception cause
     */
    public ApproovException(int errorCode, String message) {
        super(formatMessage(errorCode, message));
        this.errorCode = errorCode;
    }

    /**
     * Constructs an exception due to an Approov error while wrapping the cause.
     *
     * @param errorCode identifies the specific Approov error condition
     * @param message is the basic information about the exception cause
     * @param cause is the underlying cause of the exception
     */
    public ApproovException(int errorCode, String message, Throwable cause) {
        super(formatMessage(errorCode, message), cause);
        this.errorCode = errorCode;
    }

    /**
     * Constructs an exception due to an Approov error (legacy signature).
     *
     * @param message is the basic information about the exception cause
     * @deprecated Use {@link #ApproovException(int, String)} instead to provide a structured error code.
     */
    @Deprecated
    public ApproovException(String message) {
        this(ApproovErrorCodes.LEGACY_GENERAL_ERROR, message);
    }

    /**
     * Constructs an exception due to an Approov error while wrapping the cause (legacy signature).
     *
     * @param message is the basic information about the exception cause
     * @param cause is the underlying cause of the exception
     * @deprecated Use {@link #ApproovException(int, String, Throwable)} instead to provide a structured error code.
     */
    @Deprecated
    public ApproovException(String message, Throwable cause) {
        this(ApproovErrorCodes.LEGACY_GENERAL_ERROR, message, cause);
    }

    /**
     * Returns the structured error code associated with the exception.
     *
     * @return the Approov error code
     */
    public int getErrorCode() {
        return errorCode;
    }

    /**
     * Returns a human readable description for the error code.
     *
     * @return the description for the error code, or a generic message if unknown
     */
    public String getErrorDescription() {
        return ApproovErrorCodes.describe(errorCode);
    }

    private static String formatMessage(int errorCode, String message) {
        StringBuilder builder = new StringBuilder("[");
        builder.append(errorCode).append("] ");
        if (message != null) {
            builder.append(message);
        } else {
            builder.append("Approov error");
        }
        return builder.toString();
    }
}
