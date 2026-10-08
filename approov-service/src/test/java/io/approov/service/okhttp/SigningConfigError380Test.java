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

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import io.approov.util.okhttp.sig.ComponentProvider;
import io.approov.util.okhttp.sig.SignatureParameters;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.mockwebserver.MockResponse;

/**
 * The two signing configuration errors that fail every request (SPECIFICATION
 * 1.7(a)), a body digest configured as required that cannot be generated and an
 * unsupported signature algorithm, surface as the platform's network exception
 * type (an IOException, SPECIFICATION 1.6): execute() throws it and an enqueued
 * call receives it in onFailure, with nothing rethrown on the dispatcher thread.
 * On 3.5.8 the required body digest threw an IllegalStateException that OkHttp
 * rethrew on its dispatcher thread, killing the app process (evidence
 * 2026-10-04-okhttp-3.5.8-url-crash-probe.txt).
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class SigningConfigError380Test {
    private LocalHttpsFixture fixture;
    private RequestPathProbe probe;

    @Before
    public void setUp() throws Exception {
        fixture = new LocalHttpsFixture(false);
        ApproovService.reset();
        ApproovService.initialize(fixture.context, LocalHttpsFixture.CONFIG, "reinit-signing-config-error");
        ApproovService.setOkHttpClientBuilder(fixture.trustingBuilder());
        probe = new RequestPathProbe(fixture);
    }

    @After
    public void tearDown() throws Exception {
        probe.close();
        fixture.shutdown();
        ApproovService.reset();
    }

    private Request get() {
        return new Request.Builder().url(fixture.server.url("/p?a=b")).get().build();
    }

    @Test
    public void requiredBodyDigestExceptionIsAnApproovException() {
        assertTrue(ApproovException.class.isAssignableFrom(
                ApproovDefaultMessageSigning.RequiredBodyDigestException.class));
        assertTrue(IOException.class.isAssignableFrom(
                ApproovDefaultMessageSigning.RequiredBodyDigestException.class));
        assertFalse(RuntimeException.class.isAssignableFrom(
                ApproovDefaultMessageSigning.RequiredBodyDigestException.class));
    }

    @Test
    public void requiredBodyDigestThatCannotBeGeneratedFailsEveryRequestWithAnIOException() throws Exception {
        ApproovService.enableMessageSigning(ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()
                .setBodyDigestConfig(ApproovDefaultMessageSigning.DIGEST_SHA256, true));
        for (boolean enqueued : new boolean[] {true, false}) {
            String what = enqueued ? "enqueue" : "execute";
            RequestPathProbe.Outcome outcome = probe.run(get(), enqueued);
            ApproovDefaultMessageSigning.RequiredBodyDigestException e = RequestPathProbe.assertFailure(what,
                    outcome, ApproovDefaultMessageSigning.RequiredBodyDigestException.class);
            assertEquals(what, "Message signing: required body digest not generated", e.getMessage());
        }
        assertEquals("a failed request never reaches the network", 0, fixture.server.getRequestCount());
    }

    @Test
    public void requiredBodyDigestStillSignsARequestWithABody() throws Exception {
        ApproovService.enableMessageSigning(ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()
                .setBodyDigestConfig(ApproovDefaultMessageSigning.DIGEST_SHA256, true));
        for (boolean enqueued : new boolean[] {true, false}) {
            fixture.server.enqueue(new MockResponse().setBody("ok"));
            Request post = new Request.Builder().url(fixture.server.url("/p"))
                    .post(RequestBody.create("{\"a\":1}", MediaType.get("application/json"))).build();
            RequestPathProbe.assertOk(enqueued ? "enqueue" : "execute", probe.run(post, enqueued));
            assertTrue(fixture.server.takeRequest().getHeader("Content-Digest").startsWith("sha-256=:"));
        }
    }

    @Test
    public void unsupportedSignatureAlgorithmFailsEveryRequestWithAnIOException() throws Exception {
        ApproovDefaultMessageSigning.SignatureParametersFactory factory =
                new ApproovDefaultMessageSigning.SignatureParametersFactory() {
                    @Override
                    public List<String> getAlgs() {
                        return Collections.singletonList("rsa-v1_5-sha256");
                    }
                }.setBaseParameters(new SignatureParameters().addComponentIdentifier(ComponentProvider.DC_METHOD))
                        .setAddApproovTokenHeader(true);
        ApproovService.enableMessageSigning(factory);
        for (boolean enqueued : new boolean[] {true, false}) {
            String what = enqueued ? "enqueue" : "execute";
            ApproovException e = RequestPathProbe.assertFailure(what, probe.run(get(), enqueued),
                    ApproovException.class);
            assertTrue(what + ": " + e.getMessage(),
                    e.getMessage().contains("Message signing: unsupported algorithm identifier: rsa-v1_5-sha256"));
        }
        assertEquals("a failed request never reaches the network", 0, fixture.server.getRequestCount());
    }
}
