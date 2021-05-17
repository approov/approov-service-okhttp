// ApproovService for integrating Approov into apps using OkHttp.
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

import android.content.SharedPreferences;
import android.util.Log;
import android.content.Context;

import com.criticalblue.approovsdk.Approov;

import java.io.IOException;
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

    // keys for the Approov shared preferences
    private static final String APPROOV_CONFIG = "approov-config";
    private static final String APPROOV_PREFS = "approov-prefs";

    // default header that will be added to Approov enabled requests
    private static final String APPROOV_TOKEN_HEADER = "Approov-Token";

    // default  prefix to be added before the Approov token by default
    private static final String APPROOV_TOKEN_PREFIX = "";

    // true if the Approov SDK initialized okay
    private boolean initialized;

    // context for handling preferences
    private Context appContext;

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

    /**
     * Creates an Approov service.
     *
     * @param context the Application context
     * @param config the initial service config string
     */
    public ApproovService(Context context, String config) {
        // setup for creating clients
        appContext = context;
        initialized = false;
        okHttpBuilder = new OkHttpClient.Builder();
        okHttpClient = null;
        approovTokenHeader = APPROOV_TOKEN_HEADER;
        approovTokenPrefix = APPROOV_TOKEN_PREFIX;
        bindingHeader = null;
    
        // initialize the Approov SDK
        String dynamicConfig = getApproovDynamicConfig();
        try {
            Approov.initialize(context, config, dynamicConfig, null);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Approov initialization failed: " + e.getMessage());
            return;
        }
        initialized = true;

        // if we didn't have a dynamic configuration (after the first launch on the app) then
        // we fetch the latest and write it to local storage now
        if (dynamicConfig == null)
            updateDynamicConfig();
    }

    /**
     * Prefetches an Approov token in the background. The placeholder domain "www.approov.io" is
     * simply used to initiate the fetch and does not need to be a valid API for the account. This
     * method can be used to lower the effective latency of a subsequent token fetch by starting
     * the operation earlier so the subsequent fetch may be able to use a cached token.
     */
    public synchronized void prefetchApproovToken() {
        if (initialized)
            Approov.fetchApproovToken(new PrefetchCallbackHandler(), "www.approov.io");
    }

    /**
     * Writes the latest dynamic configuration that the Approov SDK has. This clears the cached
     * OkHttp client since the pins may have changed and therefore a client rebuild is required.
     */
    public synchronized void updateDynamicConfig() {
        Log.i(TAG, "Approov dynamic configuration updated");
        putApproovDynamicConfig(Approov.fetchConfig());
        okHttpClient = null;
    }

    /**
     * Stores an application's dynamic configuration string in non-volatile storage.
     *
     * The default implementation stores the string in shared preferences, and setting
     * the config string to null is equivalent to removing the config.
     *
     * @param config a configuration string
     */
    protected void putApproovDynamicConfig(String config) {
        SharedPreferences prefs = appContext.getSharedPreferences(APPROOV_PREFS, 0);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(APPROOV_CONFIG, config);
        editor.apply();
    }

    /**
     * Returns the application's dynamic configuration string from non-volatile storage.
     *
     * The default implementation retrieves the string from shared preferences.
     *
     * @return config string, or null if not present
     */
    protected String getApproovDynamicConfig() {
        SharedPreferences prefs = appContext.getSharedPreferences(APPROOV_PREFS, 0);
        return prefs.getString(APPROOV_CONFIG, null);
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
                Map<String, List<String>> pins = Approov.getPins("public-key-sha256");
                for (Map.Entry<String, List<String>> entry : pins.entrySet()) {
                    for (String pin : entry.getValue())
                        pinBuilder = pinBuilder.add(entry.getKey(), "sha256/" + pin);
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
                Log.i(TAG, "Building new Approov OkHttpClient");
                okHttpClient = okHttpBuilder.certificatePinner(pinBuilder.build())
                        .addInterceptor(new ApproovTokenInterceptor(this, approovTokenHeader, approovTokenPrefix, bindingHeader))
                        .build();
            } else {
                // if the Approov SDK could not be initialized then we can't pin or add Approov tokens
                Log.e(TAG, "Cannot build Approov OkHttpClient due to initialization failure");
                okHttpClient = okHttpBuilder.build();
            }
        }
        return okHttpClient;
    }
}

/**
 * Callback handler for prefetching an Approov token. We simply log as we don't need the token
 * itself, as it will be returned as a cached value on a subsequent token fetch.
 */
final class PrefetchCallbackHandler implements Approov.TokenFetchCallback {
    // logging tag
    private static final String TAG = "ApproovPrefetch";

    @Override
    public void approovCallback(Approov.TokenFetchResult pResult) {
        if (pResult.getStatus() == Approov.TokenFetchStatus.UNKNOWN_URL)
            Log.i(TAG, "Approov prefetch success");
        else
            Log.i(TAG, "Approov prefetch failure: " + pResult.getStatus().toString());
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

    /**
     * Constructs an new interceptor that adds Approov tokens.
     *
     * @param service is the underlying ApproovService being used
     * @param approovTokenHeader is the name of the header to be used for the Approov token
     * @param approovTokenPrefix is the prefix string to be used with the Approov token
     * @param bindingHeader is any token binding header to use or null otherwise
     */
    public ApproovTokenInterceptor(ApproovService service, String approovTokenHeader, String approovTokenPrefix, String bindingHeader) {
        approovService = service;
        this.approovTokenHeader = approovTokenHeader;
        this.approovTokenPrefix = approovTokenPrefix;
        this.bindingHeader = bindingHeader;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        // update the data hash based on any token binding header
        Request request = chain.request();
        if ((bindingHeader != null) && request.headers().names().contains(bindingHeader))
            Approov.setDataHashInToken(request.header(bindingHeader));

        // request an Approov token for the domain
        String host = request.url().host();
        Approov.TokenFetchResult approovResults = Approov.fetchApproovTokenAndWait(host);

        // provide information about the obtained token or error (note "approov token -check" can
        // be used to check the validity of the token and if you use token annotations they
        // will appear here to determine why a request is being rejected)
        Log.i(TAG, "Approov Token for " + host + ": " + approovResults.getLoggableToken());

        // update any dynamic configuration
        if (approovResults.isConfigChanged())
            approovService.updateDynamicConfig();

        // we cannot proceed if the pins to be updated (this will be cleared by using getOkHttpClient
        // but will persist if the app fails to rebuild the OkHttpClient regularly)
        if (approovResults.isForceApplyPins())
            throw new IOException("Approov pins need to be updated");

        // check the status of Approov token fetch
        if (approovResults.getStatus() == Approov.TokenFetchStatus.SUCCESS) {
            // we successfully obtained a token so add it to the header for the request
            request = request.newBuilder().header(approovTokenHeader, approovTokenPrefix + approovResults.getToken()).build();
        }
        else if ((approovResults.getStatus() != Approov.TokenFetchStatus.NO_APPROOV_SERVICE) &&
                 (approovResults.getStatus() != Approov.TokenFetchStatus.UNKNOWN_URL) &&
                 (approovResults.getStatus() != Approov.TokenFetchStatus.UNPROTECTED_URL)) {
            // we have failed to get an Approov token in such a way that there is no point in proceeding
            // with the request - generally a retry is needed, unless the error is permanent
            throw new IOException("Approov token fetch failed: " + approovResults.getStatus().toString());
        }

        // proceed with the rest of the chain
        return chain.proceed(request);
    }
}
