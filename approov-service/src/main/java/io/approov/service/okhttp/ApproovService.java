//
// MIT License
// 
// Copyright (c) 2016-present, Critical Blue Ltd.
//
// Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files
// (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge,
// publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so,
// subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
// 
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING WITHOUT LIMITATION THE WARRANTIES OF
// MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
// ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH
// THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

package io.approov.service.okhttp;

import android.util.Log;
import android.content.Context;

import com.criticalblue.approovsdk.Approov;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import okhttp3.CertificatePinner;
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

    // default prefix to be added before the Approov token by default
    private static final String APPROOV_TOKEN_PREFIX = "";

    // name for the default builder
    private static final String DEFAULT_BUILDER_NAME = "_default";

    // true if the Approov SDK initialized okay
    private static boolean isInitialized = false;

    // true if the interceptor should proceed on network failures and not add an
    // Approov token
    private static boolean proceedOnNetworkFail = false;

    // builders to be used for new OkHttp clients where each can be named
    private static Map<String, OkHttpClient.Builder> okHttpBuilders = null;

    // cached OkHttpClients to use for each of the named builders
    private static Map<String, OkHttpClient> okHttpClients = null;

    // header to be used to send Approov tokens
    private static String approovTokenHeader = null;

    // any prefix String to be added before the transmitted Approov token
    private static String approovTokenPrefix = null;

    // any header to be used for binding in Approov tokens or null if not set
    private static String bindingHeader = null;

    // An optional property to receive callbacks during the processing of a request. Added to
    // support message signing in a general way, the callbacks give an opportunity for apps to
    // customise behaviour at specific points in the attestation flow.
    // Defaults to null - no callback.
    private static ApproovInterceptorExtensions interceptorExtensions = null;

    // map of headers that should have their values substituted for secure strings, mapped to their
    // required prefixes
    private static Map<String, String> substitutionHeaders = null;

    // set of query parameters that may be substituted, specified by the key name
    private static Set<String> substitutionQueryParams = null;

    // set of URL regexs that should be excluded from any Approov protection, mapped to the compiled Pattern
    private static Map<String, Pattern> exclusionURLRegexs = null;

    /**
     * Construction is disallowed as this is a static only class.
     */
    private ApproovService() {
    }

    /**
     * Initializes the ApproovService with an account configuration.
     *
     * @param context the Application context
     * @param config the configuration string, or empty for no SDK initialization
     */
    public static void initialize(Context context, String config) {
        // setup for creating clients
        isInitialized = false;
        proceedOnNetworkFail = false;
        okHttpBuilders = new HashMap<>();
        okHttpBuilders.put(DEFAULT_BUILDER_NAME, new OkHttpClient.Builder());
        okHttpClients =  new HashMap<>();
        approovTokenHeader = APPROOV_TOKEN_HEADER;
        approovTokenPrefix = APPROOV_TOKEN_PREFIX;
        bindingHeader = null;
        substitutionHeaders = new HashMap<>();
        substitutionQueryParams = new HashSet<>();
        exclusionURLRegexs = new HashMap<>();

        // initialize the Approov SDK
        try {
            if (config.length() != 0)
                Approov.initialize(context, config, "auto", "init-fetch");
            Approov.setUserProperty("approov-service-okhttp");
            isInitialized = true;
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Approov initialization failed: " + e.getMessage());
        }
    }

    /**
     * Initializes the ApproovService with an account configuration and comment.
     *
     * @param context the Application context
     * @param config the configuration string, or empty for no SDK initialization
     * @param comment the comment string, or empty for no comment
     */
    public static void initialize(Context context, String config, String comment) {
        // initialize the Approov SDK
        try {
            ApproovService.initialize(context, config);
            if ((comment != null) && (comment.length() != 0)) {
                Approov.initialize(context, config, "auto", comment);
            }
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Approov initialization failed: " + e.getMessage());
        } catch (IllegalStateException e) {
            Log.e(TAG, "Approov initialization failed: " + e.getMessage());
        }
    }

    /**
     * Sets a flag indicating if the network interceptor should proceed anyway if it is
     * not possible to obtain an Approov token due to a networking failure. If this is set
     * then your backend API can receive calls without the expected Approov token header
     * being added, or without header/query parameter substitutions being made. Note that
     * this should be used with caution because it may allow a connection to be established
     * before any dynamic pins have been received via Approov, thus potentially opening the
     * channel to a MitM.
     *
     * @param proceed is true if Approov networking fails should allow continuation
     */
    public static synchronized void setProceedOnNetworkFail(boolean proceed) {
        Log.d(TAG, "setProceedOnNetworkFail " + proceed);
        proceedOnNetworkFail = proceed;
        okHttpClients.clear();
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
     * @throws ApproovException if there was a problem
     */
    public static synchronized void setDevKey(String devKey) throws ApproovException {
        try {
            Approov.setDevKey(devKey);
            Log.d(TAG, "setDevKey");
        }
        catch (IllegalStateException e) {
            throw new ApproovException("IllegalState: " + e.getMessage());
        }
        catch (IllegalArgumentException e) {
            throw new ApproovException("IllegalArgument: " + e.getMessage());
        }
    }

    /**
     * Sets the header that the Approov token is added on, as well as an optional
     * prefix String (such as "Bearer "). By default the token is provided on
     * "Approov-Token" with no prefix.
     *
     * @param header is the header to place the Approov token on
     * @param prefix is any prefix String for the Approov token header
     */
    public static synchronized void setApproovHeader(String header, String prefix) {
        Log.d(TAG, "setApproovHeader " + header + ", " + prefix);
        approovTokenHeader = header;
        approovTokenPrefix = prefix;
        okHttpClients.clear();
    }

    /**
     * Sets a binding header that must be present on all requests using the Approov service. A
     * header should be chosen whose value is unchanging for most requests (such as an
     * Authorization header). A hash of the header value is included in the issued Approov tokens
     * to bind them to the value. This may then be verified by the backend API integration. This
     * method should typically only be called once.
     *
     * @param header is the header to use for Approov token binding
     */
    public static synchronized void setBindingHeader(String header) {
        Log.d(TAG, "setBindingHeader " + header);
        bindingHeader = header;
        okHttpClients.clear();
    }

    /**
     * Sets the interceptor extensions callback handler. This facility was introduced to support
     * message signing that is independent from the rest of the attestation flow. The default
     * ApproovService layer issues no callbacks, provide a non-null ApproovLifecycleCallbackHandler
     * handler to add functionality to the attestation flow.
     *
     * @param callbacks is the configuration used to control message signing. The behaviour of the
     *              provided configuration must remain constant while in use by the ApproovService.
     *              Passing null to this method will disable message signing.
     */
    public static synchronized void setApproovInterceptorExtensions(ApproovInterceptorExtensions callbacks) {
        if (callbacks == null) {
            Log.d(TAG, "Interceptor extension disabled");
        } else {
            Log.d(TAG, "Interceptor extension enabled");
        }
        interceptorExtensions = callbacks;
        okHttpClients.clear();
    }

    /**
     * Adds the name of a header which should be subject to secure strings substitution. This
     * means that if the header is present then the value will be used as a key to look up a
     * secure string value which will be substituted into the header value instead. This allows
     * easy migration to the use of secure strings. Note that this function should be called on initialization
     * rather than for every request as it will require a new OkHttpClient to be built. A required
     * prefix may be specified to deal with cases such as the use of "Bearer " prefixed before values
     * in an authorization header.
     *
     * @param header is the header to be marked for substitution
     * @param requiredPrefix is any required prefix to the value being substituted or null if not required
     */
    public static synchronized void addSubstitutionHeader(String header, String requiredPrefix) {
        if (isInitialized) {
            Log.d(TAG, "addSubstitutionHeader " + header + ", " + requiredPrefix);
            if (requiredPrefix == null)
                substitutionHeaders.put(header, "");
            else
                substitutionHeaders.put(header, requiredPrefix);
            okHttpClients.clear();
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
            substitutionHeaders.remove(header);
            okHttpClients.clear();
        }
    }

    /**
     * Adds a key name for a query parameter that should be subject to secure strings substitution.
     * This means that if the query parameter is present in a URL then the value will be used as a
     * key to look up a secure string value which will be substituted as the query parameter value
     * instead. This allows easy migration to the use of secure strings. Note that this function
     * should be called on initialization rather than for every request as it will require a new
     * OkHttpClient to be built.
     *
     * @param key is the query parameter key name to be added for substitution
     */
    public static synchronized void addSubstitutionQueryParam(String key) {
        if (isInitialized) {
            Log.d(TAG, "addSubstitutionQueryParam " + key);
            substitutionQueryParams.add(key);
            okHttpClients.clear();
        }
    }

    /**
     * Removes a query parameter key name previously added using addSubstitutionQueryParam.
     *
     * @param key is the query parameter key name to be removed for substitution
     */
    public static synchronized void removeSubstitutionQueryParam(String key) {
        if (isInitialized) {
            Log.d(TAG, "removeSubstitutionQueryParam " + key);
            substitutionQueryParams.remove(key);
            okHttpClients.clear();
        }
    }

    /**
     * Adds an exclusion URL regular expression. If a URL for a request matches this regular expression
     * then it will not be subject to any Approov protection. Note that this facility must be used with
     * EXTREME CAUTION due to the impact of dynamic pinning. Pinning may be applied to all domains added
     * using Approov, and updates to the pins are received when an Approov fetch is performed. If you
     * exclude some URLs on domains that are protected with Approov, then these will be protected with
     * Approov pins but without a path to update the pins until a URL is used that is not excluded. Thus
     * you are responsible for ensuring that there is always a possibility of calling a non-excluded
     * URL, or you should make an explicit call to fetchToken if there are persistent pinning failures.
     * Conversely, use of those option may allow a connection to be established before any dynamic pins
     * have been received via Approov, thus potentially opening the channel to a MitM.
     *
     * @param urlRegex is the regular expression that will be compared against URLs to exclude them
     */
    public static synchronized void addExclusionURLRegex(String urlRegex) {
        if (isInitialized) {
            try {
                Pattern pattern = Pattern.compile(urlRegex);
                exclusionURLRegexs.put(urlRegex, pattern);
                okHttpClients.clear();
                Log.d(TAG, "addExclusionURLRegex " + urlRegex);
            } catch (PatternSyntaxException e) {
                Log.e(TAG, "addExclusionURLRegex " + urlRegex + " error: " + e.getMessage());
            }
        }
    }

    /**
     * Removes an exclusion URL regular expression previously added using addExclusionURLRegex.
     *
     * @param urlRegex is the regular expression that will be compared against URLs to exclude them
     */
    public static synchronized void removeExclusionURLRegex(String urlRegex) {
        if (isInitialized) {
            Log.d(TAG, "removeExclusionURLRegex " + urlRegex);
            exclusionURLRegexs.remove(urlRegex);
            okHttpClients.clear();
        }
    }

    /**
     * Prefetches in the background to lower the effective latency of a subsequent token fetch or
     * secure string fetch by starting the operation earlier so the subsequent fetch may be able to
     * use cached data.
     */
    public static synchronized void prefetch() {
        if (isInitialized)
            // fetch an Approov token using a placeholder domain
            Approov.fetchApproovToken(new PrefetchCallbackHandler(), "approov.io");
    }

    /**
     * Performs a precheck to determine if the app will pass attestation. This requires secure
     * strings to be enabled for the account, although no strings need to be set up. This will
     * likely require network access so may take some time to complete. It may throw ApproovException
     * if the precheck fails or if there is some other problem. ApproovRejectionException is thrown
     * if the app has failed Approov checks or ApproovNetworkException for networking issues where a
     * user initiated retry of the operation should be allowed. An ApproovRejectionException may provide
     * additional information about the cause of the rejection.
     *
     * @throws ApproovException if there was a problem
     */
    public static void precheck() throws ApproovException {
        // try and fetch a non-existent secure string in order to check for a rejection
        Approov.TokenFetchResult approovResults;
        try {
            approovResults = Approov.fetchSecureStringAndWait("precheck-dummy-key", null);
            Log.d(TAG, "precheck: " + approovResults.getStatus().toString());
        }
        catch (IllegalStateException e) {
            throw new ApproovException("IllegalState: " + e.getMessage());
        }
        catch (IllegalArgumentException e) {
            throw new ApproovException("IllegalArgument: " + e.getMessage());
        }

        // process the returned Approov status
        if (approovResults.getStatus() == Approov.TokenFetchStatus.REJECTED)
            // if the request is rejected then we provide a special exception with additional information
            throw new ApproovRejectionException("precheck: " + approovResults.getStatus().toString() + ": " +
                    approovResults.getARC() + " " + approovResults.getRejectionReasons(),
                    approovResults.getARC(), approovResults.getRejectionReasons());
        else if ((approovResults.getStatus() == Approov.TokenFetchStatus.NO_NETWORK) ||
                (approovResults.getStatus() == Approov.TokenFetchStatus.POOR_NETWORK) ||
                (approovResults.getStatus() == Approov.TokenFetchStatus.MITM_DETECTED))
            // we are unable to get the secure string due to network conditions so the request can
            // be retried by the user later
            throw new ApproovNetworkException("precheck: " + approovResults.getStatus().toString());
        else if ((approovResults.getStatus() != Approov.TokenFetchStatus.SUCCESS) &&
                (approovResults.getStatus() != Approov.TokenFetchStatus.UNKNOWN_KEY))
            // we are unable to get the secure string due to a more permanent error
            throw new ApproovException("precheck:" + approovResults.getStatus().toString());
    }

    /**
     * Gets the device ID used by Approov to identify the particular device that the SDK is running on. Note
     * that different Approov apps on the same device will return a different ID. Moreover, the ID may be
     * changed by an uninstall and reinstall of the app.
     *
     * @return String of the device ID
     * @throws ApproovException if there was a problem
     */
    public static String getDeviceID() throws ApproovException {
        try {
            String deviceID = Approov.getDeviceID();
            Log.d(TAG, "getDeviceID: " + deviceID);
            return deviceID;
        }
        catch (IllegalStateException e) {
            throw new ApproovException("IllegalState: " + e.getMessage());
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
     * @throws ApproovException if there was a problem
     */
    public static void setDataHashInToken(String data) throws ApproovException {
        try {
            Approov.setDataHashInToken(data);
            Log.d(TAG, "setDataHashInToken");
        }
        catch (IllegalStateException e) {
            throw new ApproovException("IllegalState: " + e.getMessage());
        }
        catch (IllegalArgumentException e) {
            throw new ApproovException("IllegalArgument: " + e.getMessage());
        }
    }

    /**
     * Performs an Approov token fetch for the given URL. This should be used in situations where it
     * is not possible to use the networking interception to add the token. This will
     * likely require network access so may take some time to complete. If the attestation fails
     * for any reason then an ApproovException is thrown. This will be ApproovNetworkException for
     * networking issues where a user initiated retry of the operation should be allowed. Note that
     * the returned token should NEVER be cached by your app, you should call this function when
     * it is needed.
     *
     * @param url is the URL giving the domain for the token fetch
     * @return String of the fetched token
     * @throws ApproovException if there was a problem
     */
    public static String fetchToken(String url) throws ApproovException {
        // fetch the Approov token
        Approov.TokenFetchResult approovResults;
        try {
            approovResults = Approov.fetchApproovTokenAndWait(url);
            Log.d(TAG, "fetchToken: " + approovResults.getStatus().toString());
        }
        catch (IllegalStateException e) {
            throw new ApproovException("IllegalState: " + e.getMessage());
        }
        catch (IllegalArgumentException e) {
            throw new ApproovException("IllegalArgument: " + e.getMessage());
        }

        // process the status
        if ((approovResults.getStatus() == Approov.TokenFetchStatus.NO_NETWORK) ||
                (approovResults.getStatus() == Approov.TokenFetchStatus.POOR_NETWORK) ||
                (approovResults.getStatus() == Approov.TokenFetchStatus.MITM_DETECTED))
            // we are unable to get the token due to network conditions
            throw new ApproovNetworkException("fetchToken: " + approovResults.getStatus().toString());
        else if (approovResults.getStatus() != Approov.TokenFetchStatus.SUCCESS)
            // we are unable to get the token due to a more permanent error
            throw new ApproovException("fetchToken: " + approovResults.getStatus().toString());
        else
            // provide the Approov token result
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
     * @throws ApproovException if there was a problem
     */
    public static String getAccountMessageSignature(String message) throws ApproovException {
        try {
            String signature = Approov.getAccountMessageSignature(message);
            Log.d(TAG, "getAccountMessageSignature");
            if (signature == null)
                throw new ApproovException("no account signature available");
            return signature;
        }
        catch (IllegalStateException e) {
            throw new ApproovException("IllegalState: " + e.getMessage());
        }
        catch (IllegalArgumentException e) {
            throw new ApproovException("IllegalArgument: " + e.getMessage());
        }
    }

    /**
     * Gets the install signature for the given message. This uses a app install specific message
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
     * @return String of the base64 encoded message signature
     * @throws ApproovException if there was a problem
     */
    public static String getInstallMessageSignature(String message) throws ApproovException {
        try {
            String signature = Approov.getInstallMessageSignature(message);
            Log.d(TAG, "getInstallMessageSignature");
            if (signature == null)
                throw new ApproovException("no device signature available");
            return signature;
        }
        catch (IllegalStateException e) {
            throw new ApproovException("IllegalState: " + e.getMessage());
        }
        catch (IllegalArgumentException e) {
            throw new ApproovException("IllegalArgument: " + e.getMessage());
        }
    }

    /**
     * Fetches a secure string with the given key. If newDef is not null then a
     * secure string for the particular app instance may be defined. In this case the
     * new value is returned as the secure string. Use of an empty string for newDef removes
     * the string entry. Note that this call may require network transaction and thus may block
     * for some time, so should not be called from the UI thread. If the attestation fails
     * for any reason then an ApproovException is thrown. This will be ApproovRejectionException
     * if the app has failed Approov checks or ApproovNetworkException for networking issues where
     * a user initiated retry of the operation should be allowed. Note that the returned string
     * should NEVER be cached by your app, you should call this function when it is needed.
     *
     * @param key is the secure string key to be looked up
     * @param newDef is any new definition for the secure string, or null for lookup only
     * @return secure string (should not be cached by your app) or null if it was not defined
     * @throws ApproovException if there was a problem
     */
    public static String fetchSecureString(String key, String newDef) throws ApproovException {
        // determine the type of operation as the values themselves cannot be logged
        String type = "lookup";
        if (newDef != null)
            type = "definition";

        // fetch any secure string keyed by the value, catching any exceptions the SDK might throw
        Approov.TokenFetchResult approovResults;
        try {
            approovResults = Approov.fetchSecureStringAndWait(key, newDef);
            Log.d(TAG, "fetchSecureString " + type + ": " + key + ", " + approovResults.getStatus().toString());
        }
        catch (IllegalStateException e) {
            throw new ApproovException("IllegalState: " + e.getMessage());
        }
        catch (IllegalArgumentException e) {
            throw new ApproovException("IllegalArgument: " + e.getMessage());
        }

        // process the returned Approov status
        if (approovResults.getStatus() == Approov.TokenFetchStatus.REJECTED)
            // if the request is rejected then we provide a special exception with additional information
            throw new ApproovRejectionException("fetchSecureString " + type + " for " + key + ": " +
                    approovResults.getStatus().toString() + ": " + approovResults.getARC() +
                    " " + approovResults.getRejectionReasons(),
                    approovResults.getARC(), approovResults.getRejectionReasons());
        else if ((approovResults.getStatus() == Approov.TokenFetchStatus.NO_NETWORK) ||
                (approovResults.getStatus() == Approov.TokenFetchStatus.POOR_NETWORK) ||
                (approovResults.getStatus() == Approov.TokenFetchStatus.MITM_DETECTED))
            // we are unable to get the secure string due to network conditions so the request can
            // be retried by the user later
            throw new ApproovNetworkException("fetchSecureString " + type + " for " + key + ":" +
                    approovResults.getStatus().toString());
        else if ((approovResults.getStatus() != Approov.TokenFetchStatus.SUCCESS) &&
                (approovResults.getStatus() != Approov.TokenFetchStatus.UNKNOWN_KEY))
            // we are unable to get the secure string due to a more permanent error
            throw new ApproovException("fetchSecureString " + type + " for " + key + ":" +
                    approovResults.getStatus().toString());
        return approovResults.getSecureString();
    }

    /**
     * Fetches a custom JWT with the given payload. Note that this call will require network
     * transaction and thus will block for some time, so should not be called from the UI thread.
     * If the attestation fails for any reason then an IOException is thrown. This will be
     * ApproovRejectionException if the app has failed Approov checks or ApproovNetworkException
     * for networking issues where a user initiated retry of the operation should be allowed.
     *
     * @param payload is the marshaled JSON object for the claims to be included
     * @return custom JWT string
     * @throws ApproovException if there was a problem
     */
    public static String fetchCustomJWT(String payload) throws ApproovException {
        // fetch the custom JWT catching any exceptions the SDK might throw
        Approov.TokenFetchResult approovResults;
        try {
            approovResults = Approov.fetchCustomJWTAndWait(payload);
            Log.d(TAG, "fetchCustomJWT: " + approovResults.getStatus().toString());
        }
        catch (IllegalStateException e) {
            throw new ApproovException("IllegalState: " + e.getMessage());
        }
        catch (IllegalArgumentException e) {
            throw new ApproovException("IllegalArgument: " + e.getMessage());
        }

        // process the returned Approov status
        if (approovResults.getStatus() == Approov.TokenFetchStatus.REJECTED)
            // if the request is rejected then we provide a special exception with additional information
            throw new ApproovRejectionException("fetchCustomJWT: "+ approovResults.getStatus().toString() + ": " +
                    approovResults.getARC() +  " " + approovResults.getRejectionReasons(),
                    approovResults.getARC(), approovResults.getRejectionReasons());
        else if ((approovResults.getStatus() == Approov.TokenFetchStatus.NO_NETWORK) ||
                (approovResults.getStatus() == Approov.TokenFetchStatus.POOR_NETWORK) ||
                (approovResults.getStatus() == Approov.TokenFetchStatus.MITM_DETECTED))
            // we are unable to get the custom JWT due to network conditions so the request can
            // be retried by the user later
            throw new ApproovNetworkException("fetchCustomJWT: " + approovResults.getStatus().toString());
        else if (approovResults.getStatus() != Approov.TokenFetchStatus.SUCCESS)
            // we are unable to get the custom JWT due to a more permanent error
            throw new ApproovException("fetchCustomJWT: " + approovResults.getStatus().toString());
        return approovResults.getToken();
    }

    /**
     * Clears the OkHttp clients if there are some potential pinning changes that require an
     * update.
     */
    public static synchronized void clearOkHttpClient() {
        Log.d(TAG, "OkHttp clients cleared");
        okHttpClients.clear();
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
        Log.d(TAG, "OkHttp client builder set for " + builderName);
        okHttpBuilders.put(builderName, builder);
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
                Log.d(TAG, "No builder available for " + builderName);
                okHttpBuilder = new OkHttpClient.Builder();
            }
            // build a new OkHttpClient on demand
            if (isInitialized) {
                // build the pinning configuration
                CertificatePinner.Builder pinBuilder = new CertificatePinner.Builder();
                Map<String, List<String>> allPins = Approov.getPins("public-key-sha256");
                for (Map.Entry<String, List<String>> entry: allPins.entrySet()) {
                    String domain = entry.getKey();
                    if (!domain.equals("*")) {
                        // the * domain is for managed trust roots and should
                        // not be added directly
                        List<String> pins = entry.getValue();

                        // if there are no pins then we try and use any managed trust roots
                        if (pins.isEmpty() && (allPins.get("*") != null))
                            pins = allPins.get("*");

                        // add the required pins for the domain
                        for (String pin: pins)
                            pinBuilder = pinBuilder.add(domain, "sha256/" + pin);
                    }
                }

                // remove any existing ApproovTokenInterceptor from the builder
                List<Interceptor> interceptors = okHttpBuilder.interceptors();
                Iterator<Interceptor> iter = interceptors.iterator();
                while (iter.hasNext()) {
                    Interceptor interceptor = iter.next();
                    if (interceptor instanceof ApproovTokenInterceptor)
                        iter.remove();
                }

                // build the OkHttpClient with the correct pins preset and ApproovTokenInterceptor
                Log.d(TAG, "Building new Approov OkHttpClient for " + builderName);
                ApproovTokenInterceptor interceptor = new ApproovTokenInterceptor(approovTokenHeader,
                        approovTokenPrefix, bindingHeader, proceedOnNetworkFail, interceptorExtensions,
                        substitutionHeaders, substitutionQueryParams, exclusionURLRegexs);
                okHttpClient = okHttpBuilder.certificatePinner(pinBuilder.build()).addInterceptor(interceptor).build();
            } else {
                // if the ApproovService was not initialized then we can't add Approov capabilities
                Log.e(TAG, "Cannot build Approov OkHttpClient as not initialized");
                okHttpClient = okHttpBuilder.build();
            }
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

/**
 * Callback handler for prefetching. We simply log as we don't need the result
 * itself, as it will be returned as a cached value on a subsequent fetch.
 */
final class PrefetchCallbackHandler implements Approov.TokenFetchCallback {
    // logging tag
    private static final String TAG = "ApproovPrefetch";

    @Override
    public void approovCallback(Approov.TokenFetchResult result) {
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
    private final static String TAG = "ApproovInterceptor";

    // the name of the header to be added to hold the Approov token
    private String approovTokenHeader;

    // prefix to be used for the Approov token
    private String approovTokenPrefix;

    // any binding header for Approov token binding, or null if none
    private String bindingHeader;

    // true if the interceptor should proceed on network failures and not add an Approov token
    private boolean proceedOnNetworkFail;

    // the target for request processing interceptorExtensions
    private ApproovInterceptorExtensions interceptorExtensions;

    // map of headers that should have their values substituted for secure strings, mapped to their
    // required prefixes
    private Map<String, String> substitutionHeaders;

    // set of query parameters that may be substituted, specified by the key name, mapped to their regex patterns
    private Map<String, Pattern> substitutionQueryParams;

    // set of URL regexs that should be excluded from any Approov protection, mapped to the compiled Pattern
    private Map<String, Pattern> exclusionURLRegexs;

    /**
     * Constructs a new interceptor that adds Approov tokens and substitutes headers or query
     * parameters.
     *
     * @param approovTokenHeader is the name of the header to be used for the Approov token
     * @param approovTokenPrefix is the prefix string to be used with the Approov token
     * @param bindingHeader is any token binding header to use or null otherwise
     * @param proceedOnNetworkFail is true the interceptor should proceed on Approov networking failures
     * @param interceptorExtensions the target for request processing interceptorExtensions
     * @param substitutionHeaders is the map of secure string substitution headers mapped to any required prefixes
     * @param substitutionQueryParams is the set of query parameter key names subject to substitution
     * @param exclusionURLRegexs specifies regexs of URLs that should be excluded
     */
    public ApproovTokenInterceptor(String approovTokenHeader, String approovTokenPrefix, String bindingHeader,
                                   boolean proceedOnNetworkFail, ApproovInterceptorExtensions interceptorExtensions,
                                   Map<String, String> substitutionHeaders,
                                   Set<String> substitutionQueryParams, Map<String, Pattern> exclusionURLRegexs) {
        this.approovTokenHeader = approovTokenHeader;
        this.approovTokenPrefix = approovTokenPrefix;
        this.bindingHeader = bindingHeader;
        this.proceedOnNetworkFail = proceedOnNetworkFail;
        this.interceptorExtensions = interceptorExtensions;
        this.substitutionHeaders = new HashMap<>(substitutionHeaders);
        this.substitutionQueryParams = new HashMap<>();
        for (String key: substitutionQueryParams) {
            try {
                Pattern pattern = Pattern.compile("[\\?&]" + key + "=([^&;]+)");
                this.substitutionQueryParams.put(key, pattern);
            }
            catch (PatternSyntaxException e) {
                Log.e(TAG, "addSubstitutionQueryParam " + key + " error: " + e.getMessage());
            }
        }
        this.exclusionURLRegexs = new HashMap<>(exclusionURLRegexs);
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        // check if the URL matches one of the exclusion regexs and just proceed
        ApproovRequestMutations changes = new ApproovRequestMutations();
        Request request = chain.request();
        String url = request.url().toString();
        for (Pattern pattern: exclusionURLRegexs.values()) {
            Matcher matcher = pattern.matcher(url);
            if (matcher.find()) {
                return chain.proceed(request);
            }
        }

        // update the data hash based on any token binding header (presence is optional)
        if ((bindingHeader != null) && request.headers().names().contains(bindingHeader))
            Approov.setDataHashInToken(request.header(bindingHeader));

        // request an Approov token for the domain
        String host = request.url().host();
        Approov.TokenFetchResult approovResults = Approov.fetchApproovTokenAndWait(host);

        // provide information about the obtained token or error (note "approov token -check" can
        // be used to check the validity of the token and if you use token annotations they
        // will appear here to determine why a request is being rejected)
        Log.d(TAG, "Token for " + host + ": " + approovResults.getLoggableToken());

        // force a pinning change if there is any dynamic config update
        if (approovResults.isConfigChanged()) {
            Approov.fetchConfig();
            ApproovService.clearOkHttpClient();
        }

        // we cannot proceed if the pins need to be updated. This will be cleared by using getOkHttpClient
        // but will persist if the app fails to rebuild the OkHttpClient regularly. This might occur
        // on first use after initial app install if the initial network fetch was unable to obtain
        // the dynamic configuration for the account if there was poor network connectivity at that
        // point.
        if (approovResults.isForceApplyPins()) {
            ApproovService.clearOkHttpClient();
            throw new ApproovNetworkException("Pins need to be updated");
        }

        // check the status of Approov token fetch
        boolean aChange = false;
        String setTokenHeaderKey = null;
        String setTokenHeaderValue = null;
        if (approovResults.getStatus() == Approov.TokenFetchStatus.SUCCESS) {
            // we successfully obtained a token so add it to the header for the request
            aChange = true;
            setTokenHeaderKey = approovTokenHeader;
            setTokenHeaderValue = approovTokenPrefix + approovResults.getToken();
        } else if ((approovResults.getStatus() == Approov.TokenFetchStatus.NO_NETWORK) ||
                 (approovResults.getStatus() == Approov.TokenFetchStatus.POOR_NETWORK) ||
                 (approovResults.getStatus() == Approov.TokenFetchStatus.MITM_DETECTED)) {
            // we are unable to get an Approov token due to network conditions so the request can
            // be retried by the user later - unless this is overridden
            if (!proceedOnNetworkFail)
                throw new ApproovNetworkException("Approov token fetch for " + host + ": " + approovResults.getStatus().toString());
        }
        else if ((approovResults.getStatus() != Approov.TokenFetchStatus.NO_APPROOV_SERVICE) &&
                 (approovResults.getStatus() != Approov.TokenFetchStatus.UNKNOWN_URL) &&
                 (approovResults.getStatus() != Approov.TokenFetchStatus.UNPROTECTED_URL))
            // we have failed to get an Approov token with a more serious permanent error
            throw new ApproovException("Approov token fetch for " + host + ": " + approovResults.getStatus().toString());

        // we only continue additional processing if we had a valid status from Approov, to prevent additional delays
        // by trying to fetch from Approov again and this also protects against header substitutions in domains not
        // protected by Approov and therefore potential subject to a MitM
        if ((approovResults.getStatus() != Approov.TokenFetchStatus.SUCCESS) &&
                (approovResults.getStatus() != Approov.TokenFetchStatus.UNPROTECTED_URL))
            // setTokenHeaderKey and setTokenHeaderValue must be null
            return chain.proceed(request);

        // we now deal with any header substitutions, which may require further fetches but these
        // should be using cached results
        Map<String,String> setSubstitutionHeaders = new LinkedHashMap<>(substitutionHeaders.size());
        for (Map.Entry<String, String> entry: substitutionHeaders.entrySet()) {
            String header = entry.getKey();
            String prefix = entry.getValue();
            String value = request.header(header);
            if ((value != null) && value.startsWith(prefix) && (value.length() > prefix.length())) {
                approovResults = Approov.fetchSecureStringAndWait(value.substring(prefix.length()), null);
                Log.d(TAG, "Substituting header: " + header + ", " + approovResults.getStatus().toString());
                if (approovResults.getStatus() == Approov.TokenFetchStatus.SUCCESS) {
                    aChange = true;
                    setSubstitutionHeaders.put(header, prefix + approovResults.getSecureString());
                }
                else if (approovResults.getStatus() == Approov.TokenFetchStatus.REJECTED)
                    // if the request is rejected then we provide a special exception with additional information
                    throw new ApproovRejectionException("Header substitution for " + header + ": " +
                            approovResults.getStatus().toString() + ": " + approovResults.getARC() +
                            " " + approovResults.getRejectionReasons(),
                            approovResults.getARC(), approovResults.getRejectionReasons());
                else if ((approovResults.getStatus() == Approov.TokenFetchStatus.NO_NETWORK) ||
                        (approovResults.getStatus() == Approov.TokenFetchStatus.POOR_NETWORK) ||
                        (approovResults.getStatus() == Approov.TokenFetchStatus.MITM_DETECTED)) {
                    // we are unable to get the secure string due to network conditions so the request can
                    // be retried by the user later - unless this is overridden
                    if (!proceedOnNetworkFail)
                        throw new ApproovNetworkException("Header substitution for " + header + ": " +
                            approovResults.getStatus().toString());
                }
                else if (approovResults.getStatus() != Approov.TokenFetchStatus.UNKNOWN_KEY)
                    // we have failed to get a secure string with a more serious permanent error
                    throw new ApproovException("Header substitution for " + header + ": " +
                            approovResults.getStatus().toString());
            }
        }

        // we now deal with any query parameter substitutions, which may require further fetches but these
        // should be using cached results
        String originalURL = request.url().toString();
        String replacementURL = originalURL;
        List<String> queryKeys = new ArrayList<>(substitutionQueryParams.size());
        for (Map.Entry<String, Pattern> entry: substitutionQueryParams.entrySet()) {
            String queryKey = entry.getKey();
            Pattern pattern = entry.getValue();
            Matcher matcher = pattern.matcher(replacementURL);
            if (matcher.find()) {
                // we have found an occurrence of the query parameter to be replaced so we look up the existing
                // value as a key for a secure string
                String queryValue = matcher.group(1);
                approovResults = Approov.fetchSecureStringAndWait(queryValue, null);
                Log.d(TAG, "Substituting query parameter: " + queryKey + ", " + approovResults.getStatus().toString());
                if (approovResults.getStatus() == Approov.TokenFetchStatus.SUCCESS) {
                    // substitute the query parameter
                    aChange = true;
                    queryKeys.add(queryKey);
                    replacementURL = new StringBuilder(replacementURL).replace(matcher.start(1),
                            matcher.end(1), approovResults.getSecureString()).toString();
                }
                else if (approovResults.getStatus() == Approov.TokenFetchStatus.REJECTED)
                    // if the request is rejected then we provide a special exception with additional information
                    throw new ApproovRejectionException("Query parameter substitution for " + queryKey + ": " +
                            approovResults.getStatus().toString() + ": " + approovResults.getARC() +
                            " " + approovResults.getRejectionReasons(),
                            approovResults.getARC(), approovResults.getRejectionReasons());
                else if ((approovResults.getStatus() == Approov.TokenFetchStatus.NO_NETWORK) ||
                        (approovResults.getStatus() == Approov.TokenFetchStatus.POOR_NETWORK) ||
                        (approovResults.getStatus() == Approov.TokenFetchStatus.MITM_DETECTED)) {
                    // we are unable to get the secure string due to network conditions so the request can
                    // be retried by the user later - unless this is overridden
                    if (!proceedOnNetworkFail)
                        throw new ApproovNetworkException("Query parameter substitution for " + queryKey + ": " +
                            approovResults.getStatus().toString());
                }
                else if (approovResults.getStatus() != Approov.TokenFetchStatus.UNKNOWN_KEY)
                    // we have failed to get a secure string with a more serious permanent error
                    throw new ApproovException("Query parameter substitution for " + queryKey + ": " +
                            approovResults.getStatus().toString());
            }
        }
        // Apply all the changes to the request
        if (aChange) {
            Request.Builder builder = request.newBuilder();
            if (setTokenHeaderKey != null) {
                builder.header(setTokenHeaderKey, setTokenHeaderValue);
                changes.setTokenHeaderKey(setTokenHeaderKey);
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
        }

        // Call the processed request callback
        if (interceptorExtensions != null) {
            request = interceptorExtensions.processedRequest(request, changes);
        }

        // proceed with the rest of the chain
        return chain.proceed(request);
    }
}
