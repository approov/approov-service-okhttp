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
import static org.junit.Assert.assertThrows;

import androidx.test.core.app.ApplicationProvider;

import com.criticalblue.minisdk.testing.AttesterProxyController;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLPeerUnverifiedException;

import okhttp3.Call;
import okhttp3.CertificatePinner;
import okhttp3.CipherSuite;
import okhttp3.Connection;
import okhttp3.Handshake;
import okhttp3.Interceptor;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Route;
import okhttp3.TlsVersion;
import okhttp3.tls.HeldCertificate;

/**
 * Pin lookup is exact per host, without regard to case and with one trailing dot
 * ignored. The Approov admin API accepts upper case (and a trailing dot) in an API
 * domain, so the SDK can deliver a pin key such as "API.Example.COM" for requests
 * to api.example.com, which must be pinned by it (the iOS package once missed
 * them). The pin keys reach OkHttp's CertificatePinner, which compares the host
 * strings as given, so the layer normalizes both sides.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class PinKeyMatching380Test {
    private static final String HOST = "api.example.com";
    private static final String MIXED_KEY = "API.Example.COM";

    private HeldCertificate certificate;

    @Before
    public void setUp() {
        ApproovService.reset();
        certificate = new HeldCertificate.Builder().commonName(HOST).addSubjectAlternativeName(HOST).build();
    }

    @After
    public void tearDown() {
        ApproovService.reset();
        AttesterProxyController.reset();
    }

    // protection enabled with the given entries in the SDK's public-key-sha256 pins
    private ApproovPinningInterceptor protect(String pinEntries) {
        AttesterProxyController.reset();
        AttesterProxyController.loadScenarioJson("{\"activeCase\": \"keys\", \"cases\": {\"keys\": {"
                + "\"protectedDomains\": [\"" + HOST + "\"],"
                + "\"pins\": {\"public-key-sha256\": {" + pinEntries + "}}}}}");
        ApproovService.initialize(ApplicationProvider.getApplicationContext(), LocalHttpsFixture.CONFIG,
                "reinit-keys-" + pinEntries);
        return new ApproovPinningInterceptor();
    }

    private String pin() {
        return CertificatePinner.pin(certificate.certificate()).substring("sha256/".length());
    }

    private int check(ApproovPinningInterceptor interceptor, String host) throws Exception {
        Handshake handshake = Handshake.get(TlsVersion.TLS_1_3, CipherSuite.TLS_AES_128_GCM_SHA256,
                Collections.singletonList(certificate.certificate()), Collections.emptyList());
        return interceptor.intercept(
                new TestChain(new Request.Builder().url("https://" + host + "/v1/data").build(), handshake)).code();
    }

    @Test
    public void aMixedCasePinKeyPinsItsLowerCaseHost() throws Exception {
        assertEquals(200, check(protect("\"" + MIXED_KEY + "\": [\"" + pin() + "\"]"), HOST));
        ApproovService.reset();
        ApproovPinningInterceptor wrong = protect("\"" + MIXED_KEY + "\": [\"" + LocalHttpsFixture.WRONG_PIN + "\"]");
        assertThrows("the mixed-case key pins the host", SSLPeerUnverifiedException.class, () -> check(wrong, HOST));
        assertThrows(SSLPeerUnverifiedException.class, () -> check(wrong, "Api.Example.Com"));
    }

    @Test
    public void aMixedCaseKeyWithNoPinsFallsBackToTheManagedTrustRoots() throws Exception {
        ApproovPinningInterceptor interceptor = protect("\"" + MIXED_KEY + "\": [], \"*\": [\""
                + LocalHttpsFixture.WRONG_PIN + "\"]");
        assertThrows("pinned to the mismatching managed trust root", SSLPeerUnverifiedException.class,
                () -> check(interceptor, HOST));
    }

    @Test
    public void aPinKeyWithATrailingDotPinsItsHost() throws Exception {
        ApproovPinningInterceptor interceptor = protect("\"" + MIXED_KEY + ".\": [\""
                + LocalHttpsFixture.WRONG_PIN + "\"]");
        assertThrows("one trailing dot ignored", SSLPeerUnverifiedException.class, () -> check(interceptor, HOST));
    }

    @Test
    public void aHostWithATrailingDotIsPinnedByItsKey() throws Exception {
        ApproovPinningInterceptor interceptor = protect("\"" + MIXED_KEY + "\": [\""
                + LocalHttpsFixture.WRONG_PIN + "\"]");
        assertThrows("one trailing dot ignored", SSLPeerUnverifiedException.class,
                () -> check(interceptor, HOST + "."));
    }

    /** A network chain whose connection carries the given handshake. */
    private static final class TestChain implements Interceptor.Chain {
        private final Request request;
        private final Connection connection;

        TestChain(Request request, Handshake handshake) {
            this.request = request;
            this.connection = new Connection() {
                @Override public Route route() { throw new UnsupportedOperationException(); }
                @Override public java.net.Socket socket() { return new java.net.Socket(); }
                @Override public Handshake handshake() { return handshake; }
                @Override public Protocol protocol() { return Protocol.HTTP_1_1; }
            };
        }

        @Override public Request request() { return request; }
        @Override public Response proceed(Request request) {
            return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").build();
        }
        @Override public Connection connection() { return connection; }
        @Override public Call call() { throw new UnsupportedOperationException(); }
        @Override public int connectTimeoutMillis() { return 0; }
        @Override public Interceptor.Chain withConnectTimeout(int t, TimeUnit u) { return this; }
        @Override public int readTimeoutMillis() { return 0; }
        @Override public Interceptor.Chain withReadTimeout(int t, TimeUnit u) { return this; }
        @Override public int writeTimeoutMillis() { return 0; }
        @Override public Interceptor.Chain withWriteTimeout(int t, TimeUnit u) { return this; }
    }
}
