//
// MIT License
//
// Copyright (c) 2016-present, Approov Ltd.
//
// Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
// documentation files (the "Software"), to deal in the Software without restriction, including without limitation
// the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and
// to permit persons to whom the Software is furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all copies or substantial portions
// of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
// TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
// THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
// CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
// DEALINGS IN THE SOFTWARE.

package io.approov.service.okhttp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import okio.BufferedSink;
import okio.ByteString;

/**
 * The body digest is hashed as the body is written, without a copy of the body
 * in memory. A body that fails while the digest is calculated leaves the request
 * signed without a Content-Digest, unless the digest is configured as required,
 * when the request fails and nothing is sent.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class BodyDigestStreaming370Test {
    private static final byte[] DATA = "{\"upload\":\"some bytes\"}".getBytes(StandardCharsets.UTF_8);

    // a body that records the sink of its first write and, if set, fails that first write only
    private static final class FirstWriteBody extends RequestBody {
        final Exception firstFailure;
        volatile int writes;
        volatile String firstSink;

        FirstWriteBody(Exception firstFailure) {
            this.firstFailure = firstFailure;
        }

        @Override
        public MediaType contentType() {
            return MediaType.get("application/json");
        }

        @Override
        public long contentLength() {
            return DATA.length;
        }

        @Override
        public void writeTo(BufferedSink sink) throws IOException {
            if (++writes == 1) {
                firstSink = sink.getClass().getName();
                if (firstFailure instanceof IOException)
                    throw (IOException) firstFailure;
                if (firstFailure != null)
                    throw (RuntimeException) firstFailure;
            }
            sink.write(DATA);
        }
    }

    private TwoOriginFixture fixture;

    @Before
    public void setUp() throws Exception {
        // localhost is an Approov API domain whose certificate the client trusts
        fixture = new TwoOriginFixture(true);
        ApproovService.reset();
        fixture.initialize();
        ApproovService.setOkHttpClientBuilder(fixture.trustingBuilder());
    }

    @After
    public void tearDown() throws Exception {
        fixture.shutdown();
        ApproovService.reset();
    }

    private static void installDigest(boolean required) {
        TestSigning.install(ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()
                .setBodyDigestConfig(ApproovDefaultMessageSigning.DIGEST_SHA256, required));
    }

    private RecordedRequest post(RequestBody body) throws Exception {
        fixture.protectedServer.enqueue(new MockResponse().setBody("ok"));
        Request request = new Request.Builder().url(fixture.protectedServer.url("/upload")).post(body).build();
        try (Response response = ApproovService.getOkHttpClient().newCall(request).execute()) {
            assertEquals(200, response.code());
        }
        return fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
    }

    @Test
    public void theDigestIsHashedWithoutACopyOfTheBody() throws Exception {
        installDigest(false);
        FirstWriteBody body = new FirstWriteBody(null);
        RecordedRequest recorded = post(body);
        assertNotEquals("the body was copied into a buffer to be hashed", "okio.Buffer", body.firstSink);
        assertEquals("sha-256=:" + ByteString.of(DATA).sha256().base64() + ":",
                recorded.getHeader("Content-Digest"));
        assertEquals("the body is sent unchanged", ByteString.of(DATA), recorded.getBody().readByteString());
    }

    @Test
    public void aBodyThatFailsWhileHashedIsSignedWithoutADigest() throws Exception {
        installDigest(false);
        for (Exception failure : new Exception[] {new IllegalStateException("stream closed"),
                new IOException("stream closed")}) {
            RecordedRequest recorded = post(new FirstWriteBody(failure));
            assertNotNull(failure + ": the request is still signed", recorded.getHeader("Signature"));
            assertNull(failure + ": no digest", recorded.getHeader("Content-Digest"));
            assertTrue(failure + ": the digest is not a covered component",
                    !recorded.getHeader("Signature-Input").contains("content-digest"));
        }
    }

    @Test
    public void aRequiredDigestOfABodyThatFailsWhileHashedFailsTheRequest() throws Exception {
        installDigest(true);
        for (Exception failure : new Exception[] {new IllegalStateException("stream closed"),
                new IOException("stream closed")}) {
            Request request = new Request.Builder().url(fixture.protectedServer.url("/upload"))
                    .post(new FirstWriteBody(failure)).build();
            try (Response response = ApproovService.getOkHttpClient().newCall(request).execute()) {
                fail(failure + ": a required digest that cannot be generated must fail, got " + response.code());
            } catch (ApproovException e) {
                assertEquals(failure.toString(), "Message signing: required body digest not generated",
                        e.getMessage());
                assertTrue(failure + ": cause " + e.getCause(),
                        e.getCause() instanceof ApproovDefaultMessageSigning.RequiredBodyDigestException);
            }
        }
        assertEquals("a failed request never reaches the network", 0, fixture.protectedServer.getRequestCount());
    }
}
