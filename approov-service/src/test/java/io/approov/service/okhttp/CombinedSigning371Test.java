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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.criticalblue.approovsdk.Approov;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import okhttp3.Request;

/**
 * The signer makes the decisions of another mutator: every decision hook calls the hook of that mutator, and
 * its processed request hook calls the one of that mutator first and then signs the result.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class CombinedSigning371Test {
    private TwoOriginFixture fixture;

    private void start() throws Exception {
        fixture = new TwoOriginFixture(true);
        ApproovService.reset();
        RecordingSdk.install();
        fixture.initialize();
    }

    @After
    public void tearDown() throws Exception {
        if (fixture != null)
            fixture.shutdown();
        ApproovService.reset();
        RecordingSdk.uninstall();
    }

    private Request request(String path) {
        return new Request.Builder().url(fixture.protectedServer.url(path)).build();
    }

    // a SUCCESS token fetch result from the SDK
    private static Approov.TokenFetchResult success() {
        Approov.TokenFetchResult result = Approov.fetchApproovTokenAndWait("https://localhost/data");
        assertSame("control: a SUCCESS result", Approov.TokenFetchStatus.SUCCESS, result.getStatus());
        return result;
    }

    // an app mutator that records every hook call with its arguments and decides the opposite of the interface
    // default for a SUCCESS result, so that a decision the signer made itself is told apart from its own
    static final class RecordingDecisions implements ApproovServiceMutator {
        final List<String> hooks = Collections.synchronizedList(new ArrayList<>());
        final List<Object[]> arguments = Collections.synchronizedList(new ArrayList<>());
        final Request processed = new Request.Builder().url("https://localhost/processed").build();
        Request processedResult = processed;

        private void record(String hook, Object... args) {
            hooks.add(hook);
            arguments.add(args);
        }

        @Override
        public void handlePrecheckResult(Approov.TokenFetchResult approovResults) throws ApproovException {
            record("handlePrecheckResult", approovResults);
            throw new ApproovException("decided by handlePrecheckResult");
        }

        @Override
        public void handleFetchTokenResult(Approov.TokenFetchResult approovResults) throws ApproovException {
            record("handleFetchTokenResult", approovResults);
            throw new ApproovException("decided by handleFetchTokenResult");
        }

        @Override
        public void handleFetchSecureStringResult(Approov.TokenFetchResult approovResults, String operation,
                String key) throws ApproovException {
            record("handleFetchSecureStringResult", approovResults, operation, key);
            throw new ApproovException("decided by handleFetchSecureStringResult");
        }

        @Override
        public void handleFetchCustomJWTResult(Approov.TokenFetchResult approovResults) throws ApproovException {
            record("handleFetchCustomJWTResult", approovResults);
            throw new ApproovException("decided by handleFetchCustomJWTResult");
        }

        @Override
        public boolean handleInterceptorShouldProcessRequest(Request request) {
            record("handleInterceptorShouldProcessRequest", request);
            return false;
        }

        @Override
        public boolean handleInterceptorFetchTokenResult(Approov.TokenFetchResult approovResults, String url) {
            record("handleInterceptorFetchTokenResult", approovResults, url);
            return false;
        }

        @Override
        public boolean handleInterceptorHeaderSubstitutionResult(Approov.TokenFetchResult approovResults,
                String header) {
            record("handleInterceptorHeaderSubstitutionResult", approovResults, header);
            return false;
        }

        @Override
        public boolean handleInterceptorQueryParamSubstitutionResult(Approov.TokenFetchResult approovResults,
                String queryKey) {
            record("handleInterceptorQueryParamSubstitutionResult", approovResults, queryKey);
            return false;
        }

        @Override
        public Request handleInterceptorProcessedRequest(Request request, ApproovRequestMutations changes) {
            record("handleInterceptorProcessedRequest", request, changes);
            return processedResult;
        }

        @Override
        public boolean supportsProtectionRefresh() {
            record("supportsProtectionRefresh");
            return false;
        }

        @Override
        public boolean handlePinningShouldProcessRequest(Request request) {
            record("handlePinningShouldProcessRequest", request);
            return false;
        }
    }

    interface ThrowingCall {
        void run() throws ApproovException;
    }

    private static void assertDecidedBy(String hook, ThrowingCall call) {
        try {
            call.run();
            fail(hook + ": the signer decided itself");
        } catch (ApproovException e) {
            assertEquals(hook, "decided by " + hook, e.getMessage());
        }
    }

    @Test
    public void everyHookOfTheMutatorInterfaceIsForwardedExceptTheRefreshSupport() throws Exception {
        start();
        Approov.TokenFetchResult success = success();
        Request request = request("/data");
        RecordingDecisions decisions = new RecordingDecisions();
        ApproovDefaultMessageSigning signer = new ApproovDefaultMessageSigning(decisions);
        Set<String> hooks = new TreeSet<>();
        for (Method hook : ApproovServiceMutator.class.getDeclaredMethods()) {
            if (!hook.isDefault() || hook.getName().equals("supportsProtectionRefresh"))
                continue;
            hooks.add(hook.getName());
            Class<?>[] types = hook.getParameterTypes();
            Object[] args = new Object[types.length];
            for (int i = 0; i < types.length; i++) {
                if (types[i] == Request.class)
                    args[i] = request;
                else if (types[i] == Approov.TokenFetchResult.class)
                    args[i] = success;
                else if (types[i] == String.class)
                    args[i] = "argument-" + i;
                else if (types[i] == ApproovRequestMutations.class)
                    args[i] = new ApproovRequestMutations();
                else
                    fail(hook.getName() + ": a parameter type the test does not know: " + types[i]);
            }
            decisions.hooks.clear();
            decisions.arguments.clear();
            try {
                signer.getClass().getMethod(hook.getName(), types).invoke(signer, args);
            } catch (InvocationTargetException e) {
                assertTrue(hook.getName() + ": " + e.getCause(), e.getCause() instanceof ApproovException);
            }
            assertEquals(hook.getName() + " is forwarded", Collections.singletonList(hook.getName()),
                    decisions.hooks);
            assertTrue(hook.getName() + " passes its arguments", Arrays.equals(args, decisions.arguments.get(0)));
        }
        assertTrue("the hooks found: " + hooks, hooks.size() >= 10);
    }

    @Test
    public void eachDecisionHookIsTheDecisionsOwn() throws Exception {
        start();
        Approov.TokenFetchResult success = success();
        Request request = request("/data");
        RecordingDecisions decisions = new RecordingDecisions();
        ApproovDefaultMessageSigning signer = new ApproovDefaultMessageSigning(decisions);

        // the interface default on SUCCESS is true for each of these, or no exception
        assertFalse(signer.handleInterceptorShouldProcessRequest(request));
        assertFalse(signer.handleInterceptorFetchTokenResult(success, "https://localhost/data"));
        assertFalse(signer.handleInterceptorHeaderSubstitutionResult(success, "X-Api-Key"));
        assertFalse(signer.handleInterceptorQueryParamSubstitutionResult(success, "key"));
        assertFalse(signer.handlePinningShouldProcessRequest(request));
        assertDecidedBy("handlePrecheckResult", () -> signer.handlePrecheckResult(success));
        assertDecidedBy("handleFetchTokenResult", () -> signer.handleFetchTokenResult(success));
        assertDecidedBy("handleFetchSecureStringResult",
                () -> signer.handleFetchSecureStringResult(success, "lookup", "the-key"));
        assertDecidedBy("handleFetchCustomJWTResult", () -> signer.handleFetchCustomJWTResult(success));
    }

    @Test
    public void theProcessedRequestHookOfTheDecisionsRunsFirstAndItsNullResultPropagates() throws Exception {
        start();
        Request request = request("/data");
        ApproovRequestMutations changes = new ApproovRequestMutations();
        RecordingDecisions decisions = new RecordingDecisions();
        ApproovDefaultMessageSigning signer = new ApproovDefaultMessageSigning(decisions);
        // a request without a token header is not signed, so the processed request is the decisions' own
        assertSame(decisions.processed, signer.handleInterceptorProcessedRequest(request, changes));
        assertSame(request, decisions.arguments.get(0)[0]);
        decisions.processedResult = null;
        assertNull(signer.handleInterceptorProcessedRequest(request, changes));
        assertEquals(Arrays.asList("handleInterceptorProcessedRequest", "handleInterceptorProcessedRequest"),
                decisions.hooks);
    }

    @Test
    public void theSignerSupportsAProtectionRefreshWhateverTheDecisions() {
        RecordingDecisions decisions = new RecordingDecisions();
        assertFalse("control: the app's mutator does not support a refresh", decisions.supportsProtectionRefresh());
        decisions.hooks.clear();
        assertTrue(new ApproovDefaultMessageSigning(decisions).supportsProtectionRefresh());
        assertEquals("the signer answers itself", Collections.emptyList(), decisions.hooks);
    }

    @Test
    public void theNoArgumentSignerStillMakesCloseFailureDecisions() throws Exception {
        start();
        ApproovDefaultMessageSigning plain = new ApproovDefaultMessageSigning();
        Request request = request("/data");
        Approov.TokenFetchResult success = success();
        assertTrue(plain.handleInterceptorShouldProcessRequest(request));
        assertTrue(plain.handlePinningShouldProcessRequest(request));
        assertTrue(plain.handleInterceptorFetchTokenResult(success, "https://localhost/data"));
        plain.handlePrecheckResult(success);
        assertEquals("ApproovDefaultMessageSigning", plain.toString());
    }
}
