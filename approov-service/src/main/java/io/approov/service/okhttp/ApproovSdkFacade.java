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

import android.content.Context;

import com.criticalblue.approovsdk.Approov;

import java.util.List;
import java.util.Map;

/**
 * Package-private boundary around the static Approov SDK API. Production uses
 * the direct delegating implementation; tests can install a recording facade
 * without changing the public service API or globally mocking static methods.
 * Mirrors io.approov.service.android.ApproovSdkFacade.
 */
interface ApproovSdkFacade {
    boolean initialize(Context context, String config, String updateConfig, String comment);

    void setUserProperty(String property);

    void setDevKey(String devKey);

    void fetchApproovToken(Approov.TokenFetchCallback callback, String url);

    Approov.TokenFetchResult fetchApproovTokenAndWait(String url);

    Approov.TokenFetchResult fetchSecureStringAndWait(String key, String newDefinition);

    Approov.TokenFetchResult fetchCustomJWTAndWait(String payload);

    String getDeviceID();

    void setDataHashInToken(String data);

    String getAccountMessageSignature(String message);

    String getInstallMessageSignature(String message);

    Map<String, List<String>> getPins(String pinType);

    void setInstallAttrsInToken(String attributes);

    String fetchConfig();
}
