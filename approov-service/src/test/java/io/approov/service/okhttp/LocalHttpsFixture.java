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

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.criticalblue.approovsdk.Approov;
import com.criticalblue.minisdk.testing.AttesterProxyController;

import java.net.InetAddress;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;

import static org.junit.Assert.assertNull;

/**
 * A local HTTPS server on localhost trusted by OS-level trust (the client trusts
 * its certificate), and a mini-SDK scenario in which Approov protects localhost
 * with a pin that does not match that certificate. A request that reaches the
 * server was therefore not subjected to Approov pinning.
 */
final class LocalHttpsFixture {
    static final String CONFIG = "#cb-ivol#mAxOF0ekJUOC36J5XWmVmVipOcUoEdMjhPSp2FVtyTo=";
    static final String WRONG_PIN = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    static final String[] APPROOV_HEADERS = {
            "Approov-Token", "Approov-TraceID", "Approov-Status",
            "Signature", "Signature-Input", "Content-Digest"
    };

    final Context context = ApplicationProvider.getApplicationContext();
    final MockWebServer server = new MockWebServer();
    private final HandshakeCertificates clientHandshake;

    LocalHttpsFixture(boolean wrongPin) throws Exception {
        HeldCertificate certificate = new HeldCertificate.Builder()
                .addSubjectAlternativeName("localhost")
                .build();
        server.useHttps(new HandshakeCertificates.Builder()
                .heldCertificate(certificate)
                .build().sslSocketFactory(), false);
        server.start(InetAddress.getByName("localhost"), 0);
        clientHandshake = new HandshakeCertificates.Builder()
                .addTrustedCertificate(certificate.certificate())
                .build();

        AttesterProxyController.reset();
        String pins = wrongPin ? "[\"" + WRONG_PIN + "\"]" : "[]";
        AttesterProxyController.loadScenarioJson("{\"activeCase\": \"local\", \"cases\": {\"local\": {"
                + "\"protectedDomains\": [\"localhost\"],"
                + "\"pins\": {\"public-key-sha256\": {\"localhost\": " + pins + "}}}}}");
    }

    /**
     * Initializes the SDK directly, as another Approov layer or caller in the
     * process would, so that it is initialized and returns the scenario's pins
     * while this layer may not be.
     */
    void initializeSdkElsewhere() {
        Approov.initialize(context, CONFIG, "auto", "reinit-elsewhere");
    }

    /** A builder whose TLS trusts the local server, as OS trust would. */
    OkHttpClient.Builder trustingBuilder() {
        return new OkHttpClient.Builder()
                .sslSocketFactory(clientHandshake.sslSocketFactory(), clientHandshake.trustManager());
    }

    static void assertNoApproovHeaders(RecordedRequest recorded) {
        for (String header : APPROOV_HEADERS)
            assertNull(header + " must not be sent", recorded.getHeader(header));
    }

    void shutdown() throws Exception {
        server.shutdown();
        AttesterProxyController.reset();
    }
}
