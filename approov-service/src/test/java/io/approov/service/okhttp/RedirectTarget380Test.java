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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.criticalblue.minisdk.testing.AttesterProxyController;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * The layer protects only the API the app
 * defines. A redirect to another origin is stripped of everything the layer added
 * (token, status, trace, signatures, substituted header values back to the app's
 * placeholders; OkHttp drops Authorization) and then evaluated from the start as a
 * new request for the target URL: a token fetched for the target URL, never the
 * first host's, secure strings only for a protected https target, signatures for
 * the new request; an unprotected target gets nothing. The target URL itself is
 * left exactly as the server sent it, including a secret the server echoed into it:
 * an echo is the server's responsibility and the layer does not restore it. Its
 * query values are never looked up, even under a substitution query parameter, so
 * only the app's own placeholders are substituted for a protected target.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class RedirectTarget380Test {
    private static final String PLACEHOLDER = "api-key-placeholder";
    private static final String SECRET = "real-api-key-value";
    private static final String FIRST_TOKEN = "token-for-the-first-host";
    private static final String TARGET_TOKEN = "token-for-the-target-host";

    private TwoOriginFixture fixture;
    private RecordingSdkFacade sdk;

    /**
     * @param targetProtected whether the other origin (127.0.0.1) is also an Approov
     *                        protected domain, with a token of its own
     */
    private void start(boolean targetProtected) throws Exception {
        fixture = new TwoOriginFixture(true, PLACEHOLDER, SECRET);
        if (targetProtected) {
            String name = "two-protected-" + java.util.UUID.randomUUID();
            AttesterProxyController.reset();
            AttesterProxyController.loadScenarioJson("{\"activeCase\": \"" + name + "\", \"cases\": {\"" + name + "\": {"
                    + "\"protectedDomains\": [\"localhost\", \"127.0.0.1\"],"
                    + "\"pins\": {\"public-key-sha256\": {\"localhost\": [], \"127.0.0.1\": []}},"
                    + "\"fetchApproovToken\": ["
                    + "{\"urlRegex\": \"https://localhost:.*\", \"token\": \"" + FIRST_TOKEN + "\"},"
                    + "{\"urlRegex\": \"https://127.0.0.1:.*\", \"token\": \"" + TARGET_TOKEN + "\"}],"
                    + "\"initialSecureStrings\": {\"" + PLACEHOLDER + "\": \"" + SECRET + "\"}}}}");
        }
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

    private Request firstRequest() {
        return new Request.Builder()
                .url(fixture.protectedServer.url("/start").newBuilder()
                        .addQueryParameter("key", PLACEHOLDER).build())
                .header("Api-Key", PLACEHOLDER)
                .header("Authorization", "Bearer app-credential")
                .build();
    }

    @Test
    public void anEchoedSecretIsFollowedAsTheServerSentItAndTheHeadersAreStripped() throws Exception {
        start(false);
        HttpUrl echo = fixture.otherUrl("/landing").newBuilder()
                .addQueryParameter("key", SECRET).addQueryParameter("x", "1").build();
        fixture.protectedServer.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", echo));
        fixture.otherServer.enqueue(new MockResponse().setBody("ok"));

        try (Response response = ApproovService.getOkHttpClient().newCall(firstRequest()).execute()) {
            assertEquals(200, response.code());
            // the target URL is the server's: neither the followed URL nor the Location
            // the app is shown is rewritten
            assertEquals(echo, response.request().url());
            assertEquals(echo.toString(), response.priorResponse().header("Location"));
        }

        RecordedRequest first = fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull("control: the first hop is protected", first.getHeader("Approov-Token"));
        assertEquals("control: the first hop is substituted", SECRET, first.getHeader("Api-Key"));
        assertEquals("control: the first hop is substituted", SECRET, first.getRequestUrl().queryParameter("key"));

        RecordedRequest second = fixture.otherServer.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("the target URL is sent as the server sent it", echo.encodedPath() + "?" + echo.encodedQuery(),
                second.getPath());
        LocalHttpsFixture.assertNoApproovHeaders(second);
        assertEquals("a substituted header goes back to the app's placeholder", PLACEHOLDER, second.getHeader("Api-Key"));
        assertNull("Authorization is not carried to another origin", second.getHeader("Authorization"));
        // the target is classified afresh by its own URL: the SDK answers that it is not
        // protected, so it gets nothing
        assertTrue("the target is classified by its own URL: " + sdk.snapshot(),
                sdk.snapshot().contains("fetchApproovTokenAndWait:" + echo));
    }

    @Test
    public void aRedirectToAnotherProtectedHostFetchesATokenForTheTarget() throws Exception {
        start(true);
        HttpUrl target = fixture.otherUrl("/other").newBuilder().addQueryParameter("key", PLACEHOLDER).build();
        fixture.protectedServer.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", target));
        fixture.otherServer.enqueue(new MockResponse().setBody("ok"));

        try (Response response = ApproovService.getOkHttpClient().newCall(firstRequest()).execute()) {
            assertEquals(200, response.code());
            assertEquals("the target URL is the server's", target, response.request().url());
        }

        RecordedRequest first = fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("control: the first host's token", FIRST_TOKEN, first.getHeader("Approov-Token"));

        RecordedRequest second = fixture.otherServer.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("the target carries its own token, not the first host's", TARGET_TOKEN,
                second.getHeader("Approov-Token"));
        assertTrue("a token is fetched for the target URL: " + sdk.snapshot(),
                sdk.snapshot().contains("fetchApproovTokenAndWait:" + target));
        assertEquals("a protected https target gets the secure string afresh", SECRET, second.getHeader("Api-Key"));
        assertEquals("the target query is the server's and is never substituted", PLACEHOLDER,
                second.getRequestUrl().queryParameter("key"));
        assertNull("Authorization is not carried to another origin", second.getHeader("Authorization"));
        String signatureInput = second.getHeader("Signature-Input");
        assertNotNull("the target request is signed afresh", signatureInput);
        assertTrue("the signature covers the target request: " + signatureInput,
                !signatureInput.equals(first.getHeader("Signature-Input")));
    }

    @Test
    public void aRedirectTargetQueryIsNeverLookedUpEvenUnderASubstitutionParameter() throws Exception {
        start(true);
        // the server puts in the Location a value that is not a secure string key and one that is
        HttpUrl target = fixture.otherUrl("/other").newBuilder()
                .addQueryParameter("key", "server-value")
                .addQueryParameter("x", "1")
                .addQueryParameter("key", PLACEHOLDER).build();
        fixture.protectedServer.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", target));
        fixture.otherServer.enqueue(new MockResponse().setBody("ok"));

        try (Response response = ApproovService.getOkHttpClient().newCall(firstRequest()).execute()) {
            assertEquals(200, response.code());
            assertEquals("the target URL is the server's", target, response.request().url());
        }

        RecordedRequest first = fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("control: the first hop is substituted", SECRET, first.getRequestUrl().queryParameter("key"));

        RecordedRequest second = fixture.otherServer.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("the target query is sent exactly as the server sent it",
                target.encodedPath() + "?" + target.encodedQuery(), second.getPath());
        assertEquals("the target carries its own token", TARGET_TOKEN, second.getHeader("Approov-Token"));
        assertEquals("the app's header placeholder is substituted for the target", SECRET,
                second.getHeader("Api-Key"));
        assertTrue("no secure string is fetched for a value the server sent: " + sdk.snapshot(),
                !sdk.snapshot().contains("fetchSecureStringAndWait:server-value"));
    }

    @Test
    public void aSameUrlRedirectSubstitutesTheAppQueryPlaceholderAgain() throws Exception {
        start(true);
        // a 303 back to the URL the request was sent to, as the server received it, turns it into a GET of the
        // app's own URL, so its query placeholder is restored and substituted again
        HttpUrl[] sent = new HttpUrl[1];
        Interceptor redirectToSelf = chain -> {
            Response response = chain.proceed(chain.request());
            if (sent[0] != null)
                return response;
            sent[0] = chain.request().url();
            return response.newBuilder().code(303).message("See Other").header("Location", sent[0].toString()).build();
        };
        ApproovService.setOkHttpClientBuilder(fixture.trustingBuilder().addNetworkInterceptor(redirectToSelf));
        fixture.protectedServer.enqueue(new MockResponse().setBody("first"));
        fixture.protectedServer.enqueue(new MockResponse().setBody("ok"));
        Request post = firstRequest().newBuilder().post(RequestBody.create("{}", MediaType.parse("application/json")))
                .build();

        try (Response response = ApproovService.getOkHttpClient().newCall(post).execute()) {
            assertEquals(200, response.code());
        }

        fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
        RecordedRequest second = fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("GET", second.getMethod());
        assertEquals("the app's query placeholder is substituted again", SECRET,
                second.getRequestUrl().queryParameter("key"));
        assertEquals("the app's header placeholder is substituted again", SECRET, second.getHeader("Api-Key"));
        assertEquals("secure strings fetched for each attempt: " + sdk.snapshot(), 4,
                sdk.count("fetchSecureStringAndWait"));
    }
}
