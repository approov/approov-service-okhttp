# Approov Service for OkHttp

A wrapper for the [Approov SDK](https://github.com/approov/approov-android-sdk) to enable easy integration when using [`OkHttp`](https://square.github.io/okhttp/) for making the API calls that you wish to protect with Approov. In order to use this you will need a trial or paid [Approov](https://www.approov.io) account.

## Adding ApproovService Dependency
The Approov integration is available via [`jitpack`](https://jitpack.io). This allows inclusion into the project by simply specifying a dependency in the `gradle` files for the app.

Firstly, `jitpack` needs to be added to the end the `repositories` section in the `build.gradle` file at the top root level of the project:

```
allprojects {
    repositories {
        ...
        maven { url 'https://jitpack.io' }
    }
}
```

Secondly, add the dependency in your app's `build.gradle`:

```
dependencies {
	 implementation 'com.github.approov:approov-service-okhttp:2.7.0'
}
```

This package is actually an open source wrapper layer that allows you to easily use Approov with `OkHttp`. This has a further dependency to the closed source [Approov SDK](https://github.com/approov/approov-android-sdk).

## Using ApproovService
In order to use the `ApproovService` you must initialize it when your app is created, usually in the `onCreate` method:

```Java
import io.approov.service.okhttp.ApproovService;

public class YourApp extends Application {
    public static ApproovService approovService;

    @Override
    public void onCreate() {
        super.onCreate();
        approovService = new ApproovService(getApplicationContext(), "init-config");
    }
}

```

The `init-config` is a custom string that configures your Approov account access. Obtain this using the Approov CLI:

```
$ approov sdk -getConfig initial-config.txt
```

Paste the file content of `initial-config.txt` into the `init-config` string above.

You can then make Approov enabled `OkHttp` API calls by using the `OkHttpClient` available from the `ApproovService`:

```Java
OkHttpClient client = YourApp.approovService.getOkHttpClient();
```

This obtains a cached client to be used for calls that includes an interceptor to add the `Approov-Token` header and pins the connections. You must always call this method whenever you want to make a request to ensure that you are using the most up to date client. Failure to do this will mean that the app is not able to dynamically change its pins.

## Custom OkHttp Builder
By default, the method gets a default client constructed with `new OkHttpClient()`. However, your existing code may use a customized client with, for instance, different timeouts or other interceptors. For example, if you have existing code:

```Java
OkHttpClient client = new OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).build();
```
Pass the modified builder to the `ApproovService` framework as follows:

```Java
YourApp.approovService.setOkHttpClientBuilder(new OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS));
```

This call only needs to be made once. Subsequent calls to `YourApp.approovService.getOkHttpClient()` will then always a `OkHttpClient` with the builder values included.

## Approov Token Header
The default header name of `Approov-Token` can be changed as follows:

```Java
YourApp.approovService.setApproovHeader("Authorization", "Bearer ")
```

The first parameter is the new header name and the second a prefix to be added to the Approov token. This is primarily for integrations where the Approov Token JWT might need to be prefixed with `Bearer` and passed in the `Authorization` header.

## Token Binding
If you are using [Token Binding](https://approov.io/docs/latest/approov-usage-documentation/#token-binding) then set the header holding the value to be used for binding as follows:

```Java
YourApp.approovService.setBindingHeader("Authorization")
```

In this case it means that the value of `Authorization` holds the token value to be bound. This only needs to be called once. On subsequent requests the value of the specified header is read and its value set as the token binding value. Note that if the header is not present on a request then the value `NONE` is used. Note that you should only select a header that is normally always present and the value does not typically change from request to request, as each change requires a new Approov token to be fetched.

## Token Prefetching
If you wish to reduce the latency associated with fetching the first Approov token, then make this call immediately after creating `ApproovService`:

```Java
YourApp.approovService.prefetchApproovToken()
```

This initiates the process of fetching an Approov token as a background task, so that a cached token is available immediately when subsequently needed, or at least the fetch time is reduced. Note that there is no point in performing a prefetch if you are using token binding.

## Configuration Persistence
An Approov app automatically downloads any new configurations of APIs and their pins that are available. These are stored in the [`SharedPreferences`](https://developer.android.com/reference/android/content/SharedPreferences) for the app in a preference file `approov-prefs` and key `approov-config`. You can store the preferences differently by modifying or overriding the methods `ApproovService.putApproovDynamicConfig` and `ApproovService.getApproovDynamicConfig`.
