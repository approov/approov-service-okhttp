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
import static org.junit.Assert.assertNotSame;
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
 * The initialize contract. The layer stores and compares no account ID: it
 * forwards every non-empty initialize to the SDK with the comment verbatim and
 * lets the SDK judge repeats, and rethrows any SDK exception unchanged with its
 * own state untouched. Every initialize that is not ignored (a non-empty config
 * the SDK accepts, a repeat of the same config and comment included, or bypass
 * mode the first time) resets the configuration to its defaults, as in 3.5.8,
 * while the logging level is kept; an empty config once protection is enabled is
 * ignored and resets nothing. The SDK is the mini SDK, already initialized by
 * setUp as it would be by the first caller in the process; RecordingSdk
 * records what reaches it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class InitializeContract370Test {
    private static final String CONFIG = "#test-account#mAxOF0ekJUOC36J5XWmVmVipOcUoEdMjhPSp2FVtyTo=";
    private static final String OTHER_CONFIG = "#test-account-other#mAxOF0ekJUOC36J5XWmVmVipOcUoEdMjhPSp2FVtyTo=";
    private static final String SDK_REJECTION =
            "Approov SDK has already been initialized with a different configuration";

    private Context context;
    private RecordingSdk sdk;
    // the service mutator configureEverything installs, which is message signing with a host factory, so that
    // a reset of the mutator also turns message signing off
    private final ApproovDefaultMessageSigning mutator = new ApproovDefaultMessageSigning()
            .setDefaultFactory(ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory())
            .putHostFactory("signed.example.com", ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory());
    private final OkHttpClient.Builder builder = new OkHttpClient.Builder().callTimeout(4321, TimeUnit.MILLISECONDS);

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        AttesterProxyController.reset();
        // the SDK holds CONFIG, as after the first initialize in the process; a
        // "reinit..." comment makes the mini SDK accept it whatever it held before
        Approov.initialize(context, CONFIG, "auto", "reinit-contract-setup");
        ApproovService.reset();
        sdk = RecordingSdk.install();
    }

    @After
    public void tearDown() {
        ApproovService.reset();
        RecordingSdk.uninstall();
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
        // a fresh process: the SDK set up with another comment refuses the same configuration with this one
        AttesterProxyController.resetSdkForTesting();
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
    public void sameConfigAndCommentRepeatIsForwardedAndResetsTheConfiguration() {
        // a fresh process: the SDK set up with another comment refuses the same configuration with this one
        AttesterProxyController.resetSdkForTesting();
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
        assertDefaultConfiguration();
    }

    @Test
    public void aRepeatTheSdkPerformsAgainResetsTheConfiguration() {
        ApproovService.initialize(context, CONFIG, "reinit-a");
        configureEverything();

        // the SDK accepts a "reinit..." comment as a new initialization (true)
        ApproovService.initialize(context, CONFIG, "reinit-b");

        assertEquals("reinit-b", sdk.lastInitializeComment);
        assertTrue(ApproovService.isApproovProtectionEnabled());
        assertDefaultConfiguration();
    }

    // ---- (b) an initialize that is not ignored resets the configuration ----

    @Test
    public void configurationSetBeforeFirstInitializeIsReset() {
        configureEverything();

        ApproovService.initialize(context, CONFIG, null);

        assertTrue(ApproovService.isApproovProtectionEnabled());
        assertDefaultConfiguration();
    }

    @Test
    public void configurationSetBeforeFirstBypassInitializeIsReset() {
        configureEverything();

        ApproovService.initialize(context, "", null);

        assertTrue(ApproovService.isApproovServiceEnabled());
        assertFalse(ApproovService.isApproovProtectionEnabled());
        assertDefaultConfiguration();
    }

    @Test
    public void configurationIsResetOnUpgradeFromBypassToProtected() {
        ApproovService.initialize(context, "");
        configureEverything();

        ApproovService.initialize(context, CONFIG);

        assertTrue(ApproovService.isApproovProtectionEnabled());
        assertDefaultConfiguration();
    }

    @Test
    public void configurationSetAfterInitializeIsKeptUntilTheNextInitialize() {
        ApproovService.initialize(context, CONFIG);
        configureEverything();

        assertConfiguredEverything();
    }

    @Test
    public void theSubstitutionAndExclusionGettersThrowBeforeAnyInitialize() {
        for (Runnable getter : new Runnable[] {ApproovService::getSubstitutionHeaders,
                ApproovService::getSubstitutionQueryParams, ApproovService::getExclusionURLRegexs}) {
            IllegalStateException thrown = assertThrows(IllegalStateException.class, getter::run);
            assertEquals("ApproovService is not initialized", thrown.getMessage());
        }

        ApproovService.initialize(context, "");
        assertTrue(ApproovService.getSubstitutionHeaders().isEmpty());
        assertTrue(ApproovService.getSubstitutionQueryParams().isEmpty());
        assertTrue(ApproovService.getExclusionURLRegexs().isEmpty());
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
        assertConfiguredEverythingBeforeInitialize();
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
        assertConfiguredEverythingBeforeInitialize();
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
        configureEverything();
        sdk.calls.clear();

        ApproovService.initialize(context, "", "anything");

        assertEquals("no SDK call at all", Collections.<String>emptyList(), sdk.snapshot());
        assertTrue(ApproovService.isApproovServiceEnabled());
        assertTrue(ApproovService.isApproovProtectionEnabled());
        assertConfiguredEverything();
    }

    @Test
    public void nullConfigIsRejectedWithNoStateChange() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> ApproovService.initialize(context, null));
        assertEquals("config must not be null; pass \"\" for bypass mode", thrown.getMessage());
        assertFalse(ApproovService.isApproovServiceEnabled());
        assertEquals(Collections.<String>emptyList(), sdk.snapshot());
    }

    // ---- helpers ----

    // the client in place after configureEverything
    private OkHttpClient configuredClient;

    private void configureEverything() {
        ApproovService.setTokenHeader("X-Approov", "Bearer ");
        ApproovService.setTraceIDHeader(null);
        ApproovService.setUseApproovStatusIfNoToken(true);
        ApproovService.setBindingHeader("Authorization");
        ApproovService.addSubstitutionHeader("Api-Key", "Key ");
        ApproovService.addSubstitutionQueryParam("api_key");
        ApproovService.addExclusionURLRegex("^https://excluded\\.example\\.com/");
        ApproovService.setServiceMutator(mutator);
        ApproovService.setStaleProtectionRefreshPeriod(1234);
        ApproovService.setOkHttpClientBuilder(builder);
        ApproovService.recordLastARC(resultWithArc("CONFIGUREDARC"));
        ApproovService.setLoggingLevel(ApproovLogLevel.ERROR);
        configuredClient = ApproovService.getOkHttpClient();
    }

    // the configuration outside the substitution and exclusion maps, which every assertion shares
    private void assertConfiguredScalars() {
        assertEquals("X-Approov", ApproovService.getTokenHeader());
        assertEquals("Bearer ", ApproovService.getTokenPrefix());
        assertNull(ApproovService.getTraceIDHeader());
        assertTrue(ApproovService.getUseApproovStatusIfNoToken());
        assertEquals("Authorization", ApproovService.getBindingHeader());
        assertSame("the message signing mutator and its host factories are kept", mutator,
                ApproovService.getServiceMutator());
        assertEquals(1234, ApproovService.getStaleProtectionRefreshPeriod());
        assertSame("the cached client is kept", configuredClient, ApproovService.getOkHttpClient());
        assertEquals("the builder set before is the one used", 4321, ApproovService.getOkHttpClient().callTimeoutMillis());
        assertEquals("CONFIGUREDARC", ApproovService.getLastARC());
        assertEquals(ApproovLogLevel.ERROR, ApproovLog.getLevel());
    }

    private void assertConfiguredEverything() {
        assertConfiguredScalars();
        assertEquals(Collections.singletonMap("Api-Key", "Key "), ApproovService.getSubstitutionHeaders());
        assertEquals(Collections.singleton("api_key"), ApproovService.getSubstitutionQueryParams().keySet());
        assertEquals(Collections.singleton("^https://excluded\\.example\\.com/"),
                ApproovService.getExclusionURLRegexs().keySet());
    }

    // before any successful initialize the getters of the maps throw, so the maps are read directly
    private void assertConfiguredEverythingBeforeInitialize() {
        assertConfiguredScalars();
        assertThrows(IllegalStateException.class, ApproovService::getSubstitutionHeaders);
        assertEquals(Collections.singletonMap("Api-Key", "Key "), staticField("substitutionHeaders"));
        assertEquals(Collections.singleton("api_key"), ((java.util.Map<?, ?>) staticField("substitutionQueryParams")).keySet());
        assertEquals(Collections.singleton("^https://excluded\\.example\\.com/"),
                ((java.util.Map<?, ?>) staticField("exclusionURLRegexs")).keySet());
    }

    // every setting configureEverything changed is back to its default, except the logging level
    private void assertDefaultConfiguration() {
        assertEquals("Approov-Token", ApproovService.getTokenHeader());
        assertEquals("", ApproovService.getTokenPrefix());
        assertEquals("Approov-TraceID", ApproovService.getTraceIDHeader());
        assertFalse(ApproovService.getUseApproovStatusIfNoToken());
        assertNull(ApproovService.getBindingHeader());
        assertTrue(ApproovService.getSubstitutionHeaders().isEmpty());
        assertTrue(ApproovService.getSubstitutionQueryParams().isEmpty());
        assertTrue(ApproovService.getExclusionURLRegexs().isEmpty());
        assertSame(ApproovServiceMutator.DEFAULT, ApproovService.getServiceMutator());
        assertFalse("message signing is off", ApproovService.getServiceMutator() instanceof ApproovDefaultMessageSigning);
        assertEquals(3000, ApproovService.getStaleProtectionRefreshPeriod());
        assertEquals("", ApproovService.getLastARC());
        assertEquals("the logging level is not reset", ApproovLogLevel.ERROR, ApproovLog.getLevel());
        OkHttpClient client = ApproovService.getOkHttpClient();
        assertNotSame("the cached client is dropped", configuredClient, client);
        assertEquals("the builder set before is dropped", 0, client.callTimeoutMillis());
    }

    private static Object staticField(String name) {
        try {
            java.lang.reflect.Field field = ApproovService.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("ApproovService." + name + " changed", e);
        }
    }

    // a fetch result carrying an ARC, built as the SDK builds it
    private static Approov.TokenFetchResult resultWithArc(String arc) {
        try {
            java.lang.reflect.Constructor<Approov.TokenFetchResult> constructor =
                    Approov.TokenFetchResult.class.getDeclaredConstructor(Approov.TokenFetchStatus.class,
                            String.class, String.class, String.class, String.class, String.class,
                            boolean.class, boolean.class, byte[].class);
            constructor.setAccessible(true);
            return constructor.newInstance(Approov.TokenFetchStatus.SUCCESS, "", null, null, arc, "", false, false,
                    null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("the mini-SDK TokenFetchResult constructor changed", e);
        }
    }

    private void assertNoFetch() {
        List<String> calls = sdk.snapshot();
        for (String call : calls)
            assertFalse("the layer starts no fetch: " + calls, call.startsWith("fetch"));
    }
}
