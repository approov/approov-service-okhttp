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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.util.Log;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import java.util.concurrent.TimeUnit;

import okhttp3.Headers;
import okhttp3.Request;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * SPECIFICATION 1.4 (added 2026-10-05): a secure string whose value an HTTP
 * header cannot carry (anything outside tab and printable ASCII: a non-ASCII or
 * DEL value set in the account, or anything an app set with a new definition) is
 * a substitution that produced no usable value under every mutator. The
 * placeholder stays, the request proceeds, and a warning names the header or
 * query parameter but never the value. In a query parameter the characters OkHttp
 * silently drops from a URL (tab, LF, FF, CR) make a value unusable the same way;
 * OkHttp percent-encodes a non-ASCII or DEL value there, which the backend
 * decodes, so such a value is substituted. A value the app itself supplies that a
 * header cannot carry (a token prefix, a header its mutator sets) is the app's
 * configuration error (SPECIFICATION 1.7(a)) and fails the request without
 * quoting the value.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class HeaderValueCharset380Test {
    // values a header cannot carry, each with a marker that must never be logged
    private static final String[] UNSAFE_VALUES = {
        "café-SECRETMARK",
        "del\u007f-SECRETMARK",
        "line\n-SECRETMARK",
        "crlf\r\n-SECRETMARK",
    };

    // values OkHttp would not carry unchanged in a query parameter: it strips LF, CR
    // and tab from a URL, an & ends the parameter and a # starts the fragment
    private static final String[] QUERY_CHANGED_VALUES = {
        "line\n-SECRETMARK",
        "crlf\r\n-SECRETMARK",
        "tab\t-SECRETMARK",
        "amp&x=SECRETMARK",
        "hash#SECRETMARK",
    };

    private static final ApproovServiceMutator[] MUTATORS = {
        ApproovServiceMutator.CLOSE_FAILURE, ApproovServiceMutator.ALWAYS_PROCEED,
    };

    private LocalHttpsFixture fixture;
    private RequestPathProbe probe;

    @Before
    public void setUp() throws Exception {
        fixture = new LocalHttpsFixture(false);
        ApproovService.reset();
        // redaction is checked at the most verbose level, the loggable token included
        ApproovService.setLoggingLevel(ApproovLogLevel.DEBUG);
        ApproovService.initialize(fixture.context, LocalHttpsFixture.CONFIG, "reinit-header-charset");
        ApproovService.setOkHttpClientBuilder(fixture.trustingBuilder());
        // app instance secure strings: the SDK returns a new definition unchanged
        for (int i = 0; i < UNSAFE_VALUES.length; i++)
            assertEquals(UNSAFE_VALUES[i], ApproovService.fetchSecureString("unsafe-" + i, UNSAFE_VALUES[i]));
        assertEquals("good-secret", ApproovService.fetchSecureString("good-key", "good-secret"));
        for (int i = 0; i < QUERY_CHANGED_VALUES.length; i++)
            assertEquals(QUERY_CHANGED_VALUES[i],
                    ApproovService.fetchSecureString("changed-" + i, QUERY_CHANGED_VALUES[i]));
        probe = new RequestPathProbe(fixture);
        ShadowLog.reset();
    }

    @After
    public void tearDown() throws Exception {
        probe.close();
        fixture.shutdown();
        ApproovService.reset();
    }

    private static boolean logged(int level, String text) {
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            if ((item.type == level) && (item.msg != null) && item.msg.contains(text))
                return true;
        }
        return false;
    }

    private static void assertNeverLogged(String what, String text) {
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            assertFalse(what + ": logged " + item.msg, (item.msg != null) && item.msg.contains(text));
            if (item.throwable != null)
                for (Throwable t = item.throwable; t != null; t = t.getCause())
                    assertFalse(what + ": logged " + t, String.valueOf(t.getMessage()).contains(text));
        }
    }

    private static void assertNeverQuoted(String what, Throwable e, String text) {
        for (Throwable t = e; t != null; t = t.getCause())
            assertFalse(what + ": quoted in " + t, String.valueOf(t.getMessage()).contains(text));
    }

    @Test
    public void unsafeSecureStringInAHeaderKeepsThePlaceholderUnderEveryMutator() throws Exception {
        ApproovService.addSubstitutionHeader("X-Api-Key", null);
        for (ApproovServiceMutator mutator : MUTATORS) {
            ApproovService.setServiceMutator(mutator);
            for (int i = 0; i < UNSAFE_VALUES.length; i++) {
                for (boolean enqueued : new boolean[] {true, false}) {
                    String what = mutator + " value " + i + (enqueued ? " enqueue" : " execute");
                    ShadowLog.reset();
                    fixture.server.enqueue(new MockResponse().setBody("ok"));
                    Request request = new Request.Builder().url(fixture.server.url("/p"))
                            .header("X-Api-Key", "unsafe-" + i).build();
                    RequestPathProbe.assertOk(what, probe.run(request, enqueued));
                    RecordedRequest recorded = fixture.server.takeRequest(1, TimeUnit.SECONDS);
                    assertEquals(what + ": the placeholder stays", "unsafe-" + i, recorded.getHeader("X-Api-Key"));
                    assertEquals(what, "success", recorded.getHeader("Approov-Status"));
                    assertTrue(what + ": a warning names the header", logged(Log.WARN, "X-Api-Key"));
                    assertNeverLogged(what, "SECRETMARK");
                }
            }
        }
        // control: a value a header can carry is substituted
        fixture.server.enqueue(new MockResponse().setBody("ok"));
        RequestPathProbe.assertOk("good", probe.execute(new Request.Builder().url(fixture.server.url("/p"))
                .header("X-Api-Key", "good-key").build()));
        assertEquals("good-secret", fixture.server.takeRequest(1, TimeUnit.SECONDS).getHeader("X-Api-Key"));
    }

    @Test
    public void secureStringOkHttpWouldNotCarryUnchangedInAQueryKeepsThePlaceholder() throws Exception {
        ApproovService.addSubstitutionQueryParam("key");
        for (ApproovServiceMutator mutator : MUTATORS) {
            ApproovService.setServiceMutator(mutator);
            for (int i = 0; i < QUERY_CHANGED_VALUES.length; i++) {
                for (boolean enqueued : new boolean[] {true, false}) {
                    String what = mutator + " value " + i + (enqueued ? " enqueue" : " execute");
                    ShadowLog.reset();
                    fixture.server.enqueue(new MockResponse().setBody("ok"));
                    RequestPathProbe.assertOk(what, probe.run(new Request.Builder()
                            .url(fixture.server.url("/p?key=changed-" + i + "&other=1")).build(), enqueued));
                    RecordedRequest recorded = fixture.server.takeRequest(1, TimeUnit.SECONDS);
                    assertEquals(what + ": the placeholder stays", "/p?key=changed-" + i + "&other=1",
                            recorded.getPath());
                    assertTrue(what + ": a warning names the query parameter",
                            logged(Log.WARN, "query parameter key"));
                    assertNeverLogged(what, "SECRETMARK");
                }
            }
        }
    }

    @Test
    public void nonAsciiAndDelSecureStringsInAQueryArePercentEncoded() throws Exception {
        ApproovService.addSubstitutionQueryParam("key");
        for (ApproovServiceMutator mutator : MUTATORS) {
            ApproovService.setServiceMutator(mutator);
            for (boolean enqueued : new boolean[] {true, false}) {
                String what = mutator + (enqueued ? " enqueue" : " execute");
                fixture.server.enqueue(new MockResponse().setBody("ok"));
                RequestPathProbe.assertOk(what + " non-ASCII", probe.run(new Request.Builder()
                        .url(fixture.server.url("/p?key=unsafe-0&other=1")).build(), enqueued));
                assertEquals(what, "/p?key=caf%C3%A9-SECRETMARK&other=1",
                        fixture.server.takeRequest(1, TimeUnit.SECONDS).getPath());
                fixture.server.enqueue(new MockResponse().setBody("ok"));
                RequestPathProbe.assertOk(what + " DEL", probe.run(new Request.Builder()
                        .url(fixture.server.url("/p?key=unsafe-1")).build(), enqueued));
                assertEquals(what, "/p?key=del%7F-SECRETMARK",
                        fixture.server.takeRequest(1, TimeUnit.SECONDS).getPath());
            }
        }
    }

    @Test
    public void tokenPrefixAHeaderCannotCarryFailsTheRequestWithoutQuotingIt() throws Exception {
        ApproovService.setTokenHeader("Authorization", "BeareréPREFIXMARK ");
        for (ApproovServiceMutator mutator : MUTATORS) {
            ApproovService.setServiceMutator(mutator);
            for (boolean enqueued : new boolean[] {true, false}) {
                String what = mutator + (enqueued ? " enqueue" : " execute");
                ShadowLog.reset();
                ApproovException e = RequestPathProbe.assertFailure(what,
                        probe.run(new Request.Builder().url(fixture.server.url("/p")).build(), enqueued),
                        ApproovException.class);
                assertEquals(what, "Approov cannot set header Authorization: its value contains a character "
                        + "a header value cannot carry", e.getMessage());
                assertNull(what + ": no cause", e.getCause());
                assertNeverQuoted(what, e, "PREFIXMARK");
                assertNeverLogged(what, "PREFIXMARK");
            }
        }
        assertEquals(0, fixture.server.getRequestCount());
    }

    @Test
    public void headerTheMutatorSetsThatAHeaderCannotCarryFailsTheRequestWithoutQuotingIt() throws Exception {
        // OkHttp's own builder refuses the value inside the hook, quoting it
        ApproovService.setServiceMutator(new ApproovServiceMutator() {
            @Override
            public Request handleInterceptorProcessedRequest(Request request, ApproovRequestMutations changes) {
                return request.newBuilder().header("X-App", "line\nAPPMARK").build();
            }
        });
        assertMutatorHeaderFails("builder");

        // a value set with addUnsafeNonAscii passes OkHttp's builder but is checked
        ApproovService.setServiceMutator(new ApproovServiceMutator() {
            @Override
            public Request handleInterceptorProcessedRequest(Request request, ApproovRequestMutations changes) {
                Headers headers = request.headers().newBuilder().addUnsafeNonAscii("X-App", "caféAPPMARK").build();
                return request.newBuilder().headers(headers).build();
            }
        });
        assertMutatorHeaderFails("addUnsafeNonAscii");
        assertEquals(0, fixture.server.getRequestCount());
    }

    private void assertMutatorHeaderFails(String how) throws Exception {
        for (boolean enqueued : new boolean[] {true, false}) {
            String what = how + (enqueued ? " enqueue" : " execute");
            ShadowLog.reset();
            ApproovException e = RequestPathProbe.assertFailure(what,
                    probe.run(new Request.Builder().url(fixture.server.url("/p")).build(), enqueued),
                    ApproovException.class);
            assertEquals(what, "Approov cannot set header X-App: its value contains a character "
                    + "a header value cannot carry", e.getMessage());
            assertNull(what + ": no cause", e.getCause());
            assertNeverQuoted(what, e, "APPMARK");
            assertNeverLogged(what, "APPMARK");
        }
    }
}
