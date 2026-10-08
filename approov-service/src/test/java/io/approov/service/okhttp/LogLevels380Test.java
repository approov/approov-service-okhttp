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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.util.Log;

import com.criticalblue.minisdk.testing.AttesterProxyController;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLPeerUnverifiedException;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

/**
 * The level of the log lines the three 3.8.0 layers share (decided 2026-10-08): an
 * empty account ID ignored once protection is enabled is information, a cleartext
 * request to a pinned host is a warning, and a pin mismatch and pins that cannot be
 * read are errors. A pin mismatch is logged naming the host, never a pin.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class LogLevels380Test {
    private LocalHttpsFixture fixture;

    @Before
    public void setUp() throws Exception {
        // localhost carries a pin that its certificate does not match
        fixture = new LocalHttpsFixture(true);
        ApproovService.reset();
        ShadowLog.reset();
    }

    @After
    public void tearDown() throws Exception {
        fixture.shutdown();
        ApproovService.reset();
        ShadowLog.reset();
    }

    // the level of the one line logged since the last reset containing every part
    private static int levelOf(String... parts) {
        Integer level = null;
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            boolean all = true;
            for (String part : parts)
                all &= item.msg.contains(part);
            if (all) {
                assertEquals("logged twice: " + item.msg, null, level);
                level = item.type;
            }
        }
        if (level == null)
            throw new AssertionError("no line with " + java.util.Arrays.toString(parts));
        return level;
    }

    @Test
    public void anEmptyConfigIgnoredIsInformation() {
        ApproovService.initialize(fixture.context, LocalHttpsFixture.CONFIG, "reinit-log-levels");
        ShadowLog.reset();
        ApproovService.initialize(fixture.context, "");
        assertEquals(Log.INFO, levelOf("empty", "ignored"));
    }

    @Test
    public void aCleartextRequestToAPinnedHostIsAWarning() throws Exception {
        MockWebServer cleartext = new MockWebServer();
        cleartext.start(InetAddress.getByName("localhost"), 0);
        try {
            ApproovService.initialize(fixture.context, LocalHttpsFixture.CONFIG, "reinit-log-levels");
            ApproovService.setServiceMutator(ApproovServiceMutator.ALWAYS_PROCEED);
            cleartext.enqueue(new MockResponse().setBody("ok"));
            ShadowLog.reset();
            try (Response response = ApproovService.getOkHttpClient().newCall(
                    new Request.Builder().url(cleartext.url("/p")).build()).execute()) {
                fail("a cleartext request to a pinned host must fail, got " + response.code());
            } catch (SSLPeerUnverifiedException expected) {
                // the pinning failure
            }
            assertEquals(Log.WARN, levelOf("cleartext", "localhost"));
            assertEquals(0, cleartext.getRequestCount());
        } finally {
            cleartext.shutdown();
        }
    }

    @Test
    public void aPinMismatchIsAnError() throws Exception {
        ApproovService.initialize(fixture.context, LocalHttpsFixture.CONFIG, "reinit-log-levels");
        ApproovService.setOkHttpClientBuilder(fixture.trustingBuilder());
        ShadowLog.reset();
        try (Response response = ApproovService.getOkHttpClient().newCall(
                new Request.Builder().url(fixture.server.url("/p")).build()).execute()) {
            fail("a pin mismatch must fail, got " + response.code());
        } catch (SSLPeerUnverifiedException expected) {
            // the pinning failure
        }
        assertEquals(Log.ERROR, levelOf("Approov pinning: no pin matches for localhost"));
        for (ShadowLog.LogItem item : ShadowLog.getLogs())
            assertFalse("a pin was logged: " + item.msg, item.msg.contains("sha256/"));
    }

    @Test
    public void pinsUnreadableInTheApiDomainCheckAreAnError() {
        ApproovService.initialize(fixture.context, LocalHttpsFixture.CONFIG, "reinit-log-levels");
        ApproovService.setSdkFacadeForTesting(new RecordingSdkFacade() {
            @Override
            public Map<String, List<String>> getPins(String pinType) {
                throw new IllegalStateException("pins unreadable in test");
            }
        });
        ShadowLog.reset();
        assertFalse(ApproovService.isApproovApiHost("localhost"));
        assertEquals(Log.ERROR, levelOf("pins unreadable in test"));
    }

    @Test
    public void pinsUnreadableForAConnectionAreAnError() throws Exception {
        ApproovService.initialize(fixture.context, LocalHttpsFixture.CONFIG, "reinit-log-levels");
        ApproovService.setOkHttpClientBuilder(fixture.trustingBuilder());
        ApproovService.setSdkFacadeForTesting(new RecordingSdkFacade() {
            @Override
            public Map<String, List<String>> getPins(String pinType) {
                throw new IllegalStateException("pins unreadable in test");
            }
        });
        // the pins fail to rebuild, so the connection check reads them again, and fails
        try {
            ApproovService.rebuildPins();
            fail("the pins must not be readable");
        } catch (ApproovException expected) {
            // every connection check now reads the pins again
        }
        ShadowLog.reset();
        try (Response response = ApproovService.getOkHttpClient().newCall(
                new Request.Builder().url(fixture.server.url("/p")).build()).execute()) {
            fail("a connection whose pins cannot be read must fail, got " + response.code());
        } catch (IOException expected) {
            // the connection fails closed
        }
        boolean error = false;
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            if (item.msg.contains("localhost") && item.msg.contains("pins unreadable in test"))
                error |= (item.type == Log.ERROR);
        }
        assertTrue("no error naming the host and the cause", error);
        assertEquals(0, fixture.server.getRequestCount());
    }
}
