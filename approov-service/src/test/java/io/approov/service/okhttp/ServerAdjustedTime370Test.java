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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.util.Log;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * Message signatures take their created and expires times from the SDK's server adjusted time while
 * protection is enabled, so that a device whose clock is wrong still signs within the backend's window. If
 * the SDK call fails the signature is still produced, on the device clock, with a warning.
 * ApproovService.getServerAdjustedCurrentTimeMS() returns the SDK value and, like the other direct methods,
 * fails before protection is enabled. The SDK hook sets the server time three hours apart from the device
 * clock, so a signature stamped from the device clock cannot pass for one stamped from the server time.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class ServerAdjustedTime370Test {
    private static final long OFFSET_MS = TimeUnit.HOURS.toMillis(3);
    private static final long DEFAULT_EXPIRES_LIFETIME = 15;
    private static final Pattern CREATED = Pattern.compile(";created=(\\d+)");
    private static final Pattern EXPIRES = Pattern.compile(";expires=(\\d+)");

    // a recording SDK hook whose server adjusted time is the device clock minus OFFSET_MS, or fails
    private static final class ServerTimeSdk extends RecordingSdk {
        volatile RuntimeException failure;
        volatile long lastServerTime;

        @Override
        public long getServerAdjustedCurrentTimeMS() {
            RuntimeException thrown = failure;
            if (thrown != null)
                throw thrown;
            lastServerTime = System.currentTimeMillis() - OFFSET_MS;
            return lastServerTime;
        }
    }

    private LocalHttpsFixture fixture;
    private ServerTimeSdk sdk;

    @Before
    public void setUp() throws Exception {
        // localhost is an Approov API domain whose certificate the client trusts
        fixture = new LocalHttpsFixture(false);
        ApproovService.reset();
        sdk = new ServerTimeSdk();
        RecordingSdk.install(sdk);
        ApproovService.initialize(fixture.context, LocalHttpsFixture.CONFIG, "reinit-server-time");
        TestSigning.install();
        ApproovService.setOkHttpClientBuilder(fixture.trustingBuilder());
        ShadowLog.reset();
    }

    @After
    public void tearDown() throws Exception {
        fixture.shutdown();
        ApproovService.reset();
        RecordingSdk.uninstall();
        ShadowLog.reset();
    }

    // sends a request to the protected host and returns the Signature-Input it carried
    private String signatureInput() throws Exception {
        fixture.server.enqueue(new MockResponse().setBody("ok"));
        try (Response response = ApproovService.getOkHttpClient().newCall(
                new Request.Builder().url(fixture.server.url("/signed")).build()).execute()) {
            assertEquals(200, response.code());
        }
        RecordedRequest recorded = fixture.server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(recorded);
        String input = recorded.getHeader("Signature-Input");
        assertNotNull("the request is signed", input);
        return input;
    }

    // the value of a parameter, which every member of the signature carries the same
    private static long parameter(Pattern pattern, String input) {
        Matcher matcher = pattern.matcher(input);
        assertTrue("no " + pattern + " in " + input, matcher.find());
        long value = Long.parseLong(matcher.group(1));
        while (matcher.find())
            assertEquals("every signature member carries the same value: " + input, value,
                    Long.parseLong(matcher.group(1)));
        return value;
    }

    private static int warningsContaining(String text) {
        int count = 0;
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            if ((item.type == Log.WARN) && item.msg.contains(text))
                count++;
        }
        return count;
    }

    @Test
    public void createdAndExpiresFollowTheServerAdjustedTime() throws Exception {
        String input = signatureInput();

        long created = parameter(CREATED, input);
        assertEquals("created is the server adjusted time, not the device clock", sdk.lastServerTime / 1000,
                created);
        assertEquals(created + DEFAULT_EXPIRES_LIFETIME, parameter(EXPIRES, input));
    }

    @Test
    public void aFailingServerTimeFallsBackToTheDeviceClockWithAWarning() throws Exception {
        sdk.failure = new IllegalStateException("server time failure");
        long before = System.currentTimeMillis() / 1000;
        String input = signatureInput();
        long after = System.currentTimeMillis() / 1000;

        long created = parameter(CREATED, input);
        assertTrue("created is the device clock: " + created, (created >= before) && (created <= after));
        assertEquals(created + DEFAULT_EXPIRES_LIFETIME, parameter(EXPIRES, input));
        assertEquals("one warning for the one signature", 1,
                warningsContaining("server adjusted time unavailable"));
    }

    @Test
    public void theDirectMethodReturnsTheSdkValue() throws Exception {
        long value = ApproovService.getServerAdjustedCurrentTimeMS();
        assertEquals(sdk.lastServerTime, value);
    }

    @Test
    public void theDirectMethodWrapsAnSdkFailure() {
        IllegalStateException cause = new IllegalStateException("server time failure");
        sdk.failure = cause;
        try {
            ApproovService.getServerAdjustedCurrentTimeMS();
            fail("an SDK failure is thrown as an ApproovException");
        } catch (ApproovException e) {
            assertEquals("Wrapped java.lang.IllegalStateException: server time failure", e.getMessage());
            assertEquals(cause, e.getCause());
        }
    }

    @Test
    public void theDirectMethodFailsBeforeProtectionIsEnabled() {
        ApproovService.reset();
        try {
            ApproovService.getServerAdjustedCurrentTimeMS();
            fail("before initialization the direct method throws");
        } catch (ApproovException e) {
            assertEquals("getServerAdjustedCurrentTimeMS: SDK not initialized", e.getMessage());
        }
    }

    @Test
    public void theSdkReportsItsServerAdjustedTime() throws Exception {
        // the mini SDK has no servers, so without the hook its server adjusted time is the device clock, and
        // the layer reaches it through the same lookup an SDK without the method would fall back from
        RecordingSdk.uninstall();
        long before = System.currentTimeMillis();
        long value = ApproovService.getServerAdjustedCurrentTimeMS();
        long after = System.currentTimeMillis();
        assertTrue("within the device clock: " + value, (value >= before - 1000) && (value <= after + 1000));
    }
}
