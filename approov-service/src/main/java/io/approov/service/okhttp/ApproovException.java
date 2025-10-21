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
import java.io.IOException;

// ApproovException is thrown if there is an error from Approov.
public class ApproovException extends IOException {

// =====================
// Approov Error Codes
// =====================

// General errors (0–99)
public static final int ERROR_UNKNOWN = 0;
public static final int ERROR_ILLEGAL_STATE = 1;
public static final int ERROR_ILLEGAL_ARGUMENT = 2;
public static final int ERROR_MISSING_VALUE = 3;
public static final int ERROR_NULL_REQUEST = 4;
public static final int ERROR_NETWORK_NO_CONNECTION_INFO = 5;
public static final int ERROR_LEGACY = 6;

// Token fetch errors (100–199)
public static final int ERROR_TOKEN_FETCH_FAILED = 100;
public static final int ERROR_TOKEN_FETCH_REJECTED = 101;
public static final int ERROR_TOKEN_FETCH_NO_NETWORK = 102;
public static final int ERROR_TOKEN_FETCH_POOR_NETWORK = 103;
public static final int ERROR_TOKEN_FETCH_MITM_DETECTED = 104;
public static final int ERROR_TOKEN_FETCH_NO_SERVICE = 105;
public static final int ERROR_TOKEN_FETCH_UNKNOWN_URL = 106;
public static final int ERROR_TOKEN_FETCH_UNPROTECTED_URL = 107;
public static final int ERROR_TOKEN_FETCH_TIMEOUT = 108;
public static final int ERROR_TOKEN_FETCH_UNKNOWN_FAILURE = 199;
// ==========================================

private final int errorCode;
    /**
     * Constructs an exception due to an Approov error.
     *
     * @param message is the basic information about the exception cause
     * @Depriciated use ApproovException(int errorCode, String message) instead.
     */
    public ApproovException(String message) {
        this(ERROR_LEGACY, message);
    }

    /**
     * Constructs an exception due to an Approov error while wrapping the cause
     *
     * @param message is the basic information about the exception cause
     * @param cause is the underlying cause of the exception
     * @Depriciated use ApproovException(int errorCode, String message, Throwable cause) instead.
     */
    public ApproovException(String message, Throwable cause) {
        this(ERROR_LEGACY, message, cause);
    }

    /**
     * Constructs an exception with a specific error code and message.
     *
     * @param errorCode provides a machine readable reason for the failure
     * @param message is the basic information about the exception cause
     */
    public ApproovException(int errorCode, String message) {
        this(errorCode, message, null);
    }

    /**
     * Constructs an exception with an error code and cause.
     *
     * @param errorCode provides a machine readable reason for the failure
     * @param message is the basic information about the exception cause
     * @param cause is the underlying cause of the exception
     */
    public ApproovException(int errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * Constructs an exception with a TokenFetchStatus and message.
     *
     * @param status TokenFetchStatus that triggered the error
     * @param message is the basic information about the exception cause
     * */
    public ApproovException(Approov.TokenFetchStatus status, String message) {
        super(message, null);
        this.errorCode = mapTokenFetchStatus(status);
    }
    /**
     * Constructs an exception with TokenFetchStatus, message and Throwable cause .
     *
     * @param status TokenFetchStatus that triggered the error
     * @param message is the basic information about the exception cause
     * @param cause is the underlying cause of the exception
     */
    public ApproovException(Approov.TokenFetchStatus status, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = mapTokenFetchStatus(status);
    }

    /**
     * Gets the error code associated with the exception.
     *
     * @return integer error code
     */
    public int getErrorCode() {
        return errorCode;
    }

    /**
     * Maps a token fetch status to a well known error code.
     *
     * @param status the token fetch status
     * @return matching error code
     */
    public static int mapTokenFetchStatus(Approov.TokenFetchStatus status) {
        if (status == null) {
            return ERROR_UNKNOWN;
        }
        switch (status) {
            case REJECTED:
                return ERROR_TOKEN_FETCH_REJECTED;
            case NO_NETWORK:
                return ERROR_TOKEN_FETCH_NO_NETWORK;
            case POOR_NETWORK:
                return ERROR_TOKEN_FETCH_POOR_NETWORK;
            case MITM_DETECTED:
                return ERROR_TOKEN_FETCH_MITM_DETECTED;
            case NO_APPROOV_SERVICE:
                return ERROR_TOKEN_FETCH_NO_SERVICE;
            case UNKNOWN_URL:
                return ERROR_TOKEN_FETCH_UNKNOWN_URL;
            case UNPROTECTED_URL:
                return ERROR_TOKEN_FETCH_UNPROTECTED_URL;
            default:
                return ERROR_TOKEN_FETCH_UNKNOWN_FAILURE;
        }
    }

}
