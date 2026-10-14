# Reference
This page lists every static method of `ApproovService`, the entry point of the Approov Package for OkHttp. To use them, import:

**Java:**
```Java
import io.approov.service.okhttp.ApproovService;
```

**Kotlin:**
```kotlin
import io.approov.service.okhttp.ApproovService
```

The rest of this introduction explains what happens to requests sent through the client, and which exceptions your app can see. The methods follow it.

A request sent through an `OkHttpClient` from `getOkHttpClient` sometimes cannot be protected, for example because no token could be fetched. Whether it is still sent is decided by the installed [service mutator](ADVANCED.md#service-mutators), an `ApproovServiceMutator`. See [setServiceMutator](#setservicemutator) and [what happens by default](ADVANCED.md#what-happens-by-default).

* In 3.7.0 the default, `ApproovServiceMutator.DEFAULT`, is `CLOSE_FAILURE`. It makes the same decisions as 3.5.x. A request goes ahead on `SUCCESS` and `NO_APPROOV_SERVICE`. Otherwise it fails with one of the `ApproovException` subclasses listed below.
* With `ApproovServiceMutator.ALWAYS_PROCEED` the request always goes ahead. If it could not be protected, it carries no `Approov-Token` header, no secure string and no signature. It carries only the fetch status, on the `Approov-Status` header. This becomes the default in 4.0.0.

Some requests are never failed by a token fetch:

* A token fetch failure for a host that is not one of your Approov API domains goes out unchanged. Your API domains are the keys of the SDK pin set, not counting the `*` key. If the pin set is empty or cannot be read, no host counts. No standard mutator aborts such a request. An exception raised there by the `CLOSE_FAILURE` decisions is ignored, even in a custom mutator that inherits or calls them. Only an exception your own mutator throws from `handleInterceptorFetchTokenResult` aborts it, which is an opt-in.
* A request to an API you added with `-noApproovToken` (status `UNPROTECTED_URL`) gets its secure strings and its pins, but no Approov header.
* A request to a host you have not added to Approov (status `UNKNOWN_URL`) is sent unchanged.

Whatever mutator is installed, these other exceptions can reach your code from a request:

* Your app's own configuration errors. A required body digest that cannot be generated throws `ApproovDefaultMessageSigning.RequiredBodyDigestException`. An unsupported signing algorithm throws `ApproovException`. Both are `IOException`s, and they fail every request they apply to.
* Pinning failures on your API domains throw `javax.net.ssl.SSLPeerUnverifiedException`, as for any OkHttp pinning failure.
* An abort your app opted in to through a service mutator throws a standard network stack exception (`IOException` or a subclass), never an Approov type.

An exception the Approov SDK throws while a request is processed fails that request with an `ApproovException`, whose cause is the SDK's exception. This covers the token fetch, a secure string substitution, token binding, the configuration update and a pin rebuild the SDK requests.

If the SDK cannot give the pins for a connection check, that is a pinning failure. The request fails with `SSLPeerUnverifiedException("Approov pinning: pins unavailable for <host>")`, with the SDK's exception as its cause. This is logged once at error level.

Secure string problems are handled like this:

* A secure string that cannot be obtained never fails a request under either standard mutator, whatever its status. The status in lowercase replaces the placeholder. A debug line names the header or query parameter, with the status, the ARC and the rejection reasons.
* A secure string that a header cannot carry is not substituted. That is anything other than tab and printable ASCII.
* A secure string that OkHttp would not carry unchanged in a query parameter is not substituted either. That is one with a tab, LF, FF or CR, which OkHttp strips, or with an `&` or `#`.
* In those two cases the placeholder stays and the request goes ahead under every mutator. A warning names the header or query parameter, never the value.
* A placeholder left because the request is not `https`, or because its URL is `BAD_URL`, is logged as a warning.

Header values are checked too:

* A value your app supplies that a header cannot carry fails the request with `ApproovException("Approov header <name>: invalid character in value")`. Examples are a token prefix, or a header your mutator sets. The exception has no cause and never quotes the value.
* A token or trace ID issued by the SDK that a header cannot carry fails the request with an `ApproovException`. It reports an SDK problem and names the header, never quoting the value.

Only an `IOException` ever reaches `execute()` or `Callback.onFailure`, and nothing is rethrown on OkHttp's dispatcher thread. If a message signing SDK call fails, only that signature is left out.

The direct methods (`precheck`, `fetchToken`, `fetchSecureString`, `fetchCustomJWT` and so on) return a value to your code. They report a failure with the exceptions described below.

Before `initialize`, and in [bypass mode](ADVANCED.md#bypass-initialization), a request made through the `OkHttpClient` goes out without any Approov processing and without Approov pinning. It is neither failed nor held.

Many methods throw an `ApproovException` when there is a problem. It is an `IOException`, so it is a checked exception, and `getMessage()` gives a description. It has these subclasses:

* `ApproovFetchStatusException`, a subclass of `ApproovException`, carries the SDK fetch status in `getTokenFetchStatus()`.
* `ApproovNetworkException`, a subclass of `ApproovFetchStatusException`, means a network problem caused the failure (`NO_NETWORK`, `POOR_NETWORK` or `UNTRUSTED_NETWORK`). Your app should let the user retry. This class is deprecated: catch `ApproovFetchStatusException` and check `getTokenFetchStatus()` instead. It is still thrown, so existing handlers keep working.
* `ApproovRejectionException`, a subclass of `ApproovFetchStatusException`, means the app failed attestation. `getARC()` returns the [Attestation Response Code](https://approov.io/docs/latest/approov-usage-documentation/#attestation-response-code). Your user can give it to your app support, who can then find the reason for the failure without it being revealed to the user. `getRejectionReasons()` returns the [Rejection Reasons](https://approov.io/docs/latest/approov-usage-documentation/#rejection-reasons) as a comma separated list, if the feature is enabled.

Every method that calls the Approov SDK first checks [isApproovProtectionEnabled](#isapproovprotectionenabled). While it is `false`, the method throws `ApproovException` with the message `<method>: Approov protection not enabled`, without calling the SDK. These methods are `setDevKey`, `precheck`, `getDeviceID`, `setDataHashInToken`, `fetchToken`, `getMessageSignature`, `getAccountMessageSignature`, `getInstallMessageSignature`, `fetchSecureString`, `fetchCustomJWT` and `setInstallAttributes`.

## initialize
Initializes the Approov SDK, which turns on the Approov features.

The `config` parameter is your Approov account ID. It is in your onboarding email, and the Approov CLI gives it with `approov sdk -getConfigString` (the CLI and the SDK call it the SDK config string). It identifies your account to the SDK. It is not a secret, and it is the same for every app in your account. See [obtaining it](https://approov.io/docs/latest/approov-usage-documentation/#getting-the-initial-sdk-configuration).

The package passes every non-empty account ID to the Approov SDK, with the comment exactly as you passed it, and the SDK decides. The package does not store or compare the account ID.

* The first initialization succeeds.
* A call with the same account ID and comment again is reported by the SDK as already initialized, and `initialize` returns at once. So every part of your app that uses Approov may call it.
* A `null` context or account ID throws `IllegalArgumentException` (`initialize: null context` or `initialize: null config`).
* Anything else the SDK rejects throws the SDK's own `IllegalStateException` or `IllegalArgumentException`, unchanged. That includes a different account ID or comment, and an account ID that did not reach the app intact. The package state stays as it was.

`initialize` never resets your configuration. These settings keep the values set before or after it, by any caller: the token, trace, status and binding headers, substitutions, exclusions, the service mutator, message signing and its host factories, the stale protection refresh period, the logging level and the `OkHttpClient` builders. `initialize` only changes what [isApproovServiceEnabled](#isapproovserviceenabled) and [isApproovProtectionEnabled](#isapproovprotectionenabled) report.

Initialize before the first request. A request made before any `initialize` goes out without Approov processing.

This is the standard form, and the one most apps should use. The `comment` parameter defaults to `null`.

**Java:**
```Java
void initialize(Context context, String config)
```

**Kotlin:**
```kotlin
fun initialize(context: Context, config: String)
```

Pass the [application context](https://developer.android.com/reference/android/content/Context#getApplicationContext()) as the `context` parameter.

You can pass an empty string instead of the account ID to skip initializing the Approov SDK. This is bypass mode:

* `isApproovServiceEnabled()` returns `true` and `isApproovProtectionEnabled()` returns `false`.
* The SDK is not called.
* Requests through the `OkHttpClient` go out with no Approov token, message signing, secure strings or Approov pinning. Connections are checked by the operating system's trust alone, even if another caller in your app's process initialized the SDK.

Bypass mode is meant as a starting state for advanced integrations. A later `initialize` with the account ID is passed to the SDK, and turns on Approov protection if the SDK accepts it. If the SDK rejects it, the package stays in bypass mode. An empty string after protection is on is ignored and not passed to the SDK. See [bypass initialization](ADVANCED.md#bypass-initialization).

To pass a `comment` to the native SDK, use the extended form. You might do this to pass `options:...` startup flags, or to start a `reinit...` flow.

**Java:**
```java
void initialize(Context context, String config, String comment)
```

**Kotlin:**
```kotlin
fun initialize(context: Context, config: String, comment: String?)
```

The `comment` parameter is passed directly to the native Approov SDK. Its main uses:
* A string starting with `options:`, passed during the initial setup, forwards custom startup options to the native SDK.
* A string starting with `reinit` triggers native re-initialization on a later call with the same account ID.
* `null`, or the two-argument form, means no comment. This is the default. Note that `null` and `""` are different comments to the SDK.

See the [Approov SDK documentation](https://approov.io/docs/latest/approov-direct-sdk-integration/#sdk-initialization-options) for the full list of comment values.


## isApproovServiceEnabled
Returns whether the package is enabled, that is, whether it is processing requests.

**Java:**
```java
boolean isApproovServiceEnabled()
```

**Kotlin:**
```kotlin
fun isApproovServiceEnabled(): Boolean
```

Returns `true` once any `initialize` has succeeded, including in bypass mode (an empty string instead of the account ID). Returns `false` only if no `initialize` has succeeded.

A later call that is rejected, for example one with a different account ID, throws and leaves the previous state in place. So this method keeps returning `true`. Use `isApproovProtectionEnabled()` to tell bypass mode from protected mode.

## isApproovProtectionEnabled
Returns whether Approov protection is enabled.

**Java:**
```java
boolean isApproovProtectionEnabled()
```

**Kotlin:**
```kotlin
fun isApproovProtectionEnabled(): Boolean
```

Returns `true` once the Approov SDK has been initialized with the account ID and Approov protection is active. Returns `false` before initialization and in bypass mode (an empty string).

While this returns `false`, the methods that call the Approov SDK, such as `fetchToken`, `precheck` and `fetchSecureString`, throw `ApproovException` without calling the SDK.

After a successful `initialize`, check `isApproovServiceEnabled()` and then this method. This one is `false` only in deliberate bypass mode.


## getOkHttpClient
Gets the default `OkHttpClient` with Approov protection.

The client:
* adds the Approov token in a header to requests
* performs any header or query parameter substitutions
* pins the connections (see [connection validation](ADVANCED.md#connection-validation)). On every request it checks the current pins for the request's host against the certificate chain that the trust manager validated.

OkHttp 4.12 or later is required. OkHttp 3.x is not supported.

A pin failure fails only that request. The connection is not closed, so other hosts that OkHttp has merged onto the same HTTP/2 connection are not affected. If the SDK cannot give the pins, the connection check fails closed with `SSLPeerUnverifiedException` (`Approov pinning: pins unavailable for <host>`), with the SDK's exception as its cause.

The client is built the first time you ask for it, and is cached until something changes.

**Java:**
```Java
OkHttpClient getOkHttpClient()
```

**Kotlin:**
```kotlin
fun getOkHttpClient(): OkHttpClient
```

You can get the client at any time, including before `initialize`. While Approov protection is not enabled (before `initialize` and in bypass mode), its requests go out without any Approov processing or Approov pinning. The same client protects its requests once `initialize` turns protection on.

Use `setOkHttpClientBuilder` to set any special builder properties. To use several different builders in your app, give each one a name when you call `setOkHttpClientBuilder`. Then get a client for a named builder with:

**Java:**
```Java
OkHttpClient getOkHttpClient(String builderName)
```

**Kotlin:**
```kotlin
fun getOkHttpClient(builderName: String): OkHttpClient
```

## setOkHttpClientBuilder
Sets the `OkHttpClient.Builder` used to build the default Approov `OkHttpClient`. Use it to add your own interceptors and properties. You can call it before or after `initialize`, which keeps it. It takes effect on the next `getOkHttpClient` call.

`getOkHttpClient` adds the Approov interceptors to the builder you pass, after removing any it added before:
* The Approov token interceptor goes after the builder's application interceptors.
* The Approov freshness and pinning interceptors go before the builder's network interceptors.

So your code at the network layer, such as a logger or an inspector, sees each attempt only after Approov has stripped or refreshed its protection. It also means the builder's network interceptors run after signing. They must not change a signed header, the URL or the body.

**Java:**
```Java
void setOkHttpClientBuilder(OkHttpClient.Builder builder)
```

**Kotlin:**
```kotlin
fun setOkHttpClientBuilder(builder: OkHttpClient.Builder)
```

You can also set a named builder, so that your app can use several builders:

**Java:**
```Java
void setOkHttpClientBuilder(String builderName, OkHttpClient.Builder builder)
```

**Kotlin:**
```kotlin
fun setOkHttpClientBuilder(builderName: String, builder: OkHttpClient.Builder)
```

## setServiceMutator
Installs an `ApproovServiceMutator`, which receives callbacks from `ApproovService`. A service mutator lets you customize what the package does at key points in the configuration and attestation flows. See [service mutators](ADVANCED.md#service-mutators) for the hooks and examples.

**Java:**
```java
void setServiceMutator(ApproovServiceMutator mutator)
```

**Kotlin:**
```kotlin
fun setServiceMutator(mutator: ApproovServiceMutator?)
```

The standard mutator is `ApproovServiceMutator.DEFAULT`, and passing `null` puts it back. `initialize` keeps whichever mutator is installed.

Mutators only make decisions. Installing one never turns message signing on or off (see [enableMessageSigning](#enablemessagesigning)).

The package reads the mutator at each decision point. A stale protection refresh, a redirect, a retry and the pinning hook all use the mutator installed at that moment. That may not be the one that first processed the request. So install your mutator once at startup, not while requests are in flight.

Three standard mutators are provided:

| Mutator | Decisions |
| :--- | :--- |
| `ApproovServiceMutator.CLOSE_FAILURE` | The 3.5.x token decisions, which are also the interface defaults. A token fetch goes ahead on `SUCCESS` (token, status and trace headers) and on `NO_APPROOV_SERVICE` (status header only). `UNKNOWN_URL` and `UNPROTECTED_URL` send the request with no Approov headers (`UNPROTECTED_URL` with its secure strings). `NO_NETWORK`, `POOR_NETWORK` and `UNTRUSTED_NETWORK` throw `ApproovNetworkException`. Every other status throws `ApproovFetchStatusException`, `REJECTED` included. These failure decisions apply only to a host in the SDK pin set. A failure for any other host goes out untouched. A secure string is substituted on `SUCCESS`. For every other secure string status, `UNKNOWN_KEY` included, the status in lowercase replaces the placeholder and the request goes ahead. |
| `ApproovServiceMutator.ALWAYS_PROCEED` | Never aborts. Every token fetch status except `UNKNOWN_URL` and `UNPROTECTED_URL` goes ahead with the Approov headers its status allows. On a failure status that is the status header only, never a token header, trace header, secure string or signature. A failure for a host not in the SDK pin set goes out untouched. A secure string is substituted on `SUCCESS`, and otherwise its status in lowercase replaces the placeholder. A request the SDK answers with `BAD_URL` keeps its placeholders and never gets a secure string. That is every cleartext `http` request, and an `https` URL the SDK cannot parse. |
| `ApproovServiceMutator.DEFAULT` | Points to the release's default behaviour: `CLOSE_FAILURE` in 3.7.0. In 4.0.0 it is planned to become the `ALWAYS_PROCEED` behaviour. If your app wants a fixed behaviour, name `CLOSE_FAILURE` or `ALWAYS_PROCEED` explicitly. |

**Java:**
```java
ApproovService.setServiceMutator(ApproovServiceMutator.ALWAYS_PROCEED);
```

**Kotlin:**
```kotlin
ApproovService.setServiceMutator(ApproovServiceMutator.ALWAYS_PROCEED)
```

A custom mutator gets the `CLOSE_FAILURE` decisions for every hook it does not override. To make one that never aborts, delegate the three interceptor decisions to `ALWAYS_PROCEED` (see [opting in to aborting requests](ADVANCED.md#opting-in-to-aborting-requests)).

A custom mutator may choose to abort a request from any interceptor hook. To do so it throws a standard network stack exception (`java.io.IOException` or a platform subclass), never an Approov type, so that the abort reaches your app as an ordinary network failure. That is an explicit decision for you to make.

The aborts that `CLOSE_FAILURE` makes keep the 3.5.x Approov exception types, which are all subclasses of `IOException`. So an existing app catches the same exceptions as before.

## enableMessageSigning
Turns message signing on. [Message signing](ADVANCED.md#message-signing) is off by default in 3.7.0, and is planned to be on by default from 4.0.0. You will still be able to turn it off with `disableMessageSigning()`.

Once it is on, every request that carries the Approov token header is signed (RFC 9421). Signing happens after the service mutator's decisions, the secure string substitutions and the mutator's `handleInterceptorProcessedRequest` callback, so the signature covers what is actually sent. The request is signed again whenever its protection is reapplied: on a redirect within a protected domain, an authenticator retry or a stale protection refresh.

Signing works the same under every mutator, and `setServiceMutator` never turns it on or off. `initialize` leaves it, and any host factories, as they are.

Without an argument it uses `ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()`. That produces two signatures over the same covered components, which are the parts of the request a signature protects:
* the install signature: member `install`, `ecdsa-p256-sha256`, with a per-installation key held in the device's secure hardware
* the account signature: member `account`, `hmac-sha256`, with the account key delivered on attestation

Pass a [SignatureParametersFactory](#approovdefaultmessagesigningsignatureparametersfactory) to choose the signatures and covered components for every host that has no host factory of its own. Passing `null` selects the default factory.

**Java:**
```java
void enableMessageSigning()
void enableMessageSigning(ApproovDefaultMessageSigning.SignatureParametersFactory defaultFactory)
```

**Kotlin:**
```kotlin
fun enableMessageSigning()
fun enableMessageSigning(defaultFactory: ApproovDefaultMessageSigning.SignatureParametersFactory?)
```

## disableMessageSigning
Turns message signing off. No `Signature` or `Signature-Input` header is added to any request. The configured factories are kept for a later `enableMessageSigning`.

**Java:**
```java
void disableMessageSigning()
```

**Kotlin:**
```kotlin
fun disableMessageSigning()
```

## isMessageSigningEnabled
Returns whether message signing is enabled.

**Java:**
```java
boolean isMessageSigningEnabled()
```

**Kotlin:**
```kotlin
fun isMessageSigningEnabled(): Boolean
```

## putMessageSigningHostFactory
Signs requests to one host with the given factory, instead of the default factory passed to `enableMessageSigning`.

* The host name is matched without regard to case, and without any port.
* Passing `null` as the factory removes the host's factory, so the default applies again.
* It applies while message signing is enabled. `initialize` keeps it.

A factory is shared by every request it applies to, so use a separate instance for each distinct configuration.

**Java:**
```java
void putMessageSigningHostFactory(String hostName, ApproovDefaultMessageSigning.SignatureParametersFactory factory)
```

**Kotlin:**
```kotlin
fun putMessageSigningHostFactory(hostName: String, factory: ApproovDefaultMessageSigning.SignatureParametersFactory?)
```

## getServiceMutator
Gets the service mutator that is currently installed.

**Java:**
```java
ApproovServiceMutator getServiceMutator()
```

**Kotlin:**
```kotlin
fun getServiceMutator(): ApproovServiceMutator
```

## setDevKey
[Sets a development key](https://approov.io/docs/latest/approov-usage-documentation/#using-a-development-key) that makes your app pass attestation. Use it when the app has to be re-signed for a test environment, and would otherwise fail attestation.

**Java:**
```Java
void setDevKey(String devKey) throws ApproovException
```

**Kotlin:**
```kotlin
@Throws(ApproovException::class)
fun setDevKey(devKey: String)
```

It throws `ApproovException` if the SDK rejects the development key. Before `initialize` and in bypass mode it throws `ApproovException` with the message `setDevKey: Approov protection not enabled`, without calling the SDK.

## setTokenHeader
Sets the `header` that carries the Approov token, and an optional `prefix` such as "`Bearer `".

* By default the token goes on `Approov-Token` with no prefix.
* Pass `null` or an empty string as `prefix` if you don't need one. `null` never adds the literal text "null".
* The header is sent only on a request whose token fetch succeeded. If no token could be obtained and the mutator lets the request go ahead, the header is not sent at all. The status is reported on the status header instead (see `setStatusHeader`). Failure information is never placed in this header.
* With message signing on, a token header that is also a covered optional header (`Authorization` in the default factory) is covered only once.

**Java:**
```Java
void setTokenHeader(String header, String prefix)
```

**Kotlin:**
```kotlin
fun setTokenHeader(header: String, prefix: String?)
```

## getTokenHeader
Gets the name of the header that carries the Approov token.

**Java:**
```java
String getTokenHeader()
```

**Kotlin:**
```kotlin
fun getTokenHeader(): String
```

## getTokenPrefix
Gets the prefix, such as "Bearer ", that is added before the Approov token in its header.

**Java:**
```java
String getTokenPrefix()
```

**Kotlin:**
```kotlin
fun getTokenPrefix(): String
```

## setTraceIDHeader
Sets the name of the header that carries the optional Approov TraceID debug value. The default is `Approov-TraceID`. Passing `null` turns the TraceID header off.

**Java:**
```java
void setTraceIDHeader(String header)
```

**Kotlin:**
```kotlin
fun setTraceIDHeader(header: String?)
```

## getTraceIDHeader
Gets the name of the header used for the Approov TraceID. Returns `null` if it is turned off.

**Java:**
```java
String getTraceIDHeader()
```

**Kotlin:**
```kotlin
fun getTraceIDHeader(): String?
```

## setStatusHeader
Sets the name of the header that reports the Approov token fetch status to your backend. The default is `Approov-Status`. See [the `Approov-Status` header](ADVANCED.md#the-approov-status-header).

The value is the SDK fetch status name in lowercase, such as `success`, `no_network`, `poor_network`, `untrusted_network`, `no_approov_service`, `rejected` or `internal_error`. It is the same on Android and iOS. It always describes the token fetch, never a secure string fetch.

The header tells your backend why a particular request carries no proof of attestation, so your backend can log that against the request and act on it.

The header is sent:
* on every request Approov processes, successful ones included
* on a request with a failure status that the mutator lets go ahead. The mutator is only asked about a host in the SDK pin set, that is an API domain you added to Approov, of either kind. A failure for any other host goes out untouched.

It is never sent to a host you have not added to Approov (`UNKNOWN_URL`), or to a secrets-only API added with `-noApproovToken` (`UNPROTECTED_URL`).

With the default message signing, the header on a token-protected request is covered by the signatures. A request without a token is not signed.

Passing `null` turns the header off. A request that could not be protected is then sent with no Approov header at all, and no explanation.

**Java:**
```java
void setStatusHeader(String header)
```

**Kotlin:**
```kotlin
fun setStatusHeader(header: String?)
```

## getStatusHeader
Gets the name of the header used to report the Approov fetch status. Returns `null` if it is turned off.

**Java:**
```java
String getStatusHeader()
```

**Kotlin:**
```kotlin
fun getStatusHeader(): String?
```

## Deprecated header aliases
These older names still work, but are deprecated. Each one calls the method with the name that every Approov package now uses:

* `setApproovHeader(header, prefix)` calls `setTokenHeader`
* `setApproovTraceIDHeader(header)` calls `setTraceIDHeader`
* `getApproovTokenHeader()` calls `getTokenHeader`
* `getApproovTokenPrefix()` calls `getTokenPrefix`
* `getApproovTraceIDHeader()` calls `getTraceIDHeader`

## setLoggingLevel
Sets the level of the package's own logging. The levels are the same as in `approov-service-ios`: `io.approov.service.okhttp.ApproovLogLevel.OFF`, `ERROR`, `WARNING`, `INFO` (the default) and `DEBUG`.

Each level includes the ones before it:
* `ERROR` writes errors only.
* `WARNING` adds warnings.
* `INFO` adds information lines, such as the result of initialization.
* `DEBUG` adds the debug lines. These include the [loggable form](https://approov.io/docs/latest/approov-usage-documentation/#loggable-tokens) of each request's token, whose claims include the device ID, and the device ID from `getDeviceID`.
* `OFF` writes nothing, not even errors.

You can set the level at any time, including before `initialize`. It takes effect for requests already in flight, through clients you already have, and `initialize` never resets it. Passing `null` throws `IllegalArgumentException` and leaves the level unchanged. The Approov SDK's own logging is not affected.

You can also turn on debug logging for the `ApproovService` tag with `adb shell setprop log.tag.ApproovService DEBUG`. This raises any level except `OFF` to `DEBUG`, for every tag of the package. It lets support read the debug lines of an installed build, release builds included, without a rebuild. Run `adb shell setprop log.tag.ApproovService INFO`, or reboot, to end it. `OFF` is final: if your app turned the package's logging off, this does not override it.

**Java:**
```Java
void setLoggingLevel(ApproovLogLevel level)
```

**Kotlin:**
```kotlin
fun setLoggingLevel(level: ApproovLogLevel)
```

## setBindingHeader
Sets a binding `header` for the [token binding](https://approov.io/docs/latest/approov-usage-documentation/#token-binding) feature (see also [token binding](ADVANCED.md#token-binding)). Choose a header whose value stays the same for most requests, such as an Authorization header.

If the `header` is present on a request, its SHA256 hash is given to Approov. The issued token can then carry the matching `pay` claim, and so be bound to that value. Your backend integration can then check it.

The binding header cannot also be a secure string substitution header. That would bind the Approov token to the placeholder, while sending the substituted value to your backend. So either configuration call throws `IllegalArgumentException` when it detects the conflict. Header names are compared without regard to case.

**Java:**
```Java
void setBindingHeader(String header)
```

**Kotlin:**
```kotlin
fun setBindingHeader(header: String)
```

## setStaleProtectionRefreshPeriod
Sets how long, in milliseconds, a request can be held before it is sent until its Approov protection is refreshed. See [stale protection refresh](ADVANCED.md#stale-protection-refresh).

A request can be held between having its Approov protection applied and actually being sent. This happens if the device goes into deep sleep or doze while the request is in flight, or if your app queues requests or backs off on its own. By the time the request is sent, the Approov token and any message signature may have expired. A message signature carries `created` and `expires` timestamps.

If a request was held for longer than this period, its protection (the Approov token and any message signature) is refreshed at the network layer, just before it is sent. A refresh:
* fetches the Approov token again, which the SDK usually answers at once from its cache
* calls the service mutator's `handleInterceptorProcessedRequest` callback again
* signs the request again, if message signing is enabled

Because the callback runs again, a refresh only happens if the mutator's `supportsProtectionRefresh()` returns `true`. `ApproovServiceMutator.DEFAULT`, `CLOSE_FAILURE` and `ALWAYS_PROCEED` support it. A custom `ApproovServiceMutator` must opt in by overriding `supportsProtectionRefresh()`, once its callback is safe to run more than once per request.

Set the period comfortably below the message signature expiry, which is 15 seconds by default. Set it high enough that ordinary requests are not processed twice. The default is 3000 ms. A value of zero or less turns the refresh off.

**Java:**
```Java
void setStaleProtectionRefreshPeriod(long periodMS)
```

**Kotlin:**
```kotlin
fun setStaleProtectionRefreshPeriod(periodMS: Long)
```

## addSubstitutionHeader
Adds a `header` whose value is replaced by a [secure string](https://approov.io/docs/latest/approov-usage-documentation/#secure-strings). If the header is present on a request, its value is used as a key to look up a secure string, which then replaces the header value. This makes it easy to move existing code to secure strings. See [secure strings](ADVANCED.md#secure-strings).

* `requiredPrefix` handles values with a fixed start, such as "`Bearer `" in an authorization header. Set it to `null` if you don't need one.
* If the secure string fetch returns any status other than `SUCCESS` and the request goes ahead, the status in lowercase goes after the prefix instead (`Bearer rejected`, `unknown_key`). This is the same way the `Approov-Status` header writes statuses.
* A secure string is only substituted into a request sent over TLS (`https`) whose token fetch did not return `BAD_URL`. Any other request keeps the placeholder under every mutator, custom mutators included. No secure string is fetched, the substitution hooks are not consulted, and a warning names the header.
* It applies to requests processed after the call, including through clients you already have.

Header names are matched without regard to case. Adding the same header again replaces its configuration, and keeps the casing from the latest call. A substitution header cannot also be the token binding header. Either configuration call throws `IllegalArgumentException` when it detects the conflict.

**Java:**
```Java
void addSubstitutionHeader(String header, String requiredPrefix)
```

**Kotlin:**
```kotlin
fun addSubstitutionHeader(header: String, requiredPrefix: String?)
```

## removeSubstitutionHeader
Removes a `header` you added with `addSubstitutionHeader`. Header names are matched without regard to case.

**Java:**
```Java
void removeSubstitutionHeader(String header)
```

**Kotlin:**
```kotlin
fun removeSubstitutionHeader(header: String)
```

## getSubstitutionHeaders
Gets the headers currently subject to secure string substitution, each mapped to its required prefix.

You can call it before `initialize`. It returns a copy.

**Java:**
```java
Map<String, String> getSubstitutionHeaders()
```

**Kotlin:**
```kotlin
fun getSubstitutionHeaders(): Map<String, String>
```

## addSubstitutionQueryParam
Adds the `key` of a query parameter whose value is replaced by a [secure string](https://approov.io/docs/latest/approov-usage-documentation/#secure-strings). If the query parameter is present in a URL, its value is used as a key to look up a secure string, which then replaces the value. This makes it easy to move existing code to secure strings.

* The `key` is matched as literal text, not as a regular expression. A `.` in it matches only a `.`.
* Every occurrence of the key in a URL's query is substituted. Each value is looked up as its own secure string key, and checked on its own.
* An occurrence whose secure string OkHttp would not carry unchanged keeps its placeholder, while the others are substituted.
* An occurrence whose fetch returns any status other than `SUCCESS` becomes that status in lowercase (`rejected`, `unknown_key` and so on).
* A cleartext (`http`) request, or one whose token fetch returned `BAD_URL`, keeps every placeholder under every mutator.

A redirect target URL is used exactly as the server sent it. Its query values are never looked up, even under a substitution query parameter. If the server echoes a secure string into it, the secure string is not changed back to the placeholder (see [ADVANCED.md](ADVANCED.md#redirects-to-another-origin)).

A response to a request whose query carries a substituted secure string is never stored in the client's OkHttp `Cache`. So the URL with the secret never reaches the disk cache, and the request always goes to the network. The network interceptor marks that response `no-store`, and the token interceptor gives your app the server's own `Cache-Control` back (see [ADVANCED.md](ADVANCED.md#secure-strings-and-the-okhttp-cache)).

> **Note**: The package inserts secure strings into the URL exactly as the Approov cloud returns them. It does **not** URL-encode them. A secure string that OkHttp would not carry unchanged (one with `&`, `#`, a tab, CR or LF) is not substituted. The placeholder stays and the request goes ahead. If your secure strings contain reserved characters, store them URL-encoded when you add them with the Approov CLI.

**Java:**
```Java
void addSubstitutionQueryParam(String key)
```

**Kotlin:**
```kotlin
fun addSubstitutionQueryParam(key: String)
```

## removeSubstitutionQueryParam
Removes a query parameter `key` you added with `addSubstitutionQueryParam`.

**Java:**
```Java
void removeSubstitutionQueryParam(String key)
```

**Kotlin:**
```kotlin
fun removeSubstitutionQueryParam(key: String)
```

## getSubstitutionQueryParams
Gets the query parameter keys currently subject to secure string substitution, each mapped to its compiled regular expression pattern.

You can call it before `initialize`. It returns a copy.

**Java:**
```java
Map<String, Pattern> getSubstitutionQueryParams()
```

**Kotlin:**
```kotlin
fun getSubstitutionQueryParams(): Map<String, Pattern>
```

## addExclusionURLRegex
Adds a [regular expression](https://regex101.com/), the `urlRegex` parameter, for URLs to exclude. A request whose URL matches it gets no Approov protection at all. See [excluding URLs](ADVANCED.md#excluding-urls).

**Java:**
```Java
void addExclusionURLRegex(String urlRegex)
```

**Kotlin:**
```kotlin
fun addExclusionURLRegex(urlRegex: String)
```

An invalid regular expression throws `IllegalArgumentException`, with the `PatternSyntaxException` as its cause, and nothing is added. The exclusions you already added are kept. Every Approov package behaves this way. The exception is unchecked.

Use this with *EXTREME CAUTION*, because of the way dynamic pinning works. Pinning may apply to every domain you added to Approov, and pin updates arrive when an Approov fetch is made. If you exclude some URLs on a domain protected by Approov, those URLs still use the Approov pins. But they give the pins no way to update until a URL that is not excluded is used.

So you must make sure your app can always call a URL that is not excluded, through the client. The pins this package applies are only rebuilt when such a request's token fetch reports a configuration change. A direct call to `fetchToken` does not rebuild them.

Excluding URLs can also let a connection be made before any dynamic pins have arrived from Approov. That may open the connection to a man-in-the-middle (MitM) attack.

## removeExclusionURLRegex
Removes an exclusion regular expression (`urlRegex`) you added with `addExclusionURLRegex`.

**Java:**
```Java
void removeExclusionURLRegex(String urlRegex)
```

**Kotlin:**
```kotlin
fun removeExclusionURLRegex(urlRegex: String)
```

## getExclusionURLRegexs
Gets the current exclusion URL regular expressions, each mapped to its compiled pattern.

You can call it before `initialize`. It returns a copy.

**Java:**
```java
Map<String, Pattern> getExclusionURLRegexs()
```

**Kotlin:**
```kotlin
fun getExclusionURLRegexs(): Map<String, Pattern>
```

## precheck
Checks whether your app will pass attestation. This needs [secure strings](https://approov.io/docs/latest/approov-usage-documentation/#secure-strings) to be enabled for your account, although you don't need to set up any strings.

**Java:**
```Java
void precheck() throws ApproovException
```

**Kotlin:**
```kotlin
@Throws(ApproovException::class)
fun precheck()
```

It throws `ApproovException` if the precheck fails. That is an `ApproovFetchStatusException`, with the status in `getTokenFetchStatus()`.

It will probably need network access, so it may take some time. Don't call it from the UI thread.

Before `initialize` and in bypass mode it throws `ApproovException` with the message `precheck: Approov protection not enabled`, without calling the SDK.

## getDeviceID
Gets the [device ID](https://approov.io/docs/latest/approov-usage-documentation/#extracting-the-device-id) that Approov uses to identify the device the SDK is running on. Different Approov apps on the same device get different IDs. The ID may also change if the app is uninstalled and reinstalled.

**Java:**
```Java
String getDeviceID() throws ApproovException
```

**Kotlin:**
```kotlin
@Throws(ApproovException::class)
fun getDeviceID(): String
```

It throws `ApproovException` if there was a problem getting the device ID. Before `initialize` and in bypass mode it throws `ApproovException` with the message `getDeviceID: Approov protection not enabled`, without calling the SDK.

## setDataHashInToken
Sets the [token binding](https://approov.io/docs/latest/approov-usage-documentation/#token-binding) hash directly, for the Approov tokens fetched after it.

If the hash differs from any value set before, the next token fetch gets a new token with the new payload data hash. The token is expected to carry the `pay` claim, a base64 encoded string of the SHA256 hash of the data. The data is hashed on the device and never sent to the Approov cloud service.

This is an alternative to `setBindingHeader`. Don't use both at the same time.

**Java:**
```Java
void setDataHashInToken(String data) throws ApproovException
```

**Kotlin:**
```kotlin
@Throws(ApproovException::class)
fun setDataHashInToken(data: String)
```

It throws `ApproovException` if there was a problem changing the data hash. Before `initialize` and in bypass mode it throws `ApproovException` with the message `setDataHashInToken: Approov protection not enabled`, without calling the SDK.

## fetchToken
Fetches an Approov token for the given `url`. Use it where the client cannot add the token to a request for you. Never cache the returned token in your app. Call this method each time you need one.

**Java:**
```Java
String fetchToken(String url) throws ApproovException
```

**Kotlin:**
```kotlin
@Throws(ApproovException::class)
fun fetchToken(url: String): String
```

It throws `ApproovException` if there was a problem getting a token. When the fetch did not succeed, that is an `ApproovFetchStatusException`, with the status in `getTokenFetchStatus()`.

It may need network access, so it may take some time. Don't call it from the UI thread.

Before `initialize` and in bypass mode it throws `ApproovException` with the message `fetchToken: Approov protection not enabled`, without calling the SDK.

It does not update the pins that the client applies. Only requests made through the client do that.

## getMessageSignature
Deprecated: use `getAccountMessageSignature` instead. It is kept in the 3.7.x releases as an alias, and returns the same value as `getAccountMessageSignature`. The first call in your app's process logs a deprecation warning that names `getAccountMessageSignature`.

**Java:**
```Java
String getMessageSignature(String message) throws ApproovException
```

**Kotlin:**
```kotlin
@Throws(ApproovException::class)
fun getMessageSignature(message: String): String
```

Before `initialize` and in bypass mode it throws `ApproovException` with the message `getMessageSignature: Approov protection not enabled`, without calling the SDK.

## getAccountMessageSignature
Gets the [account message signature](https://approov.io/docs/latest/approov-usage-documentation/#account-message-signing) for the given message, as a base64 encoded string.

It uses a signing key specific to your account. The SDK receives this key after a successful fetch, if the feature is enabled for your account. If attestation failed, the key it receives is random, so the signature will be wrong. Always include an Approov token in the message you sign, and send the token alongside the signature, to prevent replay attacks.

**Java:**
```java
String getAccountMessageSignature(String message) throws ApproovException
```

**Kotlin:**
```kotlin
@Throws(ApproovException::class)
fun getAccountMessageSignature(message: String): String
```

It throws `ApproovException` if no signature is available, because there has been no fetch yet or the feature is not enabled. Before `initialize` and in bypass mode it throws `ApproovException` with the message `getAccountMessageSignature: Approov protection not enabled`, without calling the SDK.

## getInstallMessageSignature
Gets the [install message signature](https://approov.io/docs/latest/approov-usage-documentation/#installation-message-signing) for the given message. It is returned as the base64 encoding of the signature in ASN.1 DER format.

It uses a signing key specific to this installation of the app, generated the first time the app launches. The key is an ECC key pair whose private key is managed by the device's secure element or trusted execution environment. Where it can, Approov uses attested key pairs for this signing. Always include an Approov token in the message you sign, and send the token alongside the signature, to prevent replay attacks.

**Java:**
```java
public static String getInstallMessageSignature(String message) throws ApproovException
```

**Kotlin:**
```kotlin
@Throws(ApproovException::class)
fun getInstallMessageSignature(message: String): String
```

It throws `ApproovException` if no signature is available, because there has been no fetch yet or the feature is not enabled. Before `initialize` and in bypass mode it throws `ApproovException` with the message `getInstallMessageSignature: Approov protection not enabled`, without calling the SDK.

## fetchSecureString
Fetches the [secure string](https://approov.io/docs/latest/approov-usage-documentation/#secure-strings) with the given `key`, or defines a new one.

* With `newDef` set to `null`, it looks up the secure string. It returns `null` if no secure string is defined for `key`.
* With `newDef` not `null`, it may define a secure string for this particular app instance. It then returns the new value as the secure string.
* An empty string as `newDef` removes the entry.

Never cache the returned string in your app. Call this method each time you need it.

**Java:**
```Java
String fetchSecureString(String key, String newDef) throws ApproovException
```

**Kotlin:**
```kotlin
@Throws(ApproovException::class)
fun fetchSecureString(key: String, newDef: String?): String?
```

It throws `ApproovException` if there was a problem getting the secure string. When the fetch did not succeed, that is an `ApproovFetchStatusException`, with the status in `getTokenFetchStatus()`. For `REJECTED` it is an `ApproovRejectionException`, with the ARC and rejection reasons.

It may need network access, so it may take some time. Don't call it from the UI thread.

Before `initialize` and in bypass mode it throws `ApproovException` with the message `fetchSecureString: Approov protection not enabled`, without calling the SDK.

## fetchCustomJWT
Fetches a [custom JWT](https://approov.io/docs/latest/approov-usage-documentation/#custom-jwts) with the given `payload`, which is a marshaled JSON string.

**Java:**
```Java
String fetchCustomJWT(String payload) throws ApproovException
```

**Kotlin:**
```kotlin
@Throws(ApproovException::class)
fun fetchCustomJWT(payload: String): String
```

It throws `ApproovException` if there was a problem getting the custom JWT. When the fetch did not succeed, that is an `ApproovFetchStatusException`, with the status in `getTokenFetchStatus()`. For `REJECTED` it is an `ApproovRejectionException`, with the ARC and rejection reasons.

It may need network access, so it may take some time. Don't call it from the UI thread.

Before `initialize` and in bypass mode it throws `ApproovException` with the message `fetchCustomJWT: Approov protection not enabled`, without calling the SDK.

## getLastARC
Gets the [Attestation Response Code](https://approov.io/docs/latest/approov-usage-documentation/#attestation-response-code) (ARC) from the most recent fetch this package made. The ARC is a code that tells Approov support why attestation gave its result.

The fetch can be a token, secure string or custom JWT fetch. It can come from the interceptor (including secure string substitutions and stale protection refreshes) or from a direct method (`fetchToken`, `fetchSecureString`, `fetchCustomJWT`, `precheck`).

It returns an empty string if:
* no fetch has been made
* the last fetch produced no ARC
* ARC is not enabled for your account
* the last fetch failed without a result (an SDK exception in a direct method)

It makes no SDK call and does no fetch. It returns the value the app already received, so you can read it after a rejected request and match it up with your backend. Where possible, prefer logging the ARC your backend saw.

**Java:**
```java
String getLastARC()
```

**Kotlin:**
```kotlin
fun getLastARC(): String
```

## setInstallAttributes
Sets an [install attributes token](https://approov.io/docs/latest/approov-usage-documentation/#application-installation-attributes), which is sent to the server and associated with this app installation for future Approov token fetches. The token must be signed, within its expiry time, and bound to the correct device ID. The package passes it to the SDK's `Approov.setInstallAttrsInToken`.

**Java:**
```java
void setInstallAttributes(String attrs) throws ApproovException
```

**Kotlin:**
```kotlin
@Throws(ApproovException::class)
fun setInstallAttributes(attrs: String)
```

It throws `ApproovException` if the SDK refuses the attributes. Before `initialize` and in bypass mode it throws `ApproovException` with the message `setInstallAttributes: Approov protection not enabled`, without calling the SDK.

# Extension classes

The classes below complete the public API of the package. A standard integration never uses them. See [ADVANCED.md](ADVANCED.md) for when you need them.

## ApproovServiceMutator

An interface with default methods, which you install with `setServiceMutator`. Its interceptor defaults are the `CLOSE_FAILURE` decisions, the same as in 3.5.x. See [service mutators](ADVANCED.md#service-mutators).

Every interceptor hook declares `IOException`. This lets a mutator that chooses to abort a request throw a standard network stack exception. A mutator aborts a request by throwing an `IOException`, which reaches your app unchanged.

Anything else a hook throws is treated as a failed request, never as a crash. That includes a `NullPointerException` or other programming error, and a deliberate `RuntimeException`:
* The request fails with an `ApproovException` whose cause is the original exception.
* The hook is named in the exception message and in an error-level log line.
* `execute()` throws it, and an enqueued call receives it in `onFailure`.

Without this, OkHttp would rethrow the exception on its dispatcher thread for an enqueued call, which would terminate the app.

Two more cases fail the request in the same way:
* `handleInterceptorProcessedRequest` returns `null`.
* A hook throws an Approov exception (`ApproovException` or a subclass) that is not one of the `CLOSE_FAILURE` decisions. For example, one your code built, or one it rethrew from a direct method. The request fails with a plain `ApproovException` that names the hook, with the original as its cause.

The `CLOSE_FAILURE` token decisions keep their 3.5.x exception types, whether a custom mutator inherits them or calls them. The standard substitution decisions never throw.

If a direct method hook (`handleFetchTokenResult`, `handleFetchSecureStringResult`, `handleFetchCustomJWTResult` or `handlePrecheckResult`) throws a `RuntimeException`, the direct method throws it as an `ApproovException`, with the original as its cause.

| Method | Default decision |
| :--- | :--- |
| `boolean handleInterceptorShouldProcessRequest(Request) throws IOException` | `false` if the URL matches an exclusion regex, else `true` |
| `boolean handleInterceptorFetchTokenResult(TokenFetchResult, String url) throws IOException` | `true` for `SUCCESS` (token, status and trace headers, signatures) and `NO_APPROOV_SERVICE` (status header only). `false` for `UNKNOWN_URL` and `UNPROTECTED_URL` (no Approov headers). Throws `ApproovNetworkException` for `NO_NETWORK`, `POOR_NETWORK` and `UNTRUSTED_NETWORK`, and `ApproovFetchStatusException` for every other status. For a failure status on a host that is not in the SDK pin set, the answer is ignored and the request goes out untouched. An exception from the standard decisions is ignored there too; only your own exception aborts it. The answer only controls the Approov headers and signatures. Secure strings follow the token fetch status: they are substituted on `SUCCESS` and `UNPROTECTED_URL` over `https`, and never otherwise. Returning `true` on `UNKNOWN_URL` or `UNPROTECTED_URL` adds nothing |
| `boolean handleInterceptorHeaderSubstitutionResult(TokenFetchResult, String header) throws IOException` | Consulted only on a `SUCCESS` or `UNPROTECTED_URL` request over `https`. `true` for `SUCCESS`. `false` for every other status; the status in lowercase then replaces the placeholder, after any required prefix, and the request goes ahead. Never throws. An override may throw a standard network stack exception to abort. An override that returns `true` for a result with no secure string is treated as `false`. One that returns `false` on `SUCCESS` leaves the placeholder |
| `boolean handleInterceptorQueryParamSubstitutionResult(TokenFetchResult, String queryKey) throws IOException` | as for headers |
| `Request handleInterceptorProcessedRequest(Request, ApproovRequestMutations) throws IOException` | returns the request unchanged; message signing, when enabled, runs after it |
| `boolean supportsProtectionRefresh()` | `false`; `true` for `DEFAULT`, `CLOSE_FAILURE` and `ALWAYS_PROCEED` |
| `boolean handlePinningShouldProcessRequest(Request) throws IOException` | `true` |
| `void handlePrecheckResult(TokenFetchResult) throws ApproovException` | throws for every status except `SUCCESS` and `UNKNOWN_KEY` |
| `void handleFetchTokenResult(TokenFetchResult) throws ApproovException` | throws for every status except `SUCCESS` |
| `void handleFetchSecureStringResult(TokenFetchResult, String operation, String key) throws ApproovException` | throws for every status except `SUCCESS` and `UNKNOWN_KEY` |
| `void handleFetchCustomJWTResult(TokenFetchResult) throws ApproovException` | throws for every status except `SUCCESS` |
| `static boolean isNetworkFailure(TokenFetchStatus)` | `true` for `NO_NETWORK`, `POOR_NETWORK`, `UNTRUSTED_NETWORK` |
| `static boolean closeFailureSubstitution(TokenFetchResult, String what)` | The substitution decision of both standard mutators: `true` for `SUCCESS`, `false` otherwise. Never throws |

| Constant | Meaning |
| :--- | :--- |
| `CLOSE_FAILURE` | an instance with these defaults |
| `ALWAYS_PROCEED` | Never aborts. `handleInterceptorFetchTokenResult` returns `false` for `UNKNOWN_URL` and `UNPROTECTED_URL`, and `true` for every other status. The substitution decisions return `true` only for `SUCCESS` |
| `DEFAULT` | Declared as `ApproovServiceMutator`. It points to the release's default behaviour: `CLOSE_FAILURE` in 3.7.0. In 4.0.0 it is planned to become the `ALWAYS_PROCEED` behaviour. If your app wants a fixed behaviour, name `CLOSE_FAILURE` or `ALWAYS_PROCEED` explicitly |

No mutator signs requests. See [enableMessageSigning](#enablemessagesigning).

## ApproovRequestMutations

Passed to `handleInterceptorProcessedRequest`. It describes what the interceptor did to the request.

| Method | Meaning |
| :--- | :--- |
| `String getTokenHeaderKey()` | name of the token header added, or `null` if none |
| `String getTokenHeaderPrefix()` | prefix placed before the token, empty if none |
| `String getTraceIDHeaderKey()` | name of the trace header added, or `null` |
| `String getStatusHeaderKey()` | name of the status header added, or `null` if disabled |
| `List<String> getSubstitutionHeaderKeys()` | headers whose values were replaced by secure strings, or `null`. A header that carries a fetch status instead of its placeholder is not listed |
| `String getOriginalURL()` | the URL before query parameters were replaced by secure strings, or `null` |
| `List<String> getSubstitutionQueryParamKeys()` | query parameters replaced by secure strings, or `null`. One that carries a fetch status is not listed |
| `void setTokenHeaderKey(String)` | sets the token header name |
| `void setTokenHeaderPrefix(String)` | sets the token prefix |
| `void setTraceIDHeaderKey(String)` | sets the trace header name |
| `void setStatusHeaderKey(String)` | sets the status header name |
| `void setSubstitutionHeaderKeys(List<String>)` | sets the substituted header names |
| `void setSubstitutionQueryParamResults(String originalURL, List<String> keys)` | sets the URL before substitution and the substituted query parameters |

The setters are used by the interceptor, and by tests that build a mutations object for a custom mutator. A mutator that receives the object only needs to read it, not call the setters. Calling them changes nothing the package does. The package keeps its own copy of what it applied, which it strips on a redirect and signs.

## SignatureParameters

`io.approov.util.okhttp.sig.SignatureParameters` holds the covered components and the signature parameters of one signature (RFC 9421 section 2.3). It is used in three places: `SignatureParametersFactory.setBaseParameters` takes it, `generateDefaultSignatureParametersFactory(SignatureParameters)` accepts it, and a factory subclass returns it from `buildSignatureParameters`.

| Method | Meaning |
| :--- | :--- |
| `SignatureParameters()` | empty parameter set |
| `SignatureParameters(SignatureParameters base)` | copy of the components, parameters and debug flag of another set |
| `SignatureParameters addComponentIdentifier(String)` / `(StringItem)` | covers a component. That is a derived component such as `ComponentProvider.DC_METHOD` (`@method`) or `DC_TARGET_URI` (`@target-uri`), or a header name. A component already covered, with the same name and parameters, is not added again (RFC 9421 section 2) |
| `boolean containsComponentIdentifier(String)` / `(StringItem)` | whether a component is covered |
| `String getAlg()` / `SignatureParameters setAlg(String)` | signature algorithm. The default factory leaves it unset, so that one signature is produced per configured algorithm. Set it to produce a single signature |
| `Long getCreated()` / `setCreated(Long)` | the `created` parameter, in seconds since the epoch |
| `Long getExpires()` / `setExpires(Long)` | the `expires` parameter |
| `String getKeyid()` / `setKeyid(String)` | the `keyid` parameter, not used by Approov signatures |
| `String getNonce()` / `setNonce(String)` | the `nonce` parameter |
| `String getTag()` / `setTag(String)` | the `tag` parameter |
| `Object getCustomParameter(String)` / `setCustomParameter(String, Object)` | any other signature parameter |
| `SignatureParameters setParameters(Map<String, Object>)` | replaces all signature parameters |
| `boolean isDebugMode()` / `void setDebugMode(boolean)` | when `true`, the signer adds `Signature-Base-Digest`, a SHA-256 of each signature base, to help you debug a verifier |
| `StringItem toComponentIdentifier()` | the `@signature-params` identifier |
| `InnerList toComponentValue()` | the `Signature-Input` member value for this set |
| `static SignatureParameters fromDictionaryEntry(Dictionary, String sigId)` | parses a `Signature-Input` member back into a parameter set. It keeps the components exactly as received, repeats included |

## ApproovDefaultMessageSigning

Holds the message signing constants and the signature parameters factory API. It is not a service mutator, and it has no public constructor. You configure message signing through [enableMessageSigning](#enablemessagesigning) and [putMessageSigningHostFactory](#putmessagesigninghostfactory).

| Member | Meaning |
| :--- | :--- |
| `static SignatureParametersFactory generateDefaultSignatureParametersFactory()` | The default factory. It produces both signatures. It covers the method and target URI; the token, trace and status headers; `Authorization`, `Content-Length` and `Content-Type` when present (the last two as OkHttp sends them, from the body); and a SHA-256 body digest when one can be computed (an empty body included). It adds `created` and a 15 second `expires` |
| `static SignatureParametersFactory generateDefaultSignatureParametersFactory(SignatureParameters base)` | as above, over a custom base set of components |
| `DIGEST_SHA256`, `DIGEST_SHA512` | body digest algorithms |
| `ALG_ES256`, `ALG_HS256` | signature algorithms (`ecdsa-p256-sha256`, `hmac-sha256`) |
| `SIG_ID_INSTALL`, `SIG_ID_ACCOUNT` | dictionary member names `install` and `account` |
| `RequiredBodyDigestException` | An `ApproovException` (an `IOException`) thrown when a required body digest cannot be generated. `execute()` throws it, and an enqueued call receives it in `onFailure` |
| `OkHttpComponentProvider` | the view of the request passed to `SignatureParametersFactory.buildSignatureParameters` (`getRequest()`, `hasField(name)`, `getField(name)`, ...) |

## ApproovDefaultMessageSigning.SignatureParametersFactory

Builds the signature parameters for each request. All setters return the factory, so you can chain them. A factory is shared by every request it applies to. So configure it fully before you pass it to `enableMessageSigning` or `putMessageSigningHostFactory`, and use a separate instance for each distinct configuration.

| Method | Meaning |
| :--- | :--- |
| `setBaseParameters(SignatureParameters)` | components that are always covered |
| `setUseInstallMessageSigning()` | produce the install signature only |
| `setUseAccountMessageSigning()` | produce the account signature only |
| `setUseInstallAndAccountMessageSigning()` | produce both. A newly constructed factory starts in this state |
| `List<String> getAlgs()` | the configured algorithms, in the order the signatures are emitted. Throws `IllegalStateException` if there are none, and a request signed by such a factory is sent unsigned |
| `setAddCreated(boolean)` | add the `created` parameter |
| `setExpiresLifetime(long seconds)` | add `expires` this many seconds after `created`; `0` leaves it out |
| `setAddApproovTokenHeader(boolean)` | cover the token header |
| `setAddApproovTraceIDHeader(boolean)` | cover the trace header when present |
| `setAddApproovStatusHeader(boolean)` | cover the status header when present |
| `addOptionalHeaders(String...)` | cover these headers when present |
| `setBodyDigestConfig(String algorithm, boolean required)` | compute `Content-Digest` with `DIGEST_SHA256` or `DIGEST_SHA512`, or pass `null` for none. With `required` set, the request fails when the digest cannot be generated |
| `public SignatureParameters buildSignatureParameters(OkHttpComponentProvider, ApproovRequestMutations) throws RequiredBodyDigestException` | The override point. You can also call it on another factory, so an override in your app can hand a request to a factory from `generateDefaultSignatureParametersFactory`. If the returned parameter set has an explicit `alg`, that single signature is produced. Otherwise the algorithms come from the installed factory (`getAlgs()`), not from the factory that built the parameters. Returning `null` leaves the request unsigned. An override that calls `super.buildSignatureParameters`, or another factory's, must declare or handle the checked `RequiredBodyDigestException`. An override must be declared `public` |
