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

import com.criticalblue.approovsdk.Approov;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.net.InetAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * A request sent over plain http keeps its placeholders even when its token fetch
 * returns SUCCESS, under every mutator: CLOSE_FAILURE, ALWAYS_PROCEED, DEFAULT and
 * a custom mutator that accepts every substitution. No secure string is fetched
 * and the substitution hooks are not consulted, while the token header shows that
 * the SUCCESS path was taken. The mini SDK answers BAD_URL for every http URL, so
 * the SDK facade fetches the token for the https form of the URL, standing in for
 * an SDK that answers SUCCESS for a cleartext URL.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class CleartextSuccess380Test {
    private static final String PLACEHOLDER = "api-key-placeholder";
    private static final String SECRET = "real-api-key-value";

    private TwoOriginFixture fixture;
    private MockWebServer cleartext;
    private RecordingSdkFacade sdk;

    // the number of substitution hook calls made to the accepting mutator
    private final AtomicInteger substitutionHooks = new AtomicInteger();

    // a custom mutator that proceeds on every token fetch and accepts every substitution
    private final ApproovServiceMutator acceptEverything = new ApproovServiceMutator() {
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

        @Override
        public String toString() {
            return "accept every substitution";
        }
    };

    @Before
    public void setUp() throws Exception {
        // localhost is an Approov API domain with no pins, so a cleartext connection to it is allowed
        fixture = new TwoOriginFixture(true, PLACEHOLDER, SECRET);
        cleartext = new MockWebServer();
        cleartext.start(InetAddress.getByName("localhost"), 0);
        ApproovService.reset();
        fixture.initialize();
        sdk = new RecordingSdkFacade() {
            @Override
            public Approov.TokenFetchResult fetchApproovTokenAndWait(String url) {
                return super.fetchApproovTokenAndWait(url.replaceFirst("^http:", "https:"));
            }
        };
        ApproovService.setSdkFacadeForTesting(sdk);
        ApproovService.addSubstitutionHeader("Api-Key", null);
        ApproovService.addSubstitutionQueryParam("key");
        ApproovService.setOkHttpClientBuilder(fixture.trustingBuilder());
    }

    @After
    public void tearDown() throws Exception {
        cleartext.shutdown();
        fixture.shutdown();
        ApproovService.reset();
    }

    @Test
    public void aCleartextSuccessKeepsItsPlaceholdersUnderEveryMutator() throws Exception {
        ApproovServiceMutator[] mutators = {ApproovServiceMutator.CLOSE_FAILURE,
            ApproovServiceMutator.ALWAYS_PROCEED, ApproovServiceMutator.DEFAULT, acceptEverything};
        for (ApproovServiceMutator mutator : mutators) {
            ApproovService.setServiceMutator(mutator);
            String what = String.valueOf(mutator);
            cleartext.enqueue(new MockResponse().setBody("ok"));
            HttpUrl url = cleartext.url("/data").newBuilder().addQueryParameter("key", PLACEHOLDER).build();
            int secureStringFetches = sdk.count("fetchSecureStringAndWait");
            try (Response response = ApproovService.getOkHttpClient().newCall(
                    new Request.Builder().url(url).header("Api-Key", PLACEHOLDER).build()).execute()) {
                assertEquals(what, 200, response.code());
            }
            RecordedRequest recorded = cleartext.takeRequest(5, TimeUnit.SECONDS);
            assertNotNull(what + ": the SUCCESS path adds the token", recorded.getHeader("Approov-Token"));
            assertEquals(what, "success", recorded.getHeader("Approov-Status"));
            assertEquals(what + ": secret sent in cleartext", PLACEHOLDER, recorded.getHeader("Api-Key"));
            assertEquals(what + ": secret sent in cleartext", PLACEHOLDER,
                    recorded.getRequestUrl().queryParameter("key"));
            assertFalse(what + ": secret on the wire", recorded.getRequestLine().contains(SECRET));
            assertEquals(what + ": secure string fetched", secureStringFetches,
                    sdk.count("fetchSecureStringAndWait"));
        }
        assertEquals("substitution hooks consulted", 0, substitutionHooks.get());
    }
}
