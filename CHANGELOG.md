# Changelog

All notable changes to this package will be documented in this file.

The format is based on Keep a Changelog and this project adheres to Semantic Versioning.

## [3.5.8] - 2026-06-12

### Added
- Stale protection refresh: a new network interceptor detects requests that were held between Approov protection being applied and actual transmission (for example by a device deep sleep or doze period, or an app-level request queueing/backoff mechanism) and refreshes the Approov token and any message signature immediately before the request is sent, instead of transmitting expired credentials. Since it operates per network attempt it also refreshes protection on OkHttp generated retries and redirect followups. Configurable via `ApproovService.setStaleProtectionRefreshPeriod()` (default 3000ms, `<=0` disables). Because a refresh reinvokes the mutator's `handleInterceptorProcessedRequest` callback, it is gated on the new `ApproovServiceMutator.supportsProtectionRefresh()` capability: the default mutator and `ApproovDefaultMessageSigning` support it, while custom mutator implementations are never reinvoked unless they opt in.
- Service-layer version is now baked into the AAR at build time via `BuildConfig.APPROOV_SERVICE_VERSION` and reported to the Approov SDK via `setUserProperty("approov-service-okhttp/X.Y.Z")` during initialization. Local builds report `dev`.
- CHANGELOG-vs-tag validation step in the publish workflow to fail fast if the top changelog entry does not match the release tag.
- Automatic release tagging on merge to `main` (`tag-release` job in `build_and_test.yml`): once the build/tests pass, the top CHANGELOG entry drives a matching git tag, which triggers the Maven publish workflow. Skipped if the tag already exists.

### Changed
- Android build migrated from the unmaintained `com.github.johnrengelman.shadow` 8.1.1 plugin to the maintained fork `com.gradleup.shadow` 8.3.11 for Gradle 9 compatibility (Gradle 9 removed `FileCopyDetails.mode`, making the old plugin fail with a `MissingPropertyException`). Shaded BouncyCastle jar verified byte-identical; minimum supported Gradle remains 8.3.
- Publish workflow now passes `-PapproovServiceVersion` to `assembleRelease`, keeping the runtime version in lockstep with the Maven artifact version.

### Fixed
- **Message-signing fail-open conformance**: a body digest configured as *required* that cannot be generated now fails **closed** (aborting the request) instead of being silently skipped. All other signing failures (signature unavailable, base64 decode, ASN.1/DER decode, account-branch errors) continue to fail **open** (the request proceeds unsigned) but are now logged at **error** level for production visibility. Unsupported signing algorithms still fail closed.
- **Token binding header**: the binding header is now matched case-insensitively (HTTP header names are case-insensitive) and a present-but-empty value is forwarded to the SDK, while an absent header is skipped.
- **Trace ID header**: an empty trace ID returned by the SDK is now emitted as an empty header value rather than omitted, so the backend has evidence that Approov processing occurred.
- **`NO_APPROOV_SERVICE`**: the request now proceeds emitting an **empty** `Approov-Token` header (and a trace ID if the SDK provides one) as evidence that Approov processing occurred, instead of omitting the headers (root TESTING_REQUIREMENTS §2 Missing Artifacts Fallback). `UNKNOWN_URL`/`UNPROTECTED_URL` still send no headers.
- **REFERENCE.md**: corrected the deprecated `setApproovInterceptorExtensions` signature — its parameter is an `ApproovServiceMutator` (the legacy `ApproovInterceptorExtensions` interface is a deprecated subtype), not `ApproovInterceptorExtensions`.

### Known Issues
- **Message signature encoding**: the `Signature` header value is emitted as a quoted String (`install="<base64>"`) rather than the Byte Sequence form (`install=:<base64>:`) required by RFC 9421 §4.2 / RFC 8941 §3.3.5 and used by every other Approov service layer. Migrating to the Byte Sequence form is a breaking change for existing verifiers and is tracked in [#34](https://github.com/approov/approov-service-okhttp/issues/34).

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
