# Approov Service for OkHttp

![Java](https://img.shields.io/badge/Java-8%2B-007396?logo=openjdk&logoColor=white)
![Android](https://img.shields.io/badge/Android-minSdk%2023-3DDC84?logo=android&logoColor=white)
![Maven Central](https://img.shields.io/maven-central/v/io.approov/service.okhttp?logo=apachemaven&logoColor=white&label=Maven%20Central)
![Message Signing](https://img.shields.io/badge/Message%20Signing-RFC%209421-1f6feb)
![Build](https://github.com/approov/approov-service-okhttp/actions/workflows/build_and_test.yml/badge.svg)

A wrapper for the [Approov SDK](https://github.com/approov/approov-android-sdk) to enable easy integration when using [`OkHttp`](https://square.github.io/okhttp/) for making the API calls that you wish to protect with Approov. In order to use this you will need a trial or paid [Approov](https://www.approov.io) account.

## ADDING APPROOV SERVICE DEPENDENCY

The Approov integration is available via [`mavenCentral`](https://mvnrepository.com/repos/central). This allows inclusion into the project by simply specifying a dependency in the `gradle` files for the app.

The `mavenCentral()` repository is already present in the build.gradle file so the only import you need to make is the actual service layer itself:

```groovy
implementation("io.approov:service.okhttp:3.5.8")
```

Make sure you do a Gradle sync (by selecting `Sync Now` in the banner at the top of the modified `.gradle` file) after making these changes.

This package is actually an open source wrapper layer that allows you to easily use Approov with `OkHttp`. This has a further dependency to the closed source [Approov SDK](https://central.sonatype.com/artifact/io.approov/approov-android-sdk/3.5.3). In some cases you may need to also add this implementation to your dependencies list to avoid build errors:

```groovy
implementation("io.approov:approov-android-sdk:3.5.3")
```

## MANIFEST CHANGES

The following app permissions need to be available in the manifest to use Approov:

```xml
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.INTERNET" />
```

Note that the minimum SDK version you can use with the Approov package is 23 (Android 6.0). 

Please [read this](https://approov.io/docs/latest/approov-usage-documentation/#targeting-android-11-and-above) section of the reference documentation if targeting Android 11 (API level 30) or above.

## INITIALIZING APPROOV SERVICE

In order to use the `ApproovService` you must initialize it when your app is created, usually in the `onCreate` method:

Wrap initialization in a try/catch: on success, confirm the layer is enabled and log the Approov device ID together with an app-generated session/correlation id; on failure, log it and continue **unprotected** by re-initializing with an empty config (bypass mode) so the app still functions.

### Java
```java
import android.util.Log;
import io.approov.service.okhttp.ApproovService;
import java.util.UUID;

public class YourApp extends Application {
    private static final String TAG = "YourApp";

    @Override
    public void onCreate() {
        super.onCreate();

        // App-generated id to correlate this install/session across your own logs and
        // backend. Use a UUID, or any session/user identifier you have — NOT an Approov secret.
        String correlationId = UUID.randomUUID().toString();
        try {
            ApproovService.initialize(getApplicationContext(), "<enter-your-config-string-here>");
            if (ApproovService.isApproovEnabled()) {
                Log.i(TAG, "Approov initialized; deviceID=" + ApproovService.getDeviceID()
                        + " session=" + correlationId);
            }
        } catch (Exception e) {
            // Initialization failed — log and continue UNPROTECTED so the app still works.
            Log.e(TAG, "Approov init failed (session=" + correlationId + "); continuing unprotected", e);
            ApproovService.initialize(getApplicationContext(), "");  // empty config = bypass mode
        }
    }
}
```

### Kotlin
```kotlin
import android.util.Log
import io.approov.service.okhttp.ApproovService
import java.util.UUID

class YourApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // App-generated id to correlate this install/session across your own logs and
        // backend. Use a UUID, or any session/user identifier you have — NOT an Approov secret.
        val correlationId = UUID.randomUUID().toString()
        try {
            ApproovService.initialize(applicationContext, "<enter-your-config-string-here>")
            if (ApproovService.isApproovEnabled()) {
                Log.i("YourApp", "Approov initialized; deviceID=${ApproovService.getDeviceID()} session=$correlationId")
            }
        } catch (e: Exception) {
            // Initialization failed — log and continue UNPROTECTED so the app still works.
            Log.e("YourApp", "Approov init failed (session=$correlationId); continuing unprotected", e)
            ApproovService.initialize(applicationContext, "")  // empty config = bypass mode
        }
    }
}
```

The `<enter-your-config-string-here>` is a custom string that configures your Approov account access. This will have been provided in your Approov onboarding email. On success the example logs the Approov **device ID** (`getDeviceID()`) and an **app-generated session/correlation id** so a given install can be correlated across your app logs, backend, and the Approov metrics. If initialization fails it re-initializes with an empty config so the app keeps working — but those requests go out **without Approov protection**, so the backend remains the enforcement point.

## USING APPROOV SERVICE

You can then make Approov enabled `OkHttp` API calls by using the `OkHttpClient` available from the `ApproovService`:

### Java
```java
OkHttpClient client = ApproovService.getOkHttpClient();
```

### Kotlin
```kotlin
val client = ApproovService.getOkHttpClient()
```

This obtains a cached client to be used for calls that includes an interceptor that protects channel integrity (with either pinning or managed trust roots). The interceptor may also add `Approov-Token` or substitute app secret values, depending upon your integration choices. You should thus use this client for all API calls you may wish to protect.

Approov errors will generate an `ApproovException`, which is a type of `IOException`. This may be further specialized into an `ApproovNetworkException`, indicating an issue with networking that should provide an option for a user initiated retry.

## CUSTOM OKHTTP BUILDER

By default, the method gets a default `OkHttpClient` client. However, your existing code may use a customized client with, for instance, different timeouts or other interceptors. For example, if you have existing code:

### Java
```java
OkHttpClient client = new OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).build();
```

### Kotlin
```kotlin
val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).build()
```

Pass the modified builder to the `ApproovService` framework as follows:

### Java
```java
ApproovService.setOkHttpClientBuilder(new OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS));
```

### Kotlin
```kotlin
ApproovService.setOkHttpClientBuilder(OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS))
```

This call only needs to be made once. Subsequent calls to `ApproovService.getOkHttpClient()` will then always return an `OkHttpClient` with the builder values included.

If you need multiple different builders in your application, with different configurations, then this is possible with named builders. This is an example of how to set one:

### Java
```java
ApproovService.setOkHttpClientBuilder("short-timeout", new OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS));
```

### Kotlin
```kotlin
ApproovService.setOkHttpClientBuilder("short-timeout", OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS))
```

Then in order to obtain a client using that builder use:

### Java
```java
OkHttpClient client = ApproovService.getOkHttpClient("short-timeout");
```

### Kotlin
```kotlin
val client = ApproovService.getOkHttpClient("short-timeout")
```

## CHECKING IT WORKS

Initially you won't have set which API domains to protect, so the interceptor will not add anything. It will have called Approov though and made contact with the Approov cloud service. You will see logging from Approov saying `UNKNOWN_URL`.

Your Approov onboarding email should contain a link allowing you to access [Live Metrics Graphs](https://approov.io/docs/latest/approov-usage-documentation/#metrics-graphs). After you've run your app with Approov integration you should be able to see the results in the live metrics within a minute or so. At this stage you could even release your app to get details of your app population and the attributes of the devices they are running upon.

## NEXT STEPS

To actually protect your APIs and/or secrets there are some further steps. Approov provides two different options for protection:

* **API PROTECTION**: You should use this if you control the backend API(s) being protected and are able to modify them to ensure that a valid Approov token is being passed by the app. An [Approov Token](https://approov.io/docs/latest/approov-usage-documentation/#approov-tokens) is short lived cryptographically signed JWT proving the authenticity of the call.

* **SECRETS PROTECTION**: This allows app secrets, including API keys for 3rd party services, to be protected so that they no longer need to be included in the released app code. These secrets are only made available to valid apps at runtime.

Note that it is possible to use both approaches side-by-side in the same app.

# Interface

Please see the [REFERENCE.md](REFERENCE.md) for more information on the Approov Service for OkHttp.

# Usage

Please see the [USAGE.md](USAGE.md) for more information on how to use this wrapper.

# Changelog

Please see the [CHANGELOG.md](CHANGELOG.md) for more information on the changes in each version.
