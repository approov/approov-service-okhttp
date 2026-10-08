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
import static org.junit.Assert.fail;

import com.criticalblue.approovsdk.Approov;
import com.criticalblue.minisdk.testing.AttesterProxyController;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

import java.io.IOException;
import java.net.ConnectException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * Decided 2026-10-08: on a request that may carry secure strings (token status
 * SUCCESS or UNPROTECTED_URL, https, not BAD_URL) a secure string fetch that fails
 * with a status the mutator lets continue replaces the placeholder with that
 * status, written as the status header writes statuses (the lowercase enum name),
 * keeping any required header prefix; UNKNOWN_KEY included. The placeholder stays
 * only where no secure string is fetched. The status header still reports the
 * token fetch only. A redirect to another origin
 * restores the app's placeholder, and a refresh fetches the secure strings afresh
 * from the placeholders, never from the status the layer wrote.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class SecureStringStatusEvidence380Test {
    private static final String PLACEHOLDER = "api-key-placeholder";
    private static final String SECRET = "real-api-key-value";

    // the status on the wire for each secure string fetch status the decision names
    private static final String[][] EVIDENCE = {
            {"REJECTED", "rejected"},
            {"DISABLED", "disabled"},
            {"NO_NETWORK", "no_network"},
            {"POOR_NETWORK", "poor_network"},
            {"NO_APPROOV_SERVICE", "no_approov_service"},
            {"INTERNAL_ERROR", "internal_error"},
            {"UNKNOWN_KEY", "unknown_key"}};

    // an SDK facade answering every secure string fetch with a fixed status and no value while one is set
    private static final class StatusFacade extends RecordingSdkFacade {
        volatile Approov.TokenFetchStatus status;

        @Override
        public Approov.TokenFetchResult fetchSecureStringAndWait(String key, String newDefinition) {
            Approov.TokenFetchStatus answer = status;
            if (answer == null)
                return super.fetchSecureStringAndWait(key, newDefinition);
            calls.add("fetchSecureStringAndWait:" + key);
            return result(answer);
        }
    }

    private TwoOriginFixture fixture;
    private StatusFacade sdk;

    private void start(String extraScenario) throws Exception {
        fixture = new TwoOriginFixture(extraScenario, true, PLACEHOLDER, SECRET);
        ApproovService.reset();
        sdk = new StatusFacade();
        ApproovService.setSdkFacadeForTesting(sdk);
        fixture.initialize();
        ApproovService.addSubstitutionHeader("Api-Key", null);
        ApproovService.addSubstitutionQueryParam("key");
        ApproovService.setOkHttpClientBuilder(fixture.trustingBuilder());
    }

    @After
    public void tearDown() throws Exception {
        if (fixture != null)
            fixture.shutdown();
        ApproovService.reset();
    }

    // a secure string fetch result with the given status and no value, built as the SDK builds it
    private static Approov.TokenFetchResult result(Approov.TokenFetchStatus status) {
        try {
            java.lang.reflect.Constructor<Approov.TokenFetchResult> constructor =
                    Approov.TokenFetchResult.class.getDeclaredConstructor(Approov.TokenFetchStatus.class,
                            String.class, String.class, String.class, String.class, String.class,
                            boolean.class, boolean.class, byte[].class);
            constructor.setAccessible(true);
            return constructor.newInstance(status, "", null, null, "ARCEVID1", "", false, false, null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("the mini-SDK TokenFetchResult constructor changed", e);
        }
    }

    private Request request() {
        HttpUrl url = fixture.protectedServer.url("/data").newBuilder()
                .addQueryParameter("key", PLACEHOLDER).build();
        return new Request.Builder().url(url).header("Api-Key", PLACEHOLDER).build();
    }

    private RecordedRequest send(OkHttpClient client, Request request) throws Exception {
        fixture.protectedServer.enqueue(new MockResponse().setBody("ok"));
        try (Response response = client.newCall(request).execute()) {
            assertEquals(200, response.code());
        }
        return fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
    }

    private RecordedRequest send(Request request) throws Exception {
        return send(ApproovService.getOkHttpClient(), request);
    }

    // a token-protected request whose secure string fetches return the status carries the expected value in
    // the header and the query, and the status header still reports the token fetch
    private void assertCarries(String what, String status, String expected) throws Exception {
        sdk.status = Approov.TokenFetchStatus.valueOf(status);
        RecordedRequest recorded = send(request());
        assertEquals(what + " " + status + ": header", expected, recorded.getHeader("Api-Key"));
        assertEquals(what + " " + status + ": query", expected, recorded.getRequestUrl().queryParameter("key"));
        assertFalse(what + " " + status + ": token", recorded.getHeader("Approov-Token").isEmpty());
        assertEquals(what + " " + status + ": the status header reports the token fetch", "success",
                recorded.getHeader("Approov-Status"));
    }

    @Test
    public void standardMutatorsWriteTheStatusInPlaceOfThePlaceholder() throws Exception {
        start(null);
        for (ApproovServiceMutator mutator : new ApproovServiceMutator[] {ApproovServiceMutator.CLOSE_FAILURE,
                ApproovServiceMutator.ALWAYS_PROCEED}) {
            ApproovService.setServiceMutator(mutator);
            for (String[] row : EVIDENCE)
                assertCarries(String.valueOf(mutator), row[0], row[1]);
        }
    }

    @Test
    public void everyFailureStatusIsWrittenAsTheStatusHeaderWritesIt() throws Exception {
        start(null);
        for (ApproovServiceMutator mutator : new ApproovServiceMutator[] {ApproovServiceMutator.CLOSE_FAILURE,
                ApproovServiceMutator.ALWAYS_PROCEED}) {
            ApproovService.setServiceMutator(mutator);
            for (Approov.TokenFetchStatus status : Approov.TokenFetchStatus.values()) {
                if (status == Approov.TokenFetchStatus.SUCCESS)
                    continue;
                assertCarries(String.valueOf(mutator), status.name(), ApproovService.buildStatusHeaderValue(
                        result(status)));
                assertEquals(status.name().toLowerCase(Locale.ROOT), ApproovService.buildStatusHeaderValue(
                        result(status)));
            }
        }
    }

    @Test
    public void aKeyTheSdkDoesNotHoldCarriesUnknownKey() throws Exception {
        // the mini SDK answers UNKNOWN_KEY itself for a key it does not hold
        start(null);
        sdk.status = null;
        RecordedRequest recorded = send(new Request.Builder().url(fixture.protectedServer.url("/data").newBuilder()
                .addQueryParameter("key", "not-a-key").build()).header("Api-Key", "not-a-key").build());
        assertEquals("unknown_key", recorded.getHeader("Api-Key"));
        assertEquals("unknown_key", recorded.getRequestUrl().queryParameter("key"));
    }

    @Test
    public void controlSuccessSubstitutesTheValue() throws Exception {
        start(null);
        sdk.status = null;
        RecordedRequest recorded = send(request());
        assertEquals(SECRET, recorded.getHeader("Api-Key"));
        assertEquals(SECRET, recorded.getRequestUrl().queryParameter("key"));
    }

    @Test
    public void aRequiredPrefixIsKept() throws Exception {
        start(null);
        ApproovService.addSubstitutionHeader("Authorization", "Bearer ");
        sdk.status = Approov.TokenFetchStatus.REJECTED;
        RecordedRequest recorded = send(new Request.Builder().url(fixture.protectedServer.url("/data"))
                .header("Authorization", "Bearer " + PLACEHOLDER).build());
        assertEquals("Bearer rejected", recorded.getHeader("Authorization"));
    }

    @Test
    public void everyOccurrenceOfAQueryParameterCarriesTheStatus() throws Exception {
        start(null);
        sdk.status = Approov.TokenFetchStatus.NO_NETWORK;
        HttpUrl url = fixture.protectedServer.url("/data").newBuilder()
                .addQueryParameter("key", PLACEHOLDER)
                .addQueryParameter("other", "1")
                .addQueryParameter("key", PLACEHOLDER).build();
        RecordedRequest recorded = send(new Request.Builder().url(url).build());
        List<String> values = recorded.getRequestUrl().queryParameterValues("key");
        assertEquals(java.util.Arrays.asList("no_network", "no_network"), values);
        assertEquals("1", recorded.getRequestUrl().queryParameter("other"));
    }

    @Test
    public void aSecretsOnlyRequestCarriesTheStatus() throws Exception {
        start("\"fetchApproovToken\": [{\"urlRegex\": \"https://localhost:.*\", \"status\": \"UNPROTECTED_URL\"}]");
        for (ApproovServiceMutator mutator : new ApproovServiceMutator[] {ApproovServiceMutator.CLOSE_FAILURE,
                ApproovServiceMutator.ALWAYS_PROCEED}) {
            ApproovService.setServiceMutator(mutator);
            sdk.status = Approov.TokenFetchStatus.REJECTED;
            RecordedRequest recorded = send(request());
            LocalHttpsFixture.assertNoApproovHeaders(recorded);
            assertEquals(mutator + ": header", "rejected", recorded.getHeader("Api-Key"));
            assertEquals(mutator + ": query", "rejected", recorded.getRequestUrl().queryParameter("key"));
        }
    }

    // a custom mutator whose substitution hooks both answer the given decision
    private static ApproovServiceMutator substitutionDecision(boolean decision) {
        return new ApproovServiceMutator() {
            @Override
            public boolean handleInterceptorHeaderSubstitutionResult(Approov.TokenFetchResult approovResults,
                    String header) {
                return decision;
            }

            @Override
            public boolean handleInterceptorQueryParamSubstitutionResult(Approov.TokenFetchResult approovResults,
                    String queryKey) {
                return decision;
            }
        };
    }

    @Test
    public void aCustomMutatorContinuingOnAFailureGetsTheStatus() throws Exception {
        start(null);
        // false skips the substitution and true asks for a value there is not, both without aborting
        for (boolean decision : new boolean[] {false, true}) {
            ApproovService.setServiceMutator(substitutionDecision(decision));
            assertCarries("custom " + decision, "REJECTED", "rejected");
            assertCarries("custom " + decision, "NO_NETWORK", "no_network");
            assertCarries("custom " + decision, "UNKNOWN_KEY", "unknown_key");
        }
    }

    @Test
    public void aCustomMutatorSkippingASuccessLeavesThePlaceholder() throws Exception {
        start(null);
        ApproovService.setServiceMutator(substitutionDecision(false));
        sdk.status = null;
        RecordedRequest recorded = send(request());
        assertEquals(PLACEHOLDER, recorded.getHeader("Api-Key"));
        assertEquals(PLACEHOLDER, recorded.getRequestUrl().queryParameter("key"));
    }

    @Test
    public void aCustomMutatorThrowingStillAborts() throws Exception {
        start(null);
        ApproovService.setServiceMutator(new ApproovServiceMutator() {
            @Override
            public boolean handleInterceptorQueryParamSubstitutionResult(Approov.TokenFetchResult approovResults,
                    String queryKey) throws IOException {
                if (approovResults.getStatus() != Approov.TokenFetchStatus.SUCCESS)
                    throw new ConnectException("app policy: no secret, no request");
                return true;
            }
        });
        sdk.status = Approov.TokenFetchStatus.REJECTED;
        HttpUrl url = fixture.protectedServer.url("/data").newBuilder().addQueryParameter("key", PLACEHOLDER).build();
        try (Response response = ApproovService.getOkHttpClient().newCall(
                new Request.Builder().url(url).build()).execute()) {
            fail("the app's own abort must surface, got " + response.code());
        } catch (ConnectException e) {
            assertEquals("app policy: no secret, no request", e.getMessage());
        }
        assertEquals("nothing sent", 0, fixture.protectedServer.getRequestCount());
    }

    @Test
    public void aRedirectToAnotherOriginRestoresThePlaceholder() throws Exception {
        start(null);
        ApproovService.addSubstitutionHeader("Authorization", "Bearer ");
        sdk.status = Approov.TokenFetchStatus.REJECTED;
        fixture.protectedServer.enqueue(new MockResponse().setResponseCode(302)
                .setHeader("Location", fixture.otherUrl("/landing")));
        fixture.otherServer.enqueue(new MockResponse().setBody("ok"));
        try (Response response = ApproovService.getOkHttpClient().newCall(request()).execute()) {
            assertEquals(200, response.code());
        }
        RecordedRequest first = fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("control: the first hop carries the status", "rejected", first.getHeader("Api-Key"));
        RecordedRequest second = fixture.otherServer.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull("not delivered", second);
        LocalHttpsFixture.assertNoApproovHeaders(second);
        assertEquals("the header goes back to the app's placeholder", PLACEHOLDER, second.getHeader("Api-Key"));
    }

    // a client whose network layer holds every attempt long enough for its protection to be stale, and then
    // answers the secure string fetches of the refresh with the given status, or the SDK value if null
    private OkHttpClient heldClient(Approov.TokenFetchStatus refreshStatus) {
        Interceptor hold = chain -> {
            ShadowSystemClock.advanceBy(Duration.ofSeconds(10));
            sdk.status = refreshStatus;
            return chain.proceed(chain.request());
        };
        OkHttpClient.Builder builder = ApproovService.getOkHttpClient().newBuilder();
        builder.networkInterceptors().add(0, hold);
        return builder.build();
    }

    // every secure string fetch was asked for the placeholder, never for a status the layer wrote
    private void assertFetchedOnlyThePlaceholder() {
        int fetches = 0;
        for (String call : sdk.snapshot()) {
            if (call.startsWith("fetchSecureStringAndWait:")) {
                assertEquals(call, "fetchSecureStringAndWait:" + PLACEHOLDER, call);
                fetches++;
            }
        }
        assertEquals("the refresh fetches afresh: " + sdk.snapshot(), 4, fetches);
    }

    @Test
    public void aRefreshFetchesTheSecureStringsAfresh() throws Exception {
        start(null);
        sdk.status = Approov.TokenFetchStatus.REJECTED;
        RecordedRequest recorded = send(heldClient(null), request());
        assertEquals(SECRET, recorded.getHeader("Api-Key"));
        assertEquals(SECRET, recorded.getRequestUrl().queryParameter("key"));
        assertFetchedOnlyThePlaceholder();
    }

    @Test
    public void aRefreshWritesTheStatusOfItsOwnFetch() throws Exception {
        start(null);
        sdk.status = Approov.TokenFetchStatus.REJECTED;
        RecordedRequest recorded = send(heldClient(Approov.TokenFetchStatus.NO_NETWORK), request());
        assertEquals("no_network", recorded.getHeader("Api-Key"));
        assertEquals("no_network", recorded.getRequestUrl().queryParameter("key"));
        assertFetchedOnlyThePlaceholder();
    }

    @Test
    public void aRefreshToUnknownUrlRestoresThePlaceholders() throws Exception {
        start(null);
        sdk.status = Approov.TokenFetchStatus.REJECTED;
        Interceptor hold = chain -> {
            ShadowSystemClock.advanceBy(Duration.ofSeconds(10));
            AttesterProxyController.setNextAttestationDirectiveJson(
                    "{\"operation\": \"fetchApproovToken\", \"response\": {\"status\": \"UNKNOWN_URL\"}}");
            return chain.proceed(chain.request());
        };
        OkHttpClient.Builder builder = ApproovService.getOkHttpClient().newBuilder();
        builder.networkInterceptors().add(0, hold);
        RecordedRequest recorded = send(builder.build(), request());
        LocalHttpsFixture.assertNoApproovHeaders(recorded);
        assertEquals(PLACEHOLDER, recorded.getHeader("Api-Key"));
        assertEquals(PLACEHOLDER, recorded.getRequestUrl().queryParameter("key"));
    }
}
