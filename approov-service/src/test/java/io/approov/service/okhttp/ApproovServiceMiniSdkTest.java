package io.approov.service.okhttp;
 
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import com.criticalblue.minisdk.testing.AttesterProxyController;
import okhttp3.CertificatePinner;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
 
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.criticalblue.approovsdk.Approov;
 
import static org.junit.Assert.*;
 
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class ApproovServiceMiniSdkTest {
    private final String validInitialConfig = "#cb-ivol#mAxOF0ekJUOC36J5XWmVmVipOcUoEdMjhPSp2FVtyTo=";
    private Context context;
 
    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        AttesterProxyController.reset();
        ApproovService.initialize(context, validInitialConfig, "reinit-okhttp-tests");
    }
 
    @After
    public void tearDown() {
        AttesterProxyController.reset();
    }
 
    @Test
    public void testInitializeIgnoresSameConfigAndRejectsDifferentConfig() {
        // Re-init with same config should be ignored (no exception)
        ApproovService.initialize(context, validInitialConfig);
 
        // Re-init with different config should throw illegal state
        String differentConfig = "#stg1006#aprv2stg-attest.api.approov.io#https://dev.approoval.com/token#dpcv6jv45r6LGC4E6ZXSMLhBVLrrhAoDcjizU/t9/Eg=";
        try {
            ApproovService.initialize(context, differentConfig);
            fail("Expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertEquals("ApproovService layer is already initialized.", e.getMessage());
        }
    }
 
    @Test
    public void testPrecheckTreatsUnknownKeyAsSuccess() throws ApproovException {
        ApproovService.precheck();
    }
 
    @Test
    public void testGetDeviceIDReturnsMiniSDKDeviceID() throws ApproovException {
        assertEquals("daIvmEWBA2gvZny7a/RC/w==", ApproovService.getDeviceID());
    }
 
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

    @Test
    public void testFetchTokenThrowsNetworkExceptionForMitmDetected() throws Exception {
        reinitializeServiceWithTargetHost("");
        setDirective("{" +
            "  \"operation\": \"fetchApproovToken\"," +
            "  \"response\": {" +
            "    \"status\": \"MITM_DETECTED\"" +
            "  }" +
            "}");

        try {
            ApproovService.fetchToken(getTargetURL());
            fail("Expected ApproovNetworkException");
        } catch (ApproovNetworkException e) {
            assertTrue(e.getMessage().contains("fetchToken: MITM_DETECTED"));
        }
    }

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
 
    @Test
    public void testFetchCustomJWTReturnsSignedJWT() throws Exception {
        String jwt = ApproovService.fetchCustomJWT("{\"role\":\"tester\"}");
        assertNotNull(jwt);
        JSONObject payload = decodeJWTBody(jwt);
 
        assertEquals("tester", payload.getString("role"));
        assertFalse(payload.has("exp"));
        assertFalse(payload.has("did"));
    }

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

    @Test
    public void testFetchCustomJWTBadPayloadThrowsApproovException() throws Exception {
        try {
            ApproovService.fetchCustomJWT("not-json");
            fail("Expected ApproovException");
        } catch (ApproovException e) {
            // Should throw due to IllegalArgumentException from SDK or ähnliches
        }
    }
 
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
 
    @Test
    public void testUpdateRequestNoApproovServiceProceedsWithoutToken() throws Exception {
        reinitializeServiceWithTargetHost("");
        setDirective("{" +
            "  \"operation\": \"fetchApproovToken\"," +
            "  \"response\": {" +
            "    \"status\": \"NO_APPROOV_SERVICE\"" +
            "  }" +
            "}");
 
        OkHttpClient client = ApproovService.getOkHttpClient();
        Request request = new Request.Builder().url(getTargetURL()).build();
        try (Response response = client.newCall(request).execute()) {
            JSONObject reply = new JSONObject(response.body().string());
            assertNull(getHeader(reply, "Approov-Token"));
            assertNull(getHeader(reply, "Approov-TraceID"));
        }
    }
 
    @Test
    public void testUpdateRequestInstallMessageSigningAddsSignatureHeaders() throws Exception {
        reinitializeServiceWithTargetHost("");
        
        ApproovDefaultMessageSigning.SignatureParametersFactory factory = 
            ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()
            .setUseInstallMessageSigning();
        ApproovService.setServiceMutator(new ApproovDefaultMessageSigning().setDefaultFactory(factory));
 
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
            assertTrue(signature.startsWith("install="));
            assertFalse(signature.contains("account="));
        }
        
        Request unprotectedRequest = new Request.Builder().url(getUnprotectedURL()).get().build();
        try (Response response = client.newCall(unprotectedRequest).execute()) {
            JSONObject reply = new JSONObject(response.body().string());
            assertNull(getHeader(reply, "Approov-Token"));
            assertNull(getHeader(reply, "Signature"));
            assertNull(getHeader(reply, "Signature-Input"));
        }
    }
 
    @Test
    public void testUpdateRequestAccountMessageSigningAddsSignatureHeaders() throws Exception {
        reinitializeServiceWithTargetHost("");
        
        ApproovDefaultMessageSigning.SignatureParametersFactory factory = 
            ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()
            .setUseAccountMessageSigning();
        ApproovService.setServiceMutator(new ApproovDefaultMessageSigning().setDefaultFactory(factory));
 
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
            assertTrue(signature.startsWith("account="));
            assertFalse(signature.contains("install="));
        }
    }
 
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
 
    private String getTargetURL() {
        String url = System.getenv("TESTING_REPLY_URL");
        return (url != null) ? url : "https://replay.ivol.workers.dev";
    }
 
    private String getUnprotectedURL() {
        String url = System.getenv("TESTING_REPLY_URL_UNPROTECTED");
        return (url != null) ? url : "https://replay-unprotected.ivol.workers.dev";
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

    @Test
    public void testInstallMessageSigningFailsGracefullyIfKeyGenerationFails() throws Exception {
        String targetHost = getTargetHost();
        String body = "\"protectedDomains\": [\"" + targetHost + "\"]," +
                       "\"simulateInstallKeyFailure\": true";
        reinitializeService(scenarioJson(uniqueCaseName("no-install-key"), body));
        
        ApproovDefaultMessageSigning.SignatureParametersFactory factory =
            ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory().setUseInstallMessageSigning();
        ApproovService.setServiceMutator(new ApproovDefaultMessageSigning().setDefaultFactory(factory));

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

    @Test
    public void testDigestBodyAppendedForPOSTPUTPATCHRequests() throws Exception {
        reinitializeServiceWithTargetHost("");
        
        ApproovDefaultMessageSigning.SignatureParametersFactory factory = ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()
            .setUseInstallMessageSigning();
        factory.setBodyDigestConfig(ApproovDefaultMessageSigning.DIGEST_SHA256, true);
        
        ApproovService.setServiceMutator(new ApproovDefaultMessageSigning().setDefaultFactory(factory));

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

    @Test
    public void testServiceMutatorOverridesFailClosedBehavior() throws Exception {
        reinitializeServiceWithTargetHost("");
        
        setDirective("{" +
            "  \"operation\": \"fetchApproovToken\"," +
            "  \"response\": {" +
            "    \"status\": \"MITM_DETECTED\"" +
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
        }
    }

    @Test
    public void testPinningAcceptAny() throws Exception {
        reinitializeServiceWithTargetHost("");
        ApproovService.setServiceMutator(ApproovServiceMutator.DEFAULT);
        
        AttesterProxyController.setNextPinningDirectiveJson("{\"operation\": \"getPins\", \"acceptAny\": true}");
        
        OkHttpClient client = ApproovService.getOkHttpClient();
        Request request = new Request.Builder().url(getTargetURL()).get().build();
        
        try (Response response = client.newCall(request).execute()) {
            assertEquals(200, response.code());
        }
    }
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
}
