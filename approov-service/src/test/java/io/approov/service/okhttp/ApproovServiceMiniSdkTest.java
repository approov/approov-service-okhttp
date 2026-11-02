package io.approov.service.okhttp;
 
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import com.criticalblue.minisdk.testing.AttesterProxyController;
import okhttp3.CertificatePinner;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;
 
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import com.criticalblue.approovsdk.Approov;
 
import static org.junit.Assert.*;

/**
 * Integration tests for the ApproovService OkHttp service layer, run against the
 * mini-SDK. Tests are grouped by area: initialization, request processing and
 * token behaviors, service mutators, pinning, message signing, and secure strings.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class ApproovServiceMiniSdkTest {
    private final String validInitialConfig = "#test-account#mAxOF0ekJUOC36J5XWmVmVipOcUoEdMjhPSp2FVtyTo=";
    private Context context;
 
    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        AttesterProxyController.reset();
        // reset also clears what initialize keeps (the logging level, the pinning
        // interceptor), so each test starts from a clean layer state
        ApproovService.reset();
        ApproovService.initialize(context, validInitialConfig, "reinit-okhttp-tests");
    }
 
    @After
    public void tearDown() {
        ApproovService.reset();
        AttesterProxyController.reset();
    }

    // ==================================================================================
    // SECTION 1: Initialization
    // ==================================================================================
    /**
     * Same Config Re-initialization / Different Config Re-initialization
     *
     * Re-initialize with the same config string should not fail (platform SDK returns false).
     * Re-initialize with a different config string should fail: the platform SDK throws
     * IllegalStateException which is re-thrown by the service layer.
     * A failure preserves the prior operating state: the service layer remains
     * initialized and Approov-enabled.
     */
    @Test
    public void testInitializeIgnoresSameConfigAndRejectsDifferentConfig() {
        // Re-init with same config: SDK returns false, service layer logs and continues
        ApproovService.initialize(context, validInitialConfig);

        // Re-init with different config: platform SDK throws IllegalStateException
        String differentConfig = "#test-account-other#mAxOF0ekJUOC36J5XWmVmVipOcUoEdMjhPSp2FVtyTo=";
        try {
            ApproovService.initialize(context, differentConfig);
            fail("Expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertNotNull(e.getMessage());
        }
        // a failure preserves the prior operating state
        assertTrue(ApproovService.isApproovServiceEnabled());
        assertTrue(ApproovService.isApproovProtectionEnabled());
    }

    /**
     * Empty Configuration (Valid Comment) / Empty Configuration (Empty Comment)
     *
     * Initializing with an empty config should keep the service layer initialized
     * while making the returned client behave like a plain OkHttp client with no
     * Approov mutations.
     */
    @Test
    public void testInitializeWithEmptyConfigBuildsPlainClient() throws Exception {
        AttesterProxyController.reset();
        AttesterProxyController.loadScenarioJson(scenarioJson(uniqueCaseName("empty-config"),
            "\"protectedDomains\": [\"" + getTargetHost() + "\"]"));
        ApproovService.reset();
        ApproovService.initialize(context, "", "reinit-empty-config");

        assertTrue(ApproovService.isApproovServiceEnabled());

        OkHttpClient client = ApproovService.getOkHttpClient();
        Request request = new Request.Builder()
            .url(getTargetURL())
            .build();

        try (Response response = client.newCall(request).execute()) {
            assertTrue(response.isSuccessful());
            JSONObject reply = new JSONObject(response.body().string());
            assertNull(getHeader(reply, "Approov-Token"));
            assertNull(getHeader(reply, "Approov-TraceID"));
        }
    }

    /**
     * Empty Configuration then Valid Configuration
     *
     * Initializing first with an empty config should allow a later valid config to
     * enable Approov protection at runtime.
     */
    @Test
    public void testInitializeWithEmptyConfigCanLaterEnableApproov() throws Exception {
        AttesterProxyController.reset();
        AttesterProxyController.loadScenarioJson(scenarioJson(uniqueCaseName("empty-then-valid"),
            "\"protectedDomains\": [\"" + getTargetHost() + "\"]"));
        ApproovService.reset();
        ApproovService.initialize(context, "", "reinit-empty-config");

        OkHttpClient plainClient = ApproovService.getOkHttpClient();
        try (Response response = plainClient.newCall(new Request.Builder().url(getTargetURL()).build()).execute()) {
            assertTrue(response.isSuccessful());
            JSONObject reply = new JSONObject(response.body().string());
            assertNull(getHeader(reply, "Approov-Token"));
            assertNull(getHeader(reply, "Approov-TraceID"));
        }

        assertTrue(ApproovService.isApproovServiceEnabled());
        assertFalse(ApproovService.isApproovProtectionEnabled());

        ApproovService.initialize(context, validInitialConfig);

        assertTrue(ApproovService.isApproovServiceEnabled());
        assertTrue(ApproovService.isApproovProtectionEnabled());

        OkHttpClient protectedClient = ApproovService.getOkHttpClient();
        try (Response response = protectedClient.newCall(new Request.Builder().url(getTargetURL()).build()).execute()) {
            assertTrue(response.isSuccessful());
            JSONObject reply = new JSONObject(response.body().string());
            assertNotNull(getHeader(reply, "Approov-Token"));
        }
    }

    // ==================================================================================
    // SECTION 2: Request Processing & Token Behaviors
    // ==================================================================================

    /**
     * Precheck Evaluation
     *
     * A call to precheck() should trigger a secure string fetch and evaluate
     * UNKNOWN_KEY as a success path.
     */
    @Test
    public void testPrecheckTreatsUnknownKeyAsSuccess() throws ApproovException {
        ApproovService.precheck();
    }

    /**
     * Supporting test
     *
     * Verifies the Mini SDK returns a stable device ID for the test environment.
     */
    @Test
    public void testGetDeviceIDReturnsMiniSDKDeviceID() throws ApproovException {
        assertEquals("daIvmEWBA2gvZny7a/RC/w==", ApproovService.getDeviceID());
    }

    /**
     * Protected Request Processing / Token Binding Hash
     *
     * A protected request is processed and modified by the service layer.
     * The token's `pay` claim should contain the SHA256 hash of the binding header value.
     * Also tests header and query parameter substitution.
     */
    @Test
    public void testUpdateRequestAddsTokenTraceBindingHashAndSubstitutions() throws Exception {
        String targetHost = getTargetHost();
        reinitializeService(scenarioJson(uniqueCaseName("substitutions"),
            "\"protectedDomains\": [\"" + targetHost + "\"]," +
            "\"initialSecureStrings\": {" +
            "  \"header-key\": \"header-secret\"," +
            "  \"query-key\": \"query-secret\"," +
            "  \"multiple-1\": \"secret-1\"," +
            "  \"multiple-2\": \"secret-2\"" +
            "}"
        ));

        ApproovService.setBindingHeader("Authorization");
        ApproovService.addSubstitutionHeader("Api-Key", null);
        ApproovService.addSubstitutionHeader("X-Multi-1", "pref-");
        ApproovService.addSubstitutionHeader("X-Multi-2", null);
        ApproovService.addSubstitutionQueryParam("api_key");
        ApproovService.addSubstitutionQueryParam("p2");

        OkHttpClient client = ApproovService.getOkHttpClient();
        Request request = new Request.Builder()
            .url(getTargetURL() + "?api_key=query-key&p2=multiple-2")
            .header("Authorization", "Bearer oauth-token")
            .header("Api-Key", "header-key")
            .header("X-Multi-1", "pref-multiple-1")
            .header("X-Multi-2", "multiple-2")
            .build();

        try (Response response = client.newCall(request).execute()) {
            JSONObject reply = new JSONObject(response.body().string());
            
            String token = getHeader(reply, "Approov-Token");
            assertNotNull(token);
            assertNotNull(getHeader(reply, "Approov-TraceID"));
            assertEquals("header-secret", getHeader(reply, "Api-Key"));
            assertEquals("pref-secret-1", getHeader(reply, "X-Multi-1"));
            assertEquals("secret-2", getHeader(reply, "X-Multi-2"));
            
            String urlFromReply = reply.getString("url");
            assertTrue(urlFromReply.contains("api_key=query-secret"));
            assertTrue(urlFromReply.contains("p2=secret-2"));

            JSONObject payload = decodeJWTBody(token);
            assertEquals(sha256Base64("Bearer oauth-token"), payload.getString("pay"));
        }
    }

    /**
     * HTTP header names are case-insensitive, so adding a substitution header with
     * different casing replaces the existing logical entry and removal works with
     * any casing.
     */
    @Test
    public void testSubstitutionHeaderConfigurationIsCaseInsensitive() {
        ApproovService.addSubstitutionHeader("Authorization", "first-");
        ApproovService.addSubstitutionHeader("authorization", "latest-");

        Map<String, String> headers = ApproovService.getSubstitutionHeaders();
        assertEquals(1, headers.size());
        assertFalse(headers.containsKey("Authorization"));
        assertEquals("latest-", headers.get("authorization"));

        ApproovService.removeSubstitutionHeader("AUTHORIZATION");
        assertTrue(ApproovService.getSubstitutionHeaders().isEmpty());
    }

    /**
     * A header cannot be used for both token binding and secure-string
     * substitution because the token would bind the pre-substitution placeholder
     * while the backend receives the substituted value.
     */
    @Test
    public void testBindingHeaderThenSubstitutionHeaderConflictIsRejected() {
        ApproovService.setBindingHeader("Authorization");

        try {
            ApproovService.addSubstitutionHeader("authorization", "Bearer ");
            fail("Expected conflicting substitution header to be rejected");
        } catch (IllegalArgumentException e) {
            assertEquals("Header authorization cannot be used for both token binding and secure string substitution",
                e.getMessage());
        }
        assertTrue(ApproovService.getSubstitutionHeaders().isEmpty());
    }

    /**
     * Conflict validation is independent of configuration call order and header
     * casing.
     */
    @Test
    public void testSubstitutionHeaderThenBindingHeaderConflictIsRejected() {
        ApproovService.addSubstitutionHeader("Authorization", "Bearer ");

        try {
            ApproovService.setBindingHeader("authorization");
            fail("Expected conflicting binding header to be rejected");
        } catch (IllegalArgumentException e) {
            assertEquals("Header authorization cannot be used for both token binding and secure string substitution",
                e.getMessage());
        }
        assertNull(ApproovService.getBindingHeader());
        assertEquals("Bearer ", ApproovService.getSubstitutionHeaders().get("Authorization"));
    }

    /**
     * Protected Request Processing
     *
     * Verifies that a protected request receives a signed token with expected
     * standard claims (ip, did, mskid, arc, exp).
     */
    @Test
    public void testFetchTokenReturnsSignedTokenWithExpectedClaims() throws Exception {
        reinitializeServiceWithTargetHost("");
        String token = ApproovService.fetchToken(getTargetURL());
        JSONObject payload = decodeJWTBody(token);
 
        assertEquals("81.149.55.236", payload.getString("ip"));
        assertEquals("daIvmEWBA2gvZny7a/RC/w==", payload.getString("did"));
        assertEquals("j3AWy6", payload.getString("mskid"));
        assertEquals("IXPSB7TRK26LXE3M", payload.getString("arc"));
        assertTrue(payload.has("exp"));
    }

    /**
     * Missing Artifacts Fallback
     *
     * When the Approov service is unavailable (NO_APPROOV_SERVICE), the request
     * proceeds with the token header carrying an empty token, and with the trace ID
     * the SDK supplies with the failure. With setUseApproovStatusIfNoToken(true) the
     * token header carries the status name instead.
     */
    @Test
    public void testUpdateRequestNoApproovServiceProceedsWithEmptyTokenHeader() throws Exception {
        reinitializeServiceWithTargetHost("");
        setDirective("{" +
            "  \"operation\": \"fetchApproovToken\"," +
            "  \"response\": {" +
            "    \"status\": \"NO_APPROOV_SERVICE\"," +
            "    \"traceID\": \"trace-no-service\"" +
            "  }" +
            "}");
 
        OkHttpClient client = ApproovService.getOkHttpClient();
        Request request = new Request.Builder().url(getTargetURL()).build();
        try (Response response = client.newCall(request).execute()) {
            JSONObject reply = new JSONObject(response.body().string());
            // NO_APPROOV_SERVICE proceeds with an empty token header and the trace ID of the failure
            assertEquals("", getHeader(reply, "Approov-Token"));
            assertEquals("trace-no-service", getHeader(reply, "Approov-TraceID"));
            assertNull("there is no status header", getHeader(reply, "Approov-Status"));
        }

        ApproovService.setUseApproovStatusIfNoToken(true);
        setDirective("{\"operation\": \"fetchApproovToken\", \"response\": {\"status\": \"NO_APPROOV_SERVICE\"}}");
        try (Response response = client.newCall(request).execute()) {
            JSONObject reply = new JSONObject(response.body().string());
            assertEquals("NO_APPROOV_SERVICE", getHeader(reply, "Approov-Token"));
            assertNotNull(getHeader(reply, "Approov-TraceID"));
        }
    }

    // ==================================================================================
    // ALWAYS_PROCEED and the token header on a failure
    // ==================================================================================

    /**
     * Under ALWAYS_PROCEED every token fetch failure status proceeds with the token
     * header carrying an empty token and with the trace ID the SDK supplies, and
     * with setUseApproovStatusIfNoToken(true) the token header carries the SDK
     * status name instead.
     */
    @Test
    public void testEveryTokenFetchFailureProceedsWithEmptyTokenOrStatus() throws Exception {
        String[] statuses = {"NO_NETWORK", "POOR_NETWORK", "MITM_DETECTED", "NO_APPROOV_SERVICE",
            "INTERNAL_ERROR", "REJECTED", "BAD_URL", "NO_NETWORK_PERMISSION", "MISSING_LIB_DEPENDENCY", "DISABLED"};
        for (boolean useStatus : new boolean[] {false, true}) {
            for (String status : statuses) {
                reinitializeServiceWithTargetHost("");
                setDirective("{" +
                    "  \"operation\": \"fetchApproovToken\"," +
                    "  \"response\": {" +
                    "    \"status\": \"" + status + "\"" +
                    "  }" +
                    "}");
                // under ALWAYS_PROCEED every status proceeds
                ApproovService.setServiceMutator(ApproovServiceMutator.ALWAYS_PROCEED);
                ApproovService.setUseApproovStatusIfNoToken(useStatus);

                OkHttpClient client = ApproovService.getOkHttpClient();
                Request request = new Request.Builder().url(getTargetURL()).get().build();
                try (Response response = client.newCall(request).execute()) {
                    assertEquals(status, 200, response.code());
                    JSONObject reply = new JSONObject(response.body().string());
                    assertEquals(status + " " + useStatus, useStatus ? status : "", getHeader(reply, "Approov-Token"));
                    assertNotNull(status, getHeader(reply, "Approov-TraceID"));
                    assertNull(status, getHeader(reply, "Approov-Status"));
                }
            }
        }
        // ...while UNKNOWN_URL and UNPROTECTED_URL still send no Approov headers at all, with the flag too
        for (String status : new String[] {"UNKNOWN_URL", "UNPROTECTED_URL"}) {
            JSONObject reply = sendWithTokenStatus(status);
            assertNull(status, getHeader(reply, "Approov-Token"));
            assertNull(status, getHeader(reply, "Approov-TraceID"));
        }
    }

    /**
     * A successful request carries the token, with any prefix, and the trace ID,
     * replacing any token header value the app set itself; with
     * setUseApproovStatusIfNoToken(true) it still carries the token, never the
     * status.
     */
    @Test
    public void testSuccessfulRequestCarriesTokenAndTraceID() throws Exception {
        reinitializeServiceWithTargetHost("");
        OkHttpClient client = ApproovService.getOkHttpClient();
        Request request = new Request.Builder().url(getTargetURL())
            .header("Approov-Token", "forged").get().build();
        for (boolean useStatus : new boolean[] {false, true}) {
            ApproovService.setUseApproovStatusIfNoToken(useStatus);
            try (Response response = client.newCall(request).execute()) {
                JSONObject reply = new JSONObject(response.body().string());
                assertSuccessToken(String.valueOf(useStatus), reply);
                assertNotEquals("forged", getHeader(reply, "Approov-Token"));
                assertNotNull(getHeader(reply, "Approov-TraceID"));
            }
        }

        // no Approov headers at all for a host not added to Approov (UNKNOWN_URL)
        Request unprotected = new Request.Builder().url(getNotInApproovURL()).get().build();
        try (Response response = client.newCall(unprotected).execute()) {
            JSONObject reply = new JSONObject(response.body().string());
            assertNull(getHeader(reply, "Approov-Token"));
            assertNull(getHeader(reply, "Approov-TraceID"));
        }
    }

    /**
     * setUseApproovStatusIfNoToken is off by default; turned on, a failed fetch
     * puts the status after the configured prefix in the configured token header,
     * and turned off again the header carries only the prefix (an empty token).
     */
    @Test
    public void testUseApproovStatusIfNoTokenOnAndOff() throws Exception {
        reinitializeServiceWithTargetHost("");
        ApproovService.setServiceMutator(ApproovServiceMutator.ALWAYS_PROCEED);
        assertFalse(ApproovService.getUseApproovStatusIfNoToken());
        ApproovService.setTokenHeader("X-Approov", "Bearer ");
        OkHttpClient client = ApproovService.getOkHttpClient();
        Request request = new Request.Builder().url(getTargetURL()).get().build();

        // on
        ApproovService.setUseApproovStatusIfNoToken(true);
        assertTrue(ApproovService.getUseApproovStatusIfNoToken());
        setDirective("{\"operation\": \"fetchApproovToken\", \"response\": {\"status\": \"NO_NETWORK\"}}");
        try (Response response = client.newCall(request).execute()) {
            JSONObject reply = new JSONObject(response.body().string());
            assertNull(getHeader(reply, "Approov-Token"));
            assertEquals("Bearer NO_NETWORK", getHeader(reply, "X-Approov"));
        }

        // off: the prefix and an empty token (the trailing space of the prefix is not kept on the wire)
        ApproovService.setUseApproovStatusIfNoToken(false);
        assertFalse(ApproovService.getUseApproovStatusIfNoToken());
        setDirective("{\"operation\": \"fetchApproovToken\", \"response\": {\"status\": \"NO_NETWORK\"}}");
        try (Response response = client.newCall(request).execute()) {
            assertEquals(200, response.code());
            JSONObject reply = new JSONObject(response.body().string());
            assertNull(getHeader(reply, "Approov-Token"));
            assertEquals("Bearer", getHeader(reply, "X-Approov"));
            assertNotNull(getHeader(reply, "Approov-TraceID"));
        }
    }

    /**
     * A secure string failure after a successful token fetch
     * proceeds instead of throwing. The header carries the secure string fetch
     * status in place of its placeholder, the token is still
     * sent: the token header reports the token fetch, never the secure string status.
     */
    @Test
    public void testSecureStringHeaderRejectionProceedsWithStatus() throws Exception {
        String targetHost = getTargetHost();
        reinitializeService(scenarioJson(uniqueCaseName("subst-rejected"),
            "\"protectedDomains\": [\"" + targetHost + "\"]," +
            "\"initialSecureStrings\": {\"header-key\": \"header-secret\"}"
        ));
        ApproovService.setServiceMutator(ApproovServiceMutator.ALWAYS_PROCEED);
        ApproovService.addSubstitutionHeader("Api-Key", null);
        setDirective("{\"operation\": \"fetchSecureString\", \"response\": {\"status\": \"REJECTED\"}}");

        OkHttpClient client = ApproovService.getOkHttpClient();
        Request request = new Request.Builder().url(getTargetURL())
            .header("Api-Key", "header-key").get().build();
        try (Response response = client.newCall(request).execute()) {
            assertEquals(200, response.code());
            JSONObject reply = new JSONObject(response.body().string());
            String token = getHeader(reply, "Approov-Token");
            assertNotNull(token);
            assertFalse(token.isEmpty());
            assertEquals("REJECTED", getHeader(reply, "Api-Key"));
            assertSuccessToken(reply);
        }
    }

    /**
     * A secure string query parameter failure proceeds with the fetch
     * status in place of the placeholder, and an unknown key
     * is treated the same way.
     */
    @Test
    public void testSecureStringQueryFailureProceedsWithStatus() throws Exception {
        String targetHost = getTargetHost();
        reinitializeService(scenarioJson(uniqueCaseName("query-failed"),
            "\"protectedDomains\": [\"" + targetHost + "\"]," +
            "\"initialSecureStrings\": {\"query-key\": \"query-secret\"}"
        ));
        ApproovService.setServiceMutator(ApproovServiceMutator.ALWAYS_PROCEED);
        ApproovService.addSubstitutionQueryParam("api_key");
        OkHttpClient client = ApproovService.getOkHttpClient();

        setDirective("{\"operation\": \"fetchSecureString\", \"response\": {\"status\": \"NO_APPROOV_SERVICE\"}}");
        Request request = new Request.Builder().url(getTargetURL() + "?api_key=query-key").get().build();
        try (Response response = client.newCall(request).execute()) {
            assertEquals(200, response.code());
            JSONObject reply = new JSONObject(response.body().string());
            assertTrue(reply.getString("url"), reply.getString("url").contains("api_key=NO_APPROOV_SERVICE"));
            assertSuccessToken(reply);
            String token = getHeader(reply, "Approov-Token");
            assertNotNull(token);
            assertFalse(token.isEmpty());
        }

        Request unknown = new Request.Builder().url(getTargetURL() + "?api_key=not-a-secure-string").get().build();
        try (Response response = client.newCall(unknown).execute()) {
            assertEquals(200, response.code());
            JSONObject reply = new JSONObject(response.body().string());
            assertTrue(reply.getString("url"), reply.getString("url").contains("api_key=UNKNOWN_KEY"));
            assertSuccessToken(reply);
        }
    }

    /**
     * A custom mutator may decide to substitute for a non-SUCCESS secure
     * string result, which carries no secure string. A substitute decision with no
     * value is treated like a decision not to substitute: the fetch status follows
     * the prefix (never the literal "null") and the request proceeds.
     */
    @Test
    public void testHeaderSubstituteDecisionWithNoValueCarriesStatus() throws Exception {
        String targetHost = getTargetHost();
        reinitializeService(scenarioJson(uniqueCaseName("subst-header-no-value"),
            "\"protectedDomains\": [\"" + targetHost + "\"]," +
            "\"initialSecureStrings\": {\"header-key\": \"header-secret\"}"
        ));
        AtomicInteger decisions = new AtomicInteger();
        ApproovService.setServiceMutator(new ApproovServiceMutator() {
            @Override
            public boolean handleInterceptorHeaderSubstitutionResult(Approov.TokenFetchResult approovResults,
                    String header) {
                assertEquals(Approov.TokenFetchStatus.REJECTED, approovResults.getStatus());
                assertNull(approovResults.getSecureString());
                decisions.incrementAndGet();
                return true;
            }
        });
        ApproovService.addSubstitutionHeader("Api-Key", "Key ");
        setDirective("{\"operation\": \"fetchSecureString\", \"response\": {\"status\": \"REJECTED\"}}");

        OkHttpClient client = ApproovService.getOkHttpClient();
        Request request = new Request.Builder().url(getTargetURL())
            .header("Api-Key", "Key header-key").get().build();
        try (Response response = client.newCall(request).execute()) {
            assertEquals(200, response.code());
            JSONObject reply = new JSONObject(response.body().string());
            assertEquals("Key REJECTED", getHeader(reply, "Api-Key"));
            String token = getHeader(reply, "Approov-Token");
            assertNotNull(token);
            assertFalse(token.isEmpty());
        }
        assertEquals(1, decisions.get());
    }

    /**
     * A substitute decision for a query parameter with no secure string
     * puts the fetch status in the URL and the request proceeds, rather than
     * throwing a NullPointerException out of the interceptor.
     */
    @Test
    public void testQuerySubstituteDecisionWithNoValueCarriesStatus() throws Exception {
        String targetHost = getTargetHost();
        reinitializeService(scenarioJson(uniqueCaseName("subst-query-no-value"),
            "\"protectedDomains\": [\"" + targetHost + "\"]," +
            "\"initialSecureStrings\": {\"query-key\": \"query-secret\"}"
        ));
        AtomicInteger decisions = new AtomicInteger();
        ApproovService.setServiceMutator(new ApproovServiceMutator() {
            @Override
            public boolean handleInterceptorQueryParamSubstitutionResult(Approov.TokenFetchResult approovResults,
                    String queryKey) {
                assertEquals(Approov.TokenFetchStatus.REJECTED, approovResults.getStatus());
                assertNull(approovResults.getSecureString());
                decisions.incrementAndGet();
                return true;
            }
        });
        ApproovService.addSubstitutionQueryParam("api_key");
        setDirective("{\"operation\": \"fetchSecureString\", \"response\": {\"status\": \"REJECTED\"}}");

        OkHttpClient client = ApproovService.getOkHttpClient();
        Request request = new Request.Builder().url(getTargetURL() + "?api_key=query-key").get().build();
        try (Response response = client.newCall(request).execute()) {
            assertEquals(200, response.code());
            JSONObject reply = new JSONObject(response.body().string());
            assertTrue(reply.getString("url"), reply.getString("url").contains("api_key=REJECTED"));
            assertFalse(reply.getString("url").contains("api_key=null"));
            String token = getHeader(reply, "Approov-Token");
            assertNotNull(token);
            assertFalse(token.isEmpty());
        }
        assertEquals(1, decisions.get());
    }

    /**
     * A request proceeding on a failed token fetch carries the token header with
     * an empty token (or the status with setUseApproovStatusIfNoToken) and the
     * trace ID, and with message signing installed it is signed, as in 3.5.8:
     * signing is the processed request hook of the ApproovDefaultMessageSigning
     * mutator, which signs every request carrying the token header, whatever its
     * value, so the signature covers the empty token (or the status).
     */
    @Test
    public void testFailedTokenFetchCarriesEmptyTokenHeaderAndIsSigned() throws Exception {
        reinitializeServiceWithTargetHost("");
        // the signing mutator's default decisions let NO_APPROOV_SERVICE proceed
        TestSigning.install();
        setDirective("{\"operation\": \"fetchApproovToken\", \"response\": {\"status\": \"NO_APPROOV_SERVICE\"}}");
        OkHttpClient client = ApproovService.getOkHttpClient();
        Request request = new Request.Builder().url(getTargetURL()).get().build();
        try (Response response = client.newCall(request).execute()) {
            JSONObject reply = new JSONObject(response.body().string());
            assertEquals("", getHeader(reply, "Approov-Token"));
            assertNotNull(getHeader(reply, "Approov-TraceID"));
            assertInstallSignatureOnly(reply);
            assertTrue("the empty token header is covered: " + getHeader(reply, "Signature-Input"),
                getHeader(reply, "Signature-Input").contains("\"approov-token\""));
        }
        ApproovService.setUseApproovStatusIfNoToken(true);
        setDirective("{\"operation\": \"fetchApproovToken\", \"response\": {\"status\": \"NO_APPROOV_SERVICE\"}}");
        try (Response response = client.newCall(request).execute()) {
            JSONObject reply = new JSONObject(response.body().string());
            assertEquals("NO_APPROOV_SERVICE", getHeader(reply, "Approov-Token"));
            assertInstallSignatureOnly(reply);
            assertTrue("the status in the token header is covered: " + getHeader(reply, "Signature-Input"),
                getHeader(reply, "Signature-Input").contains("\"approov-token\""));
        }
    }

    /**
     * getLastARC reports the ARC of the most recent fetch this layer made and
     * performs no fetch of its own. After a rejected interceptor request it returns
     * that rejection's ARC; a following successful request replaces it.
     */
    @Test
    public void testGetLastARCReportsMostRecentFetchWithoutFetching() throws Exception {
        reinitializeServiceWithTargetHost("");
        ApproovService.setServiceMutator(ApproovServiceMutator.ALWAYS_PROCEED);
        assertEquals("", ApproovService.getLastARC());
        setDirective("{\"operation\": \"fetchApproovToken\", \"response\": {\"status\": \"REJECTED\", \"arc\": \"REJECTARC1\"}}");
        OkHttpClient client = ApproovService.getOkHttpClient();
        Request request = new Request.Builder().url(getTargetURL()).get().build();
        try (Response response = client.newCall(request).execute()) {
            JSONObject reply = new JSONObject(response.body().string());
            assertEquals("the rejection proceeds with an empty token", "", getHeader(reply, "Approov-Token"));
        }
        // no directive is queued now: a fetch inside getLastARC would return the
        // scenario's default ARC instead of the rejection's
        assertEquals("REJECTARC1", ApproovService.getLastARC());
        assertEquals("REJECTARC1", ApproovService.getLastARC());
        try (Response response = client.newCall(request).execute()) {
            assertEquals(200, response.code());
        }
        assertNotEquals("REJECTARC1", ApproovService.getLastARC());
    }

    /**
     * Common service layer interface names: setTokenHeader / setTraceIDHeader
     * are the names, and the okhttp-specific setApproovHeader /
     * setApproovTraceIDHeader remain as deprecated aliases.
     */
    @Test
    @SuppressWarnings("deprecation")
    public void testHeaderSetterNamesAndDeprecatedAliases() throws Exception {
        reinitializeServiceWithTargetHost("");
        assertEquals("Approov-Token", ApproovService.getTokenHeader());
        assertEquals("", ApproovService.getTokenPrefix());
        assertEquals("Approov-TraceID", ApproovService.getTraceIDHeader());

        ApproovService.setTokenHeader("Authorization", "Bearer ");
        ApproovService.setTraceIDHeader("X-Trace");
        assertEquals("Authorization", ApproovService.getApproovTokenHeader());
        assertEquals("Bearer ", ApproovService.getApproovTokenPrefix());
        assertEquals("X-Trace", ApproovService.getApproovTraceIDHeader());

        ApproovService.setApproovHeader("X-Token", null);
        ApproovService.setApproovTraceIDHeader(null);
        assertEquals("X-Token", ApproovService.getTokenHeader());
        assertEquals("", ApproovService.getTokenPrefix());
        assertNull(ApproovService.getTraceIDHeader());
    }

    /**
     * Exclusion URL Matching
     *
     * An excluded URL (using regular expression checks) should not be processed
     * by the service layer.
     */
    @Test
    public void testUpdateRequestCanIgnoreExcludedURL() throws Exception {
        reinitializeServiceWithTargetHost("");
        ApproovService.addExclusionURLRegex("^.*excluded.*$");
 
        OkHttpClient client = ApproovService.getOkHttpClient();
        Request request = new Request.Builder().url(getTargetURL() + "/excluded").build();
        try (Response response = client.newCall(request).execute()) {
            JSONObject reply = new JSONObject(response.body().string());
            String token = getHeader(reply, "Approov-Token");
            assertNull("Expected null Approov-Token for excluded URL, but got: " + token, token);
        }
    }

    /**
     * Token Fallback Status (error status mapping)
     *
     * Each SDK fetch status is correctly mapped to the expected exception type.
     * NO_NETWORK → ApproovNetworkException
     */
    @Test
    public void testFetchTokenThrowsNetworkingErrorForNoNetwork() throws Exception {
        reinitializeServiceWithTargetHost("");
        setDirective("{" +
            "  \"operation\": \"fetchApproovToken\"," +
            "  \"response\": {" +
            "    \"status\": \"NO_NETWORK\"" +
            "  }" +
            "}");
 
        try {
            String token = ApproovService.fetchToken(getTargetURL());
            fail("Expected ApproovNetworkException, but got token: " + token);
        } catch (ApproovNetworkException e) {
            assertTrue(e.getMessage().contains("fetchToken: NO_NETWORK"));
        } catch (ApproovException e) {
            fail("Expected ApproovNetworkException, but got " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Token Fallback Status (error status mapping)
     *
     * REJECTED → ApproovFetchStatusException
     */
    @Test
    public void testFetchTokenThrowsFetchStatusExceptionForRejected() throws Exception {
        reinitializeServiceWithTargetHost("");
        setDirective("{" +
            "  \"operation\": \"fetchApproovToken\"," +
            "  \"response\": {" +
            "    \"status\": \"REJECTED\"" +
            "  }" +
            "}");

        try {
            ApproovService.fetchToken(getTargetURL());
            fail("Expected ApproovFetchStatusException");
        } catch (ApproovFetchStatusException e) {
            String msg = e.getMessage();
            assertTrue("Message did not contain REJECTED: " + msg, msg.contains("REJECTED"));
        }
    }

    /**
     * Token Fallback Status (error status mapping)
     *
     * BAD_URL → ApproovFetchStatusException
     */
    @Test
    public void testFetchTokenThrowsFetchStatusExceptionForBadURL() throws Exception {
        reinitializeServiceWithTargetHost("");
        setDirective("{" +
            "  \"operation\": \"fetchApproovToken\"," +
            "  \"response\": {" +
            "    \"status\": \"BAD_URL\"" +
            "  }" +
            "}");

        try {
            ApproovService.fetchToken(getTargetURL());
            fail("Expected ApproovFetchStatusException");
        } catch (ApproovFetchStatusException e) {
            assertTrue(e.getMessage().contains("fetchToken: BAD_URL"));
        }
    }

    /**
     * Token Fallback Status (error status mapping)
     *
     * POOR_NETWORK → ApproovNetworkException
     */
    @Test
    public void testFetchTokenThrowsNetworkExceptionForPoorNetwork() throws Exception {
        reinitializeServiceWithTargetHost("");
        setDirective("{" +
            "  \"operation\": \"fetchApproovToken\"," +
            "  \"response\": {" +
            "    \"status\": \"POOR_NETWORK\"" +
            "  }" +
            "}");

        try {
            ApproovService.fetchToken(getTargetURL());
            fail("Expected ApproovNetworkException");
        } catch (ApproovNetworkException e) {
            assertTrue(e.getMessage().contains("fetchToken: POOR_NETWORK"));
        }
    }

    /**
     * MITM_DETECTED (a fundamental TLS trust failure to the Approov cloud) is a
     * network failure for the direct methods: fetchToken,
     * fetchSecureString, fetchCustomJWT and precheck throw ApproovNetworkException.
     */
    @Test
    @SuppressWarnings("deprecation")
    public void testDirectMethodsClassifyMitmDetectedAsNetworkFailure() throws Exception {
        reinitializeServiceWithTargetHost("");
        String[][] ops = {
            {"fetchApproovToken", "fetchToken"},
            {"fetchSecureString", "fetchSecureString"},
            {"fetchCustomJWT", "fetchCustomJWT"},
            {"fetchSecureString", "precheck"}};
        for (String[] op : ops) {
            setDirective("{\"operation\": \"" + op[0] + "\", \"response\": {\"status\": \"MITM_DETECTED\"}}");
            try {
                switch (op[1]) {
                    case "fetchToken": ApproovService.fetchToken(getTargetURL()); break;
                    case "fetchSecureString": ApproovService.fetchSecureString("header-key", null); break;
                    case "fetchCustomJWT": ApproovService.fetchCustomJWT("{\"role\":\"tester\"}"); break;
                    default: ApproovService.precheck();
                }
                fail(op[1] + ": expected ApproovNetworkException");
            } catch (ApproovNetworkException e) {
                assertTrue(op[1] + ": " + e.getMessage(), e.getMessage().contains("MITM_DETECTED"));
                assertEquals(Approov.TokenFetchStatus.MITM_DETECTED, e.getTokenFetchStatus());
            }
        }
    }

    /**
     * On the request path MITM_DETECTED proceeds like
     * NO_NETWORK under the ALWAYS_PROCEED decision, with an empty token header (the SDK's
     * status with setUseApproovStatusIfNoToken), and with message signing installed it
     * is signed over that token header. An interception on the
     * attestation path no longer blocks the request.
     */
    @Test
    public void testMitmDetectedProceedsOnTheWire() throws Exception {
        reinitializeServiceWithTargetHost("");
        // message signing subclassed to make the ALWAYS_PROCEED token decision
        TestSigning.installAlwaysProceed();
        setDirective("{\"operation\": \"fetchApproovToken\", \"response\": {\"status\": \"MITM_DETECTED\"}}");
        OkHttpClient client = ApproovService.getOkHttpClient();
        try (Response response = client.newCall(new Request.Builder().url(getTargetURL()).get().build()).execute()) {
            assertEquals(200, response.code());
            JSONObject reply = new JSONObject(response.body().string());
            assertEquals("", getHeader(reply, "Approov-Token"));
            assertInstallSignatureOnly(reply);
            assertTrue(getHeader(reply, "Signature-Input").contains("\"approov-token\""));
        }
        ApproovService.setUseApproovStatusIfNoToken(true);
        // the mini SDK's token fetch interception directive now yields the same status
        setDirective("{\"operation\": \"fetchApproovToken\", \"response\": {\"attesterChannelMitm\": true}}");
        try (Response response = client.newCall(new Request.Builder().url(getTargetURL()).get().build()).execute()) {
            assertEquals(200, response.code());
            JSONObject reply = new JSONObject(response.body().string());
            assertEquals("MITM_DETECTED", getHeader(reply, "Approov-Token"));
            assertInstallSignatureOnly(reply);
            assertTrue(getHeader(reply, "Signature-Input").contains("\"approov-token\""));
        }
    }

    /**
     * Token Fallback Status (error status mapping)
     *
     * INTERNAL_ERROR → ApproovFetchStatusException
     */
    @Test
    public void testFetchTokenThrowsFetchStatusExceptionForInternalError() throws Exception {
        reinitializeServiceWithTargetHost("");
        setDirective("{" +
            "  \"operation\": \"fetchApproovToken\"," +
            "  \"response\": {" +
            "    \"status\": \"INTERNAL_ERROR\"" +
            "  }" +
            "}");

        try {
            ApproovService.fetchToken(getTargetURL());
            fail("Expected ApproovFetchStatusException");
        } catch (ApproovFetchStatusException e) {
            assertTrue(e.getMessage().contains("fetchToken: INTERNAL_ERROR"));
        }
    }

    /**
     * Token Fallback Status (error status mapping)
     *
     * UNKNOWN_URL → ApproovFetchStatusException
     */
    @Test
    public void testFetchTokenThrowsFetchStatusExceptionForUnknownURL() throws Exception {
        reinitializeServiceWithTargetHost("");
        setDirective("{" +
            "  \"operation\": \"fetchApproovToken\"," +
            "  \"response\": {" +
            "    \"status\": \"UNKNOWN_URL\"" +
            "  }" +
            "}");

        try {
            ApproovService.fetchToken(getTargetURL());
            fail("Expected ApproovFetchStatusException");
        } catch (ApproovFetchStatusException e) {
            assertTrue(e.getMessage().contains("fetchToken: UNKNOWN_URL"));
        }
    }

    // ==================================================================================
    // SECTION 3: Service Mutators & Decision Overrides
    // ==================================================================================

    /**
     * Service Mutator Override
     *
     * A custom ApproovServiceMutator can still decide that a request carries no
     * Approov headers at all for a given status: returning false from
     * handleInterceptorFetchTokenResult sends the request untouched, with neither
     * a token header nor an error header.
     */
    @Test
    public void testServiceMutatorCanSuppressAllApproovHeaders() throws Exception {
        reinitializeServiceWithTargetHost("");
        
        setDirective("{" +
            "  \"operation\": \"fetchApproovToken\"," +
            "  \"response\": {" +
            "    \"status\": \"NO_NETWORK\"" +
            "  }" +
            "}");
            
        ApproovService.setServiceMutator(new ApproovServiceMutator() {
            @Override
            public boolean handleInterceptorFetchTokenResult(Approov.TokenFetchResult approovResults, String url) throws ApproovException {
                return false;
            }
        });

        OkHttpClient client = ApproovService.getOkHttpClient();
        Request request = new Request.Builder().url(getTargetURL()).get().build();
        
        try (Response response = client.newCall(request).execute()) {
            assertEquals(200, response.code());
            JSONObject reply = new JSONObject(response.body().string());
            assertNull(getHeader(reply, "Approov-Token"));
            assertNull(getHeader(reply, "Approov-TraceID"));
        }
    }

    /**
     * Service Mutator Override / opt-in abort
     *
     * A custom mutator may opt in to aborting a request for an outcome the app does
     * not accept by throwing. The exception is a standard network stack exception
     * an ApproovException, as the hooks declare (3.5.8). The layer wraps it as an
     * ApproovException naming the hook with the app's exception as its cause, and
     * it reaches the caller with no request sent. This is the integrator's explicit
     * decision, not a default of the layer.
     */
    @Test
    public void testServiceMutatorCanAbortWithAnApproovException() throws Exception {
        reinitializeServiceWithTargetHost("");
        setDirective("{" +
            "  \"operation\": \"fetchApproovToken\"," +
            "  \"response\": {" +
            "    \"status\": \"NO_NETWORK\"" +
            "  }" +
            "}");
        List<ApproovException> thrown = new ArrayList<>();
        ApproovService.setServiceMutator(new ApproovServiceMutator() {
            @Override
            public boolean handleInterceptorFetchTokenResult(Approov.TokenFetchResult approovResults, String url)
                    throws ApproovException {
                if (approovResults.getStatus() != Approov.TokenFetchStatus.SUCCESS) {
                    ApproovException e = new ApproovException("attestation unavailable: " + approovResults.getStatus());
                    thrown.add(e);
                    throw e;
                }
                return true;
            }
        });

        // count network attempts so that the abort is shown to send nothing
        AtomicInteger attempts = new AtomicInteger();
        ApproovService.setOkHttpClientBuilder(new OkHttpClient.Builder().addNetworkInterceptor(chain -> {
            attempts.incrementAndGet();
            return chain.proceed(chain.request());
        }));
        OkHttpClient client = ApproovService.getOkHttpClient();
        Request request = new Request.Builder().url(getTargetURL()).get().build();
        try (Response response = client.newCall(request).execute()) {
            fail("Expected ApproovException");
        } catch (ApproovException e) {
            assertSame("the layer's hook failure: " + e, ApproovException.class, e.getClass());
            assertTrue(e.getMessage(), e.getMessage().contains("handleInterceptorFetchTokenResult"));
            assertEquals(1, thrown.size());
            assertSame("the app's exception is the cause", thrown.get(0), e.getCause());
            assertTrue(e.getCause().getMessage().contains("attestation unavailable: NO_NETWORK"));
        }
        assertEquals(0, attempts.get());
    }

    // ==================================================================================
    // DEFAULT is CLOSE_FAILURE, ALWAYS_PROCEED opt-in
    // ==================================================================================

    /**
     * The out-of-the-box mutator, and the one setServiceMutator(null)
     * reinstates, is CLOSE_FAILURE in 3.7.0: a REJECTED token fetch aborts with the
     * fetch status exception and sends nothing, a network status aborts with the
     * network exception, and NO_APPROOV_SERVICE proceeds with an empty token
     * header. A custom mutator that overrides no decision gets the
     * same decisions, since they are the interface defaults.
     */
    @Test
    public void testDefaultMutatorIsCloseFailure() throws Exception {
        reinitializeServiceWithTargetHost("");
        assertSame(ApproovServiceMutator.CLOSE_FAILURE, ApproovServiceMutator.DEFAULT);
        assertNotSame(ApproovServiceMutator.CLOSE_FAILURE, ApproovServiceMutator.ALWAYS_PROCEED);
        assertSame(ApproovServiceMutator.DEFAULT, ApproovService.getServiceMutator());
        AtomicInteger attempts = countNetworkAttempts();

        assertTokenFetchAborts("REJECTED", ApproovFetchStatusException.class, attempts);
        assertTokenFetchAborts("NO_NETWORK", ApproovNetworkException.class, attempts);
        JSONObject reply = sendWithTokenStatus("NO_APPROOV_SERVICE");
        assertEquals("", getHeader(reply, "Approov-Token"));

        ApproovService.setServiceMutator(ApproovServiceMutator.ALWAYS_PROCEED);
        ApproovService.setServiceMutator(null);
        assertSame(ApproovServiceMutator.DEFAULT, ApproovService.getServiceMutator());
        assertTokenFetchAborts("REJECTED", ApproovFetchStatusException.class, attempts);

        ApproovService.setServiceMutator(new ApproovServiceMutator() {
            @Override
            public Request handleInterceptorProcessedRequest(Request request, ApproovRequestMutations changes) {
                return request;
            }
        });
        assertTokenFetchAborts("REJECTED", ApproovFetchStatusException.class, attempts);
        assertTokenFetchAborts("MITM_DETECTED", ApproovNetworkException.class, attempts);
    }

    /**
     * Every CLOSE_FAILURE token fetch decision, the 3.5.x ones.
     * SUCCESS adds the token, NO_APPROOV_SERVICE an empty token header, UNKNOWN_URL and
     * UNPROTECTED_URL send no Approov headers, the network statuses throw the
     * network exception and every other status the fetch status exception, with no
     * request reaching the wire. setProceedOnNetworkFail is removed in 3.7.0:
     * the network statuses always throw under CLOSE_FAILURE.
     */
    @Test
    public void testCloseFailureTokenFetchDecisions() throws Exception {
        reinitializeServiceWithTargetHost("");
        ApproovService.setServiceMutator(ApproovServiceMutator.CLOSE_FAILURE);
        AtomicInteger attempts = countNetworkAttempts();

        JSONObject reply = sendWithTokenStatus(null);
        assertSuccessToken(reply);
        reply = sendWithTokenStatus("NO_APPROOV_SERVICE");
        assertEquals("", getHeader(reply, "Approov-Token"));
        for (String status : new String[] {"UNKNOWN_URL", "UNPROTECTED_URL"}) {
            reply = sendWithTokenStatus(status);
            assertNull(status, getHeader(reply, "Approov-Token"));
            assertNull(status, getHeader(reply, "Approov-TraceID"));
        }
        String[] network = {"NO_NETWORK", "POOR_NETWORK", "MITM_DETECTED"};
        for (String status : network)
            assertTokenFetchAborts(status, ApproovNetworkException.class, attempts);
        String[] others = {"REJECTED", "INTERNAL_ERROR", "BAD_URL", "NO_NETWORK_PERMISSION",
            "MISSING_LIB_DEPENDENCY", "DISABLED"};
        for (String status : others)
            assertTokenFetchAborts(status, ApproovFetchStatusException.class, attempts);
    }

    /**
     * Under CLOSE_FAILURE with setUseApproovStatusIfNoToken(true) the network
     * failures (NO_NETWORK, POOR_NETWORK, MITM_DETECTED) proceed with the status in
     * the token header instead of throwing, as does NO_APPROOV_SERVICE, while every
     * other failure still aborts with the fetch status exception.
     */
    @Test
    public void testCloseFailureWithStatusInTokenHeaderProceedsOnNetworkFailures() throws Exception {
        reinitializeServiceWithTargetHost("");
        ApproovService.setServiceMutator(ApproovServiceMutator.CLOSE_FAILURE);
        ApproovService.setUseApproovStatusIfNoToken(true);
        AtomicInteger attempts = countNetworkAttempts();

        for (String status : new String[] {"NO_NETWORK", "POOR_NETWORK", "MITM_DETECTED", "NO_APPROOV_SERVICE"}) {
            JSONObject reply = sendWithTokenStatus(status);
            assertEquals(status, status, getHeader(reply, "Approov-Token"));
            assertNotNull(status, getHeader(reply, "Approov-TraceID"));
        }
        for (String status : new String[] {"REJECTED", "INTERNAL_ERROR", "BAD_URL", "NO_NETWORK_PERMISSION",
                "MISSING_LIB_DEPENDENCY", "DISABLED"})
            assertTokenFetchAborts(status, ApproovFetchStatusException.class, attempts);

        // with the flag off again the network failures throw
        ApproovService.setUseApproovStatusIfNoToken(false);
        assertTokenFetchAborts("NO_NETWORK", ApproovNetworkException.class, attempts);
    }

    /**
     * Every CLOSE_FAILURE substitution decision, for headers and query parameters,
     * under CLOSE_FAILURE and under DEFAULT (setServiceMutator(null)). SUCCESS
     * substitutes; every other secure string status is written in place of the
     * placeholder and the request
     * proceeds with the token (a
     * standard mutator never aborts a request because of a secure string).
     */
    @Test
    public void testCloseFailureSubstitutionDecisions() throws Exception {
        String targetHost = getTargetHost();
        reinitializeService(scenarioJson(uniqueCaseName("close-failure-subst"),
            "\"protectedDomains\": [\"" + targetHost + "\"]," +
            "\"initialSecureStrings\": {\"the-key\": \"the-secret\"}"
        ));
        ApproovService.addSubstitutionHeader("Api-Key", null);
        ApproovService.addSubstitutionQueryParam("api_key");
        AtomicInteger attempts = countNetworkAttempts();
        OkHttpClient client = ApproovService.getOkHttpClient();
        Request header = new Request.Builder().url(getTargetURL()).header("Api-Key", "the-key").build();
        Request query = new Request.Builder().url(getTargetURL() + "?api_key=the-key").build();

        for (ApproovServiceMutator mutator : new ApproovServiceMutator[] {ApproovServiceMutator.CLOSE_FAILURE, null}) {
            ApproovService.setServiceMutator(mutator);
            assertSame(ApproovServiceMutator.CLOSE_FAILURE, ApproovService.getServiceMutator());

            JSONObject reply = send(client, header);
            assertEquals("the-secret", getHeader(reply, "Api-Key"));
            reply = send(client, query);
            assertTrue(reply.getString("url"), reply.getString("url").contains("api_key=the-secret"));

            // a value that is not a secure string key is replaced by unknown_key
            reply = send(client, new Request.Builder().url(getTargetURL()).header("Api-Key", "not-a-key").build());
            assertEquals("UNKNOWN_KEY", getHeader(reply, "Api-Key"));
            assertFalse(getHeader(reply, "Approov-Token").isEmpty());
            reply = send(client, new Request.Builder().url(getTargetURL() + "?api_key=not-a-key").build());
            assertTrue(reply.getString("url"), reply.getString("url").contains("api_key=UNKNOWN_KEY"));

            for (String status : new String[] {"UNKNOWN_KEY", "REJECTED", "NO_APPROOV_SERVICE"}) {
                String expected = status;
                setDirective("{\"operation\": \"fetchSecureString\", \"response\": {\"status\": \"" + status + "\"}}");
                reply = send(client, header);
                assertEquals(status + " in place of the placeholder", expected, getHeader(reply, "Api-Key"));
                assertFalse(status + " still carries the token", getHeader(reply, "Approov-Token").isEmpty());
                assertSuccessToken(status, reply);
                setDirective("{\"operation\": \"fetchSecureString\", \"response\": {\"status\": \"" + status + "\"}}");
                reply = send(client, query);
                assertTrue(status + ": " + reply.getString("url"), reply.getString("url").contains("api_key=" + expected));
                assertFalse(status + " still carries the token", getHeader(reply, "Approov-Token").isEmpty());
                assertSuccessToken(status, reply);
            }

            for (String status : new String[] {"NO_NETWORK", "POOR_NETWORK", "MITM_DETECTED",
                    "INTERNAL_ERROR", "BAD_URL", "DISABLED"}) {
                int before = attempts.get();
                String expected = status;
                setDirective("{\"operation\": \"fetchSecureString\", \"response\": {\"status\": \"" + status + "\"}}");
                reply = send(client, header);
                assertEquals(status + " in place of the placeholder", expected, getHeader(reply, "Api-Key"));
                assertSuccessToken(status, reply);
                setDirective("{\"operation\": \"fetchSecureString\", \"response\": {\"status\": \"" + status + "\"}}");
                reply = send(client, query);
                assertTrue(status + ": " + reply.getString("url"), reply.getString("url").contains("api_key=" + expected));
                assertEquals(status + " proceeds", before + 2, attempts.get());
            }
        }
    }

    /**
     * ALWAYS_PROCEED aborts for no status. A failed secure
     * string substitution puts its status in place of the placeholder, the request
     * goes out with the token, and the token header reports the token fetch.
     */
    @Test
    public void testAlwaysProceedNeverAbortsASubstitution() throws Exception {
        String targetHost = getTargetHost();
        reinitializeService(scenarioJson(uniqueCaseName("always-proceed-subst"),
            "\"protectedDomains\": [\"" + targetHost + "\"]," +
            "\"initialSecureStrings\": {\"the-key\": \"the-secret\"}"
        ));
        ApproovService.setServiceMutator(ApproovServiceMutator.ALWAYS_PROCEED);
        ApproovService.addSubstitutionHeader("Api-Key", null);
        ApproovService.addSubstitutionQueryParam("api_key");
        OkHttpClient client = ApproovService.getOkHttpClient();
        for (String status : new String[] {"REJECTED", "NO_NETWORK", "POOR_NETWORK", "MITM_DETECTED",
                "NO_APPROOV_SERVICE", "INTERNAL_ERROR", "UNKNOWN_KEY"}) {
            setDirective("{\"operation\": \"fetchSecureString\", \"response\": {\"status\": \"" + status + "\"}}");
            String expected = status;
            JSONObject reply = send(client, new Request.Builder().url(getTargetURL()).header("Api-Key", "the-key").build());
            assertEquals(status, expected, getHeader(reply, "Api-Key"));
            assertFalse(status, getHeader(reply, "Approov-Token").isEmpty());
            assertSuccessToken(status, reply);
            setDirective("{\"operation\": \"fetchSecureString\", \"response\": {\"status\": \"" + status + "\"}}");
            reply = send(client, new Request.Builder().url(getTargetURL() + "?api_key=the-key").build());
            assertTrue(status, reply.getString("url").contains("api_key=" + expected));
        }
    }

    // ==================================================================================
    // SECTION 4: Pinning Configuration & Scenarios
    // ==================================================================================

    /**
     * Generate Valid Pins / Generate Invalid Pins
     *
     * Verifies that when the SDK provides invalid (dummy) pins, the CertificatePinner
     * rejects the connection. This also implicitly validates that valid pins (default)
     * allow connections to succeed.
     */
    @Test
    public void testPinningIsAppliedCorrectly() throws Exception {
        reinitializeServiceWithTargetHost("");
        String targetHost = getTargetHost();
        
        // Set pinning failure directive BEFORE rebuilding pins
        AttesterProxyController.setNextPinningDirectiveJson("{\"operation\": \"getPins\", \"shouldFail\": true}");
        
        ApproovService.rebuildPins();
        
        // Verify that the CertificatePinner has the dummy pins by checking against an empty cert list
        CertificatePinner pinner = ApproovService.getCertificatePinner();
        try {
            pinner.check(targetHost, new java.util.ArrayList<java.security.cert.Certificate>());
            fail("Expected SSLPeerUnverifiedException due to pinning dummy pins");
        } catch (javax.net.ssl.SSLPeerUnverifiedException e) {
            assertTrue("Exception message should contain dummy pin: " + e.getMessage(),
                e.getMessage().contains("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="));
        }
    }

    /**
     * Accept Any Pins
     *
     * The SDK provides no specific pins for the API and suppresses the wildcard
     * fallback pins, allowing the connection to succeed without pinning validation.
     */
    @Test
    public void testPinningAcceptAny() throws Exception {
        reinitializeServiceWithTargetHost("");
        
        AttesterProxyController.setNextPinningDirectiveJson("{\"operation\": \"getPins\", \"acceptAny\": true}");
        
        OkHttpClient client = ApproovService.getOkHttpClient();
        Request request = new Request.Builder().url(getTargetURL()).get().build();
        
        try (Response response = client.newCall(request).execute()) {
            assertEquals(200, response.code());
        }
    }

    /**
     * Dynamic Pinning Updates
     *
     * When pins are updated dynamically to an invalid state via rebuildPins(),
     * subsequent requests on the same client should fail with a pinning error.
     */
    @Test
    public void testDynamicPinningUpdate() throws Exception {
        reinitializeServiceWithTargetHost("");
        OkHttpClient client = ApproovService.getOkHttpClient();
        Request request = new Request.Builder().url(getTargetURL()).build();
        
        // 1. Success case (default pins are valid)
        try (Response response = client.newCall(request).execute()) {
            assertEquals(200, response.code());
        }
        
        // 2. Update pins to invalid state
        AttesterProxyController.setNextPinningDirectiveJson("{\"operation\": \"getPins\", \"shouldFail\": true}");
        ApproovService.rebuildPins();
        
        // 3. Failure case (same client, same host, now should fail)
        try {
            client.newCall(request).execute();
            fail("Expected pinning failure after dynamic update");
        } catch (IOException e) {
            // Expected
        }
    }

    /**
     * Dynamic Pinning Updates (isForceApplyPins): the SDK can ask for the
     * current pins to be applied without a
     * configuration change. The pins are rebuilt even though the pinner already
     * holds pins (so the empty-pinner rebuild in the pinning interceptor does not
     * apply), the request is checked against the rebuilt pins, and there is no new
     * configuration to acknowledge with fetchConfig.
     */
    @Test
    public void testForceApplyPinsRebuildsPinsWithoutConfigChange() throws Exception {
        // the default case pins the target host with the managed trust roots, so the
        // pinner is not empty
        assertFalse(ApproovService.getCertificatePinner().getPins().isEmpty());
        RecordingSdkFacade sdk = RecordingSdkFacade.install();
        OkHttpClient client = ApproovService.getOkHttpClient();
        Request request = new Request.Builder().url(getTargetURL()).get().build();

        // the next pins the SDK provides are dummy pins that the target host fails
        AttesterProxyController.setNextPinningDirectiveJson("{\"operation\": \"getPins\", \"shouldFail\": true}");
        setDirective("{\"operation\": \"fetchApproovToken\", \"response\": {\"status\": \"SUCCESS\"," +
            " \"configChanged\": false, \"forceApplyPins\": true}}");
        try (Response response = client.newCall(request).execute()) {
            fail("Expected a pinning failure against the force-applied pins, got " + response.code());
        } catch (javax.net.ssl.SSLPeerUnverifiedException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="));
        }
        assertEquals(1, sdk.count("getPins"));
        assertEquals(0, sdk.count("fetchConfig"));
    }

    /**
     * Dynamic Pinning Updates: a configuration change that also sets
     * isForceApplyPins acknowledges the configuration with fetchConfig and rebuilds
     * the pins once.
     */
    @Test
    public void testConfigChangeWithForceApplyPinsRebuildsPinsOnce() throws Exception {
        RecordingSdkFacade sdk = RecordingSdkFacade.install();
        OkHttpClient client = ApproovService.getOkHttpClient();
        Request request = new Request.Builder().url(getTargetURL()).get().build();

        setDirective("{\"operation\": \"fetchApproovToken\", \"response\": {\"status\": \"SUCCESS\"," +
            " \"configChanged\": true, \"forceApplyPins\": true}}");
        try (Response response = client.newCall(request).execute()) {
            assertEquals(200, response.code());
        }
        assertEquals(1, sdk.count("getPins"));
        assertEquals(1, sdk.count("fetchConfig"));
    }

    /**
     * Dynamic Pinning Updates: with neither isConfigChanged nor isForceApplyPins
     * set, the pins already held are kept: the SDK is not asked for pins and the
     * pending dummy pins are never applied.
     */
    @Test
    public void testNoConfigChangeOrForceApplyPinsKeepsPins() throws Exception {
        assertFalse(ApproovService.getCertificatePinner().getPins().isEmpty());
        RecordingSdkFacade sdk = RecordingSdkFacade.install();
        OkHttpClient client = ApproovService.getOkHttpClient();
        Request request = new Request.Builder().url(getTargetURL()).get().build();

        AttesterProxyController.setNextPinningDirectiveJson("{\"operation\": \"getPins\", \"shouldFail\": true}");
        setDirective("{\"operation\": \"fetchApproovToken\", \"response\": {\"status\": \"SUCCESS\"," +
            " \"configChanged\": false, \"forceApplyPins\": false}}");
        try (Response response = client.newCall(request).execute()) {
            assertEquals(200, response.code());
        }
        assertEquals(0, sdk.count("getPins"));
        assertEquals(0, sdk.count("fetchConfig"));
    }

    // ==================================================================================
    // SECTION 5: Message Signing
    // ==================================================================================

    /**
     * Guard the out-of-the-box signing state
     * so that a change of default cannot ship unreviewed. In 3.7.0, as in 3.5.8,
     * no request carries Signature or Signature-Input until an
     * ApproovDefaultMessageSigning is installed as the service mutator; with the
     * default factory every protected request then carries the install
     * (ecdsa-p256-sha256) signature only, and the account (hmac-sha256) member is
     * added to the same dictionaries only by a factory set for both.
     */
    @Test
    public void testMessageSigningIsOffByDefaultAndTheSigningMutatorTurnsItOn() throws Exception {
        reinitializeServiceWithTargetHost("");
        assertSame(ApproovServiceMutator.DEFAULT, ApproovService.getServiceMutator());

        OkHttpClient client = ApproovService.getOkHttpClient();
        Request request = new Request.Builder().url(getTargetURL()).get().build();
        JSONObject reply = send(client, request);
        String token = getHeader(reply, "Approov-Token");
        assertNotNull(token);
        assertFalse(token.isEmpty());
        assertNull(getHeader(reply, "Signature"));
        assertNull(getHeader(reply, "Signature-Input"));

        ApproovService.setServiceMutator(new ApproovDefaultMessageSigning()
            .setDefaultFactory(ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()));
        assertInstallSignatureOnly(send(client, request));

        ApproovService.setServiceMutator(new ApproovDefaultMessageSigning()
            .setDefaultFactory(ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()
                .setUseInstallAndAccountMessageSigning()));
        assertBothSignatures(send(client, request));
    }

    /**
     * Signing is the service mutator (3.5.8). Installing any other mutator,
     * DEFAULT, ALWAYS_PROCEED, a custom one or null, turns signing off. To combine
     * signing with other decisions an app subclasses ApproovDefaultMessageSigning:
     * a subclass making the ALWAYS_PROCEED token decision signs a request that
     * proceeds after a failed fetch over its empty token header, and a subclass
     * whose processed request hook changes the request before calling super has
     * those changes covered by the signature.
     */
    @Test
    public void testMessageSigningIsTheServiceMutator() throws Exception {
        reinitializeServiceWithTargetHost("");
        OkHttpClient client = ApproovService.getOkHttpClient();
        Request request = new Request.Builder().url(getTargetURL()).get().build();

        TestSigning.install();
        assertInstallSignatureOnly(send(client, request));

        // signing with the ALWAYS_PROCEED token decision
        TestSigning.installAlwaysProceed();
        assertInstallSignatureOnly(send(client, request));
        setDirective("{\"operation\": \"fetchApproovToken\", \"response\": {\"status\": \"NO_NETWORK\"}}");
        JSONObject failed = send(client, request);
        assertEquals("", getHeader(failed, "Approov-Token"));
        assertInstallSignatureOnly(failed);
        assertTrue(getHeader(failed, "Signature-Input").contains("\"approov-token\""));

        // a subclass whose processed request hook adds a covered header before signing
        ApproovService.setServiceMutator(new ApproovDefaultMessageSigning() {
            @Override
            public Request handleInterceptorProcessedRequest(Request request, ApproovRequestMutations changes)
                    throws ApproovException {
                return super.handleInterceptorProcessedRequest(
                    request.newBuilder().header("Authorization", "Bearer from-hook").build(), changes);
            }
        }.setDefaultFactory(ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()));
        JSONObject reply = send(client, request);
        assertInstallSignatureOnly(reply);
        assertEquals("Bearer from-hook", getHeader(reply, "Authorization"));
        String signatureInput = getHeader(reply, "Signature-Input");
        assertTrue("the hook's change is signed: " + signatureInput, signatureInput.contains("\"authorization\""));

        // any other mutator turns signing off
        ApproovServiceMutator custom = new ApproovServiceMutator() {
            @Override
            public Request handleInterceptorProcessedRequest(Request request, ApproovRequestMutations changes) {
                return request.newBuilder().header("Authorization", "Bearer from-hook").build();
            }
        };
        for (ApproovServiceMutator mutator : new ApproovServiceMutator[] {ApproovServiceMutator.DEFAULT,
                ApproovServiceMutator.ALWAYS_PROCEED, custom, null}) {
            TestSigning.install();
            ApproovService.setServiceMutator(mutator);
            reply = send(client, request);
            assertSuccessToken(String.valueOf(mutator), reply);
            assertNull(mutator + ": unsigned", getHeader(reply, "Signature"));
            assertNull(mutator + ": unsigned", getHeader(reply, "Signature-Input"));
        }
        assertSame(ApproovServiceMutator.DEFAULT, ApproovService.getServiceMutator());
    }

    /**
     * A host factory replaces the default factory for its
     * host (matched without regard to case), removing it reinstates the default,
     * a signer with no factory signs nothing, and initialize() resets the mutator
     * to DEFAULT so that signing is off until the app installs a signer again.
     */
    @Test
    public void testHostFactoryAppliesAndInitializeResetsSigning() throws Exception {
        reinitializeServiceWithTargetHost("");
        ApproovDefaultMessageSigning.SignatureParametersFactory accountOnly =
            ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory().setUseAccountMessageSigning();
        ApproovDefaultMessageSigning signer = new ApproovDefaultMessageSigning()
            .setDefaultFactory(ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory())
            .putHostFactory(getTargetHost().toUpperCase(java.util.Locale.ROOT), accountOnly);
        ApproovService.setServiceMutator(signer);
        Request request = new Request.Builder().url(getTargetURL()).get().build();

        JSONObject reply = send(ApproovService.getOkHttpClient(), request);
        assertTrue(getHeader(reply, "Signature"), getHeader(reply, "Signature").startsWith("account=:"));
        assertFalse(getHeader(reply, "Signature-Input"), getHeader(reply, "Signature-Input").contains("install="));

        signer.putHostFactory(getTargetHost(), null);
        assertInstallSignatureOnly(send(ApproovService.getOkHttpClient(), request));

        // initialize resets the mutator, so signing is off
        signer.putHostFactory(getTargetHost(), accountOnly);
        ApproovService.initialize(context, validInitialConfig, "reinit");
        assertSame(ApproovServiceMutator.DEFAULT, ApproovService.getServiceMutator());
        reply = send(ApproovService.getOkHttpClient(), request);
        assertSuccessToken(reply);
        assertNull(getHeader(reply, "Signature"));
        assertNull(getHeader(reply, "Signature-Input"));
        // a new signer has no host factory; the app's own signer keeps its own
        TestSigning.install();
        assertInstallSignatureOnly(send(ApproovService.getOkHttpClient(), request));
        ApproovService.setServiceMutator(signer);
        assertTrue(getHeader(send(ApproovService.getOkHttpClient(), request), "Signature").startsWith("account=:"));
        // a signer with no factory signs nothing
        ApproovService.setServiceMutator(new ApproovDefaultMessageSigning());
        reply = send(ApproovService.getOkHttpClient(), request);
        assertSuccessToken(reply);
        assertNull(getHeader(reply, "Signature"));
        assertNull(getHeader(reply, "Signature-Input"));
    }

    /**
     * A bare SignatureParametersFactory, like the default factory, produces the
     * install signature only; both signatures only when selected explicitly.
     */
    @Test
    public void testBareFactoryDefaultsToTheInstallSignature() {
        ApproovDefaultMessageSigning.SignatureParametersFactory factory =
            new ApproovDefaultMessageSigning.SignatureParametersFactory();
        assertEquals(java.util.Collections.singletonList(ApproovDefaultMessageSigning.ALG_ES256), factory.getAlgs());
        assertEquals(java.util.Collections.singletonList(ApproovDefaultMessageSigning.ALG_ES256),
            ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory().getAlgs());
        assertEquals(java.util.Collections.singletonList(ApproovDefaultMessageSigning.ALG_ES256),
            factory.setUseInstallMessageSigning().getAlgs());
        assertEquals(java.util.Collections.singletonList(ApproovDefaultMessageSigning.ALG_HS256),
            factory.setUseAccountMessageSigning().getAlgs());
        assertEquals(java.util.Arrays.asList(ApproovDefaultMessageSigning.ALG_ES256,
            ApproovDefaultMessageSigning.ALG_HS256), factory.setUseInstallAndAccountMessageSigning().getAlgs());
    }

    /**
     * When one of the two signatures of a factory set for both cannot be produced
     * the request proceeds with the other, rather than unsigned or aborted.
     */
    @Test
    public void testBothSigningFallsBackToAccountWhenInstallKeyUnavailable() throws Exception {
        String targetHost = getTargetHost();
        reinitializeService(scenarioJson(uniqueCaseName("no-install-key-default"),
            "\"protectedDomains\": [\"" + targetHost + "\"]," +
            "\"simulateInstallKeyFailure\": true"));
        TestSigning.install(ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()
            .setUseInstallAndAccountMessageSigning());

        OkHttpClient client = ApproovService.getOkHttpClient();
        Request request = new Request.Builder().url(getTargetURL()).get().build();
        try (Response response = client.newCall(request).execute()) {
            assertEquals(200, response.code());
            JSONObject reply = new JSONObject(response.body().string());
            String signature = getHeader(reply, "Signature");
            assertNotNull(signature);
            assertTrue(signature, signature.startsWith("account=:"));
            assertFalse(signature, signature.contains("install="));
            String signatureInput = getHeader(reply, "Signature-Input");
            assertTrue(signatureInput, signatureInput.startsWith("account=("));
            assertFalse(signatureInput, signatureInput.contains("install="));
        }
    }

    /**
     * Install Signature Success / Single Signature Application
     * Unprotected Request Processing
     *
     * Install message signing successfully generates signature headers (install=...)
     * only once per request. Unprotected requests receive no signature headers.
     */
    @Test
    public void testUpdateRequestInstallMessageSigningAddsSignatureHeaders() throws Exception {
        reinitializeServiceWithTargetHost("");
        
        ApproovDefaultMessageSigning.SignatureParametersFactory factory = 
            ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()
            .setUseInstallMessageSigning();
        TestSigning.install(factory);
 
        OkHttpClient client = ApproovService.getOkHttpClient();
        Request request = new Request.Builder().url(getTargetURL()).get().build();
        try (Response response = client.newCall(request).execute()) {
            JSONObject reply = new JSONObject(response.body().string());
            
            assertNotNull(getHeader(reply, "Approov-Token"));
            String signatureInput = getHeader(reply, "Signature-Input");
            assertNotNull(signatureInput);
            assertTrue(signatureInput.startsWith("install="));
            assertFalse(signatureInput.contains("account="));
            
            String signature = getHeader(reply, "Signature");
            assertNotNull(signature);
            assertTrue(signature.startsWith("install=:"));
            assertTrue(signature.endsWith(":"));
            assertFalse(signature.contains("\""));
            assertFalse(signature.contains("account="));
        }
        
        Request unprotectedRequest = new Request.Builder().url(getNotInApproovURL()).get().build();
        try (Response response = client.newCall(unprotectedRequest).execute()) {
            JSONObject reply = new JSONObject(response.body().string());
            assertNull(getHeader(reply, "Approov-Token"));
            assertNull(getHeader(reply, "Signature"));
            assertNull(getHeader(reply, "Signature-Input"));
        }
    }

    /**
     * Account Message Signing
     *
     * Account message signing produces the expected signature headers (account=...).
     */
    @Test
    public void testUpdateRequestAccountMessageSigningAddsSignatureHeaders() throws Exception {
        reinitializeServiceWithTargetHost("");
        
        ApproovDefaultMessageSigning.SignatureParametersFactory factory = 
            ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()
            .setUseAccountMessageSigning();
        TestSigning.install(factory);
 
        OkHttpClient client = ApproovService.getOkHttpClient();
        Request request = new Request.Builder().url(getTargetURL()).get().build();
        try (Response response = client.newCall(request).execute()) {
            JSONObject reply = new JSONObject(response.body().string());
            
            assertNotNull(getHeader(reply, "Approov-Token"));
            String signatureInput = getHeader(reply, "Signature-Input");
            assertNotNull(signatureInput);
            assertTrue(signatureInput.startsWith("account="));
            assertFalse(signatureInput.contains("install="));
            
            String signature = getHeader(reply, "Signature");
            assertNotNull(signature);
            assertTrue(signature.startsWith("account=:"));
            assertTrue(signature.endsWith(":"));
            assertFalse(signature.contains("\""));
            assertFalse(signature.contains("install="));
        }
    }

    /**
     * Install Key Generation Failure / Signing Failure Fallback
     *
     * Install message signature fails if key pair generation fails; no signature
     * headers are added to the request, but the request proceeds with a token.
     */
    @Test
    public void testInstallMessageSigningFailsGracefullyIfKeyGenerationFails() throws Exception {
        String targetHost = getTargetHost();
        String body = "\"protectedDomains\": [\"" + targetHost + "\"]," +
                       "\"simulateInstallKeyFailure\": true";
        reinitializeService(scenarioJson(uniqueCaseName("no-install-key"), body));
        
        ApproovDefaultMessageSigning.SignatureParametersFactory factory =
            ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory().setUseInstallMessageSigning();
        TestSigning.install(factory);

        OkHttpClient client = ApproovService.getOkHttpClient();
        Request request = new Request.Builder().url(getTargetURL()).get().build();
        
        try (Response response = client.newCall(request).execute()) {
            assertEquals(200, response.code());
            JSONObject reply = new JSONObject(response.body().string());
            
            assertNotNull(getHeader(reply, "Approov-Token"));
            assertNull(getHeader(reply, "Signature"));
            assertNull(getHeader(reply, "Signature-Input"));
        }
    }

    /**
     * Digest Body Application
     *
     * The digest body (Content-Digest) for an install message signature is present
     * for POST, PUT, and PATCH requests when body digest is configured.
     */
    @Test
    public void testDigestBodyAppendedForPOSTPUTPATCHRequests() throws Exception {
        reinitializeServiceWithTargetHost("");
        
        ApproovDefaultMessageSigning.SignatureParametersFactory factory = ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()
            .setUseInstallMessageSigning();
        factory.setBodyDigestConfig(ApproovDefaultMessageSigning.DIGEST_SHA256, true);
        
        TestSigning.install(factory);

        String[] methods = {"POST", "PUT", "PATCH"};
        for (String method : methods) {
            OkHttpClient client = ApproovService.getOkHttpClient();
            RequestBody reqBody = RequestBody.create(MediaType.parse("application/json"), "{\"test\": 1}");
            Request request = new Request.Builder()
                .url(getTargetURL())
                .method(method, reqBody)
                .build();
            
            try (Response response = client.newCall(request).execute()) {
                assertEquals(200, response.code());
                JSONObject reply = new JSONObject(response.body().string());
                
                assertNotNull(getHeader(reply, "Approov-Token"));
                String sigInput = getHeader(reply, "Signature-Input");
                assertNotNull("Missing Signature-Input for " + method, sigInput);
                assertTrue(sigInput.contains("content-digest"));
                assertNotNull("Missing Content-Digest for " + method, getHeader(reply, "Content-Digest"));
            }
        }
    }

    // ==================================================================================
    // SECTION 6: Secure Strings & Custom JWT
    // ==================================================================================

    /**
     * Valid Secure String Key
     *
     * Fetch a secure string using a valid key returns the expected value.
     */
    @Test
    public void testFetchSecureStringReturnsConfiguredValue() throws ApproovException {
        setDirective("{" +
            "  \"operation\": \"fetchSecureString\"," +
            "  \"response\": {" +
            "    \"status\": \"SUCCESS\"," +
            "    \"secureString\": \"mini-secret\"" +
            "  }" +
            "}");
 
        String secureString = ApproovService.fetchSecureString("api-key", null);
        assertEquals("mini-secret", secureString);
    }

    /**
     * Non-existent Secure String Key
     *
     * Fetch a secure string using a non-existent key returns null.
     */
    @Test
    public void testFetchSecureStringReturnsNilForUnknownKey() throws ApproovException {
        setDirective("{" +
            "  \"operation\": \"fetchSecureString\"," +
            "  \"response\": {" +
            "    \"status\": \"UNKNOWN_KEY\"" +
            "  }" +
            "}");
 
        String secureString = ApproovService.fetchSecureString("missing-key", null);
        assertNull(secureString);
    }

    /**
     * Empty Secure String Key
     *
     * Fetch a secure string using an empty key throws an exception.
     */
    @Test
    public void testFetchSecureStringEmptyKeyThrowsException() {
        try {
            ApproovService.fetchSecureString("", null);
            fail("Expected ApproovException");
        } catch (ApproovException e) {
            // SDK validates key length: "Approov secure string key must be between 1 and 64 characters"
            assertTrue("Expected key validation message: " + e.getMessage(),
                e.getMessage().contains("1 and 64") || e.getMessage().toUpperCase().contains("BAD_KEY"));
        }
    }

    /**
     * Nil Secure String Key
     *
     * Fetch a secure string using a null key throws an exception.
     */
    @Test
    public void testFetchSecureStringNilKeyThrowsException() {
        try {
            ApproovService.fetchSecureString(null, null);
            fail("Expected ApproovException or IllegalArgumentException");
        } catch (ApproovException e) {
            // SDK wraps IllegalArgumentException("Approov key cannot be null")
            assertTrue("Expected null-related message: " + e.getMessage(),
                e.getMessage().toLowerCase().contains("null"));
        } catch (IllegalArgumentException e) {
            // SDK may throw directly before service layer catches it
            assertTrue("Expected null-related message: " + e.getMessage(),
                e.getMessage().toLowerCase().contains("null"));
        }
    }

    /**
     * Custom JWT Fetch
     *
     * Fetching a Custom JWT should accurately return the marshaled payload as a token.
     */
    @Test
    public void testFetchCustomJWTReturnsSignedJWT() throws Exception {
        String jwt = ApproovService.fetchCustomJWT("{\"role\":\"tester\"}");
        assertNotNull(jwt);
        JSONObject payload = decodeJWTBody(jwt);
 
        assertEquals("tester", payload.getString("role"));
        assertFalse(payload.has("exp"));
        assertFalse(payload.has("did"));
    }

    /**
     * Custom JWT Fetch (18KB payload)
     *
     * Fetching a Custom JWT with an 18KB JSON payload should work correctly.
     */
    @Test
    public void testFetchCustomJWT18KBPayload() throws Exception {
        StringBuilder sb = new StringBuilder(18 * 1024);
        for (int i = 0; i < 18 * 1024; i++) {
            sb.append("A");
        }
        String largePayload = sb.toString();
        
        JSONObject payloadStruct = new JSONObject();
        payloadStruct.put("data", largePayload);
        
        String jwt = ApproovService.fetchCustomJWT(payloadStruct.toString());
        assertNotNull(jwt);
        JSONObject payloadMap = decodeJWTBody(jwt);
        assertEquals(largePayload, payloadMap.getString("data"));
    }

    /**
     * Custom JWT Fetch (disabled)
     *
     * Fetching a Custom JWT when the feature is disabled throws an exception.
     */
    @Test
    public void testFetchCustomJWTDisabledThrowsFetchStatusException() throws Exception {
        reinitializeService(scenarioJson(uniqueCaseName("custom-jwt-disabled"),
            "\"customJWTEnabled\": false"
        ));

        try {
            ApproovService.fetchCustomJWT("{\"role\":\"tester\"}");
            fail("Expected ApproovFetchStatusException");
        } catch (ApproovFetchStatusException e) {
            assertTrue(e.getMessage().contains("fetchCustomJWT: DISABLED"));
        }
    }

    /**
     * Custom JWT Fetch (malformed JSON)
     *
     * Fetching a Custom JWT with a malformed JSON string throws an exception.
     */
    @Test
    public void testFetchCustomJWTBadPayloadThrowsApproovException() throws Exception {
        try {
            ApproovService.fetchCustomJWT("not-json");
            fail("Expected ApproovException");
        } catch (ApproovException e) {
            // Should throw due to IllegalArgumentException from SDK
        }
    }

    // ==================================================================================
    // Stale Protection Refresh
    // ==================================================================================

    // message signing (which supports protection refresh) that counts processed
    // request callback invocations, so that tests can observe how many times
    // protection was applied, and the request signed, for a request
    private static class CountingMutator extends ApproovDefaultMessageSigning {
        final AtomicInteger processedCount = new AtomicInteger();

        CountingMutator(ApproovDefaultMessageSigning.SignatureParametersFactory factory) {
            setDefaultFactory(factory);
        }

        @Override
        public Request handleInterceptorProcessedRequest(Request request, ApproovRequestMutations changes)
                throws ApproovException {
            processedCount.incrementAndGet();
            return super.handleInterceptorProcessedRequest(request, changes);
        }
    }

    /**
     * A request that is held between the token interceptor applying protection and
     * the request being transmitted (simulated by advancing the elapsed realtime
     * clock in a network interceptor that runs before the Approov freshness
     * interceptor) must have its protection refreshed at the network layer: the
     * processed request callback runs a second time and the transmitted request
     * carries exactly one token and one set of signature headers.
     */
    @Test
    public void testStaleRequestProtectionRefreshedAtNetworkLayer() throws Exception {
        reinitializeServiceWithTargetHost("");

        CountingMutator signing = new CountingMutator(
            ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory().setUseInstallMessageSigning());
        ApproovService.setServiceMutator(signing);

        // simulate a device suspend between protection and transmission on the
        // first network attempt only
        AtomicInteger attempts = new AtomicInteger();
        okhttp3.Interceptor hold = chain -> {
                if (attempts.getAndIncrement() == 0)
                    ShadowSystemClock.advanceBy(Duration.ofSeconds(10));
                return chain.proceed(chain.request());
            };

        OkHttpClient client = clientHeldBy(hold);
        Request request = new Request.Builder().url(getTargetURL()).get().build();
        try (Response response = client.newCall(request).execute()) {
            JSONObject reply = new JSONObject(response.body().string());

            // protection was applied at the application layer and refreshed once at
            // the network layer
            assertEquals(2, signing.processedCount.get());

            // the transmitted request carries a token and exactly one signature
            assertNotNull(getHeader(reply, "Approov-Token"));
            String signature = getHeader(reply, "Signature");
            assertNotNull(signature);
            assertTrue(signature.startsWith("install="));
            assertEquals(signature.indexOf("install="), signature.lastIndexOf("install="));
            String signatureInput = getHeader(reply, "Signature-Input");
            assertNotNull(signatureInput);
            assertTrue(signatureInput.startsWith("install="));
            assertEquals(signatureInput.indexOf("install="), signatureInput.lastIndexOf("install="));
        }
    }

    /**
     * A request that is not held between protection and transmission must not have
     * its protection refreshed, and disabling the stale protection refresh must
     * prevent a refresh even for a held request.
     */
    @Test
    public void testProtectionNotRefreshedWhenFreshOrDisabled() throws Exception {
        reinitializeServiceWithTargetHost("");

        CountingMutator signing = new CountingMutator(
            ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory().setUseInstallMessageSigning());
        ApproovService.setServiceMutator(signing);

        // a request that is transmitted promptly is not reprocessed
        OkHttpClient client = ApproovService.getOkHttpClient();
        Request request = new Request.Builder().url(getTargetURL()).get().build();
        try (Response response = client.newCall(request).execute()) {
            assertEquals(200, response.code());
            assertEquals(1, signing.processedCount.get());
        }

        // with the refresh disabled even a held request is not reprocessed
        ApproovService.setStaleProtectionRefreshPeriod(0);
        AtomicInteger attempts = new AtomicInteger();
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
            .addNetworkInterceptor(chain -> {
                if (attempts.getAndIncrement() == 0)
                    ShadowSystemClock.advanceBy(Duration.ofSeconds(10));
                return chain.proceed(chain.request());
            });
        ApproovService.setOkHttpClientBuilder(builder);
        client = ApproovService.getOkHttpClient();
        try (Response response = client.newCall(request).execute()) {
            assertEquals(200, response.code());
            assertEquals(2, signing.processedCount.get());
        }
    }

    /**
     * A custom mutator that does not opt in to protection refresh (via
     * supportsProtectionRefresh) must never have its processed request callback
     * reinvoked, even for a held request.
     */
    @Test
    public void testProtectionNotRefreshedForNonOptedInMutator() throws Exception {
        reinitializeServiceWithTargetHost("");

        // a custom mutator implementing the interface directly, which does not
        // override supportsProtectionRefresh and so defaults to unsupported
        AtomicInteger processedCount = new AtomicInteger();
        ApproovService.setServiceMutator(new ApproovServiceMutator() {
            @Override
            public Request handleInterceptorProcessedRequest(Request request, ApproovRequestMutations changes)
                    throws ApproovException {
                processedCount.incrementAndGet();
                return request;
            }
        });

        AtomicInteger attempts = new AtomicInteger();
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
            .addNetworkInterceptor(chain -> {
                if (attempts.getAndIncrement() == 0)
                    ShadowSystemClock.advanceBy(Duration.ofSeconds(10));
                return chain.proceed(chain.request());
            });
        ApproovService.setOkHttpClientBuilder(builder);

        OkHttpClient client = ApproovService.getOkHttpClient();
        Request request = new Request.Builder().url(getTargetURL()).get().build();
        try (Response response = client.newCall(request).execute()) {
            assertEquals(200, response.code());
            assertEquals(1, processedCount.get());
        }
    }

    // ==================================================================================
    // Regression tests: refresh, redirect, retry and pin rebuild behaviour
    // ==================================================================================

    /**
     * Control: a held request whose refreshed fetch fails is sent with an empty
     * token header (the token of the stale attempt replaced) and, with message
     * signing making the ALWAYS_PROCEED decision, a single signature regenerated
     * over that empty token header (3.5.8 signs such a request).
     */
    @Test
    public void testStaleFailureRewritesStatusOnWire() throws Exception {
        reinitializeServiceWithTargetHost("");
        TestSigning.installAlwaysProceed();
        okhttp3.Interceptor hold = chain -> {
            ShadowSystemClock.advanceBy(Duration.ofSeconds(10));
            setDirective("{\"operation\":\"fetchApproovToken\",\"response\":{\"status\":\"NO_NETWORK\"}}");
            return chain.proceed(chain.request());
        };
        try (Response response = clientHeldBy(hold).newCall(new Request.Builder().url(getTargetURL()).build()).execute()) {
            JSONObject reply = new JSONObject(response.body().string());
            assertEquals("", getHeader(reply, "Approov-Token"));
            assertInstallSignatureOnly(reply);
            String signatureInput = getHeader(reply, "Signature-Input");
            assertTrue(signatureInput, signatureInput.contains("\"approov-token\""));
            assertEquals("one signature, regenerated", signatureInput.indexOf("install="),
                signatureInput.lastIndexOf("install="));
        }
    }

    /**
     * A refresh whose fetch says the URL is a secrets-only API
     * (UNPROTECTED_URL) must strip the token and trace headers and the
     * signatures rather than send them unchanged.
     */
    @Test
    public void testStaleUnprotectedRemovesHeadersOnWire() throws Exception {
        reinitializeServiceWithTargetHost("");
        TestSigning.install();
        okhttp3.Interceptor hold = chain -> {
            ShadowSystemClock.advanceBy(Duration.ofSeconds(10));
            setDirective("{\"operation\":\"fetchApproovToken\",\"response\":{\"status\":\"UNPROTECTED_URL\"}}");
            return chain.proceed(chain.request());
        };
        try (Response response = clientHeldBy(hold).newCall(new Request.Builder().url(getTargetURL()).build()).execute()) {
            JSONObject reply = new JSONObject(response.body().string());
            assertNull("Stale token must not survive UNPROTECTED_URL", getHeader(reply, "Approov-Token"));
            assertNull(getHeader(reply, "Approov-TraceID"));
            assertNull(getHeader(reply, "Signature"));
        }
    }

    /**
     * A redirect from a protected host to one not added to Approov must not carry
     * the protected host's token, trace ID or signatures.
     */
    @Test
    public void testRedirectToHostNotInApproovRemovesProtectionOnWire() throws Exception {
        reinitializeServiceWithTargetHost("");
        TestSigning.install();
        AtomicInteger attempts = new AtomicInteger();
        ApproovService.setOkHttpClientBuilder(new OkHttpClient.Builder().addNetworkInterceptor(chain -> {
            Response response = chain.proceed(chain.request());
            if (attempts.getAndIncrement() == 0) {
                return response.newBuilder().code(302).message("Found")
                    .header("Location", getNotInApproovURL()).build();
            }
            return response;
        }));
        try (Response response = ApproovService.getOkHttpClient().newCall(new Request.Builder().url(getTargetURL()).build()).execute()) {
            assertEquals(2, attempts.get());
            JSONObject reply = new JSONObject(response.body().string());
            assertNull("Unprotected redirect received protected origin token", getHeader(reply, "Approov-Token"));
            assertNull(getHeader(reply, "Approov-TraceID"));
            assertNull(getHeader(reply, "Signature"));
        }
    }

    /**
     * A redirect to another path on the protected host is
     * re-protected for the new URL, with signatures regenerated over the new target.
     */
    @Test
    public void testRedirectWithinProtectedHostReprotectsForNewURL() throws Exception {
        reinitializeServiceWithTargetHost("");
        TestSigning.install();
        AtomicInteger attempts = new AtomicInteger();
        ApproovService.setOkHttpClientBuilder(new OkHttpClient.Builder().addNetworkInterceptor(chain -> {
            Response response = chain.proceed(chain.request());
            if (attempts.getAndIncrement() == 0) {
                return response.newBuilder().code(302).message("Found")
                    .header("Location", getTargetURL() + "/redirected").build();
            }
            return response;
        }));
        try (Response response = ApproovService.getOkHttpClient().newCall(new Request.Builder().url(getTargetURL()).build()).execute()) {
            assertEquals(2, attempts.get());
            JSONObject reply = new JSONObject(response.body().string());
            assertTrue(reply.getString("url").endsWith("/redirected"));
            String token = getHeader(reply, "Approov-Token");
            assertNotNull(token);
            assertFalse(token.isEmpty());
            assertSuccessToken(reply);
            assertNotNull(getHeader(reply, "Signature"));
            // one set of signature members, not the original plus a new one
            String signature = getHeader(reply, "Signature");
            assertEquals(signature.indexOf("install="), signature.lastIndexOf("install="));
        }
    }

    /**
     * A refresh must not mutate the marker of the original request. A retry
     * that reuses the original request object must be refreshed again rather than
     * sent with its stale headers under a marker that now looks fresh.
     */
    @Test
    public void testRetryOfOriginalRequestIsRefreshedAgain() throws Exception {
        reinitializeServiceWithTargetHost("");
        ApproovService.setServiceMutator(ApproovServiceMutator.ALWAYS_PROCEED);
        // the refreshed failure is visible as the status in the token header
        ApproovService.setUseApproovStatusIfNoToken(true);
        AtomicInteger attempts = new AtomicInteger();
        okhttp3.Interceptor hold = chain -> {
            if (attempts.getAndIncrement() == 0) {
                ShadowSystemClock.advanceBy(Duration.ofSeconds(10));
                setDirective("{\"operation\":\"fetchApproovToken\",\"response\":{\"status\":\"NO_NETWORK\"}}");
            } else {
                setDirective("{\"operation\":\"fetchApproovToken\",\"response\":{\"status\":\"NO_NETWORK\"}}");
            }
            return chain.proceed(chain.request());
        };
        // RetryAndFollowUpInterceptor reuses its original Request after a recoverable
        // IOException; this application interceptor, appended after token processing,
        // repeats the same request object to exercise the same ownership problem
        OkHttpClient client = clientHeldBy(hold).newBuilder().addInterceptor(chain -> {
            try (Response first = chain.proceed(chain.request())) {
                try {
                    JSONObject reply = new JSONObject(first.body().string());
                    assertEquals("NO_NETWORK", getHeader(reply, "Approov-Token"));
                } catch (Exception e) {
                    throw new IOException(e);
                }
            }
            return chain.proceed(chain.request());
        }).build();
        try (Response response = client.newCall(new Request.Builder().url(getTargetURL()).build()).execute()) {
            assertEquals(2, attempts.get());
            JSONObject reply = new JSONObject(response.body().string());
            assertEquals("Retry must use refreshed status, not mark the original status fresh", "NO_NETWORK", getHeader(reply, "Approov-Token"));
        }
    }

    /**
     * Control: a factory subclass setting an explicit algorithm produces that single
     * signature, and debug mode emits a per-member Signature-Base-Digest.
     */
    @Test
    public void testExplicitAlgorithmAndDebugDigest() throws Exception {
        reinitializeServiceWithTargetHost("");
        ApproovDefaultMessageSigning.SignatureParametersFactory factory = new ApproovDefaultMessageSigning.SignatureParametersFactory() {
            @Override
            protected io.approov.util.okhttp.sig.SignatureParameters buildSignatureParameters(ApproovDefaultMessageSigning.OkHttpComponentProvider provider, ApproovRequestMutations changes) throws ApproovDefaultMessageSigning.RequiredBodyDigestException {
                io.approov.util.okhttp.sig.SignatureParameters params = super.buildSignatureParameters(provider, changes);
                params.setAlg(ApproovDefaultMessageSigning.ALG_HS256);
                params.setDebugMode(true);
                return params;
            }
        };
        factory.setAddApproovTokenHeader(true).setUseInstallAndAccountMessageSigning();
        TestSigning.install(factory);
        try (Response response = ApproovService.getOkHttpClient().newCall(new Request.Builder().url(getTargetURL()).build()).execute()) {
            JSONObject reply = new JSONObject(response.body().string());
            assertTrue(getHeader(reply, "Signature").startsWith("account=:"));
            assertFalse(getHeader(reply, "Signature-Input").contains("install="));
            assertTrue(getHeader(reply, "Signature-Base-Digest").startsWith("account=:"));
        }
    }

    /**
     * Every interceptor hook, including the pinning hook, declares exactly
     * ApproovException (3.5.8), so that an app aborts a request with an
     * ApproovException and a delegating override can declare it. Message signing
     * is a mutator, with a processed request override of its own.
     */
    @Test
    public void testAllInterceptorHooksDeclareApproovException() throws Exception {
        int hooks = 0;
        for (java.lang.reflect.Method m : ApproovServiceMutator.class.getMethods()) {
            if (m.getName().startsWith("handleInterceptor") || m.getName().equals("handlePinningShouldProcessRequest")) {
                hooks++;
                assertEquals(m.getName(), java.util.Collections.singletonList(ApproovException.class),
                    java.util.Arrays.asList(m.getExceptionTypes()));
            }
        }
        assertEquals(6, hooks);
        assertTrue(ApproovServiceMutator.class.isAssignableFrom(ApproovDefaultMessageSigning.class));
        assertEquals(ApproovDefaultMessageSigning.class, ApproovDefaultMessageSigning.class.getMethod(
            "handleInterceptorProcessedRequest", Request.class, ApproovRequestMutations.class).getDeclaringClass());
        ApproovServiceMutator delegating = new ApproovServiceMutator() {
            @Override
            public Request handleInterceptorProcessedRequest(Request request, ApproovRequestMutations changes) throws ApproovException {
                return ApproovServiceMutator.DEFAULT.handleInterceptorProcessedRequest(request, changes);
            }
            @Override
            public boolean handlePinningShouldProcessRequest(Request request) throws ApproovException {
                return ApproovServiceMutator.DEFAULT.handlePinningShouldProcessRequest(request);
            }
        };
        assertNotNull(delegating);
    }

    /**
     * A pin rebuild that lands while a request is being checked against
     * the old pins applies to the next request.
     */
    @Test
    public void testPinUpdateDuringCheckAppliesToNextRequest() throws Exception {
        reinitializeServiceWithTargetHost("");
        java.security.cert.Certificate peer;
        try (Response response = new OkHttpClient().newCall(new Request.Builder().url(getTargetURL()).build()).execute()) {
            peer = response.handshake().peerCertificates().get(0);
        }
        CertificatePinner valid = new CertificatePinner.Builder().add(getTargetHost(), CertificatePinner.pin(peer)).build();
        AtomicInteger reads = new AtomicInteger();
        ApproovPinningInterceptor interceptor = new ApproovPinningInterceptor() {
            @Override synchronized CertificatePinner getCertificatePinner() {
                CertificatePinner snapshot = super.getCertificatePinner();
                if (reads.incrementAndGet() == 2) {
                    // inject the interleaving: another dispatcher finishes a config change
                    // rebuild after this request obtains its old pinner, before this request
                    // completes its check
                    AttesterProxyController.setNextPinningDirectiveJson("{\"operation\":\"getPins\",\"shouldFail\":true}");
                    try {
                        buildPins();
                    } catch (ApproovException e) {
                        throw new AssertionError(e);
                    }
                }
                return snapshot;
            }
        };
        java.lang.reflect.Field pinnerField = ApproovPinningInterceptor.class.getDeclaredField("certificatePinner");
        pinnerField.setAccessible(true);
        pinnerField.set(interceptor, valid);
        OkHttpClient client = new OkHttpClient.Builder().addNetworkInterceptor(interceptor).build();
        Request request = new Request.Builder().url(getTargetURL()).build();
        try (Response response = client.newCall(request).execute()) {
            assertEquals(200, response.code());
        }
        assertTrue(interceptor.getCertificatePinner().getPins().toString().contains("AAAAAAAA"));
        try (Response response = client.newCall(request).execute()) {
            fail("Request succeeded after pins rotated to a nonmatching pin");
        } catch (javax.net.ssl.SSLPeerUnverifiedException expected) {
            // the new pin generation is enforced on the next request
        }
    }

    /**
     * A 303 to the same URL turns a POST into a GET. The followup
     * is reprotected so that its signature covers the new method rather than POST.
     */
    @Test
    public void testSameURL303ReprotectsForTheNewMethod() throws Exception {
        reinitializeServiceWithTargetHost("");
        TestSigning.install();
        AtomicInteger attempts = new AtomicInteger();
        ApproovService.setOkHttpClientBuilder(new OkHttpClient.Builder().addNetworkInterceptor(chain -> {
            Response response = chain.proceed(chain.request());
            if (attempts.getAndIncrement() == 0)
                return response.newBuilder().code(303).message("See Other").header("Location", getTargetURL()).build();
            return response;
        }));
        RequestBody body = RequestBody.create("{\"a\":1}", MediaType.parse("application/json"));
        Request request = new Request.Builder().url(getTargetURL()).post(body).build();
        try (Response response = ApproovService.getOkHttpClient().newCall(request).execute()) {
            assertEquals(2, attempts.get());
            JSONObject reply = new JSONObject(response.body().string());
            assertEquals("GET", reply.getString("method"));
            assertSuccessToken(reply);
            // the POST attempt carried a Content-Digest; a signature regenerated for the GET
            // does not cover one and the header is gone with the body
            assertNull(getHeader(reply, "Content-Digest"));
            String signatureInput = getHeader(reply, "Signature-Input");
            assertNotNull(signatureInput);
            assertFalse(signatureInput, signatureInput.contains("content-digest"));
            assertEquals(signatureInput.indexOf("install="), signatureInput.lastIndexOf("install="));
        }
    }

    /**
     * A secure string with surrounding whitespace is stored
     * trimmed by OkHttp; stripping must still recognise the header as one this layer
     * installed and restore the placeholder before a redirect to a host not added to Approov.
     */
    @Test
    public void testWhitespaceSecretIsRestoredBeforeRedirectToHostNotInApproov() throws Exception {
        String targetHost = getTargetHost();
        reinitializeService(scenarioJson(uniqueCaseName("padded-secret"),
            "\"protectedDomains\": [\"" + targetHost + "\"]," +
            "\"initialSecureStrings\": {\"header-key\": \"  padded-secret  \"}"
        ));
        TestSigning.install();
        ApproovService.addSubstitutionHeader("Api-Key", null);
        AtomicInteger attempts = new AtomicInteger();
        ApproovService.setOkHttpClientBuilder(new OkHttpClient.Builder().addNetworkInterceptor(chain -> {
            Response response = chain.proceed(chain.request());
            if (attempts.getAndIncrement() == 0)
                return response.newBuilder().code(302).message("Found").header("Location", getNotInApproovURL()).build();
            return response;
        }));
        Request request = new Request.Builder().url(getTargetURL()).header("Api-Key", "header-key").build();
        try (Response response = ApproovService.getOkHttpClient().newCall(request).execute()) {
            assertEquals(2, attempts.get());
            JSONObject reply = new JSONObject(response.body().string());
            assertEquals("header-key", getHeader(reply, "Api-Key"));
            assertNull(getHeader(reply, "Approov-Token"));
            assertNull(getHeader(reply, "Signature"));
        }
    }

    /**
     * An OkHttp Authenticator retrying a 401 immediately with a
     * new Authorization header changes a covered component; the retry is reprotected
     * and signed over the new value rather than sent with the previous signature.
     */
    @Test
    public void testAuthenticatorRetryIsResignedOverNewAuthorization() throws Exception {
        reinitializeServiceWithTargetHost("");
        TestSigning.install();
        List<String> signatures = new ArrayList<>();
        List<String> authorizations = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        ApproovService.setOkHttpClientBuilder(new OkHttpClient.Builder()
            .addNetworkInterceptor(chain -> {
                signatures.add(chain.request().header("Signature"));
                authorizations.add(chain.request().header("Authorization"));
                Response response = chain.proceed(chain.request());
                if (attempts.getAndIncrement() == 0)
                    return response.newBuilder().code(401).message("Unauthorized").build();
                return response;
            })
            .authenticator((route, response) -> {
                if ("Bearer second".equals(response.request().header("Authorization")))
                    return null;
                return response.request().newBuilder().header("Authorization", "Bearer second").build();
            }));
        Request request = new Request.Builder().url(getTargetURL()).header("Authorization", "Bearer first").build();
        try (Response response = ApproovService.getOkHttpClient().newCall(request).execute()) {
            assertEquals(200, response.code());
            assertEquals(2, attempts.get());
            assertEquals(java.util.Arrays.asList("Bearer first", "Bearer second"), authorizations);
            assertNotNull(signatures.get(0));
            JSONObject reply = new JSONObject(response.body().string());
            // the recording interceptor runs before the freshness interceptor, so the
            // reply carries what left after reprotection: a different signature
            assertNotEquals("the retry must be signed over the new Authorization",
                signatures.get(0), getHeader(reply, "Signature"));
            assertEquals("Bearer second", getHeader(reply, "Authorization"));
            String signatureInput = getHeader(reply, "Signature-Input");
            assertTrue(signatureInput, signatureInput.contains("\"authorization\""));
            assertSuccessToken(reply);
        }
    }

    /**
     * A header-only rebuild (an Authenticator retry) must restore a
     * substituted query parameter to its placeholder so that the reapply fetches the
     * secure string afresh; a rotated value reaches the wire, not the one from the
     * first attempt.
     */
    @Test
    public void testAuthenticatorRetryRefreshesSubstitutedQueryParameter() throws Exception {
        String targetHost = getTargetHost();
        reinitializeService(scenarioJson(uniqueCaseName("query-rotates"),
            "\"protectedDomains\": [\"" + targetHost + "\"]," +
            "\"initialSecureStrings\": {\"query-key\": \"old-secret\"}"
        ));
        ApproovService.addSubstitutionQueryParam("api_key");
        AtomicInteger attempts = new AtomicInteger();
        ApproovService.setOkHttpClientBuilder(new OkHttpClient.Builder()
            .addNetworkInterceptor(chain -> {
                Response response = chain.proceed(chain.request());
                if (attempts.getAndIncrement() == 0) {
                    // the secure string is rotated before the authenticator retries
                    setDirective("{\"operation\":\"fetchSecureString\",\"response\":{\"secureString\":\"new-secret\"}}");
                    return response.newBuilder().code(401).message("Unauthorized").build();
                }
                return response;
            })
            .authenticator((route, response) -> {
                if ("Bearer second".equals(response.request().header("Authorization")))
                    return null;
                return response.request().newBuilder().header("Authorization", "Bearer second").build();
            }));
        Request request = new Request.Builder().url(getTargetURL() + "?api_key=query-key")
            .header("Authorization", "Bearer first").build();
        try (Response response = ApproovService.getOkHttpClient().newCall(request).execute()) {
            assertEquals(200, response.code());
            assertEquals(2, attempts.get());
            JSONObject reply = new JSONObject(response.body().string());
            String url = reply.getString("url");
            assertTrue(url, url.contains("api_key=new-secret"));
            assertFalse(url, url.contains("old-secret"));
            assertEquals("Bearer second", getHeader(reply, "Authorization"));
        }
    }

    /**
     * A header-only rebuild (an
     * authenticator retry) whose refreshed fetch reports the URL as a secrets-only
     * API (UNPROTECTED_URL, added with -noApproovToken) substitutes the query
     * parameter afresh from the restored placeholder and adds no token or trace
     * header. Leaving the placeholder in place would break secrets-only APIs.
     */
    @Test
    public void testAuthenticatorRetryToUnprotectedSubstitutesQueryAfresh() throws Exception {
        JSONObject reply = authenticatorRetryWithRefreshedStatus("UNPROTECTED_URL");
        String url = reply.getString("url");
        assertTrue(url, url.contains("api_key=query-secret"));
        assertFalse(url, url.contains("api_key=query-key"));
        assertNull(getHeader(reply, "Approov-Token"));
        assertNull(getHeader(reply, "Approov-TraceID"));
    }

    /**
     * The same rebuild whose refreshed fetch reports a host not added to Approov
     * (UNKNOWN_URL) leaves the query placeholder, not the previously substituted
     * secret, on the wire.
     */
    @Test
    public void testAuthenticatorRetryToUnknownUrlRestoresQueryPlaceholder() throws Exception {
        JSONObject reply = authenticatorRetryWithRefreshedStatus("UNKNOWN_URL");
        String url = reply.getString("url");
        assertTrue(url, url.contains("api_key=query-key"));
        assertFalse(url, url.contains("query-secret"));
        assertNull(getHeader(reply, "Approov-Token"));
        assertNull(getHeader(reply, "Approov-TraceID"));
    }

    // sends a request with a substituted query parameter that is retried by an
    // authenticator after a 401, the retry's token fetch reporting the status, and
    // returns the echo of the retry
    private JSONObject authenticatorRetryWithRefreshedStatus(String status) throws Exception {
        String targetHost = getTargetHost();
        reinitializeService(scenarioJson(uniqueCaseName("query-refresh"),
            "\"protectedDomains\": [\"" + targetHost + "\"]," +
            "\"initialSecureStrings\": {\"query-key\": \"query-secret\"}"
        ));
        ApproovService.addSubstitutionQueryParam("api_key");
        AtomicInteger attempts = new AtomicInteger();
        ApproovService.setOkHttpClientBuilder(new OkHttpClient.Builder()
            .addNetworkInterceptor(chain -> {
                Response response = chain.proceed(chain.request());
                if (attempts.getAndIncrement() == 0) {
                    setDirective("{\"operation\":\"fetchApproovToken\",\"response\":{\"status\":\"" + status + "\"}}");
                    return response.newBuilder().code(401).message("Unauthorized").build();
                }
                return response;
            })
            .authenticator((route, response) -> {
                if ("Bearer second".equals(response.request().header("Authorization")))
                    return null;
                return response.request().newBuilder().header("Authorization", "Bearer second").build();
            }));
        Request request = new Request.Builder().url(getTargetURL() + "?api_key=query-key")
            .header("Authorization", "Bearer first").build();
        try (Response response = ApproovService.getOkHttpClient().newCall(request).execute()) {
            assertEquals(2, attempts.get());
            return new JSONObject(response.body().string());
        }
    }

    /**
     * A refresh must not undo a header change the app made after
     * the substitution. An OkHttp authenticator replaces the placeholder with a second
     * key; the stale refresh substitutes that key, not the first one.
     */
    @Test
    public void testRefreshedSubstitutionHonoursAuthenticatorHeaderReplacement() throws Exception {
        String targetHost = getTargetHost();
        reinitializeService(scenarioJson(uniqueCaseName("auth-rotates-key"),
            "\"protectedDomains\": [\"" + targetHost + "\"]," +
            "\"initialSecureStrings\": {\"first-key\": \"first-secret\", \"second-key\": \"second-secret\"}"
        ));
        ApproovService.addSubstitutionHeader("Api-Key", null);
        AtomicInteger attempts = new AtomicInteger();
        ApproovService.setOkHttpClientBuilder(new OkHttpClient.Builder()
            .addNetworkInterceptor(chain -> {
                Response response = chain.proceed(chain.request());
                if (attempts.getAndIncrement() == 0) {
                    // the app's authenticator will retry after this; make the retry stale
                    ShadowSystemClock.advanceBy(Duration.ofSeconds(10));
                    return response.newBuilder().code(401).message("Unauthorized").build();
                }
                return response;
            })
            .authenticator((route, response) -> {
                if ("second-key".equals(response.request().header("Api-Key")) ||
                        "second-secret".equals(response.request().header("Api-Key")))
                    return null;
                return response.request().newBuilder().header("Api-Key", "second-key").build();
            }));
        Request request = new Request.Builder().url(getTargetURL()).header("Api-Key", "first-key").build();
        try (Response response = ApproovService.getOkHttpClient().newCall(request).execute()) {
            assertEquals(200, response.code());
            assertEquals(2, attempts.get());
            JSONObject reply = new JSONObject(response.body().string());
            assertEquals("second-secret", getHeader(reply, "Api-Key"));
            assertSuccessToken(reply);
        }
    }

    /**
     * Stripping a substitution restores every original field of
     * a repeated header, in order.
     */
    @Test
    public void testStaleUnknownUrlRestoresAllOriginalHeaderValues() throws Exception {
        String targetHost = getTargetHost();
        reinitializeService(scenarioJson(uniqueCaseName("repeated-header"),
            "\"protectedDomains\": [\"" + targetHost + "\"]," +
            "\"initialSecureStrings\": {\"second-key\": \"second-secret\"}"
        ));
        ApproovService.addSubstitutionHeader("X-List", null);
        okhttp3.Interceptor hold = chain -> {
            ShadowSystemClock.advanceBy(Duration.ofSeconds(10));
            // a refresh to a host not added to Approov restores the placeholders (an
            // UNPROTECTED_URL refresh would substitute afresh)
            setDirective("{\"operation\":\"fetchApproovToken\",\"response\":{\"status\":\"UNKNOWN_URL\"}}");
            return chain.proceed(chain.request());
        };
        Request request = new Request.Builder().url(getTargetURL())
            .addHeader("X-List", "first-key").addHeader("X-List", "second-key").build();
        try (Response response = clientHeldBy(hold).newCall(request).execute()) {
            JSONObject reply = new JSONObject(response.body().string());
            assertNull(getHeader(reply, "Approov-Token"));
            assertEquals(java.util.Arrays.asList("first-key", "second-key"), getHeaderValues(reply, "X-List"));
        }
    }

    /**
     * With signing on, a renamed token header is covered by the default
     * signature under its new name, and no status header is covered or sent.
     */
    @Test
    public void testRenamedTokenHeaderIsCoveredBySignature() throws Exception {
        reinitializeServiceWithTargetHost("");
        TestSigning.install();
        ApproovService.setTokenHeader("X-Approov-Fetch-Token", "");
        try (Response response = ApproovService.getOkHttpClient().newCall(new Request.Builder().url(getTargetURL()).build()).execute()) {
            JSONObject reply = new JSONObject(response.body().string());
            assertNull(getHeader(reply, "Approov-Token"));
            assertNull(getHeader(reply, "Approov-Status"));
            String token = getHeader(reply, "X-Approov-Fetch-Token");
            assertNotNull(token);
            assertEquals(token, 3, token.split("\\.").length);
            String signatureInput = getHeader(reply, "Signature-Input");
            assertTrue(signatureInput, signatureInput.contains("\"x-approov-fetch-token\""));
            assertFalse(signatureInput, signatureInput.contains("\"approov-token\""));
            assertFalse(signatureInput, signatureInput.contains("\"approov-status\""));
        }
    }

    /**
     * No failure cache. A request after a failed fetch is
     * given a token as soon as the SDK provides one again; nothing in the layer
     * replays the failure.
     */
    @Test
    public void testNoFailureCacheBetweenRequests() throws Exception {
        reinitializeServiceWithTargetHost("");
        ApproovService.setServiceMutator(ApproovServiceMutator.ALWAYS_PROCEED);
        OkHttpClient client = ApproovService.getOkHttpClient();
        setDirective("{\"operation\": \"fetchApproovToken\", \"response\": {\"status\": \"NO_NETWORK\"}}");
        try (Response response = client.newCall(new Request.Builder().url(getTargetURL()).build()).execute()) {
            JSONObject reply = new JSONObject(response.body().string());
            assertEquals("", getHeader(reply, "Approov-Token"));
        }
        try (Response response = client.newCall(new Request.Builder().url(getTargetURL()).build()).execute()) {
            JSONObject reply = new JSONObject(response.body().string());
            assertSuccessToken(reply);
            String token = getHeader(reply, "Approov-Token");
            assertNotNull(token);
            assertFalse(token.isEmpty());
        }
    }

    /**
     * The removed APIs are absent from the public surface
     * and the deprecated ones that must stay are present.
     */
    @Test
    public void testRemovedApisAreAbsentAndRetainedDeprecatedOnesPresent() throws Exception {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (java.lang.reflect.Method m : ApproovService.class.getMethods())
            names.add(m.getName());
        for (String removed : new String[] {
                "setStatusHeader", "getStatusHeader",
                "setApproovInterceptorExtensions", "getApproovInterceptorExtensions",
                "setProceedOnNetworkFail", "getProceedOnNetworkFail", "prefetch"})
            assertFalse(removed + " must be removed on 3.7.x", names.contains(removed));
        // signing is the ApproovDefaultMessageSigning mutator (3.5.8), with no ApproovService methods
        assertFalse("createDefaultServiceMutator must be removed", names.contains("createDefaultServiceMutator"));
        for (String removed : new String[] {"enableMessageSigning", "disableMessageSigning",
                "isMessageSigningEnabled", "putMessageSigningHostFactory"})
            assertFalse(removed + " must be absent on 3.7.x", names.contains(removed));
        for (String retained : new String[] {"getMessageSignature", "setApproovHeader",
                "setApproovTraceIDHeader", "setTokenHeader", "setTraceIDHeader",
                "setUseApproovStatusIfNoToken", "getUseApproovStatusIfNoToken",
                "getLastARC", "isInitialized", "isApproovEnabled", "setInstallAttrsInToken"})
            assertTrue(retained + " must be present", names.contains(retained));
        try {
            Class.forName("io.approov.service.okhttp.ApproovInterceptorExtensions");
            fail("ApproovInterceptorExtensions must be removed on 3.7.x");
        } catch (ClassNotFoundException expected) {
            // removed
        }
        // the signer is a mutator with the 3.5.8 entry points, constructed by the app
        for (String retained : new String[] {"processedRequest", "handleInterceptorProcessedRequest"})
            assertEquals(retained, Request.class, ApproovDefaultMessageSigning.class.getMethod(retained, Request.class,
                ApproovRequestMutations.class).getReturnType());
        assertNotNull("a public constructor", ApproovDefaultMessageSigning.class.getConstructor());
    }

    /**
     * With both signatures configured, a refreshed request carries exactly one
     * Signature header with both members, regenerated once, and the refreshed token.
     */
    @Test
    public void testStaleRefreshRegeneratesBothSignaturesOnce() throws Exception {
        reinitializeServiceWithTargetHost("");
        CountingMutator signing = new CountingMutator(ApproovDefaultMessageSigning
            .generateDefaultSignatureParametersFactory().setUseInstallAndAccountMessageSigning());
        ApproovService.setServiceMutator(signing);
        AtomicInteger attempts = new AtomicInteger();
        okhttp3.Interceptor hold = chain -> {
            if (attempts.getAndIncrement() == 0)
                ShadowSystemClock.advanceBy(Duration.ofSeconds(10));
            return chain.proceed(chain.request());
        };
        try (Response response = clientHeldBy(hold).newCall(new Request.Builder().url(getTargetURL()).build()).execute()) {
            JSONObject reply = new JSONObject(response.body().string());
            assertEquals(2, signing.processedCount.get());
            String signature = getHeader(reply, "Signature");
            assertTrue(signature, signature.startsWith("install=:"));
            assertTrue(signature, signature.contains(", account=:"));
            assertEquals(signature.indexOf("install="), signature.lastIndexOf("install="));
            assertEquals(signature.indexOf("account="), signature.lastIndexOf("account="));
            assertSuccessToken(reply);
            // the reply reports headers as arrays where repeated; a single value proves one header
            assertEquals(1, reply.getJSONObject("headers").optJSONArray("signature") == null ? 1
                    : reply.getJSONObject("headers").getJSONArray("signature").length());
        }
    }

    /**
     * A cleartext connection (no TLS handshake) is never blocked by the pinning
     * interceptor, to a host with pins or without: there is no handshake to pin,
     * and the SDK reports a cleartext URL as BAD_URL to the token interceptor,
     * whose mutator decides the request.
     */
    @Test
    public void testCleartextConnectionIsNeverBlockedByPinning() throws Exception {
        reinitializeServiceWithTargetHost("");
        ApproovPinningInterceptor interceptor = new ApproovPinningInterceptor();
        CertificatePinner pinned = new CertificatePinner.Builder()
            .add("pinned.example.com", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=").build();
        java.lang.reflect.Field pinnerField = ApproovPinningInterceptor.class.getDeclaredField("certificatePinner");
        pinnerField.setAccessible(true);
        pinnerField.set(interceptor, pinned);

        Request toPinned = new Request.Builder().url("http://pinned.example.com/").build();
        NoHandshakeChain pinnedChain = new NoHandshakeChain(toPinned);
        // the chain's response has no body, so it is not closed (OkHttp 4 refuses to close it)
        assertEquals(200, interceptor.intercept(pinnedChain).code());
        assertSame(toPinned, pinnedChain.proceededWith);

        Request toUnpinned = new Request.Builder().url("http://unpinned.example.com/").build();
        NoHandshakeChain chain = new NoHandshakeChain(toUnpinned);
        interceptor.intercept(chain);
        assertSame(toUnpinned, chain.proceededWith);
    }

    /**
     * Pins are rebuilt lazily until they exist and not afterwards.
     */
    @Test
    public void testPinsNotRebuiltOnEveryRequestOncePresent() throws Exception {
        reinitializeServiceWithTargetHost("");
        AtomicInteger rebuilds = new AtomicInteger();
        ApproovPinningInterceptor interceptor = new ApproovPinningInterceptor() {
            @Override public synchronized void buildPins() throws ApproovException {
                rebuilds.incrementAndGet();
                super.buildPins();
            }
        };
        // the test scenario provides no pins for the host, so install a pinner holding the
        // real peer pin: once pins are present no request may rebuild them
        java.security.cert.Certificate peer;
        try (Response response = new OkHttpClient().newCall(new Request.Builder().url(getTargetURL()).build()).execute()) {
            peer = response.handshake().peerCertificates().get(0);
        }
        java.lang.reflect.Field pinnerField = ApproovPinningInterceptor.class.getDeclaredField("certificatePinner");
        pinnerField.setAccessible(true);
        pinnerField.set(interceptor, new CertificatePinner.Builder().add(getTargetHost(), CertificatePinner.pin(peer)).build());
        int afterConstruction = rebuilds.get();
        OkHttpClient client = new OkHttpClient.Builder().addNetworkInterceptor(interceptor).build();
        for (int i = 0; i < 3; i++) {
            try (Response response = client.newCall(new Request.Builder().url(getTargetURL()).build()).execute()) {
                assertEquals(200, response.code());
            }
        }
        assertEquals(afterConstruction, rebuilds.get());
    }

    // ==================================================================================
    // Every connection check evaluates the current pins for the request's host
    // ==================================================================================

    // one certificate valid for two hosts, as behind a wildcard certificate or a CDN
    private static final String GOOD_HOST = "good.pinned.example.com";
    private static final String BAD_HOST = "bad.pinned.example.com";
    private static final String NON_MATCHING_PIN = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    private static okhttp3.tls.HeldCertificate sharedCertificate() {
        return new okhttp3.tls.HeldCertificate.Builder()
            .addSubjectAlternativeName(GOOD_HOST)
            .addSubjectAlternativeName(BAD_HOST)
            .build();
    }

    // GOOD_HOST pinned to the shared certificate, BAD_HOST to a pin it does not match
    private static CertificatePinner sharedCertificatePinner(okhttp3.tls.HeldCertificate cert) {
        return new CertificatePinner.Builder()
            .add(GOOD_HOST, CertificatePinner.pin(cert.certificate()))
            .add(BAD_HOST, NON_MATCHING_PIN)
            .build();
    }

    private static okhttp3.Handshake handshakeFor(okhttp3.tls.HeldCertificate cert) {
        return okhttp3.Handshake.get(okhttp3.TlsVersion.TLS_1_3, okhttp3.CipherSuite.TLS_AES_128_GCM_SHA256,
            java.util.Collections.<java.security.cert.Certificate>singletonList(cert.certificate()),
            java.util.Collections.<java.security.cert.Certificate>emptyList());
    }

    private static ApproovPinningInterceptor interceptorWith(ApproovPinningInterceptor interceptor,
            CertificatePinner pinner) throws Exception {
        java.lang.reflect.Field pinnerField = ApproovPinningInterceptor.class.getDeclaredField("certificatePinner");
        pinnerField.setAccessible(true);
        pinnerField.set(interceptor, pinner);
        return interceptor;
    }

    // runs one connection check for the host over the given handshake
    private static HandshakeChain check(ApproovPinningInterceptor interceptor, String host,
            okhttp3.Handshake handshake) throws IOException {
        HandshakeChain chain = new HandshakeChain(new Request.Builder().url("https://" + host + "/").build(), handshake);
        interceptor.intercept(chain);
        return chain;
    }

    private static void assertRejected(ApproovPinningInterceptor interceptor, String host,
            okhttp3.Handshake handshake) throws IOException {
        try {
            check(interceptor, host, handshake);
            fail(host + " must be rejected: its pin does not match the certificate");
        } catch (javax.net.ssl.SSLPeerUnverifiedException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(host));
        }
    }

    /**
     * The mispinned host requested first, on a fresh interceptor,
     * is rejected.
     */
    @Test
    public void testMispinnedHostRequestedFirstIsRejected() throws Exception {
        reinitializeServiceWithTargetHost("");
        okhttp3.tls.HeldCertificate cert = sharedCertificate();
        ApproovPinningInterceptor interceptor = interceptorWith(new ApproovPinningInterceptor(), sharedCertificatePinner(cert));
        assertRejected(interceptor, BAD_HOST, handshakeFor(cert));
    }

    /**
     * On its own fresh interceptor, a correctly pinned host that shares the
     * certificate passes first, then the mispinned host over the same handshake
     * is still rejected.
     */
    @Test
    public void testMispinnedHostAfterSharedCertificateHostIsRejected() throws Exception {
        reinitializeServiceWithTargetHost("");
        okhttp3.tls.HeldCertificate cert = sharedCertificate();
        okhttp3.Handshake handshake = handshakeFor(cert);
        ApproovPinningInterceptor interceptor = interceptorWith(new ApproovPinningInterceptor(), sharedCertificatePinner(cert));
        assertNotNull(check(interceptor, GOOD_HOST, handshake).proceededWith);
        assertRejected(interceptor, BAD_HOST, handshake);
        // and over an equal but distinct handshake object, as a new connection carries
        assertRejected(interceptor, BAD_HOST, okhttp3.Handshake.get(handshake.tlsVersion(), handshake.cipherSuite(),
            handshake.peerCertificates(), handshake.localCertificates()));
    }

    /**
     * After a pin rebuild the next request to a host is checked
     * against the new pins, even over a handshake that passed against the old ones.
     */
    @Test
    public void testPinRebuildAppliesToTheNextCheckOnTheSameHandshake() throws Exception {
        reinitializeServiceWithTargetHost("");
        okhttp3.tls.HeldCertificate cert = sharedCertificate();
        okhttp3.Handshake handshake = handshakeFor(cert);
        ApproovPinningInterceptor interceptor = interceptorWith(new ApproovPinningInterceptor(), sharedCertificatePinner(cert));
        assertNotNull(check(interceptor, GOOD_HOST, handshake).proceededWith);
        // the pins rotate: the good host's pin no longer matches
        interceptor.buildPins();
        interceptorWith(interceptor, new CertificatePinner.Builder().add(GOOD_HOST, NON_MATCHING_PIN).build());
        assertRejected(interceptor, GOOD_HOST, handshake);
    }

    /**
     * Every connection check consults the current pins: a second request over the
     * same handshake to the same host is evaluated exactly like the first.
     */
    @Test
    public void testPinsAreEvaluatedOnEveryCheck() throws Exception {
        reinitializeServiceWithTargetHost("");
        okhttp3.tls.HeldCertificate cert = sharedCertificate();
        okhttp3.Handshake handshake = handshakeFor(cert);
        AtomicInteger reads = new AtomicInteger();
        ApproovPinningInterceptor interceptor = interceptorWith(new ApproovPinningInterceptor() {
            @Override synchronized CertificatePinner getCertificatePinner() {
                reads.incrementAndGet();
                return super.getCertificatePinner();
            }
        }, sharedCertificatePinner(cert));
        check(interceptor, GOOD_HOST, handshake);
        int first = reads.getAndSet(0);
        check(interceptor, GOOD_HOST, handshake);
        assertEquals("the second check must evaluate the pins like the first", first, reads.get());
        assertTrue(first >= 2);
    }

    // connection carrying a fixed TLS handshake
    private static class HandshakeConnection implements okhttp3.Connection {
        private final okhttp3.Handshake handshake;
        HandshakeConnection(okhttp3.Handshake handshake) { this.handshake = handshake; }
        @Override public okhttp3.Route route() { throw new UnsupportedOperationException(); }
        @Override public java.net.Socket socket() { return new java.net.Socket(); }
        @Override public okhttp3.Handshake handshake() { return handshake; }
        @Override public okhttp3.Protocol protocol() { return okhttp3.Protocol.HTTP_2; }
    }

    // chain whose connection carries a fixed TLS handshake
    private static class HandshakeChain extends NoHandshakeChain {
        private final okhttp3.Connection connection;
        HandshakeChain(Request request, okhttp3.Handshake handshake) {
            super(request);
            this.connection = new HandshakeConnection(handshake);
        }
        @Override public okhttp3.Connection connection() { return connection; }
    }

    // chain with no connection, standing for a cleartext attempt
    private static class NoHandshakeChain implements okhttp3.Interceptor.Chain {
        private final Request request;
        Request proceededWith;
        NoHandshakeChain(Request request) { this.request = request; }
        @Override public Request request() { return request; }
        @Override public Response proceed(Request request) {
            proceededWith = request;
            return new Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK").build();
        }
        @Override public okhttp3.Connection connection() { return null; }
        @Override public okhttp3.Call call() { throw new UnsupportedOperationException(); }
        @Override public int connectTimeoutMillis() { return 0; }
        @Override public okhttp3.Interceptor.Chain withConnectTimeout(int t, java.util.concurrent.TimeUnit u) { return this; }
        @Override public int readTimeoutMillis() { return 0; }
        @Override public okhttp3.Interceptor.Chain withReadTimeout(int t, java.util.concurrent.TimeUnit u) { return this; }
        @Override public int writeTimeoutMillis() { return 0; }
        @Override public okhttp3.Interceptor.Chain withWriteTimeout(int t, java.util.concurrent.TimeUnit u) { return this; }
    }

    // ==================================================================================
    // Test Helpers
    // ==================================================================================

    // A client on which the given interceptor runs at the network layer before
    // Approov's own network interceptors, which come last: it stands for a
    // hold between protection and transmission (a doze period, an app queue), which
    // happens before the request reaches the network interceptors.
    private static OkHttpClient clientHeldBy(okhttp3.Interceptor hold) {
        OkHttpClient.Builder builder = ApproovService.getOkHttpClient().newBuilder();
        builder.networkInterceptors().add(0, hold);
        return builder.build();
    }

    // sends a request and returns the echoed reply
    private JSONObject send(OkHttpClient client, Request request) throws Exception {
        try (Response response = client.newCall(request).execute()) {
            assertEquals(200, response.code());
            return new JSONObject(response.body().string());
        }
    }

    // installs a client builder that counts the attempts reaching the network
    private AtomicInteger countNetworkAttempts() {
        AtomicInteger attempts = new AtomicInteger();
        ApproovService.setOkHttpClientBuilder(new OkHttpClient.Builder().addNetworkInterceptor(chain -> {
            attempts.incrementAndGet();
            return chain.proceed(chain.request());
        }));
        return attempts;
    }

    // sends a request to the protected target with the given token fetch status
    // directive (none if null) and returns the echoed reply
    private JSONObject sendWithTokenStatus(String status) throws Exception {
        if (status != null)
            setDirective("{\"operation\": \"fetchApproovToken\", \"response\": {\"status\": \"" + status + "\"}}");
        return send(ApproovService.getOkHttpClient(), new Request.Builder().url(getTargetURL()).get().build());
    }

    // asserts that a token fetch status aborts the request with exactly the given
    // exception type, carrying the status, and that nothing reaches the network
    private void assertTokenFetchAborts(String status, Class<?> expected, AtomicInteger attempts) throws Exception {
        int before = attempts.get();
        setDirective("{\"operation\": \"fetchApproovToken\", \"response\": {\"status\": \"" + status + "\"}}");
        try (Response response = ApproovService.getOkHttpClient().newCall(
                new Request.Builder().url(getTargetURL()).get().build()).execute()) {
            fail(status + " must abort the request, got " + response.code());
        } catch (IOException e) {
            assertEquals(status + ": " + e, expected, e.getClass());
            assertEquals(status, Approov.TokenFetchStatus.valueOf(status),
                ((ApproovFetchStatusException) e).getTokenFetchStatus());
            // the full URL of the request, as OkHttp spells it
            assertEquals("Approov token fetch for " + okhttp3.HttpUrl.get(getTargetURL()) + ": " + status,
                e.getMessage());
        }
        assertEquals(status + " must send nothing", before, attempts.get());
    }

    // asserts that the token header carries a token from a successful fetch (a JWT), not
    // an empty token or a status name
    private void assertSuccessToken(String what, JSONObject reply) throws Exception {
        String token = getHeader(reply, "Approov-Token");
        assertNotNull(what + ": token header missing", token);
        assertEquals(what + ": not a token: " + token, 3, token.split("\\.").length);
    }

    private void assertSuccessToken(JSONObject reply) throws Exception {
        assertSuccessToken("success", reply);
    }

    // asserts the install signature only, the default of generateDefaultSignatureParametersFactory()
    private void assertInstallSignatureOnly(JSONObject reply) throws Exception {
        String signatureInput = getHeader(reply, "Signature-Input");
        assertNotNull("Signature-Input missing", signatureInput);
        assertTrue(signatureInput, signatureInput.startsWith("install=("));
        assertFalse(signatureInput, signatureInput.contains("account="));
        assertTrue(signatureInput, signatureInput.contains("alg=\"ecdsa-p256-sha256\""));
        assertFalse(signatureInput, signatureInput.contains("hmac-sha256"));
        String signature = getHeader(reply, "Signature");
        assertNotNull("Signature missing", signature);
        assertTrue(signature, signature.startsWith("install=:"));
        assertFalse(signature, signature.contains("account="));
        assertTrue(signature, signature.endsWith(":"));
    }

    // asserts both signatures as members of the same dictionaries
    private void assertBothSignatures(JSONObject reply) throws Exception {
        String signatureInput = getHeader(reply, "Signature-Input");
        assertNotNull("Signature-Input missing", signatureInput);
        assertTrue(signatureInput, signatureInput.startsWith("install=("));
        assertTrue(signatureInput, signatureInput.contains(", account=("));
        assertTrue(signatureInput, signatureInput.contains("alg=\"ecdsa-p256-sha256\""));
        assertTrue(signatureInput, signatureInput.contains("alg=\"hmac-sha256\""));
        String signature = getHeader(reply, "Signature");
        assertNotNull("Signature missing", signature);
        assertTrue(signature, signature.startsWith("install=:"));
        assertTrue(signature, signature.contains(", account=:"));
        assertTrue(signature, signature.endsWith(":"));
    }

    private String getTargetURL() {
        return requiredEnvironmentURL("TESTING_REPLY_URL");
    }
 
    // a host not added to Approov: the mini-SDK reports UNKNOWN_URL for it (the
    // environment variable name is historical: UNPROTECTED_URL is the status of a
    // secrets-only API)
    private String getNotInApproovURL() {
        return requiredEnvironmentURL("TESTING_REPLY_URL_UNPROTECTED");
    }

    // the echo worker URLs come from the environment, as CI sets them; a local run must set them too
    private static String requiredEnvironmentURL(String name) {
        String url = System.getenv(name);
        if ((url == null) || url.isEmpty())
            throw new IllegalStateException(name + " is not set: export the echo worker URL before running the tests");
        return url;
    }
 
    private String getTargetHost() {
        String url = getTargetURL();
        return url.replace("https://", "").split("/")[0];
    }
 
    private void reinitializeServiceWithTargetHost(String scenarioBody) throws Exception {
        String targetHost = getTargetHost();
        String domainsJson = "\"protectedDomains\": [\"" + targetHost + "\"]," +
                             "\"pins\": {\"public-key-sha256\": {\"" + targetHost + "\": []}}";
        String fullBody = scenarioBody.isEmpty() ? domainsJson : domainsJson + ", " + scenarioBody;
 
        reinitializeService(scenarioJson(uniqueCaseName("target-host"), fullBody));
    }
 
    private void reinitializeService(String scenarioJson) {
        AttesterProxyController.reset();
        if (scenarioJson != null) {
            AttesterProxyController.loadScenarioJson(scenarioJson);
        }
        ApproovService.initialize(context, validInitialConfig, "reinit");
    }
 
    private void setDirective(String json) {
        AttesterProxyController.setNextAttestationDirectiveJson(json);
    }

    private String uniqueCaseName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().toLowerCase();
    }
 
    private String scenarioJson(String caseName, String body) {
        return "{" +
            "  \"activeCase\": \"" + caseName + "\"," +
            "  \"cases\": {" +
            "    \"" + caseName + "\": {" +
            "      " + body + "" +
            "    }" +
            "  }" +
            "}";
    }
 
    private String getHeader(JSONObject reply, String key) throws Exception {
        if (!reply.has("headers")) return null;
        JSONObject headers = reply.getJSONObject("headers");
        String lowerKey = key.toLowerCase();
        if (headers.has(lowerKey)) {
            Object val = headers.get(lowerKey);
            if (val instanceof String) return (String) val;
            if (val instanceof org.json.JSONArray) {
                org.json.JSONArray arr = (org.json.JSONArray) val;
                if (arr.length() > 0) return arr.getString(0);
            }
        }
        if (headers.has(key)) {
            Object val = headers.get(key);
            if (val instanceof String) return (String) val;
            if (val instanceof org.json.JSONArray) {
                org.json.JSONArray arr = (org.json.JSONArray) val;
                if (arr.length() > 0) return arr.getString(0);
            }
        }
        return null;
    }
 
    // all values of a header from the echo reply, whether reported as an array or as a
    // single comma joined field
    private List<String> getHeaderValues(JSONObject reply, String key) throws Exception {
        JSONObject headers = reply.getJSONObject("headers");
        Object val = headers.has(key.toLowerCase()) ? headers.get(key.toLowerCase()) : headers.opt(key);
        List<String> values = new ArrayList<>();
        if (val instanceof org.json.JSONArray) {
            org.json.JSONArray arr = (org.json.JSONArray) val;
            for (int i = 0; i < arr.length(); i++) values.add(arr.getString(i));
        } else if (val instanceof String) {
            for (String part : ((String) val).split(",")) values.add(part.trim());
        }
        return values;
    }

    private JSONObject decodeJWTBody(String jwt) throws Exception {
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) return null;
        byte[] bytes = Base64.getUrlDecoder().decode(parts[1]);
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }
 
    private String sha256Base64(String data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hash);
    }
}
