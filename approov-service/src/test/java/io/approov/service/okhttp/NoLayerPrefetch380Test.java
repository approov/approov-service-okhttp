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

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.criticalblue.minisdk.testing.AttesterProxyController;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;

/**
 * The layer starts no prefetch of its own (SPECIFICATION 5.2, changed
 * 2026-10-04): prefetch() is removed and initialize starts no fetch, the
 * platform SDK manages prefetching. getLastARC reports the last fetch the layer
 * made and performs none (SPECIFICATION 5.3). Mirrors approov-service-android's
 * NoLayerPrefetch380Test.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class NoLayerPrefetch380Test {
    private static final String CONFIG = "#cb-ivol#mAxOF0ekJUOC36J5XWmVmVipOcUoEdMjhPSp2FVtyTo=";

    private Context context;
    private RecordingSdkFacade sdk;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        AttesterProxyController.reset();
        ApproovService.reset();
        sdk = RecordingSdkFacade.install();
    }

    @After
    public void tearDown() {
        ApproovService.reset();
        AttesterProxyController.reset();
    }

    private int fetches() {
        return sdk.count("fetchApproovToken") + sdk.count("fetchApproovTokenAndWait")
                + sdk.count("fetchSecureStringAndWait") + sdk.count("fetchCustomJWTAndWait");
    }

    @Test
    public void prefetchIsRemoved() {
        assertFalse(PublicSurface380Test.hasPublicMethod(ApproovService.class, "prefetch"));
        try {
            Class.forName("io.approov.service.okhttp.PrefetchCallbackHandler");
            fail("PrefetchCallbackHandler must be removed with prefetch");
        } catch (ClassNotFoundException expected) {
            // removed
        }
    }

    @Test
    public void initializeStartsNoFetch() throws Exception {
        ApproovService.initialize(context, CONFIG, "reinit-no-prefetch");
        assertTrue(ApproovService.isApproovProtectionEnabled());
        // a non-blocking fetch completes on another thread, so give it a chance to
        // show up before asserting that none was started
        Thread.sleep(200);
        assertEquals(sdk.snapshot().toString(), 0, fetches());
        assertTrue(sdk.snapshot().toString(), sdk.count("initialize") == 1);
    }

    @Test
    public void getLastARCPerformsNoFetchAndNoSdkCall() throws Exception {
        ApproovService.initialize(context, CONFIG, "reinit-no-prefetch");
        sdk.calls.clear();

        assertEquals("", ApproovService.getLastARC());
        assertEquals("", ApproovService.getLastARC());

        assertEquals(Collections.<String>emptyList(), sdk.snapshot());
    }

    @Test
    public void getLastARCBeforeInitializeAndInBypassMakesNoSdkCall() {
        assertEquals("", ApproovService.getLastARC());
        ApproovService.initialize(context, "");
        assertEquals("", ApproovService.getLastARC());
        assertEquals(Collections.<String>emptyList(), sdk.snapshot());
    }

    @Test
    public void getLastARCIsClearedByADirectFetchThatFailedWithoutAResult() throws Exception {
        // SPECIFICATION 5.3: the ARC belongs to the most recent fetch, so a direct
        // method whose SDK call threw (no result, no ARC) leaves none behind
        ApproovService.initialize(context, CONFIG, "reinit-no-prefetch");
        AttesterProxyController.setNextAttestationDirectiveJson(
                "{\"operation\": \"fetchApproovToken\", \"response\": {\"status\": \"REJECTED\", \"arc\": \"ARC-1\"}}");
        try {
            ApproovService.fetchToken("https://api.example.com/");
            fail("REJECTED must throw from fetchToken");
        } catch (ApproovFetchStatusException e) {
            assertEquals(com.criticalblue.approovsdk.Approov.TokenFetchStatus.REJECTED, e.getTokenFetchStatus());
        }
        assertEquals("ARC-1", ApproovService.getLastARC());

        try {
            // the SDK rejects a null payload with IllegalArgumentException
            ApproovService.fetchCustomJWT(null);
            fail("a null payload must throw");
        } catch (ApproovException e) {
            assertTrue(e.getCause() instanceof IllegalArgumentException);
        }
        assertEquals("", ApproovService.getLastARC());
    }
}
