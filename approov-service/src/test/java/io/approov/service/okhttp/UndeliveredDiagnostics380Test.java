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

import com.criticalblue.minisdk.testing.AttesterProxyController;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;

/**
 * Decided 2026-10-07: when the layer does not deliver a secure string (any secure
 * string status other than SUCCESS, or a channel that allows no secrets) or lets a
 * request proceed on a token fetch failure, it logs at DEBUG only: the header or
 * query parameter name (never a value, placeholder or secret), the fetch status,
 * and the ARC and rejection reasons when the SDK gives them. At the default INFO
 * level none of this is logged, at any severity.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class UndeliveredDiagnostics380Test {
    private static final String PLACEHOLDER = "api-key-placeholder";
    private static final String SECRET = "real-api-key-value";
    private static final String ARC = "ARCDIAG1";
    private static final String REASONS = "rooted-device";

    private TwoOriginFixture fixture;

    @Before
    public void setUp() throws Exception {
        fixture = new TwoOriginFixture(true, PLACEHOLDER, SECRET);
        ApproovService.reset();
        fixture.initialize();
        ApproovService.addSubstitutionHeader("Api-Key", null);
        ApproovService.addSubstitutionQueryParam("key");
        ApproovService.setOkHttpClientBuilder(fixture.trustingBuilder());
        ApproovService.setServiceMutator(ApproovServiceMutator.ALWAYS_PROCEED);
    }

    @After
    public void tearDown() throws Exception {
        fixture.shutdown();
        ApproovService.reset();
    }

    private void send(String operation, String status) throws Exception {
        AttesterProxyController.setNextAttestationDirectiveJson("{\"operation\": \"" + operation + "\","
                + " \"response\": {\"status\": \"" + status + "\", \"arc\": \"" + ARC + "\","
                + " \"rejectionReasons\": \"" + REASONS + "\"}}");
        fixture.protectedServer.enqueue(new MockResponse().setBody("ok"));
        HttpUrl url = fixture.protectedServer.url("/data").newBuilder().addQueryParameter("key", PLACEHOLDER).build();
        ShadowLog.reset();
        try (Response response = ApproovService.getOkHttpClient().newCall(
                new Request.Builder().url(url).header("Api-Key", PLACEHOLDER).build()).execute()) {
            assertEquals(200, response.code());
        }
        fixture.protectedServer.takeRequest(5, TimeUnit.SECONDS);
    }

    // every line logged since the last send that carries the diagnostic ARC
    private static List<ShadowLog.LogItem> diagnostics() {
        List<ShadowLog.LogItem> lines = new ArrayList<>();
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            assertFalse("a value was logged: " + item.msg, item.msg.contains(PLACEHOLDER));
            assertFalse("a secret was logged: " + item.msg, item.msg.contains(SECRET));
            if (item.msg.contains(ARC))
                lines.add(item);
        }
        return lines;
    }

    private static void assertDebugLine(List<ShadowLog.LogItem> lines, String... parts) {
        for (ShadowLog.LogItem item : lines) {
            boolean all = true;
            for (String part : parts)
                all &= item.msg.contains(part);
            if (all) {
                assertEquals("logged above DEBUG: " + item.msg, android.util.Log.DEBUG, item.type);
                return;
            }
        }
        throw new AssertionError("no DEBUG line with " + java.util.Arrays.toString(parts) + " in " + messages(lines));
    }

    private static List<String> messages(List<ShadowLog.LogItem> lines) {
        List<String> out = new ArrayList<>();
        for (ShadowLog.LogItem item : lines)
            out.add(item.msg);
        return out;
    }

    @Test
    public void aTokenFailureIsLoggedAtDebugWithItsArcAndReasons() throws Exception {
        ApproovService.setLoggingLevel(ApproovLogLevel.DEBUG);
        send("fetchApproovToken", "REJECTED");
        List<ShadowLog.LogItem> lines = diagnostics();
        // the request proceeding on the failure
        assertDebugLine(lines, "Approov token fetch for localhost: REJECTED", ARC, REASONS,
                "proceeding with the status header only");
        // the secure strings the failure channel does not deliver, by name
        assertDebugLine(lines, "header Api-Key", "token fetch status REJECTED", ARC, REASONS);
        assertDebugLine(lines, "query parameter key", "token fetch status REJECTED", ARC, REASONS);
        for (ShadowLog.LogItem item : lines)
            assertEquals(item.msg, android.util.Log.DEBUG, item.type);
    }

    @Test
    public void aSecureStringFailureIsLoggedAtDebugWithItsArcAndReasons() throws Exception {
        ApproovService.setLoggingLevel(ApproovLogLevel.DEBUG);
        send("fetchSecureString", "REJECTED");
        List<ShadowLog.LogItem> lines = diagnostics();
        assertDebugLine(lines, "header Api-Key", "secure string fetch status REJECTED", ARC, REASONS,
                "status sent in its place");
        for (ShadowLog.LogItem item : lines)
            assertEquals(item.msg, android.util.Log.DEBUG, item.type);
    }

    @Test
    public void aFailureToAHostNotInThePinSetIsLoggedAtDebug() throws Exception {
        ApproovService.setLoggingLevel(ApproovLogLevel.DEBUG);
        AttesterProxyController.setNextAttestationDirectiveJson("{\"operation\": \"fetchApproovToken\","
                + " \"response\": {\"status\": \"NO_APPROOV_SERVICE\", \"arc\": \"" + ARC + "\"}}");
        fixture.otherServer.enqueue(new MockResponse().setBody("ok"));
        HttpUrl url = fixture.otherUrl("/data").newBuilder().addQueryParameter("key", PLACEHOLDER).build();
        ShadowLog.reset();
        try (Response response = ApproovService.getOkHttpClient().newCall(
                new Request.Builder().url(url).header("Api-Key", PLACEHOLDER).build()).execute()) {
            assertEquals(200, response.code());
        }
        List<ShadowLog.LogItem> lines = diagnostics();
        assertDebugLine(lines, "Approov token fetch for 127.0.0.1: NO_APPROOV_SERVICE", ARC,
                "not an Approov API domain, proceeding untouched");
        assertDebugLine(lines, "header Api-Key", "token fetch status NO_APPROOV_SERVICE", ARC);
        assertDebugLine(lines, "query parameter key", "token fetch status NO_APPROOV_SERVICE", ARC);
    }

    @Test
    public void aTokenFailureSentWithNoApproovHeaderIsLoggedAtDebug() throws Exception {
        // a custom mutator that lets a failure proceed with no Approov header at all
        ApproovService.setServiceMutator(new ApproovServiceMutator() {
            @Override
            public boolean handleInterceptorFetchTokenResult(com.criticalblue.approovsdk.Approov.TokenFetchResult
                    approovResults, String url) {
                return approovResults.getStatus() == com.criticalblue.approovsdk.Approov.TokenFetchStatus.SUCCESS;
            }
        });
        ApproovService.setLoggingLevel(ApproovLogLevel.DEBUG);
        send("fetchApproovToken", "REJECTED");
        assertDebugLine(diagnostics(), "Approov token fetch for localhost: REJECTED", ARC, REASONS,
                "proceeding with no Approov header");
    }

    @Test
    public void anAbortIgnoredForAHostNotInThePinSetIsLoggedAtDebug() throws Exception {
        // CLOSE_FAILURE throws for REJECTED, which is ignored for a host that is not an Approov API domain
        ApproovService.setServiceMutator(ApproovServiceMutator.CLOSE_FAILURE);
        ApproovService.setLoggingLevel(ApproovLogLevel.DEBUG);
        AttesterProxyController.setNextAttestationDirectiveJson("{\"operation\": \"fetchApproovToken\","
                + " \"response\": {\"status\": \"REJECTED\"}}");
        fixture.otherServer.enqueue(new MockResponse().setBody("ok"));
        ShadowLog.reset();
        try (Response response = ApproovService.getOkHttpClient().newCall(
                new Request.Builder().url(fixture.otherUrl("/data")).build()).execute()) {
            assertEquals(200, response.code());
        }
        boolean logged = false;
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            if (item.msg.equals("handleInterceptorFetchTokenResult: abort ignored for 127.0.0.1, "
                    + "not an Approov API domain")) {
                assertEquals("logged above DEBUG: " + item.msg, android.util.Log.DEBUG, item.type);
                logged = true;
            }
        }
        assertTrue("no DEBUG line for the ignored abort", logged);
    }

    @Test
    public void nothingIsLoggedAtInfo() throws Exception {
        // INFO is the default level
        ApproovService.setLoggingLevel(ApproovLogLevel.INFO);
        send("fetchApproovToken", "REJECTED");
        assertTrue("logged at INFO: " + messages(diagnostics()), diagnostics().isEmpty());
        assertNoLineNaming("Api-Key");
        send("fetchSecureString", "REJECTED");
        assertTrue("logged at INFO: " + messages(diagnostics()), diagnostics().isEmpty());
        assertNoLineNaming("Api-Key");
    }

    @Test
    public void aCleartextRequestIsStillAWarning() throws Exception {
        // decided 2026-10-07: a placeholder left because the request is not https
        // (or its token fetch returned BAD_URL) is a configuration problem and stays
        // a warning, visible at the default level
        ApproovService.setLoggingLevel(ApproovLogLevel.INFO);
        TwoOriginFixture cleartext = new TwoOriginFixture(false, PLACEHOLDER, SECRET);
        try {
            ApproovService.reset();
            cleartext.initialize();
            ApproovService.addSubstitutionHeader("Api-Key", null);
            ApproovService.setServiceMutator(ApproovServiceMutator.ALWAYS_PROCEED);
            cleartext.otherServer.enqueue(new MockResponse().setBody("ok"));
            ShadowLog.reset();
            try (Response response = ApproovService.getOkHttpClient().newCall(new Request.Builder()
                    .url(cleartext.otherUrl("/data")).header("Api-Key", PLACEHOLDER).build()).execute()) {
                assertEquals(200, response.code());
            }
            boolean warned = false;
            for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
                assertFalse("a value was logged: " + item.msg, item.msg.contains(PLACEHOLDER));
                warned |= (item.type == android.util.Log.WARN) && item.msg.contains("header Api-Key")
                        && item.msg.contains("request is not https");
            }
            assertTrue("no warning naming the header", warned);
        } finally {
            cleartext.shutdown();
        }
    }

    private static void assertNoLineNaming(String name) {
        for (ShadowLog.LogItem item : ShadowLog.getLogs())
            assertFalse("logged at INFO: " + item.msg, item.msg.contains(name));
    }
}
