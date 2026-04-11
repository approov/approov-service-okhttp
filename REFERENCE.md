# Reference
This provides a reference for all of the static methods defined on `ApproovService`. These are available if you import:

**Java:**
```Java
import io.approov.service.okhttp.ApproovService;
```

**Kotlin:**
```kotlin
import io.approov.service.okhttp.ApproovService
```

Various methods may throw an `ApproovException` if there is a problem. The method `getMessage()` provides a descriptive message.

If a method throws an `ApproovNetworkException` (a subclass of `ApproovException`) then this indicates the problem was caused by a networking issue, and a user initiated retry should be allowed.

If a method throws an `ApproovRejectionException` (a subclass of `ApproovException`) the this indicates the problem was that the app failed attestation. An additional method `getARC()` provides the [Attestation Response Code](https://approov.io/docs/latest/approov-usage-documentation/#attestation-response-code), which could be provided to the user for communication with your app support to determine the reason for failure, without this being revealed to the end user. The method `getRejectionReasons()` provides the [Rejection Reasons](https://approov.io/docs/latest/approov-usage-documentation/#rejection-reasons) if the feature is enabled, providing a comma separated list of reasons why the app attestation was rejected.

## initialize
Initializes the Approov SDK and thus enables the Approov features. The `config` will have been provided in the initial onboarding or email or can be [obtained](https://approov.io/docs/latest/approov-usage-documentation/#getting-the-initial-sdk-configuration) using the approov CLI. This will generate an error if a second attempt is made at initialization with a different `config`.

**Java:**
```Java
void initialize(Context context, String config)
```

**Kotlin:**
```kotlin
fun initialize(context: Context, config: String)
```

The [application context](https://developer.android.com/reference/android/content/Context#getApplicationContext()) must be provided using the `context` parameter.

It is possible to pass an empty `config` string to bypass Approov SDK initialization. In that case the service layer still reports itself as initialized, but any `OkHttpClient` obtained from it behaves as a plain client with no Approov token injection, message signing, secure strings, or pinning.

This empty-config mode is intended as a bootstrap or bypass state for advanced integrations. A later call to `initialize()` with a valid non-empty config string is allowed and will then enable the native Approov SDK at runtime. By contrast, reinitializing from one non-empty config string to a different non-empty config string is still rejected unless you are intentionally using a supported same-config `reinit...` flow.

Initialization comments starting with `options:` should be treated as initial-call options, not as a repeated runtime update path. Repeated same-config `options:...` calls may fail at the native SDK level.

An alternative initialization function allows to provide further options in the `comment` parameter. Please refer to the [Approov SDK documentation](https://approov.io/docs/latest/approov-direct-sdk-integration/#sdk-initialization-options) for details.

**Java:**
```java
void initialize(Context context, String config, String comment)
```

**Kotlin:**
```kotlin
fun initialize(context: Context, config: String, comment: String)
```

## isInitialized
Returns whether the service layer itself has been initialized.

**Java:**
```java
boolean isInitialized()
```

**Kotlin:**
```kotlin
fun isInitialized(): Boolean
```

This reports the state of the service layer, not whether Approov protection is currently active. If initialization used an empty `config` string then this returns `true`, while the layer still operates as a plain `OkHttpClient` wrapper without Approov features.

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

This returns `true` only if the service layer has been initialized with a valid, non-empty configuration string. If initialized with an empty string, or not initialized at all, it returns `false`.

## setApproovInterceptorExtensions

**OBSOLETED**: Use `setServiceMutator` instead.

Sets the interceptor extensions callback handler. This facility supports message signing that is independent from the rest of the attestation flow. The default ApproovService layer issues no callbacks. Provide a non-null handler to add functionality to the attestation flow. The configuration used to control installation message signing is passed in the `callbacks` parameter. The behavior of the provided configuration must remain constant while in use by the ApproovService. Passing `null` to this method will disable message signing.

**Java:**
```java
void setApproovInterceptorExtensions(ApproovInterceptorExtensions callbacks)
```

**Kotlin:**
```kotlin
fun setApproovInterceptorExtensions(callbacks: ApproovInterceptorExtensions)
```

Provide an ApproovDefaultMessageSigning object instantiated as shown below to enable installation message signing:

**Java:**
```java
    ApproovService.setApproovInterceptorExtensions(
        new ApproovDefaultMessageSigning().setDefaultFactory(
            ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()));
```

**Kotlin:**
```kotlin
    ApproovService.setApproovInterceptorExtensions(
        ApproovDefaultMessageSigning().setDefaultFactory(
            ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()))
```

This default setup provides a basic signature mechanism that is not specific to the requests that are issued by an app.

The default signature parameter factory returned by `ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory` can be customized by calling further methods. Please refer to the code for the `SignatureParametersFactory` class in the [approov-service-okhttp](https://github.com/approov/approov-service-okhttp) repository's [`ApproovDefaultMessageSigning.java`](https://github.com/approov/approov-service-okhttp/blob/main/approov-service/src/main/java/io/approov/service/okhttp/ApproovDefaultMessageSigning.java) source file for information on the available options. Alternatively, you can extend the `SignatureParametersFactory` class and provide an object of the derived class as the argument to `ApproovDefaultMessageSigning().setDefaultFactory`.

Additionally, you can provide a custom implementation of the [`ApproovInterceptorExtensions`](https://github.com/approov/approov-service-okhttp/blob/main/approov-service/src/main/java/io/approov/service/okhttp/ApproovInterceptorExtensions.java) interface to have full control of the message signing. Please see the implementation of the class `ApproovDefaultMessageSigning` in [`ApproovDefaultMessageSigning.java`](https://github.com/approov/approov-service-okhttp/blob/main/approov-service/src/main/java/io/approov/service/okhttp/ApproovDefaultMessageSigning.java) for an example.

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

You must initialize the service layer before calling this method. If initialization used an empty config string then this provides a plain `OkHttpClient` without any Approov protection.

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

## setProceedOnNetworkFail
If the provided `proceed` value is `true` then this indicates that the network interceptor should proceed anyway if it is not possible to obtain an Approov token due to a networking failure. If this is called then the backend API can receive calls without the expected Approov token header being added, or without header/query parameter substitutions being made. This should only ever be used if there is some particular reason, perhaps due to local network conditions, that you believe that traffic to the Approov cloud service will be particularly problematic.

**Java:**
```Java
void setProceedOnNetworkFail(boolean proceed)
```

**Kotlin:**
```kotlin
fun setProceedOnNetworkFail(proceed: Boolean)
```

**OBSOLETED**: Use `setServiceMutator` instead to control this behavior.


Note that this should be used with *CAUTION* because it may allow a connection to be established before any dynamic pins have been received via Approov, thus potentially opening the channel to a MitM.

## setUseApproovStatusIfNoToken
If the provided `shouldUse` value is `true` then this indicates that the Approov fetch status (e.g. "NO_NETWORK", "MITM_DETECTED") should be used as the token header value if the actual token fetch fails or returns an empty token. This allows passing error condition information to the backend via the Approov-Token header, which might otherwise be empty or missing.

**Java:**
```Java
void setUseApproovStatusIfNoToken(boolean shouldUse)
```

**Kotlin:**
```kotlin
fun setUseApproovStatusIfNoToken(shouldUse: Boolean)
```

## setServiceMutator
Sets the `ApproovServiceMutator` instance to handle callbacks from the ApproovService implementation. This facility enables customization of ApproovService operations at key points in the configuration and attestation flows.

**Java:**
```java
void setServiceMutator(ApproovServiceMutator mutator)
```

**Kotlin:**
```kotlin
fun setServiceMutator(mutator: ApproovServiceMutator?)
```

Passing `null` (or omitting the parameter in Java) reinstates the default behavior.

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

## setApproovHeader
Sets the `header` that the Approov token is added on, as well as an optional `prefix` String (such as "`Bearer `"). Set `prefix` to the empty string if it is not required. By default the token is provided on `Approov-Token` with no prefix.

**Java:**
```Java
void setApproovHeader(String header, String prefix)
```

**Kotlin:**
```kotlin
fun setApproovHeader(header: String, prefix: String?)
```

## getApproovTokenHeader
Gets the name of the header used to carry the Approov token.

**Java:**
```java
String getApproovTokenHeader()
```

**Kotlin:**
```kotlin
fun getApproovTokenHeader(): String
```

## getApproovTokenPrefix
Gets any prefix string (e.g., "Bearer ") being added to the Approov token header value.

**Java:**
```java
String getApproovTokenPrefix()
```

**Kotlin:**
```kotlin
fun getApproovTokenPrefix(): String
```

## setApproovTraceIDHeader
Sets the header name used to provide the optional Approov TraceID debug value. Passing `null` disables the TraceID header.

**Java:**
```java
void setApproovTraceIDHeader(String header)
```

**Kotlin:**
```kotlin
fun setApproovTraceIDHeader(header: String?)
```

## getApproovTraceIDHeader
Gets the header name currently used for the Approov TraceID. Returns `null` if disabled.

**Java:**
```java
String getApproovTraceIDHeader()
```

**Kotlin:**
```kotlin
fun getApproovTraceIDHeader(): String?
```

## setBindingHeader
Sets a binding `header` that may be present on requests being made. This is for the [token binding](https://approov.io/docs/latest/approov-usage-documentation/#token-binding) feature. A header should be chosen whose value is unchanging for most requests (such as an Authorization header). If the `header` is present, then its SHA256 hash is supplied to Approov so the issued token can carry the corresponding `pay` claim and be bound to the value. This may then be verified by the backend API integration.

**Java:**
```Java
void setBindingHeader(String header)
```

**Kotlin:**
```kotlin
fun setBindingHeader(header: String)
```

## addSubstitutionHeader
Adds the name of a `header` which should be subject to [secure strings](https://approov.io/docs/latest/approov-usage-documentation/#secure-strings) substitution. This means that if the `header` is present then the value will be used as a key to look up a secure string value which will be substituted into the `header` value instead. This allows easy migration to the use of secure strings. A `requiredPrefix` may be specified to deal with cases such as the use of "`Bearer `" prefixed before values in an authorization header. Set `requiredPrefix` to `null` if it is not required.

**Java:**
```Java
void addSubstitutionHeader(String header, String requiredPrefix)
```

**Kotlin:**
```kotlin
fun addSubstitutionHeader(header: String, requiredPrefix: String?)
```

## removeSubstitutionHeader
Removes a `header` previously added using `addSubstitutionHeader`.

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
Gets the set of query parameter keys currently subject to secure string substitution.

**Java:**
```java
Set<String> getSubstitutionQueryParams()
```

**Kotlin:**
```kotlin
fun getSubstitutionQueryParams(): Set<String>
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
Obtains the last [Attestation Response Code](https://ext.approov.io/docs/latest/approov-usage-documentation/#attestation-response-code) provided a network request to the Approov servers has succeeded. 

**Java:**
```Java
String getLastARC()
```

**Kotlin:**
```kotlin
fun getLastARC(): String
```

In the event of no network available this function returns an empty string. This function should be used with *CAUTION* and instead rely on a customized error response from the server which includes the `ARC` code if one is available. 

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
