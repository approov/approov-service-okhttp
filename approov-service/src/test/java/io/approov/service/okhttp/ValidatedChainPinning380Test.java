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

import java.net.InetAddress;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;

import okhttp3.CertificatePinner;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;

/**
 * Pins are matched against the chain the trust manager validated, never against
 * whatever the server presents (CVE-2016-2402 class). A server that appends the
 * genuine pinned certificate to a chain issued by another CA the device trusts
 * (an interception proxy whose root is installed) must fail the pin check. This
 * holds because OkHttp 4.x stores the cleaned chain in the connection's
 * handshake, which is what the layer checks; it pins that assumption, and so the
 * minimum supported OkHttp version.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class ValidatedChainPinning380Test {
    private final Context context = ApplicationProvider.getApplicationContext();
    private final MockWebServer server = new MockWebServer();
    private HeldCertificate interceptionRoot;
    private HeldCertificate interceptionLeaf;
    private HeldCertificate genuine;

    @Before
    public void setUp() throws Exception {
        // the interception CA the device trusts, and the leaf it issued for the host
        interceptionRoot = new HeldCertificate.Builder().commonName("Root").certificateAuthority(0).build();
        interceptionLeaf = new HeldCertificate.Builder()
                .addSubjectAlternativeName("localhost")
                .signedBy(interceptionRoot)
                .build();
        // the genuine certificate whose public key the account pins for the host; its
        // subject names the interception root, so that the server's key store accepts
        // it after the root in the presented chain (its key signed nothing there)
        genuine = new HeldCertificate.Builder().commonName("Root").addSubjectAlternativeName("localhost").build();

        // the server presents the interception chain with the genuine certificate
        // appended, as in CVE-2016-2402
        server.useHttps(new HandshakeCertificates.Builder()
                .heldCertificate(interceptionLeaf, interceptionRoot.certificate(), genuine.certificate())
                .build().sslSocketFactory(), false);
        server.enqueue(new MockResponse().setBody("ok"));
        server.start(InetAddress.getByName("localhost"), 0);
        ApproovService.reset();
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
        AttesterProxyController.reset();
        ApproovService.reset();
    }

    private HandshakeCertificates deviceTrust() {
        return new HandshakeCertificates.Builder().addTrustedCertificate(interceptionRoot.certificate()).build();
    }

    private void protectWithPin(HeldCertificate pinned) {
        String pin = CertificatePinner.pin(pinned.certificate()).substring("sha256/".length());
        AttesterProxyController.reset();
        AttesterProxyController.loadScenarioJson("{\"activeCase\": \"chain\", \"cases\": {\"chain\": {"
                + "\"protectedDomains\": [\"localhost\"],"
                + "\"pins\": {\"public-key-sha256\": {\"localhost\": [\"" + pin + "\"]}}}}}");
        ApproovService.initialize(context, LocalHttpsFixture.CONFIG, "reinit-chain");
        HandshakeCertificates trust = deviceTrust();
        ApproovService.setOkHttpClientBuilder(new OkHttpClient.Builder()
                .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager()));
    }

    // the certificates the server presents, read with a plain TLS handshake
    private List<Certificate> presentedChain() throws Exception {
        try (SSLSocket socket = (SSLSocket) deviceTrust().sslSocketFactory()
                .createSocket(server.getHostName(), server.getPort())) {
            socket.startHandshake();
            List<Certificate> chain = new ArrayList<>();
            for (Certificate certificate : socket.getSession().getPeerCertificates())
                chain.add(certificate);
            return chain;
        }
    }

    @Test
    public void theGenuineCertificateIsOnTheWire() throws Exception {
        List<Certificate> chain = presentedChain();
        assertEquals("leaf, root and the appended certificate: " + chain, 3, chain.size());
        assertEquals(genuine.certificate(), chain.get(2));
    }

    @Test
    public void aPinOnTheValidatedLeafPasses() throws Exception {
        protectWithPin(interceptionLeaf);
        try (Response response = ApproovService.getOkHttpClient().newCall(
                new Request.Builder().url(server.url("/v1")).build()).execute()) {
            assertEquals(200, response.code());
        }
    }

    @Test
    public void aPinnedCertificateAppendedToAnUnrelatedChainFails() throws Exception {
        protectWithPin(genuine);
        try (Response response = ApproovService.getOkHttpClient().newCall(
                new Request.Builder().url(server.url("/v1")).build()).execute()) {
            fail("the appended genuine certificate must not satisfy the pin, got " + response.code());
        } catch (SSLPeerUnverifiedException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Certificate pinning failure"));
        }
        assertEquals("nothing sent to the interception server", 0, server.getRequestCount());
    }
}
