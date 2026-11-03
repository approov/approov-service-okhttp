# Changelog

All notable changes to this package will be documented in this file.

The format is based on Keep a Changelog and this project adheres to Semantic Versioning.

## [3.7.0] - 2026-11-02

Entries marked **Breaking** need a source change. See [Upgrading from 3.5.x](README.md#upgrading-from-35x).

### Changed
- BouncyCastle is replaced by Google Tink 1.23.0, bundled in the AAR and relocated, so it cannot clash with your own dependencies and the published POM adds none. The release AAR shrinks from 5.7 MB to 3.4 MB.
- **Breaking:** The message signing helper classes moved from `io.approov.util.http.sfv` and `io.approov.util.sig` to `io.approov.util.okhttp.http.sfv` and `io.approov.util.okhttp.sig`. Update imports of classes such as `SignatureParameters` and `ComponentProvider`.
- **Breaking:** `SignatureParametersFactory` has a new protected field, `useInstallMessageSigning`, which starts `true`. A subclass that sets `useAccountMessageSigning = true` directly now produces both signatures. Call `setUseAccountMessageSigning()` to produce the account signature only.
- The package depends on OkHttp 5.3.2, where 3.5.x depended on 4.12.0. OkHttp 5 brings Happy Eyeballs: it tries IPv6 and IPv4 addresses side by side, so requests connect on networks that drop IPv6. Apps that compile Kotlin need Kotlin 2.1 or later. See [OkHttp version](ADVANCED.md#okhttp-version).
- A failed secure string fetch no longer fails the request. The status name replaces the placeholder, such as `X-Api-Key: REJECTED`, so a backend that checks the value must expect it. See [secure strings](ADVANCED.md#secure-strings).
- A failed token fetch only affects requests to your Approov API domains, the hosts in your pin set. Requests to other hosts are sent unchanged.
- A WebSocket upgrade request is sent untouched, with no Approov token, secure strings or signature. See [WebSockets](ADVANCED.md#websockets).

### Deprecated
- `getMessageSignature(message)`. Use `getAccountMessageSignature`.
- `setApproovHeader`, `setApproovTraceIDHeader`, `getApproovTokenHeader`, `getApproovTokenPrefix` and `getApproovTraceIDHeader`. Use `setTokenHeader(header, prefix)`, `getTokenHeader()`, `getTokenPrefix()`, `setTraceIDHeader(header)` and `getTraceIDHeader()`.

### Added
- `ApproovServiceMutator.CLOSE_FAILURE`, the default, which keeps the 3.5.x decisions, and `ALWAYS_PROCEED`, which never aborts a request. See [what happens by default](ADVANCED.md#what-happens-by-default).
- `ApproovService.getServerAdjustedCurrentTimeMS()`, the current time corrected to the Approov servers' clock. Message signatures take their `created` and `expires` times from it.
- `ApproovService.setLoggingLevel(ApproovLogLevel)`, `INFO` by default. See [diagnostics](ADVANCED.md#diagnostics).
- Message signing can produce the account signature as well as the install signature: `SignatureParametersFactory.setUseInstallAndAccountMessageSigning()`. See [message signing](ADVANCED.md#message-signing).
- The names shared by all Approov packages: `isApproovServiceEnabled()`, `isApproovProtectionEnabled()`, `setInstallAttributes(String)` and the header methods listed under Deprecated.

### Removed
- `setProceedOnNetworkFail(boolean)` and `getProceedOnNetworkFail()`, which had no effect in 3.5.x. Use `ALWAYS_PROCEED` or `setUseApproovStatusIfNoToken(true)`.
- `prefetch()`. The SDK manages prefetching itself.
- `setApproovInterceptorExtensions`, `getApproovInterceptorExtensions` and the `ApproovInterceptorExtensions` interface. Use `setServiceMutator` with an `ApproovServiceMutator`.

### Fixed
- An exception from your mutator hook, from the Approov SDK or from a required body digest that cannot be generated fails the request with an `ApproovException` whose cause is the original, and never crashes an enqueued call.
- Cleartext requests are no longer blocked by the pinning interceptor.
- A connection that fails validation is no longer closed. Only the request fails. OkHttp can share one HTTP/2 connection between several hosts, so leaving it open keeps requests to the other hosts on it from failing.
- Each request's token is bound to its own binding header value.
- APIs added with `approov api -add <domain> -noApproovToken` get their secure strings.
- Redirects, `Authenticator` retries and stale refreshes are protected for what they send, with the mutator the request was first processed with.
- Message signing signs the request exactly as OkHttp sends it, as RFC 9421 requires.
- `getLastARC()` makes no token fetch of its own.

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
