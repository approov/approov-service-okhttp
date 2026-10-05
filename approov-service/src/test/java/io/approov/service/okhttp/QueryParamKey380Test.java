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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.TimeUnit;

import okhttp3.Request;
import okhttp3.mockwebserver.MockResponse;

/**
 * SPECIFICATION 1.4 (5b415f9): a substitution query parameter key is matched as
 * a literal string, not as a regular expression, and only its first occurrence
 * is substituted. A key may contain '.', which as a pattern would match any
 * character and so substitute a different parameter.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class QueryParamKey380Test {
    private LocalHttpsFixture fixture;
    private RequestPathProbe probe;

    @Before
    public void setUp() throws Exception {
        fixture = new LocalHttpsFixture(false);
        ApproovService.reset();
        ApproovService.initialize(fixture.context, LocalHttpsFixture.CONFIG, "reinit-query-key");
        ApproovService.setOkHttpClientBuilder(fixture.trustingBuilder());
        assertEquals("the-secret", ApproovService.fetchSecureString("PH", "the-secret"));
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

    @Test
    public void keyWithADotMatchesOnlyItself() throws Exception {
        ApproovService.addSubstitutionQueryParam("a.b");
        assertEquals("'.' is not a wildcard", "/p?axb=PH", sentPath("/p?axb=PH"));
        assertEquals("/p?a.b=the-secret", sentPath("/p?a.b=PH"));
    }

    @Test
    public void onlyTheFirstOccurrenceIsSubstituted() throws Exception {
        ApproovService.addSubstitutionQueryParam("key");
        assertEquals("/p?key=the-secret&other=1&key=PH", sentPath("/p?key=PH&other=1&key=PH"));
    }
}
