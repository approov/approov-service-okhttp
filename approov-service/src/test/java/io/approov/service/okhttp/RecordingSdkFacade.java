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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * An SDK facade that records every call the layer makes, as "method" or
 * "method:argument", and then delegates to the real (mini) SDK, so that a test
 * can assert both what reached the SDK and how the layer behaved on its answer.
 * Install with ApproovService.setSdkFacadeForTesting; ApproovService.reset()
 * reinstates the default facade.
 */
class RecordingSdkFacade implements ApproovSdkFacade {
    private final ApproovSdkFacade delegate = new DefaultApproovSdkFacade();

    // every call in order; synchronized because interceptors run on OkHttp threads
    final List<String> calls = Collections.synchronizedList(new ArrayList<>());

    // the arguments of the most recent initialize, with null kept as null
    volatile String lastInitializeConfig;
    volatile String lastInitializeComment;
    volatile boolean initializeCalled;

    static RecordingSdkFacade install() {
        RecordingSdkFacade facade = new RecordingSdkFacade();
        ApproovService.setSdkFacadeForTesting(facade);
        return facade;
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
    public boolean initialize(Context context, String config, String updateConfig, String comment) {
        calls.add("initialize");
        initializeCalled = true;
        lastInitializeConfig = config;
        lastInitializeComment = comment;
        return delegate.initialize(context, config, updateConfig, comment);
    }

    @Override
    public void setUserProperty(String property) {
        calls.add("setUserProperty:" + property);
        delegate.setUserProperty(property);
    }

    @Override
    public void setDevKey(String devKey) {
        calls.add("setDevKey:" + devKey);
        delegate.setDevKey(devKey);
    }

    @Override
    public void fetchApproovToken(Approov.TokenFetchCallback callback, String url) {
        calls.add("fetchApproovToken:" + url);
        delegate.fetchApproovToken(callback, url);
    }

    @Override
    public Approov.TokenFetchResult fetchApproovTokenAndWait(String url) {
        calls.add("fetchApproovTokenAndWait:" + url);
        return delegate.fetchApproovTokenAndWait(url);
    }

    @Override
    public Approov.TokenFetchResult fetchSecureStringAndWait(String key, String newDefinition) {
        calls.add("fetchSecureStringAndWait:" + key);
        return delegate.fetchSecureStringAndWait(key, newDefinition);
    }

    @Override
    public Approov.TokenFetchResult fetchCustomJWTAndWait(String payload) {
        calls.add("fetchCustomJWTAndWait");
        return delegate.fetchCustomJWTAndWait(payload);
    }

    @Override
    public String getDeviceID() {
        calls.add("getDeviceID");
        return delegate.getDeviceID();
    }

    @Override
    public void setDataHashInToken(String data) {
        calls.add("setDataHashInToken");
        delegate.setDataHashInToken(data);
    }

    @Override
    public String getAccountMessageSignature(String message) {
        calls.add("getAccountMessageSignature");
        return delegate.getAccountMessageSignature(message);
    }

    @Override
    public String getInstallMessageSignature(String message) {
        calls.add("getInstallMessageSignature");
        return delegate.getInstallMessageSignature(message);
    }

    @Override
    public Map<String, List<String>> getPins(String pinType) {
        calls.add("getPins:" + pinType);
        return delegate.getPins(pinType);
    }

    @Override
    public void setInstallAttrsInToken(String attributes) {
        calls.add("setInstallAttrsInToken:" + attributes);
        delegate.setInstallAttrsInToken(attributes);
    }

    @Override
    public String fetchConfig() {
        calls.add("fetchConfig");
        return delegate.fetchConfig();
    }
}
