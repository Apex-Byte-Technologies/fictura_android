# Fictura for Android

The Fictura SDK for native Android apps written in Kotlin or Java. It sends the same events and uses the same paywalls, placements and experiments as the React Native SDK, so one dashboard covers both.

- minSdk 23, compileSdk 36, Java 17
- No dependencies (Google's Install Referrer library is optional)

## Install

Until it is on Maven Central, include the module from this repo (`include(":fictura")` with `project(":fictura").projectDir`), or build the AAR with `./gradlew :fictura:assembleRelease`.

## Start

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Fictura.configure(this, "gk_...")
    }
}
```

Pass every opening Uri (App Links, deep links) to `Fictura.handle(intent.data)`.

Recorded automatically: `first_open`, `app_open`, `app_backgrounded`, `app_updated`, a `screen_viewed` per Activity (Compose screens: call `Fictura.screen("Name")`), `element_tapped` for clickable views (name them with `contentDescription` or a view id), and crashes, uncaught exceptions and ANRs.

## Events and users

```kotlin
Fictura.track("photo_saved", mapOf("style" to "modern"))
Fictura.identify("user_123", mapOf("plan" to "free"))   // after sign-in
Fictura.reset()                                          // after sign-out
Fictura.setAttributes(mapOf("plan" to "pro"))
Fictura.capture(exception)
Fictura.optOut()
```

## Paywalls and placements

```kotlin
Fictura.register(activity, "export_hd") { exportHD() }   // shows the dashboard's paywall, then runs the feature unless a gated paywall was closed unpaid
```

Only served HTML designs (paywall schema 5) show on Android. Paywalls buy through your `PurchaseController`. With RevenueCat:

```kotlin
object RevenueCatPurchases : PurchaseController {
    override fun packages(productIds: List<String>, done: (List<FicturaPackage>) -> Unit) {
        Purchases.sharedInstance.getOfferingsWith({ done(emptyList()) }) { offerings ->
            done(offerings.current?.availablePackages.orEmpty().map { p ->
                val sp = p.product
                FicturaPackage(p.identifier, sp.id, sp.price.formatted, sp.price.amountMicros, sp.price.currencyCode,
                    when (p.packageType) { PackageType.WEEKLY -> "weekly"; PackageType.MONTHLY -> "monthly"; PackageType.ANNUAL -> "annual"; PackageType.LIFETIME -> "lifetime"; else -> "other" })
            })
        }
    }
    override fun purchase(activity: Activity, pkg: FicturaPackage, context: PurchaseContext, done: (PurchaseResult) -> Unit) {
        Purchases.sharedInstance.getOfferingsWith({ done(PurchaseResult.Failed(it.message)) }) { offerings ->
            val p = offerings.current?.getPackage(pkg.id) ?: return@getOfferingsWith done(PurchaseResult.Failed("not_found"))
            Purchases.sharedInstance.purchaseWith(PurchaseParams.Builder(activity, p).build(),
                { error, cancelled -> done(if (cancelled) PurchaseResult.Cancelled else PurchaseResult.Failed(error.message)) },
                { tx, _ -> done(PurchaseResult.Purchased(tx?.orderId)) })
        }
    }
    override fun isSubscribed(done: (Boolean) -> Unit) =
        Purchases.sharedInstance.getCustomerInfoWith({ done(false) }) { done(it.entitlements.active.isNotEmpty()) }
}

Fictura.purchaseController = RevenueCatPurchases
Fictura.setRevenueCatUserId(Purchases.sharedInstance.appUserID)
```

## Not yet available on Android

- Push sent by Fictura (Expo-only today). Use OneSignal with `OneSignal.login(userId)`.
- Revenue without RevenueCat (Play Real-time Developer Notifications), and a built-in Play Billing purchase controller.
- Install referrer attribution and deferred deep links (`/v1/resolve`).
- Cancel survey, performance traces, and handled-error deobfuscation with R8 mapping files.
- Onboarding flows (deprecated) and React Native-only paywall templates (schemas 1–4).

## Development

```bash
./gradlew :fictura:testDebugUnitTest
./gradlew :demo:installDebug -PficturaKey=gk_... -PficturaApi=http://10.0.2.2:4000
```

The unit tests run the shared contract vectors generated from the React Native SDK (`fictura/src/test/resources/vectors`).
