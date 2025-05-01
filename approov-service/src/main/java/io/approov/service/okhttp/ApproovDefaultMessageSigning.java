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

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.approov.util.http.sfv.ByteSequenceItem;
import io.approov.util.http.sfv.Dictionary;
import io.approov.util.sig.ComponentProvider;
import io.approov.util.sig.SignatureBaseBuilder;
import io.approov.util.sig.SignatureParameters;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okio.Buffer;
import okio.BufferedSink;
import okio.ByteString;
import okio.Okio;

public class ApproovDefaultMessageSigning implements ApproovLifecycleCallbackHandler{
    public static final String DIGEST_SHA256 = "sha-256";
    public static final String DIGEST_SHA512 = "sha-512";
    public final static String ALG_ES256 = "ecdsa-p256-sha256";
    public final static String ALG_HS256 = "hmac-sha256";

    protected SignatureParametersFactory defaultFactory;
    protected final Map<String,SignatureParametersFactory> hostFactories;

    public ApproovDefaultMessageSigning() {
        hostFactories = new HashMap<>();
    }

    public ApproovDefaultMessageSigning setDefaultFactory(SignatureParametersFactory factory) {
        this.defaultFactory = factory;
        return this;
    }

    public ApproovDefaultMessageSigning putHostFactory(String hostName, SignatureParametersFactory factory) {
        this.hostFactories.put(hostName, factory);
        return this;
    }

    protected SignatureParameters buildSignatureParameters(OkHttpComponentProvider provider, ApproovRequestMutations changes) {
        SignatureParametersFactory factory = hostFactories.get(provider.getAuthority());
        if (factory == null) {
            factory = defaultFactory;
            if (factory == null) {
                return null;
            }
        }
        return factory.buildSignatureParameters(provider, changes);
    }

    @Override
    public Request processedRequest(Request request, ApproovRequestMutations changes) throws ApproovException {
        // generate and add a message signature
        OkHttpComponentProvider provider = new OkHttpComponentProvider(request);
        SignatureParameters params = buildSignatureParameters(provider, changes);
        if (params == null) {
            // No sig to be added to the request; return the original request.
            return request;
        }

        // Apply the params to get the message
        SignatureBaseBuilder baseBuilder = new SignatureBaseBuilder(params, provider);
        String message = baseBuilder.createSignatureBase();
        // Generate the signature
        String sigId;
        byte[] signature;
        switch (params.getAlg()) {
            case ALG_ES256: {
                sigId = "device";
                String base64 = ApproovService.getDeviceMessageSignature(message);
                signature = Base64.decode(base64, Base64.DEFAULT);
                break;
            }
            case ALG_HS256: {
                sigId = "account";
                String base64 = ApproovService.getAccountMessageSignature(message);
                signature = Base64.decode(base64, Base64.DEFAULT);
                break;
            }
            default:
                throw new IllegalStateException("Unsupported algorithm identifier: " + params.getAlg());
        }

        // Calculate the signature and message descriptor headers
        Dictionary sigHeader = Dictionary.valueOf(Map.of(
                sigId, ByteSequenceItem.valueOf(signature)));

        Dictionary sigInputHeader = Dictionary.valueOf(Map.of(
                sigId, params.toComponentValue()));

        // Update the request from the one held by the component provider as the signature builder
        // may have modified it.
        return provider.getRequest().newBuilder()
                .addHeader("Signature", sigHeader.serialize())
                .addHeader("Signature-Input", sigInputHeader.serialize())
                .build();
    }

    public static SignatureParametersFactory generateDefaultSignatureParametersFactory() {
        return generateDefaultSignatureParametersFactory(null);
    }

    public static SignatureParametersFactory generateDefaultSignatureParametersFactory(
            SignatureParameters baseParametersOverride
    ) {
        SignatureParameters baseParameters;
        if (baseParametersOverride != null) {
            baseParameters = baseParametersOverride;
        } else {
            baseParameters = new SignatureParameters()
                    .addComponentIdentifier(ComponentProvider.DC_METHOD)
                    .addComponentIdentifier(ComponentProvider.DC_TARGET_URI)
                    ;
        }
        return new SignatureParametersFactory()
                .setBaseParameters(baseParameters)
                .setUseDeviceMessageSigning()
                .setAddCreated(true)
                .setExpiresLifetime(5)
                .addOptionalHeaders("Content-Length", "Content-Type")
                .setBodyDigestConfig(DIGEST_SHA256, false)
                ;
    }

    public static class SignatureParametersFactory {
        protected SignatureParameters baseParameters;
        protected String bodyDigestAlgorithm;
        protected boolean bodyDigestRequired;
        protected boolean useAccountMessageSigning;
        protected boolean addCreated;
        protected long expiresLifetime;
        protected boolean addApproovTokenHeader;
        protected List<String> optionalHeaders;

