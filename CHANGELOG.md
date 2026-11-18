# Changelog

Format based on [Keep a Changelog](https://keepachangelog.com/).

## [0.2.0] - Unreleased

For SDKs that wrap this one, starting with `fictura_flutter`. Apps that call the SDK directly see no change.

### Added
- `FicturaOptions.wrapper` (`FicturaWrapper(name, version)`): a wrapping SDK names itself. Every request then also carries `x-fictura-wrapper: flutter/0.1.0`, every event `$wrapper` and `$wrapper_version`, and every error `contexts.app.wrapper`. `x-fictura-sdk` stays `android/…`, so the backend keeps reading Java and Kotlin stacks and serving HTML paywalls.
- `Fictura.Wrapper.captureError(...)`: reports an error raised in the wrapper's own language (a Dart exception) with its own stack text, marked with `contexts.runtime.name`.
- `Fictura.Wrapper.attach(activity)`: hands over the Activity that was already on screen when `configure` ran. A Flutter app configures from Dart, after its Activity started, so without it the launch had no `app_open`, backgrounding no `app_backgrounded`, and a paywall no Activity to open on.

### Changed
- An Activity stop the SDK never saw start no longer pushes its started count below zero.

## [0.1.0] - Unreleased

First release: Fictura for native Android apps.

### Added
- Published to Maven Central as `co.fictura:fictura-android`, with sources, javadoc and signed files. A `v*` tag uploads a release.
- `Fictura.configure`, events, screens, sessions, identity, attributes, super properties and opt-out, in the React Native SDK's batch format (`x-fictura-sdk: android/0.1.0`, `$lib: fictura-android`). The device id is `and-<ANDROID_ID>`, as in the React Native SDK.
- Automatic `first_open`, `app_open`, `app_backgrounded`, `app_updated`, Activity screens and `element_tapped` for clickable views.
- Remote config with kill switches and sampling; placements, campaigns, audiences, holdouts and A/B assignment confirmed with the server (checked against the shared contract vectors).
- Served HTML paywalls in a WebView with the shared bridge and binder; paywall and purchase events; web checkout.
- Purchases through your `PurchaseController` (RevenueCat example in the README).
- Tracking-link and campaign parameters from opening Uris.
- Handled errors, uncaught exceptions (sent on the next launch), and ANRs and native crashes from ApplicationExitInfo (API 30+).
