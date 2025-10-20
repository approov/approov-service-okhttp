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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Defines integer error codes used by {@link ApproovException} and related subclasses.
 * Each code maps to a short description that can be surfaced in logs or analytics
 * to aid debugging without relying on exception message parsing.
 */
public final class ApproovErrorCodes {
    // SDK interaction failures
    public static final int SDK_SET_DEV_KEY_ILLEGAL_STATE = 1000;
    public static final int SDK_SET_DEV_KEY_ILLEGAL_ARGUMENT = 1001;
    public static final int SDK_PRECHECK_ILLEGAL_STATE = 1002;
    public static final int SDK_PRECHECK_ILLEGAL_ARGUMENT = 1003;
    public static final int SDK_GET_DEVICE_ID_ILLEGAL_STATE = 1004;
    public static final int SDK_SET_DATA_HASH_ILLEGAL_STATE = 1005;
    public static final int SDK_SET_DATA_HASH_ILLEGAL_ARGUMENT = 1006;
    public static final int SDK_FETCH_TOKEN_ILLEGAL_STATE = 1007;
    public static final int SDK_FETCH_TOKEN_ILLEGAL_ARGUMENT = 1008;
    public static final int ACCOUNT_SIGNATURE_UNAVAILABLE = 1009;
    public static final int SDK_GET_ACCOUNT_SIGNATURE_ILLEGAL_STATE = 1010;
    public static final int SDK_GET_ACCOUNT_SIGNATURE_ILLEGAL_ARGUMENT = 1011;
    public static final int INSTALL_SIGNATURE_UNAVAILABLE = 1012;
    public static final int SDK_GET_INSTALL_SIGNATURE_ILLEGAL_STATE = 1013;
    public static final int SDK_GET_INSTALL_SIGNATURE_ILLEGAL_ARGUMENT = 1014;
    public static final int SDK_FETCH_SECURE_STRING_ILLEGAL_STATE = 1015;
    public static final int SDK_FETCH_SECURE_STRING_ILLEGAL_ARGUMENT = 1016;
    public static final int SDK_FETCH_CUSTOM_JWT_ILLEGAL_STATE = 1017;
    public static final int SDK_FETCH_CUSTOM_JWT_ILLEGAL_ARGUMENT = 1018;

    // Mutator and interceptor validation failures
    public static final int MUTATOR_PRECHECK_UNEXPECTED_STATUS = 3000;
    public static final int MUTATOR_FETCH_TOKEN_UNEXPECTED_STATUS = 3001;
    public static final int MUTATOR_FETCH_SECURE_STRING_UNEXPECTED_STATUS = 3002;
    public static final int MUTATOR_FETCH_CUSTOM_JWT_UNEXPECTED_STATUS = 3003;
    public static final int MUTATOR_INTERCEPTOR_NULL_REQUEST = 3004;
    public static final int MUTATOR_INTERCEPTOR_FETCH_TOKEN_UNEXPECTED_STATUS = 3005;
    public static final int MUTATOR_HEADER_SUBSTITUTION_UNEXPECTED_STATUS = 3006;
    public static final int MUTATOR_QUERY_SUBSTITUTION_UNEXPECTED_STATUS = 3007;

    // Network related failures that should support retry logic
    public static final int NETWORK_PRECHECK_FAILURE = 4000;
    public static final int NETWORK_FETCH_TOKEN_FAILURE = 4001;
    public static final int NETWORK_FETCH_SECURE_STRING_FAILURE = 4002;
    public static final int NETWORK_FETCH_CUSTOM_JWT_FAILURE = 4003;
    public static final int NETWORK_INTERCEPTOR_FETCH_TOKEN_FAILURE = 4004;
    public static final int NETWORK_HEADER_SUBSTITUTION_FAILURE = 4005;
    public static final int NETWORK_QUERY_SUBSTITUTION_FAILURE = 4006;
    public static final int NETWORK_PINNING_NO_CONNECTION_INFO = 4007;

    // Explicit rejection scenarios surfaced by the Approov service
    public static final int REJECTION_PRECHECK = 5000;
    public static final int REJECTION_FETCH_SECURE_STRING = 5001;
    public static final int REJECTION_FETCH_CUSTOM_JWT = 5002;
    public static final int REJECTION_HEADER_SUBSTITUTION = 5003;
    public static final int REJECTION_QUERY_SUBSTITUTION = 5004;

    // Legacy compatibility codes used when older constructors are invoked
    public static final int LEGACY_GENERAL_ERROR = 9000;
    public static final int LEGACY_NETWORK_ERROR = 9001;
    public static final int LEGACY_REJECTION_ERROR = 9002;

    private static final Map<Integer, String> DESCRIPTIONS;

