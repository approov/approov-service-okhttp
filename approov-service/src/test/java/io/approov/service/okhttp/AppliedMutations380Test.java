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
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * The layer strips what it applied, whatever the mutator does to the
 * ApproovRequestMutations it is handed. Its setters are public, so a processed
 * request callback can clear or rename the header keys; the strip on a redirect
 * to an origin Approov does not protect must still remove the token, the status
 * and the trace headers, and signing must still cover the token header.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class AppliedMutations380Test {
    private TwoOriginFixture fixture;

    @Before
    public void setUp() throws Exception {
        fixture = new TwoOriginFixture(true);
        ApproovService.reset();
        fixture.initialize();
        ApproovService.enableMessageSigning();
        ApproovService.setOkHttpClientBuilder(fixture.trustingBuilder());
        ApproovService.setServiceMutator(new ApproovServiceMutator() {
            @Override
            public Request handleInterceptorProcessedRequest(Request request, ApproovRequestMutations changes)
                    throws IOException {
                changes.setTokenHeaderKey(null);
                changes.setStatusHeaderKey("Something-Else");
                changes.setTraceIDHeaderKey(null);
                return request;
            }
        });
    }

    @After
    public void tearDown() throws Exception {
        fixture.shutdown();
        ApproovService.reset();
    }

    @Test
    public void aRedirectIsStrippedOfWhatTheLayerApplied() throws Exception {
        fixture.protectedServer.enqueue(new MockResponse().setResponseCode(302)
                .setHeader("Location", fixture.otherUrl("/landing")));
        fixture.otherServer.enqueue(new MockResponse().setBody("ok"));
        try (Response response = ApproovService.getOkHttpClient().newCall(
                new Request.Builder().url(fixture.protectedServer.url("/start")).build()).execute()) {
            assertEquals(200, response.code());
        }
        RecordedRequest first = fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull("control: the first hop is protected", first.getHeader("Approov-Token"));
        LocalHttpsFixture.assertNoApproovHeaders(fixture.otherServer.takeRequest(5, TimeUnit.SECONDS));
    }

    @Test
    public void theTokenHeaderIsSigned() throws Exception {
        fixture.protectedServer.enqueue(new MockResponse().setBody("ok"));
        try (Response response = ApproovService.getOkHttpClient().newCall(
                new Request.Builder().url(fixture.protectedServer.url("/start")).build()).execute()) {
            assertEquals(200, response.code());
        }
        RecordedRequest recorded = fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull("a request carrying the token is signed", recorded.getHeader("Signature-Input"));
    }
}
