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

import android.util.Log;
import android.content.Context;
import android.os.SystemClock;
import androidx.annotation.VisibleForTesting;

import com.criticalblue.approovsdk.Approov;

import java.io.IOException;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.net.ssl.SSLPeerUnverifiedException;

import okhttp3.CertificatePinner;
import okhttp3.Connection;
import okhttp3.Handshake;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * ApproovService provides a mediation layer to the Approov SDK, enabling secure
 * token-based
 * authentication and dynamic pinning for network requests. It offers methods to
 * initialize
 * the SDK, configure token headers, handle secure strings, and manage OkHttp
 * clients.
 */
public class ApproovService {
    // logging tag
    private static final String TAG = "ApproovService";

    // default header that will be added to Approov enabled requests
    private static final String APPROOV_TOKEN_HEADER = "Approov-Token";

    // default header that will carry any optional Approov TraceID debug value from
    // the SDK
    private static final String APPROOV_TRACE_ID_HEADER = "Approov-TraceID";

    // default header that reports the Approov token fetch status for every request
    // processed by Approov, as the lowercased SDK status name (for example "success"
    // or "no_network"), so that the backend can tell why a request carries no token
    private static final String APPROOV_STATUS_HEADER = "Approov-Status";

    // default prefix to be added before the Approov token by default
    private static final String APPROOV_TOKEN_PREFIX = "";

    // name for the default builder
    private static final String DEFAULT_BUILDER_NAME = "_default";

    // default period in milliseconds after which a request that has been held
    // between Approov protection being applied and actual transmission (such as by
    // a device deep sleep or doze period) has its protection refreshed at the
    // network layer before being sent - this must be comfortably less than both
    // the Approov token lifetime and the default message signature expiry (15s)
    private static final long DEFAULT_STALE_PROTECTION_REFRESH_MS = 3000;

    // true once any initialize has succeeded, bypass mode (an empty account ID)
    // included: the layer is processing requests
    private static boolean approovServiceEnabled = false;

    // true once the Approov SDK is initialized and Approov protection is active
    private static boolean approovProtectionEnabled = false;

    // the Attestation Response Code (ARC) from the most recent token fetch result
    // seen by this layer, or empty if none or if ARC is not enabled for the account
    private static String lastARC = "";

    // the Approov pinning interceptor shared by every client, created on first use;
    // it applies no pins while Approov protection is not enabled
    private static ApproovPinningInterceptor pinningInterceptor = null;

    // builders to be used for new OkHttp clients where each can be named
    private static Map<String, OkHttpClient.Builder> okHttpBuilders = new HashMap<>();

    // cached OkHttpClients to use for each of the named builders
    private static Map<String, OkHttpClient> okHttpClients = new HashMap<>();

    // header to be used to send Approov tokens
    private static String approovTokenHeader = APPROOV_TOKEN_HEADER;

    // header used to send any optional Approov TraceID debug value provided by the
    // SDK
    private static String approovTraceIDHeader = APPROOV_TRACE_ID_HEADER;

    // header used to report the Approov fetch status to the backend, or null if
    // disabled
    private static String approovStatusHeader = APPROOV_STATUS_HEADER;

    // any prefix String to be added before the transmitted Approov token
    private static String approovTokenPrefix = APPROOV_TOKEN_PREFIX;

    // any header to be used for binding in Approov tokens or null if not set
    private static String bindingHeader = null;

    // period in milliseconds after which a request held between protection and
    // transmission has its Approov protection refreshed at the network layer, or
    // <=0 if stale protection refresh is disabled
    private static long staleProtectionRefreshMS = DEFAULT_STALE_PROTECTION_REFRESH_MS;

    // The mutator instance used to control ApproovService behavior at key points in
    // the flow. Unless set using the ApproovService.setServiceMutator() method, the
    // out-of-the-box decisions of ApproovServiceMutator.DEFAULT are used. Mutators
    // carry decisions only: message signing is switched separately.
    private static ApproovServiceMutator serviceMutator = ApproovServiceMutator.DEFAULT;

    // the message signing applied to protected requests while it is enabled, holding
    // the default and per host signature parameters factories
    private static ApproovDefaultMessageSigning messageSigning = new ApproovDefaultMessageSigning();

    // true if message signing is enabled; off by default in 3.8.0
    private static boolean messageSigningEnabled = false;

    // map of headers that should have their values substituted for secure strings,
    // mapped to their
    // required prefixes
    private static Map<String, String> substitutionHeaders = new HashMap<>();

    // set of query parameters that may be substituted, specified by the key name
    // and mapped to the compiled Pattern
    private static Map<String, Pattern> substitutionQueryParams = new HashMap<>();

    // set of URL regexs that should be excluded from any Approov protection, mapped
    // to the compiled Pattern
    private static Map<String, Pattern> exclusionURLRegexs = new HashMap<>();

    // thin injectable boundary around the static Approov SDK API; package-private
    // so that tests can record the calls the layer makes, the public API is unchanged
    private static volatile ApproovSdkFacade sdkFacade = new DefaultApproovSdkFacade();

    /**
     * Construction is disallowed as this is a static only class.
     */
    private ApproovService() {
    }

    /**
     * Gets the boundary through which every Approov SDK call is made.
     *
     * @return the SDK facade
     */
    static ApproovSdkFacade sdk() {
        return sdkFacade;
    }

    /**
     * Converts a RuntimeException thrown by an Approov SDK call (an
     * IllegalStateException, an IllegalArgumentException or anything else) into an
     * ApproovException, an IOException, with the SDK's exception as its cause. On
     * the request path an exception other than an IOException is rethrown by OkHttp
     * on its dispatcher thread for an enqueued call, which terminates the app, so no
     * SDK call reachable from a request may let one escape (SPECIFICATION 1.6); the
     * direct methods report an SDK failure the same way (SPECIFICATION 1.8).
     *
     * @param operation what the layer was doing, for the exception message
     * @param e         the exception thrown by the SDK
     * @return the exception to throw
     */
    static ApproovException sdkFailure(String operation, RuntimeException e) {
        Log.e(TAG, operation + ": Approov SDK failure: " + e);
        return new ApproovException(operation + ": Approov SDK failure: " + e, e);
    }

    /**
     * A call into the installed service mutator.
     */
    interface MutatorHook<T> {
        T call() throws IOException;
    }

    /**
     * Calls a hook of the installed service mutator on the request path. An
     * IOException the hook throws is the app's own abort and passes through
     * unchanged (SPECIFICATION 1.6). Any RuntimeException, a programming error such
     * as a null pointer or a deliberate runtime exception, is converted to an
     * ApproovException with the original as its cause and logged at error level
     * naming the hook, so that the request fails through OkHttp's normal error
     * channel instead of being rethrown on the dispatcher thread for an enqueued
     * call, which would terminate the app (SPECIFICATION 1.6.1).
     *
     * @param hook the name of the hook, for the log and the exception message
     * @param call the call into the mutator
     * @return what the hook returned
     * @throws IOException if the hook throws one, or throws a RuntimeException
     */
    static <T> T callMutator(String hook, MutatorHook<T> call) throws IOException {
        try {
            return call.call();
        } catch (IllegalArgumentException e) {
            // OkHttp refusing a header the hook set quotes the value: never kept
            ApproovException unsafe = unsafeHeaderFromOkHttp(e);
            throw (unsafe != null) ? unsafe : mutatorFailure(hook, e);
        } catch (RuntimeException e) {
            throw mutatorFailure(hook, e);
        }
    }

    /**
     * Converts a RuntimeException thrown by a hook of the installed service mutator
     * into an ApproovException with the original as its cause, logged at error level
     * naming the hook (SPECIFICATION 1.6.1).
     *
     * @param hook the name of the hook
     * @param e    the exception the hook threw
     * @return the exception to throw
     */
    static ApproovException mutatorFailure(String hook, RuntimeException e) {
        Log.e(TAG, "ApproovServiceMutator." + hook + " threw " + e);
        return new ApproovException("ApproovServiceMutator." + hook + " failed: " + e, e);
    }

    // OkHttp's message for a header value it refuses, which quotes the value unless
    // the header is one OkHttp considers sensitive
    private static final Pattern OKHTTP_HEADER_VALUE_ERROR =
            Pattern.compile("^Unexpected char \\S+ at \\d+ in (.*?) value(?:: .*)?$", Pattern.DOTALL);

    // OkHttp's message for a header name it refuses
    private static final String OKHTTP_HEADER_NAME_ERROR = "Unexpected char ";

