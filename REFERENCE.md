# Reference
This provides a reference for all of the static methods defined on `ApproovService`, the entry point of the Approov Package for OkHttp. These are available if you import:

**Java:**
```Java
import io.approov.service.okhttp.ApproovService;
```

**Kotlin:**
```kotlin
import io.approov.service.okhttp.ApproovService
```

The request path never throws for a runtime Approov condition: a request made through an `OkHttpClient` from `getOkHttpClient` always proceeds, with the fetch status on the `Approov-Status` header and an empty `Approov-Token` header if it could not be protected (see [ADVANCED.md](ADVANCED.md)). The exceptions that can reach the caller from the request path are the app's own configuration errors (`IllegalStateException` for a required body digest that cannot be generated or an unsupported signing algorithm), pinning failures on the app's API domains (`javax.net.ssl.SSLPeerUnverifiedException`, as for any OkHttp pinning failure), and aborts the app itself opted in to through a service mutator. The exceptions below are thrown by the direct methods (`precheck`, `fetchToken`, `fetchSecureString`, `fetchCustomJWT`, ...), which return a value to the caller, A custom `ApproovServiceMutator` that opts in to aborting requests throws a standard network stack exception (`IOException` or a subclass), never an Approov type.

Various methods may throw an `ApproovException` (an `IOException`) if there is a problem. The method `getMessage()` provides a descriptive message. An `ApproovFetchStatusException` (a subclass of `ApproovException`) carries the SDK fetch status in `getStatus()`.

If a method throws an `ApproovNetworkException` (a subclass of `ApproovFetchStatusException`) then this indicates the problem was caused by a networking issue (`NO_NETWORK`, `POOR_NETWORK` or `UNTRUSTED_NETWORK`), and a user initiated retry should be allowed.

