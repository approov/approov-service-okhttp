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

import android.util.Base64;
import android.util.Log;

import io.approov.util.okhttp.tink.subtle.EllipticCurves;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.approov.util.okhttp.http.sfv.ByteSequenceItem;
import io.approov.util.okhttp.http.sfv.Dictionary;
import io.approov.util.okhttp.http.sfv.ListElement;
import io.approov.util.okhttp.sig.ComponentProvider;
import io.approov.util.okhttp.sig.SignatureBaseBuilder;
import io.approov.util.okhttp.sig.SignatureParameters;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.RequestBody;
import okio.Buffer;
import okio.ByteString;

/**
 * HTTP message signing (RFC 9421) of the requests Approov protects, and the
 * signature parameters factory API that configures it.
 *
 * Message signing is not a service mutator. It is off by default in 3.8.0 and
 * switched on with {@link ApproovService#enableMessageSigning()}, whichever
 * {@link ApproovServiceMutator} makes the request decisions, and installing a
 * mutator never switches it on or off. Once on, every request carrying the
 * Approov token header is signed last, after the mutator's decisions, the
 * secure string substitutions and its handleInterceptorProcessedRequest
 * callback, so the signature covers what is sent. It is signed again whenever
 * its protection is reapplied (a redirect within a protected domain, an
 * authenticator retry, a stale protection refresh). From 4.0.0 signing is on
 * and compulsory.
 *
 * The default factory ({@link #generateDefaultSignatureParametersFactory()})
 * produces both the install (ECDSA P-256, per app installation key held in the
 * device secure hardware) and the account (HMAC-SHA256, shared account key)
 * signatures, emitted as the members "install" and "account" of the same
 * Signature and Signature-Input dictionaries over the same covered components.
 * Both are produced so that a device without secure hardware for the install
 * key still yields a verifiable signature; the backend chooses which it
 * verifies. A signature that cannot be produced is omitted and the request
 * proceeds with the remaining one, or unsigned, since the backend is the
 * enforcement point. The only deliberate failures are configuration errors by
 * the integrator: a required body digest that cannot be generated, or an
 * unsupported signature algorithm.
 *
 * Apps never construct this class: they pass a {@link SignatureParametersFactory}
 * to {@link ApproovService#enableMessageSigning(SignatureParametersFactory)} or
 * {@link ApproovService#putMessageSigningHostFactory(String, SignatureParametersFactory)}.
 */
public class ApproovDefaultMessageSigning {
    // logging tag
    private static final String TAG = "ApproovMsgSign";

    /**
     * Constant for the SHA-256 digest algorithm (used for body digests).
     */
    public static final String DIGEST_SHA256 = "sha-256";

    /**
     * Constant for the SHA-512 digest algorithm (used for body digests).
     */
    public static final String DIGEST_SHA512 = "sha-512";

    /**
     * Constant for the ECDSA P-256 with SHA-256 algorithm (used when signing with
     * install private key).
     */
    public final static String ALG_ES256 = "ecdsa-p256-sha256";

    /**
     * Constant for the HMAC with SHA-256 algorithm (used when signing with the
     * account signing key).
     */
    public final static String ALG_HS256 = "hmac-sha256";

    /**
     * Signature dictionary member name for the install message signature.
     */
    public final static String SIG_ID_INSTALL = "install";

    /**
     * Signature dictionary member name for the account message signature.
     */
    public final static String SIG_ID_ACCOUNT = "account";

    // the factory used for every host without a factory of its own, or null to
    // sign nothing; read on the request threads, so volatile
    private volatile SignatureParametersFactory defaultFactory;

    // factories for individual hosts, keyed by the lowercase host name (no port),
    // read on the request threads
    private final Map<String, SignatureParametersFactory> hostFactories = new ConcurrentHashMap<>();

    /**
     * Constructs a signer with no factory. Only ApproovService constructs one.
     */
    ApproovDefaultMessageSigning() {
    }

    @Override
    public String toString() {
        return "ApproovDefaultMessageSigning";
    }

    /**
     * Sets the factory used for every host without a factory of its own.
     *
     * @param factory the factory, or null to sign nothing for such hosts
     * @return this signer
     */
    ApproovDefaultMessageSigning setDefaultFactory(SignatureParametersFactory factory) {
        this.defaultFactory = factory;
        return this;
    }

