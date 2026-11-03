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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.criticalblue.approovsdk.Approov;
import com.criticalblue.minisdk.testing.AttesterProxyController;
import com.example.app.PathSigning;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import io.approov.service.okhttp.ApproovDefaultMessageSigning.OkHttpComponentProvider;
import io.approov.service.okhttp.ApproovDefaultMessageSigning.RequiredBodyDigestException;
import io.approov.service.okhttp.ApproovDefaultMessageSigning.SignatureParametersFactory;
import io.approov.util.okhttp.sig.ComponentProvider;
import io.approov.util.okhttp.sig.SignatureParameters;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;

/**
 * Message signing that chooses its signed components by the request path, written in an app package with the
 * 3.5.8 API: com.example.app.PathSigning subclasses ApproovDefaultMessageSigning and overrides its protected
 * buildSignatureParameters, handing a download request to a second signer with a ready-made default factory.
 * Per host factories (putHostFactory) apply to their host only. A bare factory, like the default factory,
 * produces the install signature only, and the algorithms always come from the installed signer's factory for
 * the host. The signatures are checked against a signature base rebuilt from the request the server received,
 * with the mini-SDK test keys.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class FactoryDelegation370Test {
    // the mini-SDK's default test account message signing key, a test key only
    private static final String MINI_SDK_ACCOUNT_KEY_BASE64 =
            "ajNBV3k2b0ZHSGczOG5ZVG05X0tCaFpVeUR2TFh5S2ZlZHQ4YkpHUXh6b3MzQzVfUVhwZ3B5M0o3eWtyVFM2Rw==";

    private final MockWebServer server = new MockWebServer();
    private RequestPathProbe probe;
    private HandshakeCertificates clientHandshake;

    @Before
    public void setUp() throws Exception {
        // one server reached as localhost (the delegating host) and as 127.0.0.1 (a second host), both
        // protected by Approov
        HeldCertificate certificate = new HeldCertificate.Builder()
                .addSubjectAlternativeName("localhost")
                .addSubjectAlternativeName("127.0.0.1")
                .build();
        server.useHttps(new HandshakeCertificates.Builder().heldCertificate(certificate).build()
                .sslSocketFactory(), false);
        server.start(InetAddress.getByName("localhost"), 0);
        clientHandshake = new HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate())
                .build();
        AttesterProxyController.reset();
        AttesterProxyController.loadScenarioJson("{\"activeCase\": \"delegation\", \"cases\": {\"delegation\": {"
                + "\"protectedDomains\": [\"localhost\", \"127.0.0.1\"],"
                + "\"pins\": {\"public-key-sha256\": {\"localhost\": [], \"127.0.0.1\": []}}}}}");
        ApproovService.reset();
        ApproovService.initialize(androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                LocalHttpsFixture.CONFIG, "reinit-factory-delegation");
        ApproovService.setOkHttpClientBuilder(new okhttp3.OkHttpClient.Builder()
                .sslSocketFactory(clientHandshake.sslSocketFactory(), clientHandshake.trustManager())
                .protocols(java.util.Collections.singletonList(Protocol.HTTP_1_1)));
        probe = new RequestPathProbe(null);
    }

    @After
    public void tearDown() throws Exception {
        probe.close();
        server.shutdown();
        AttesterProxyController.reset();
        ApproovService.reset();
    }

    private Request get(String host, String path) {
        HttpUrl url = server.url(path).newBuilder().host(host).build();
        return new Request.Builder().url(url).get().build();
    }

    private RecordedRequest send(Request request) throws Exception {
        server.enqueue(new MockResponse().setBody("ok"));
        RequestPathProbe.assertOk(request.url().encodedPath(), probe.execute(request));
        return server.takeRequest(5, TimeUnit.SECONDS);
    }

    // the factory for download requests, whose only base component is the method
    private static SignatureParametersFactory methodOnly() {
        return ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory(
                new SignatureParameters().addComponentIdentifier(ComponentProvider.DC_METHOD));
    }

    private static SignatureParametersFactory standard() {
        return ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory();
    }

    // ==================================================================================
    // the path rule on the wire
    // ==================================================================================

    @Test
    public void downloadPathIsSignedWithoutTargetUriAndOtherPathsWithIt() throws Exception {
        ApproovService.setServiceMutator(new PathSigning(methodOnly()).setDefaultFactory(standard()));

        RecordedRequest download = send(get("localhost", "/download/x?a=b"));
        for (Map.Entry<String, List<String>> member : verifyInstallSignature(download).entrySet()) {
            assertFalse(member.getKey(), member.getValue().contains("@target-uri"));
            assertTrue(member.getKey(), member.getValue().contains("@method"));
            assertTrue(member.getKey(), member.getValue().contains("approov-token"));
        }

        RecordedRequest other = send(get("localhost", "/other"));
        for (Map.Entry<String, List<String>> member : verifyInstallSignature(other).entrySet())
            assertTrue(member.getKey(), member.getValue().contains("@target-uri"));
    }

    @Test
    public void aHostFactoryLeavesASecondHostOnTheDefaultFactory() throws Exception {
        ApproovService.setServiceMutator(new ApproovDefaultMessageSigning().setDefaultFactory(standard())
                .putHostFactory("localhost", methodOnly()));

        // the same path on the other host still covers the target URI
        RecordedRequest other = send(get("127.0.0.1", "/download/x"));
        for (Map.Entry<String, List<String>> member : verifyInstallSignature(other).entrySet())
            assertTrue(member.getKey(), member.getValue().contains("@target-uri"));

        // control: the host factory applies to its own host
        RecordedRequest own = send(get("localhost", "/download/x"));
        for (Map.Entry<String, List<String>> member : verifyInstallSignature(own).entrySet())
            assertFalse(member.getKey(), member.getValue().contains("@target-uri"));
    }

    @Test
    public void otherPathsSignLikeTheStandardFactory() throws Exception {
        ApproovService.setServiceMutator(new PathSigning(methodOnly()).setDefaultFactory(standard()));
        Map<String, List<String>> delegated = verifyInstallSignature(send(get("localhost", "/other")));

        ApproovService.setServiceMutator(new ApproovDefaultMessageSigning().setDefaultFactory(standard()));
        Map<String, List<String>> plain = verifyInstallSignature(send(get("localhost", "/other")));

        assertEquals(plain, delegated);
        // the default components cover no status header, as there is none
        assertEquals(Arrays.asList("@method", "@target-uri", "approov-token", "approov-traceid"),
                plain.get("install"));
    }

    // ==================================================================================
    // algorithms come from the installed signer's factory
    // ==================================================================================

    @Test
    public void algorithmsComeFromTheInstalledFactoryNotTheDelegate() throws Exception {
        SignatureParametersFactory both = methodOnly().setUseInstallAndAccountMessageSigning();

        // the installed signer's factory keeps the default algorithm, so the delegate configured for both
        // signs once
        ApproovService.setServiceMutator(new PathSigning(both).setDefaultFactory(standard()));
        RecordedRequest request = send(get("localhost", "/download/x"));
        assertEquals(Arrays.asList("install"), new ArrayList<>(members(request.getHeader("Signature-Input")).keySet()));
        verifySignatures(request);

        // configured for both itself, the installed signer signs twice with the delegate's parameters
        ApproovService.setServiceMutator(new PathSigning(methodOnly())
                .setDefaultFactory(standard().setUseInstallAndAccountMessageSigning()));
        request = send(get("localhost", "/download/x"));
        for (Map.Entry<String, List<String>> member : verifyBothSignatures(request).entrySet())
            assertFalse(member.getKey(), member.getValue().contains("@target-uri"));
    }

    // ==================================================================================
    // behaviour that must not change
    // ==================================================================================

    // a signer whose per request override returns what the given factory override returns, and a signer with
    // a host factory overriding buildSignatureParameters itself
    private static ApproovDefaultMessageSigning[] overriding(java.util.function.Supplier<SignatureParameters> body) {
        ApproovDefaultMessageSigning signerOverride = new ApproovDefaultMessageSigning() {
            @Override
            protected SignatureParameters buildSignatureParameters(OkHttpComponentProvider provider,
                    ApproovRequestMutations changes) {
                return body.get();
            }
        }.setDefaultFactory(standard());
        ApproovDefaultMessageSigning factoryOverride = new ApproovDefaultMessageSigning().setDefaultFactory(standard())
                .putHostFactory("localhost", new SignatureParametersFactory() {
                    @Override
                    protected SignatureParameters buildSignatureParameters(OkHttpComponentProvider provider,
                            ApproovRequestMutations changes) {
                        return body.get();
                    }
                });
        return new ApproovDefaultMessageSigning[] {signerOverride, factoryOverride};
    }

    @Test
    public void aNullReturnLeavesTheRequestUnsigned() throws Exception {
        for (ApproovDefaultMessageSigning signing : overriding(() -> null)) {
            ApproovService.setServiceMutator(signing);
            RecordedRequest request = send(get("localhost", "/other"));
            assertNull(request.getHeader("Signature"));
            assertNull(request.getHeader("Signature-Input"));
            assertNotNull("the token header is still sent", request.getHeader("Approov-Token"));
        }
    }

    @Test
    public void aThrowingOverrideLeavesTheRequestUnsigned() throws Exception {
        for (ApproovDefaultMessageSigning signing : overriding(() -> {
            throw new IllegalStateException("custom factory failure");
        })) {
            ApproovService.setServiceMutator(signing);
            RecordedRequest request = send(get("localhost", "/other"));
            assertNull(request.getHeader("Signature"));
            assertNull(request.getHeader("Signature-Input"));
            assertNotNull("the token header is still sent", request.getHeader("Approov-Token"));
        }
    }

    @Test
    public void aRequiredBodyDigestThatCannotBeGeneratedStillFailsThroughADelegate() throws Exception {
        SignatureParametersFactory required = standard()
                .setBodyDigestConfig(ApproovDefaultMessageSigning.DIGEST_SHA256, true);
        ApproovService.setServiceMutator(new PathSigning(required).setDefaultFactory(standard()));

        // a GET has no body to digest, and the signing decision is not reported as a hook failure
        ApproovException e = RequestPathProbe.assertFailure("download",
                probe.execute(get("localhost", "/download/x")), ApproovException.class);
        assertEquals("Message signing: required body digest not generated", e.getMessage());
        assertTrue("cause " + e.getCause(), e.getCause() instanceof RequiredBodyDigestException);
        assertEquals("a failed request never reaches the network", 0, server.getRequestCount());

        // the other path does not require one
        send(get("localhost", "/other"));
    }

    // ==================================================================================
    // the independent verifier
    // ==================================================================================

    // verifies the install signature only, the default, against a base rebuilt from the received request,
    // returning the covered components
    private Map<String, List<String>> verifyInstallSignature(RecordedRequest request) throws Exception {
        Map<String, List<String>> covered = verifySignatures(request);
        assertEquals(Arrays.asList("install"), new ArrayList<>(covered.keySet()));
        return covered;
    }

    // verifies both signatures against a base rebuilt from the received request, returning the covered
    // components of each member
    private Map<String, List<String>> verifyBothSignatures(RecordedRequest request) throws Exception {
        Map<String, List<String>> covered = verifySignatures(request);
        assertEquals(Arrays.asList("install", "account"), new ArrayList<>(covered.keySet()));
        return covered;
    }

    private Map<String, List<String>> verifySignatures(RecordedRequest request) throws Exception {
        Map<String, String> inputs = members(request.getHeader("Signature-Input"));
        Map<String, String> signatures = members(request.getHeader("Signature"));
        assertEquals(inputs.keySet(), signatures.keySet());
        Map<String, List<String>> covered = new LinkedHashMap<>();
        for (Map.Entry<String, String> input : inputs.entrySet()) {
            String inner = input.getValue();
            List<String> components = new ArrayList<>();
            for (String name : inner.substring(1, inner.indexOf(')')).split(" ")) {
                if (!name.isEmpty())
                    components.add(name.substring(1, name.length() - 1));
            }
            StringBuilder base = new StringBuilder();
            for (String component : components)
                base.append('"').append(component).append("\": ").append(value(request, component)).append('\n');
            base.append("\"@signature-params\": ").append(inner);
            String sig = signatures.get(input.getKey());
            byte[] signature = Base64.getDecoder().decode(sig.substring(1, sig.length() - 1));
            assertTrue(input.getKey() + " must verify", verify(input.getKey(), base.toString(), signature));
            covered.put(input.getKey(), components);
        }
        return covered;
    }

    private static String value(RecordedRequest request, String component) {
        switch (component) {
            case "@method":
                return request.getMethod();
            case "@target-uri":
                return "https://" + request.getHeader("Host").toLowerCase() + request.getPath();
            case "@authority":
                return request.getHeader("Host").toLowerCase();
            case "@path":
                return request.getPath().split("\\?")[0];
            default:
                assertFalse("unexpected derived component " + component, component.startsWith("@"));
                Headers headers = request.getHeaders();
                assertFalse(component + " was not received", headers.values(component).isEmpty());
                return String.join(", ", headers.values(component));
        }
    }

    private static boolean verify(String member, String base, byte[] signature) throws Exception {
        byte[] message = base.getBytes(StandardCharsets.UTF_8);
        if (member.equals("account")) {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(MINI_SDK_ACCOUNT_KEY_BASE64), "HmacSHA256"));
            return Arrays.equals(mac.doFinal(message), signature);
        }
        assertEquals(64, signature.length);
        PublicKey key = KeyFactory.getInstance("EC").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(Approov.getInstallPublicKey())));
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(key);
        verifier.update(message);
        return verifier.verify(rawToDer(signature));
    }

    // splits a Signature or Signature-Input header into its members, keeping each value as sent
    private static Map<String, String> members(String header) {
        Map<String, String> members = new LinkedHashMap<>();
        if (header == null)
            return members;
        for (String member : header.split(",\\s*(?=[a-z]+=[(:])")) {
            int eq = member.indexOf('=');
            members.put(member.substring(0, eq), member.substring(eq + 1));
        }
        return members;
    }

    private static byte[] rawToDer(byte[] raw) {
        byte[] r = new BigInteger(1, Arrays.copyOfRange(raw, 0, 32)).toByteArray();
        byte[] s = new BigInteger(1, Arrays.copyOfRange(raw, 32, 64)).toByteArray();
        byte[] der = new byte[6 + r.length + s.length];
        der[0] = 0x30;
        der[1] = (byte) (4 + r.length + s.length);
        der[2] = 0x02;
        der[3] = (byte) r.length;
        System.arraycopy(r, 0, der, 4, r.length);
        der[4 + r.length] = 0x02;
        der[5 + r.length] = (byte) s.length;
        System.arraycopy(s, 0, der, 6 + r.length, s.length);
        return der;
    }
}
