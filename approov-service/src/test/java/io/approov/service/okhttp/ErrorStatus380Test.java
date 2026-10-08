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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.criticalblue.approovsdk.Approov;
import com.criticalblue.minisdk.testing.AttesterProxyController;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The direct methods report a failure by throwing the layer's Approov exception
 * with the SDK status available on it: every exception they
 * throw for a fetch status is an ApproovFetchStatusException whose
 * getTokenFetchStatus() is that status. The network statuses throw
 * ApproovNetworkException, REJECTED from a secure string, custom JWT or precheck
 * throws ApproovRejectionException with the ARC and reasons, and every other
 * status ApproovFetchStatusException, as in approov-service-android.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class ErrorStatus380Test {
    private static final String CONFIG = "#cb-ivol#mAxOF0ekJUOC36J5XWmVmVipOcUoEdMjhPSp2FVtyTo=";
    private static final String URL = "https://api.example.com/v1";

    private interface DirectCall {
        void call() throws ApproovException;
    }

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        AttesterProxyController.reset();
        AttesterProxyController.loadScenarioJson("{\"activeCase\": \"errors\", \"cases\": {\"errors\": {"
                + "\"protectedDomains\": [\"api.example.com\"],"
                + "\"initialSecureStrings\": {\"the-key\": \"the-secret\"}}}}");
        ApproovService.reset();
        ApproovService.initialize(context, CONFIG, "reinit-error-status");
    }

    @After
    public void tearDown() {
        ApproovService.reset();
        AttesterProxyController.reset();
    }

    private void assertThrowsWithStatus(String operation, String method, DirectCall call, String status,
            Class<?> expected) {
        AttesterProxyController.setNextAttestationDirectiveJson("{\"operation\": \"" + operation
                + "\", \"response\": {\"status\": \"" + status + "\", \"arc\": \"ARC-" + status + "\"}}");
        try {
            call.call();
            fail(method + " " + status + " must throw");
        } catch (ApproovException e) {
            String what = method + " " + status + ": " + e;
            assertEquals(what, expected, e.getClass());
            assertSame(what, Approov.TokenFetchStatus.valueOf(status),
                    ((ApproovFetchStatusException) e).getTokenFetchStatus());
            if (e instanceof ApproovRejectionException)
                assertEquals(what, "ARC-" + status, ((ApproovRejectionException) e).getARC());
        }
    }

    private void assertStatuses(String operation, String method, DirectCall call, Class<?> rejected) {
        for (String status : new String[] {"NO_NETWORK", "POOR_NETWORK", "UNTRUSTED_NETWORK"})
            assertThrowsWithStatus(operation, method, call, status, ApproovNetworkException.class);
        assertThrowsWithStatus(operation, method, call, "REJECTED", rejected);
        for (String status : new String[] {"INTERNAL_ERROR", "DISABLED", "BAD_URL"})
            assertThrowsWithStatus(operation, method, call, status, ApproovFetchStatusException.class);
    }

    @Test
    public void fetchTokenCarriesTheStatus() {
        assertStatuses("fetchApproovToken", "fetchToken", () -> ApproovService.fetchToken(URL),
                ApproovFetchStatusException.class);
    }

    @Test
    public void fetchSecureStringCarriesTheStatus() {
        assertStatuses("fetchSecureString", "fetchSecureString",
                () -> ApproovService.fetchSecureString("the-key", null), ApproovRejectionException.class);
    }

    @Test
    public void fetchCustomJWTCarriesTheStatus() {
        assertStatuses("fetchCustomJWT", "fetchCustomJWT",
                () -> ApproovService.fetchCustomJWT("{\"role\":\"tester\"}"), ApproovRejectionException.class);
    }

    @Test
    public void precheckCarriesTheStatus() {
        assertStatuses("fetchSecureString", "precheck", ApproovService::precheck, ApproovRejectionException.class);
    }
}
