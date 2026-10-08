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

import android.content.Context;
import android.os.SystemClock;
import androidx.annotation.VisibleForTesting;

import com.criticalblue.approovsdk.Approov;

import java.io.IOException;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * ApproovService provides a mediation layer to the Approov SDK, enabling secure token-based
 * authentication and dynamic pinning for network requests. It offers methods to initialize
 * the SDK, configure token headers, handle secure strings, and manage OkHttp clients.
 */
public class ApproovService {
    // logging tag
    private static final String TAG = "ApproovService";

    // default header that will be added to Approov enabled requests
    private static final String APPROOV_TOKEN_HEADER = "Approov-Token";

    // default header that will carry any optional Approov TraceID debug value from the SDK
    private static final String APPROOV_TRACE_ID_HEADER = "Approov-TraceID";

    // default header that reports the Approov token fetch status, in lowercase, on every request processed
    // by Approov, so that the backend can tell why a request carries no token
    private static final String APPROOV_STATUS_HEADER = "Approov-Status";

    // default prefix to be added before the Approov token by default
    private static final String APPROOV_TOKEN_PREFIX = "";

    // name for the default builder
    private static final String DEFAULT_BUILDER_NAME = "_default";

    // default period in milliseconds after which a request held between being protected and being sent
    // has its protection refreshed, which must be comfortably less than both the Approov token lifetime
    // and the default message signature expiry (15s)
    private static final long DEFAULT_STALE_PROTECTION_REFRESH_MS = 3000;

    // true once any initialize has succeeded, including bypass mode with an empty account ID
    private static boolean approovServiceEnabled = false;

    // true once the Approov SDK is initialized and Approov protection is active
    private static boolean approovProtectionEnabled = false;

    // the Attestation Response Code (ARC) from the most recent fetch result, or empty if none
    private static String lastARC = "";

    // the Approov pinning interceptor shared by every client, created on first use
    private static ApproovPinningInterceptor pinningInterceptor = null;

    // builders to be used for new OkHttp clients where each can be named
    private static Map<String, OkHttpClient.Builder> okHttpBuilders = new HashMap<>();

    // cached OkHttpClients to use for each of the named builders
    private static Map<String, OkHttpClient> okHttpClients = new HashMap<>();

    // header to be used to send Approov tokens
    private static String approovTokenHeader = APPROOV_TOKEN_HEADER;

    // header used to send any optional Approov TraceID debug value provided by the SDK
    private static String approovTraceIDHeader = APPROOV_TRACE_ID_HEADER;

    // header used to report the Approov fetch status to the backend, or null if disabled
    private static String approovStatusHeader = APPROOV_STATUS_HEADER;

    // any prefix String to be added before the transmitted Approov token
    private static String approovTokenPrefix = APPROOV_TOKEN_PREFIX;

    // any header to be used for binding in Approov tokens or null if not set
    private static String bindingHeader = null;

    // period in milliseconds after which a held request has its Approov protection refreshed, or <=0 if
    // the refresh is disabled
    private static long staleProtectionRefreshMS = DEFAULT_STALE_PROTECTION_REFRESH_MS;

    // the mutator used to control the ApproovService behaviour at key points in the flow
    private static ApproovServiceMutator serviceMutator = ApproovServiceMutator.DEFAULT;

    // the message signing applied to protected requests while it is enabled, holding the default and
    // per host signature parameters factories
    private static ApproovDefaultMessageSigning messageSigning = new ApproovDefaultMessageSigning();

    // true if message signing is enabled
    private static boolean messageSigningEnabled = false;

    // map of headers that should have their values substituted for secure strings, mapped to their
    // required prefixes
    private static Map<String, String> substitutionHeaders = new HashMap<>();

    // set of query parameters that may be substituted, specified by the key name and mapped to the
    // compiled Pattern
    private static Map<String, Pattern> substitutionQueryParams = new HashMap<>();

    // set of URL regexs that should be excluded from any Approov protection, mapped to the compiled Pattern
    private static Map<String, Pattern> exclusionURLRegexs = new HashMap<>();

    // the boundary through which every Approov SDK call is made, which tests may replace
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
     * Converts a RuntimeException thrown by an Approov SDK call into an ApproovException with it as the
     * cause. No SDK exception may escape on the request path, since OkHttp rethrows anything other than
     * an IOException on its dispatcher thread for an enqueued call, which terminates the app.
     *
     * @param operation is what the layer was doing, for the exception message
     * @param e is the exception thrown by the SDK
     * @return the exception to throw
     */
    static ApproovException sdkFailure(String operation, RuntimeException e) {
        ApproovLog.e(TAG, operation + ": Approov SDK failure: " + e);
        return new ApproovException(operation + ": Approov SDK failure: " + e, e);
    }

    // a call into the installed service mutator
    interface MutatorHook<T> {
        T call() throws IOException;
    }

    // the exceptions raised by the standard request decisions during the mutator hook call in progress on
    // this thread, or null outside a hook call
    private static final ThreadLocal<Set<Throwable>> standardDecisions = new ThreadLocal<>();

    /**
     * Records an exception raised by a standard request decision (the interface defaults of
     * ApproovServiceMutator, which CLOSE_FAILURE uses) during the hook call in progress on this thread,
     * so that callMutator passes it through unchanged whether the installed mutator inherited or called
     * the decision. Outside a hook call it only returns the exception.
     *
     * @param e is the exception the decision raises
     * @return the exception, to be thrown
     */
    static <E extends ApproovException> E standardDecision(E e) {
        Set<Throwable> made = standardDecisions.get();
        if (made != null)
            made.add(e);
        return e;
    }

    /**
     * Calls a hook of the installed service mutator on the request path. An IOException the hook throws,
     * other than an ApproovException, is the app's own abort and is passed through unchanged, and so is
     * an ApproovException raised by a standard decision during the call. Any other ApproovException and
     * any RuntimeException is logged naming the hook and converted to an ApproovException with the
     * original as its cause, so that the request fails through the normal OkHttp error handling rather
     * than terminating the app.
     *
     * @param hook is the name of the hook, for the log and the exception message
     * @param call is the call into the mutator
     * @return the result of the hook
     * @throws IOException if the hook throws one, or throws a RuntimeException
     */
    static <T> T callMutator(String hook, MutatorHook<T> call) throws IOException {
        return callMutator(hook, call, false);
    }

    /**
     * Calls a hook of the installed service mutator on the request path as callMutator(hook, call),
     * except that an exception raised by a standard decision may be ignored, in which case null is
     * returned. This is used where the standard decisions never abort a request but an app's own hook
     * may, such as for a token fetch failure for a host that is not in the SDK pin set.
     *
     * @param hook is the name of the hook
     * @param call is the call into the mutator
     * @param ignoreStandard is true if an exception raised by a standard decision is to be ignored
     * @return the result of the hook, or null if an exception raised by a standard decision was ignored
     * @throws IOException if the hook throws one, or throws a RuntimeException
     */
    static <T> T callMutator(String hook, MutatorHook<T> call, boolean ignoreStandard) throws IOException {
        Set<Throwable> previous = standardDecisions.get();
        Set<Throwable> made = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        standardDecisions.set(made);
        try {
            return call.call();
        } catch (ApproovException e) {
            // a standard decision keeps its form, even if caught and rethrown unchanged
            if (made.contains(e)) {
                if (ignoreStandard)
                    return null;
                throw e;
            }
            throw mutatorFailure(hook, e);
        } catch (IllegalArgumentException e) {
            // OkHttp refusing a header set by the hook quotes the value so it is never kept
            ApproovException unsafe = unsafeHeaderFromOkHttp(e);
            throw (unsafe != null) ? unsafe : mutatorFailure(hook, e);
        } catch (RuntimeException e) {
            throw mutatorFailure(hook, e);
        } finally {
            if (previous == null)
                standardDecisions.remove();
            else
                standardDecisions.set(previous);
        }
    }

    /**
     * Converts an exception thrown by a hook of the installed service mutator into an ApproovException
     * with the original as its cause, logged at error level naming the hook.
     *
     * @param hook is the name of the hook
     * @param e is the exception the hook threw
     * @return the exception to throw
     */
    static ApproovException mutatorFailure(String hook, Exception e) {
        ApproovLog.e(TAG, "ApproovServiceMutator." + hook + " threw " + e);
        return new ApproovException("ApproovServiceMutator." + hook + " failed: " + e, e);
    }

    // the OkHttp message for a header value it refuses, which quotes the value unless OkHttp considers the
    // header sensitive
    private static final Pattern OKHTTP_HEADER_VALUE_ERROR =
            Pattern.compile("^Unexpected char \\S+ at \\d+ in (.*?) value(?:: .*)?$", Pattern.DOTALL);

    // the OkHttp message for a header name it refuses
    private static final String OKHTTP_HEADER_NAME_ERROR = "Unexpected char ";

    /**
     * Determines if a header value can be carried by OkHttp, which allows horizontal tab and printable
     * ASCII only. A secure string may break this rule, while a token or trace ID that does is reported
     * as an SDK problem.
     *
     * @param value is the header value
     * @return true if the value can be set, false otherwise
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
     * Determines if a secure string substituted into an occurrence of a query parameter reaches the
     * backend unchanged. The URL is parsed as OkHttp will send it and the occurrence is read back from the
     * query, which must be printable ASCII and either the secure string itself or its percent-encoded form.
     * Note that OkHttp silently drops tab, LF, FF and CR from a URL, and an & or a # in the value changes
     * the structure of the URL.
     *
     * @param url is the URL with the secure string substituted
     * @param pattern is the query parameter pattern, whose group 1 is its value
     * @param occurrence is the occurrence of the parameter in the query, counting from 0
     * @param secureString is the secure string substituted
     * @return true if the backend receives the secure string unchanged, false otherwise
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
     * @param value is the encoded value
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
     * Reports a header value, set from a value the app supplied, that a header cannot carry. It is logged
     * at error level and returned as an ApproovException naming the header, never quoting the value.
     *
     * @param header is the header name
     * @return the exception to throw
     */
    static ApproovException unsafeHeaderValue(String header) {
        String message = "Approov cannot set header " + header
                + ": its value contains a character a header value cannot carry";
        ApproovLog.e(TAG, message);
        return new ApproovException(message);
    }

    /**
     * Reports a value issued by the Approov SDK (a token or a trace ID) that a header cannot carry, which
     * is an SDK problem rather than an app configuration error. It is logged at error level and returned
     * as an ApproovException naming the header, never quoting the value.
     *
     * @param what is what the SDK issued, such as "a token"
     * @param header is the header name
     * @return the exception to throw
     */
    static ApproovException unsafeSdkValue(String what, String header) {
        String message = "Approov SDK problem: the Approov SDK issued " + what + " that header " + header
                + " cannot carry: it contains a character outside tab and printable ASCII";
        ApproovLog.e(TAG, message);
        return new ApproovException(message);
    }

    /**
     * Converts the IllegalArgumentException thrown by OkHttp for a header name or value it refuses inside
     * a mutator hook into an ApproovException. The OkHttp message quotes the value, which may be a secret,
     * so neither the message nor the cause is kept.
     *
     * @param e is the exception the hook threw
     * @return the exception to throw, or null if it is not an OkHttp header error
     */
    private static ApproovException unsafeHeaderFromOkHttp(IllegalArgumentException e) {
        String message = e.getMessage();
        if ((message == null) || !message.startsWith(OKHTTP_HEADER_NAME_ERROR))
            return null;
        Matcher matcher = OKHTTP_HEADER_VALUE_ERROR.matcher(message);
        if (matcher.matches())
            return unsafeHeaderValue(matcher.group(1));
        String nameError = "Approov cannot set a header: its name contains a character a header name cannot carry";
        ApproovLog.e(TAG, nameError);
        return new ApproovException(nameError);
    }