    /**
     * Indicates whether a header value can be carried by OkHttp: horizontal tab and
     * printable ASCII only, the rule OkHttp applies to header values and the one
     * approov-service-android uses. A secure string can break it (a non-ASCII or DEL
     * value set in the account, or any value an app set with a new definition); the
     * token and the trace ID are always base64url and cannot.
     *
     * @param value the header value
     * @return true if the value can be set
     */
    static boolean isSafeHeaderValue(String value) {
        if (value == null)
            return true;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c != '\t') && ((c < 0x20) || (c > 0x7e)))
                return false;
        }
        return true;
    }

    /**
     * Indicates whether a secure string substituted into an occurrence of a query
     * parameter reaches the backend unchanged. The URL is parsed as OkHttp will
     * send it and the given occurrence of the parameter is read back from the
     * query OkHttp stores: it must be printable ASCII and either the secure string
     * itself or its percent-encoded form. OkHttp percent-encodes non-ASCII and DEL,
     * which the backend decodes to the same value, but silently drops tab, LF, FF
     * and CR from a URL, and an & or a # in the value changes the structure of the
     * URL. Mirrors approov-service-android's check (ee11052), reading only the
     * query so that a value cut short by a fragment is caught.
     *
     * @param url          the URL with the secure string substituted
     * @param pattern      the query parameter's pattern, whose group 1 is its value
     * @param occurrence   the occurrence of the parameter in the query, from 0
     * @param secureString the secure string substituted
     * @return true if the backend receives the secure string unchanged
     */
    static boolean isCarriedInQuery(String url, Pattern pattern, int occurrence, String secureString) {
        HttpUrl parsed = HttpUrl.parse(url);
        if ((parsed == null) || (parsed.encodedQuery() == null))
            return false;
        Matcher stored = pattern.matcher("?" + parsed.encodedQuery());
        String value = null;
        for (int i = 0; i <= occurrence; i++) {
            if (!stored.find())
                return false;
            value = stored.group(1);
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < 0x20) || (c > 0x7e))
                return false;
        }
        return value.equals(secureString) || secureString.equals(percentDecode(value));
    }

    /**
     * Decodes %XX escapes as UTF-8, leaving every other character as it is.
     *
     * @param value the encoded value
     * @return the decoded value, or null if an escape is malformed
     */
    private static String percentDecode(String value) {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%') {
                if (i + 2 >= value.length())
                    return null;
                int high = Character.digit(value.charAt(i + 1), 16);
                int low = Character.digit(value.charAt(i + 2), 16);
                if ((high < 0) || (low < 0))
                    return null;
                bytes.write((high << 4) | low);
                i += 2;
            } else {
                byte[] encoded = String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                bytes.write(encoded, 0, encoded.length);
            }
        }
        return new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Reports a header value a header cannot carry, set from a value the app
     * supplied: logged at error level and returned as an ApproovException naming
     * the header, with no cause and never quoting the value (SPECIFICATION 1.7(a)).
     *
     * @param header the header name
     * @return the exception to throw
     */
    static ApproovException unsafeHeaderValue(String header) {
        String message = "Approov cannot set header " + header
                + ": its value contains a character a header value cannot carry";
        Log.e(TAG, message);
        return new ApproovException(message);
    }

    /**
     * Converts OkHttp's IllegalArgumentException for a header name or value it
     * refuses, thrown inside a mutator hook, into the layer's exception without
     * quoting the value: OkHttp's message carries it and the value may be a secret,
     * so neither the message nor the cause is kept.
     *
     * @param e the exception the hook threw
     * @return the exception to throw, or null if e is not OkHttp's header error
     */
    private static ApproovException unsafeHeaderFromOkHttp(IllegalArgumentException e) {
        String message = e.getMessage();
        if ((message == null) || !message.startsWith(OKHTTP_HEADER_NAME_ERROR))
            return null;
        Matcher matcher = OKHTTP_HEADER_VALUE_ERROR.matcher(message);
        if (matcher.matches())
            return unsafeHeaderValue(matcher.group(1));
        String nameError = "Approov cannot set a header: its name contains a character a header name cannot carry";
        Log.e(TAG, nameError);
        return new ApproovException(nameError);
    }

    /**
     * Describes a service mutator for a log message without letting an exception
     * from its toString escape.
     *
     * @param mutator the mutator
     * @return its description
     */
    static String describe(ApproovServiceMutator mutator) {
        try {
            return String.valueOf(mutator);
        } catch (RuntimeException e) {
            return mutator.getClass().getName();
        }
    }

    /**
     * Installs the SDK boundary, for tests only.
     *
     * @param facade the SDK facade to use
     */
    @VisibleForTesting
    static synchronized void setSdkFacadeForTesting(ApproovSdkFacade facade) {
        if (facade == null)
            throw new IllegalArgumentException("SDK facade must not be null");
        sdkFacade = facade;
    }

    /**
     * Initializes the ApproovService with an Approov account ID and comment. Every
     * caller in the process (for example native code and a React Native module)
     * may call this; all of them drive the same service state.
     *
     * A non-empty account ID is always forwarded to the Approov SDK with the
     * comment unchanged, and the SDK decides: the first initialization succeeds, a
     * repeat with the same account ID and comment is reported by the SDK as
     * already initialized and returns at once, and anything else the SDK rejects
     * (a different account ID or comment, or an account ID that did not reach the
     * app intact) is thrown unchanged to the caller with the service state
     * untouched. An empty account ID enables the service in bypass mode, without
     * Approov protection; it is ignored once protection is enabled. Initialization
     * never resets any configuration: headers, binding, substitutions, exclusions,
     * the mutator, message signing, the OkHttp builders and the other settings
     * keep the values set before or after it. It changes only what
     * isApproovServiceEnabled() and isApproovProtectionEnabled() report.
     *
     * @param context the Application context
     * @param config  your Approov account ID (the SDK config string from the
     *                onboarding email or "approov sdk -getConfigString"), or empty
     *                for bypass mode with no SDK initialization
     * @param comment the comment passed to the SDK unchanged, or null for no
     *                comment (null and "" are different comments to the SDK)
     * @throws IllegalArgumentException if the context or account ID is null, or
     *                                  the SDK rejects the account ID
     * @throws IllegalStateException    if the SDK is already initialized with a
     *                                  different account ID or comment
     */
    public static void initialize(Context context, String config, String comment) {
        // the pins are rebuilt after the ApproovService monitor is released, keeping
        // the lock order ApproovService then pinning interceptor. No prefetch is
        // started: the SDK manages prefetching (SPECIFICATION 5.2)
        if (initializeLocked(context, config, comment)) {
            try {
                rebuildPins();
            } catch (ApproovException e) {
                // the SDK is initialized and protection is enabled; the pinning
                // interceptor asks for the pins again before its next connection check
                // and fails that connection while they cannot be read
                Log.e(TAG, "Approov pins not built on initialization: " + e.getMessage());
            }
        }
    }

    /**
     * Performs the state changes of initialize() under the ApproovService monitor.
     * Any exception from the SDK propagates before any state is changed.
     *
     * @return true if Approov protection was newly enabled, or the SDK performed a
     *         new initialization, so the pins must be rebuilt
     */
    private static synchronized boolean initializeLocked(Context context, String config, String comment) {
        if (context == null)
            throw new IllegalArgumentException("ApproovService.initialize requires a non-null context");
        if (config == null)
            throw new IllegalArgumentException("config must not be null; pass \"\" for bypass mode");

        if (config.isEmpty()) {
            if (approovProtectionEnabled) {
                // an empty account ID never downgrades an active Approov protection and
                // is not forwarded to the SDK
                Log.d(TAG, "Approov protection already enabled; ignoring initialization with an empty account ID");
            } else {
                Log.i(TAG, "ApproovService enabled in bypass mode (empty account ID): Approov protection is not active");
                approovServiceEnabled = true;
            }
            return false;
        }

        // forward every non-empty account ID, with the comment exactly as given; the
        // SDK is the only judge of a repeat call and any exception it throws reaches
        // the caller with the service state untouched
        boolean newlyInitialized;
        try {
            newlyInitialized = sdk().initialize(context.getApplicationContext(), config, "auto", comment);
        } catch (RuntimeException e) {
            Log.e(TAG, "Approov SDK initialization failed: " + e.getMessage());
            throw e;
        }
        boolean protectionWasEnabled = approovProtectionEnabled;
        approovServiceEnabled = true;
        approovProtectionEnabled = true;
        if (newlyInitialized)
            Log.i(TAG, "Approov SDK initialized: Approov protection enabled");
        else
            Log.d(TAG, "Approov SDK already initialized with the same account ID and comment");
        if (!protectionWasEnabled) {
            try {
                sdk().setUserProperty("approov-service-okhttp/" + BuildConfig.APPROOV_SERVICE_VERSION);
            } catch (RuntimeException e) {
                // the property is diagnostic only and must not fail a successful initialization
                Log.e(TAG, "Approov user property not set: " + e.getMessage());
            }
        }

        // A repeat that the SDK reports as already initialized (false) while protection
        // is already enabled changed nothing, so the pins are not rebuilt. They are
        // rebuilt when this layer first enables protection (a pinner built before holds
        // no pins, including when another caller initialized the SDK first) and
        // whenever the SDK reports a new initialization (true), since a
        // re-initialization may carry new options.
        return newlyInitialized || !protectionWasEnabled;
    }

    /**
     * Initializes the ApproovService with an Approov account ID and no comment (a
     * null comment is passed to the SDK). See initialize(Context, String, String).
     *
     * @param context the Application context
     * @param config  your Approov account ID (the SDK config string from the
     *                onboarding email or "approov sdk -getConfigString"), or empty
     *                for bypass mode with no SDK initialization
     * @throws IllegalArgumentException if the context or account ID is null, or
     *                                  the SDK rejects the account ID
     * @throws IllegalStateException    if the SDK is already initialized with a
     *                                  different account ID or comment
     */
    public static void initialize(Context context, String config) {
        // default uses null comment
        initialize(context, config, null);
    }

    /**
     * Indicates whether the ApproovService is enabled, which is true once any
     * initialize has succeeded, including bypass mode (an empty account ID). Before
     * that, requests made through the OkHttpClient go out without Approov
     * processing. Replaces isInitialized(), removed in 3.8.0.
     *
     * @return true if the service has been enabled by a successful initialize
     */
    public static synchronized boolean isApproovServiceEnabled() {
        return approovServiceEnabled;
    }

    /**
     * Indicates whether Approov protection is enabled, which is true once the
     * Approov SDK has been initialized by a successful initialize with a non-empty
     * account ID. It is false before initialization and in bypass mode, where
     * requests pass through without Approov processing. Replaces isApproovProtectionEnabled(),
     * removed in 3.8.0.
     *
     * @return true if the Approov SDK is initialized and requests are protected
     */
    public static synchronized boolean isApproovProtectionEnabled() {
        return approovProtectionEnabled;
    }

    /**
     * Resets the ApproovService state. This should only be used for testing
     * purposes.
     */
    @VisibleForTesting
    static synchronized void reset() {
        approovServiceEnabled = false;
        approovProtectionEnabled = false;
        lastARC = "";
        pinningInterceptor = null;
        okHttpBuilders = new HashMap<>();
        okHttpClients = new HashMap<>();
        approovTokenHeader = APPROOV_TOKEN_HEADER;
        approovTraceIDHeader = APPROOV_TRACE_ID_HEADER;
        approovStatusHeader = APPROOV_STATUS_HEADER;
        approovTokenPrefix = APPROOV_TOKEN_PREFIX;
        bindingHeader = null;
        staleProtectionRefreshMS = DEFAULT_STALE_PROTECTION_REFRESH_MS;
        serviceMutator = ApproovServiceMutator.DEFAULT;
        messageSigning = new ApproovDefaultMessageSigning();
        messageSigningEnabled = false;
        substitutionHeaders = new HashMap<>();
        substitutionQueryParams = new HashMap<>();
        exclusionURLRegexs = new HashMap<>();
        sdkFacade = new DefaultApproovSdkFacade();
    }

    /**
     * Switches message signing on with the default signature parameters factory
     * ({@link ApproovDefaultMessageSigning#generateDefaultSignatureParametersFactory()}),
     * which produces both the install signature (member "install",
     * ecdsa-p256-sha256, per installation key held in the device secure hardware)
     * and the account signature (member "account", hmac-sha256, account key
     * delivered on attestation) over the same covered components. See
     * {@link #enableMessageSigning(ApproovDefaultMessageSigning.SignatureParametersFactory)}.
     */
    public static void enableMessageSigning() {
        enableMessageSigning(ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory());
    }

    /**
     * Switches message signing on. Message signing is off by default in 3.8.0 (it
     * is on and compulsory from 4.0.0). Once on, every request carrying the Approov
     * token header is signed (RFC 9421) after the service mutator's decisions,
     * the secure string substitutions and its processed request callback, so the
     * signature covers what is sent, and it is signed again whenever its
     * protection is reapplied. Signing is independent of the service mutator: it
     * works the same under ApproovServiceMutator.DEFAULT, CLOSE_FAILURE,
     * ALWAYS_PROCEED and any custom mutator, and setServiceMutator never switches
     * it on or off. initialize() leaves it, and any host factories, as they are.
     *
     * @param defaultFactory the signature parameters factory for every host
     *                       without a factory of its own (see
     *                       putMessageSigningHostFactory), or null for the default
     *                       factory
     */
    public static synchronized void enableMessageSigning(
            ApproovDefaultMessageSigning.SignatureParametersFactory defaultFactory) {
        if (defaultFactory == null)
            defaultFactory = ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory();
        Log.d(TAG, "enableMessageSigning");
        messageSigning.setDefaultFactory(defaultFactory);
        messageSigningEnabled = true;
    }

    /**
     * Switches message signing off: no Signature or Signature-Input header is
     * added to any request. The configured factories are kept for a later
     * enableMessageSigning.
     */
    public static synchronized void disableMessageSigning() {
        Log.d(TAG, "disableMessageSigning");
        messageSigningEnabled = false;
    }

    /**
     * Indicates whether message signing is enabled.
     *
     * @return true if protected requests are signed, false otherwise
     */
    public static synchronized boolean isMessageSigningEnabled() {
        return messageSigningEnabled;
    }

    /**
     * Signs requests to one host with the given signature parameters factory
     * instead of the default factory passed to enableMessageSigning. It applies
     * while message signing is enabled; initialize() keeps it.
     *
     * @param hostName the host name, matched without regard to case and without
     *                 any port
     * @param factory  the signature parameters factory for the host, or null to
     *                 remove the host's factory so that the default factory
     *                 applies again
     */
    public static synchronized void putMessageSigningHostFactory(String hostName,
            ApproovDefaultMessageSigning.SignatureParametersFactory factory) {
        Log.d(TAG, "putMessageSigningHostFactory " + hostName);
        messageSigning.putHostFactory(hostName, factory);
    }

    /**
     * Gets the message signing to apply to protected requests.
     *
     * @return the message signing, or null while message signing is disabled
     */
    static synchronized ApproovDefaultMessageSigning getActiveMessageSigning() {
        return messageSigningEnabled ? messageSigning : null;
    }

    /**
     * Sets a development key indicating that the app is a development version and
     * it should
     * pass attestation even if the app is not registered or it is running on an
     * emulator. The
     * development key value can be rotated at any point in the account if a version
     * of the app
     * containing the development key is accidentally released. This is primarily
     * used for situations where the app package must be modified or resigned in
     * some way as part of the testing process.
     *
     * @param devKey is the development key to be used
     * @throws ApproovException if there was a problem, or if Approov protection is
     *                          not enabled (before initialization or in bypass mode)
     */
    public static synchronized void setDevKey(String devKey) throws ApproovException {
        requireProtection("setDevKey");
        try {
            ApproovService.sdk().setDevKey(devKey);
            Log.d(TAG, "setDevKey");
        } catch (RuntimeException e) {
            throw new ApproovException(e);
        }
    }

    /**
     * Guards a public method that consumes the Approov SDK: while Approov
     * protection is not enabled (before initialize and in bypass mode) it throws
     * without calling the SDK, even if another caller initialized the SDK.
     *
     * @param method the name of the public method, for the exception message
     * @throws ApproovException if Approov protection is not enabled
     */
    private static void requireProtection(String method) throws ApproovException {
        if (!isApproovProtectionEnabled()) {
            Log.e(TAG, method + ": Approov protection not enabled");
            throw new ApproovException(method + ": Approov protection not enabled");
        }
    }

    /**
     * Sets the header that the Approov token is added on, as well as an optional
     * prefix String (such as "Bearer "). By default the token is provided on
     * "Approov-Token" with no prefix. If no token could be obtained the header is
     * still added with an empty value (after any prefix) as evidence that Approov
     * processing occurred, and the reason is reported on the status header (see
     * setStatusHeader). Failure information is never placed in this header.
     *
     * @param header is the header to place the Approov token on
     * @param prefix is any prefix String for the Approov token header, or null
     *               for none
     */
    public static synchronized void setTokenHeader(String header, String prefix) {
        Log.d(TAG, "setTokenHeader " + header + ", " + prefix);
        approovTokenHeader = header;
        approovTokenPrefix = (prefix != null) ? prefix : APPROOV_TOKEN_PREFIX;
    }

    /**
     * Gets the header that is used to add the Approov token.
     *
     * @return String of the header used for the Approov token
     */
    public static synchronized String getTokenHeader() {
        return approovTokenHeader;
    }

    /**
     * Gets the prefix that is added before the Approov token in the header.
     *
     * @return String of the prefix added before the Approov token
     */
    public static synchronized String getTokenPrefix() {
        return approovTokenPrefix;
    }

    /**
     * Sets the header name that is used to pass any optional Approov TraceID debug
     * value. By default the TraceID is provided on "Approov-TraceID" if one is
     * available. Passing null disables adding the TraceID header.
     *
     * @param header is the name of the header on which to place the Approov
     *               TraceID, or null to disable the header
     */
    public static synchronized void setTraceIDHeader(String header) {
        Log.d(TAG, "setTraceIDHeader " + header);
        approovTraceIDHeader = header;
    }

    /**
     * Gets the name of the header that is used to hold the optional Approov
     * TraceID.
     *
     * @return String the name of the header used for the Approov TraceID, or
     *         null if disabled
     */
    public static synchronized String getTraceIDHeader() {
        return approovTraceIDHeader;
    }

    /**
     * Sets the header name that is used to report the Approov token fetch status to
     * the backend on every request processed by Approov. By default this is
     * "Approov-Status". The value is the SDK fetch status name in lowercase, for
     * example "success", "no_network", "untrusted_network", "no_approov_service" or
     * "rejected", identical on Android and iOS. It is sent on every processed
     * request, including successful ones, and tells the backend why this
     * particular request carries no attestation proof, to log against the request
     * and act on (rejecting it, for example). It is not sent on requests to
     * domains that are not protected by Approov. Passing null disables the header,
     * in which case a request that could not be protected is sent with an empty
     * token header and no explanation.
     *
     * @param header is the name of the header on which to report the Approov fetch
     *               status, or null to disable the header
     */
    public static synchronized void setStatusHeader(String header) {
        Log.d(TAG, "setStatusHeader " + header);
        approovStatusHeader = header;
    }

    /**
     * Gets the name of the header that is used to report the Approov fetch status.
     *
     * @return String the name of the header used for the Approov fetch status, or
     *         null if disabled
     */
    public static synchronized String getStatusHeader() {
        return approovStatusHeader;
    }

    /**
     * Builds the value of the status header from a token fetch result: the SDK
     * fetch status name in lowercase.
     *
     * @param approovResults the token fetch result
     * @return the value to set on the status header
     */
    static String buildStatusHeaderValue(Approov.TokenFetchResult approovResults) {
        return approovResults.getStatus().name().toLowerCase(Locale.ROOT);
    }

    /**
     * @deprecated Use {@link #setTokenHeader(String, String)}, which is the name
     *             used by every Approov service layer.
     */
    @Deprecated
    public static void setApproovHeader(String header, String prefix) {
        setTokenHeader(header, prefix);
    }

    /**
     * @deprecated Use {@link #setTraceIDHeader(String)}, which is the name used
     *             by every Approov service layer.
     */
    @Deprecated
    public static void setApproovTraceIDHeader(String header) {
        setTraceIDHeader(header);
    }

    /**
     * @deprecated Use {@link #getTokenHeader()}.
     */
    @Deprecated
    public static String getApproovTokenHeader() {
        return getTokenHeader();
    }

    /**
     * @deprecated Use {@link #getTraceIDHeader()}.
     */
    @Deprecated
    public static String getApproovTraceIDHeader() {
        return getTraceIDHeader();
    }

    /**
     * @deprecated Use {@link #getTokenPrefix()}.
     */
    @Deprecated
    public static String getApproovTokenPrefix() {
        return getTokenPrefix();
    }

    /**
     * Builds the value to be used for the Approov token header from a token
     * fetch result. If no token was obtained the value is empty (after any
     * prefix): failure information is never placed in the token header but is
     * reported on the status header instead.
     *
     * @param approovResults the token fetch result
     * @return the value to set on the Approov token header
     */
    static String buildTokenHeaderValue(String prefix, Approov.TokenFetchResult approovResults) {
        return prefix + approovResults.getToken();
    }

    /**
     * Reads the token header name and prefix as one consistent snapshot, so that a
     * request never combines the header name of one configuration with the prefix
     * of another when the app reconfigures them while requests are in flight.
     *
     * @return a two element array of header name and prefix
     */
    static synchronized String[] snapshotTokenHeader() {
        return new String[] { approovTokenHeader, approovTokenPrefix };
    }

    /**
     * Updates the data hash for token binding from any binding header present
     * on the given request, ahead of a token fetch. The binding header
     * presence is optional.
     *
     * @param request the request that may carry the binding header
     * @throws ApproovException if the SDK fails to set the data hash
     */
    static void updateBindingDataHash(Request request) throws ApproovException {
        String bindingHeader = getBindingHeader();
        if (bindingHeader != null) {
            // Header names are case-insensitive. A null value means the header is
            // absent; a present-but-empty value must still be forwarded to the SDK.
            String bindingValue = request.header(bindingHeader);
            if (bindingValue != null) {
                try {
                    ApproovService.sdk().setDataHashInToken(bindingValue);
                } catch (RuntimeException e) {
                    throw sdkFailure("Token binding for " + bindingHeader, e);
                }
            }
        }
    }

    /**
     * Forces a pinning rebuild if the given token fetch result indicates that
     * there was a dynamic configuration update, or that the SDK asks for the
     * current pins to be applied (isForceApplyPins) without a configuration
     * change. The pins are rebuilt once if both are set; fetchConfig acknowledges
     * a configuration change only.
     *
     * @param approovResults the token fetch result
     * @throws ApproovException if the SDK fails to provide the configuration or the
     *                          pins
     */
    static void updatePinsIfConfigChanged(Approov.TokenFetchResult approovResults) throws ApproovException {
        boolean configChanged = approovResults.isConfigChanged();
        if (configChanged) {
            try {
                ApproovService.sdk().fetchConfig();
            } catch (RuntimeException e) {
                throw sdkFailure("Approov dynamic configuration", e);
            }
            Log.d(TAG, "Dynamic configuration updated");
        }
        if (configChanged || approovResults.isForceApplyPins()) {
            rebuildPins();
            Log.d(TAG, "Pins rebuilt");
        }
    }

    /**
     * Sets a binding header that must be present on all requests using the Approov
     * service. A
     * header should be chosen whose value is unchanging for most requests (such as
     * an
     * Authorization header). A hash of the header value is included in the issued
     * Approov tokens
     * to bind them to the value. This may then be verified by the backend API
     * integration. This
     * method should typically only be called once.
     *
     * @param header is the header to use for Approov token binding
     * @throws IllegalArgumentException if the header is already configured for
     *                                  secure string substitution
     */
    public static synchronized void setBindingHeader(String header) {
        Log.d(TAG, "setBindingHeader " + header);
        if ((header != null) && (substitutionHeaders != null) &&
                (findSubstitutionHeaderKey(header) != null)) {
            throw new IllegalArgumentException("Header " + header +
                    " cannot be used for both token binding and secure string substitution");
        }
        bindingHeader = header;
    }

    /**
     * Gets any current binding header.
     *
     * @return binding header or null if not set
     */
    static synchronized String getBindingHeader() {
        return bindingHeader;
    }

    /**
     * Sets the period after which a request that was held between having its
     * Approov protection applied and being actually transmitted has that
     * protection (Approov token and any message signature) refreshed at the
     * network layer immediately before transmission. Requests may be held in
     * this way if the device enters a deep sleep or doze state while the
     * request is in flight, or if the app employs its own request queueing or
     * backoff mechanism. Note that such a refresh reissues the Approov token
     * fetch (usually satisfied instantly from the SDK's cache) and reapplies
     * any message signing and the service mutator's processed request
     * callback, so it is only performed if the mutator's
     * supportsProtectionRefresh() indicates that this is safe (true for the
     * standard mutators, false for custom mutators unless they opt in). The period should be comfortably less than the
     * message signature expiry (15 seconds by default) but high enough that
     * ordinary requests are not reprocessed. The default is 3000ms.
     *
     * @param periodMS refresh threshold in milliseconds, or <=0 to disable
     *                 stale protection refresh
     */
    public static synchronized void setStaleProtectionRefreshPeriod(long periodMS) {
        Log.d(TAG, "setStaleProtectionRefreshPeriod " + periodMS);
        staleProtectionRefreshMS = periodMS;
    }

    /**
     * Gets the period after which a held request has its Approov protection
     * refreshed at the network layer before transmission.
     *
     * @return refresh threshold in milliseconds, or <=0 if disabled
     */
    static synchronized long getStaleProtectionRefreshPeriod() {
        return staleProtectionRefreshMS;
    }

    /**
     * Sets the ApproovServiceMutator instance to handle callbacks from the
     * ApproovService implementation. This facility enables customization of
     * ApproovService operations at key points in the configuration and
     * attestation flows. It should reduce the number of times this service
     * layer implementation needs to be forked in order to introduce custom
     * behavior.
     *
     * Mutators carry decisions only: installing one never switches message
     * signing on or off (see enableMessageSigning).
     *
     * @param mutator is the ApproovServiceMutator with callback handlers that may
     *                override the default behavior of the ApproovService singleton.
     *                Passing null to this method reinstates the out-of-the-box
     *                ApproovServiceMutator.DEFAULT.
     */
    public static synchronized void setServiceMutator(ApproovServiceMutator mutator) {
        if (mutator == null) {
            mutator = ApproovServiceMutator.DEFAULT;
        }
        Log.d(TAG, "Applied ApproovServiceMutator:" + describe(mutator));
        serviceMutator = mutator;
    }

    /**
     * Gets the active service mutator instance that is handling callbacks from
     * ApproovService.
     *
     * @return the service mutator instance (never null)
     */
    public static synchronized ApproovServiceMutator getServiceMutator() {
        return serviceMutator;
    }

    /**
     * Adds the name of a header which should be subject to secure strings
     * substitution. This
     * means that if the header is present then the value will be used as a key to
     * look up a
     * secure string value which will be substituted into the header value instead.
     * This allows
     * easy migration to the use of secure strings. Note that this function should
     * be called on initialization
     * rather than for every request as it will require a new OkHttpClient to be
     * built. A required
     * prefix may be specified to deal with cases such as the use of "Bearer "
     * prefixed before values
     * in an authorization header.
     *
     * @param header         is the header to be marked for substitution
     * @param requiredPrefix is any required prefix to the value being substituted
     *                       or null if not required
     * @throws IllegalArgumentException if the header is already configured for
     *                                  token binding
     */
    public static synchronized void addSubstitutionHeader(String header, String requiredPrefix) {
        Log.d(TAG, "addSubstitutionHeader " + header + ", " + requiredPrefix);
        if ((header != null) && (bindingHeader != null) && header.equalsIgnoreCase(bindingHeader)) {
            throw new IllegalArgumentException("Header " + header +
                    " cannot be used for both token binding and secure string substitution");
        }

        // HTTP header names are case-insensitive. Remove any logically equivalent
        // entry first so that the map contains only one entry and preserves the
        // casing from the latest call.
        String existingKey = findSubstitutionHeaderKey(header);
        if (existingKey != null)
            substitutionHeaders.remove(existingKey);
        if (requiredPrefix == null)
            substitutionHeaders.put(header, "");
        else
            substitutionHeaders.put(header, requiredPrefix);
    }

    /**
     * Removes a header previously added using addSubstitutionHeader.
     *
     * @param header is the header to be removed for substitution
     */
    public static synchronized void removeSubstitutionHeader(String header) {
        Log.d(TAG, "removeSubstitutionHeader " + header);
        String existingKey = findSubstitutionHeaderKey(header);
        if (existingKey != null)
            substitutionHeaders.remove(existingKey);
    }

    /**
     * Finds the stored substitution-header key matching the supplied HTTP header
     * name without regard to case.
     *
     * @param header the HTTP header name to find
     * @return the stored key, or null if no matching entry exists
     */
    private static String findSubstitutionHeaderKey(String header) {
        if ((header == null) || (substitutionHeaders == null))
            return null;
        for (String key : substitutionHeaders.keySet()) {
            if (header.equalsIgnoreCase(key))
                return key;
        }
        return null;
    }

    /**
     * Gets the map of headers that are subject to substitution.
     *
     * @return a map of headers that are subject to substitution, mapped to the
     *         required prefix
     */
    public static synchronized Map<String, String> getSubstitutionHeaders() {
        return new HashMap<>(substitutionHeaders);
    }

    /**
     * Adds a key name for a query parameter that should be subject to secure
     * strings substitution.
     * This means that if the query parameter is present in a URL then the value
     * will be used as a
     * key to look up a secure string value which will be substituted as the query
     * parameter value
     * instead. This allows easy migration to the use of secure strings. Note that
     * this function
     * should be called on initialization rather than for every request as it will
     * require a new
     * OkHttpClient to be built.
     *
     * The key is matched as a literal string, not as a regular expression, and every
     * occurrence of it in a URL's query is substituted, each value looked up as its
     * own secure string key (SPECIFICATION 1.4).
     *
     * @param key is the query parameter key name to be added for substitution
     */
    public static synchronized void addSubstitutionQueryParam(String key) {
        Log.d(TAG, "addSubstitutionQueryParam " + key);
        try {
            // quoted, so that a key such as "a.b" never matches another parameter
            Pattern pattern = Pattern.compile("[\\?&]" + Pattern.quote(key) + "=([^&;]+)");
            substitutionQueryParams.put(key, pattern);
        } catch (PatternSyntaxException e) {
            Log.e(TAG, "addSubstitutionQueryParam " + key + " error: " + e.getMessage());
        }
    }

    /**
     * Removes a query parameter key name previously added using
     * addSubstitutionQueryParam.
     *
     * @param key is the query parameter key name to be removed for substitution
     */
    public static synchronized void removeSubstitutionQueryParam(String key) {
        Log.d(TAG, "removeSubstitutionQueryParam " + key);
        substitutionQueryParams.remove(key);
    }

    /**
     * Gets the map of substitution query parameters.
     *
     * @return a map of query parameters to be substituted, mapped to the compiled
     *         Pattern
     */
    public static synchronized Map<String, Pattern> getSubstitutionQueryParams() {
        return new HashMap<>(substitutionQueryParams);
    }

    /**
     * Adds an exclusion URL regular expression. If a URL for a request matches this
     * regular expression
     * then it will not be subject to any Approov protection. Note that this
     * facility must be used with
     * EXTREME CAUTION due to the impact of dynamic pinning. Pinning may be applied
     * to all domains added
     * using Approov, and updates to the pins are received when an Approov fetch is
     * performed. If you
     * exclude some URLs on domains that are protected with Approov, then these will
     * be protected with
     * Approov pins but without a path to update the pins until a URL is used that
     * is not excluded. Thus
     * you are responsible for ensuring that there is always a possibility of
     * calling a non-excluded
     * URL, or you should make an explicit call to fetchToken if there are
     * persistent pinning failures.
     * Conversely, use of those option may allow a connection to be established
     * before any dynamic pins
     * have been received via Approov, thus potentially opening the channel to a
     * MitM.
     *
     * @param urlRegex is the regular expression that will be compared against URLs
     *                 to exclude them
     */
    public static synchronized void addExclusionURLRegex(String urlRegex) {
        try {
            Pattern pattern = Pattern.compile(urlRegex);
            exclusionURLRegexs.put(urlRegex, pattern);
            Log.d(TAG, "addExclusionURLRegex " + urlRegex);
        } catch (PatternSyntaxException e) {
            Log.e(TAG, "addExclusionURLRegex " + urlRegex + " error: " + e.getMessage());
        }
    }

    /**
     * Removes an exclusion URL regular expression previously added using
     * addExclusionURLRegex.
     *
     * @param urlRegex is the regular expression that will be compared against URLs
     *                 to exclude them
     */
    public static synchronized void removeExclusionURLRegex(String urlRegex) {
        Log.d(TAG, "removeExclusionURLRegex " + urlRegex);
        exclusionURLRegexs.remove(urlRegex);
    }

    /**
     * Gets a copy of the current exclusion URL regexs.
     *
     * @return Map<String, Pattern> of the exclusion regexs to their respective
     *         Patterns
     */
    public static synchronized Map<String, Pattern> getExclusionURLRegexs() {
        return new HashMap<>(exclusionURLRegexs);
    }

    /**
     * Performs a precheck to determine if the app will pass attestation. This
     * requires secure
     * strings to be enabled for the account, although no strings need to be set up.
     * This will
     * likely require network access so may take some time to complete. It may throw
     * ApproovException
     * if the precheck fails or if there is some other problem. A
     * ApproovFetchStatusException is thrown
     * when the SDK reports a token fetch status. ApproovRejectionException
     * continues to expose the
     * rejection ARC and reasons, while the deprecated ApproovNetworkException
     * remains available for
     * backwards compatibility with retryable network failures.
     *
     * @throws ApproovException if there was a problem, or if Approov protection is
     *                          not enabled (before initialization or in bypass mode)
     */
    public static void precheck() throws ApproovException {
        requireProtection("precheck");
        // try and fetch a non-existent secure string in order to check for a rejection
        Approov.TokenFetchResult approovResults;
        try {
            approovResults = ApproovService.sdk().fetchSecureStringAndWait("precheck-dummy-key", null);
            recordLastARC(approovResults);
            Log.d(TAG, "precheck: " + approovResults.getStatus().toString());
        } catch (RuntimeException e) {
            // the fetch failed without a result, so it leaves no ARC behind; any
            // RuntimeException from the SDK, including a null result, is reported as
            // an ApproovException (SPECIFICATION 1.8)
            recordLastARC(null);
            throw new ApproovException(e);
        }

        // process the returned Approov status using decision maker
        try {
            getServiceMutator().handlePrecheckResult(approovResults);
        } catch (RuntimeException e) {
            throw mutatorFailure("handlePrecheckResult", e);
        }
    }

    /**
     * Gets the device ID used by Approov to identify the particular device that the
     * SDK is running on. Note
     * that different Approov apps on the same device will return a different ID.
     * Moreover, the ID may be
     * changed by an uninstall and reinstall of the app.
     *
     * @return String of the device ID
     * @throws ApproovException if there was a problem, or if Approov protection is
     *                          not enabled (before initialization or in bypass mode)
     */
    public static String getDeviceID() throws ApproovException {
        requireProtection("getDeviceID");
        try {
            String deviceID = ApproovService.sdk().getDeviceID();
            Log.d(TAG, "getDeviceID: " + deviceID);
            return deviceID;
        } catch (RuntimeException e) {
            throw new ApproovException(e);
        }
    }

    /**
     * Directly sets the data hash to be included in subsequently fetched Approov
     * tokens. If the hash is
     * different from any previously set value then this will cause the next token
     * fetch operation to
     * fetch a new token with the correct payload data hash. The hash appears in the
     * 'pay' claim of the Approov token as a base64 encoded string of the SHA256
     * hash of the
     * data. Note that the data is hashed locally and never sent to the Approov
     * cloud service.
     *
     * @param data is the data to be hashed and set in the token
     * @throws ApproovException if there was a problem, or if Approov protection is
     *                          not enabled (before initialization or in bypass mode)
     */
    public static void setDataHashInToken(String data) throws ApproovException {
        requireProtection("setDataHashInToken");
        try {
            ApproovService.sdk().setDataHashInToken(data);
            Log.d(TAG, "setDataHashInToken");
        } catch (RuntimeException e) {
            throw new ApproovException(e);
        }
    }

    /**
     * Performs an Approov token fetch for the given URL. This should be used in
     * situations where it
     * is not possible to use the networking interception to add the token. This
     * operation will
     * sometimes require network access so may take some time to complete. If the
     * attestation fails
     * for any reason then an ApproovException is thrown. A
     * ApproovFetchStatusException encapsulates the
     * status reported by the SDK. For backwards compatibility callers may still
     * observe the deprecated
     * ApproovNetworkException subclass for retryable networking issues. Note that
     * the returned token
     * should NEVER be cached by your app, you should call this function when it is
     * needed.
     *
     * @param url is the full URL (including path) for the token fetch
     * @return String of the fetched token
     * @throws ApproovException if there was a problem, or if Approov protection is
     *                          not enabled (before initialization or in bypass mode)
     */
    public static String fetchToken(String url) throws ApproovException {
        requireProtection("fetchToken");
        // fetch the Approov token
        Approov.TokenFetchResult approovResults;
        try {
            approovResults = ApproovService.sdk().fetchApproovTokenAndWait(url);
            recordLastARC(approovResults);
            Log.d(TAG, "fetchToken: " + approovResults.getStatus().toString());
        } catch (RuntimeException e) {
            // the fetch failed without a result, so it leaves no ARC behind; any
            // RuntimeException from the SDK, including a null result, is reported as
            // an ApproovException (SPECIFICATION 1.8)
            recordLastARC(null);
            throw new ApproovException(e);
        }

        // process the status using decision maker
        try {
            getServiceMutator().handleFetchTokenResult(approovResults);
        } catch (RuntimeException e) {
            throw mutatorFailure("handleFetchTokenResult", e);
        }
        return approovResults.getToken();
    }

    /**
     * Gets the signature for the given message. This uses an account specific
     * message signing key that is
     * transmitted to the SDK after a successful fetch if the facility is enabled
     * for the account. Note
     * that if the attestation failed then the signing key provided is actually
     * random so that the
     * signature will be incorrect. An Approov token should always be included in
     * the message
     * being signed and sent alongside this signature to prevent replay attacks. If
     * no signature is
     * available, because there has been no prior fetch or the feature is not
     * enabled, then an
     * ApproovException is thrown.
     * <p>
     * Deprecated: use getAccountMessageSignature instead
     *
     * @param message is the message whose content is to be signed
     * @return String of the base64 encoded message signature
     * @throws ApproovException if there was a problem, or if Approov protection is
     *                          not enabled (before initialization or in bypass mode)
     */
    @Deprecated
    public static String getMessageSignature(String message) throws ApproovException {
        requireProtection("getMessageSignature");
        return getAccountMessageSignature(message);
    }

    /**
     * Gets the signature for the given message. This uses an account specific
     * message signing key that is
     * transmitted to the SDK after a successful fetch if the facility is enabled
     * for the account. Note
     * that if the attestation failed then the signing key provided is actually
     * random so that the
     * signature will be incorrect. An Approov token should always be included in
     * the message
     * being signed and sent alongside this signature to prevent replay attacks. If
     * no signature is
     * available, because there has been no prior fetch or the feature is not
     * enabled, then an
     * ApproovException is thrown.
     *
     * @param message is the message whose content is to be signed
     * @return String of the base64 encoded message signature
     * @throws ApproovException if there was a problem, or if Approov protection is
     *                          not enabled (before initialization or in bypass mode)
     */
    public static String getAccountMessageSignature(String message) throws ApproovException {
        requireProtection("getAccountMessageSignature");
        try {
            String signature = ApproovService.sdk().getAccountMessageSignature(message);
            Log.d(TAG, "getAccountMessageSignature");
            if (signature == null)
                throw new ApproovException("no account signature available");
            return signature;
        } catch (RuntimeException e) {
            throw new ApproovException(e);
        }
    }

    /**
     * Gets the install signature for the given message. This uses an app install
     * specific message
     * signing key that is generated the first time an app launches. This signing
     * mechanism uses an
     * ECC key pair where the private key is managed by the secure element or
     * trusted execution
     * environment of the device. Where it can, Approov uses attested key pairs to
     * perform the
     * message signing.
     * <p>
     * An Approov token should always be included in the message being signed and
     * sent alongside
     * this signature to prevent replay attacks.
     * <p>
     * If no signature is available, because there has been no prior fetch or the
     * feature is not
     * enabled, then an ApproovException is thrown.
     *
     * @param message is the message whose content is to be signed
     * @return String of the base64 encoded message signature in ASN.1 DER format
     * @throws ApproovException if there was a problem, or if Approov protection is
     *                          not enabled (before initialization or in bypass mode)
     */
    public static String getInstallMessageSignature(String message) throws ApproovException {
        requireProtection("getInstallMessageSignature");
        try {
            String signature = ApproovService.sdk().getInstallMessageSignature(message);
            Log.d(TAG, "getInstallMessageSignature");
            if (signature == null)
                throw new ApproovException("no device signature available");
            return signature;
        } catch (RuntimeException e) {
            throw new ApproovException(e);
        }
    }

    /**
     * Fetches a secure string with the given key. If newDef is not null then a
     * secure string for the particular app instance may be defined. In this case
     * the
     * new value is returned as the secure string. Use of an empty string for newDef
     * removes
     * the string entry. Note that this call may require network transaction and
     * thus may block
     * for some time, so should not be called from the UI thread. If the attestation
     * fails
     * for any reason then an ApproovException is thrown. A
     * ApproovFetchStatusException encapsulates the
     * status reported by the SDK. ApproovRejectionException exposes ARC/rejection
     * metadata, while the
     * deprecated ApproovNetworkException indicates retryable networking issues.
     * Note that the returned
     * string should NEVER be cached by your app, you should call this function when
     * it is needed.
     *
     * @param key    is the secure string key to be found
     * @param newDef is any new definition for the secure string, or null to perform
     *               a lookup
     *               for an existing value. If a new value is defined then it will
     *               overwrite
     *               any existing value for the key.
     * @return secure string (should not be cached by your app) or null if it was
     *         not defined
     * @throws ApproovException if there was a problem, or if Approov protection is
     *                          not enabled (before initialization or in bypass mode)
     */
    public static String fetchSecureString(String key, String newDef) throws ApproovException {
        requireProtection("fetchSecureString");
        // determine the type of operation as the values themselves cannot be logged
        String type = "lookup";
        if (newDef != null)
            type = "definition";

        // fetch any secure string keyed by the value, catching any exceptions the SDK
        // might throw
        Approov.TokenFetchResult approovResults;
        try {
            approovResults = ApproovService.sdk().fetchSecureStringAndWait(key, newDef);
            recordLastARC(approovResults);
            Log.d(TAG, "fetchSecureString " + type + ": " + key + ", " + approovResults.getStatus().toString());
        } catch (RuntimeException e) {
            // the fetch failed without a result, so it leaves no ARC behind; any
            // RuntimeException from the SDK, including a null result, is reported as
            // an ApproovException (SPECIFICATION 1.8)
            recordLastARC(null);
            throw new ApproovException(e);
        }

        // process the returned Approov status using decision maker
        try {
            getServiceMutator().handleFetchSecureStringResult(approovResults, type, key);
        } catch (RuntimeException e) {
            throw mutatorFailure("handleFetchSecureStringResult", e);
        }
        return approovResults.getSecureString();
    }

    /**
     * Fetches a custom JWT with the given payload. Note that this call will require
     * network
     * transaction and thus will block for some time, so should not be called from
     * the UI thread.
     * If the attestation fails for any reason then an ApproovException is thrown. A
     * ApproovFetchStatusException
     * encapsulates the SDK status, while ApproovRejectionException (ARC/reasons)
     * and the deprecated
     * ApproovNetworkException (retryable networking issues) continue to be emitted
     * for backwards compatibility.
     *
     * @param payload is the marshaled JSON object for the claims to be included
     * @return custom JWT string
     * @throws ApproovException if there was a problem, or if Approov protection is
     *                          not enabled (before initialization or in bypass mode)
     */
    public static String fetchCustomJWT(String payload) throws ApproovException {
        requireProtection("fetchCustomJWT");
        // fetch the custom JWT catching any exceptions the SDK might throw
        Approov.TokenFetchResult approovResults;
        try {
            approovResults = ApproovService.sdk().fetchCustomJWTAndWait(payload);
            recordLastARC(approovResults);
            Log.d(TAG, "fetchCustomJWT: " + approovResults.getStatus().toString());
        } catch (RuntimeException e) {
            // the fetch failed without a result, so it leaves no ARC behind; any
            // RuntimeException from the SDK, including a null result, is reported as
            // an ApproovException (SPECIFICATION 1.8)
            recordLastARC(null);
            throw new ApproovException(e);
        }

        // process the returned Approov status using decision maker
        try {
            getServiceMutator().handleFetchCustomJWTResult(approovResults);
        } catch (RuntimeException e) {
            throw mutatorFailure("handleFetchCustomJWTResult", e);
        }
        return approovResults.getToken();
    }

    /**
     * Gets the Attestation Response Code (ARC) from the most recent fetch this layer
     * performed: a token, secure string or custom JWT fetch, from the interceptor
     * (including secure string substitutions and stale protection refreshes) or
     * from a direct method (fetchToken, fetchSecureString, fetchCustomJWT,
     * precheck). It performs no fetch of its own and no network activity: it
     * reports the result the app already received, so it can be read after a
     * rejected request to correlate with the backend's view.
     *
     * The empty string is returned when no fetch has been made since
     * initialization, when the last fetch carried no ARC, when ARC is not enabled
     * for the account, or when the last fetch failed without a result (an SDK
     * exception in a direct method). Prefer receiving the ARC on the server side
     * where possible.
     *
     * @return the ARC of the most recent fetch, or an empty string
     */
    public static synchronized String getLastARC() {
        return lastARC;
    }

    /**
     * Records the ARC carried by a fetch result as the most recent one, for
     * getLastARC. An empty or null ARC clears the previous value, matching the SDK
     * semantics of the ARC belonging to the last fetch.
     *
     * @param approovResults the fetch result just obtained, or null if the fetch
     *                       failed without a result
     */
    static synchronized void recordLastARC(Approov.TokenFetchResult approovResults) {
        String arc = (approovResults != null) ? approovResults.getARC() : null;
        lastARC = (arc != null) ? arc : "";
    }

    /**
     * Sets an install attributes token to be sent to the server and associated with
     * this particular app installation for future Approov token fetches. The token
     * must be signed, within its expiry time and bound to the correct device ID for
     * it to be accepted by the server. Calling this method ensures that the next
     * call to fetch an Approov token will not use a cached version, so that this
     * information can be transmitted to the server. It replaces
     * setInstallAttrsInToken, removed in 3.8.0, and passes the token to the SDK's
     * Approov.setInstallAttrsInToken.
     *
     * @param attrs is the signed JWT holding the new install attributes
     * @throws ApproovException if the attrs parameter is invalid, or if Approov
     *                          protection is not enabled (before initialization or
     *                          in bypass mode)
     */
    public static void setInstallAttributes(String attrs) throws ApproovException {
        requireProtection("setInstallAttributes");
        try {
            ApproovService.sdk().setInstallAttrsInToken(attrs);
            Log.d(TAG, "setInstallAttributes");
        } catch (RuntimeException e) {
            Log.e(TAG, "setInstallAttributes failed: " + e.getMessage());
            throw new ApproovException(e);
        }
    }

    /**
     * Rebuilds the pins in the pinning interceptor after a dynamic configuration
     * change or when Approov protection is enabled. The ApproovService monitor is
     * not held while the pins are built, so the lock order is always
     * ApproovService then interceptor, never the reverse.
     *
     * @throws ApproovException if the SDK fails to provide the pins; the pinning
     *                          interceptor then fails every connection check until
     *                          they can be read
     */
    static void rebuildPins() throws ApproovException {
        ApproovPinningInterceptor interceptor;
        synchronized (ApproovService.class) {
            if (pinningInterceptor == null) {
                // a new interceptor builds the current pins as it is constructed
                getPinningInterceptor();
                return;
            }
            interceptor = pinningInterceptor;
        }
        interceptor.buildPins();
    }

    /**
     * Gets the pinning interceptor shared by every client, creating it on first
     * use. It applies no pins while Approov protection is not enabled.
     *
     * @return the shared pinning interceptor
     */
    private static synchronized ApproovPinningInterceptor getPinningInterceptor() {
        if (pinningInterceptor == null)
            pinningInterceptor = new ApproovPinningInterceptor();
        return pinningInterceptor;
    }

    /**
     * Gets the current CertificatePinner.
     *
     * @return the current CertificatePinner
     */
    @VisibleForTesting
    static synchronized CertificatePinner getCertificatePinner() {
        return getPinningInterceptor().getCertificatePinner();
    }

    /**
     * Sets the OkHttpClient.Builder to be used for constructing the Approov
     * OkHttpClient for a
     * named builder. This allows custom configurations to be set, with additional
     * interceptors and
     * properties. This clears the appropriate cached OkHttp client so should only
     * be called when an
     * actual builder change is required.
     *
     * @param builderName is the name of the builder to set
     * @param builder     is the OkHttpClient.Builder to be used as a basis for the
     *                    Approov OkHttpClient
     */
    public static synchronized void setOkHttpClientBuilder(String builderName, OkHttpClient.Builder builder) {
        Log.d(TAG, "OkHttp client builder set for " + builderName);
        OkHttpClient.Builder oldBuilder = okHttpBuilders.put(builderName, builder);
        if (oldBuilder != builder)
            // force a rebuild of the client if the builder has changed
            okHttpClients.remove(builderName);
    }

    /**
     * Sets the OkHttpClient.Builder to be used for constructing the default Approov
     * OkHttpClient.
     * This allows a default custom configuration to be set, with additional
     * interceptors and properties.
     *
     * @param builder is the OkHttpClient.Builder to be used as a basis for the
     *                Approov OkHttpClient
     */
    public static synchronized void setOkHttpClientBuilder(OkHttpClient.Builder builder) {
        setOkHttpClientBuilder(DEFAULT_BUILDER_NAME, builder);
    }

    /**
     * Gets the OkHttpClient that enables the Approov service for the named builder.
     * This adds
     * the Approov token in a header to requests, and also pins the connections. The
     * OkHttpClient
     * is constructed lazily on demand but is cached if there are no changes. Use
     * "setOkHttpClientBuilder"
     * to provide any special properties.
     *
     * @param builderName is the name for the builder
     * @return OkHttpClient to be used with Approov
     */
    public static synchronized OkHttpClient getOkHttpClient(String builderName) {
        OkHttpClient okHttpClient = okHttpClients.get(builderName);
        if (okHttpClient == null) {
            // get the builder and warn if none was available
            OkHttpClient.Builder okHttpBuilder = okHttpBuilders.get(builderName);
            if (okHttpBuilder == null) {
                Log.d(TAG, "No builder available for " + builderName);
                okHttpBuilder = new OkHttpClient.Builder();
            }

            // remove any existing ApproovTokenInterceptor from the builder
            List<Interceptor> interceptors = okHttpBuilder.interceptors();
            Iterator<Interceptor> iter = interceptors.iterator();
            while (iter.hasNext()) {
                Interceptor interceptor = iter.next();
                if (interceptor instanceof ApproovTokenInterceptor)
                    iter.remove();
            }

            // remove any existing ApproovFreshnessInterceptor or
            // ApproovPinningInterceptor from the builder
            interceptors = okHttpBuilder.networkInterceptors();
            iter = interceptors.iterator();
            while (iter.hasNext()) {
                Interceptor interceptor = iter.next();
                if ((interceptor instanceof ApproovFreshnessInterceptor) ||
                        (interceptor instanceof ApproovPinningInterceptor))
                    iter.remove();
            }

            // The Approov interceptors are always attached, even before initialize()
            // and in bypass mode. Each decides per request: while Approov protection is
            // not enabled a request goes out with no Approov processing and no Approov
            // pinning, only OS trust (SPECIFICATION 5.7(f)), and once initialize()
            // enables protection the same client protects its requests, so a client
            // obtained early is never cached unprotected.
            if (!isApproovServiceEnabled())
                Log.w(TAG, "Building Approov OkHttpClient for " + builderName + " before ApproovService "
                        + "initialization; requests proceed without Approov protection until it is initialized");
            else
                Log.d(TAG, "Building new Approov OkHttpClient for " + builderName);
            // The token interceptor runs after the app's own interceptors, so they
            // never see the protection. The network interceptors run before the
            // app's own network interceptors (a logger, an inspector, an APM agent),
            // so that an attempt the network stack rebuilt (a redirect, a retry) has
            // its protection stripped or refreshed, and its connection checked,
            // before any app code sees it: a redirect to a domain Approov does not
            // protect never shows the app's network interceptors the token, the
            // status or the substituted secrets of the original destination.
            okHttpBuilder.addInterceptor(new ApproovTokenInterceptor());
            List<Interceptor> networkInterceptors = okHttpBuilder.networkInterceptors();
            networkInterceptors.add(0, getPinningInterceptor());
            networkInterceptors.add(0, new ApproovFreshnessInterceptor());
            okHttpClient = okHttpBuilder.build();

            // cache the client for future usages
            okHttpClients.put(builderName, okHttpClient);
        }
        return okHttpClient;
    }

    /**
     * Gets the default OkHttpClient that enables the Approov service. This adds the
     * Approov token
     * in a header to requests, and also pins the connections. The OkHttpClient is
     * constructed
     * lazily on demand but is cached if there are no changes. Use
     * "setOkHttpClientBuilder" to
     * provide any special properties.
     *
     * @return OkHttpClient to be used with Approov
     */
    public static synchronized OkHttpClient getOkHttpClient() {
        return getOkHttpClient(DEFAULT_BUILDER_NAME);
    }
}

