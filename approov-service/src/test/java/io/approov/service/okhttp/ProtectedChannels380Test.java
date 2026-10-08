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
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.net.ConnectException;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * Protected channels and failure decisions (decided by Ivo 2026-10-07). The token
 * fetch status of the request URL decides the channel:
 *
 * - SUCCESS (a token-protected API): token, status and trace headers, secure
 *   strings, signatures.
 * - UNPROTECTED_URL (an API added with -noApproovToken, a secrets-only API):
 *   secure strings only; no token, status or trace header and no signatures.
 * - UNKNOWN_URL (a host not added to Approov): nothing at all.
 * - any failure status: the mutator's token decision aborts or proceeds, as
 *   before. When it proceeds the request carries the status header and nothing
 *   else (no token header, absent rather than empty, no trace header, no secure
 *   string, no signatures), and the status header only if the host is an Approov
 *   API domain, a key of the SDK pin set; any other host gets nothing at all.
 *
 * A secure string fetch decides only its own substitution: under both standard
 * mutators SUCCESS substitutes, every other status is written in place of the
 * placeholder (decided 2026-10-08), and the request proceeds. The status header always describes the token fetch.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class ProtectedChannels380Test {
    private static final String PLACEHOLDER = "api-key-placeholder";
    private static final String SECRET = "real-api-key-value";

    // every token fetch status other than SUCCESS, UNKNOWN_URL and UNPROTECTED_URL
    // that the SDK can report on the request path
    private static final String[] NETWORK_FAILURES = {"NO_NETWORK", "POOR_NETWORK", "UNTRUSTED_NETWORK"};
    private static final String[] OTHER_FAILURES = {"REJECTED", "INTERNAL_ERROR", "BAD_URL",
            "NO_NETWORK_PERMISSION", "MISSING_LIB_DEPENDENCY", "DISABLED"};

    // every secure string fetch status other than SUCCESS
    private static final String[] SECURE_STRING_FAILURES = {"UNKNOWN_KEY", "REJECTED", "NO_APPROOV_SERVICE",
            "NO_NETWORK", "POOR_NETWORK", "UNTRUSTED_NETWORK", "INTERNAL_ERROR", "DISABLED", "BAD_URL",
            "NO_NETWORK_PERMISSION", "MISSING_LIB_DEPENDENCY"};

    private TwoOriginFixture fixture;
    private RecordingSdkFacade sdk;

    private void start(String extraScenario) throws Exception {
        fixture = new TwoOriginFixture(extraScenario, true, PLACEHOLDER, SECRET);
        ApproovService.reset();
        sdk = RecordingSdkFacade.install();
        fixture.initialize();
        ApproovService.addSubstitutionHeader("Api-Key", null);
        ApproovService.addSubstitutionQueryParam("key");
        ApproovService.enableMessageSigning();
        ApproovService.setOkHttpClientBuilder(fixture.trustingBuilder());
    }

    @After
    public void tearDown() throws Exception {
        if (fixture != null)
            fixture.shutdown();
        ApproovService.reset();
    }

    private Request request() {
        HttpUrl url = fixture.protectedServer.url("/data").newBuilder()
                .addQueryParameter("key", PLACEHOLDER).build();
        return new Request.Builder().url(url).header("Api-Key", PLACEHOLDER).build();
    }

    private static void nextTokenFetch(String status) {
        AttesterProxyController.setNextAttestationDirectiveJson(
                "{\"operation\": \"fetchApproovToken\", \"response\": {\"status\": \"" + status + "\"}}");
    }

    private static void nextSecureStringFetch(String status) {
        AttesterProxyController.setNextAttestationDirectiveJson(
                "{\"operation\": \"fetchSecureString\", \"response\": {\"status\": \"" + status + "\"}}");
    }

    private RecordedRequest sendAndTake(Request request) throws Exception {
        try (Response response = ApproovService.getOkHttpClient().newCall(request).execute()) {
            assertEquals(200, response.code());
        }
        return fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
    }

    // a request that proceeds on a failure status: the status header and nothing else
    private void assertFailureProceeds(String what, String status) throws Exception {
        fixture.protectedServer.enqueue(new MockResponse().setBody("ok"));
        int fetches = sdk.count("fetchSecureStringAndWait");
        nextTokenFetch(status);
        RecordedRequest recorded = sendAndTake(request());
        assertNotNull(what + " " + status + ": not delivered", recorded);
        assertEquals(what + " " + status + ": status header", status.toLowerCase(),
                recorded.getHeader("Approov-Status"));
        assertNull(what + " " + status + ": token header must be absent, not empty",
                recorded.getHeader("Approov-Token"));
        assertNull(what + " " + status + ": trace header", recorded.getHeader("Approov-TraceID"));
        assertNull(what + " " + status + ": Signature", recorded.getHeader("Signature"));
        assertNull(what + " " + status + ": Signature-Input", recorded.getHeader("Signature-Input"));
        assertNull(what + " " + status + ": Content-Digest", recorded.getHeader("Content-Digest"));
        assertEquals(what + " " + status + ": secret in the header", PLACEHOLDER, recorded.getHeader("Api-Key"));
        assertEquals(what + " " + status + ": secret in the query", PLACEHOLDER,
                recorded.getRequestUrl().queryParameter("key"));
        assertEquals(what + " " + status + ": a secure string was fetched", fetches,
                sdk.count("fetchSecureStringAndWait"));
    }

    private void assertTokenFailureAborts(String what, String status, Class<?> expected) throws Exception {
        int before = fixture.protectedServer.getRequestCount();
        nextTokenFetch(status);
        try (Response response = ApproovService.getOkHttpClient().newCall(request()).execute()) {
            fail(what + " " + status + " must abort, got " + response.code());
        } catch (IOException e) {
            assertEquals(what + " " + status + ": " + e, expected, e.getClass());
            assertSame(Approov.TokenFetchStatus.valueOf(status),
                    ((ApproovFetchStatusException) e).getTokenFetchStatus());
        }
        assertEquals(what + " " + status + ": nothing sent", before, fixture.protectedServer.getRequestCount());
    }

    // a secrets-only request: secure strings and nothing else
    private static void assertSecretsOnly(String what, RecordedRequest recorded) {
        assertNotNull(what + ": not delivered", recorded);
        LocalHttpsFixture.assertNoApproovHeaders(recorded);
        assertEquals(what + ": secret in the header", SECRET, recorded.getHeader("Api-Key"));
        assertEquals(what + ": secret in the query", SECRET, recorded.getRequestUrl().queryParameter("key"));
    }

    // ---------------------------------------------------------------------------
    // token fetch status: abort or proceed, and exactly what a proceeding request
    // carries
    // ---------------------------------------------------------------------------

    @Test
    public void control_successCarriesEverything() throws Exception {
        start(null);
        fixture.protectedServer.enqueue(new MockResponse().setBody("ok"));
        RecordedRequest recorded = sendAndTake(request());
        assertFalse("token", recorded.getHeader("Approov-Token").isEmpty());
        assertEquals("success", recorded.getHeader("Approov-Status"));
        assertNotNull("trace", recorded.getHeader("Approov-TraceID"));
        assertNotNull("signatures", recorded.getHeader("Signature-Input"));
        assertEquals(SECRET, recorded.getHeader("Api-Key"));
        assertEquals(SECRET, recorded.getRequestUrl().queryParameter("key"));
    }

    @Test
    public void closeFailureTokenFailuresAbortOrProceedWithTheStatusHeaderOnly() throws Exception {
        start(null);
        for (ApproovServiceMutator mutator : new ApproovServiceMutator[] {ApproovServiceMutator.CLOSE_FAILURE, null}) {
            ApproovService.setServiceMutator(mutator);
            String what = String.valueOf(mutator);
            assertFailureProceeds(what, "NO_APPROOV_SERVICE");
            for (String status : NETWORK_FAILURES)
                assertTokenFailureAborts(what, status, ApproovNetworkException.class);
            for (String status : OTHER_FAILURES)
                assertTokenFailureAborts(what, status, ApproovFetchStatusException.class);
        }
    }

    @Test
    public void alwaysProceedTokenFailuresProceedWithTheStatusHeaderOnly() throws Exception {
        start(null);
        ApproovService.setServiceMutator(ApproovServiceMutator.ALWAYS_PROCEED);
        assertFailureProceeds("ALWAYS_PROCEED", "NO_APPROOV_SERVICE");
        for (String status : NETWORK_FAILURES)
            assertFailureProceeds("ALWAYS_PROCEED", status);
        for (String status : OTHER_FAILURES)
            assertFailureProceeds("ALWAYS_PROCEED", status);
    }

    @Test
    public void aCustomMutatorProceedingOnAFailureGetsTheStatusHeaderOnly() throws Exception {
        start(null);
        ApproovService.setServiceMutator(new ApproovServiceMutator() {
            @Override
            public boolean handleInterceptorFetchTokenResult(Approov.TokenFetchResult approovResults, String url) {
                return true;
            }

            @Override
            public boolean handleInterceptorHeaderSubstitutionResult(Approov.TokenFetchResult approovResults,
                    String header) {
                return true;
            }

            @Override
            public boolean handleInterceptorQueryParamSubstitutionResult(Approov.TokenFetchResult approovResults,
                    String queryKey) {
                return true;
            }
        });
        assertFailureProceeds("custom", "NO_APPROOV_SERVICE");
        assertFailureProceeds("custom", "NO_NETWORK");
        assertFailureProceeds("custom", "REJECTED");
    }

    @Test
    public void aCustomMutatorDecliningAFailureSendsTheRequestUntouched() throws Exception {
        start(null);
        ApproovService.setServiceMutator(new ApproovServiceMutator() {
            @Override
            public boolean handleInterceptorFetchTokenResult(Approov.TokenFetchResult approovResults, String url) {
                return approovResults.getStatus() == Approov.TokenFetchStatus.SUCCESS;
            }
        });
        fixture.protectedServer.enqueue(new MockResponse().setBody("ok"));
        nextTokenFetch("NO_NETWORK");
        RecordedRequest recorded = sendAndTake(request());
        LocalHttpsFixture.assertNoApproovHeaders(recorded);
        assertEquals(PLACEHOLDER, recorded.getHeader("Api-Key"));
        assertEquals(PLACEHOLDER, recorded.getRequestUrl().queryParameter("key"));
        assertEquals("a secure string was fetched", 0, sdk.count("fetchSecureStringAndWait"));
    }

    // ---------------------------------------------------------------------------
    // a failure status the mutator lets proceed: the status header goes only to a
    // host that is an Approov API domain, recognised by being a key of the SDK pin
    // set (the "*" Managed Trust Roots key excluded, case-insensitive, one trailing
    // dot ignored); any other host, or an empty pin set, gets nothing at all
    // ---------------------------------------------------------------------------

    // replaces the fixture's scenario with one whose pin set is given, keeping its
    // secure strings, and reinitializes so that the SDK takes it
    private void pinSet(String pinEntries) {
        String name = "pins-" + java.util.UUID.randomUUID();
        AttesterProxyController.reset();
        AttesterProxyController.loadScenarioJson("{\"activeCase\": \"" + name + "\", \"cases\": {\"" + name + "\": {"
                + "\"protectedDomains\": [\"localhost\"],"
                + "\"pins\": {\"public-key-sha256\": {" + pinEntries + "}},"
                + "\"initialSecureStrings\": {\"" + PLACEHOLDER + "\": \"" + SECRET + "\"}}}}");
        ApproovService.initialize(fixture.context, TwoOriginFixture.CONFIG, "reinit-" + name);
    }

    // a request that proceeds on a failure status with nothing at all
    private void assertFailureGetsNothing(String what, String status, HttpUrl base,
            okhttp3.mockwebserver.MockWebServer server) throws Exception {
        server.enqueue(new MockResponse().setBody("ok"));
        nextTokenFetch(status);
        HttpUrl url = base.newBuilder().addQueryParameter("key", PLACEHOLDER).build();
        try (Response response = ApproovService.getOkHttpClient().newCall(
                new Request.Builder().url(url).header("Api-Key", PLACEHOLDER).build()).execute()) {
            assertEquals(what + " " + status, 200, response.code());
        }
        RecordedRequest recorded = server.takeRequest(5, TimeUnit.SECONDS);
        LocalHttpsFixture.assertNoApproovHeaders(recorded);
        assertEquals(what + " " + status + ": header", PLACEHOLDER, recorded.getHeader("Api-Key"));
        assertEquals(what + " " + status + ": query", PLACEHOLDER, recorded.getRequestUrl().queryParameter("key"));
    }

    @Test
    public void aFailureToAHostNotInThePinSetGetsNothing() throws Exception {
        start(null);
        // 127.0.0.1 is not a key of the pin set
        ApproovService.setServiceMutator(ApproovServiceMutator.CLOSE_FAILURE);
        assertFailureGetsNothing("CLOSE_FAILURE unlisted", "NO_APPROOV_SERVICE", fixture.otherUrl("/data"),
                fixture.otherServer);
        ApproovService.setServiceMutator(ApproovServiceMutator.ALWAYS_PROCEED);
        for (String status : new String[] {"NO_APPROOV_SERVICE", "NO_NETWORK", "REJECTED"})
            assertFailureGetsNothing("ALWAYS_PROCEED unlisted", status, fixture.otherUrl("/data"),
                    fixture.otherServer);
        assertEquals("a secure string was fetched", 0, sdk.count("fetchSecureStringAndWait"));
    }

    @Test
    public void aFailureWithAnEmptyPinSetGetsNothing() throws Exception {
        start(null);
        pinSet("");
        ApproovService.setServiceMutator(ApproovServiceMutator.ALWAYS_PROCEED);
        assertFailureGetsNothing("empty pin set", "NO_APPROOV_SERVICE", fixture.protectedServer.url("/data"),
                fixture.protectedServer);
        assertFailureGetsNothing("empty pin set", "NO_NETWORK", fixture.protectedServer.url("/data"),
                fixture.protectedServer);
    }

    // decided 2026-10-07: the mutator's abort or proceed decision on a failure status
    // applies only to a host in the pin set; a failure on any other host is never
    // aborted and goes out untouched, like UNKNOWN_URL

    @Test
    public void closeFailureNeverAbortsAFailureToAHostNotInThePinSet() throws Exception {
        start(null);
        ApproovService.setServiceMutator(ApproovServiceMutator.CLOSE_FAILURE);
        for (String status : new String[] {"NO_NETWORK", "REJECTED", "INTERNAL_ERROR"})
            assertFailureGetsNothing("CLOSE_FAILURE unlisted", status, fixture.otherUrl("/data"),
                    fixture.otherServer);
        // control: the listed host still aborts
        assertTokenFailureAborts("CLOSE_FAILURE listed", "NO_NETWORK", ApproovNetworkException.class);
        assertTokenFailureAborts("CLOSE_FAILURE listed", "REJECTED", ApproovFetchStatusException.class);
    }

    @Test
    public void closeFailureNeverAbortsAFailureWithAnEmptyPinSet() throws Exception {
        start(null);
        pinSet("");
        ApproovService.setServiceMutator(ApproovServiceMutator.CLOSE_FAILURE);
        for (String status : new String[] {"NO_NETWORK", "REJECTED"})
            assertFailureGetsNothing("CLOSE_FAILURE empty pin set", status, fixture.protectedServer.url("/data"),
                    fixture.protectedServer);
    }

    // decided 2026-10-07 (custom abort off the pin set): for a failure on a host
    // not in the pin set the fetch hook is consulted. A standard decision it throws
    // (inherited or called) is ignored and the request goes out untouched; any
    // other throw is the app's own opt-in abort (1.6.1 rules); true or false leave
    // the request untouched, nothing added

    // a custom mutator whose fetch hook does what the given hook does, counting calls
    private static final class FetchHook implements ApproovServiceMutator {
        interface Body {
            boolean decide(Approov.TokenFetchResult results, String url) throws IOException;
        }

        final java.util.concurrent.atomic.AtomicInteger asked = new java.util.concurrent.atomic.AtomicInteger();
        private final Body body;

        FetchHook(Body body) {
            this.body = body;
        }

        @Override
        public boolean handleInterceptorFetchTokenResult(Approov.TokenFetchResult approovResults, String url)
                throws IOException {
            asked.incrementAndGet();
            return body.decide(approovResults, url);
        }
    }

    // the request to the unlisted host fails with exactly the given type, nothing sent
    private <T extends Throwable> T assertUnlistedAborts(String what, String status, Class<T> type)
            throws Exception {
        int before = fixture.otherServer.getRequestCount();
        nextTokenFetch(status);
        HttpUrl url = fixture.otherUrl("/data").newBuilder().addQueryParameter("key", PLACEHOLDER).build();
        try (Response response = ApproovService.getOkHttpClient().newCall(
                new Request.Builder().url(url).header("Api-Key", PLACEHOLDER).build()).execute()) {
            fail(what + " " + status + " must abort, got " + response.code());
            return null;
        } catch (IOException e) {
            assertSame(what + " " + status + ": " + e, type, e.getClass());
            assertEquals(what + " " + status + ": nothing sent", before, fixture.otherServer.getRequestCount());
            return type.cast(e);
        }
    }

    @Test
    public void aCustomMutatorsOwnThrowAbortsAFailureToAHostNotInThePinSet() throws Exception {
        start(null);
        FetchHook mutator = new FetchHook((results, url) -> {
            throw new ConnectException("app policy: only my own hosts");
        });
        ApproovService.setServiceMutator(mutator);
        ConnectException e = assertUnlistedAborts("custom", "NO_NETWORK", ConnectException.class);
        assertEquals("app policy: only my own hosts", e.getMessage());
        assertEquals("the fetch hook was consulted", 1, mutator.asked.get());
    }

    @Test
    public void aCustomMutatorsOwnApproovExceptionIsItsHooksFailureOffThePinSet() throws Exception {
        start(null);
        ApproovService.setServiceMutator(new FetchHook((results, url) -> {
            throw new ApproovFetchStatusException(results.getStatus(), "app policy");
        }));
        ApproovException e = assertUnlistedAborts("custom Approov type", "REJECTED", ApproovException.class);
        assertTrue(e.getMessage(), e.getMessage().contains("handleInterceptorFetchTokenResult"));
        assertTrue("the app's exception is the cause", e.getCause() instanceof ApproovFetchStatusException);
    }

    @Test
    public void aCustomMutatorsRuntimeExceptionAbortsOffThePinSet() throws Exception {
        start(null);
        ApproovService.setServiceMutator(new FetchHook((results, url) -> {
            throw new IllegalStateException("bug");
        }));
        ApproovException e = assertUnlistedAborts("custom runtime", "NO_NETWORK", ApproovException.class);
        assertTrue("the runtime exception is the cause", e.getCause() instanceof IllegalStateException);
    }

    @Test
    public void aStandardDecisionThrownOffThePinSetIsIgnored() throws Exception {
        start(null);
        FetchHook delegating = new FetchHook(ApproovServiceMutator.CLOSE_FAILURE::handleInterceptorFetchTokenResult);
        FetchHook rethrowing = new FetchHook((results, url) -> {
            try {
                return ApproovServiceMutator.CLOSE_FAILURE.handleInterceptorFetchTokenResult(results, url);
            } catch (ApproovException caught) {
                throw caught;
            }
        });
        ApproovServiceMutator inheriting = new ApproovServiceMutator() {
            @Override
            public Request handleInterceptorProcessedRequest(Request request, ApproovRequestMutations changes) {
                return request;
            }
        };
        for (ApproovServiceMutator mutator : new ApproovServiceMutator[] {inheriting, delegating, rethrowing}) {
            ApproovService.setServiceMutator(mutator);
            for (String status : new String[] {"NO_NETWORK", "REJECTED", "INTERNAL_ERROR"})
                assertFailureGetsNothing(mutator.getClass().getSimpleName() + " unlisted", status,
                        fixture.otherUrl("/data"), fixture.otherServer);
        }
        assertEquals("the delegating hook was consulted", 3, delegating.asked.get());
        assertEquals("the rethrowing hook was consulted", 3, rethrowing.asked.get());
        // control: the listed host still aborts under the same mutator
        assertTokenFailureAborts("delegating listed", "NO_NETWORK", ApproovNetworkException.class);
    }

    @Test
    public void aCustomMutatorAnsweringTrueOrFalseOffThePinSetAddsNothing() throws Exception {
        start(null);
        for (boolean answer : new boolean[] {true, false}) {
            FetchHook mutator = new FetchHook((results, url) -> answer);
            ApproovService.setServiceMutator(mutator);
            for (String status : new String[] {"NO_APPROOV_SERVICE", "NO_NETWORK", "REJECTED"})
                assertFailureGetsNothing("custom " + answer + " unlisted", status, fixture.otherUrl("/data"),
                        fixture.otherServer);
            assertEquals("the fetch hook was consulted", 3, mutator.asked.get());
        }
    }

    @Test
    public void aCustomMutatorMayAbortAFailureWithAnEmptyPinSet() throws Exception {
        start(null);
        pinSet("");
        ApproovService.setServiceMutator(new FetchHook((results, url) -> {
            throw new ConnectException("app policy");
        }));
        nextTokenFetch("NO_NETWORK");
        try (Response response = ApproovService.getOkHttpClient().newCall(request()).execute()) {
            fail("the app's own abort must surface, got " + response.code());
        } catch (ConnectException e) {
            assertEquals("app policy", e.getMessage());
        }
        assertEquals("nothing sent", 0, fixture.protectedServer.getRequestCount());
    }

    @Test
    public void anUnreadablePinSetListsNoHost() throws Exception {
        start(null);
        ApproovService.setSdkFacadeForTesting(new RecordingSdkFacade() {
            @Override
            public java.util.Map<String, java.util.List<String>> getPins(String pinType) {
                throw new IllegalStateException("no pins");
            }
        });
        assertFalse(ApproovService.isApproovApiHost("localhost"));
        ApproovService.setSdkFacadeForTesting(new RecordingSdkFacade() {
            @Override
            public java.util.Map<String, java.util.List<String>> getPins(String pinType) {
                return null;
            }
        });
        assertFalse(ApproovService.isApproovApiHost("localhost"));
    }

    @Test
    public void theManagedTrustRootsKeyDoesNotListAHost() throws Exception {
        start(null);
        pinSet("\"*\": []");
        ApproovService.setServiceMutator(ApproovServiceMutator.ALWAYS_PROCEED);
        assertFailureGetsNothing("only the * key", "NO_APPROOV_SERVICE", fixture.protectedServer.url("/data"),
                fixture.protectedServer);
    }

    @Test
    public void aPinKeyListsItsHostWithoutRegardToCaseOrATrailingDot() throws Exception {
        start(null);
        ApproovService.setServiceMutator(ApproovServiceMutator.ALWAYS_PROCEED);
        for (String key : new String[] {"LocalHost", "localhost."}) {
            pinSet("\"" + key + "\": []");
            assertFailureProceeds("key " + key, "NO_APPROOV_SERVICE");
        }
    }

    @Test
    public void unprotectedUrlIsASecretsOnlyChannelUnderEveryStandardMutator() throws Exception {
        start(null);
        ApproovServiceMutator[] mutators = {ApproovServiceMutator.CLOSE_FAILURE,
                ApproovServiceMutator.ALWAYS_PROCEED, null};
        for (ApproovServiceMutator mutator : mutators) {
            ApproovService.setServiceMutator(mutator);
            fixture.protectedServer.enqueue(new MockResponse().setBody("ok"));
            nextTokenFetch("UNPROTECTED_URL");
            assertSecretsOnly(String.valueOf(mutator), sendAndTake(request()));
        }
    }

    @Test
    public void unknownUrlIsUntouchedUnderEveryStandardMutator() throws Exception {
        start(null);
        ApproovServiceMutator[] mutators = {ApproovServiceMutator.CLOSE_FAILURE,
                ApproovServiceMutator.ALWAYS_PROCEED, null};
        for (ApproovServiceMutator mutator : mutators) {
            ApproovService.setServiceMutator(mutator);
            fixture.protectedServer.enqueue(new MockResponse().setBody("ok"));
            nextTokenFetch("UNKNOWN_URL");
            RecordedRequest recorded = sendAndTake(request());
            LocalHttpsFixture.assertNoApproovHeaders(recorded);
            assertEquals(mutator + ": header", PLACEHOLDER, recorded.getHeader("Api-Key"));
            assertEquals(mutator + ": query", PLACEHOLDER, recorded.getRequestUrl().queryParameter("key"));
        }
        assertEquals("a secure string was fetched", 0, sdk.count("fetchSecureStringAndWait"));
    }

    @Test
    public void unprotectedUrlOverCleartextKeepsThePlaceholders() throws Exception {
        // the SDK answers BAD_URL for every http URL, so this facade answers
        // UNPROTECTED_URL for one itself, to show that the layer's own https check
        // keeps a secret out of cleartext on the secrets-only channel too
        fixture = new TwoOriginFixture(false, PLACEHOLDER, SECRET);
        ApproovService.reset();
        RecordingSdkFacade facade = new RecordingSdkFacade() {
            @Override
            public Approov.TokenFetchResult fetchApproovTokenAndWait(String url) {
                calls.add("fetchApproovTokenAndWait:" + url);
                return tokenResult(Approov.TokenFetchStatus.UNPROTECTED_URL);
            }
        };
        ApproovService.setSdkFacadeForTesting(facade);
        sdk = facade;
        fixture.initialize();
        ApproovService.addSubstitutionHeader("Api-Key", null);
        ApproovService.addSubstitutionQueryParam("key");
        fixture.otherServer.enqueue(new MockResponse().setBody("ok"));
        HttpUrl url = fixture.otherUrl("/data").newBuilder().addQueryParameter("key", PLACEHOLDER).build();
        try (Response response = ApproovService.getOkHttpClient().newCall(
                new Request.Builder().url(url).header("Api-Key", PLACEHOLDER).build()).execute()) {
            assertEquals(200, response.code());
        }
        RecordedRequest recorded = fixture.otherServer.takeRequest(5, TimeUnit.SECONDS);
        LocalHttpsFixture.assertNoApproovHeaders(recorded);
        assertEquals(PLACEHOLDER, recorded.getHeader("Api-Key"));
        assertEquals(PLACEHOLDER, recorded.getRequestUrl().queryParameter("key"));
        assertEquals("the facade was asked", 1, sdk.count("fetchApproovTokenAndWait"));
        assertEquals("a secure string was fetched", 0, sdk.count("fetchSecureStringAndWait"));
    }

    // a token fetch result with the given status and no token, built as the SDK
    // builds it (its constructor is package private)
    private static Approov.TokenFetchResult tokenResult(Approov.TokenFetchStatus status) {
        try {
            java.lang.reflect.Constructor<Approov.TokenFetchResult> constructor =
                    Approov.TokenFetchResult.class.getDeclaredConstructor(Approov.TokenFetchStatus.class,
                            String.class, String.class, String.class, String.class, String.class,
                            boolean.class, boolean.class, byte[].class);
            constructor.setAccessible(true);
            return constructor.newInstance(status, "", null, null, "", "", false, false, null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("the mini-SDK TokenFetchResult constructor changed", e);
        }
    }

    // ---------------------------------------------------------------------------
    // secure string fetch status: SUCCESS substitutes, everything else is written
    // in place of the placeholder and the request proceeds under both standard
    // mutators
    // ---------------------------------------------------------------------------

    private void assertSecureStringFailureProceeds(String what, String status, boolean header,
            String tokenStatus) throws Exception {
        String expected = status.toLowerCase(java.util.Locale.ROOT);
        fixture.protectedServer.enqueue(new MockResponse().setBody("ok"));
        HttpUrl.Builder url = fixture.protectedServer.url("/data").newBuilder();
        Request.Builder builder = new Request.Builder();
        if (header)
            builder.header("Api-Key", PLACEHOLDER);
        else
            url.addQueryParameter("key", PLACEHOLDER);
        nextSecureStringFetch(status);
        RecordedRequest recorded;
        try (Response response = ApproovService.getOkHttpClient().newCall(builder.url(url.build()).build()).execute()) {
            assertEquals(what + " " + status, 200, response.code());
        } catch (IOException e) {
            throw new AssertionError(what + " " + status + " must not abort the request", e);
        }
        recorded = fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
        if (header)
            assertEquals(what + " " + status + ": header", expected, recorded.getHeader("Api-Key"));
        else
            assertEquals(what + " " + status + ": query", expected, recorded.getRequestUrl().queryParameter("key"));
        if ("success".equals(tokenStatus)) {
            assertFalse(what + " " + status + ": token", recorded.getHeader("Approov-Token").isEmpty());
            assertEquals(what + " " + status + ": the status header reports the token fetch", "success",
                    recorded.getHeader("Approov-Status"));
        } else {
            LocalHttpsFixture.assertNoApproovHeaders(recorded);
        }
    }

    @Test
    public void secureStringFailuresNeverAbortUnderTheStandardMutators() throws Exception {
        start(null);
        ApproovServiceMutator[] mutators = {ApproovServiceMutator.CLOSE_FAILURE,
                ApproovServiceMutator.ALWAYS_PROCEED, null};
        for (ApproovServiceMutator mutator : mutators) {
            ApproovService.setServiceMutator(mutator);
            for (String status : SECURE_STRING_FAILURES) {
                assertSecureStringFailureProceeds(String.valueOf(mutator), status, true, "success");
                assertSecureStringFailureProceeds(String.valueOf(mutator), status, false, "success");
            }
        }
    }

    @Test
    public void secureStringFailuresOnASecretsOnlyChannelNeverAbort() throws Exception {
        // localhost is a secrets-only API for every token fetch
        start("\"fetchApproovToken\": [{\"urlRegex\": \"https://localhost:.*\", \"status\": \"UNPROTECTED_URL\"}]");
        for (ApproovServiceMutator mutator : new ApproovServiceMutator[] {ApproovServiceMutator.CLOSE_FAILURE,
                ApproovServiceMutator.ALWAYS_PROCEED}) {
            ApproovService.setServiceMutator(mutator);
            for (String status : new String[] {"NO_NETWORK", "REJECTED", "INTERNAL_ERROR"}) {
                assertSecureStringFailureProceeds(String.valueOf(mutator), status, true, null);
                assertSecureStringFailureProceeds(String.valueOf(mutator), status, false, null);
            }
        }
        // control: a successful secure string fetch on that channel substitutes
        fixture.protectedServer.enqueue(new MockResponse().setBody("ok"));
        assertSecretsOnly("control", sendAndTake(request()));
    }

    @Test
    public void closeFailureSubstitutionNeverThrows() throws Exception {
        start(null);
        for (Approov.TokenFetchStatus status : Approov.TokenFetchStatus.values()) {
            Approov.TokenFetchResult result = resultWithStatus(status);
            assertEquals(status.toString(), status == Approov.TokenFetchStatus.SUCCESS,
                    ApproovServiceMutator.closeFailureSubstitution(result, "test"));
        }
    }

    @Test
    public void aCustomMutatorMayStillAbortFromASubstitutionHook() throws Exception {
        start(null);
        ApproovService.setServiceMutator(new ApproovServiceMutator() {
            @Override
            public boolean handleInterceptorHeaderSubstitutionResult(Approov.TokenFetchResult approovResults,
                    String header) throws IOException {
                if (approovResults.getStatus() != Approov.TokenFetchStatus.SUCCESS)
                    throw new ConnectException("app policy: no secret, no request");
                return true;
            }
        });
        nextSecureStringFetch("NO_NETWORK");
        try (Response response = ApproovService.getOkHttpClient().newCall(request()).execute()) {
            fail("the app's own abort must surface, got " + response.code());
        } catch (ConnectException e) {
            assertEquals("app policy: no secret, no request", e.getMessage());
        }
        assertEquals("nothing sent", 0, fixture.protectedServer.getRequestCount());
    }

    // a secure string fetch result with the given status, from the SDK
    private static Approov.TokenFetchResult resultWithStatus(Approov.TokenFetchStatus status) {
        AttesterProxyController.setNextAttestationDirectiveJson(
                "{\"operation\": \"fetchSecureString\", \"response\": {\"status\": \"" + status + "\"}}");
        return Approov.fetchSecureStringAndWait(PLACEHOLDER, null);
    }
}
