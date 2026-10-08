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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.core.app.ApplicationProvider;

import com.criticalblue.minisdk.testing.AttesterProxyController;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;

/**
 * The 3.8.0 public surface of ApproovService (SPECIFICATION 5.2, 5.7,
 * TESTING_REQUIREMENTS 7 and 10): what is removed and what replaces it, with no
 * aliases. Mirrors approov-service-android's PublicSurface380Test.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class PublicSurface380Test {
    private static final String CONFIG = "#cb-ivol#mAxOF0ekJUOC36J5XWmVmVipOcUoEdMjhPSp2FVtyTo=";

    @After
    public void tearDown() {
        ApproovService.reset();
        AttesterProxyController.reset();
    }

    static boolean hasPublicMethod(Class<?> type, String name) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name))
                return true;
        }
        return false;
    }

    @Test
    public void enabledFlagsUseThe380NamesWithNoAliases() {
        // isInitialized and isApproovEnabled are removed
        assertTrue(hasPublicMethod(ApproovService.class, "isApproovServiceEnabled"));
        assertTrue(hasPublicMethod(ApproovService.class, "isApproovProtectionEnabled"));
        assertFalse(hasPublicMethod(ApproovService.class, "isInitialized"));
        assertFalse(hasPublicMethod(ApproovService.class, "isApproovEnabled"));
    }

    @Test
    public void proceedOnNetworkFailIsRemoved() {
        // a decision on the network statuses
        // belongs to the mutator, ALWAYS_PROCEED or the app's own
        assertFalse(hasPublicMethod(ApproovService.class, "setProceedOnNetworkFail"));
        assertFalse(hasPublicMethod(ApproovService.class, "getProceedOnNetworkFail"));
    }

    @Test
    public void setInstallAttributesReplacesSetInstallAttrsInToken() throws Exception {
        // TESTING_REQUIREMENTS 7 (changed 2026-10-04): only the new name, no alias;
        // it throws the checked ApproovException and still calls the SDK's
        // Approov.setInstallAttrsInToken
        assertFalse(hasPublicMethod(ApproovService.class, "setInstallAttrsInToken"));
        Method method = ApproovService.class.getMethod("setInstallAttributes", String.class);
        assertEquals(void.class, method.getReturnType());
        assertArrayEquals(new Class<?>[] {ApproovException.class}, method.getExceptionTypes());

        ApproovService.reset();
        AttesterProxyController.reset();
        RecordingSdkFacade sdk = RecordingSdkFacade.install();
        ApproovService.initialize(ApplicationProvider.getApplicationContext(), CONFIG, "reinit-surface");
        sdk.calls.clear();
        method.invoke(null, "signed-jwt");
        assertEquals(java.util.Collections.singletonList("setInstallAttrsInToken:signed-jwt"), sdk.snapshot());
    }
}
