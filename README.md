# Approov Package for OkHttp

![Java](https://img.shields.io/badge/Java-8%2B-007396?logo=openjdk&logoColor=white)
![Android](https://img.shields.io/badge/Android-minSdk%2023-3DDC84?logo=android&logoColor=white)
![Maven Central](https://img.shields.io/maven-central/v/io.approov/service.okhttp?logo=apachemaven&logoColor=white&label=Maven%20Central)
![Approov SDK](https://img.shields.io/badge/Approov%20SDK-3.5.3-0A66C2)
![Message Signing](https://img.shields.io/badge/Message%20Signing-RFC%209421-1f6feb)
![Build](https://github.com/approov/approov-service-okhttp/actions/workflows/build_and_test.yml/badge.svg)

Add this package, initialize it with your Approov account ID, and every API call your Android app makes through its `OkHttpClient` carries an [Approov](https://www.approov.io) token, and once you switch them on message signatures, that your backend can verify, over a TLS connection validated against the trust roots Approov manages for your account. Your backend then knows each request came from a genuine, unmodified instance of your app.

You'll need a trial or paid Approov account. The [Approov documentation](https://approov.io/docs/latest/) walks you through setting up your account, registering your app and checking the integration. The essentials for using this package are below; optional features are in [ADVANCED.md](ADVANCED.md) and every method is in [REFERENCE.md](REFERENCE.md).

## ADDING THE DEPENDENCY

Add the package to your app's Gradle dependencies, with `mavenCentral()` enabled in your repositories:

```groovy
implementation("io.approov:service.okhttp:3.8.0")
```

The package supports Android 6.0 (API level 23) and later, and OkHttp 4.12 or later (it depends on 4.12.0; OkHttp 3.x is not supported). Add these permissions to your app manifest:

```xml
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.INTERNET" />
```

## INITIALIZING

Initialize `ApproovService` when your app starts, in your `Application` class's `onCreate`, before the first request, with your **Approov account ID**, and then configure it; a request made before initialization goes out without Approov protection. Initialization throws if the value it receives is not a complete, unaltered account ID, so wrap the call: the app then logs the problem and starts in **bypass mode**, without Approov protection, instead of failing to start. The device ID log is optional. Approov knows an installation only by its device ID, so logging it next to your own user or session identifier gives you the correlation between the two.

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

Your account ID is in your onboarding email, or from the Approov CLI at any time (the CLI calls it the SDK config string):

```sh
approov sdk -getConfigString
```

It is the same for every app in your account and is not a secret, so it can live in your source code or be delivered with your app's configuration. Whichever way it reaches the app, the whole value must arrive intact, punctuation included; that is the only thing the guard above is for. More about what it is in [ADVANCED.md](ADVANCED.md#about-the-approov-account-id).

If your account ID is not available when the app starts, initialize with `""` and again with the account ID once you have it; see [bypass initialization](ADVANCED.md#bypass-initialization).

`initialize` is synchronous, so check its result and then make your configuration calls (headers, secure strings, exclusions, the service mutator, message signing, the logging level and so on) straight after it, before the app issues protected requests. Initialization done elsewhere for you, for example by a one-line native bootstrap, configures nothing: it initializes with the account ID and comment only, and the configuration is still your app's to make. A request processed before your configuration call runs uses the defaults in force at that moment, and a setting applies only to requests processed after it is made. More in [ADVANCED.md](ADVANCED.md#initialize-then-configure).

## MAKING REQUESTS

Use the client returned by `ApproovService` for the API calls you want to protect:

```kotlin
val client = ApproovService.getOkHttpClient()
```

<details>
<summary>Java</summary>

```java
OkHttpClient client = ApproovService.getOkHttpClient();
```
</details>

If you already configure an OkHttp client, for example to set timeouts or add interceptors, pass its builder to `ApproovService` before obtaining the client (initialization keeps it, like every other setting). Approov's network interceptors are placed ahead of yours, so your network interceptors see each request as it is sent:

```kotlin
ApproovService.setOkHttpClientBuilder(
    OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
)
val client = ApproovService.getOkHttpClient()
```

Each request to a domain you have added to Approov is sent with its Approov token, the proof of attestation, and the `Approov-Status` header, over a connection validated against the Managed Trust Roots Approov maintains for your account; a connection that fails validation is refused with OkHttp's standard `SSLPeerUnverifiedException`. An API you added with `-noApproovToken` gets its secure strings and a validated connection and nothing else. A secure string the SDK cannot provide for either kind of API is replaced by its fetch status in lowercase, after any required prefix (`rejected`, `unknown_key`, `no_network`, `Bearer rejected`, ...), so your backend sees why the secret is missing; the `Approov-Status` header still reports the token fetch. Requests to other domains are sent unchanged.

If the app could not be attested at that moment, 3.8.0 keeps the 3.5.x behaviour by default (`ApproovServiceMutator.DEFAULT` is `CLOSE_FAILURE`): the request goes out without a token header, with the reason in `Approov-Status`, if the Approov service was unavailable, and otherwise fails in the app with an `ApproovException`, an `ApproovNetworkException` for a network problem the user can retry. To always send the request, without a token header and with the reason in `Approov-Status`, and leave the decision to your backend, install `ApproovServiceMutator.ALWAYS_PROCEED`; it becomes the default in 4.0.0. A request sent without a token carries no secure string and no signature. A failed token fetch for a host outside your Approov API domains is sent unchanged; neither standard mutator fails it. See [what happens by default](ADVANCED.md#what-happens-by-default).

```kotlin
ApproovService.setServiceMutator(ApproovServiceMutator.ALWAYS_PROCEED)  // optional, the 4.0.0 default
```

Message signing is off by default in 3.8.0. One call, after `initialize`, signs every protected request with both the install and the account signatures, whichever mutator is installed; it is on and compulsory from 4.0.0:

```kotlin
ApproovService.enableMessageSigning()
```

The headers, and what your backend does with each, are listed in [ADVANCED.md](ADVANCED.md#what-each-request-carries); choosing signatures, per host factories and verifying them are in [message signing](ADVANCED.md#message-signing).

## VERIFYING

Run your app and make a request. The package never logs a token; it logs the [loggable form](https://approov.io/docs/latest/approov-usage-documentation/#loggable-tokens) of each result at debug level, whose `arc` claim says why the attestation produced the result it did. Debug lines are off by default: call `ApproovService.setLoggingLevel(ApproovLogLevel.DEBUG)` in a debug build, or run `adb shell setprop log.tag.ApproovService DEBUG` to see them on an installed build without a rebuild. Decoding the `arc` is described under [diagnostics](ADVANCED.md#diagnostics). Your account's [live metrics](https://approov.io/docs/latest/approov-usage-documentation/#metrics-graphs) show the same reasons in aggregate within a minute, and a [development signing certificate](https://approov.io/docs/latest/approov-usage-documentation/#development-app-signing-certificates) lets debug builds and emulators pass attestation while you work.

## UPGRADING FROM 3.5.x

By default 3.8.0 makes the same token decisions as 3.5.x and throws the same exceptions. What changes:

* Every protected request also carries the `Approov-Status` header. A request that proceeds without a token (`NO_APPROOV_SERVICE`) carries no `Approov-Token` header (3.5.8 sent it empty) and no secure string. Your backend must tolerate both.
* An API added with `approov api -add <domain> -noApproovToken` gets its secure strings substituted again, as up to 3.5.1; 3.5.2 to 3.5.8 left its placeholders in place.
* Message signing is switched on with `ApproovService.enableMessageSigning()`, not by installing a mutator. If you installed `ApproovDefaultMessageSigning` with `setServiceMutator`, replace that call with `enableMessageSigning(factory)` (it no longer compiles).
* The message signing helper classes moved: change imports of `io.approov.util.sig.*` to `io.approov.util.okhttp.sig.*` and of `io.approov.util.http.sfv.*` to `io.approov.util.okhttp.http.sfv.*`.
* The package no longer depends on BouncyCastle; it bundles its own relocated copy of Google Tink, so it cannot clash with your app's Tink, Gson or BouncyCastle, or with another Approov package.
* A secure string that cannot be obtained never fails a request: whatever its status (`REJECTED`, `UNKNOWN_KEY`, `NO_APPROOV_SERVICE`, `NO_NETWORK`, ...) the request proceeds with the status in lowercase in place of the placeholder (`rejected`, `unknown_key`, ...; `Bearer rejected` for a header with a required prefix), where 3.5.x threw `ApproovRejectionException`, `ApproovNetworkException` or `ApproovFetchStatusException` and left an unknown key in place. A backend that compares the value it receives must expect these status values.
* `initialize` never resets configuration: settings made before or after it are kept, a repeat with the same account ID returns at once, and the SDK's own exception reaches the caller of a rejected one. `isInitialized()` and `isApproovEnabled()` are replaced by `isApproovServiceEnabled()` and `isApproovProtectionEnabled()`.
* `setInstallAttrsInToken` is renamed `setInstallAttributes`. `setUseApproovStatusIfNoToken`, `setProceedOnNetworkFail` and `prefetch` are removed; install `ApproovServiceMutator.ALWAYS_PROCEED` to proceed on network failures, and the SDK manages prefetching.
* Approov's network interceptors now run before the network interceptors your builder adds, so a logger or inspector there no longer sees a redirect before Approov strips it. Such interceptors run after signing and must not change a signed header, the URL or the body.
* A secure string is only substituted into a request sent over `https`; a cleartext request keeps its placeholder.
* The methods that call the SDK throw `ApproovException` (`<method>: Approov protection not enabled`) before initialization and in bypass mode, and `getOkHttpClient()` may be called before initialization.
* `getMessageSignature` remains as a deprecated alias of `getAccountMessageSignature`, returning the same value and logging a deprecation warning once per process; call `getAccountMessageSignature`.
* `ApproovDefaultMessageSigning.RequiredBodyDigestException` is an `ApproovException`, so a checked `IOException`, where it was an `IllegalStateException`.
* `addExclusionURLRegex` with an invalid regular expression throws `IllegalArgumentException` (unchecked) where 3.5.x logged the error and ignored it.
* The package's logging has a level, `INFO` by default, so the loggable token and the device ID, which are debug lines, are no longer logged unless you call `ApproovService.setLoggingLevel(ApproovLogLevel.DEBUG)` or run `adb shell setprop log.tag.ApproovService DEBUG`.

4.0.0 is planned to make `ALWAYS_PROCEED` the default and message signing compulsory, so your backend becomes the only place that rejects requests without a valid token. Read the [changelog](CHANGELOG.md) before upgrading and test app and backend together.

## NEXT STEPS

[Add your API domains](https://approov.io/docs/latest/approov-usage-documentation/#managed-trust-roots) to Approov and [register your app's signing certificate](https://approov.io/docs/latest/approov-usage-documentation/#android-app-signing-certificates), then set up token and signature verification on your backend following the [Approov documentation](https://approov.io/docs/latest/).

The defaults are ready to use; you don't need to configure a mutator to get started, and message signing is the one call above. Custom header names, secure strings, token binding, excluding URLs and the service mutator are in [ADVANCED.md](ADVANCED.md); individual methods are in [REFERENCE.md](REFERENCE.md).
