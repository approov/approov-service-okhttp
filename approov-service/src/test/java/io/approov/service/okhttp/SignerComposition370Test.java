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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.criticalblue.approovsdk.Approov;
import com.criticalblue.minisdk.testing.AttesterProxyController;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.TimeUnit;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * Message signing under other decisions by composition, as ADVANCED.md documents it: an app mutator that
 * implements ApproovServiceMutator, makes the ALWAYS_PROCEED token decision, calls an
 * ApproovDefaultMessageSigning from its own processed request hook and supports a protection refresh. A
 * request is signed on SUCCESS, a failure that proceeds to an Approov API domain is signed over its empty
 * token header, and a same-origin redirect is signed again. Without the refresh support the redirect goes
 * unsigned.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class SignerComposition370Test {
    private TwoOriginFixture fixture;

    // the mutator from "Signing with other decisions" in ADVANCED.md, in Java
    static class ProceedAndSign implements ApproovServiceMutator {
        private final ApproovDefaultMessageSigning signer = new ApproovDefaultMessageSigning().setDefaultFactory(
                ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory());

        @Override
        public boolean handleInterceptorFetchTokenResult(Approov.TokenFetchResult approovResults, String url)
                throws ApproovException {
            return ApproovServiceMutator.ALWAYS_PROCEED.handleInterceptorFetchTokenResult(approovResults, url);
        }

        @Override
        public Request handleInterceptorProcessedRequest(Request request, ApproovRequestMutations changes)
                throws ApproovException {
            return signer.handleInterceptorProcessedRequest(request, changes);
        }

        @Override
        public boolean supportsProtectionRefresh() {
            return true;
        }

        @Override
        public String toString() {
            return "ProceedAndSign";
        }
    }

    // the same mutator without the refresh support, which the documentation warns against
    static class ProceedAndSignWithoutRefresh extends ProceedAndSign {
        @Override
        public boolean supportsProtectionRefresh() {
            return false;
        }
    }

    private void start(ApproovServiceMutator mutator) throws Exception {
        fixture = new TwoOriginFixture(true);
        ApproovService.reset();
        RecordingSdk.install();
        fixture.initialize();
        ApproovService.setServiceMutator(mutator);
        ApproovService.setOkHttpClientBuilder(fixture.trustingBuilder());
    }

    @After
    public void tearDown() throws Exception {
        if (fixture != null)
            fixture.shutdown();
        ApproovService.reset();
        RecordingSdk.uninstall();
    }

    private Request request(String path) {
        return new Request.Builder().url(fixture.protectedServer.url(path)).build();
    }

    // the next token fetch reports the status, with a trace ID named after it
    private static void nextTokenFetch(String status) {
        AttesterProxyController.setNextAttestationDirectiveJson(
                "{\"operation\": \"fetchApproovToken\", \"response\": {\"status\": \"" + status + "\","
                        + " \"traceID\": \"trace-" + status + "\"}}");
    }

    private RecordedRequest sendAndTake(Request request) throws Exception {
        fixture.protectedServer.enqueue(new MockResponse().setBody("ok"));
        try (Response response = ApproovService.getOkHttpClient().newCall(request).execute()) {
            assertEquals(200, response.code());
        }
        return fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
    }

    // the request carries the install signature, covering the token header
    private static void assertInstallSigned(String what, RecordedRequest recorded) {
        assertNotNull(what + ": not delivered", recorded);
        String input = recorded.getHeader("Signature-Input");
        String signature = recorded.getHeader("Signature");
        assertNotNull(what + ": Signature-Input", input);
        assertNotNull(what + ": Signature", signature);
        assertTrue(what + ": the install member: " + input, input.startsWith("install=("));
        assertTrue(what + ": the install member: " + signature, signature.startsWith("install=:"));
        assertTrue(what + ": the token header is covered: " + input, input.contains("\"approov-token\""));
    }

    @Test
    public void theComposedSignerIsNotTheInstalledMutator() throws Exception {
        start(new ProceedAndSign());
        assertFalse("composition, not a subclass of the signer",
                ApproovService.getServiceMutator() instanceof ApproovDefaultMessageSigning);
    }

    @Test
    public void aSuccessfulRequestIsSignedWithTheInstallSignature() throws Exception {
        start(new ProceedAndSign());
        RecordedRequest recorded = sendAndTake(request("/data"));
        assertEquals("a token: " + recorded.getHeader("Approov-Token"), 3,
                recorded.getHeader("Approov-Token").split("\\.").length);
        assertInstallSigned("SUCCESS", recorded);
        assertFalse("no account signature by default: " + recorded.getHeader("Signature-Input"),
                recorded.getHeader("Signature-Input").contains("account="));
    }

    @Test
    public void aNoNetworkRequestToAnApproovApiDomainProceedsWithAnEmptyTokenHeaderAndIsSigned() throws Exception {
        // localhost is a key of the fixture's pin set, so it is an Approov API domain
        start(new ProceedAndSign());
        nextTokenFetch("NO_NETWORK");
        RecordedRequest recorded = sendAndTake(request("/data"));
        assertNotNull("ALWAYS_PROCEED lets NO_NETWORK proceed", recorded);
        assertEquals("an empty token header", "", recorded.getHeader("Approov-Token"));
        assertEquals("the trace ID of the failure", "trace-NO_NETWORK", recorded.getHeader("Approov-TraceID"));
        assertInstallSigned("NO_NETWORK", recorded);
    }

    @Test
    public void aSameOriginRedirectIsSignedAgain() throws Exception {
        start(new ProceedAndSign());
        fixture.protectedServer.enqueue(new MockResponse().setResponseCode(302)
                .setHeader("Location", fixture.protectedServer.url("/next")));
        fixture.protectedServer.enqueue(new MockResponse().setBody("ok"));
        try (Response response = ApproovService.getOkHttpClient().newCall(request("/start")).execute()) {
            assertEquals(200, response.code());
        }
        RecordedRequest first = fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
        RecordedRequest second = fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
        assertInstallSigned("first hop", first);
        assertNotNull("redirect not followed", second);
        assertEquals("/next", second.getRequestUrl().encodedPath());
        assertInstallSigned("same-origin redirect", second);
        // the target URI is covered, so a signature made afresh for the target differs from the first hop's
        assertNotEquals("signed afresh for the target", first.getHeader("Signature"), second.getHeader("Signature"));
    }

    @Test
    public void withoutRefreshSupportASameOriginRedirectGoesUnsigned() throws Exception {
        start(new ProceedAndSignWithoutRefresh());
        fixture.protectedServer.enqueue(new MockResponse().setResponseCode(302)
                .setHeader("Location", fixture.protectedServer.url("/next")));
        fixture.protectedServer.enqueue(new MockResponse().setBody("ok"));
        try (Response response = ApproovService.getOkHttpClient().newCall(request("/start")).execute()) {
            assertEquals(200, response.code());
        }
        assertInstallSigned("control: first hop", fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS));
        RecordedRequest second = fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull("redirect not followed", second);
        assertNotNull("the redirect is protected again", second.getHeader("Approov-Token"));
        assertNull("the first hop's signature is stripped and not made again", second.getHeader("Signature"));
        assertNull("the first hop's signature is stripped and not made again", second.getHeader("Signature-Input"));
    }
}
