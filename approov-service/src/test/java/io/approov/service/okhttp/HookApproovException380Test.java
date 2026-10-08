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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import okhttp3.Request;

/**
 * An Approov-typed exception (an
 * ApproovException or a subclass) that an app's service mutator hook throws, one
 * it built or one it rethrows from a direct method such as fetchToken, is the
 * hook's failure: the request fails with the layer's hook failure, an
 * ApproovException (an IOException) naming the hook with the original as its
 * cause, logged at error level. Only the exceptions the standard CLOSE_FAILURE
 * token decisions raise keep their Approov form, whether a custom mutator
 * inherits those decisions or calls them; the standard substitution decisions
 * raise none (decided 2026-10-07).
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class HookApproovException380Test {
    private LocalHttpsFixture fixture;
    private RequestPathProbe probe;

    // the request path hooks that may throw a checked exception
    private static final String[] HOOKS = {
        "handleInterceptorShouldProcessRequest",
        "handleInterceptorFetchTokenResult",
        "handleInterceptorHeaderSubstitutionResult",
        "handleInterceptorQueryParamSubstitutionResult",
        "handleInterceptorProcessedRequest",
        "handlePinningShouldProcessRequest",
    };

    private static final List<Supplier<ApproovException>> APPROOV_EXCEPTIONS = new ArrayList<>();

    static {
        APPROOV_EXCEPTIONS.add(() -> new ApproovException("app policy"));
        APPROOV_EXCEPTIONS.add(() -> new ApproovFetchStatusException(Approov.TokenFetchStatus.REJECTED,
                "app policy"));
        APPROOV_EXCEPTIONS.add(() -> new ApproovNetworkException(Approov.TokenFetchStatus.NO_NETWORK,
                "app policy"));
        APPROOV_EXCEPTIONS.add(() -> new ApproovRejectionException("app policy", "ARC1", "root"));
    }

    @Before
    public void setUp() throws Exception {
        fixture = new LocalHttpsFixture(false);
        AttesterProxyController.loadScenarioJson("{\"activeCase\": \"local\", \"cases\": {\"local\": {"
                + "\"protectedDomains\": [\"localhost\"],"
                + "\"pins\": {\"public-key-sha256\": {\"localhost\": []}},"
                + "\"initialSecureStrings\": {\"the-key\": \"the-secret\"}}}}");
        ApproovService.reset();
        ApproovService.initialize(fixture.context, LocalHttpsFixture.CONFIG, "reinit-hook-approov-exception");
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
            default:
                return new Request.Builder().url(fixture.server.url("/p")).build();
        }
    }

    // a request reaching the token decision and both substitution decisions
    private Request everyDecision() {
        return new Request.Builder().url(fixture.server.url("/p?key=the-key")).header("X-Api-Key", "the-key")
                .build();
    }

    private static boolean loggedErrorNaming(String hook) {
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            if ((item.type == Log.ERROR) && (item.msg != null) && item.msg.contains(hook))
                return true;
        }
        return false;
    }

    private static void assertHookFailure(String what, RequestPathProbe.Outcome outcome, String hook,
            Throwable original) {
        ApproovException e = RequestPathProbe.assertFailure(what, outcome, ApproovException.class);
        assertSame(what + ": the hook failure, not the Approov type it threw: " + e, ApproovException.class,
                e.getClass());
        assertSame(what + ": the cause is the hook's exception", original, e.getCause());
        assertTrue(what + ": the message names the hook: " + e.getMessage(), e.getMessage().contains(hook));
        assertTrue(what + ": an error naming the hook is logged", loggedErrorNaming(hook));
    }

    @Test
    public void anApproovExceptionAHookThrowsIsTheHooksFailure() throws Exception {
        for (String hook : HOOKS) {
            for (Supplier<ApproovException> failure : APPROOV_EXCEPTIONS) {
                for (boolean enqueued : new boolean[] {true, false}) {
                    MutatorRuntimeException380Test.ThrowingMutator mutator =
                            new MutatorRuntimeException380Test.ThrowingMutator(hook, failure);
                    ApproovService.setServiceMutator(mutator);
                    ShadowLog.reset();
                    RequestPathProbe.Outcome outcome = probe.run(requestFor(hook), enqueued);
                    String what = hook + " " + (enqueued ? "enqueue" : "execute") + " " + mutator.lastThrown;
                    assertNotNull(what + ": the hook was never reached: " + outcome, mutator.lastThrown);
                    assertHookFailure(what, outcome, hook, mutator.lastThrown);
                }
            }
        }
        assertEquals("nothing sent", 0, fixture.server.getRequestCount());
    }

    @Test
    public void anApproovExceptionRethrownFromADirectMethodIsTheHooksFailure() throws Exception {
        // the app's processed request callback calls fetchToken for a URL the SDK
        // reports as BAD_URL and lets its ApproovFetchStatusException escape
        List<Throwable> thrown = new ArrayList<>();
        ApproovService.setServiceMutator(new ApproovServiceMutator() {
            @Override
            public Request handleInterceptorProcessedRequest(Request request, ApproovRequestMutations changes)
                    throws IOException {
                try {
                    ApproovService.fetchToken("http://localhost/not-https");
                } catch (ApproovException e) {
                    thrown.add(e);
                    throw e;
                }
                return request;
            }
        });
        for (boolean enqueued : new boolean[] {true, false}) {
            thrown.clear();
            ShadowLog.reset();
            RequestPathProbe.Outcome outcome = probe.run(requestFor("handleInterceptorProcessedRequest"), enqueued);
            assertEquals(1, thrown.size());
            assertTrue("the direct method threw " + thrown.get(0), thrown.get(0) instanceof ApproovFetchStatusException);
            assertHookFailure("rethrown fetchToken failure", outcome, "handleInterceptorProcessedRequest",
                    thrown.get(0));
        }
        assertEquals("nothing sent", 0, fixture.server.getRequestCount());
    }

    // a custom mutator that inherits every decision
    static final class InheritingMutator implements ApproovServiceMutator {
        @Override
        public Request handleInterceptorProcessedRequest(Request request, ApproovRequestMutations changes) {
            return request;
        }
    }

    // a custom mutator that calls the CLOSE_FAILURE decisions itself, rethrowing
    // the token decision it caught unchanged
    static final class DelegatingMutator implements ApproovServiceMutator {
        @Override
        public boolean handleInterceptorFetchTokenResult(Approov.TokenFetchResult approovResults, String url)
                throws IOException {
            try {
                return ApproovServiceMutator.CLOSE_FAILURE.handleInterceptorFetchTokenResult(approovResults, url);
            } catch (ApproovException e) {
                throw e;
            }
        }

        @Override
        public boolean handleInterceptorHeaderSubstitutionResult(Approov.TokenFetchResult approovResults,
                String header) throws IOException {
            return ApproovServiceMutator.CLOSE_FAILURE.handleInterceptorHeaderSubstitutionResult(approovResults,
                    header);
        }

        @Override
        public boolean handleInterceptorQueryParamSubstitutionResult(Approov.TokenFetchResult approovResults,
                String queryKey) throws IOException {
            return ApproovServiceMutator.closeFailureSubstitution(approovResults, "query " + queryKey);
        }
    }

    private void assertDecision(String what, Class<? extends ApproovFetchStatusException> type,
            Approov.TokenFetchStatus status) throws Exception {
        for (boolean enqueued : new boolean[] {true, false}) {
            String operation = (status == Approov.TokenFetchStatus.INTERNAL_ERROR) ? "fetchSecureString"
                    : "fetchApproovToken";
            AttesterProxyController.setNextAttestationDirectiveJson("{\"operation\": \"" + operation
                    + "\", \"response\": {\"status\": \"" + status.name() + "\"}}");
            RequestPathProbe.Outcome outcome = probe.run(everyDecision(), enqueued);
            ApproovFetchStatusException e = RequestPathProbe.assertFailure(what, outcome, type);
            assertSame(what + ": " + e, type, e.getClass());
            assertSame(what, status, e.getTokenFetchStatus());
        }
    }

    @Test
    public void control_theCloseFailureDecisionsKeepTheirTypeInACustomMutator() throws Exception {
        ApproovServiceMutator[] mutators = {
            ApproovServiceMutator.CLOSE_FAILURE, new InheritingMutator(), new DelegatingMutator()
        };
        for (ApproovServiceMutator mutator : mutators) {
            ApproovService.setServiceMutator(mutator);
            String what = mutator.getClass().getSimpleName();
            assertDecision(what + " token REJECTED", ApproovFetchStatusException.class,
                    Approov.TokenFetchStatus.REJECTED);
            assertDecision(what + " token NO_NETWORK", ApproovNetworkException.class,
                    Approov.TokenFetchStatus.NO_NETWORK);
        }
        assertEquals("nothing sent", 0, fixture.server.getRequestCount());
    }

    @Test
    public void theStandardSubstitutionDecisionsNeverAbortInACustomMutator() throws Exception {
        // decided 2026-10-07: a standard substitution decision never throws, so a
        // custom mutator that inherits or calls it proceeds with the placeholder
        ApproovServiceMutator[] mutators = {
            ApproovServiceMutator.CLOSE_FAILURE, new InheritingMutator(), new DelegatingMutator()
        };
        int sent = 0;
        for (ApproovServiceMutator mutator : mutators) {
            ApproovService.setServiceMutator(mutator);
            String what = mutator.getClass().getSimpleName();
            for (boolean enqueued : new boolean[] {true, false}) {
                for (String status : new String[] {"INTERNAL_ERROR", "NO_NETWORK"}) {
                    AttesterProxyController.setNextAttestationDirectiveJson("{\"operation\": \"fetchSecureString\","
                            + " \"response\": {\"status\": \"" + status + "\"}}");
                    fixture.server.enqueue(new okhttp3.mockwebserver.MockResponse().setBody("ok"));
                    RequestPathProbe.assertOk(what + " secure string " + status, probe.run(everyDecision(), enqueued));
                    sent++;
                }
            }
        }
        assertEquals("every request sent", sent, fixture.server.getRequestCount());
    }
}