    static {
        Map<Integer, String> descriptions = new LinkedHashMap<>();

        descriptions.put(SDK_SET_DEV_KEY_ILLEGAL_STATE, "Approov SDK rejected setDevKey due to illegal state");
        descriptions.put(SDK_SET_DEV_KEY_ILLEGAL_ARGUMENT, "Approov SDK rejected setDevKey due to invalid argument");
        descriptions.put(SDK_PRECHECK_ILLEGAL_STATE, "Approov SDK rejected precheck due to illegal state");
        descriptions.put(SDK_PRECHECK_ILLEGAL_ARGUMENT, "Approov SDK rejected precheck due to invalid argument");
        descriptions.put(SDK_GET_DEVICE_ID_ILLEGAL_STATE, "Approov SDK getDeviceID called in illegal state");
        descriptions.put(SDK_SET_DATA_HASH_ILLEGAL_STATE, "Approov SDK rejected setDataHashInToken due to illegal state");
        descriptions.put(SDK_SET_DATA_HASH_ILLEGAL_ARGUMENT, "Approov SDK rejected setDataHashInToken due to invalid argument");
        descriptions.put(SDK_FETCH_TOKEN_ILLEGAL_STATE, "Approov SDK rejected fetchToken due to illegal state");
        descriptions.put(SDK_FETCH_TOKEN_ILLEGAL_ARGUMENT, "Approov SDK rejected fetchToken due to invalid argument");
        descriptions.put(ACCOUNT_SIGNATURE_UNAVAILABLE, "Account message signing key not yet available");
        descriptions.put(SDK_GET_ACCOUNT_SIGNATURE_ILLEGAL_STATE, "Approov SDK rejected getAccountMessageSignature due to illegal state");
        descriptions.put(SDK_GET_ACCOUNT_SIGNATURE_ILLEGAL_ARGUMENT, "Approov SDK rejected getAccountMessageSignature due to invalid argument");
        descriptions.put(INSTALL_SIGNATURE_UNAVAILABLE, "Install message signing key not yet available");
        descriptions.put(SDK_GET_INSTALL_SIGNATURE_ILLEGAL_STATE, "Approov SDK rejected getInstallMessageSignature due to illegal state");
        descriptions.put(SDK_GET_INSTALL_SIGNATURE_ILLEGAL_ARGUMENT, "Approov SDK rejected getInstallMessageSignature due to invalid argument");
        descriptions.put(SDK_FETCH_SECURE_STRING_ILLEGAL_STATE, "Approov SDK rejected fetchSecureString due to illegal state");
        descriptions.put(SDK_FETCH_SECURE_STRING_ILLEGAL_ARGUMENT, "Approov SDK rejected fetchSecureString due to invalid argument");
        descriptions.put(SDK_FETCH_CUSTOM_JWT_ILLEGAL_STATE, "Approov SDK rejected fetchCustomJWT due to illegal state");
        descriptions.put(SDK_FETCH_CUSTOM_JWT_ILLEGAL_ARGUMENT, "Approov SDK rejected fetchCustomJWT due to invalid argument");

        descriptions.put(MUTATOR_PRECHECK_UNEXPECTED_STATUS, "Precheck produced unexpected status in mutator");
        descriptions.put(MUTATOR_FETCH_TOKEN_UNEXPECTED_STATUS, "fetchToken produced unexpected status in mutator");
        descriptions.put(MUTATOR_FETCH_SECURE_STRING_UNEXPECTED_STATUS, "fetchSecureString produced unexpected status in mutator");
        descriptions.put(MUTATOR_FETCH_CUSTOM_JWT_UNEXPECTED_STATUS, "fetchCustomJWT produced unexpected status in mutator");
        descriptions.put(MUTATOR_INTERCEPTOR_NULL_REQUEST, "Interceptor received null request");
        descriptions.put(MUTATOR_INTERCEPTOR_FETCH_TOKEN_UNEXPECTED_STATUS, "Interceptor token fetch produced unexpected status");
        descriptions.put(MUTATOR_HEADER_SUBSTITUTION_UNEXPECTED_STATUS, "Header substitution produced unexpected status");
        descriptions.put(MUTATOR_QUERY_SUBSTITUTION_UNEXPECTED_STATUS, "Query substitution produced unexpected status");

        descriptions.put(NETWORK_PRECHECK_FAILURE, "Network failure during precheck");
        descriptions.put(NETWORK_FETCH_TOKEN_FAILURE, "Network failure during fetchToken");
        descriptions.put(NETWORK_FETCH_SECURE_STRING_FAILURE, "Network failure during fetchSecureString");
        descriptions.put(NETWORK_FETCH_CUSTOM_JWT_FAILURE, "Network failure during fetchCustomJWT");
        descriptions.put(NETWORK_INTERCEPTOR_FETCH_TOKEN_FAILURE, "Network failure in interceptor token fetch");
        descriptions.put(NETWORK_HEADER_SUBSTITUTION_FAILURE, "Network failure during header substitution");
        descriptions.put(NETWORK_QUERY_SUBSTITUTION_FAILURE, "Network failure during query substitution");
        descriptions.put(NETWORK_PINNING_NO_CONNECTION_INFO, "Network interceptor lacked connection for pinning");

        descriptions.put(REJECTION_PRECHECK, "Precheck rejected by Approov");
        descriptions.put(REJECTION_FETCH_SECURE_STRING, "Secure string request rejected by Approov");
        descriptions.put(REJECTION_FETCH_CUSTOM_JWT, "Custom JWT request rejected by Approov");
        descriptions.put(REJECTION_HEADER_SUBSTITUTION, "Header substitution rejected by Approov");
        descriptions.put(REJECTION_QUERY_SUBSTITUTION, "Query substitution rejected by Approov");

        descriptions.put(LEGACY_GENERAL_ERROR, "Legacy Approov exception without explicit error code");
        descriptions.put(LEGACY_NETWORK_ERROR, "Legacy Approov network exception without explicit error code");
        descriptions.put(LEGACY_REJECTION_ERROR, "Legacy Approov rejection exception without explicit error code");

        DESCRIPTIONS = Collections.unmodifiableMap(descriptions);
    }

    private ApproovErrorCodes() {
    }

    /**
     * Provides a human readable description for the supplied error code.
     *
     * @param code the Approov error code
     * @return the associated description, or a default when the code is unknown
     */
    public static String describe(int code) {
        String description = DESCRIPTIONS.get(code);
        return (description != null) ? description : "Unknown Approov error code";
    }
}
