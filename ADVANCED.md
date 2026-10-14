# Advanced Options

A standard integration of the Approov Package for OkHttp needs nothing in this document. You add the dependency, initialize, and use the client, as the [README](README.md) shows. The options below change the defaults, for apps with specific needs. Every method is documented in [REFERENCE.md](REFERENCE.md).

## What each request carries

What the client adds to a request depends on where the request goes, and on the token fetch status the Approov SDK reports for its URL:

| Request goes to | SDK status | What the client adds |
| :--- | :--- | :--- |
| an API domain you added to Approov (token-protected) | `SUCCESS` | the token, status and trace headers; your secure strings, or the [status of each one that could not be fetched](#secure-strings); the signatures, once [message signing](#message-signing) is on; a checked connection |
| an API domain you added with `approov api -add <domain> -noApproovToken` (secrets-only) | `UNPROTECTED_URL` | your secure strings, or the status of each one that could not be fetched; a checked connection. No token, status or trace header, and no signatures. |
| a host you have not added to Approov | `UNKNOWN_URL` | nothing. The connection relies on the device's trust store. |
| an API domain you added (either kind), when the SDK could not attest the app at that moment | a failure status | depends on the service mutator (see below) |
| any other host | a failure status | nothing (see below) |

A failure status means the SDK could not attest the app at that moment. For example, there was no network, the device or app was rejected, or the Approov service could not be reached.

For an API domain you added, the [service mutator](#what-happens-by-default) decides what happens. A service mutator is the object that holds the package's decisions, such as whether a failed attestation fails the request. It decides whether the request fails in the app or is sent. A request that is sent carries the `Approov-Status` header with the reason, and nothing else. It has no token header, no trace header, no secure string and no signatures.

For any other host, the request goes out unchanged. The standard mutators never fail it in the app. Only your own mutator can, by throwing from `handleInterceptorFetchTokenResult` (see [Service mutators](#service-mutators)).

The headers your backend may receive:

* `Approov-Token` carries the Approov token. This is a short-lived signed JWT that proves the app passed attestation for this request. The header is **absent** if no token could be obtained. Your backend checks the token's signature and expiry, and rejects a request whose token is missing or invalid. The token also carries the public key of the app installation, which verifies the `install` message signature described below.
* `Approov-Status` carries the outcome of the token fetch for this request, in lowercase: `success`, `no_network`, `rejected` and so on. It tells your backend why this particular request carries no proof of attestation, so you can log the reason when you reject it.
* `Signature` and `Signature-Input` appear once you turn on [message signing](#message-signing), which is off by default in 3.7.0 and takes one call. They hold RFC 9421 message signatures, on a request that carries a token. See below.
* `Approov-TraceID` is an optional debug header the SDK adds to a request that carries a token. Your backend does nothing with it; pass it through unchanged.

The signature headers hold two members. The `install` member is made with a key unique to the app installation. The `account` member is made with your account key. Both cover the method, the URL, the headers above, the body digest when there is a body, and any headers you choose to add. Your backend verifies whichever signature it is set up for.

A signature means the request cannot be changed, signed headers and body included. It also links the request to this token, and so to the attested app installation. If you sign your session or `Authorization` header too, the request is linked to the user as well.

Whatever the attestation outcome, the package checks the TLS connection to each domain you have added, token-protected or secrets-only. It checks against the specific certificate public keys you configure for that domain, or else against the [Managed Trust Roots](https://approov.io/docs/latest/approov-usage-documentation/#managed-trust-roots). These are the trusted root certificate keys that Approov maintains for your account. The set of keys is updated dynamically, without an app release, and checked for the request's host on every request.

A connection that fails the check is refused with OkHttp's standard `SSLPeerUnverifiedException`. This is exactly how OkHttp refuses any connection whose certificate it cannot trust. Only that request fails. Other hosts that share an HTTP/2 connection with it are not affected.

The check uses the certificate chain your device's trust store validated, as OkHttp 4.x records it. It never uses a certificate the server merely appends. This is why the package needs OkHttp 4.12 or later. It depends on 4.12.0. OkHttp 3.x is not supported, and OkHttp 5 is not yet tested.

Requests to domains you haven't added to Approov are sent unchanged.

### Secrets-only APIs

An API you added with `-noApproovToken` needs no Approov token. Its connections are still pinned, meaning checked against its keys as described above, and its requests may use [secure strings](#secure-strings).

The SDK reports such an API as `UNPROTECTED_URL`. Despite the name, this does not mean Approov leaves it unprotected. The client substitutes your secure strings into its requests and checks its connections. It just adds no Approov header.

## What happens by default

For every request, the `ApproovService` interceptor fetches an Approov token for the request URL. It then applies the decisions of the installed `ApproovServiceMutator`. A service mutator is an object with one method for each decision the package makes, such as whether a failed token fetch fails the request (see [Service mutators](#service-mutators)). The package provides three standard ones:

* `ApproovServiceMutator.CLOSE_FAILURE` keeps the 3.5.x token decisions. A failed attestation fails the request in the app, with the same `ApproovException` types as 3.5.x. A secure string that cannot be obtained never fails a request, whatever its status. The status replaces the placeholder and the request goes ahead.
* `ApproovServiceMutator.ALWAYS_PROCEED` never fails a request. Whatever the outcome, the request is sent, and your backend decides. Your backend is then the enforcement point.
* `ApproovServiceMutator.DEFAULT` is the mutator installed out of the box. `setServiceMutator(null)` puts it back, and `initialize` keeps whichever mutator is installed. `DEFAULT` points to whatever the release considers default behaviour. **In 3.7.0 that is `CLOSE_FAILURE`. In 4.0.0 it is planned to become the `ALWAYS_PROCEED` behaviour.** That breaking change is kept for the major release. If your app needs a fixed behaviour, name `CLOSE_FAILURE` or `ALWAYS_PROCEED` explicitly.

In the table below, "a host in the pin set" means one of the API domains you added to Approov, of either kind. The next section explains the term.

| Approov token fetch status | `CLOSE_FAILURE` (3.7.0 default) | `ALWAYS_PROCEED` |
| :--- | :--- | :--- |
| `SUCCESS` | proceeds with the token, `Approov-Status: success`, the trace header, the secure strings and the signatures | same |
| `UNPROTECTED_URL` (secrets-only API) | proceeds with the secure strings only, no Approov headers | same |
| `UNKNOWN_URL` (host not added to Approov) | proceeds untouched | same |
| `NO_APPROOV_SERVICE`, host in the pin set | proceeds with `Approov-Status: no_approov_service` only (no token header, no secure strings, no signatures) | same |
| `NO_NETWORK`, `POOR_NETWORK`, `UNTRUSTED_NETWORK`, host in the pin set | fails with `ApproovNetworkException`, which can be retried. To send the request instead, install `ALWAYS_PROCEED` or your own mutator | proceeds with the status, in lowercase, and nothing else |
| any other status (`REJECTED`, `INTERNAL_ERROR`, `BAD_URL`, ...), host in the pin set | fails with `ApproovFetchStatusException` | proceeds with the status, in lowercase, and nothing else |
| any failure status, host not in the pin set (or the pin set is empty or cannot be read) | proceeds untouched, never fails | same |

### Hosts in the pin set

A host is in the pin set if it is a key of the SDK's pin set (`public-key-sha256`). The comparison ignores case and one trailing dot. The `*` key, which holds the Managed Trust Roots, does not count. So every API domain you added, of either kind, is in the pin set.

The pin set matters because a failure status says nothing about the host. The SDK looks up the domain only after a successful fetch. So the pin set decides whether a failure concerns your API at all.

A request to any other host is treated like one to a host not added to Approov. It goes out unchanged, and no standard mutator fails it in the app. Your own mutator is still asked, through `handleInterceptorFetchTokenResult`, and may fail the request by throwing. This is an opt-in for an app that should only ever connect to its own protected hosts. Returning `true` or `false` adds nothing there. An exception from a standard decision that your mutator inherits or calls is ignored there.

While the pin set is empty, every failing request is sent this way. The pin set is empty before the first dynamic configuration arrives, and on a device in unpin mode.

For everything else, the token fetch status alone decides.

### Secure string results

Secure strings (see [Secure strings](#secure-strings)) are substituted only on a request over `https` whose token fetch status is `SUCCESS` or `UNPROTECTED_URL`. On such a request, each placeholder is decided by its own secure string fetch:

| Secure string fetch status | `CLOSE_FAILURE` (3.7.0 default) | `ALWAYS_PROCEED` |
| :--- | :--- | :--- |
| `SUCCESS` | the secret is substituted | same |
| `SUCCESS`, but the secret is a value the header or URL cannot carry | the placeholder is left in place, the request proceeds and a warning names the header or query parameter, never the value | same |
| `UNKNOWN_KEY` (the value is not a secure string key, the key was deleted, or its value cannot be decrypted) | `unknown_key` replaces the placeholder and the request proceeds | same |
| any other status (`REJECTED`, `DISABLED`, `NO_APPROOV_SERVICE`, `NO_NETWORK`, `POOR_NETWORK`, `UNTRUSTED_NETWORK`, `INTERNAL_ERROR`, ...) | the status in lowercase (`rejected`, `disabled`, `no_approov_service`, ...) replaces the placeholder and the request proceeds | same |

The status is written the same way the `Approov-Status` header writes statuses: the enum name in lowercase. A header with a required prefix keeps the prefix, as in `Authorization: Bearer rejected`. Each occurrence of a query parameter gets the status of its own fetch, as in `?api_key=no_network`.

This gives your backend the reason a secret is missing, without another header. Compare the value with your real secret. When it does not match, log the value as the reason.

A standard mutator never fails a request because of a secure string. Your own mutator can, by throwing from its substitution hooks (see [Service mutators](#service-mutators)). The `Approov-Status` header always reports the token fetch, never a secure string fetch.

### Values a header or URL cannot carry

A header can carry only tab and printable ASCII characters. Two kinds of value may break that rule:

* a secure string set in the account with a non-ASCII or DEL character
* any value your app set with a new definition (`fetchSecureString(key, newDef)`)

Such a value is never substituted into a header.

In a query parameter, the package reads the URL back as OkHttp stores it. It substitutes a value only if the value comes back unchanged or percent-encoded. OkHttp percent-encodes non-ASCII and DEL characters, and your backend decodes them to the same value, so those values are substituted. But OkHttp silently drops tab, LF, FF and CR from a URL. An `&` or `#` in the value would end the parameter or start a fragment. A value containing any of these keeps the placeholder.

The Approov token and the trace ID are always base64url. If the SDK issues one that a header cannot carry, the package reports it as an SDK problem, never as your configuration. The exception is `ApproovException("Approov header <name>: invalid character in SDK token")`, or `SDK trace ID` in place of `SDK token`. It never quotes the value.

A value you supply yourself that a header cannot carry is a configuration error. Examples are a token prefix given to `setTokenHeader`, or a header your mutator sets in `handleInterceptorProcessedRequest`. The request fails with `ApproovException("Approov header <name>: invalid character in value")`, which never quotes the value.

To opt in to always proceeding in 3.7.0:

```kotlin
ApproovService.setServiceMutator(ApproovServiceMutator.ALWAYS_PROCEED)
```

### Exceptions your app can see

A request that fails under `CLOSE_FAILURE` never reaches the network. All the exceptions above are `ApproovException`s, and so `IOException`s. `execute()` throws them, and an enqueued call receives them in `Callback.onFailure`.

Under either mutator, a request can also fail for these reasons:

* a configuration error in your app: a body digest configured as required that cannot be generated, or an unsupported signature algorithm. Both fail with an `ApproovException`.
* a TLS connection to one of your API domains that does not pass the check against the Managed Trust Roots or the certificate public keys configured for the domain. This fails with `javax.net.ssl.SSLPeerUnverifiedException`, like any OkHttp certificate check.
* an abort your app opted in to through a [service mutator](#service-mutators).

The direct methods (`fetchToken`, `fetchSecureString`, `fetchCustomJWT`, `precheck`) return a value to the caller. So they report failures by throwing an `ApproovException`, whichever mutator is installed. See the [reference](REFERENCE.md).

## The `Approov-Status` header

Every request to a token-protected API carries the `Approov-Status` header. It holds the SDK's token fetch status in lowercase, the same on Android and iOS: `success`, `no_network`, `poor_network`, `untrusted_network`, `no_approov_service`, `rejected`, `internal_error` and so on. It is sent on successful requests too.

The header tells your backend why a particular request carries no proof of attestation. Your backend can log the reason against the request and act on it, for example by rejecting the request.

When a request goes ahead on a failure status, the header is sent only to a host in your account's pin set (see [What happens by default](#what-happens-by-default)). It is not sent to hosts you have not added to Approov, nor to a secrets-only API added with `-noApproovToken`.

The header does not report secure string failures. For those, the value left in the header or query parameter is the evidence.

You can rename the header, or turn it off by passing `null`. With the header off, a request that could not be protected is sent with no Approov header at all, and so with no explanation:

```kotlin
ApproovService.setStatusHeader("X-Approov-Fetch-Status")
ApproovService.setStatusHeader(null)
```

With message signing on, the signatures cover the status header. It cannot be removed or changed in transit without breaking them.

## Message signing

Message signing is **off by default in 3.7.0**. You turn it on with one call, independent of the service mutator:

```kotlin
ApproovService.enableMessageSigning()
```

From then on, every request that carries an Approov token header is signed with **both** of these:

* the install signature, `ecdsa-p256-sha256`. It uses a key unique to the app installation, held in the device's secure hardware. Its dictionary member is `install`.
* the account signature, `hmac-sha256`. It uses a shared account key, delivered to the app when it passes attestation. Its member is `account`.

Both are members of the same `Signature` and `Signature-Input` headers, over the same covered components. The covered components are the parts of the request a signature protects, such as the method, the URL and chosen headers. Sending both means a device without secure hardware still produces a signature your backend can verify. Your backend chooses which one it verifies. If one signature cannot be produced, the request goes ahead with the other, or unsigned.

Only a request carrying a token is signed. A request that goes ahead without a token carries no token header and is not signed. Nor is a request to a secrets-only API added with `-noApproovToken`.

See [Installation Message Signing](https://approov.io/docs/latest/approov-usage-documentation/#installation-message-signing) and [Account Message Signing](https://approov.io/docs/latest/approov-usage-documentation/#account-message-signing).

**From 4.0.0, signing is on and compulsory.** Make sure your backend accepts signed requests before you turn signing on, and before you upgrade to 4.0.0.

### When signing runs

Signing runs last. It comes after the mutator's decisions, the secure string substitutions and the mutator's `handleInterceptorProcessedRequest` callback, so the signature covers what is sent.

`Content-Type` and `Content-Length` are covered with the values OkHttp sends. OkHttp sets them from the request body after the package has signed. It replaces any value your app set, and sends no `Content-Length` for a chunked body.

Your own network interceptors run after the package's, so they see each request as it is sent. They must not change a signed header, the URL or the body, or the signature no longer verifies.

Signing works the same under `DEFAULT`, `CLOSE_FAILURE`, `ALWAYS_PROCEED` and any custom mutator. Installing a mutator never turns signing on or off.

A redirect within a protected domain, an authenticator retry and a stale protection refresh are each signed afresh.

`initialize` leaves the switch and any host factories as they are. So you can call `enableMessageSigning` before or after it.

### What the default signature covers

The default signature covers:

* the request method and target URI
* the `Approov-Token` header
* the `Approov-TraceID` and `Approov-Status` headers, when present
* the `Authorization`, `Content-Length` and `Content-Type` headers, when present
* a SHA-256 `Content-Digest` of the body, when one can be computed. An empty body is included. A one-shot or chunked body is not.
* a `created` timestamp and a 15 second `expires`

Each component is covered once, as RFC 9421 requires. So with `setTokenHeader("Authorization", "Bearer ")`, the token header is the `authorization` component.

To turn signing off again, or check whether it is on:

```kotlin
ApproovService.disableMessageSigning()      // switch it off again
ApproovService.isMessageSigningEnabled()    // whether it is on
```

### Choosing the signatures and the covered components

A signature parameters factory decides which signatures a request gets and which components they cover. Pass a factory to `enableMessageSigning`, and set a factory for a single host with `putMessageSigningHostFactory`.

Start from the default factory and override what you need. A bare `SignatureParametersFactory()` covers nothing, so it is of no use:

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

A host factory applies to requests to that host. The host is matched without regard to case, and without the port. The default factory applies to every other protected host.

`putMessageSigningHostFactory(host, null)` removes a host factory. A custom factory can return `null` to leave a request unsigned. To build custom covered components, use `io.approov.util.okhttp.sig.SignatureParameters`.

### Custom factory

To choose the covered components per request, subclass `SignatureParametersFactory` and override `buildSignatureParameters`. In it, hand each request to a ready-made factory from `generateDefaultSignatureParametersFactory`.

This example covers requests to `example.api.com`. Those whose path starts with `/download` are signed without the target URI. Every other path gets the default components:

```kotlin
import io.approov.service.okhttp.ApproovDefaultMessageSigning
import io.approov.service.okhttp.ApproovDefaultMessageSigning.OkHttpComponentProvider
import io.approov.service.okhttp.ApproovDefaultMessageSigning.SignatureParametersFactory
import io.approov.service.okhttp.ApproovRequestMutations
import io.approov.service.okhttp.ApproovService
import io.approov.util.okhttp.sig.ComponentProvider
import io.approov.util.okhttp.sig.SignatureParameters

class DownloadPathFactory : SignatureParametersFactory() {
    // the factory for download requests, whose only base component is the method
    private val downloadFactory = ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory(
        SignatureParameters().addComponentIdentifier(ComponentProvider.DC_METHOD))

    // the factory for every other request
    private val defaultFactory = ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()

    override fun buildSignatureParameters(provider: OkHttpComponentProvider,
                                          changes: ApproovRequestMutations): SignatureParameters? {
        // choose the delegate by the request path
        return if (provider.path.startsWith("/download")) {
            downloadFactory.buildSignatureParameters(provider, changes)
        } else {
            defaultFactory.buildSignatureParameters(provider, changes)
        }
    }
}

ApproovService.enableMessageSigning()
ApproovService.putMessageSigningHostFactory("example.api.com", DownloadPathFactory())
```

The same in Java:

```java
public class DownloadPathFactory extends ApproovDefaultMessageSigning.SignatureParametersFactory {
    // the factory for download requests, whose only base component is the method
    private final SignatureParametersFactory downloadFactory = ApproovDefaultMessageSigning
            .generateDefaultSignatureParametersFactory(
                    new SignatureParameters().addComponentIdentifier(ComponentProvider.DC_METHOD));

    // the factory for every other request
    private final SignatureParametersFactory defaultFactory =
            ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory();

    @Override
    public SignatureParameters buildSignatureParameters(OkHttpComponentProvider provider,
            ApproovRequestMutations changes) throws RequiredBodyDigestException {
        // choose the delegate by the request path
        if (provider.getPath().startsWith("/download")) {
            return downloadFactory.buildSignatureParameters(provider, changes);
        }
        return defaultFactory.buildSignatureParameters(provider, changes);
    }
}
```

Keep three things in mind when you write one:

* The installed factory decides which signatures are made, not the delegate that built the parameters. Here the installed factory is `DownloadPathFactory`. A newly constructed factory makes both the install and the account signature. If your delegates make only one, call the same method on the installed factory too, for example `DownloadPathFactory().setUseInstallMessageSigning()`. Otherwise the other signature is added as well. Parameters that set an explicit `alg` make that single signature, whatever the installed factory says.
* Returning `null` leaves the request unsigned. So does an override that throws, except for `RequiredBodyDigestException`, which fails the request. Let that exception propagate from a delegate configured with a required body digest.
* Each delegate is shared by every request it serves. Configure it once and do not change it afterwards.

### Verifying signatures on the backend

To verify the `account` signature, your backend needs the account message signing key. The `install` signature is verified with the public key carried in the Approov token.

The CLI prints the key with `approov secret -messageSigningKey get-base64url`. That command prints more than the key. A banner, a warning, the password prompt, the key ID and the encoding all come before the key line. Copy or capture only the key line, whether by hand or in a script. Anything else in the configured key makes every account signature fail to verify.

## Header names

You can change the name of each header the package adds. Passing `null` turns the trace or status header off:

```kotlin
ApproovService.setTokenHeader("Authorization", "Bearer ")   // default "Approov-Token", no prefix
ApproovService.setTraceIDHeader(null)                       // default "Approov-TraceID"; null disables
ApproovService.setStatusHeader("X-Approov-Fetch-Status")          // default "Approov-Status"; null disables
```

## About the Approov account ID

The string you pass to `initialize` looks like `#your-account#p6nZ...=`. The CLI and the SDK call it the SDK config string (`approov sdk -getConfigString`).

It names your account and carries a fingerprint of the account's public key. From it, the SDK knows which Approov service to attest against. It can also verify the configuration it downloads later.

The account ID is not a secret, and it does not rotate. The same value serves every app in the account. Registering an app is a separate step. It is not an API key either: knowing it grants nothing.

## Bypass initialization

Initialize with an empty string instead of the account ID to start the package in bypass mode. In bypass mode the package is enabled but protects nothing: `isApproovServiceEnabled()` is `true` and `isApproovProtectionEnabled()` is `false`. In this mode:

* requests through the `OkHttpClient` go out with no Approov processing. There is no token, signing, secure strings or Approov connection check, only OS trust.
* the methods that call the SDK throw `ApproovException` without calling it.

A request made before any `initialize` is handled the same way. It is neither failed nor held.

```java
ApproovService.initialize(context, "");
```

Bypass mode is a starting or fallback state. Use it, for example, while your app fetches the account ID remotely. The README example also uses it as a guard, in case the account ID does not reach the app intact.

A later `initialize` with the account ID turns Approov on at runtime, if the SDK accepts it. Configuration set before then is kept. Clients you already got from `getOkHttpClient()` protect their requests from then on.

Once protection is on, an `initialize` with an empty string is ignored. Initializing again with a different account ID is rejected by the SDK. Its exception reaches your code, and the package state is unchanged. See [initialize in the reference](REFERENCE.md#initialize).

## Initialize, then configure

Call `initialize` and check the result. It is synchronous, so there is nothing to wait for. Then make every configuration call your app needs, such as:

* the token, trace, status and binding headers
* substitution headers and query parameters
* exclusion regular expressions
* the service mutator
* message signing and its host factories
* the logging level
* the stale protection refresh period

Make these calls straight after `initialize`, in the same `Application.onCreate`, before the app sends protected requests. `initialize` never resets configuration, so calls made before it still apply. But configuring straight after it is the documented pattern.

Initialization can also be done for you, for example by a one-line native bootstrap in a React Native app. This is supported, but it configures nothing. It calls `initialize` with the account ID and comment, and that is all. Your app makes all the configuration calls afterwards.

### Requests before the configuration call

The package may process a request after `initialize` but before your configuration call. That request uses the defaults in force at the time:

* the `DEFAULT` service mutator (`CLOSE_FAILURE` in 3.7.0)
* the `Approov-Token` header with no prefix
* no binding header and no exclusions
* no substitutions, so a secure string placeholder goes out unchanged
* message signing off
* logging at `INFO`

A configuration change applies only to requests processed after the call. A request already in flight is not processed again.

Connection checks do not depend on this order. Their keys come from the Approov SDK's configuration, not from your configuration calls, and apply from initialization.

Requests that can run before your configuration call include:

* native code at startup, for example SDKs your app starts that use the package's client, and Android headless JS tasks in a React Native app
* JavaScript modules that fetch when they are imported, in a React Native app

Configure straight after `initialize`, before the app sends protected requests. Keep startup requests to protected hosts until after that point.

## Token binding

[Token binding](https://approov.io/docs/latest/approov-usage-documentation/#token-binding) ties the Approov token to the value of a header on the request, typically an OAuth `Authorization` header. A token then cannot be replayed with different credentials. Only a hash of the value reaches Approov. It appears as the `pay` claim of the token, which your backend checks.

```kotlin
ApproovService.setBindingHeader("Authorization")
```

Each request's token is bound to that request's own header value. This holds even when requests with different values are in flight at once, as during an OAuth token refresh.

The SDK holds one binding value for the whole app. So for every request that carries the binding header, the package sets the value and fetches the token as one step. It does this on the first attempt, and whenever the request's protection is reapplied: on a stale protection refresh, a redirect or an authenticator retry. Requests that carry no binding header are not held up by this.

`setDataHashInToken` binds arbitrary data instead. Never use both: the binding header overrides the data hash on every request.

## Secure strings

[Secure strings](https://approov.io/docs/latest/approov-usage-documentation/#secure-strings) let you remove API keys and other secrets from your app. Approov delivers them only to attested installations. In your request you put a secure string key where the secret goes, and the package substitutes the secret. Register the headers and query parameters whose values are secure string keys:

```kotlin
ApproovService.addSubstitutionHeader("X-Api-Key", null)        // whole value is the key
ApproovService.addSubstitutionHeader("Authorization", "Bearer ") // value after the prefix is the key
ApproovService.addSubstitutionQueryParam("api_key")
```

If a value's secure string fetch fails, the value is replaced by the fetch status in lowercase and the request goes ahead. This includes `unknown_key`, which means no secure string is defined for that key. See the [secure string table](#what-happens-by-default).

Header names are matched without regard to case. A header cannot be both a binding header and a substitution header. To fetch or define a secure string directly, use `fetchSecureString`.

### When placeholders stay in place

A secure string is only ever substituted into a request sent over TLS to an API domain you added to Approov. That domain is either token-protected (`SUCCESS`) or secrets-only (`UNPROTECTED_URL`, added with `-noApproovToken`).

These requests keep their placeholders, with no status written:

* a request to a host not added to Approov (`UNKNOWN_URL`)
* a cleartext `http` request
* an `https` request whose URL the SDK reports as `BAD_URL`, meaning a URL it cannot parse
* a request whose token fetch failed for any other reason

This holds under every mutator, `ALWAYS_PROCEED` and custom mutators included. No secure string is fetched for such a request, and the substitution hooks are not consulted. The request goes ahead as the token decision says.

A log line names the header or query parameter, never the value. It is a warning for a cleartext or `BAD_URL` request, because that is a configuration problem. Otherwise it is a DEBUG line (see [Diagnostics](#diagnostics)).

This matters under `ALWAYS_PROCEED`, which sends a request whatever its token fetch status. That includes a redirect from your API to an `http` URL. Under `CLOSE_FAILURE` a cleartext or `BAD_URL` request never gets this far: the SDK reports `BAD_URL` for it, and the request fails with `ApproovFetchStatusException`, as in 3.5.x.

### Secure strings and the OkHttp cache

A request with a secure string substituted into its query bypasses your OkHttp `Cache`. Its response is never stored, so the URL with the secret is never written to the cache on disk. The request always goes to the network.

To do this, the package marks the response `no-store` as it passes through the package's network interceptor, where OkHttp's cache reads it. Your code gets the server's own `Cache-Control` back. Only `networkResponse()` shows `no-store`, and nothing changes on the wire.

Each network attempt is checked against what it carried. So a redirect from such a URL is not stored, while a redirect target without a query secret is cached as usual.

Every other response is cached as OkHttp decides, including one to a request with a substituted header. **Do not let a server name a substituted header in `Vary`.** OkHttp would then store that header, and so its secure string, with the response.

## Excluding URLs

Requests whose URL matches an exclusion regular expression are sent untouched, without a token fetch:

```kotlin
ApproovService.addExclusionURLRegex("https://api\\.example\\.com/health.*")
```

Connection checks still apply to excluded requests on domains added to Approov.

The keys for those checks are updated only when a request that is not excluded fetches a token through the client, and the SDK reports a configuration change. A direct `fetchToken` call does not update them. So make sure your app keeps calling some URL that is not excluded on each protected domain.

An invalid regular expression throws `IllegalArgumentException` and adds nothing. The exception is unchecked, with the `PatternSyntaxException` as its cause. Every Approov package behaves this way.

## Connection validation

The pinning interceptor checks the TLS connection to each domain you have added to Approov. Pinning means a connection is accepted only if its certificate matches keys known in advance. The interceptor checks against the certificate public keys configured for the domain. For a domain with none of its own, it falls back to the Managed Trust Roots.

### How hosts are matched

Keys are looked up for the exact host, without regard to case and ignoring one trailing dot. So a domain added as `API.Example.com` applies to `api.example.com`. A request to `api.example.com.` is checked like one to `api.example.com`.

Approov API domains never contain wildcards, because the Approov admin API accepts only letters, digits, `.` and `-`. So no key is a pattern. The package passes the keys to OkHttp's `CertificatePinner`, which would read a `*.` key as a wildcard pattern, but such keys cannot occur.

The `*` entry is not a host. It holds the Managed Trust Roots, which are used for a listed domain with no keys of its own. They are never used for a domain that is not listed.

### Without Managed Trust Roots

Suppose a domain is added with no keys of its own, while the Managed Trust Roots are empty or absent. That domain is checked by OS trust only. This is a valid development setup.

It is not silent. The first connection to each such host logs a warning under `ApproovPinningInterceptor`, once per host. The warning names the host, never a key.

### Failures in the log

A connection whose certificate matches none of the domain's keys fails with `SSLPeerUnverifiedException`. An error line names the host, never a key.

A cleartext (`http`) request to a domain with keys fails the same way, with a warning.

If the keys cannot be read from the SDK, the connection fails closed, which means it is refused. It fails with `SSLPeerUnverifiedException` and the message `Approov pinning: pins unavailable for <host>`, with the SDK's exception as its cause. One error line names the host and the cause.

## Service mutators

An `ApproovServiceMutator` holds your app's own policy, so you don't need to fork the package. The package asks the installed mutator at each decision point. Every method has a default, so override only what you need.

The interceptor defaults are the `CLOSE_FAILURE` decisions (see [What happens by default](#what-happens-by-default)). So a custom mutator that should never abort a request delegates those decisions to `ApproovServiceMutator.ALWAYS_PROCEED`.

Mutators carry decisions only. None of them signs, and installing one never turns [message signing](#message-signing) on or off.

The hooks:

| Hook | Decides |
| :--- | :--- |
| `handleInterceptorShouldProcessRequest` | whether a request gets Approov processing at all (by default, every request that is not excluded) |
| `handleInterceptorFetchTokenResult` | given the token fetch result, whether to send the request with its Approov headers, send it without them, or abort it (see below) |
| `handleInterceptorHeaderSubstitutionResult`, `handleInterceptorQueryParamSubstitutionResult` | whether to apply a secure string substitution (see below) |
| `handleInterceptorProcessedRequest` | final changes to the processed request. Message signing, when on, runs after it and covers its changes |
| `supportsProtectionRefresh` | whether the processed request callback may run again on a stale request (see [Stale protection refresh](#stale-protection-refresh)) |
| `handlePinningShouldProcessRequest` | whether Approov connection checks (Managed Trust Roots or configured public keys) apply to a request |
| `handlePrecheckResult`, `handleFetchTokenResult`, `handleFetchSecureStringResult`, `handleFetchCustomJWTResult` | how the direct methods turn a result into an exception |

### The token fetch decision

`handleInterceptorFetchTokenResult` receives the token fetch result and gives one of three answers:

* `true` sends the request with the Approov headers its status allows. On `SUCCESS` those are the token, status and trace headers and the signatures. On a failure status, the status header alone. On `UNKNOWN_URL` or `UNPROTECTED_URL`, none.
* `false` sends the request with no Approov headers at all.
* throwing a standard network stack exception aborts the request.

For a failure status on a host not in the pin set, the answer is ignored and the request goes out untouched. The only exception is when your own code throws, which aborts the request. A `CLOSE_FAILURE` decision you inherit or call is ignored there.

The hook governs the Approov headers only. Secure strings follow the token fetch status. A `SUCCESS` or `UNPROTECTED_URL` request gets them whatever the hook returns, and a request with a failure status never does.

The interceptor sets the status header from the token fetch result whenever the hook returns `true` for `SUCCESS` or a failure status. The hook is asked about a failure status only for a host in the pin set.

The processed request callback, `handleInterceptorProcessedRequest`, runs for every request the package processes. That is a `SUCCESS` or `UNPROTECTED_URL` request, or a request with a failure status that the mutator lets go ahead to a host in the pin set.

### The substitution hooks

`handleInterceptorHeaderSubstitutionResult` and `handleInterceptorQueryParamSubstitutionResult` decide whether to apply a secure string substitution. They are asked only on a `SUCCESS` or `UNPROTECTED_URL` request over `https`.

* Both standard mutators return `true` only for `SUCCESS`, and never throw.
* Returning `false` on `SUCCESS` leaves the placeholder.
* On any other status there is no secure string to substitute. The request goes ahead with the status in place of the placeholder, whether the hook returns `false` or `true`.
* Your own mutator may throw a standard network stack exception to abort the request.

### Opting in to aborting requests

Under `ALWAYS_PROCEED`, every outcome goes ahead. Your app may decide that some outcome should not.

The common case is `NO_NETWORK`. The device could not reach Approov, and the API call is about to fail on the same network anyway. Your app may prefer to get a network error at once and show its offline state, rather than send a request that will not reach your backend.

That is your app's policy, so you express it by overriding the hook and throwing. Throw a standard network stack exception, not an Approov type: `java.io.IOException` or a platform subclass such as `java.net.ConnectException` or `javax.net.ssl.SSLException`. The abort then reaches your app's own error handling, retry logic and support tooling like any other network failure. The Approov status is still available from the result for your own logging.

The interceptor hooks declare `IOException` for this purpose. A mutator aborts a request only by throwing one.

Anything else a hook throws is treated as a failed request, never as a crash. That includes a `NullPointerException` or other programming error, and a deliberate `RuntimeException`. The request fails with an `ApproovException` whose cause is the original exception. The hook is named in its message and in an error-level log line. `execute()` throws it, and an enqueued call receives it in `onFailure`. Otherwise OkHttp would rethrow the exception on its dispatcher thread for an enqueued call, which would terminate your app.

An Approov exception your hook throws is treated the same way. That is an `ApproovException` or a subclass, whether your code built it or let it escape from a direct method such as `fetchToken` or `fetchSecureString`. It is your hook's failure, not an Approov decision. So the request fails with a plain `ApproovException` that names the hook, with yours as its cause.

The aborts that `CLOSE_FAILURE` makes keep the 3.5.x Approov exception types, so 3.7.0 does not change what an existing app catches. That also holds in a custom mutator that inherits those decisions or calls them, for example `ApproovServiceMutator.CLOSE_FAILURE.handleInterceptorFetchTokenResult(results, url)`. A decision your hook catches and rethrows unchanged keeps its type too. The standard substitution decisions, `closeFailureSubstitution` included, never throw.

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

Keep hooks fast and free of side effects, because they run on the request path.

The stale protection refresh may run a mutator's processed request callback again, but only if `supportsProtectionRefresh()` returns `true`. It does for `DEFAULT`, `CLOSE_FAILURE` and `ALWAYS_PROCEED`. A custom mutator that does not opt in gets no stale refresh at all.

## Stale protection refresh

A request can be held between Approov processing and sending, for example during a device doze period or in an app-level queue. It would then go out with an expired token or signature.

To prevent this, a network interceptor strips the request's protection and applies it again just before sending, if the request was held longer than the refresh period. The default period is 3000 ms. On a refresh:

* the token is fetched again, from the SDK cache if it is still valid
* the status header reflects the new result
* substitutions are redone
* with message signing on, the signatures are made again

```kotlin
ApproovService.setStaleProtectionRefreshPeriod(1000)   // <= 0 disables
```

The refresh runs on every network attempt, so OkHttp's own retries are refreshed too.

### Redirects and retries

A redirect is classified again for its new URL:

* a redirect to a host not added to Approov leaves with no Approov headers and no secrets
* a redirect to a secrets-only API carries its secure strings and nothing else
* a redirect within a token-protected domain is signed again over the new target

Each attempt is classified by its own token fetch:

* a refresh that reports `UNPROTECTED_URL` drops the token, status and trace headers and the signatures. It substitutes the secure strings afresh from your placeholders.
* one that reports `UNKNOWN_URL` drops everything and restores the placeholders.
* one that reports a failure the mutator lets go ahead carries the status header only.

Any attempt whose headers differ from the protected request is protected again the same way. The typical case is an OkHttp `Authenticator` retrying a `401` with a new `Authorization` header. So the signature always covers what is sent.

A header your app changed between attempts is kept and substituted afresh. Only headers still holding the value the package installed are restored to their placeholders. That value is either a secure string or a status written in place of the placeholder. The URL goes back to your placeholders before the secure strings are fetched again.

### Redirects to another origin

A redirect to another origin (a different scheme, host or port) is first stripped of everything the package added:

* the token, status and trace headers
* the signatures
* each substituted header value, a secure string or a status, which goes back to your placeholder

OkHttp itself drops `Authorization`.

The redirect is then handled from the start, as a new request for the target URL:

* a token is fetched for the target URL. It is never reused from the first host.
* secure strings are substituted only if the target is a token-protected or secrets-only `https` API.
* the signatures are computed only for a token-protected target.

A target not added to Approov goes out with none of it.

The redirect target URL is left exactly as the server sent it. That applies to the request that follows it, the redirect response's `Location` header, and `response.request().url()`.

If your server echoes a secure string into a redirect target, for example in a query parameter, the package does not restore your placeholder. The next hop receives what the server sent. **Do not let a server echo a secret into a redirect.** What it echoes is the server's responsibility.

The query of a redirect target is never looked up either. A parameter name may be one you added with `addSubstitutionQueryParam`, but its value is the server's. So no secure string is fetched for it, and no status is written into it.

On a protected target, only your own placeholders are substituted, the ones in your request headers. A query parameter placeholder is substituted again only on an attempt to the URL your request was protected for. Examples are an authenticator retry, or a `303` back to the same URL.

### Limits

Two limits apply, by design:

* Only requests that were given protection are classified again on a redirect. A request to a domain not in Approov that redirects into a protected domain arrives there without protection, and your backend rejects it. Add every domain your app calls to Approov, including legacy or alias domains that redirect.
* The package restores what it added, not what it replaced. Suppose your app sets its own `Signature` or `Signature-Input` headers on a request that Approov also signs. On a redirect, the value the package wrote is treated as your app's, and travels with it. Do not set those headers yourself on Approov-protected requests.

The package's network interceptors come first in the client, ahead of any your app adds, including through `setOkHttpClientBuilder`. So a network interceptor of yours, such as a logger or an inspector, only sees an attempt after its protection was stripped or refreshed. A redirect to a domain Approov does not protect never shows it the token, the status or the substituted secrets. This also means a request held inside one of your own network interceptors is not refreshed.

A refresh, a redirect, a retry and the connection validation hook all use the mutator installed at that moment, not the one that first processed the request. Install your mutator once, at startup, and do not replace it while requests are in flight.

## Diagnostics

The package never logs a token. For each fetch, it logs the [loggable form](https://approov.io/docs/latest/approov-usage-documentation/#loggable-tokens) of the result at debug level, which is off by default (see the logging levels below). The loggable form is the token's claims plus a short fragment of its signature. It cannot be turned back into a usable token.

The claim to look at is `arc`, the [Attestation Response Code](https://approov.io/docs/latest/approov-usage-documentation/#attestation-response-code). It encodes why attestation produced the result it did. For example:

```
D/ApproovTokenInterceptor: Token for https://api.example.com/v1/items: {"did":"...","exp":1757400000,"arc":"IXPSB7TRK26LXE3M","sip":"a1b2c3", ...}
```

The URL in the line is the request URL without its user info, query or fragment. So no query value, such as a substituted secure string or personal data, is logged.

To see these lines, set the level in a debug build:

```Java
ApproovService.setLoggingLevel(ApproovLogLevel.DEBUG);
```

Or enable debug logging for the `ApproovService` tag, with no rebuild. This works on any installed build, including a release one. It raises any level except `OFF` to `DEBUG`, for all of the package's tags. It lasts until you set it back or the device reboots:

```sh
adb shell setprop log.tag.ApproovService DEBUG
```

To decode an `arc`, ask the CLI for the decoding command once. It prints a `curl` command with your account's API key filled in and an `<arc>` placeholder:

```sh
approov token -showArcInfoCurl
```

```
curl -H "Authorization: <api-key>" -H "Arc: <arc>" https://<management-url>/arc-info/
```

Run it with the `arc` value from the log in place of `<arc>`. The response lists the flags behind the result, for example `emulator` or `app-not-registered`.

Your account's [live metrics](https://approov.io/docs/latest/approov-usage-documentation/#metrics-graphs) show the same reasons in aggregate, within a minute. While you work, a [development signing certificate](https://approov.io/docs/latest/approov-usage-documentation/#development-app-signing-certificates) lets debug builds and emulators pass attestation.

### Undelivered secrets and failing requests

In these cases the package writes a line at debug level only, and nothing at `INFO` or above:

* It does not deliver a secure string because its fetch returned a status other than `SUCCESS`. The status is sent in place of the placeholder.
* It does not deliver a secure string because the request's token fetch failed, or its host is not added to Approov. The placeholder is left.
* It lets a request go ahead on a token fetch failure. The request carries the status header only, or no Approov header because your mutator said so. For a host not in the pin set it goes out untouched, and an abort by a standard decision is ignored.

The line names the header or query parameter, never its value. It gives the fetch status, with the ARC and the rejection reasons when the SDK supplies them. For example:

```
D/ApproovTokenInterceptor: Approov token fetch for api.example.com: REJECTED, ARC IXPSB7TRK26LXE3M, rejection reasons root, proceeding with the status header only
D/ApproovTokenInterceptor: Secure string not substituted in header Api-Key: token fetch status REJECTED, ARC IXPSB7TRK26LXE3M, rejection reasons root, placeholder left
D/ApproovTokenInterceptor: Secure string not substituted in query parameter api_key: secure string fetch status NO_NETWORK, status sent in its place
```

Three cases are configuration problems, so they stay warnings, visible at the default level:

* a placeholder left because the request is not `https`
* a placeholder left because the SDK reports the URL as `BAD_URL`
* a secure string value that a header or URL cannot carry

### Log tags and levels

The `Approov-TraceID` header is a debug header; pass it through unchanged.

The package writes its log output under the tags `ApproovService`, `ApproovTokenInterceptor`, `ApproovFreshness`, `ApproovPinningInterceptor` and `ApproovMsgSign`. [setLoggingLevel](REFERENCE.md#setlogginglevel) controls what is written:

* `ApproovLogLevel.ERROR` writes errors only
* `WARNING` adds warnings
* `INFO`, the default, adds information lines such as the initialization result
* `DEBUG` adds the debug lines
* `OFF` writes nothing, errors included

The loggable token carries the device ID and the client IP claims, and `getDeviceID` logs the device ID. So both are debug lines, and at the default level a release build writes neither.

You can [check](https://approov.io/docs/latest/approov-usage-documentation/#loggable-tokens) the loggable form of each fetched token with the Approov CLI. No token, secure string value, signature, query or substituted URL is ever logged.

To log rejections, prefer your backend's response over calling `getLastARC()` on the device. Your backend saw the token and the `Approov-Status` header. The device value may belong to a later attestation than the request that failed.
