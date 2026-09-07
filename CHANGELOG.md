# Changelog

All notable changes to this package will be documented in this file.

The format is based on Keep a Changelog and this project adheres to Semantic Versioning.

## [3.7.0] - Unreleased

This release targets the Approov SDK 3.7.0 and changes the request contract every integration has been coded against. It is the 3.7.x line; the 3.5.x line keeps its current behaviour. See the 3.7.0 behaviour specification for the cross-layer rules.

### Changed
- **Requests always proceed.** No runtime condition aborts a request on the service layer's behalf any more. A token fetch that fails for any reason (`NO_NETWORK`, `POOR_NETWORK`, `UNTRUSTED_NETWORK`, `NO_APPROOV_SERVICE`, `REJECTED`, `INTERNAL_ERROR`, ...) sends the request with an **empty** `Approov-Token` header, and a secure string substitution that fails leaves the placeholder value in the header or query parameter and sends the request. `UNKNOWN_URL` and `UNPROTECTED_URL` still send the request with no Approov headers at all. The backend is the enforcement point. The default `ApproovServiceMutator` interceptor decisions (`handleInterceptorFetchTokenResult`, `handleInterceptorHeaderSubstitutionResult`, `handleInterceptorQueryParamSubstitutionResult`) no longer throw. An app may opt in to aborting a request for an outcome it does not accept by overriding a hook and throwing; the interceptor hooks now declare `IOException` so that such an abort is a standard network stack exception (`IOException` or a platform subclass), never an Approov specific type, and surfaces to the app as an ordinary network failure. Existing overrides declaring `ApproovException` still compile.
- **Message signing is on by default, with both signatures.** `ApproovService.initialize` installs an `ApproovDefaultMessageSigning` mutator (see the new `ApproovService.createDefaultServiceMutator()`) so every request carrying an Approov token header is signed with both the install (`ecdsa-p256-sha256`, member `install`) and the account (`hmac-sha256`, member `account`) signatures, emitted as two members of the same `Signature` and `Signature-Input` dictionaries over the same covered components. A signature that cannot be produced is omitted and the request proceeds with the other, or unsigned. Pass `ApproovServiceMutator.DEFAULT` to `setServiceMutator` to switch signing off; `setServiceMutator(null)` now reinstates the signing default rather than `ApproovServiceMutator.DEFAULT`.
- `SignatureParametersFactory` gains `setUseInstallAndAccountMessageSigning()` (the new default of `generateDefaultSignatureParametersFactory()`), `getAlgs()` and `setAddApproovStatusHeader(boolean)`. `setUseInstallMessageSigning()` and `setUseAccountMessageSigning()` now select a single signature. The factory no longer sets `alg` on the built `SignatureParameters`; a subclass that does set one produces that single signature (backwards compatible).
- `MITM_DETECTED` is gone from the Approov channel. The 3.7.0 SDK no longer pins its own attestation channel (an additional encryption layer replaces it), so an intercepting proxy on the attestation path no longer blocks a token fetch, and `Approov.TokenFetchStatus.MITM_DETECTED` is replaced by `UNTRUSTED_NETWORK` (a fundamental TLS trust failure). This layer classifies `UNTRUSTED_NETWORK` as a network failure alongside `NO_NETWORK` and `POOR_NETWORK` (`ApproovServiceMutator.isNetworkFailure`). Pinning of the integrator's own API domains is unchanged and a pin mismatch there still fails the connection.
- Header configuration uses the common service layer names: `setTokenHeader(header, prefix)`, `getTokenHeader()`, `getTokenPrefix()`, `setTraceIDHeader(header)`, `getTraceIDHeader()`. The okhttp-specific `setApproovHeader`, `setApproovTraceIDHeader`, `getApproovTokenHeader`, `getApproovTokenPrefix` and `getApproovTraceIDHeader` remain as deprecated aliases.
- `ApproovPinningInterceptor` reports a cleartext (non-TLS) connection to a pinned host with `javax.net.ssl.SSLPeerUnverifiedException`, the same network stack exception as a pin mismatch, instead of the Approov-specific `ApproovNetworkException`; a cleartext connection to a host with no pins is no longer blocked by this layer. The interceptor also rebuilds its pins on first use if none were available when it was constructed, since the 3.7.0 SDK only holds pins once a token has been fetched for the installation.

