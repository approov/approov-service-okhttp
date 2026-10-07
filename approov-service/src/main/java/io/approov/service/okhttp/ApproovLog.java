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

/**
 * The single route by which the service layer writes to the device log, gated by
 * the level set with {@link ApproovService#setLoggingLevel(ApproovLogLevel)}: an
 * error is written at ERROR and above, a warning at WARNING and above, an
 * information line at INFO (the default) and above, and a debug line at DEBUG.
 * Nothing is written at OFF.
 *
 * Debug logging enabled for the ApproovService tag, for example with
 * "adb shell setprop log.tag.ApproovService DEBUG", raises any level but OFF to
 * DEBUG, so that support can read the debug lines of a release build without a
 * rebuild. OFF is final: the app has opted out of all layer logging.
 */
final class ApproovLog {
    // the tag whose platform debug setting raises the level to DEBUG
    static final String SUPPORT_TAG = "ApproovService";

    // the level set by the app; volatile as it is read on every request thread
    private static volatile ApproovLogLevel level = ApproovLogLevel.INFO;

    private ApproovLog() {
    }

    static void setLevel(ApproovLogLevel newLevel) {
        level = newLevel;
    }

    static ApproovLogLevel getLevel() {
        return level;
    }

    /**
     * Indicates whether a line at the given level is written.
     *
     * @param wanted the level of the line
     * @return true if the line is written
     */
    static boolean isEnabled(ApproovLogLevel wanted) {
        ApproovLogLevel current = level;
        if (current == ApproovLogLevel.OFF)
            return false;
        if (current.compareTo(wanted) >= 0)
            return true;
        return supportDebug();
    }

    /**
     * Indicates whether debug lines, the loggable token among them, are written.
     *
     * @return true if debug lines are written
     */
    static boolean isDebugEnabled() {
        return isEnabled(ApproovLogLevel.DEBUG);
    }

    // whether debug logging is enabled for the support tag by the platform setting
    private static boolean supportDebug() {
        try {
            return Log.isLoggable(SUPPORT_TAG, Log.DEBUG);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The URL as it may be logged: scheme, host, any non-default port and path,
     * without user info, query or fragment, which may carry personal data or a
     * substituted secure string.
     *
     * @param url the URL
     * @return the loggable form of the URL
     */
    static String loggableURL(HttpUrl url) {
        return url.newBuilder().username("").password("").query(null).fragment(null).build().toString();
    }

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
