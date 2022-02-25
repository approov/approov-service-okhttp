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
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
// MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
// ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH
// THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

package io.approov.service.okhttp;

import android.util.Log;
import android.content.Context;

import com.criticalblue.approovsdk.Approov;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import okhttp3.CertificatePinner;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

// ApproovService provides a mediation layer to the Approov SDK itself
public class ApproovService {
    // logging tag
    private static final String TAG = "ApproovService";

    // default header that will be added to Approov enabled requests
    private static final String APPROOV_TOKEN_HEADER = "Approov-Token";

    // default  prefix to be added before the Approov token by default
    private static final String APPROOV_TOKEN_PREFIX = "";

    // true if the Approov SDK initialized okay
    private boolean initialized;

    // builder to be used for new OkHttp clients
    private OkHttpClient.Builder okHttpBuilder;

    // cached OkHttpClient to use or null if not set
    private OkHttpClient okHttpClient;

    // header to be used to send Approov tokens
    private String approovTokenHeader;

    // any prefix String to be added before the transmitted Approov token
    private String approovTokenPrefix;

    // any header to be used for binding in Approov tokens or null if not set
    private String bindingHeader;

    // map of headers that should have their values substituted for secure strings, mapped to their
    // required prefixes
    private Map<String, String> substitutionHeaders;

    /**
     * Creates an Approov service.
     *
     * @param context the Application context
     * @param config the initial service config string
     */
    public ApproovService(Context context, String config) {
        // setup for creating clients
        initialized = false;
        okHttpBuilder = new OkHttpClient.Builder();
        okHttpClient = null;
        approovTokenHeader = APPROOV_TOKEN_HEADER;
        approovTokenPrefix = APPROOV_TOKEN_PREFIX;
        bindingHeader = null;
        substitutionHeaders = new HashMap<>();
    
        // initialize the Approov SDK
        try {
            Approov.initialize(context, config, "auto", null);
            Approov.setUserProperty("QuickstartOkHttp");
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Approov initialization failed: " + e.getMessage());
            return;
        }
        initialized = true;
    }

    /**
     * Prefetches in the background to lower the effective latency of a subsequent token fetch or
     * secure string fetch by starting the operation earlier so the subsequent fetch may be able to
     * use cached data.
     */
    public synchronized void prefetch() {
        if (initialized)
            // fetch an Approov token using a placeholder domain
            Approov.fetchApproovToken(new PrefetchCallbackHandler(), "www.approov.io");
    }

    /**
     * Clears the OkHttp client if there are some potential pinning changes that require an
     * update.
     */
    public synchronized void clearOkHttpClient() {
        Log.d(TAG, "OKHttp client cleared");
        okHttpClient = null;
    }

    /**
     * Sets the OkHttpClient.Builder to be used for constructing the Approov OkHttpClient. This
     * allows a custom configuration to be set, with additional interceptors and properties.
     * This clears the cached OkHttp client so should only be called when an actual builder
     * change is required.
     *
     * @param builder is the OkHttpClient.Builder to be used as a basis for the Approov OkHttpClient
     */
    public synchronized void setOkHttpClientBuilder(OkHttpClient.Builder builder) {
        okHttpBuilder = builder;
        okHttpClient = null;
    }

