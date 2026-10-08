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

// ApproovNetworkException indicates an exception caused by networking conditions which is likely to be
// temporary so a user initiated retry should be performed. Deprecated: catch ApproovFetchStatusException
// instead, this subclass is kept so that existing handlers continue to work
@Deprecated
public class ApproovNetworkException extends ApproovFetchStatusException {

    /**
     * Constructs an Approov networking exception.
     *
     * @param message is the basic information about the exception cause
     */
    public ApproovNetworkException(String message) {
        super(null, message);
    }

    /**
     * Constructs an Approov networking exception with the fetch status that caused it.
     *
     * @param status is the fetch status that caused the exception
     * @param message is the basic information about the exception cause
     */
    public ApproovNetworkException(Approov.TokenFetchStatus status, String message) {
        super(status, message);
    }
}
