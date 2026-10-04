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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.criticalblue.approovsdk.Approov;
import com.criticalblue.minisdk.testing.AttesterProxyController;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;
import java.util.Collections;

/**
 * Every public method that consumes the SDK throws
 * ApproovException("<method>: Approov protection not enabled") without calling
 * the SDK while protection is not enabled, before initialize and in bypass mode,
 * even when the SDK was initialized elsewhere in the process (TESTING_REQUIREMENTS
 * 1 "All methods documented in REFERENCE.md must include guards"). Each declares
 * the checked ApproovException. Request path helpers are not guarded: requests
 * pass through (RequestBeforeInitialize380Test). Mirrors approov-service-android's
 * DirectMethodGuard380Test.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class DirectMethodGuard380Test {
    private static final String CONFIG = "#cb-ivol#mAxOF0ekJUOC36J5XWmVmVipOcUoEdMjhPSp2FVtyTo=";

    private interface DirectCall {
        void call() throws ApproovException;
    }

    private Context context;
    private RecordingSdkFacade sdk;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        AttesterProxyController.reset();
        // the SDK is initialized elsewhere in the process, so a missing guard would
        // reach a working SDK rather than fail inside it
        Approov.initialize(context, CONFIG, "auto", "reinit-guard-setup");
        ApproovService.reset();
        sdk = RecordingSdkFacade.install();
    }

    @After
    public void tearDown() {
        ApproovService.reset();
        AttesterProxyController.reset();
    }

    private void assertGuarded(String method, DirectCall call) {
        for (boolean bypass : new boolean[] {false, true}) {
            ApproovService.reset();
            sdk = RecordingSdkFacade.install();
            if (bypass) {
                ApproovService.initialize(context, "");
                assertTrue(ApproovService.isApproovServiceEnabled());
            }
            assertFalse(ApproovService.isApproovProtectionEnabled());
            String when = bypass ? " in bypass mode" : " before initialize";
            try {
                call.call();
                fail(method + " must throw" + when);
            } catch (ApproovException e) {
                assertEquals(method + when, ApproovException.class, e.getClass());
                assertEquals(method + ": Approov protection not enabled", e.getMessage());
                assertNull(method + when + " threw before reaching the SDK", e.getCause());
            }
            assertEquals(method + when + " must not call the SDK",
                    Collections.<String>emptyList(), sdk.snapshot());
        }
    }

    @Test
    public void approovExceptionIsChecked() {
        assertTrue(java.io.IOException.class.isAssignableFrom(ApproovException.class));
        assertFalse(RuntimeException.class.isAssignableFrom(ApproovException.class));
    }

    @Test
    public void everyGuardedMethodDeclaresApproovException() throws Exception {
        Object[][] methods = {
            {"setDevKey", new Class<?>[] {String.class}},
            {"precheck", new Class<?>[] {}},
            {"getDeviceID", new Class<?>[] {}},
            {"setDataHashInToken", new Class<?>[] {String.class}},
            {"fetchToken", new Class<?>[] {String.class}},
            {"getMessageSignature", new Class<?>[] {String.class}},
            {"getAccountMessageSignature", new Class<?>[] {String.class}},
            {"getInstallMessageSignature", new Class<?>[] {String.class}},
            {"fetchSecureString", new Class<?>[] {String.class, String.class}},
            {"fetchCustomJWT", new Class<?>[] {String.class}},
            {"setInstallAttributes", new Class<?>[] {String.class}},
        };
        for (Object[] m : methods) {
            Method method = ApproovService.class.getMethod((String) m[0], (Class<?>[]) m[1]);
            assertArrayEquals(m[0] + " declares ApproovException",
                    new Class<?>[] {ApproovException.class}, method.getExceptionTypes());
        }
    }

    @Test
    public void setDevKeyIsGuarded() {
        assertGuarded("setDevKey", () -> ApproovService.setDevKey("dev-key"));
    }

    @Test
    public void precheckIsGuarded() {
        assertGuarded("precheck", ApproovService::precheck);
    }

    @Test
    public void getDeviceIDIsGuarded() {
        assertGuarded("getDeviceID", ApproovService::getDeviceID);
    }

    @Test
    public void setDataHashInTokenIsGuarded() {
        assertGuarded("setDataHashInToken", () -> ApproovService.setDataHashInToken("data"));
    }

    @Test
    public void fetchTokenIsGuarded() {
        assertGuarded("fetchToken", () -> ApproovService.fetchToken("https://api.example.com/"));
    }

    @Test
    @SuppressWarnings("deprecation")
    public void getMessageSignatureIsGuarded() {
        assertGuarded("getMessageSignature", () -> ApproovService.getMessageSignature("message"));
    }

    @Test
    public void getAccountMessageSignatureIsGuarded() {
        assertGuarded("getAccountMessageSignature", () -> ApproovService.getAccountMessageSignature("message"));
    }

    @Test
    public void getInstallMessageSignatureIsGuarded() {
        assertGuarded("getInstallMessageSignature", () -> ApproovService.getInstallMessageSignature("message"));
    }

    @Test
    public void fetchSecureStringIsGuarded() {
        assertGuarded("fetchSecureString", () -> ApproovService.fetchSecureString("key", null));
    }

    @Test
    public void fetchCustomJWTIsGuarded() {
        assertGuarded("fetchCustomJWT", () -> ApproovService.fetchCustomJWT("{}"));
    }

    @Test
    public void setInstallAttributesIsGuarded() {
        assertGuarded("setInstallAttributes", () -> ApproovService.setInstallAttributes("signed-jwt"));
    }

    @Test
    public void guardedMethodsWorkOnceProtected() throws Exception {
        ApproovService.initialize(context, CONFIG, "reinit-guard");
        sdk.calls.clear();
        ApproovService.getDeviceID();
        ApproovService.setDataHashInToken("data");
        assertEquals(java.util.Arrays.asList("getDeviceID", "setDataHashInToken"), sdk.snapshot());
    }
}