    /**
     * Uses a factory for requests to one host instead of the default factory.
     *
     * @param hostName the host name, matched without regard to case and without
     *                 any port
     * @param factory  the factory for the host, or null to remove the host's
     *                 factory so that the default factory applies again
     * @return this signer
     */
    ApproovDefaultMessageSigning putHostFactory(String hostName, SignatureParametersFactory factory) {
        if (hostName == null)
            throw new IllegalArgumentException("hostName must not be null");
        String key = hostName.toLowerCase(Locale.ROOT);
        if (factory == null)
            hostFactories.remove(key);
        else
            hostFactories.put(key, factory);
        return this;
    }

    /**
     * Selects the factory for a request: the factory of its host if there is one,
     * otherwise the default factory.
     *
     * @param provider the component provider for the request
     * @return the factory, or null if none applies
     */
    private SignatureParametersFactory factoryFor(OkHttpComponentProvider provider) {
        SignatureParametersFactory factory = hostFactories.get(provider.getAuthority().toLowerCase(Locale.ROOT));
        return (factory != null) ? factory : defaultFactory;
    }

    /**
     * Signs a request that the Approov interceptor has protected. The request is
     * only modified if it carries the Approov token header and a factory applies
     * to its host.
     *
     * @param request the request as the Approov interceptor and the service
     *                mutator left it
     * @param changes the protection the Approov interceptor applied to it
     * @return the request with the signature headers added, or the request
     *         unchanged if it is not signed
     * @throws RequiredBodyDigestException if a body digest configured as required
     *                                     cannot be generated
     * @throws IllegalStateException       if a signature algorithm is unsupported
     */
    Request sign(Request request, ApproovRequestMutations changes) {
        if (changes == null || changes.getTokenHeaderKey() == null) {
            // the request doesn't have an Approov token header, so we don't need to sign it
            return request;
        }
        OkHttpComponentProvider provider = new OkHttpComponentProvider(request);
        SignatureParametersFactory factory = factoryFor(provider);
        if (factory == null) {
            // no factory applies to this host so the request is not signed
            return request;
        }
        // Build the signature parameters. This fails CLOSED (the IllegalStateException is rethrown)
        // only when a body digest configured as required cannot be generated — that must abort the
        // request. Any other failure here (including from a custom SignatureParametersFactory) fails
        // OPEN: we log at error and proceed unsigned, because the backend is the enforcement point.
        SignatureParameters params;
        try {
            params = factory.buildSignatureParameters(provider, changes);
        } catch (RequiredBodyDigestException e) {
            // The only deliberate fail-closed build condition: a body digest configured as
            // required could not be generated, so the request must be aborted.
            throw e;
        } catch (Exception e) {
            Log.e(TAG, "Failed to build signature parameters - proceeding unsigned: " + e);
            return request;
        }
        if (params == null) {
            // No signature to be added to the request; return the original request.
            return request;
        }

        // Determine the algorithms to sign with. A factory (or subclass) that sets an
        // explicit algorithm on the parameters produces that single signature; otherwise
        // the algorithms configured on the factory are used, which by default are both
        // the install and the account signature.
        List<String> algs;
        if (params.getAlg() != null) {
            algs = Collections.singletonList(params.getAlg());
        } else {
            algs = factory.getAlgs();
        }
        for (String alg : algs) {
            // an unsupported algorithm is an integrator configuration error and the
            // only signing condition, besides a required body digest, that fails closed
            if (!ALG_ES256.equals(alg) && !ALG_HS256.equals(alg))
                throw new IllegalStateException("Unsupported algorithm identifier: " + alg);
        }

        // Generate a signature per algorithm over the same covered components. Each
        // signature has its own signature base since the base includes the algorithm.
        Map<String, ListElement<?>> signatures = new LinkedHashMap<>();
        Map<String, ListElement<?>> signatureInputs = new LinkedHashMap<>();
        Map<String, String> messages = new LinkedHashMap<>();
        for (String alg : algs) {
            SignatureParameters algParams = new SignatureParameters(params).setAlg(alg);
            // Apply the params to get the message. A failure building the signature base is not a
            // deliberate fail-closed condition, so it also fails OPEN (proceed unsigned).
            // WARNING never log the message as it contains an Approov token which provides
            // access to your API.
            String message;
            try {
                message = new SignatureBaseBuilder(algParams, provider).createSignatureBase();
            } catch (Exception e) {
                Log.e(TAG, "Failed to build signature base - proceeding unsigned: " + e);
                return request;
            }
            String sigId = ALG_ES256.equals(alg) ? SIG_ID_INSTALL : SIG_ID_ACCOUNT;
            byte[] signature = ALG_ES256.equals(alg) ? installSignature(message) : accountSignature(message);
            if (signature == null) {
                // this signature could not be produced: proceed with any other
                Log.e(TAG, "Skipping " + sigId + " message signature");
                continue;
            }
            signatures.put(sigId, ByteSequenceItem.valueOf(signature));
            signatureInputs.put(sigId, algParams.toComponentValue());
            messages.put(sigId, message);
        }
        if (signatures.isEmpty()) {
            Log.e(TAG, "No message signature could be produced - proceeding unsigned");
            return request;
        }

        // RFC 9421 §4.2 defines each Signature dictionary member value as a Byte
        // Sequence, serialized by RFC 8941 §3.3.5 as colon-delimited base64
        // (for example, install=:<base64>:). Both signatures are members of the
        // same dictionaries.
        String sigHeader = Dictionary.valueOf(signatures).serialize();
        String sigInputHeader = Dictionary.valueOf(signatureInputs).serialize();

        // Update the request from the one held by the component provider as the
        // signature builder may have modified it.
        Request.Builder signedBuilder = provider.getRequest().newBuilder()
                .header("Signature", sigHeader)
                .header("Signature-Input", sigInputHeader);
        if (params.isDebugMode()) {
            try {
                MessageDigest digestBuilder = MessageDigest.getInstance("SHA-256");
                Map<String, ListElement<?>> digests = new LinkedHashMap<>();
                for (Map.Entry<String, String> entry : messages.entrySet()) {
                    digestBuilder.reset();
                    byte[] digest = digestBuilder.digest(entry.getValue().getBytes(StandardCharsets.UTF_8));
                    digests.put(entry.getKey(), ByteSequenceItem.valueOf(digest));
                }
                signedBuilder.header("Signature-Base-Digest", Dictionary.valueOf(digests).serialize());
            } catch (NoSuchAlgorithmException e) {
                Log.d(TAG, "Failed to get digest algorithm - no debug entry " + e);
            }
        }
        Request signed = signedBuilder.build();

        // WARNING never log the full request as it contains an Approov token which
        // provides access to your API
        // Log.d(TAG, "Request String: " + signed.toString());
        return signed;
    }

