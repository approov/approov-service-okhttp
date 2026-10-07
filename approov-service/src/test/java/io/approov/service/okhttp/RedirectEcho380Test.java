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
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * SPECIFICATION 7.3 (changed 2026-10-07): a redirect target whose query echoes a
 * secure string the layer substituted on the request has the value restored to
 * its placeholder, compared by digest, in what the app is shown and in the next
 * hop's processing. In OkHttp the layer rewrites the echoed value in the
 * redirect response's Location header at the network layer, before OkHttp builds
 * the followup from it, so the followed request (response.request().url()), the
 * Location of the redirect response (priorResponse(), or the response itself
 * when redirects are not followed) and the next hop all carry the placeholder;
 * the next hop is then classified afresh, receiving the placeholder if it is not
 * a protected https destination and the secure string substituted afresh if it
 * is. Nothing else in the target URL is changed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class RedirectEcho380Test {
    private static final String PLACEHOLDER = "api-key-placeholder";
    private static final String SECRET = "real-api-key-value";
    // a secure string OkHttp percent-encodes in a query
    private static final String SPACED_PLACEHOLDER = "spaced-placeholder";
    private static final String SPACED_SECRET = "spaced secret";

    private TwoOriginFixture fixture;
    private RecordingSdkFacade sdk;

    @Before
    public void setUp() throws Exception {
        fixture = new TwoOriginFixture(true, PLACEHOLDER, SECRET, SPACED_PLACEHOLDER, SPACED_SECRET);
        ApproovService.reset();
        sdk = RecordingSdkFacade.install();
        fixture.initialize();
        ApproovService.setServiceMutator(ApproovServiceMutator.ALWAYS_PROCEED);
        ApproovService.addSubstitutionQueryParam("key");
        ApproovService.addSubstitutionQueryParam("spaced");
        ApproovService.setOkHttpClientBuilder(fixture.trustingBuilder());
    }

    @After
    public void tearDown() throws Exception {
        fixture.shutdown();
        ApproovService.reset();
    }

    private Request start() {
        return new Request.Builder().url(fixture.protectedServer.url("/start").newBuilder()
                .addQueryParameter("key", PLACEHOLDER).addQueryParameter("x", "1").build()).build();
    }

    private static void assertNoSecret(String what, String text) {
        assertNotNull(what, text);
        assertFalse(what + " carries the secret: " + text, text.contains(SECRET));
    }

    @Test
    public void control_theFirstHopIsSubstituted() throws Exception {
        fixture.protectedServer.enqueue(new MockResponse().setBody("ok"));
        try (Response response = ApproovService.getOkHttpClient().newCall(start()).execute()) {
            assertEquals(200, response.code());
        }
        RecordedRequest first = fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
        assertEquals(SECRET, first.getRequestUrl().queryParameter("key"));
    }

    @Test
    public void anEchoToAnotherOriginReachesItAsThePlaceholder() throws Exception {
        HttpUrl echo = fixture.otherUrl("/steal").newBuilder()
                .addQueryParameter("key", SECRET).addQueryParameter("x", "1").build();
        fixture.protectedServer.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", echo));
        fixture.otherServer.enqueue(new MockResponse().setBody("ok"));

        try (Response response = ApproovService.getOkHttpClient().newCall(start()).execute()) {
            assertEquals(200, response.code());
            // what the app is shown: the followed request's URL and the redirect's Location
            // only the echoed value changes: the rest of the target is as the server sent it
            String expected = echo.toString().replace("key=" + SECRET, "key=" + PLACEHOLDER);
            HttpUrl followed = response.request().url();
            assertEquals(PLACEHOLDER, followed.queryParameter("key"));
            assertEquals(expected, followed.toString());
            Response redirect = response.priorResponse();
            assertNotNull(redirect);
            assertNoSecret("the Location the app is shown", redirect.header("Location"));
            assertEquals(expected, redirect.header("Location"));
        }

        RecordedRequest first = fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("control: the protected hop is substituted", SECRET, first.getRequestUrl().queryParameter("key"));
        RecordedRequest second = fixture.otherServer.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("/steal", second.getRequestUrl().encodedPath());
        assertEquals("echoed secret sent on", PLACEHOLDER, second.getRequestUrl().queryParameter("key"));
        assertEquals("1", second.getRequestUrl().queryParameter("x"));
        assertNull("not protected", second.getHeader("Approov-Token"));
    }

    @Test
    public void anEchoWithinTheProtectedOriginIsSubstitutedAfresh() throws Exception {
        fixture.protectedServer.enqueue(new MockResponse().setResponseCode(302)
                .setHeader("Location", "/next?key=" + SECRET + "&y=2"));
        fixture.protectedServer.enqueue(new MockResponse().setBody("ok"));

        try (Response response = ApproovService.getOkHttpClient().newCall(start()).execute()) {
            assertEquals(200, response.code());
            assertEquals(PLACEHOLDER, response.request().url().queryParameter("key"));
            assertEquals("/next", response.request().url().encodedPath());
            assertEquals("/next?key=" + PLACEHOLDER + "&y=2", response.priorResponse().header("Location"));
        }

        fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
        RecordedRequest second = fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("/next", second.getRequestUrl().encodedPath());
        assertEquals("a protected target is substituted afresh", SECRET,
                second.getRequestUrl().queryParameter("key"));
        assertEquals("2", second.getRequestUrl().queryParameter("y"));
        assertEquals("the placeholder was looked up again", 2,
                sdk.count("fetchSecureStringAndWait:" + PLACEHOLDER));
    }

    @Test
    public void anEchoIsRestoredInTheLocationWhenRedirectsAreNotFollowed() throws Exception {
        String echo = fixture.otherUrl("/steal").toString() + "?key=" + SECRET;
        fixture.protectedServer.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", echo));
        ApproovService.setOkHttpClientBuilder(fixture.trustingBuilder().followRedirects(false));

        OkHttpClient client = ApproovService.getOkHttpClient();
        try (Response response = client.newCall(start()).execute()) {
            assertEquals(302, response.code());
            assertEquals(fixture.otherUrl("/steal").toString() + "?key=" + PLACEHOLDER, response.header("Location"));
        }
        assertEquals(0, fixture.otherServer.getRequestCount());
    }

    @Test
    public void aPercentEncodedEchoIsRestored() throws Exception {
        HttpUrl url = fixture.protectedServer.url("/start").newBuilder()
                .addQueryParameter("spaced", SPACED_PLACEHOLDER).build();
        HttpUrl echo = fixture.otherUrl("/steal").newBuilder().addQueryParameter("spaced", SPACED_SECRET).build();
        assertTrue("the echo is percent-encoded: " + echo, echo.toString().contains("spaced%20secret"));
        fixture.protectedServer.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", echo));
        fixture.otherServer.enqueue(new MockResponse().setBody("ok"));

        try (Response response = ApproovService.getOkHttpClient().newCall(
                new Request.Builder().url(url).build()).execute()) {
            assertEquals(200, response.code());
            assertEquals(SPACED_PLACEHOLDER, response.request().url().queryParameter("spaced"));
        }
        RecordedRequest first = fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("control: substituted", SPACED_SECRET, first.getRequestUrl().queryParameter("spaced"));
        RecordedRequest second = fixture.otherServer.takeRequest(5, TimeUnit.SECONDS);
        assertEquals(SPACED_PLACEHOLDER, second.getRequestUrl().queryParameter("spaced"));
    }

    @Test
    public void aRebuiltAttemptToAnotherUrlHasAnEchoRestoredBeforeItIsReclassified() {
        // the next hop's processing restores an echo whatever brought it there, not
        // only a Location the layer saw: here an attempt whose URL differs from the
        // one the protection was applied to and still carries the secret
        ApproovRequestMutations changes = new ApproovRequestMutations();
        changes.setTokenHeaderKey("Approov-Token");
        ApproovRequestFreshness marker = new ApproovRequestFreshness("https://localhost/start", changes);
        marker.addQuerySubstitutions(java.util.Collections.singletonList(
                ApproovRequestFreshness.querySubstitution("key", PLACEHOLDER, SECRET)));
        Request attempt = new Request.Builder()
                .url("https://elsewhere.example/x?a=" + SECRET + "&key=" + SECRET + "&key=other#key=" + SECRET)
                .header("Approov-Token", "token")
                .tag(ApproovRequestFreshness.class, marker)
                .build();
        Request stripped = ApproovTokenInterceptor.stripProtection(attempt, marker, false);
        assertEquals("only the substituted parameter's echoed value changes",
                "https://elsewhere.example/x?a=" + SECRET + "&key=" + PLACEHOLDER + "&key=other#key=" + SECRET,
                stripped.url().toString());
        assertNull(stripped.header("Approov-Token"));
    }

    @Test
    public void control_aTargetThatDoesNotEchoTheSecretIsLeftAsSent() throws Exception {
        HttpUrl target = fixture.otherUrl("/elsewhere").newBuilder()
                .addQueryParameter("key", "unrelated-value").addQueryParameter("x", "1").build();
        fixture.protectedServer.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", target));
        fixture.otherServer.enqueue(new MockResponse().setBody("ok"));

        try (Response response = ApproovService.getOkHttpClient().newCall(start()).execute()) {
            assertEquals(200, response.code());
            assertEquals(target, response.request().url());
            assertEquals(target.toString(), response.priorResponse().header("Location"));
        }
        fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
        RecordedRequest second = fixture.otherServer.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("unrelated-value", second.getRequestUrl().queryParameter("key"));
    }
}