// interceptor to add Approov tokens or substitute headers and query parameters
class ApproovTokenInterceptor implements Interceptor {
    // logging tag
    private final static String TAG = "ApproovTokenInterceptor";

    /**
     * Constructs a new interceptor that adds Approov tokens and substitutes headers
     * or query parameters. Whether a request proceeds when a token or a secure
     * string cannot be obtained is decided by the service mutator (see
     * ApproovServiceMutator.CLOSE_FAILURE, the 3.8.0 default, and ALWAYS_PROCEED).
     * A request that proceeds without a token is sent with an empty token header,
     * and the Approov fetch status is reported on the status header for every
     * processed request.
     */
    public ApproovTokenInterceptor() {
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        // before initialize() and in bypass mode a request goes out with no Approov
        // processing at all: it is neither held nor failed, and the SDK is not called
        // (SPECIFICATION 5.7(f))
        if (!ApproovService.isApproovProtectionEnabled())
            return chain.proceed(chain.request());

        // cache the mutator for the duration of the interceptor to make sure
        // it is not changed mid-flight
        ApproovServiceMutator mutator = ApproovService.getServiceMutator();
        ApproovDefaultMessageSigning signing = ApproovService.getActiveMessageSigning();
        return chain.proceed(applyProtection(chain.request(), mutator, signing, true));
    }