    /**
     * Produces the install message signature over the given message as the raw
     * 64 byte r||s value required by RFC 9421 for ecdsa-p256-sha256, or null if
     * it cannot be produced.
     *
     * @param message the signature base
     * @return the signature bytes, or null if unavailable
     */
    private static byte[] installSignature(String message) {
        String base64;
        try {
            base64 = ApproovService.getInstallMessageSignature(message);
        } catch (ApproovException e) {
            Log.e(TAG, "Failed to get InstallMessageSignature: " + e);
            return null;
        }
        if (base64.isEmpty()) {
            Log.e(TAG, "InstallMessageSignature is empty");
            return null;
        }
        byte[] signature;
        try {
            signature = Base64.decode(base64, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "Failed to decode base64 signature: " + e);
            return null;
        }
        // decode the signature from ASN.1 DER format
        try {
            return es256DerToRaw(signature);
        } catch (Exception e) {
            Log.e(TAG, "Failed to decode ASN.1 DER ES256 signature", e);
            return null;
        }
    }

    /**
     * Converts an ASN.1 DER encoded ECDSA P-256 signature, as the SDK returns the
     * install message signature, to the raw 64 byte r||s form that RFC 9421
     * requires for ecdsa-p256-sha256. Tink's ecdsaDer2Ieee checks the DER
     * structure but not that r and s fit in 32 bytes (an oversized r throws and an
     * oversized s overwrites the end of r), so only input that is the canonical DER
     * encoding of the result is accepted, as in approov-service-android.
     *
     * @param der the DER encoded signature
     * @return the raw r||s signature
     * @throws IllegalArgumentException if der is not a canonical DER ES256 signature
     */
    static byte[] es256DerToRaw(byte[] der) {
        byte[] raw;
        byte[] canonical;
        try {
            raw = EllipticCurves.ecdsaDer2Ieee(der, 64);
            canonical = EllipticCurves.ecdsaIeee2Der(raw);
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IllegalArgumentException("Not an ASN.1 DER ES256 signature: " + e, e);
        }
        if (!Arrays.equals(canonical, der))
            throw new IllegalArgumentException("Not an ASN.1 DER ES256 signature: integer exceeds 32 bytes");
        return raw;
    }

