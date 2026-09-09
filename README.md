# Approov Package for OkHttp

![Java](https://img.shields.io/badge/Java-8%2B-007396?logo=openjdk&logoColor=white)
![Android](https://img.shields.io/badge/Android-minSdk%2023-3DDC84?logo=android&logoColor=white)
![Maven Central](https://img.shields.io/maven-central/v/io.approov/service.okhttp?logo=apachemaven&logoColor=white&label=Maven%20Central)
![Message Signing](https://img.shields.io/badge/Message%20Signing-RFC%209421-1f6feb)
![Build](https://github.com/approov/approov-service-okhttp/actions/workflows/build_and_test.yml/badge.svg)

Add [Approov](https://www.approov.io) protection to your Android app's API calls with [`OkHttp`](https://square.github.io/okhttp/). This package provides an `OkHttpClient` that handles Approov tokens, request signing and TLS pinning for the API domains you register with Approov. Your backend verifies the tokens and signatures to decide which requests to accept.

You'll need a trial or paid Approov account. The [Approov documentation](https://approov.io/docs/latest/) walks you through setting up your account, registering your app and checking the integration. The essentials for using this package are below; optional features are covered in [ADVANCED.md](ADVANCED.md), and the full API is in [REFERENCE.md](REFERENCE.md).

> **Breaking behaviour changes in 3.7.x — for existing users.** If you are upgrading from 3.5.x, review how your app and backend handle requests without an Approov token. Token fetch failures no longer stop API requests by default: the client sends an empty `Approov-Token` header and reports the fetch result in `Approov-Status`. Requests without a valid Approov token are rejected by your backend rather than stopped in the app. Message signing is also enabled by default with both install and account signatures. These changes may affect your existing integration, so review the [migration details in the changelog](CHANGELOG.md) and test your app and backend before upgrading.

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

Initialize `ApproovService` when your app starts, usually in your `Application` class's `onCreate` method. Pass your **Approov account ID**, which you'll find in your onboarding email. You can also retrieve it with the Approov CLI:

```sh
approov sdk -getConfigString
```

The CLI calls this the **SDK config string**. It looks like `#your-account#p6nZ...=` and contains the information the package needs to connect to your Approov account. It is the same for every app in your account and is not a secret, so you can safely include it in your source code. Copy the whole value, including its punctuation. You'll register the app itself separately as part of the integration guide.

The examples below guard against a mistyped or incomplete account ID so a setup mistake doesn't prevent your app from starting. If the value is invalid, the app logs the problem and starts in **bypass mode**, without Approov protection.

### Java

```java
import android.app.Application;
import android.util.Log;
import io.approov.service.okhttp.ApproovService;

public class YourApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        try {
            // Use the account ID from your onboarding email or "approov sdk -getConfigString".
            ApproovService.initialize(getApplicationContext(), "<your-approov-account-id>");
            if (ApproovService.isApproovEnabled())
                Log.i("YourApp", "Approov initialized; deviceID=" + ApproovService.getDeviceID());
        } catch (Exception e) {
            Log.e("YourApp", "Check your Approov account ID; starting in bypass mode", e);
            ApproovService.initialize(getApplicationContext(), "");
        }
    }
}
```

### Kotlin

```kotlin
import android.app.Application
import android.util.Log
import io.approov.service.okhttp.ApproovService

class YourApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            // Use the account ID from your onboarding email or "approov sdk -getConfigString".
            ApproovService.initialize(applicationContext, "<your-approov-account-id>")
            if (ApproovService.isApproovEnabled())
                Log.i("YourApp", "Approov initialized; deviceID=${ApproovService.getDeviceID()}")
        } catch (e: Exception) {
            Log.e("YourApp", "Check your Approov account ID; starting in bypass mode", e)
            ApproovService.initialize(applicationContext, "")
        }
    }
}
```

The optional device ID log helps you find this app installation in Approov metrics when troubleshooting.

**If your account ID isn't available when the app starts**, you can also pass `""` to `initialize` to start in bypass mode. This lets you use the package's client while your app loads the account ID. In bypass mode the client behaves like a regular OkHttp client: it adds no Approov tokens or signatures and applies no Approov pinning. APIs that require a valid Approov token will reject those requests.

Once the account ID is available, call `initialize` with it, reapply any custom settings, then obtain a new client with `getOkHttpClient()`. Clients obtained in bypass mode remain unprotected. See [initialization in the reference](REFERENCE.md#initialize) for more about switching modes.

## MAKING REQUESTS

Use the client returned by `ApproovService` for the API calls you want to protect:

### Java

```java
OkHttpClient client = ApproovService.getOkHttpClient();
```

### Kotlin

```kotlin
val client = ApproovService.getOkHttpClient()
```

If you already configure an OkHttp client—for example, to set timeouts or add interceptors—pass its builder to `ApproovService` after initialization and before obtaining the client:

```kotlin
ApproovService.setOkHttpClientBuilder(
    OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
)
val client = ApproovService.getOkHttpClient()
```

For each request to an API domain registered with Approov, the client:

* **Adds the token and fetch status.** `Approov-Token` carries the token, and `Approov-Status` reports the fetch result, such as `success`, `no_network` or `rejected`. If a token isn't available, the token header is empty and the request still proceeds. Your backend uses the status to decide how to handle it.
* **Signs the request.** Install and account signatures are sent in the `Signature` and `Signature-Input` headers using RFC 9421 HTTP Message Signatures. If a signing key is unavailable, the client sends whichever signature it can produce. Your backend verifies the signatures alongside the token.
* **Applies TLS pinning.** Connections are checked against the certificates or managed trust roots configured for the domain in Approov. A pin mismatch fails the connection with OkHttp's standard `SSLPeerUnverifiedException`.

Requests to domains you haven't added to Approov are sent unchanged.

## NEXT STEPS

Follow the [Approov documentation](https://approov.io/docs/latest/) to add your API domains, register your app and set up token and signature verification on your backend.

The defaults are ready to use; you don't need to configure a mutator or message signing to get started. If you need custom headers, secure strings, token binding or other options, see [ADVANCED.md](ADVANCED.md). Use [REFERENCE.md](REFERENCE.md) to look up individual methods.
