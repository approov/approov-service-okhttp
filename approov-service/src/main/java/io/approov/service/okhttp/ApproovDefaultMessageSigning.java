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

import io.approov.util.okhttp.tink.subtle.EllipticCurves;

import java.io.IOException;
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
 * ApproovDefaultMessageSigning provides HTTP message signing (RFC 9421) of the requests Approov protects,
 * and the signature parameters factory that configures it. Message signing is enabled with
 * ApproovService.enableMessageSigning, whichever service mutator makes the request decisions, and every
 * request carrying the Approov token header is then signed last, so that the signature covers what is
 * sent. It is signed again whenever its protection is reapplied.
 * <p>
 * The default factory produces both the install signature (ECDSA P-256 with a per app installation key
 * held in the device secure hardware) and the account signature (HMAC-SHA256 with the account key), as
 * the members "install" and "account" of the same Signature and Signature-Input headers, so that a device
 * without secure hardware still provides a verifiable signature. A signature that cannot be produced is
 * omitted and the request proceeds with the other one, or unsigned, since your backend API is the
 * enforcement point. The only failures that abort a request are configuration errors: a required body
 * digest that cannot be generated, or an unsupported signature algorithm.
 * <p>
 * Apps never construct this class, but pass a SignatureParametersFactory to
 * ApproovService.enableMessageSigning or ApproovService.putMessageSigningHostFactory.
 */
public class ApproovDefaultMessageSigning {
    // logging tag
    private static final String TAG = "ApproovMsgSign";

    // the SHA-256 digest algorithm, used for body digests
    public static final String DIGEST_SHA256 = "sha-256";

    // the SHA-512 digest algorithm, used for body digests
    public static final String DIGEST_SHA512 = "sha-512";

    // the ECDSA P-256 with SHA-256 algorithm, used when signing with the install private key
    public final static String ALG_ES256 = "ecdsa-p256-sha256";

    // the HMAC with SHA-256 algorithm, used when signing with the account signing key
    public final static String ALG_HS256 = "hmac-sha256";

    // the Signature dictionary member name for the install message signature
    public final static String SIG_ID_INSTALL = "install";

    // the Signature dictionary member name for the account message signature
    public final static String SIG_ID_ACCOUNT = "account";

    // the factory used for every host without a factory of its own, or null to sign nothing, which is
    // volatile as it is read on the request threads
    private volatile SignatureParametersFactory defaultFactory;

    // factories for individual hosts, keyed by the lowercase host name without any port
    private final Map<String, SignatureParametersFactory> hostFactories = new ConcurrentHashMap<>();

    /**
     * Constructs a signer with no factory, which is only done by ApproovService.
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
     * @param factory is the factory to be used, or null to sign nothing for such hosts
     * @return this signer
     */
    ApproovDefaultMessageSigning setDefaultFactory(SignatureParametersFactory factory) {
        this.defaultFactory = factory;
        return this;
    }

    /**
     * Sets the factory used for requests to one host instead of the default factory.
     *
     * @param hostName is the host name, matched without regard to case and without any port
     * @param factory is the factory for the host, or null to use the default factory again
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
     * Selects the factory for a request, which is the factory of its host if there is one or otherwise
     * the default factory.
     *
     * @param provider is the component provider for the request
     * @return the factory, or null if none applies
     */
    private SignatureParametersFactory factoryFor(OkHttpComponentProvider provider) {
        SignatureParametersFactory factory = hostFactories.get(provider.getHost().toLowerCase(Locale.ROOT));
        return (factory != null) ? factory : defaultFactory;
    }

    /**
     * Signs a request that the Approov interceptor has protected. The request is only modified if it
     * carries the Approov token header and a factory applies to its host. Any failure other than a
     * configuration error leaves the request unsigned.
     *
     * @param request is the request as the Approov interceptor and the service mutator left it
     * @param changes is the protection the Approov interceptor applied to it
     * @return the request with the signature headers added, or the request unchanged if it is not signed
     * @throws RequiredBodyDigestException if a body digest configured as required cannot be generated
     * @throws ApproovException if a signature algorithm is unsupported
     */
    Request sign(Request request, ApproovRequestMutations changes) throws ApproovException {
        // any failure other than the configuration errors, including a RuntimeException from the SDK, from a
        // custom factory or from building the headers, leaves the request unsigned so that nothing but an
        // IOException leaves the request path
        try {
            return signOrFail(request, changes);
        } catch (RuntimeException e) {
            ApproovLog.e(TAG, "Message signing failed - proceeding unsigned: " + e);
            return request;
        }
    }

