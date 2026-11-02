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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * Approov's freshness and pinning network interceptors are appended after the
 * app's own network interceptors (added through setOkHttpClientBuilder), as in
 * 3.5.8, so that they run last, immediately before transmission. An app network
 * interceptor therefore sees each attempt before Approov strips or refreshes its
 * protection, and whatever it sees, the wire to an origin Approov does not
 * protect never carries the token or the substituted secrets of the original
 * destination.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class InterceptorOrder370Test {
    private static final String PLACEHOLDER = "api-key-placeholder";
    private static final String SECRET = "real-api-key-value";

    private TwoOriginFixture fixture;

    // the URL and headers each attempt had when the app's network interceptor saw it
    private final List<String> seenUrls = Collections.synchronizedList(new ArrayList<>());
    private final List<Headers> seenHeaders = Collections.synchronizedList(new ArrayList<>());

    @Before
    public void setUp() throws Exception {
        fixture = new TwoOriginFixture(true, PLACEHOLDER, SECRET);
        ApproovService.reset();
        fixture.initialize();
        ApproovService.addSubstitutionHeader("Api-Key", null);
        TestSigning.install();
        Interceptor logger = chain -> {
            seenUrls.add(chain.request().url().toString());
            seenHeaders.add(chain.request().headers());
            return chain.proceed(chain.request());
        };
        ApproovService.setOkHttpClientBuilder(fixture.trustingBuilder().addNetworkInterceptor(logger));
    }

    @After
    public void tearDown() throws Exception {
        fixture.shutdown();
        ApproovService.reset();
    }

    @Test
    public void approovNetworkInterceptorsComeLast() {
        List<Interceptor> network = ApproovService.getOkHttpClient().networkInterceptors();
        assertEquals("the app's interceptor and Approov's two: " + network, 3, network.size());
        assertFalse("first is the app's: " + network.get(0), (network.get(0) instanceof ApproovFreshnessInterceptor)
                || (network.get(0) instanceof ApproovPinningInterceptor));
        assertTrue("second: " + network.get(1), network.get(1) instanceof ApproovFreshnessInterceptor);
        assertTrue("third: " + network.get(2), network.get(2) instanceof ApproovPinningInterceptor);
    }

    @Test
    public void aCrossOriginRedirectIsStrippedAfterTheAppNetworkInterceptor() throws Exception {
        String elsewhere = fixture.otherUrl("/landing").toString();
        fixture.protectedServer.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", elsewhere));
        fixture.otherServer.enqueue(new MockResponse().setBody("ok"));

        Request request = new Request.Builder().url(fixture.protectedServer.url("/start"))
                .header("Api-Key", PLACEHOLDER).build();
        try (Response response = ApproovService.getOkHttpClient().newCall(request).execute()) {
            assertEquals(200, response.code());
        }

        // control: the first hop is protected, and the app's interceptor sees it so
        RecordedRequest first = fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(first.getHeader("Approov-Token"));
        assertEquals(SECRET, first.getHeader("Api-Key"));
        assertEquals(2, seenHeaders.size());
        assertNotNull("first hop seen protected", seenHeaders.get(0).get("Approov-Token"));

        // the wire at the other origin carries nothing of the protected destination
        RecordedRequest second = fixture.otherServer.takeRequest(5, TimeUnit.SECONDS);
        LocalHttpsFixture.assertNoApproovHeaders(second);
        assertEquals(PLACEHOLDER, second.getHeader("Api-Key"));

        // the app's network interceptor runs before Approov's, so it saw the redirect hop as OkHttp
        // built it, still carrying the token of the protected destination that Approov then stripped
        assertEquals(elsewhere, seenUrls.get(1));
        Headers redirected = seenHeaders.get(1);
        assertEquals("the app's interceptor ran before the strip: " + redirected,
                first.getHeader("Approov-Token"), redirected.get("Approov-Token"));
    }
}