    /**
     * Applies Approov protection to a request: decides whether the request is
     * processed at all, fetches a token for its URL, adds the token, trace and
     * status headers, performs secure string substitutions, invokes the
     * mutator's processed request callback and finally, if message signing is
     * enabled, signs the request. The returned request carries an
     * ApproovRequestFreshness marker describing exactly the protection applied
     * to it, so that the network layer can strip and reapply that protection on
     * a stale attempt or on a redirect followup. A request that is not processed
     * (excluded, or to a domain Approov does not protect) is returned unchanged
     * with no marker.
     *
     * @param request         the request to protect, carrying no Approov protection
     * @param mutator         the service mutator to consult
     * @param signing         the message signing to apply, or null if message
     *                        signing is disabled
     * @param invokeProcessed whether the mutator's processed request callback may
     *                        be invoked (false when reapplying protection under a
     *                        mutator that does not support refresh)
     * @return the protected request
     * @throws IOException if the mutator opts in to aborting the request
     */
    static Request applyProtection(Request request, ApproovServiceMutator mutator,
            ApproovDefaultMessageSigning signing, boolean invokeProcessed) throws IOException {
        // first check if we are to proceed with any Approov processing
        Request unprotected = request;
        if (!ApproovService.callMutator("handleInterceptorShouldProcessRequest",
                () -> mutator.handleInterceptorShouldProcessRequest(unprotected))) {
            // we are not to proceed with any Approov processing so just continue
            return request;
        }

        // update the data hash based on any token binding header (presence is optional)
        ApproovService.updateBindingDataHash(request);

        HttpUrl url = request.url();

        // request an Approov token for the request URL
        Approov.TokenFetchResult approovResults = fetchForRequest("Approov token fetch for " + url, () ->
                ApproovService.sdk().fetchApproovTokenAndWait(url.toString()));

        // provide information about the obtained token or error (note "approov token
        // -check" can be used to check the validity of the token and if you use token
        // annotations they will appear here to determine why a request is being rejected)
        Log.d(TAG, "Token for " + url.toString() + ": " + approovResults.getLoggableToken());

        // force a pinning rebuild if there is any dynamic config update
        ApproovService.updatePinsIfConfigChanged(approovResults);

        // check the status of Approov token fetch using decision maker
        boolean aChange = false;
        String setTokenHeaderKey = null;
        String setTokenHeaderPrefix = null;
        String setTokenHeaderValue = null;
        String setTraceIDHeaderKey = null;
        String setTraceIDHeaderValue = null;
        String setStatusHeaderKey = null;
        String setStatusHeaderValue = null;
        Approov.TokenFetchResult tokenResults = approovResults;
        if (ApproovService.callMutator("handleInterceptorFetchTokenResult",
                () -> mutator.handleInterceptorFetchTokenResult(tokenResults, url.toString()))) {
            // the request is Approov processed so add the token header (empty if no
            // token was obtained, as evidence that Approov processing occurred) and
            // report the fetch status on the status header; the token header name and
            // prefix are read as one snapshot
            aChange = true;
            String[] tokenHeader = ApproovService.snapshotTokenHeader();
            setTokenHeaderKey = tokenHeader[0];
            setTokenHeaderPrefix = tokenHeader[1];
            setTokenHeaderValue = ApproovService.buildTokenHeaderValue(setTokenHeaderPrefix, approovResults);
            setStatusHeaderKey = ApproovService.getStatusHeader();
            setStatusHeaderValue = ApproovService.buildStatusHeaderValue(approovResults);

            String traceIDHeader = ApproovService.getTraceIDHeader();
            String traceID = approovResults.getTraceID();
            // Emit the trace header whenever the SDK provides a value, even if it is empty, so the
            // backend has evidence that Approov processing occurred (see Missing Artifacts Fallback).
            // A null trace ID means none is available, so the header is omitted in that case.
            if ((traceIDHeader != null) && (traceID != null)) {
                setTraceIDHeaderKey = traceIDHeader;
                setTraceIDHeaderValue = traceID;
            }
        } else {
            // the request is not for a domain protected by Approov (or the mutator has
            // decided it must be sent untouched) so no Approov headers are added: this
            // also protects against header substitutions in domains not protected by
            // Approov and therefore potentially subject to a MitM
            return request;
        }

        // we now deal with any header substitutions, which may require further fetches
        // but these should be using cached results; the original values are kept so
        // that the substitution can be undone if the protection is reapplied
        Map<String, String> substitutionHeaders = ApproovService.getSubstitutionHeaders();
        Map<String, String> setSubstitutionHeaders = new LinkedHashMap<>(substitutionHeaders.size());
        Map<String, List<String>> originalHeaderValues = new LinkedHashMap<>(substitutionHeaders.size());
        for (Map.Entry<String, String> entry : substitutionHeaders.entrySet()) {
            String header = entry.getKey();
            String prefix = entry.getValue();
            String value = request.header(header);
            if ((value != null) && value.startsWith(prefix) && (value.length() > prefix.length())) {
                String key = value.substring(prefix.length());
                approovResults = fetchForRequest("Header substitution for " + header, () ->
                        ApproovService.sdk().fetchSecureStringAndWait(key, null));
                Log.d(TAG, "Substituting header: " + header + ", " + approovResults.getStatus().toString());
                // a failed substitution leaves the placeholder value in the header and the
                // request proceeds: the backend sees the placeholder and decides
                Approov.TokenFetchResult headerResults = approovResults;
                if (ApproovService.callMutator("handleInterceptorHeaderSubstitutionResult",
                        () -> mutator.handleInterceptorHeaderSubstitutionResult(headerResults, header))) {
                    String secureString = approovResults.getSecureString();
                    if (secureString == null) {
                        // a decision to substitute with no value (a custom mutator accepting
                        // a non-SUCCESS result) leaves the placeholder, never "null"
                        Log.d(TAG, "No secure string to substitute, placeholder left in header: " + header
                                + ", " + approovResults.getStatus().toString());
                    } else if (!ApproovService.isSafeHeaderValue(secureString)) {
                        // a value a header cannot carry is a substitution that produced no
                        // usable value, under every mutator (SPECIFICATION 1.4); the value
                        // is never logged
                        Log.w(TAG, "Secure string for header " + header + " contains a character a header "
                                + "value cannot carry, placeholder left");
                    } else {
                        aChange = true;
                        setSubstitutionHeaders.put(header, prefix + secureString);
                        // every field of the name, in order, so that all can be restored
                        originalHeaderValues.put(header, new ArrayList<>(request.headers(header)));
                    }
                }
            }
        }

        // we now deal with any query parameter substitutions, which may require further
        // fetches but these should be using cached results
        String originalURL = request.url().toString();
        String replacementURL = originalURL;
        Map<String, Pattern> substitutionQueryParams = ApproovService.getSubstitutionQueryParams();
        List<String> queryKeys = new ArrayList<>(substitutionQueryParams.size());
        for (Map.Entry<String, Pattern> entry : substitutionQueryParams.entrySet()) {
            String queryKey = entry.getKey();
            Pattern pattern = entry.getValue();
            // every occurrence of the parameter in the query is substituted, each value
            // looked up as its own key and read back on its own (SPECIFICATION 1.4);
            // the search is confined to the query, after the first '?' and before any
            // '#', so that the occurrences counted here are the ones read back from it
            int occurrence = 0;
            int from = replacementURL.indexOf('?');
            while (from >= 0) {
                int hash = replacementURL.indexOf('#', from);
                Matcher matcher = pattern.matcher(replacementURL);
                matcher.region(from, (hash < 0) ? replacementURL.length() : hash);
                if (!matcher.find())
                    break;
                int start = matcher.start(1);
                from = matcher.end(1);
                // we have found an occurrence of the query parameter to be replaced so we look
                // up the existing value as a key for a secure string
                String queryValue = matcher.group(1);
                approovResults = fetchForRequest("Query parameter substitution for " + queryKey, () ->
                        ApproovService.sdk().fetchSecureStringAndWait(queryValue, null));
                Log.d(TAG, "Substituting query parameter: " + queryKey + ", " + approovResults.getStatus().toString());
                Approov.TokenFetchResult queryResults = approovResults;
                if (ApproovService.callMutator("handleInterceptorQueryParamSubstitutionResult",
                        () -> mutator.handleInterceptorQueryParamSubstitutionResult(queryResults, queryKey))) {
                    String secureString = approovResults.getSecureString();
                    if (secureString == null) {
                        // a decision to substitute with no value leaves the placeholder
                        Log.d(TAG, "No secure string to substitute, placeholder left in query parameter: "
                                + queryKey + ", " + approovResults.getStatus().toString());
                    } else {
                        // substitute this occurrence, then read it back from the URL as
                        // OkHttp stores it: a value OkHttp does not carry unchanged (it
                        // strips tab, LF, FF and CR from a URL; an & ends the parameter, a
                        // # starts the fragment) would reach the backend changed, so it is
                        // a substitution with no usable value under every mutator and this
                        // occurrence keeps its placeholder (SPECIFICATION 1.4); the value
                        // is never logged
                        String candidateURL = new StringBuilder(replacementURL).replace(start, from,
                                secureString).toString();
                        if (ApproovService.isCarriedInQuery(candidateURL, pattern, occurrence, secureString)) {
                            aChange = true;
                            if (!queryKeys.contains(queryKey))
                                queryKeys.add(queryKey);
                            replacementURL = candidateURL;
                            from = start + secureString.length();
                        } else {
                            Log.w(TAG, "Secure string for query parameter " + queryKey + " cannot be carried "
                                    + "in the URL unchanged, placeholder left");
                        }
                    }
                }
                occurrence++;
            }
        }

        // gather the request changes applied to the request
        ApproovRequestMutations changes = new ApproovRequestMutations();
        // apply all the changes to the request
        ApproovRequestFreshness freshness = null;
        if (aChange) {
            Request.Builder builder = request.newBuilder();
            if (setTokenHeaderKey != null) {
                setHeader(builder, setTokenHeaderKey, setTokenHeaderValue);
                changes.setTokenHeaderKey(setTokenHeaderKey);
                changes.setTokenHeaderPrefix(setTokenHeaderPrefix);

                // tag the request so that the freshness interceptor can determine at the
                // network layer whether the protection was applied too long ago and must
                // be refreshed before transmission, or whether the request was redirected
                freshness = new ApproovRequestFreshness(url.toString(), changes);
                builder.tag(ApproovRequestFreshness.class, freshness);
            }
            if (setTraceIDHeaderKey != null) {
                setHeader(builder, setTraceIDHeaderKey, setTraceIDHeaderValue);
                changes.setTraceIDHeaderKey(setTraceIDHeaderKey);
            }
            // report the fetch status on the status header, replacing any value the app
            // may have set itself so that the backend only sees what this layer observed
            if (setStatusHeaderKey != null) {
                setHeader(builder, setStatusHeaderKey, setStatusHeaderValue);
                changes.setStatusHeaderKey(setStatusHeaderKey);
            }
            if (!setSubstitutionHeaders.isEmpty()) {
                for (Map.Entry<String, String> entry : setSubstitutionHeaders.entrySet()) {
                    // substitute the header
                    setHeader(builder, entry.getKey(), entry.getValue());
                }
                changes.setSubstitutionHeaderKeys(new ArrayList<>(setSubstitutionHeaders.keySet()));
            }
            if (!originalURL.equals(replacementURL)) {
                try {
                    builder.url(replacementURL);
                } catch (IllegalArgumentException e) {
                    // OkHttp's message quotes the URL, which now holds secure strings, so
                    // neither it nor the cause is kept
                    throw new ApproovException("Query parameter substitution for " + queryKeys
                            + ": the secure string does not form a valid URL");
                }
                changes.setSubstitutionQueryParamResults(originalURL, queryKeys);
            }
            request = builder.build();

            // record the substituted values as they are actually stored on the request
            // (OkHttp trims header values when they are set) so that stripping can
            // recognise a header it installed
            if (freshness != null) {
                Map<String, String> installedHeaderValues = new LinkedHashMap<>(setSubstitutionHeaders.size());
                for (String header : setSubstitutionHeaders.keySet())
                    installedHeaderValues.put(header, request.header(header));
                freshness.setSubstitutions(originalHeaderValues, installedHeaderValues);
            }
        }

        // call the processed request callback, unless protection is being reapplied
        // under a mutator whose callback is not safe to invoke again
        Request processedRequest = request;
        if (invokeProcessed) {
            Request mutated = request;
            processedRequest = ApproovService.callMutator("handleInterceptorProcessedRequest",
                    () -> mutator.handleInterceptorProcessedRequest(mutated, changes));
            if (processedRequest == null) {
                // a programming error in the app's callback, treated like a runtime
                // exception from it (SPECIFICATION 1.6.1)
                Log.e(TAG, "ApproovServiceMutator.handleInterceptorProcessedRequest returned null");
                throw new ApproovException("ApproovServiceMutator.handleInterceptorProcessedRequest returned null");
            }
            // a header the callback set or changed that a header cannot carry is the
            // app's configuration error (SPECIFICATION 1.4, 1.7(a)): OkHttp's builder
            // refuses most such values inside the callback (see callMutator), but one
            // set with Headers.Builder.addUnsafeNonAscii would reach the wire
            for (String name : processedRequest.headers().names()) {
                List<String> values = processedRequest.headers(name);
                if (values.equals(request.headers(name)))
                    continue;
                for (String value : values) {
                    if (!ApproovService.isSafeHeaderValue(value))
                        throw ApproovService.unsafeHeaderValue(name);
                }
            }
        } else {
            Log.d(TAG, "Protection reapplied without the processed request callback of "
                    + ApproovService.describe(mutator));
        }

        // message signing runs last, over the final token, status, trace and
        // substituted values and whatever the processed request callback changed,
        // whichever mutator made the decisions above; only a request carrying the
        // token header is signed
        if ((signing != null) && (freshness != null))
            processedRequest = signing.sign(processedRequest, changes);

        // record the time at which the protection was applied, the URL it was applied
        // to, and the names of any headers added by the processed request callback
        // or the message signing, so that the freshness interceptor can
        // strip and reapply the protection at the network layer if the request is
        // held too long before transmission or is redirected
        if (freshness != null) {
            freshness.markProtected(SystemClock.elapsedRealtime(),
                    ApproovRequestFreshness.addedHeaderNames(request, processedRequest));
            freshness.setAppliedURL(processedRequest.url().toString());
            freshness.setAppliedMethod(processedRequest.method());
        }

        return processedRequest;
    }

