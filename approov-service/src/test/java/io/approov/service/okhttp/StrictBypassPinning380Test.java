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
import static org.junit.Assert.fail;

import com.criticalblue.approovsdk.Approov;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLPeerUnverifiedException;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;

/**
 * Bypass mode is honoured strictly (SPECIFICATION 5.7(c)(f), TESTING_REQUIREMENTS
 * 1 and 4): while Approov protection is not enabled, before initialize or in
 * bypass mode, the layer applies no Approov pinning at all and never asks the
 * SDK for pins, even when another caller has initialized the SDK and it returns
 * pins for the host; only OS trust applies. Once protection is enabled the pins
 * are enforced. Mirrors approov-service-android's StrictBypassPinning380Test.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class StrictBypassPinning380Test {
    private LocalHttpsFixture fixture;
    private RecordingSdkFacade sdk;

    @Before
    public void setUp() throws Exception {
        fixture = new LocalHttpsFixture(true);
        // the SDK was initialized by something else and returns a wrong pin for localhost
        fixture.initializeSdkElsewhere();
        assertEquals(Collections.singletonList(LocalHttpsFixture.WRONG_PIN),
                Approov.getPins("public-key-sha256").get("localhost"));
        ApproovService.reset();
        sdk = RecordingSdkFacade.install();
        ApproovService.setOkHttpClientBuilder(fixture.trustingBuilder());
    }

    @After
    public void tearDown() throws Exception {
        fixture.shutdown();
        ApproovService.reset();
    }

    private void sendAndExpectDelivered(OkHttpClient client) throws Exception {
        fixture.server.enqueue(new MockResponse().setBody("ok"));
        try (Response response = client.newCall(new Request.Builder()
                .url(fixture.server.url("/v1")).build()).execute()) {
            assertEquals(200, response.code());
        }
        LocalHttpsFixture.assertNoApproovHeaders(fixture.server.takeRequest(5, TimeUnit.SECONDS));
    }

    @Test
    public void connectsOnOsTrustBeforeInitializeWithoutReadingPins() throws Exception {
        sendAndExpectDelivered(ApproovService.getOkHttpClient());

        assertEquals(1, fixture.server.getRequestCount());
        assertEquals(0, sdk.count("getPins"));
        assertEquals(Collections.<String>emptyList(), sdk.snapshot());
    }

    @Test
    public void connectsOnOsTrustInBypassModeWithoutReadingPins() throws Exception {
        ApproovService.initialize(fixture.context, "");
        assertFalse(ApproovService.isApproovProtectionEnabled());

        sendAndExpectDelivered(ApproovService.getOkHttpClient());

        assertEquals(1, fixture.server.getRequestCount());
        assertEquals(Collections.<String>emptyList(), sdk.snapshot());
    }

    @Test
    public void thePinnerHoldsNoPinsWhileProtectionIsNotEnabled() throws Exception {
        ApproovService.getOkHttpClient();
        ApproovService.rebuildPins();
        assertTrue(ApproovService.getCertificatePinner().getPins().isEmpty());
        ApproovService.initialize(fixture.context, "");
        ApproovService.rebuildPins();
        assertTrue(ApproovService.getCertificatePinner().getPins().isEmpty());
        assertEquals(0, sdk.count("getPins"));
    }

    @Test
    public void enforcesThePinsOnceProtected() throws Exception {
        OkHttpClient client = ApproovService.getOkHttpClient();
        sendAndExpectDelivered(client);

        ApproovService.initialize(fixture.context, LocalHttpsFixture.CONFIG, "reinit-protect");
        assertTrue(ApproovService.isApproovProtectionEnabled());
        try (Response response = client.newCall(new Request.Builder()
                .url(fixture.server.url("/v1")).build()).execute()) {
            fail("the wrong pin must fail the connection once protected, got " + response.code());
        } catch (SSLPeerUnverifiedException expected) {
            // the platform's standard pinning exception (SPECIFICATION 2.3)
        }
        assertEquals("nothing sent after the pin failure", 1, fixture.server.getRequestCount());
        assertTrue(sdk.count("getPins") > 0);
    }
}