    /**
     * Signs a request, throwing any RuntimeException for sign to handle.
     *
     * @param request is the request as the Approov interceptor and the service mutator left it
     * @param changes is the protection the Approov interceptor applied to it
     * @return the request with the signature headers added, or the request unchanged if it is not signed
     * @throws RequiredBodyDigestException if a body digest configured as required cannot be generated
     * @throws ApproovException if a signature algorithm is unsupported
     */
    private Request signOrFail(Request request, ApproovRequestMutations changes) throws ApproovException {
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

        // build the signature parameters, which only aborts the request if a body digest configured as
        // required cannot be generated, while any other failure (including from a custom factory) leaves
        // the request unsigned as the backend is the enforcement point
        SignatureParameters params;
        try {
            params = factory.buildSignatureParameters(provider, changes);
        } catch (RequiredBodyDigestException e) {
            throw e;
        } catch (Exception e) {
            ApproovLog.e(TAG, "Failed to build signature parameters - proceeding unsigned: " + e);
            return request;
        }
        if (params == null) {
            // no signature is to be added to the request
            return request;
        }

        // determine the algorithms to sign with, which is a single algorithm if the factory sets one
        // explicitly on the parameters, or otherwise the algorithms configured on the factory (by default
        // both the install and the account signature)
        List<String> algs;
        if (params.getAlg() != null) {
            algs = Collections.singletonList(params.getAlg());
        } else {
            // a factory with no algorithm enabled throws IllegalStateException here, which leaves the
            // request unsigned
            algs = factory.getAlgs();
        }
        for (String alg : algs) {
            // an unsupported algorithm is a configuration error and aborts the request, as an IOException so
            // that an enqueued call reports it to onFailure
            if (!ALG_ES256.equals(alg) && !ALG_HS256.equals(alg))
                throw new ApproovException("Unsupported algorithm identifier: " + alg);
        }

        // generate a signature per algorithm over the same covered components, each with its own signature
        // base since the base includes the algorithm
        Map<String, ListElement<?>> signatures = new LinkedHashMap<>();
        Map<String, ListElement<?>> signatureInputs = new LinkedHashMap<>();
        Map<String, String> messages = new LinkedHashMap<>();
        for (String alg : algs) {
            SignatureParameters algParams = new SignatureParameters(params).setAlg(alg);
            // apply the params to get the message, leaving the request unsigned if this fails; WARNING never
            // log the message as it contains an Approov token which provides access to your API
            String message;
            try {
                message = new SignatureBaseBuilder(algParams, provider).createSignatureBase();
            } catch (Exception e) {
                ApproovLog.e(TAG, "Failed to build signature base - proceeding unsigned: " + e);
                return request;
            }
            String sigId = ALG_ES256.equals(alg) ? SIG_ID_INSTALL : SIG_ID_ACCOUNT;
            byte[] signature = ALG_ES256.equals(alg) ? installSignature(message) : accountSignature(message);
            if (signature == null) {
                // this signature could not be produced so we proceed with any other
                ApproovLog.e(TAG, "Skipping " + sigId + " message signature");
                continue;
            }
            signatures.put(sigId, ByteSequenceItem.valueOf(signature));
            signatureInputs.put(sigId, algParams.toComponentValue());
            messages.put(sigId, message);
        }
        if (signatures.isEmpty()) {
            ApproovLog.e(TAG, "No message signature could be produced - proceeding unsigned");
            return request;
        }

        // each signature is a Byte Sequence member of the same Signature and Signature-Input dictionaries,
        // serialized as colon delimited base64 (for example install=:<base64>:)
        String sigHeader = Dictionary.valueOf(signatures).serialize();
        String sigInputHeader = Dictionary.valueOf(signatureInputs).serialize();

        // update the request from the one held by the component provider as the signature builder may have
        // modified it
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
                ApproovLog.d(TAG, "Failed to get digest algorithm - no debug entry " + e);
            }
        }
        Request signed = signedBuilder.build();
        return signed;
    }

    /**
     * Produces the install message signature over the given message as the raw 64 byte r||s value that
     * ecdsa-p256-sha256 requires.
     *
     * @param message is the signature base
     * @return the signature bytes, or null if not available
     */
    private static byte[] installSignature(String message) {
        String base64;
        try {
            base64 = ApproovService.getInstallMessageSignature(message);
        } catch (ApproovException e) {
            ApproovLog.e(TAG, "Failed to get InstallMessageSignature: " + e);
            return null;
        }
        if (base64.isEmpty()) {
            ApproovLog.e(TAG, "InstallMessageSignature is empty");
            return null;
        }
        byte[] signature;
        try {
            signature = Base64.decode(base64, Base64.NO_WRAP);
        } catch (Exception e) {
            ApproovLog.e(TAG, "Failed to decode base64 signature: " + e);
            return null;
        }
        // decode the signature from ASN.1 DER format
        try {
            return es256DerToRaw(signature);
        } catch (Exception e) {
            ApproovLog.e(TAG, "Failed to decode ASN.1 DER ES256 signature", e);
            return null;
        }
    }

    /**
     * Converts an ASN.1 DER encoded ECDSA P-256 signature, as the SDK returns the install message signature,
     * to the raw 64 byte r||s form that ecdsa-p256-sha256 requires. Only the canonical DER encoding of the
     * result is accepted, since the Tink conversion does not check that r and s fit in 32 bytes.
     *
     * @param der is the DER encoded signature
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
     * Produces the account message signature over the given message, which may not be available as the
     * account key is only delivered on a successful attestation.
     *
     * @param message is the signature base
     * @return the signature bytes, or null if not available
     */
    private static byte[] accountSignature(String message) {
        String base64;
        try {
            base64 = ApproovService.getAccountMessageSignature(message);
        } catch (ApproovException e) {
            ApproovLog.e(TAG, "Failed to get AccountMessageSignature: " + e);
            return null;
        }
        if (base64.isEmpty()) {
            ApproovLog.e(TAG, "AccountMessageSignature is empty");
            return null;
        }
        try {
            return Base64.decode(base64, Base64.NO_WRAP);
        } catch (Exception e) {
            ApproovLog.e(TAG, "Failed to decode base64 signature: " + e);
            return null;
        }
    }

    /**
     * Generates a default SignatureParametersFactory with predefined settings.
     *
     * @return a new instance of SignatureParametersFactory
     */
    public static SignatureParametersFactory generateDefaultSignatureParametersFactory() {
        return generateDefaultSignatureParametersFactory(null);
    }

    /**
     * Generates a default SignatureParametersFactory with optional base parameters.
     *
     * @param baseParametersOverride is the base parameters to be used, or null to use the defaults
     * @return a new instance of SignatureParametersFactory
     */
    public static SignatureParametersFactory generateDefaultSignatureParametersFactory(
            SignatureParameters baseParametersOverride) {
        // default expiry seconds - must encompass worst case request retry time and clock skew
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

    // SignatureParametersFactory creates the SignatureParameters for each request from its configured settings
    public static class SignatureParametersFactory {
        // the base parameters that are copied for every new message signature, initially empty so that a
        // bare factory is safe to use
        protected SignatureParameters baseParameters = new SignatureParameters();

        // the algorithm to use for body digests, or null if no body digest is to be used
        protected String bodyDigestAlgorithm;

        // true if a body digest is required, noting that one cannot be generated for every request as it
        // may have no body or a one shot body
        protected boolean bodyDigestRequired;

        // true to produce the install message signature (ECDSA P-256 with the per installation key)
        protected boolean useInstallMessageSigning = true;

        // true to produce the account message signature (HMAC-SHA256 with the account key), so that by
        // default a bare factory produces both signatures
        protected boolean useAccountMessageSigning = true;

        // true to add the "created" timestamp field to the signature parameters
        protected boolean addCreated;

        // the expiration lifetime in seconds, where the "expires" field is only added if this is >0
        protected long expiresLifetime;

        // true to add the Approov token header to the signature parameters, which is strongly advised
        protected boolean addApproovTokenHeader;

        // true to add any Approov TraceID header to the signature parameters
        protected boolean addApproovTraceIDHeader;

        // true to add any Approov status header to the signature parameters, so that the reported fetch
        // status cannot be stripped or altered without invalidating the signature
        protected boolean addApproovStatusHeader;

        // the headers to add to the message signature if they are present in the request (headers that are
        // always required should be added to the base parameters), initially empty so that a bare factory
        // is safe to use
        protected List<String> optionalHeaders = new ArrayList<>();

        /**
         * Sets the base parameters for the factory, which are copied for every new message signature.
         *
         * @param baseParameters is the base parameters to be used
         * @return this factory for method chaining
         */
        public SignatureParametersFactory setBaseParameters(SignatureParameters baseParameters) {
            this.baseParameters = baseParameters;
            return this;
        }

        /**
         * Sets the body digest configuration for the factory. If set, then a request with a body has the
         * digest added as a header, which is included in the message signature.
         *
         * @param bodyDigestAlgorithm is the digest algorithm to use, or null to disable body digests
         * @param required is true if the body digest is required
         * @return this factory for method chaining
         * @throws IllegalArgumentException if an unsupported algorithm is specified
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
         * Sets the factory to produce only the install message signature.
         *
         * @return this factory for method chaining
         */
        public SignatureParametersFactory setUseInstallMessageSigning() {
            this.useInstallMessageSigning = true;
            this.useAccountMessageSigning = false;
            return this;
        }

        /**
         * Sets the factory to produce only the account message signature.
         *
         * @return this factory for method chaining
         */
        public SignatureParametersFactory setUseAccountMessageSigning() {
            this.useInstallMessageSigning = false;
            this.useAccountMessageSigning = true;
            return this;
        }

        /**
         * Sets the factory to produce both the install and the account message signatures, as the members
         * "install" and "account" of the same Signature and Signature-Input headers over the same covered
         * components. This is the default of a newly constructed factory.
         *
         * @return this factory for method chaining
         */
        public SignatureParametersFactory setUseInstallAndAccountMessageSigning() {
            this.useInstallMessageSigning = true;
            this.useAccountMessageSigning = true;
            return this;
        }

        /**
         * Gets the signature algorithms configured on this factory, in the order the signatures are emitted.
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
         * Sets whether the "created" field should be added to the signature parameters, which holds the
         * device timestamp indicating when the request was created.
         *
         * @param addCreated is true to add the "created" field
         * @return this factory for method chaining
         */
        public SignatureParametersFactory setAddCreated(boolean addCreated) {
            this.addCreated = addCreated;
            return this;
        }

        /**
         * Sets the expiration lifetime for the signature parameters. Only a value >0 causes the "expires"
         * field to be added, which holds the timestamp when the message signature expires, equal to the
         * created timestamp plus the expiration lifetime.
         *
         * @param expiresLifetime is the expiration lifetime in seconds, or <=0 to add no expiration
         * @return this factory for method chaining
         */
        public SignatureParametersFactory setExpiresLifetime(long expiresLifetime) {
            this.expiresLifetime = expiresLifetime;
            return this;
        }

        /**
         * Sets whether the Approov token header should be added to the signature parameters.
         *
         * @param addApproovTokenHeader is true to add the Approov token header
         * @return this factory for method chaining
         */
        public SignatureParametersFactory setAddApproovTokenHeader(boolean addApproovTokenHeader) {
            this.addApproovTokenHeader = addApproovTokenHeader;
            return this;
        }

        /**
         * Sets whether the optional Approov TraceID header should be added to the signature parameters.
         *
         * @param addApproovTraceIDHeader is true to add the Approov TraceID header
         * @return this factory for method chaining
         */
        public SignatureParametersFactory setAddApproovTraceIDHeader(boolean addApproovTraceIDHeader) {
            this.addApproovTraceIDHeader = addApproovTraceIDHeader;
            return this;
        }

        /**
         * Sets whether the Approov status header, if present on the request, is covered by the signature so
         * that the reported fetch status cannot be stripped or altered without invalidating the signature.
         *
         * @param addApproovStatusHeader is true to cover the Approov status header
         * @return this factory for method chaining
         */
        public SignatureParametersFactory setAddApproovStatusHeader(boolean addApproovStatusHeader) {
            this.addApproovStatusHeader = addApproovStatusHeader;
            return this;
        }

        /**
         * Adds optional headers to the signature parameters. A header configured as optional is added to
         * the generated SignatureParameters if the request includes it, and is otherwise ignored.
         *
         * @param headers is the headers to be added
         * @return this factory for method chaining
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
         * @param provider is the component provider for the request
         * @param requestParameters is the signature parameters to be updated
         * @return true if the body digest was generated, false otherwise
         */
        protected boolean generateBodyDigest(OkHttpComponentProvider provider, SignatureParameters requestParameters) {
            // ignore null bodies, one shot bodies, or bodies of unknown length as these will likely require
            // more specific knowledge, while an empty body has a digest like any other
            RequestBody body = provider.request.body();
            if (body == null || body.isOneShot()) {
                return false;
            } else {
                try {
                    if (body.contentLength() < 0) {
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
         * @param provider is the component provider for the request
         * @param changes is the mutations applied to the request by Approov
         * @return the generated SignatureParameters
         * @throws RequiredBodyDigestException if a body digest configured as required cannot be generated
         */
        protected SignatureParameters buildSignatureParameters(OkHttpComponentProvider provider,
                ApproovRequestMutations changes) throws RequiredBodyDigestException {
            // the algorithm is left unset so that one signature per configured algorithm is produced over
            // these same parameters, while a subclass may set an explicit algorithm for a single signature
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

    // RequiredBodyDigestException is thrown if a body digest configured as required cannot be generated, which
    // aborts the request
    public static class RequiredBodyDigestException extends ApproovException {
        /**
         * Constructs an exception due to a required body digest that cannot be generated.
         *
         * @param message is the basic information about the exception cause
         */
        public RequiredBodyDigestException(String message) {
            super(message);
        }
    }

    // OkHttpComponentProvider implements the ComponentProvider interface for OkHttp requests, deriving every
    // component from the request URL exactly as OkHttp sends it so that the backend reconstructs the same
    // values, and never parsing it again with java.net.URI, which encodes some characters differently
    public static final class OkHttpComponentProvider implements ComponentProvider {
        // the request being signed
        private Request request;

        // the URL of the request
        private HttpUrl okURL;

        /**
         * Constructs a component provider for a request.
         *
         * @param request is the OkHttp request to be wrapped
         */
        OkHttpComponentProvider(Request request) {
            this.request = request;
            this.okURL = request.url();
        }

        /**
         * Gets the request being signed.
         *
         * @return the request
         */
        public Request getRequest() {
            return request;
        }

        /**
         * Sets the request being signed, such as when a body digest header is added.
         *
         * @param request is the new request
         */
        public void setRequest(Request request) {
            this.request = request;
            this.okURL = request.url();
        }

        /**
         * Gets the host of the request without any port, as OkHttp holds it (in lowercase, and an IPv6
         * address without brackets), which is used to select a host factory.
         *
         * @return the host of the request
         */
        String getHost() {
            return okURL.host();
        }

        @Override
        public String getMethod() {
            return request.method();
        }

        /**
         * Gets the @authority derived component, which is the lowercase host, in brackets for an IPv6
         * address, followed by the port only if it is not the default port of the scheme.
         *
         * @return the authority of the request
         */
        @Override
        public String getAuthority() {
            String host = okURL.host();
            String authority = (host.indexOf(':') >= 0) ? "[" + host + "]" : host;
            if (okURL.port() != HttpUrl.defaultPort(okURL.scheme()))
                authority += ":" + okURL.port();
            return authority;
        }

        @Override
        public String getScheme() {
            return okURL.scheme();
        }

        /**
         * Gets the @target-uri derived component, which is the URL as OkHttp sends it, leaving out any
         * fragment as that is never sent.
         *
         * @return the target URI of the request
         */
        @Override
        public String getTargetUri() {
            String url = okURL.toString();
            String fragment = okURL.encodedFragment();
            if (fragment != null)
                url = url.substring(0, url.length() - fragment.length() - 1);
            return url;
        }

        /**
         * Gets the @request-target derived component, which is the path and query as they appear in the
         * request line.
         *
         * @return the request target of the request
         */
        @Override
        public String getRequestTarget() {
            String query = okURL.encodedQuery();
            return (query == null) ? okURL.encodedPath() : okURL.encodedPath() + "?" + query;
        }

        @Override
        public String getPath() {
            return okURL.encodedPath();
        }

        /**
         * Gets the @query derived component, which is the query as sent with its leading "?", or "?" alone
         * if there is none.
         *
         * @return the query of the request
         */
        @Override
        public String getQuery() {
            String query = okURL.encodedQuery();
            return (query == null) ? "?" : "?" + query;
        }

        /**
         * Gets the @query-param derived component for a name, which is the encoded name from the component
         * identifier. The value is the decoded value of the parameter encoded again with the
         * application/x-www-form-urlencoded percent-encode set, with a space as %20.
         *
         * @param name is the encoded name of the query parameter
         * @return the value of the query parameter, or null if it occurs more than once
         */
        @Override
        public String getQueryParam(String name) {
            List<String> values = okURL.queryParameterValues(formDecode(name));
            if (values.isEmpty()) {
                throw new IllegalArgumentException("Could not find query parameter named " + name);
            } else if (values.size() > 1) {
                // a query parameter that occurs more than once must not be included in the signature, which we
                // indicate by returning null, and the whole @query should be signed instead
                return null;
            }
            String value = values.get(0);
            return (value == null) ? "" : formEncode(value);
        }

        // decodes application/x-www-form-urlencoded text where + is a space and %XX a byte of UTF-8, keeping
        // a malformed escape as it is
        private static String formDecode(String text) {
            if (text.indexOf('%') < 0 && text.indexOf('+') < 0)
                return text;
            Buffer out = new Buffer();
            for (int i = 0; i < text.length(); ) {
                int c = text.codePointAt(i);
                if (c == '+') {
                    out.writeByte(' ');
                } else if (c == '%' && i + 2 < text.length()
                        && Character.digit(text.charAt(i + 1), 16) >= 0
                        && Character.digit(text.charAt(i + 2), 16) >= 0) {
                    out.writeByte((Character.digit(text.charAt(i + 1), 16) << 4)
                            | Character.digit(text.charAt(i + 2), 16));
                    i += 3;
                    continue;
                } else {
                    out.writeUtf8CodePoint(c);
                }
                i += Character.charCount(c);
            }
            return out.readUtf8();
        }

        // percent-encodes UTF-8 text with the application/x-www-form-urlencoded percent-encode set, which
        // leaves only ASCII letters, digits and *-._
        private static String formEncode(String text) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            StringBuilder out = new StringBuilder(bytes.length);
            for (byte b : bytes) {
                int c = b & 0xff;
                if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                        || c == '*' || c == '-' || c == '.' || c == '_') {
                    out.append((char) c);
                } else {
                    out.append('%').append(HEX_DIGITS[c >> 4]).append(HEX_DIGITS[c & 0xf]);
                }
            }
            return out.toString();
        }

        // the hexadecimal digits used for percent-encoding
        private static final char[] HEX_DIGITS = "0123456789ABCDEF".toCharArray();

        @Override
        public String getStatus() {
            throw new IllegalStateException("Only requests are supported");
        }

        @Override
        public boolean hasField(String name) {
            return !fieldValues(name).isEmpty();
        }

        @Override
        public String getField(String name) {
            return ComponentProvider.combineFieldValues(fieldValues(name));
        }

        /**
         * Gets the values of a header field as OkHttp will send them. The request is signed before the OkHttp
         * BridgeInterceptor, which sets Content-Type from the media type of any body and Content-Length from
         * its length, removing Content-Length if the body is sent chunked, whatever the app set. Those two
         * fields are reported as it will set them, so that the signature covers the values on the wire,
         * while every other field is taken from the request.
         *
         * @param name is the field name
         * @return the values of the field, or an empty list if it is not sent
         */
        private List<String> fieldValues(String name) {
            RequestBody body = request.body();
            if (body != null) {
                if ("Content-Type".equalsIgnoreCase(name)) {
                    okhttp3.MediaType type = body.contentType();
                    if (type != null)
                        return Collections.singletonList(type.toString());
                } else if ("Content-Length".equalsIgnoreCase(name)) {
                    long length;
                    try {
                        length = body.contentLength();
                    } catch (IOException e) {
                        // OkHttp fails the request on the same exception before sending
                        return request.headers(name);
                    }
                    return (length != -1) ? Collections.singletonList(Long.toString(length))
                            : Collections.<String>emptyList();
                }
            }
            return request.headers(name);
        }

        @Override
        public boolean hasBody() {
            return request.body() != null;
        }
    }
}
