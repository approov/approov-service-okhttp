# Usage

This document describes the features and functionality of the Approov Service for OkHttp. It provides details on how to interact with the service layer and customize its behavior to suit your application's needs, specifically through the `ApproovServiceMutator`. For a basic integration example, please refer to the [Quickstart guide](https://github.com/approov/quickstart-android-kotlin-okhttp).

## Empty Config Initialization
You can initialize the `ApproovService` with an empty configuration string if you want to use the service layer without active Approov protection. This is useful for apps that remotely activate Approov selectively or when you need the service to function as a standard `OkHttpClient` wrapper without any Approov processing (e.g., during backend maintenance).

> ```java
> // Initialize with an empty string to operate as a standard OkHttpClient
> ApproovService.initialize(context, "");
> ```

When initialized this way, `ApproovService.getOkHttpClient()` returns an `OkHttpClient` instance that behaves exactly like a standard client. It will not perform token injection, message signing, secure string substitution, or dynamic pinning. You can enable full Approov protection later in the application lifecycle by calling `ApproovService.initialize(context, config)` with a valid configuration string.

# Approov Service Mutator

The `ApproovServiceMutator` allows you to customize the behavior of the Approov OkHttp layer at key points in the request lifecycle. You can override specific methods to tailor the handling of attestations and requests while retaining the default behavior for other cases.

## Why use a mutator

- Centralize app-specific policy without forking the service layer.
- Add telemetry on rejections or network failures.
- Skip Approov processing for health checks or local endpoints.
- Customize pinning decisions per request.
- Adjust behavior when token or secure string fetches fail.

## Default Behavior

