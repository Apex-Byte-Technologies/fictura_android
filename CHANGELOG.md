# Changelog

Format based on [Keep a Changelog](https://keepachangelog.com/).

## [0.1.0] - Unreleased

First release: Fictura for native Android apps.

### Added
- `Fictura.configure`, events, screens, sessions, identity, attributes, super properties and opt-out, in the React Native SDK's batch format (`x-fictura-sdk: android/0.1.0`, `$lib: fictura-android`). The device id is `and-<ANDROID_ID>`, as in the React Native SDK.
- Automatic `first_open`, `app_open`, `app_backgrounded`, `app_updated`, Activity screens and `element_tapped` for clickable views.
- Remote config with kill switches and sampling; placements, campaigns, audiences, holdouts and A/B assignment confirmed with the server (checked against the shared contract vectors).
- Served HTML paywalls in a WebView with the shared bridge and binder; paywall and purchase events; web checkout.
- Purchases through your `PurchaseController` (RevenueCat example in the README).
- Tracking-link and campaign parameters from opening Uris.
- Handled errors, uncaught exceptions (sent on the next launch), and ANRs and native crashes from ApplicationExitInfo (API 30+).