        public SignatureParametersFactory setBaseParameters(SignatureParameters baseParameters) {
            this.baseParameters = baseParameters;
            return this;
        }

        /**
         * Set the body digest configuration for the factory.
         *
         * @param bodyDigestAlgorithm Specify the digest algorithm to use or null to disable the
         *                           addition of a digest (in which case the required parameter is
         *                           ignored)
         * @param required true to indicate that a body digest must be added; an
         *                IllegalStateException is thrown if a body digest could not be added for a
         *                request. The default implementation cannot add body digests for requests
         *                with no body, one shot bodies, bodies of unknown length, or ones that
         *                cannot be read for some other reason.
         * @return <code>this</code> to enable method chaining for factory configuration
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

        public SignatureParametersFactory setUseDeviceMessageSigning() {
            this.useAccountMessageSigning = false;
            return this;
        }

        public SignatureParametersFactory setUseAccountMessageSigning() {
            this.useAccountMessageSigning = true;
            return this;
        }

        public SignatureParametersFactory setAddCreated(boolean addCreated) {
            this.addCreated = addCreated;
            return this;
        }

        public SignatureParametersFactory setExpiresLifetime(long expiresLifetime) {
            this.expiresLifetime = expiresLifetime;
            return this;
        }

        public SignatureParametersFactory setAddApproovTokenHeader(boolean addApproovTokenHeader) {
            this.addApproovTokenHeader = addApproovTokenHeader;
            return this;
        }
        public SignatureParametersFactory addOptionalHeaders(String ... headers) {
            if (this.optionalHeaders == null) {
                this.optionalHeaders = new ArrayList<>(Arrays.asList(headers));
            } else {
                this.optionalHeaders.addAll(Arrays.asList(headers));
            }
            return this;
        }

        protected boolean generateBodyDigest(OkHttpComponentProvider provider, SignatureParameters requestParameters) {
            RequestBody body = provider.request.body();
            // ignore null bodies, one shot bodies, or bodies of unknown length as these will
            // likely require more specific knowledge
            if (body == null || body.isOneShot()) {
                return false;
            } else {
                try {
                    if (body.contentLength() <= 0 ) {
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
            Dictionary digestHeader = Dictionary.valueOf(Map.of(
                    bodyDigestAlgorithm, ByteSequenceItem.valueOf(digest.toByteArray())));

            // add the digest to the request
            Request request = provider.getRequest();
            request = request.newBuilder()
                    .addHeader("Content-Digest", digestHeader.serialize())
                    .build();
            provider.setRequest(request);
            // add the header to the SignatureParameters
            requestParameters.addComponentIdentifier("Content-Digest");
            return true;
        }

        protected SignatureParameters buildSignatureParameters(OkHttpComponentProvider provider, ApproovRequestMutations changes) {
            SignatureParameters requestParameters = new SignatureParameters(baseParameters);
            if (useAccountMessageSigning) {
                requestParameters.setAlg(ALG_HS256);
            } else {
                requestParameters.setAlg(ALG_ES256);
            }
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
            for (String headerName: optionalHeaders) {
                if (provider.hasField(headerName)) {
                    requestParameters.addComponentIdentifier(headerName);
                }
            }
            if (bodyDigestAlgorithm != null) {
                if (!generateBodyDigest(provider, requestParameters) && bodyDigestRequired) {
                    throw new IllegalStateException("Failed to create required body digest");
                }
            }
            return requestParameters;
        }
    }

    /**
     * OkHttpComponentProvider implements the ComponentProvider interface for OkHttp3 requests.
     */
    protected static final class OkHttpComponentProvider implements ComponentProvider {
        private Request request;
        
        private HttpUrl okURL;
        
        private URI jURI;
        
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
                reqt += jURI.getRawPath();
            }
            if (jURI.getRawQuery() != null) {
                reqt += "?" + jURI.getRawQuery();
            }
            return reqt;
        }

        @Override
        public String getPath() {
            return jURI.getRawPath();
        }

        @Override
        public String getQuery() {
            return jURI.getRawQuery();
        }

        @Override
        public String getQueryParam(String name) {
            List<String> values = okURL.queryParameterValues(name);
            if (values.isEmpty()) {
                throw new IllegalArgumentException("Could not find query parameter named " + name);
            } else if (values.size() > 1) {
                // From 2.2.8 of the spec: If a parameter name occurs multiple times in a request, the
                // named query parameter MUST NOT be included. If multiple parameters are common within
                // an application, it is RECOMMENDED to sign the entire query string using the @query
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
            return headers.isEmpty();
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
