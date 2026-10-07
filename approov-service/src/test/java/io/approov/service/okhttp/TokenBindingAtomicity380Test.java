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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.criticalblue.approovsdk.Approov;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * A request is sent with a token bound to its own binding header value (okhttp D4,
 * ported from approov-service-android 05a0c79). The SDK's setDataHashInToken writes
 * one process-wide value, which the token fetch reads when it builds its request,
 * so two requests binding different values (an OAuth refresh with requests
 * carrying the old and the new token in flight) could each set their value and
 * then fetch: one got a token bound to the other's value, and the backend binding
 * check rejected it. Setting the value and fetching are now one step for bound
 * requests, on the first attempt and on every reapplication (stale refresh,
 * redirect, authenticator retry); requests without a binding value still fetch
 * concurrently.
 *
 * The SDK here is a facade whose fetch waits for both requests to set their value
 * (or 300 ms) and returns a token naming the value current when it completes, as
 * the real SDK's pay claim would.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class TokenBindingAtomicity380Test {
    private LocalHttpsFixture fixture;
    private final AtomicReference<String> currentBinding = new AtomicReference<>();
    private volatile CountDownLatch gate;
    private final AtomicInteger concurrentFetches = new AtomicInteger();
    private final AtomicInteger maxConcurrentFetches = new AtomicInteger();

    @Before
    public void setUp() throws Exception {
        fixture = new LocalHttpsFixture(false);
        ApproovService.reset();
        ApproovService.setSdkFacadeForTesting(new RecordingSdkFacade() {
            @Override
            public void setDataHashInToken(String data) {
                currentBinding.set(data);
                CountDownLatch latch = gate;
                if (latch != null)
                    latch.countDown();
            }

            @Override
            public Approov.TokenFetchResult fetchApproovTokenAndWait(String url) {
                int now = concurrentFetches.incrementAndGet();
                maxConcurrentFetches.accumulateAndGet(now, Math::max);
                try {
                    CountDownLatch latch = gate;
                    if (latch != null)
                        latch.await(300, TimeUnit.MILLISECONDS);
                    else
                        Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    concurrentFetches.decrementAndGet();
                }
                return success("pay=" + currentBinding.get());
            }
        });
        fixture.server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setBody("ok");
            }
        });
        ApproovService.initialize(fixture.context, LocalHttpsFixture.CONFIG, "reinit-binding-atomic");
        ApproovService.setOkHttpClientBuilder(fixture.trustingBuilder());
    }

    @After
    public void tearDown() throws Exception {
        fixture.shutdown();
        ApproovService.reset();
    }

    // a successful token fetch result carrying the token, built as the SDK builds it
    private static Approov.TokenFetchResult success(String token) {
        try {
            java.lang.reflect.Constructor<Approov.TokenFetchResult> constructor =
                    Approov.TokenFetchResult.class.getDeclaredConstructor(Approov.TokenFetchStatus.class,
                            String.class, String.class, String.class, String.class, String.class,
                            boolean.class, boolean.class, byte[].class);
            constructor.setAccessible(true);
            return constructor.newInstance(Approov.TokenFetchStatus.SUCCESS, token, null, null, "", "", false,
                    false, null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("the mini-SDK TokenFetchResult constructor changed", e);
        }
    }

    // sends one request per binding value from its own thread, all at once, and
    // returns the token each request carried on the wire, by binding value
    private Map<String, String> sendConcurrently(OkHttpClient client, String... bindings) throws Exception {
        Map<String, String> tokens = new ConcurrentHashMap<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        Thread[] threads = new Thread[bindings.length];
        for (int i = 0; i < bindings.length; i++) {
            String binding = bindings[i];
            threads[i] = new Thread(() -> {
                try {
                    start.await();
                    Request.Builder builder = new Request.Builder().url(fixture.server.url("/v1"));
                    if (binding != null)
                        builder.header("Authorization", binding);
                    try (Response response = client.newCall(builder.build()).execute()) {
                        assertEquals(200, response.code());
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
            threads[i].start();
        }
        start.countDown();
        for (Thread thread : threads)
            thread.join(10000);
        assertNull(String.valueOf(failure.get()), failure.get());
        for (int i = 0; i < fixture.server.getRequestCount(); i++) {
            RecordedRequest recorded = fixture.server.takeRequest(5, TimeUnit.SECONDS);
            String authorization = recorded.getHeader("Authorization");
            tokens.put((authorization != null) ? authorization : "none-" + i, recorded.getHeader("Approov-Token"));
        }
        return tokens;
    }

    @Test
    public void eachRequestCarriesATokenBoundToItsOwnValue() throws Exception {
        ApproovService.setBindingHeader("Authorization");
        gate = new CountDownLatch(2);
        Map<String, String> tokens = sendConcurrently(ApproovService.getOkHttpClient(), "Bearer old", "Bearer new");
        assertEquals("pay=Bearer old", tokens.get("Bearer old"));
        assertEquals("pay=Bearer new", tokens.get("Bearer new"));
    }

    @Test
    public void aStaleRefreshBindsAndFetchesAtomicallyToo() throws Exception {
        ApproovService.setBindingHeader("Authorization");
        // every attempt is held past the refresh period, so the token on the wire
        // is the one the network layer fetched again
        Interceptor hold = chain -> {
            ShadowSystemClock.advanceBy(Duration.ofSeconds(10));
            return chain.proceed(chain.request());
        };
        OkHttpClient.Builder builder = ApproovService.getOkHttpClient().newBuilder();
        builder.networkInterceptors().add(0, hold);
        gate = new CountDownLatch(4);
        Map<String, String> tokens = sendConcurrently(builder.build(), "Bearer old", "Bearer new");
        assertEquals("pay=Bearer old", tokens.get("Bearer old"));
        assertEquals("pay=Bearer new", tokens.get("Bearer new"));
    }

    @Test
    public void control_requestsWithoutABindingValueFetchConcurrently() throws Exception {
        ApproovService.setBindingHeader("Authorization");
        // neither request carries the binding header: the fetches are not
        // serialized, so both are inside the SDK at the same time
        gate = new CountDownLatch(1);
        CountDownLatch bothInside = new CountDownLatch(2);
        ApproovService.setSdkFacadeForTesting(new RecordingSdkFacade() {
            @Override
            public Approov.TokenFetchResult fetchApproovTokenAndWait(String url) {
                bothInside.countDown();
                try {
                    bothInside.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return success("unbound-" + bothInside.getCount());
            }
        });
        sendConcurrently(ApproovService.getOkHttpClient(), null, null);
        assertEquals("the unbound fetches overlapped", 0, bothInside.getCount());
    }

    @Test
    public void control_boundRequestsAreSerialized() throws Exception {
        ApproovService.setBindingHeader("Authorization");
        gate = null;
        sendConcurrently(ApproovService.getOkHttpClient(), "Bearer a", "Bearer b", "Bearer c");
        assertTrue("bound fetches overlapped: " + maxConcurrentFetches.get(), maxConcurrentFetches.get() <= 1);
    }
}
