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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Runs a request through the Approov OkHttpClient synchronously or enqueued and
 * reports what the app would see, including whether anything escaped the
 * request path uncaught. An exception other than an IOException thrown inside an
 * interceptor is delivered by OkHttp to onFailure as an IOException "canceled due
 * to ..." and then rethrown on the dispatcher thread, which kills an Android app.
 * The probe records every exception that reaches the default uncaught exception
 * handler while it is installed, and joins the dispatcher threads after each
 * enqueued call so that a rethrow has certainly been delivered before the
 * outcome is checked.
 */
final class RequestPathProbe {
    private final List<Throwable> uncaught = Collections.synchronizedList(new ArrayList<>());
    private final Thread.UncaughtExceptionHandler previousHandler;
    private final LocalHttpsFixture fixture;

    RequestPathProbe(LocalHttpsFixture fixture) {
        this.fixture = fixture;
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> uncaught.add(e));
    }

    void close() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler);
    }

    /** The outcome of a call: the HTTP status, or what was thrown or reported. */
    static final class Outcome {
        Integer code;
        Throwable failure;

        @Override
        public String toString() {
            return (failure != null) ? "failure " + failure : "response " + code;
        }
    }

    /**
     * Executes the request on the Approov client, catching anything thrown.
     */
    Outcome execute(Request request) {
        Outcome outcome = new Outcome();
        OkHttpClient client = ApproovService.getOkHttpClient();
        try (Response response = client.newCall(request).execute()) {
            outcome.code = response.code();
        } catch (Throwable t) {
            outcome.failure = t;
        }
        assertTrue("nothing may escape a synchronous call uncaught: " + uncaught, uncaught.isEmpty());
        return outcome;
    }

    /**
     * Enqueues the request on an Approov client with a dispatcher of its own,
     * waits for the callback, then shuts the dispatcher down and joins its threads,
     * and checks that nothing reached the uncaught exception handler and that
     * OkHttp did not cancel the call because of a non-IOException.
     */
    Outcome enqueue(Request request) throws Exception {
        List<Thread> threads = Collections.synchronizedList(new ArrayList<>());
        ExecutorService executor = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60, TimeUnit.SECONDS,
                new SynchronousQueue<>(), runnable -> {
                    Thread thread = new Thread(runnable, "RequestPathProbe Dispatcher");
                    threads.add(thread);
                    return thread;
                });
        ApproovService.setOkHttpClientBuilder(fixture.trustingBuilder().dispatcher(new Dispatcher(executor)));

        Outcome outcome = new Outcome();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Outcome> result = new AtomicReference<>(outcome);
        ApproovService.getOkHttpClient().newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                result.get().failure = e;
                done.countDown();
            }

            @Override
            public void onResponse(Call call, Response response) {
                result.get().code = response.code();
                response.close();
                done.countDown();
            }
        });
        assertTrue("the enqueued call never completed", done.await(10, TimeUnit.SECONDS));

        // a rethrow happens on the dispatcher thread after the callback; once the
        // threads have terminated it has reached the uncaught exception handler
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        synchronized (threads) {
            for (Thread thread : threads)
                thread.join(10000);
        }
        assertTrue("nothing may escape an enqueued call to the dispatcher thread: " + uncaught,
                uncaught.isEmpty());
        if ((outcome.failure != null) && (outcome.failure.getMessage() != null))
            assertFalse("OkHttp canceled the call because of a non-IOException: " + outcome.failure,
                    outcome.failure.getMessage().startsWith("canceled due to"));
        return outcome;
    }

    /** Runs the request synchronously or enqueued. */
    Outcome run(Request request, boolean enqueued) throws Exception {
        return enqueued ? enqueue(request) : execute(request);
    }

    /**
     * Asserts that a call failed with an exception of the given type and
     * returns it.
     */
    static <T extends Throwable> T assertFailure(String what, Outcome outcome, Class<T> type) {
        if (outcome.failure == null)
            fail(what + ": expected " + type.getSimpleName() + ", got " + outcome);
        if (!type.isInstance(outcome.failure))
            throw new AssertionError(what + ": expected " + type.getSimpleName() + ", got " + outcome,
                    outcome.failure);
        return type.cast(outcome.failure);
    }

    /** Asserts that a call completed with HTTP 200. */
    static void assertOk(String what, Outcome outcome) {
        if (outcome.failure != null)
            throw new AssertionError(what + ": expected a response, got " + outcome, outcome.failure);
        assertEquals(what, Integer.valueOf(200), outcome.code);
    }
}