### Added
- **`Approov-Status` header** (`setStatusHeader(header)`, `getStatusHeader()`), on by default, carrying the SDK token fetch status in lowercase (`success`, `no_network`, `untrusted_network`, `no_approov_service`, `rejected`, ...) on **every** request processed by Approov, successful ones included, and identical on Android and iOS. It lets the backend distinguish a request whose Approov headers were stripped from one sent without a token because the fetch failed. Not sent to domains that are not protected by Approov. `setStatusHeader(null)` disables it. Failure information is never placed in the token header. `ApproovRequestMutations.getStatusHeaderKey()` exposes it to mutators, and the default message signing covers it so the reported status cannot be stripped or altered without invalidating the signature.
- `ApproovService.createDefaultServiceMutator()` returns the out-of-the-box mutator (message signing with both signatures).
- Tests for the 3.7.0 contract: every failure status proceeds with an empty token header and the lowercased status on the status header; a successful request reports `success`; the status header can be renamed and disabled; secure string header and query failures proceed with the placeholder in place; the out-of-the-box mutator signs with both signatures (guarding the default); one signature falls back to the other when a key is unavailable; a custom mutator can still suppress all headers or fail closed by throwing.

### Removed
- `setProceedOnNetworkFail` / `getProceedOnNetworkFail` (no-ops since 3.5.7).
- `setUseApproovStatusIfNoToken` / `getUseApproovStatusIfNoToken`: the fetch status is reported on `Approov-Status`, never in the token header.
- `setApproovInterceptorExtensions` / `getApproovInterceptorExtensions` and the `ApproovInterceptorExtensions` interface: use `setServiceMutator` / `getServiceMutator` with an `ApproovServiceMutator`.
- `ApproovDefaultMessageSigning.processedRequest(request, changes)` (deprecated): the implementation is `handleInterceptorProcessedRequest`.
- `USAGE.md` is replaced by `ADVANCED.md`, since the standard integration needs none of it: message signing is on by default and requests always proceed. The documentation now calls this the Approov Package for OkHttp: integrating the package is integrating Approov, and the wrapped SDK is a detail.

### Fixed
- `getLastARC()` no longer performs a token fetch of its own (it fetched a token for the first pinned domain, which could return the ARC of a later, successful attestation rather than the one behind the request that failed, and cost a network round trip). It now returns the ARC recorded from the most recent fetch performed by this layer, from the interceptor or a direct method, with no network activity. Removes the getLastARC patch implementation tracked in approov/core-project-approov#566.
- A header that is present with an empty value is now a valid covered component for message signing (RFC 9421 §2.1), so a request sent with an empty `Approov-Token` is still signed. Previously the signature base build failed and the request went out unsigned. This is in the shared `io.approov.util.sig.ComponentProvider` and applies to every layer carrying a copy of it.

### Notes
- The `approov-android-sdk` dependency still points at 3.5.3 because the 3.7.0 SDK is not yet published. `UNTRUSTED_NETWORK` is matched by name until then (`ApproovServiceMutator.isNetworkFailure`), and the `UNTRUSTED_NETWORK` tests wait on the mini SDK carrying the 3.7.0 enum.

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
- **REFERENCE.md**: corrected the deprecated `setApproovInterceptorExtensions` signature — its parameter is an `ApproovServiceMutator` (the legacy `ApproovInterceptorExtensions` interface is a deprecated subtype), not `ApproovInterceptorExtensions`.

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
- Simplified `initialize` — removed the service-layer re-initialization guards (same-config short-circuit, `reinit` comment check). The service layer now always resets its own state and forwards non-empty config directly to the platform SDK. The SDK returns `false` if already initialized with the same config (service layer logs and continues), or throws `IllegalStateException` for a different config (service layer re-throws).

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
