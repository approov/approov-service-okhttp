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
 * A secure string never travels in cleartext. Under ALWAYS_PROCEED (or any mutator
 * that lets a cleartext request proceed) the SDK answers BAD_URL for an http URL
 * and the fetch hook returns true, so substitution used to run and send the
 * secret over plain HTTP: on a request the app made, or on a redirect from a
 * protected host to an attacker's http URL carrying the placeholder. The
 * placeholder now stays (SPECIFICATION 1.4: a substitution that produces no
 * value) and a warning names the header or parameter.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class CleartextSubstitution380Test {
    private static final String PLACEHOLDER = "api-key-placeholder";
    private static final String SECRET = "real-api-key-value";

    private TwoOriginFixture fixture;

    @Before
    public void setUp() throws Exception {
        // the other origin is cleartext HTTP on 127.0.0.1
        fixture = new TwoOriginFixture(false, PLACEHOLDER, SECRET);
        ApproovService.reset();
        fixture.initialize();
        ApproovService.setServiceMutator(ApproovServiceMutator.ALWAYS_PROCEED);
        ApproovService.addSubstitutionHeader("Api-Key", null);
        ApproovService.addSubstitutionQueryParam("key");
        ApproovService.setOkHttpClientBuilder(fixture.trustingBuilder());
        ShadowLog.reset();
    }

    @After
    public void tearDown() throws Exception {
        fixture.shutdown();
        ApproovService.reset();
    }

    private Request request(HttpUrl base) {
        return new Request.Builder()
                .url(base.newBuilder().addQueryParameter("key", PLACEHOLDER).build())
                .header("Api-Key", PLACEHOLDER)
                .build();
    }

    private void send(Request request) throws Exception {
        try (Response response = ApproovService.getOkHttpClient().newCall(request).execute()) {
            assertEquals(200, response.code());
        }
    }

    @Test
    public void aProtectedHttpsRequestIsSubstituted() throws Exception {
        fixture.protectedServer.enqueue(new MockResponse().setBody("ok"));
        send(request(fixture.protectedServer.url("/data")));
        RecordedRequest recorded = fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
        assertEquals(SECRET, recorded.getHeader("Api-Key"));
        assertEquals(SECRET, recorded.getRequestUrl().queryParameter("key"));
        assertEquals("success", recorded.getHeader("Approov-Status"));
    }

    @Test
    public void aCleartextRequestKeepsItsPlaceholders() throws Exception {
        fixture.otherServer.enqueue(new MockResponse().setBody("ok"));
        send(request(fixture.otherUrl("/data")));
        RecordedRequest recorded = fixture.otherServer.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("BAD_URL proceeds under ALWAYS_PROCEED", "bad_url", recorded.getHeader("Approov-Status"));
        assertEquals("secret sent in cleartext", PLACEHOLDER, recorded.getHeader("Api-Key"));
        assertEquals("secret sent in cleartext", PLACEHOLDER, recorded.getRequestUrl().queryParameter("key"));
        assertWarned();
    }

    @Test
    public void aRedirectToCleartextNeverCarriesTheSecret() throws Exception {
        // an open redirect on the protected host to an http URL holding the placeholder
        HttpUrl steal = fixture.otherUrl("/steal").newBuilder().addQueryParameter("key", PLACEHOLDER).build();
        fixture.protectedServer.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", steal));
        fixture.otherServer.enqueue(new MockResponse().setBody("ok"));

        send(request(fixture.protectedServer.url("/data")));

        RecordedRequest first = fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("control: the protected hop is substituted", SECRET, first.getHeader("Api-Key"));
        RecordedRequest second = fixture.otherServer.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("/steal", second.getRequestUrl().encodedPath());
        assertEquals("secret sent in cleartext", PLACEHOLDER, second.getHeader("Api-Key"));
        assertEquals("secret sent in cleartext", PLACEHOLDER, second.getRequestUrl().queryParameter("key"));
        assertWarned();
    }

    // a warning names the header and the parameter, never the value
    private void assertWarned() {
        boolean header = false;
        boolean query = false;
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            assertFalse("secret logged: " + item.msg, item.msg.contains(SECRET));
            if (item.type == android.util.Log.WARN && item.msg.contains("not sent over TLS")) {
                header |= item.msg.contains("Api-Key");
                query |= item.msg.contains("key");
            }
        }
        assertTrue("no warning naming the header", header);
        assertTrue("no warning naming the query parameter", query);
    }
}