    /**
     * Produces the account message signature over the given message, or null if
     * it cannot be produced (for instance because the account key is only
     * delivered on a successful attestation).
     *
     * @param message the signature base
     * @return the signature bytes, or null if unavailable
     */
    private static byte[] accountSignature(String message) {
        String base64;
        try {
            base64 = ApproovService.getAccountMessageSignature(message);
        } catch (ApproovException e) {
            Log.e(TAG, "Failed to get AccountMessageSignature: " + e);
            return null;
        }
        if (base64.isEmpty()) {
            Log.e(TAG, "AccountMessageSignature is empty");
            return null;
        }
        try {
            return Base64.decode(base64, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "Failed to decode base64 signature: " + e);
            return null;
        }
    }

    /**
     * Generates a default {@link SignatureParametersFactory} with predefined
     * settings.
     *
     * @return A new instance of {@link SignatureParametersFactory}.
     */
    public static SignatureParametersFactory generateDefaultSignatureParametersFactory() {
        return generateDefaultSignatureParametersFactory(null);
    }

    /**
     * Generates a default {@link SignatureParametersFactory} with optional base
     * parameters.
     *
     * @param baseParametersOverride The base parameters to override, or
     *                               {@code null} to use defaults.
     * @return A new instance of {@link SignatureParametersFactory}.
     */
    public static SignatureParametersFactory generateDefaultSignatureParametersFactory(
            SignatureParameters baseParametersOverride) {
        // default expiry seconds - must encompass worst case request retry
        // time and clock skew
        long defaultExpiresLifetime = 15;
        SignatureParameters baseParameters;
        if (baseParametersOverride != null) {
            baseParameters = baseParametersOverride;
        } else {
            baseParameters = new SignatureParameters()
                    .addComponentIdentifier(ComponentProvider.DC_METHOD)
                    .addComponentIdentifier(ComponentProvider.DC_TARGET_URI);
        }
        return new SignatureParametersFactory()
                .setBaseParameters(baseParameters)
                .setUseInstallAndAccountMessageSigning()
                .setAddCreated(true)
                .setExpiresLifetime(defaultExpiresLifetime)
                .setAddApproovTokenHeader(true)
                .setAddApproovTraceIDHeader(true)
                .setAddApproovStatusHeader(true)
                .addOptionalHeaders("Authorization", "Content-Length", "Content-Type")
                .setBodyDigestConfig(DIGEST_SHA256, false);
    }

    /**
     * Factory class for creating pre-request {@link SignatureParameters} with
     * configurable settings. Each request passed to the factory builds a new
     * SignatureParameters instance based on the configured settings and
     * specific for the request.
     */
    public static class SignatureParametersFactory {
        // The base parameters that are copied for every new generated message
        // signature. Initialised to an empty SignatureParameters so that a bare
        // SignatureParametersFactory() is safe to use without calling setBaseParameters().
        protected SignatureParameters baseParameters = new SignatureParameters();
        // The algorithm to use for body digests, or null if no body digest is to be
        // used.
        protected String bodyDigestAlgorithm;
        // True if a body digest is required; body digests cannot be generated for all
        // requests - either because they have no body or because the request body is
        // one shot.
        protected boolean bodyDigestRequired;
        // True to produce the install message signature (ECDSA P-256 with the per
        // installation key).
        protected boolean useInstallMessageSigning = true;
        // True to produce the account message signature (HMAC-SHA256 with the shared
        // account key). Both are true by default so that a bare factory produces both
        // signatures unless one is selected explicitly.
        protected boolean useAccountMessageSigning = true;
        // True to add the "created" timestamp field to the signature parameters.
        protected boolean addCreated;
        // Expiration lifetime in seconds; if >0 the "expires" field is added to the
        // signature parameters.
        protected long expiresLifetime;
        // True to add the Approov token header to the signature parameters. This is
        // strongly advised.
        protected boolean addApproovTokenHeader;
        protected boolean addApproovTraceIDHeader;
        // True to add the Approov status header to the signature parameters if it is
        // present, so that the reported fetch status cannot be stripped or altered
        // without invalidating the signature.
        protected boolean addApproovStatusHeader;
        // Lists the headers to add to the message signature if they are present in the
        // request. (Non-optional headers should be added to the base parameters).
        // Initialised to an empty list so that a bare SignatureParametersFactory() is
        // safe to use without calling addOptionalHeaders().
        protected List<String> optionalHeaders = new ArrayList<>();

