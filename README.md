# Approov Package for OkHttp

![Java](https://img.shields.io/badge/Java-8%2B-007396?logo=openjdk&logoColor=white)
![Android](https://img.shields.io/badge/Android-minSdk%2023-3DDC84?logo=android&logoColor=white)
![Maven Central](https://img.shields.io/maven-central/v/io.approov/service.okhttp?logo=apachemaven&logoColor=white&label=Maven%20Central)
![Message Signing](https://img.shields.io/badge/Message%20Signing-RFC%209421-1f6feb)
![Build](https://github.com/approov/approov-service-okhttp/actions/workflows/build_and_test.yml/badge.svg)

Add this package, initialize it with your Approov account ID, and every API call your Android app makes through its `OkHttpClient` carries an [Approov](https://www.approov.io) token and message signatures that your backend can verify, over a TLS connection validated against the trust roots Approov manages for your account. Your backend then knows each request came from a genuine, unmodified instance of your app.

You'll need a trial or paid Approov account. The [Approov documentation](https://approov.io/docs/latest/) walks you through setting up your account, registering your app and checking the integration. The essentials for using this package are below; optional features are in [ADVANCED.md](ADVANCED.md) and every method is in [REFERENCE.md](REFERENCE.md).

## ADDING THE DEPENDENCY

Add the package to your app's Gradle dependencies, with `mavenCentral()` enabled in your repositories:

```groovy
implementation("io.approov:service.okhttp:3.7.0")
```

The package supports Android 6.0 (API level 23) and later. Add these permissions to your app manifest:

```xml
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.INTERNET" />
```

## INITIALIZING

Initialize `ApproovService` when your app starts, usually in your `Application` class's `onCreate`, with your **Approov account ID**. Initialization throws if the value it receives is not a complete, unaltered account ID, so wrap the call: the app then logs the problem and starts in **bypass mode**, without Approov protection, instead of failing to start. The device ID log is optional. Approov knows an installation only by its device ID, so logging it next to your own user or session identifier gives you the correlation between the two.

```kotlin
import android.util.Log
import io.approov.service.okhttp.ApproovService

class YourApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            // your Approov account ID, from your onboarding email or "approov sdk -getConfigString"
            ApproovService.initialize(applicationContext, "<your-approov-account-id>")
            if (ApproovService.isApproovEnabled())
                Log.i("YourApp", "Approov initialized; deviceID=${ApproovService.getDeviceID()}")
        } catch (e: Exception) {
            // the value passed was not a valid account ID; run without Approov rather than not at all
            Log.e("YourApp", "Approov account ID rejected; starting in bypass mode", e)
            ApproovService.initialize(applicationContext, "")
        }
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
            if (ApproovService.isApproovEnabled())
                Log.i("YourApp", "Approov initialized; deviceID=" + ApproovService.getDeviceID());
        } catch (Exception e) {
            // the value passed was not a valid account ID; run without Approov rather than not at all
            Log.e("YourApp", "Approov account ID rejected; starting in bypass mode", e);
            ApproovService.initialize(getApplicationContext(), "");
        }
    }
}
```
</details>

Your account ID is in your onboarding email, or from the Approov CLI at any time (the CLI calls it the SDK config string):

```sh
approov sdk -getConfigString
```

It is the same for every app in your account and is not a secret, so it can live in your source code or be delivered with your app's configuration. Whichever way it reaches the app, the whole value must arrive intact, punctuation included; that is the only thing the guard above is for. More about what it is in [ADVANCED.md](ADVANCED.md#about-the-approov-account-id).

**If your account ID isn't available when the app starts**, pass `""` to `initialize` to start in bypass mode, where the client behaves like a regular OkHttp client with no Approov tokens, signatures or connection validation. Once the account ID is available, call `initialize` with it, reapply any custom settings, then obtain a new client with `getOkHttpClient()`; clients obtained in bypass mode stay unprotected. See [initialize in the reference](REFERENCE.md#initialize).

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

If you already configure an OkHttp client, for example to set timeouts or add interceptors, pass its builder to `ApproovService` after initialization and before obtaining the client:

```kotlin
ApproovService.setOkHttpClientBuilder(
    OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
)
val client = ApproovService.getOkHttpClient()
```

Every request to an API domain you have added to Approov carries an Approov token as its proof of attestation. The only exception is when the SDK could not attest the app at that moment (no network, the device or app was rejected, the Approov service was unreachable): the request is still sent, with an empty token header, and `Approov-Status` says why. For each such request the client adds:

| Header | Value | What your backend does with it |
| :--- | :--- | :--- |
| `Approov-Token` | the Approov token, a short lived signed JWT that is the proof of attestation for this request; **empty** if no token could be obtained | verifies the token's signature and expiry and rejects a request whose token is missing or invalid; the token also carries the app installation's public key, which verifies the `install` message signature below |
| `Approov-Status` | the outcome of the attestation for this request, in lowercase: `success`, `no_network`, `rejected`, ... | says why this particular request carries no attestation proof, so you can log the reason against the rejection |
| `Signature`, `Signature-Input` | RFC 9421 message signatures, an `install` member (per installation key) and an `account` member (account key), over the method, URL, the headers above, the body digest when there is a body, and any headers you choose to add | verifies whichever signature it is configured for; the signature makes the request immutable, signed headers and body included, and links it to this token and so to the attested app installation; sign your session or `Authorization` header too and the request is linked to the user as well |
| `Approov-TraceID` | an optional debug header added by the SDK | nothing, it is a debug header; pass it through unchanged |

The client from `getOkHttpClient()` never holds back or fails a request because of the attestation outcome: whatever it is, the request is sent, and when no token could be obtained it goes out with an empty token header and the reason in `Approov-Status`. The one thing that does stop a request is the connection itself: the TLS connection to each domain you have added is validated against the [Managed Trust Roots](https://approov.io/docs/latest/approov-usage-documentation/#managed-trust-roots) that Approov maintains for your account, or against the specific certificate public keys you configure for that domain, with the validation set updated dynamically without an app release. A connection that does not validate is refused with OkHttp's standard `SSLPeerUnverifiedException`, exactly as OkHttp refuses any connection whose certificate it cannot trust. Requests to domains you haven't added to Approov are sent unchanged.

## VERIFYING

Run your app and make a request. The package never logs a token. For each fetch it logs, at debug level, the [loggable form](https://approov.io/docs/latest/approov-usage-documentation/#loggable-tokens) of the result: the token's claims plus a short fragment of its signature, which cannot be turned back into a usable token. The claim to look at is `arc`, the [Attestation Response Code](https://approov.io/docs/latest/approov-usage-documentation/#attestation-response-code): it encodes why the attestation produced the result it did. Paste the logged value into the CLI to check it and decode the `arc`:

```sh
approov token -check '<loggable token from logcat>'
```

Your account's [live metrics](https://approov.io/docs/latest/approov-usage-documentation/#metrics-graphs) show the same within a minute. While you work, a [development signing certificate](https://approov.io/docs/latest/approov-usage-documentation/#development-app-signing-certificates) lets debug builds and emulators pass attestation.

## UPGRADING FROM 3.5.x

3.7.0 changes the request contract: requests are never stopped in the app, a failed token fetch sends an empty `Approov-Token` with the reason in `Approov-Status`, and message signing is on by default with both signatures. Your backend must tolerate the new headers and is the place that rejects requests without a valid token. Read the [changelog](CHANGELOG.md) before upgrading and test app and backend together.

## NEXT STEPS

[Add your API domains](https://approov.io/docs/latest/approov-usage-documentation/#managed-trust-roots) to Approov and [register your app's signing certificate](https://approov.io/docs/latest/approov-usage-documentation/#android-app-signing-certificates), then set up token and signature verification on your backend following the [Approov documentation](https://approov.io/docs/latest/).

The defaults are ready to use; you don't need to configure a mutator or message signing to get started. Custom header names, secure strings, token binding, excluding URLs and the service mutator are in [ADVANCED.md](ADVANCED.md); individual methods are in [REFERENCE.md](REFERENCE.md).
