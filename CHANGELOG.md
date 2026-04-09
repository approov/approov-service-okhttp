# Changelog

All notable changes to this package will be documented in this file.

The format is based on Keep a Changelog and this project adheres to Semantic Versioning.

## [3.5.7] - 2026-04-09

### Added
- Integrated a localized testing framework for comprehensive service layer verification.
- Added extensive test coverage for token management, pinning synchronization, and request mutation scenarios.
- Enhanced internal service components to improve testability.
- Added `ApproovService.isInitialized()` to expose the service-layer initialization state.

### Fixed
- Improved service re-initialization consistency for internal state management.
- Initializing with an empty config string now keeps the service layer initialized while returning a plain `OkHttpClient` without Approov processing.

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
