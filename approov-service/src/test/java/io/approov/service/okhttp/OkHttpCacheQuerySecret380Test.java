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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

import okhttp3.Cache;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * A request whose query carries a substituted secure string bypasses the app's OkHttp Cache: its response
 * is never stored, so the secret in its URL never reaches the cache on disk, and it is never served from the
 * cache. The app still sees the server's own Cache-Control, and the wire request carries no cache header the
 * layer made. A request without a substituted query secret is cached as usual.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class OkHttpCacheQuerySecret380Test {
    private static final String SECRET = "query-secret-7Hd";
    private static final String SERVER_CACHE_CONTROL = "max-age=3600";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private LocalHttpsFixture fixture;
    private Cache cache;
    private OkHttpClient client;

    @Before
    public void setUp() throws Exception {
        fixture = new LocalHttpsFixture(false);
        ApproovService.reset();
        ApproovService.initialize(fixture.context, LocalHttpsFixture.CONFIG, "reinit-cache-query-secret");
        assertEquals(SECRET, ApproovService.fetchSecureString("placeholder", SECRET));
        ApproovService.addSubstitutionQueryParam("api_key");
        cache = new Cache(folder.newFolder("http-cache"), 1024 * 1024);
        ApproovService.setOkHttpClientBuilder(fixture.trustingBuilder().cache(cache));
        client = ApproovService.getOkHttpClient();
    }

    @After
    public void tearDown() throws Exception {
        if (cache != null)
            cache.close();
        fixture.shutdown();
        ApproovService.reset();
    }

    // performs a GET, reading the whole body so that the cache completes any write
    private Response get(String pathAndQuery) throws Exception {
        Response response = client.newCall(new Request.Builder().url(fixture.server.url(pathAndQuery)).build())
                .execute();
        response.body().string();
        response.close();
        return response;
    }

    // performs a GET answered by the server with a cacheable response
    private Response getCacheable(String pathAndQuery) throws Exception {
        fixture.server.enqueue(new MockResponse().setHeader("Cache-Control", SERVER_CACHE_CONTROL).setBody("ok"));
        return get(pathAndQuery);
    }

    // whether any URL the cache holds, or any file the cache wrote, contains the text
    private boolean cacheHolds(String text) throws Exception {
        cache.flush();
        for (Iterator<String> urls = cache.urls(); urls.hasNext();) {
            if (urls.next().contains(text))
                return true;
        }
        File[] files = cache.directory().listFiles();
        if (files == null)
            return false;
        for (File file : files) {
            if (file.isFile() && new String(Files.readAllBytes(file.toPath()), StandardCharsets.ISO_8859_1)
                    .contains(text))
                return true;
        }
        return false;
    }

    @Test
    public void aResponseToASubstitutedQuerySecretIsNeverStored() throws Exception {
        Response first = getCacheable("/v1/items?api_key=placeholder");

        // the secret went to the server with no cache header the layer made, and the app sees the server's
        // own Cache-Control
        RecordedRequest sent = fixture.server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("/v1/items?api_key=" + SECRET, sent.getPath());
        assertNull("the wire request carries no cache header the layer made", sent.getHeader("Cache-Control"));
        assertNull(sent.getHeader("Pragma"));
        assertEquals(SERVER_CACHE_CONTROL, first.header("Cache-Control"));

        // nothing was written to the cache, and the secret is in no cached URL or cache file
        assertEquals("a response was stored", 0, cache.writeSuccessCount());
        assertFalse("the secret reached the cache on disk", cacheHolds(SECRET));

        // so the same request goes to the network again, never served from the cache
        Response second = getCacheable("/v1/items?api_key=placeholder");
        assertEquals(2, fixture.server.getRequestCount());
        assertNull(second.cacheResponse());
        assertEquals(0, cache.hitCount());
        assertEquals(SERVER_CACHE_CONTROL, second.header("Cache-Control"));
        assertFalse(cacheHolds(SECRET));
    }

    @Test
    public void aRedirectFromASubstitutedQuerySecretIsNeverStored() throws Exception {
        // a cacheable redirect from the URL holding the secret, to a target without one
        fixture.server.enqueue(new MockResponse().setResponseCode(301)
                .setHeader("Cache-Control", SERVER_CACHE_CONTROL).setHeader("Location", "/v1/next"));
        Response response = getCacheable("/v1/start?api_key=placeholder");

        assertEquals(200, response.code());
        assertEquals("/v1/start?api_key=" + SECRET, fixture.server.takeRequest(1, TimeUnit.SECONDS).getPath());
        assertEquals("/v1/next", fixture.server.takeRequest(1, TimeUnit.SECONDS).getPath());

        // only the target, which carried no secret, is stored
        assertEquals(1, cache.writeSuccessCount());
        assertFalse("the secret reached the cache on disk", cacheHolds(SECRET));
        assertEquals(SERVER_CACHE_CONTROL, response.header("Cache-Control"));
    }

    @Test
    public void aRequestWithoutASubstitutedQuerySecretIsCachedAsUsual() throws Exception {
        // control: the cache works for this client, so the tests above show a bypass, not a broken cache
        getCacheable("/v1/public?page=2");
        Response second = getCacheable("/v1/public?page=2");

        assertEquals(1, fixture.server.getRequestCount());
        assertEquals(1, cache.writeSuccessCount());
        assertNotNull(second.cacheResponse());
        assertNull(second.networkResponse());
        assertEquals(SERVER_CACHE_CONTROL, second.header("Cache-Control"));
    }
}
