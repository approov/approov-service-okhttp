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
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * No log line carries a substituted secure string. A request whose protection is
 * reapplied at the network layer (a redirect, or an authenticator retry on the
 * same URL) is logged, and the URL the protection was applied to holds the
 * substituted query parameter, so that URL is never logged as such.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class LogRedaction380Test {
    private static final String PLACEHOLDER = "api-key-placeholder";
    private static final String SECRET = "real-api-key-value";

    private TwoOriginFixture fixture;

    @Before
    public void setUp() throws Exception {
        fixture = new TwoOriginFixture(true, PLACEHOLDER, SECRET);
        ApproovService.reset();
        // redaction is checked at the most verbose level, the loggable token included
        ApproovService.setLoggingLevel(ApproovLogLevel.DEBUG);
        fixture.initialize();
        ApproovService.addSubstitutionQueryParam("key");
        ShadowLog.reset();
    }

    @After
    public void tearDown() throws Exception {
        fixture.shutdown();
        ApproovService.reset();
    }

    private HttpUrl protectedUrl() {
        return fixture.protectedServer.url("/data").newBuilder().addQueryParameter("key", PLACEHOLDER).build();
    }

    // asserts that a rebuild was logged and that no log line holds the secret
    private void assertRebuildLoggedWithoutTheSecret() {
        boolean rebuilt = false;
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            assertFalse("secret logged: " + item.msg, item.msg.contains(SECRET));
            rebuilt |= item.msg.startsWith("Request rebuilt");
        }
        assertTrue("the rebuild was not logged", rebuilt);
    }

    @Test
    public void aCrossOriginRedirectIsLoggedWithoutTheSecret() throws Exception {
        ApproovService.setOkHttpClientBuilder(fixture.trustingBuilder());
        fixture.protectedServer.enqueue(new MockResponse().setResponseCode(302)
                .setHeader("Location", fixture.otherUrl("/landing")));
        fixture.otherServer.enqueue(new MockResponse().setBody("ok"));
        try (Response response = ApproovService.getOkHttpClient().newCall(
                new Request.Builder().url(protectedUrl()).build()).execute()) {
            assertEquals(200, response.code());
        }
        RecordedRequest first = fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("control: substituted", SECRET, first.getRequestUrl().queryParameter("key"));
        assertRebuildLoggedWithoutTheSecret();
    }

    @Test
    public void anAuthenticatorRetryIsLoggedWithoutTheSecret() throws Exception {
        ApproovService.setOkHttpClientBuilder(fixture.trustingBuilder().authenticator((route, response) ->
                (response.priorResponse() != null) ? null
                        : response.request().newBuilder().header("Authorization", "Bearer renewed").build()));
        fixture.protectedServer.enqueue(new MockResponse().setResponseCode(401));
        fixture.protectedServer.enqueue(new MockResponse().setBody("ok"));
        try (Response response = ApproovService.getOkHttpClient().newCall(
                new Request.Builder().url(protectedUrl()).build()).execute()) {
            assertEquals(200, response.code());
        }
        fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
        RecordedRequest retry = fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("control: the retry is substituted afresh", SECRET, retry.getRequestUrl().queryParameter("key"));
        assertEquals("Bearer renewed", retry.getHeader("Authorization"));
        assertRebuildLoggedWithoutTheSecret();
    }
}