        /**
         * Sets the base parameters for the factory. The base parameters are copied for
         * every new generated message signature.
         *
         * @param baseParameters The base parameters to set.
         * @return The current instance for method chaining.
         */
        public SignatureParametersFactory setBaseParameters(SignatureParameters baseParameters) {
            this.baseParameters = baseParameters;
            return this;
        }

        /**
         * Configures the body digest settings for the factory. If set, then requests
         * with bodies will have the digest created and added as a header to the request
         * with the header included in the request's message signature.
         *
         * @param bodyDigestAlgorithm The digest algorithm to use, or {@code null} to
         *                            disable.
         * @param required            Whether the body digest is required.
         * @return The current instance for method chaining.
         * @throws IllegalArgumentException If an unsupported algorithm is specified.
         */
        public SignatureParametersFactory setBodyDigestConfig(String bodyDigestAlgorithm, boolean required) {
            if (bodyDigestAlgorithm == null) {
                required = false;
            } else if (!bodyDigestAlgorithm.equals(DIGEST_SHA256)
                    && !bodyDigestAlgorithm.equals(DIGEST_SHA512)) {
                throw new IllegalArgumentException("Unsupported body digest algorithm: " + bodyDigestAlgorithm);
            }
            this.bodyDigestAlgorithm = bodyDigestAlgorithm;
            this.bodyDigestRequired = required;
            return this;
        }

        /**
         * Configures the factory to produce only the install message signature.
         *
         * @return The current instance for method chaining.
         */
        public SignatureParametersFactory setUseInstallMessageSigning() {
            this.useInstallMessageSigning = true;
            this.useAccountMessageSigning = false;
            return this;
        }

        /**
         * Configures the factory to produce only the account message signature.
         *
         * @return The current instance for method chaining.
         */
        public SignatureParametersFactory setUseAccountMessageSigning() {
            this.useInstallMessageSigning = false;
            this.useAccountMessageSigning = true;
            return this;
        }

        /**
         * Configures the factory to produce both the install and the account
         * message signatures, as members "install" and "account" of the same
         * Signature and Signature-Input dictionaries over the same covered
         * components. This is the default of a newly constructed factory.
         *
         * @return The current instance for method chaining.
         */
        public SignatureParametersFactory setUseInstallAndAccountMessageSigning() {
            this.useInstallMessageSigning = true;
            this.useAccountMessageSigning = true;
            return this;
        }

        /**
         * Gets the signature algorithms configured on this factory, in the order
         * the signatures are emitted.
         *
         * @return the algorithm identifiers
         * @throws IllegalStateException if neither signature is enabled
         */
        public List<String> getAlgs() {
            List<String> algs = new ArrayList<>(2);
            if (useInstallMessageSigning)
                algs.add(ALG_ES256);
            if (useAccountMessageSigning)
                algs.add(ALG_HS256);
            if (algs.isEmpty())
                throw new IllegalStateException("No message signing algorithm is enabled");
            return algs;
        }

        /**
         * Sets whether the "created" field should be added to the signature parameters.
         * The created field holds the device's timestamp indicating when the request
         * was
         * created.
         *
         * @param addCreated Whether to add the "created" field.
         * @return The current instance for method chaining.
         */
        public SignatureParametersFactory setAddCreated(boolean addCreated) {
            this.addCreated = addCreated;
            return this;
        }

        /**
         * Sets the expiration lifetime for the signature parameters. Only a
         * value >0 will cause the expires attribute to be added to the
         * SignatureParameters for a request. The expires attribute holds the
         * timestamp indicating when the message signature will expire. It is
         * equal to the created timestamp (if included) plus the expiration
         * lifetime.
         *
         * @param expiresLifetime The expiration lifetime in seconds, if <=0
         *                        no expiration is added.
         * @return The current instance for method chaining.
         */
        public SignatureParametersFactory setExpiresLifetime(long expiresLifetime) {
            this.expiresLifetime = expiresLifetime;
            return this;
        }

