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

import android.util.Log;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;

/**
 * SPECIFICATION 1.4 (e41153e): a substitution query parameter key is matched as
 * a literal string, not as a regular expression, and every occurrence of it is
 * substituted, each value looked up as its own placeholder key and read back on
 * its own. A key may contain '.', which as a pattern would match any character
 * and so substitute a different parameter.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class QueryParamKey380Test {
    private static final ApproovServiceMutator[] MUTATORS = {
        ApproovServiceMutator.CLOSE_FAILURE, ApproovServiceMutator.ALWAYS_PROCEED,
    };

    private LocalHttpsFixture fixture;
    private RequestPathProbe probe;

    @Before
    public void setUp() throws Exception {
        fixture = new LocalHttpsFixture(false);
        ApproovService.reset();
        ApproovService.initialize(fixture.context, LocalHttpsFixture.CONFIG, "reinit-query-key");
        ApproovService.setOkHttpClientBuilder(fixture.trustingBuilder());
        assertEquals("the-secret", ApproovService.fetchSecureString("PH", "the-secret"));
        assertEquals("v1", ApproovService.fetchSecureString("PH1", "v1"));
        assertEquals("v2", ApproovService.fetchSecureString("PH2", "v2"));
        // a value OkHttp would not carry unchanged: a # starts the fragment
        assertEquals("hash#SECRETMARK", ApproovService.fetchSecureString("PHH", "hash#SECRETMARK"));
        probe = new RequestPathProbe(fixture);
    }

    @After
    public void tearDown() throws Exception {
        probe.close();
        fixture.shutdown();
        ApproovService.reset();
    }

    private String sentPath(String pathAndQuery) throws Exception {
        fixture.server.enqueue(new MockResponse().setBody("ok"));
        RequestPathProbe.assertOk(pathAndQuery, probe.execute(new Request.Builder()
                .url(fixture.server.url(pathAndQuery)).build()));
        return fixture.server.takeRequest(1, TimeUnit.SECONDS).getPath();
    }

    private String sentPath(String pathAndQuery, boolean enqueued) throws Exception {
        fixture.server.enqueue(new MockResponse().setBody("ok"));
        RequestPathProbe.assertOk(pathAndQuery, probe.run(new Request.Builder()
                .url(fixture.server.url(pathAndQuery)).build(), enqueued));
        return fixture.server.takeRequest(1, TimeUnit.SECONDS).getPath();
    }

    private static boolean warned(String text) {
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            if ((item.type == Log.WARN) && (item.msg != null) && item.msg.contains(text))
                return true;
        }
        return false;
    }

    private static void assertNeverLogged(String what, String text) {
        for (ShadowLog.LogItem item : ShadowLog.getLogs())
            assertFalse(what + ": logged " + item.msg, (item.msg != null) && item.msg.contains(text));
    }

    @Test
    public void keyWithADotMatchesOnlyItself() throws Exception {
        ApproovService.addSubstitutionQueryParam("a.b");
        assertEquals("'.' is not a wildcard", "/p?axb=PH", sentPath("/p?axb=PH"));
        assertEquals("/p?a.b=the-secret", sentPath("/p?a.b=PH"));
    }

    @Test
    public void keyWithADotSubstitutesEveryLiteralOccurrenceOnly() throws Exception {
        ApproovService.addSubstitutionQueryParam("a.b");
        for (ApproovServiceMutator mutator : MUTATORS) {
            ApproovService.setServiceMutator(mutator);
            for (boolean enqueued : new boolean[] {true, false}) {
                String what = mutator + (enqueued ? " enqueue" : " execute");
                assertEquals(what, "/p?axb=PH1&a.b=v1&a.b=v2",
                        sentPath("/p?axb=PH1&a.b=PH1&a.b=PH2", enqueued));
            }
        }
    }

    @Test
    public void everyOccurrenceIsSubstitutedWithItsOwnValue() throws Exception {
        ApproovService.addSubstitutionQueryParam("key");
        for (ApproovServiceMutator mutator : MUTATORS) {
            ApproovService.setServiceMutator(mutator);
            for (boolean enqueued : new boolean[] {true, false}) {
                String what = mutator + (enqueued ? " enqueue" : " execute");
                assertEquals(what, "/p?key=v1&other=1&key=v2", sentPath("/p?key=PH1&other=1&key=PH2", enqueued));
                assertEquals(what, "/p?key=v1&other=1&key=v1", sentPath("/p?key=PH1&other=1&key=PH1", enqueued));
                // the fragment is not part of the query and is never sent
                assertEquals(what, "/p?key=v1&key=v2", sentPath("/p?key=PH1&key=PH2#key=PH1", enqueued));
            }
        }
    }

    @Test
    public void eachOccurrenceIsReadBackOnItsOwn() throws Exception {
        ApproovService.addSubstitutionQueryParam("key");
        for (ApproovServiceMutator mutator : MUTATORS) {
            ApproovService.setServiceMutator(mutator);
            for (boolean enqueued : new boolean[] {true, false}) {
                String what = mutator + (enqueued ? " enqueue" : " execute");
                // the first occurrence is usable, the second would cut the URL at its #
                ShadowLog.reset();
                assertEquals(what, "/p?key=v1&other=1&key=PHH", sentPath("/p?key=PH1&other=1&key=PHH", enqueued));
                assertTrue(what + ": a warning names the query parameter", warned("query parameter key"));
                assertNeverLogged(what, "SECRETMARK");
                // the unusable occurrence first: the usable one after it is still substituted
                ShadowLog.reset();
                assertEquals(what, "/p?key=PHH&other=1&key=v1", sentPath("/p?key=PHH&other=1&key=PH1", enqueued));
                assertTrue(what + ": a warning names the query parameter", warned("query parameter key"));
                assertNeverLogged(what, "SECRETMARK");
            }
        }
    }

    @Test
    public void aRetryRestoresEveryOccurrenceAndSubstitutesAfresh() throws Exception {
        ApproovService.addSubstitutionQueryParam("key");
        for (ApproovServiceMutator mutator : MUTATORS) {
            ApproovService.setServiceMutator(mutator);
            assertEquals("v1", ApproovService.fetchSecureString("PH1", "v1"));
            assertEquals("v2", ApproovService.fetchSecureString("PH2", "v2"));
            AtomicInteger attempts = new AtomicInteger();
            ApproovService.setOkHttpClientBuilder(fixture.trustingBuilder()
                    .addNetworkInterceptor(chain -> {
                        Response response = chain.proceed(chain.request());
                        if (attempts.getAndIncrement() == 0) {
                            // both secure strings rotate before the authenticator retries
                            try {
                                ApproovService.fetchSecureString("PH1", "r1");
                                ApproovService.fetchSecureString("PH2", "r2");
                            } catch (ApproovException e) {
                                throw new java.io.IOException(e);
                            }
                            return response.newBuilder().code(401).message("Unauthorized").build();
                        }
                        return response;
                    })
                    .authenticator((route, response) -> {
                        if ("second".equals(response.request().header("Authorization")))
                            return null;
                        return response.request().newBuilder().header("Authorization", "second").build();
                    }));
            fixture.server.enqueue(new MockResponse().setBody("ok"));
            fixture.server.enqueue(new MockResponse().setBody("ok"));
            RequestPathProbe.assertOk(mutator.toString(), probe.execute(new Request.Builder()
                    .url(fixture.server.url("/p?key=PH1&other=1&key=PH2")).header("Authorization", "first").build()));
            assertEquals(2, attempts.get());
            assertEquals(mutator + " first attempt", "/p?key=v1&other=1&key=v2",
                    fixture.server.takeRequest(1, TimeUnit.SECONDS).getPath());
            assertEquals(mutator + " retry: every occurrence restored and substituted afresh",
                    "/p?key=r1&other=1&key=r2", fixture.server.takeRequest(1, TimeUnit.SECONDS).getPath());
        }
    }
}