    /**
     * A fetch made by the SDK on the request path.
     */
    private interface RequestFetch {
        Approov.TokenFetchResult fetch();
    }

    /**
     * Performs a token or secure string fetch for a request and records its ARC. A
     * RuntimeException from the SDK, or a fetch that returns no result, is an
     * ApproovException with the SDK's exception as its cause, and leaves no ARC
     * behind, as in the direct methods.
     *
     * @param operation what is fetched, for the exception message
     * @param fetch     the SDK call
     * @return the fetch result, never null
     * @throws ApproovException if the SDK throws or returns no result
     */
    private static Approov.TokenFetchResult fetchForRequest(String operation, RequestFetch fetch)
            throws ApproovException {
        Approov.TokenFetchResult approovResults;
        try {
            approovResults = fetch.fetch();
        } catch (RuntimeException e) {
            ApproovService.recordLastARC(null);
            throw ApproovService.sdkFailure(operation, e);
        }
        if (approovResults == null) {
            ApproovService.recordLastARC(null);
            Log.e(TAG, operation + ": no result from the Approov SDK");
            throw new ApproovException(operation + ": no result from the Approov SDK");
        }
        ApproovService.recordLastARC(approovResults);
        return approovResults;
    }

    /**
     * Sets a header that this layer adds or substitutes. The value is checked first
     * (SPECIFICATION 1.4, 1.7(a)): a value a header cannot carry, which can only come
     * from what the app supplied (a token prefix), fails the request with an
     * ApproovException naming the header, never quoting the value. OkHttp's own
     * IllegalArgumentException quotes the value, so it is never let through; a
     * header name OkHttp rejects (set by the app with setTokenHeader or
     * setStatusHeader) fails the request the same way.
     *
     * @param builder the request builder
     * @param name    the header name
     * @param value   the header value
     * @throws ApproovException if the name or the value cannot be set
     */
    private static void setHeader(Request.Builder builder, String name, String value) throws ApproovException {
        if (!ApproovService.isSafeHeaderValue(value))
            throw ApproovService.unsafeHeaderValue(name);
        try {
            builder.header(name, value);
        } catch (IllegalArgumentException e) {
            String message = "Approov cannot set header " + name + ": not a valid header name";
            Log.e(TAG, message);
            throw new ApproovException(message);
        }
    }

