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

import com.criticalblue.approovsdk.Approov;

import java.io.IOException;
import java.security.cert.Certificate;
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

    // default prefix to be added before the Approov token by default
    private static final String APPROOV_TOKEN_PREFIX = "";

    // name for the default builder
    private static final String DEFAULT_BUILDER_NAME = "_default";

    // true if the Approov SDK initialized okay
    private static boolean isInitialized = false;

    // the config string used for initialization
    private static String configString;

    // true if the interceptor should proceed on network failures and not add an
    // Approov token
    private static boolean proceedOnNetworkFail = false;

    // the Approov pinning interceptor to be used for all requests
    private static ApproovPinningInterceptor pinningInterceptor = null;

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

    // The mutator instance used to control ApproovService behavior at key points in the flow.
    // Unless set using the ApproovService.setApproovServiceMutator() method, the default
    // behaviour defined in the default implementation of ApproovServiceMutator will be used.
    private static ApproovServiceMutator serviceMutator = new ApproovServiceMutator() {};

    // map of headers that should have their values substituted for secure strings, mapped to their
    // required prefixes
    private static Map<String, String> substitutionHeaders = null;

    // set of query parameters that may be substituted, specified by the key name and mapped to the compiled Pattern
    private static Map<String, Pattern> substitutionQueryParams = null;

    // set of URL regexs that should be excluded from any Approov protection, mapped to the compiled Pattern
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
     * @param config the configuration string, or empty for no SDK initialization
     * @param comment the comment string, or empty for no comment
     */
    public static synchronized void initialize(Context context, String config, String comment) {
        // check if the Approov SDK is already initialized
        if (isInitialized && !comment.startsWith("reinit")) {
            if (!config.equals(configString)) {
                throw new IllegalStateException("ApproovService layer is already initialized.");
            }
            Log.d(TAG, "Ignoring multiple ApproovService layer initializations with the same config");
        }
        else {
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
            substitutionQueryParams = new HashMap<>();
            exclusionURLRegexs = new HashMap<>();

            // initialize the Approov SDK
            try {
                if (!config.isEmpty())
                    Approov.initialize(context.getApplicationContext(), config, "auto", comment);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Approov initialization failed: " + e.getMessage());
                throw e;
            } catch (IllegalStateException e) {
                Log.e(TAG, "Approov already intialized: Ignoring native layer exception " + e.getMessage());
            }
            pinningInterceptor = new ApproovPinningInterceptor();
            isInitialized = true;
            configString = config;
            Approov.setUserProperty("approov-service-okhttp");
        }
    }

    /**
     * Initializes the ApproovService with an account configuration
     *
     * @param context the Application context
     * @param config the configuration string, or empty for no SDK initialization
     */
    public static void initialize(Context context, String config) {
        // default uses the empty comment string
        initialize(context, config, "");
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
    }

    /**
     * Gets a flag indicating if the network interceptor should proceed anyway if it is
     * not possible to obtain an Approov token due to a networking failure.
     *
     * @return true if Approov networking fails should allow continuation, false otherwise
     */
    public static synchronized boolean getProceedOnNetworkFail() {
        return proceedOnNetworkFail;
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
            throw new ApproovException("IllegalState: " + e.getMessage(), e);
        }
        catch (IllegalArgumentException e) {
            throw new ApproovException("IllegalArgument: " + e.getMessage(), e);
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
    }

    /**
     * Gets the header that is used to add the Approov token.
     *
     * @return String of the header used for the Approov token
     */
    public static synchronized String getApproovTokenHeader() {
        return approovTokenHeader;
    }

    /**
     * Gets the prefix that is added before the Approov token in the header.
     *
     * @return String of the prefix added before the Approov token
     */
    public static synchronized String getApproovTokenPrefix() {
        return approovTokenPrefix;
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
     * Sets the ApproovServiceMutator instance to handle callbacks from the
     * ApproovService implementation. This facility enables customization of
     * ApproovService operations at key points in the configuration and
     * attestation flows. It should reduce the number of times this service
     * layer implementation needs to be forked in order to introduce custom
     * behavior.
     *
     * @param mutator is the ApproovServiceMutator with callback handlers that may
     *                override the default behavior of the ApproovService singleton.
     *                Passing null to this method will reinstate the default
     *                behavior.
     */
    public static synchronized void setServiceMutator(ApproovServiceMutator mutator) {
        if (mutator == null) {
            Log.d(TAG, "Applied default ApproovServiceMutator");
            mutator = new ApproovServiceMutator() {};
        } else {
            Log.d(TAG, "Applied custom ApproovServiceMutator:" + mutator.toString());
        }
        serviceMutator = mutator;
    }

    /**
     * @deprecated Use setApproovServiceMutator instead
     */
    @Deprecated
    public static void setApproovInterceptorExtensions(ApproovServiceMutator mutator) {
        setServiceMutator(mutator);
    }

    /**
     * Gets the active service mutator instance that is handling callbacks from ApproovService.
     *
     * @return the service mutator instance (never null)
     */
    public static synchronized ApproovServiceMutator getServiceMutator() {
        return serviceMutator;
    }

    /**
     * Gets the interceptor extensions callback handlers.
     *
     * @return the interceptor extensions callback handlers or null if none set
     * @deprecated Use getApproovServiceMutator instead
     */
    @Deprecated
    public static ApproovServiceMutator getApproovInterceptorExtensions() {
        return getServiceMutator();
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
        }
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
     * instead. This allows easy migration to the use of secure strings. Note that this function
     * should be called on initialization rather than for every request as it will require a new
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
            }
            catch (PatternSyntaxException e) {
                Log.e(TAG, "addSubstitutionQueryParam " + key + " error: " + e.getMessage());
            }
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
        }
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
        }
    }

    /**
     * Gets a copy of the current exclusion URL regexs.
     *
     * @return Map<String, Pattern> of the exclusion regexs to their respective Patterns
     */
    static synchronized Map<String, Pattern> getExclusionURLRegexs() {
        return new HashMap<>(exclusionURLRegexs);
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
            throw new ApproovException("IllegalState: " + e.getMessage(), e);
        }
        catch (IllegalArgumentException e) {
            throw new ApproovException("IllegalArgument: " + e.getMessage(), e);
        }

        // process the returned Approov status using decision maker
        getServiceMutator().handlePrecheckResult(approovResults);
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
            throw new ApproovException("IllegalState: " + e.getMessage(), e);
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
     * is not possible to use the networking interception to add the token. This operation will
     * sometimes require network access so may take some time to complete. If the attestation fails
     * for any reason then an ApproovException is thrown. This will be ApproovNetworkException for
     * networking issues where a user initiated retry of the operation should be allowed. Note that
     * the returned token should NEVER be cached by your app, you should call this function when
     * it is needed.
     *
     * @param url is the URL of the request to which the token will be added
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

        // process the status using decision maker
        getServiceMutator().handleFetchTokenResult(approovResults);
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
     * @param key is the secure string key to be found
     * @param newDef is any new definition for the secure string, or null to perform a lookup
     *               for an existing value. If a new value is defined then it will overwrite
     *               any existing value for the key.
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

        // process the returned Approov status using decision maker
        getServiceMutator().handleSecureStringResult(approovResults, type, key);
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

        // process the returned Approov status using decision maker
        getServiceMutator().handleCustomJWTResult(approovResults);
        return approovResults.getToken();
    }

    /**
     * Rebuilds the pins in the pinning interceptor after a dynamic configuration change.
     */
    static synchronized void rebuildPins() {
        if (pinningInterceptor != null)
            pinningInterceptor.buildPins();
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
                Log.d(TAG, "No builder available for " + builderName);
                okHttpBuilder = new OkHttpClient.Builder();
            }
            // build a new OkHttpClient on demand
            if (isInitialized) {
                // remove any existing ApproovTokenInterceptor from the builder
                List<Interceptor> interceptors = okHttpBuilder.interceptors();
                Iterator<Interceptor> iter = interceptors.iterator();
                while (iter.hasNext()) {
                    Interceptor interceptor = iter.next();
                    if (interceptor instanceof ApproovTokenInterceptor)
                        iter.remove();
                }

                // remove any existing ApproovPinningInterceptor from the builder
                interceptors = okHttpBuilder.networkInterceptors();
                iter = interceptors.iterator();
                while (iter.hasNext()) {
                    Interceptor interceptor = iter.next();
                    if (interceptor instanceof ApproovPinningInterceptor)
                        iter.remove();
                }

                // build the OkHttpClient with the interceptors
                Log.d(TAG, "Building new Approov OkHttpClient for " + builderName);
                ApproovTokenInterceptor tokenInterceptor = new ApproovTokenInterceptor();
                okHttpClient = okHttpBuilder
                        .addInterceptor(tokenInterceptor)
                        .addNetworkInterceptor(pinningInterceptor).build();
            } else {
                // if the ApproovService was not initialized or Approov is disabled, build plain client
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
    private final static String TAG = "ApproovTokenInterceptor";

    /**
     * Constructs a new interceptor that adds Approov tokens and substitutes headers or query
     * parameters.
     */
    public ApproovTokenInterceptor() {
    }

    @Override
    public Response intercept(Chain chain) throws IOException {

        Request request = chain.request();
        // cache the mutator for the duration of the interceptor to make sure
        // it is not changed mid-flight
        ApproovServiceMutator mutator = ApproovService.getApproovServiceMutator();
        // first check if we are to proceed with any Approov processing
        if(!mutator.handleInterceptorShouldProcessRequest(request)){
            // we are not to proceed with any Approov processing so just continue
            return chain.proceed(request);
        }

        // update the data hash based on any token binding header (presence is optional)
        String bindingHeader = ApproovService.getBindingHeader();
        if ((bindingHeader != null) && request.headers().names().contains(bindingHeader))
            Approov.setDataHashInToken(request.header(bindingHeader));

        // request an Approov token for the URL
        HttpUrl url = request.url();
        Approov.TokenFetchResult approovResults = Approov.fetchApproovTokenAndWait(url.toString());
        
        // provide information about the obtained token or error (note "approov token -check" can
        // be used to check the validity of the token and if you use token annotations they
        // will appear here to determine why a request is being rejected)
        Log.d(TAG, "Token for " + url + ": " + approovResults.getLoggableToken());

        // force a pinning rebuild if there is any dynamic config update
        if (approovResults.isConfigChanged()) {
            Approov.fetchConfig();
            ApproovService.rebuildPins();
            Log.d(TAG, "Dynamic configuration updated");
        }

        // check the status of Approov token fetch using decision maker
        boolean aChange = false;
        String setTokenHeaderKey = null;
        String setTokenHeaderValue = null;
        
        if (mutator.handleInterceptorFetchTokenResult(approovResults, url)) {
            // we successfully obtained a token so add it to the header for the request
            aChange = true;
            setTokenHeaderKey = ApproovService.getApproovTokenHeader();
            setTokenHeaderValue = ApproovService.getApproovTokenPrefix() + approovResults.getToken();
            // add trace id header too (if it is available)
        } else {
            // we only continue additional processing if we had a valid status from Approov, to prevent additional delays
            // by trying to fetch from Approov again and this also protects against header substitutions in domains not
            // protected by Approov and therefore potential subject to a MitM
            // setTokenHeaderKey and setTokenHeaderValue must be null
            return chain.proceed(request);
        }



        // we now deal with any header substitutions, which may require further fetches but these
        // should be using cached results
        Map<String, String> substitutionHeaders = ApproovService.getSubstitutionHeaders();
        Map<String, String> setSubstitutionHeaders = new LinkedHashMap<>(substitutionHeaders.size());
        for (Map.Entry<String, String> entry: substitutionHeaders.entrySet()) {
            String header = entry.getKey();
            String prefix = entry.getValue();
            String value = request.header(header);
            if ((value != null) && value.startsWith(prefix) && (value.length() > prefix.length())) {
                approovResults = Approov.fetchSecureStringAndWait(value.substring(prefix.length()), null);
                Log.d(TAG, "Substituting header: " + header + ", " + approovResults.getStatus().toString());
                if (mutator.handleInterceptorHeaderSubstitutionResult(approovResults, header)) {
                    aChange = true;
                    setSubstitutionHeaders.put(header, prefix + approovResults.getSecureString());
                }
            }
        }

        // we now deal with any query parameter substitutions, which may require further fetches but these
        // should be using cached results
        String originalURL = request.url().toString();
        String replacementURL = originalURL;
        Map<String, Pattern> substitutionQueryParams = ApproovService.getSubstitutionQueryParams();
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
                if (mutator.handleInterceptorHeaderSubstitutionResult(approovResults, queryKey)) {
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

        // call the processed request callback
        request = mutator.handleInterceptorProcessedRequest(request, changes);

        // proceed with the rest of the chain
        return chain.proceed(request);
    }
}

// interceptor to implement pinning on network connections
class ApproovPinningInterceptor implements Interceptor {
    // logging tag
    private final static String TAG = "ApproovPinningInterceptor";

    // maximum number of elements that may be held in the handshake cache to allow caching
    // of different concurrent connections but without causing a significant memory leak
    private final static int maxCachedHandshakes = 10;

    // the certificate pinner to use for pinning that may be rebuilt if there is a change
    // in the pinning configuration
    private CertificatePinner certificatePinner;

    // set of TLS handshakes that are known to be valid constrained to a size of maxCachedHandshakes
    // to prevent a memory leak for long running apps
    private LinkedHashSet<Handshake> knownValidHandshakes;

    /**
     * Construct a new pinning interceptor.
     */
    public ApproovPinningInterceptor() {
        knownValidHandshakes = new LinkedHashSet<>();
        buildPins();
    }

    /**
     * Rebuild the pinning configuration. This is called when the dynamic configuration
     * changes and we need to update the pinning information. This forces all known valid
     * handshakes to be cleared.
     */
    synchronized public void buildPins() {
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
        certificatePinner = pinBuilder.build();
        knownValidHandshakes.clear();
    }

    /**
     * Gets the current CertificatePinner for checking peer certificate on a TLS handshake.
     *
     * @return the current CertificatePinner
     */
    synchronized private CertificatePinner getCertificatePinner() {
        return certificatePinner;
    }

    /**
     * Determines if the given handshake is known to be valid, supporting different TLS
     * negotiations on different domains as required.
     *
     * @param handshake ot be checked
     * @return true if the handshake is known valid, false otherwise
     */
    synchronized private boolean isValidHandshake(Handshake handshake) {
        return knownValidHandshakes.contains(handshake);
    }

    /**
     * Adds a valid handshake to the cached set, clearing the cache if that would exceed
     * the maximum size.
     *
     * @param handshake to be added as known valid
     */
    synchronized private void addValidHandshake(Handshake handshake) {
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
        if(!ApproovService.getApproovServiceMutator().handlePinningShouldProcessRequest(request)){
            // we are not to proceed with any pinning processing so just continue
            return chain.proceed(request);
        }

        // certain requests, e.g. those that do not require Approov processing
        String host = chain.request().url().host();
        Connection connection = chain.connection();
        Handshake handshake = (connection != null) ? connection.handshake() : null;
        if (handshake == null)
            throw new ApproovNetworkException("network interceptor has no connection information");
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

            // pins were valid for the handshake so cache it
            addValidHandshake(handshake);
            Log.d(TAG, "Valid pinning for: " + handshake.toString());
        }
        return chain.proceed(chain.request());
    }
}
