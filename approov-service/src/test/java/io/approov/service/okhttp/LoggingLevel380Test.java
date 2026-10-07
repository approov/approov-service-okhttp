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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;

/**
 * ApproovService.setLoggingLevel, matching approov-service-ios: ERROR writes
 * errors, WARNING adds warnings, INFO (the default) adds information and DEBUG
 * adds debug lines, the loggable token of each request among them. OFF writes
 * nothing from the layer, errors included. The level is configuration, so
 * initialize never resets it (SPECIFICATION 5.7). Debug logging enabled for the
 * ApproovService tag (adb shell setprop log.tag.ApproovService DEBUG, which
 * ShadowLog.setLoggable stands in for) raises any level but OFF to DEBUG, so
 * support can read the debug lines of a release build without a rebuild.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class LoggingLevel380Test {
    private static final Set<String> LAYER_TAGS = new HashSet<>(Arrays.asList(
            "ApproovService", "ApproovTokenInterceptor", "ApproovFreshness",
            "ApproovPinningInterceptor", "ApproovMsgSign"));

    // a distinctive line logged at each level, and the call that logs it
    private static final String ERROR_LINE = "getDeviceID: Approov protection not enabled";
    private static final String WARNING_LINE = "before ApproovService initialization";
    private static final String INFO_LINE = "enabled in bypass mode";
    private static final String DEBUG_LINE = "setTokenHeader Probe-Token";

    private static final String QUERY_VALUE = "query-value-never-logged";
    private static final String USER = "user-never-logged";

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

    private static List<ShadowLog.LogItem> layerLogs() {
        List<ShadowLog.LogItem> items = new ArrayList<>();
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            if (LAYER_TAGS.contains(item.tag))
                items.add(item);
        }
        return items;
    }

    private static boolean logged(int type, String text) {
        for (ShadowLog.LogItem item : layerLogs()) {
            if ((item.type == type) && (item.msg != null) && item.msg.contains(text))
                return true;
        }
        return false;
    }

    // logs one line at each level: a warning, an error (a direct method before
    // initialization), a debug line, then an information line (bypass mode
    // initialization)
    private void logOneLineAtEachLevel() {
        ApproovService.getOkHttpClient();
        assertThrows(ApproovException.class, ApproovService::getDeviceID);
        ApproovService.setTokenHeader("Probe-Token", "");
        ApproovService.initialize(fixture.context, "");
    }

    private void assertLevelsLogged(String what, boolean error, boolean warning, boolean info, boolean debug) {
        assertEquals(what + ": error", error, logged(Log.ERROR, ERROR_LINE));
        assertEquals(what + ": warning", warning, logged(Log.WARN, WARNING_LINE));
        assertEquals(what + ": info", info, logged(Log.INFO, INFO_LINE));
        assertEquals(what + ": debug", debug, logged(Log.DEBUG, DEBUG_LINE));
    }

    // makes a protected request whose URL carries a query, a fragment and user
    // info, with debug logging enabled for the ApproovService tag if asked, and
    // returns the token lines logged for it
    private List<String> tokenLinesForAProtectedRequest(boolean debugTag) throws Exception {
        ApproovService.initialize(fixture.context, LocalHttpsFixture.CONFIG, "reinit-logging-level");
        ApproovService.setOkHttpClientBuilder(fixture.trustingBuilder());
        fixture.server.enqueue(new MockResponse().setBody("ok"));
        HttpUrl url = fixture.server.url("/v1/items").newBuilder()
                .username(USER).password("pw")
                .addQueryParameter("q", QUERY_VALUE)
                .fragment("frag")
                .build();
        ShadowLog.reset();
        if (debugTag)
            ShadowLog.setLoggable("ApproovService", Log.DEBUG);
        try (Response response = ApproovService.getOkHttpClient().newCall(
                new Request.Builder().url(url).build()).execute()) {
            assertEquals(200, response.code());
        }
        fixture.server.takeRequest(5, TimeUnit.SECONDS);
        List<String> lines = new ArrayList<>();
        for (ShadowLog.LogItem item : layerLogs()) {
            assertFalse("the query is logged: " + item.msg, item.msg.contains(QUERY_VALUE));
            assertFalse("the user info is logged: " + item.msg, item.msg.contains(USER));
            if (item.msg.startsWith("Token for "))
                lines.add(item.msg);
        }
        return lines;
    }

    @Test
    public void eachLevelLogsItsOwnLinesAndThoseOfTheLevelsBefore() {
        for (ApproovLogLevel level : ApproovLogLevel.values()) {
            ApproovService.reset();
            ShadowLog.reset();
            ApproovService.setLoggingLevel(level);
            logOneLineAtEachLevel();
            int n = level.ordinal();
            assertLevelsLogged(level.name(), n >= 1, n >= 2, n >= 3, n >= 4);
        }
    }

    @Test
    public void theDefaultIsInfo() {
        logOneLineAtEachLevel();
        assertLevelsLogged("default", true, true, true, false);
    }

    @Test
    public void offLogsNothingFromTheLayer() {
        ApproovService.setLoggingLevel(ApproovLogLevel.OFF);
        logOneLineAtEachLevel();
        assertEquals("layer lines at OFF: " + layerLogs().size(), 0, layerLogs().size());
    }

    @Test
    public void theDefaultDoesNotLogTheLoggableToken() throws Exception {
        assertEquals(new ArrayList<String>(), tokenLinesForAProtectedRequest(false));
    }

    @Test
    public void debugLogsTheLoggableTokenWithTheUrlWithoutItsQuery() throws Exception {
        ApproovService.setLoggingLevel(ApproovLogLevel.DEBUG);
        List<String> lines = tokenLinesForAProtectedRequest(false);
        assertEquals("one token line: " + lines, 1, lines.size());
        String expected = "Token for https://localhost:" + fixture.server.getPort() + "/v1/items: ";
        assertTrue("host and path only: " + lines.get(0), lines.get(0).startsWith(expected));
        assertFalse("no query separator: " + lines.get(0), lines.get(0).contains("?"));
        assertFalse("no fragment: " + lines.get(0), lines.get(0).contains("frag"));
    }

    @Test
    public void debugLoggingForTheTagEnablesDebugLinesAtAnyLevelButOff() throws Exception {
        for (ApproovLogLevel level : ApproovLogLevel.values()) {
            ApproovService.reset();
            ShadowLog.reset();
            ShadowLog.setLoggable("ApproovService", Log.DEBUG);
            ApproovService.setLoggingLevel(level);
            logOneLineAtEachLevel();
            boolean on = level != ApproovLogLevel.OFF;
            assertLevelsLogged(level.name() + " with the tag at debug", on, on, on, on);
        }
        // and the loggable token, at the default level
        ApproovService.reset();
        assertEquals(1, tokenLinesForAProtectedRequest(true).size());
    }

    @Test
    public void debugLoggingForTheTagDoesNotOverrideOff() throws Exception {
        ApproovService.setLoggingLevel(ApproovLogLevel.OFF);
        assertEquals(new ArrayList<String>(), tokenLinesForAProtectedRequest(true));
        assertEquals("layer lines at OFF", 0, layerLogs().size());
    }

    @Test
    public void aLevelSetBeforeInitializeSurvivesIt() {
        // OFF before initialize: initialize logs nothing, and the lines after it neither
        ApproovService.setLoggingLevel(ApproovLogLevel.OFF);
        ApproovService.initialize(fixture.context, LocalHttpsFixture.CONFIG, "reinit-logging-before");
        ApproovService.setTokenHeader("Probe-Token", "");
        ApproovService.getOkHttpClient();
        assertEquals("layer lines at OFF across initialize", 0, layerLogs().size());

        // DEBUG before initialize: debug lines still logged after it
        ApproovService.reset();
        ShadowLog.reset();
        ApproovService.setLoggingLevel(ApproovLogLevel.DEBUG);
        ApproovService.initialize(fixture.context, LocalHttpsFixture.CONFIG, "reinit-logging-before-debug");
        ApproovService.setTokenHeader("Probe-Token", "");
        assertTrue("debug after initialize", logged(Log.DEBUG, DEBUG_LINE));
    }

    @Test
    public void aNullLevelIsRejectedAndLeavesTheLevelUnchanged() {
        ApproovService.setLoggingLevel(ApproovLogLevel.ERROR);
        assertThrows(IllegalArgumentException.class, () -> ApproovService.setLoggingLevel(null));
        logOneLineAtEachLevel();
        assertLevelsLogged("ERROR kept", true, false, false, false);
    }
}
