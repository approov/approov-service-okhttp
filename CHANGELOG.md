# Changelog

All notable changes to this package will be documented in this file.

The format is based on Keep a Changelog and this project adheres to Semantic Versioning.

## [3.7.0] - Unreleased

This release works with the Approov SDK 3.7.0. The 3.5.x line keeps its current behaviour.

By default 3.7.0 makes the same token decisions and throws the same exceptions as 3.5.x, with two differences. A secure string that cannot be fetched no longer fails the request: its status is sent in place of the placeholder. A request sent without a token no longer carries an empty token header.

To always send requests and let your backend decide, install `ApproovServiceMutator.ALWAYS_PROCEED`. Message signing is now a single call. Both are planned to become the default in 4.0.0. Signing will still be possible to turn off. `initialize` never resets your configuration.

Entries marked **Breaking** need a source change, as does everything under Removed. [Upgrading from 3.5.x](README.md#upgrading-from-35x) in the README lists what to check first.

### Changed
- `ApproovServiceMutator` now offers two sets of decisions. `CLOSE_FAILURE` keeps the 3.5.x token decisions and exceptions, and is what `DEFAULT` and `setServiceMutator(null)` give you in 3.7.0. `ALWAYS_PROCEED` never aborts: the request goes out with the reason in `Approov-Status`. `DEFAULT` is planned to become `ALWAYS_PROCEED` in 4.0.0, so name one explicitly if you want your behaviour fixed. See [what happens by default](ADVANCED.md#what-happens-by-default).
- **Breaking:** The interceptor hooks of `ApproovServiceMutator`, `handlePinningShouldProcessRequest` included, now declare `IOException`. To abort a request from your own mutator, throw a standard exception such as `IOException`. An override that calls the interface default (`ApproovServiceMutator.super...`) must declare or catch `IOException`. See [opting in to aborting requests](ADVANCED.md#opting-in-to-aborting-requests).
- A request sent after a failed token fetch no longer carries the empty `Approov-Token` header that 3.5.8 added. It carries only `Approov-Status`, with no token, trace ID, secure strings or signatures. This covers `NO_APPROOV_SERVICE` under `CLOSE_FAILURE` and every failure under `ALWAYS_PROCEED`.
- A failed token fetch now only affects requests to your Approov API domains, the hosts in your account's pin set. A request to any other host is sent unchanged, so under `CLOSE_FAILURE` a `NO_NETWORK`, `REJECTED` or `BAD_URL` fetch for a third-party host no longer fails it. While the pin set is empty or cannot be read, every host is treated this way. Your own mutator can still abort such a request by throwing; see [service mutators](ADVANCED.md#service-mutators).
- A failed secure string fetch no longer fails the request under `CLOSE_FAILURE` or `ALWAYS_PROCEED`. 3.5.x threw `ApproovNetworkException` or `ApproovFetchStatusException`. A custom mutator can still abort by throwing from its substitution hooks. The standard decision is available to custom mutators as `ApproovServiceMutator.closeFailureSubstitution`.
- When a secure string fetch fails, the placeholder is replaced by the status in lowercase, such as `rejected`, `unknown_key` or `no_network`. A required prefix stays, so a header becomes `Authorization: Bearer rejected`. If your backend checks the value, it must expect these status values. See [secure strings](ADVANCED.md#secure-strings) for when the placeholder is left unchanged instead.
- `ApproovRequestMutations` now lists only the headers and query parameters that received a secure string.
- On a redirect, query values in the server's `Location` URL are never treated as placeholders. The redirect target is sent exactly as the server gave it.
- Every occurrence of a substitution query parameter is now substituted, each with its own secure string. Before, only the first was, so in `?key=PH1&key=PH2` the second placeholder was sent unchanged. A fragment after the query no longer runs into the last parameter's value.
- A secure string the package does not deliver, and a request sent after a failed token fetch, are logged at `DEBUG` only. The line names the header or query parameter, never the value. Configuration problems stay warnings: a placeholder left on a cleartext or `BAD_URL` request, or a secure string a header or URL cannot carry.
- **Breaking:** Message signing is now a switch, off by default, and no longer a service mutator. Turn it on with `ApproovService.enableMessageSigning()`, which adds both the install and the account signature. Replace `setServiceMutator(new ApproovDefaultMessageSigning().setDefaultFactory(factory))` with `ApproovService.enableMessageSigning(factory)`.
- Message signing runs after your mutator and works the same under every mutator. `setServiceMutator` and `initialize` never turn it on or off. A signature that cannot be produced is left out and the request still goes. Signing is planned to be on by default from 4.0.0, and can still be turned off. See [message signing](ADVANCED.md#message-signing).
- **Breaking:** `SignatureParametersFactory.buildSignatureParameters` is now public, as is `ApproovDefaultMessageSigning.OkHttpComponentProvider`. A factory subclass can now hand a request to a factory from `generateDefaultSignatureParametersFactory`, for example to leave `@target-uri` out on some paths. An existing override declared `protected` must be declared `public`. See [custom factory](ADVANCED.md#custom-factory).
- `SignatureParametersFactory` adds `setUseInstallAndAccountMessageSigning()`, the new default of `generateDefaultSignatureParametersFactory()`, plus `getAlgs()` and `setAddApproovStatusHeader(boolean)`. `setUseInstallMessageSigning()` and `setUseAccountMessageSigning()` now select one signature only. A bare `new SignatureParametersFactory()` produces both signatures unless you select one. The factory no longer sets `alg` on the built `SignatureParameters`; a subclass that sets one gets that single signature.
- **Breaking:** The message signing helper classes moved from `io.approov.util.http.sfv` and `io.approov.util.sig` to `io.approov.util.okhttp.http.sfv` and `io.approov.util.okhttp.sig`. Update imports of classes such as `SignatureParameters` and `ComponentProvider`. Two Approov Android packages in one app no longer fail R8 with duplicate classes.
- BouncyCastle is replaced by Google Tink 1.23.0, bundled in the AAR and relocated under `io.approov.util.okhttp`. It cannot clash with your own Tink, Gson or BouncyCastle, or with another Approov package, and the published POM adds no dependency. A malformed install signature is now left out rather than converted wrongly. The release AAR shrinks from 5.7 MB to 3.4 MB.
- An empty request body now gets a `Content-Digest`. Before, with the body digest set as required, every empty `POST` failed with `RequiredBodyDigestException`.
- Approov's network interceptors now run before any your app adds through `setOkHttpClientBuilder`. A logger or APM agent there no longer sees the token, status or secrets of the original host on a redirect to another host. Your network interceptors run after signing, so they must not change a signed header, the URL or the body. A request held inside one of them is not refreshed.
- A connection that fails validation is no longer closed. Only the request fails, with `SSLPeerUnverifiedException`. Closing it also failed requests to other hosts sharing the same HTTP/2 connection.
- A cleartext request to a host with pins now fails with `javax.net.ssl.SSLPeerUnverifiedException`, like a pin mismatch, instead of `ApproovNetworkException`. A cleartext request to a host with no pins is no longer blocked. The pins are read on first use if none were available yet, because the 3.7.0 SDK has them only after its first token fetch.
- A domain with no pins of its own, while your Managed Trust Roots are empty, is checked by OS trust only. The first connection to each such host now logs a warning. See [connection validation](ADVANCED.md#connection-validation).
- The 3.7.0 SDK no longer pins its own attestation channel, so a proxy on that path no longer blocks a token fetch. `MITM_DETECTED` is replaced by `UNTRUSTED_NETWORK`, a TLS trust failure, which the package treats as a network failure like `NO_NETWORK` and `POOR_NETWORK` (see `ApproovServiceMutator.isNetworkFailure`). Pinning of your own API domains is unchanged.
- `initialize` passes every non-empty account ID and its comment to the SDK unchanged, and the SDK judges repeat calls. A repeat call with the same account ID and comment returns at once, so any part of your app may call it. Any SDK exception reaches your code unchanged, for example `IllegalStateException` for a different account ID. A null context throws `IllegalArgumentException`. See [bypass initialization](ADVANCED.md#bypass-initialization).
- `initialize` never resets configuration. Settings made before or after it are kept, including headers, substitutions, exclusions, the mutator, message signing and client builders. In 3.5.x each successful `initialize` reset them, and settings made before the first call were ignored. `getSubstitutionHeaders`, `getSubstitutionQueryParams` and `getExclusionURLRegexs` no longer throw before `initialize`, and `initialize` no longer clears `getLastARC()`.
- `getOkHttpClient()` can be called before `initialize`; 3.5.x threw `IllegalStateException`. Until protection is enabled, requests are sent untouched, with no Approov headers, secure strings, pinning or SDK call. A client obtained earlier, or in bypass mode, protects its requests once `initialize` enables protection. See [initialize, then configure](ADVANCED.md#initialize-then-configure).
- Methods that call the SDK throw `ApproovException` with the message `<method>: Approov protection not enabled` before `initialize` and in bypass mode, without calling the SDK. The message was `<method>: SDK not initialized`. This applies to `setDevKey`, `precheck`, `getDeviceID`, `setDataHashInToken`, `fetchToken`, `getMessageSignature`, `getAccountMessageSignature`, `getInstallMessageSignature`, `fetchSecureString`, `fetchCustomJWT` and `setInstallAttributes`. Requests never fail for this reason.
- `addExclusionURLRegex` throws `IllegalArgumentException` for an invalid regular expression and adds nothing. 3.5.x logged the error, so the matching requests were protected after all. The exception is unchecked, so existing code still compiles.
- Logging now has a level, `INFO` by default (see Added). The loggable token and the device ID from `getDeviceID` are debug lines, so release builds no longer write them. The token line shows the URL without its query, fragment or user info.
- Log and exception messages now read the same as in the other Approov 3.7.0 packages, in the form `context: detail`. If your app matches on message text, check these:
  - a `CLOSE_FAILURE` token failure names the host only, for example `Approov token fetch for api.example.com: REJECTED`
  - a rejection from a direct method reads `fetchCustomJWT: REJECTED, ARC <arc>, rejection reasons <reasons>`
  - an SDK exception reads `Approov SDK <call> failed: <exception>` (it was `Wrapped <exception>`), and a fetch with no result `Approov SDK <call> returned no result`
  - `getAccountMessageSignature: no signature available` and `getInstallMessageSignature: no signature available`
  - `Message signing: unsupported algorithm identifier: <alg>` and `Message signing: required body digest not generated`
  - `initialize`, `setLoggingLevel`, `setBindingHeader` and `addSubstitutionHeader` name the method in their `IllegalArgumentException`
  - debug lines name the host rather than the URL, and give the status by name, such as `REJECTED`
- Some log levels changed to match the other packages. An empty account ID ignored after protection is enabled is logged as info. A cleartext request to a pinned host is a warning. A pin mismatch, and pins that cannot be read, are errors that name the host only.
- The package needs OkHttp 4.12 or later and depends on 4.12.0. OkHttp 3.x is not supported.
- The README and ADVANCED.md now describe one integration order: call `initialize`, check the result, then make your configuration calls before sending protected requests. See [initialize, then configure](ADVANCED.md#initialize-then-configure).

### Deprecated
- `ApproovService.getMessageSignature(message)` still returns the same value as `getAccountMessageSignature(message)`. Its first call now logs a deprecation warning. Use `getAccountMessageSignature`.
- `setApproovHeader`, `setApproovTraceIDHeader`, `getApproovTokenHeader`, `getApproovTokenPrefix` and `getApproovTraceIDHeader` still work. Use the names shared by all Approov packages: `setTokenHeader(header, prefix)`, `getTokenHeader()`, `getTokenPrefix()`, `setTraceIDHeader(header)` and `getTraceIDHeader()`.

### Added
- `ApproovServiceMutator.isNetworkFailure(status)` and `ApproovServiceMutator.closeFailureSubstitution(result, what)`, static helpers for custom mutators. The first tells you whether a status is a network failure. The second is the secure string decision both standard mutators use: `true` only for `SUCCESS`.
- The `Approov-Status` header carries the token fetch status in lowercase (`success`, `no_network`, `rejected`, ...) on every request Approov processes. Your backend can use it to see why a request has no valid token. Rename it with `setStatusHeader(header)`, read it with `getStatusHeader()` and turn it off with `setStatusHeader(null)`. See [the `Approov-Status` header](ADVANCED.md#the-approov-status-header).
- `ApproovServiceMutator.CLOSE_FAILURE` and `ApproovServiceMutator.ALWAYS_PROCEED` (see Changed). `ApproovServiceMutator.DEFAULT` refers to `CLOSE_FAILURE`.
- `ApproovService.enableMessageSigning()`, `enableMessageSigning(SignatureParametersFactory)`, `disableMessageSigning()`, `isMessageSigningEnabled()` and `putMessageSigningHostFactory(host, factory)` (see Changed).
- `ApproovService.setLoggingLevel(ApproovLogLevel)` with the levels `OFF`, `ERROR`, `WARNING`, `INFO` and `DEBUG`. The default is `INFO`, `OFF` writes nothing, and `null` throws `IllegalArgumentException`. Running `adb shell setprop log.tag.ApproovService DEBUG` raises any level except `OFF` to `DEBUG` without a rebuild; see [diagnostics](ADVANCED.md#diagnostics).
- Two debug lines. One shows a request sent without Approov headers after a failed token fetch. The other shows an abort ignored for a host that is not an Approov API domain.
- `isApproovServiceEnabled()`, true once any `initialize` has succeeded, bypass mode included. `isApproovProtectionEnabled()`, true once the SDK is initialized and requests are protected. They replace `isInitialized()` and `isApproovEnabled()`.
- `setInstallAttributes(String)`, which replaces `setInstallAttrsInToken`. It throws `ApproovException`.
- `ApproovRequestMutations.getStatusHeaderKey()` and `getTokenHeaderPrefix()`, so a mutator can see the status header name and the token prefix used for a request.

### Removed
- `isInitialized()` and `isApproovEnabled()`. Use `isApproovServiceEnabled()` and `isApproovProtectionEnabled()`.
- `setProceedOnNetworkFail(boolean)` and `getProceedOnNetworkFail()`. To send requests when the token fetch hits a network failure, install `ApproovServiceMutator.ALWAYS_PROCEED` or your own mutator.
- `prefetch()`. The SDK manages prefetching itself.
- `setInstallAttrsInToken(String)`. Use `setInstallAttributes(String)`.
- `setUseApproovStatusIfNoToken` and `getUseApproovStatusIfNoToken`. The status is reported in `Approov-Status`, never in the token header.
- `setApproovInterceptorExtensions`, `getApproovInterceptorExtensions` and the `ApproovInterceptorExtensions` interface. Use `setServiceMutator` and `getServiceMutator` with an `ApproovServiceMutator`.
- From `ApproovDefaultMessageSigning`: the `ApproovServiceMutator` interface, the public constructor, `setDefaultFactory`, `putHostFactory`, `handleInterceptorProcessedRequest`, `supportsProtectionRefresh` and the deprecated `processedRequest(request, changes)`. Use `ApproovService.enableMessageSigning(factory)` and `putMessageSigningHostFactory(host, factory)`.
- The BouncyCastle dependency (`bcprov-jdk15to18`).
- `USAGE.md`, replaced by ADVANCED.md.

### Fixed
- Each request's token is now bound to that request's own binding header value. Before, two requests binding different values at once could get each other's token and be rejected. This could happen to the old and new OAuth token during a refresh.
- APIs added with `approov api -add <domain> -noApproovToken` get their secure strings again. Versions 3.5.2 to 3.5.8 sent their placeholders unchanged. These requests are pinned and get secure strings over `https`, but carry no token, status, trace or signature headers. Secure strings now follow the token fetch status (`SUCCESS` or `UNPROTECTED_URL`), not the answer of `handleInterceptorFetchTokenResult`.
- An `https` request the SDK answers with `BAD_URL` never receives a secure string, under any mutator. The placeholders stay and a warning names the header or query parameter. `CLOSE_FAILURE` still fails the request with `ApproovFetchStatusException`.
- An `ApproovException` thrown by your own mutator hook now fails the request with a plain `ApproovException` that names the hook, with yours as the cause. Before, it passed through and looked like an Approov decision. The exceptions raised by the standard `CLOSE_FAILURE` token decisions keep their 3.5.x types.
- A token or trace ID from the SDK that a header cannot carry now fails with `ApproovException("Approov header <name>: invalid character in SDK token")` (or `SDK trace ID`). Before, the message blamed your configuration. Current SDKs never produce such values.
- Message signing now covers `Content-Type` and `Content-Length` as OkHttp sends them. Before, an app-set header that differed from the body could fail verification, and a chunked body covered a `Content-Length` that was never sent.
- Each signed component is listed once. With `setTokenHeader("Authorization", "Bearer ")` and signing on, `authorization` appeared twice in `Signature-Input`, which a conforming verifier rejects.
- **Breaking:** `ApproovDefaultMessageSigning.RequiredBodyDigestException` is now an `ApproovException`, so a checked `IOException`, instead of an `IllegalStateException`. Before, it crashed apps that enqueue calls, because OkHttp rethrew it on its dispatcher thread. A `buildSignatureParameters` override that calls `super` must declare or handle it, and code that caught `IllegalStateException` must catch `IOException`. An unsupported signature algorithm also fails the request with `ApproovException`.
- A substitution query parameter key is matched literally. Before, the key `a.b` also substituted the parameter `axb`.
- A value your app supplies that a header cannot carry, such as a token prefix or a header set in `handleInterceptorProcessedRequest`, fails the request with `ApproovException("Approov header <name>: invalid character in value")`. The value never appears in the message or the log.
- A `RuntimeException` from your service mutator no longer crashes the app. The request fails with an `ApproovException` that names the hook and has the original as its cause, and an enqueued call receives it in `onFailure`. A `null` returned from `handleInterceptorProcessedRequest` fails the same way. An `IOException` your hook throws still reaches your app unchanged.
- An exception from the Approov SDK no longer escapes a request or a direct method as a `RuntimeException`, which could crash an enqueued call. It now fails with an `ApproovException` whose cause is the SDK's exception. If the pins cannot be read, connections fail closed with `SSLPeerUnverifiedException("Approov pinning: pins unavailable for <host>")`. A query substitution that does not form a valid URL fails with `ApproovException("Query parameter substitution: invalid URL")`. Message signing still fails open: a signature that cannot be produced is left out.
- Message signing signs the URL exactly as OkHttp sends it. Before, characters such as `|` in a query were re-encoded for the signature, so the backend could not verify it. The other derived components now follow RFC 9421 section 2.2. `@authority` keeps a non-default port, and `@query` always starts with `?`, so a request with no query is signed. `@query-param` signs the value percent-encoded, as sent.
- A stale protection refresh that finds the URL no longer protected removes the old token, status and signatures instead of sending them again. A header value your app changed between attempts, for example in an `Authenticator`, is kept and substituted afresh. See [stale protection refresh](ADVANCED.md#stale-protection-refresh).
- An OkHttp retry that reuses the original request object is refreshed again, instead of being sent with stale headers.
- The token header name and prefix are read together, so calling `setTokenHeader` while requests are in flight cannot mix the two.
- Repeated header values combined for signing keep the separator after an empty first value (`["", "value"]` becomes `", value"`), as RFC 9421 section 2.1 requires.
- The `SignatureParameters` copy constructor now copies the debug mode flag, so `setDebugMode(true)` applies to both signatures.
- `getLastARC()` no longer makes its own token fetch. It returns the ARC from the most recent fetch by this package, from a request or a direct method, with no network call. A direct method whose SDK call throws clears it.
- A header present with an empty value can be a signed component. Before, such a request went out unsigned.
- If a custom mutator returns `true` from a substitution hook for a result with no secure string, the status now replaces the placeholder. Before, a header got the literal `null` and a query parameter crashed the interceptor.

### Security
- Secure strings are never substituted into a cleartext (`http`) request, under any mutator. The placeholder stays and a warning names the header or query parameter. Under `ALWAYS_PROCEED`, 3.5.x could send the secret over plain HTTP, for example after a redirect to an `http` URL.
- A request whose query carries a substituted secure string is no longer stored in an OkHttp `Cache`, so the secret is never written to disk. Your app still sees the server's `Cache-Control`, and nothing changes on the wire. A response whose `Vary` names a substituted header is still stored with that header. See [secure strings](ADVANCED.md#secure-strings).
- A domain added with a trailing dot, such as `api.example.com.`, now pins requests to `api.example.com`, and the other way round. Before, both were checked by OS trust only.
- A substituted query parameter is never logged. The debug line for a redirect or an authenticator retry no longer includes the URL.
- Changes a `handleInterceptorProcessedRequest` callback makes to its `ApproovRequestMutations` no longer affect what the package strips on a redirect or signs. Before, clearing the token header key could send the token to a host Approov does not protect.
- A secure string that a header cannot carry, such as one with a control or non-ASCII character, keeps its placeholder and the request proceeds with a warning. Before, OkHttp threw an exception that quoted the secret and could crash an enqueued call. In a query parameter, a value OkHttp would not send unchanged (tab, LF, CR, `&` or `#`) keeps its placeholder; non-ASCII and DEL are still sent percent-encoded.
- Redirects and `Authenticator` retries are protected again for what they actually send. A redirect to a host Approov does not protect carries no Approov headers or secrets. A redirect within your API, including a `303` that changes the method, and a retry with a new `Authorization` are signed again. Before, the original headers, substituted secrets included, went with the redirect, and a retry kept the old signature.
- Every connection is checked against the current pins for its own host. Before, two hosts with the same certificate could share a cached result. A host whose own pins did not match could then be admitted. This affected 3.4.1 and later.
- The pins are rebuilt when the SDK reports `isForceApplyPins`, not only on a configuration change. Before, a pin update without a configuration change was ignored.

## [3.5.8] - 2026-07-16

### Added
- Stale protection refresh: a new network interceptor detects requests that were held between Approov protection being applied and actual transmission (for example by a device deep sleep or doze period, or an app-level request queueing/backoff mechanism) and refreshes the Approov token and any message signature immediately before the request is sent, instead of transmitting expired credentials. Since it operates per network attempt it also refreshes protection on OkHttp generated retries and redirect followups. Configurable via `ApproovService.setStaleProtectionRefreshPeriod()` (default 3000ms, `<=0` disables). Because a refresh reinvokes the mutator's `handleInterceptorProcessedRequest` callback, it is gated on the new `ApproovServiceMutator.supportsProtectionRefresh()` capability: the default mutator and `ApproovDefaultMessageSigning` support it, while custom mutator implementations are never reinvoked unless they opt in.
- Service-layer version is now baked into the AAR at build time via `BuildConfig.APPROOV_SERVICE_VERSION` and reported to the Approov SDK via `setUserProperty("approov-service-okhttp/X.Y.Z")` during initialization. Local builds report `dev`.
- Manual `Release to Maven` workflow for publishing an existing release tag from `main`. It validates the requested semantic-version tag, verifies that the tag belongs to `main`, and requires the top CHANGELOG entry to match both on `main` and at the tagged commit before publishing.

### Changed
- Android build migrated from the unmaintained `com.github.johnrengelman.shadow` 8.1.1 plugin to the maintained fork `com.gradleup.shadow` 8.3.11 for Gradle 9 compatibility (Gradle 9 removed `FileCopyDetails.mode`, making the old plugin fail with a `MissingPropertyException`). Shaded BouncyCastle jar verified byte-identical; minimum supported Gradle remains 8.3.
- Publish workflow now passes `-PapproovServiceVersion` to `assembleRelease`, keeping the runtime version in lockstep with the Maven artifact version.
- **Breaking:** the HTTP `Signature` header now encodes install and account signatures as RFC 9421 Structured Fields Byte Sequences (`install=:<base64>:` / `account=:<base64>:`), matching every other Approov service layer. Verifiers that only accept the legacy quoted-String form must be updated.

### Fixed
- **Message-signing fail-open conformance**: a body digest configured as *required* that cannot be generated now fails **closed** (aborting the request) instead of being silently skipped. All other signing failures (signature unavailable, base64 decode, ASN.1/DER decode, account-branch errors) continue to fail **open** (the request proceeds unsigned) but are now logged at **error** level for production visibility. Unsupported signing algorithms still fail closed.
- **Token binding header**: the binding header is now matched case-insensitively (HTTP header names are case-insensitive) and a present-but-empty value is forwarded to the SDK, while an absent header is skipped.
- **Substitution header configuration**: adding and removing secure-string substitution headers now matches names case-insensitively. Re-adding the same logical header replaces its configuration without creating duplicate entries.
- **Binding/substitution conflict**: configuring the same header for both token binding and secure-string substitution now fails fast with `IllegalArgumentException`, preventing a token from being bound to a placeholder that differs from the substituted value sent to the backend.
- **Trace ID header**: an empty trace ID returned by the SDK is now emitted as an empty header value rather than omitted, so the backend has evidence that Approov processing occurred.
- **`NO_APPROOV_SERVICE`**: the request now proceeds emitting an **empty** `Approov-Token` header (and a trace ID if the SDK provides one) as evidence that Approov processing occurred, instead of omitting the headers (root TESTING_REQUIREMENTS §2 Missing Artifacts Fallback). `UNKNOWN_URL`/`UNPROTECTED_URL` still send no headers.
- **REFERENCE.md**: corrected the deprecated `setApproovInterceptorExtensions` signature: its parameter is an `ApproovServiceMutator` (the legacy `ApproovInterceptorExtensions` interface is a deprecated subtype), not `ApproovInterceptorExtensions`.

## [3.5.7] - 2026-04-09

### Added
- Integrated a localized testing framework for comprehensive service layer verification.
- Added extensive test coverage for token management, pinning synchronization, and request mutation scenarios.
- Enhanced internal service components to improve testability.
- Added `ApproovService.isInitialized()` to expose the service-layer initialization state.
- Consumer ProGuard rules (`consumer-rules.pro`) to automatically preserve native SDK interfaces and internal cryptography bounds.

### Changed
- `setProceedOnNetworkFail()` and `getProceedOnNetworkFail()` are now obsolete no-ops. Mutator defaults dynamically enforce exceptions upon network drops.
- Shaded and relocated the BouncyCastle dependency (`io.approov.internal.okhttp.bouncycastle`) to prevent version collisions for consuming applications.
- Removed the transitive `org.bouncycastle:bcprov-jdk18on` dependency from `pom.xml`.
- Simplified `initialize`: removed the service-layer re-initialization guards (same-config short-circuit, `reinit` comment check). The service layer now always resets its own state and forwards non-empty config directly to the platform SDK. The SDK returns `false` if already initialized with the same config (service layer logs and continues), or throws `IllegalStateException` for a different config (service layer re-throws).

### Fixed
- Enforced SDK initialization gating across all public API endpoints (`fetchCustomJWT`, `getDeviceID`, `setDataHashInToken`, `setInstallAttrsInToken`, etc.) to prevent unhandled `IllegalStateException` crashes from the platform SDK when the service layer is operating in bypass/uninitialized mode.
- Prevented premature construction of the `ApproovPinningInterceptor` and immediate `getPins()` calls when the Approov service layer is initialized with an empty configuration string.
- Initializing with an empty config string now keeps the service layer initialized while returning a plain `OkHttpClient` without Approov processing.
- Initializing first with an empty config string and later with a valid non-empty config string now enables Approov at runtime instead of being rejected as a different-config reinitialization.
- `initialize` now explicitly throws `IllegalArgumentException` when `config` is `null`, with a clear message directing callers to pass `""` for bypass mode. Passing `null` previously caused a silent coercion to `""` which masked caller errors.
- The 2-arg `initialize(context, config)` overload now correctly passes `null` (not `""`) as the comment to the native SDK, preventing unexpected re-initialization mismatches on subsequent calls.


## [3.5.6] - 2026-02-11

### Added
- ApproovServiceMutator protocol with default behavior to centralize decision points in the service flow.
- Mutator hooks for precheck, token fetch, secure string fetch, custom JWT fetch, interceptor decisions, and pinning.
- REFERENCE.md & CHANGELOG.md & USAGE.md
- Added `setUseApproovStatusIfNoToken` to allow using status as token value when token is missing.
### Changed
- ApproovService now routes decision logic through the service mutator and exposes set/get APIs.
- Pinning logic is now applied via `ApproovPinningInterceptor` which checks `ApproovServiceMutator.handlePinningShouldProcessRequest`.
### Fixed
- Prevented exceptions when key-pair generation fails. The service now logs an error and continues without the install message signature, allowing the backend to decide whether to reject the request.
### Deprecated
- ApproovInterceptorExtensions in favor of ApproovServiceMutator.
- setProceedOnNetworkFail() and getProceedOnNetworkFail() in favor of ApproovServiceMutator.
- prefetch() is now automatically called when the service is initialized.
