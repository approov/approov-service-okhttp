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
import static org.junit.Assert.fail;

import com.criticalblue.approovsdk.Approov;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * TESTING_REQUIREMENTS "Target URI Is The Wire URL": a
 * signed request whose URL has a {@code |}, a {@code %20}, a literal {@code %25},
 * a non-ASCII character, or characters {@code java.net.URI} rejects reaches the
 * wire, and both signatures verify against a signature base rebuilt only from
 * what the server received, with {@code @target-uri} reconstructed from the
 * request line. The verifier here is independent of the layer's signing code: it
 * parses the headers itself and checks the hmac with the mini-SDK's test account
 * key and the ecdsa with the mini-SDK's install public key.
 *
 * Set APPROOV_SIGNING_FIXTURE_OUT to a file path to have the {@code |} case write
 * the received request as a JSON fixture for checking a backend verifier
 * separately. The fixture only ever holds mini-SDK test keys.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class TargetUriWireUrl380Test {
    // the mini-SDK's default test account message signing key (MiniAttesterConfig);
    // a test key only, never a real account key
    private static final String MINI_SDK_ACCOUNT_KEY_BASE64 =
            "ajNBV3k2b0ZHSGczOG5ZVG05X0tCaFpVeUR2TFh5S2ZlZHQ4YkpHUXh6b3MzQzVfUVhwZ3B5M0o3eWtyVFM2Rw==";

    private static final Pattern COMPONENT = Pattern.compile("\"([^\"]*)\"");

    private LocalHttpsFixture fixture;

    @Before
    public void setUp() throws Exception {
        fixture = new LocalHttpsFixture(false);
        ApproovService.reset();
        ApproovService.setSdkFacadeForTesting(new OriginTokenFetchFacade());
        ApproovService.initialize(fixture.context, LocalHttpsFixture.CONFIG, "reinit-wire-url");
        // HTTP/1.1 so that the server sees a real request line and Host header
        ApproovService.setOkHttpClientBuilder(fixture.trustingBuilder()
                .protocols(Collections.singletonList(Protocol.HTTP_1_1)));
        ApproovService.enableMessageSigning();
    }

    @After
    public void tearDown() throws Exception {
        fixture.shutdown();
        ApproovService.reset();
    }

    /**
     * The mini-SDK's token fetch parses its URL with java.net.URI and answers
     * BAD_URL for the URLs under test, so the request would abort before it is
     * signed. This facade hands the token fetch only the origin of the URL and
     * otherwise delegates to the mini-SDK, so that the signing of these URLs is
     * what is tested. Whether the real SDK accepts these URLs in a token fetch is
     * not established by this test.
     */
    private static final class OriginTokenFetchFacade extends RecordingSdkFacade {
        private static String origin(String url) {
            HttpUrl parsed = HttpUrl.get(url);
            return parsed.scheme() + "://" + parsed.host() + ":" + parsed.port() + "/";
        }

        @Override
        public void fetchApproovToken(Approov.TokenFetchCallback callback, String url) {
            super.fetchApproovToken(callback, origin(url));
        }

        @Override
        public Approov.TokenFetchResult fetchApproovTokenAndWait(String url) {
            return super.fetchApproovTokenAndWait(origin(url));
        }
    }

    // ==================================================================================
    // the cases
    // ==================================================================================

    @Test
    public void pipeInTheQueryIsSignedAsSent() throws Exception {
        HttpUrl url = url("/maps/api/staticmap?path=color:red|weight:5&markers=size:mid|label:A");
        assertTrue("OkHttp sends | raw: " + url, url.toString().contains("color:red|weight:5"));

        Received received = execute(new Request.Builder().url(url).get().build());
        assertEquals(url.toString(), received.wireUrl);
        Map<String, String> bases = verifyBothSignatures(received);
        writeFixtureIfRequested(received, bases);
    }

    @Test
    public void encodedSpacePercentAndNonAsciiAreSignedAsSent() throws Exception {
        HttpUrl url = url("/café/x?q=a%20b&pct=100%25&name=café&pipe=x|y");
        assertEquals("OkHttp encodes the non-ASCII characters and keeps the rest",
                base() + "/caf%C3%A9/x?q=a%20b&pct=100%25&name=caf%C3%A9&pipe=x|y", url.toString());

        Received received = execute(new Request.Builder().url(url)
                .post(RequestBody.create("{\"a\":1}", MediaType.get("application/json")))
                .build());
        assertEquals(url.toString(), received.wireUrl);
        verifyBothSignatures(received);
    }

    @Test
    public void urlThatJavaNetUriRejectsIsSignedAndSent() throws Exception {
        HttpUrl url = url("/s?f={%22a%22:1}&c=^`&p=x|y");
        assertJavaNetUriRejects(url);

        Received received = execute(new Request.Builder().url(url).get().build());
        assertEquals(url.toString(), received.wireUrl);
        verifyBothSignatures(received);
    }

    @Test
    public void urlThatJavaNetUriRejectsNeverAbortsAnEnqueuedCall() throws Exception {
        HttpUrl url = url("/s?f={%22a%22:1}&c=^`&p=x|y");
        assertJavaNetUriRejects(url);
        fixture.server.enqueue(new MockResponse().setBody("ok"));

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Object> outcome = new AtomicReference<>();
        ApproovService.getOkHttpClient().newCall(new Request.Builder().url(url).get().build())
                .enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {
                        outcome.set(e);
                        done.countDown();
                    }

                    @Override
                    public void onResponse(Call call, Response response) {
                        outcome.set(response.code());
                        response.close();
                        done.countDown();
                    }
                });
        assertTrue("the enqueued call never completed", done.await(10, TimeUnit.SECONDS));
        assertEquals("the call must complete with the server's response: " + outcome.get(), 200, outcome.get());
        verifyBothSignatures(received(fixture.server.takeRequest(5, TimeUnit.SECONDS)));
    }

    @Test
    public void fragmentIsNeverPartOfTheSignedTargetUri() throws Exception {
        HttpUrl url = url("/p?x=1|2#frag");
        Received received = execute(new Request.Builder().url(url).get().build());
        assertEquals(base() + "/p?x=1|2", received.wireUrl);
        verifyBothSignatures(received);
    }

    @Test
    public void redirectFollowupIsSignedOverItsWireUrl() throws Exception {
        fixture.server.enqueue(new MockResponse().setResponseCode(302)
                .setHeader("Location", "/next?path=color:blue|weight:2&q=caf%C3%A9%20%25"));
        Received first = execute(new Request.Builder().url(url("/start?a=1|2")).get().build());
        verifyBothSignatures(first);
        Received second = received(fixture.server.takeRequest(5, TimeUnit.SECONDS));
        assertEquals(base() + "/next?path=color:blue|weight:2&q=caf%C3%A9%20%25", second.wireUrl);
        verifyBothSignatures(second);
    }

    @Test
    public void hostFactoryIsChosenByHostWithoutPort() throws Exception {
        ApproovService.putMessageSigningHostFactory("LOCALHOST",
                ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory().setUseAccountMessageSigning());
        Received received = execute(new Request.Builder().url(url("/p?x=1|2")).get().build());
        Map<String, String> inputs = members(received.header("Signature-Input"));
        assertEquals("only the host factory's account signature: " + inputs,
                Arrays.asList(ApproovDefaultMessageSigning.SIG_ID_ACCOUNT), new ArrayList<>(inputs.keySet()));
        verifySignatures(received, 1);
    }

    // ==================================================================================
    // header fields OkHttp sets after signing, and repeated components
    // ==================================================================================

    @Test
    public void contentTypeAndLengthFromTheBodyAreCoveredAsSent() throws Exception {
        // the app sets neither header: OkHttp's BridgeInterceptor adds both from the
        // body after the application interceptors, where this layer signs
        Received received = execute(new Request.Builder().url(url("/body"))
                .post(RequestBody.create("{\"a\":1}", MediaType.get("application/json; charset=utf-8")))
                .build());
        assertEquals("application/json; charset=utf-8", received.header("Content-Type"));
        assertEquals("7", received.header("Content-Length"));
        for (Map.Entry<String, String> input : members(received.header("Signature-Input")).entrySet()) {
            List<String> components = components(input.getValue());
            assertTrue(input.getKey() + " must cover content-type: " + components, components.contains("content-type"));
            assertTrue(input.getKey() + " must cover content-length: " + components, components.contains("content-length"));
        }
        verifyBothSignatures(received);
    }

    @Test
    public void appBodyHeadersOkHttpReplacesAreSignedAsSent() throws Exception {
        // OkHttp replaces an app's Content-Type and Content-Length with the body's
        Received received = execute(new Request.Builder().url(url("/body"))
                .header("Content-Type", "application/json")
                .header("Content-Length", "1")
                .post(RequestBody.create("{\"a\":1}", MediaType.get("application/json; charset=utf-8")))
                .build());
        assertEquals("application/json; charset=utf-8", received.header("Content-Type"));
        assertEquals("7", received.header("Content-Length"));
        verifyBothSignatures(received);
    }

    @Test
    public void contentLengthOkHttpRemovesForAChunkedBodyIsNotSigned() throws Exception {
        RequestBody unknownLength = new RequestBody() {
            @Override
            public MediaType contentType() {
                return MediaType.get("text/plain");
            }

            @Override
            public void writeTo(okio.BufferedSink sink) throws IOException {
                sink.writeUtf8("chunked");
            }
        };
        Received received = execute(new Request.Builder().url(url("/body"))
                .header("Content-Length", "7")
                .post(unknownLength)
                .build());
        assertNull("OkHttp sends a body of unknown length chunked", received.header("Content-Length"));
        assertEquals("chunked", received.header("Transfer-Encoding"));
        verifyBothSignatures(received);
    }

    @Test
    public void aTokenHeaderThatIsAlsoAnOptionalHeaderIsCoveredOnce() throws Exception {
        // the token on Authorization, which the default factory also covers when present
        ApproovService.setTokenHeader("Authorization", "Bearer ");
        Received received = execute(new Request.Builder().url(url("/p")).get().build());
        assertTrue(received.header("Authorization"), received.header("Authorization").startsWith("Bearer ey"));
        for (Map.Entry<String, String> input : members(received.header("Signature-Input")).entrySet()) {
            List<String> components = components(input.getValue());
            // RFC 9421 section 2: each component identifier MUST occur only once
            assertEquals(input.getKey() + " covers authorization once: " + components, 1,
                    Collections.frequency(components, "authorization"));
        }
        verifyBothSignatures(received);
    }

    @Test
    public void anEmptyBodyHasAContentDigest() throws Exception {
        // required, so that a body digest that cannot be generated fails the request
        ApproovService.enableMessageSigning(ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()
                .setBodyDigestConfig(ApproovDefaultMessageSigning.DIGEST_SHA256, true));
        Received received = execute(new Request.Builder().url(url("/empty"))
                .post(RequestBody.create(new byte[0], null))
                .build());
        assertEquals("0", received.header("Content-Length"));
        // SHA-256 of no bytes
        assertEquals("sha-256=:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=:", received.header("Content-Digest"));
        for (Map.Entry<String, String> input : members(received.header("Signature-Input")).entrySet())
            assertTrue(input.getKey(), components(input.getValue()).contains("content-digest"));
        verifyBothSignatures(received);
    }

    // ==================================================================================
    // sending and receiving
    // ==================================================================================

    // what the server received, with the target URI rebuilt from the request line
    private static final class Received {
        RecordedRequest recorded;
        String scheme;
        String authority;
        String requestTarget;
        String wireUrl;

        String header(String name) {
            return recorded.getHeader(name);
        }
    }

    private String base() {
        return "https://localhost:" + fixture.server.getPort();
    }

    private HttpUrl url(String pathAndQuery) {
        return HttpUrl.get(base() + pathAndQuery);
    }

    private Received execute(Request request) throws Exception {
        fixture.server.enqueue(new MockResponse().setBody("ok"));
        try (Response response = ApproovService.getOkHttpClient().newCall(request).execute()) {
            assertEquals(200, response.code());
        }
        return received(fixture.server.takeRequest(5, TimeUnit.SECONDS));
    }

    private static Received received(RecordedRequest recorded) {
        assertNotNull("the request never reached the wire", recorded);
        Received received = new Received();
        received.recorded = recorded;
        // the request line is "METHOD request-target HTTP/1.1"; the target is taken
        // as sent, never parsed
        String[] requestLine = recorded.getRequestLine().split(" ");
        assertEquals(recorded.getRequestLine(), 3, requestLine.length);
        received.requestTarget = requestLine[1];
        received.scheme = (recorded.getTlsVersion() != null) ? "https" : "http";
        received.authority = recorded.getHeader("Host").toLowerCase(Locale.ROOT);
        received.wireUrl = received.scheme + "://" + received.authority + received.requestTarget;
        return received;
    }

    private static void assertJavaNetUriRejects(HttpUrl url) {
        try {
            URI.create(url.toString());
            fail("java.net.URI is expected to reject " + url);
        } catch (IllegalArgumentException expected) {
            // OkHttp sends it all the same
        }
    }

    // ==================================================================================
    // the independent verifier
    // ==================================================================================

    private Map<String, String> verifyBothSignatures(Received received) throws Exception {
        Map<String, String> inputs = members(received.header("Signature-Input"));
        assertEquals("both signatures: " + inputs,
                Arrays.asList(ApproovDefaultMessageSigning.SIG_ID_INSTALL, ApproovDefaultMessageSigning.SIG_ID_ACCOUNT),
                new ArrayList<>(inputs.keySet()));
        return verifySignatures(received, 2);
    }

    // verifies every signature on the received request, returning the signature
    // base of each member
    private Map<String, String> verifySignatures(Received received, int expected) throws Exception {
        assertNotNull("Signature-Input missing", received.header("Signature-Input"));
        assertNotNull("Signature missing", received.header("Signature"));
        Map<String, String> inputs = members(received.header("Signature-Input"));
        Map<String, String> signatures = members(received.header("Signature"));
        assertEquals(inputs.keySet(), signatures.keySet());
        assertEquals(expected, inputs.size());
        Map<String, String> bases = new LinkedHashMap<>();
        for (Map.Entry<String, String> input : inputs.entrySet()) {
            String member = input.getKey();
            List<String> components = components(input.getValue());
            assertTrue(member + " must cover @target-uri: " + input.getValue(), components.contains("@target-uri"));
            String sig = signatures.get(member);
            assertTrue(sig, sig.startsWith(":") && sig.endsWith(":"));
            byte[] signature = Base64.getDecoder().decode(sig.substring(1, sig.length() - 1));
            String base = signatureBase(received, components, input.getValue(), received.wireUrl);
            boolean ok = verify(member, input.getValue(), base, signature);
            if (!ok) {
                // say what the layer did sign, to make a failure readable
                String reEncoded = HttpUrl.get(received.wireUrl).uri().toString();
                String hint = verify(member, input.getValue(),
                        signatureBase(received, components, input.getValue(), reEncoded), signature)
                        ? "the layer signed the java.net.URI form " + reEncoded
                        : "the layer signed neither the wire URL nor its java.net.URI form";
                fail(member + " signature does not verify over @target-uri " + received.wireUrl + ": " + hint);
            }
            bases.put(member, base);
        }
        return bases;
    }

    private static boolean verify(String member, String input, String base, byte[] signature) throws Exception {
        byte[] message = base.getBytes(StandardCharsets.UTF_8);
        if (input.contains("alg=\"hmac-sha256\"")) {
            assertEquals(ApproovDefaultMessageSigning.SIG_ID_ACCOUNT, member);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(MINI_SDK_ACCOUNT_KEY_BASE64), "HmacSHA256"));
            return Arrays.equals(mac.doFinal(message), signature);
        } else if (input.contains("alg=\"ecdsa-p256-sha256\"")) {
            assertEquals(ApproovDefaultMessageSigning.SIG_ID_INSTALL, member);
            assertEquals("RFC 9421 ecdsa-p256-sha256 is the raw r||s", 64, signature.length);
            PublicKey key = KeyFactory.getInstance("EC").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(Approov.getInstallPublicKey())));
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(key);
            verifier.update(message);
            return verifier.verify(rawToDer(signature));
        }
        fail("unexpected algorithm: " + input);
        return false;
    }

    // rebuilds the RFC 9421 signature base from the received request alone
    private static String signatureBase(Received received, List<String> components, String signatureParams,
                                        String targetUri) {
        StringBuilder base = new StringBuilder();
        for (String component : components) {
            base.append('"').append(component).append("\": ")
                    .append(componentValue(received, component, targetUri)).append('\n');
        }
        return base.append("\"@signature-params\": ").append(signatureParams).toString();
    }

    private static String componentValue(Received received, String component, String targetUri) {
        String target = received.requestTarget;
        int q = target.indexOf('?');
        switch (component) {
            case "@method":
                return received.recorded.getMethod();
            case "@target-uri":
                return targetUri;
            case "@authority":
                return received.authority;
            case "@scheme":
                return received.scheme;
            case "@request-target":
                return target;
            case "@path":
                return (q < 0) ? target : target.substring(0, q);
            case "@query":
                return (q < 0) ? "?" : target.substring(q);
            default:
                assertFalse("unexpected derived component " + component, component.startsWith("@"));
                List<String> values = received.recorded.getHeaders().values(component);
                assertFalse("covered header " + component + " was not received", values.isEmpty());
                StringBuilder joined = new StringBuilder();
                for (String value : values) {
                    if (joined.length() > 0)
                        joined.append(", ");
                    joined.append(value.trim());
                }
                return joined.toString();
        }
    }

    // the component names of an inner list such as ("@method" "@target-uri");alg=...
    private static List<String> components(String innerList) {
        assertTrue(innerList, innerList.startsWith("("));
        String inside = innerList.substring(1, innerList.indexOf(')'));
        List<String> components = new ArrayList<>();
        Matcher matcher = COMPONENT.matcher(inside);
        while (matcher.find())
            components.add(matcher.group(1));
        assertEquals("component identifiers with parameters are not expected: " + inside,
                inside.trim().isEmpty() ? 0 : inside.trim().split(" ").length, components.size());
        return components;
    }

    // splits a structured field dictionary into its members, keeping each value as sent
    private static Map<String, String> members(String dictionary) {
        Map<String, String> members = new LinkedHashMap<>();
        if (dictionary == null)
            return members;
        int depth = 0;
        boolean quoted = false;
        int start = 0;
        for (int i = 0; i <= dictionary.length(); i++) {
            char c = (i < dictionary.length()) ? dictionary.charAt(i) : ',';
            if (quoted) {
                if (c == '\\')
                    i++;
                else if (c == '"')
                    quoted = false;
            } else if (c == '"') {
                quoted = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                String member = dictionary.substring(start, i).trim();
                int eq = member.indexOf('=');
                members.put(member.substring(0, eq), member.substring(eq + 1));
                start = i + 1;
            }
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

    // ==================================================================================
    // the backend fixture
    // ==================================================================================

    private static void writeFixtureIfRequested(Received received, Map<String, String> bases) throws Exception {
        String out = System.getenv("APPROOV_SIGNING_FIXTURE_OUT");
        if (out == null || out.isEmpty())
            return;
        JSONObject fixture = new JSONObject();
        fixture.put("description", "A request signed by approov-service-okhttp (feature/3.8.0) with the mini-SDK, "
                + "as the server received it. The query contains | which OkHttp sends raw; @target-uri must be "
                + "the URL as received, not a re-encoded form. Keys are mini-SDK test keys only.");
        fixture.put("method", received.recorded.getMethod());
        fixture.put("requestLine", received.recorded.getRequestLine());
        fixture.put("url", received.wireUrl);
        JSONArray headers = new JSONArray();
        for (int i = 0; i < received.recorded.getHeaders().size(); i++) {
            headers.put(new JSONArray()
                    .put(received.recorded.getHeaders().name(i))
                    .put(received.recorded.getHeaders().value(i)));
        }
        fixture.put("headers", headers);
        fixture.put("body", received.recorded.getBody().readUtf8());
        JSONObject signatureBases = new JSONObject();
        for (Map.Entry<String, String> base : bases.entrySet())
            signatureBases.put(base.getKey(), base.getValue());
        fixture.put("signatureBases", signatureBases);
        JSONObject keys = new JSONObject();
        keys.put("note", "mini-SDK test keys (MiniAttesterConfig defaults), never real keys");
        keys.put("accountHmacSha256KeyBase64", MINI_SDK_ACCOUNT_KEY_BASE64);
        keys.put("installPublicKeySpkiBase64", Approov.getInstallPublicKey());
        fixture.put("keys", keys);
        File file = new File(out);
        if (file.getParentFile() != null)
            file.getParentFile().mkdirs();
        Files.write(file.toPath(), fixture.toString(2).getBytes(StandardCharsets.UTF_8));
    }
}
