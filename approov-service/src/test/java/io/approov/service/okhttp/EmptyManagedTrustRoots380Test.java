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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.util.Log;

import com.criticalblue.minisdk.testing.AttesterProxyController;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.SSLPeerUnverifiedException;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;

/**
 * A domain added to Approov with no pins of its own falls back to the managed
 * trust roots (the "*" pin set). When that set is empty, or absent, the
 * connection is validated by OS trust only (a valid development setup). That
 * fallback is not silent: the pinning interceptor logs one warning per host,
 * naming the host and never a pin, as approov-service-ios and
 * approov-service-android do.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class EmptyManagedTrustRoots380Test {
    private LocalHttpsFixture fixture;

    @Before
    public void setUp() throws Exception {
        fixture = new LocalHttpsFixture(false);
        ApproovService.reset();
        ShadowLog.reset();
    }

    @After
    public void tearDown() throws Exception {
        fixture.shutdown();
        ApproovService.reset();
        ShadowLog.reset();
    }

    // localhost protected with the given pin lists (JSON arrays); managed null for no "*" entry
    private void protect(String own, String managed) {
        AttesterProxyController.reset();
        AttesterProxyController.loadScenarioJson("{\"activeCase\": \"mtr\", \"cases\": {\"mtr\": {"
                + "\"protectedDomains\": [\"localhost\"],"
                + "\"pins\": {\"public-key-sha256\": {\"localhost\": " + own
                + ((managed == null) ? "" : ", \"*\": " + managed) + "}}}}}");
        ApproovService.initialize(fixture.context, LocalHttpsFixture.CONFIG, "reinit-mtr-" + own + managed);
        ApproovService.setOkHttpClientBuilder(fixture.trustingBuilder());
    }

    private int get() throws Exception {
        fixture.server.enqueue(new MockResponse().setBody("ok"));
        try (Response response = ApproovService.getOkHttpClient().newCall(
                new Request.Builder().url(fixture.server.url("/v1/items")).build()).execute()) {
            return response.code();
        }
    }

    private static List<String> fallbackWarnings() {
        List<String> out = new ArrayList<>();
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            if ((item.type == Log.WARN) && (item.msg != null) && item.msg.contains("managed trust roots"))
                out.add(item.tag + ": " + item.msg);
        }
        return out;
    }

    @Test
    public void anEmptyManagedTrustRootSetIsWarnedOncePerHostByHostOnly() throws Exception {
        protect("[]", "[]");
        for (int i = 0; i < 3; i++)
            assertEquals("OS trust applies", 200, get());
        List<String> warnings = fallbackWarnings();
        assertEquals("once for the host: " + warnings, 1, warnings.size());
        assertTrue(warnings.get(0), warnings.get(0).startsWith("ApproovPinningInterceptor: "));
        assertTrue(warnings.get(0), warnings.get(0).contains("localhost"));
        assertTrue(warnings.get(0), warnings.get(0).contains("OS trust"));
    }

    @Test
    public void anAbsentManagedTrustRootSetIsWarnedAsAnEmptyOne() throws Exception {
        protect("[]", null);
        assertEquals(200, get());
        assertEquals(200, get());
        assertEquals(fallbackWarnings().toString(), 1, fallbackWarnings().size());
    }

    @Test
    public void noWarningWhenTheManagedTrustRootsApplyAndTheyAreEnforced() throws Exception {
        // control: with a "*" pin the host is pinned to it, so the mismatching managed
        // trust root fails the connection and nothing is left to OS trust
        protect("[]", "[\"" + LocalHttpsFixture.WRONG_PIN + "\"]");
        assertThrows(SSLPeerUnverifiedException.class, this::get);
        assertEquals(fallbackWarnings().toString(), 0, fallbackWarnings().size());
        for (ShadowLog.LogItem item : ShadowLog.getLogs())
            assertFalse("never a pin: " + item.msg, (item.msg != null) && item.msg.contains(LocalHttpsFixture.WRONG_PIN));
    }

    @Test
    public void noWarningWhenTheHostHasPinsOfItsOwn() throws Exception {
        protect("[\"" + LocalHttpsFixture.WRONG_PIN + "\"]", "[]");
        assertThrows(SSLPeerUnverifiedException.class, this::get);
        assertEquals(fallbackWarnings().toString(), 0, fallbackWarnings().size());
    }

    @Test
    public void noWarningWhenAnotherSpellingOfTheHostHasPins() throws Exception {
        // pin keys match without regard to case: an empty spelling never hides the pins
        // of another, so the host is pinned (the wrong pin fails it) and not on OS trust
        protect("[], \"LOCALHOST\": [\"" + LocalHttpsFixture.WRONG_PIN + "\"]", "[]");
        assertThrows(SSLPeerUnverifiedException.class, this::get);
        assertEquals(fallbackWarnings().toString(), 0, fallbackWarnings().size());
    }
}
