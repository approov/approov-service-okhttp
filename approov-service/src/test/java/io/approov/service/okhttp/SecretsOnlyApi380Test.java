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
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLPeerUnverifiedException;

import okhttp3.CertificatePinner;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;

/**
 * A secrets-only API (decided 2026-10-07; regression since okhttp 3.5.2): a
 * domain added with {@code approov api -add <domain> -noApproovToken}, which the
 * SDK reports as UNPROTECTED_URL ("the domain is pinned but no Approov token is
 * required"). Its requests get their secure strings substituted and its
 * connections pinned, and carry no token, status or trace header and no
 * signatures, with signing enabled. A wrong pin, on the host or through the
 * {@code *} entry, fails the connection before anything is sent. Control: a host
 * not added to Approov gets nothing and is not pinned.
 *
 * The mini-SDK scenario lists localhost under {@code noApproovTokenDomains} and
 * not under {@code protectedDomains}, so the token fetch for it reports
 * UNPROTECTED_URL from the SDK's own lookup, not from a forced status.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class SecretsOnlyApi380Test {
    private static final String CONFIG = LocalHttpsFixture.CONFIG;
    private static final String PLACEHOLDER = "api-key-placeholder";
    private static final String SECRET = "real-api-key-value";

    private final Context context = ApplicationProvider.getApplicationContext();
    private final MockWebServer server = new MockWebServer();
    private HandshakeCertificates clientHandshake;
    private String serverPin;

    @Before
    public void setUp() throws Exception {
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
        serverPin = CertificatePinner.pin(certificate.certificate()).substring("sha256/".length());
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
        AttesterProxyController.reset();
        ApproovService.reset();
    }

    /**
     * @param localhostPins the JSON pin list of localhost, or null to leave it out of
     *                      the pin set
     * @param starPins      the JSON pin list of "*", or null for none
     * @param secretsOnly   whether localhost is a -noApproovToken API domain; if not,
     *                      the token fetch reports UNKNOWN_URL for it
     */
    private void start(String localhostPins, String starPins, boolean secretsOnly) {
        StringBuilder pins = new StringBuilder();
        if (localhostPins != null)
            pins.append("\"localhost\": ").append(localhostPins);
        if (starPins != null)
            pins.append((pins.length() > 0) ? ", " : "").append("\"*\": ").append(starPins);
        String name = "secrets-only-" + java.util.UUID.randomUUID();
        AttesterProxyController.reset();
        AttesterProxyController.loadScenarioJson("{\"activeCase\": \"" + name + "\", \"cases\": {\"" + name + "\": {"
                + "\"protectedDomains\": [\"api.example.com\"],"
                + (secretsOnly ? "\"noApproovTokenDomains\": [\"localhost\"],"
                        : "\"fetchApproovToken\": [{\"urlRegex\": \"https://localhost:.*\", \"status\": \"UNKNOWN_URL\"}],")
                + "\"pins\": {\"public-key-sha256\": {" + pins + "}},"
                + "\"initialSecureStrings\": {\"" + PLACEHOLDER + "\": \"" + SECRET + "\"}}}}");
        ApproovService.reset();
        // a comment of its own, so that the mini-SDK takes this scenario's pins
        ApproovService.initialize(context, CONFIG, "reinit-" + name);
        ApproovService.addSubstitutionHeader("Api-Key", null);
        ApproovService.addSubstitutionQueryParam("key");
        ApproovService.enableMessageSigning();
        ApproovService.setOkHttpClientBuilder(new OkHttpClient.Builder()
                .sslSocketFactory(clientHandshake.sslSocketFactory(), clientHandshake.trustManager()));
    }

    private Request request() {
        HttpUrl url = server.url("/data").newBuilder().addQueryParameter("key", PLACEHOLDER).build();
        return new Request.Builder().url(url).header("Api-Key", PLACEHOLDER).build();
    }

    private RecordedRequest sendAndTake() throws Exception {
        server.enqueue(new MockResponse().setBody("ok"));
        try (Response response = ApproovService.getOkHttpClient().newCall(request()).execute()) {
            assertEquals(200, response.code());
        }
        return server.takeRequest(5, TimeUnit.SECONDS);
    }

    private void assertPinningFails() throws Exception {
        server.enqueue(new MockResponse().setBody("ok"));
        try (Response response = ApproovService.getOkHttpClient().newCall(request()).execute()) {
            fail("a wrong pin must fail the connection, got " + response.code());
        } catch (SSLPeerUnverifiedException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("localhost"));
        }
        assertEquals("nothing sent", 0, server.getRequestCount());
    }

    @Test
    public void theSecretIsSubstitutedOnAPinnedConnectionWithNoApproovHeaders() throws Exception {
        start("[\"" + serverPin + "\"]", null, true);
        RecordedRequest recorded = sendAndTake();
        assertEquals("secret in the header", SECRET, recorded.getHeader("Api-Key"));
        assertEquals("secret in the query", SECRET, recorded.getRequestUrl().queryParameter("key"));
        LocalHttpsFixture.assertNoApproovHeaders(recorded);
    }

    @Test
    public void underAlwaysProceedToo() throws Exception {
        start("[\"" + serverPin + "\"]", null, true);
        ApproovService.setServiceMutator(ApproovServiceMutator.ALWAYS_PROCEED);
        RecordedRequest recorded = sendAndTake();
        assertEquals(SECRET, recorded.getHeader("Api-Key"));
        assertEquals(SECRET, recorded.getRequestUrl().queryParameter("key"));
        LocalHttpsFixture.assertNoApproovHeaders(recorded);
    }

    @Test
    public void aWrongPinFailsTheConnection() throws Exception {
        start("[\"" + LocalHttpsFixture.WRONG_PIN + "\"]", null, true);
        assertPinningFails();
    }

    @Test
    public void aHostWithNoPinsOfItsOwnIsPinnedToTheStarEntry() throws Exception {
        start("[]", "[\"" + LocalHttpsFixture.WRONG_PIN + "\"]", true);
        assertPinningFails();
    }

    @Test
    public void control_aHostNotAddedToApproovGetsNothingAndIsNotPinned() throws Exception {
        // not in the pin set: the wrong "*" pin does not apply to it
        start(null, "[\"" + LocalHttpsFixture.WRONG_PIN + "\"]", false);
        RecordedRequest recorded = sendAndTake();
        assertEquals(PLACEHOLDER, recorded.getHeader("Api-Key"));
        assertEquals(PLACEHOLDER, recorded.getRequestUrl().queryParameter("key"));
        LocalHttpsFixture.assertNoApproovHeaders(recorded);
    }
}
