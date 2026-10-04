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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Method;

/**
 * The 3.8.0 public surface of ApproovService (SPECIFICATION 5.2, 5.7,
 * TESTING_REQUIREMENTS 7 and 10): what is removed and what replaces it, with no
 * aliases. Mirrors approov-service-android's PublicSurface380Test.
 */
public class PublicSurface380Test {

    static boolean hasPublicMethod(Class<?> type, String name) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name))
                return true;
        }
        return false;
    }

    @Test
    public void enabledFlagsUseThe380NamesWithNoAliases() {
        // SPECIFICATION 5.7(c): isInitialized and isApproovEnabled are removed
        assertTrue(hasPublicMethod(ApproovService.class, "isApproovServiceEnabled"));
        assertTrue(hasPublicMethod(ApproovService.class, "isApproovProtectionEnabled"));
        assertFalse(hasPublicMethod(ApproovService.class, "isInitialized"));
        assertFalse(hasPublicMethod(ApproovService.class, "isApproovEnabled"));
    }

    @Test
    public void proceedOnNetworkFailIsRemoved() {
        // SPECIFICATION 5.2 (changed 2026-10-04): a decision on the network statuses
        // belongs to the mutator, ALWAYS_PROCEED or the app's own
        assertFalse(hasPublicMethod(ApproovService.class, "setProceedOnNetworkFail"));
        assertFalse(hasPublicMethod(ApproovService.class, "getProceedOnNetworkFail"));
    }
}
