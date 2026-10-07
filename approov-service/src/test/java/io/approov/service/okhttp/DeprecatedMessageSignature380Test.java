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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.util.Log;

import androidx.test.core.app.ApplicationProvider;

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

/**
 * getMessageSignature is kept in 3.8.0 as a deprecated alias of
 * getAccountMessageSignature (decision 2026-10-07): it returns the same value
 * and logs one deprecation warning per process naming the replacement. Mirrors
 * approov-service-android's DeprecatedMessageSignature380Test.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class DeprecatedMessageSignature380Test {
    @Before
    public void setUp() {
        ApproovService.reset();
        ShadowLog.reset();
        ApproovService.setSdkFacadeForTesting(new RecordingSdkFacade() {
            @Override
            public String getAccountMessageSignature(String message) {
                calls.add("getAccountMessageSignature");
                return "ACCOUNT-SIGNATURE";
            }
        });
        AttesterProxyController.reset();
        ApproovService.initialize(ApplicationProvider.getApplicationContext(), LocalHttpsFixture.CONFIG,
                "reinit-deprecated-signature");
    }

    @After
    public void tearDown() {
        ApproovService.reset();
        AttesterProxyController.reset();
        ShadowLog.reset();
    }

    private static List<String> deprecationWarnings() {
        List<String> out = new ArrayList<>();
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            if ((item.type == Log.WARN) && (item.msg != null) && item.msg.contains("getMessageSignature"))
                out.add(item.msg);
        }
        return out;
    }

    @Test
    @SuppressWarnings("deprecation")
    public void theAliasReturnsTheAccountSignatureAndWarnsOncePerProcess() throws Exception {
        assertEquals(ApproovService.getAccountMessageSignature("message"),
                ApproovService.getMessageSignature("message"));
        assertEquals("ACCOUNT-SIGNATURE", ApproovService.getMessageSignature("other message"));
        assertEquals("one warning: " + deprecationWarnings(), 1, deprecationWarnings().size());
        assertTrue(deprecationWarnings().get(0), deprecationWarnings().get(0).contains("getAccountMessageSignature"));
        assertTrue(deprecationWarnings().get(0), deprecationWarnings().get(0).contains("deprecated"));
    }

    @Test
    public void getAccountMessageSignatureItselfDoesNotWarn() throws Exception {
        ApproovService.getAccountMessageSignature("message");
        assertEquals(deprecationWarnings().toString(), 0, deprecationWarnings().size());
    }

    @Test
    public void theAliasIsDeprecatedInTheBytecode() throws Exception {
        assertNotNull(ApproovService.class.getMethod("getMessageSignature", String.class)
                .getAnnotation(Deprecated.class));
    }
}
