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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.criticalblue.approovsdk.Approov;
import com.criticalblue.minisdk.testing.AttesterProxyController;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import okhttp3.Headers;
import okhttp3.Request;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * Message signing under the decisions of any service mutator, with the ApproovDefaultMessageSigning(decisions)
 * constructor. Every decision hook of the signer is that of the given mutator, and its processed request hook
 * calls the given mutator's and then signs the result, so that a change the mutator makes is covered by the
 * signature. With ALWAYS_PROCEED a token fetch failure to an Approov API domain proceeds signed over its empty
 * token header, and a same-origin redirect is signed again. With CLOSE_FAILURE the signer behaves as the
 * no-argument signer, whose behaviour is unchanged, and a standard decision made through the given mutator
 * keeps its form while an exception of the app's own mutator is the hook's failure.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class SigningWithDecisions371Test {
    private TwoOriginFixture fixture;
    private RequestPathProbe probe;

    private void start(ApproovServiceMutator mutator) throws Exception {
        fixture = new TwoOriginFixture(true);
        ApproovService.reset();
        RecordingSdk.install();
        fixture.initialize();
        ApproovService.setServiceMutator(mutator);
        ApproovService.setOkHttpClientBuilder(fixture.trustingBuilder());
        probe = new RequestPathProbe(null);
    }

    @After
    public void tearDown() throws Exception {
        if (probe != null)
            probe.close();
        if (fixture != null)
            fixture.shutdown();
        ApproovService.reset();
        RecordingSdk.uninstall();
    }

    // a signer with the default factory that makes the decisions of the given mutator
    private static ApproovDefaultMessageSigning signing(ApproovServiceMutator decisions) {
        return new ApproovDefaultMessageSigning(decisions)
                .setDefaultFactory(ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory());
    }

    // the no-argument signer with the default factory
    private static ApproovDefaultMessageSigning noArgumentSigning() {
        return new ApproovDefaultMessageSigning()
                .setDefaultFactory(ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory());
    }

    private Request request(String path) {
        return new Request.Builder().url(fixture.protectedServer.url(path)).build();
    }

    // the next token fetch reports the status, with a trace ID named after it
    private static void nextTokenFetch(String status) {
        AttesterProxyController.setNextAttestationDirectiveJson(
                "{\"operation\": \"fetchApproovToken\", \"response\": {\"status\": \"" + status + "\","
                        + " \"traceID\": \"trace-" + status + "\"}}");
    }

    private RecordedRequest sendAndTake(Request request) throws Exception {
        fixture.protectedServer.enqueue(new MockResponse().setBody("ok"));
        RequestPathProbe.assertOk(request.url().encodedPath(), probe.execute(request));
        return fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
    }

    // the request carries the install signature, covering the token header
    private static void assertInstallSigned(String what, RecordedRequest recorded) {
        assertNotNull(what + ": not delivered", recorded);
        String input = recorded.getHeader("Signature-Input");
        String signature = recorded.getHeader("Signature");
        assertNotNull(what + ": Signature-Input", input);
        assertNotNull(what + ": Signature", signature);
        assertTrue(what + ": the install member: " + input, input.startsWith("install=("));
        assertTrue(what + ": the install member: " + signature, signature.startsWith("install=:"));
        assertTrue(what + ": the token header is covered: " + input, input.contains("\"approov-token\""));
    }

    // ==================================================================================
    // the constructor
    // ==================================================================================

    @Test
    public void nullDecisionsAreRejected() {
        try {
            new ApproovDefaultMessageSigning(null);
            fail("null decisions must be rejected");
        } catch (IllegalArgumentException e) {
            assertTrue("the message names the argument: " + e.getMessage(), e.getMessage().contains("decisions"));
        }
    }

    @Test
    public void toStringNamesTheDecisions() {
        assertEquals("ApproovDefaultMessageSigning(ApproovServiceMutator.ALWAYS_PROCEED)",
                new ApproovDefaultMessageSigning(ApproovServiceMutator.ALWAYS_PROCEED).toString());
        assertEquals("ApproovDefaultMessageSigning(ApproovServiceMutator.CLOSE_FAILURE)",
                new ApproovDefaultMessageSigning(ApproovServiceMutator.CLOSE_FAILURE).toString());
        assertEquals("ApproovDefaultMessageSigning(AppDecisions)",
                new ApproovDefaultMessageSigning(new AddsAuthorization()).toString());
    }

    @Test
    public void theSignerSupportsAProtectionRefreshWhateverTheDecisions() {
        RecordingDecisions decisions = new RecordingDecisions();
        assertFalse("control: the app's mutator does not support a refresh", decisions.supportsProtectionRefresh());
        decisions.hooks.clear();
        assertTrue(new ApproovDefaultMessageSigning(decisions).supportsProtectionRefresh());
        assertTrue(new ApproovDefaultMessageSigning(ApproovServiceMutator.ALWAYS_PROCEED).supportsProtectionRefresh());
        assertEquals("the signer answers itself", Collections.emptyList(), decisions.hooks);
    }

    // ==================================================================================
    // ALWAYS_PROCEED plus signing
    // ==================================================================================

    @Test
    public void alwaysProceedSignsASuccessfulRequest() throws Exception {
        start(signing(ApproovServiceMutator.ALWAYS_PROCEED));
        RecordedRequest recorded = sendAndTake(request("/data"));
        assertEquals("a token: " + recorded.getHeader("Approov-Token"), 3,
                recorded.getHeader("Approov-Token").split("\\.").length);
        assertInstallSigned("SUCCESS", recorded);
        verifyInstallSignature("SUCCESS", recorded);
    }

    @Test
    public void alwaysProceedLetsANoNetworkFetchToAnApproovApiDomainProceedSignedWithAnEmptyTokenHeader()
            throws Exception {
        // localhost is a key of the fixture's pin set, so it is an Approov API domain
        start(signing(ApproovServiceMutator.ALWAYS_PROCEED));
        nextTokenFetch("NO_NETWORK");
        RecordedRequest recorded = sendAndTake(request("/data"));
        assertEquals("an empty token header", "", recorded.getHeader("Approov-Token"));
        assertEquals("the trace ID of the failure", "trace-NO_NETWORK", recorded.getHeader("Approov-TraceID"));
        assertInstallSigned("NO_NETWORK", recorded);
        verifyInstallSignature("NO_NETWORK", recorded);
    }

    @Test
    public void control_theNoArgumentSignerAbortsTheSameNoNetworkFetch() throws Exception {
        start(noArgumentSigning());
        nextTokenFetch("NO_NETWORK");
        ApproovNetworkException e = RequestPathProbe.assertFailure("NO_NETWORK",
                probe.execute(request("/data")), ApproovNetworkException.class);
        assertSame(Approov.TokenFetchStatus.NO_NETWORK, e.getTokenFetchStatus());
        assertEquals("nothing sent", 0, fixture.protectedServer.getRequestCount());
    }

    @Test
    public void aSameOriginRedirectIsSignedAgain() throws Exception {
        // the first hop proceeds on a NO_NETWORK token fetch under ALWAYS_PROCEED, and the redirect fetches a
        // token again
        start(signing(ApproovServiceMutator.ALWAYS_PROCEED));
        nextTokenFetch("NO_NETWORK");
        fixture.protectedServer.enqueue(new MockResponse().setResponseCode(302)
                .setHeader("Location", fixture.protectedServer.url("/next")));
        fixture.protectedServer.enqueue(new MockResponse().setBody("ok"));
        RequestPathProbe.assertOk("redirect", probe.execute(request("/start")));
        RecordedRequest first = fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
        RecordedRequest second = fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("the first hop proceeds with an empty token header", "", first.getHeader("Approov-Token"));
        assertInstallSigned("first hop", first);
        verifyInstallSignature("first hop", first);
        assertNotNull("redirect not followed", second);
        assertEquals("/next", second.getRequestUrl().encodedPath());
        assertEquals("a token for the redirect: " + second.getHeader("Approov-Token"), 3,
                second.getHeader("Approov-Token").split("\\.").length);
        assertInstallSigned("same-origin redirect", second);
        verifyInstallSignature("same-origin redirect", second);
        // the target URI is covered, so a signature made afresh for the target differs from the first hop's
        assertNotEquals("signed afresh for the target", first.getHeader("Signature"), second.getHeader("Signature"));
    }

    // ==================================================================================
    // the processed request change of the given mutator comes before signing
    // ==================================================================================

    // an app mutator that adds an Authorization header in its processed request hook, which the default
    // factory covers when present, recording whether the request was already signed when it saw it
    static final class AddsAuthorization implements ApproovServiceMutator {
        final List<Boolean> sawSignature = Collections.synchronizedList(new ArrayList<>());

        @Override
        public Request handleInterceptorProcessedRequest(Request request, ApproovRequestMutations changes) {
            sawSignature.add(request.header("Signature") != null);
            return request.newBuilder().header("Authorization", "Bearer app-session").build();
        }

        @Override
        public String toString() {
            return "AppDecisions";
        }
    }

    @Test
    public void theProcessedRequestChangeOfTheDecisionsIsCoveredByTheSignature() throws Exception {
        AddsAuthorization decisions = new AddsAuthorization();
        start(signing(decisions));
        RecordedRequest recorded = sendAndTake(request("/data"));
        assertEquals("the mutator's change is sent", "Bearer app-session", recorded.getHeader("Authorization"));
        assertInstallSigned("processed change", recorded);
        List<String> covered = verifyInstallSignature("processed change", recorded);
        assertTrue("the mutator's header is covered: " + covered, covered.contains("authorization"));
        assertEquals("the mutator saw the request before it was signed", Arrays.asList(false),
                decisions.sawSignature);
    }

    @Test
    public void theProcessedRequestChangeIsSignedAgainOnASameOriginRedirect() throws Exception {
        AddsAuthorization decisions = new AddsAuthorization();
        start(signing(decisions));
        fixture.protectedServer.enqueue(new MockResponse().setResponseCode(302)
                .setHeader("Location", fixture.protectedServer.url("/next")));
        fixture.protectedServer.enqueue(new MockResponse().setBody("ok"));
        RequestPathProbe.assertOk("redirect", probe.execute(request("/start")));
        fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
        RecordedRequest second = fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull("redirect not followed", second);
        assertEquals("one Authorization header", Arrays.asList("Bearer app-session"),
                second.getHeaders().values("Authorization"));
        List<String> covered = verifyInstallSignature("redirect", second);
        assertTrue("the mutator's header is covered again: " + covered, covered.contains("authorization"));
        assertEquals("the mutator saw each hop before it was signed", Arrays.asList(false, false),
                decisions.sawSignature);
    }

    // ==================================================================================
    // CLOSE_FAILURE through the constructor and the no-argument signer
    // ==================================================================================

    // what the app sees for one request under the installed mutator after a token fetch with the status, or
    // SUCCESS when status is null: the failure type, message and status, or the token and covered components
    private String outcome(ApproovServiceMutator mutator, String status) throws Exception {
        ApproovService.setServiceMutator(mutator);
        if (status != null)
            nextTokenFetch(status);
        int before = fixture.protectedServer.getRequestCount();
        RequestPathProbe.Outcome outcome = probe.execute(request("/data"));
        if (outcome.failure != null) {
            assertEquals("a failed request is not sent", before, fixture.protectedServer.getRequestCount());
            Throwable f = outcome.failure;
            String fetchStatus = (f instanceof ApproovFetchStatusException)
                    ? String.valueOf(((ApproovFetchStatusException) f).getTokenFetchStatus()) : "-";
            return f.getClass().getSimpleName() + " " + fetchStatus + " " + f.getMessage() + " cause "
                    + f.getCause();
        }
        RecordedRequest recorded = fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
        String token = recorded.getHeader("Approov-Token");
        String input = recorded.getHeader("Signature-Input");
        String covered = (input == null) ? "unsigned" : input.substring(0, input.indexOf(')') + 1);
        if (input != null)
            verifyInstallSignature(String.valueOf(status), recorded);
        return outcome.code + " token " + ((token == null) ? "none" : token.isEmpty() ? "empty" : "present")
                + " " + covered;
    }

    private void alwaysOk() {
        fixture.protectedServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setBody("ok");
            }
        });
    }

    @Test
    public void closeFailureThroughTheConstructorMatchesTheNoArgumentSigner() throws Exception {
        start(noArgumentSigning());
        alwaysOk();
        for (String status : new String[] {null, "NO_APPROOV_SERVICE", "NO_NETWORK", "POOR_NETWORK", "REJECTED",
                "INTERNAL_ERROR"}) {
            String noArgument = outcome(noArgumentSigning(), status);
            String closeFailure = outcome(signing(ApproovServiceMutator.CLOSE_FAILURE), status);
            assertEquals(String.valueOf(status), noArgument, closeFailure);
        }
    }

    // ==================================================================================
    // standard decisions keep their form, an app's exception is the hook's failure
    // ==================================================================================

    // the message of the standard decision for the status, checking its form
    private String assertStandardDecision(String what, String status,
            Class<? extends ApproovFetchStatusException> type) throws Exception {
        nextTokenFetch(status);
        ApproovFetchStatusException e = RequestPathProbe.assertFailure(what + " " + status,
                probe.execute(request("/data")), type);
        assertSame(what + " " + status + ": the standard decision's own type, not a hook failure: " + e, type,
                e.getClass());
        assertEquals(what, Approov.TokenFetchStatus.valueOf(status), e.getTokenFetchStatus());
        assertTrue(what + " " + status + ": the standard message: " + e.getMessage(),
                e.getMessage().startsWith("Approov token fetch for ") && e.getMessage().endsWith(": " + status));
        assertNull(what + " " + status + ": not wrapped", e.getCause());
        return e.getMessage();
    }

    @Test
    public void aCloseFailureAbortThroughTheDecisionsKeepsItsStandardForm() throws Exception {
        start(ApproovServiceMutator.CLOSE_FAILURE);
        // a response is ready, so that a request that is not aborted completes
        alwaysOk();
        String networkMessage = assertStandardDecision("control: CLOSE_FAILURE installed directly", "NO_NETWORK",
                ApproovNetworkException.class);
        String rejectedMessage = assertStandardDecision("control: CLOSE_FAILURE installed directly", "REJECTED",
                ApproovFetchStatusException.class);
        ApproovServiceMutator[] signers = {signing(ApproovServiceMutator.CLOSE_FAILURE), noArgumentSigning()};
        for (ApproovServiceMutator mutator : signers) {
            ApproovService.setServiceMutator(mutator);
            assertEquals(networkMessage, assertStandardDecision(mutator.toString(), "NO_NETWORK",
                    ApproovNetworkException.class));
            assertEquals(rejectedMessage, assertStandardDecision(mutator.toString(), "REJECTED",
                    ApproovFetchStatusException.class));
        }
        assertEquals("nothing sent", 0, fixture.protectedServer.getRequestCount());
    }

    // an app mutator whose token decision aborts every request with its own exception
    static final class AppAbort implements ApproovServiceMutator {
        final List<ApproovException> thrown = new ArrayList<>();

        @Override
        public boolean handleInterceptorFetchTokenResult(Approov.TokenFetchResult approovResults, String url)
                throws ApproovException {
            ApproovException e = new ApproovFetchStatusException(Approov.TokenFetchStatus.REJECTED, "app policy");
            thrown.add(e);
            throw e;
        }
    }

    @Test
    public void anAppExceptionFromTheDecisionsIsTheHooksFailure() throws Exception {
        AppAbort decisions = new AppAbort();
        start(signing(decisions));
        alwaysOk();
        ApproovException e = RequestPathProbe.assertFailure("app abort", probe.execute(request("/data")),
                ApproovException.class);
        assertEquals("the hook was reached once", 1, decisions.thrown.size());
        assertSame("the hook failure, not the type the app threw: " + e, ApproovException.class, e.getClass());
        assertSame("the cause is the app's exception", decisions.thrown.get(0), e.getCause());
        assertTrue("the message names the hook: " + e.getMessage(),
                e.getMessage().contains("handleInterceptorFetchTokenResult"));
    }

    // an app mutator whose processed request hook aborts the request with its own exception
    static final class AppProcessedAbort implements ApproovServiceMutator {
        final List<ApproovException> thrown = new ArrayList<>();

        @Override
        public Request handleInterceptorProcessedRequest(Request request, ApproovRequestMutations changes)
                throws ApproovException {
            ApproovException e = new ApproovException("app policy");
            thrown.add(e);
            throw e;
        }
    }

    @Test
    public void anAppExceptionFromTheProcessedRequestOfTheDecisionsIsTheHooksFailure() throws Exception {
        // the signer's own signing decisions keep their form, but the app's processed request hook is not one
        AppProcessedAbort decisions = new AppProcessedAbort();
        start(signing(decisions));
        alwaysOk();
        ApproovException e = RequestPathProbe.assertFailure("app abort", probe.execute(request("/data")),
                ApproovException.class);
        assertEquals("the hook was reached once", 1, decisions.thrown.size());
        assertSame("the cause is the app's exception: " + e, decisions.thrown.get(0), e.getCause());
        assertTrue("the message names the hook: " + e.getMessage(),
                e.getMessage().contains("handleInterceptorProcessedRequest"));
        assertEquals("nothing sent", 0, fixture.protectedServer.getRequestCount());
    }

    @Test
    public void aSigningConfigurationErrorKeepsItsFormUnderTheDecisions() throws Exception {
        // a required body digest that cannot be generated is the signer's own decision, not a hook failure
        start(new ApproovDefaultMessageSigning(ApproovServiceMutator.ALWAYS_PROCEED).setDefaultFactory(
                ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()
                        .setBodyDigestConfig(ApproovDefaultMessageSigning.DIGEST_SHA256, true)));
        ApproovException e = RequestPathProbe.assertFailure("required digest", probe.execute(request("/data")),
                ApproovException.class);
        assertEquals("Message signing: required body digest not generated", e.getMessage());
        assertTrue("cause " + e.getCause(),
                e.getCause() instanceof ApproovDefaultMessageSigning.RequiredBodyDigestException);
    }

    // ==================================================================================
    // every decision hook is delegated
    // ==================================================================================

    // an app mutator that records every hook call with its arguments and decides the opposite of the interface
    // default for a SUCCESS result, so that a decision the signer made itself is told apart from its own
    static final class RecordingDecisions implements ApproovServiceMutator {
        final List<String> hooks = Collections.synchronizedList(new ArrayList<>());
        final List<Object[]> arguments = Collections.synchronizedList(new ArrayList<>());
        final Request processed = new Request.Builder().url("https://localhost/processed").build();

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
            return processed;
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

    // a SUCCESS token fetch result from the SDK
    private static Approov.TokenFetchResult success() {
        Approov.TokenFetchResult result = Approov.fetchApproovTokenAndWait("https://localhost/data");
        assertSame("control: a SUCCESS result", Approov.TokenFetchStatus.SUCCESS, result.getStatus());
        return result;
    }

    private static void assertDecidedBy(String hook, ThrowingCall call) {
        try {
            call.run();
            fail(hook + ": the signer decided itself");
        } catch (ApproovException e) {
            assertEquals(hook, "decided by " + hook, e.getMessage());
        }
    }

    interface ThrowingCall {
        void run() throws ApproovException;
    }

    @Test
    public void eachDecisionHookIsTheDecisionsOwn() throws Exception {
        start(ApproovServiceMutator.DEFAULT);
        Approov.TokenFetchResult success = success();
        Request request = request("/data");
        ApproovRequestMutations unprotected = new ApproovRequestMutations();
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

        // a request without a token header is not signed, so the processed request is the decisions' own
        assertSame(decisions.processed, signer.handleInterceptorProcessedRequest(request, unprotected));

        assertEquals(Arrays.asList("handleInterceptorShouldProcessRequest", "handleInterceptorFetchTokenResult",
                "handleInterceptorHeaderSubstitutionResult", "handleInterceptorQueryParamSubstitutionResult",
                "handlePinningShouldProcessRequest", "handlePrecheckResult", "handleFetchTokenResult",
                "handleFetchSecureStringResult", "handleFetchCustomJWTResult", "handleInterceptorProcessedRequest"),
                decisions.hooks);
        Object[][] expected = {
            {request}, {success, "https://localhost/data"}, {success, "X-Api-Key"}, {success, "key"}, {request},
            {success}, {success}, {success, "lookup", "the-key"}, {success}, {request, unprotected}};
        for (int i = 0; i < expected.length; i++) {
            Object[] got = decisions.arguments.get(i);
            assertEquals(decisions.hooks.get(i), expected[i].length, got.length);
            for (int j = 0; j < got.length; j++)
                assertSame(decisions.hooks.get(i) + " argument " + j, expected[i][j], got[j]);
        }
    }

    @Test
    public void everyHookOfTheMutatorInterfaceIsDelegatedExceptTheRefreshSupport() throws Exception {
        // a hook added to ApproovServiceMutator later must be delegated too
        start(ApproovServiceMutator.DEFAULT);
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
            assertEquals(hook.getName() + " is delegated", Collections.singletonList(hook.getName()),
                    decisions.hooks);
            assertTrue(hook.getName() + " passes its arguments", Arrays.equals(args, decisions.arguments.get(0)));
        }
        assertTrue("the hooks found: " + hooks, hooks.size() >= 10);
    }

    // ==================================================================================
    // the no-argument signer is unchanged
    // ==================================================================================

    @Test
    public void theNoArgumentSignerIsUnchanged() throws Exception {
        ApproovDefaultMessageSigning plain = new ApproovDefaultMessageSigning();
        assertEquals("ApproovDefaultMessageSigning", plain.toString());
        assertTrue(plain.supportsProtectionRefresh());

        start(noArgumentSigning());
        Request request = request("/data");
        // the interface default decisions, CLOSE_FAILURE
        assertTrue(plain.handleInterceptorShouldProcessRequest(request));
        assertTrue(plain.handlePinningShouldProcessRequest(request));
        Approov.TokenFetchResult success = success();
        assertTrue(plain.handleInterceptorFetchTokenResult(success, "https://localhost/data"));
        assertTrue(plain.handleInterceptorHeaderSubstitutionResult(success, "X-Api-Key"));
        assertTrue(plain.handleInterceptorQueryParamSubstitutionResult(success, "key"));
        plain.handlePrecheckResult(success);
        plain.handleFetchTokenResult(success);
        plain.handleFetchSecureStringResult(success, "lookup", "the-key");

        // a protected request is signed, and a subclass overriding a decision still makes its own
        RecordedRequest recorded = sendAndTake(request("/data"));
        assertInstallSigned("no-argument signer", recorded);
        verifyInstallSignature("no-argument signer", recorded);
        ApproovService.setServiceMutator(TestSigning.alwaysProceed());
        nextTokenFetch("NO_NETWORK");
        recorded = sendAndTake(request("/data"));
        assertEquals("the subclass decision lets NO_NETWORK proceed", "", recorded.getHeader("Approov-Token"));
        assertInstallSigned("subclass", recorded);
    }

    // ==================================================================================
    // the independent verifier
    // ==================================================================================

    // verifies the install signature, the only member, against a base rebuilt from the received request with
    // the mini-SDK install key, returning the covered components
    private static List<String> verifyInstallSignature(String what, RecordedRequest request) throws Exception {
        String input = request.getHeader("Signature-Input");
        String sig = request.getHeader("Signature");
        assertTrue(what + ": one install member: " + input, input.startsWith("install=(") && !input.contains(", "));
        assertTrue(what + ": one install member: " + sig, sig.startsWith("install=:") && sig.endsWith(":"));
        String inner = input.substring("install=".length());
        List<String> components = new ArrayList<>();
        for (String name : inner.substring(1, inner.indexOf(')')).split(" ")) {
            if (!name.isEmpty())
                components.add(name.substring(1, name.length() - 1));
        }
        StringBuilder base = new StringBuilder();
        for (String component : components)
            base.append('"').append(component).append("\": ").append(value(request, component)).append('\n');
        base.append("\"@signature-params\": ").append(inner);
        byte[] signature = Base64.getDecoder().decode(sig.substring("install=:".length(), sig.length() - 1));
        assertEquals(what + ": a raw ES256 signature", 64, signature.length);
        PublicKey key = KeyFactory.getInstance("EC").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(Approov.getInstallPublicKey())));
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(key);
        verifier.update(base.toString().getBytes(StandardCharsets.UTF_8));
        assertTrue(what + ": the install signature must verify", verifier.verify(rawToDer(signature)));
        return components;
    }

    private static String value(RecordedRequest request, String component) {
        switch (component) {
            case "@method":
                return request.getMethod();
            case "@target-uri":
                // the request URL, as HTTP/2 carries no Host header
                return request.getRequestUrl().toString();
            default:
                assertFalse("unexpected derived component " + component, component.startsWith("@"));
                Headers headers = request.getHeaders();
                assertFalse(component + " was not received", headers.values(component).isEmpty());
                return String.join(", ", headers.values(component));
        }
    }

    private static byte[] rawToDer(byte[] raw) {
        byte[] r = new BigInteger(1, Arrays.copyOfRange(raw, 0, 32)).toByteArray();
        byte[] s = new BigInteger(1, Arrays.copyOfRange(raw, 32, 64)).toByteArray();
        byte[] der = new byte[6 + r.length + s.length];
        der[0] = 0x30;
        der[1] = (byte) (4 + r.length + s.length);
        der[2] = 0x02;
        der[3] = (byte) r.length;
        System.arraycopy(r, 0, der, 4, r.length);
        der[4 + r.length] = 0x02;
        der[5 + r.length] = (byte) s.length;
        System.arraycopy(s, 0, der, 6 + r.length, s.length);
        return der;
    }
}
