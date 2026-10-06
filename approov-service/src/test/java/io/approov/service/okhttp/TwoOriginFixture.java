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

import com.criticalblue.minisdk.testing.AttesterProxyController;

import java.net.InetAddress;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;

/**
 * Two local origins and a mini-SDK scenario in which Approov protects only the
 * first: {@code protectedServer} is HTTPS on localhost, a protected domain with no
 * pins (OS trust), and {@code otherServer} listens on 127.0.0.1, a host Approov
 * does not protect, over HTTPS or, when asked, over cleartext HTTP. A redirect
 * from one to the other crosses origins. Secure strings are set as
 * {@code key: value} pairs.
 */
final class TwoOriginFixture {
    static final String CONFIG = LocalHttpsFixture.CONFIG;

    final Context context = ApplicationProvider.getApplicationContext();
    final MockWebServer protectedServer = new MockWebServer();
    final MockWebServer otherServer = new MockWebServer();
    private final HandshakeCertificates clientHandshake;

    /**
     * @param otherOverTls  whether the other origin is HTTPS (true) or HTTP (false)
     * @param secureStrings secure string keys and values, alternating
     */
    TwoOriginFixture(boolean otherOverTls, String... secureStrings) throws Exception {
        HeldCertificate certificate = new HeldCertificate.Builder()
                .addSubjectAlternativeName("localhost")
                .addSubjectAlternativeName("127.0.0.1")
                .build();
        HandshakeCertificates serverHandshake = new HandshakeCertificates.Builder()
                .heldCertificate(certificate)
                .build();
        protectedServer.useHttps(serverHandshake.sslSocketFactory(), false);
        protectedServer.start(InetAddress.getByName("localhost"), 0);
        if (otherOverTls)
            otherServer.useHttps(serverHandshake.sslSocketFactory(), false);
        otherServer.start(InetAddress.getByName("127.0.0.1"), 0);
        clientHandshake = new HandshakeCertificates.Builder()
                .addTrustedCertificate(certificate.certificate())
                .build();

        StringBuilder strings = new StringBuilder();
        for (int i = 0; i + 1 < secureStrings.length; i += 2) {
            if (strings.length() > 0)
                strings.append(", ");
            strings.append('"').append(secureStrings[i]).append("\": \"").append(secureStrings[i + 1]).append('"');
        }
        AttesterProxyController.reset();
        AttesterProxyController.loadScenarioJson("{\"activeCase\": \"two-origin\", \"cases\": {\"two-origin\": {"
                + "\"protectedDomains\": [\"localhost\"],"
                + "\"pins\": {\"public-key-sha256\": {\"localhost\": []}},"
                + "\"initialSecureStrings\": {" + strings + "}}}}");
    }

    /**
     * A URL on the other origin, named by its IP address: MockWebServer names both
     * servers "localhost", which Approov protects.
     */
    okhttp3.HttpUrl otherUrl(String path) {
        return otherServer.url(path).newBuilder().host("127.0.0.1").build();
    }

    /** A builder whose TLS trusts both local servers, as OS trust would. */
    OkHttpClient.Builder trustingBuilder() {
        return new OkHttpClient.Builder()
                .sslSocketFactory(clientHandshake.sslSocketFactory(), clientHandshake.trustManager());
    }

    /** Initializes the layer with Approov protection enabled. */
    void initialize() {
        ApproovService.initialize(context, CONFIG, "reinit-two-origin");
    }

    void shutdown() throws Exception {
        protectedServer.shutdown();
        otherServer.shutdown();
        AttesterProxyController.reset();
    }
}
