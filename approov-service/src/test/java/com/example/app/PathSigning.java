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

package com.example.app;

import io.approov.service.okhttp.ApproovDefaultMessageSigning;
import io.approov.service.okhttp.ApproovRequestMutations;
import io.approov.util.okhttp.sig.SignatureParameters;

// PathSigning is message signing that chooses the signature parameters per request path with the 3.5.8 API: it
// subclasses ApproovDefaultMessageSigning and overrides its protected buildSignatureParameters. A request whose
// path starts with /download is handed to a second signer configured with the download factory, and every other
// request to this signer's own host or default factory. It lives outside the library package, as app code does,
// so it only compiles while buildSignatureParameters and OkHttpComponentProvider are accessible to a subclass.
public class PathSigning extends ApproovDefaultMessageSigning {
    // the signer for download requests, or null in that signer itself
    private final PathSigning downloadSigning;

    // a signer used for download requests only
    private PathSigning() {
        this.downloadSigning = null;
    }

    /**
     * Constructs path dependent message signing.
     *
     * @param downloadFactory is the factory for the parameters of requests whose path starts with /download
     */
    public PathSigning(SignatureParametersFactory downloadFactory) {
        this.downloadSigning = new PathSigning();
        this.downloadSigning.setDefaultFactory(downloadFactory);
    }

    @Override
    protected SignatureParameters buildSignatureParameters(OkHttpComponentProvider provider,
            ApproovRequestMutations changes) throws RequiredBodyDigestException {
        if ((downloadSigning != null) && provider.getPath().startsWith("/download"))
            return downloadSigning.buildSignatureParameters(provider, changes);
        return super.buildSignatureParameters(provider, changes);
    }
}
