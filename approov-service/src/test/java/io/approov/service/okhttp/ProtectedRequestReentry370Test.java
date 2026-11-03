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

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.TimeUnit;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * An app interceptor that sends a request again with response.request(), as an
 * auth-refresh interceptor does on a 401, hands the layer a request it already
 * protected: its substitution header and query parameter hold the secure string,
 * not the placeholder. The layer protects it again from the placeholders and sends
 * one token and one signature.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class ProtectedRequestReentry370Test {
    private static final String PLACEHOLDER = "api-key-placeholder";
    private static final String SECRET = "real-api-key-value";

    private TwoOriginFixture fixture;
    private RecordingSdk sdk;

    @After
    public void tearDown() throws Exception {
        if (fixture != null)
            fixture.shutdown();
        ApproovService.reset();
        RecordingSdk.uninstall();
    }

    @Test
    public void aRequestSentAgainFromItsResponseIsProtectedFromThePlaceholders() throws Exception {
        fixture = new TwoOriginFixture(true, PLACEHOLDER, SECRET);
        ApproovService.reset();
        sdk = RecordingSdk.install();
        fixture.initialize();
        ApproovService.addSubstitutionHeader("Api-Key", null);
        ApproovService.addSubstitutionQueryParam("key");
        TestSigning.install();
        Interceptor resend = chain -> {
            Response response = chain.proceed(chain.request());
            if (response.code() != 401)
                return response;
            Request again = response.request();
            response.close();
            return chain.proceed(again);
        };
        ApproovService.setOkHttpClientBuilder(fixture.trustingBuilder().addInterceptor(resend));

        fixture.protectedServer.enqueue(new MockResponse().setResponseCode(401));
        fixture.protectedServer.enqueue(new MockResponse().setBody("ok"));
        Request request = new Request.Builder()
                .url(fixture.protectedServer.url("/data").newBuilder().addQueryParameter("key", PLACEHOLDER).build())
                .header("Api-Key", PLACEHOLDER)
                .build();
        try (Response response = ApproovService.getOkHttpClient().newCall(request).execute()) {
            assertEquals(200, response.code());
        }

        RecordedRequest first = fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
        RecordedRequest again = fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("control: the first attempt is substituted", SECRET, first.getHeader("Api-Key"));
        assertEquals("the header carries the secret again", SECRET, again.getHeader("Api-Key"));
        assertEquals("the query carries the secret again", SECRET, again.getRequestUrl().queryParameter("key"));
        assertEquals("every secure string fetch is for the placeholder", sdk.count("fetchSecureStringAndWait"),
                sdk.count("fetchSecureStringAndWait:" + PLACEHOLDER));
        assertEquals("one token header", 1, again.getHeaders().values("Approov-Token").size());
        assertNotNull(again.getHeader("Signature"));
        assertEquals("one signature", 1, again.getHeaders().values("Signature").size());
        assertEquals("one signature input", 1, again.getHeaders().values("Signature-Input").size());
    }
}
