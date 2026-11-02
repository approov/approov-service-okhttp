# Changelog

All notable changes to this package will be documented in this file.

The format is based on Keep a Changelog and this project adheres to Semantic Versioning.

## [3.7.0] - 2026-11-02

The current line maintains the behaviour of the 3.5.x versions. The main difference: a secure string that cannot be fetched no longer fails the request. Its status is sent in place of the placeholder.

To always send requests and let your backend decide, install `ApproovServiceMutator.ALWAYS_PROCEED`. Each `initialize` still resets your configuration, so configure after it.

Entries marked **Breaking** need a source change, as does everything under Removed. [Upgrading from 3.5.x](README.md#upgrading-from-35x) in the README lists what to check first.

### Changed
- `ApproovServiceMutator` now offers two sets of decisions. `CLOSE_FAILURE` keeps the 3.5.x token decisions and exceptions, and is what `DEFAULT`, `setServiceMutator(null)` and `initialize` give you currently. `ALWAYS_PROCEED` never aborts: a request without a token goes out with an empty `Approov-Token` header, or the status name with `setUseApproovStatusIfNoToken(true)`. See [what happens by default](ADVANCED.md#what-happens-by-default).
- **Breaking:** `handlePinningShouldProcessRequest` now declares `ApproovException`, like the other interceptor hooks. An override needs no change, but code that calls it, such as `ApproovServiceMutator.super.handlePinningShouldProcessRequest(request)`, must declare or catch `ApproovException`.
- A request that goes ahead after a failed token fetch keeps its secure string placeholders. No secure string is fetched for it. This covers `NO_APPROOV_SERVICE` under `CLOSE_FAILURE` and every failure under `ALWAYS_PROCEED`. It still carries the token header and any trace header.
- `ApproovRequestMutations` now lists only the headers and query parameters that received a secure string.
- On a redirect, query values in the server's `Location` URL are never treated as placeholders. The redirect target is sent exactly as the server gave it.
- Every occurrence of a substitution query parameter is now substituted, each with its own secure string. Before, only the first was, so in `?key=PH1&key=PH2` the second placeholder was sent unchanged. A fragment after the query no longer runs into the last parameter's value.
- A secure string the package does not deliver, and a request sent after a failed token fetch, are logged at `DEBUG` only. The line names the header or query parameter, never the value. Configuration problems stay warnings: a placeholder left on a cleartext or `BAD_URL` request, or a secure string a header or URL cannot carry.
- The default signature parameters factory still produces the install signature only. `SignatureParametersFactory` adds `setUseInstallAndAccountMessageSigning()` to produce the account signature as well, as the members `install` and `account` of the same `Signature` and `Signature-Input` headers, plus `getAlgs()`. `setUseInstallMessageSigning()` and `setUseAccountMessageSigning()` select one signature. The factory no longer sets `alg` on the built `SignatureParameters`; a subclass that sets one gets that single signature. See [message signing](ADVANCED.md#message-signing).
- Message signatures take their `created` and `expires` times from `ApproovService.getServerAdjustedCurrentTimeMS()` (see Added).
- `ApproovDefaultMessageSigning.putHostFactory` matches the host name without regard to case and without the port. `putHostFactory(host, null)` removes a host factory.
- **Breaking:** The message signing helper classes moved from `io.approov.util.http.sfv` and `io.approov.util.sig` to `io.approov.util.okhttp.http.sfv` and `io.approov.util.okhttp.sig`. Update imports of classes such as `SignatureParameters` and `ComponentProvider`. Two Approov Android packages in one app no longer fail R8 with duplicate classes.
- BouncyCastle is replaced by Google Tink 1.23.0, bundled in the AAR and relocated under `io.approov.util.okhttp`. It cannot clash with your own Tink, Gson or BouncyCastle, or with another Approov package, and the published POM adds no dependency. A malformed install signature is now left out rather than converted wrongly. The release AAR shrinks from 5.7 MB to 3.4 MB.
- An empty request body now gets a `Content-Digest`. Before, with the body digest set as required, every empty `POST` failed with `RequiredBodyDigestException`.
- A connection that fails validation is no longer closed. Only the request fails, with `SSLPeerUnverifiedException`. Closing it also failed requests to other hosts sharing the same HTTP/2 connection.
- The pins are read on first use if none were available yet.
- A domain with no pins of its own, while your Managed Trust Roots are empty, is checked by OS trust only. The first connection to each such host now logs a warning. See [connection validation](ADVANCED.md#connection-validation).
- `initialize` with a null context throws `IllegalArgumentException("initialize: null context")`. An empty account ID while protection is enabled is ignored and resets nothing. Every other `initialize`, a repeat with the same account ID included, resets the configuration as in 3.5.x. That now also covers `setUseApproovStatusIfNoToken` and clears `getLastARC()`, while the logging level (see Added) is kept. See [initialize, then configure](ADVANCED.md#initialize-then-configure).
- `getOkHttpClient()` can be called before `initialize`; 3.5.x threw `IllegalStateException`. Until protection is enabled, requests are sent untouched, with no Approov headers, secure strings, pinning or SDK call. A client obtained earlier, or in bypass mode, protects its requests once `initialize` enables protection. See [initialize, then configure](ADVANCED.md#initialize-then-configure).
- Logging now has a level, `INFO` by default (see Added). The loggable token and the device ID from `getDeviceID` are debug lines, so release builds no longer write them. The token line shows the URL without its query, fragment or user info.
- Some exception messages changed. If your app matches on message text, check these:
  - `Message signing: unsupported algorithm identifier: <alg>` and `Message signing: required body digest not generated`
  - a fetch with no result from the SDK: `Approov SDK <call> returned no result`
  - `initialize` with a null context, and `setLoggingLevel(null)`, name the method in their `IllegalArgumentException`
  - debug lines name the host rather than the URL, and give the status by name, such as `REJECTED`
- Some log levels changed. An empty account ID ignored after protection is enabled is logged as info. A pin mismatch, and pins that cannot be read, are errors that name the host only.
- **Breaking:** The package now depends on OkHttp 5.3.2, where 3.5.x depended on 4.12.0. OkHttp 5 tries IPv6 and IPv4 addresses side by side, so your app's requests and the Approov SDK's connect on networks that drop IPv6. Apps that compile Kotlin need Kotlin 2.1 or later. Keep your other OkHttp libraries on the same version with the OkHttp BOM, or stay on OkHttp 4.12 or later with `enforcedPlatform`. OkHttp 3.x is not supported. See [OkHttp version](ADVANCED.md#okhttp-version).
- The README and ADVANCED.md now describe one integration order: call `initialize`, check the result, then make your configuration calls before sending protected requests. See [initialize, then configure](ADVANCED.md#initialize-then-configure).

### Deprecated
- `ApproovService.getMessageSignature(message)` still returns the same value as `getAccountMessageSignature(message)`. Its first call now logs a deprecation warning. Use `getAccountMessageSignature`.
- `setApproovHeader`, `setApproovTraceIDHeader`, `getApproovTokenHeader`, `getApproovTokenPrefix` and `getApproovTraceIDHeader` still work. Use the names shared by all Approov packages: `setTokenHeader(header, prefix)`, `getTokenHeader()`, `getTokenPrefix()`, `setTraceIDHeader(header)` and `getTraceIDHeader()`.

### Added
- `ApproovService.getServerAdjustedCurrentTimeMS()`, the current time corrected to the Approov servers' clock. Message signatures now take their `created` and `expires` times from it, so a device whose clock is wrong still signs within your backend's window.
- `ApproovServiceMutator.isNetworkFailure(status)` and `ApproovServiceMutator.closeFailureSubstitution(result, what)`, static helpers for custom mutators. The first tells you whether a status is a network failure. The second is the secure string decision both standard mutators use: `true` only for `SUCCESS`.
- `ApproovServiceMutator.CLOSE_FAILURE` and `ApproovServiceMutator.ALWAYS_PROCEED` (see Changed). `ApproovServiceMutator.DEFAULT` refers to `CLOSE_FAILURE`.
- `ApproovService.setLoggingLevel(ApproovLogLevel)` with the levels `OFF`, `ERROR`, `WARNING`, `INFO` and `DEBUG`. The default is `INFO`, `OFF` writes nothing, and `null` throws `IllegalArgumentException`. `initialize` does not reset it. Running `adb shell setprop log.tag.ApproovService DEBUG` raises any level except `OFF` to `DEBUG` without a rebuild; see [diagnostics](ADVANCED.md#diagnostics).
- Two debug lines. One shows a request sent after a failed token fetch, with an empty token header, the status, or no Approov header. The other shows an abort ignored for a host that is not an Approov API domain.
- `isApproovServiceEnabled()` and `isApproovProtectionEnabled()`, the names shared by all Approov packages for `isInitialized()` and `isApproovEnabled()`, which still work.
- `setInstallAttributes(String)`, the shared name for `setInstallAttrsInToken(String)`, which still works.
- `setTokenHeader`, `getTokenHeader`, `getTokenPrefix`, `setTraceIDHeader` and `getTraceIDHeader` (see Deprecated).
- `ApproovRequestMutations.getTokenHeaderPrefix()`, so a mutator can see the token prefix used for a request.

### Removed
- `setProceedOnNetworkFail(boolean)` and `getProceedOnNetworkFail()`, which had no effect in 3.5.x. To send requests when the token fetch hits a network failure, install `ApproovServiceMutator.ALWAYS_PROCEED`, turn on `setUseApproovStatusIfNoToken(true)`, or write your own mutator.
- `prefetch()`. The SDK manages prefetching itself.
- `setApproovInterceptorExtensions`, `getApproovInterceptorExtensions` and the `ApproovInterceptorExtensions` interface. Use `setServiceMutator` and `getServiceMutator` with an `ApproovServiceMutator`.
- The BouncyCastle dependency (`bcprov-jdk15to18`).
- `USAGE.md`, replaced by ADVANCED.md.

### Fixed
- A failed secure string fetch no longer fails the request under `CLOSE_FAILURE` or `ALWAYS_PROCEED`. 3.5.x threw `ApproovRejectionException`, `ApproovNetworkException` or `ApproovFetchStatusException`. The status name, unchanged, is written in place of the placeholder instead, such as `REJECTED`, `UNKNOWN_KEY` or `NO_NETWORK`. A required prefix stays, so a header becomes `Authorization: Bearer REJECTED`. If your backend checks the value, it must expect these status values. See [secure strings](ADVANCED.md#secure-strings) for when the placeholder is left unchanged instead. A custom mutator can still abort by throwing from its substitution hooks. The standard decision is available to custom mutators as `ApproovServiceMutator.closeFailureSubstitution`.
- A failed token fetch now only affects requests to your Approov API domains, the hosts in your account's pin set. A request to any other host is sent unchanged, so under `CLOSE_FAILURE` a `NO_NETWORK`, `REJECTED` or `BAD_URL` fetch for a third-party host no longer fails it. While the pin set is empty or cannot be read, every host is treated this way. Your own mutator can still abort such a request by throwing; see [service mutators](ADVANCED.md#service-mutators).
- An exception your own mutator hook throws, an `ApproovException` included, now fails the request with a plain `ApproovException` that names the hook (`ApproovServiceMutator.<hook> failed: <exception>`), with yours as the cause. Before, a `RuntimeException` crashed apps that enqueue calls, because OkHttp rethrew it on its dispatcher thread, and an `ApproovException` looked like an Approov decision. A `null` returned from `handleInterceptorProcessedRequest` fails the same way. The exceptions raised by the standard `CLOSE_FAILURE` token decisions, and by message signing, keep their types. See [opting in to aborting requests](ADVANCED.md#opting-in-to-aborting-requests).
- An exception from the Approov SDK no longer escapes a request or a direct method as a `RuntimeException`, which could crash an enqueued call. It now fails with an `ApproovException` whose cause is the SDK's exception (`Wrapped <class>: <message>`). If the pins cannot be read, connections fail closed with `SSLPeerUnverifiedException("Approov pinning: pins unavailable for <host>")`. A query substitution that does not form a valid URL fails with `ApproovException("Query parameter substitution: invalid URL")`. Message signing still fails open: a signature that cannot be produced is left out.
- `getLastARC()` no longer makes its own token fetch. It returns the ARC from the most recent fetch by this package, from a request or a direct method, with no network call. `initialize` clears it, and so does a direct method whose SDK call throws.
- A WebSocket upgrade request (`Upgrade: websocket`) is sent untouched, with no token fetch, Approov headers, secure strings or signature, because OkHttp does not run the pinning interceptor for a WebSocket connection. So nothing Approov issues travels over a connection that was not pinned. See [WebSockets](ADVANCED.md#websockets).
- Cleartext requests are no longer blocked by the pinning interceptor. In 3.5.x every cleartext request through the client failed with `ApproovNetworkException`. The token fetch now decides: the SDK answers `BAD_URL` for a cleartext URL, so `CLOSE_FAILURE` fails a cleartext request to one of your Approov API domains with `ApproovFetchStatusException`, and `ALWAYS_PROCEED` sends it without secure strings. A cleartext request to any other host is never blocked.
- **Breaking:** `ApproovDefaultMessageSigning.RequiredBodyDigestException` is now an `ApproovException`, so a checked `IOException`, instead of an `IllegalStateException`. Before, it crashed apps that enqueue calls, because OkHttp rethrew it on its dispatcher thread. A `buildSignatureParameters` override that calls `super` must declare or handle it, and code that caught `IllegalStateException` must catch `IOException`. An unsupported signature algorithm also fails the request with `ApproovException`.
- Each request's token is now bound to that request's own binding header value. Before, two requests binding different values at once could get each other's token and be rejected. This could happen to the old and new OAuth token during a refresh.
- APIs added with `approov api -add <domain> -noApproovToken` get their secure strings again. Versions 3.5.2 to 3.5.8 sent their placeholders unchanged. These requests are pinned and get secure strings over `https`, but carry no token, trace or signature headers. Secure strings now follow the token fetch status (`SUCCESS` or `UNPROTECTED_URL`), not the answer of `handleInterceptorFetchTokenResult`.
- An `https` request the SDK answers with `BAD_URL` never receives a secure string, under any mutator. The placeholders stay and a warning names the header or query parameter. `CLOSE_FAILURE` still fails the request with `ApproovFetchStatusException`.
- A token or trace ID from the SDK that a header cannot carry now fails with `ApproovException("Approov header <name>: invalid character in SDK token")` (or `SDK trace ID`). Before, the message blamed your configuration.
- Message signing now covers `Content-Type` and `Content-Length` as OkHttp sends them. Before, an app-set header that differed from the body could fail verification, and a chunked body covered a `Content-Length` that was never sent.
- Each signed component is listed once. With `setTokenHeader("Authorization", "Bearer ")` and signing on, `authorization` appeared twice in `Signature-Input`, which a conforming verifier rejects.
- A substitution query parameter key is matched literally. Before, the key `a.b` also substituted the parameter `axb`.
- A value your app supplies that a header cannot carry, such as a token prefix or a header set in `handleInterceptorProcessedRequest`, fails the request with `ApproovException("Approov header <name>: invalid character in value")`. The value never appears in the message or the log.
- Message signing signs the URL exactly as OkHttp sends it. Before, characters such as `|` in a query were re-encoded for the signature, so the backend could not verify it. The other derived components now follow RFC 9421. `@authority` keeps a non-default port, and `@query` always starts with `?`, so a request with no query is signed. `@query-param` signs the value percent-encoded, as sent.
- A stale protection refresh that finds the URL no longer protected removes the old token and signatures instead of sending them again. A header value your app changed between attempts, for example in an `Authenticator`, is kept and substituted afresh. See [stale protection refresh](ADVANCED.md#stale-protection-refresh).
- An OkHttp retry that reuses the original request object is refreshed again, instead of being sent with stale headers.
- The token header name and prefix are read together, so calling `setTokenHeader` while requests are in flight cannot mix the two.
- Repeated header values combined for signing keep the separator after an empty first value (`["", "value"]` becomes `", value"`), as RFC 9421 requires.
- The `SignatureParameters` copy constructor now copies the debug mode flag.
- A header present with an empty value can be a signed component. Before, such a request went out unsigned.
- If a custom mutator returns `true` from a substitution hook for a result with no secure string, the status now replaces the placeholder. Before, a header got the literal `null` and a query parameter crashed the interceptor.

### Request handling
- Secure strings are never substituted into a cleartext (`http`) request, under any mutator. The placeholder stays and a warning names the header or query parameter.
- A request whose query carries a substituted secure string is no longer stored in an OkHttp `Cache`. Your app still sees the server's `Cache-Control`, and nothing changes on the wire. A response whose `Vary` names a substituted header is still stored with that header. See [secure strings](ADVANCED.md#secure-strings).
- A domain added with a trailing dot, such as `api.example.com.`, now pins requests to `api.example.com`, and the other way round.
- A substituted query parameter is never logged. The debug line for a redirect or an authenticator retry no longer includes the URL.
- Changes a `handleInterceptorProcessedRequest` callback makes to its `ApproovRequestMutations` no longer affect what the package strips on a redirect or signs.
- A secure string that a header cannot carry, such as one with a control or non-ASCII character, keeps its placeholder and the request proceeds with a warning. In a query parameter, a value OkHttp would not send unchanged (tab, LF, CR, `&` or `#`) keeps its placeholder; non-ASCII and DEL are still sent percent-encoded.
- Redirects and `Authenticator` retries are protected for what they actually send. A redirect to a host Approov does not protect carries no Approov headers or secrets. A redirect within your API, including a `303` that changes the method, and a retry with a new `Authorization` are signed again.
- Every connection is checked against the current pins for its own host.
- The pins are rebuilt when the SDK reports `isForceApplyPins`, not only on a configuration change.

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
- **`NO_APPROOV_SERVICE`**: the request now proceeds emitting an **empty** `Approov-Token` header (and a trace ID if the SDK provides one) as evidence that Approov processing occurred, instead of omitting the headers. `UNKNOWN_URL`/`UNPROTECTED_URL` still send no headers.
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
