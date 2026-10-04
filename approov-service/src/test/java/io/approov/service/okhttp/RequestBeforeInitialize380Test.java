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

import java.util.Collections;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * A request made before any initialize, or in bypass mode, goes out without
 * Approov processing (SPECIFICATION 5.7(f), TESTING_REQUIREMENTS 1 "Request
 * Before Initialize"): no token, trace, status or signature headers, no
 * substitutions and no Approov pins. The layer neither throws nor holds it, and
 * makes no SDK call, even though the SDK was initialized elsewhere in the process
 * and protects the host. A client obtained before initialize is protected once
 * initialize enables protection. Mirrors approov-service-android's
 * RequestBeforeInitializeTest.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class RequestBeforeInitialize380Test {
    private LocalHttpsFixture fixture;
    private RecordingSdkFacade sdk;

    @Before
    public void setUp() throws Exception {
        fixture = new LocalHttpsFixture(false);
        fixture.initializeSdkElsewhere();
        ApproovService.reset();
        sdk = RecordingSdkFacade.install();
    }

    @After
    public void tearDown() throws Exception {
        fixture.shutdown();
        ApproovService.reset();
    }

    private RecordedRequest sendWithPlaceholders(OkHttpClient client) throws Exception {
        fixture.server.enqueue(new MockResponse().setBody("ok"));
        Request request = new Request.Builder()
                .url(fixture.server.url("/v1/data?api_key=placeholder"))
                .header("Api-Key", "placeholder")
                .post(RequestBody.create("{}", MediaType.get("application/json")))
                .build();
        try (Response response = client.newCall(request).execute()) {
            assertEquals(200, response.code());
        }
        return fixture.server.takeRequest(5, TimeUnit.SECONDS);
    }

    private void configureProtection() {
        ApproovService.addSubstitutionHeader("Api-Key", null);
        ApproovService.addSubstitutionQueryParam("api_key");
        ApproovService.enableMessageSigning();
        ApproovService.setOkHttpClientBuilder(fixture.trustingBuilder());
    }

    private static void assertUnprocessed(RecordedRequest recorded) {
        LocalHttpsFixture.assertNoApproovHeaders(recorded);
        assertEquals("placeholder", recorded.getHeader("Api-Key"));
        assertTrue(recorded.getPath(), recorded.getPath().contains("api_key=placeholder"));
    }

    @Test
    public void requestBeforeInitializeGoesOutUnprocessedWithNoSdkCall() throws Exception {
        configureProtection();

        OkHttpClient client = ApproovService.getOkHttpClient();
        assertUnprocessed(sendWithPlaceholders(client));

        assertFalse(ApproovService.isApproovServiceEnabled());
        assertFalse(ApproovService.isApproovProtectionEnabled());
        assertEquals(Collections.<String>emptyList(), sdk.snapshot());
    }

    @Test
    public void requestInBypassModeGoesOutUnprocessedWithNoSdkCall() throws Exception {
        ApproovService.initialize(fixture.context, "");
        configureProtection();

        assertUnprocessed(sendWithPlaceholders(ApproovService.getOkHttpClient()));

        assertTrue(ApproovService.isApproovServiceEnabled());
        assertFalse(ApproovService.isApproovProtectionEnabled());
        assertEquals(Collections.<String>emptyList(), sdk.snapshot());
    }

    @Test
    public void clientObtainedBeforeInitializeIsProtectedOnceInitialized() throws Exception {
        ApproovService.setOkHttpClientBuilder(fixture.trustingBuilder());
        OkHttpClient client = ApproovService.getOkHttpClient();
        LocalHttpsFixture.assertNoApproovHeaders(sendWithPlaceholders(client));

        ApproovService.initialize(fixture.context, LocalHttpsFixture.CONFIG, "reinit-protect");

        fixture.server.enqueue(new MockResponse().setBody("ok"));
        try (Response response = client.newCall(new Request.Builder()
                .url(fixture.server.url("/v1/data")).build()).execute()) {
            assertEquals(200, response.code());
        }
        RecordedRequest recorded = fixture.server.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("success", recorded.getHeader("Approov-Status"));
        assertFalse(recorded.getHeader("Approov-Token").isEmpty());
    }
}
