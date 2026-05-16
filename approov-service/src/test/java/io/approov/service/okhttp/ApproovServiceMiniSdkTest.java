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

/**
 * Integration tests for the ApproovService OkHttp service layer.
 *
 * Tests are organized to match the sections defined in TESTING_REQUIREMENTS.md
 * from the core-service-layers-testing repository. Each test includes a comment
 * referencing the requirement(s) it covers.
 *
 * @see <a href="https://github.com/approov/core-service-layers-testing/blob/main/TESTING_REQUIREMENTS.md">TESTING_REQUIREMENTS.md</a>
 */
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
        ApproovService.setServiceMutator(ApproovServiceMutator.DEFAULT);
        AttesterProxyController.reset();
    }

    // ==================================================================================
    // SECTION 1: Initialization
    // TESTING_REQUIREMENTS.md §1
    // ==================================================================================

    /**
     * §1 Same Config Re-initialization / Different Config Re-initialization
     *
     * Re-initialize with the same config string should not fail.
     * Re-initialize with a different config string should fail with an exception.
     */
    @Test
    public void testInitializeIgnoresSameConfigAndRejectsDifferentConfig() {
        // Re-init with same config should be ignored (no exception)
        ApproovService.initialize(context, validInitialConfig);
 
        // Re-init with different config should throw illegal state
        String differentConfig = "#cb-other#mAxOF0ekJUOC36J5XWmVmVipOcUoEdMjhPSp2FVtyTo=";
        try {
            ApproovService.initialize(context, differentConfig);
            fail("Expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertEquals("ApproovService layer is already initialized.", e.getMessage());
        }
    }

    /**
     * §1 Empty Configuration (Valid Comment) / Empty Configuration (Empty Comment)
     *
     * Initializing with an empty config should keep the service layer initialized
     * while making the returned client behave like a plain OkHttp client with no
     * Approov mutations.
     */
    @Test
    public void testInitializeWithEmptyConfigBuildsPlainClient() throws Exception {
        reinitializeService(scenarioJson(uniqueCaseName("empty-config"),
            "\"protectedDomains\": [\"" + getTargetHost() + "\"]"));
        ApproovService.initialize(context, "", "reinit-empty-config");

        assertTrue(ApproovService.isInitialized());

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
     * §1 Empty Configuration then Valid Configuration
     *
     * Initializing first with an empty config should allow a later valid config to
     * enable Approov protection at runtime.
     */
    @Test
    public void testInitializeWithEmptyConfigCanLaterEnableApproov() throws Exception {
        reinitializeService(scenarioJson(uniqueCaseName("empty-then-valid"),
            "\"protectedDomains\": [\"" + getTargetHost() + "\"]"));
        ApproovService.initialize(context, "", "reinit-empty-config");

        OkHttpClient plainClient = ApproovService.getOkHttpClient();
        try (Response response = plainClient.newCall(new Request.Builder().url(getTargetURL()).build()).execute()) {
            assertTrue(response.isSuccessful());
            JSONObject reply = new JSONObject(response.body().string());
            assertNull(getHeader(reply, "Approov-Token"));
            assertNull(getHeader(reply, "Approov-TraceID"));
        }

        assertTrue(ApproovService.isInitialized());
        assertFalse(ApproovService.isApproovEnabled());

        ApproovService.initialize(context, validInitialConfig);

        assertTrue(ApproovService.isInitialized());
        assertTrue(ApproovService.isApproovEnabled());

        OkHttpClient protectedClient = ApproovService.getOkHttpClient();
        try (Response response = protectedClient.newCall(new Request.Builder().url(getTargetURL()).build()).execute()) {
            assertTrue(response.isSuccessful());
            JSONObject reply = new JSONObject(response.body().string());
            assertNotNull(getHeader(reply, "Approov-Token"));
        }
    }

    // ==================================================================================
    // SECTION 2: Request Processing & Token Behaviors
    // TESTING_REQUIREMENTS.md §2
    // ==================================================================================

    /**
     * §2 Precheck Evaluation
     *
     * A call to precheck() should trigger a secure string fetch and evaluate
     * UNKNOWN_KEY as a success path.
     */
    @Test
    public void testPrecheckTreatsUnknownKeyAsSuccess() throws ApproovException {
        ApproovService.precheck();
    }

    /**
     * §2 (Supporting test)
     *
     * Verifies the Mini SDK returns a stable device ID for the test environment.
     */
    @Test
    public void testGetDeviceIDReturnsMiniSDKDeviceID() throws ApproovException {
        assertEquals("daIvmEWBA2gvZny7a/RC/w==", ApproovService.getDeviceID());
    }

    /**
     * §2 Protected Request Processing / Token Binding Hash
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
     * §2 Protected Request Processing
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
     * §2 Missing Artifacts Fallback
     *
     * When the Approov service is unavailable (NO_APPROOV_SERVICE), the request
     * should proceed without an Approov token or trace ID.
     */
    @Test
    public void testUpdateRequestNoApproovServiceProceedsWithEmptyHeaders() throws Exception {
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
            assertEquals("Approov-Token", getHeader(reply, "Approov-Token"));
            assertEquals("", getHeader(reply, "Approov-TraceID"));
        }
    }

    /**
     * §2 Exclusion URL Matching
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
     * §2 Token Fallback Status (error status mapping)
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
     * §2 Token Fallback Status (error status mapping)
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
     * §2 Token Fallback Status (error status mapping)
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
     * §2 Token Fallback Status (error status mapping)
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
     * §2 Token Fallback Status (error status mapping)
     *
     * MITM_DETECTED → ApproovNetworkException
     */
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

    /**
     * §2 Token Fallback Status (error status mapping)
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
     * §2 Token Fallback Status (error status mapping)
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
    // TESTING_REQUIREMENTS.md §3
    // ==================================================================================

    /**
     * §3 Custom Mutators / Decision Overrides
     *
     * Overriding the default fail-closed behavior for MITM_DETECTED via a custom
     * ApproovServiceMutator allows the request to proceed without a token.
     */
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

    // ==================================================================================
    // SECTION 4: Pinning Configuration & Scenarios
    // TESTING_REQUIREMENTS.md §4
    // ==================================================================================

    /**
     * §4 Generate Valid Pins / Generate Invalid Pins
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
     * §4 Accept Any Pins
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
     * §4 Dynamic Pinning Updates
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

    // ==================================================================================
    // SECTION 5: Message Signing
    // TESTING_REQUIREMENTS.md §5
    // ==================================================================================

    /**
     * §5 Install Signature Success / Single Signature Application
     * §2 Unprotected Request Processing
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

    /**
     * §5 Account Message Signing
     *
     * Account message signing produces the expected signature headers (account=...).
     */
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

    /**
     * §5 Install Key Generation Failure / Signing Failure Fallback
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

    /**
     * §5 Digest Body Application
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

    // ==================================================================================
    // SECTION 6: Secure Strings & Custom JWT
    // TESTING_REQUIREMENTS.md §6
    // ==================================================================================

    /**
     * §6 Valid Secure String Key
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
     * §6 Non-existent Secure String Key
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
     * §6 Empty Secure String Key
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
     * §6 Nil Secure String Key
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
     * §6 Custom JWT Fetch
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
     * §6 Custom JWT Fetch (18KB payload)
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
     * §6 Custom JWT Fetch (disabled)
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
     * §6 Custom JWT Fetch (malformatted JSON)
     *
     * Fetching a Custom JWT with a malformatted JSON string throws an exception.
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
    // Test Helpers
    // ==================================================================================

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
}
