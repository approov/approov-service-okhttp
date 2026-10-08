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

// ApproovFetchStatusException is thrown if an Approov fetch returns a status other than success.
public class ApproovFetchStatusException extends ApproovException {
    // the fetch status returned by the Approov SDK, or null if not available
    private final Approov.TokenFetchStatus tokenFetchStatus;

    /**
     * Constructs an exception due to an Approov fetch status.
     *
     * @param status is the fetch status returned by the Approov SDK, or null if not available
     * @param message is the basic information about the exception cause
     */
    public ApproovFetchStatusException(Approov.TokenFetchStatus status, String message) {
        super(message);
        this.tokenFetchStatus = status;
    }

    /**
     * Gets the fetch status associated with this exception.
     *
     * @return the status returned by the Approov SDK, or null if not available
     */
    public Approov.TokenFetchStatus getTokenFetchStatus() {
        return tokenFetchStatus;
    }
}
