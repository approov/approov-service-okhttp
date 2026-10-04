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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.criticalblue.approovsdk.Approov;
import com.criticalblue.minisdk.testing.AttesterProxyController;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Request;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * A RuntimeException thrown by the Approov SDK never escapes the request path or
 * a direct method as a RuntimeException. On the request path (the token,
 * freshness and pinning interceptors, binding, substitutions and the pin
 * rebuild) it surfaces as an ApproovException, an IOException, with the SDK's
 * exception as its cause: execute() throws it and an enqueued call receives it in
 * onFailure, with nothing rethrown on the dispatcher thread (SPECIFICATION 1.6).
 * A failure of a message signing SDK call is inside the signing fail-open
 * (SPECIFICATION 3.5): the request proceeds without that signature. The direct
 * methods throw an ApproovException with the SDK's exception as its cause
 * (SPECIFICATION 1.8).
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class SdkRuntimeException380Test {
    private static final Pattern MEMBER = Pattern.compile("(?:^|,)\\s*([a-z]+)=");

    private LocalHttpsFixture fixture;
    private RequestPathProbe probe;
    private FailingSdkFacade sdk;

    /**
     * Delegates to the mini-SDK, recording every call, but throws a chosen
     * RuntimeException (or returns null) from one chosen method, from its n-th call
     * after being armed.
     */
    static final class FailingSdkFacade extends RecordingSdkFacade {
        private volatile String method;
        private volatile Supplier<RuntimeException> failure;
        private volatile int fromCall;
        private final AtomicInteger callsSinceArmed = new AtomicInteger();
        volatile RuntimeException lastThrown;

        void fail(String method, Supplier<RuntimeException> failure, int fromCall) {
            this.failure = failure;
            this.fromCall = fromCall;
            callsSinceArmed.set(0);
            lastThrown = null;
            this.method = method;
        }

        void returnNull(String method) {
            fail(method, null, 1);
        }

        void clear() {
            method = null;
        }

        // throws if armed for this method, or returns true if it must return null
        private boolean trip(String name) {
            if (!name.equals(method) || (callsSinceArmed.incrementAndGet() < fromCall))
                return false;
            if (failure == null)
                return true;
            RuntimeException e = failure.get();
            lastThrown = e;
            throw e;
        }

        @Override
        public void setDevKey(String devKey) {
            trip("setDevKey");
            super.setDevKey(devKey);
        }

        @Override
        public Approov.TokenFetchResult fetchApproovTokenAndWait(String url) {
            return trip("fetchApproovTokenAndWait") ? null : super.fetchApproovTokenAndWait(url);
        }

        @Override
        public Approov.TokenFetchResult fetchSecureStringAndWait(String key, String newDefinition) {
            return trip("fetchSecureStringAndWait") ? null : super.fetchSecureStringAndWait(key, newDefinition);
        }

        @Override
        public Approov.TokenFetchResult fetchCustomJWTAndWait(String payload) {
            return trip("fetchCustomJWTAndWait") ? null : super.fetchCustomJWTAndWait(payload);
        }

        @Override
        public String getDeviceID() {
            return trip("getDeviceID") ? null : super.getDeviceID();
        }

        @Override
        public void setDataHashInToken(String data) {
            trip("setDataHashInToken");
            super.setDataHashInToken(data);
        }

        @Override
        public String getAccountMessageSignature(String message) {
            return trip("getAccountMessageSignature") ? null : super.getAccountMessageSignature(message);
        }

        @Override
        public String getInstallMessageSignature(String message) {
            return trip("getInstallMessageSignature") ? null : super.getInstallMessageSignature(message);
        }

        @Override
        public Map<String, List<String>> getPins(String pinType) {
            return trip("getPins") ? null : super.getPins(pinType);
        }

        @Override
        public void setInstallAttrsInToken(String attributes) {
            trip("setInstallAttrsInToken");
            super.setInstallAttrsInToken(attributes);
        }

        @Override
        public String fetchConfig() {
            return trip("fetchConfig") ? null : super.fetchConfig();
        }
    }

    // the RuntimeExceptions an SDK call might throw, each made fresh per call
    private static final List<Supplier<RuntimeException>> FAILURES = new ArrayList<>();

    static {
        FAILURES.add(() -> new IllegalStateException("sdk illegal state"));
        FAILURES.add(() -> new IllegalArgumentException("sdk illegal argument"));
        FAILURES.add(() -> new NullPointerException("sdk null pointer"));
    }

    private interface Arrangement {
        Request arrange() throws Exception;
    }

    @Before
    public void setUp() throws Exception {
        fixture = new LocalHttpsFixture(false);
        AttesterProxyController.loadScenarioJson("{\"activeCase\": \"local\", \"cases\": {\"local\": {"
                + "\"protectedDomains\": [\"localhost\"],"
                + "\"pins\": {\"public-key-sha256\": {\"localhost\": []}},"
                + "\"initialSecureStrings\": {\"the-key\": \"the-secret\"}}}}");
        ApproovService.reset();
        sdk = new FailingSdkFacade();
        ApproovService.setSdkFacadeForTesting(sdk);
        ApproovService.initialize(fixture.context, LocalHttpsFixture.CONFIG, "reinit-sdk-runtime-exception");
        ApproovService.setOkHttpClientBuilder(fixture.trustingBuilder());
        probe = new RequestPathProbe(fixture);
    }

    @After
    public void tearDown() throws Exception {
        probe.close();
        fixture.shutdown();
        ApproovService.reset();
    }

    private Request get(String pathAndQuery) {
        return new Request.Builder().url(fixture.server.url(pathAndQuery)).get().build();
    }

    /**
     * For every RuntimeException kind, synchronously and enqueued: arms the facade
     * to throw from the method, runs the arranged request and asserts that the app
     * sees an ApproovException whose cause is exactly what the SDK threw, and that
     * the request never reached the server unless expected.
     */
    private void assertRequestPathFailure(String method, int fromCall, int expectedServerRequests,
            Arrangement arrangement) throws Exception {
        for (Supplier<RuntimeException> failure : FAILURES) {
            for (boolean enqueued : new boolean[] {true, false}) {
                int before = fixture.server.getRequestCount();
                Request request = arrangement.arrange();
                sdk.fail(method, failure, fromCall);
                RequestPathProbe.Outcome outcome = probe.run(request, enqueued);
                sdk.clear();
                String what = method + " " + (enqueued ? "enqueue" : "execute");
                RuntimeException thrown = sdk.lastThrown;
                assertNotNull(what + ": the SDK was never reached: " + outcome, thrown);
                ApproovException e = RequestPathProbe.assertFailure(what + " " + thrown, outcome,
                        ApproovException.class);
                assertSame(what + ": the cause is the SDK's exception", thrown, e.getCause());
                assertEquals(what + ": requests that reached the server", expectedServerRequests,
                        fixture.server.getRequestCount() - before);
                // drain anything the server received so that the next round starts clean
                for (int i = 0; i < expectedServerRequests; i++)
                    fixture.server.takeRequest(1, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    public void tokenFetchFailureIsAnIOException() throws Exception {
        assertRequestPathFailure("fetchApproovTokenAndWait", 1, 0, () -> get("/p?a=b"));
    }

    @Test
    public void headerSubstitutionFailureIsAnIOException() throws Exception {
        ApproovService.addSubstitutionHeader("X-Api-Key", null);
        assertRequestPathFailure("fetchSecureStringAndWait", 1, 0, () -> new Request.Builder()
                .url(fixture.server.url("/p")).header("X-Api-Key", "the-key").build());
    }

    @Test
    public void queryParamSubstitutionFailureIsAnIOException() throws Exception {
        ApproovService.addSubstitutionQueryParam("key");
        assertRequestPathFailure("fetchSecureStringAndWait", 1, 0, () -> get("/p?key=the-key"));
    }

    @Test
    public void bindingFailureIsAnIOException() throws Exception {
        ApproovService.setBindingHeader("Authorization");
        assertRequestPathFailure("setDataHashInToken", 1, 0, () -> new Request.Builder()
                .url(fixture.server.url("/p")).header("Authorization", "Bearer user").build());
    }

    @Test
    public void pinRebuildOnConnectionCheckFailureIsAnIOException() throws Exception {
        // localhost has no pins, so every connection check asks the SDK for them
        assertTrue(ApproovService.getCertificatePinner().getPins().isEmpty());
        assertRequestPathFailure("getPins", 1, 0, () -> get("/p"));
    }

    @Test
    public void configChangeFailureIsAnIOException() throws Exception {
        assertRequestPathFailure("fetchConfig", 1, 0, () -> {
            AttesterProxyController.setNextAttestationDirectiveJson("{\"operation\": \"fetchApproovToken\","
                    + " \"response\": {\"status\": \"SUCCESS\", \"configChanged\": true}}");
            return get("/p");
        });
    }

    @Test
    public void forcedPinRebuildFailureIsAnIOException() throws Exception {
        assertRequestPathFailure("getPins", 1, 0, () -> {
            AttesterProxyController.setNextAttestationDirectiveJson("{\"operation\": \"fetchApproovToken\","
                    + " \"response\": {\"status\": \"SUCCESS\", \"forceApplyPins\": true}}");
            return get("/p");
        });
    }

    @Test
    public void reprotectionOfARedirectFailureIsAnIOException() throws Exception {
        // the redirect followup is reprotected at the network layer by the freshness
        // interceptor, whose token fetch is the second one of the call
        assertRequestPathFailure("fetchApproovTokenAndWait", 2, 1, () -> {
            fixture.server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "/next"));
            return get("/start");
        });
    }

    @Test
    public void staleProtectionRefreshFailureIsAnIOException() throws Exception {
        // a request held after its protection was applied, as by a device doze, is
        // refreshed at the network layer by the freshness interceptor
        for (Supplier<RuntimeException> failure : FAILURES) {
            Request protectedRequest = ApproovTokenInterceptor.applyProtection(get("/p"),
                    ApproovService.getServiceMutator(), null, true);
            assertNotNull(protectedRequest.tag(ApproovRequestFreshness.class));
            // the request is held well beyond the refresh period
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(60));
            sdk.fail("fetchApproovTokenAndWait", failure, 1);
            try {
                new ApproovFreshnessInterceptor().intercept(new ProceedChain(protectedRequest));
                fail("a stale refresh whose token fetch fails must throw");
            } catch (ApproovException e) {
                assertNotNull("the refresh never reached the SDK", sdk.lastThrown);
                assertSame(sdk.lastThrown, e.getCause());
            } catch (RuntimeException e) {
                throw new AssertionError("the stale refresh leaked " + e, e);
            } finally {
                sdk.clear();
            }
        }
    }

    @Test
    public void nullTokenFetchResultIsAnIOException() throws Exception {
        for (boolean enqueued : new boolean[] {true, false}) {
            sdk.returnNull("fetchApproovTokenAndWait");
            RequestPathProbe.Outcome outcome = probe.run(get("/p"), enqueued);
            sdk.clear();
            RequestPathProbe.assertFailure("null token result", outcome, ApproovException.class);
        }
        assertEquals(0, fixture.server.getRequestCount());
    }

    @Test
    public void nullPinsAreAnIOException() throws Exception {
        for (boolean enqueued : new boolean[] {true, false}) {
            sdk.returnNull("getPins");
            RequestPathProbe.Outcome outcome = probe.run(get("/p"), enqueued);
            sdk.clear();
            RequestPathProbe.assertFailure("null pins", outcome, ApproovException.class);
        }
        assertEquals(0, fixture.server.getRequestCount());
    }

    @Test
    public void secureStringThatIsNotAValidHeaderValueIsAnIOExceptionWithoutTheValue() throws Exception {
        // a secure string with a line break cannot be a header value; OkHttp's own
        // message for that quotes the value, so it must not reach the app
        assertEquals("line-one\nsecret-line-two",
                ApproovService.fetchSecureString("bad-key", "line-one\nsecret-line-two"));
        ApproovService.addSubstitutionHeader("X-Api-Key", null);
        for (boolean enqueued : new boolean[] {true, false}) {
            // a response in case the request wrongly reaches the server
            fixture.server.enqueue(new MockResponse().setBody("ok"));
            Request request = new Request.Builder().url(fixture.server.url("/p"))
                    .header("X-Api-Key", "bad-key").build();
            ApproovException e = RequestPathProbe.assertFailure("invalid secure string",
                    probe.run(request, enqueued), ApproovException.class);
            assertTrue(e.getMessage(), e.getMessage().contains("X-Api-Key"));
            for (Throwable t = e; t != null; t = t.getCause())
                assertFalse("the secure string must not appear in " + t,
                        String.valueOf(t.getMessage()).contains("secret-line-two"));
        }
        assertEquals(0, fixture.server.getRequestCount());
    }

    // ==================================================================================
    // message signing: SDK failures are inside the fail-open (SPECIFICATION 3.5)
    // ==================================================================================

    private static List<String> members(String header) {
        List<String> members = new ArrayList<>();
        if (header == null)
            return members;
        Matcher matcher = MEMBER.matcher(header);
        while (matcher.find())
            members.add(matcher.group(1));
        return members;
    }

    private void assertSigningFailOpen(String method, List<String> expectedMembers) throws Exception {
        ApproovService.enableMessageSigning();
        for (Supplier<RuntimeException> failure : FAILURES) {
            for (boolean enqueued : new boolean[] {true, false}) {
                fixture.server.enqueue(new MockResponse().setBody("ok"));
                sdk.fail(method, failure, 1);
                RequestPathProbe.Outcome outcome = probe.run(get("/p"), enqueued);
                sdk.clear();
                String what = method + " " + (enqueued ? "enqueue" : "execute") + " " + sdk.lastThrown;
                assertNotNull(what + ": the SDK was never reached", sdk.lastThrown);
                RequestPathProbe.assertOk(what, outcome);
                RecordedRequest recorded = fixture.server.takeRequest(1, TimeUnit.SECONDS);
                assertEquals(what, expectedMembers, members(recorded.getHeader("Signature")));
                assertEquals(what, expectedMembers, members(recorded.getHeader("Signature-Input")));
            }
        }
    }

    @Test
    public void installSignatureFailureProceedsWithTheAccountSignature() throws Exception {
        List<String> account = new ArrayList<>();
        account.add(ApproovDefaultMessageSigning.SIG_ID_ACCOUNT);
        assertSigningFailOpen("getInstallMessageSignature", account);
    }

    @Test
    public void accountSignatureFailureProceedsWithTheInstallSignature() throws Exception {
        List<String> install = new ArrayList<>();
        install.add(ApproovDefaultMessageSigning.SIG_ID_INSTALL);
        assertSigningFailOpen("getAccountMessageSignature", install);
    }

    @Test
    public void signatureFailureWithOnlyThatSignatureProceedsUnsigned() throws Exception {
        ApproovService.enableMessageSigning(ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()
                .setUseInstallMessageSigning());
        for (Supplier<RuntimeException> failure : FAILURES) {
            for (boolean enqueued : new boolean[] {true, false}) {
                fixture.server.enqueue(new MockResponse().setBody("ok"));
                sdk.fail("getInstallMessageSignature", failure, 1);
                RequestPathProbe.Outcome outcome = probe.run(get("/p"), enqueued);
                sdk.clear();
                RequestPathProbe.assertOk("unsigned " + enqueued, outcome);
                RecordedRequest recorded = fixture.server.takeRequest(1, TimeUnit.SECONDS);
                assertNull(recorded.getHeader("Signature"));
                assertNull(recorded.getHeader("Signature-Input"));
                assertNotNull("the token header is still sent", recorded.getHeader("Approov-Token"));
            }
        }
    }

    @Test
    public void factoryWithNoAlgorithmProceedsUnsigned() throws Exception {
        ApproovDefaultMessageSigning.SignatureParametersFactory factory =
                ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory();
        factory.useInstallMessageSigning = false;
        factory.useAccountMessageSigning = false;
        ApproovService.enableMessageSigning(factory);
        for (boolean enqueued : new boolean[] {true, false}) {
            fixture.server.enqueue(new MockResponse().setBody("ok"));
            RequestPathProbe.assertOk("no algorithm " + enqueued, probe.run(get("/p"), enqueued));
            assertNull(fixture.server.takeRequest(1, TimeUnit.SECONDS).getHeader("Signature"));
        }
    }

    // ==================================================================================
    // direct methods (SPECIFICATION 1.8)
    // ==================================================================================

    private interface DirectCall {
        void call() throws ApproovException;
    }

    private void assertDirectFailure(String name, String method, DirectCall call) {
        for (Supplier<RuntimeException> failure : FAILURES) {
            sdk.fail(method, failure, 1);
            try {
                call.call();
                fail(name + " must throw");
            } catch (ApproovException e) {
                assertNotNull(name + ": the SDK was never reached", sdk.lastThrown);
                assertSame(name + ": the cause is the SDK's exception", sdk.lastThrown, e.getCause());
            } catch (RuntimeException e) {
                throw new AssertionError(name + " leaked " + e, e);
            } finally {
                sdk.clear();
            }
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    public void directMethodsNeverLeakASdkRuntimeException() {
        assertDirectFailure("setDevKey", "setDevKey", () -> ApproovService.setDevKey("dev-key"));
        assertDirectFailure("precheck", "fetchSecureStringAndWait", ApproovService::precheck);
        assertDirectFailure("getDeviceID", "getDeviceID", ApproovService::getDeviceID);
        assertDirectFailure("setDataHashInToken", "setDataHashInToken",
                () -> ApproovService.setDataHashInToken("data"));
        assertDirectFailure("fetchToken", "fetchApproovTokenAndWait",
                () -> ApproovService.fetchToken(fixture.server.url("/p").toString()));
        assertDirectFailure("getMessageSignature", "getAccountMessageSignature",
                () -> ApproovService.getMessageSignature("message"));
        assertDirectFailure("getAccountMessageSignature", "getAccountMessageSignature",
                () -> ApproovService.getAccountMessageSignature("message"));
        assertDirectFailure("getInstallMessageSignature", "getInstallMessageSignature",
                () -> ApproovService.getInstallMessageSignature("message"));
        assertDirectFailure("fetchSecureString", "fetchSecureStringAndWait",
                () -> ApproovService.fetchSecureString("the-key", null));
        assertDirectFailure("fetchCustomJWT", "fetchCustomJWTAndWait",
                () -> ApproovService.fetchCustomJWT("{}"));
        assertDirectFailure("setInstallAttributes", "setInstallAttrsInToken",
                () -> ApproovService.setInstallAttributes("signed-jwt"));
    }

    @Test
    public void directFetchesWithANullResultThrowAnApproovException() {
        String[][] cases = {
            {"fetchToken", "fetchApproovTokenAndWait"},
            {"fetchSecureString", "fetchSecureStringAndWait"},
            {"precheck", "fetchSecureStringAndWait"},
            {"fetchCustomJWT", "fetchCustomJWTAndWait"},
        };
        for (String[] c : cases) {
            sdk.returnNull(c[1]);
            try {
                switch (c[0]) {
                    case "fetchToken": ApproovService.fetchToken(fixture.server.url("/p").toString()); break;
                    case "fetchSecureString": ApproovService.fetchSecureString("the-key", null); break;
                    case "precheck": ApproovService.precheck(); break;
                    default: ApproovService.fetchCustomJWT("{}"); break;
                }
                fail(c[0] + " must throw on a null result");
            } catch (ApproovException expected) {
                assertEquals(c[0], "", ApproovService.getLastARC());
            } catch (RuntimeException e) {
                throw new AssertionError(c[0] + " leaked " + e, e);
            } finally {
                sdk.clear();
            }
        }
    }

    // a network chain that proceeds without a connection, for driving the freshness
    // interceptor directly
    private static final class ProceedChain implements okhttp3.Interceptor.Chain {
        private final Request request;

        ProceedChain(Request request) {
            this.request = request;
        }

        @Override
        public Request request() {
            return request;
        }

        @Override
        public okhttp3.Response proceed(Request request) {
            return new okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200).message("OK").build();
        }

        @Override
        public okhttp3.Connection connection() {
            return null;
        }

        @Override
        public okhttp3.Call call() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int connectTimeoutMillis() {
            return 0;
        }

        @Override
        public okhttp3.Interceptor.Chain withConnectTimeout(int timeout, TimeUnit unit) {
            return this;
        }

        @Override
        public int readTimeoutMillis() {
            return 0;
        }

        @Override
        public okhttp3.Interceptor.Chain withReadTimeout(int timeout, TimeUnit unit) {
            return this;
        }

        @Override
        public int writeTimeoutMillis() {
            return 0;
        }

        @Override
        public okhttp3.Interceptor.Chain withWriteTimeout(int timeout, TimeUnit unit) {
            return this;
        }
    }
}
