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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import androidx.test.core.app.ApplicationProvider;

import com.criticalblue.approovsdk.Approov;
import com.criticalblue.minisdk.testing.AttesterProxyController;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;

import io.approov.service.okhttp.ApproovDefaultMessageSigning.OkHttpComponentProvider;
import io.approov.service.okhttp.ApproovDefaultMessageSigning.SignatureParametersFactory;
import okhttp3.Request;

/**
 * The 3.7.0 public surface of ApproovService. The 3.5.8 names isInitialized,
 * isApproovEnabled and setInstallAttrsInToken are kept beside the 3.7.0 names
 * and behave the same. Message signing is the 3.5.8 service mutator
 * ApproovDefaultMessageSigning, installed with setServiceMutator, and
 * ApproovService has no message signing methods of its own. Every interceptor
 * hook of ApproovServiceMutator declares ApproovException, as in 3.5.8.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class PublicSurface370Test {
    private static final String CONFIG = "#test-account#mAxOF0ekJUOC36J5XWmVmVipOcUoEdMjhPSp2FVtyTo=";

    @After
    public void tearDown() {
        ApproovService.reset();
        AttesterProxyController.reset();
    }

    static boolean hasPublicMethod(Class<?> type, String name) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name))
                return true;
        }
        return false;
    }

    // the 3.5.8 names report exactly what the 3.7.0 names report
    private static void assertEnabledFlags(String what, boolean serviceEnabled, boolean protectionEnabled) {
        assertEquals(what + ": isApproovServiceEnabled", serviceEnabled, ApproovService.isApproovServiceEnabled());
        assertEquals(what + ": isInitialized", serviceEnabled, ApproovService.isInitialized());
        assertEquals(what + ": isApproovProtectionEnabled", protectionEnabled,
                ApproovService.isApproovProtectionEnabled());
        assertEquals(what + ": isApproovEnabled", protectionEnabled, ApproovService.isApproovEnabled());
    }

    @Test
    public void restoredEnabledFlagsMatchThe370Names() throws Exception {
        for (String name : new String[] {"isInitialized", "isApproovEnabled", "isApproovServiceEnabled",
                "isApproovProtectionEnabled"}) {
            Method method = ApproovService.class.getMethod(name);
            assertTrue(name + " is static", Modifier.isStatic(method.getModifiers()));
            assertEquals(name, boolean.class, method.getReturnType());
        }

        ApproovService.reset();
        AttesterProxyController.reset();
        assertEnabledFlags("before initialize", false, false);
        ApproovService.initialize(ApplicationProvider.getApplicationContext(), "");
        assertEnabledFlags("bypass mode", true, false);
        ApproovService.initialize(ApplicationProvider.getApplicationContext(), CONFIG, "reinit-surface-flags");
        assertEnabledFlags("protection enabled", true, true);
    }

    @Test
    public void proceedOnNetworkFailIsRemoved() {
        // a decision on the network statuses
        // belongs to the mutator, ALWAYS_PROCEED or the app's own
        assertFalse(hasPublicMethod(ApproovService.class, "setProceedOnNetworkFail"));
        assertFalse(hasPublicMethod(ApproovService.class, "getProceedOnNetworkFail"));
    }

    @Test
    public void setInstallAttrsInTokenIsRestoredBesideSetInstallAttributes() throws Exception {
        // both names, each throwing the checked ApproovException and calling the SDK's
        // Approov.setInstallAttrsInToken
        for (String name : new String[] {"setInstallAttributes", "setInstallAttrsInToken"}) {
            Method method = ApproovService.class.getMethod(name, String.class);
            assertTrue(name + " is static", Modifier.isStatic(method.getModifiers()));
            assertEquals(name, void.class, method.getReturnType());
            assertArrayEquals(name, new Class<?>[] {ApproovException.class}, method.getExceptionTypes());
        }

        // before initialize, and in bypass mode, each fails naming itself with no SDK call
        ApproovService.reset();
        AttesterProxyController.reset();
        RecordingSdkFacade sdk = RecordingSdkFacade.install();
        for (boolean bypass : new boolean[] {false, true}) {
            if (bypass)
                ApproovService.initialize(ApplicationProvider.getApplicationContext(), "");
            ApproovException e = assertThrows(ApproovException.class,
                    () -> ApproovService.setInstallAttrsInToken("signed-jwt"));
            assertEquals("setInstallAttrsInToken: SDK not initialized", e.getMessage());
            e = assertThrows(ApproovException.class, () -> ApproovService.setInstallAttributes("signed-jwt"));
            assertEquals("setInstallAttributes: SDK not initialized", e.getMessage());
            assertEquals("no SDK call", Collections.<String>emptyList(), sdk.snapshot());
        }

        ApproovService.initialize(ApplicationProvider.getApplicationContext(), CONFIG, "reinit-surface");
        sdk.calls.clear();
        ApproovService.setInstallAttrsInToken("signed-jwt-1");
        ApproovService.setInstallAttributes("signed-jwt-2");
        assertEquals(java.util.Arrays.asList("setInstallAttrsInToken:signed-jwt-1",
                "setInstallAttrsInToken:signed-jwt-2"), sdk.snapshot());
    }

    @Test
    public void setInstallAttrsInTokenReportsAnSdkFailureAsSetInstallAttributesDoes() throws Exception {
        ApproovService.reset();
        AttesterProxyController.reset();
        IllegalArgumentException thrown = new IllegalArgumentException("bad attrs");
        ApproovService.setSdkFacadeForTesting(new RecordingSdkFacade() {
            @Override
            public void setInstallAttrsInToken(String attributes) {
                throw thrown;
            }
        });
        ApproovService.initialize(ApplicationProvider.getApplicationContext(), CONFIG, "reinit-surface-failure");
        ApproovException restored = assertThrows(ApproovException.class,
                () -> ApproovService.setInstallAttrsInToken("signed-jwt"));
        ApproovException current = assertThrows(ApproovException.class,
                () -> ApproovService.setInstallAttributes("signed-jwt"));
        assertSame("the SDK's exception is the cause", thrown, restored.getCause());
        assertSame("the SDK's exception is the cause", thrown, current.getCause());
        assertEquals(current.getMessage(), restored.getMessage());
    }

    @Test
    public void messageSigningIsTheServiceMutatorWithNoApproovServiceMethods() throws Exception {
        for (String name : new String[] {"enableMessageSigning", "disableMessageSigning", "isMessageSigningEnabled",
                "putMessageSigningHostFactory"}) {
            assertFalse(name + " is removed", hasPublicMethod(ApproovService.class, name));
        }
        assertTrue(ApproovServiceMutator.class.isAssignableFrom(ApproovDefaultMessageSigning.class));
        assertTrue("a public constructor", Modifier.isPublic(
                ApproovDefaultMessageSigning.class.getConstructor().getModifiers()));
        Method setDefaultFactory = ApproovDefaultMessageSigning.class.getMethod("setDefaultFactory",
                SignatureParametersFactory.class);
        assertEquals(ApproovDefaultMessageSigning.class, setDefaultFactory.getReturnType());
        Method putHostFactory = ApproovDefaultMessageSigning.class.getMethod("putHostFactory", String.class,
                SignatureParametersFactory.class);
        assertEquals(ApproovDefaultMessageSigning.class, putHostFactory.getReturnType());

        // the per request override points are protected again, as is the component provider
        Method signerBuild = ApproovDefaultMessageSigning.class.getDeclaredMethod("buildSignatureParameters",
                OkHttpComponentProvider.class, ApproovRequestMutations.class);
        assertTrue("ApproovDefaultMessageSigning.buildSignatureParameters is protected",
                Modifier.isProtected(signerBuild.getModifiers()));
        Method factoryBuild = SignatureParametersFactory.class.getDeclaredMethod("buildSignatureParameters",
                OkHttpComponentProvider.class, ApproovRequestMutations.class);
        assertTrue("SignatureParametersFactory.buildSignatureParameters is protected",
                Modifier.isProtected(factoryBuild.getModifiers()));
        assertTrue("OkHttpComponentProvider is protected",
                Modifier.isProtected(OkHttpComponentProvider.class.getModifiers()));

        // signing is reapplied on a protection refresh, and the deprecated 3.5.8 entry point remains
        assertTrue(new ApproovDefaultMessageSigning().supportsProtectionRefresh());
        Method processedRequest = ApproovDefaultMessageSigning.class.getMethod("processedRequest", Request.class,
                ApproovRequestMutations.class);
        assertTrue("processedRequest is deprecated", processedRequest.isAnnotationPresent(Deprecated.class));
    }

    @Test
    public void theDeprecatedProcessedRequestSignsAsTheProcessedRequestHookDoes() throws Exception {
        ApproovService.reset();
        AttesterProxyController.reset();
        ApproovService.initialize(ApplicationProvider.getApplicationContext(), CONFIG, "reinit-surface-signing");
        ApproovDefaultMessageSigning signing = new ApproovDefaultMessageSigning()
                .setDefaultFactory(ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory());
        Request request = new Request.Builder().url("https://api.example.com/p").header("Approov-Token", "t.o.k")
                .build();
        ApproovRequestMutations changes = new ApproovRequestMutations();
        changes.setTokenHeaderKey("Approov-Token");
        changes.setTokenHeaderPrefix("");
        @SuppressWarnings("deprecation")
        Request viaDeprecated = signing.processedRequest(request, changes);
        Request viaHook = signing.handleInterceptorProcessedRequest(request, changes);
        String input = viaDeprecated.header("Signature-Input");
        assertTrue("signed: " + input, (input != null) && input.startsWith("install=(")
                && input.contains("\"approov-token\""));
        assertTrue("the hook signs too: " + viaHook.header("Signature-Input"),
                viaHook.header("Signature-Input") != null);
        // without the token header key nothing is signed
        Request unsigned = signing.processedRequest(request, new ApproovRequestMutations());
        assertNull(unsigned.header("Signature-Input"));
        assertNull(unsigned.header("Signature"));
    }

    @Test
    public void everyInterceptorHookDeclaresApproovException() throws Exception {
        Object[][] hooks = {
            {"handleInterceptorShouldProcessRequest", new Class<?>[] {Request.class}},
            {"handleInterceptorFetchTokenResult", new Class<?>[] {Approov.TokenFetchResult.class, String.class}},
            {"handleInterceptorHeaderSubstitutionResult",
                    new Class<?>[] {Approov.TokenFetchResult.class, String.class}},
            {"handleInterceptorQueryParamSubstitutionResult",
                    new Class<?>[] {Approov.TokenFetchResult.class, String.class}},
            {"handleInterceptorProcessedRequest", new Class<?>[] {Request.class, ApproovRequestMutations.class}},
            {"handlePinningShouldProcessRequest", new Class<?>[] {Request.class}},
        };
        for (Object[] hook : hooks) {
            Method method = ApproovServiceMutator.class.getMethod((String) hook[0], (Class<?>[]) hook[1]);
            assertArrayEquals((String) hook[0], new Class<?>[] {ApproovException.class}, method.getExceptionTypes());
        }
    }
}
