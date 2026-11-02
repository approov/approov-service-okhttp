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
import static org.junit.Assert.assertTrue;

import android.util.Log;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;

/**
 * Public API decisions, the same on every Approov package:
 * addExclusionURLRegex logs an error for an invalid regular expression and adds
 * nothing, without throwing (as in 3.5.8), and
 * ApproovServiceMutator.DEFAULT is declared as the interface, pointing at the
 * decisions the release considers default (CLOSE_FAILURE in 3.7.0).
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class PublicApiDecisions370Test {
    @Before
    public void setUp() {
        ApproovService.reset();
        // the exclusions can only be read once the service is initialized, here in bypass mode
        ApproovService.initialize(ApplicationProvider.getApplicationContext(), "");
        ShadowLog.reset();
    }

    // true if the layer logged an error naming the regular expression
    private static boolean loggedErrorFor(String regex) {
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            if ((item.type == Log.ERROR) && item.msg.contains("addExclusionURLRegex " + regex))
                return true;
        }
        return false;
    }

    @After
    public void tearDown() {
        ApproovService.reset();
    }

    @Test
    public void anInvalidExclusionRegexIsLoggedAsAnErrorAndAddsNothing() {
        ApproovService.addExclusionURLRegex("(");
        assertTrue("an error names the pattern: " + ShadowLog.getLogs(), loggedErrorFor("("));
        assertEquals(Collections.emptyMap(), ApproovService.getExclusionURLRegexs());
    }

    @Test
    public void anInvalidExclusionRegexLeavesTheValidOnesInPlace() {
        ApproovService.addExclusionURLRegex("^https://health\\.example\\.com/");
        ApproovService.addExclusionURLRegex("[unclosed");
        assertTrue(loggedErrorFor("[unclosed"));
        assertEquals(Collections.singleton("^https://health\\.example\\.com/"),
                ApproovService.getExclusionURLRegexs().keySet());
        assertTrue(ApproovService.getExclusionURLRegexs().get("^https://health\\.example\\.com/")
                .matcher("https://health.example.com/ping").find());
    }

    @Test
    public void addExclusionURLRegexDeclaresNoCheckedException() throws Exception {
        Method method = ApproovService.class.getMethod("addExclusionURLRegex", String.class);
        assertEquals("no checked exception, so 3.5.x callers still compile", 0, method.getExceptionTypes().length);
    }

    @Test
    public void defaultIsDeclaredAsTheInterfaceAndIsCloseFailureIn370() throws Exception {
        Field field = ApproovServiceMutator.class.getField("DEFAULT");
        assertSame("DEFAULT is typed as the interface, not as the class it points at",
                ApproovServiceMutator.class, field.getType());
        assertTrue(Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers()));
        assertSame("3.7.0 default behaviour", ApproovServiceMutator.CLOSE_FAILURE, ApproovServiceMutator.DEFAULT);
        assertSame(ApproovServiceMutator.class, ApproovServiceMutator.class.getField("CLOSE_FAILURE").getType());
        assertSame(ApproovServiceMutator.class, ApproovServiceMutator.class.getField("ALWAYS_PROCEED").getType());
        assertSame("the out-of-the-box mutator", ApproovServiceMutator.DEFAULT, ApproovService.getServiceMutator());
    }
}