    /**
     * Removes the Approov protection described by a freshness marker from a
     * request: the token, trace and status headers, the headers added by the
     * mutator's processed request callback, the marker itself, and the secure
     * string substitutions, whose placeholder values (every field of the name, in
     * order) are restored. A substituted header is only restored if it still holds
     * the value this layer installed: a value the app changed afterwards, for
     * example from an OkHttp authenticator, is left in place and is substituted
     * afresh when protection is reapplied. The URL is restored to its
     * pre-substitution form only when the request has not been redirected, since a
     * redirect target is the server's URL, not ours.
     *
     * @param request    the request carrying the protection
     * @param freshness  the marker describing the protection
     * @param restoreURL whether to restore the pre-substitution URL
     * @return the request with no Approov protection
     */
    static Request stripProtection(Request request, ApproovRequestFreshness freshness, boolean restoreURL) {
        ApproovRequestMutations changes = freshness.getChanges();
        Request.Builder builder = request.newBuilder();
        for (String header : freshness.getMutatorAddedHeaders())
            builder.removeHeader(header);
        if (changes.getTokenHeaderKey() != null)
            builder.removeHeader(changes.getTokenHeaderKey());
        if (changes.getTraceIDHeaderKey() != null)
            builder.removeHeader(changes.getTraceIDHeaderKey());
        if (changes.getStatusHeaderKey() != null)
            builder.removeHeader(changes.getStatusHeaderKey());
        for (Map.Entry<String, List<String>> entry : freshness.getOriginalHeaderValues().entrySet()) {
            String header = entry.getKey();
            List<String> current = request.headers(header);
            if ((current.size() != 1) || !freshness.isInstalledValue(header, current.get(0)))
                continue;
            builder.removeHeader(header);
            for (String value : entry.getValue())
                builder.addHeader(header, value);
        }
        if (restoreURL && (changes.getOriginalURL() != null))
            builder.url(changes.getOriginalURL());
        builder.tag(ApproovRequestFreshness.class, null);
        return builder.build();
    }
}

