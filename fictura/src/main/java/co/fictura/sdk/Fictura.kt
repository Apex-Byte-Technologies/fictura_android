package co.fictura.sdk

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.CompoundButton
import android.widget.TextView
import java.io.File
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.TimeZone

/**
 * The SDK that wraps this one, such as `fictura_flutter`: its name and version.
 * Set only by that SDK, so the backend can tell its apps apart.
 */
data class FicturaWrapper(val name: String, val version: String) {
    /**
     * `name/version`, sent as `x-fictura-wrapper`. Characters the backend's version
     * check refuses are dropped, so a bad value never costs the whole header.
     */
    val header: String?
        get() {
            val n = name.lowercase().filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(20)
            val v = version.filter { it.isLetterOrDigit() || it in ".+-" }.take(40)
            return if (n.isEmpty() || v.isEmpty()) null else "$n/$v"
        }

    /** `$wrapper` and `$wrapper_version` on every event. */
    internal fun properties(): JsonMap {
        val h = header ?: return emptyMap()
        return mapOf("\$wrapper" to Json.Str(h.substringBefore('/')), "\$wrapper_version" to Json.Str(h.substringAfter('/')))
    }
}

/** Settings for [Fictura.configure]. Defaults match the React Native SDK. */
class FicturaOptions {
    var apiBase: String = "https://api.fictura.co"
    var captureScreens = true
    var captureTaps = true
    var captureLifecycle = true
    var captureErrors = true
    var release: String? = null
    var environment: String? = null
    var flushIntervalMs: Long = 4000
    var debug = false
    /** Set by an SDK that wraps this one (`fictura_flutter`). Leave it null in app code. */
    var wrapper: FicturaWrapper? = null
}

/**
 * Fictura for native Android apps.
 *
 * ```kotlin
 * Fictura.configure(application, "gk_...")
 * Fictura.track("photo_saved", mapOf("style" to "modern"))
 * Fictura.register(activity, "export_hd") { exportHD() }
 * ```
 */
object Fictura {
    private var core: Core? = null
    private var config: RemoteConfig? = null
    private var placements: PlacementRuntime? = null
    private var errors: ErrorReporter? = null
    private var options = FicturaOptions()
    private val main = Handler(Looper.getMainLooper())
    private var currentActivity: WeakReference<Activity>? = null
    private var screen: Pair<String, Long>? = null
    private var appContext: Context? = null
    private var lifecycle: Lifecycle? = null

    /** How paywalls buy (RevenueCat or Play Billing in your app; see the README). */
    @JvmStatic var purchaseController: PurchaseController? = null
    var termsUrl: String? = null
    var privacyUrl: String? = null
    var customActionHandler: ((String, Map<String, Any?>) -> Map<String, Any?>?)? = null
    var onConversion: ((Map<String, Any?>) -> Unit)? = null

    internal fun apiBase() = options.apiBase