        /**
         * Sets whether the Approov token header should be added to the signature
         * parameters.
         *
         * @param addApproovTokenHeader Whether to add the Approov token header.
         * @return The current instance for method chaining.
         */
        public SignatureParametersFactory setAddApproovTokenHeader(boolean addApproovTokenHeader) {
            this.addApproovTokenHeader = addApproovTokenHeader;
            return this;
        }

        /**
         * Sets whether the optional Approov traceID header should be added to the
         * signature
         * parameters.
         *
         * @param addApproovTraceIDHeader Whether to add the Approov traceID header.
         * @return The current instance for method chaining.
         */
        public SignatureParametersFactory setAddApproovTraceIDHeader(boolean addApproovTraceIDHeader) {
            this.addApproovTraceIDHeader = addApproovTraceIDHeader;
            return this;
        }

        /**
         * Sets whether the Approov status header, when present on the request, is
         * covered by the signature so that the reported fetch status cannot be
         * stripped or altered without invalidating the signature.
         *
         * @param addApproovStatusHeader Whether to cover the Approov status header.
         * @return The current instance for method chaining.
         */
        public SignatureParametersFactory setAddApproovStatusHeader(boolean addApproovStatusHeader) {
            this.addApproovStatusHeader = addApproovStatusHeader;
            return this;
        }

        /**
         * Adds optional headers to the signature parameters. Headers
         * configured as optional are added to the generated
         * SignatureParameters if the target request includes the header
         * otherwise they are ignored.
         *
         * @param headers The headers to add.
         * @return The current instance for method chaining.
         */
        public SignatureParametersFactory addOptionalHeaders(String... headers) {
            if (this.optionalHeaders == null) {
                this.optionalHeaders = new ArrayList<>(Arrays.asList(headers));
            } else {
                this.optionalHeaders.addAll(Arrays.asList(headers));
            }
            return this;
        }

        /**
         * Generates a body digest for the request if possible.
         *
         * @param provider          The component provider for the request.
         * @param requestParameters The signature parameters to update.
         * @return {@code true} if the body digest was successfully generated,
         *         {@code false} otherwise.
         */
        protected boolean generateBodyDigest(OkHttpComponentProvider provider, SignatureParameters requestParameters) {
            RequestBody body = provider.request.body();
            // ignore null bodies, one shot bodies, or bodies of unknown length as these
            // will
            // likely require more specific knowledge
            if (body == null || body.isOneShot()) {
                return false;
            } else {
                try {
                    if (body.contentLength() <= 0) {
                        return false;
                    }
                } catch (IOException e) {
                    // ignore bodies that can't be interrogated
                    return false;
                }
            }

            // grab the body data
            final Buffer buffer = new Buffer();
            try {
                body.writeTo(buffer);
            } catch (IOException e) {
                // ignore bodies that can't be interrogated
                return false;
            }
            // calculate the digest
            ByteString digest;
            switch (bodyDigestAlgorithm) {
                case DIGEST_SHA256:
                    digest = buffer.sha256();
                    break;
                case DIGEST_SHA512:
                    digest = buffer.sha512();
                    break;
                default:
                    return false;
            }
            // generate the header value
            Dictionary digestHeader = Dictionary.valueOf(Collections.singletonMap(
                    bodyDigestAlgorithm, ByteSequenceItem.valueOf(digest.toByteArray())));

            // add the digest to the request
            Request request = provider.getRequest();
            request = request.newBuilder()
                    .header("Content-Digest", digestHeader.serialize())
                    .build();
            provider.setRequest(request);
            // add the header to the SignatureParameters
            requestParameters.addComponentIdentifier("Content-Digest");
            return true;
        }