    /**
     * Sets the header that the Approov token is added on, as well as an optional
     * prefix String (such as "Bearer "). By default the token is provided on
     * "Approov-Token" with no prefix.
     *
     * @param header is the header to place the Approov token on
     * @param prefix is any prefix String for the Approov token header
     */
    public synchronized void setApproovHeader(String header, String prefix) {
        approovTokenHeader = header;
        approovTokenPrefix = prefix;
        okHttpClient = null;
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
    public synchronized void setBindingHeader(String header) {
        bindingHeader = header;
        okHttpClient = null;
    }

    /**
     * Adds the name of a header which should be subject to secure strings substitution. This
     * means that if the header is present then the value will be used as a key to look up a
     * secure string value which will be substituted into the header value instead. This allows
     * easy migration to the use of secure strings. Note that this should be done on initialization
     * rather than for every request as it will require a new OkHttpClient to be built. A required
     * prefix may be specified to deal with cases such as the use of "Bearer " prefixed before values
     * in an authorization header.
     *
     * @param header is the header to be marked for substitution
     * @param requiredPrefix is any required prefix to the value being substituted or null if not required
     */
    public synchronized void addSubstitutionHeader(String header, String requiredPrefix) {
        if (requiredPrefix == null)
            substitutionHeaders.put(header, "");
        else
            substitutionHeaders.put(header, requiredPrefix);
        okHttpClient = null;
    }

    /**
     * Removes a header previously added using addSubstitutionHeader.
     *
     * @param header is the header to be removed for substitution
     */
    public synchronized void removeSubstitutionHeader(String header) {
        substitutionHeaders.remove(header);
        okHttpClient = null;
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
     * @throws ApproovException if here was a problem
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
     * @throws ApproovException if here was a problem
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
     * Gets the OkHttpClient that enables the Approov service. This adds the Approov token in
     * a header to requests, and also pins the connections. The OkHttpClient is constructed
     * lazily on demand but is cached if there are no changes. Use "setOkHttpClientBuilder" to
     * provide any special properties.
     *
     * @return OkHttpClient to be used with Approov
     */
    public synchronized OkHttpClient getOkHttpClient() {
        if (okHttpClient == null) {
            // build a new OkHttpClient on demand
            if (initialized) {
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
                Log.d(TAG, "Building new Approov OkHttpClient");
                ApproovTokenInterceptor interceptor = new ApproovTokenInterceptor(this, approovTokenHeader,
                        approovTokenPrefix, bindingHeader, substitutionHeaders);
                okHttpClient = okHttpBuilder.certificatePinner(pinBuilder.build()).addInterceptor(interceptor).build();
            } else {
                // if the Approov SDK could not be initialized then we can't add Approov capabilities
                Log.e(TAG, "Cannot build Approov OkHttpClient due to initialization failure");
                okHttpClient = okHttpBuilder.build();
            }
        }
        return okHttpClient;
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
    public void approovCallback(Approov.TokenFetchResult pResult) {
        if (pResult.getStatus() == Approov.TokenFetchStatus.UNKNOWN_URL)
            Log.d(TAG, "Prefetch success");
        else
            Log.e(TAG, "Prefetch failure: " + pResult.getStatus().toString());
    }
}

// interceptor to add Approov tokens
class ApproovTokenInterceptor implements Interceptor {
    // logging tag
    private final static String TAG = "ApproovInterceptor";

    // underlying ApproovService being utilized
    private ApproovService approovService;

    // the name of the header to be added to hold the Approov token
    private String approovTokenHeader;

    // prefix to be used for the Approov token
    private String approovTokenPrefix;

    // any binding header for Approov token binding, or null if none
    private String bindingHeader;

    // map of headers that should have their values substituted for secure strings, mapped to their
    // required prefixes
    private Map<String, String> substitutionHeaders;

    /**
     * Constructs a new interceptor that adds Approov tokens and substitute headers.
     *
     * @param approovService is the underlying ApproovService being used
     * @param approovTokenHeader is the name of the header to be used for the Approov token
     * @param approovTokenPrefix is the prefix string to be used with the Approov token
     * @param bindingHeader is any token binding header to use or null otherwise
     * @param substitutionHeaders is the map of secure string substitution headers mapped to any required prefixes
     */
    public ApproovTokenInterceptor(ApproovService approovService, String approovTokenHeader, String approovTokenPrefix,
                                   String bindingHeader, Map<String, String> substitutionHeaders) {
        this.approovService = approovService;
        this.approovTokenHeader = approovTokenHeader;
        this.approovTokenPrefix = approovTokenPrefix;
        this.bindingHeader = bindingHeader;
        this.substitutionHeaders = new HashMap<>(substitutionHeaders);
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        // update the data hash based on any token binding header (presence is optional)
        Request request = chain.request();
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
            approovService.clearOkHttpClient();
        }

        // we cannot proceed if the pins need to be updated. This will be cleared by using getOkHttpClient
        // but will persist if the app fails to rebuild the OkHttpClient regularly. This might occur
        // on first use after initial app install if the initial network fetch was unable to obtain
        // the dynamic configuration for the account if there was poor network connectivity at that
        // point.
        if (approovResults.isForceApplyPins()) {
            approovService.clearOkHttpClient();
            throw new ApproovNetworkException("Pins need to be updated");
        }

        // check the status of Approov token fetch
        if (approovResults.getStatus() == Approov.TokenFetchStatus.SUCCESS)
            // we successfully obtained a token so add it to the header for the request
            request = request.newBuilder().header(approovTokenHeader, approovTokenPrefix + approovResults.getToken()).build();
        else if ((approovResults.getStatus() == Approov.TokenFetchStatus.NO_NETWORK) ||
                 (approovResults.getStatus() == Approov.TokenFetchStatus.POOR_NETWORK) ||
                 (approovResults.getStatus() == Approov.TokenFetchStatus.MITM_DETECTED))
            // we are unable to get an Approov token due to network conditions so the request can
            // be retried by the user later
            throw new ApproovNetworkException("Approov token fetch for " + host + ": " + approovResults.getStatus().toString());
        else if ((approovResults.getStatus() != Approov.TokenFetchStatus.NO_APPROOV_SERVICE) &&
                 (approovResults.getStatus() != Approov.TokenFetchStatus.UNKNOWN_URL) &&
                 (approovResults.getStatus() != Approov.TokenFetchStatus.UNPROTECTED_URL))
            // we have failed to get an Approov token with a more serious permanent error
            throw new ApproovException("Approov token fetch for " + host + ": " + approovResults.getStatus().toString());

        // we now deal with any header substitutions, which may require further fetches but these
        // should be using cached results
        boolean isIllegalSubstitution = (approovResults.getStatus() == Approov.TokenFetchStatus.UNKNOWN_URL);
        for (Map.Entry<String, String> entry: substitutionHeaders.entrySet()) {
            String header = entry.getKey();
            String prefix = entry.getValue();
            String value = request.header(header);
            if ((value != null) && value.startsWith(prefix) && (value.length() > prefix.length())) {
                approovResults = Approov.fetchSecureStringAndWait(value.substring(prefix.length()), null);
                Log.d(TAG, "Substituting header: " + header + ", " + approovResults.getStatus().toString());
                if (approovResults.getStatus() == Approov.TokenFetchStatus.SUCCESS) {
                    if (isIllegalSubstitution)
                        // don't allow substitutions on unadded API domains to prevent them accidentally being
                        // subject to a Man-in-the-Middle (MitM) attack
                        throw new ApproovException("Header substitution for " + header +
                                " illegal for " + host + " that is not an added API domain");
                    request = request.newBuilder().header(header, prefix + approovResults.getSecureString()).build();
                }
                else if (approovResults.getStatus() == Approov.TokenFetchStatus.REJECTED)
                    // if the request is rejected then we provide a special exception with additional information
                    throw new ApproovRejectionException("Header substitution for " + header + ": " +
                            approovResults.getStatus().toString() + ": " + approovResults.getARC() +
                            " " + approovResults.getRejectionReasons(),
                            approovResults.getARC(), approovResults.getRejectionReasons());
                else if ((approovResults.getStatus() == Approov.TokenFetchStatus.NO_NETWORK) ||
                        (approovResults.getStatus() == Approov.TokenFetchStatus.POOR_NETWORK) ||
                        (approovResults.getStatus() == Approov.TokenFetchStatus.MITM_DETECTED))
                    // we are unable to get the secure string due to network conditions so the request can
                    // be retried by the user later
                    throw new ApproovNetworkException("Header substitution for " + header + ": " +
                            approovResults.getStatus().toString());
                else if (approovResults.getStatus() != Approov.TokenFetchStatus.UNKNOWN_KEY)
                    // we have failed to get a secure string with a more serious permanent error
                    throw new ApproovException("Header substitution for " + header + ": " +
                            approovResults.getStatus().toString());
            }
        }

        // proceed with the rest of the chain
        return chain.proceed(request);
    }
}
