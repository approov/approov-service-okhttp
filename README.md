# Approov Service for OkHttp

![Java](https://img.shields.io/badge/Java-8%2B-007396?logo=openjdk&logoColor=white)
![Android](https://img.shields.io/badge/Android-minSdk%2023-3DDC84?logo=android&logoColor=white)
![Maven Central](https://img.shields.io/maven-central/v/io.approov/service.okhttp?logo=apachemaven&logoColor=white&label=Maven%20Central)
![Message Signing](https://img.shields.io/badge/Message%20Signing-RFC%209421-1f6feb)
![Build](https://github.com/approov/approov-service-okhttp/actions/workflows/build_and_test.yml/badge.svg)

The [Approov](https://www.approov.io) integration package for Android apps that make their API requests with [`OkHttp`](https://square.github.io/okhttp/). Adding this package to an app is integrating Approov: every request made through the `OkHttpClient` it provides carries a short lived Approov token and message signatures proving that it came from a genuine, unmodified app, and its TLS connections are pinned. The backend verifies these. The native Approov SDK that the package wraps is an implementation detail.

The step by step guide for integrating Approov into an Android app using this package, registering the app and checking that it works is in the [Approov documentation](https://approov.io/docs/latest/). This repository holds the source of the package, its [reference](REFERENCE.md), the [advanced options](ADVANCED.md) that a standard integration does not need, and the [changelog](CHANGELOG.md). You will need a trial or paid Approov account.

> **3.7.x line.** This version targets the Approov SDK 3.7.0. Requests always proceed, message signing is on by default with both the install and the account signatures, and the Approov fetch status of every request is reported to the backend on the `Approov-Status` header. The 3.5.x line keeps its previous behaviour; see the [changelog](CHANGELOG.md) before upgrading.

## ADDING THE DEPENDENCY

The package is available from [`mavenCentral`](https://mvnrepository.com/repos/central):

```groovy
implementation("io.approov:service.okhttp:3.7.0")
```

The app manifest needs the following permissions, and the minimum supported SDK version is 23 (Android 6.0):

```xml
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.INTERNET" />
```

## INITIALIZING

Initialize the `ApproovService` when the app is created, usually in `onCreate` of your `Application`, with the configuration string from your Approov onboarding email. Wrap the call so that a failure is logged and the app keeps working, unprotected, by re-initializing in bypass mode with an empty configuration:

### Java
```java
import android.util.Log;
import io.approov.service.okhttp.ApproovService;

public class YourApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        try {
            ApproovService.initialize(getApplicationContext(), "<enter-your-config-string-here>");
            if (ApproovService.isApproovEnabled())
                Log.i("YourApp", "Approov initialized; deviceID=" + ApproovService.getDeviceID());
        } catch (Exception e) {
            Log.e("YourApp", "Approov init failed; continuing unprotected", e);
            ApproovService.initialize(getApplicationContext(), "");
        }
    }
}
```

### Kotlin
```kotlin
import android.util.Log
import io.approov.service.okhttp.ApproovService

class YourApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            ApproovService.initialize(applicationContext, "<enter-your-config-string-here>")
            if (ApproovService.isApproovEnabled())
                Log.i("YourApp", "Approov initialized; deviceID=${ApproovService.getDeviceID()}")
        } catch (e: Exception) {
            Log.e("YourApp", "Approov init failed; continuing unprotected", e)
            ApproovService.initialize(applicationContext, "")
        }
    }
}
```

Logging the Approov device ID lets a given installation be correlated between your logs and the Approov metrics.

## MAKING REQUESTS

Use the `OkHttpClient` provided by the `ApproovService` for every API call you want to protect:

### Java
```java
OkHttpClient client = ApproovService.getOkHttpClient();
```

### Kotlin
```kotlin
val client = ApproovService.getOkHttpClient()
```

If your code already configures its own client (timeouts, interceptors, ...) pass the builder in once, and the returned client includes its settings:

```kotlin
ApproovService.setOkHttpClientBuilder(OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS))
```

For each request to an API domain you have added to Approov the client:

* Adds the `Approov-Token` header, and the `Approov-Status` header carrying the Approov fetch status in lowercase (`success`, `no_network`, `rejected`, ...). If a token could not be obtained the token header is sent **empty** and the request still proceeds; the backend reads the status header to decide. No failure ever aborts a request on the app's behalf.
* Signs the request with both the install and the account message signatures (`Signature` and `Signature-Input` headers, RFC 9421), so the backend can verify that the request and its token came from this app installation.
* Pins the TLS connection to the certificates or managed trust roots configured for the domain in Approov. A pin mismatch fails the connection with `javax.net.ssl.SSLPeerUnverifiedException`, as any pinning failure does in OkHttp.

Requests to domains not added to Approov are sent untouched.

## NEXT STEPS

Follow the [Approov documentation](https://approov.io/docs/latest/) to add your API domains, register the app and verify the tokens and signatures in your backend. The options for changing the defaults (header names, disabling the status header, customizing or switching off message signing, secure strings, token binding, excluding URLs, service mutators) are described in [ADVANCED.md](ADVANCED.md), and every method is documented in [REFERENCE.md](REFERENCE.md).
