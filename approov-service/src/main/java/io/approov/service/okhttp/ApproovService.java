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
import java.util.LinkedHashSet;
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

    // true if the Approov SDK initialized okay
    private static boolean isInitialized = false;

    // the Approov account ID (SDK config string) used for initialization, or empty in bypass mode
    private static String configString;

    // the Attestation Response Code (ARC) from the most recent token fetch result
    // seen by this layer, or empty if none or if ARC is not enabled for the account
    private static String lastARC = "";

    // the Approov pinning interceptor to be used for all requests
    private static ApproovPinningInterceptor pinningInterceptor = null;

    // builders to be used for new OkHttp clients where each can be named
    private static Map<String, OkHttpClient.Builder> okHttpBuilders = null;

    // cached OkHttpClients to use for each of the named builders
    private static Map<String, OkHttpClient> okHttpClients = null;

    // header to be used to send Approov tokens
    private static String approovTokenHeader = null;

    // header used to send any optional Approov TraceID debug value provided by the
    // SDK
    private static String approovTraceIDHeader = null;

    // header used to report the Approov fetch status to the backend, or null if
    // disabled
    private static String approovStatusHeader = null;

    // any prefix String to be added before the transmitted Approov token
    private static String approovTokenPrefix = null;

    // any header to be used for binding in Approov tokens or null if not set
    private static String bindingHeader = null;

    // period in milliseconds after which a request held between protection and
    // transmission has its Approov protection refreshed at the network layer, or
    // <=0 if stale protection refresh is disabled
    private static long staleProtectionRefreshMS = DEFAULT_STALE_PROTECTION_REFRESH_MS;

    // The mutator instance used to control ApproovService behavior at key points in
    // the flow. Unless set using the ApproovService.setServiceMutator() method, the
    // out-of-the-box mutator created by createDefaultServiceMutator() (message
    // signing with both install and account signatures) is used.
    private static ApproovServiceMutator serviceMutator = createDefaultServiceMutator();

    // map of headers that should have their values substituted for secure strings,
    // mapped to their
    // required prefixes
    private static Map<String, String> substitutionHeaders = null;

    // set of query parameters that may be substituted, specified by the key name
    // and mapped to the compiled Pattern
    private static Map<String, Pattern> substitutionQueryParams = null;

    // set of URL regexs that should be excluded from any Approov protection, mapped
    // to the compiled Pattern
    private static Map<String, Pattern> exclusionURLRegexs = null;

    /**
     * Construction is disallowed as this is a static only class.
     */
    private ApproovService() {
    }

    /**
     * Initializes the ApproovService with an account configuration and comment.
     *
     * @param context the Application context
     * @param config  your Approov account ID (the SDK config string from the
     *                onboarding email or "approov sdk -getConfigString"), or empty
     *                for bypass mode with no SDK initialization
     * @param comment the comment string, or null for no comment
     */
    public static synchronized void initialize(Context context, String config, String comment) {
        if (config == null)
            throw new IllegalArgumentException("config must not be null; pass \"\" for bypass mode");

        // If we are already initialized with a valid config, ignore any subsequent
        // empty config initialization
        if (isApproovEnabled() && config.isEmpty()) {
            Log.d(TAG, "ApproovService already initialized with a valid config; ignoring empty configuration");
            return;
        }

        // Initialize the platform SDK if not in bypass mode (empty config).
        // State is only modified after the SDK confirms success, preserving the current
        // operating mode (protected or bypass) if the call fails.
        if (!config.isEmpty()) {
            try {
                boolean sdkInitialized = Approov.initialize(context.getApplicationContext(), config, "auto", comment);
                if (!sdkInitialized) {
                    Log.d(TAG, "Approov SDK already initialized");
                }
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Approov initialization failed: " + e.getMessage());
                throw e; // service-layer state NOT modified — prior operating mode preserved
            } catch (IllegalStateException e) {
                Log.e(TAG, "Approov initialization failed: " + e.getMessage());
                throw e; // service-layer state NOT modified — prior operating mode preserved
            }
        }
        // SDK succeeded (or bypass) — now reset and commit new service-layer state.
        isInitialized = false;
        okHttpBuilders = new HashMap<>();
        okHttpBuilders.put(DEFAULT_BUILDER_NAME, new OkHttpClient.Builder());
        okHttpClients = new HashMap<>();
        approovTokenHeader = APPROOV_TOKEN_HEADER;
        approovTraceIDHeader = APPROOV_TRACE_ID_HEADER;
        approovStatusHeader = APPROOV_STATUS_HEADER;
        approovTokenPrefix = APPROOV_TOKEN_PREFIX;
        bindingHeader = null;
        staleProtectionRefreshMS = DEFAULT_STALE_PROTECTION_REFRESH_MS;
        serviceMutator = createDefaultServiceMutator();
        substitutionHeaders = new HashMap<>();
        substitutionQueryParams = new HashMap<>();
        exclusionURLRegexs = new HashMap<>();
        isInitialized = true;
        configString = config;
        lastARC = "";
        if (isApproovEnabled()) {
            pinningInterceptor = new ApproovPinningInterceptor();
            Approov.setUserProperty("approov-service-okhttp/" + BuildConfig.APPROOV_SERVICE_VERSION);
        } else {
            pinningInterceptor = null;
        }
    }

    /**
     * Initializes the ApproovService with an account configuration
     *
     * @param context the Application context
     * @param config  your Approov account ID (the SDK config string from the
     *                onboarding email or "approov sdk -getConfigString"), or empty
     *                for bypass mode with no SDK initialization
     */
    public static void initialize(Context context, String config) {
        // default uses null comment
        initialize(context, config, null);
    }

    /**
     * Indicates whether the service layer has been initialized.
     *
     * @return true if the service layer has been initialized, false otherwise
     */
    public static synchronized boolean isInitialized() {
        return isInitialized;
    }

    /**
     * Indicates whether Approov protection is enabled for this service layer
     * instance. If initialization used an empty string instead of the account ID then the layer is
     * initialized but Approov protection is bypassed.
     *
     * @return true if Approov protection is enabled, false otherwise
     */
    public static synchronized boolean isApproovEnabled() {
        return isInitialized && (configString != null) && !configString.isEmpty();
    }

    /**
     * Resets the ApproovService state. This should only be used for testing
     * purposes.
     */
    @VisibleForTesting
    static synchronized void reset() {
        isInitialized = false;
        configString = null;
        lastARC = "";
        pinningInterceptor = null;
        okHttpBuilders = null;
        okHttpClients = null;
        approovTokenHeader = null;
        approovTraceIDHeader = null;
        approovStatusHeader = null;
        approovTokenPrefix = APPROOV_TOKEN_PREFIX;
        bindingHeader = null;
        staleProtectionRefreshMS = DEFAULT_STALE_PROTECTION_REFRESH_MS;
        serviceMutator = createDefaultServiceMutator();
        substitutionHeaders = null;
        substitutionQueryParams = null;
        exclusionURLRegexs = null;
    }

    /**
     * Creates the mutator that the service layer installs out of the box: the
     * standard decisions of ApproovServiceMutator plus message signing of every
     * request carrying an Approov token header with both the install (per app
     * installation, ECDSA P-256 key held in the device secure hardware) and the
     * account (shared account key, HMAC-SHA256) signatures. Both are produced so
     * that devices without secure hardware still yield a verifiable signature;
     * the backend decides which it accepts. Use setServiceMutator to install a
     * customized ApproovDefaultMessageSigning, or ApproovServiceMutator.DEFAULT
     * to switch message signing off.
     *
     * @return a new default service mutator instance
     */
    public static ApproovServiceMutator createDefaultServiceMutator() {
        return new ApproovDefaultMessageSigning().setDefaultFactory(
                ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory());
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
     * @throws ApproovException if there was a problem
     */
    public static synchronized void setDevKey(String devKey) throws ApproovException {
        if (!isApproovEnabled()) {
            Log.e(TAG, "setDevKey: SDK not initialized");
            throw new ApproovException("setDevKey: SDK not initialized");
        }
        try {
            Approov.setDevKey(devKey);
            Log.d(TAG, "setDevKey");
        } catch (IllegalStateException e) {
            throw new ApproovException(e);
        } catch (IllegalArgumentException e) {
            throw new ApproovException(e);
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
     * request, including successful ones, so that the backend can distinguish a
     * request whose Approov headers were stripped from one that this layer sent
     * without a token because the fetch failed. It is not sent on requests to
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
     */
    static void updateBindingDataHash(Request request) {
        String bindingHeader = getBindingHeader();
        if (bindingHeader != null) {
            // Header names are case-insensitive. A null value means the header is
            // absent; a present-but-empty value must still be forwarded to the SDK.
            String bindingValue = request.header(bindingHeader);
            if (bindingValue != null)
                Approov.setDataHashInToken(bindingValue);
        }
    }

    /**
     * Forces a pinning rebuild if the given token fetch result indicates that
     * there was a dynamic configuration update.
     *
     * @param approovResults the token fetch result
     */
    static void updatePinsIfConfigChanged(Approov.TokenFetchResult approovResults) {
        if (approovResults.isConfigChanged()) {
            Approov.fetchConfig();
            rebuildPins();
            Log.d(TAG, "Dynamic configuration updated");
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
     * any message signing by reinvoking the service mutator's processed
     * request callback, so it is only performed if the mutator's
     * supportsProtectionRefresh() indicates that this is safe (true for the
     * default mutator and ApproovDefaultMessageSigning, false for custom
     * mutators unless they opt in). The period should be comfortably less than the
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
     * @param mutator is the ApproovServiceMutator with callback handlers that may
     *                override the default behavior of the ApproovService singleton.
     *                Passing null to this method reinstates the out-of-the-box
     *                mutator from createDefaultServiceMutator(), including default
     *                message signing; pass ApproovServiceMutator.DEFAULT to keep
     *                the standard decisions without message signing.
     */
    public static synchronized void setServiceMutator(ApproovServiceMutator mutator) {
        if (mutator == null) {
            mutator = createDefaultServiceMutator();
        }
        Log.d(TAG, "Applied ApproovServiceMutator:" + mutator.toString());
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
        if (isInitialized) {
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
    }

    /**
     * Removes a header previously added using addSubstitutionHeader.
     *
     * @param header is the header to be removed for substitution
     */
    public static synchronized void removeSubstitutionHeader(String header) {
        if (isInitialized) {
            Log.d(TAG, "removeSubstitutionHeader " + header);
            String existingKey = findSubstitutionHeaderKey(header);
            if (existingKey != null)
                substitutionHeaders.remove(existingKey);
        }
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
        if (!isInitialized) {
            throw new IllegalStateException("ApproovService is not initialized");
        }
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
     * @param key is the query parameter key name to be added for substitution
     */
    public static synchronized void addSubstitutionQueryParam(String key) {
        if (isInitialized) {
            Log.d(TAG, "addSubstitutionQueryParam " + key);
            try {
                Pattern pattern = Pattern.compile("[\\?&]" + key + "=([^&;]+)");
                substitutionQueryParams.put(key, pattern);
            } catch (PatternSyntaxException e) {
                Log.e(TAG, "addSubstitutionQueryParam " + key + " error: " + e.getMessage());
            }
        }
    }

    /**
     * Removes a query parameter key name previously added using
     * addSubstitutionQueryParam.
     *
     * @param key is the query parameter key name to be removed for substitution
     */
    public static synchronized void removeSubstitutionQueryParam(String key) {
        if (isInitialized) {
            Log.d(TAG, "removeSubstitutionQueryParam " + key);
            substitutionQueryParams.remove(key);
        }
    }

    /**
     * Gets the map of substitution query parameters.
     *
     * @return a map of query parameters to be substituted, mapped to the compiled
     *         Pattern
     */
    public static synchronized Map<String, Pattern> getSubstitutionQueryParams() {
        if (!isInitialized) {
            throw new IllegalStateException("ApproovService is not initialized");
        }
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
        if (isInitialized) {
            try {
                Pattern pattern = Pattern.compile(urlRegex);
                exclusionURLRegexs.put(urlRegex, pattern);
                Log.d(TAG, "addExclusionURLRegex " + urlRegex);
            } catch (PatternSyntaxException e) {
                Log.e(TAG, "addExclusionURLRegex " + urlRegex + " error: " + e.getMessage());
            }
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
        if (isInitialized) {
            Log.d(TAG, "removeExclusionURLRegex " + urlRegex);
            exclusionURLRegexs.remove(urlRegex);
        }
    }

    /**
     * Gets a copy of the current exclusion URL regexs.
     *
     * @return Map<String, Pattern> of the exclusion regexs to their respective
     *         Patterns
     */
    public static synchronized Map<String, Pattern> getExclusionURLRegexs() {
        if (!isInitialized) {
            throw new IllegalStateException("ApproovService is not initialized");
        }
        return new HashMap<>(exclusionURLRegexs);
    }

    /**
     * Allows an Approov fetch operation to be performed as early as possible. This
     * permits a token or secure strings to be available while an application might
     * be loading resources or is awaiting user input. Since the initial fetch is
     * the
     * most expensive the prefetch can hide the most latency.
     *
     * @deprecated This method is now automatically called when the service is
     *             initialized.
     */
    @Deprecated
    public static synchronized void prefetch() {
        if (isApproovEnabled())
            // fire and forget the prefetch
            Approov.fetchApproovToken(new PrefetchCallbackHandler(), "approov.io");
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
     * @throws ApproovException if there was a problem
     */
    public static void precheck() throws ApproovException {
        if (!isApproovEnabled()) {
            Log.e(TAG, "precheck: SDK not initialized");
            throw new ApproovException("precheck: SDK not initialized");
        }
        // try and fetch a non-existent secure string in order to check for a rejection
        Approov.TokenFetchResult approovResults;
        try {
            approovResults = Approov.fetchSecureStringAndWait("precheck-dummy-key", null);
            recordLastARC(approovResults);
            Log.d(TAG, "precheck: " + approovResults.getStatus().toString());
        } catch (IllegalStateException e) {
            throw new ApproovException(e);
        } catch (IllegalArgumentException e) {
            throw new ApproovException(e);
        }

        // process the returned Approov status using decision maker
        getServiceMutator().handlePrecheckResult(approovResults);
    }

    /**
     * Gets the device ID used by Approov to identify the particular device that the
     * SDK is running on. Note
     * that different Approov apps on the same device will return a different ID.
     * Moreover, the ID may be
     * changed by an uninstall and reinstall of the app.
     *
     * @return String of the device ID
     * @throws ApproovException if there was a problem
     */
    public static String getDeviceID() throws ApproovException {
        if (!isApproovEnabled()) {
            Log.e(TAG, "getDeviceID: SDK not initialized");
            throw new ApproovException("getDeviceID: SDK not initialized");
        }
        try {
            String deviceID = Approov.getDeviceID();
            Log.d(TAG, "getDeviceID: " + deviceID);
            return deviceID;
        } catch (IllegalStateException e) {
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
     * @throws ApproovException if there was a problem
     */
    public static void setDataHashInToken(String data) throws ApproovException {
        if (!isApproovEnabled()) {
            Log.e(TAG, "setDataHashInToken: SDK not initialized");
            throw new ApproovException("setDataHashInToken: SDK not initialized");
        }
        try {
            Approov.setDataHashInToken(data);
            Log.d(TAG, "setDataHashInToken");
        } catch (IllegalStateException e) {
            throw new ApproovException(e);
        } catch (IllegalArgumentException e) {
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
     * @throws ApproovException if there was a problem
     */
    public static String fetchToken(String url) throws ApproovException {
        if (!isApproovEnabled()) {
            Log.e(TAG, "fetchToken: SDK not initialized");
            throw new ApproovException("fetchToken: SDK not initialized");
        }
        // fetch the Approov token
        Approov.TokenFetchResult approovResults;
        try {
            approovResults = Approov.fetchApproovTokenAndWait(url);
            recordLastARC(approovResults);
            Log.d(TAG, "fetchToken: " + approovResults.getStatus().toString());
        } catch (IllegalStateException e) {
            throw new ApproovException(e);
        } catch (IllegalArgumentException e) {
            throw new ApproovException(e);
        }

        // process the status using decision maker
        getServiceMutator().handleFetchTokenResult(approovResults);
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
     * @throws ApproovException if there was a problem
     */
    @Deprecated
    public static String getMessageSignature(String message) throws ApproovException {
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
     * @throws ApproovException if there was a problem
     */
    public static String getAccountMessageSignature(String message) throws ApproovException {
        if (!isApproovEnabled()) {
            Log.e(TAG, "getAccountMessageSignature: SDK not initialized");
            throw new ApproovException("getAccountMessageSignature: SDK not initialized");
        }
        try {
            String signature = Approov.getAccountMessageSignature(message);
            Log.d(TAG, "getAccountMessageSignature");
            if (signature == null)
                throw new ApproovException("no account signature available");
            return signature;
        } catch (IllegalStateException e) {
            throw new ApproovException(e);
        } catch (IllegalArgumentException e) {
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
     * @throws ApproovException if there was a problem
     */
    public static String getInstallMessageSignature(String message) throws ApproovException {
        if (!isApproovEnabled()) {
            Log.e(TAG, "getInstallMessageSignature: SDK not initialized");
            throw new ApproovException("getInstallMessageSignature: SDK not initialized");
        }
        try {
            String signature = Approov.getInstallMessageSignature(message);
            Log.d(TAG, "getInstallMessageSignature");
            if (signature == null)
                throw new ApproovException("no device signature available");
            return signature;
        } catch (IllegalStateException e) {
            throw new ApproovException(e);
        } catch (IllegalArgumentException e) {
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
     * @throws ApproovException if there was a problem
     */
    public static String fetchSecureString(String key, String newDef) throws ApproovException {
        if (!isApproovEnabled()) {
            Log.e(TAG, "fetchSecureString: SDK not initialized");
            throw new ApproovException("fetchSecureString: SDK not initialized");
        }
        // determine the type of operation as the values themselves cannot be logged
        String type = "lookup";
        if (newDef != null)
            type = "definition";

        // fetch any secure string keyed by the value, catching any exceptions the SDK
        // might throw
        Approov.TokenFetchResult approovResults;
        try {
            approovResults = Approov.fetchSecureStringAndWait(key, newDef);
            recordLastARC(approovResults);
            Log.d(TAG, "fetchSecureString " + type + ": " + key + ", " + approovResults.getStatus().toString());
        } catch (IllegalStateException e) {
            throw new ApproovException(e);
        } catch (IllegalArgumentException e) {
            throw new ApproovException(e);
        }

        // process the returned Approov status using decision maker
        getServiceMutator().handleFetchSecureStringResult(approovResults, type, key);
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
     * @throws ApproovException if there was a problem
     */
    public static String fetchCustomJWT(String payload) throws ApproovException {
        if (!isApproovEnabled()) {
            Log.e(TAG, "fetchCustomJWT: SDK not initialized");
            throw new ApproovException("fetchCustomJWT: SDK not initialized");
        }
        // fetch the custom JWT catching any exceptions the SDK might throw
        Approov.TokenFetchResult approovResults;
        try {
            approovResults = Approov.fetchCustomJWTAndWait(payload);
            recordLastARC(approovResults);
            Log.d(TAG, "fetchCustomJWT: " + approovResults.getStatus().toString());
        } catch (IllegalStateException e) {
            throw new ApproovException(e);
        } catch (IllegalArgumentException e) {
            throw new ApproovException(e);
        }

        // process the returned Approov status using decision maker
        getServiceMutator().handleFetchCustomJWTResult(approovResults);
        return approovResults.getToken();
    }

    /**
     * Gets the Attestation Response Code (ARC) from the most recent token, secure
     * string or custom JWT fetch performed by this layer, whether from the
     * interceptor or from a direct method. Returns an empty string if no fetch has
     * been made since initialization, if the last fetch produced no ARC, or if ARC
     * is not enabled for the account. This performs no network activity: it reports
     * the result the app already received, so it can be read after a rejected
     * request to correlate with the backend's view.
     *
     * @return the ARC of the most recent fetch, or an empty string
     */
    public static synchronized String getLastARC() {
        if (!isApproovEnabled()) {
            Log.e(TAG, "getLastARC: SDK not initialized");
            return "";
        }
        return lastARC;
    }

    /**
     * Records the ARC carried by a fetch result as the most recent one, for
     * getLastARC. An empty or null ARC clears the previous value, matching the SDK
     * semantics of the ARC belonging to the last fetch.
     *
     * @param approovResults the fetch result just obtained
     */
    static synchronized void recordLastARC(Approov.TokenFetchResult approovResults) {
        String arc = (approovResults != null) ? approovResults.getARC() : null;
        lastARC = (arc != null) ? arc : "";
    }

    /**
     * Sets an install attributes token to be sent to the server and associated with
     * this particular
     * app installation for future Approov token fetches. The token must be signed,
     * within its
     * expiry time and bound to the correct device ID for it to be accepted by the
     * server.
     * Calling this method ensures that the next call to fetch an Approov
     * token will not use a cached version, so that this information can be
     * transmitted to the server.
     *
     * @param attrs is the signed JWT holding the new install attributes
     * @return void
     * @throws ApproovException if the attrs parameter is invalid or the SDK is not
     *                          initialized
     */
    public static void setInstallAttrsInToken(String attrs) throws ApproovException {
        if (!isApproovEnabled()) {
            Log.e(TAG, "setInstallAttrsInToken: SDK not initialized");
            throw new ApproovException("setInstallAttrsInToken: SDK not initialized");
        }
        try {
            Approov.setInstallAttrsInToken(attrs);
            Log.d(TAG, "setInstallAttrsInToken");
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "setInstallAttrsInToken failed with IllegalArgument: " + e.getMessage());
            throw new ApproovException(e);
        } catch (IllegalStateException e) {
            Log.e(TAG, "setInstallAttrsInToken failed with IllegalState: " + e.getMessage());
            throw new ApproovException(e);
        }
    }

    /**
     * Rebuilds the pins in the pinning interceptor after a dynamic configuration
     * change.
     */
    static synchronized void rebuildPins() {
        if (pinningInterceptor != null)
            pinningInterceptor.buildPins();
    }

    /**
     * Gets the current CertificatePinner.
     *
     * @return the current CertificatePinner
     */
    @VisibleForTesting
    static synchronized CertificatePinner getCertificatePinner() {
        if (pinningInterceptor != null)
            return pinningInterceptor.getCertificatePinner();
        return new CertificatePinner.Builder().build();
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
        if (!isInitialized) {
            Log.e(TAG, "getOkHttpClient: SDK not initialized");
            throw new IllegalStateException("getOkHttpClient: SDK not initialized");
        }
        OkHttpClient okHttpClient = okHttpClients.get(builderName);
        if (okHttpClient == null) {
            // get the builder and warn if none was available
            OkHttpClient.Builder okHttpBuilder = okHttpBuilders.get(builderName);
            if (okHttpBuilder == null) {
                Log.d(TAG, "No builder available for " + builderName);
                okHttpBuilder = new OkHttpClient.Builder();
            }
            // build a new OkHttpClient on demand
            if (isApproovEnabled()) {
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

                // build the OkHttpClient with the interceptors
                Log.d(TAG, "Building new Approov OkHttpClient for " + builderName);
                ApproovTokenInterceptor tokenInterceptor = new ApproovTokenInterceptor();
                okHttpClient = okHttpBuilder
                        .addInterceptor(tokenInterceptor)
                        .addNetworkInterceptor(new ApproovFreshnessInterceptor())
                        .addNetworkInterceptor(pinningInterceptor).build();
            } else {
                // if the ApproovService was not initialized or Approov is bypassed, build a
                // plain client
                Log.d(TAG, "Building plain OkHttpClient for " + builderName);
                okHttpClient = okHttpBuilder.build();
            }
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

/**
 * Callback handler for prefetching. We simply log as we don't need the result
 * itself, as it will be returned as a cached value on a subsequent fetch.
 */
final class PrefetchCallbackHandler implements Approov.TokenFetchCallback {
    // logging tag
    private static final String TAG = "ApproovPrefetch";

    @Override
    public void approovCallback(Approov.TokenFetchResult result) {
        ApproovService.recordLastARC(result);
        if ((result.getStatus() == Approov.TokenFetchStatus.SUCCESS) ||
                (result.getStatus() == Approov.TokenFetchStatus.UNKNOWN_URL))
            Log.d(TAG, "Prefetch success");
        else
            Log.e(TAG, "Prefetch failure: " + result.getStatus().toString());
    }
}

// interceptor to add Approov tokens or substitute headers and query parameters
class ApproovTokenInterceptor implements Interceptor {
    // logging tag
    private final static String TAG = "ApproovTokenInterceptor";

    /**
     * Constructs a new interceptor that adds Approov tokens and substitutes headers
     * or query parameters. The request always proceeds: if a token cannot be
     * obtained the request is sent with an empty token header, and if a secure
     * string substitution fails the placeholder value is left in place. The
     * Approov fetch status is reported on the status header for every processed
     * request.
     */
    public ApproovTokenInterceptor() {
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        // cache the mutator for the duration of the interceptor to make sure
        // it is not changed mid-flight
        ApproovServiceMutator mutator = ApproovService.getServiceMutator();
        return chain.proceed(applyProtection(chain.request(), mutator, true));
    }

    /**
     * Applies Approov protection to a request: decides whether the request is
     * processed at all, fetches a token for its URL, adds the token, trace and
     * status headers, performs secure string substitutions and invokes the
     * mutator's processed request callback. The returned request carries an
     * ApproovRequestFreshness marker describing exactly the protection applied
     * to it, so that the network layer can strip and reapply that protection on
     * a stale attempt or on a redirect followup. A request that is not processed
     * (excluded, or to a domain Approov does not protect) is returned unchanged
     * with no marker.
     *
     * @param request         the request to protect, carrying no Approov protection
     * @param mutator         the service mutator to consult
     * @param invokeProcessed whether the mutator's processed request callback may
     *                        be invoked (false when reapplying protection under a
     *                        mutator that does not support refresh)
     * @return the protected request
     * @throws IOException if the mutator opts in to aborting the request
     */
    static Request applyProtection(Request request, ApproovServiceMutator mutator, boolean invokeProcessed)
            throws IOException {
        // first check if we are to proceed with any Approov processing
        if (!mutator.handleInterceptorShouldProcessRequest(request)) {
            // we are not to proceed with any Approov processing so just continue
            return request;
        }

        // update the data hash based on any token binding header (presence is optional)
        ApproovService.updateBindingDataHash(request);

        HttpUrl url = request.url();

        // request an Approov token for the request URL
        Approov.TokenFetchResult approovResults = Approov.fetchApproovTokenAndWait(url.toString());
        ApproovService.recordLastARC(approovResults);

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
        if (mutator.handleInterceptorFetchTokenResult(approovResults, url.toString())) {
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
                approovResults = Approov.fetchSecureStringAndWait(value.substring(prefix.length()), null);
                ApproovService.recordLastARC(approovResults);
                Log.d(TAG, "Substituting header: " + header + ", " + approovResults.getStatus().toString());
                // a failed substitution leaves the placeholder value in the header and the
                // request proceeds: the backend sees the placeholder and decides
                if (mutator.handleInterceptorHeaderSubstitutionResult(approovResults, header)) {
                    aChange = true;
                    setSubstitutionHeaders.put(header, prefix + approovResults.getSecureString());
                    // every field of the name, in order, so that all can be restored
                    originalHeaderValues.put(header, new ArrayList<>(request.headers(header)));
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
            Matcher matcher = pattern.matcher(replacementURL);
            if (matcher.find()) {
                // we have found an occurrence of the query parameter to be replaced so we look
                // up the existing value as a key for a secure string
                String queryValue = matcher.group(1);
                approovResults = Approov.fetchSecureStringAndWait(queryValue, null);
                ApproovService.recordLastARC(approovResults);
                Log.d(TAG, "Substituting query parameter: " + queryKey + ", " + approovResults.getStatus().toString());
                if (mutator.handleInterceptorQueryParamSubstitutionResult(approovResults, queryKey)) {
                    // substitute the query parameter
                    aChange = true;
                    queryKeys.add(queryKey);
                    replacementURL = new StringBuilder(replacementURL).replace(matcher.start(1),
                            matcher.end(1), approovResults.getSecureString()).toString();
                }
            }
        }

        // gather the request changes applied to the request
        ApproovRequestMutations changes = new ApproovRequestMutations();
        // apply all the changes to the request
        ApproovRequestFreshness freshness = null;
        if (aChange) {
            Request.Builder builder = request.newBuilder();
            if (setTokenHeaderKey != null) {
                builder.header(setTokenHeaderKey, setTokenHeaderValue);
                changes.setTokenHeaderKey(setTokenHeaderKey);
                changes.setTokenHeaderPrefix(setTokenHeaderPrefix);

                // tag the request so that the freshness interceptor can determine at the
                // network layer whether the protection was applied too long ago and must
                // be refreshed before transmission, or whether the request was redirected
                freshness = new ApproovRequestFreshness(url.toString(), changes);
                builder.tag(ApproovRequestFreshness.class, freshness);
            }
            if (setTraceIDHeaderKey != null) {
                builder.header(setTraceIDHeaderKey, setTraceIDHeaderValue);
                changes.setTraceIDHeaderKey(setTraceIDHeaderKey);
            }
            // report the fetch status on the status header, replacing any value the app
            // may have set itself so that the backend only sees what this layer observed
            if (setStatusHeaderKey != null) {
                builder.header(setStatusHeaderKey, setStatusHeaderValue);
                changes.setStatusHeaderKey(setStatusHeaderKey);
            }
            if (!setSubstitutionHeaders.isEmpty()) {
                for (Map.Entry<String, String> entry : setSubstitutionHeaders.entrySet()) {
                    // substitute the header
                    builder.header(entry.getKey(), entry.getValue());
                }
                changes.setSubstitutionHeaderKeys(new ArrayList<>(setSubstitutionHeaders.keySet()));
            }
            if (!originalURL.equals(replacementURL)) {
                builder.url(replacementURL);
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
        if (invokeProcessed)
            processedRequest = mutator.handleInterceptorProcessedRequest(request, changes);
        else
            Log.d(TAG, "Protection reapplied without the processed request callback of " + mutator);

        // record the time at which the protection was applied, the URL it was applied
        // to, and the names of any headers added by the processed request callback
        // (normally message signature headers), so that the freshness interceptor can
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
// it runs immediately before transmission for every attempt, including OkHttp
// generated retries and redirect followups which do not pass through the
// application layer ApproovTokenInterceptor again.
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
        boolean redirected = ((appliedURL != null) && !request.url().toString().equals(appliedURL))
                || ((appliedMethod != null) && !request.method().equals(appliedMethod))
                || ((appliedHeaders != null) && !request.headers().equals(appliedHeaders));

        ApproovServiceMutator mutator;
        boolean invokeProcessed;
        if (redirected) {
            Log.d(TAG, "Request rebuilt since protection was applied to " + appliedURL +
                    " (now " + request.method() + " " + request.url() + "), reapplying Approov protection");
            // cache the mutator for the duration of the interceptor to make sure it is
            // not changed mid-flight; a mutator that does not support refresh has its
            // headers stripped (they must not leak to the new destination) but its
            // processed request callback is not invoked again
            mutator = ApproovService.getServiceMutator();
            invokeProcessed = mutator.supportsProtectionRefresh();
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
            if (!mutator.supportsProtectionRefresh()) {
                Log.d(TAG, "Request held for " + heldMS + "ms but " + mutator +
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
        Request stripped = ApproovTokenInterceptor.stripProtection(request, freshness, !redirected);
        Request refreshed = ApproovTokenInterceptor.applyProtection(stripped, mutator, invokeProcessed);
        // the refreshed request is already at the network layer, so its header baseline
        // is what it carries now
        ApproovRequestFreshness refreshedMarker = refreshed.tag(ApproovRequestFreshness.class);
        if (refreshedMarker != null)
            refreshedMarker.setAppliedHeaders(refreshed.headers());
        return chain.proceed(refreshed);
    }
}

// interceptor to implement pinning on network connections
class ApproovPinningInterceptor implements Interceptor {
    // logging tag
    private final static String TAG = "ApproovPinningInterceptor";

    // maximum number of elements that may be held in the handshake cache to allow
    // caching
    // of different concurrent connections but without causing a significant memory
    // leak
    private final static int maxCachedHandshakes = 10;

    // the certificate pinner to use for pinning that may be rebuilt if there is a
    // change
    // in the pinning configuration
    private CertificatePinner certificatePinner;

    // set of TLS handshakes that are known to be valid constrained to a size of
    // maxCachedHandshakes
    // to prevent a memory leak for long running apps
    private LinkedHashSet<Handshake> knownValidHandshakes;

    // incremented on every rebuild of the pins so that a handshake checked against an
    // earlier generation of pins is never cached as valid for the current one
    private long pinGeneration = 0;

    /**
     * Construct a new pinning interceptor.
     */
    public ApproovPinningInterceptor() {
        knownValidHandshakes = new LinkedHashSet<>();
        buildPins();
    }

    /**
     * Rebuild the pinning configuration. This is called when the dynamic
     * configuration
     * changes and we need to update the pinning information. This forces all known
     * valid
     * handshakes to be cleared.
     */
    synchronized public void buildPins() {
        CertificatePinner.Builder pinBuilder = new CertificatePinner.Builder();
        Map<String, List<String>> allPins = Approov.getPins("public-key-sha256");
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
        certificatePinner = pinBuilder.build();
        knownValidHandshakes.clear();
        pinGeneration++;
    }

    /**
     * Gets the current pin generation, which changes on every rebuild of the pins.
     *
     * @return the pin generation
     */
    synchronized long getPinGeneration() {
        return pinGeneration;
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

    /**
     * Determines if the given handshake is known to be valid, supporting different
     * TLS
     * negotiations on different domains as required.
     *
     * @param handshake ot be checked
     * @return true if the handshake is known valid, false otherwise
     */
    synchronized private boolean isValidHandshake(Handshake handshake) {
        return knownValidHandshakes.contains(handshake);
    }

    /**
     * Adds a valid handshake to the cached set, clearing the cache if that would
     * exceed
     * the maximum size.
     *
     * @param handshake to be added as known valid
     */
    synchronized private void addValidHandshake(Handshake handshake, long generation) {
        // a verdict reached against an earlier generation of pins is not cached: the
        // pins were rebuilt while this handshake was being checked
        if (generation != pinGeneration) {
            Log.d(TAG, "Pins rebuilt during check, handshake verdict not cached");
            return;
        }
        while (knownValidHandshakes.size() >= maxCachedHandshakes) {
            Iterator<Handshake> it = knownValidHandshakes.iterator();
            if (it.hasNext()) { // can't really fail, but this keeps it safe
                it.next();
                it.remove();
            }
        }
        knownValidHandshakes.add(handshake);
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        // first check if we are to proceed with any pinning processing
        if (!ApproovService.getServiceMutator().handlePinningShouldProcessRequest(request)) {
            // we are not to proceed with any pinning processing so just continue
            return chain.proceed(request);
        }

        // the pins may not have been available when this interceptor was constructed
        // (the SDK only holds pins once a token has been fetched for the app
        // installation) so build them now if there are still none
        if (getCertificatePinner().getPins().isEmpty())
            buildPins();

        // the generation is read before the pinner so that a rebuild between the two
        // reads, or between the check and the caching of the verdict, is detected and
        // the verdict is not cached against the new pins
        long generation = getPinGeneration();

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
        if (!isValidHandshake(handshake)) {
            // if we haven't seen this handshake and pins combination before then we
            // need to check it
            List<Certificate> certs = handshake.peerCertificates();
            try {
                getCertificatePinner().check(host, certs);
            } catch (SSLPeerUnverifiedException e) {
                // if a certificate pinning error is detected then close the socket to force
                // the next request to redo the TLS negotiation
                Log.d(TAG, "Pinning failure: " + e.toString());
                connection.socket().close();
                throw e;
            }

            // pins were valid for the handshake so cache it, unless the pins changed
            addValidHandshake(handshake, generation);
            Log.d(TAG, "Valid pinning for: " + handshake.toString());
        }
        return chain.proceed(chain.request());
    }
}
