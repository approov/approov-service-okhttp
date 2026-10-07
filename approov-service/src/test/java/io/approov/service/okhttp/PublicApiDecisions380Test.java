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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.regex.PatternSyntaxException;

/**
 * Public API decisions of 2026-10-07, the same on every Approov package:
 * addExclusionURLRegex throws an unchecked IllegalArgumentException for an
 * invalid regular expression and adds nothing (as the iOS package throws), and
 * ApproovServiceMutator.DEFAULT is declared as the interface, pointing at the
 * decisions the release considers default (CLOSE_FAILURE in 3.8.0). Mirrors
 * approov-service-android's PublicApiDecisions380Test.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class PublicApiDecisions380Test {
    @Before
    public void setUp() {
        ApproovService.reset();
    }

    @After
    public void tearDown() {
        ApproovService.reset();
    }

    @Test
    public void anInvalidExclusionRegexThrowsIllegalArgumentExceptionAndAddsNothing() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> ApproovService.addExclusionURLRegex("("));
        assertTrue("the syntax error is the cause: " + thrown.getCause(),
                thrown.getCause() instanceof PatternSyntaxException);
        assertTrue("the message names the pattern: " + thrown.getMessage(), thrown.getMessage().contains("("));
        assertEquals(Collections.emptyMap(), ApproovService.getExclusionURLRegexs());
    }

    @Test
    public void anInvalidExclusionRegexLeavesTheValidOnesInPlace() {
        ApproovService.addExclusionURLRegex("^https://health\\.example\\.com/");
        assertThrows(IllegalArgumentException.class, () -> ApproovService.addExclusionURLRegex("[unclosed"));
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
    public void defaultIsDeclaredAsTheInterfaceAndIsCloseFailureIn380() throws Exception {
        Field field = ApproovServiceMutator.class.getField("DEFAULT");
        assertSame("DEFAULT is typed as the interface, not as the class it points at",
                ApproovServiceMutator.class, field.getType());
        assertTrue(Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers()));
        assertSame("3.8.0 default behaviour", ApproovServiceMutator.CLOSE_FAILURE, ApproovServiceMutator.DEFAULT);
        assertSame(ApproovServiceMutator.class, ApproovServiceMutator.class.getField("CLOSE_FAILURE").getType());
        assertSame(ApproovServiceMutator.class, ApproovServiceMutator.class.getField("ALWAYS_PROCEED").getType());
        assertSame("the out-of-the-box mutator", ApproovServiceMutator.DEFAULT, ApproovService.getServiceMutator());
    }
}