    /**
     * Describes a service mutator for logging without letting an exception from its toString escape.
     *
     * @param mutator is the mutator to be described
     * @return the description of the mutator
     */
    static String describe(ApproovServiceMutator mutator) {
        try {
            return String.valueOf(mutator);
        } catch (RuntimeException e) {
            return mutator.getClass().getName();
        }
    }

    /**
     * Sets the boundary through which every Approov SDK call is made. This should only be used for testing
     * purposes.
     *
     * @param facade is the SDK facade to be used
     */
    @VisibleForTesting
    static synchronized void setSdkFacadeForTesting(ApproovSdkFacade facade) {
        if (facade == null)
            throw new IllegalArgumentException("SDK facade must not be null");
        sdkFacade = facade;
    }

    /**
     * Initializes the ApproovService with an Approov account ID and comment. Any caller in the process may
     * call this and all of them share the same service state. A non-empty account ID is always passed to
     * the Approov SDK with the comment unchanged and the SDK decides: a repeat with the same account ID and
     * comment returns at once, while anything else the SDK rejects is thrown unchanged with the service
     * state untouched. An empty account ID enables the service in bypass mode, without Approov protection,
     * and is ignored once protection is enabled. Note that initialization never resets any configuration.
     *
     * @param context is the Application context
     * @param config is your Approov account ID, or an empty string for bypass mode with no SDK initialization
     * @param comment is the comment passed to the SDK unchanged, or null for no comment
     * @throws IllegalArgumentException if the context or account ID is null, or the SDK rejects the account ID
     * @throws IllegalStateException if the SDK is already initialized with a different account ID or comment
     */
    public static void initialize(Context context, String config, String comment) {
        // the pins are rebuilt once the lock is released, keeping the lock order ApproovService then
        // pinning interceptor, and no prefetch is started as the SDK manages prefetching
        if (initializeLocked(context, config, comment)) {
            try {
                rebuildPins();
            } catch (ApproovException e) {
                // the pinning interceptor reads the pins again before its next connection check and fails
                // that connection while they cannot be read
                ApproovLog.e(TAG, "Approov pins not built on initialization: " + e.getMessage());
            }
        }
    }

    /**
     * Performs the state changes of initialize while holding the lock. Any exception from the SDK is
     * thrown before any state is changed.
     *
     * @param context is the Application context
     * @param config is your Approov account ID, or an empty string for bypass mode
     * @param comment is the comment passed to the SDK unchanged, or null for no comment
     * @return true if the pins must be rebuilt, false otherwise
     */
    private static synchronized boolean initializeLocked(Context context, String config, String comment) {
        if (context == null)
            throw new IllegalArgumentException("ApproovService.initialize requires a non-null context");
        if (config == null)
            throw new IllegalArgumentException("config must not be null; pass \"\" for bypass mode");

        if (config.isEmpty()) {
            if (approovProtectionEnabled) {
                // an empty account ID never downgrades active Approov protection and is not passed to the SDK
                ApproovLog.d(TAG, "Approov protection already enabled; ignoring initialization with an empty account ID");
            } else {
                ApproovLog.i(TAG, "ApproovService enabled in bypass mode (empty account ID): Approov protection is not active");
                approovServiceEnabled = true;
            }
            return false;
        }

        // pass every non-empty account ID to the SDK with the comment exactly as given, as the SDK is the
        // only judge of a repeat call and any exception it throws reaches the caller with the state untouched
        boolean newlyInitialized;
        try {
            newlyInitialized = sdk().initialize(context.getApplicationContext(), config, "auto", comment);
        } catch (RuntimeException e) {
            ApproovLog.e(TAG, "Approov SDK initialization failed: " + e.getMessage());
            throw e;
        }
        boolean protectionWasEnabled = approovProtectionEnabled;
        approovServiceEnabled = true;
        approovProtectionEnabled = true;
        if (newlyInitialized)
            ApproovLog.i(TAG, "Approov SDK initialized: Approov protection enabled");
        else
            ApproovLog.d(TAG, "Approov SDK already initialized with the same account ID and comment");
        if (!protectionWasEnabled) {
            try {
                sdk().setUserProperty("approov-service-okhttp/" + BuildConfig.APPROOV_SERVICE_VERSION);
            } catch (RuntimeException e) {
                // the property is diagnostic only and must not fail a successful initialization
                ApproovLog.e(TAG, "Approov user property not set: " + e.getMessage());
            }
        }

        // the pins are rebuilt when this layer first enables protection (a pinner built before holds no
        // pins) and whenever the SDK reports a new initialization, which may carry new options
        return newlyInitialized || !protectionWasEnabled;
    }

    /**
     * Initializes the ApproovService with an Approov account ID and no comment.
     *
     * @param context is the Application context
     * @param config is your Approov account ID, or an empty string for bypass mode with no SDK initialization
     * @throws IllegalArgumentException if the context or account ID is null, or the SDK rejects the account ID
     * @throws IllegalStateException if the SDK is already initialized with a different account ID or comment
     */
    public static void initialize(Context context, String config) {
        // default uses null comment
        initialize(context, config, null);
    }

    /**
     * Determines if the ApproovService is enabled, which is true once any initialize has succeeded,
     * including bypass mode with an empty account ID. Before that, requests made through the OkHttpClient
     * are sent without Approov processing.
     *
     * @return true if the service has been enabled by a successful initialize, false otherwise
     */
    public static synchronized boolean isApproovServiceEnabled() {
        return approovServiceEnabled;
    }

    /**
     * Determines if Approov protection is enabled, which is true once the Approov SDK has been initialized
     * by a successful initialize with a non-empty account ID. It is false before initialization and in
     * bypass mode, where requests are sent without Approov processing.
     *
     * @return true if the Approov SDK is initialized and requests are protected, false otherwise
     */
    public static synchronized boolean isApproovProtectionEnabled() {
        return approovProtectionEnabled;
    }

    /**
     * Resets the ApproovService state. This should only be used for testing purposes.
     */
    @VisibleForTesting
    static synchronized void reset() {
        messageSignatureDeprecationLogged.set(false);
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
        ApproovLog.setLevel(ApproovLogLevel.INFO);
    }

    /**
     * Sets the logging level of the service layer. ERROR writes errors only, WARNING adds warnings, INFO
     * (the default) adds information and DEBUG adds the debug lines, including the loggable token of each
     * request whose claims include the device ID. OFF writes nothing. The level may be set at any time and
     * is not reset by initialize. Debug logging enabled for the ApproovService tag, with
     * "adb shell setprop log.tag.ApproovService DEBUG", raises any level but OFF to DEBUG without a rebuild.
     * Note that the logging of the Approov SDK itself is not affected.
     *
     * @param level is the logging level to be used
     * @throws IllegalArgumentException if the level is null
     */
    public static void setLoggingLevel(ApproovLogLevel level) {
        if (level == null)
            throw new IllegalArgumentException("ApproovService.setLoggingLevel requires a non-null level");
        ApproovLog.setLevel(level);
        ApproovLog.d(TAG, "setLoggingLevel " + level);
    }

    /**
     * Enables message signing with the default signature parameters factory, which produces both the
     * install signature (ecdsa-p256-sha256 with a per installation key held in the device secure hardware)
     * and the account signature (hmac-sha256 with the account key delivered on attestation) over the same
     * covered components.
     */
    public static void enableMessageSigning() {
        enableMessageSigning(ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory());
    }

    /**
     * Enables message signing. Every request carrying the Approov token header is then signed with an HTTP
     * message signature (RFC 9421) after the service mutator decisions, the secure string substitutions and
     * the processed request callback, so that the signature covers what is sent. It is signed again whenever
     * its protection is reapplied. Note that message signing is independent of the service mutator and is
     * not changed by setServiceMutator or initialize.
     *
     * @param defaultFactory is the signature parameters factory for every host without a factory of its
     * own, or null for the default factory
     */
    public static synchronized void enableMessageSigning(
            ApproovDefaultMessageSigning.SignatureParametersFactory defaultFactory) {
        if (defaultFactory == null)
            defaultFactory = ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory();
        ApproovLog.d(TAG, "enableMessageSigning");
        messageSigning.setDefaultFactory(defaultFactory);
        messageSigningEnabled = true;
    }

    /**
     * Disables message signing so that no Signature or Signature-Input header is added to any request. The
     * configured factories are kept for a later enableMessageSigning.
     */
    public static synchronized void disableMessageSigning() {
        ApproovLog.d(TAG, "disableMessageSigning");
        messageSigningEnabled = false;
    }

    /**
     * Determines if message signing is enabled.
     *
     * @return true if protected requests are signed, false otherwise
     */
    public static synchronized boolean isMessageSigningEnabled() {
        return messageSigningEnabled;
    }

    /**
     * Sets the signature parameters factory used to sign requests to one host instead of the default
     * factory passed to enableMessageSigning. It applies while message signing is enabled.
     *
     * @param hostName is the host name, matched without regard to case and without any port
     * @param factory is the signature parameters factory for the host, or null to use the default factory
     */
    public static synchronized void putMessageSigningHostFactory(String hostName,
            ApproovDefaultMessageSigning.SignatureParametersFactory factory) {
        ApproovLog.d(TAG, "putMessageSigningHostFactory " + hostName);
        messageSigning.putHostFactory(hostName, factory);
    }

    /**
     * Gets the message signing to apply to protected requests.
     *
     * @return the message signing, or null if message signing is disabled
     */
    static synchronized ApproovDefaultMessageSigning getActiveMessageSigning() {
        return messageSigningEnabled ? messageSigning : null;
    }

    /**
     * Sets a development key indicating that the app is a development version and it should
     * pass attestation even if the app is not registered or it is running on an emulator. The
     * development key value can be rotated at any point in the account if a version of the app
     * containing the development key is accidentally released. This is primarily
     * used for situations where the app package must be modified or resigned in
     * some way as part of the testing process.
     *
     * @param devKey is the development key to be used
     * @throws ApproovException if there was a problem, or if Approov protection is not enabled
     */
    public static synchronized void setDevKey(String devKey) throws ApproovException {
        requireProtection("setDevKey");
        try {
            ApproovService.sdk().setDevKey(devKey);
            ApproovLog.d(TAG, "setDevKey");
        } catch (RuntimeException e) {
            throw new ApproovException(e);
        }
    }

    /**
     * Checks that Approov protection is enabled before a public method uses the Approov SDK, so that the
     * SDK is not called before initialization or in bypass mode, even if another caller initialized it.
     *
     * @param method is the name of the public method, for the exception message
     * @throws ApproovException if Approov protection is not enabled
     */
    private static void requireProtection(String method) throws ApproovException {
        if (!isApproovProtectionEnabled()) {
            ApproovLog.e(TAG, method + ": Approov protection not enabled");
            throw new ApproovException(method + ": Approov protection not enabled");
        }
    }