// network interceptor that refreshes the Approov protection (token and any
// message signature) on requests that were held for too long between the
// ApproovTokenInterceptor applying the protection and the request actually
// being transmitted. Requests may be held in this way if the device enters a
// deep sleep or doze state while the request is queued, or if the app employs
// its own request queueing or backoff mechanism; the Approov token and any
// message signature (which carries created/expires timestamps) may then have
// expired by the time the request is sent. Since this is a network interceptor
// it runs on every attempt once its connection is established, including OkHttp
// generated retries and redirect followups which do not pass through the
// application layer ApproovTokenInterceptor again. It is the first network
// interceptor, ahead of any the app adds, so app code at the network layer only
// ever sees an attempt after its protection was stripped or refreshed.
class ApproovFreshnessInterceptor implements Interceptor {
    // logging tag
    private final static String TAG = "ApproovFreshness";

    /**
     * Constructs a new interceptor that refreshes stale Approov protection and
     * reclassifies redirected requests.
     */
    public ApproovFreshnessInterceptor() {
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();

        // only requests given protection by the ApproovTokenInterceptor carry a
        // freshness marker and are candidates for a refresh or a reclassification
        ApproovRequestFreshness freshness = request.tag(ApproovRequestFreshness.class);
        if (freshness == null)
            return chain.proceed(request);

        // a network attempt whose URL, method or headers differ from the request the
        // protection was applied to was rebuilt by OkHttp or by the app: a redirect
        // followup (a 303 also turns the method into GET), or an Authenticator retrying
        // a 401 with a new Authorization header. The destination may be a different
        // host, so the protection of the original destination must never travel with
        // it, and a signature over the old method, URL or headers is invalid, so the
        // new attempt is classified and signed afresh. The header baseline is taken on
        // the first network attempt, since OkHttp adds its transport headers between
        // the application and the network interceptors.
        String appliedURL = freshness.getAppliedURL();
        String appliedMethod = freshness.getAppliedMethod();
        okhttp3.Headers appliedHeaders = freshness.getAppliedHeaders();
        if (appliedHeaders == null)
            freshness.setAppliedHeaders(request.headers());
        boolean urlChanged = (appliedURL != null) && !request.url().toString().equals(appliedURL);
        boolean rebuilt = urlChanged
                || ((appliedMethod != null) && !request.method().equals(appliedMethod))
                || ((appliedHeaders != null) && !request.headers().equals(appliedHeaders));

        ApproovServiceMutator mutator;
        boolean invokeProcessed;
        // message signing is independent of the mutator and is reapplied whenever the
        // protection is reapplied
        ApproovDefaultMessageSigning signing = ApproovService.getActiveMessageSigning();
        if (rebuilt) {
            Log.d(TAG, "Request rebuilt since protection was applied to " + appliedURL +
                    " (now " + request.method() + " " + request.url() + "), reapplying Approov protection");
            // cache the mutator for the duration of the interceptor to make sure it is
            // not changed mid-flight; a mutator that does not support refresh has its
            // headers stripped (they must not leak to the new destination) but its
            // processed request callback is not invoked again
            mutator = ApproovService.getServiceMutator();
            ApproovServiceMutator rebuiltMutator = mutator;
            invokeProcessed = ApproovService.callMutator("supportsProtectionRefresh",
                    rebuiltMutator::supportsProtectionRefresh);
        } else {
            // measure how long the request has been held since the protection was
            // applied, using a clock that advances during device sleep, and proceed
            // unchanged if within the refresh period or if the refresh is disabled
            long refreshPeriodMS = ApproovService.getStaleProtectionRefreshPeriod();
            if ((refreshPeriodMS <= 0) || (freshness.getProtectedAtMillis() < 0))
                return chain.proceed(request);
            long heldMS = SystemClock.elapsedRealtime() - freshness.getProtectedAtMillis();
            if (heldMS <= refreshPeriodMS)
                return chain.proceed(request);

            // a refresh reinvokes the mutator's processed request callback so it is
            // only performed if the mutator declares that this is safe
            mutator = ApproovService.getServiceMutator();
            ApproovServiceMutator staleMutator = mutator;
            if (!ApproovService.callMutator("supportsProtectionRefresh", staleMutator::supportsProtectionRefresh)) {
                Log.d(TAG, "Request held for " + heldMS + "ms but " + ApproovService.describe(mutator) +
                        " does not support protection refresh");
                return chain.proceed(request);
            }
            Log.d(TAG, "Request held for " + heldMS + "ms since Approov protection was applied, " +
                    "refreshing before transmission");
            invokeProcessed = true;
        }

        // strip the protection described by the marker and apply protection afresh for
        // the request's current URL: the token is refetched (usually from the SDK cache
        // when it is still valid), the token, trace and status headers reflect the new
        // result, substitutions are redone and the signatures regenerated. The refreshed
        // request carries its own marker; the original request keeps the marker that
        // describes its own headers, so that a retry of the original request by OkHttp
        // is refreshed again rather than sent with stale headers under a fresh marker.
        // The pre-substitution URL (query placeholders) is restored whenever the URL is
        // the one the protection was applied to, whatever else changed; a redirect
        // target is the server's URL and is left alone.
        Request stripped = ApproovTokenInterceptor.stripProtection(request, freshness, !urlChanged);
        Request refreshed = ApproovTokenInterceptor.applyProtection(stripped, mutator, signing, invokeProcessed);
        // the refreshed request is already at the network layer, so its header baseline
        // is what it carries now
        ApproovRequestFreshness refreshedMarker = refreshed.tag(ApproovRequestFreshness.class);
        if (refreshedMarker != null)
            refreshedMarker.setAppliedHeaders(refreshed.headers());
        return chain.proceed(refreshed);
    }
}

