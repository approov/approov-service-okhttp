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

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * The mutator in force when the layer first processes a request governs every
 * attempt of that request: a redirect, a stale protection refresh and the pinning
 * of each attempt. An app network interceptor here changes the service mutator
 * during the first network attempt, which runs before the layer's own network
 * interceptors, so the attempts after the change must still be decided by the
 * mutator the request started with.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class OneRequestOneMutator370Test {
    // a mutator that names itself in a header and counts the questions it is asked
    private static final class NamedMutator implements ApproovServiceMutator {
        final String name;
        final AtomicInteger processed = new AtomicInteger();
        final AtomicInteger refreshAsked = new AtomicInteger();
        final AtomicInteger pinningAsked = new AtomicInteger();

        NamedMutator(String name) {
            this.name = name;
        }

        @Override
        public Request handleInterceptorProcessedRequest(Request request, ApproovRequestMutations changes) {
            processed.incrementAndGet();
            return request.newBuilder().header("Mutator", name).build();
        }

        @Override
        public boolean supportsProtectionRefresh() {
            refreshAsked.incrementAndGet();
            return true;
        }

        @Override
        public boolean handlePinningShouldProcessRequest(Request request) {
            pinningAsked.incrementAndGet();
            return true;
        }
    }

    private final NamedMutator first = new NamedMutator("first");
    private final NamedMutator second = new NamedMutator("second");
    private TwoOriginFixture fixture;

    // starts the layer under the first mutator, with an app network interceptor that switches to the second
    // during the first network attempt and, if held, also advances the clock past the refresh period
    private void start(boolean held) throws Exception {
        fixture = new TwoOriginFixture(true);
        ApproovService.reset();
        fixture.initialize();
        ApproovService.setServiceMutator(first);
        AtomicInteger attempts = new AtomicInteger();
        Interceptor switcher = chain -> {
            if (attempts.getAndIncrement() == 0) {
                ApproovService.setServiceMutator(second);
                if (held)
                    ShadowSystemClock.advanceBy(Duration.ofSeconds(10));
            }
            return chain.proceed(chain.request());
        };
        ApproovService.setOkHttpClientBuilder(fixture.trustingBuilder().addNetworkInterceptor(switcher));
    }

    @After
    public void tearDown() throws Exception {
        if (fixture != null)
            fixture.shutdown();
        ApproovService.reset();
    }

    private void assertSecondNeverAsked() {
        assertEquals("the second mutator processed an attempt", 0, second.processed.get());
        assertEquals("the second mutator was asked about refresh", 0, second.refreshAsked.get());
        assertEquals("the second mutator was asked about pinning", 0, second.pinningAsked.get());
    }

    @Test
    public void aRedirectIsDecidedByTheMutatorTheRequestStartedWith() throws Exception {
        start(false);
        fixture.protectedServer.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "/next"));
        fixture.protectedServer.enqueue(new MockResponse().setBody("ok"));
        try (Response response = ApproovService.getOkHttpClient().newCall(
                new Request.Builder().url(fixture.protectedServer.url("/start")).build()).execute()) {
            assertEquals(200, response.code());
        }
        RecordedRequest start = fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
        RecordedRequest next = fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("first", start.getHeader("Mutator"));
        assertEquals("the redirect is protected by the first mutator", "first", next.getHeader("Mutator"));
        assertEquals("processed, then reprotected for the redirect", 2, first.processed.get());
        assertEquals("both attempts pinned under the first mutator", 2, first.pinningAsked.get());
        assertSecondNeverAsked();
    }

    @Test
    public void aStaleRefreshIsDecidedByTheMutatorTheRequestStartedWith() throws Exception {
        start(true);
        fixture.protectedServer.enqueue(new MockResponse().setBody("ok"));
        try (Response response = ApproovService.getOkHttpClient().newCall(
                new Request.Builder().url(fixture.protectedServer.url("/held")).build()).execute()) {
            assertEquals(200, response.code());
        }
        RecordedRequest held = fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("the refresh is made by the first mutator", "first", held.getHeader("Mutator"));
        assertEquals("processed, then refreshed", 2, first.processed.get());
        assertEquals("the refresh asked the first mutator", 1, first.refreshAsked.get());
        assertEquals("pinned under the first mutator", 1, first.pinningAsked.get());
        assertSecondNeverAsked();
    }

    @Test
    public void aMutatorSetBeforeTheRequestGovernsIt() throws Exception {
        // control: a change made before the request is processed applies to it
        start(false);
        ApproovService.setServiceMutator(second);
        fixture.protectedServer.enqueue(new MockResponse().setBody("ok"));
        try (Response response = ApproovService.getOkHttpClient().newCall(
                new Request.Builder().url(fixture.protectedServer.url("/later")).build()).execute()) {
            assertEquals(200, response.code());
        }
        assertEquals("second", fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS).getHeader("Mutator"));
        assertEquals(0, first.processed.get());
    }
}
