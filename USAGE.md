# Usage

This document describes the features and functionality of the Approov Service for OkHttp. It provides details on how to interact with the service layer and customize its behavior to suit your application's needs, specifically through the `ApproovServiceMutator`. For a basic integration example, please refer to the [Quickstart guide](https://github.com/approov/quickstart-android-kotlin-okhttp).

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
| **No Network / Poor Network** | Throw Exception | An `ApproovNetworkException` is thrown. The request should be retried. |
| **Rejection** | Throw Exception | An `ApproovRejectionException` is thrown. The request is marked as rejected. |
| **No Approov Service / Unknown URL** | Proceed | The request is sent **without** an `Approov-Token`. |

## Customizing Request Handling with Mutators

You may want to modify this behavior to suit specific app requirements. A common use case is handling `NO_APPROOV_SERVICE` statuses.

### Prevent Access Without a Token (e.g. NO_APPROOV_SERVICE)

The standard behavior for statuses like `NO_APPROOV_SERVICE` is to proceed with the request without adding an Approov token. This might occur, for example, if a device cannot connect to the Approov cloud due to a restricted network environment. You may wish to prevent this behavior to ensure that *only* requests with valid proof of attestation reach your backend API, allowing you to explicitly handle this case within your application.

You can use a mutator to enforce this policy by throwing an error or returning `false` for such statuses.

### Example: Enforce Token Presence

Override `handleInterceptorFetchTokenResult` to check for `NO_APPROOV_SERVICE` and prevent the request to your API from continuing; instead log the event. Since `NO_APPROOV_SERVICE` implies the SDK cannot reach the Approov servers, this could be a transient issue (e.g., no DNS server available) or a permanent configuration/network restriction. You might choose to retry the request once to handle transient errors, or if the issue persists, inform the user of a network issue and suggest checking their connection or changing networks.

```kotlin
import io.approov.service.okhttp.ApproovServiceMutator
import io.approov.service.okhttp.ApproovNetworkException
import io.approov.service.okhttp.ApproovServiceMutator.DEFAULT
import com.criticalblue.approovsdk.Approov

class EnforceTokenMutator : ApproovServiceMutator {
    override fun handleInterceptorFetchTokenResult(approovResults: Approov.TokenFetchResult, url: String): Boolean {
        // If the service is not available (NO_APPROOV_SERVICE), do not proceed.
        // This could be transient (e.g. no DNS) so we throw a networking error to trigger a retry.
        if (approovResults.status == Approov.TokenFetchStatus.NO_APPROOV_SERVICE) {
            throw ApproovNetworkException(approovResults.status, "Network issue. Will attempt connection again.")
        }

        // For all other statuses, use the default behavior.
        return DEFAULT.handleInterceptorFetchTokenResult(approovResults, url)
    }
}
```

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

Create a mutator, then install it once during app startup (for example in your Application class or initialization path).

```kotlin
import io.approov.service.okhttp.ApproovService
import io.approov.service.okhttp.ApproovServiceMutator

class MyMutator : ApproovServiceMutator {
    // Override only the hooks you need.
}
ApproovService.setServiceMutator(MyMutator()) // Install custom implementation or pass null to revert to default behaviour
```

## Approov Token Fallback Status

If the SDK cannot obtain a valid Approov token (e.g., due to a `NO_NETWORK` or `MITM_DETECTED` state), the request traditionally proceeds without the `Approov-Token` header or fails entirely depending on the current policy. To give your backend visibility into *why* there is no token, you can use `ApproovService.setUseApproovStatusIfNoToken(true)`.

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

[Token Binding](https://ext.approov.io/docs/latest/approov-usage-documentation/#token-binding) allows you to bind the Approov token to a specific piece of data, such as an OAuth token or a user session identifier. This adds an extra layer of security by ensuring that the Approov token can only be used in conjunction with the bound data. The `ApproovService` calculates a hash of the binding data locally and includes this hash in the Approov token claims. It is important to note that the actual binding data is never sent to the Approov cloud service; only the hash is transmitted.

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

An important part of your security strategy is to monitor and analyze rejections. Ideally, the server response would be customized to include the ARC and device ID in the response body or headers. However, if this is not possible, you can obtain these values from the `ApproovService` and log them to your telemetry directly from your application code.

This example shows how to log rejections with the ARC and device ID. 

```kotlin
        val response = client.newCall(request).execute()
        if (response.isSuccessful) {
            // Process request
        } else {
            // Log rejection: ARC + device ID can be added for correlating a particular request to the failure reason
            val arc = ApproovService.getLastARC() 
            val deviceID = ApproovService.getDeviceID()
            // Log rejection
            Log.d("StartFragment", "Request rejected with ARC: $arc and device ID: $deviceID; response code: ${response.code}")
        }
```

## Tips

- Keep mutator logic fast and side-effect safe. These hooks run on the request path.
- Use `ApproovServiceMutator.DEFAULT` to preserve the existing behavior and layer your changes on top.
- If you override multiple hooks, keep them focused (one concern per hook) for easier testing and maintenance.
