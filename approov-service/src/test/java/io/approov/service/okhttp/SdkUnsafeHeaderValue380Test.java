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

import com.criticalblue.minisdk.testing.AttesterProxyController;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import java.util.concurrent.TimeUnit;

import okhttp3.Request;
import okhttp3.mockwebserver.MockResponse;

/**
 * SPECIFICATION 1.6.1 (added 2026-10-07): a token or trace ID the Approov SDK
 * issued that contains a character a header cannot carry is reported as an SDK
 * problem naming the header, never as the app's configuration error (1.4,
 * 1.7(a)), and the value is never quoted. A token prefix the app set that a
 * header cannot carry is still the app's configuration error.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class SdkUnsafeHeaderValue380Test {
    private static final String APP_CONFIG_MESSAGE = "its value contains a character a header value cannot carry";
    private static final ApproovServiceMutator[] MUTATORS = {
        ApproovServiceMutator.CLOSE_FAILURE, ApproovServiceMutator.ALWAYS_PROCEED
    };

    private LocalHttpsFixture fixture;
    private RequestPathProbe probe;

    @Before
    public void setUp() throws Exception {
        fixture = new LocalHttpsFixture(false);
        AttesterProxyController.loadScenarioJson("{\"activeCase\": \"local\", \"cases\": {\"local\": {"
                + "\"protectedDomains\": [\"localhost\"],"
                + "\"pins\": {\"public-key-sha256\": {\"localhost\": []}}}}}");
        ApproovService.reset();
        // the default level: at DEBUG the SDK's loggable token, which carries the
        // trace ID, is logged by design (SPECIFICATION 5.10)
        ApproovService.initialize(fixture.context, LocalHttpsFixture.CONFIG, "reinit-sdk-unsafe-header");
        ApproovService.setOkHttpClientBuilder(fixture.trustingBuilder());
        probe = new RequestPathProbe(fixture);
    }

    @After
    public void tearDown() throws Exception {
        probe.close();
        fixture.shutdown();
        ApproovService.reset();
    }

    private Request request() {
        return new Request.Builder().url(fixture.server.url("/p")).build();
    }

    private static void assertNotQuoted(String what, Throwable e, String marker) {
        for (Throwable t = e; t != null; t = t.getCause())
            assertFalse(what + ": value quoted: " + t, String.valueOf(t.getMessage()).contains(marker));
        for (ShadowLog.LogItem item : ShadowLog.getLogs())
            assertFalse(what + ": value logged: " + item.msg, String.valueOf(item.msg).contains(marker));
    }

    private void assertSdkProblem(String directive, String what, String header, String marker) throws Exception {
        for (ApproovServiceMutator mutator : MUTATORS) {
            ApproovService.setServiceMutator(mutator);
            for (boolean enqueued : new boolean[] {true, false}) {
                String label = mutator + (enqueued ? " enqueue" : " execute");
                AttesterProxyController.setNextAttestationDirectiveJson(directive);
                ShadowLog.reset();
                ApproovException e = RequestPathProbe.assertFailure(label, probe.run(request(), enqueued),
                        ApproovException.class);
                assertTrue(label + ": an SDK problem naming the header: " + e.getMessage(),
                        e.getMessage().contains("the Approov SDK issued " + what + " that header " + header
                                + " cannot carry"));
                assertFalse(label + ": reported as the app's configuration: " + e.getMessage(),
                        e.getMessage().contains(APP_CONFIG_MESSAGE));
                assertNotQuoted(label, e, marker);
            }
        }
        assertEquals("nothing sent", 0, fixture.server.getRequestCount());
    }

    @Test
    public void anUnsafeTokenIsAnSdkProblem() throws Exception {
        assertSdkProblem("{\"operation\": \"fetchApproovToken\", \"response\": {\"status\": \"SUCCESS\","
                + " \"token\": \"tok\\u00e9TOKENMARK\"}}", "a token", "Approov-Token", "TOKENMARK");
    }

    @Test
    public void anUnsafeTraceIDIsAnSdkProblem() throws Exception {
        assertSdkProblem("{\"operation\": \"fetchApproovToken\", \"response\": {\"status\": \"SUCCESS\","
                + " \"traceID\": \"trace\\u0001TRACEMARK\"}}", "a trace ID", "Approov-TraceID", "TRACEMARK");
    }

    @Test
    public void control_anUnsafeTokenPrefixIsTheAppsConfigurationError() throws Exception {
        ApproovService.setTokenHeader("Authorization", "Beareré ");
        ApproovException e = RequestPathProbe.assertFailure("prefix", probe.run(request(), false),
                ApproovException.class);
        assertEquals("Approov cannot set header Authorization: " + APP_CONFIG_MESSAGE, e.getMessage());
    }

    @Test
    public void control_aSafeTokenAndTraceIDAreSent() throws Exception {
        fixture.server.enqueue(new MockResponse().setBody("ok"));
        RequestPathProbe.assertOk("safe", probe.run(request(), false));
        okhttp3.mockwebserver.RecordedRequest recorded = fixture.server.takeRequest(1, TimeUnit.SECONDS);
        assertFalse(recorded.getHeader("Approov-Token").isEmpty());
        assertFalse(recorded.getHeader("Approov-TraceID").isEmpty());
    }
}
