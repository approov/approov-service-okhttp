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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.util.Log;

import com.criticalblue.approovsdk.Approov;
import com.criticalblue.minisdk.testing.AttesterProxyController;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import java.io.IOException;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import okhttp3.Request;
import okhttp3.mockwebserver.MockResponse;

/**
 * SPECIFICATION 1.6.1: a RuntimeException thrown by the app's own service mutator
 * never escapes the request path as such. The layer converts it to an
 * ApproovException, an IOException, with the original as its cause, logs it at
 * error level naming the hook, and the request fails through OkHttp's normal
 * error channel: execute() throws it and an enqueued call receives it in
 * onFailure, with nothing rethrown on the dispatcher thread and nothing sent. An
 * IOException a hook throws is the app's opt-in abort and passes through
 * unchanged (SPECIFICATION 1.6).
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class MutatorRuntimeException380Test {
    private LocalHttpsFixture fixture;
    private RequestPathProbe probe;

    /**
     * A mutator with the default decisions that throws from one chosen hook, and
     * supports protection refresh so that the freshness hooks are reached.
     */
    static final class ThrowingMutator implements ApproovServiceMutator {
        final String hook;
        final Supplier<? extends Exception> failure;
        volatile Exception lastThrown;

        ThrowingMutator(String hook, Supplier<? extends Exception> failure) {
            this.hook = hook;
            this.failure = failure;
        }

        private void trip(String name) throws IOException {
            if (!name.equals(hook))
                return;
            Exception e = failure.get();
            lastThrown = e;
            if (e instanceof IOException)
                throw (IOException) e;
            throw (RuntimeException) e;
        }

        private void tripUnchecked(String name) {
            try {
                trip(name);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }

        private void tripDirect(String name) throws ApproovException {
            try {
                trip(name);
            } catch (ApproovException e) {
                throw e;
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }

        @Override
        public void handlePrecheckResult(Approov.TokenFetchResult approovResults) throws ApproovException {
            tripDirect("handlePrecheckResult");
            ApproovServiceMutator.super.handlePrecheckResult(approovResults);
        }

        @Override
        public void handleFetchTokenResult(Approov.TokenFetchResult approovResults) throws ApproovException {
            tripDirect("handleFetchTokenResult");
            ApproovServiceMutator.super.handleFetchTokenResult(approovResults);
        }

        @Override
        public void handleFetchSecureStringResult(Approov.TokenFetchResult approovResults, String operation,
                String key) throws ApproovException {
            tripDirect("handleFetchSecureStringResult");
            ApproovServiceMutator.super.handleFetchSecureStringResult(approovResults, operation, key);
        }

        @Override
        public void handleFetchCustomJWTResult(Approov.TokenFetchResult approovResults) throws ApproovException {
            tripDirect("handleFetchCustomJWTResult");
            ApproovServiceMutator.super.handleFetchCustomJWTResult(approovResults);
        }

        @Override
        public boolean handleInterceptorShouldProcessRequest(Request request) throws IOException {
            trip("handleInterceptorShouldProcessRequest");
            return ApproovServiceMutator.super.handleInterceptorShouldProcessRequest(request);
        }

        @Override
        public boolean handleInterceptorFetchTokenResult(Approov.TokenFetchResult approovResults, String url)
                throws IOException {
            trip("handleInterceptorFetchTokenResult");
            return ApproovServiceMutator.super.handleInterceptorFetchTokenResult(approovResults, url);
        }

        @Override
        public boolean handleInterceptorHeaderSubstitutionResult(Approov.TokenFetchResult approovResults,
                String header) throws IOException {
            trip("handleInterceptorHeaderSubstitutionResult");
            return ApproovServiceMutator.super.handleInterceptorHeaderSubstitutionResult(approovResults, header);
        }

        @Override
        public boolean handleInterceptorQueryParamSubstitutionResult(Approov.TokenFetchResult approovResults,
                String queryKey) throws IOException {
            trip("handleInterceptorQueryParamSubstitutionResult");
            return ApproovServiceMutator.super.handleInterceptorQueryParamSubstitutionResult(approovResults,
                    queryKey);
        }

        @Override
        public Request handleInterceptorProcessedRequest(Request request, ApproovRequestMutations changes)
                throws IOException {
            trip("handleInterceptorProcessedRequest");
            return ApproovServiceMutator.super.handleInterceptorProcessedRequest(request, changes);
        }

        @Override
        public boolean supportsProtectionRefresh() {
            tripUnchecked("supportsProtectionRefresh");
            return true;
        }

        @Override
        public boolean handlePinningShouldProcessRequest(Request request) throws IOException {
            trip("handlePinningShouldProcessRequest");
            return ApproovServiceMutator.super.handlePinningShouldProcessRequest(request);
        }

        @Override
        public String toString() {
            return "ThrowingMutator(" + hook + ")";
        }
    }

    private static final List<Supplier<RuntimeException>> FAILURES = new ArrayList<>();

    static {
        FAILURES.add(() -> new NullPointerException("app null pointer"));
        FAILURES.add(() -> new IllegalStateException("app illegal state"));
    }

    // every hook on the request path, with the request that reaches it and the
    // number of requests that reach the server before it is called
    private static final String[] REQUEST_PATH_HOOKS = {
        "handleInterceptorShouldProcessRequest",
        "handleInterceptorFetchTokenResult",
        "handleInterceptorHeaderSubstitutionResult",
        "handleInterceptorQueryParamSubstitutionResult",
        "handleInterceptorProcessedRequest",
        "handlePinningShouldProcessRequest",
        "supportsProtectionRefresh",
    };

    @Before
    public void setUp() throws Exception {
        fixture = new LocalHttpsFixture(false);
        AttesterProxyController.loadScenarioJson("{\"activeCase\": \"local\", \"cases\": {\"local\": {"
                + "\"protectedDomains\": [\"localhost\"],"
                + "\"pins\": {\"public-key-sha256\": {\"localhost\": []}},"
                + "\"initialSecureStrings\": {\"the-key\": \"the-secret\"}}}}");
        ApproovService.reset();
        ApproovService.initialize(fixture.context, LocalHttpsFixture.CONFIG, "reinit-mutator-runtime-exception");
        ApproovService.setOkHttpClientBuilder(fixture.trustingBuilder());
        ApproovService.addSubstitutionHeader("X-Api-Key", null);
        ApproovService.addSubstitutionQueryParam("key");
        probe = new RequestPathProbe(fixture);
    }

    @After
    public void tearDown() throws Exception {
        probe.close();
        fixture.shutdown();
        ApproovService.reset();
    }

    private Request requestFor(String hook) {
        switch (hook) {
            case "handleInterceptorHeaderSubstitutionResult":
                return new Request.Builder().url(fixture.server.url("/p")).header("X-Api-Key", "the-key").build();
            case "handleInterceptorQueryParamSubstitutionResult":
                return new Request.Builder().url(fixture.server.url("/p?key=the-key")).build();
            case "supportsProtectionRefresh":
                // the redirect followup is reprotected at the network layer, which asks
                // the mutator whether its callback may be invoked again
                fixture.server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "/next"));
                return new Request.Builder().url(fixture.server.url("/start")).build();
            default:
                return new Request.Builder().url(fixture.server.url("/p")).build();
        }
    }

    private static int requestsBeforeHook(String hook) {
        return "supportsProtectionRefresh".equals(hook) ? 1 : 0;
    }

    private static boolean loggedErrorNaming(String hook) {
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            if ((item.type == Log.ERROR) && (item.msg != null) && item.msg.contains(hook))
                return true;
        }
        return false;
    }

    @Test
    public void runtimeExceptionFromEveryRequestPathHookIsAnApproovExceptionWithItsCause() throws Exception {
        for (String hook : REQUEST_PATH_HOOKS) {
            for (Supplier<RuntimeException> failure : FAILURES) {
                for (boolean enqueued : new boolean[] {true, false}) {
                    ThrowingMutator mutator = new ThrowingMutator(hook, failure);
                    ApproovService.setServiceMutator(mutator);
                    ShadowLog.reset();
                    int before = fixture.server.getRequestCount();
                    RequestPathProbe.Outcome outcome = probe.run(requestFor(hook), enqueued);
                    String what = hook + " " + (enqueued ? "enqueue" : "execute") + " " + mutator.lastThrown;
                    assertNotNull(what + ": the hook was never reached: " + outcome, mutator.lastThrown);
                    ApproovException e = RequestPathProbe.assertFailure(what, outcome, ApproovException.class);
                    assertSame(what + ": the cause is the hook's exception", mutator.lastThrown, e.getCause());
                    assertTrue(what + ": the message names the hook: " + e.getMessage(),
                            e.getMessage().contains(hook));
                    assertTrue(what + ": an error naming the hook is logged", loggedErrorNaming(hook));
                    assertEquals(what + ": requests sent", requestsBeforeHook(hook),
                            fixture.server.getRequestCount() - before);
                    for (int i = 0; i < requestsBeforeHook(hook); i++)
                        fixture.server.takeRequest(1, TimeUnit.SECONDS);
                }
            }
        }
    }

    @Test
    public void ioExceptionFromARequestPathHookPassesThroughUnchanged() throws Exception {
        for (String hook : REQUEST_PATH_HOOKS) {
            if ("supportsProtectionRefresh".equals(hook))
                continue; // declares no exception
            for (boolean enqueued : new boolean[] {true, false}) {
                ThrowingMutator mutator = new ThrowingMutator(hook, () -> new ConnectException("app abort"));
                ApproovService.setServiceMutator(mutator);
                RequestPathProbe.Outcome outcome = probe.run(requestFor(hook), enqueued);
                String what = hook + " " + (enqueued ? "enqueue" : "execute");
                ConnectException e = RequestPathProbe.assertFailure(what, outcome, ConnectException.class);
                assertSame(what, mutator.lastThrown, e);
            }
        }
        assertEquals(0, fixture.server.getRequestCount());
    }

    @Test
    public void processedRequestCallbackReturningNullFailsTheRequest() throws Exception {
        ApproovService.setServiceMutator(new ApproovServiceMutator() {
            @Override
            public Request handleInterceptorProcessedRequest(Request request, ApproovRequestMutations changes) {
                return null;
            }
        });
        for (boolean enqueued : new boolean[] {true, false}) {
            ApproovException e = RequestPathProbe.assertFailure("null processed request",
                    probe.run(requestFor("handleInterceptorProcessedRequest"), enqueued), ApproovException.class);
            assertTrue(e.getMessage(), e.getMessage().contains("handleInterceptorProcessedRequest"));
        }
        assertEquals(0, fixture.server.getRequestCount());
    }

    private interface DirectCall {
        void call() throws ApproovException;
    }

    @Test
    public void runtimeExceptionFromADirectMethodHookIsAnApproovExceptionWithItsCause() throws Exception {
        Object[][] cases = {
            {"handleFetchTokenResult", (DirectCall) () -> ApproovService.fetchToken(fixture.server.url("/p").toString())},
            {"handleFetchSecureStringResult", (DirectCall) () -> ApproovService.fetchSecureString("the-key", null)},
            {"handleFetchCustomJWTResult", (DirectCall) () -> ApproovService.fetchCustomJWT("{}")},
            {"handlePrecheckResult", (DirectCall) ApproovService::precheck},
        };
        for (Object[] c : cases) {
            String hook = (String) c[0];
            for (Supplier<RuntimeException> failure : FAILURES) {
                ThrowingMutator mutator = new ThrowingMutator(hook, failure);
                ApproovService.setServiceMutator(mutator);
                try {
                    ((DirectCall) c[1]).call();
                    fail(hook + " must throw");
                } catch (ApproovException e) {
                    assertSame(hook, mutator.lastThrown, e.getCause());
                    assertTrue(hook + ": " + e.getMessage(), e.getMessage().contains(hook));
                } catch (RuntimeException e) {
                    throw new AssertionError(hook + " leaked " + e, e);
                }
            }
        }
    }
}
