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
import static org.junit.Assert.fail;

import org.junit.Test;

import java.net.URI;

import okhttp3.HttpUrl;
import okhttp3.Request;

/**
 * The derived components of a signed request come from the URL exactly as OkHttp
 * sends it (SPECIFICATION 3.3, TESTING_REQUIREMENTS "Target URI Is The Wire
 * URL"): {@code @target-uri} is {@link HttpUrl#toString()} without the fragment,
 * which is never sent, and the other components follow RFC 9421 section 2.2 from
 * that same string. Nothing is re-parsed with {@code java.net.URI}, which
 * re-encodes {@code |} to {@code %7C} and rejects some queries OkHttp sends.
 */
public class WireUrlComponentProvider380Test {

    private static ApproovDefaultMessageSigning.OkHttpComponentProvider provider(String url) {
        return new ApproovDefaultMessageSigning.OkHttpComponentProvider(
                new Request.Builder().url(url).get().build());
    }

    @Test
    public void targetUriIsTheUrlOkHttpSendsForPipeQueries() {
        String url = "https://maps.example.com/api/staticmap?path=color:red|weight:5&markers=a|b";
        assertEquals("OkHttp keeps | raw", url, HttpUrl.get(url).toString());
        ApproovDefaultMessageSigning.OkHttpComponentProvider p = provider(url);
        assertEquals(url, p.getTargetUri());
        assertEquals("/api/staticmap?path=color:red|weight:5&markers=a|b", p.getRequestTarget());
        assertEquals("?path=color:red|weight:5&markers=a|b", p.getQuery());
    }

    @Test
    public void targetUriKeepsOkHttpEncodingOfSpacesPercentAndNonAscii() {
        String wire = "https://example.com/caf%C3%A9/a%2Fb?q=a%20b&pct=100%25&name=caf%C3%A9&pipe=x|y&plus=a+b";
        HttpUrl url = HttpUrl.get("https://Example.COM/café/a%2Fb?q=a%20b&pct=100%25&name=café&pipe=x|y&plus=a+b");
        assertEquals("as OkHttp sends it", wire, url.toString());
        ApproovDefaultMessageSigning.OkHttpComponentProvider p = provider(url.toString());
        assertEquals(wire, p.getTargetUri());
        assertEquals("https", p.getScheme());
        assertEquals("example.com", p.getAuthority());
        assertEquals("/caf%C3%A9/a%2Fb", p.getPath());
        assertEquals("?q=a%20b&pct=100%25&name=caf%C3%A9&pipe=x|y&plus=a+b", p.getQuery());
        assertEquals("/caf%C3%A9/a%2Fb?q=a%20b&pct=100%25&name=caf%C3%A9&pipe=x|y&plus=a+b", p.getRequestTarget());
    }

    @Test
    public void targetUriForAUrlJavaNetUriRejects() {
        String url = "https://example.com/s?f={%22a%22:1}&c=^`&p=x|y";
        assertEquals(url, HttpUrl.get(url).toString());
        try {
            URI.create(HttpUrl.get(url).toString());
            fail("java.net.URI is expected to reject this URL, which OkHttp sends as is");
        } catch (IllegalArgumentException expected) {
            // the reason the layer must never parse the request URL with java.net.URI
        }
        ApproovDefaultMessageSigning.OkHttpComponentProvider p = provider(url);
        assertEquals(url, p.getTargetUri());
        assertEquals("/s?f={%22a%22:1}&c=^`&p=x|y", p.getRequestTarget());
        assertEquals("?f={%22a%22:1}&c=^`&p=x|y", p.getQuery());
    }

    @Test
    public void targetUriLeavesOutTheFragmentWhichIsNeverSent() {
        ApproovDefaultMessageSigning.OkHttpComponentProvider p = provider("https://example.com/p?x=1|2#frag|ment");
        assertEquals("https://example.com/p?x=1|2", p.getTargetUri());
        assertEquals("/p?x=1|2", p.getRequestTarget());
        assertEquals("?x=1|2", p.getQuery());
    }

    @Test
    public void authorityHasTheNonDefaultPortOnly() {
        assertEquals("example.com:8443", provider("https://Example.com:8443/").getAuthority());
        assertEquals("example.com", provider("https://example.com:443/").getAuthority());
        assertEquals("example.com", provider("http://example.com:80/").getAuthority());
        assertEquals("example.com:443", provider("http://example.com:443/").getAuthority());
        assertEquals("[::1]:8080", provider("http://[::1]:8080/").getAuthority());
        assertEquals("[::1]", provider("https://[::1]/").getAuthority());
    }

    @Test
    public void absentAndEmptyQueriesFollowRfc9421() {
        ApproovDefaultMessageSigning.OkHttpComponentProvider none = provider("https://example.com/p");
        assertEquals("https://example.com/p", none.getTargetUri());
        assertEquals("?", none.getQuery());
        assertEquals("/p", none.getRequestTarget());
        assertEquals("/p", none.getPath());

        ApproovDefaultMessageSigning.OkHttpComponentProvider empty = provider("https://example.com/p?");
        assertEquals("https://example.com/p?", empty.getTargetUri());
        assertEquals("?", empty.getQuery());
        assertEquals("/p?", empty.getRequestTarget());

        assertEquals("/", provider("https://example.com").getPath());
    }

    @Test
    public void queryParamIsReEncodedAndLookedUpByItsEncodedName() {
        ApproovDefaultMessageSigning.OkHttpComponentProvider p = provider(
                "https://example.com/p?q=a%20b&pct=100%25&caf%C3%A9=caf%C3%A9&pipe=x|y&plus=a+b&safe=A-z_0.9*");
        assertEquals("a%20b", p.getQueryParam("q"));
        assertEquals("100%25", p.getQueryParam("pct"));
        assertEquals("caf%C3%A9", p.getQueryParam("caf%C3%A9"));
        assertEquals("x%7Cy", p.getQueryParam("pipe"));
        assertEquals("a%20b", p.getQueryParam("plus"));
        assertEquals("A-z_0.9*", p.getQueryParam("safe"));
    }

    @Test
    public void factoryLookupIsByHostWithoutPort() {
        ApproovDefaultMessageSigning.OkHttpComponentProvider p = provider("https://Example.com:8443/p");
        assertEquals("example.com", p.getHost());
        assertEquals("::1", provider("http://[::1]:8080/").getHost());
    }
}
