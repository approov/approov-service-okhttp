# Advanced Options

The `approov-service-okhttp` package works through the `OkHttpClient` that you get from `ApproovService`. After you call `initialize` with your account ID, the package adds an Approov token to each request to your API domains. The token is a signed JWT that provides proof of attestation, which your backend can check.

A standard integration of the Approov Package for OkHttp needs nothing from this document. You add the dependency, initialize, and use the client, as the [README](README.md) shows. The options below change the defaults, suitable for applications with specific needs. You can find every method used in this document in [REFERENCE.md](REFERENCE.md).

| What you need | Read |
| :--- | :--- |
| Understand what reaches your backend | [What each request carries](#what-each-request-carries), then [What happens by default](#what-happens-by-default) |
| Change how network failures are handled | [What happens by default](#what-happens-by-default), then [Service mutators](#service-mutators) |
| Protect request contents with signatures | [Message signing](#message-signing), then [Account Message Signing](https://docs.approov.io/operations/message-signing/account/) |
| Replace an embedded API secret | [Secure strings](#secure-strings) |
| Configure startup or runtime activation | [Initialize, then configure](#initialize-then-configure), then [Bypass initialization](#bypass-initialization) |
| Investigate an unexpected result | [Diagnostics](#diagnostics), then [What happens by default](#what-happens-by-default) |

## What each request carries

What the package adds to a request depends on two things:

* the kind of host: a token-protected API domain, a secrets-only API domain, or a host not protected by Approov (one you have not added)
* the outcome of the attestation attempt for the request

Based on the attestation outcome and the kind of host, the package and its [service mutator](#service-mutators) decide what to add to the request. They also decide whether the request goes ahead at all.

| Request goes to | Outcome | What the package adds |
| :--- | :--- | :--- |
| a token-protected domain: an API domain you added to Approov | `SUCCESS` | the token header, and the trace header when the SDK gives a trace ID. Your secure strings, or the [status of each one that could not be fetched](#secure-strings). The signatures, once you install [message signing](#message-signing). A checked connection. |
| a secrets-only domain: an API domain you added with `approov api -add <domain> -noApproovToken` | `UNPROTECTED_URL` | your secure strings, or the status of each one that could not be fetched. A checked connection. No token or trace header, and no signatures. |
| a host not protected by Approov | `UNKNOWN_URL` | nothing. The connection relies on the device's trust store. |
| a token-fetch failure, to a host protected by Approov | a failure status | depends on the service mutator (see below) |
| a token-fetch failure, to a host not protected by Approov | a failure status | nothing (see below) |

A failure status means the app could not complete the attestation. For example, there was no network, or the Approov service could not be reached. The [service mutator](#what-happens-by-default) then decides whether the request fails in the app or is sent. A request that is sent carries the `Approov-Token` header with an empty token after any prefix. If you turned on [setUseApproovStatusIfNoToken](REFERENCE.md#setuseapproovstatusifnotoken), it carries the status name instead. It has no trace header and no secure strings.

For a host not protected by Approov, the request goes out unchanged, whatever the outcome. The standard mutators never fail it in the app. Only your own mutator can, by throwing from `handleInterceptorFetchTokenResult` (see [Service mutators](#service-mutators)).

The headers your backend may receive:

* `Approov-Token` carries the Approov token, or is empty (or holds the [status](REFERENCE.md#setuseapproovstatusifnotoken)) when no token could be fetched. Only your backend can tell from the token whether the app passed. See [Approov tokens](https://docs.approov.io/core-concepts/approov-tokens/architecture-and-security/).
* `Signature` and `Signature-Input` carry the signatures, once you install [message signing](#message-signing).
* `Approov-TraceID` is an optional debug header. Your backend passes it through unchanged.

Whatever the attestation outcome, the package checks the TLS connection to each domain you added, token-protected or secrets-only (see [Connection validation](#connection-validation)).

## What happens by default

For every request it processes, the `ApproovService` interceptor fetches an Approov token for the request URL. It does not process a request before `initialize` or in [bypass mode](#bypass-initialization), a WebSocket upgrade, or a request to an [excluded URL](#excluding-urls). It then applies the decisions of the installed [service mutator](#service-mutators). The package provides three standard ones:

* `ApproovServiceMutator.CLOSE_FAILURE` keeps the 3.5.x token decisions for your API domains. A failed token fetch fails the request in the app, with the same `ApproovException` types as 3.5.x.
* `ApproovServiceMutator.ALWAYS_PROCEED` never fails a request because of its token fetch. Whatever the token fetch outcome, the request is sent, and your backend decides. Your backend is then the enforcement point.
* `ApproovServiceMutator.DEFAULT` is the mutator installed out of the box. `setServiceMutator(null)` puts it back, and so does each `initialize` that succeeds. `DEFAULT` points to whatever the release considers default behaviour, which is currently `CLOSE_FAILURE`.

To opt in to always proceeding:

```kotlin
ApproovService.setServiceMutator(ApproovServiceMutator.ALWAYS_PROCEED)
```

In the table below, "an Approov protected host" means one of the API domains you added to Approov, of either kind.

| Approov token fetch status | `CLOSE_FAILURE` (default) | `ALWAYS_PROCEED` |
| :--- | :--- | :--- |
| `SUCCESS` | proceeds with the token, any trace header, the secure strings and, with message signing installed, the signatures | same |
| `UNPROTECTED_URL` (secrets-only API) | proceeds with the secure strings only, no Approov headers | same |
| `UNKNOWN_URL` (host not protected by Approov) | proceeds untouched | same |
| `NO_APPROOV_SERVICE`, Approov protected host | proceeds with an empty token header (or the status), but no trace header and no secure strings | same |
| `NO_NETWORK`, `POOR_NETWORK`, `MITM_DETECTED`, Approov protected host | fails with `ApproovNetworkException`, which can be retried. This is a deprecated subclass of `ApproovFetchStatusException`, so catch `ApproovFetchStatusException` and read `getTokenFetchStatus()`. With `setUseApproovStatusIfNoToken(true)` it proceeds with the status in the token header instead | proceeds with an empty token header (or the status), but no trace header and no secure strings |
| any other status (`INTERNAL_ERROR`, `BAD_URL`, ...), Approov protected host | fails with `ApproovFetchStatusException` | proceeds with an empty token header (or the status), but no trace header and no secure strings |
| any failure status, host not protected by Approov, or any host before the app has its first Approov configuration | proceeds untouched, and no standard mutator fails it | same |

"The status" in this table means the status name in the token header, after any prefix, when you turn on [setUseApproovStatusIfNoToken](REFERENCE.md#setuseapproovstatusifnotoken). A request that goes ahead after a failed token fetch is signed too, if you installed [message signing](#message-signing).

`NO_APPROOV_SERVICE` means the app could not reach the Approov service or its failover. Requests to your API domains then go out with an empty token header (or the status) under either standard mutator.

These are the token fetch decisions only. Under either mutator, these still fail the request:

* a TLS connection that fails the check
* an abort that your own hooks ask for

## Message signing

The signer, `ApproovDefaultMessageSigning`, adds RFC 9421 message signatures to every request that carries the Approov token header.

Message signing is **off unless you install it**. If you do not need signatures, keep the default and install nothing.

The signer is implemented as a service mutator, so installing another mutator, passing `null` to `setServiceMutator` or a successful `initialize` removes it. The signer makes the standard decisions, the same as `CLOSE_FAILURE`. If those suit you, give it a default factory and install it right after `initialize`, as in the example. To sign under other decisions, such as `ALWAYS_PROCEED`, combine them with the signer in a mutator of your own, as shown below.

```kotlin
ApproovService.setServiceMutator(
    ApproovDefaultMessageSigning().setDefaultFactory(
        ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()))
```

```java
ApproovService.setServiceMutator(new ApproovDefaultMessageSigning().setDefaultFactory(
        ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()));
```

Once the signer is installed, every request that carries the Approov token header is signed with the install signature, `ecdsa-p256-sha256`. It uses a key unique to the app installation, held in the Android Keystore. The Keystore keeps the private key in the device's secure hardware when the device has it. Its dictionary member is `install`.

You can add the account signature, `hmac-sha256`, with `setUseInstallAndAccountMessageSigning()` on the factory. It uses a shared account key, delivered to the app when it passes attestation. Its member is `account`. Both are members of the same `Signature` and `Signature-Input` headers, over the same covered components. If one signature cannot be produced, the request goes ahead with the other, or unsigned.

A request to a secrets-only API that the SDK reports as `UNPROTECTED_URL` carries no token header and is not signed. A request to an Approov protected host can go ahead after a failed token fetch. It then still carries the token header, empty or with the status. So the signer signs it too, over that value.

Each `initialize` that succeeds puts back the `DEFAULT` mutator, which removes the signer. So install it again after each `initialize`.

To sign under other decisions, combine them with the signer in a mutator of your own. Your mutator makes its decisions and calls the signer from its `handleInterceptorProcessedRequest`. This mutator makes the `ALWAYS_PROCEED` token decisions and signs:

```kotlin
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

<details>
<summary>Java</summary>

```java
class ProceedAndSign implements ApproovServiceMutator {
    private final ApproovDefaultMessageSigning signer = new ApproovDefaultMessageSigning().setDefaultFactory(
            ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory());

    @Override
    public boolean handleInterceptorFetchTokenResult(Approov.TokenFetchResult result, String url)
            throws ApproovException {
        return ApproovServiceMutator.ALWAYS_PROCEED.handleInterceptorFetchTokenResult(result, url);
    }

    @Override
    public Request handleInterceptorProcessedRequest(Request request, ApproovRequestMutations changes)
            throws ApproovException {
        return signer.handleInterceptorProcessedRequest(request, changes);
    }

    @Override
    public boolean supportsProtectionRefresh() {
        return true;
    }
}

ApproovService.setServiceMutator(new ProceedAndSign());
```
</details>

When you write such a mutator:

* Return `true` from `supportsProtectionRefresh()`. Otherwise a request held past the refresh period goes out with its old token and signature. A redirect or an authenticator retry goes out unsigned. Your `handleInterceptorProcessedRequest` then runs again on each of those attempts, so keep it safe to repeat.
* To change the request before it is signed, make your change first and then call the signer.
* Let an exception from the signer propagate unchanged. A required body digest that cannot be generated then fails the request, as it does when the signer is installed on its own.

Your backend receives `Signature` and `Signature-Input` headers whose members include `install`, `account` or both. A signature that cannot be produced is logged as an error under the `ApproovMsgSign` tag.

Your backend verifies the signatures as [Installation Message Signing](https://docs.approov.io/operations/message-signing/installation/) and [Account Message Signing](https://docs.approov.io/operations/message-signing/account/) describe.

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

Until your configuration calls run, requests use the defaults.

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

If a value's secure string fetch fails, the value is replaced by the fetch status name and the request goes ahead. `UNKNOWN_KEY` is the exception: no secure string is defined for that value, so it is left as your app wrote it.

Header names are matched without regard to case. A header cannot be both a binding header and a substitution header. The second call throws `IllegalArgumentException`, whatever the case of the name. To fetch or define a secure string directly, use `fetchSecureString`. Each `initialize` that succeeds removes the substitutions.

Your backend receives the secret in place of the key. When it does not, two things give the reason: the value it receives, and the `Secure string not substituted` log line. The value is a status name such as `REJECTED`, or the key itself. Most of these log lines are at `DEBUG`, so set the logging level to `DEBUG` to see them (see [Diagnostics](#diagnostics)).

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

To skip it for a host, override `handlePinningShouldProcessRequest` in your own mutator (see [Service mutators](#service-mutators)).

A request whose connection fails the check fails with `SSLPeerUnverifiedException`, the same exception that OkHttp's own `CertificatePinner` throws. A certificate that OS trust rejects fails earlier, with `SSLHandshakeException`. Only that request fails. Other hosts that share an HTTP/2 connection with it are not affected.

The check uses the certificate chain that your device's trust store accepted, as OkHttp records it from version 4. It never uses a certificate the server merely appends. This is why the package needs OkHttp 4.12 or later (see [OkHttp version](#okhttp-version)).

A failure logs an error under the `ApproovPinningInterceptor` tag that names the host, never a key.

## OkHttp version

The package depends on OkHttp 5.3.2. OkHttp 5 tries a host's IPv6 and IPv4 addresses side by side. On a network that drops IPv6 traffic, requests still connect quickly. The Approov SDK makes its own calls with your app's OkHttp, so its token fetches get the same benefit.

OkHttp 5.3.2 is a minimum. Gradle uses the highest OkHttp version that anything in your app asks for, so an app that declares a later OkHttp keeps it.

The package also works on OkHttp 4.12 and later, and is tested on 4.12.0. To keep your app on OkHttp 4, pin every OkHttp artifact with the BOM:

```kotlin
implementation(enforcedPlatform("com.squareup.okhttp3:okhttp-bom:4.12.0"))
```

Your app then does not get the side-by-side IPv6 and IPv4 connections. React Native ships its own OkHttp 4. Test a React Native app on OkHttp 5 before you ship it, or pin it to 4.12.0 this way.

Gradle's `dependencies` task for your app module shows the OkHttp version it resolved.

## Service mutators

A service mutator, an `ApproovServiceMutator`, is the object that holds the package's decisions, such as whether a failed token fetch fails the request. It has one method for each decision, and the package asks the installed mutator at each decision point. The standard mutators and their decisions are in [What happens by default](#what-happens-by-default).

Write your own mutator when your app needs a decision that the standard ones do not make. You do not need to fork the package. Every method has a default, so override only what you need. A mutator of your own that should never abort a request delegates the token decisions to `ApproovServiceMutator.ALWAYS_PROCEED`. A mutator you install applies to requests processed after the call.

The package's signer is also a mutator. To sign under decisions of your own, see [Message signing](#message-signing).

The hooks:

| Hook | Decides |
| :--- | :--- |
| `handleInterceptorShouldProcessRequest` | whether a request gets Approov processing at all (by default, every request that is not excluded) |
| `handleInterceptorFetchTokenResult` | given the token fetch result, whether to send the request with its Approov headers, send it without them, or abort it |
| `handleInterceptorHeaderSubstitutionResult`, `handleInterceptorQueryParamSubstitutionResult` | whether to apply a secure string substitution |
| `handleInterceptorProcessedRequest` | final changes to the processed request. The signer signs here, so a mutator that calls it makes its own changes first |
| `supportsProtectionRefresh` | whether the processed request callback may run again on a stale request, a redirect or a retry with changed headers (see [setStaleProtectionRefreshPeriod](REFERENCE.md#setstaleprotectionrefreshperiod)) |
| `handlePinningShouldProcessRequest` | whether Approov connection checks (Managed Trust Roots or configured public keys) apply to a request |
| `handlePrecheckResult`, `handleFetchTokenResult`, `handleFetchSecureStringResult`, `handleFetchCustomJWTResult` | how the direct methods turn a result into an exception |

## Diagnostics

The package writes a diagnostic line for each token fetch, and for each secure string it does not deliver.

These lines are written at debug level, which is off by default (see [setLoggingLevel](REFERENCE.md#setlogginglevel)).

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
