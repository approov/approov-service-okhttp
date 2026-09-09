# Advanced Options

A standard integration of the Approov Package for OkHttp needs nothing in this document: add the dependency, initialize, use the client (see the [README](README.md)). The options below change the defaults, and are for apps with specific requirements. Every method is documented in [REFERENCE.md](REFERENCE.md).

## What happens by default

For a request to a protected API domain, the `ApproovService` interceptor fetches an Approov token and applies the decisions below. These are made by the installed `ApproovServiceMutator` (see [Service mutators](#service-mutators)); the table shows the default.

| Approov fetch status | Request | `Approov-Token` | `Approov-Status` |
| :--- | :--- | :--- | :--- |
| `SUCCESS` | proceeds | the token | `success` |
| `UNKNOWN_URL`, `UNPROTECTED_URL` | proceeds untouched | not sent | not sent |
| any other status (`NO_NETWORK`, `POOR_NETWORK`, `UNTRUSTED_NETWORK`, `NO_APPROOV_SERVICE`, `REJECTED`, `INTERNAL_ERROR`, ...) | proceeds | sent **empty** | the status, lowercased (`no_network`, ...) |

A secure string substitution (see [Secure strings](#secure-strings)) that fails leaves the placeholder value in the header or query parameter and the request proceeds; the backend sees the placeholder. A fetch outcome other than `SUCCESS` is an outcome, not a failure: no runtime condition aborts a request, the backend is the enforcement point and receives the evidence it needs to decide. The only exceptions an app sees from the request path are its own configuration errors (a body digest configured as required that cannot be generated, or an unsupported signature algorithm), pinning failures on its API domains, which fail the connection with `javax.net.ssl.SSLPeerUnverifiedException` like any OkHttp pinning failure, and aborts the app itself opted in to through a [service mutator](#service-mutators).

The direct methods (`fetchToken`, `fetchSecureString`, `fetchCustomJWT`, `precheck`) return a value to the caller and therefore still report failures by throwing an `ApproovException`; see the [reference](REFERENCE.md).

## The `Approov-Status` header

Every request processed by Approov carries the `Approov-Status` header with the SDK token fetch status in lowercase, identical on Android and iOS: `success`, `no_network`, `poor_network`, `untrusted_network`, `no_approov_service`, `rejected`, `internal_error`, and so on. It is sent on successful requests too, so the backend can tell a request whose Approov headers were stripped from one this layer sent without a token because the fetch failed. It is not sent to domains that are not protected by Approov. Secure string substitution failures are not reported on it: the placeholder value left in the header or query parameter is the evidence.

The header name can be changed, and the header disabled by passing `null`, in which case a request that could not be protected is sent with an empty token header and no explanation:

```kotlin
ApproovService.setStatusHeader("X-Approov-Fetch-Status")
ApproovService.setStatusHeader(null)
```

With the default message signing the status header is covered by the signatures, so it cannot be stripped or altered in transit without invalidating them.

## Message signing

Every request carrying an Approov token header is signed by default with **both** the install signature (`ecdsa-p256-sha256`, per app installation key held in the device secure hardware, dictionary member `install`) and the account signature (`hmac-sha256`, shared account key delivered on attestation, member `account`). They are emitted as two members of the same `Signature` and `Signature-Input` headers over the same covered components, so that a device without secure hardware still yields a verifiable signature; the backend chooses which it verifies. If one signature cannot be produced the request proceeds with the other, or unsigned. See [Installation Message Signing](https://approov.io/docs/latest/approov-usage-documentation/#installation-message-signing) and [Account Message Signing](https://approov.io/docs/latest/approov-usage-documentation/#account-message-signing).

The default signature covers the request method and target URI, the `Approov-Token` header, the `Approov-TraceID` and `Approov-Status` headers when present, the `Authorization`, `Content-Length` and `Content-Type` headers when present, a SHA-256 `Content-Digest` of the body when one can be computed, a `created` timestamp and a 15 second `expires`.

### Switching message signing off

```kotlin
ApproovService.setServiceMutator(ApproovServiceMutator.DEFAULT)
```

`ApproovServiceMutator.DEFAULT` keeps every other default decision. Passing `null` to `setServiceMutator` reinstates the signing default.

### Choosing the signatures and the covered components

Start from the default factory and override what you need; a bare `SignatureParametersFactory()` covers nothing and is worthless:

```kotlin
import io.approov.service.okhttp.ApproovDefaultMessageSigning
import io.approov.service.okhttp.ApproovService

val factory = ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()
    .setUseInstallMessageSigning()          // install only; or setUseAccountMessageSigning(), or
                                            // setUseInstallAndAccountMessageSigning() (the default)
    .setExpiresLifetime(60)                 // default 15s
    .addOptionalHeaders("X-Request-Id")     // covered when present
    .setBodyDigestConfig(ApproovDefaultMessageSigning.DIGEST_SHA256, true) // digest required: a body that
                                            // cannot be digested fails the request (a configuration error)

// a factory is shared by every request it is installed for, so use a separate
// instance for a host that needs different settings
val paymentsFactory = ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()
    .setExpiresLifetime(5)

ApproovService.setServiceMutator(
    ApproovDefaultMessageSigning()
        .setDefaultFactory(factory)
        .putHostFactory("payments.example.com", paymentsFactory))
```

A host factory applies to requests to that host; the default factory to every other protected host. Return `null` from a custom factory to leave a request unsigned.

## Header names

```kotlin
ApproovService.setTokenHeader("Authorization", "Bearer ")   // default "Approov-Token", no prefix
ApproovService.setTraceIDHeader(null)                       // default "Approov-TraceID"; null disables
ApproovService.setStatusHeader("X-Approov-Fetch-Status")          // default "Approov-Status"; null disables
```

## About the Approov account ID

The string passed to `initialize` (the CLI and the SDK call it the SDK config string, `approov sdk -getConfigString`) looks like `#your-account#p6nZ...=`. It names your account and carries a fingerprint of the account's public key, so the SDK knows which Approov service to attest against and can verify the configuration it later downloads. It is not a secret and does not rotate; the same value serves every app in the account, and registering an app is a separate step. It is not an API key: nothing is granted by knowing it.

## Bypass initialization

Initializing with an empty string instead of the Approov account ID keeps the package initialized but returns plain `OkHttpClient` instances with no Approov processing (no token, signing, secure strings or pinning). This is a bootstrap or fallback state, for example while the account ID is fetched remotely, or as the guard in the README example against an account ID that does not reach the app intact. A later `initialize` with the account ID enables Approov at runtime; reinitializing from one account ID to a different one is rejected by the SDK.

```java
ApproovService.initialize(context, "");
```

## Token binding

[Token binding](https://approov.io/docs/latest/approov-usage-documentation/#token-binding) ties the Approov token to the value of a header on the request, typically an OAuth `Authorization` header, so a token cannot be replayed with different credentials. Only a hash of the value reaches Approov, as the `pay` claim of the token, which the backend verifies.

```kotlin
ApproovService.setBindingHeader("Authorization")
```

`setDataHashInToken` binds arbitrary data instead. Never use both: the binding header overrides the data hash on every request.

## Secure strings

[Secure strings](https://approov.io/docs/latest/approov-usage-documentation/#secure-strings) let API keys and other secrets be removed from the app and delivered only to attested installations. Register the headers, and the query parameters, whose values are secure string keys to be substituted:

```kotlin
ApproovService.addSubstitutionHeader("X-Api-Key", null)        // whole value is the key
ApproovService.addSubstitutionHeader("Authorization", "Bearer ") // value after the prefix is the key
ApproovService.addSubstitutionQueryParam("api_key")
```

A value whose substitution fails for any reason (including `UNKNOWN_KEY`, meaning no secure string is defined for it) is left unchanged and the request proceeds. Header names are matched case-insensitively, and a header cannot be both a binding header and a substitution header. `fetchSecureString` fetches or defines a secure string directly.

## Excluding URLs

Requests whose URL matches an exclusion regular expression are sent untouched, without a token fetch:

```kotlin
ApproovService.addExclusionURLRegex("https://api\\.example\\.com/health.*")
```

Pinning still applies to excluded requests on pinned domains.

## Service mutators

An `ApproovServiceMutator` centralizes app-specific policy without forking the package. The installed mutator is consulted at each decision point; every method has a default, so override only what you need. The hooks:

| Hook | Decides |
| :--- | :--- |
| `handleInterceptorShouldProcessRequest` | whether a request gets Approov processing at all (default: unless excluded) |
| `handleInterceptorFetchTokenResult` | given the token fetch result, `true` to add the token header (empty if there is no token), `false` to send the request with no Approov headers, or throw a standard network stack exception to abort the request |
| `handleInterceptorHeaderSubstitutionResult`, `handleInterceptorQueryParamSubstitutionResult` | whether to apply a secure string substitution |
| `handleInterceptorProcessedRequest` | final changes to the processed request; this is where `ApproovDefaultMessageSigning` signs |
| `supportsProtectionRefresh` | whether the processed request callback may run again on a stale request (see below) |
| `handlePinningShouldProcessRequest` | whether pinning applies to a request |
| `handlePrecheckResult`, `handleFetchTokenResult`, `handleFetchSecureStringResult`, `handleFetchCustomJWTResult` | how the direct methods map a result to an exception |

The status header is set by the interceptor from the token fetch result whenever the mutator returns `true` from `handleInterceptorFetchTokenResult`; returning `false` sends the request with no Approov headers at all.

### Opting in to aborting requests

An app may decide that some outcomes must not reach its backend at all, for example that a payments host only ever receives requests carrying a token. That is the app's policy, so it is expressed by overriding the hook and throwing. The exception must be a standard network stack exception (`java.io.IOException` or a platform subclass such as `java.net.ConnectException` or `javax.net.ssl.SSLException`), not an Approov type: the abort then surfaces to the app's own error handling, retry logic and support tooling exactly like any other network failure, with the Approov status available from the result for the app's own logging. The interceptor hooks declare `IOException` for this purpose; the default implementations never throw.

Extend `ApproovDefaultMessageSigning` to keep the default signing while changing other decisions:

```kotlin
import android.util.Log
import com.criticalblue.approovsdk.Approov
import io.approov.service.okhttp.*
import okhttp3.Request

class AppPolicy : ApproovDefaultMessageSigning() {
    init { setDefaultFactory(ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()) }

    // opt in to aborting for one host: an explicit app decision, surfaced as an
    // ordinary network failure rather than an Approov exception type
    override fun handleInterceptorFetchTokenResult(result: Approov.TokenFetchResult, url: String): Boolean {
        if (java.net.URI(url).host == "payments.example.com" && result.status != Approov.TokenFetchStatus.SUCCESS) {
            Log.w("AppPolicy", "payments request aborted, Approov status ${result.status}")
            throw java.net.ConnectException("payments unavailable")
        }
        return super.handleInterceptorFetchTokenResult(result, url)
    }

    // add app metadata after Approov processing and signing
    override fun handleInterceptorProcessedRequest(request: Request, changes: ApproovRequestMutations): Request =
        super.handleInterceptorProcessedRequest(request, changes).newBuilder()
            .header("X-Client-Platform", "android").build()

    // skip pinning for a telemetry host
    override fun handlePinningShouldProcessRequest(request: Request): Boolean =
        request.url.host != "metrics.example.com"
}

ApproovService.setServiceMutator(AppPolicy())
```

Keep hooks fast and free of side effects: they run on the request path. A mutator's processed request callback may be invoked again by the stale protection refresh only if `supportsProtectionRefresh()` returns `true` (it does for `ApproovServiceMutator.DEFAULT` and `ApproovDefaultMessageSigning`).

## Stale protection refresh

A request held between Approov processing and transmission, for example by a device doze period or an app-level queue, would otherwise go out with an expired token or signature. A network interceptor strips and reapplies the protection immediately before transmission when the request has been held for longer than the refresh period (default 3000 ms): the token is refetched (from the SDK cache when still valid), the status header reflects the new result, substitutions are redone and the signatures regenerated. It runs on every network attempt, so OkHttp's own retries are refreshed too, and a **redirect** is reclassified for its new URL: a redirect to a domain Approov does not protect leaves with no Approov headers, and a redirect within a protected domain is re-signed over the new target. Any attempt whose headers differ from the protected request, typically an OkHttp `Authenticator` retrying a `401` with a new `Authorization`, is reprotected the same way, so the signature always covers what is sent. A header the app changed between attempts is kept and substituted afresh; only headers still holding the value this layer installed are restored to their placeholders.

Two limits, accepted by design:

* Only requests that were given protection are reclassified on redirect. A request to a domain that is not in Approov which redirects into a protected domain arrives at the protected domain without protection and the backend rejects it. Add every domain the app calls, including legacy or alias domains that redirect, to Approov.
* The layer restores what it added, not what it replaced. If the app itself sets `Signature` or `Signature-Input` headers on a request that Approov also signs, the value this layer wrote is treated as the app's on a redirect and travels with it. Do not set those headers yourself on Approov-protected requests.

```kotlin
ApproovService.setStaleProtectionRefreshPeriod(1000)   // <= 0 disables
```

## Diagnostics

The `Approov-TraceID` header carries an optional SDK trace value for correlating a request with the Approov logs. Log output from the package is at `DEBUG` level under the tags `ApproovService`, `ApproovTokenInterceptor`, `ApproovFreshness`, `ApproovPinningInterceptor` and `ApproovMsgSign`; the loggable form of each fetched token is logged and can be [checked](https://approov.io/docs/latest/approov-usage-documentation/#loggable-tokens) with the Approov CLI. Prefer logging rejections from your backend's response, which saw the token and the `Approov-Status` header, over calling `getLastARC()` on the device: the device value may belong to a later attestation than the request that failed.
