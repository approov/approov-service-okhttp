# Approov Package for OkHttp

![Java](https://img.shields.io/badge/Java-8%2B-007396?logo=openjdk&logoColor=white)
![Android](https://img.shields.io/badge/Android-minSdk%2023-3DDC84?logo=android&logoColor=white)
![Maven Central](https://img.shields.io/maven-central/v/io.approov/service.okhttp?logo=apachemaven&logoColor=white&label=Maven%20Central)
![Message Signing](https://img.shields.io/badge/Message%20Signing-RFC%209421-1f6feb)
![Build](https://github.com/approov/approov-service-okhttp/actions/workflows/build_and_test.yml/badge.svg)

This package adds [Approov](https://www.approov.io) to an Android app that uses OkHttp. Once it is initialized, every request your app makes through the `OkHttpClient` it provides carries an Approov token, and optionally a message signature, that your backend can check. Your backend then knows each request came from a genuine, unmodified copy of your app.

The package also checks the TLS connection to each of your API domains, against the certificate keys you configured for that domain or else the trust roots Approov manages for your account. A connection that does not match is refused.

You need a trial or paid Approov account. The [Approov documentation](https://approov.io/docs/latest/) shows how to set up your account, register your app and check the integration. This page covers the essentials. Optional features are in [ADVANCED.md](ADVANCED.md), and every method is listed in [REFERENCE.md](REFERENCE.md).

## ADDING THE DEPENDENCY

Make sure `mavenCentral()` is in your repositories, then add the package to your app's Gradle dependencies:

```groovy
implementation("io.approov:service.okhttp:3.7.0")
```

The package supports Android 6.0 (API level 23) and later. It brings in OkHttp 5.3.2, and also works if your app stays on OkHttp 4.12 or later. OkHttp 3.x is not supported. If your app compiles Kotlin, it needs Kotlin 2.1 or later. See [OkHttp version](ADVANCED.md#okhttp-version).

Add these permissions to your app manifest:

```xml
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.INTERNET" />
```

## INITIALIZING

Initialize `ApproovService` once, when your app starts, with your Approov account ID. The usual place is the `onCreate` method of your `Application` class. Requests made before initialization are sent without Approov protection.

```kotlin
import android.util.Log
import io.approov.service.okhttp.ApproovService

class YourApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            // your Approov account ID, from your onboarding email or "approov sdk -getConfigString"
            ApproovService.initialize(applicationContext, "<your-approov-account-id>")
            if (ApproovService.isApproovServiceEnabled() && ApproovService.isApproovProtectionEnabled())
                Log.i("YourApp", "Approov initialized; deviceID=${ApproovService.getDeviceID()}")
        } catch (e: Exception) {
            // the value passed was not a valid account ID; run without Approov rather than not at all
            Log.e("YourApp", "Approov account ID rejected; starting in bypass mode", e)
            ApproovService.initialize(applicationContext, "")
        }
        // then configure, after initialize (which resets the configuration) and before the
        // app issues protected requests; only the settings your app uses, for example:
        // ApproovService.setTokenHeader("Authorization", "Bearer ")
        // ApproovService.addSubstitutionHeader("Api-Key", null)
    }
}
```

<details>
<summary>Java</summary>

```java
import android.util.Log;
import io.approov.service.okhttp.ApproovService;

public class YourApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        try {
            // your Approov account ID, from your onboarding email or "approov sdk -getConfigString"
            ApproovService.initialize(getApplicationContext(), "<your-approov-account-id>");
            if (ApproovService.isApproovServiceEnabled() && ApproovService.isApproovProtectionEnabled())
                Log.i("YourApp", "Approov initialized; deviceID=" + ApproovService.getDeviceID());
        } catch (Exception e) {
            // the value passed was not a valid account ID; run without Approov rather than not at all
            Log.e("YourApp", "Approov account ID rejected; starting in bypass mode", e);
            ApproovService.initialize(getApplicationContext(), "");
        }
        // then configure, after initialize (which resets the configuration) and before the
        // app issues protected requests; only the settings your app uses, for example:
        // ApproovService.setTokenHeader("Authorization", "Bearer ");
        // ApproovService.addSubstitutionHeader("Api-Key", null);
    }
}
```
</details>

A few notes on this example:

* `initialize` throws if the account ID it receives is incomplete or altered. The `catch` block then starts the package in **bypass mode** by initializing with `""`. In bypass mode requests are sent without Approov protection, but your app still starts.
* Logging the device ID is optional. Approov identifies an installation only by its device ID, so logging it next to your own user or session ID lets you match the two later.
* Make your configuration calls (custom headers, message signing, secure strings and so on) after `initialize`, before the app sends protected requests. Each `initialize` resets the configuration to its defaults, so a setting made before it is lost. A setting only applies to requests sent after it is made. See [initialize, then configure](ADVANCED.md#initialize-then-configure).

### Your account ID

Your account ID is in your onboarding email. You can also get it at any time from the Approov CLI, which calls it the SDK config string:

```sh
approov sdk -getConfigString
```

The account ID is the same for every app in your account and is not a secret. You can keep it in your source code or deliver it with your app's configuration. Either way, the whole value must reach the app unchanged, including its punctuation. More in [about the Approov account ID](ADVANCED.md#about-the-approov-account-id).

If the account ID is not available when the app starts, initialize with `""` first and call `initialize` again once you have it. See [bypass initialization](ADVANCED.md#bypass-initialization).

If another part of your app initializes Approov for you, for example a native bootstrap, that only sets the account ID. Your app still needs to make its own configuration calls, after that `initialize`, because every `initialize` resets them.

## MAKING REQUESTS

Use the client from `ApproovService` for the API calls you want to protect:

```kotlin
val client = ApproovService.getOkHttpClient()
```

<details>
<summary>Java</summary>

```java
OkHttpClient client = ApproovService.getOkHttpClient();
```
</details>

If you already build your own OkHttp client, for example to set timeouts or add interceptors, give its builder to `ApproovService` after `initialize` and before you get the client. `initialize` resets the builders, so set yours again if you initialize again. Your network interceptors run before Approov's, which run last, just before each request is sent.

```kotlin
ApproovService.setOkHttpClientBuilder(
    OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
)
val client = ApproovService.getOkHttpClient()
```

### What gets sent

For a domain you have added to Approov, each request carries:

* the `Approov-Token` header, the proof that the app passed attestation
* the `Approov-TraceID` header, an optional debug value, whenever the SDK gives one

The connection is checked against the certificate keys configured for the domain, or your account's Managed Trust Roots if it has none. If the check fails, the request fails with OkHttp's standard `SSLPeerUnverifiedException`.

A domain you added with `-noApproovToken` gets a checked connection and its secure strings, but no Approov headers. Requests to any other domain are sent unchanged.

If a secure string cannot be fetched, its placeholder is replaced by the fetch status name, for example `REJECTED` or `UNKNOWN_KEY`. A required prefix stays in front of it, so a header becomes `Bearer REJECTED`. This lets your backend see why the secret is missing.

### When the app cannot get a token

By default the `ApproovServiceMutator.DEFAULT` is `CLOSE_FAILURE`:

* If the Approov service cannot be reached (`NO_APPROOV_SERVICE`), the request is sent with an empty `Approov-Token` header.
* For any other failure the request fails in the app with an `ApproovFetchStatusException`. Its `getTokenFetchStatus()` tells you why, for example `NO_NETWORK` for a problem the user can retry. The older `ApproovNetworkException` subclass is still thrown for network problems but is deprecated.

To always send the request and let your backend decide, install `ALWAYS_PROCEED`. A request without a token is then sent with an empty `Approov-Token` header.

```kotlin
ApproovService.setServiceMutator(ApproovServiceMutator.ALWAYS_PROCEED)  // optional but recommended
```

To tell your backend why there is no token, turn on `setUseApproovStatusIfNoToken`. The token header then carries the status name, such as `NO_NETWORK`, in place of the empty token. Under `CLOSE_FAILURE` this also lets `NO_NETWORK`, `POOR_NETWORK` and `MITM_DETECTED` go ahead with the status instead of failing. See [sending the status in the token header](ADVANCED.md#sending-the-status-in-the-token-header).

```kotlin
ApproovService.setUseApproovStatusIfNoToken(true)
```

When a request to one of your token-protected APIs goes ahead without a token, it carries the token header (empty, or with the status) and the trace header if the SDK gives one. Its secure string placeholders are sent unchanged. If you installed message signing, it is signed. The connection is still checked against the domain's pins. An API you added with `-noApproovToken` is different: it never uses a token, and still gets its secure strings.

If the token fetch fails for a host that is not one of your Approov API domains, the request is sent unchanged under either mutator. More in [what happens by default](ADVANCED.md#what-happens-by-default).

### Message signing

Message signing is off unless you install it. The signer, `ApproovDefaultMessageSigning`, is a service mutator. Install it after `initialize`:

```kotlin
ApproovService.setServiceMutator(
    ApproovDefaultMessageSigning().setDefaultFactory(
        ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()))
```

<details>
<summary>Java</summary>

```java
ApproovService.setServiceMutator(new ApproovDefaultMessageSigning().setDefaultFactory(
        ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()));
```
</details>

Every request that carries the Approov token header is then signed with the install signature. Call `setUseInstallAndAccountMessageSigning()` on the factory to add the account signature as well. The signer makes the same decisions as `CLOSE_FAILURE`. To sign under other decisions, such as `ALWAYS_PROCEED`, subclass it; see [signing with other decisions](ADVANCED.md#signing-with-other-decisions).

The full list of headers, and what your backend should do with each, is in [what each request carries](ADVANCED.md#what-each-request-carries). Choosing signatures, per-host settings and verifying signatures are covered in [message signing](ADVANCED.md#message-signing).

## VERIFYING

Run your app and make a request. To see what happened, turn on debug logging, which is off by default. Either call `ApproovService.setLoggingLevel(ApproovLogLevel.DEBUG)` in a debug build, or run this to enable it on an installed build without rebuilding:

```sh
adb shell setprop log.tag.ApproovService DEBUG
```

The package never logs a token. At debug level it logs the [loggable form](https://approov.io/docs/latest/approov-usage-documentation/#loggable-tokens) of each result. Its `arc` claim explains why attestation gave that result; [diagnostics](ADVANCED.md#diagnostics) shows how to decode it.

Your account's [live metrics](https://approov.io/docs/latest/approov-usage-documentation/#metrics-graphs) show the same results in aggregate, usually within a minute. While you develop, a [development signing certificate](https://approov.io/docs/latest/approov-usage-documentation/#development-app-signing-certificates) lets debug builds and emulators pass attestation.

## UPGRADING FROM 3.5.x

Read the [changelog](CHANGELOG.md) for the full list of changes, and test your app and backend together.

### Code changes

* The message signing helper classes moved. Change imports of `io.approov.util.sig.*` to `io.approov.util.okhttp.sig.*`, and `io.approov.util.http.sfv.*` to `io.approov.util.okhttp.http.sfv.*`.
* The package now brings in OkHttp 5.3.2 instead of 4.12.0. Keep your other OkHttp libraries on the same version, and use Kotlin 2.1 or later if your app compiles Kotlin. You can also stay on OkHttp 4.12 or later. See [OkHttp version](ADVANCED.md#okhttp-version).
* `ApproovDefaultMessageSigning.RequiredBodyDigestException` is now an `ApproovException`, which is a checked `IOException`. It used to be an `IllegalStateException`. Code that caught `IllegalStateException` must catch `IOException`, and a `buildSignatureParameters` override that calls `super` must declare or handle it.
* `setProceedOnNetworkFail`, `getProceedOnNetworkFail` and `prefetch` are removed. To go ahead on network failures, install `ApproovServiceMutator.ALWAYS_PROCEED` or turn on `setUseApproovStatusIfNoToken(true)`. The SDK handles prefetching itself.
* `setApproovInterceptorExtensions`, `getApproovInterceptorExtensions` and the `ApproovInterceptorExtensions` interface are removed. Use `setServiceMutator` and `getServiceMutator`.
* `handlePinningShouldProcessRequest` now declares `ApproovException`, like the other interceptor hooks. Code that calls it directly must handle the exception.

### What you may notice

* A secure string that cannot be fetched no longer fails the request. The placeholder is replaced by the status name, such as `REJECTED` or `UNKNOWN_KEY` (or `Bearer REJECTED` with a prefix). In 3.5.x the request failed instead. If your backend checks the value it receives, it must expect these status values.
* A failed token fetch only affects requests to your Approov API domains. A request to any other host is sent unchanged instead of failing.
* An API added with `approov api -add <domain> -noApproovToken` gets its secure strings again. Versions 3.5.2 to 3.5.8 left the placeholders in place.
* Secure strings are only substituted into `https` requests. A cleartext request keeps its placeholder.
* A cleartext (`http`) request is no longer failed by the connection check. To one of your Approov API domains it fails under `CLOSE_FAILURE` with `ApproovFetchStatusException` (`BAD_URL`). To any other host it is sent.
* A WebSocket upgrade request is sent untouched, with no token or secure strings.
* An exception your own mutator throws, or a `RuntimeException` from the SDK, fails the request with an `ApproovException` that names the hook, with the original as its cause. Before, it reached your code unchanged, or crashed an enqueued call.
* `getLastARC()` no longer makes a token fetch. It returns the ARC of the most recent fetch, and `initialize` clears it.
* `getOkHttpClient()` can now be called before `initialize`. 3.5.x threw `IllegalStateException`. Until protection is on, its requests are sent untouched.
* Message signatures take their `created` and `expires` times from the Approov servers' clock. The signer can now add the account signature next to the install signature. A host factory is matched without regard to case and without the port.
* The package no longer depends on BouncyCastle. It bundles its own relocated copy of Google Tink, which cannot clash with your app's Tink, Gson or BouncyCastle, or with another Approov package.
* Logging now has a level, `INFO` by default. The loggable token and the device ID are debug lines, so they only appear after `ApproovService.setLoggingLevel(ApproovLogLevel.DEBUG)` or `adb shell setprop log.tag.ApproovService DEBUG`.

## NEXT STEPS

1. [Add your API domains](https://approov.io/docs/latest/approov-usage-documentation/#managed-trust-roots) to Approov.
2. [Register your app's signing certificate](https://approov.io/docs/latest/approov-usage-documentation/#android-app-signing-certificates).
3. Set up token and signature checks on your backend, following the [Approov documentation](https://approov.io/docs/latest/).

The defaults are ready to use. You don't need a service mutator to get started, and message signing is one call. Custom header names, secure strings, token binding, excluding URLs and service mutators are in [ADVANCED.md](ADVANCED.md). Every method is in [REFERENCE.md](REFERENCE.md).