    @JvmStatic
    @JvmOverloads
    fun configure(app: Application, apiKey: String, options: FicturaOptions = FicturaOptions()) {
        if (core != null) return
        this.options = options
        appContext = app
        val key = apiKey.trim()
        val dir = File(app.noBackupFilesDir, "co.fictura/" + Ids.sha256Hex(key).take(12))
        val store = FileStore(dir)
        val c = Core(Api(options.apiBase, key, options.wrapper?.header), store, HttpTransport(), deviceId(app, store),
            everyEventProperties(app) + (options.wrapper?.properties() ?: emptyMap()),
            flushIntervalMs = options.flushIntervalMs, appVersion = appVersion(app), country = { Locale.getDefault().country.takeIf { it.isNotEmpty() } })
        core = c
        val cfg = RemoteConfig(c) { Locale.getDefault().toLanguageTag() }
        config = cfg
        val pr = PlacementRuntime(c, cfg) { now, installed, occ, first -> deviceFields(app, now, installed, occ, first) }
        pr.controller = { purchaseController }
        pr.presenter = Presenter { req -> main.post { present(req) } }
        placements = pr
        val er = ErrorReporter(c, options.release ?: appVersion(app)?.let { v -> appBuild(app)?.let { "$v+$it" } ?: v }, options.environment ?: "production",
            options.wrapper?.header)
        errors = er
        c.onEvent = { er.addBreadcrumb(it) }
        c.onConversion = { data -> main.post { onConversion?.invoke(data.mapValues { it.value.any }) } }
        cfg.refresh(force = true)
        if (options.captureErrors) {
            er.installUncaughtHandler()
            er.readExitInfo(app)
            er.send()
        }
        // first_open survives reinstalls through the device id store, like the React Native SDK's Keychain flag.
        val firstOpen = store.get("first_open_v1") == null
        if (firstOpen) store.set("first_open_v1", "1")
        if (options.captureLifecycle) {
            if (firstOpen) c.track("first_open")
            val v = appVersion(app); val b = appBuild(app)
            val prev = store.getJson("app_version_v1")?.obj
            if (!firstOpen && prev != null && (prev["version"]?.string != v || prev["build"]?.string != b)) {
                c.track("app_updated", properties = mapOf("previous_version" to Json.of(prev["version"]?.string), "previous_build" to Json.of(prev["build"]?.string), "version" to Json.of(v), "build" to Json.of(b)))
            }
            store.setJson("app_version_v1", Json.obj("version" to v, "build" to b))
        }
        val lc = Lifecycle()
        lifecycle = lc
        app.registerActivityLifecycleCallbacks(lc)
        pr.onLaunch(firstOpen)
        log("configured ${SdkInfo.HEADER}${options.wrapper?.header?.let { " (wrapped by $it)" } ?: ""}, device ${c.deviceId}")
    }