By default, the `ApproovService` processes requests based on the attestation status. It relies on the underlying SDK to provide a proof of attestation, which is a cryptographically signed JWT token. Requesting this attestation typically returns the token immediately; however, a network connection to the Approov cloud is required upon app launch or when the token is nearing expiration. Note that the SDK only knows if an attestation token has been obtained; it cannot determine if the token is valid (validity is checked by your backend). The default behavior is described in more detail in the official documentation section [Approov Token Fetch Results](https://approov.io/docs/latest/approov-usage-documentation/#approov-token-fetch-results) and is summarized in the table below:

| Approov Fetch Status | Action | Result |
| :--- | :--- | :--- |
| **Success** | Proceed | The request acts as expected and is sent with the `Approov-Token`. |
| **No Network / Poor Network / MITM Detected** | Throw Exception | An `ApproovNetworkException` is thrown. The request should be retried. |
| **Rejection** | Throw Exception | An `ApproovRejectionException` is thrown. The request is marked as rejected. |
| **No Approov Service** | Proceed | The request is sent with an **empty** `Approov-Token` header (or carries the fetch status if `setUseApproovStatusIfNoToken(true)` is enabled). |
| **Unknown URL / Unprotected URL** | Proceed | The request is sent **without** an `Approov-Token` header. |

## Customizing Request Handling with Mutators

You may want to modify this behavior to suit specific app requirements. A common use case is handling `NO_APPROOV_SERVICE` statuses.

### Prevent Access Without a Token (e.g. NO_APPROOV_SERVICE)

The standard behavior for `NO_APPROOV_SERVICE` is to proceed with the request, which adds an empty `Approov-Token` header (unless `setUseApproovStatusIfNoToken(true)` is enabled, which adds the status string instead). For completely unrecognized or skipped URLs (e.g., `UNKNOWN_URL`), the request proceeds without adding any `Approov-Token` header at all. This might occur, for example, if a device cannot connect to the Approov cloud due to a restricted network environment. You may wish to prevent this behavior to ensure that *only* requests with valid proof of attestation reach your backend API, allowing you to explicitly handle this case within your application.

You can use a mutator to enforce this policy by throwing an error or returning `false` for such statuses.

### Example: Proceed on Selected Failure Statuses

The example below shows a custom mutator that:
- Uses the normal Approov token flow on `SUCCESS`.
- Allows requests to continue on `MITM_DETECTED` and `NO_APPROOV_SERVICE`.
- Signs the final outbound request with `ApproovDefaultMessageSigning`.

To send the failure reason in the token header when the request is allowed to continue without a real token, you must also enable `setUseApproovStatusIfNoToken(true)` as shown in the next section.


> ```java
> import com.criticalblue.approovsdk.Approov;
> import io.approov.service.okhttp.ApproovDefaultMessageSigning;
> import io.approov.service.okhttp.ApproovException;
> import io.approov.service.okhttp.ApproovService;
> import io.approov.service.okhttp.ApproovServiceMutator;
> 
> public class ProceedOnSelectedStatusesMutator extends ApproovDefaultMessageSigning {
> 
>     @Override
>     public boolean handleInterceptorFetchTokenResult(Approov.TokenFetchResult result, String url) throws ApproovException {
>         Approov.TokenFetchStatus status = result.getStatus();
> 
>         // Allow SUCCESS, MITM_DETECTED and NO_APPROOV_SERVICE to proceed
>         if (status == Approov.TokenFetchStatus.SUCCESS ||
>             status == Approov.TokenFetchStatus.MITM_DETECTED ||
>             status == Approov.TokenFetchStatus.NO_APPROOV_SERVICE) {
>             return true;
>         }
> 
>         // For all other statuses, use the default fail-closed behavior.
>         return super.handleInterceptorFetchTokenResult(result, url);
>     }
> }
> ```

### Allow Access Without Token (Optional)

Conversely, if the device could not obtain proof of attestation, for example because of a `POOR_NETWORK` or `NO_NETWORK` response from the SDK, the default behavior is to cancel the request to your API. However, you might prefer to let the request attempt the connection to your backend without the Approov Token to allow for server-side handling (e.g., returning a custom 401/403).

To implement this, check for `POOR_NETWORK` and return `false`, which proceeds without the token validation (skips adding token).

```kotlin
    if (approovResults.status == Approov.TokenFetchStatus.POOR_NETWORK) {
        return false // Proceed without token
    }
```


### Add custom headers using a mutator

You can override `handleInterceptorProcessedRequest` to add additional headers or modify the request after Approov has processed it. This is useful for adding app metadata or other diagnostics.

```kotlin
import okhttp3.Request
import io.approov.service.okhttp.ApproovRequestMutations

class MyMutator : ApproovServiceMutator {
    // If you are composing with another mutator (like a signer), initialize it here.
    // Otherwise, you can use ApproovServiceMutator.DEFAULT.
    val signer: ApproovServiceMutator = ApproovServiceMutator.DEFAULT

    /// Called after Approov has already mutated the request (token, substitutions, signing).
    ///
    /// Use this to add *additional* headers or rewrite the request further. This is also
    /// where message signing should remain in place if you use a signer mutator.
    override fun handleInterceptorProcessedRequest(request: Request,
                                           changes: ApproovRequestMutations): Request {
        val req = signer.handleInterceptorProcessedRequest(request, changes)
        // Example: attach app metadata for backend diagnostics or routing.
        return req.newBuilder()
            .addHeader("Client-Platform", "android")
            .build()
    }
}
```

## How to use a custom mutator in your application

Register your custom mutator and enable status-to-header injection during app startup:

> ```java
> import io.approov.service.okhttp.ApproovService;
> 
> // 1. Standard Initialization
> ApproovService.initialize(context, "<your-config-string>");
> 
> // 2. Enable status-as-token injection (matches React Native behavior)
> ApproovService.setUseApproovStatusIfNoToken(true);
> 
> // 3. Register your custom mutator logic
> ApproovService.setServiceMutator(new ProceedOnSelectedStatusesMutator());
> ```

## Approov Token Fallback Status

If the SDK cannot obtain a valid Approov token (e.g., due to a `NO_NETWORK` or `MITM_DETECTED` state), the request might proceed without the `Approov-Token` header or fail entirely depending on the current policy. To give your backend visibility into *why* there is no token, you can use `ApproovService.setUseApproovStatusIfNoToken(true)`.

When enabled, the service will inject the Approov fetch status directly into the `Approov-Token` header if the actual token fetch fails or is empty. Your backend can then distinguish between a request that was sent without a token due to an attacker stripping it, versus a legitimate request that encountered a specific failure like `POOR_NETWORK`. 

Please note that this behavior is conditional upon the configuration of your `ApproovServiceMutator`. If your mutator explicitly throws an error or aborts the request entirely for a particular status (for example, throwing an exception on `NO_NETWORK`), the request will never proceed to the server, and this status fallback feature will effectively not be used for that specific case.

## Message signing

It is possible to sign HTTP requests using Approov to ensure message integrity and authenticity. There are two types of message signing available:

1.  [Installation Message Signing](https://ext.approov.io/docs/latest/approov-usage-documentation/#installation-message-signing): Uses an installation-specific key (held in the device's Secure Enclave/TEE) to sign requests. This provides strong non-repudiation as the signing key never leaves the device and is unique to that specific installation.
2.  [Account Message Signing](https://ext.approov.io/docs/latest/approov-usage-documentation/#account-message-signing): Uses a shared account-specific secret key (HMAC-SHA256) to sign requests. This key is delivered to the SDK only upon successful attestation.

**Advantages of Message Signing:**
*   **Integrity:** Ensures that the request parameters (headers, body, URL) have not been tampered with during transit.
*   **Authenticity:** Proves that the request originated from a genuine, attested application instance.

Message signing is not enabled unless you opt in. By default, the `ApproovService` uses the interface `ApproovServiceMutator` default, which does no message signing. Even if you install `ApproovDefaultMessageSigning`, a signature is only added when:

- The request already has an `Approov-Token` header (i.e., Approov processing ran).
- A `SignatureParametersFactory` is configured (default or host-specific).

### Enable with default settings

```kotlin
import io.approov.service.okhttp.ApproovDefaultMessageSigning
import io.approov.service.okhttp.ApproovService

val factory = ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()
val signer = ApproovDefaultMessageSigning().setDefaultFactory(factory)
ApproovService.setServiceMutator(signer)
```

If you have already customized the mutator, you can add message signing to it by composing or delegating.

### Customize behavior

```kotlin
import io.approov.service.okhttp.ApproovDefaultMessageSigning.SignatureParametersFactory

val factory = SignatureParametersFactory()
    .setUseAccountMessageSigning() // or setUseInstallMessageSigning()
    .setAddCreated(true)
    .setExpiresLifetime(60)

val signer = ApproovDefaultMessageSigning()
    .setDefaultFactory(factory)
    .putHostFactory("api.example.com", factory)

ApproovService.setServiceMutator(signer)
```

To disable signing, remove the signer (`setServiceMutator(null)`) or return `null` from your factory for hosts you want to skip.

## Token Binding

[Token Binding](https://ext.approov.io/docs/latest/approov-usage-documentation/#token-binding) allows you to bind the Approov token to a specific piece of data, such as an OAuth token or a user session identifier. This adds an extra layer of security by ensuring that the Approov token can only be used in conjunction with the bound data. The `ApproovService` calculates a hash of the binding data locally and supplies that hash to Approov so the resulting token can carry the corresponding `pay` claim. It is important to note that the actual binding data is never sent to the Approov cloud service; only the hash is transmitted.

To set up token binding, you specify a header name. The value of this header in your requests will be used for the binding.

### Example: Bind to Authorization Header

```kotlin
// Bind the Approov token to the Authorization header (e.g., for OAuth)
ApproovService.setBindingHeader("Authorization")
```

If the value of the binding header changes (e.g., the user logs in and gets a new OAuth token), the SDK automatically invalidates the current Approov token and fetches a new one with the updated binding on the next request.

## Real-world examples

### Policy-driven mutator (host scoping, offline fallback, message signing, pinning)

This example implementation demonstrates how to customize the `ApproovServiceMutator` to apply different options to API requests based on the hostname.

```kotlin
import okhttp3.Request
import io.approov.service.okhttp.*
import com.criticalblue.approovsdk.Approov

class CustomLogic(
    private val signer: ApproovServiceMutator = ApproovDefaultMessageSigning(),
    private val protectedHosts: Set<String> = setOf("api.example.com"),
    private val allowOfflineForHosts: Set<String> = setOf("status.example.com"),
    private val skipPinningHosts: Set<String> = setOf("metrics.example.com")
) : ApproovServiceMutator {

    override fun handleInterceptorShouldProcessRequest(request: Request): Boolean {
        val host = request.url.host
        if (!protectedHosts.contains(host)) return false
        return ApproovServiceMutator.DEFAULT.handleInterceptorShouldProcessRequest(request)
    }

    override fun handleInterceptorFetchTokenResult(approovResults: Approov.TokenFetchResult,
                                           url: String): Boolean {
        val host = java.net.URI(url).host
        if ((approovResults.status == Approov.TokenFetchStatus.NO_NETWORK || 
             approovResults.status == Approov.TokenFetchStatus.POOR_NETWORK) &&
           allowOfflineForHosts.contains(host)) {
            return false
        }
        return ApproovServiceMutator.DEFAULT.handleInterceptorFetchTokenResult(approovResults, url)
    }

    override fun handleInterceptorProcessedRequest(request: Request,
                                           changes: ApproovRequestMutations): Request {
        val req = signer.handleInterceptorProcessedRequest(request, changes)
        return req.newBuilder()
            .addHeader("X-Client-Platform", "android")
            .build()
    }

    override fun handlePinningShouldProcessRequest(request: Request): Boolean {
        val host = request.url.host
        if (skipPinningHosts.contains(host)) return false
        return true
    }
}
```

### Log rejections with ARC + device ID to your telemetry

Monitoring and analyzing rejections is a key part of your security strategy. Ideally, your backend should be customized to include the **ARC (Approov Rejection Code)** and **Device ID** in its error responses (e.g., in a JSON body or a custom header) when it rejects a request due to a missing or invalid Approov token.

#### Why Server-Side Logging is Preferred
While you can obtain these values directly from the SDK using `ApproovService.getLastARC()`, it is generally safer and more reliable to log them from the server response for several reasons:

1.  **Avoid Misleading Network Events**: On poor network connections, a call to `getLastARC()` can inadvertently trigger a background network event that successfully completes a delayed attestation. This might provide an ARC associated with a *successful* attestation that occurred *after* your original request failed, creating confusing telemetry.
2.  **Corporate Firewall & MITM Bypass**: If your custom mutator allows a request to proceed on `MITM_DETECTED` (a common result of corporate firewalls), the request is sent without a token. In this state, `getLastARC()` will not yet have a rejection code available for that specific attempt.
3.  **Accuracy and Correlation**: Logging the ARC that the server actually observed and used as the basis for rejection ensures perfect correlation in your monitoring dashboards.

If you must log from the client, ensure you have a fallback strategy for when the server doesn't provide the code.


> ```kotlin
>         val response = client.newCall(request).execute()
>         if (response.isSuccessful) {
>             // Process request
>         } else {
>             // Preferred: Extract ARC and Device ID from your own server's response
>             val serverArc = response.header("X-Approov-Error-ARC") 
>             
>             // ALTERNATIVE: (DISCOURAGED) Obtain from SDK only if server-side retrieval is impossible.
>             // Note: This may trigger background network events and return misleading results.
>             val sdkArc = serverArc ?: ApproovService.getLastARC()
>             val deviceID = ApproovService.getDeviceID()
>             
>             Log.d("Telemetry", "Request rejected. ARC: $sdkArc, DeviceID: $deviceID")
>         }
> ```

## Tips

- Keep mutator logic fast and side-effect safe. These hooks run on the request path.
- Use `ApproovServiceMutator.DEFAULT` to preserve the existing behavior and layer your changes on top.
- If you override multiple hooks, keep them focused (one concern per hook) for easier testing and maintenance.