// interceptor to implement pinning on network connections. Every connection check
// evaluates the current pins for the request's host: no verdict is cached, since a
// cache keyed by the TLS handshake is host-blind (okhttp's Handshake equality covers
// the TLS version, cipher suite and certificate chain only) and would admit a
// mispinned host behind a certificate another host had passed with
// (core-project-approov#723), while the check itself is cheap
class ApproovPinningInterceptor implements Interceptor {
    // logging tag
    private final static String TAG = "ApproovPinningInterceptor";

    // the certificate pinner to use for pinning that may be rebuilt if there is a
    // change in the pinning configuration
    private CertificatePinner certificatePinner = new CertificatePinner.Builder().build();

    // true while the most recent attempt to read the pins from the SDK failed: every
    // connection check then reads them again and fails while they cannot be read,
    // since the hosts that must be pinned are unknown
    private boolean rebuildRequired = false;

    /**
     * Construct a new pinning interceptor. If the SDK fails to provide the pins
     * they are read again before the next connection check.
     */
    public ApproovPinningInterceptor() {
        try {
            buildPins();
        } catch (ApproovException e) {
            Log.e(TAG, "Approov pins not built: " + e.getMessage());
        }
    }

    /**
     * Rebuild the pinning configuration. This is called when the dynamic
     * configuration changes and we need to update the pinning information; the
     * next connection check uses the new pins.
     *
     * @throws ApproovException if the SDK fails to provide the pins (a
     *                          RuntimeException from the SDK, a null pin map or a
     *                          pin OkHttp rejects); the pins in force are kept and
     *                          every connection check fails until a rebuild succeeds
     */
    public void buildPins() throws ApproovException {
        // read before this interceptor's monitor is taken: the lock order is always
        // ApproovService then interceptor, never the reverse
        boolean protectionEnabled = ApproovService.isApproovProtectionEnabled();
        synchronized (this) {
            buildPinsLocked(protectionEnabled);
        }
    }

    private void buildPinsLocked(boolean protectionEnabled) throws ApproovException {
        CertificatePinner.Builder pinBuilder = new CertificatePinner.Builder();
        if (!protectionEnabled) {
            // before initialize() and in bypass mode the layer applies no Approov pinning
            // and does not ask the SDK for pins, even if another caller initialized the
            // SDK and it holds pins (SPECIFICATION 5.7(f)); initialize() rebuilds the
            // pins once protection is enabled
            certificatePinner = pinBuilder.build();
            rebuildRequired = false;
            return;
        }
        CertificatePinner pinner;
        try {
            Map<String, List<String>> allPins = ApproovService.sdk().getPins("public-key-sha256");
            if (allPins == null)
                throw new IllegalStateException("getPins returned no pins");
            for (Map.Entry<String, List<String>> entry : allPins.entrySet()) {
                String domain = entry.getKey();
                if (!domain.equals("*")) {
                    // the * domain is for managed trust roots and should
                    // not be added directly
                    List<String> pins = entry.getValue();

                    // if there are no pins then we try and use any managed trust roots
                    if (pins.isEmpty() && (allPins.get("*") != null))
                        pins = allPins.get("*");

                    // add the required pins for the domain
                    for (String pin : pins)
                        pinBuilder = pinBuilder.add(domain, "sha256/" + pin);
                }
            }
            pinner = pinBuilder.build();
        } catch (RuntimeException e) {
            // a pin set that cannot be read fails closed: the pins in force are kept
            // and every connection check reads them again, failing until it succeeds
            rebuildRequired = true;
            throw ApproovService.sdkFailure("Approov pins", e);
        }
        certificatePinner = pinner;
        rebuildRequired = false;
    }

    /**
     * Indicates whether the last attempt to read the pins from the SDK failed, so
     * that they must be read again before a connection check.
     *
     * @return true if the pins must be rebuilt
     */
    private synchronized boolean isRebuildRequired() {
        return rebuildRequired;
    }

    /**
     * Gets the current CertificatePinner for checking peer certificate on a TLS
     * handshake.
     *
     * @return the current CertificatePinner
     */
    synchronized CertificatePinner getCertificatePinner() {
        return certificatePinner;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();

        // before initialize() and in bypass mode the layer applies no Approov pinning
        // at all: only OS trust applies, and the SDK is not asked for pins
        // (SPECIFICATION 5.7(f))
        if (!ApproovService.isApproovProtectionEnabled())
            return chain.proceed(request);

        // first check if we are to proceed with any pinning processing
        ApproovServiceMutator mutator = ApproovService.getServiceMutator();
        if (!ApproovService.callMutator("handlePinningShouldProcessRequest",
                () -> mutator.handlePinningShouldProcessRequest(request))) {
            // we are not to proceed with any pinning processing so just continue
            return chain.proceed(request);
        }

        // the pins may not have been available when this interceptor was constructed
        // (the SDK only holds pins once a token has been fetched for the app
        // installation) so build them now if there are still none, or if the last
        // attempt to read them failed; a failure to read them fails the connection
        // with an ApproovException
        if (isRebuildRequired() || getCertificatePinner().getPins().isEmpty())
            buildPins();

        String host = chain.request().url().host();
        Connection connection = chain.connection();
        Handshake handshake = (connection != null) ? connection.handshake() : null;
        if (handshake == null) {
            // there is no TLS handshake, so this is a cleartext connection: a pinned
            // host must never be reached without TLS, and the failure is reported with
            // the same network stack exception as a pin mismatch rather than an Approov
            // specific one, while an unpinned host is left to the app's own policy
            if (getCertificatePinner().findMatchingPins(host).isEmpty())
                return chain.proceed(chain.request());
            Log.d(TAG, "Pinning failure: cleartext connection to pinned host " + host);
            throw new SSLPeerUnverifiedException("Approov pinning: cleartext connection to pinned host " + host);
        }
        // check the peer certificates against the current pins for this host on every
        // connection check. OkHttp 4.x holds the chain its trust manager validated in
        // the handshake (RealConnection.connectTls cleans the presented chain with the
        // client's CertificateChainCleaner), so a pinned certificate the server merely
        // appends to an unrelated chain never matches.
        List<Certificate> certs = handshake.peerCertificates();
        try {
            getCertificatePinner().check(host, certs);
        } catch (SSLPeerUnverifiedException e) {
            // Only this request fails; the connection is not closed. OkHttp may have
            // coalesced this host onto another host's HTTP/2 connection, whose streams
            // must survive this host's pin failure. No verdict is cached, so the next
            // request to this host on the same connection is checked again and fails
            // again, and OkHttp itself cancels the exchange of this request (closing
            // an HTTP/1 connection, which carries one request at a time).
            Log.d(TAG, "Pinning failure: " + e.toString());
            throw e;
        }
        return chain.proceed(chain.request());
    }
}
