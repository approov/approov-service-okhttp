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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLPeerUnverifiedException;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * WebSockets are not supported, and the unsupported path must fail safe. OkHttp runs
 * application interceptors on a WebSocket upgrade but never its network interceptors,
 * so ApproovPinningInterceptor never checks the connection. The token interceptor
 * therefore forwards any "Upgrade: websocket" request untouched: no token or secure
 * string fetch, no Approov header, no substitution and no signature, so that nothing
 * Approov issues travels over a connection that has not been pinned. The upgrade
 * reaching a server whose certificate does not match the pin proves that no pin check
 * ran; the control shows that an ordinary request on the same client is refused.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class WebSocketUpgrade370Test {
    private static final String PLACEHOLDER = "api-key-placeholder";

    private LocalHttpsFixture fixture;
    private RecordingSdk sdk;

    @Before
    public void setUp() throws Exception {
        // localhost is an Approov API domain carrying a pin that its certificate does not match
        fixture = new LocalHttpsFixture(true);
        ApproovService.reset();
        sdk = RecordingSdk.install();
        ApproovService.initialize(fixture.context, LocalHttpsFixture.CONFIG, "reinit-websocket");
        ApproovService.addSubstitutionHeader("Api-Key", null);
        TestSigning.install();
        ApproovService.setOkHttpClientBuilder(fixture.trustingBuilder());
        sdk.calls.clear();
    }

    @After
    public void tearDown() throws Exception {
        fixture.shutdown();
        ApproovService.reset();
        RecordingSdk.uninstall();
    }

    // opens a WebSocket and waits for it to open or fail, returning the failure or null; a failure reported after
    // the socket opened is the test's own cancel closing it, not an outcome of the upgrade, so it is ignored
    private Throwable open() throws InterruptedException {
        CountDownLatch settled = new CountDownLatch(1);
        AtomicBoolean opened = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        WebSocket socket = ApproovService.getOkHttpClient().newWebSocket(
                new Request.Builder().url("wss://localhost:" + fixture.server.getPort() + "/socket")
                        .header("Api-Key", PLACEHOLDER).build(),
                new WebSocketListener() {
                    @Override
                    public void onOpen(WebSocket webSocket, Response response) {
                        opened.set(true);
                        settled.countDown();
                    }

                    @Override
                    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                        if (!opened.get())
                            failure.set(t);
                        settled.countDown();
                    }
                });
        assertTrue("the WebSocket opened or failed", settled.await(10, TimeUnit.SECONDS));
        Throwable outcome = failure.get();
        socket.cancel();
        return outcome;
    }

    @Test
    public void anUpgradeOverAConnectionThatFailsThePinCarriesNothingFromApproov() throws Exception {
        fixture.server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
        }));

        assertNull("OkHttp runs no network interceptor on the upgrade, so no pin check refuses it", open());

        RecordedRequest upgrade = fixture.server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull("the upgrade reaches the server", upgrade);
        assertEquals("websocket", upgrade.getHeader("Upgrade"));
        LocalHttpsFixture.assertNoApproovHeaders(upgrade);
        assertEquals("the placeholder is not substituted", PLACEHOLDER, upgrade.getHeader("Api-Key"));
        for (String call : sdk.snapshot())
            assertTrue("no token or secure string fetch for the upgrade: " + call, !call.startsWith("fetch"));
    }

    @Test
    public void controlAPlainRequestOnTheSameClientIsRefusedByThePin() throws Exception {
        fixture.server.enqueue(new MockResponse().setBody("ok"));
        try {
            ApproovService.getOkHttpClient().newCall(new Request.Builder().url(fixture.server.url("/plain"))
                    .header("Api-Key", PLACEHOLDER).build()).execute().close();
            fail("a plain request over a connection that fails the pin is refused");
        } catch (SSLPeerUnverifiedException expected) {
            // refused by the pin check
        }
        assertEquals("nothing reaches the server", 0, fixture.server.getRequestCount());
    }
}
