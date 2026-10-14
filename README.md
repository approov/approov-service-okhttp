# Approov Package for OkHttp

![Java](https://img.shields.io/badge/Java-8%2B-007396?logo=openjdk&logoColor=white)
![Android](https://img.shields.io/badge/Android-minSdk%2023-3DDC84?logo=android&logoColor=white)
![Maven Central](https://img.shields.io/maven-central/v/io.approov/service.okhttp?logo=apachemaven&logoColor=white&label=Maven%20Central)
![Approov SDK](https://img.shields.io/badge/Approov%20SDK-3.5.3-0A66C2)
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

The package supports Android 6.0 (API level 23) and later. It needs OkHttp 4.12 or later and brings in OkHttp 4.12.0 itself. OkHttp 3.x is not supported.

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
        // then configure, straight after initialize and before the app issues protected
        // requests; only the settings your app uses, for example:
        // ApproovService.setTokenHeader("Authorization", "Bearer ")
        // ApproovService.enableMessageSigning()
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
        // then configure, straight after initialize and before the app issues protected
        // requests; only the settings your app uses, for example:
        // ApproovService.setTokenHeader("Authorization", "Bearer ");
        // ApproovService.enableMessageSigning();
    }
}
```
</details>

A few notes on this example:

* `initialize` throws if the account ID it receives is incomplete or altered. The `catch` block then starts the package in **bypass mode** by initializing with `""`. In bypass mode requests are sent without Approov protection, but your app still starts.
* Logging the device ID is optional. Approov identifies an installation only by its device ID, so logging it next to your own user or session ID lets you match the two later.
* Make your configuration calls (custom headers, message signing, secure strings and so on) straight after `initialize`, before the app sends protected requests. A setting only applies to requests sent after it is made. See [initialize, then configure](ADVANCED.md#initialize-then-configure).

### Your account ID

Your account ID is in your onboarding email. You can also get it at any time from the Approov CLI, which calls it the SDK config string:

```sh
approov sdk -getConfigString
```

The account ID is the same for every app in your account and is not a secret. You can keep it in your source code or deliver it with your app's configuration. Either way, the whole value must reach the app unchanged, including its punctuation. More in [about the Approov account ID](ADVANCED.md#about-the-approov-account-id).

If the account ID is not available when the app starts, initialize with `""` first and call `initialize` again once you have it. See [bypass initialization](ADVANCED.md#bypass-initialization).

If another part of your app initializes Approov for you, for example a native bootstrap, that only sets the account ID. Your app still needs to make its own configuration calls.

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

If you already build your own OkHttp client, for example to set timeouts or add interceptors, give its builder to `ApproovService` before you get the client. The builder is kept if you initialize again. Approov's network interceptors run before yours, so your interceptors see each request as it is actually sent.

```kotlin
ApproovService.setOkHttpClientBuilder(
    OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
)
val client = ApproovService.getOkHttpClient()
```

### What gets sent

For a domain you have added to Approov, each request carries:

* the `Approov-Token` header, the proof that the app passed attestation
* the `Approov-Status` header, which says how the token fetch went (for example `success` or `no_network`)

The connection is checked against the certificate keys configured for the domain, or your account's Managed Trust Roots if it has none. If the check fails, the request fails with OkHttp's standard `SSLPeerUnverifiedException`.

A domain you added with `-noApproovToken` gets a checked connection and its secure strings, but no Approov headers. Requests to any other domain are sent unchanged.

If a secure string cannot be fetched, its placeholder is replaced by the fetch status in lowercase, for example `rejected` or `unknown_key`. A required prefix stays in front of it, so a header becomes `Bearer rejected`. This lets your backend see why the secret is missing.

### When the app cannot get a token

By default 3.7.0 behaves like 3.5.x (`ApproovServiceMutator.DEFAULT` is `CLOSE_FAILURE`):

* If the Approov service cannot be reached, the request is sent without a token header, and `Approov-Status` gives the reason.
* For any other failure the request fails in the app with an `ApproovFetchStatusException`. Its `getTokenFetchStatus()` tells you why, for example `NO_NETWORK` for a problem the user can retry. The older `ApproovNetworkException` subclass is still thrown for network problems but is deprecated.

To always send the request and let your backend decide, install `ALWAYS_PROCEED`. The request is then sent without a token header and `Approov-Status` gives the reason. This becomes the default in 4.0.0.

```kotlin
ApproovService.setServiceMutator(ApproovServiceMutator.ALWAYS_PROCEED)  // optional, the 4.0.0 default
```

When a request to one of your token-protected APIs goes ahead without a token, it carries only the `Approov-Status` header. Its secure string placeholders are sent unchanged, and it is not signed. The connection is still checked against the domain's pins. An API you added with `-noApproovToken` is different: it never uses a token, and still gets its secure strings.

If the token fetch fails for a host that is not one of your Approov API domains, the request is sent unchanged under either mutator. More in [what happens by default](ADVANCED.md#what-happens-by-default).

### Message signing

Message signing is off by default in 3.7.0. Turn it on with one call after `initialize`:

```kotlin
ApproovService.enableMessageSigning()
```

Every protected request is then signed with both the install signature and the account signature. From 4.0.0 message signing is planned to be on by default. You will still be able to turn it off with `disableMessageSigning()`.

The full list of headers, and what your backend should do with each, is in [what each request carries](ADVANCED.md#what-each-request-carries). Choosing signatures, per-host settings and verifying signatures are covered in [message signing](ADVANCED.md#message-signing).

## VERIFYING

Run your app and make a request. To see what happened, turn on debug logging, which is off by default. Either call `ApproovService.setLoggingLevel(ApproovLogLevel.DEBUG)` in a debug build, or run this to enable it on an installed build without rebuilding:

```sh
adb shell setprop log.tag.ApproovService DEBUG
```

The package never logs a token. At debug level it logs the [loggable form](https://approov.io/docs/latest/approov-usage-documentation/#loggable-tokens) of each result. Its `arc` claim explains why attestation gave that result; [diagnostics](ADVANCED.md#diagnostics) shows how to decode it.

Your account's [live metrics](https://approov.io/docs/latest/approov-usage-documentation/#metrics-graphs) show the same results in aggregate, usually within a minute. While you develop, a [development signing certificate](https://approov.io/docs/latest/approov-usage-documentation/#development-app-signing-certificates) lets debug builds and emulators pass attestation.

## UPGRADING FROM 3.5.x

By default 3.7.0 makes the same token decisions as 3.5.x and throws the same exceptions. Read the [changelog](CHANGELOG.md) for the full list, and test your app and backend together. The changes most likely to affect you:

### What your backend receives

* Every protected request carries the new `Approov-Status` header.
* A request that goes ahead without a token no longer has an `Approov-Token` header at all (3.5.8 sent it empty), and carries no secure strings.
* A secure string that cannot be fetched never fails the request any more. The placeholder is replaced by the status in lowercase, such as `rejected` or `unknown_key` (or `Bearer rejected` with a prefix). In 3.5.x the request failed instead, with `ApproovRejectionException`, `ApproovNetworkException` or `ApproovFetchStatusException`. If your backend checks the value it receives, it must expect these status values.
* An API added with `approov api -add <domain> -noApproovToken` gets its secure strings again. Versions 3.5.2 to 3.5.8 left the placeholders in place.
* Secure strings are only substituted into `https` requests. A cleartext request keeps its placeholder.

### Code changes

* Message signing is turned on with `ApproovService.enableMessageSigning()`. If you installed `ApproovDefaultMessageSigning` with `setServiceMutator`, use `enableMessageSigning(factory)` instead. The old call no longer compiles.
* The message signing helper classes moved. Change imports of `io.approov.util.sig.*` to `io.approov.util.okhttp.sig.*`, and `io.approov.util.http.sfv.*` to `io.approov.util.okhttp.http.sfv.*`.
* `isInitialized()` and `isApproovEnabled()` are replaced by `isApproovServiceEnabled()` and `isApproovProtectionEnabled()`.
* `setInstallAttrsInToken` is renamed `setInstallAttributes`.
* `setUseApproovStatusIfNoToken`, `setProceedOnNetworkFail` and `prefetch` are removed. To go ahead on network failures, install `ApproovServiceMutator.ALWAYS_PROCEED`. The SDK now handles prefetching itself.
* `getMessageSignature` still works but is deprecated and logs a warning once. Use `getAccountMessageSignature`.
* `ApproovDefaultMessageSigning.RequiredBodyDigestException` is now an `ApproovException`, which is a checked `IOException`. It used to be an `IllegalStateException`.
* `addExclusionURLRegex` throws `IllegalArgumentException` for an invalid regular expression. 3.5.x logged the error and ignored the exclusion.

### Behaviour changes

* `initialize` never resets your configuration. Settings made before or after it are kept. Calling it again with the same account ID returns straight away, and if the SDK rejects a call, its exception reaches your code.
* Methods that call the SDK throw `ApproovException` with the message `<method>: Approov protection not enabled` before initialization and in bypass mode. `getOkHttpClient()` can now be called before initialization.
* Approov's network interceptors now run before the ones your builder adds. A logger or inspector there no longer sees a redirect before Approov removes its headers. Your interceptors run after signing, so they must not change a signed header, the URL or the body.
* The package no longer depends on BouncyCastle. It bundles its own relocated copy of Google Tink, which cannot clash with your app's Tink, Gson or BouncyCastle, or with another Approov package.
* Logging now has a level, `INFO` by default. The loggable token and the device ID are debug lines, so they only appear after `ApproovService.setLoggingLevel(ApproovLogLevel.DEBUG)` or `adb shell setprop log.tag.ApproovService DEBUG`.

4.0.0 is planned to make `ALWAYS_PROCEED` and message signing the defaults. Signing can still be turned off. Your backend will then be the only place that rejects requests without a valid token.

## NEXT STEPS

1. [Add your API domains](https://approov.io/docs/latest/approov-usage-documentation/#managed-trust-roots) to Approov.
2. [Register your app's signing certificate](https://approov.io/docs/latest/approov-usage-documentation/#android-app-signing-certificates).
3. Set up token and signature checks on your backend, following the [Approov documentation](https://approov.io/docs/latest/).

The defaults are ready to use. You don't need a service mutator to get started, and message signing is one call. Custom header names, secure strings, token binding, excluding URLs and service mutators are in [ADVANCED.md](ADVANCED.md). Every method is in [REFERENCE.md](REFERENCE.md).
