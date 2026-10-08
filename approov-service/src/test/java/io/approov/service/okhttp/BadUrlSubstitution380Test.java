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
import org.robolectric.shadows.ShadowLog;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * A request whose token fetch returned
 * BAD_URL never receives a secure string, under every mutator that lets it
 * proceed, ALWAYS_PROCEED and custom mutators included, even when its URL is
 * https. The SDK reports BAD_URL for a URL it cannot parse
 * (core-project-approov#822), so such a request is not known to go to a
 * protected domain. No secure string is fetched, the substitution hooks are not
 * consulted, the placeholder goes out in the header and the query, and a
 * warning names the header or parameter, never the value. CLOSE_FAILURE still
 * aborts on BAD_URL.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class BadUrlSubstitution380Test {
    private static final String PLACEHOLDER = "api-key-placeholder";
    private static final String SECRET = "real-api-key-value";

    private TwoOriginFixture fixture;
    private RecordingSdkFacade sdk;

    /**
     * Lets every token status proceed and accepts every substitution result,
     * counting the substitution hooks it is asked.
     */
    static final class AcceptEverything implements ApproovServiceMutator {
        final AtomicInteger substitutionHooks = new AtomicInteger();

        @Override
        public boolean handleInterceptorFetchTokenResult(Approov.TokenFetchResult approovResults, String url) {
            return true;
        }

        @Override
        public boolean handleInterceptorHeaderSubstitutionResult(Approov.TokenFetchResult approovResults,
                String header) {
            substitutionHooks.incrementAndGet();
            return true;
        }

        @Override
        public boolean handleInterceptorQueryParamSubstitutionResult(Approov.TokenFetchResult approovResults,
                String queryKey) {
            substitutionHooks.incrementAndGet();
            return true;
        }
    }

    @Before
    public void setUp() throws Exception {
        fixture = new TwoOriginFixture(true, PLACEHOLDER, SECRET);
        ApproovService.reset();
        sdk = RecordingSdkFacade.install();
        // redaction is checked at the most verbose level, the loggable token included
        ApproovService.setLoggingLevel(ApproovLogLevel.DEBUG);
        fixture.initialize();
        ApproovService.addSubstitutionHeader("Api-Key", null);
        ApproovService.addSubstitutionQueryParam("key");
        ApproovService.setOkHttpClientBuilder(fixture.trustingBuilder());
    }

    @After
    public void tearDown() throws Exception {
        fixture.shutdown();
        ApproovService.reset();
    }

    private Request request() {
        HttpUrl url = fixture.protectedServer.url("/data").newBuilder()
                .addQueryParameter("key", PLACEHOLDER).build();
        return new Request.Builder().url(url).header("Api-Key", PLACEHOLDER).build();
    }

    // the next token fetch (and only it) is answered BAD_URL for the https URL
    private static void nextTokenFetchIsBadUrl() {
        AttesterProxyController.setNextAttestationDirectiveJson(
                "{\"operation\": \"fetchApproovToken\", \"response\": {\"status\": \"BAD_URL\"}}");
    }

    private RecordedRequest sendAndTake(Request request) throws Exception {
        try (Response response = ApproovService.getOkHttpClient().newCall(request).execute()) {
            assertEquals(200, response.code());
        }
        return fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
    }

    private void assertNoSecureString(String what, RecordedRequest recorded) {
        assertEquals(what + ": BAD_URL proceeds", "bad_url", recorded.getHeader("Approov-Status"));
        assertEquals(what + ": secret in the header", PLACEHOLDER, recorded.getHeader("Api-Key"));
        assertEquals(what + ": secret in the query", PLACEHOLDER, recorded.getRequestUrl().queryParameter("key"));
        assertEquals(what + ": a secure string was fetched", 0, sdk.count("fetchSecureStringAndWait"));
        boolean header = false;
        boolean query = false;
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            assertFalse(what + ": secret logged: " + item.msg, item.msg.contains(SECRET));
            if ((item.type == android.util.Log.WARN) && item.msg.contains("token fetch status BAD_URL")) {
                assertFalse(what + ": placeholder logged: " + item.msg, item.msg.contains(PLACEHOLDER));
                header |= item.msg.contains("header Api-Key");
                query |= item.msg.contains("query parameter key");
            }
        }
        assertTrue(what + ": no warning naming the header", header);
        assertTrue(what + ": no warning naming the query parameter", query);
    }

    @Test
    public void control_aSuccessfulFetchIsSubstituted() throws Exception {
        ApproovService.setServiceMutator(ApproovServiceMutator.ALWAYS_PROCEED);
        fixture.protectedServer.enqueue(new MockResponse().setBody("ok"));
        RecordedRequest recorded = sendAndTake(request());
        assertEquals("success", recorded.getHeader("Approov-Status"));
        assertEquals(SECRET, recorded.getHeader("Api-Key"));
        assertEquals(SECRET, recorded.getRequestUrl().queryParameter("key"));
    }

    @Test
    public void alwaysProceedNeverSubstitutesIntoABadUrlRequest() throws Exception {
        ApproovService.setServiceMutator(ApproovServiceMutator.ALWAYS_PROCEED);
        fixture.protectedServer.enqueue(new MockResponse().setBody("ok"));
        ShadowLog.reset();
        nextTokenFetchIsBadUrl();
        assertNoSecureString("ALWAYS_PROCEED", sendAndTake(request()));
    }

    @Test
    public void aCustomMutatorAcceptingEverySubstitutionIsNotConsulted() throws Exception {
        AcceptEverything mutator = new AcceptEverything();
        ApproovService.setServiceMutator(mutator);
        fixture.protectedServer.enqueue(new MockResponse().setBody("ok"));
        ShadowLog.reset();
        nextTokenFetchIsBadUrl();
        assertNoSecureString("custom mutator", sendAndTake(request()));
        assertEquals("substitution hooks consulted", 0, mutator.substitutionHooks.get());
    }

    @Test
    public void control_closeFailureStillAbortsOnBadUrl() throws Exception {
        ApproovService.setServiceMutator(ApproovServiceMutator.CLOSE_FAILURE);
        nextTokenFetchIsBadUrl();
        try (Response response = ApproovService.getOkHttpClient().newCall(request()).execute()) {
            fail("CLOSE_FAILURE must abort on BAD_URL, got " + response.code());
        } catch (ApproovFetchStatusException e) {
            assertSame(Approov.TokenFetchStatus.BAD_URL, e.getTokenFetchStatus());
        } catch (IOException e) {
            throw new AssertionError("expected ApproovFetchStatusException", e);
        }
        assertEquals("nothing sent", 0, fixture.protectedServer.getRequestCount());
        assertEquals("a secure string was fetched", 0, sdk.count("fetchSecureStringAndWait"));
    }
}
