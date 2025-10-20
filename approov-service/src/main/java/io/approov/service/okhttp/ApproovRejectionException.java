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

// ApproovRejectionException provides additional information if the app has been rejected by Approov
public class ApproovRejectionException extends ApproovException {
    // provides a code of the app state for support purposes
    private String arc;

    // provides a comma separated list of rejection reasons (if the feature is enabled in Approov)
    private String rejectionReasons;

    /**
     * Constructs an exception if the app is rejected by Approov.
     *
     * @param errorCode identifies the rejection scenario
     * @param message is the basic information about the exception cause
     * @param arc is the code that can be used for support purposes
     * @param rejectionReasons may provide a comma separated list of rejection reasons
     */
    public ApproovRejectionException(int errorCode, String message, String arc, String rejectionReasons) {
        super(errorCode, message);
        this.arc = arc;
        this.rejectionReasons = rejectionReasons;
    }

    /**
     * Constructs an exception if the app is rejected by Approov (legacy signature).
     *
     * @param message is the basic information about the exception cause
     * @param arc is the code that can be used for support purposes
     * @param rejectionReasons may provide a comma separated list of rejection reasons
     * @deprecated Use {@link #ApproovRejectionException(int, String, String, String)} instead to provide a structured error code.
     */
    @Deprecated
    public ApproovRejectionException(String message, String arc, String rejectionReasons) {
        this(ApproovErrorCodes.LEGACY_REJECTION_ERROR, message, arc, rejectionReasons);
    }

    /**
     * Gets the ARC associated with the rejection, which may be used for support as Approov
     * cloud access allows the code to be mapped to device properties.
     *
     * @return ARC for the failure
     */
    public String getARC() {
        return arc;
    }

    /**
     * Gets a comma separated list of device properties that are causing a rejection, if this
     * feature is enabled.
     *
     * @return comma separated list of rejection reasons
     */
    public String getRejectionReasons() {
        return rejectionReasons;
    }
}
