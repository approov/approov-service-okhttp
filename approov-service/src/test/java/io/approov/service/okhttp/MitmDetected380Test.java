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

import java.io.IOException;

import okhttp3.Request;
import okhttp3.Response;

/**
 * MITM_DETECTED is reported only by the 3.5.x SDK, which this layer depends on
 * while it is tested, and the 3.8.0 SDK replaces it with UNTRUSTED_NETWORK. Until
 * then it is a retryable network failure like UNTRUSTED_NETWORK: the direct
 * methods throw ApproovNetworkException and CLOSE_FAILURE aborts a request to an
 * Approov API domain with ApproovNetworkException, sending nothing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class MitmDetected380Test {
    private static final String STATUS = "MITM_DETECTED";

    private TwoOriginFixture fixture;

    private interface DirectCall {
        void call() throws ApproovException;
    }

    @Before
    public void setUp() throws Exception {
        fixture = new TwoOriginFixture(true, "the-key", "the-secret");
        ApproovService.reset();
        fixture.initialize();
        ApproovService.setOkHttpClientBuilder(fixture.trustingBuilder());
    }

    @After
    public void tearDown() throws Exception {
        fixture.shutdown();
        ApproovService.reset();
    }

    private static void reportMitm(String operation) {
        AttesterProxyController.setNextAttestationDirectiveJson("{\"operation\": \"" + operation
                + "\", \"response\": {\"status\": \"" + STATUS + "\"}}");
    }

    private static void assertNetworkFailure(String method, String operation, DirectCall call) {
        reportMitm(operation);
        try {
            call.call();
            fail(method + ": " + STATUS + " must throw");
        } catch (ApproovException e) {
            assertEquals(method + ": " + e, ApproovNetworkException.class, e.getClass());
            assertSame(method, Approov.TokenFetchStatus.valueOf(STATUS),
                    ((ApproovNetworkException) e).getTokenFetchStatus());
            assertTrue(method + ": " + e.getMessage(), e.getMessage().contains(STATUS));
        }
    }

    @Test
    public void mitmDetectedIsANetworkFailure() {
        assertTrue(ApproovServiceMutator.isNetworkFailure(Approov.TokenFetchStatus.valueOf(STATUS)));
    }

    @Test
    public void directMethodsThrowTheNetworkException() {
        assertNetworkFailure("fetchToken", "fetchApproovToken",
                () -> ApproovService.fetchToken(fixture.protectedServer.url("/p").toString()));
        assertNetworkFailure("fetchSecureString", "fetchSecureString",
                () -> ApproovService.fetchSecureString("the-key", null));
        assertNetworkFailure("fetchCustomJWT", "fetchCustomJWT",
                () -> ApproovService.fetchCustomJWT("{\"role\":\"tester\"}"));
        assertNetworkFailure("precheck", "fetchSecureString", ApproovService::precheck);
    }

    @Test
    public void closeFailureAbortsARequestToAnApproovApiDomain() throws Exception {
        ApproovService.setServiceMutator(ApproovServiceMutator.CLOSE_FAILURE);
        reportMitm("fetchApproovToken");
        Request request = new Request.Builder().url(fixture.protectedServer.url("/p")).build();
        try (Response response = ApproovService.getOkHttpClient().newCall(request).execute()) {
            fail("CLOSE_FAILURE must abort on " + STATUS + ", got " + response.code());
        } catch (ApproovNetworkException e) {
            assertSame(Approov.TokenFetchStatus.valueOf(STATUS), e.getTokenFetchStatus());
        } catch (IOException e) {
            throw new AssertionError("expected ApproovNetworkException, got " + e, e);
        }
        assertEquals("a request reached the server", 0, fixture.protectedServer.getRequestCount());
    }
}