If a method throws an `ApproovRejectionException` (a subclass of `ApproovException`) the this indicates the problem was that the app failed attestation. An additional method `getARC()` provides the [Attestation Response Code](https://approov.io/docs/latest/approov-usage-documentation/#attestation-response-code), which could be provided to the user for communication with your app support to determine the reason for failure, without this being revealed to the end user. The method `getRejectionReasons()` provides the [Rejection Reasons](https://approov.io/docs/latest/approov-usage-documentation/#rejection-reasons) if the feature is enabled, providing a comma separated list of reasons why the app attestation was rejected.

## initialize
Initializes the Approov SDK and thus enables the Approov features. The `config` parameter is your Approov account ID: the string from your onboarding email, also available from the CLI with `approov sdk -getConfigString` (the CLI and the SDK call it the SDK config string). It identifies your account to the SDK, is not a secret, and is the same for every app in the account. See [obtaining it](https://approov.io/docs/latest/approov-usage-documentation/#getting-the-initial-sdk-configuration). Initialization throws `IllegalArgumentException` only if the account ID was not copied exactly, and `IllegalStateException` if a second attempt is made with a different account ID.

This is the standard form and should be used in most cases. The `comment` parameter defaults to `null` when not supplied.

**Java:**
```Java
void initialize(Context context, String config)
```

**Kotlin:**
```kotlin
fun initialize(context: Context, config: String)
```

The [application context](https://developer.android.com/reference/android/content/Context#getApplicationContext()) must be provided using the `context` parameter.

It is possible to pass an empty string instead of the account ID to bypass Approov SDK initialization. In that case the package still reports itself as initialized, but any `OkHttpClient` obtained from it behaves as a plain client with no Approov token injection, message signing, secure strings, or pinning.

This bypass mode is intended as a bootstrap state for advanced integrations. A later call to `initialize()` with the account ID is allowed and will then enable the native Approov SDK at runtime. By contrast, reinitializing from one account ID to a different one is rejected by the platform SDK.

If you need to supply a `comment` to the native SDK (for example to pass `options:...` startup flags or trigger a `reinit...` flow), use the extended form instead:

**Java:**
```java
void initialize(Context context, String config, String comment)
```

**Kotlin:**
```kotlin
fun initialize(context: Context, config: String, comment: String?)
```

The `comment` parameter is passed directly to the native Approov SDK. Key uses:
* Pass a string starting with `options:` during the initial setup to forward custom startup options to the native SDK.
* Pass a string starting with `reinit` to trigger native re-initialization on a subsequent same-config call.
* Pass `null` (or use the 2-arg form) when no comment is needed — this is the default.

Please refer to the [Approov SDK documentation](https://approov.io/docs/latest/approov-direct-sdk-integration/#sdk-initialization-options) for full details on supported comment values.


## isInitialized
Returns whether the package itself has been initialized.

**Java:**
```java
boolean isInitialized()
```

**Kotlin:**
```kotlin
fun isInitialized(): Boolean
```

Returns `true` if `initialize` has been called successfully, including when bypass mode is active (empty string instead of the account ID). Returns `false` only if `initialize` has never been called successfully. A rejected later call (for example with a different account ID) throws and leaves the previous state in place, so this keeps returning `true`. Use `isApproovEnabled()` to distinguish between bypass and protected modes.

## isApproovEnabled
Returns whether Approov protection is currently enabled.

**Java:**
```java
boolean isApproovEnabled()
```

**Kotlin:**
```kotlin
fun isApproovEnabled(): Boolean
```

Returns `true` only when the package was initialized with the Approov account ID and the native Approov SDK is active. Returns `false` in all other cases: not initialized, or initialized in bypass mode (empty string). All direct Approov SDK methods (such as `fetchToken`, `precheck`, `fetchSecureString`) will throw `ApproovException` if called when this returns `false`.


## getOkHttpClient
Gets the default `OkHttpClient` that enables the Approov service. This adds the Approov token in a header to requests, performs and header or query parameter substitutions and also pins the connections. The `OkHttpClient` is constructed lazily on demand but is cached if there are no changes.

**Java:**
```Java
OkHttpClient getOkHttpClient()
```

**Kotlin:**
```kotlin
fun getOkHttpClient(): OkHttpClient
```

You must initialize the package before calling this method. If initialization used an empty string instead of the account ID then this provides a plain `OkHttpClient` without any Approov protection.

Use `setOkHttpClientBuilder` to provide any special builder properties. If you wish to use multiple different builders in your application you can set them by also providing a builder name to `setOkHttpClientBuilder`. In this case you get an `OkHttpClient` using a specific builder using:

**Java:**
```Java
OkHttpClient getOkHttpClient(String builderName)
```

**Kotlin:**
```kotlin
fun getOkHttpClient(builderName: String): OkHttpClient
```

## setOkHttpClientBuilder
Sets the `OkHttpClient.Builder` to be used for constructing the default Approov `OkHttpClient`. This allows a custom configuration to be set, with additional interceptors and properties.

**Java:**
```Java
void setOkHttpClientBuilder(OkHttpClient.Builder builder)
```

**Kotlin:**
```kotlin
fun setOkHttpClientBuilder(builder: OkHttpClient.Builder)
```

Additionally, it is also possible to set a custom named builder to allow multiple builders within the same application. Use:

**Java:**
```Java
void setOkHttpClientBuilder(String builderName, OkHttpClient.Builder builder)
```

**Kotlin:**
```kotlin
fun setOkHttpClientBuilder(builderName: String, builder: OkHttpClient.Builder)
```

## setServiceMutator
Sets the `ApproovServiceMutator` instance to handle callbacks from the ApproovService implementation. This facility enables customization of ApproovService operations at key points in the configuration and attestation flows; see [ADVANCED.md](ADVANCED.md) for the hooks and examples.

**Java:**
```java
void setServiceMutator(ApproovServiceMutator mutator)
```

**Kotlin:**
```kotlin
fun setServiceMutator(mutator: ApproovServiceMutator?)
```

The mutator installed by `initialize` is the one returned by `createDefaultServiceMutator`: the standard decisions plus message signing with both the install and the account signatures. Passing `null` reinstates that default. Passing `ApproovServiceMutator.DEFAULT` keeps the standard decisions with message signing switched off. To customize the signatures provide an `ApproovDefaultMessageSigning` configured with a `SignatureParametersFactory`:

**Java:**
```java
    ApproovService.setServiceMutator(
        new ApproovDefaultMessageSigning().setDefaultFactory(
            ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()
                .setUseInstallMessageSigning()));
```

**Kotlin:**
```kotlin
    ApproovService.setServiceMutator(
        ApproovDefaultMessageSigning().setDefaultFactory(
            ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()
                .setUseInstallMessageSigning()))
```

The default decisions of `ApproovServiceMutator` never abort a request: `handleInterceptorFetchTokenResult` returns `true` for every status except `UNKNOWN_URL` and `UNPROTECTED_URL` (for which it returns `false` so that no Approov headers are sent), and the substitution decisions return `true` only for `SUCCESS`. A custom mutator may opt in to aborting a request from any interceptor hook by throwing a standard network stack exception (`java.io.IOException` or a platform subclass), never an Approov specific type, so that the abort surfaces to the app as an ordinary network failure; that is an explicit integrator decision.

## createDefaultServiceMutator
Creates the mutator that `initialize` installs out of the box: an `ApproovDefaultMessageSigning` configured with `generateDefaultSignatureParametersFactory()`, producing both the install (`ecdsa-p256-sha256`) and the account (`hmac-sha256`) signatures as members `install` and `account` of the `Signature` and `Signature-Input` headers.

**Java:**
```java
ApproovServiceMutator createDefaultServiceMutator()
```

**Kotlin:**
```kotlin
fun createDefaultServiceMutator(): ApproovServiceMutator
```

## getServiceMutator
Gets the active service mutator instance.

**Java:**
```java
ApproovServiceMutator getServiceMutator()
```

**Kotlin:**
```kotlin
fun getServiceMutator(): ApproovServiceMutator
```

## setDevKey
[Sets a development key](https://approov.io/docs/latest/approov-usage-documentation/#using-a-development-key) in order to force an app to be passed. This can be used if the app has to be resigned in a test environment and would thus fail attestation otherwise.

**Java:**
```Java
void setDevKey(String devKey)
```

**Kotlin:**
```kotlin
fun setDevKey(devKey: String)
```

## setTokenHeader
Sets the `header` that the Approov token is added on, as well as an optional `prefix` String (such as "`Bearer `"). Pass `null` or the empty string for `prefix` if it is not required; `null` never prepends the literal string "null". By default the token is provided on `Approov-Token` with no prefix. If no token could be obtained the header is still sent, with an empty value after any prefix, and the status is reported on the status header (see `setStatusHeader`). Failure information is never placed in this header.

**Java:**
```Java
void setTokenHeader(String header, String prefix)
```

**Kotlin:**
```kotlin
fun setTokenHeader(header: String, prefix: String?)
```

## getTokenHeader
Gets the name of the header used to carry the Approov token.

**Java:**
```java
String getTokenHeader()
```

**Kotlin:**
```kotlin
fun getTokenHeader(): String
```

## getTokenPrefix
Gets any prefix string (e.g., "Bearer ") being added to the Approov token header value.

**Java:**
```java
String getTokenPrefix()
```

**Kotlin:**
```kotlin
fun getTokenPrefix(): String
```

## setTraceIDHeader
Sets the header name used to provide the optional Approov TraceID debug value. By default this is `Approov-TraceID`. Passing `null` disables the TraceID header.

**Java:**
```java
void setTraceIDHeader(String header)
```

**Kotlin:**
```kotlin
fun setTraceIDHeader(header: String?)
```

## getTraceIDHeader
Gets the header name currently used for the Approov TraceID. Returns `null` if disabled.

**Java:**
```java
String getTraceIDHeader()
```

**Kotlin:**
```kotlin
fun getTraceIDHeader(): String?
```

## setStatusHeader
Sets the name of the header used to report the Approov token fetch status to the backend on every request processed by Approov. By default this is `Approov-Status`. The value is the SDK fetch status name in lowercase (`success`, `no_network`, `poor_network`, `untrusted_network`, `no_approov_service`, `rejected`, `internal_error`, ...), identical on Android and iOS. It is sent on successful requests too, and never on requests to domains not protected by Approov. With the default message signing the header is covered by the signatures. Passing `null` disables the header, in which case a request that could not be protected is sent with an empty token header and no explanation.

**Java:**
```java
void setStatusHeader(String header)
```

**Kotlin:**
```kotlin
fun setStatusHeader(header: String?)
```

## getStatusHeader
Gets the header name currently used to report the Approov fetch status. Returns `null` if disabled.

**Java:**
```java
String getStatusHeader()
```

**Kotlin:**
```kotlin
fun getStatusHeader(): String?
```

## Deprecated header aliases
`setApproovHeader(header, prefix)`, `setApproovTraceIDHeader(header)`, `getApproovTokenHeader()`, `getApproovTokenPrefix()` and `getApproovTraceIDHeader()` remain as deprecated aliases of `setTokenHeader`, `setTraceIDHeader`, `getTokenHeader`, `getTokenPrefix` and `getTraceIDHeader`, which are the names used by every Approov package.

## setBindingHeader
Sets a binding `header` that may be present on requests being made. This is for the [token binding](https://approov.io/docs/latest/approov-usage-documentation/#token-binding) feature. A header should be chosen whose value is unchanging for most requests (such as an Authorization header). If the `header` is present, then its SHA256 hash is supplied to Approov so the issued token can carry the corresponding `pay` claim and be bound to the value. This may then be verified by the backend API integration.

The binding header cannot also be configured for secure string substitution. Such a configuration would bind the Approov token to the placeholder value while sending the substituted value to the backend, so either configuration call throws `IllegalArgumentException` when it detects the conflict. Header-name comparison is case-insensitive.

**Java:**
```Java
void setBindingHeader(String header)
```

**Kotlin:**
```kotlin
fun setBindingHeader(header: String)
```

## setStaleProtectionRefreshPeriod
Sets the period in milliseconds after which a request that was held between having its Approov protection applied and being actually transmitted has that protection (Approov token and any message signature) refreshed at the network layer immediately before transmission. Requests may be held in this way if the device enters a deep sleep or doze state while the request is in flight, or if the app employs its own request queueing or backoff mechanism; the Approov token and any message signature (which carries created/expires timestamps) may then have expired by the time the request is sent. A refresh reissues the Approov token fetch (usually satisfied instantly from the SDK's cache) and reapplies any message signing via the service mutator's `handleInterceptorProcessedRequest` callback. Because this reinvokes the callback, a refresh is only performed if the mutator's `supportsProtectionRefresh()` returns true: the default mutator and `ApproovDefaultMessageSigning` support it, while custom `ApproovServiceMutator` implementations must opt in by overriding `supportsProtectionRefresh()` once their callback is safe to invoke more than once per request. The period should be comfortably less than the message signature expiry (15 seconds by default) but high enough that ordinary requests are not reprocessed. The default is 3000ms and passing a value less than or equal to zero disables the refresh.

**Java:**
```Java
void setStaleProtectionRefreshPeriod(long periodMS)
```

**Kotlin:**
```kotlin
fun setStaleProtectionRefreshPeriod(periodMS: Long)
```

## addSubstitutionHeader
Adds the name of a `header` which should be subject to [secure strings](https://approov.io/docs/latest/approov-usage-documentation/#secure-strings) substitution. This means that if the `header` is present then the value will be used as a key to look up a secure string value which will be substituted into the `header` value instead. This allows easy migration to the use of secure strings. A `requiredPrefix` may be specified to deal with cases such as the use of "`Bearer `" prefixed before values in an authorization header. Set `requiredPrefix` to `null` if it is not required.

Header names are matched case-insensitively. Adding the same logical header again replaces its existing configuration and preserves the casing from the latest call. A substitution header cannot also be the token binding header; either configuration call throws `IllegalArgumentException` when it detects the conflict.

**Java:**
```Java
void addSubstitutionHeader(String header, String requiredPrefix)
```

**Kotlin:**
```kotlin
fun addSubstitutionHeader(header: String, requiredPrefix: String?)
```

## removeSubstitutionHeader
Removes a `header` previously added using `addSubstitutionHeader`. Header-name matching is case-insensitive.

**Java:**
```Java
void removeSubstitutionHeader(String header)
```

**Kotlin:**
```kotlin
fun removeSubstitutionHeader(header: String)
```

## getSubstitutionHeaders
Gets the map of headers currently subject to secure string substitution, mapped to their required prefixes.

This throws `IllegalStateException` if `ApproovService` is not initialized.

**Java:**
```java
Map<String, String> getSubstitutionHeaders()
```

**Kotlin:**
```kotlin
fun getSubstitutionHeaders(): Map<String, String>
```

## addSubstitutionQueryParam
Adds a `key` name for a query parameter that should be subject to [secure strings](https://approov.io/docs/latest/approov-usage-documentation/#secure-strings) substitution. This means that if the query parameter is present in a URL then the value will be used as a key to look up a secure string value which will be substituted as the query parameter value instead. This allows easy migration to the use of secure strings.

> **Note**: The package inserts secure strings into the URL exactly as they are returned by the Approov cloud. It does **not** automatically apply URL encoding. If your secure strings contain reserved characters (like `&`, `=`, `#`, or spaces), you must ensure they are properly URL-encoded when adding them via the Approov CLI to avoid mangling the query parameters.

**Java:**
```Java
void addSubstitutionQueryParam(String key)
```

**Kotlin:**
```kotlin
fun addSubstitutionQueryParam(key: String)
```

## removeSubstitutionQueryParam
Removes a query parameter `key` name previously added using `addSubstitutionQueryParam`.

**Java:**
```Java
void removeSubstitutionQueryParam(String key)
```

**Kotlin:**
```kotlin
fun removeSubstitutionQueryParam(key: String)
```

## getSubstitutionQueryParams
Gets the map of query parameter keys to compiled regex patterns currently subject to secure string substitution.

This throws `IllegalStateException` if `ApproovService` is not initialized.

**Java:**
```java
Map<String, Pattern> getSubstitutionQueryParams()
```

**Kotlin:**
```kotlin
fun getSubstitutionQueryParams(): Map<String, Pattern>
```

## addExclusionURLRegex
Adds an exclusion URL [regular expression](https://regex101.com/) via the `urlRegex` parameter. If a URL for a request matches this regular expression then it will not be subject to any Approov protection.

**Java:**
```Java
void addExclusionURLRegex(String urlRegex)
```

**Kotlin:**
```kotlin
fun addExclusionURLRegex(urlRegex: String)
```

Note that this facility must be used with *EXTREME CAUTION* due to the impact of dynamic pinning. Pinning may be applied to all domains added using Approov, and updates to the pins are received when an Approov fetch is performed. If you exclude some URLs on domains that are protected with Approov, then these will be protected with Approov pins but without a path to update the pins until a URL is used that is not excluded. Thus you are responsible for ensuring that there is always a possibility of calling a non-excluded URL, or you should make an explicit call to fetchToken if there are persistent pinning failures. Conversely, use of those option may allow a connection to be established before any dynamic pins have been received via Approov, thus potentially opening the channel to a MitM.

## removeExclusionURLRegex
Removes an exclusion URL regular expression (`urlRegex`) previously added using `addExclusionURLRegex`.

**Java:**
```Java
void removeExclusionURLRegex(String urlRegex)
```

**Kotlin:**
```kotlin
fun removeExclusionURLRegex(urlRegex: String)
```

## getExclusionURLRegexs
Gets the current map of exclusion URL regular expressions.

This throws `IllegalStateException` if `ApproovService` is not initialized.

**Java:**
```java
Map<String, Pattern> getExclusionURLRegexs()
```

**Kotlin:**
```kotlin
fun getExclusionURLRegexs(): Map<String, Pattern>
```

## prefetch
Allows an Approov fetch operation to be performed as early as possible. This permits a token or secure strings to be available while an application might be loading resources or is awaiting user input. Since the initial fetch is the most expensive the prefetch can hide the most latency.

**DEPRECATED**: This method is now automatically called when the service is initialized.

**Java:**
```Java
void prefetch()
```

**Kotlin:**
```kotlin
fun prefetch()
```

## precheck
Performs a precheck to determine if the app will pass attestation. This requires [secure strings](https://approov.io/docs/latest/approov-usage-documentation/#secure-strings) to be enabled for the account, although no strings need to be set up. 

**Java:**
```Java
void precheck() throws ApproovException
```

**Kotlin:**
```kotlin
@Throws(ApproovException::class)
fun precheck()
```

This throws `ApproovException` if the precheck failed. This will likely require network access so may take some time to complete, and should not be called from the UI thread.

## getDeviceID
Gets the [device ID](https://approov.io/docs/latest/approov-usage-documentation/#extracting-the-device-id) used by Approov to identify the particular device that the SDK is running on. Note that different Approov apps on the same device will return a different ID. Moreover, the ID may be changed by an uninstall and reinstall of the app.

**Java:**
```Java
String getDeviceID() throws ApproovException
```

**Kotlin:**
```kotlin
@Throws(ApproovException::class)
fun getDeviceID(): String
```

This throws `ApproovException` if there was a problem obtaining the device ID.

## setDataHashInToken
Directly sets the [token binding](https://approov.io/docs/latest/approov-usage-documentation/#token-binding) hash for subsequently fetched Approov tokens. If the hash is different from any previously set value then this will cause the next token fetch operation to fetch a new token with the correct payload data hash. The resulting token is expected to carry the `pay` claim as a base64 encoded string of the SHA256 hash of the data. Note that the data is hashed locally and never sent to the Approov cloud service. This is an alternative to using `setBindingHeader` and you should not use both methods at the same time.

**Java:**
```Java
void setDataHashInToken(String data) throws ApproovException
```

**Kotlin:**
```kotlin
@Throws(ApproovException::class)
fun setDataHashInToken(data: String)
```

This throws `ApproovException` if there was a problem changing the data hash.

## fetchToken
Performs an Approov token fetch for the given `url`. This should be used in situations where it is not possible to use the networking interception to add the token. Note that the returned token should NEVER be cached by your app, you should call this function when it is needed.

**Java:**
```Java
String fetchToken(String url) throws ApproovException
```

**Kotlin:**
```kotlin
@Throws(ApproovException::class)
fun fetchToken(url: String): String
```

This throws `ApproovException` if there was a problem obtaining an Approov token. This may require network access so may take some time to complete, and should not be called from the UI thread.

## getMessageSignature
**DEPRECATED**, replaced by `getAccountMessageSignature`.

**Java:**
```Java
String getMessageSignature(String message) throws ApproovException
```

**Kotlin:**
```kotlin
@Throws(ApproovException::class)
fun getMessageSignature(message: String): String
```

## getAccountMessageSignature
Gets the [account message signature](https://approov.io/docs/latest/approov-usage-documentation/#account-message-signing) for the given message. This is returned as a base64 encoded signature. This feature uses an account specific message signing key that is transmitted to the SDK after a successful fetch if the facility is enabled for the account. Note that if the attestation failed then the signing key provided is actually random so that the signature will be incorrect. An Approov token should always be included in the message being signed and sent alongside this signature to prevent replay attacks.
    
**Java:**
```java
String getAccountMessageSignature(String message) throws ApproovException
```

**Kotlin:**
```kotlin
@Throws(ApproovException::class)
fun getAccountMessageSignature(message: String): String
```

This throws `ApproovException` if no signature is available, because there has been no prior fetch or the feature is not enabled.

## getInstallMessageSignature
Gets the [install message signature](https://approov.io/docs/latest/approov-usage-documentation/#installation-message-signing) for the given message. This is returned as the base64 encoding of the signature in ASN.1 DER format. This feature uses an app install specific message signing key that is generated the first time an app launches. This signing mechanism uses an ECC key pair where the private key is managed by the secure element or trusted execution environment of the device. Where it can, Approov uses attested key pairs to perform the message signing. An Approov token should always be included in the message being signed and sent alongside this signature to prevent replay attacks.

**Java:**
```java
public static String getInstallMessageSignature(String message) throws ApproovException
```

**Kotlin:**
```kotlin
@Throws(ApproovException::class)
fun getInstallMessageSignature(message: String): String
```

This throws `ApproovException` if no signature is available, because there has been no prior fetch or the feature is not enabled.

## fetchSecureString
Fetches a [secure string](https://approov.io/docs/latest/approov-usage-documentation/#secure-strings) with the given `key` if `newDef` is `null`. Returns `null` if the `key` secure string is not defined. If `newDef` is not `null` then a secure string for the particular app instance may be defined. In this case the new value is returned as the secure string. Use of an empty string for `newDef` removes the string entry. Note that the returned string should NEVER be cached by your app, you should call this function when it is needed.

**Java:**
```Java
String fetchSecureString(String key, String newDef) throws ApproovException
```

**Kotlin:**
```kotlin
@Throws(ApproovException::class)
fun fetchSecureString(key: String, newDef: String?): String?
```

This throws `ApproovException` if there was a problem obtaining the secure string. This may require network access so may take some time to complete, and should not be called from the UI thread.

## fetchCustomJWT
Fetches a [custom JWT](https://approov.io/docs/latest/approov-usage-documentation/#custom-jwts) with the given marshaled JSON `payload`.

**Java:**
```Java
String fetchCustomJWT(String payload) throws ApproovException
```

**Kotlin:**
```kotlin
@Throws(ApproovException::class)
fun fetchCustomJWT(payload: String): String
```

This throws `ApproovException` if there was a problem obtaining the custom JWT. This may require network access so may take some time to complete, and should not be called from the UI thread.

## getLastARC
Gets the [Attestation Response Code](https://approov.io/docs/latest/approov-usage-documentation/#attestation-response-code) from the most recent token, secure string or custom JWT fetch made by this layer, from the interceptor or a direct method. Returns an empty string if no fetch has been made since initialization, if the last fetch produced no ARC, or if ARC is not enabled for the account. No network activity is performed: the value is the one the app already received, so read it after a rejected request to correlate with the backend. Prefer logging the ARC your backend observed where possible.

**Java:**
```java
String getLastARC()
```

**Kotlin:**
```kotlin
fun getLastARC(): String
```

## setInstallAttrsInToken
Sets an [install attributes token](https://approov.io/docs/latest/approov-usage-documentation/#application-installation-attributes) to be sent to the server and associated with this particular app installation for future Approov token fetches.

**Java:**
```java
void setInstallAttrsInToken(String attrs) throws ApproovException
```

**Kotlin:**
```kotlin
@Throws(ApproovException::class)
fun setInstallAttrsInToken(attrs: String)
```

# Extension classes

The following classes complete the public surface of the package. A standard integration never touches them; see [ADVANCED.md](ADVANCED.md) for when they are needed.

## ApproovServiceMutator

Interface with default methods, installed with `setServiceMutator`. Every interceptor hook declares `IOException` so that an implementation opting in to aborting a request can throw a standard network stack exception; the defaults never throw.

| Method | Default decision |
| :--- | :--- |
| `boolean handleInterceptorShouldProcessRequest(Request) throws IOException` | `false` if the URL matches an exclusion regex, else `true` |
| `boolean handleInterceptorFetchTokenResult(TokenFetchResult, String url) throws IOException` | `false` for `UNKNOWN_URL` and `UNPROTECTED_URL` (no Approov headers), `true` for every other status (token header, empty if no token, and status header) |
| `boolean handleInterceptorHeaderSubstitutionResult(TokenFetchResult, String header) throws IOException` | `true` only for `SUCCESS` |
| `boolean handleInterceptorQueryParamSubstitutionResult(TokenFetchResult, String queryKey) throws IOException` | `true` only for `SUCCESS` |
| `Request handleInterceptorProcessedRequest(Request, ApproovRequestMutations) throws IOException` | returns the request unchanged; `ApproovDefaultMessageSigning` signs here |
| `boolean supportsProtectionRefresh()` | `false`; `true` for `ApproovServiceMutator.DEFAULT` and `ApproovDefaultMessageSigning` |
| `boolean handlePinningShouldProcessRequest(Request) throws IOException` | `true` |
| `void handlePrecheckResult(TokenFetchResult) throws ApproovException` | throws for every status except `SUCCESS` and `UNKNOWN_KEY` |
| `void handleFetchTokenResult(TokenFetchResult) throws ApproovException` | throws for every status except `SUCCESS` |
| `void handleFetchSecureStringResult(TokenFetchResult, String operation, String key) throws ApproovException` | throws for every status except `SUCCESS` and `UNKNOWN_KEY` |
| `void handleFetchCustomJWTResult(TokenFetchResult) throws ApproovException` | throws for every status except `SUCCESS` |
| `static boolean isNetworkFailure(TokenFetchStatus)` | `true` for `NO_NETWORK`, `POOR_NETWORK`, `UNTRUSTED_NETWORK` |

`ApproovServiceMutator.DEFAULT` is an instance with these defaults and no message signing.

## ApproovRequestMutations

Passed to `handleInterceptorProcessedRequest`, describing what the interceptor did to the request.

| Method | Meaning |
| :--- | :--- |
| `String getTokenHeaderKey()` | name of the token header added, or `null` if none |
| `String getTokenHeaderPrefix()` | prefix placed before the token, empty if none |
| `String getTraceIDHeaderKey()` | name of the trace header added, or `null` |
| `String getStatusHeaderKey()` | name of the status header added, or `null` if disabled |
| `List<String> getSubstitutionHeaderKeys()` | headers whose values were substituted with secure strings, or `null` |
| `String getOriginalURL()` | the URL before query parameter substitution, or `null` |
| `List<String> getSubstitutionQueryParamKeys()` | query parameters substituted, or `null` |
| `void setTokenHeaderKey(String)` | sets the token header name |
| `void setTokenHeaderPrefix(String)` | sets the token prefix |
| `void setTraceIDHeaderKey(String)` | sets the trace header name |
| `void setStatusHeaderKey(String)` | sets the status header name |
| `void setSubstitutionHeaderKeys(List<String>)` | sets the substituted header names |
| `void setSubstitutionQueryParamResults(String originalURL, List<String> keys)` | sets the pre-substitution URL and the substituted query parameters |

The setters are used by the interceptor and by tests that construct a mutations object for a custom mutator; a mutator receiving the object reads it and does not need to call them.

## SignatureParameters

`io.approov.util.sig.SignatureParameters` holds the covered components and the signature parameters of one signature (RFC 9421 section 2.3). It is what `SignatureParametersFactory.setBaseParameters` takes, what `generateDefaultSignatureParametersFactory(SignatureParameters)` accepts, and what a factory subclass returns from `buildSignatureParameters`.

| Method | Meaning |
| :--- | :--- |
| `SignatureParameters()` | empty parameter set |
| `SignatureParameters(SignatureParameters base)` | copy of the components, parameters and debug flag of another set |
| `SignatureParameters addComponentIdentifier(String)` / `(StringItem)` | cover a component: a derived component such as `ComponentProvider.DC_METHOD` (`@method`) or `DC_TARGET_URI` (`@target-uri`), or a header name |
| `boolean containsComponentIdentifier(String)` / `(StringItem)` | whether a component is covered |
| `String getAlg()` / `SignatureParameters setAlg(String)` | signature algorithm; left unset by the default factory so that one signature per configured algorithm is produced, set explicitly to produce a single signature |
| `Long getCreated()` / `setCreated(Long)` | the `created` parameter, seconds since the epoch |
| `Long getExpires()` / `setExpires(Long)` | the `expires` parameter |
| `String getKeyid()` / `setKeyid(String)` | the `keyid` parameter, unused by Approov signatures |
| `String getNonce()` / `setNonce(String)` | the `nonce` parameter |
| `String getTag()` / `setTag(String)` | the `tag` parameter |
| `Object getCustomParameter(String)` / `setCustomParameter(String, Object)` | any other signature parameter |
| `SignatureParameters setParameters(Map<String, Object>)` | replaces all signature parameters |
| `boolean isDebugMode()` / `void setDebugMode(boolean)` | when true the signer adds `Signature-Base-Digest`, a SHA-256 of each signature base, for verifier debugging |
| `StringItem toComponentIdentifier()` | the `@signature-params` identifier |
| `InnerList toComponentValue()` | the `Signature-Input` member value for this set |
| `static SignatureParameters fromDictionaryEntry(Dictionary, String sigId)` | parses a `Signature-Input` member back into a parameter set |

## ApproovDefaultMessageSigning

The out-of-the-box mutator (see `createDefaultServiceMutator`). Public surface beyond the mutator hooks:

| Member | Meaning |
| :--- | :--- |
| `ApproovDefaultMessageSigning()` | constructs a signer with no factory; install one with `setDefaultFactory` |
| `ApproovDefaultMessageSigning setDefaultFactory(SignatureParametersFactory)` | factory used for every host without a host factory |
| `ApproovDefaultMessageSigning putHostFactory(String host, SignatureParametersFactory)` | factory for one host (authority) |
| `static SignatureParametersFactory generateDefaultSignatureParametersFactory()` | the default factory: both signatures, method and target URI, token, trace and status headers, `Authorization`, `Content-Length`, `Content-Type` when present, optional SHA-256 body digest, `created`, 15 second `expires` |
| `static SignatureParametersFactory generateDefaultSignatureParametersFactory(SignatureParameters base)` | as above over a custom base component set |
| `DIGEST_SHA256`, `DIGEST_SHA512` | body digest algorithms |
| `ALG_ES256`, `ALG_HS256` | signature algorithms (`ecdsa-p256-sha256`, `hmac-sha256`) |
| `SIG_ID_INSTALL`, `SIG_ID_ACCOUNT` | dictionary member names `install` and `account` |
| `RequiredBodyDigestException` | `IllegalStateException` thrown when a required body digest cannot be generated |

## ApproovDefaultMessageSigning.SignatureParametersFactory

Builds the signature parameters for each request. All setters return the factory for chaining. A factory is shared by every request it is installed for, so configure it fully before installing it and use a separate instance per distinct configuration.

| Method | Meaning |
| :--- | :--- |
| `setBaseParameters(SignatureParameters)` | components always covered |
| `setUseInstallMessageSigning()` | produce the install signature only |
| `setUseAccountMessageSigning()` | produce the account signature only |
| `setUseInstallAndAccountMessageSigning()` | produce both; this is also the state of a newly constructed factory |
| `List<String> getAlgs()` | the configured algorithms in emission order; throws `IllegalStateException` if none |
| `setAddCreated(boolean)` | add the `created` parameter |
| `setExpiresLifetime(long seconds)` | add `expires` this many seconds after `created`; `0` omits it |
| `setAddApproovTokenHeader(boolean)` | cover the token header |
| `setAddApproovTraceIDHeader(boolean)` | cover the trace header when present |
| `setAddApproovStatusHeader(boolean)` | cover the status header when present |
| `addOptionalHeaders(String...)` | cover these headers when present |
| `setBodyDigestConfig(String algorithm, boolean required)` | compute `Content-Digest` with `DIGEST_SHA256` or `DIGEST_SHA512`, or `null` for none; `required` fails the request when the digest cannot be generated |
| `protected SignatureParameters buildSignatureParameters(OkHttpComponentProvider, ApproovRequestMutations)` | override point; a returned parameter set with an explicit `alg` produces that single signature |
