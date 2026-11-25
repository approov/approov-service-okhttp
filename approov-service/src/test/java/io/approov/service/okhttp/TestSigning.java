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

import com.criticalblue.approovsdk.Approov;

import io.approov.service.okhttp.ApproovDefaultMessageSigning.SignatureParametersFactory;

// TestSigning installs message signing the way an app does with the 3.5.8 API: an ApproovDefaultMessageSigning
// service mutator with a default factory, or a subclass of it that also makes the ALWAYS_PROCEED token decision.
final class TestSigning {
    private TestSigning() {
    }

    // installs message signing with the default factory, returning the installed signer
    static ApproovDefaultMessageSigning install() {
        return install(ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory());
    }

    // installs message signing with the given default factory, returning the installed signer
    static ApproovDefaultMessageSigning install(SignatureParametersFactory factory) {
        ApproovDefaultMessageSigning signing = new ApproovDefaultMessageSigning().setDefaultFactory(factory);
        ApproovService.setServiceMutator(signing);
        return signing;
    }

    // a signer with the default factory whose token decision is that of ALWAYS_PROCEED, so that a request
    // proceeds after any token fetch failure
    static ApproovDefaultMessageSigning alwaysProceed() {
        return new AlwaysProceedSigning()
                .setDefaultFactory(ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory());
    }

    // installs alwaysProceed(), returning the installed signer
    static ApproovDefaultMessageSigning installAlwaysProceed() {
        ApproovDefaultMessageSigning signing = alwaysProceed();
        ApproovService.setServiceMutator(signing);
        return signing;
    }

    // a message signing mutator that subclasses ApproovDefaultMessageSigning to make the ALWAYS_PROCEED token
    // decision instead of the interface default
    static class AlwaysProceedSigning extends ApproovDefaultMessageSigning {
        @Override
        public boolean handleInterceptorFetchTokenResult(Approov.TokenFetchResult approovResults, String url)
                throws ApproovException {
            return ApproovServiceMutator.ALWAYS_PROCEED.handleInterceptorFetchTokenResult(approovResults, url);
        }

        @Override
        public String toString() {
            return "AlwaysProceedSigning";
        }
    }
}
