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

import com.criticalblue.minisdk.testing.AttesterProxyController;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * The channel rules (decided 2026-10-07) on every path that reapplies protection
 * at the network layer: a stale protection refresh and a redirect. Each attempt is
 * classified afresh by its own token fetch: a refresh reporting UNPROTECTED_URL
 * strips the token, status and trace headers and the signatures and substitutes
 * the secure strings again from the placeholders; one reporting UNKNOWN_URL strips
 * everything and leaves the placeholders; one reporting a failure the mutator
 * lets proceed carries the status header only. A redirect to another origin is
 * stripped and then classified for its target: a secrets-only target gets its
 * secure strings and nothing else, a target not added to Approov gets nothing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class ChannelReapply380Test {
    private static final String PLACEHOLDER = "api-key-placeholder";
    private static final String SECRET = "real-api-key-value";

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

    private Request request(String path) {
        HttpUrl url = fixture.protectedServer.url(path).newBuilder()
                .addQueryParameter("key", PLACEHOLDER).build();
        return new Request.Builder().url(url).header("Api-Key", PLACEHOLDER)
                .header("Authorization", "Bearer app-credential").build();
    }

    private static void nextTokenFetch(String status) {
        AttesterProxyController.setNextAttestationDirectiveJson(
                "{\"operation\": \"fetchApproovToken\", \"response\": {\"status\": \"" + status + "\"}}");
    }

    // a client whose network layer holds every attempt long enough for its
    // protection to be stale, and answers the refresh's token fetch with the status
    private static OkHttpClient heldClient(String refreshStatus) {
        Interceptor hold = chain -> {
            ShadowSystemClock.advanceBy(Duration.ofSeconds(10));
            nextTokenFetch(refreshStatus);
            return chain.proceed(chain.request());
        };
        OkHttpClient.Builder builder = ApproovService.getOkHttpClient().newBuilder();
        builder.networkInterceptors().add(0, hold);
        return builder.build();
    }

    private RecordedRequest sendHeld(String refreshStatus) throws Exception {
        fixture.protectedServer.enqueue(new MockResponse().setBody("ok"));
        try (Response response = heldClient(refreshStatus).newCall(request("/data")).execute()) {
            assertEquals(200, response.code());
        }
        return fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
    }

    private static void assertSecretsOnly(String what, RecordedRequest recorded) {
        assertNotNull(what + ": not delivered", recorded);
        LocalHttpsFixture.assertNoApproovHeaders(recorded);
        assertEquals(what + ": secret in the header", SECRET, recorded.getHeader("Api-Key"));
        assertEquals(what + ": secret in the query", SECRET, recorded.getRequestUrl().queryParameter("key"));
    }

    // ---------------------------------------------------------------------------
    // stale protection refresh
    // ---------------------------------------------------------------------------

    @Test
    public void aRefreshReportingUnprotectedUrlKeepsTheSecretsAndNothingElse() throws Exception {
        start(null);
        RecordedRequest recorded = sendHeld("UNPROTECTED_URL");
        assertSecretsOnly("refreshed to UNPROTECTED_URL", recorded);
        // the secrets are substituted afresh from the placeholders, not carried over
        assertEquals("secure strings fetched", 4, sdk.count("fetchSecureStringAndWait"));
    }

    @Test
    public void aRefreshReportingUnknownUrlRestoresThePlaceholders() throws Exception {
        start(null);
        RecordedRequest recorded = sendHeld("UNKNOWN_URL");
        LocalHttpsFixture.assertNoApproovHeaders(recorded);
        assertEquals(PLACEHOLDER, recorded.getHeader("Api-Key"));
        assertEquals(PLACEHOLDER, recorded.getRequestUrl().queryParameter("key"));
    }

    @Test
    public void aRefreshReportingAProceedingFailureCarriesTheStatusHeaderOnly() throws Exception {
        start(null);
        // CLOSE_FAILURE lets NO_APPROOV_SERVICE proceed
        RecordedRequest recorded = sendHeld("NO_APPROOV_SERVICE");
        assertEquals("no_approov_service", recorded.getHeader("Approov-Status"));
        assertNull("token header must be absent", recorded.getHeader("Approov-Token"));
        assertNull("trace header", recorded.getHeader("Approov-TraceID"));
        assertNull("signatures", recorded.getHeader("Signature-Input"));
        assertNull("signatures", recorded.getHeader("Signature"));
        assertEquals(PLACEHOLDER, recorded.getHeader("Api-Key"));
        assertEquals(PLACEHOLDER, recorded.getRequestUrl().queryParameter("key"));
    }

    @Test
    public void aSecretsOnlyRequestIsRefreshedAsSecretsOnly() throws Exception {
        start(null);
        fixture.protectedServer.enqueue(new MockResponse().setBody("ok"));
        // the first fetch and the refresh both report a secrets-only API
        nextTokenFetch("UNPROTECTED_URL");
        try (Response response = heldClient("UNPROTECTED_URL").newCall(request("/data")).execute()) {
            assertEquals(200, response.code());
        }
        assertSecretsOnly("refreshed secrets-only", fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS));
        assertEquals("the refresh substitutes afresh", 4, sdk.count("fetchSecureStringAndWait"));
    }

    @Test
    public void aSecretsOnlyRequestRefreshedToSuccessGetsTheFullProtection() throws Exception {
        start(null);
        fixture.protectedServer.enqueue(new MockResponse().setBody("ok"));
        nextTokenFetch("UNPROTECTED_URL");
        try (Response response = heldClient("SUCCESS").newCall(request("/data")).execute()) {
            assertEquals(200, response.code());
        }
        RecordedRequest recorded = fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
        assertFalse(recorded.getHeader("Approov-Token").isEmpty());
        assertEquals("success", recorded.getHeader("Approov-Status"));
        assertNotNull(recorded.getHeader("Signature-Input"));
        assertEquals(SECRET, recorded.getHeader("Api-Key"));
        assertEquals(SECRET, recorded.getRequestUrl().queryParameter("key"));
    }

    // ---------------------------------------------------------------------------
    // redirects
    // ---------------------------------------------------------------------------

    @Test
    public void aRedirectFromATokenProtectedHostToASecretsOnlyHostCarriesTheSecretsOnly() throws Exception {
        start("\"fetchApproovToken\": ["
                + "{\"urlRegex\": \"https://localhost:.*\"},"
                + "{\"urlRegex\": \"https://127.0.0.1:.*\", \"status\": \"UNPROTECTED_URL\"}]");
        HttpUrl target = fixture.otherUrl("/other").newBuilder().addQueryParameter("key", PLACEHOLDER).build();
        fixture.protectedServer.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", target));
        fixture.otherServer.enqueue(new MockResponse().setBody("ok"));
        try (Response response = ApproovService.getOkHttpClient().newCall(request("/start")).execute()) {
            assertEquals(200, response.code());
        }
        RecordedRequest first = fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("control: the first hop is token-protected", "success", first.getHeader("Approov-Status"));
        RecordedRequest second = fixture.otherServer.takeRequest(5, TimeUnit.SECONDS);
        assertSecretsOnly("secrets-only redirect target", second);
        assertNull("Authorization is not carried to another origin", second.getHeader("Authorization"));
    }

    @Test
    public void aRedirectFromASecretsOnlyHostToAHostNotInApproovRestoresThePlaceholder() throws Exception {
        start("\"fetchApproovToken\": ["
                + "{\"urlRegex\": \"https://localhost:.*\", \"status\": \"UNPROTECTED_URL\"},"
                + "{\"urlRegex\": \"https://127.0.0.1:.*\", \"status\": \"UNKNOWN_URL\"}]");
        HttpUrl target = fixture.otherUrl("/other");
        fixture.protectedServer.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", target));
        fixture.otherServer.enqueue(new MockResponse().setBody("ok"));
        try (Response response = ApproovService.getOkHttpClient().newCall(request("/start")).execute()) {
            assertEquals(200, response.code());
        }
        assertSecretsOnly("control: the secrets-only first hop", fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS));
        RecordedRequest second = fixture.otherServer.takeRequest(5, TimeUnit.SECONDS);
        LocalHttpsFixture.assertNoApproovHeaders(second);
        assertEquals("the substituted header goes back to the app's placeholder", PLACEHOLDER,
                second.getHeader("Api-Key"));
    }

    @Test
    public void aSameOriginRedirectOnASecretsOnlyHostSubstitutesAgain() throws Exception {
        start("\"fetchApproovToken\": [{\"urlRegex\": \"https://localhost:.*\", \"status\": \"UNPROTECTED_URL\"}]");
        HttpUrl target = fixture.protectedServer.url("/next").newBuilder()
                .addQueryParameter("key", PLACEHOLDER).build();
        fixture.protectedServer.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", target));
        fixture.protectedServer.enqueue(new MockResponse().setBody("ok"));
        try (Response response = ApproovService.getOkHttpClient().newCall(request("/start")).execute()) {
            assertEquals(200, response.code());
        }
        assertSecretsOnly("first hop", fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS));
        RecordedRequest second = fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("/next", second.getRequestUrl().encodedPath());
        assertSecretsOnly("same-origin redirect", second);
        assertEquals("Authorization stays on the same origin", "Bearer app-credential",
                second.getHeader("Authorization"));
    }
}
