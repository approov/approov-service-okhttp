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

import java.util.ArrayList;
import java.util.List;

import okhttp3.Request;

/**
 * A signature that cannot be produced is logged once per process at a visible level, and at debug level after
 * that, for each kind. The request is signed with the other signature, or goes unsigned, as before.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class MissingSignatureLogging371Test {
    private TwoOriginFixture fixture;
    private volatile boolean installMissing;
    private volatile boolean accountMissing;

    // a mini-sdk that answers an empty signature for the kinds marked missing
    private final class Sdk extends RecordingSdk {
        @Override
        public String getInstallMessageSignature(String message) {
            return installMissing ? "" : super.getInstallMessageSignature(message);
        }

        @Override
        public String getAccountMessageSignature(String message) {
            return accountMissing ? "" : super.getAccountMessageSignature(message);
        }
    }

    @Before
    public void setUp() throws Exception {
        fixture = new TwoOriginFixture(true);
        ApproovService.reset();
        RecordingSdk.install(new Sdk());
        fixture.initialize();
        ApproovLog.setLevel(ApproovLogLevel.DEBUG);
        ApproovDefaultMessageSigning.resetMissingSignatureLogsForTesting();
        ShadowLog.reset();
    }

    @After
    public void tearDown() throws Exception {
        fixture.shutdown();
        ApproovLog.setLevel(ApproovLogLevel.INFO);
        ApproovService.reset();
        RecordingSdk.uninstall();
        ApproovDefaultMessageSigning.resetMissingSignatureLogsForTesting();
        ShadowLog.reset();
    }

    // signs a request carrying the token header with both signatures, returning the signed request
    private Request sign() throws Exception {
        Request request = new Request.Builder().url("https://localhost/data").header("Approov-Token", "t").build();
        ApproovRequestMutations changes = new ApproovRequestMutations();
        changes.setTokenHeaderKey("Approov-Token");
        return ApproovDefaultMessageSigning.combined().handleInterceptorProcessedRequest(request, changes);
    }

    // the levels of the lines logged since the last call about the signature of the kind, then clears the log
    private static List<Integer> levels(String kind) {
        List<Integer> levels = new ArrayList<>();
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            if (item.msg.startsWith("Message signing:") && item.msg.contains(kind))
                levels.add(item.type);
        }
        ShadowLog.reset();
        return levels;
    }

    private static List<Integer> levels(Integer... expected) {
        List<Integer> list = new ArrayList<>();
        for (Integer level : expected)
            list.add(level);
        return list;
    }

    @Test
    public void aMissingInstallSignatureIsWarnedOnceThenDebug() throws Exception {
        installMissing = true;
        Request signed = sign();
        assertTrue(signed.header("Signature"), signed.header("Signature").startsWith("account=:"));
        assertEquals(levels(Log.WARN), levels("install signature"));
        sign();
        assertEquals(levels(Log.DEBUG), levels("install signature"));
    }

    @Test
    public void aMissingAccountSignatureIsWarnedOnceThenDebug() throws Exception {
        accountMissing = true;
        Request signed = sign();
        assertTrue(signed.header("Signature"), signed.header("Signature").startsWith("install=:"));
        assertEquals(levels(Log.WARN), levels("account signature"));
        sign();
        assertEquals(levels(Log.DEBUG), levels("account signature"));
    }

    @Test
    public void whenBothAreMissingTheRequestIsUnsignedAndLoggedAsAnErrorOnceThenDebug() throws Exception {
        installMissing = true;
        accountMissing = true;
        Request unsigned = sign();
        assertNull(unsigned.header("Signature"));
        assertNotNull(unsigned.header("Approov-Token"));
        assertEquals(levels(Log.ERROR), levels("no signature produced"));
        sign();
        assertEquals(levels(Log.DEBUG), levels("no signature produced"));
    }

    @Test
    public void theResetRestoresTheFirstTimeLogs() throws Exception {
        installMissing = true;
        sign();
        sign();
        ShadowLog.reset();
        ApproovDefaultMessageSigning.resetMissingSignatureLogsForTesting();
        sign();
        assertEquals(levels(Log.WARN), levels("install signature"));
    }
}
