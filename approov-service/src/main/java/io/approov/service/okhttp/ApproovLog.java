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

import android.util.Log;

import okhttp3.HttpUrl;

// ApproovLog writes all the service layer logging, gated by the level set with ApproovService.setLoggingLevel
final class ApproovLog {
    // the tag whose platform debug setting raises the level to DEBUG
    static final String SUPPORT_TAG = "ApproovService";

    // the level set by the app; volatile as it is read on every request thread
    private static volatile ApproovLogLevel level = ApproovLogLevel.INFO;

    /**
     * Construction is disallowed as this is a static only class.
     */
    private ApproovLog() {
    }

    /**
     * Sets the logging level.
     *
     * @param newLevel is the new logging level
     */
    static void setLevel(ApproovLogLevel newLevel) {
        level = newLevel;
    }

    /**
     * Gets the logging level.
     *
     * @return the current logging level
     */
    static ApproovLogLevel getLevel() {
        return level;
    }

    /**
     * Determines if a line at the given level is written.
     *
     * @param wanted is the level of the line
     * @return true if the line is written, false otherwise
     */
    static boolean isEnabled(ApproovLogLevel wanted) {
        // OFF is final as the app has opted out of all logging
        ApproovLogLevel current = level;
        if (current == ApproovLogLevel.OFF)
            return false;
        if (current.compareTo(wanted) >= 0)
            return true;
        return supportDebug();
    }

    /**
     * Determines if debug lines, the loggable token among them, are written.
     *
     * @return true if debug lines are written, false otherwise
     */
    static boolean isDebugEnabled() {
        return isEnabled(ApproovLogLevel.DEBUG);
    }

    // determines if debug logging is enabled for the support tag (for example with "adb shell setprop
    // log.tag.ApproovService DEBUG"), which raises any level but OFF to DEBUG without a rebuild
    private static boolean supportDebug() {
        try {
            return Log.isLoggable(SUPPORT_TAG, Log.DEBUG);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Gets the form of a URL that may be logged: scheme, host, any non-default port and path, without the
     * user info, query or fragment, which may carry personal data or a substituted secure string.
     *
     * @param url is the URL to be logged
     * @return the loggable form of the URL
     */
    static String loggableURL(HttpUrl url) {
        return url.newBuilder().username("").password("").query(null).fragment(null).build().toString();
    }

    // writers for each level, each only writing if its level is enabled
    static void e(String tag, String message) {
        if (isEnabled(ApproovLogLevel.ERROR))
            Log.e(tag, message);
    }

    static void e(String tag, String message, Throwable throwable) {
        if (isEnabled(ApproovLogLevel.ERROR))
            Log.e(tag, message, throwable);
    }

    static void w(String tag, String message) {
        if (isEnabled(ApproovLogLevel.WARNING))
            Log.w(tag, message);
    }

    static void i(String tag, String message) {
        if (isEnabled(ApproovLogLevel.INFO))
            Log.i(tag, message);
    }

    static void d(String tag, String message) {
        if (isEnabled(ApproovLogLevel.DEBUG))
            Log.d(tag, message);
    }
}
