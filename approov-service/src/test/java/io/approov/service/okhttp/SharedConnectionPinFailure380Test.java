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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.criticalblue.minisdk.testing.AttesterProxyController;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLPeerUnverifiedException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.EventListener;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;

/**
 * A pin failure on one host never takes down another host's traffic (blind spot
 * 5). OkHttp coalesces two hosts onto one HTTP/2 connection when they share an IP
 * address and the certificate covers both; a pin mismatch for the second host
 * must fail only that host's request, with the platform pinning exception, and
 * leave the first host's stream on the shared connection running.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class SharedConnectionPinFailure380Test {
    private static final String CONFIG = LocalHttpsFixture.CONFIG;

    private final Context context = ApplicationProvider.getApplicationContext();
    private final MockWebServer server = new MockWebServer();
    private final CountDownLatch slowArrived = new CountDownLatch(1);
    private final CountDownLatch releaseSlow = new CountDownLatch(1);
    private final AtomicInteger connections = new AtomicInteger();
    private OkHttpClient.Builder builder;

    @Before
    public void setUp() throws Exception {
        HeldCertificate certificate = new HeldCertificate.Builder()
                .addSubjectAlternativeName("a.test")
                .addSubjectAlternativeName("b.test")
                .build();
        server.useHttps(new HandshakeCertificates.Builder().heldCertificate(certificate).build()
                .sslSocketFactory(), false);
        server.setProtocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1));
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                if (request.getPath().startsWith("/slow")) {
                    slowArrived.countDown();
                    releaseSlow.await(10, TimeUnit.SECONDS);
                    return new MockResponse().setBody("slow-ok");
                }
                return new MockResponse().setBody("ok");
            }
        });
        server.start(InetAddress.getByName("127.0.0.1"), 0);

        HandshakeCertificates client = new HandshakeCertificates.Builder()
                .addTrustedCertificate(certificate.certificate()).build();
        InetAddress local = InetAddress.getByName("127.0.0.1");
        builder = new OkHttpClient.Builder()
                .sslSocketFactory(client.sslSocketFactory(), client.trustManager())
                .proxy(Proxy.NO_PROXY)
                // both hosts resolve to the server, so OkHttp may coalesce them
                .dns(hostname -> Collections.singletonList(local))
                .eventListener(new EventListener() {
                    @Override
                    public void connectStart(Call call, InetSocketAddress address, Proxy proxy) {
                        connections.incrementAndGet();
                    }
                });
        ApproovService.reset();
    }

    @After
    public void tearDown() throws Exception {
        releaseSlow.countDown();
        server.shutdown();
        AttesterProxyController.reset();
        ApproovService.reset();
    }

    // a.test has no pins (OS trust); b.test has the given pins
    private void protect(String bPins) {
        AttesterProxyController.reset();
        AttesterProxyController.loadScenarioJson("{\"activeCase\": \"coalesced\", \"cases\": {\"coalesced\": {"
                + "\"protectedDomains\": [\"a.test\", \"b.test\"],"
                + "\"pins\": {\"public-key-sha256\": {\"a.test\": [], \"b.test\": " + bPins + "}}}}}");
        ApproovService.initialize(context, CONFIG, "reinit-coalesced");
        ApproovService.setOkHttpClientBuilder(builder);
    }

    private HttpUrl url(String host, String path) {
        return new HttpUrl.Builder().scheme("https").host(host).port(server.getPort()).encodedPath(path).build();
    }

    // starts a request to a.test whose response is held until releaseSlow
    private AtomicReference<Object> startSlowRequestToA(CountDownLatch done) throws Exception {
        AtomicReference<Object> outcome = new AtomicReference<>();
        ApproovService.getOkHttpClient().newCall(new Request.Builder().url(url("a.test", "/slow")).build())
                .enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {
                        outcome.set(e);
                        done.countDown();
                    }

                    @Override
                    public void onResponse(Call call, Response response) throws IOException {
                        try {
                            outcome.set(response.code() + " " + response.protocol() + " " + response.body().string());
                        } catch (IOException e) {
                            outcome.set(e);
                        } finally {
                            response.close();
                            done.countDown();
                        }
                    }
                });
        assertTrue("the request to a.test never reached the server", slowArrived.await(10, TimeUnit.SECONDS));
        return outcome;
    }

    @Test
    public void bothHostsShareOneConnectionWhenBothPass() throws Exception {
        protect("[]");
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Object> slow = startSlowRequestToA(done);

        try (Response response = ApproovService.getOkHttpClient().newCall(
                new Request.Builder().url(url("b.test", "/x")).build()).execute()) {
            assertEquals(200, response.code());
            assertEquals(Protocol.HTTP_2, response.protocol());
        }
        assertEquals("b.test must be coalesced onto a.test's connection", 1, connections.get());

        releaseSlow.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertEquals("200 h2 slow-ok", slow.get());
    }

    @Test
    public void aPinFailureOnACoalescedHostLeavesTheOtherHostsStreamRunning() throws Exception {
        protect("[\"" + LocalHttpsFixture.WRONG_PIN + "\"]");
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Object> slow = startSlowRequestToA(done);

        try (Response response = ApproovService.getOkHttpClient().newCall(
                new Request.Builder().url(url("b.test", "/x")).build()).execute()) {
            fail("b.test's wrong pin must fail its request, got " + response.code());
        } catch (SSLPeerUnverifiedException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("b.test"));
        }
        assertEquals("b.test must have been coalesced onto a.test's connection", 1, connections.get());

        releaseSlow.countDown();
        assertTrue("a.test's request never completed", done.await(10, TimeUnit.SECONDS));
        Object outcome = slow.get();
        assertNotNull(outcome);
        assertEquals("a.test's stream must survive b.test's pin failure: " + outcome, "200 h2 slow-ok", outcome);
    }

    @Test
    public void aPinFailureIsReportedOnEveryRequestToTheHost() throws Exception {
        protect("[\"" + LocalHttpsFixture.WRONG_PIN + "\"]");
        List<String> paths = Arrays.asList("/one", "/two");
        for (String path : paths) {
            try (Response response = ApproovService.getOkHttpClient().newCall(
                    new Request.Builder().url(url("b.test", path)).build()).execute()) {
                fail("b.test's wrong pin must fail " + path + ", got " + response.code());
            } catch (SSLPeerUnverifiedException expected) {
                // checked again on the pooled connection: no verdict is cached
            }
        }
        assertEquals("nothing reached the server", 0, server.getRequestCount());
    }
}
