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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

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

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

/**
 * The 3.8.0 initialize contract (SPECIFICATION 5.7, TESTING_REQUIREMENTS 1). The
 * layer stores and compares no account ID: it forwards every non-empty
 * initialize to the SDK with the comment verbatim and lets the SDK judge
 * repeats, rethrows any SDK exception unchanged with its own state untouched,
 * never resets configuration, and changes only the service and protection
 * flags. The SDK is the mini SDK, already initialized by setUp as it would be by
 * the first caller in the process; RecordingSdkFacade records what reaches it.
 * Mirrors approov-service-android's InitializeContractTest.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class InitializeContract380Test {
    private static final String CONFIG = "#cb-ivol#mAxOF0ekJUOC36J5XWmVmVipOcUoEdMjhPSp2FVtyTo=";
    private static final String OTHER_CONFIG = "#cb-other#mAxOF0ekJUOC36J5XWmVmVipOcUoEdMjhPSp2FVtyTo=";
    private static final String SDK_REJECTION =
            "Approov SDK has already been initialized with a different configuration";

    private Context context;
    private RecordingSdkFacade sdk;
    private final ApproovServiceMutator mutator = new ApproovServiceMutator() {
    };
    private final OkHttpClient.Builder builder = new OkHttpClient.Builder().callTimeout(4321, TimeUnit.MILLISECONDS);

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        AttesterProxyController.reset();
        // the SDK holds CONFIG, as after the first initialize in the process; a
        // "reinit..." comment makes the mini SDK accept it whatever it held before
        Approov.initialize(context, CONFIG, "auto", "reinit-contract-setup");
        ApproovService.reset();
        sdk = RecordingSdkFacade.install();
    }

    @After
    public void tearDown() {
        ApproovService.reset();
        AttesterProxyController.reset();
    }

    // ---- (a) forwarding ----

    @Test
    public void initializeForwardsConfigAndCommentAndEnablesBothFlags() {
        ApproovService.initialize(context, CONFIG, "reinit-first");

        assertEquals(1, sdk.count("initialize"));
        assertEquals(CONFIG, sdk.lastInitializeConfig);
        assertEquals("reinit-first", sdk.lastInitializeComment);
        assertTrue(ApproovService.isApproovServiceEnabled());
        assertTrue(ApproovService.isApproovProtectionEnabled());
        assertNoFetch();
    }

    @Test
    public void nullCommentIsForwardedAsNull() {
        ApproovService.initialize(context, CONFIG, null);

        assertTrue(sdk.initializeCalled);
        assertNull(sdk.lastInitializeComment);
    }

    @Test
    public void emptyCommentIsForwardedAsEmpty() {
        ApproovService.initialize(context, CONFIG, "");

        assertEquals("", sdk.lastInitializeComment);
    }

    @Test
    public void initializeWithoutCommentForwardsNullComment() {
        ApproovService.initialize(context, CONFIG);

        assertEquals(1, sdk.count("initialize"));
        assertNull(sdk.lastInitializeComment);
    }

    @Test
    public void sameConfigAndCommentRepeatIsForwardedAndReturnsWithNothingChanged() {
        ApproovService.initialize(context, CONFIG, "comment");
        configureEverything();
        sdk.calls.clear();

        // the SDK reports "already initialized" (false), which is success
        ApproovService.initialize(context, CONFIG, "comment");

        assertEquals("the repeat is forwarded, the SDK is the judge, and nothing else is called",
                Collections.singletonList("initialize"), sdk.snapshot());
        assertEquals("comment", sdk.lastInitializeComment);
        assertTrue(ApproovService.isApproovServiceEnabled());
        assertTrue(ApproovService.isApproovProtectionEnabled());
        assertConfiguredEverything();
    }

    @Test
    public void aRepeatTheSdkPerformsAgainKeepsTheConfiguration() {
        ApproovService.initialize(context, CONFIG, "reinit-a");
        configureEverything();

        // the SDK accepts a "reinit..." comment as a new initialization (true)
        ApproovService.initialize(context, CONFIG, "reinit-b");

        assertEquals("reinit-b", sdk.lastInitializeComment);
        assertTrue(ApproovService.isApproovProtectionEnabled());
        assertConfiguredEverything();
    }

    // ---- (b) configuration is never reset ----

    @Test
    public void configurationSetBeforeFirstInitializeSurvivesIt() {
        configureEverything();

        ApproovService.initialize(context, CONFIG, null);

        assertTrue(ApproovService.isApproovProtectionEnabled());
        assertConfiguredEverything();
    }

    @Test
    public void configurationSetBeforeEmptyConfigInitializeSurvivesIt() {
        configureEverything();

        ApproovService.initialize(context, "", null);

        assertTrue(ApproovService.isApproovServiceEnabled());
        assertConfiguredEverything();
    }

    @Test
    public void configurationSurvivesUpgradeFromBypassToProtected() {
        ApproovService.initialize(context, "");
        configureEverything();

        ApproovService.initialize(context, CONFIG);

        assertTrue(ApproovService.isApproovProtectionEnabled());
        assertConfiguredEverything();
    }

    // ---- SDK rejections propagate unchanged and change nothing ----

    @Test
    public void differentConfigPropagatesTheSdkExceptionAndChangesNothing() {
        ApproovService.initialize(context, CONFIG, null);
        configureEverything();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> ApproovService.initialize(context, OTHER_CONFIG, null));

        assertEquals("the SDK's own exception, not one of the layer's", SDK_REJECTION, thrown.getMessage());
        assertEquals(OTHER_CONFIG, sdk.lastInitializeConfig);
        assertTrue(ApproovService.isApproovServiceEnabled());
        assertTrue(ApproovService.isApproovProtectionEnabled());
        assertConfiguredEverything();
        assertNoFetch();
    }

    @Test
    public void firstInitializeRejectedBySdkPropagatesAndServiceIsNotEnabled() {
        // another caller initialized the SDK with a different account
        configureEverything();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> ApproovService.initialize(context, OTHER_CONFIG));

        assertEquals(SDK_REJECTION, thrown.getMessage());
        assertFalse(ApproovService.isApproovServiceEnabled());
        assertFalse(ApproovService.isApproovProtectionEnabled());
        assertConfiguredEverything();
        assertEquals(Collections.singletonList("initialize"), sdk.snapshot());
    }

    @Test
    public void badConfigPropagatesIllegalArgumentAndServiceIsNotEnabled() {
        configureEverything();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> ApproovService.initialize(context, "corrupted-config"));

        assertEquals("Approov initial configuration is malformed", thrown.getMessage());
        assertFalse(ApproovService.isApproovServiceEnabled());
        assertFalse(ApproovService.isApproovProtectionEnabled());
        assertConfiguredEverything();
        assertEquals(Collections.singletonList("initialize"), sdk.snapshot());
    }

    @Test
    public void validConfigAfterEmptyRejectedBySdkStaysInBypassAndPropagates() {
        ApproovService.initialize(context, "");
        configureEverything();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> ApproovService.initialize(context, OTHER_CONFIG));

        assertEquals(SDK_REJECTION, thrown.getMessage());
        assertTrue(ApproovService.isApproovServiceEnabled());
        assertFalse(ApproovService.isApproovProtectionEnabled());
        assertConfiguredEverything();
        assertEquals(Collections.singletonList("initialize"), sdk.snapshot());
    }

    @Test
    public void aRejectedRepeatLeavesProtectionWorking() {
        ApproovService.initialize(context, CONFIG);
        assertThrows(IllegalStateException.class, () -> ApproovService.initialize(context, OTHER_CONFIG));

        // the rejection is not sticky: the original account is accepted again
        ApproovService.initialize(context, CONFIG);
        assertTrue(ApproovService.isApproovProtectionEnabled());
        assertEquals(3, sdk.count("initialize"));
    }

    // ---- (c) empty config and the two flags ----

    @Test
    public void flagsAreFalseBeforeAnyInitialize() {
        assertFalse(ApproovService.isApproovServiceEnabled());
        assertFalse(ApproovService.isApproovProtectionEnabled());
    }

    @Test
    public void emptyConfigFirstEnablesServiceOnlyAndNeverTouchesTheSdk() {
        ApproovService.initialize(context, "", "comment");

        assertTrue(ApproovService.isApproovServiceEnabled());
        assertFalse(ApproovService.isApproovProtectionEnabled());
        assertEquals(Collections.<String>emptyList(), sdk.snapshot());
    }

    @Test
    public void emptyThenValidConfigUpgradesToProtected() {
        ApproovService.initialize(context, "");

        ApproovService.initialize(context, CONFIG, "reinit-upgrade");

        assertTrue(ApproovService.isApproovServiceEnabled());
        assertTrue(ApproovService.isApproovProtectionEnabled());
        assertEquals(CONFIG, sdk.lastInitializeConfig);
        assertEquals("reinit-upgrade", sdk.lastInitializeComment);
        assertNoFetch();
    }

    @Test
    public void emptyConfigAfterValidIsIgnoredAndNotForwarded() {
        ApproovService.initialize(context, CONFIG);
        sdk.calls.clear();

        ApproovService.initialize(context, "", "anything");

        assertEquals("no SDK call at all", Collections.<String>emptyList(), sdk.snapshot());
        assertTrue(ApproovService.isApproovServiceEnabled());
        assertTrue(ApproovService.isApproovProtectionEnabled());
    }

    @Test
    public void nullConfigIsRejectedWithNoStateChange() {
        assertThrows(IllegalArgumentException.class, () -> ApproovService.initialize(context, null));
        assertFalse(ApproovService.isApproovServiceEnabled());
        assertEquals(Collections.<String>emptyList(), sdk.snapshot());
    }

    // ---- helpers ----

    private void configureEverything() {
        ApproovService.setTokenHeader("X-Approov", "Bearer ");
        ApproovService.setTraceIDHeader(null);
        ApproovService.setStatusHeader("X-Approov-Status");
        ApproovService.setBindingHeader("Authorization");
        ApproovService.addSubstitutionHeader("Api-Key", "Key ");
        ApproovService.addSubstitutionQueryParam("api_key");
        ApproovService.addExclusionURLRegex("^https://excluded\\.example\\.com/");
        ApproovService.setServiceMutator(mutator);
        ApproovService.enableMessageSigning();
        ApproovService.setStaleProtectionRefreshPeriod(1234);
        ApproovService.setOkHttpClientBuilder(builder);
    }

    private void assertConfiguredEverything() {
        assertEquals("X-Approov", ApproovService.getTokenHeader());
        assertEquals("Bearer ", ApproovService.getTokenPrefix());
        assertNull(ApproovService.getTraceIDHeader());
        assertEquals("X-Approov-Status", ApproovService.getStatusHeader());
        assertEquals("Authorization", ApproovService.getBindingHeader());
        assertEquals(Collections.singletonMap("Api-Key", "Key "), ApproovService.getSubstitutionHeaders());
        assertEquals(Collections.singleton("api_key"), ApproovService.getSubstitutionQueryParams().keySet());
        assertEquals(Collections.singleton("^https://excluded\\.example\\.com/"),
                ApproovService.getExclusionURLRegexs().keySet());
        assertSame(mutator, ApproovService.getServiceMutator());
        assertTrue(ApproovService.isMessageSigningEnabled());
        assertEquals(1234, ApproovService.getStaleProtectionRefreshPeriod());
        assertEquals("the builder set before is the one used", 4321, ApproovService.getOkHttpClient().callTimeoutMillis());
    }

    private void assertNoFetch() {
        List<String> calls = sdk.snapshot();
        for (String call : calls)
            assertFalse("the layer starts no fetch: " + calls, call.startsWith("fetch"));
    }
}