    /** `and-<ANDROID_ID>` like the React Native SDK, so an app rewritten from React Native keeps its users. */
    @SuppressLint("HardwareIds")
    private fun deviceId(ctx: Context, store: KeyValueStore): String {
        store.get("device_id_reset_v1")?.let { return it }
        val ssaid = try { Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) } catch (_: Exception) { null }
        if (!ssaid.isNullOrEmpty()) return "and-$ssaid"
        return store.get("device_id_v1") ?: "and-${Ids.uuidv4()}".also { store.set("device_id_v1", it) }
    }

    private fun appVersion(ctx: Context) = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (_: Exception) { null }
    @Suppress("DEPRECATION")
    private fun appBuild(ctx: Context) = try {
        val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        (if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()).toString()
    } catch (_: Exception) { null }

    private fun everyEventProperties(ctx: Context): JsonMap {
        val dm = ctx.resources.displayMetrics
        val tablet = ctx.resources.configuration.smallestScreenWidthDp >= 600
        val out = linkedMapOf<String, Json>(
            "\$lib" to Json.Str(SdkInfo.LIB), "\$lib_version" to Json.Str(SdkInfo.VERSION), "\$os" to Json.Str("Android"),
            "\$os_version" to Json.Str(Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString()),
            "\$device_type" to Json.Str(if (tablet) "Tablet" else "Mobile"), "\$device_model" to Json.Str(Build.MODEL ?: ""),
            "\$device_manufacturer" to Json.Str(Build.MANUFACTURER ?: ""), "\$app_namespace" to Json.Str(ctx.packageName),
            "\$locale" to Json.Str(Locale.getDefault().toLanguageTag()), "\$timezone" to Json.Str(TimeZone.getDefault().id),
            "\$screen_width" to Json.of((dm.widthPixels / dm.density).toInt()), "\$screen_height" to Json.of((dm.heightPixels / dm.density).toInt()),
            "\$is_emulator" to Json.Bool(Build.FINGERPRINT?.contains("generic") == true || Build.MODEL?.contains("sdk_gphone") == true || Build.PRODUCT?.contains("sdk") == true),
        )
        appVersion(ctx)?.let { out["\$app_version"] = Json.Str(it) }
        appBuild(ctx)?.let { out["\$app_build"] = Json.Str(it) }
        return out
    }

    private fun deviceFields(ctx: Context, now: Double, installed: Double, occ: Map<String, List<Double>>, firstOpen: Boolean): JsonMap {
        val ev = everyEventProperties(ctx)
        val cal = java.util.Calendar.getInstance()
        val views = occ[Occurrences.PAYWALL_VIEW_KEY] ?: emptyList()
        val out = linkedMapOf<String, Json>("platform" to Json.Str("android"), "sdk_version" to Json.Str(SdkInfo.VERSION),
            "language" to Json.Str(Locale.getDefault().language), "region" to Json.Str(Locale.getDefault().country),
            "timezone_offset" to Json.of(TimeZone.getDefault().getOffset(now.toLong()) / 60_000),
            "local_hour" to Json.of(cal.get(java.util.Calendar.HOUR_OF_DAY)), "local_weekday" to Json.of(cal.get(java.util.Calendar.DAY_OF_WEEK) - 1),
            "is_tablet" to Json.Bool(ev["\$device_type"]?.string == "Tablet"),
            "minutes_since_install" to Json.of(((now - installed) / 60_000).toLong()), "days_since_install" to Json.of(((now - installed) / 86_400_000).toLong()),
            "total_paywall_views" to Json.of(views.size), "is_first_app_open" to Json.Bool(firstOpen), "font_scale" to Json.of(ctx.resources.configuration.fontScale.toDouble()))
        for ((k, src) in listOf("app_version" to "\$app_version", "app_build" to "\$app_build", "os_version" to "\$os_version", "device_model" to "\$device_model", "locale" to "\$locale", "screen_width" to "\$screen_width", "screen_height" to "\$screen_height")) ev[src]?.let { out[k] = it }
        views.lastOrNull()?.let { out["days_since_last_paywall_view"] = Json.of(((now - it) / 86_400_000).toLong()) }
        return out
    }

    // ── Paywalls ────────────────────────────────────────────────────────────

    private fun present(req: PresentRequest) {
        val c = core ?: return
        val activity = currentActivity?.get()
        if (activity == null || activity.isFinishing) { req.onClose("declined", null, "system"); return }
        val page = LinkedHashMap(req.pageConfig)
        val pr = placements!!
        page["device"] = Json.Obj(deviceFields(activity, c.now().toDouble(), pr.installedAtMs, pr.occurrencesSnapshot(), pr.firstOpen))
        page["user"] = Json.obj("id" to (c.ids().accountId ?: c.masterUser), "attributes" to c.allAttributes().mapValues { it.value.any })
        page["legal"] = Json.obj("termsUrl" to termsUrl, "privacyUrl" to privacyUrl)
        val bridge = PaywallBridge(c, purchaseController, req, config!!.products, page, config!!.webCheckoutUrl,
            presentPlacement = { name -> currentActivity?.get()?.let { register(it, name) } }, customAction = customActionHandler)
        if (bridge.html.isEmpty()) {
            val ids = c.ids()
            c.transport.send(c.api.request("api/v1/sdk/paywall/${req.info.paywallId}/html", query = mapOf("sv" to "5", "locale" to Locale.getDefault().toLanguageTag()), deviceId = ids.deviceId)) { res ->
                val html = Json.parse(res.body)?.get("html")?.string ?: ""
                main.post {
                    val withHtml = PresentRequest(req.id, req.info, html, req.version, req.pageConfig, req.params, req.onShown, req.onClose, req.onPurchaseCancelled, req.onPurchaseFailed)
                    PaywallActivity.start(activity, PaywallBridge(c, purchaseController, withHtml, config!!.products, page, config!!.webCheckoutUrl,
                        { name -> currentActivity?.get()?.let { register(it, name) } }, customActionHandler))
                }
            }
        } else PaywallActivity.start(activity, bridge)
    }

    /**
     * Shows the paywall the dashboard set for this placement, if any, then runs
     * [feature] unless a gated paywall was closed without a purchase.
     */
    @JvmStatic
    @JvmOverloads
    fun register(activity: Activity, placement: String, params: Map<String, Any?> = emptyMap(), handler: PlacementHandler? = null, feature: (() -> Unit)? = null) {
        val pr = placements ?: run { feature?.invoke(); return }
        currentActivity = WeakReference(activity)
        pr.register(placement, PlacementRuntime.sanitize(params), handler, feature, { main.post(it) })
    }

    // ── Events and users ────────────────────────────────────────────────────

    @JvmStatic @JvmOverloads
    fun track(event: String, properties: Map<String, Any?> = emptyMap()) {
        core?.track(event, properties = properties.mapValues { Json.of(it.value) })
    }

    /** Records a screen (`screen_viewed`). Activities are recorded automatically; Compose screens need this call. */
    @JvmStatic @JvmOverloads
    fun screen(name: String, properties: Map<String, Any?> = emptyMap()) {
        val c = core ?: return
        val to = name.trim()
        if (to.isEmpty() || to == screen?.first) return
        val now = c.now()
        screen?.let { (from, at) ->
            c.track("screen_closed", properties = mapOf("screen" to Json.Str(from), "to" to Json.Str(to), "duration_ms" to Json.of(maxOf(0L, now - at)), "\$screen_name" to Json.Str(from)))
        }
        val from = screen?.first
        screen = to to now
        c.setScreen(to)
        c.track("screen_viewed", properties = properties.mapValues { Json.of(it.value) } + mapOf("screen" to Json.Str(to), "from" to Json.of(from), "\$screen_name" to Json.Str(to)))
    }

    @JvmStatic @JvmOverloads
    fun identify(userId: String, attributes: Map<String, Any?>? = null) {
        val c = core ?: return
        val clean = legalId(userId) ?: return
        c.identify(clean)
        placements?.identify(clean)
        attributes?.let { c.setAttributes(it.mapValues { e -> Json.of(e.value) }) }
        config?.refresh(force = true)
    }

    @JvmStatic fun reset() { core?.reset(); placements?.reset(); config?.refresh(force = true) }
    @JvmStatic fun setRevenueCatUserId(id: String?) { core?.setRevenueCatId(id); config?.refresh(force = true) }
    @JvmStatic fun setAttributes(attrs: Map<String, Any?>) { core?.setAttributes(attrs.mapValues { Json.of(it.value) }) }
    @JvmStatic fun setOnce(attrs: Map<String, Any?>) { core?.setOnce(attrs.mapValues { Json.of(it.value) }) }
    @JvmStatic fun unsetAttribute(key: String) { core?.unsetAttribute(key) }
    @JvmStatic fun registerSuperProperties(props: Map<String, Any?>) { core?.register(props.mapValues { Json.of(it.value) }) }
    @JvmStatic fun optOut() { core?.setOptOut(true) }
    @JvmStatic fun optIn() { core?.setOptOut(false) }
    @JvmStatic fun flush(done: (() -> Unit)? = null) { core?.flush { done?.let { main.post(it) } } ?: done?.invoke() }
    @JvmStatic val deviceId: String? get() = core?.deviceId

    /** Pass every Uri that opens the app (App Links, deep links): Fictura reads tracking-link and campaign parameters. */
    @JvmStatic
    fun handle(uri: Uri?): Boolean {
        val c = core ?: return false
        val params = attributionParams(uri?.toString() ?: return false) ?: return false
        c.setPendingAttribution(params)
        params["growth_click_id"]?.string?.let { click ->
            val ids = c.ids()
            c.transport.send(c.api.request("api/v1/attribution/claim", "POST", body = Json.stringify(Json.obj("growth_click_id" to click, "revenuecat_id" to ids.revenuecatId)), deviceId = ids.deviceId, accountId = ids.accountId)) { }
        }
        return true
    }

    fun attributionParams(url: String): JsonMap? {
        val q = url.substringAfter('?', "").substringBefore('#')
        if (q.isEmpty()) return null
        val m = q.split('&').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { java.net.URLDecoder.decode(it[0], "UTF-8") to java.net.URLDecoder.decode(it[1], "UTF-8") } }.toMap()
        val out = LinkedHashMap<String, Json>()
        fun put(k: String, v: String?, max: Int = 200) { if (!v.isNullOrEmpty()) out[k] = Json.Str(v.take(max)) }
        put("link_slug", m["growth_slug"]); put("utm_source", m["utm_source"]); put("utm_medium", m["utm_medium"])
        put("utm_campaign", m["utm_campaign"]); put("utm_content", m["utm_content"]); put("utm_term", m["utm_term"])
        put("click_id", m["fbclid"] ?: m["ttclid"] ?: m["gclid"], 500)
        // The server refuses a whole batch over a malformed click id, so it is dropped here.
        m["growth_click"]?.takeIf { Ids.UUID_RE.matches(it) }?.let { out["growth_click_id"] = Json.Str(it) }
        return out.takeIf { it.isNotEmpty() }
    }

    // ── Errors ──────────────────────────────────────────────────────────────

    @JvmStatic @JvmOverloads
    fun capture(error: Throwable, properties: Map<String, Any?> = emptyMap()) { errors?.capture(error, properties) }

    @JvmStatic @JvmOverloads
    fun captureMessage(message: String, level: String = "error") { errors?.captureMessage(message, level) }

    // ── Wrapping SDKs ───────────────────────────────────────────────────────

    /** For SDKs that wrap this one, such as `fictura_flutter`. App code doesn't need these. */
    object Wrapper {
        /**
         * The Activity that was already on screen when [configure] ran. A Flutter app
         * configures from Dart, after its Activity started, so the SDK missed that start:
         * without this there is no `app_open` for the launch, no `app_backgrounded`, and
         * no Activity to show a paywall on. Ignored once the SDK has seen an Activity start.
         */
        @JvmStatic
        fun attach(activity: Activity) {
            main.post { if (!activity.isFinishing) lifecycle?.adopt(activity) }
        }

        /**
         * Reports an error raised in the wrapper's own language (a Dart exception), with
         * its own type, message and stack text. [runtime] names that language (`dart`),
         * so the backend reads the stack's frames. [level]: info, warning, error, fatal.
         */
        @JvmStatic
        @JvmOverloads
        fun captureError(type: String, message: String?, stack: String?, handled: Boolean = true, level: String = "error",
                         mechanism: String = "manual", runtime: String, properties: Map<String, Any?> = emptyMap()) {
            errors?.captureForeign(type, message, stack, level, handled, mechanism, runtime, properties)
        }
    }

    internal fun log(s: String) { if (options.debug) android.util.Log.d("Fictura", s) }

    // ── Lifecycle, screens and taps ─────────────────────────────────────────

    private class Lifecycle : Application.ActivityLifecycleCallbacks {
        private var started = 0
        private var backgroundedAt: Long? = null
        private var seen = false

        /** Counts an Activity that started before these callbacks were registered (see [Wrapper.attach]). */
        fun adopt(a: Activity) {
            if (seen || started > 0) return
            seen = true
            started = 1
            if (options.captureLifecycle && !openLogged) {
                openLogged = true
                core?.track("app_open", properties = mapOf("from_background" to Json.Bool(false)))
            }
            onActivityResumed(a)
        }

        override fun onActivityCreated(a: Activity, b: Bundle?) {}
        override fun onActivityStarted(a: Activity) {
            seen = true
            if (started++ == 0) {
                val c = core ?: return
                if (backgroundedAt != null) {
                    backgroundedAt = null
                    if (options.captureLifecycle) c.track("app_open", properties = mapOf("from_background" to Json.Bool(true)))
                    config?.refresh(); placements?.onForeground(); errors?.send()
                } else if (options.captureLifecycle && !openLogged) {
                    openLogged = true
                    c.track("app_open", properties = mapOf("from_background" to Json.Bool(false)))
                }
            }
        }
        override fun onActivityResumed(a: Activity) {
            if (a is PaywallActivity) return
            currentActivity = WeakReference(a)
            if (options.captureScreens) screen(a.javaClass.simpleName.removeSuffix("Activity").ifEmpty { a.javaClass.simpleName })
            if (options.captureTaps) TapWatcher.attach(a.window)
        }
        override fun onActivityPaused(a: Activity) {}
        override fun onActivityStopped(a: Activity) {
            if (started == 0) return // an Activity that started before configure, never adopted
            if (--started == 0) {
                backgroundedAt = System.currentTimeMillis()
                if (options.captureLifecycle) core?.track("app_backgrounded")
                placements?.onBackground()
                core?.flush()
            }
        }
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        override fun onActivityDestroyed(a: Activity) {}
    }

    private var openLogged = false

    /** `element_tapped` for clickable views, via the window's touch callback. */
    private object TapWatcher {
        fun attach(window: Window) {
            val cb = window.callback ?: return
            if (cb is Wrapped) return
            window.callback = Wrapped(cb, WeakReference(window))
        }

        class Wrapped(private val inner: Window.Callback, private val window: WeakReference<Window>) : Window.Callback by inner {
            override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
                val handled = inner.dispatchTouchEvent(event)
                if (event?.actionMasked == MotionEvent.ACTION_UP) {
                    window.get()?.decorView?.let { root -> find(root, event.rawX.toInt(), event.rawY.toInt())?.let { report(it) } }
                }
                return handled
            }
        }

        fun find(v: View, x: Int, y: Int): View? {
            if (v.visibility != View.VISIBLE) return null
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            if (x < loc[0] || y < loc[1] || x > loc[0] + v.width || y > loc[1] + v.height) return null
            if (v is ViewGroup) for (i in v.childCount - 1 downTo 0) find(v.getChildAt(i), x, y)?.let { return it }
            return if (v.isClickable) v else null
        }

        fun report(v: View) {
            val id = if (v.id != View.NO_ID) try { v.resources.getResourceEntryName(v.id) } catch (_: Exception) { null } else null
            val text = (v as? TextView)?.text?.toString()?.takeIf { it.isNotBlank() }
            val label = v.contentDescription?.toString() ?: text ?: id ?: "unlabeled"
            val props = linkedMapOf<String, Json>("element" to Json.Str(Delivery.truncate(label, 100)), "component" to Json.Str(v.javaClass.simpleName),
                "via" to Json.Str("press"), "screen" to Json.Str(screen?.first ?: "Home"))
            id?.let { props["element_id"] = Json.Str(it) }
            if (v is CompoundButton) props["element_value"] = Json.Bool(v.isChecked)
            else if (v is Button) props["element_text"] = Json.Str(Delivery.truncate(v.text.toString(), 100))
            core?.track("element_tapped", properties = props)
        }
    }
}