        /**
         * Builds the signature parameters for a given request.
         *
         * @param provider The component provider for the request.
         * @param changes  The request mutations to apply.
         * @return The generated {@link SignatureParameters}.
         * @throws IllegalStateException If required parameters cannot be generated.
         */
        protected SignatureParameters buildSignatureParameters(OkHttpComponentProvider provider,
                ApproovRequestMutations changes) {
            // the algorithm is left unset so that one signature per configured
            // algorithm (see getAlgs) is produced over these same parameters; a
            // subclass may set an explicit algorithm to produce that single signature
            SignatureParameters requestParameters = new SignatureParameters(baseParameters);
            if (addCreated || expiresLifetime > 0) {
                long currentTime = System.currentTimeMillis() / 1000;
                if (addCreated) {
                    requestParameters.setCreated(currentTime);
                }
                if (expiresLifetime > 0) {
                    requestParameters.setExpires(currentTime + expiresLifetime);
                }
            }
            if (addApproovTokenHeader) {
                requestParameters.addComponentIdentifier(changes.getTokenHeaderKey());
            }
            if (addApproovTraceIDHeader && changes.getTraceIDHeaderKey() != null) {
                requestParameters.addComponentIdentifier(changes.getTraceIDHeaderKey());
            }
            if (addApproovStatusHeader && changes.getStatusHeaderKey() != null) {
                requestParameters.addComponentIdentifier(changes.getStatusHeaderKey());
            }
            for (String headerName : optionalHeaders) {
                if (provider.hasField(headerName)) {
                    requestParameters.addComponentIdentifier(headerName);
                }
            }
            if (bodyDigestAlgorithm != null) {
                if (!generateBodyDigest(provider, requestParameters) && bodyDigestRequired) {
                    throw new RequiredBodyDigestException("Failed to create required body digest");
                }
            }
            return requestParameters;
        }
    }

    /**
     * Thrown when a body digest configured as <em>required</em> cannot be generated.
     * This is the only signature-build condition that must fail CLOSED (abort the
     * request). Every other build failure — including an {@link IllegalStateException}
     * raised by a custom {@link SignatureParametersFactory} for an unrelated reason —
     * fails OPEN (the request proceeds unsigned), because the backend is the
     * enforcement point for message signatures.
     */
    public static class RequiredBodyDigestException extends IllegalStateException {
        public RequiredBodyDigestException(String message) {
            super(message);
        }
    }

    /**
     * OkHttpComponentProvider implements the ComponentProvider interface for
     * OkHttp3 requests.
     */
    public static final class OkHttpComponentProvider implements ComponentProvider {
        private Request request;

        private HttpUrl okURL;

        private URI jURI;

        /**
         * Constructs an instance of {@code OkHttpComponentProvider}.
         *
         * @param request The OkHttp request to wrap.
         */
        OkHttpComponentProvider(Request request) {
            this.request = request;
            this.okURL = request.url();
            this.jURI = okURL.uri();
        }

        public Request getRequest() {
            return request;
        }

        public void setRequest(Request request) {
            this.request = request;
            this.okURL = request.url();
            this.jURI = okURL.uri();
        }

        @Override
        public String getMethod() {
            return request.method();
        }

        @Override
        public String getAuthority() {
            return okURL.host();
        }

        @Override
        public String getScheme() {
            return okURL.scheme();
        }

        @Override
        public String getTargetUri() {
            return jURI.toString();
        }

        @Override
        public String getRequestTarget() {
            String reqt = "";
            if (jURI.getRawPath() != null) {
                reqt += okURL.encodedPath();
            }
            if (jURI.getRawQuery() != null) {
                reqt += "?" + okURL.encodedQuery();
            }
            return reqt;
        }

        @Override
        public String getPath() {
            return okURL.encodedPath();
        }

        @Override
        public String getQuery() {
            return okURL.encodedQuery();
        }

        @Override
        public String getQueryParam(String name) {
            List<String> values = okURL.queryParameterValues(name);
            if (values.isEmpty()) {
                throw new IllegalArgumentException("Could not find query parameter named " + name);
            } else if (values.size() > 1) {
                // From Section 2.2.8 of the spec: If a parameter name occurs multiple times in
                // a request, the
                // named query parameter MUST NOT be included. If multiple parameters are common
                // within
                // an application, it is RECOMMENDED to sign the entire query string using the
                // @query
                // component identifier defined in Section 2.2.7.

                // to indicate that a query param must not be included, we return null
                return null;
            }
            return values.get(0);
        }

        @Override
        public String getStatus() {
            throw new IllegalStateException("Only requests are supported");
        }

        @Override
        public boolean hasField(String name) {
            List<String> headers = request.headers(name);
            return !headers.isEmpty();
        }

        @Override
        public String getField(String name) {
            List<String> headers = request.headers(name);
            return ComponentProvider.combineFieldValues(headers);
        }

        @Override
        public boolean hasBody() {
            return request.body() != null;
        }
    }
}
