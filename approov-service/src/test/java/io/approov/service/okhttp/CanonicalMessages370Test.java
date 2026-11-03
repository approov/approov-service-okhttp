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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.util.Log;

import com.criticalblue.approovsdk.Approov;
import com.criticalblue.minisdk.testing.AttesterProxyController;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLPeerUnverifiedException;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;

/**
 * The exception and log texts shared by the Approov packages read the same in
 * each, the 3.5.8 texts where 3.5.8 had one. These are the texts no other test
 * asserts exactly.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class CanonicalMessages370Test {
    private TwoOriginFixture fixture;

    @Before
    public void setUp() throws Exception {
        fixture = new TwoOriginFixture(true, "the-key", "the-secret");
        ApproovService.reset();
        fixture.initialize();
        ApproovService.setOkHttpClientBuilder(fixture.trustingBuilder());
        ShadowLog.reset();
    }

    @After
    public void tearDown() throws Exception {
        fixture.shutdown();
        ApproovService.reset();
        RecordingSdk.uninstall();
    }

    private static boolean logged(String text) {
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            if (text.equals(item.msg))
                return true;
        }
        return false;
    }

    @Test
    public void theStandardTokenDecisionNamesTheFullUrl() throws Exception {
        AttesterProxyController.setNextAttestationDirectiveJson("{\"operation\": \"fetchApproovToken\","
                + " \"response\": {\"status\": \"REJECTED\"}}");
        Request request = new Request.Builder()
                .url(fixture.protectedServer.url("/users/someone?email=someone@example.com")).build();
        try (Response response = ApproovService.getOkHttpClient().newCall(request).execute()) {
            fail("CLOSE_FAILURE must abort on REJECTED, got " + response.code());
        } catch (ApproovFetchStatusException e) {
            assertEquals("Approov token fetch for " + request.url() + ": REJECTED", e.getMessage());
        }
    }

    @Test
    public void aRejectionCarriesItsArcAndReasons() {
        AttesterProxyController.setNextAttestationDirectiveJson("{\"operation\": \"fetchCustomJWT\","
                + " \"response\": {\"status\": \"REJECTED\", \"arc\": \"ARC1\", \"rejectionReasons\": \"root\"}}");
        ApproovRejectionException e = assertThrows(ApproovRejectionException.class,
                () -> ApproovService.fetchCustomJWT("{\"role\":\"tester\"}"));
        assertEquals("fetchCustomJWT: REJECTED: ARC1 root", e.getMessage());

        AttesterProxyController.setNextAttestationDirectiveJson("{\"operation\": \"fetchSecureString\","
                + " \"response\": {\"status\": \"REJECTED\", \"arc\": \"ARC2\", \"rejectionReasons\": \"emulator\"}}");
        e = assertThrows(ApproovRejectionException.class, ApproovService::precheck);
        assertEquals("precheck: REJECTED: ARC2 emulator", e.getMessage());

        AttesterProxyController.setNextAttestationDirectiveJson("{\"operation\": \"fetchSecureString\","
                + " \"response\": {\"status\": \"REJECTED\", \"arc\": \"ARC3\", \"rejectionReasons\": \"root\"}}");
        e = assertThrows(ApproovRejectionException.class, () -> ApproovService.fetchSecureString("the-key", null));
        assertEquals("fetchSecureString lookup for the-key: REJECTED: ARC3 root", e.getMessage());

        AttesterProxyController.setNextAttestationDirectiveJson("{\"operation\": \"fetchSecureString\","
                + " \"response\": {\"status\": \"REJECTED\", \"arc\": \"ARC4\", \"rejectionReasons\": \"root\"}}");
        e = assertThrows(ApproovRejectionException.class, () -> ApproovService.fetchSecureString("the-key", "new"));
        assertEquals("fetchSecureString definition for the-key: REJECTED: ARC4 root", e.getMessage());
    }

    @Test
    public void anSdkFailureIsWrappedWithItsClassAndMessage() {
        IllegalStateException thrown = new IllegalStateException("sdk failure in test");
        RecordingSdk.install(new RecordingSdk() {
            @Override
            public Approov.TokenFetchResult fetchApproovTokenAndWait(String url) {
                throw thrown;
            }

            @Override
            public Approov.TokenFetchResult fetchCustomJWTAndWait(String payload) {
                return null;
            }

            @Override
            public String getAccountMessageSignature(String message) {
                return null;
            }

            @Override
            public String getInstallMessageSignature(String message) {
                return null;
            }
        });
        ApproovException e = assertThrows(ApproovException.class,
                () -> ApproovService.fetchToken("https://localhost/p"));
        assertEquals("Wrapped java.lang.IllegalStateException: sdk failure in test", e.getMessage());
        assertSame(thrown, e.getCause());
        // the log names the SDK call that failed
        assertTrue(logged("fetchApproovTokenAndWait: Wrapped java.lang.IllegalStateException: sdk failure in test"));
        e = assertThrows(ApproovException.class, () -> ApproovService.fetchCustomJWT("{}"));
        assertEquals("Approov SDK fetchCustomJWTAndWait returned no result", e.getMessage());
        e = assertThrows(ApproovException.class, () -> ApproovService.getAccountMessageSignature("m"));
        assertEquals("no account signature available", e.getMessage());
        e = assertThrows(ApproovException.class, () -> ApproovService.getInstallMessageSignature("m"));
        assertEquals("no device signature available", e.getMessage());
    }

    @Test
    public void pinsUnavailableForAConnectionNameTheHost() throws Exception {
        IllegalStateException thrown = new IllegalStateException("pins unreadable in test");
        RecordingSdk.install(new RecordingSdk() {
            @Override
            public Map<String, List<String>> getPins(String pinType) {
                throw thrown;
            }
        });
        assertThrows(ApproovException.class, ApproovService::rebuildPins);
        ShadowLog.reset();
        try (Response response = ApproovService.getOkHttpClient().newCall(
                new Request.Builder().url(fixture.protectedServer.url("/p")).build()).execute()) {
            fail("a connection whose pins cannot be read must fail, got " + response.code());
        } catch (SSLPeerUnverifiedException e) {
            assertEquals("Approov pinning: pins unavailable for localhost", e.getMessage());
            assertSame(thrown, e.getCause());
        }

        // the failure is logged once, at error level
        List<String> lines = new ArrayList<>();
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            if ((item.msg != null) && item.msg.contains(thrown.toString()))
                lines.add(item.type + " " + item.msg);
        }
        assertEquals(Collections.singletonList(Log.ERROR + " Approov pinning: pins unavailable for localhost: "
                + thrown), lines);
    }

    @Test
    public void aRedirectToANewOriginIsLoggedWithoutAnyUrl() throws Exception {
        ApproovService.setLoggingLevel(ApproovLogLevel.DEBUG);
        fixture.protectedServer.enqueue(new MockResponse().setResponseCode(302)
                .setHeader("Location", fixture.otherUrl("/next").toString()));
        fixture.otherServer.enqueue(new MockResponse().setBody("ok"));
        try (Response response = ApproovService.getOkHttpClient().newCall(new Request.Builder()
                .url(fixture.protectedServer.url("/start?user=someone")).build()).execute()) {
            assertEquals(200, response.code());
        }
        assertTrue(logged("Request rebuilt for a new origin: reapplying Approov protection"));
    }

    @Test
    public void nullArgumentsNameTheMethod() {
        assertEquals("initialize: null context", assertThrows(IllegalArgumentException.class,
                () -> ApproovService.initialize(null, TwoOriginFixture.CONFIG)).getMessage());
        assertEquals("config must not be null; pass \"\" for bypass mode", assertThrows(IllegalArgumentException.class,
                () -> ApproovService.initialize(fixture.context, null)).getMessage());
        assertEquals("setLoggingLevel: null level", assertThrows(IllegalArgumentException.class,
                () -> ApproovService.setLoggingLevel(null)).getMessage());
        assertEquals("addSubstitutionHeader: null header", assertThrows(IllegalArgumentException.class,
                () -> ApproovService.addSubstitutionHeader(null, "Bearer ")).getMessage());
        assertTrue("a null header is never stored", ApproovService.getSubstitutionHeaders().isEmpty());
    }

    @Test
    public void aNullTokenHeaderIsAWarning() {
        // a null token header still means no token header, so it is warned about rather than refused
        ApproovService.setTokenHeader(null, null);
        int warnings = 0;
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            if ((item.type == Log.WARN) && item.msg.contains("setTokenHeader: null header"))
                warnings++;
        }
        assertEquals("one warning naming the method", 1, warnings);
    }
}
