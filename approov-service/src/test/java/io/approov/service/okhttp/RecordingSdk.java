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
import com.criticalblue.approovsdk.MiniSdkCallHook;
import com.criticalblue.minisdk.testing.AttesterProxyController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A mini-sdk call hook that records every Approov SDK call the layer makes, as
 * "method" or "method:argument", and then lets the mini-sdk answer it, so that a
 * test can assert both what reached the SDK and how the layer behaved on its
 * answer. A test overrides a method to answer a call itself; super runs the
 * mini-sdk with the arguments it is given. Install with install() and remove with
 * uninstall(), which every test class that installs one calls when it finishes.
 */
class RecordingSdk implements MiniSdkCallHook {
    // the mini-sdk's handling of the call this thread is in
    private final ThreadLocal<Call> current = new ThreadLocal<>();

    // every call in order; synchronized because interceptors run on OkHttp threads
    final List<String> calls = Collections.synchronizedList(new ArrayList<>());

    // the arguments of the most recent initialize, with null kept as null
    volatile String lastInitializeConfig;
    volatile String lastInitializeComment;
    volatile boolean initializeCalled;

    static RecordingSdk install() {
        return install(new RecordingSdk());
    }

    static <T extends RecordingSdk> T install(T sdk) {
        AttesterProxyController.setCallHook(sdk);
        return sdk;
    }

    static void uninstall() {
        AttesterProxyController.setCallHook(null);
    }

    List<String> snapshot() {
        synchronized (calls) {
            return new ArrayList<>(calls);
        }
    }

    int count(String prefix) {
        int n = 0;
        for (String call : snapshot()) {
            if (call.equals(prefix) || call.startsWith(prefix + ":"))
                n++;
        }
        return n;
    }

    @Override
    @SuppressWarnings("unchecked")
    public final Object onCall(String method, Object[] args, Call sdk) {
        Call outer = current.get();
        current.set(sdk);
        try {
            switch (method) {
                case "initialize":
                    return initialize((Context) args[0], (String) args[1], (String) args[2], (String) args[3]);
                case "setUserProperty":
                    setUserProperty((String) args[0]);
                    return null;
                case "setDevKey":
                    setDevKey((String) args[0]);
                    return null;
                case "fetchApproovToken":
                    fetchApproovToken((Approov.TokenFetchCallback) args[0], (String) args[1]);
                    return null;
                case "fetchApproovTokenAndWait":
                    return fetchApproovTokenAndWait((String) args[0]);
                case "fetchSecureStringAndWait":
                    return fetchSecureStringAndWait((String) args[0], (String) args[1]);
                case "fetchCustomJWTAndWait":
                    return fetchCustomJWTAndWait((String) args[0]);
                case "getDeviceID":
                    return getDeviceID();
                case "setDataHashInToken":
                    setDataHashInToken((String) args[0]);
                    return null;
                case "getAccountMessageSignature":
                    return getAccountMessageSignature((String) args[0]);
                case "getInstallMessageSignature":
                    return getInstallMessageSignature((String) args[0]);
                case "getPins":
                    return getPins((String) args[0]);
                case "setInstallAttrsInToken":
                    setInstallAttrsInToken((String) args[0]);
                    return null;
                case "fetchConfig":
                    return fetchConfig();
                case "getServerAdjustedCurrentTimeMS":
                    return getServerAdjustedCurrentTimeMS();
                default:
                    calls.add(method);
                    return sdk.proceed(args);
            }
        } finally {
            current.set(outer);
        }
    }

    // runs the current call in the mini-sdk with the given arguments
    @SuppressWarnings("unchecked")
    private <T> T sdk(Object... args) {
        return (T) current.get().proceed(args);
    }

    public boolean initialize(Context context, String config, String updateConfig, String comment) {
        calls.add("initialize");
        initializeCalled = true;
        lastInitializeConfig = config;
        lastInitializeComment = comment;
        return this.<Boolean>sdk(context, config, updateConfig, comment);
    }

    public void setUserProperty(String property) {
        calls.add("setUserProperty:" + property);
        sdk(property);
    }

    public void setDevKey(String devKey) {
        calls.add("setDevKey:" + devKey);
        sdk(devKey);
    }

    public void fetchApproovToken(Approov.TokenFetchCallback callback, String url) {
        calls.add("fetchApproovToken:" + url);
        sdk(callback, url);
    }

    public Approov.TokenFetchResult fetchApproovTokenAndWait(String url) {
        calls.add("fetchApproovTokenAndWait:" + url);
        return sdk(url);
    }

    public Approov.TokenFetchResult fetchSecureStringAndWait(String key, String newDefinition) {
        calls.add("fetchSecureStringAndWait:" + key);
        return sdk(key, newDefinition);
    }

    public Approov.TokenFetchResult fetchCustomJWTAndWait(String payload) {
        calls.add("fetchCustomJWTAndWait");
        return sdk(payload);
    }

    public String getDeviceID() {
        calls.add("getDeviceID");
        return sdk();
    }

    public void setDataHashInToken(String data) {
        calls.add("setDataHashInToken");
        sdk(data);
    }

    public String getAccountMessageSignature(String message) {
        calls.add("getAccountMessageSignature");
        return sdk(message);
    }

    public String getInstallMessageSignature(String message) {
        calls.add("getInstallMessageSignature");
        return sdk(message);
    }

    public Map<String, List<String>> getPins(String pinType) {
        calls.add("getPins:" + pinType);
        return sdk(pinType);
    }

    public void setInstallAttrsInToken(String attributes) {
        calls.add("setInstallAttrsInToken:" + attributes);
        sdk(attributes);
    }

    public String fetchConfig() {
        calls.add("fetchConfig");
        return sdk();
    }

    public long getServerAdjustedCurrentTimeMS() {
        return this.<Long>sdk();
    }
}
