# Advanced Options

A standard integration of the Approov Package for OkHttp needs nothing in this document: add the dependency, initialize, use the client (see the [README](README.md)). The options below change the defaults, and are for apps with specific requirements. Every method is documented in [REFERENCE.md](REFERENCE.md).

## What each request carries

The token fetch status the SDK reports for the request URL decides what the request carries:

| Request goes to | SDK status | What the client adds |
| :--- | :--- | :--- |
| an API domain you added to Approov (token-protected) | `SUCCESS` | the token, status and trace headers, your secure strings (or the [status of each that could not be fetched](#secure-strings)), the signatures once [message signing](#message-signing) is enabled, and a validated connection |
| an API domain you added with `approov api -add <domain> -noApproovToken` (secrets-only) | `UNPROTECTED_URL` | your secure strings (or the status of each that could not be fetched) and a validated connection; no token, status or trace header and no signatures |
| a host you have not added to Approov | `UNKNOWN_URL` | nothing; the connection relies on the device's trust store |
| an API domain you added (either kind), when the SDK could not attest the app at that moment (no network, the device or app was rejected, the Approov service was unreachable) | a failure status | the [service mutator](#what-happens-by-default) decides whether the request fails in the app or is sent; a request that is sent carries the `Approov-Status` header with the reason, and nothing else: no token header, no trace header, no secure string, no signatures |
| any other host, with a failure status | a failure status | nothing: it goes out unchanged and the standard mutators never fail it in the app; only your own mutator can, by throwing from `handleInterceptorFetchTokenResult` (see [Service mutators](#service-mutators)) |

The headers:

| Header | Value | What your backend does with it |
| :--- | :--- | :--- |
| `Approov-Token` | the Approov token, a short lived signed JWT that is the proof of attestation for this request; **absent** if no token could be obtained (3.8.0 no longer sends it empty) | verifies the token's signature and expiry and rejects a request whose token is missing or invalid; the token also carries the app installation's public key, which verifies the `install` message signature below |
| `Approov-Status` | the outcome of the token fetch for this request, in lowercase: `success`, `no_network`, `rejected`, ... | says why this particular request carries no attestation proof, so you can log the reason against the rejection |
| `Signature`, `Signature-Input` | once [message signing](#message-signing) is enabled (off by default in 3.8.0, one call): RFC 9421 message signatures on a request that carries a token, an `install` member (per installation key) and an `account` member (account key), over the method, URL, the headers above, the body digest when there is a body, and any headers you choose to add | verifies whichever signature it is configured for; the signature makes the request immutable, signed headers and body included, and links it to this token and so to the attested app installation; sign your session or `Authorization` header too and the request is linked to the user as well |
| `Approov-TraceID` | an optional debug header added by the SDK, on a request that carries a token | nothing, it is a debug header; pass it through unchanged |

Whatever the attestation outcome, the TLS connection to each domain you have added, token-protected or secrets-only, is validated against the [Managed Trust Roots](https://approov.io/docs/latest/approov-usage-documentation/#managed-trust-roots) that Approov maintains for your account, or against the specific certificate public keys you configure for that domain, with the validation set updated dynamically without an app release and checked for the request's host on every request. A connection that does not validate is refused with OkHttp's standard `SSLPeerUnverifiedException`, exactly as OkHttp refuses any connection whose certificate it cannot trust; only that request fails, so other hosts sharing an HTTP/2 connection with it are unaffected. The check uses the certificate chain your device's trust store validated, as OkHttp 4.x records it, never a certificate the server merely appends, which is why the package needs OkHttp 4.12 or later (it depends on 4.12.0; OkHttp 3.x is not supported and OkHttp 5 is not yet tested). Requests to domains you haven't added to Approov are sent unchanged.

**Secrets-only APIs.** An API you added with `-noApproovToken` needs no Approov token but is pinned and may use [secure strings](#secure-strings). The SDK reports it as `UNPROTECTED_URL`, which does not mean "not protected by Approov": the client substitutes your secure strings into its requests and validates its connections, and adds no Approov header. Releases 3.5.2 to 3.5.8 stopped substituting secure strings for these APIs; 3.8.0 restores the 3.5.1 behaviour.

## What happens by default

For every request the `ApproovService` interceptor fetches an Approov token for the request URL and applies the decisions of the installed `ApproovServiceMutator` (see [Service mutators](#service-mutators)). Three standard decision sets are provided:

* `ApproovServiceMutator.CLOSE_FAILURE` keeps the 3.5.x token decisions. A failed attestation fails the request in the app with the same `ApproovException` types as 3.5.x. Secure strings changed: a secure string that cannot be obtained never fails a request, whatever its status; the status replaces the placeholder and the request proceeds, where 3.5.x threw.
* `ApproovServiceMutator.ALWAYS_PROCEED` never fails a request: whatever the outcome the request is sent, and the backend, which is the enforcement point, decides.
* `ApproovServiceMutator.DEFAULT` is the out-of-the-box mutator, which `setServiceMutator(null)` reinstates; `initialize` keeps whichever mutator is installed. `DEFAULT` points to whatever the release considers default behaviour: **in 3.8.0 that is `CLOSE_FAILURE`; in 4.0.0 it is planned to become the `ALWAYS_PROCEED` behaviour**, a breaking change kept for the major release. An app that wants a fixed behaviour names `CLOSE_FAILURE` or `ALWAYS_PROCEED` explicitly.

| Approov token fetch status | `CLOSE_FAILURE` (3.8.0 default) | `ALWAYS_PROCEED` |
| :--- | :--- | :--- |
| `SUCCESS` | proceeds with the token, `Approov-Status: success`, the trace header, the secure strings and the signatures | same |
| `UNPROTECTED_URL` (secrets-only API) | proceeds with the secure strings only, no Approov headers | same |
| `UNKNOWN_URL` (host not added to Approov) | proceeds untouched | same |
| `NO_APPROOV_SERVICE`, host in the pin set | proceeds with `Approov-Status: no_approov_service` only (no token header, no secure strings, no signatures) | same |
| `NO_NETWORK`, `POOR_NETWORK`, `UNTRUSTED_NETWORK`, host in the pin set | fails with `ApproovNetworkException` (retryable); `setProceedOnNetworkFail` is removed in 3.8.0, install `ALWAYS_PROCEED` or your own mutator to proceed | proceeds with the status, lowercased, and nothing else |
| any other status (`REJECTED`, `INTERNAL_ERROR`, `BAD_URL`, ...), host in the pin set | fails with `ApproovFetchStatusException` | proceeds with the status, lowercased, and nothing else |
| any failure status, host not in the pin set (or the pin set is empty or cannot be read) | proceeds untouched, never fails | same |

"A host in the pin set" is a host that is a key of the SDK's pin set (`public-key-sha256`), compared without regard to case and ignoring one trailing dot, the `*` Managed Trust Roots key excluded: every API domain you added, either kind. A failure status says nothing about the host, because the SDK looks the domain up only after a successful fetch, so the pin set decides whether the failure concerns your API at all. A request to any other host is treated like one to a host not added to Approov: it goes out unchanged, and no standard mutator fails it in the app. Your own mutator is still asked (`handleInterceptorFetchTokenResult`) and may fail it by throwing, an opt-in for an app that should only ever connect to its own protected hosts; returning `true` or `false` adds nothing, and an exception from the standard decisions it inherits or calls is ignored there. While the pin set is empty (before the first dynamic configuration, or on a device in unpin mode) every failing request is sent that way. The token fetch status alone decides everything else.

Secure string substitutions (see [Secure strings](#secure-strings)) happen only on a `SUCCESS` or `UNPROTECTED_URL` request over `https`. On such a request each placeholder is decided by its own secure string fetch:

| Secure string fetch status | `CLOSE_FAILURE` (3.8.0 default) | `ALWAYS_PROCEED` |
| :--- | :--- | :--- |
| `SUCCESS` | the secret is substituted | same |
| `SUCCESS`, but the secret is a value the header or URL cannot carry | the placeholder is left in place, the request proceeds and a warning names the header or query parameter, never the value | same |
| `UNKNOWN_KEY` (the value is not a secure string key, the key was deleted, or its value cannot be decrypted) | `unknown_key` replaces the placeholder and the request proceeds | same |
| any other status (`REJECTED`, `DISABLED`, `NO_APPROOV_SERVICE`, `NO_NETWORK`, `POOR_NETWORK`, `UNTRUSTED_NETWORK`, `INTERNAL_ERROR`, ...) | the status in lowercase (`rejected`, `disabled`, `no_approov_service`, ...) replaces the placeholder and the request proceeds (3.5.x threw for each of these) | same |

The status is written exactly as the `Approov-Status` header writes statuses, the lowercase enum name. A header with a required prefix keeps it (`Authorization: Bearer rejected`), and each occurrence of a query parameter becomes the status of its own fetch (`?api_key=no_network`). This gives your backend the reason a secret is missing without another header: compare the value against your real secret and, when it does not match, log it as the reason.

A standard mutator never fails a request because of a secure string. Your own mutator may still do so by throwing from its substitution hooks (see [Service mutators](#service-mutators)). The `Approov-Status` header always reports the token fetch, never a secure string fetch.

A header can carry only tab and printable ASCII. A secure string set in the account with a non-ASCII or DEL character, or any value an app set with a new definition (`fetchSecureString(key, newDef)`), may break that rule; such a value is never substituted into a header. In a query parameter the layer reads the URL back as OkHttp stores it and substitutes only a value that comes back unchanged or percent-encoded: OkHttp percent-encodes non-ASCII and DEL, which the backend decodes to the same value, so those are substituted, but it silently drops tab, LF, FF and CR from a URL, and an `&` or `#` in the value would end the parameter or start a fragment, so such a value keeps the placeholder. The Approov token and the trace ID are always base64url; one the SDK issued that a header cannot carry is reported as an SDK problem, `ApproovException("Approov SDK problem: token for header <name> has an invalid character")` (or `trace ID`), never as your configuration and never quoting the value. A value you supply yourself that a header cannot carry (a token prefix given to `setTokenHeader`, or a header your mutator sets in `handleInterceptorProcessedRequest`) is a configuration error and fails the request with `ApproovException("Approov header <name>: invalid character in value")`, which never quotes the value.

To opt in to always proceeding in 3.8.0:

```kotlin
ApproovService.setServiceMutator(ApproovServiceMutator.ALWAYS_PROCEED)
```

A request that fails under `CLOSE_FAILURE` never reaches the network. All the exceptions above are `ApproovException`s, and so `IOException`s, delivered by `execute()` or to `Callback.onFailure`. The other exceptions an app sees from the request path, under either mutator, are its own configuration errors (a body digest configured as required that cannot be generated, or an unsupported signature algorithm, both failing with an `ApproovException`), TLS connections to its API domains that do not validate against the Managed Trust Roots or the certificate public keys configured for the domain, which fail with `javax.net.ssl.SSLPeerUnverifiedException` like any OkHttp certificate check, and aborts the app itself opted in to through a [service mutator](#service-mutators).

The direct methods (`fetchToken`, `fetchSecureString`, `fetchCustomJWT`, `precheck`) return a value to the caller and therefore report failures by throwing an `ApproovException` whichever mutator is installed; see the [reference](REFERENCE.md).

## The `Approov-Status` header

Every request to a token-protected API carries the `Approov-Status` header with the SDK token fetch status in lowercase, identical on Android and iOS: `success`, `no_network`, `poor_network`, `untrusted_network`, `no_approov_service`, `rejected`, `internal_error`, and so on. It is sent on successful requests too. It says why this particular request carries no attestation proof, so the backend can log the reason against the request and act on it, rejecting it for example. A request that proceeds on a failure status carries it to a host in the account's pin set and to no other host (see [What happens by default](#what-happens-by-default)). It is not sent to hosts you have not added to Approov, nor to a secrets-only API added with `-noApproovToken`. Secure string substitution failures are not reported on it: the placeholder value left in the header or query parameter is the evidence.

The header name can be changed, and the header disabled by passing `null`, in which case a request that could not be protected is sent with no Approov header at all and no explanation:

```kotlin
ApproovService.setStatusHeader("X-Approov-Fetch-Status")
ApproovService.setStatusHeader(null)
```

With message signing enabled the status header is covered by the signatures, so it cannot be stripped or altered in transit without invalidating them.

## Message signing

Message signing is **off by default in 3.8.0** and switched on with a single call, independent of the service mutator:

```kotlin
ApproovService.enableMessageSigning()
```

From then on every request carrying an Approov token header is signed with **both** the install signature (`ecdsa-p256-sha256`, per app installation key held in the device secure hardware, dictionary member `install`) and the account signature (`hmac-sha256`, shared account key delivered on attestation, member `account`). They are emitted as two members of the same `Signature` and `Signature-Input` headers over the same covered components, so that a device without secure hardware still yields a verifiable signature; the backend chooses which it verifies. If one signature cannot be produced the request proceeds with the other, or unsigned. A request that proceeds without a token carries no token header and is not signed, nor is a request to a secrets-only API added with `-noApproovToken`: only a request carrying a token is signed. See [Installation Message Signing](https://approov.io/docs/latest/approov-usage-documentation/#installation-message-signing) and [Account Message Signing](https://approov.io/docs/latest/approov-usage-documentation/#account-message-signing). **From 4.0.0 signing is on and compulsory.** Make sure your backend accepts signed requests before you switch it on, and before you upgrade to 4.0.0.

Signing runs last, after the mutator's decisions, the secure string substitutions and the mutator's `handleInterceptorProcessedRequest` callback, so the signature covers what is sent. `Content-Type` and `Content-Length` are covered with the values OkHttp sends, which it sets from the request body after the package has signed (replacing any value your app set, and sending no `Content-Length` for a chunked body). Your own network interceptors run after the package's, so they see each request as it is sent; they must not change a signed header, the URL or the body, or the signature no longer verifies. It works the same under `DEFAULT`, `CLOSE_FAILURE`, `ALWAYS_PROCEED` and any custom mutator: installing a mutator never switches signing on or off. A redirect within a protected domain, an authenticator retry and a stale protection refresh are signed afresh. `initialize` leaves the switch and any host factories as they are, so `enableMessageSigning` may be called before or after it.

The default signature covers the request method and target URI, the `Approov-Token` header, the `Approov-TraceID` and `Approov-Status` headers when present, the `Authorization`, `Content-Length` and `Content-Type` headers when present, a SHA-256 `Content-Digest` of the body when one can be computed (an empty body included; not a one-shot or chunked body), a `created` timestamp and a 15 second `expires`. Each component is covered once, as RFC 9421 requires: with `setTokenHeader("Authorization", "Bearer ")` the token header is the `authorization` component.

```kotlin
ApproovService.disableMessageSigning()      // switch it off again
ApproovService.isMessageSigningEnabled()    // whether it is on
```

### Choosing the signatures and the covered components

Pass a factory to `enableMessageSigning`, and a factory per host with `putMessageSigningHostFactory`. Start from the default factory and override what you need; a bare `SignatureParametersFactory()` covers nothing and is worthless:

```kotlin
import io.approov.service.okhttp.ApproovDefaultMessageSigning
import io.approov.service.okhttp.ApproovService

val factory = ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()
    .setUseInstallMessageSigning()          // install only; or setUseAccountMessageSigning(), or
                                            // setUseInstallAndAccountMessageSigning() (the default)
    .setExpiresLifetime(60)                 // default 15s
    .addOptionalHeaders("X-Request-Id")     // covered when present
    .setBodyDigestConfig(ApproovDefaultMessageSigning.DIGEST_SHA256, true) // digest required: a body that
                                            // cannot be digested fails the request with
                                            // RequiredBodyDigestException, an IOException
                                            // (a configuration error)

// a factory is shared by every request it applies to, so use a separate
// instance for a host that needs different settings
val paymentsFactory = ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()
    .setExpiresLifetime(5)

ApproovService.enableMessageSigning(factory)
ApproovService.putMessageSigningHostFactory("payments.example.com", paymentsFactory)
```

A host factory applies to requests to that host (matched without regard to case, without the port); the default factory to every other protected host. `putMessageSigningHostFactory(host, null)` removes a host factory. Return `null` from a custom factory to leave a request unsigned. Custom covered components are built with `io.approov.util.okhttp.sig.SignatureParameters`; before 3.8.0 that class was `io.approov.util.sig.SignatureParameters`.

### Verifying signatures on the backend

The verifier needs the account message signing key for the `account` signature (the `install` signature is verified with the public key carried in the Approov token). The CLI prints it with `approov secret -messageSigningKey get-base64url`, but that command does not print the key alone: a banner, a warning, the password prompt, the key ID and the encoding come before the key line. Take only the key line when you copy it or capture it in a script; anything else in the configured key makes every account signature fail to verify.

## Header names

```kotlin
ApproovService.setTokenHeader("Authorization", "Bearer ")   // default "Approov-Token", no prefix
ApproovService.setTraceIDHeader(null)                       // default "Approov-TraceID"; null disables
ApproovService.setStatusHeader("X-Approov-Fetch-Status")          // default "Approov-Status"; null disables
```

## About the Approov account ID

The string passed to `initialize` (the CLI and the SDK call it the SDK config string, `approov sdk -getConfigString`) looks like `#your-account#p6nZ...=`. It names your account and carries a fingerprint of the account's public key, so the SDK knows which Approov service to attest against and can verify the configuration it later downloads. It is not a secret and does not rotate; the same value serves every app in the account, and registering an app is a separate step. It is not an API key: nothing is granted by knowing it.

## Bypass initialization

Initializing with an empty string instead of the Approov account ID enables the package in bypass mode (`isApproovServiceEnabled()` is `true`, `isApproovProtectionEnabled()` is `false`): requests through the `OkHttpClient` go out with no Approov processing (no token, signing, secure strings or Approov connection validation, only OS trust), and the methods that call the SDK throw `ApproovException` without calling it. The same applies to a request made before any `initialize`, which is neither failed nor held. This is a bootstrap or fallback state, for example while the account ID is fetched remotely, or as the guard in the README example against an account ID that does not reach the app intact. A later `initialize` with the account ID enables Approov at runtime when the SDK accepts it. Configuration set before it is kept, and clients already obtained from `getOkHttpClient()` protect their requests from then on. An empty string after protection is enabled is ignored. Reinitializing from one account ID to a different one is rejected by the SDK, whose exception reaches the caller with the package state unchanged. See [initialize in the reference](REFERENCE.md#initialize).

```java
ApproovService.initialize(context, "");
```

## Initialize, then configure

Call `initialize`, check the result (it is synchronous, so there is nothing to await), then make every configuration call your app needs: the token, trace, status and binding headers, substitution headers and query parameters, exclusion regular expressions, the service mutator, message signing and its host factories, the logging level, the stale protection refresh period and so on. Make them straight after `initialize`, in the same `Application.onCreate`, before the app issues protected requests. `initialize` never resets configuration, so configuration made before it still applies, but this is the documented pattern.

Initialization done for you, for example by a one-line native bootstrap in a React Native app, is supported and configures nothing: it calls `initialize` with the account ID and comment, and that is all. All configuration is made by the app afterwards.

**Requests before the configuration call.** A request the package processes after `initialize` and before your configuration call uses the defaults in force at that moment: the `DEFAULT` service mutator (`CLOSE_FAILURE` in 3.8.0), the `Approov-Token` header with no prefix, no binding header, no exclusions, no substitutions (so a secure string placeholder goes out unchanged), message signing off and logging at `INFO`. A configuration change applies only to requests processed after the call; a request already in flight is not processed again. Connection validation does not depend on this order: the keys come from the Approov SDK's configuration, not from your configuration calls, and apply from initialization. Requests that can run before the app's configuration call include:

* native code at startup, for example SDKs the app starts that use the package's client, and Android headless JS tasks in a React Native app;
* JavaScript modules that fetch when they are imported, in a React Native app.

Configure immediately after `initialize`, before the app issues protected requests, and keep startup requests to protected hosts after that point.

## Token binding

[Token binding](https://approov.io/docs/latest/approov-usage-documentation/#token-binding) ties the Approov token to the value of a header on the request, typically an OAuth `Authorization` header, so a token cannot be replayed with different credentials. Only a hash of the value reaches Approov, as the `pay` claim of the token, which the backend verifies.

```kotlin
ApproovService.setBindingHeader("Authorization")
```

Each request's token is bound to that request's own header value, also when requests carrying different values are in flight at once, as during an OAuth token refresh: the SDK holds one binding value for the whole app, so the package sets the value and fetches the token as one step for every request that carries the binding header, on the first attempt and whenever its protection is reapplied (a stale protection refresh, a redirect, an authenticator retry). Requests that carry no binding header are not held up by this. Before 3.8.0 two such requests could each be sent with a token bound to the other's value, which the backend rejected.

`setDataHashInToken` binds arbitrary data instead. Never use both: the binding header overrides the data hash on every request.

## Secure strings

[Secure strings](https://approov.io/docs/latest/approov-usage-documentation/#secure-strings) let API keys and other secrets be removed from the app and delivered only to attested installations. Register the headers, and the query parameters, whose values are secure string keys to be substituted:

```kotlin
ApproovService.addSubstitutionHeader("X-Api-Key", null)        // whole value is the key
ApproovService.addSubstitutionHeader("Authorization", "Bearer ") // value after the prefix is the key
ApproovService.addSubstitutionQueryParam("api_key")
```

A value whose secure string fetch fails is replaced by the fetch status in lowercase and the request proceeds, including `unknown_key`, meaning no secure string is defined for it (see the [secure string table](#what-happens-by-default)). A secure string is only ever substituted into a request to an API domain you added to Approov, token-protected (`SUCCESS`) or secrets-only (`UNPROTECTED_URL`, added with `-noApproovToken`), sent over TLS. A request to a host not added to Approov (`UNKNOWN_URL`), a cleartext `http` request, an `https` request whose URL the SDK reports as `BAD_URL` (a URL it cannot parse, see [core-project-approov#822](https://github.com/approov/core-project-approov/issues/822)) and a request whose token fetch failed for any other reason keep their placeholders under every mutator, `ALWAYS_PROCEED` and custom mutators included, with no status written. No secure string is fetched for such a request and the substitution hooks are not consulted; the request proceeds as the token decision says, and a log line names the header or query parameter, never the value: a warning for a cleartext or `BAD_URL` request, which is a configuration problem, and a DEBUG line otherwise (see [Diagnostics](#diagnostics)). This matters under `ALWAYS_PROCEED`, which sends a request whatever its token fetch status, including a redirect from your API to an `http` URL. `CLOSE_FAILURE` fails such a request with `ApproovFetchStatusException` as before. A substituted query parameter is part of the URL OkHttp stores, so if your client has an OkHttp `Cache` the secret is written to the cache on disk with the response; don't give a client that uses query parameter substitution a cache, or substitute a header instead. Header names are matched case-insensitively, and a header cannot be both a binding header and a substitution header. `fetchSecureString` fetches or defines a secure string directly.

## Excluding URLs

Requests whose URL matches an exclusion regular expression are sent untouched, without a token fetch:

```kotlin
ApproovService.addExclusionURLRegex("https://api\\.example\\.com/health.*")
```

Connection validation still applies to excluded requests on domains added to Approov. The validation set is updated only when a request that is not excluded fetches a token through the client and the SDK reports a configuration change; a direct `fetchToken` call does not update it. Make sure the app keeps calling some URL on each protected domain that is not excluded. An invalid regular expression throws `IllegalArgumentException` (unchecked, with the `PatternSyntaxException` as its cause) and adds nothing, as on every Approov package; 3.5.x logged it and carried on without the exclusion.

## Connection validation

The pinning interceptor validates the TLS connection to each domain you have added to Approov against the certificate public keys configured for it, falling back to the Managed Trust Roots for a domain with none of its own.

* **Exact host lookup.** Keys are looked up exactly per host, without regard to case and with one trailing dot ignored: a domain added as `API.Example.com` applies to `api.example.com`, and a request to `api.example.com.` is checked like one to `api.example.com`. Approov API domains never contain wildcards (the Approov admin API accepts only letters, digits, `.` and `-`), so no key is a pattern. The `*` entry is not a host: it holds the Managed Trust Roots, used for a listed domain with no keys of its own, never for a domain that is not listed. The package passes the keys to OkHttp's `CertificatePinner`, which would read a `*.` key as a wildcard pattern, but such keys cannot occur.
* **No Managed Trust Roots.** A domain added with no keys of its own while the Managed Trust Roots are empty or absent is validated by OS trust only, a valid development setup. It is not silent: the first connection to each such host logs a warning under `ApproovPinningInterceptor` naming the host, never a key, once per host.
* **Failures in the log.** A connection whose certificate matches none of the domain's keys fails with `SSLPeerUnverifiedException` and an error line naming the host, never a key. A cleartext (`http`) request to a domain with keys fails the same way, with a warning. If the keys cannot be read from the SDK, the connection fails closed with an `ApproovException` and an error line naming the host and the cause.

## Service mutators

An `ApproovServiceMutator` centralizes app-specific policy without forking the package. The installed mutator is consulted at each decision point; every method has a default, so override only what you need. The interceptor defaults are the `CLOSE_FAILURE` decisions (see [What happens by default](#what-happens-by-default)), so a custom mutator that should never abort a request delegates those decisions to `ApproovServiceMutator.ALWAYS_PROCEED`. Mutators carry decisions only: none signs, and installing one never switches [message signing](#message-signing) on or off. The hooks:

| Hook | Decides |
| :--- | :--- |
| `handleInterceptorShouldProcessRequest` | whether a request gets Approov processing at all (default: unless excluded) |
| `handleInterceptorFetchTokenResult` | given the token fetch result, `true` to send the request with the Approov headers its status allows (token, status and trace headers and signatures on `SUCCESS`; the status header alone on a failure status; none on `UNKNOWN_URL` or `UNPROTECTED_URL`); for a failure status on a host not in the pin set the answer is ignored and the request goes out untouched, unless your own code throws, which aborts it (a `CLOSE_FAILURE` decision you inherit or call is ignored there); `false` to send it with no Approov headers, or throw a standard network stack exception to abort the request. It governs the Approov headers only: secure strings follow the token fetch status, so a `SUCCESS` or `UNPROTECTED_URL` request gets them whatever this returns, and a failure status never does |
| `handleInterceptorHeaderSubstitutionResult`, `handleInterceptorQueryParamSubstitutionResult` | consulted only on a `SUCCESS` or `UNPROTECTED_URL` request over `https`: whether to apply a secure string substitution. Both standard mutators return `true` only for `SUCCESS` and never throw. On any other status the request proceeds with the status in place of the placeholder, whether the hook returns `false` or `true` (there is no secure string to substitute); returning `false` on `SUCCESS` leaves the placeholder. Your own mutator may throw a standard network stack exception to abort |
| `handleInterceptorProcessedRequest` | final changes to the processed request; message signing, when enabled, runs after it and covers its changes |
| `supportsProtectionRefresh` | whether the processed request callback may run again on a stale request (see below) |
| `handlePinningShouldProcessRequest` | whether Approov connection validation (Managed Trust Roots or configured public keys) applies to a request |
| `handlePrecheckResult`, `handleFetchTokenResult`, `handleFetchSecureStringResult`, `handleFetchCustomJWTResult` | how the direct methods map a result to an exception |

The status header is set by the interceptor from the token fetch result whenever the mutator returns `true` from `handleInterceptorFetchTokenResult` for `SUCCESS` or a failure status (it is asked about a failure status only for a host in the pin set); returning `false` sends the request with no Approov headers at all. The processed request callback runs for every request the layer processes: a `SUCCESS` or `UNPROTECTED_URL` request, and a failure status the mutator lets proceed to a host in the pin set.

### Opting in to aborting requests

Under `ALWAYS_PROCEED` every outcome proceeds. An app may decide that some outcome should not: the common case is `NO_NETWORK`, where the device could not reach Approov and the API call is about to fail on the same network anyway, so the app would rather get a network error at once and show its offline state than send a request that will not reach its backend. That is the app's policy, so it is expressed by overriding the hook and throwing. The exception must be a standard network stack exception (`java.io.IOException` or a platform subclass such as `java.net.ConnectException` or `javax.net.ssl.SSLException`), not an Approov type: the abort then surfaces to the app's own error handling, retry logic and support tooling exactly like any other network failure, with the Approov status available from the result for the app's own logging. The interceptor hooks declare `IOException` for this purpose, and a mutator aborts a request only by throwing one. Anything else a hook throws (a `NullPointerException` or other programming error, or a deliberate `RuntimeException`) is treated as a failed request, never as a crash: the request fails with an `ApproovException` whose cause is the original exception, the hook is named in its message and in an error-level log, `execute()` throws it and an enqueued call receives it in `onFailure` (SPECIFICATION 1.6.1). OkHttp would otherwise rethrow it on its dispatcher thread for an enqueued call, terminating the app. An Approov exception your hook throws (an `ApproovException` or a subclass, one your code built or one it lets escape from a direct method such as `fetchToken` or `fetchSecureString`) is treated the same way: it is your hook's failure, not an Approov decision, so the request fails with a plain `ApproovException` naming the hook with yours as its cause. (The aborts `CLOSE_FAILURE` makes keep the 3.5.x Approov exception types, so that 3.8.0 does not change what an existing app catches. That holds in a custom mutator too, whether it inherits those decisions or calls them, for example `ApproovServiceMutator.CLOSE_FAILURE.handleInterceptorFetchTokenResult(results, url)`, and a decision your hook catches and rethrows unchanged keeps its type. The standard substitution decisions, `closeFailureSubstitution` included, never throw.)

Delegate to `ALWAYS_PROCEED` for every decision you don't change:

```kotlin
import android.util.Log
import com.criticalblue.approovsdk.Approov
import io.approov.service.okhttp.*
import okhttp3.Request

class AppPolicy : ApproovServiceMutator {
    private val base = ApproovServiceMutator.ALWAYS_PROCEED

    // opt out of proceeding for one outcome: with no network the request will not
    // reach the backend, so fail it now as an ordinary network error instead of
    // sending it. Every other outcome proceeds.
    override fun handleInterceptorFetchTokenResult(result: Approov.TokenFetchResult, url: String): Boolean {
        if (result.status == Approov.TokenFetchStatus.NO_NETWORK) {
            Log.w("AppPolicy", "no network for Approov attestation, not sending $url")
            throw java.net.ConnectException("no network")
        }
        return base.handleInterceptorFetchTokenResult(result, url)
    }

    override fun handleInterceptorHeaderSubstitutionResult(result: Approov.TokenFetchResult, header: String): Boolean =
        base.handleInterceptorHeaderSubstitutionResult(result, header)

    override fun handleInterceptorQueryParamSubstitutionResult(result: Approov.TokenFetchResult, queryKey: String): Boolean =
        base.handleInterceptorQueryParamSubstitutionResult(result, queryKey)

    // add app metadata after Approov processing; message signing, if enabled, runs after this and covers it
    override fun handleInterceptorProcessedRequest(request: Request, changes: ApproovRequestMutations): Request =
        request.newBuilder().header("X-Client-Platform", "android").build()

    // the callback above only replaces a header, so it is safe to run again on a refresh
    override fun supportsProtectionRefresh(): Boolean = true

    // skip Approov connection validation for a telemetry host
    override fun handlePinningShouldProcessRequest(request: Request): Boolean =
        request.url.host != "metrics.example.com"
}

ApproovService.setServiceMutator(AppPolicy())
ApproovService.enableMessageSigning()   // independent of the mutator
```

Keep hooks fast and free of side effects: they run on the request path. A mutator's processed request callback may be invoked again by the stale protection refresh only if `supportsProtectionRefresh()` returns `true` (it does for `DEFAULT`, `CLOSE_FAILURE` and `ALWAYS_PROCEED`); a custom mutator that does not opt in gets no stale refresh at all.

## Stale protection refresh

A request held between Approov processing and transmission, for example by a device doze period or an app-level queue, would otherwise go out with an expired token or signature. A network interceptor strips and reapplies the protection before transmission when the request has been held for longer than the refresh period (default 3000 ms): the token is refetched (from the SDK cache when still valid), the status header reflects the new result, substitutions are redone and, with message signing enabled, the signatures regenerated. It runs on every network attempt, so OkHttp's own retries are refreshed too, and a **redirect** is reclassified for its new URL: a redirect to a host not added to Approov leaves with no Approov headers and no secrets, a redirect to a secrets-only API carries its secure strings and nothing else, and a redirect within a token-protected domain is re-signed over the new target. Each attempt is classified by its own token fetch: a refresh that reports `UNPROTECTED_URL` drops the token, status and trace headers and the signatures and substitutes the secure strings afresh from your placeholders; one that reports `UNKNOWN_URL` drops everything and restores the placeholders; one that reports a failure the mutator lets proceed carries the status header only. Any attempt whose headers differ from the protected request, typically an OkHttp `Authenticator` retrying a `401` with a new `Authorization`, is reprotected the same way, so the signature always covers what is sent. A header the app changed between attempts is kept and substituted afresh; only headers still holding the value this layer installed, a secure string or a status written in place of the placeholder, are restored to their placeholders, and the URL goes back to your placeholders before the secure strings are fetched again.

A redirect to another origin (scheme, host or port) is stripped of everything this layer added (the token, status and trace headers, the signatures, and each substituted header value, a secure string or a status, back to your placeholder; OkHttp itself drops `Authorization`) and then evaluated from the start as a new request for the target URL: a token is fetched for the target URL, never reused from the first host, secure strings are substituted only if the target is a token-protected or secrets-only `https` API, and the signatures are computed for a token-protected target; a target not added to Approov goes out with none of it. The redirect target URL is left exactly as the server sent it, in the request that follows it, in the redirect response's `Location` header and in `response.request().url()`. If your server echoes a secure string into a redirect target, for example in a query parameter, the layer does not restore your placeholder: the next hop receives what the server sent. Do not let a server echo a secret into a redirect; what it echoes is its responsibility. The query of a redirect target is never looked up either: even where a parameter name is one you added with `addSubstitutionQueryParam`, its value is the server's, so no secure string is fetched for it and no status is written into it. On a protected target only your own placeholders are substituted, those in your request headers; a query parameter placeholder is substituted again only on an attempt to the URL your request was protected for, such as an authenticator retry or a `303` back to the same URL.

Two limits, accepted by design:

* Only requests that were given protection are reclassified on redirect. A request to a domain that is not in Approov which redirects into a protected domain arrives at the protected domain without protection and the backend rejects it. Add every domain the app calls, including legacy or alias domains that redirect, to Approov.
* The layer restores what it added, not what it replaced. If the app itself sets `Signature` or `Signature-Input` headers on a request that Approov also signs, the value this layer wrote is treated as the app's on a redirect and travels with it. Do not set those headers yourself on Approov-protected requests.

The package's network interceptors are the first in the client, ahead of any your app adds (including through `setOkHttpClientBuilder`), so an app network interceptor such as a logger or an inspector only ever sees an attempt after its protection was stripped or refreshed: a redirect to a domain Approov does not protect never shows it the token, the status or the substituted secrets. A request held inside one of your own network interceptors is therefore not refreshed.

One request, one mutator (SPECIFICATION 6.5) is not implemented in 3.8.0: a refresh, a redirect, a retry and the connection validation hook use the mutator installed at that moment, not the one that first processed the request. Install your mutator once, at startup, and do not replace it while requests are in flight.

```kotlin
ApproovService.setStaleProtectionRefreshPeriod(1000)   // <= 0 disables
```

## Diagnostics

The package never logs a token. For each fetch it logs, at debug level, which is off by default (see the logging level below), the [loggable form](https://approov.io/docs/latest/approov-usage-documentation/#loggable-tokens) of the result: the token's claims plus a short fragment of its signature, which cannot be turned back into a usable token. The claim to look at is `arc`, the [Attestation Response Code](https://approov.io/docs/latest/approov-usage-documentation/#attestation-response-code): it encodes why the attestation produced the result it did, for example:

```
D/ApproovTokenInterceptor: Token for https://api.example.com/v1/items: {"did":"...","exp":1757400000,"arc":"IXPSB7TRK26LXE3M","sip":"a1b2c3", ...}
```

The URL in the line is the request URL without its user info, query or fragment, so no query value (a substituted secure string or personal data) is logged. To see these lines, set the level in a debug build:

```Java
ApproovService.setLoggingLevel(ApproovLogLevel.DEBUG);
```

or, without a rebuild, on any installed build including a release one, enable debug logging for the `ApproovService` tag; it raises any level but `OFF` to `DEBUG` for all of the package's tags until it is set back or the device reboots:

```sh
adb shell setprop log.tag.ApproovService DEBUG
```

To decode an `arc`, ask the CLI for the decoding command once; it prints a `curl` with your account's API key filled in and an `<arc>` placeholder:

```sh
approov token -showArcInfoCurl
```

```
curl -H "Authorization: <api-key>" -H "Arc: <arc>" https://<management-url>/arc-info/
```

Run it with the `arc` value from the log in place of `<arc>` and the response lists the flags behind the result, for example `emulator` or `app-not-registered`. Your account's [live metrics](https://approov.io/docs/latest/approov-usage-documentation/#metrics-graphs) show the same reasons in aggregate within a minute. While you work, a [development signing certificate](https://approov.io/docs/latest/approov-usage-documentation/#development-app-signing-certificates) lets debug builds and emulators pass attestation.

**Undelivered secrets and failing requests.** When the package does not deliver a secure string at run time, because its fetch returned a status other than `SUCCESS` (the status is sent in place of the placeholder) or because the request's token fetch failed or its host is not added to Approov (the placeholder is left), and when it lets a request proceed on a token fetch failure (with the status header only, with no Approov header because your mutator said so, or untouched for a host not in the pin set, where an abort by a standard decision is ignored), it writes a line at debug level only, nothing at `INFO` or above. The line names the header or query parameter, never its value, and gives the fetch status with the ARC and the rejection reasons when the SDK supplies them, for example:

```
D/ApproovTokenInterceptor: Proceeding without a token for https://api.example.com/v1/items: token fetch status rejected, ARC IXPSB7TRK26LXE3M, rejection reasons root
D/ApproovTokenInterceptor: Secure string not substituted in header Api-Key: token fetch status rejected, ARC IXPSB7TRK26LXE3M, rejection reasons root, placeholder left
D/ApproovTokenInterceptor: Secure string not substituted in query parameter api_key: secure string fetch status no_network, status sent in its place
```

Three cases are configuration problems and stay warnings, visible at the default level: a placeholder left because the request is not `https`, one left because the SDK reports the URL as `BAD_URL`, and a secure string value a header or URL cannot carry.

The `Approov-TraceID` header is a debug header; pass it through unchanged. Log output from the package is written under the tags `ApproovService`, `ApproovTokenInterceptor`, `ApproovFreshness`, `ApproovPinningInterceptor` and `ApproovMsgSign`, gated by [setLoggingLevel](REFERENCE.md#setlogginglevel): `ApproovLogLevel.ERROR` writes errors only, `WARNING` adds warnings, `INFO` (the default) adds information lines such as the initialization result, `DEBUG` adds the debug lines, and `OFF` writes nothing, errors included. The loggable token carries the device ID and the client IP claims, and `getDeviceID` logs the device ID, so both are debug lines: at the default level a release build writes neither. The loggable form of each fetched token can be [checked](https://approov.io/docs/latest/approov-usage-documentation/#loggable-tokens) with the Approov CLI. No token, secure string value, signature, query or substituted URL is logged. Prefer logging rejections from your backend's response, which saw the token and the `Approov-Status` header, over calling `getLastARC()` on the device: the device value may belong to a later attestation than the request that failed.
