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
import io.approov.service.okhttp.ApproovDefaultMessageSigning.OkHttpComponentProvider;
import io.approov.service.okhttp.ApproovDefaultMessageSigning.RequiredBodyDigestException;
import io.approov.service.okhttp.ApproovDefaultMessageSigning.SignatureParametersFactory;
import io.approov.service.okhttp.ApproovRequestMutations;
import io.approov.util.okhttp.sig.ComponentProvider;
import io.approov.util.okhttp.sig.SignatureParameters;

// DownloadPathFactory signs requests whose path starts with /download without the target URI and every
// other request with the default components. It lives outside the library package, as app code does.
public class DownloadPathFactory extends SignatureParametersFactory {
    // the factory for download requests, whose only base component is the method
    private final SignatureParametersFactory downloadFactory = ApproovDefaultMessageSigning
            .generateDefaultSignatureParametersFactory(
                    new SignatureParameters().addComponentIdentifier(ComponentProvider.DC_METHOD));

    // the factory for every other request
    private final SignatureParametersFactory defaultFactory =
            ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory();

    @Override
    public SignatureParameters buildSignatureParameters(OkHttpComponentProvider provider,
            ApproovRequestMutations changes) throws RequiredBodyDigestException {
        // choose the delegate by the request path
        if (provider.getPath().startsWith("/download")) {
            return downloadFactory.buildSignatureParameters(provider, changes);
        }
        return defaultFactory.buildSignatureParameters(provider, changes);
    }
}