    /**
     * Sets the header that the Approov token is added on, as well as an optional prefix String (such as
     * "Bearer "). By default the token is provided on "Approov-Token" with no prefix. The header is only
     * added if the token fetch succeeded. Note that if no token could be obtained and the service mutator
     * lets the request proceed, then the header is not added at all and the token fetch status is reported
     * on the status header instead.
     *
     * @param header is the header to place the Approov token on
     * @param prefix is any prefix String for the Approov token header, or null for none
     */
    public static synchronized void setTokenHeader(String header, String prefix) {
        ApproovLog.d(TAG, "setTokenHeader " + header + ", " + prefix);
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
     * Sets the header that any optional Approov TraceID debug value is added on. By default the TraceID is
     * provided on "Approov-TraceID" if one is available.
     *
     * @param header is the header to place the Approov TraceID on, or null to disable the header
     */
    public static synchronized void setTraceIDHeader(String header) {
        ApproovLog.d(TAG, "setTraceIDHeader " + header);
        approovTraceIDHeader = header;
    }

    /**
     * Gets the header that is used to add the optional Approov TraceID.
     *
     * @return String of the header used for the Approov TraceID, or null if disabled
     */
    public static synchronized String getTraceIDHeader() {
        return approovTraceIDHeader;
    }

    /**
     * Sets the header that reports the Approov token fetch status to your backend API on every request
     * processed by Approov. By default this is "Approov-Status". The value is the SDK token fetch status in
     * lowercase, such as "success", "no_network" or "rejected", and tells your backend API why a particular
     * request carries no Approov token. It is not sent on requests to hosts not added to Approov, to a
     * secrets-only API added with -noApproovToken, or on a failed fetch for a host that is not in the
     * Approov pin set. If the header is disabled then a request that could not be protected is sent with no
     * Approov header at all.
     *
     * @param header is the header to report the Approov token fetch status on, or null to disable the header
     */
    public static synchronized void setStatusHeader(String header) {
        ApproovLog.d(TAG, "setStatusHeader " + header);
        approovStatusHeader = header;
    }

    /**
     * Gets the header that is used to report the Approov token fetch status.
     *
     * @return String of the header used for the Approov token fetch status, or null if disabled
     */
    public static synchronized String getStatusHeader() {
        return approovStatusHeader;
    }

    /**
     * Builds the value of the status header from a token fetch result, which is the status in lowercase.
     *
     * @param approovResults is the token fetch result
     * @return the value to set on the status header
     */
    static String buildStatusHeaderValue(Approov.TokenFetchResult approovResults) {
        return approovResults.getStatus().name().toLowerCase(Locale.ROOT);
    }

    /**
     * Describes a fetch result for a debug log as the status in lowercase and any ARC and rejection
     * reasons. It never includes a token, a secure string or a placeholder.
     *
     * @param approovResults is the fetch result
     * @return the description of the fetch result
     */
    static String describeOutcome(Approov.TokenFetchResult approovResults) {
        StringBuilder sb = new StringBuilder("status ").append(buildStatusHeaderValue(approovResults));
        String arc = approovResults.getARC();
        if ((arc != null) && !arc.isEmpty())
            sb.append(", ARC ").append(arc);
        String reasons = approovResults.getRejectionReasons();
        if ((reasons != null) && !reasons.isEmpty())
            sb.append(", rejection reasons ").append(reasons);
        return sb.toString();
    }

    /**
     * Determines if a host is an Approov API domain, that is a key of the SDK public-key-sha256 pin set
     * other than the "*" managed trust roots key, compared without regard to case and ignoring one
     * trailing dot. An empty pin set, or one the SDK fails to provide, lists no host. This only decides
     * what happens to a request whose token fetch failed, as one to a host that is not listed is sent
     * untouched unless the app's own mutator aborts it.
     *
     * @param host is the request host
     * @return true if the host is a key of the pin set, false otherwise
     */
    static boolean isApproovApiHost(String host) {
        Map<String, List<String>> pins;
        try {
            pins = sdk().getPins("public-key-sha256");
        } catch (RuntimeException e) {
            // a pin set that cannot be read lists no host
            ApproovLog.d(TAG, "Approov pins could not be read: " + e);
            return false;
        }
        if (pins == null)
            return false;
        String wanted = ApproovPinningInterceptor.normalizeHost(host);
        for (String domain : pins.keySet()) {
            if ((domain != null) && !domain.equals("*")
                    && ApproovPinningInterceptor.normalizeHost(domain).equals(wanted))
                return true;
        }
        return false;
    }

    /**
     * Sets the header that the Approov token is added on, as well as an optional prefix String.
     * Deprecated: use setTokenHeader instead.
     *
     * @param header is the header to place the Approov token on
     * @param prefix is any prefix String for the Approov token header, or null for none
     */
    @Deprecated
    public static void setApproovHeader(String header, String prefix) {
        setTokenHeader(header, prefix);
    }

    /**
     * Sets the header that any optional Approov TraceID debug value is added on.
     * Deprecated: use setTraceIDHeader instead.
     *
     * @param header is the header to place the Approov TraceID on, or null to disable the header
     */
    @Deprecated
    public static void setApproovTraceIDHeader(String header) {
        setTraceIDHeader(header);
    }

    /**
     * Gets the header that is used to add the Approov token. Deprecated: use getTokenHeader instead.
     *
     * @return String of the header used for the Approov token
     */
    @Deprecated
    public static String getApproovTokenHeader() {
        return getTokenHeader();
    }

    /**
     * Gets the header that is used to add the optional Approov TraceID. Deprecated: use getTraceIDHeader
     * instead.
     *
     * @return String of the header used for the Approov TraceID, or null if disabled
     */
    @Deprecated
    public static String getApproovTraceIDHeader() {
        return getTraceIDHeader();
    }

    /**
     * Gets the prefix that is added before the Approov token in the header. Deprecated: use getTokenPrefix
     * instead.
     *
     * @return String of the prefix added before the Approov token
     */
    @Deprecated
    public static String getApproovTokenPrefix() {
        return getTokenPrefix();
    }

    /**
     * Builds the value of the Approov token header from a successful token fetch result. Note that
     * failure information is never placed in the token header, but is reported on the status header.
     *
     * @param prefix is the prefix to be placed before the token
     * @param approovResults is the token fetch result
     * @return the value to set on the Approov token header
     */
    static String buildTokenHeaderValue(String prefix, Approov.TokenFetchResult approovResults) {
        return prefix + approovResults.getToken();
    }

    /**
     * Gets the token header name and prefix together, so that a request never combines the header of one
     * configuration with the prefix of another if the app changes them while requests are in flight.
     *
     * @return a two element array of the header name and prefix
     */
    static synchronized String[] snapshotTokenHeader() {
        return new String[] { approovTokenHeader, approovTokenPrefix };
    }

    /**
     * Rebuilds the pins if a token fetch result indicates a dynamic configuration update, or that the SDK
     * requires the current pins to be applied without a configuration change.
     *
     * @param approovResults is the token fetch result
     * @throws ApproovException if the SDK fails to provide the configuration or the pins
     */
    static void updatePinsIfConfigChanged(Approov.TokenFetchResult approovResults) throws ApproovException {
        // fetch any updated dynamic configuration
        boolean configChanged = approovResults.isConfigChanged();
        if (configChanged) {
            try {
                ApproovService.sdk().fetchConfig();
            } catch (RuntimeException e) {
                throw sdkFailure("Approov dynamic configuration", e);
            }
            ApproovLog.d(TAG, "Dynamic configuration updated");
        }

        // rebuild the pins once if either is indicated
        if (configChanged || approovResults.isForceApplyPins()) {
            rebuildPins();
            ApproovLog.d(TAG, "Pins rebuilt");
        }
    }

    /**
     * Sets a binding header that must be present on all requests using the Approov service. A
     * header should be chosen whose value is unchanging for most requests (such as an
     * Authorization header). A hash of the header value is included in the issued Approov tokens
     * to bind them to the value. This may then be verified by the backend API integration. This
     * method should typically only be called once.
     *
     * @param header is the header to use for Approov token binding
     * @throws IllegalArgumentException if the header is already used for secure string substitution
     */
    public static synchronized void setBindingHeader(String header) {
        ApproovLog.d(TAG, "setBindingHeader " + header);
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
     * Sets the period after which a request that was held between having its Approov protection applied
     * and being transmitted, such as during a device doze or by the app's own request queueing, has that
     * protection (the Approov token and any message signature) refreshed immediately before transmission.
     * A refresh reapplies the processed request callback of the service mutator, so it is only performed
     * if its supportsProtectionRefresh indicates that this is safe. The period should be comfortably less
     * than the message signature expiry (15 seconds by default). The default is 3000ms.
     *
     * @param periodMS is the refresh period in milliseconds, or <=0 to disable the refresh
     */
    public static synchronized void setStaleProtectionRefreshPeriod(long periodMS) {
        ApproovLog.d(TAG, "setStaleProtectionRefreshPeriod " + periodMS);
        staleProtectionRefreshMS = periodMS;
    }

    /**
     * Gets the period after which a held request has its Approov protection refreshed before transmission.
     *
     * @return the refresh period in milliseconds, or <=0 if disabled
     */
    static synchronized long getStaleProtectionRefreshPeriod() {
        return staleProtectionRefreshMS;
    }

    /**
     * Sets the ApproovServiceMutator instance to handle callbacks from the ApproovService implementation.
     * This facility enables customization of ApproovService operations at key points in the configuration
     * and attestation flows. It should reduce the number of times this service layer implementation needs
     * to be forked in order to introduce custom behavior. Note that installing a mutator never enables or
     * disables message signing.
     *
     * @param mutator is the ApproovServiceMutator with callback handlers that may override the default
     * behavior, or null to reinstate ApproovServiceMutator.DEFAULT
     */
    public static synchronized void setServiceMutator(ApproovServiceMutator mutator) {
        if (mutator == null) {
            mutator = ApproovServiceMutator.DEFAULT;
        }
        ApproovLog.d(TAG, "Applied ApproovServiceMutator:" + describe(mutator));
        serviceMutator = mutator;
    }

    /**
     * Gets the active service mutator instance that is handling callbacks from ApproovService.
     *
     * @return the service mutator instance, never null
     */
    public static synchronized ApproovServiceMutator getServiceMutator() {
        return serviceMutator;
    }

    /**
     * Adds the name of a header which should be subject to secure strings substitution. This
     * means that if the header is present then the value will be used as a key to look up a
     * secure string value which will be substituted into the header value instead. This allows
     * easy migration to the use of secure strings. It applies to every request processed after the
     * call, through any client already obtained. A required prefix may be specified to deal with cases
     * such as the use of "Bearer " prefixed before values in an authorization header. Note that a secure
     * string is only substituted into a request sent over TLS.
     *
     * @param header is the header to be marked for substitution
     * @param requiredPrefix is any required prefix to the value being substituted or null if not required
     * @throws IllegalArgumentException if the header is already used for token binding
     */
    public static synchronized void addSubstitutionHeader(String header, String requiredPrefix) {
        ApproovLog.d(TAG, "addSubstitutionHeader " + header + ", " + requiredPrefix);
        if ((header != null) && (bindingHeader != null) && header.equalsIgnoreCase(bindingHeader)) {
            throw new IllegalArgumentException("Header " + header +
                    " cannot be used for both token binding and secure string substitution");
        }

        // header names are case-insensitive so remove any equivalent entry first, keeping the casing of the
        // latest call
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
        ApproovLog.d(TAG, "removeSubstitutionHeader " + header);
        String existingKey = findSubstitutionHeaderKey(header);
        if (existingKey != null)
            substitutionHeaders.remove(existingKey);
    }

    /**
     * Finds the stored substitution header key matching a header name without regard to case.
     *
     * @param header is the header name to find
     * @return the stored key, or null if there is none
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
     * @return a map of headers that are subject to substitution, mapped to the required prefix
     */
    public static synchronized Map<String, String> getSubstitutionHeaders() {
        return new HashMap<>(substitutionHeaders);
    }

    /**
     * Adds a key name for a query parameter that should be subject to secure strings substitution.
     * This means that if the query parameter is present in a URL then the value will be used as a
     * key to look up a secure string value which will be substituted as the query parameter value
     * instead. This allows easy migration to the use of secure strings. It applies to every request
     * processed after the call, through any client already obtained. The key is matched as a literal
     * string and every occurrence of it in the query is substituted. Note that a secure string is only
     * substituted into a request sent over TLS.
     *
     * @param key is the query parameter key name to be added for substitution
     */
    public static synchronized void addSubstitutionQueryParam(String key) {
        ApproovLog.d(TAG, "addSubstitutionQueryParam " + key);
        try {
            // the key is quoted so that a key such as "a.b" never matches another parameter
            Pattern pattern = Pattern.compile("[\\?&]" + Pattern.quote(key) + "=([^&;]+)");
            substitutionQueryParams.put(key, pattern);
        } catch (PatternSyntaxException e) {
            ApproovLog.e(TAG, "addSubstitutionQueryParam " + key + " error: " + e.getMessage());
        }
    }

    /**
     * Removes a query parameter key name previously added using addSubstitutionQueryParam.
     *
     * @param key is the query parameter key name to be removed for substitution
     */
    public static synchronized void removeSubstitutionQueryParam(String key) {
        ApproovLog.d(TAG, "removeSubstitutionQueryParam " + key);
        substitutionQueryParams.remove(key);
    }

    /**
     * Gets the map of substitution query parameters.
     *
     * @return a map of query parameters to be substituted, mapped to the compiled Pattern
     */
    public static synchronized Map<String, Pattern> getSubstitutionQueryParams() {
        return new HashMap<>(substitutionQueryParams);
    }

    /**
     * Adds an exclusion URL regular expression. If a URL for a request matches this regular expression
     * then it will not be subject to any Approov protection. Note that this facility must be used with
     * EXTREME CAUTION due to the impact of dynamic pinning. Pinning may be applied to all domains added
     * using Approov, and updates to the pins are received when an Approov fetch is performed. If you
     * exclude some URLs on domains that are protected with Approov, then these will be protected with
     * Approov pins but without a path to update the pins until a URL is used that is not excluded. Thus
     * you are responsible for ensuring that there is always a possibility of calling a non-excluded
     * URL through an OkHttpClient from getOkHttpClient, since a direct fetchToken call does not update the
     * pins. Conversely, use of those option may allow a connection to be established before any dynamic
     * pins have been received via Approov, thus potentially opening the channel to a MitM.
     *
     * @param urlRegex is the regular expression that will be compared against URLs to exclude them
     * @throws IllegalArgumentException if the regular expression is invalid
     */
    public static synchronized void addExclusionURLRegex(String urlRegex) {
        Pattern pattern;
        try {
            pattern = Pattern.compile(urlRegex);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("addExclusionURLRegex: invalid regular expression "
                    + urlRegex + ": " + e.getDescription(), e);
        }
        exclusionURLRegexs.put(urlRegex, pattern);
        ApproovLog.d(TAG, "addExclusionURLRegex " + urlRegex);
    }

    /**
     * Removes an exclusion URL regular expression previously added using addExclusionURLRegex.
     *
     * @param urlRegex is the regular expression that will be compared against URLs to exclude them
     */
    public static synchronized void removeExclusionURLRegex(String urlRegex) {
        ApproovLog.d(TAG, "removeExclusionURLRegex " + urlRegex);
        exclusionURLRegexs.remove(urlRegex);
    }

    /**
     * Gets a copy of the current exclusion URL regexs.
     *
     * @return Map<String, Pattern> of the exclusion regexs to their respective Patterns
     */
    public static synchronized Map<String, Pattern> getExclusionURLRegexs() {
        return new HashMap<>(exclusionURLRegexs);
    }

    /**
     * Performs a precheck to determine if the app will pass attestation. This requires secure
     * strings to be enabled for the account, although no strings need to be set up. This will
     * likely require network access so may take some time to complete. It may throw ApproovException
     * if the precheck fails or if there is some other problem. ApproovFetchStatusException is thrown with
     * the status reported by the SDK, as ApproovRejectionException if the app has failed Approov checks,
     * providing the ARC and rejection reasons, or as the deprecated ApproovNetworkException for networking
     * issues where a user initiated retry of the operation should be allowed.
     *
     * @throws ApproovException if there was a problem, or if Approov protection is not enabled
     */
    public static void precheck() throws ApproovException {
        requireProtection("precheck");
        // try and fetch a non-existent secure string in order to check for a rejection
        Approov.TokenFetchResult approovResults;
        try {
            approovResults = ApproovService.sdk().fetchSecureStringAndWait("precheck-dummy-key", null);
            recordLastARC(approovResults);
            ApproovLog.d(TAG, "precheck: " + approovResults.getStatus().toString());
        } catch (RuntimeException e) {
            // the fetch failed without a result, including a null result, so it leaves no ARC
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
     * Gets the device ID used by Approov to identify the particular device that the SDK is running on. Note
     * that different Approov apps on the same device will return a different ID. Moreover, the ID may be
     * changed by an uninstall and reinstall of the app.
     *
     * @return String of the device ID
     * @throws ApproovException if there was a problem, or if Approov protection is not enabled
     */
    public static String getDeviceID() throws ApproovException {
        requireProtection("getDeviceID");
        try {
            String deviceID = ApproovService.sdk().getDeviceID();
            ApproovLog.d(TAG, "getDeviceID: " + deviceID);
            return deviceID;
        } catch (RuntimeException e) {
            throw new ApproovException(e);
        }
    }

    /**
     * Directly sets the data hash to be included in subsequently fetched Approov tokens. If the hash is
     * different from any previously set value then this will cause the next token fetch operation to
     * fetch a new token with the correct payload data hash. The hash appears in the
     * 'pay' claim of the Approov token as a base64 encoded string of the SHA256 hash of the
     * data. Note that the data is hashed locally and never sent to the Approov cloud service.
     *
     * @param data is the data to be hashed and set in the token
     * @throws ApproovException if there was a problem, or if Approov protection is not enabled
     */
    public static void setDataHashInToken(String data) throws ApproovException {
        requireProtection("setDataHashInToken");
        try {
            ApproovService.sdk().setDataHashInToken(data);
            ApproovLog.d(TAG, "setDataHashInToken");
        } catch (RuntimeException e) {
            throw new ApproovException(e);
        }
    }

    /**
     * Performs an Approov token fetch for the given URL. This should be used in situations where it
     * is not possible to use the networking interception to add the token. This will
     * likely require network access so may take some time to complete. If the attestation fails
     * for any reason then an ApproovException is thrown. This will be ApproovFetchStatusException with the
     * status reported by the SDK, or the deprecated ApproovNetworkException for networking issues where a
     * user initiated retry of the operation should be allowed. Note that the returned token should NEVER be
     * cached by your app, you should call this function when it is needed.
     *
     * @param url is the full URL (including path) for the token fetch
     * @return String of the fetched token
     * @throws ApproovException if there was a problem, or if Approov protection is not enabled
     */
    public static String fetchToken(String url) throws ApproovException {
        requireProtection("fetchToken");
        // fetch the Approov token
        Approov.TokenFetchResult approovResults;
        try {
            approovResults = ApproovService.sdk().fetchApproovTokenAndWait(url);
            recordLastARC(approovResults);
            ApproovLog.d(TAG, "fetchToken: " + approovResults.getStatus().toString());
        } catch (RuntimeException e) {
            // the fetch failed without a result, including a null result, so it leaves no ARC
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
     * Gets the signature for the given message. This uses an account specific message signing key that is
     * transmitted to the SDK after a successful fetch if the facility is enabled for the account. Note
     * that if the attestation failed then the signing key provided is actually random so that the
     * signature will be incorrect. An Approov token should always be included in the message
     * being signed and sent alongside this signature to prevent replay attacks. If no signature is
     * available, because there has been no prior fetch or the feature is not enabled, then an
     * ApproovException is thrown.
     * <p>
     * Deprecated: use getAccountMessageSignature instead. The first call logs a deprecation warning.
     *
     * @param message is the message whose content is to be signed
     * @return String of the base64 encoded message signature
     * @throws ApproovException if there was a problem, or if Approov protection is not enabled
     */
    @Deprecated
    public static String getMessageSignature(String message) throws ApproovException {
        if (messageSignatureDeprecationLogged.compareAndSet(false, true))
            ApproovLog.w(TAG, "getMessageSignature is deprecated: use getAccountMessageSignature");
        requireProtection("getMessageSignature");
        return getAccountMessageSignature(message);
    }

    // true once the deprecation warning of getMessageSignature has been logged
    private static final AtomicBoolean messageSignatureDeprecationLogged = new AtomicBoolean();

    /**
     * Gets the signature for the given message. This uses an account specific message signing key that is
     * transmitted to the SDK after a successful fetch if the facility is enabled for the account. Note
     * that if the attestation failed then the signing key provided is actually random so that the
     * signature will be incorrect. An Approov token should always be included in the message
     * being signed and sent alongside this signature to prevent replay attacks. If no signature is
     * available, because there has been no prior fetch or the feature is not enabled, then an
     * ApproovException is thrown.
     *
     * @param message is the message whose content is to be signed
     * @return String of the base64 encoded message signature
     * @throws ApproovException if there was a problem, or if Approov protection is not enabled
     */
    public static String getAccountMessageSignature(String message) throws ApproovException {
        requireProtection("getAccountMessageSignature");
        try {
            String signature = ApproovService.sdk().getAccountMessageSignature(message);
            ApproovLog.d(TAG, "getAccountMessageSignature");
            if (signature == null)
                throw new ApproovException("no account signature available");
            return signature;
        } catch (RuntimeException e) {
            throw new ApproovException(e);
        }
    }

    /**
     * Gets the install signature for the given message. This uses an app install specific message
     * signing key that is generated the first time an app launches. This signing mechanism uses an
     * ECC key pair where the private key is managed by the secure element or trusted execution
     * environment of the device. Where it can, Approov uses attested key pairs to perform the
     * message signing.
     * <p>
     * An Approov token should always be included in the message being signed and sent alongside
     * this signature to prevent replay attacks.
     * <p>
     * If no signature is available, because there has been no prior fetch or the feature is not
     * enabled, then an ApproovException is thrown.
     *
     * @param message is the message whose content is to be signed
     * @return String of the base64 encoded message signature in ASN.1 DER format
     * @throws ApproovException if there was a problem, or if Approov protection is not enabled
     */
    public static String getInstallMessageSignature(String message) throws ApproovException {
        requireProtection("getInstallMessageSignature");
        try {
            String signature = ApproovService.sdk().getInstallMessageSignature(message);
            ApproovLog.d(TAG, "getInstallMessageSignature");
            if (signature == null)
                throw new ApproovException("no device signature available");
            return signature;
        } catch (RuntimeException e) {
            throw new ApproovException(e);
        }
    }

    /**
     * Fetches a secure string with the given key. If newDef is not null then a
     * secure string for the particular app instance may be defined. In this case the
     * new value is returned as the secure string. Use of an empty string for newDef removes
     * the string entry. Note that this call may require network transaction and thus may block
     * for some time, so should not be called from the UI thread. If the attestation fails
     * for any reason then an ApproovException is thrown. This will be ApproovFetchStatusException with
     * the status reported by the SDK, as ApproovRejectionException if the app has failed Approov checks or
     * as the deprecated ApproovNetworkException for networking issues where a user initiated retry of the
     * operation should be allowed. Note that the returned string should NEVER be cached by your app, you
     * should call this function when it is needed.
     *
     * @param key is the secure string key to be looked up
     * @param newDef is any new definition for the secure string, or null for lookup only
     * @return secure string (should not be cached by your app) or null if it was not defined
     * @throws ApproovException if there was a problem, or if Approov protection is not enabled
     */
    public static String fetchSecureString(String key, String newDef) throws ApproovException {
        requireProtection("fetchSecureString");
        // determine the type of operation as the values themselves cannot be logged
        String type = "lookup";
        if (newDef != null)
            type = "definition";

        // fetch any secure string keyed by the value, catching any exceptions the SDK might throw
        Approov.TokenFetchResult approovResults;
        try {
            approovResults = ApproovService.sdk().fetchSecureStringAndWait(key, newDef);
            recordLastARC(approovResults);
            ApproovLog.d(TAG, "fetchSecureString " + type + ": " + key + ", " + approovResults.getStatus().toString());
        } catch (RuntimeException e) {
            // the fetch failed without a result, including a null result, so it leaves no ARC
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
     * Fetches a custom JWT with the given payload. Note that this call will require network
     * transaction and thus will block for some time, so should not be called from the UI thread.
     * If the attestation fails for any reason then an ApproovException is thrown. This will be
     * ApproovFetchStatusException with the status reported by the SDK, as ApproovRejectionException if the
     * app has failed Approov checks or as the deprecated ApproovNetworkException for networking issues where
     * a user initiated retry of the operation should be allowed.
     *
     * @param payload is the marshaled JSON object for the claims to be included
     * @return custom JWT string
     * @throws ApproovException if there was a problem, or if Approov protection is not enabled
     */
    public static String fetchCustomJWT(String payload) throws ApproovException {
        requireProtection("fetchCustomJWT");
        // fetch the custom JWT catching any exceptions the SDK might throw
        Approov.TokenFetchResult approovResults;
        try {
            approovResults = ApproovService.sdk().fetchCustomJWTAndWait(payload);
            recordLastARC(approovResults);
            ApproovLog.d(TAG, "fetchCustomJWT: " + approovResults.getStatus().toString());
        } catch (RuntimeException e) {
            // the fetch failed without a result, including a null result, so it leaves no ARC
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
     * Gets the Attestation Response Code (ARC) from the most recent token, secure string or custom JWT
     * fetch made by this layer, either by the interceptor or by a direct method. It performs no fetch of its
     * own, so it can be read after a rejected request to correlate with your backend API. An empty string
     * is returned if no fetch has been made, if the last fetch carried no ARC or failed without a result,
     * or if ARC is not enabled for the account. Note that it is preferable to obtain the ARC on the server
     * side where possible.
     *
     * @return the ARC of the most recent fetch, or an empty string
     */
    public static synchronized String getLastARC() {
        return lastARC;
    }

    /**
     * Records the ARC of a fetch result as the most recent one. An empty or null ARC clears the previous
     * value, as the ARC belongs to the last fetch.
     *
     * @param approovResults is the fetch result, or null if the fetch failed without a result
     */
    static synchronized void recordLastARC(Approov.TokenFetchResult approovResults) {
        String arc = (approovResults != null) ? approovResults.getARC() : null;
        lastARC = (arc != null) ? arc : "";
    }

    /**
     * Sets an install attributes token to be sent to the server and associated with this particular app
     * installation for future Approov token fetches. The token must be signed, within its expiry time and
     * bound to the correct device ID for it to be accepted by the server. Calling this method ensures that
     * the next call to fetch an Approov token will not use a cached version, so that this information can
     * be transmitted to the server.
     *
     * @param attrs is the signed JWT holding the new install attributes
     * @throws ApproovException if the attrs parameter is invalid, or if Approov protection is not enabled
     */
    public static void setInstallAttributes(String attrs) throws ApproovException {
        requireProtection("setInstallAttributes");
        try {
            ApproovService.sdk().setInstallAttrsInToken(attrs);
            ApproovLog.d(TAG, "setInstallAttributes");
        } catch (RuntimeException e) {
            ApproovLog.e(TAG, "setInstallAttributes failed: " + e.getMessage());
            throw new ApproovException(e);
        }
    }

    /**
     * Rebuilds the pins in the pinning interceptor after a dynamic configuration change or when Approov
     * protection is enabled. The pins are built without holding the ApproovService lock, so that the lock
     * order is always ApproovService then pinning interceptor.
     *
     * @throws ApproovException if the SDK fails to provide the pins, in which case every connection check
     * fails until they can be read
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
     * Gets the pinning interceptor shared by every client, creating it on first use.
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
     * Sets the OkHttpClient.Builder to be used for constructing the Approov OkHttpClient for a
     * named builder. This allows custom configurations to be set, with additional interceptors and
     * properties. This clears the appropriate cached OkHttp client so should only be called when an
     * actual builder change is required.
     *
     * @param builderName is the name of the builder to set
     * @param builder is the OkHttpClient.Builder to be used as a basis for the Approov OkHttpClient
     */
    public static synchronized void setOkHttpClientBuilder(String builderName, OkHttpClient.Builder builder) {
        ApproovLog.d(TAG, "OkHttp client builder set for " + builderName);
        OkHttpClient.Builder oldBuilder = okHttpBuilders.put(builderName, builder);
        if (oldBuilder != builder)
            // force a rebuild of the client if the builder has changed
            okHttpClients.remove(builderName);
    }

    /**
     * Sets the OkHttpClient.Builder to be used for constructing the default Approov OkHttpClient.
     * This allows a default custom configuration to be set, with additional interceptors and properties.
     *
     * @param builder is the OkHttpClient.Builder to be used as a basis for the Approov OkHttpClient
     */
    public static synchronized void setOkHttpClientBuilder(OkHttpClient.Builder builder) {
        setOkHttpClientBuilder(DEFAULT_BUILDER_NAME, builder);
    }

    /**
     * Gets the OkHttpClient that enables the Approov service for the named builder. This adds
     * the Approov token in a header to requests, and also pins the connections. The OkHttpClient
     * is constructed lazily on demand but is cached if there are no changes. Use "setOkHttpClientBuilder"
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
                ApproovLog.d(TAG, "No builder available for " + builderName);
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

            // remove any existing ApproovFreshnessInterceptor or ApproovPinningInterceptor from the builder
            interceptors = okHttpBuilder.networkInterceptors();
            iter = interceptors.iterator();
            while (iter.hasNext()) {
                Interceptor interceptor = iter.next();
                if ((interceptor instanceof ApproovFreshnessInterceptor) ||
                        (interceptor instanceof ApproovPinningInterceptor))
                    iter.remove();
            }

            // the Approov interceptors are always added, even before initialization and in bypass mode, as
            // each decides per request whether Approov protection is enabled, so that a client obtained early
            // is never cached unprotected
            if (!isApproovServiceEnabled())
                ApproovLog.w(TAG, "Building Approov OkHttpClient for " + builderName + " before ApproovService "
                        + "initialization; requests proceed without Approov protection until it is initialized");
            else
                ApproovLog.d(TAG, "Building new Approov OkHttpClient for " + builderName);

            // the token interceptor runs after the app's own interceptors, and the network interceptors run
            // before the app's own network interceptors, so that app code never sees the protection of an
            // attempt the network stack rebuilt (a redirect or a retry) before it is stripped or refreshed
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
     * Gets the default OkHttpClient that enables the Approov service. This adds the Approov token
     * in a header to requests, and also pins the connections. The OkHttpClient is constructed
     * lazily on demand but is cached if there are no changes. Use "setOkHttpClientBuilder" to
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
     * Constructs a new interceptor that adds Approov tokens and substitutes headers or query
     * parameters.
     */
    public ApproovTokenInterceptor() {
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        // before initialization and in bypass mode the request is sent without any Approov processing
        if (!ApproovService.isApproovProtectionEnabled())
            return chain.proceed(chain.request());

        // cache the mutator and message signing for the duration of the interceptor to make sure they are
        // not changed mid-flight
        ApproovServiceMutator mutator = ApproovService.getServiceMutator();
        ApproovDefaultMessageSigning signing = ApproovService.getActiveMessageSigning();
        return chain.proceed(applyProtection(chain.request(), mutator, signing, true));
    }

    /**
     * Applies Approov protection to a request. A token is fetched for the request URL and the protection
     * its status allows is applied: on a token-protected API (SUCCESS) the token, trace and status headers,
     * the secure strings and any message signatures; on a secrets-only API (UNPROTECTED_URL) the secure
     * strings only; and on a failure status the service mutator lets proceed the status header only. The
     * returned request carries an ApproovRequestFreshness marker describing the protection applied, so
     * that the network layer can strip and reapply it. A request that is not processed is returned
     * unchanged with no marker.
     *
     * @param request is the request to protect, carrying no Approov protection
     * @param mutator is the service mutator to consult
     * @param signing is the message signing to apply, or null if message signing is disabled
     * @param invokeProcessed is true if the processed request callback of the mutator may be invoked
     * @return the protected request
     * @throws IOException if the request is to be aborted
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

        HttpUrl url = request.url();

        // request an Approov token for the request URL, bound to the value of any token binding header
        // (presence is optional)
        Approov.TokenFetchResult approovResults = bindAndFetchToken(request, url);

        // provide information about the obtained token or error (note "approov token -check" can be used to
        // check the validity of the token and if you use token annotations they will appear here to determine
        // why a request is being rejected), only at debug level as the loggable token carries the device ID
        if (ApproovLog.isDebugEnabled())
            ApproovLog.d(TAG, "Token for " + ApproovLog.loggableURL(url) + ": " + approovResults.getLoggableToken());

        // force a pinning rebuild if there is any dynamic config update
        ApproovService.updatePinsIfConfigChanged(approovResults);

        // the token fetch status decides the channel: SUCCESS is a token-protected API, UNPROTECTED_URL a
        // secrets-only API (added with -noApproovToken), UNKNOWN_URL a host not added to Approov and every
        // other status a failure
        Approov.TokenFetchStatus tokenStatus = approovResults.getStatus();
        Approov.TokenFetchResult tokenResults = approovResults;
        boolean tokenProtected = (tokenStatus == Approov.TokenFetchStatus.SUCCESS);
        boolean secretsOnly = (tokenStatus == Approov.TokenFetchStatus.UNPROTECTED_URL);
        boolean failure = !tokenProtected && !secretsOnly && (tokenStatus != Approov.TokenFetchStatus.UNKNOWN_URL);
        String undelivered = undeliveredReason(url, approovResults);
        if (failure && !ApproovService.isApproovApiHost(url.host())) {
            // a failure status says nothing about the host, so a host that is not an Approov API domain is
            // treated like UNKNOWN_URL and the request is sent untouched; the hook is still consulted so that
            // an app's own mutator may abort it, while the standard decisions never do
            ApproovService.callMutator("handleInterceptorFetchTokenResult",
                    () -> mutator.handleInterceptorFetchTokenResult(tokenResults, url.toString()), true);
            ApproovLog.d(TAG, "Proceeding without a token for " + ApproovLog.loggableURL(url)
                    + ", a host not in the Approov pin set, untouched: token fetch "
                    + ApproovService.describeOutcome(approovResults));
            logUndeliveredPlaceholders(request, undelivered, isConfigurationReason(url, tokenStatus));
            return request;
        }
        boolean proceedWithHeaders = ApproovService.callMutator("handleInterceptorFetchTokenResult",
                () -> mutator.handleInterceptorFetchTokenResult(tokenResults, url.toString()));
        if (tokenStatus == Approov.TokenFetchStatus.UNKNOWN_URL) {
            // a host not added to Approov has nothing added or substituted, whatever the mutator decided
            logUndeliveredPlaceholders(request, undelivered, isConfigurationReason(url, tokenStatus));
            return request;
        }
        if (failure && !proceedWithHeaders) {
            // the mutator decided to send the request untouched on a failure status, so it also carries
            // no secure strings
            logUndeliveredPlaceholders(request, undelivered, isConfigurationReason(url, tokenStatus));
            return request;
        }
        if (failure)
            ApproovLog.d(TAG, "Proceeding without a token for " + ApproovLog.loggableURL(url) + ": token fetch "
                    + ApproovService.describeOutcome(approovResults));

        // determine the headers to be added: the token, status and trace headers on a token-protected request
        // the mutator lets proceed with them, only the status header on a failure status the mutator lets
        // proceed, and none on a secrets-only request
        String setTokenHeaderKey = null;
        String setTokenHeaderPrefix = null;
        String setTokenHeaderValue = null;
        String setTraceIDHeaderKey = null;
        String setTraceIDHeaderValue = null;
        String setStatusHeaderKey = null;
        String setStatusHeaderValue = null;
        if (proceedWithHeaders && !secretsOnly) {
            setStatusHeaderKey = ApproovService.getStatusHeader();
            setStatusHeaderValue = ApproovService.buildStatusHeaderValue(approovResults);
            if (tokenProtected) {
                // the token header name and prefix are read together
                String[] tokenHeader = ApproovService.snapshotTokenHeader();
                setTokenHeaderKey = tokenHeader[0];
                setTokenHeaderPrefix = tokenHeader[1];
                setTokenHeaderValue = ApproovService.buildTokenHeaderValue(setTokenHeaderPrefix, approovResults);

                // the trace header is sent whenever the SDK provides a value with a token, even an empty one
                String traceIDHeader = ApproovService.getTraceIDHeader();
                String traceID = approovResults.getTraceID();
                if ((traceIDHeader != null) && (traceID != null)) {
                    setTraceIDHeaderKey = traceIDHeader;
                    setTraceIDHeaderValue = traceID;
                }
            }
        }

        // we now deal with any header substitutions, which may require further fetches but these should be
        // using cached results; the original values are kept so that the substitution can be undone if the
        // protection is reapplied. A secure string is only substituted on a token-protected or secrets-only
        // API over TLS, so that a redirect to another URL carrying the placeholder never receives the secret,
        // and every placeholder left is logged by name, never by value
        String noSecureStrings = undelivered;
        boolean noSecureStringsWarns = isConfigurationReason(url, tokenStatus);
        Map<String, String> substitutionHeaders = ApproovService.getSubstitutionHeaders();
        Map<String, String> setSubstitutionHeaders = new LinkedHashMap<>(substitutionHeaders.size());
        Map<String, List<String>> originalHeaderValues = new LinkedHashMap<>(substitutionHeaders.size());
        for (Map.Entry<String, String> entry : substitutionHeaders.entrySet()) {
            String header = entry.getKey();
            String prefix = entry.getValue();
            String value = request.header(header);
            if ((value != null) && value.startsWith(prefix) && (value.length() > prefix.length())) {
                // the placeholder is left if the request may not receive secure strings
                if (noSecureStrings != null) {
                    logPlaceholderLeft(noSecureStringsWarns, "Secure string not substituted in header " + header
                            + ": " + noSecureStrings + ", placeholder left");
                    continue;
                }
                String key = value.substring(prefix.length());
                approovResults = fetchForRequest("Header substitution for " + header, () ->
                        ApproovService.sdk().fetchSecureStringAndWait(key, null));
                ApproovLog.d(TAG, "Substituting header: " + header + ", " + approovResults.getStatus().toString());
                // a failed substitution leaves the placeholder in the header and the request proceeds
                Approov.TokenFetchResult headerResults = approovResults;
                if (ApproovService.callMutator("handleInterceptorHeaderSubstitutionResult",
                        () -> mutator.handleInterceptorHeaderSubstitutionResult(headerResults, header))) {
                    String secureString = approovResults.getSecureString();
                    if (secureString == null) {
                        // a decision to substitute with no value leaves the placeholder, never "null"
                        ApproovLog.d(TAG, "No secure string to substitute, placeholder left in header: " + header
                                + ", " + approovResults.getStatus().toString());
                    } else if (!ApproovService.isSafeHeaderValue(secureString)) {
                        // a value a header cannot carry leaves the placeholder and is never logged
                        ApproovLog.w(TAG, "Secure string for header " + header + " contains a character a header "
                                + "value cannot carry, placeholder left");
                    } else {
                        setSubstitutionHeaders.put(header, prefix + secureString);
                        // keep every value of the header, in order, so that all can be restored
                        originalHeaderValues.put(header, new ArrayList<>(request.headers(header)));
                    }
                } else {
                    ApproovLog.d(TAG, "Secure string not substituted in header " + header + ": secure string fetch "
                            + ApproovService.describeOutcome(approovResults) + ", placeholder left");
                }
            }
        }

        // we now deal with any query parameter substitutions, which may require further fetches but these
        // should be using cached results
        String originalURL = request.url().toString();
        String replacementURL = originalURL;
        Map<String, Pattern> substitutionQueryParams = ApproovService.getSubstitutionQueryParams();
        List<String> queryKeys = new ArrayList<>(substitutionQueryParams.size());
        for (Map.Entry<String, Pattern> entry : substitutionQueryParams.entrySet()) {
            String queryKey = entry.getKey();
            Pattern pattern = entry.getValue();
            // every occurrence of the parameter in the query is substituted, each value looked up as its own
            // key, and the search is confined to the query after the first '?' and before any '#'
            int occurrence = 0;
            int from = replacementURL.indexOf('?');
            while (from >= 0) {
                int hash = replacementURL.indexOf('#', from);
                Matcher matcher = pattern.matcher(replacementURL);
                matcher.region(from, (hash < 0) ? replacementURL.length() : hash);
                if (!matcher.find())
                    break;
                if (noSecureStrings != null) {
                    logPlaceholderLeft(noSecureStringsWarns, "Secure string not substituted in query parameter "
                            + queryKey + ": " + noSecureStrings + ", placeholder left");
                    break;
                }
                int start = matcher.start(1);
                from = matcher.end(1);
                // we have found an occurrence of the query parameter to be replaced so we look up the existing
                // value as a key for a secure string
                String queryValue = matcher.group(1);
                approovResults = fetchForRequest("Query parameter substitution for " + queryKey, () ->
                        ApproovService.sdk().fetchSecureStringAndWait(queryValue, null));
                ApproovLog.d(TAG, "Substituting query parameter: " + queryKey + ", " + approovResults.getStatus().toString());
                Approov.TokenFetchResult queryResults = approovResults;
                if (ApproovService.callMutator("handleInterceptorQueryParamSubstitutionResult",
                        () -> mutator.handleInterceptorQueryParamSubstitutionResult(queryResults, queryKey))) {
                    String secureString = approovResults.getSecureString();
                    if (secureString == null) {
                        // a decision to substitute with no value leaves the placeholder
                        ApproovLog.d(TAG, "No secure string to substitute, placeholder left in query parameter: "
                                + queryKey + ", " + approovResults.getStatus().toString());
                    } else {
                        // substitute this occurrence and read it back from the URL as OkHttp stores it, leaving
                        // the placeholder if OkHttp would not carry the value unchanged; the value is never logged
                        String candidateURL = new StringBuilder(replacementURL).replace(start, from,
                                secureString).toString();
                        if (ApproovService.isCarriedInQuery(candidateURL, pattern, occurrence, secureString)) {
                                if (!queryKeys.contains(queryKey))
                                queryKeys.add(queryKey);
                            replacementURL = candidateURL;
                            from = start + secureString.length();
                        } else {
                            ApproovLog.w(TAG, "Secure string for query parameter " + queryKey + " cannot be carried "
                                    + "in the URL unchanged, placeholder left");
                        }
                    }
                } else {
                    ApproovLog.d(TAG, "Secure string not substituted in query parameter " + queryKey
                            + ": secure string fetch " + ApproovService.describeOutcome(approovResults)
                            + ", placeholder left");
                }
                occurrence++;
            }
        }

        // gather the changes applied to the request and apply them, together with a freshness marker
        // describing exactly what was applied, even if nothing was
        ApproovRequestMutations changes = new ApproovRequestMutations();
        Request.Builder builder = request.newBuilder();
        if (setTokenHeaderKey != null) {
            // a prefix a header cannot carry is an app configuration error, while such a token is an SDK problem
            if (!ApproovService.isSafeHeaderValue(setTokenHeaderPrefix))
                throw ApproovService.unsafeHeaderValue(setTokenHeaderKey);
            if (!ApproovService.isSafeHeaderValue(tokenResults.getToken()))
                throw ApproovService.unsafeSdkValue("a token", setTokenHeaderKey);
            setHeader(builder, setTokenHeaderKey, setTokenHeaderValue);
            changes.setTokenHeaderKey(setTokenHeaderKey);
            changes.setTokenHeaderPrefix(setTokenHeaderPrefix);
        }
        if (setTraceIDHeaderKey != null) {
            if (!ApproovService.isSafeHeaderValue(setTraceIDHeaderValue))
                throw ApproovService.unsafeSdkValue("a trace ID", setTraceIDHeaderKey);
            setHeader(builder, setTraceIDHeaderKey, setTraceIDHeaderValue);
            changes.setTraceIDHeaderKey(setTraceIDHeaderKey);
        }
        // report the token fetch status on the status header, replacing any value the app may have set
        // itself so that the backend only sees what this layer observed
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
                // the OkHttp message quotes the URL, which now holds secure strings, so neither it nor the
                // cause is kept
                throw new ApproovException("Query parameter substitution for " + queryKeys
                        + ": the secure string does not form a valid URL");
            }
            changes.setSubstitutionQueryParamResults(originalURL, queryKeys);
        }

        // tag the request so that the freshness interceptor can determine at the network layer whether the
        // protection must be refreshed before transmission, or whether the request was redirected
        ApproovRequestFreshness freshness = new ApproovRequestFreshness(url.toString(), changes);
        builder.tag(ApproovRequestFreshness.class, freshness);
        request = builder.build();

        // record the substituted values as they are actually stored on the request (OkHttp trims header
        // values when they are set) so that stripping can recognise a header it installed
        Map<String, String> installedHeaderValues = new LinkedHashMap<>(setSubstitutionHeaders.size());
        for (String header : setSubstitutionHeaders.keySet())
            installedHeaderValues.put(header, request.header(header));
        freshness.setSubstitutions(originalHeaderValues, installedHeaderValues);
        freshness.freezeChanges();

        // call the processed request callback, unless protection is being reapplied under a mutator whose
        // callback is not safe to invoke again
        Request processedRequest = request;
        if (invokeProcessed) {
            Request mutated = request;
            processedRequest = ApproovService.callMutator("handleInterceptorProcessedRequest",
                    () -> mutator.handleInterceptorProcessedRequest(mutated, changes));
            if (processedRequest == null) {
                // a null result is a programming error in the callback, treated like a runtime exception from it
                ApproovLog.e(TAG, "ApproovServiceMutator.handleInterceptorProcessedRequest returned null");
                throw new ApproovException("ApproovServiceMutator.handleInterceptorProcessedRequest returned null");
            }
            // a header the callback set or changed that a header cannot carry is an app configuration error,
            // since a value set with Headers.Builder.addUnsafeNonAscii would otherwise reach the wire
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
            ApproovLog.d(TAG, "Protection reapplied without the processed request callback of "
                    + ApproovService.describe(mutator));
        }

        // message signing runs last, over the final headers and whatever the processed request callback
        // changed, and only a request carrying the token header is signed
        if ((signing != null) && (freshness.getChanges().getTokenHeaderKey() != null))
            processedRequest = signing.sign(processedRequest, freshness.getChanges());

        // record when and to what URL the protection was applied, and the headers added by the processed
        // request callback or message signing, so that the freshness interceptor can strip and reapply
        // the protection if the request is held too long before transmission or is redirected
        freshness.markProtected(SystemClock.elapsedRealtime(),
                ApproovRequestFreshness.addedHeaderNames(request, processedRequest));
        freshness.setAppliedURL(processedRequest.url().toString());
        freshness.setAppliedMethod(processedRequest.method());

        return processedRequest;
    }

    /**
     * Determines why a request whose token fetch returned the given result receives no secure strings,
     * since they are only sent to a token-protected (SUCCESS) or secrets-only (UNPROTECTED_URL) API over
     * TLS.
     *
     * @param url is the request URL
     * @param approovResults is the token fetch result
     * @return the reason including the fetch outcome, or null if secure strings may be substituted
     */
    private static String undeliveredReason(HttpUrl url, Approov.TokenFetchResult approovResults) {
        Approov.TokenFetchStatus status = approovResults.getStatus();
        if (!url.isHttps())
            return "the request is not sent over TLS";
        if (status == Approov.TokenFetchStatus.BAD_URL)
            return "the SDK reports the URL as bad_url, token fetch " + ApproovService.describeOutcome(approovResults);
        if ((status == Approov.TokenFetchStatus.SUCCESS) || (status == Approov.TokenFetchStatus.UNPROTECTED_URL))
            return null;
        return "token fetch " + ApproovService.describeOutcome(approovResults);
    }

    /**
     * Determines if a placeholder left for a request is due to a configuration problem that is worth a
     * warning, as the request is not https or the SDK reports its URL as BAD_URL. Any other undelivered
     * secure string is logged at debug level only.
     *
     * @param url is the request URL
     * @param status is the token fetch status
     * @return true if the reason is a configuration problem, false otherwise
     */
    private static boolean isConfigurationReason(HttpUrl url, Approov.TokenFetchStatus status) {
        return !url.isHttps() || (status == Approov.TokenFetchStatus.BAD_URL);
    }

    // logs a placeholder left in place, as a warning or at debug level
    private static void logPlaceholderLeft(boolean warn, String message) {
        if (warn)
            ApproovLog.w(TAG, message);
        else
            ApproovLog.d(TAG, message);
    }

    /**
     * Logs by name each substitution header and query parameter a request carries that is left unchanged
     * because the request may not receive secure strings, as a warning for a configuration problem or at
     * debug level otherwise. A value is never logged.
     *
     * @param request is the request carrying any placeholders
     * @param reason is why no secure string is delivered, or null if they are
     * @param warn is true if the reason is a configuration problem
     */
    private static void logUndeliveredPlaceholders(Request request, String reason, boolean warn) {
        if ((reason == null) || !ApproovLog.isEnabled(warn ? ApproovLogLevel.WARNING : ApproovLogLevel.DEBUG))
            return;

        // log the substitution headers carrying a placeholder
        for (Map.Entry<String, String> entry : ApproovService.getSubstitutionHeaders().entrySet()) {
            String value = request.header(entry.getKey());
            if ((value != null) && value.startsWith(entry.getValue()) && (value.length() > entry.getValue().length()))
                logPlaceholderLeft(warn, "Secure string not substituted in header " + entry.getKey() + ": " + reason
                        + ", placeholder left");
        }

        // log the substitution query parameters found in the query, after the first '?' and before any '#'
        String url = request.url().toString();
        int from = url.indexOf('?');
        if (from < 0)
            return;
        int hash = url.indexOf('#', from);
        for (Map.Entry<String, Pattern> entry : ApproovService.getSubstitutionQueryParams().entrySet()) {
            Matcher matcher = entry.getValue().matcher(url);
            matcher.region(from, (hash < 0) ? url.length() : hash);
            if (matcher.find())
                logPlaceholderLeft(warn, "Secure string not substituted in query parameter " + entry.getKey() + ": "
                        + reason + ", placeholder left");
        }
    }

    // the lock held while a request sets its token binding value and fetches its token
    private static final Object BINDING_LOCK = new Object();

    /**
     * Fetches the token for a request, bound to the value of the token binding header if the request
     * carries it. The SDK holds one binding value for the process, so setting the value and fetching the
     * token are done under a lock, as otherwise two requests binding different values could each get a
     * token bound to the value of the other. Requests without a binding value fetch concurrently.
     *
     * @param request is the request that may carry the binding header
     * @param url is the URL to fetch the token for
     * @return the fetch result, never null
     * @throws ApproovException if the SDK fails or returns no result
     */
    private static Approov.TokenFetchResult bindAndFetchToken(Request request, HttpUrl url)
            throws ApproovException {
        String operation = "Approov token fetch for " + url;
        String bindingHeader = ApproovService.getBindingHeader();
        // header names are case-insensitive; a null value means the header is absent, while a present but
        // empty value is still bound
        String bindingValue = (bindingHeader != null) ? request.header(bindingHeader) : null;
        if (bindingValue == null)
            return fetchForRequest(operation, () -> ApproovService.sdk().fetchApproovTokenAndWait(url.toString()));

        // set the binding value and fetch the token, holding the lock across the two SDK calls only
        Approov.TokenFetchResult approovResults;
        RuntimeException bindingFailure = null;
        RuntimeException fetchFailure = null;
        synchronized (BINDING_LOCK) {
            approovResults = null;
            try {
                ApproovService.sdk().setDataHashInToken(bindingValue);
            } catch (RuntimeException e) {
                bindingFailure = e;
            }
            if (bindingFailure == null) {
                try {
                    approovResults = ApproovService.sdk().fetchApproovTokenAndWait(url.toString());
                } catch (RuntimeException e) {
                    fetchFailure = e;
                }
            }
        }

        // report any failure once the lock is released
        if (bindingFailure != null)
            throw ApproovService.sdkFailure("Token binding for " + bindingHeader, bindingFailure);
        Approov.TokenFetchResult results = approovResults;
        RuntimeException failure = fetchFailure;
        return fetchForRequest(operation, () -> {
            if (failure != null)
                throw failure;
            return results;
        });
    }

    // a fetch made by the SDK on the request path
    private interface RequestFetch {
        Approov.TokenFetchResult fetch();
    }

    /**
     * Performs a token or secure string fetch for a request and records its ARC. A RuntimeException from
     * the SDK, or a fetch that returns no result, is thrown as an ApproovException and leaves no ARC.
     *
     * @param operation is what is fetched, for the exception message
     * @param fetch is the SDK call
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
            ApproovLog.e(TAG, operation + ": no result from the Approov SDK");
            throw new ApproovException(operation + ": no result from the Approov SDK");
        }
        ApproovService.recordLastARC(approovResults);
        return approovResults;
    }

    /**
     * Sets a header that this layer adds or substitutes. A value a header cannot carry, or a header name
     * OkHttp rejects, fails the request with an ApproovException naming the header, never quoting the
     * value, since the OkHttp exception message quotes it.
     *
     * @param builder is the request builder
     * @param name is the header name
     * @param value is the header value
     * @throws ApproovException if the name or the value cannot be set
     */
    private static void setHeader(Request.Builder builder, String name, String value) throws ApproovException {
        if (!ApproovService.isSafeHeaderValue(value))
            throw ApproovService.unsafeHeaderValue(name);
        try {
            builder.header(name, value);
        } catch (IllegalArgumentException e) {
            String message = "Approov cannot set header " + name + ": not a valid header name";
            ApproovLog.e(TAG, message);
            throw new ApproovException(message);
        }
    }

    /**
     * Removes the Approov protection described by a freshness marker from a request: the token, trace and
     * status headers, the headers added by the processed request callback, the marker itself, and the
     * secure string substitutions, whose placeholder values are restored. A substituted header is only
     * restored if it still holds the value this layer installed, so a value the app changed afterwards (such
     * as from an OkHttp authenticator) is left in place. The URL is only restored if the request was not
     * redirected, since a redirect target is the URL of the server.
     *
     * @param request is the request carrying the protection
     * @param freshness is the marker describing the protection
     * @param restoreURL is true if the URL before any substitution is to be restored
     * @return the request with no Approov protection
     */
    static Request stripProtection(Request request, ApproovRequestFreshness freshness, boolean restoreURL) {
        // remove the headers that were added
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

        // restore the placeholders of the headers still holding the value we installed
        for (Map.Entry<String, List<String>> entry : freshness.getOriginalHeaderValues().entrySet()) {
            String header = entry.getKey();
            List<String> current = request.headers(header);
            if ((current.size() != 1) || !freshness.isInstalledValue(header, current.get(0)))
                continue;
            builder.removeHeader(header);
            for (String value : entry.getValue())
                builder.addHeader(header, value);
        }

        // restore the URL if required and remove the marker
        if (restoreURL && (changes.getOriginalURL() != null))
            builder.url(changes.getOriginalURL());
        builder.tag(ApproovRequestFreshness.class, null);
        return builder.build();
    }
}

// network interceptor that refreshes the Approov protection (token and any message signature) on requests held
// too long between being protected and being transmitted, such as during a device doze or by the app's own
// request queueing, and reapplies it to attempts rebuilt by OkHttp or the app, such as redirects and retries;
// it is the first network interceptor, so app code at the network layer only ever sees an attempt after its
// protection was stripped or refreshed
class ApproovFreshnessInterceptor implements Interceptor {
    // logging tag
    private final static String TAG = "ApproovFreshness";

    /**
     * Constructs a new interceptor that refreshes stale Approov protection and reclassifies redirected
     * requests.
     */
    public ApproovFreshnessInterceptor() {
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();

        // only requests given protection by the ApproovTokenInterceptor carry a freshness marker and are
        // candidates for a refresh or a reclassification
        ApproovRequestFreshness freshness = request.tag(ApproovRequestFreshness.class);
        if (freshness == null)
            return chain.proceed(request);

        // an attempt whose URL, method or headers differ from those the protection was applied to was rebuilt
        // by OkHttp or the app (a redirect, or an Authenticator retrying a 401), so it is protected afresh as
        // the protection of the original destination must never travel with it; the header baseline is taken
        // on the first network attempt, since OkHttp adds its transport headers before the network layer
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
        // message signing is independent of the mutator and is reapplied whenever the protection is reapplied
        ApproovDefaultMessageSigning signing = ApproovService.getActiveMessageSigning();
        if (rebuilt) {
            // the URLs may hold substituted query parameters, so only the URL before substitution and the
            // origin of the attempt are logged
            HttpUrl now = request.url();
            ApproovLog.d(TAG, "Request rebuilt since protection was applied to " + freshness.getFetchURL() +
                    " (now " + request.method() + " " + now.scheme() + "://" + now.host() + ":" + now.port() +
                    (urlChanged ? ", another URL" : ", the same URL") + "), reapplying Approov protection");
            // cache the mutator for the duration of the interceptor to make sure it is not changed mid-flight;
            // a mutator that does not support refresh has its headers stripped but its processed request
            // callback is not invoked again
            mutator = ApproovService.getServiceMutator();
            ApproovServiceMutator rebuiltMutator = mutator;
            invokeProcessed = ApproovService.callMutator("supportsProtectionRefresh",
                    rebuiltMutator::supportsProtectionRefresh);
        } else {
            // measure how long the request has been held since the protection was applied, using a clock that
            // advances during device sleep, and proceed unchanged if within the refresh period or if the
            // refresh is disabled
            long refreshPeriodMS = ApproovService.getStaleProtectionRefreshPeriod();
            if ((refreshPeriodMS <= 0) || (freshness.getProtectedAtMillis() < 0))
                return chain.proceed(request);
            long heldMS = SystemClock.elapsedRealtime() - freshness.getProtectedAtMillis();
            if (heldMS <= refreshPeriodMS)
                return chain.proceed(request);

            // a refresh reinvokes the processed request callback of the mutator so it is only performed if
            // the mutator declares that this is safe
            mutator = ApproovService.getServiceMutator();
            ApproovServiceMutator staleMutator = mutator;
            if (!ApproovService.callMutator("supportsProtectionRefresh", staleMutator::supportsProtectionRefresh)) {
                ApproovLog.d(TAG, "Request held for " + heldMS + "ms but " + ApproovService.describe(mutator) +
                        " does not support protection refresh");
                return chain.proceed(request);
            }
            ApproovLog.d(TAG, "Request held for " + heldMS + "ms since Approov protection was applied, " +
                    "refreshing before transmission");
            invokeProcessed = true;
        }

        // strip the protection described by the marker and apply it afresh for the current URL, restoring the
        // URL before substitution unless the request was redirected; the original request keeps its own
        // marker so that a retry of it by OkHttp is refreshed again rather than sent with stale headers
        Request stripped = ApproovTokenInterceptor.stripProtection(request, freshness, !urlChanged);
        Request refreshed = ApproovTokenInterceptor.applyProtection(stripped, mutator, signing, invokeProcessed);
        // the refreshed request is already at the network layer, so its header baseline is what it carries now
        ApproovRequestFreshness refreshedMarker = refreshed.tag(ApproovRequestFreshness.class);
        if (refreshedMarker != null)
            refreshedMarker.setAppliedHeaders(refreshed.headers());
        return chain.proceed(refreshed);
    }
}

// interceptor to implement pinning on network connections, checking the current pins for the host of every
// request rather than caching a verdict for a TLS handshake, since an OkHttp Handshake does not identify the
// host it was made for
class ApproovPinningInterceptor implements Interceptor {
    // logging tag
    private final static String TAG = "ApproovPinningInterceptor";

    // the certificate pinner to use for pinning that may be rebuilt if there is a change in the pinning
    // configuration
    private CertificatePinner certificatePinner = new CertificatePinner.Builder().build();

    // true if the last attempt to read the pins from the SDK failed, in which case every connection check
    // reads them again and fails until they can be read
    private boolean rebuildRequired = false;

    // the Approov domains, in lowercase, with no pins of their own while the managed trust roots (the "*"
    // pin set) are empty or absent, which are validated by OS trust only
    private Set<String> osTrustOnlyHosts = Collections.emptySet();

    // the OS trust only hosts that have already been warned about
    private final Set<String> warnedOsTrustOnlyHosts = new HashSet<>();

    /**
     * Construct a new pinning interceptor. If the SDK fails to provide the pins then they are read again
     * before the next connection check.
     */
    public ApproovPinningInterceptor() {
        try {
            buildPins();
        } catch (ApproovException e) {
            ApproovLog.e(TAG, "Approov pins not built: " + e.getMessage());
        }
    }

    /**
     * Rebuild the pinning configuration. This is called when the dynamic configuration changes and we
     * need to update the pinning information, and the next connection check uses the new pins.
     *
     * @throws ApproovException if the SDK fails to provide the pins, in which case the current pins are kept
     * and every connection check fails until a rebuild succeeds
     */
    public void buildPins() throws ApproovException {
        // read the protection state before taking this lock, as the lock order is always ApproovService then
        // pinning interceptor
        boolean protectionEnabled = ApproovService.isApproovProtectionEnabled();
        synchronized (this) {
            buildPinsLocked(protectionEnabled);
        }
    }

    /**
     * Rebuilds the pinning configuration while holding the lock.
     *
     * @param protectionEnabled is true if Approov protection is enabled
     * @throws ApproovException if the SDK fails to provide the pins
     */
    private void buildPinsLocked(boolean protectionEnabled) throws ApproovException {
        CertificatePinner.Builder pinBuilder = new CertificatePinner.Builder();
        if (!protectionEnabled) {
            // before initialization and in bypass mode no Approov pinning is applied and the SDK is not asked
            // for pins, even if another caller initialized it
            certificatePinner = pinBuilder.build();
            osTrustOnlyHosts = Collections.emptySet();
            rebuildRequired = false;
            return;
        }
        CertificatePinner pinner;
        Set<String> osTrustOnly = new HashSet<>();
        Set<String> pinnedHosts = new HashSet<>();
        try {
            Map<String, List<String>> allPins = ApproovService.sdk().getPins("public-key-sha256");
            if (allPins == null)
                throw new IllegalStateException("getPins returned no pins");
            for (Map.Entry<String, List<String>> entry : allPins.entrySet()) {
                String domain = entry.getKey();
                if (!domain.equals("*")) {
                    // the * domain is for managed trust roots and should not be added directly
                    List<String> pins = entry.getValue();

                    // if there are no pins then we try and use any managed trust roots
                    if (pins.isEmpty() && (allPins.get("*") != null))
                        pins = allPins.get("*");

                    // with no pins and no managed trust roots the host is validated by OS trust only
                    if (pins.isEmpty())
                        osTrustOnly.add(normalizeHost(domain));
                    else
                        pinnedHosts.add(normalizeHost(domain));

                    // add the required pins for the domain
                    for (String pin : pins)
                        pinBuilder = pinBuilder.add(normalizeHost(domain), "sha256/" + pin);
                }
            }
            pinner = pinBuilder.build();
        } catch (RuntimeException e) {
            // a pin set that cannot be read fails closed, keeping the current pins and failing every
            // connection check until it can be read
            rebuildRequired = true;
            throw ApproovService.sdkFailure("Approov pins", e);
        }
        certificatePinner = pinner;
        // a host with pins under any spelling is never OS trust only
        osTrustOnly.removeAll(pinnedHosts);
        osTrustOnlyHosts = osTrustOnly;
        rebuildRequired = false;
    }

    // normalizes a host as the pins are looked up, in lowercase with one trailing dot removed
    static String normalizeHost(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.endsWith(".") ? lower.substring(0, lower.length() - 1) : lower;
    }

    /**
     * Warns once per host that an Approov domain with no pins of its own is validated by OS trust only,
     * because the managed trust roots are empty or absent. This is a valid development setup, but should
     * not be silent. The host is named, never a pin.
     *
     * @param host is the host being connected to
     */
    private void warnIfOsTrustOnly(String host) {
        String normalized = normalizeHost(host);
        synchronized (this) {
            if (!osTrustOnlyHosts.contains(normalized) || !warnedOsTrustOnlyHosts.add(normalized))
                return;
        }
        ApproovLog.w(TAG, "Approov domain " + normalized + " has no pins and the managed trust roots are empty: "
                + "its connections are validated by OS trust only");
    }

    /**
     * Determines if the last attempt to read the pins from the SDK failed, so that they must be read again
     * before a connection check.
     *
     * @return true if the pins must be rebuilt, false otherwise
     */
    private synchronized boolean isRebuildRequired() {
        return rebuildRequired;
    }

    /**
     * Gets the current CertificatePinner for checking peer certificate on a TLS handshake.
     *
     * @return the current CertificatePinner
     */
    synchronized CertificatePinner getCertificatePinner() {
        return certificatePinner;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();

        // before initialization and in bypass mode no Approov pinning is applied, only OS trust
        if (!ApproovService.isApproovProtectionEnabled())
            return chain.proceed(request);

        // first check if we are to proceed with any pinning processing
        ApproovServiceMutator mutator = ApproovService.getServiceMutator();
        if (!ApproovService.callMutator("handlePinningShouldProcessRequest",
                () -> mutator.handlePinningShouldProcessRequest(request))) {
            // we are not to proceed with any pinning processing so just continue
            return chain.proceed(request);
        }

        // the pins may not have been available when this interceptor was constructed (the SDK only holds
        // pins once a token has been fetched) so build them now if there are still none, or if the last
        // attempt to read them failed
        if (isRebuildRequired() || getCertificatePinner().getPins().isEmpty())
            buildPins();

        // the pins are looked up for the normalized host, so that neither a pin key nor a request spelled with
        // a different case or a trailing dot escapes the pins
        String host = normalizeHost(chain.request().url().host());
        warnIfOsTrustOnly(host);
        Connection connection = chain.connection();
        Handshake handshake = (connection != null) ? connection.handshake() : null;
        if (handshake == null) {
            // there is no TLS handshake so this is a cleartext connection: a pinned host must never be
            // reached without TLS, while an unpinned host is left to the app's own policy
            if (getCertificatePinner().findMatchingPins(host).isEmpty())
                return chain.proceed(chain.request());
            ApproovLog.d(TAG, "Pinning failure: cleartext connection to pinned host " + host);
            throw new SSLPeerUnverifiedException("Approov pinning: cleartext connection to pinned host " + host);
        }

        // check the peer certificates against the current pins for this host on every connection check,
        // using the chain OkHttp has already cleaned and validated
        List<Certificate> certs = handshake.peerCertificates();
        try {
            getCertificatePinner().check(host, certs);
        } catch (SSLPeerUnverifiedException e) {
            // only this request fails and the connection is not closed, since OkHttp may have coalesced this
            // host onto the HTTP/2 connection of another host
            ApproovLog.d(TAG, "Pinning failure: " + e.toString());
            throw e;
        }
        return chain.proceed(chain.request());
    }
}
