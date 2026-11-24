# Advanced Options

The `approov-service-okhttp` package works through the `OkHttpClient` that you get from `ApproovService`. After you call `initialize` with your account ID, the package adds an Approov token to each request to your API domains. The token is a signed JWT that provides proof of attestation, which your backend can check.

A standard integration of the Approov Package for OkHttp needs nothing from this document. You add the dependency, initialize, and use the client, as the [README](README.md) shows. The options below change the defaults, suitable for applications with specific needs. You can find every method used in this document in [REFERENCE.md](REFERENCE.md).

| What you need | Read first | Then |
| :--- | :--- | :--- |
| Understand what reaches your backend | [What each request carries](#what-each-request-carries) | [What happens by default](#what-happens-by-default) |
| Change how network failures are handled | [What happens by default](#what-happens-by-default) | [Sending the status in the token header](#sending-the-status-in-the-token-header), or [Service mutators](#service-mutators) |
| Protect request contents with signatures | [Message signing](#message-signing) | [Choosing the signatures and the covered components](#choosing-the-signatures-and-the-covered-components), then [Account Message Signing](https://docs.approov.io/operations/message-signing/account/) |
| Replace an embedded API secret | [Secure strings](#secure-strings) | [Secure string results](#secure-string-results), [When placeholders stay in place](#when-placeholders-stay-in-place) |
| Configure startup or runtime activation | [Initialize, then configure](#initialize-then-configure) | [Bypass initialization](#bypass-initialization) |
| Investigate an unexpected result | [Diagnostics](#diagnostics) | the matching table in [What happens by default](#what-happens-by-default) |

## What each request carries

What the package adds to a request depends on two things:

* the kind of host: a token-protected API domain, a secrets-only API domain, or a host not protected by Approov (one you have not added)
* the outcome of the attestation attempt for the request

Both come from that one outcome. From it, the package and its [service mutator](#service-mutators) decide what to add to the request, and whether the request goes ahead at all.

| Request goes to | Outcome | What the package adds |
| :--- | :--- | :--- |
| a token-protected domain: an API domain you added to Approov | `SUCCESS` | the token header, and the trace header when the SDK gives a trace ID. Your secure strings, or the [status of each one that could not be fetched](#secure-strings). The signatures, once you install [message signing](#message-signing). A checked connection. |
| a secrets-only domain: an API domain you added with `approov api -add <domain> -noApproovToken` | `UNPROTECTED_URL` | your secure strings, or the status of each one that could not be fetched. A checked connection. No token or trace header, and no signatures. |
| a host not protected by Approov | `UNKNOWN_URL` | nothing. The connection relies on the device's trust store. |
| a token-fetch failure, to a host protected by Approov | a failure status | depends on the service mutator (see below) |
| a token-fetch failure, to a host not protected by Approov | a failure status | nothing (see below) |

A failure status means the app has no token to add. For example, there was no network, or the Approov service could not be reached. The [service mutator](#what-happens-by-default) then decides whether the request fails in the app or is sent. A request that is sent carries the `Approov-Token` header with an empty token after any prefix. If you turned on [setUseApproovStatusIfNoToken](#sending-the-status-in-the-token-header), it carries the status name instead. It has no trace header and no secure strings. With message signing installed, it is signed.

For a host not protected by Approov, the request goes out unchanged, whatever the outcome. The standard mutators never fail it in the app. Only your own mutator can, by throwing from `handleInterceptorFetchTokenResult` (see [Service mutators](#service-mutators)).

The headers your backend may receive:

* `Approov-Token` carries the Approov token, or is empty (or holds the [status](#sending-the-status-in-the-token-header)) when no token could be fetched. Only your backend can tell from the token whether the app passed. See [Approov tokens](https://docs.approov.io/core-concepts/approov-tokens/architecture-and-security/).
* `Signature` and `Signature-Input` carry the signatures, once you install [message signing](#message-signing).
* `Approov-TraceID` is an optional debug header. Your backend passes it through unchanged.

Whatever the attestation outcome, the package checks the TLS connection to each domain you added, token-protected or secrets-only (see [Connection validation](#connection-validation)).

### Secrets-only APIs

An API you added with `-noApproovToken` is secrets-only. Its connections are checked, and its requests may use [secure strings](#secure-strings). When its token fetch succeeds, the SDK reports it as `UNPROTECTED_URL`, and the request carries no Approov header.

## What happens by default

For every request it processes, the `ApproovService` interceptor fetches an Approov token for the request URL. It does not process a request before `initialize` or in [bypass mode](#bypass-initialization), a [WebSocket](#websockets) upgrade, or a request to an [excluded URL](#excluding-urls). It then applies the decisions of the installed `ApproovServiceMutator`. A service mutator is an object with one method for each decision that the package makes. For example, one method decides whether a failed token fetch fails the request (see [Service mutators](#service-mutators)). The package provides three standard ones:

* `ApproovServiceMutator.CLOSE_FAILURE` keeps the 3.5.x token decisions for your API domains. A failed token fetch fails the request in the app, with the same `ApproovException` types as 3.5.x. A failed token fetch for a host not in the pin set no longer fails the request (see [Hosts in the pin set](#hosts-in-the-pin-set)). A secure string that cannot be fetched never fails a request, whatever its status. The status replaces the placeholder and the request goes ahead.
* `ApproovServiceMutator.ALWAYS_PROCEED` never fails a request because of its token fetch. Whatever the token fetch outcome, the request is sent, and your backend decides. Your backend is then the enforcement point.
* `ApproovServiceMutator.DEFAULT` is the mutator installed out of the box. `setServiceMutator(null)` puts it back, and so does each `initialize` that succeeds. `DEFAULT` points to whatever the release considers default behaviour, which is currently `CLOSE_FAILURE`.

To opt in to always proceeding:

```kotlin
ApproovService.setServiceMutator(ApproovServiceMutator.ALWAYS_PROCEED)
```

In the table below, "a host in the pin set" means one of the API domains you added to Approov, of either kind. The next section explains the term.

| Approov token fetch status | `CLOSE_FAILURE` (default) | `ALWAYS_PROCEED` |
| :--- | :--- | :--- |
| `SUCCESS` | proceeds with the token, any trace header, the secure strings and, with message signing installed, the signatures | same |
| `UNPROTECTED_URL` (secrets-only API) | proceeds with the secure strings only, no Approov headers | same |
| `UNKNOWN_URL` (host not protected by Approov) | proceeds untouched | same |
| `NO_APPROOV_SERVICE`, host in the pin set | proceeds with an empty token header (or the status), but no trace header and no secure strings | same |
| `NO_NETWORK`, `POOR_NETWORK`, `MITM_DETECTED`, host in the pin set | fails with `ApproovNetworkException`, which can be retried. This is a deprecated subclass of `ApproovFetchStatusException`, so catch `ApproovFetchStatusException` and read `getTokenFetchStatus()`. With `setUseApproovStatusIfNoToken(true)` it proceeds with the status in the token header instead | proceeds with an empty token header (or the status), but no trace header and no secure strings |
| any other status (`INTERNAL_ERROR`, `BAD_URL`, ...), host in the pin set | fails with `ApproovFetchStatusException` | proceeds with an empty token header (or the status), but no trace header and no secure strings |
| any failure status, host not in the pin set (or the pin set is empty or cannot be read) | proceeds untouched, and no standard mutator fails it | same |

"The status" in this table means the status name in the token header, after any prefix, when you turn on [setUseApproovStatusIfNoToken](#sending-the-status-in-the-token-header). A request that goes ahead after a failed token fetch is signed too, if you installed [message signing](#message-signing).

`NO_APPROOV_SERVICE` means the app could not reach the Approov service or its failover. Requests to your API domains then go out with an empty token header (or the status) under either standard mutator.

These are the token fetch decisions only. Under either mutator, these still fail the request (see [Exceptions your app can see](#exceptions-your-app-can-see)):

* a TLS connection that fails the check
* a message signing configuration error
* an abort that your own hooks ask for
* a failure of the Approov SDK itself, or a value that the request cannot carry

### Hosts in the pin set

A host is in the pin set if it is a key of the SDK's pin set (`public-key-sha256`). The comparison ignores case and one trailing dot. The `*` key, which holds the Managed Trust Roots, does not count. So every API domain you added, of either kind, is in the pin set.

The pin set matters because a failure status says nothing about the host. The SDK looks up the domain only after a successful fetch. So the pin set decides whether a failure concerns your API at all.

A request to any other host is treated like one to a host not protected by Approov. It goes out unchanged, and no standard mutator fails it in the app. Your own mutator is still asked, through `handleInterceptorFetchTokenResult`, and may fail the request by throwing. This is an opt-in for an app that should only ever connect to its own protected hosts. Returning `true` or `false` adds nothing there. There, the package ignores an exception from a standard decision that your mutator inherits or calls.

While the pin set is empty, every failing request is sent this way. The pin set is empty before the first dynamic configuration arrives, and on a device in unpin mode.

For everything else, the token fetch status alone decides.

### Secure string results

Secure strings (see [Secure strings](#secure-strings)) are substituted only on a request over `https` whose token fetch status is `SUCCESS` or `UNPROTECTED_URL`. On such a request, each placeholder is decided by its own secure string fetch:

| Secure string fetch status | `CLOSE_FAILURE` (default) | `ALWAYS_PROCEED` |
| :--- | :--- | :--- |
| `SUCCESS` | the secret is substituted | same |
| `SUCCESS`, but the secret is a value the header or URL cannot carry | the placeholder is left in place, the request proceeds and a warning names the header or query parameter, never the value | same |
| `UNKNOWN_KEY` (the value is not a secure string key, the key was removed from the account, or its value cannot be decrypted) | the value is left as your app wrote it and the request proceeds | same |
| any other status (`REJECTED`, `DISABLED`, `NO_APPROOV_SERVICE`, `NO_NETWORK`, `POOR_NETWORK`, `MITM_DETECTED`, `INTERNAL_ERROR`, ...) | the status name (`REJECTED`, `DISABLED`, `NO_APPROOV_SERVICE`, ...) replaces the placeholder and the request proceeds | same |

The status is the SDK status name, unchanged. A header becomes, for example, `X-Api-Key: REJECTED`, and a required prefix stays in front of the status. Each occurrence of a query parameter gets the status of its own fetch, as in `?api_key=NO_NETWORK`.

A standard mutator never fails a request because of a secure string. Your own mutator can, by throwing from its substitution hooks (see [Service mutators](#service-mutators)). A status in the token header always reports the token fetch, never a secure string fetch.

### Values a header or URL cannot carry

A header can carry only tab and printable ASCII characters. Two kinds of value may break that rule:

* a secure string set in the account with a non-ASCII or DEL character
* any value your app set with a new definition (`fetchSecureString(key, newDef)`)

Such a value is never substituted into a header.

In a query parameter, the package reads the URL back as OkHttp stores it. It substitutes a value only if the value comes back unchanged or percent-encoded. OkHttp percent-encodes non-ASCII and DEL characters, and your backend decodes them to the same value, so those values are substituted. But OkHttp silently drops tab, LF, FF and CR from a URL. An `&` or `#` in the value would end the parameter or start a fragment. A value containing any of these keeps the placeholder. A `%` followed by two hex digits, or a `+`, comes back unchanged, so the package substitutes it. But a backend that decodes the query reads a different value (see [Keys and values](#keys-and-values)).

The Approov token and the trace ID are always base64url. If the SDK issues one that a header cannot carry, the package reports it as an SDK problem, never as your configuration. The exception is `ApproovException("Approov header <name>: invalid character in SDK token")`, or `SDK trace ID` in place of `SDK token`. It never quotes the value.

A value you supply yourself that a header cannot carry is a configuration error. Examples are a token prefix given to `setTokenHeader`, or a header your mutator sets in `handleInterceptorProcessedRequest`. The request fails with `ApproovException("Approov header <name>: invalid character in value")`, which never quotes the value.

### Exceptions your app can see

A request attempt that fails under `CLOSE_FAILURE` is never sent. On a redirect, a retry or a refresh, the decision runs again for the new attempt. So an earlier attempt of the same call can have reached your backend. All the exceptions above are `ApproovException`s, and so `IOException`s. `execute()` throws them, and an enqueued call receives them in `Callback.onFailure`.

Under either mutator, a request can also fail for these reasons:

* a configuration error in your app: a body digest configured as required that cannot be generated, or an unsupported signature algorithm. Both fail with an `ApproovException`.
* a TLS connection to one of your API domains that fails the [connection check](#connection-validation). This fails with `javax.net.ssl.SSLPeerUnverifiedException`, the same exception that OkHttp's own `CertificatePinner` throws.
* an abort your app opted in to through a [service mutator](#service-mutators).
* a failure of the Approov SDK itself. Examples are a fetch that throws or returns no result, and keys that cannot be read after a configuration change. This fails with an `ApproovException`.
* a value the request cannot carry, such as an SDK token or trace ID, a token prefix or a header from your mutator. A URL that OkHttp refuses after query substitution is the same. These fail with an `ApproovException` (see [Values a header or URL cannot carry](#values-a-header-or-url-cannot-carry)).
* a substitution value longer than 64 characters (see [Keys and values](#keys-and-values)).

The direct methods (`fetchToken`, `fetchSecureString`, `fetchCustomJWT`, `precheck`) return a value to the caller. With either standard mutator, they report a failure by throwing an `ApproovException`. Your own mutator decides through `handleFetchTokenResult`, `handleFetchSecureStringResult`, `handleFetchCustomJWTResult` and `handlePrecheckResult`. `fetchSecureString` returns null for `UNKNOWN_KEY`. See the [reference](REFERENCE.md).

## Sending the status in the token header

With this option, a request that goes ahead without a token carries the SDK's token fetch status name in the token header. The name is unchanged and comes after any prefix, for example `NO_NETWORK`, `POOR_NETWORK`, `MITM_DETECTED`, `NO_APPROOV_SERVICE` or `INTERNAL_ERROR`.

`setUseApproovStatusIfNoToken` is off by default. Then a request that goes ahead without a token carries the token header with an empty token after any prefix. Your backend sees that the request carries no proof that Approov checked it, but not why.

Turn it on when your backend should log the reason when it rejects such a request. A status name is never a valid token, so a backend that checks the token rejects the request as before.

```kotlin
ApproovService.setUseApproovStatusIfNoToken(true)
```

The flag also changes one `CLOSE_FAILURE` decision. With the flag on, `NO_NETWORK`, `POOR_NETWORK` and `MITM_DETECTED` let the request go ahead with the status. Without it, they fail the request with `ApproovNetworkException`. `NO_APPROOV_SERVICE` goes ahead with or without the flag. Every other failure still fails the request.

The status is only sent to a host in your account's pin set (see [Hosts in the pin set](#hosts-in-the-pin-set)). It is never sent to a host not protected by Approov. A secrets-only API added with `-noApproovToken` is in the pin set. After a failed token fetch, a request to it that goes ahead also carries the status in the token header. The status never reports a secure string fetch. For a secure string, the value left in the header or query parameter is the evidence. Each `initialize` that succeeds turns the flag off again.

At debug level, the log line for such a request ends `proceeding with the status in the token header` (see [Diagnostics](#diagnostics)), and your backend receives the status name in the token header.

## Message signing

The signer, `ApproovDefaultMessageSigning`, adds RFC 9421 message signatures to every request that carries the Approov token header.

Message signing is **off unless you install it**. If you do not need signatures, keep the default and install nothing.

Install it when your backend verifies signatures. The signer is a service mutator, so installing another mutator, passing `null` to `setServiceMutator` or a successful `initialize` removes it. The signer makes the standard decisions, the same as `CLOSE_FAILURE`. If those suit you, give it a default factory and install it right after `initialize`, as in the example. To sign under other decisions, such as `ALWAYS_PROCEED`, install your own mutator and have it call the signer (see [signing with other decisions](#signing-with-other-decisions)).

```kotlin
ApproovService.setServiceMutator(
    ApproovDefaultMessageSigning().setDefaultFactory(
        ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()))
```

```java
ApproovService.setServiceMutator(new ApproovDefaultMessageSigning().setDefaultFactory(
        ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()));
```

Once the signer is installed, every request that carries the Approov token header is signed with the install signature, `ecdsa-p256-sha256`. It uses a key unique to the app installation, held in the Android Keystore. The Keystore uses the device's secure hardware when the device has it. Its dictionary member is `install`.

You can add the account signature, `hmac-sha256`, with `setUseInstallAndAccountMessageSigning()` on the factory. It uses a shared account key, delivered to the app when it passes attestation. Its member is `account`. Both are members of the same `Signature` and `Signature-Input` headers, over the same covered components. If one signature cannot be produced, the request goes ahead with the other, or unsigned.

A request to a secrets-only API that the SDK reports as `UNPROTECTED_URL` carries no token header and is not signed. A request to a host in your pin set can go ahead after a failed token fetch. It then still carries the token header, empty or with the status. So the signer signs it too, over that value.

Each `initialize` that succeeds puts back the `DEFAULT` mutator, which removes the signer. So install it again after each `initialize`.

Your backend receives `Signature` and `Signature-Input` headers whose members include `install` (and `account`, if you chose it). A signature that cannot be produced is logged as an error under the `ApproovMsgSign` tag.

Your backend verifies the signatures as [Installation Message Signing](https://docs.approov.io/operations/message-signing/installation/) and [Account Message Signing](https://docs.approov.io/operations/message-signing/account/) describe.

### When signing runs

The signer signs in its `handleInterceptorProcessedRequest` hook. That runs after the mutator's decisions and the secure string substitutions, so the signature covers what the package sends. A mutator of your own that changes the request makes its changes first and then calls the signer, so the signature covers them too.

`Content-Type` and `Content-Length` are covered with the values OkHttp sends. OkHttp sets them from the request body after the package signs. It replaces any value your app set, and sends no `Content-Length` for a chunked body.

Your own network interceptors run after signing, before the package's network interceptors. They must not change a signed header, the URL or the body, or the signature no longer verifies.

A redirect within a protected domain, an authenticator retry and a stale protection refresh are each signed afresh.

### What the default signature covers

The default signature covers:

* the request method and target URI
* the `Approov-Token` header
* the `Approov-TraceID` header, when present
* the `Authorization`, `Content-Length` and `Content-Type` headers, when present
* a SHA-256 `Content-Digest` of the body, when the request has a body and a digest can be computed. An empty body (length 0) gets a digest. A request with no body (such as a GET), a one-shot body, a chunked body or a body that fails while it is written gets none.
* a `created` timestamp and a 15 second `expires`, both from `ApproovService.getServerAdjustedCurrentTimeMS()`, so they follow the Approov servers' clock even when the device clock is wrong. If that time cannot be read, the device clock is used and a warning is logged

Each component is covered once, as RFC 9421 requires. So with `setTokenHeader("Authorization", "Bearer ")`, the token header is the `authorization` component.

### Choosing the signatures and the covered components

A signature parameters factory decides which signatures a request gets and which components they cover.

`generateDefaultSignatureParametersFactory()` makes the install signature only, over the components in [what the default signature covers](#what-the-default-signature-covers).

Change it to add the account signature, cover more headers, require a body digest, or use different settings for one host. Give the signer a default factory with `setDefaultFactory`, and a factory for a single host with `putHostFactory`. Start from the default factory and override what you need. A bare `SignatureParametersFactory()` covers nothing, so it is of no use.

```kotlin
import io.approov.service.okhttp.ApproovDefaultMessageSigning
import io.approov.service.okhttp.ApproovService

val factory = ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()
    .setUseInstallAndAccountMessageSigning() // add the account signature; or setUseAccountMessageSigning(),
                                            // or setUseInstallMessageSigning() (the default)
    .setExpiresLifetime(60)                 // default 15s
    .addOptionalHeaders("X-Request-Id")     // covered when present

// a factory is shared by every request it applies to, so use a separate
// instance for a host that needs different settings
val paymentsFactory = ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()
    .setExpiresLifetime(5)
    .setBodyDigestConfig(ApproovDefaultMessageSigning.DIGEST_SHA256, true) // digest required, so every
                                            // request to this host must have a body that can be
                                            // digested. A GET fails with an ApproovException whose
                                            // cause is RequiredBodyDigestException

ApproovService.setServiceMutator(
    ApproovDefaultMessageSigning()
        .setDefaultFactory(factory)
        .putHostFactory("payments.example.com", paymentsFactory))
```

A host factory applies to requests to that host. The host is matched without regard to case, and without the port. The default factory applies to every other protected host. A signer with no factory for a host leaves its requests unsigned. `putHostFactory(host, null)` removes a host factory. To build custom covered components, use `io.approov.util.okhttp.sig.SignatureParameters`. Each `initialize` that succeeds removes the signer, and its factories with it.

The `Signature-Input` header your backend receives lists, for each member (`install`, `account`), the components it covers and its `created` and `expires` parameters.

### Choosing parameters per request

Every request that carries the token header, to a host with a factory, is signed with that factory's parameters. The factories cover most needs. To leave some requests unsigned, call the signer only for the others from your own mutator (see [signing with other decisions](#signing-with-other-decisions)). The signer can still be subclassed, as in 3.5.x, by overriding its protected `buildSignatureParameters` (see the [reference](REFERENCE.md#approovdefaultmessagesigning)).

### Signing with other decisions

Your own mutator makes its decisions and calls the signer from its `handleInterceptorProcessedRequest`, so requests are signed under those decisions.

On its own, the signer makes the standard `CLOSE_FAILURE` decisions. Use your own mutator when your app needs other decisions, such as `ALWAYS_PROCEED`, together with signatures.

This mutator makes the `ALWAYS_PROCEED` decisions and signs:

```kotlin
import com.criticalblue.approovsdk.Approov
import io.approov.service.okhttp.*
import okhttp3.Request

class ProceedAndSign : ApproovServiceMutator {
    private val signer = ApproovDefaultMessageSigning().setDefaultFactory(
        ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory())

    override fun handleInterceptorFetchTokenResult(result: Approov.TokenFetchResult, url: String): Boolean =
        ApproovServiceMutator.ALWAYS_PROCEED.handleInterceptorFetchTokenResult(result, url)

    override fun handleInterceptorProcessedRequest(request: Request, changes: ApproovRequestMutations): Request =
        signer.handleInterceptorProcessedRequest(request, changes)

    override fun supportsProtectionRefresh(): Boolean = true
}

ApproovService.setServiceMutator(ProceedAndSign())
```

The substitution decisions are the same in both standard mutators, so the defaults serve. Keep these in mind:

* Return `true` from `supportsProtectionRefresh()`. Otherwise a request held past the refresh period goes out with its old token and signature. A redirect or an authenticator retry goes out unsigned. Your `handleInterceptorProcessedRequest` then runs again on each of those attempts, so keep it safe to repeat.
* To change the request before it is signed, make your change and then call the signer, as in the [fuller example](#opting-in-to-aborting-requests).
* Let an exception from the signer propagate unchanged. A required body digest that cannot be generated then fails the request exactly as it does when the signer is installed on its own.

When a token fetch for one of your API domains fails, this mutator lets the request go ahead. The request reaches your backend with the empty token header (or the status). When a signature can be produced, it also carries the `Signature` and `Signature-Input` headers.

## Header names

You can change the name of the token header and the trace header, and the prefix in front of the token. By default, the token goes in `Approov-Token` with no prefix, and the trace ID in `Approov-TraceID`.

Change them when your backend expects the token in another header, such as `Authorization` with a `Bearer ` prefix, or does not want the trace header. Passing `null` turns the trace header off.

```kotlin
ApproovService.setTokenHeader("Authorization", "Bearer ")   // default "Approov-Token", no prefix
ApproovService.setTraceIDHeader(null)                       // default "Approov-TraceID"; null disables
```

Each `initialize` that succeeds puts these back to their defaults, so set them after it.

## Bypass initialization

Initialize with an empty string instead of the account ID to start the package in bypass mode. In bypass mode the package is enabled but protects nothing: `isApproovServiceEnabled()` is `true` and `isApproovProtectionEnabled()` is `false`. In this mode:

* requests through the `OkHttpClient` go out with no Approov processing. There is no token, signing, secure strings or Approov connection check, only OS trust.
* the methods that call the SDK throw `ApproovException` without calling it.

Before any `initialize`, a request is handled the same way as in bypass mode. It is neither failed nor held.

Bypass mode is a starting or fallback state. Use it, for example, while your app fetches the account ID remotely. The README example also uses it as a guard, in case the account ID does not reach the app intact.

```java
ApproovService.initialize(context, "");
```

A later `initialize` with the account ID turns Approov on at runtime, if the SDK accepts it. Like each `initialize` that succeeds, it resets the configuration to its defaults, client builders included, so make your configuration calls again after it. Clients you already got from `getOkHttpClient()` protect their requests from then on.

Once protection is on, the package ignores an `initialize` with an empty string, and resets nothing. The SDK rejects a later `initialize` with a different account ID or a different comment. Its exception reaches your code, and the package state is unchanged. See [initialize in the reference](REFERENCE.md#initialize).

In bypass mode, the `ApproovService` tag logs `initialize: bypass mode, Approov protection not active` at `INFO`.

## Initialize, then configure

Call `initialize` and handle any exception it throws. It is synchronous, so there is nothing to wait for. Then make every configuration call your app needs, such as:

* the token, trace and binding headers, and `setUseApproovStatusIfNoToken`
* substitution headers and query parameters
* exclusion regular expressions
* the service mutator, and so message signing
* the logging level
* the stale protection refresh period
* the `OkHttpClient` builders

Until your configuration calls run, requests use the defaults (see [requests before the configuration call](#requests-before-the-configuration-call)).

```kotlin
ApproovService.initialize(applicationContext, "<your-approov-account-id>")
ApproovService.setServiceMutator(ApproovServiceMutator.ALWAYS_PROCEED)
```

Configure immediately after `initialize`, before the app sends protected requests. The [README](README.md#initializing) shows where to call `initialize`.

Each `initialize` that succeeds resets these settings to their defaults:

* the token, trace and binding headers
* `setUseApproovStatusIfNoToken`
* the substitutions and the exclusions
* the service mutator
* the stale protection refresh period
* the client builders

It also drops cached clients and clears `getLastARC()`. It keeps the logging level. So a setting made before `initialize` is lost. A repeat call with the same account ID and the same comment resets too.

Two kinds of `initialize` change nothing. The first has an empty account ID after protection is on, and the package ignores it. The second is one that the SDK rejects, for example a repeat call with a different account ID or a different comment. The SDK then throws `IllegalStateException`, which reaches your code.

Initialization can also be done for you, for example by a one-line native bootstrap in a React Native app. This is supported, but it configures nothing. It calls `initialize` with the account ID and comment, and that is all. Your app makes all the configuration calls afterwards, after the last `initialize`.

When the SDK initializes, `initialize` logs `initialize: Approov protection enabled` at `INFO`. When the SDK was already initialized with the same account ID and comment, it logs `initialize: Approov SDK already initialized with the same config and comment` at `DEBUG`.

### Requests before the configuration call

The package may process a request after `initialize` but before your configuration call. That request uses the defaults in force at the time:

* the `DEFAULT` service mutator (`CLOSE_FAILURE`)
* the `Approov-Token` header with no prefix
* no binding header and no exclusions
* no substitutions, so a secure string placeholder goes out unchanged
* message signing off
* an empty token header, not the status, when no token is available
* logging at `INFO`, unless you set another level

A configuration change applies to requests processed after the call. A request already in flight keeps the mutator it started with. The package can apply its protection again before the request is sent, on a stale protection refresh, a redirect or a retry. It then reads the other settings again, such as the token header and the substitutions.

Connection checks do not depend on this order. Their keys come from the Approov SDK's configuration, not from your configuration calls (see [Connection validation](#connection-validation)).

Requests that can run before your configuration call include:

* native code at startup, for example SDKs your app starts that use the package's client, and Android headless JS tasks in a React Native app
* JavaScript modules that fetch when they are imported, in a React Native app

Configure straight after `initialize`, before the app sends protected requests. Keep startup requests to protected hosts until after that point.

## Token binding

[Token binding](https://docs.approov.io/backend-integration/token-binding/) ties the Approov token to the value of a header on the request, typically an OAuth `Authorization` header. A token then cannot be replayed with different credentials.

There is no binding header by default. Set one when your requests carry a credential header, such as an OAuth `Authorization` header, that a replayed token must not be used with. `setDataHashInToken` binds arbitrary data instead. Never use both. On every request that carries the binding header, the package replaces the data hash. That value then stays in force for later tokens.

```kotlin
ApproovService.setBindingHeader("Authorization")
```

Each request's token is bound to that request's own header value. This holds even when requests with different values are in flight at once, as during an OAuth token refresh.

The SDK holds one binding value for the whole app. So for every request that carries the binding header, the package sets the value and fetches the token as one step. It does this on the first attempt, and whenever the request's protection is reapplied: on a stale protection refresh, a redirect or an authenticator retry. Requests that carry no binding header are not held up by this.

The package can only order the calls it makes itself. Other code can change the binding value between the package setting a request's value and fetching its token. Examples are a `setDataHashInToken` call from your own code, or another Approov package in the same app that binds its own requests. That request's token is then bound to the other value, and your backend rejects it. In the same way, a `fetchToken` call from your own code can get a token bound to a request's value. Do not call `setDataHashInToken` or `fetchToken` while requests that carry the binding header are in flight. Each `initialize` that succeeds removes the binding header.

The call logs `setBindingHeader <name>` at `DEBUG` under the `ApproovService` tag, and the token your backend receives carries the `pay` claim.

## Secure strings

[Secure strings](https://docs.approov.io/backend-integration/secure-strings/) remove API keys and other secrets from your app. In your request you put a secure string key where the secret goes, and the package substitutes the secret.

By default there are no substitution headers or query parameters, so a secure string placeholder goes out unchanged. Register the headers and query parameters whose values are secure string keys.

```kotlin
ApproovService.addSubstitutionHeader("X-Api-Key", null)        // whole value is the key
ApproovService.addSubstitutionHeader("Authorization", "Bearer ") // value after the prefix is the key
ApproovService.addSubstitutionQueryParam("api_key")
```

If a value's secure string fetch fails, the value is replaced by the fetch status name and the request goes ahead. `UNKNOWN_KEY` is the exception: no secure string is defined for that value, so it is left as your app wrote it. See the [secure string table](#secure-string-results).

Header names are matched without regard to case. A header cannot be both a binding header and a substitution header. The second call throws `IllegalArgumentException`, whatever the case of the name. To fetch or define a secure string directly, use `fetchSecureString`. Each `initialize` that succeeds removes the substitutions.

Your backend receives the secret in place of the key. When it does not, two things give the reason: the value it receives, and the `Secure string not substituted` log line. The value is a status name such as `REJECTED`, or the key itself (see [When placeholders stay in place](#when-placeholders-stay-in-place)). Most of these log lines are at `DEBUG`, so set the logging level to `DEBUG` to see them (see [Diagnostics](#diagnostics)).

### Keys and values

A secure string key is at most 64 characters (see [secure strings](https://docs.approov.io/backend-integration/secure-strings/)). A longer value in a substitution header, after the prefix, or in a substitution query parameter fails the request with `ApproovException`. This applies under every mutator. So do not register a header, such as `Authorization`, that also carries real credentials longer than 64 characters.

The package uses the whole header value after the prefix as the key, exactly as it appears in the request. The prefix must match exactly, including case. A value that does not start with the prefix is sent unchanged, and nothing is logged. For a query parameter, the package uses the whole value. For a key used in a query parameter, use only letters, digits, `.`, `_`, `~` and `-`. OkHttp encodes other characters in a URL, so the value it sends is no longer the key you wrote. Characters such as `&`, `#`, `=`, `+`, space and `%` also change how the URL is read.

A value that a request cannot carry unchanged keeps its placeholder (see [When placeholders stay in place](#when-placeholders-stay-in-place)). Your app can still read it with `fetchSecureString`.

A backend that decodes the query as a form reads a `+` in a substituted value as a space. It reads a `%` followed by two hex digits as the character they encode. If your backend decodes the query, store the value URL-encoded, with `%2B` for `+` and `%25` for `%`.

### When placeholders stay in place

A request can go out without the secret in three cases: no substitution attempted, fetch failed and status substituted, and value cannot be carried. In a fourth case, your own substitution hook declines a `SUCCESS` result, and the key stays in place.

**No substitution attempted.** A secure string is only ever substituted into a request sent over TLS to an API domain you added to Approov. That domain is either token-protected (`SUCCESS`) or secrets-only (`UNPROTECTED_URL`, added with `-noApproovToken`).

These requests keep their placeholders, with no status written:

* a request to a host not protected by Approov (`UNKNOWN_URL`)
* a cleartext `http` request
* an `https` request whose URL the SDK reports as `BAD_URL`, meaning a URL it cannot parse
* a request whose token fetch failed for any other reason

This holds under every mutator, `ALWAYS_PROCEED` and custom mutators included. No secure string is fetched for such a request, and the substitution hooks are not consulted. The request goes ahead as the token decision says.

A log line names the header or query parameter, never the value. It is a warning for a cleartext or `BAD_URL` request, because that is a configuration problem. Otherwise it is a DEBUG line (see [Diagnostics](#diagnostics)).

This matters under `ALWAYS_PROCEED`, which sends a request whatever its token fetch status. That includes a redirect from your API to an `http` URL. Under `CLOSE_FAILURE`, a cleartext or `BAD_URL` request to one of your Approov API domains never gets this far. The SDK reports `BAD_URL` for it, and the request fails with `ApproovFetchStatusException`. The standard mutators send a cleartext request to a host not protected by Approov unchanged. A custom mutator can still abort it.

**Fetch failed and status substituted.** This applies to a request that does get secure strings. A secure string fetch that does not return `SUCCESS` writes its status name in place of the placeholder. The request goes ahead (see [Secure string results](#secure-string-results)).

**Value cannot be carried.** A secure string whose value the header or URL cannot carry keeps the placeholder. A warning names the header or query parameter, never the value (see [Values a header or URL cannot carry](#values-a-header-or-url-cannot-carry)).

### Secure strings and the OkHttp cache

The package does not change how an OkHttp `Cache` treats a response. A `Cache` stores a response under its request URL. So if a request has a secure string in its query, the cache on disk holds its response under a URL that contains the secret. It also stores the request headers that the response names in `Vary`, so a substituted header named there is stored with its secret.

If your client has a `Cache`, send requests that carry secure strings with `Cache-Control: no-store`. OkHttp then never stores their responses. Also, **do not let a server name a substituted header in `Vary`**.

## Excluding URLs

Requests whose URL contains a match for an exclusion regular expression are sent untouched, without a token fetch. The expression is not anchored, and the package compares it with the full URL, query included. Use `^` and `$` to match the whole URL. The default `handleInterceptorShouldProcessRequest` applies the exclusions, so a mutator that overrides it without calling the default drops them.

There are no exclusions by default. Use them for requests to your API domains that should not fetch a token, such as a health check.

```kotlin
ApproovService.addExclusionURLRegex("^https://api\\.example\\.com/health.*")
```

Connection checks still apply to excluded requests on domains added to Approov.

After initialization, the package takes new keys for those checks only when a request that is not excluded fetches a token through the client. The SDK must also report a configuration change, or ask for the keys to be applied again. A direct `fetchToken` call does not update them. So make sure your app keeps calling some URL that is not excluded on each protected domain.

Each `initialize` that succeeds resets the exclusions.

`addExclusionURLRegex` logs the expression at `DEBUG`. An invalid regular expression is logged as an error and nothing is added. No exception is thrown, so check the log when you add one.

## Connection validation

The pinning interceptor checks the TLS connection to each domain you added to Approov. Pinning means a connection is accepted only if its certificate matches keys known in advance. The interceptor checks against the certificate public keys configured for the domain. For a domain with none of its own, it falls back to the Managed Trust Roots.

It is on for every domain you added to Approov. The keys come from the Approov SDK's configuration, not from your configuration calls.

On a new install, the SDK has no keys until its first fetch from the Approov service. Until then, connections are checked by OS trust only, with no warning. The first request that is not excluded fetches the keys.

To skip it for a host, override `handlePinningShouldProcessRequest` in your own mutator (see [opting in to aborting requests](#opting-in-to-aborting-requests)).

A request whose connection fails the check fails with `SSLPeerUnverifiedException`, the same exception that OkHttp's own `CertificatePinner` throws. A certificate that OS trust rejects fails earlier, with `SSLHandshakeException`. Only that request fails. Other hosts that share an HTTP/2 connection with it are not affected.

The check uses the certificate chain that your device's trust store accepted, as OkHttp records it from version 4. It never uses a certificate the server merely appends. This is why the package needs OkHttp 4.12 or later (see [OkHttp version](#okhttp-version)).

A failure logs an error under the `ApproovPinningInterceptor` tag that names the host, never a key (see [failures in the log](#failures-in-the-log)).

### WebSockets

WebSockets are not supported. OkHttp never runs network interceptors for a WebSocket, so the pinning interceptor cannot check its connection. A request carrying `Upgrade: websocket` is therefore sent exactly as your app built it: no token fetch, no Approov headers, no secure string substitution and no signature. Nothing Approov issues is sent over a connection that has not been pinned. Do not put a secure string placeholder on a WebSocket request and expect it to be substituted.

### How hosts are matched

Keys are looked up for the exact host, without regard to case and ignoring one trailing dot. So a domain added as `API.Example.com` applies to `api.example.com`. A request to `api.example.com.` is checked like one to `api.example.com`.

Approov API domains never contain wildcards, so no key is a pattern.

The `*` entry is not a host. It holds the Managed Trust Roots, which are used for a listed domain with no keys of its own. They are never used for a domain that is not listed.

### Without Managed Trust Roots

Suppose a domain is added with no keys of its own, while the Managed Trust Roots are empty or absent. That domain is checked by OS trust only. This is a valid development setup.

It is not silent. The first connection to each such host logs a warning under `ApproovPinningInterceptor`, once per host. The warning names the host, never a key.

### Failures in the log

A connection whose certificate matches none of the domain's keys fails with `SSLPeerUnverifiedException`. An error line names the host, never a key.

A cleartext (`http`) request has no TLS connection to check, so the connection check passes it on. The token fetch decides it instead: the SDK reports `BAD_URL` for it, so under `CLOSE_FAILURE` a cleartext request to one of your Approov API domains fails with `ApproovFetchStatusException`. Under `ALWAYS_PROCEED` it is sent with an empty token header (or the status), and without secure strings. The standard mutators never block a cleartext request to a host not protected by Approov. A custom mutator can still abort it.

If the keys cannot be read from the SDK, the check fails closed. The request fails with `SSLPeerUnverifiedException` and the message `Approov pinning: pins unavailable for <host>`. Its cause is the reason the keys could not be read. One error line names the host and the cause.

If the keys cannot be read while a token fetch applies a configuration update, the request fails earlier. It fails with `ApproovException` and the message `Approov SDK getPins failed: ...`, logged under `ApproovService`.

## OkHttp version

The package depends on OkHttp 5.3.2. OkHttp 5 tries a host's IPv6 and IPv4 addresses side by side. On a network that drops IPv6 traffic, requests still connect quickly. The Approov SDK makes its own calls with your app's OkHttp, so its token fetches get the same benefit.

OkHttp 5.3.2 is a minimum. Gradle uses the highest OkHttp version that anything in your app asks for, so an app that declares a later OkHttp keeps it.

To keep your app on OkHttp 4, see [staying on OkHttp 4](#staying-on-okhttp-4).

Gradle's `dependencies` task for your app module shows the OkHttp version it resolved.

### What OkHttp 5 asks of your build

* Kotlin: OkHttp 5.3.2 brings version 2.2 of the Kotlin standard library. If your app compiles Kotlin, it needs Kotlin 2.1 or later. Java-only apps are unaffected.
* Other OkHttp libraries: keep every OkHttp artifact on the same version. An artifact built for OkHttp 4, such as `logging-interceptor` 4.12.0, can call code that OkHttp 5 no longer has, and then fails at run time. The OkHttp BOM keeps them in line:

```kotlin
implementation(platform("com.squareup.okhttp3:okhttp-bom:5.3.2"))
implementation("com.squareup.okhttp3:logging-interceptor")
```

* Manifest: OkHttp 5 adds an `androidx.startup` provider to your merged manifest.

### Staying on OkHttp 4

The package also works on OkHttp 4.12 and later, and is tested on 4.12.0. To keep your app on OkHttp 4, pin every OkHttp artifact with the BOM:

```kotlin
implementation(enforcedPlatform("com.squareup.okhttp3:okhttp-bom:4.12.0"))
```

Your app then does not get the side-by-side IPv6 and IPv4 connections. React Native ships its own OkHttp 4. Test a React Native app on OkHttp 5 before you ship it, or pin it to 4.12.0 this way.

## Service mutators

A service mutator is the object that holds the package's decisions, such as whether a failed token fetch fails the request.
An `ApproovServiceMutator` holds your app's own policy, so you don't need to fork the package. The package asks the installed mutator at each decision point. Every method has a default, so override only what you need.

The default mutator is `DEFAULT`, which is `CLOSE_FAILURE` (see [What happens by default](#what-happens-by-default)).

Install a standard mutator to change the token fetch decisions, or your own when your app needs a decision the standard ones do not make. A custom mutator that should never abort a request delegates those decisions to `ApproovServiceMutator.ALWAYS_PROCEED`.

```kotlin
ApproovService.setServiceMutator(ApproovServiceMutator.ALWAYS_PROCEED)
```

The package's signer, `ApproovDefaultMessageSigning`, is itself a mutator. Installing another mutator removes it, so a mutator of your own that should also sign calls the signer from its `handleInterceptorProcessedRequest` (see [signing with other decisions](#signing-with-other-decisions)). Each `initialize` that succeeds puts back `DEFAULT`. A mutator you install applies to requests processed after the call.

The hooks:

| Hook | Decides |
| :--- | :--- |
| `handleInterceptorShouldProcessRequest` | whether a request gets Approov processing at all (by default, every request that is not excluded) |
| `handleInterceptorFetchTokenResult` | given the token fetch result, whether to send the request with its Approov headers, send it without them, or abort it (see below) |
| `handleInterceptorHeaderSubstitutionResult`, `handleInterceptorQueryParamSubstitutionResult` | whether to apply a secure string substitution (see below) |
| `handleInterceptorProcessedRequest` | final changes to the processed request. The signer signs here, so a mutator that calls it makes its own changes first |
| `supportsProtectionRefresh` | whether the processed request callback may run again on a stale request, a redirect or a retry with changed headers (see [Stale protection refresh](#stale-protection-refresh)) |
| `handlePinningShouldProcessRequest` | whether Approov connection checks (Managed Trust Roots or configured public keys) apply to a request |
| `handlePrecheckResult`, `handleFetchTokenResult`, `handleFetchSecureStringResult`, `handleFetchCustomJWTResult` | how the direct methods turn a result into an exception |

### The token fetch decision

`handleInterceptorFetchTokenResult` receives the token fetch result and gives one of three answers:

* `true` sends the request with the Approov headers its status allows. On `SUCCESS` those are the token and trace headers. On a failure status, the token header with an empty token or the status. On `UNKNOWN_URL` or `UNPROTECTED_URL`, none.
* `false` sends the request with no Approov headers at all.
* throwing an `ApproovException` aborts the request.

By default it makes the `CLOSE_FAILURE` decision (see [What happens by default](#what-happens-by-default)).

To abort on an outcome that would otherwise go ahead, see [opting in to aborting requests](#opting-in-to-aborting-requests).

For a failure status on a host not in the pin set, the package still asks the hook. It ignores the answer, and the request goes out untouched. The only exception is when your own code throws, which aborts the request. There, the package also ignores a `CLOSE_FAILURE` decision that you inherit or call.

The hook governs the Approov headers only. Secure strings follow the token fetch status. A `SUCCESS` or `UNPROTECTED_URL` request gets them whatever the hook returns, and a request with a failure status never does.

With message signing installed, any request that gets the token header is signed.

The processed request callback, `handleInterceptorProcessedRequest`, runs for every request the package processes. That is a `SUCCESS` or `UNPROTECTED_URL` request, or a request to a host in the pin set with a failure status for which the hook returns `true`.

At `DEBUG`, a request that goes ahead on a failure status logs a line that starts `Approov token fetch for <host>: <status>`. The line ends `proceeding with an empty token header`, `proceeding with the status in the token header` or `proceeding with no Approov header`. For a host not in the pin set, it ends `not an Approov API domain, proceeding untouched`.

### The substitution hooks

`handleInterceptorHeaderSubstitutionResult` and `handleInterceptorQueryParamSubstitutionResult` decide whether to apply a secure string substitution. They are asked only on a `SUCCESS` or `UNPROTECTED_URL` request over `https`.

Both standard mutators return `true` only for `SUCCESS`, and never throw.

The answers work like this:

* Returning `false` on `SUCCESS` leaves the placeholder.
* On any other status there is no secure string to substitute. The request goes ahead with the status in place of the placeholder, whether the hook returns `false` or `true`.
* Your own mutator may throw an `ApproovException` to abort the request.

A placeholder the hook leaves in place is logged at `DEBUG` as `Secure string not substituted in header <name>` or `in query parameter <name>`, never with its value.

### Opting in to aborting requests

Your own mutator fails a request for an outcome that would otherwise go ahead, by overriding the hook and throwing an `ApproovException`. That is your app's policy, not the package's. Every `handleInterceptor...` hook declares `ApproovException`. `handlePinningShouldProcessRequest` declares no checked exception, so it aborts by throwing a `RuntimeException`, with the same result. The request then fails with a plain `ApproovException` that names the hook, with yours as its cause. Your app's error handling finds your exception with `getCause()`. The Approov status is still available from the result for your own logging.

Under `ALWAYS_PROCEED`, every outcome goes ahead. Your app may decide that some outcome should not.

The common case is `NO_NETWORK`. The device could not reach Approov, and the API call is about to fail on the same network anyway. Your app may prefer a network error at once, so that it can show its offline state. Otherwise it sends a request that will not reach your backend.

Delegate to `ALWAYS_PROCEED` for every decision you don't change. This mutator also signs, so it calls the signer:

```kotlin
import android.util.Log
import com.criticalblue.approovsdk.Approov
import io.approov.service.okhttp.*
import okhttp3.Request

class AppPolicy : ApproovServiceMutator {
    private val base = ApproovServiceMutator.ALWAYS_PROCEED
    private val signer = ApproovDefaultMessageSigning().setDefaultFactory(
        ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory())

    // opt out of proceeding for one outcome: with no network the request will not
    // reach the backend, so fail it now instead of sending it. Every other outcome proceeds.
    override fun handleInterceptorFetchTokenResult(result: Approov.TokenFetchResult, url: String): Boolean {
        if (result.status == Approov.TokenFetchStatus.NO_NETWORK) {
            Log.w("AppPolicy", "no network for Approov attestation, not sending the request")
            throw ApproovException("no network for Approov attestation")
        }
        return base.handleInterceptorFetchTokenResult(result, url)
    }

    // add app metadata after Approov processing, then sign, so the signature covers it; this only
    // replaces a header, so it stays safe to run again on a refresh
    override fun handleInterceptorProcessedRequest(request: Request, changes: ApproovRequestMutations): Request =
        signer.handleInterceptorProcessedRequest(
            request.newBuilder().header("X-Client-Platform", "android").build(), changes)

    override fun supportsProtectionRefresh(): Boolean = true

    // skip Approov connection validation for a telemetry host
    override fun handlePinningShouldProcessRequest(request: Request): Boolean =
        request.url.host != "metrics.example.com"
}

ApproovService.setServiceMutator(AppPolicy())
```

The package treats a hook that throws a `RuntimeException` as a failed request too, never as a crash. That includes a `NullPointerException` or other programming error, and a deliberate `RuntimeException`. The request fails with an `ApproovException` whose cause is the original exception. The hook is named in its message and in an error-level log line. `execute()` throws it, and an enqueued call receives it in `onFailure`. Otherwise OkHttp would rethrow the exception on its dispatcher thread for an enqueued call, which would terminate your app.

The package converts only an `ApproovException` or a `RuntimeException`. Kotlin has no checked exceptions, so a Kotlin hook can also throw a checked exception, such as `Exception` or `org.json.JSONException`. The package does not catch it, so on an enqueued call it can terminate your app. Throw only an `ApproovException` or a `RuntimeException` from a hook.

This applies to any `ApproovException` or subclass your hook throws, whether your code built it or let it escape from a direct method such as `fetchToken` or `fetchSecureString`. It is your hook's failure, not an Approov decision, so it never looks like one.

The aborts that `CLOSE_FAILURE` makes keep the 3.5.x Approov exception types. That also holds in a custom mutator that inherits those decisions or calls them, for example `ApproovServiceMutator.CLOSE_FAILURE.handleInterceptorFetchTokenResult(results, url)`. A decision your hook catches and rethrows unchanged keeps its type too. The standard substitution decisions, `closeFailureSubstitution` included, never throw.

Keep hooks fast and free of side effects, because they run on the request path.

The stale protection refresh may run a mutator's processed request callback again, but only if `supportsProtectionRefresh()` returns `true`. It does for `DEFAULT`, `CLOSE_FAILURE`, `ALWAYS_PROCEED` and `ApproovDefaultMessageSigning`. A custom mutator that does not opt in gets no stale refresh at all. On each redirect and each retry with changed headers, the package removes the headers its callback added and does not call it again. So such an attempt goes out unsigned.

## Stale protection refresh

A request can be held between Approov processing and sending, for example during a device doze period or in an app-level queue. It would then go out with an expired token or signature.

To prevent this, a network interceptor acts just before sending. If the request was held longer than the refresh period, the interceptor strips its protection and applies it again. On a refresh:

* the token is fetched again, from the SDK cache if it is still valid
* the token header reflects the new result
* substitutions are redone
* with message signing installed, the signatures are made again

The refresh is on by default, with a period of 3000 ms.

Set another period, or turn the refresh off with a period of 0 or less.

```kotlin
ApproovService.setStaleProtectionRefreshPeriod(1000)   // <= 0 disables
```

The refresh runs on every network attempt, so OkHttp's own retries are refreshed too. It runs a mutator's processed request callback again only if `supportsProtectionRefresh()` returns `true`. Each `initialize` that succeeds puts the period back to 3000 ms.

At `DEBUG`, the `ApproovFreshness` tag logs `refreshProtection: held <n>ms, refreshing before transmission`.

### Redirects and retries

A redirect is classified again for its new URL:

* a redirect to a host not protected by Approov leaves with no Approov headers and no secrets
* a redirect to a secrets-only API carries its secure strings and nothing else
* a redirect within a token-protected domain is signed again over the new target, if the mutator's `supportsProtectionRefresh()` returns `true`. Otherwise it goes out unsigned.

Each attempt is classified by its own token fetch:

* a refresh that reports `UNPROTECTED_URL` drops the token and trace headers and the signatures. It substitutes the secure strings afresh from your placeholders.
* one that reports `UNKNOWN_URL` drops everything and restores the placeholders.
* one that reports a failure the mutator lets go ahead carries the token header with an empty token or the status, and no secure strings.

Any later attempt whose URL, method or headers differ from the first attempt is protected again the same way. The typical case is an OkHttp `Authenticator` retrying a `401` with a new `Authorization` header. A header that an interceptor changes before the first attempt is not signed again. Set signed headers in the request you build, or in an application interceptor on the builder you pass to `setOkHttpClientBuilder`.

A header your app changed between attempts is kept and substituted afresh. Only headers still holding the value the package installed are restored to their placeholders. That value is either a secure string or a status written in place of the placeholder. The URL goes back to your placeholders before the secure strings are fetched again.

### Redirects to another origin

A redirect to another origin (a different scheme, host or port) is first stripped of everything the package added:

* the token and trace headers
* the signatures
* each substituted header value, a secure string or a status, which goes back to your placeholder

OkHttp itself drops `Authorization`.

The redirect is then handled from the start, as a new request for the target URL:

* a token is fetched for the target URL. It is never reused from the first host.
* secure strings are substituted only if the target is a token-protected or secrets-only `https` API.
* the signatures are computed only for a token-protected target.

A target not protected by Approov goes out with none of it.

The redirect target URL is left exactly as the server sent it. That applies to the request that follows it, the redirect response's `Location` header, and `response.request().url()`.

If your backend echoes a secure string into a redirect target, for example in a query parameter, the package does not restore your placeholder. The next hop receives what the server sent. **Do not let a server echo a secret into a redirect.** What it echoes is the server's responsibility.

The query of a redirect target is never looked up either. A parameter name may be one you added with `addSubstitutionQueryParam`, but its value is the server's. So no secure string is fetched for it, and no status is written into it.

On a protected target, only your own placeholders are substituted, the ones in your request headers. A query parameter placeholder is substituted again only on an attempt to the URL your request was protected for. Examples are an authenticator retry, or a `303` back to the same URL.

### Limits

Three limits apply, by design:

* Only requests that were given protection are classified again on a redirect. A request to a domain not in Approov that redirects into a protected domain arrives there without protection, and your backend rejects it. Add every domain your app calls to Approov, including legacy or alias domains that redirect.
* The package restores what it added, not what it replaced. Suppose your app sets its own `Signature` or `Signature-Input` headers on a request that Approov also signs. On a redirect, the value the package wrote is treated as your app's, and travels with it. Do not set those headers yourself on Approov-protected requests.
* The package expects a request to keep its scheme, host and port after the package protects it. An interceptor you add to a client you got from `getOkHttpClient`, for example with `newBuilder().addInterceptor`, runs after the package. If it changes the scheme, host or port, the package treats the new URL like a redirect target and leaves its query as it is. So a secure string substituted into the query goes to the new host. **Do not change the host of a request after the package processes it.** Change it in the request you build, or in an interceptor on the builder you pass to `setOkHttpClientBuilder`.

The package's network interceptors run last in the client, after any your app adds through `setOkHttpClientBuilder`, just before the request is sent. So a network interceptor of yours, such as a logger or an inspector, sees each attempt before the package strips or refreshes its protection. On a redirect to another host it sees the token and the substituted secrets of the original host before the package removes them. Do not log those values there. A request held inside one of your own network interceptors is refreshed before it is sent.

A request keeps the mutator that was installed when the package first processed it. Its refresh, its redirects, its retries and the connection validation hook for each of its attempts all use that mutator. This is true even if you install another mutator while the request is in flight. A mutator you install applies to requests processed after the call.

## Multiple service layers

You can use more than one Approov package in the same app. The Approov SDK is initialized once. A later `initialize` with the same account ID and the same comment does not initialize the SDK again, but it still resets this package's configuration. A later `initialize` with a different account ID or a different comment fails with an `IllegalStateException`.

## Diagnostics

The package writes a diagnostic line for each token fetch, and for each secure string it does not deliver.

These lines are written at debug level, which is off by default (see [log tags and levels](#log-tags-and-levels)).

To write them, set the level in a debug build:

```Java
ApproovService.setLoggingLevel(ApproovLogLevel.DEBUG);
```

Or enable debug logging for the `ApproovService` tag, with no rebuild. This works on any installed build, including a release one. It raises any level except `OFF` to `DEBUG`, for all of the package's tags. It lasts until you set it back or the device reboots:

```sh
adb shell setprop log.tag.ApproovService DEBUG
```

The package never logs a token. For each request it processes, it logs the [loggable form](https://docs.approov.io/core-concepts/approov-tokens/architecture-and-security/#loggable-tokens) of the result. Its `arc` claim, the [Attestation Response Code](https://docs.approov.io/app-integration/frontend/attestation/#attestation-response-code), tells why attestation gave that result. The direct methods, such as `fetchToken`, log only the status. For example:

```
D/ApproovTokenInterceptor: Token for https://api.example.com/v1/items: {"did":"...","exp":1757400000,"arc":"IXPSB7TRK26LXE3M", ...,"sip":"a1b2c3"}
```

The URL in the line is the request URL without its user info, query or fragment. So no query value, such as a substituted secure string or personal data, is logged.

To find the reasons behind an `arc`, see [decoding an Attestation Response Code](https://docs.approov.io/app-integration/frontend/attestation/#decoding-an-attestation-response-code). Your account's [metrics](https://docs.approov.io/operations/metrics-graphs/grafana-overview/) show the same results in aggregate. A [development signing certificate](https://docs.approov.io/operations/app-signing-certificates/development-and-testing/#development-app-signing-certificates) lets debug builds and emulators pass attestation.

### Undelivered secrets and failing requests

In these cases the package writes a line at debug level only, and nothing at `INFO` or above:

* It does not deliver a secure string because its fetch returned a status other than `SUCCESS`. The status is sent in place of the placeholder.
* It does not deliver a secure string because the request's token fetch failed, or its host is not protected by Approov. The placeholder is left.
* It lets a request go ahead on a token fetch failure. The request carries the token header with an empty token or the status, or no Approov header because your mutator said so. For a host not in the pin set it goes out untouched, and the package ignores an abort by a standard decision.

The line names the header or query parameter, never its value. It gives the fetch status, with the ARC and the rejection reasons when the SDK supplies them. For example:

```
D/ApproovTokenInterceptor: Approov token fetch for api.example.com: NO_NETWORK, proceeding with an empty token header
D/ApproovTokenInterceptor: Secure string not substituted in header Api-Key: token fetch status NO_NETWORK, placeholder left
D/ApproovTokenInterceptor: Secure string not substituted in query parameter api_key: secure string fetch status NO_NETWORK, status sent in its place
```

Three cases are configuration problems, so they stay warnings, visible at the default level:

* a placeholder left because the request is not `https`
* a placeholder left because the SDK reports the URL as `BAD_URL`
* a secure string value that a header or URL cannot carry

### Log tags and levels

The package writes its log output under the tags `ApproovService`, `ApproovTokenInterceptor`, `ApproovFreshness`, `ApproovPinningInterceptor` and `ApproovMsgSign`. [setLoggingLevel](REFERENCE.md#setlogginglevel) controls what is written:

* `ApproovLogLevel.ERROR` writes errors only
* `WARNING` adds warnings
* `INFO`, the default, adds information lines such as the initialization result
* `DEBUG` adds the debug lines
* `OFF` writes nothing, errors included

The default level is `INFO`. The loggable token carries the device ID and the client IP claims, and `getDeviceID` logs the device ID. So both are debug lines, and at the default level a release build writes neither.

Raise it to `DEBUG` while you investigate, as in [Diagnostics](#diagnostics), or lower it to write less.

```Java
ApproovService.setLoggingLevel(ApproovLogLevel.WARNING);
```

`initialize` keeps the logging level. The `Approov-TraceID` header is a debug header. Pass it through unchanged.

The package never writes a token, secure string value, signature, query or substituted URL in its own log lines. It logs the message of an exception that your hook throws, so keep secrets out of those messages.