/** Errors for Issues: handled, uncaught (sent next launch), and ANRs and crashes from ApplicationExitInfo (API 30+). */
class ErrorReporter(private val core: Core, private val release: String?, private val environment: String,
                    private val wrapper: String? = null) {
    private val pending = ArrayList<Json>()
    private val breadcrumbs = ArrayList<Json>()
    @Volatile private var sending = false

    init { core.store.getJson("errors_v1")?.arr?.let { pending.addAll(it) } }

    @Synchronized fun addBreadcrumb(name: String) {
        breadcrumbs.add(Json.obj("category" to "event", "message" to name, "ts" to Iso.format(core.now())))
        while (breadcrumbs.size > 30) breadcrumbs.removeAt(0)
    }

    @Synchronized private fun persist() = core.store.setJson("errors_v1", Json.Arr(ArrayList(pending)))

    fun event(excType: String, message: String?, stack: String?, level: String, mechanism: String, handled: Boolean, eventId: String = Ids.uuidv4(), native: JsonMap? = null): JsonMap {
        val ids = core.ids()
        val e = linkedMapOf<String, Json>("event_id" to Json.Str(eventId), "exc_type" to Json.Str(excType.take(120)), "level" to Json.Str(level),
            "mechanism" to Json.Str(mechanism), "handled" to Json.Bool(handled), "platform" to Json.Str("android"), "environment" to Json.Str(environment),
            "ts" to Json.Str(Iso.format(core.now())), "contexts" to Json.obj("app" to listOfNotNull("sdk" to SdkInfo.HEADER, wrapper?.let { "wrapper" to it }).toMap(),
                "os" to mapOf("name" to "Android", "version" to Build.VERSION.RELEASE)))
        release?.let { e["release"] = Json.Str(it) }
        message?.let { e["message"] = Json.Str(it.take(500)) }
        stack?.takeIf { it.isNotEmpty() }?.let { e["stack"] = Json.Str(it.take(64_000)) }
        ids.accountId?.let { e["user_id"] = Json.Str(it.take(120)) }
        core.currentSessionId()?.let { e["session_id"] = Json.Str(it) }
        synchronized(this) { if (breadcrumbs.isNotEmpty()) e["breadcrumbs"] = Json.Arr(ArrayList(breadcrumbs)) }
        native?.let { e["native"] = Json.Obj(it) }
        return e
    }

    fun enqueue(e: JsonMap, sendNow: Boolean = true) {
        if (core.isOptedOut) return
        synchronized(this) { pending.add(Json.Obj(e)); while (pending.size > 100) pending.removeAt(0) }
        persist()
        if (sendNow) send()
    }

    fun capture(t: Throwable, props: Map<String, Any?> = emptyMap()) {
        val e = event(t.javaClass.simpleName, t.message, android.util.Log.getStackTraceString(t), "error", "manual", true).toMutableMap()
        if (props.isNotEmpty()) e["tags"] = Json.of(props)
        enqueue(e)
    }

    fun captureMessage(m: String, level: String) = enqueue(event("Message", m, null, level, "manual", true))

    /**
     * An error raised in a wrapping SDK's own language (a Dart exception from
     * `fictura_flutter`): its type, message and stack text, which this SDK can't see.
     * `contexts.runtime.name` tells the backend how to read the stack.
     */
    fun captureForeign(type: String, message: String?, stack: String?, level: String, handled: Boolean, mechanism: String,
                       runtime: String, props: Map<String, Any?> = emptyMap()) {
        val e = event(type.trim().ifEmpty { "Error" }, message, stack, level, mechanism.take(40), handled).toMutableMap()
        val contexts = (e["contexts"] as? Json.Obj)?.v?.toMutableMap() ?: linkedMapOf()
        contexts["runtime"] = Json.obj("name" to runtime.take(40))
        e["contexts"] = Json.Obj(contexts)
        if (props.isNotEmpty()) e["tags"] = Json.of(props)
        enqueue(e)
    }

    fun installUncaughtHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            try {
                val e = event(t.javaClass.simpleName, t.message, android.util.Log.getStackTraceString(t), "fatal", "uncaught", false)
                synchronized(this) { pending.add(Json.Obj(e)) }
                persist() // the process is about to die: sent on the next launch
            } catch (_: Throwable) { }
            previous?.uncaughtException(thread, t)
        }
    }

    /** ANRs and native crashes the system recorded since the last launch (API 30+). */
    fun readExitInfo(ctx: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val since = core.store.get("exit_info_since_v1")?.toLongOrNull() ?: System.currentTimeMillis()
            var newest = since
            for (info in am.getHistoricalProcessExitReasons(null, 0, 10)) {
                if (info.timestamp <= since) continue
                newest = maxOf(newest, info.timestamp)
                val (type, level) = when (info.reason) {
                    ApplicationExitInfo.REASON_ANR -> "ANR" to "error"
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "NativeCrash" to "fatal"
                    else -> continue // JVM crashes are reported by the uncaught handler with their stack
                }
                val trace = if (info.reason == ApplicationExitInfo.REASON_ANR) try { info.traceInputStream?.use { it.readBytes().toString(Charsets.UTF_8) } } catch (_: Exception) { null } else null
                val main = trace?.split(Regex("\\n\\s*\\n"))?.firstOrNull { it.startsWith("\"main\"") }
                enqueue(event(type, info.description ?: type, main?.take(64_000), level, "android_exit_info", false,
                    eventId = Ids.format(Ids.sha256("exit:${info.pid}:${info.timestamp}").copyOf(16).also { it[6] = ((it[6].toInt() and 0x0f) or 0x40).toByte(); it[8] = ((it[8].toInt() and 0x3f) or 0x80).toByte() }),
                    native = mapOf("kind" to Json.Str("android_exit_info"), "reason" to Json.of(info.reason), "importance" to Json.of(info.importance))), sendNow = false)
            }
            core.store.set("exit_info_since_v1", newest.toString())
        } catch (_: Exception) { }
    }

    fun send() {
        val batch: List<Json>
        synchronized(this) {
            if (sending || pending.isEmpty()) return
            sending = true
            batch = pending.take(20)
        }
        val ids = core.ids()
        val body = Json.stringify(Json.obj("events" to batch, "sent_at" to Iso.format(core.now())))
        core.transport.send(core.api.request("api/v1/errors", "POST", body = body, deviceId = ids.deviceId, accountId = ids.accountId)) { res ->
            val done = res.ok || (res.status in 400..499 && res.status != 429)
            val more: Boolean
            synchronized(this) {
                sending = false
                if (done) pending.removeAll(batch.toSet())
                more = done && pending.isNotEmpty()
            }
            persist()
            if (more) send()
        }
    }

    val pendingCount: Int get() = synchronized(this) { pending.size }
}
