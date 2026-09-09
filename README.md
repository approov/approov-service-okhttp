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

If your account ID is not available when the app starts, initialize with `""` and again with the account ID once you have it; see [bypass initialization](ADVANCED.md#bypass-initialization).

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

Each request to a domain you have added to Approov is sent with its Approov token, the proof of attestation, both message signatures and the `Approov-Status` header, over a connection validated against the Managed Trust Roots Approov maintains for your account. If the app could not be attested at that moment the request still goes out, with an empty token header and the reason in `Approov-Status`; the only thing that stops a request is a connection that fails validation, refused with OkHttp's standard `SSLPeerUnverifiedException`. Requests to other domains are sent unchanged. The headers, and what your backend does with each, are listed in [ADVANCED.md](ADVANCED.md#what-each-request-carries).

## VERIFYING

Run your app and make a request. The package never logs a token; it logs the [loggable form](https://approov.io/docs/latest/approov-usage-documentation/#loggable-tokens) of each result at debug level, whose `arc` claim says why the attestation produced the result it did. Decoding it is described under [diagnostics](ADVANCED.md#diagnostics). Your account's [live metrics](https://approov.io/docs/latest/approov-usage-documentation/#metrics-graphs) show the same reasons in aggregate within a minute, and a [development signing certificate](https://approov.io/docs/latest/approov-usage-documentation/#development-app-signing-certificates) lets debug builds and emulators pass attestation while you work.

## UPGRADING FROM 3.5.x

3.7.0 changes the request contract: requests are never stopped in the app, a failed token fetch sends an empty `Approov-Token` with the reason in `Approov-Status`, and message signing is on by default with both signatures. Your backend must tolerate the new headers and is the place that rejects requests without a valid token. Read the [changelog](CHANGELOG.md) before upgrading and test app and backend together.

## NEXT STEPS

[Add your API domains](https://approov.io/docs/latest/approov-usage-documentation/#managed-trust-roots) to Approov and [register your app's signing certificate](https://approov.io/docs/latest/approov-usage-documentation/#android-app-signing-certificates), then set up token and signature verification on your backend following the [Approov documentation](https://approov.io/docs/latest/).

The defaults are ready to use; you don't need to configure a mutator or message signing to get started. Custom header names, secure strings, token binding, excluding URLs and the service mutator are in [ADVANCED.md](ADVANCED.md); individual methods are in [REFERENCE.md](REFERENCE.md).
