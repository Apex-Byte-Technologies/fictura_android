package co.fictura.sdk

import java.util.concurrent.TimeUnit

/** GET /api/v1/sdk/config, cached so a cold start offline still has the last config. */
class RemoteConfig(private val core: Core, private val locale: () -> String?) {
    @Volatile var current: JsonMap = core.store.getJson("config_v1")?.obj ?: emptyMap()
        private set
    @Volatile var source = if (current.isEmpty() && core.store.get("config_v1") == null) "none" else "cache"
        private set
    private var etag: String? = core.store.get("config_etag_v1")
    private var fetchedAt = 0L
    private var lastAttempt = 0L
    private var inflight = false
    private val waiters = ArrayList<(Boolean) -> Unit>()

    val hasConfig get() = source != "none"
    fun isToolEnabled(key: String) = current["tools"]?.get(key)?.bool != false
    val products: Map<String, String> get() = current["products"]?.obj?.mapNotNull { (k, v) -> v.string?.let { k to it } }?.toMap() ?: emptyMap()
    val paywalls: JsonMap get() = current["paywalls"]?.obj ?: emptyMap()
    val webCheckoutUrl: String? get() = current["web_checkout"]?.get("url")?.string

    private fun applySettings() {
        val disabled = current["disabled_events"]?.arr?.mapNotNull { it.string }?.toSet() ?: emptySet()
        val sampling = current["sampling"]?.obj?.mapNotNull { (k, v) -> v.double?.let { k to it } }?.toMap() ?: emptyMap()
        core.applyEventSettings(disabled, sampling)
    }

    init { if (hasConfig) applySettings() }

    fun apply(cfg: JsonMap, tag: String?) {
        current = cfg; source = "network"; etag = tag; fetchedAt = core.now()
        core.store.setJson("config_v1", Json.Obj(cfg))
        core.store.set("config_etag_v1", tag)
        applySettings()
    }

    @Synchronized
    fun refresh(force: Boolean = false, done: ((Boolean) -> Unit)? = null) {
        val now = core.now()
        if (!force && (now - fetchedAt < 5 * 60_000 || (now - lastAttempt < 10_000 && !inflight))) { done?.invoke(hasConfig); return }
        done?.let { waiters.add(it) }
        if (inflight) return
        inflight = true
        lastAttempt = now
        val ids = core.ids()
        val req = core.api.request("api/v1/sdk/config", query = mapOf(
            "sv" to SdkInfo.PAYWALL_SCHEMA.toString(), "cv" to SdkInfo.CONFIG_CONTRACT.toString(), "device_id" to ids.deviceId,
            "revenuecat_id" to ids.revenuecatId, "account_id" to ids.accountId, "platform" to "android", "locale" to locale(),
        ), deviceId = ids.deviceId, accountId = ids.accountId)
        val withTag = etag?.let { req.copy(headers = req.headers + ("If-None-Match" to it)) } ?: req
        core.transport.send(withTag) { res ->
            when {
                res.status == 304 -> fetchedAt = core.now()
                res.ok -> Json.parse(res.body)?.obj?.let { apply(it, res.header("etag")) }
            }
            val w: List<(Boolean) -> Unit>
            synchronized(this) { inflight = false; w = ArrayList(waiters); waiters.clear() }
            w.forEach { try { it(hasConfig) } catch (_: Exception) { } }
        }
    }
}

/** A product a paywall can sell (`window.__FICTURA__.packages`). */
data class FicturaPackage(
    val id: String,
    val productId: String,
    val priceString: String,
    val priceMicros: Long,
    val currency: String,
    val period: String,
    val trialDays: Int? = null,
    val trialEligible: Boolean? = null,
    val title: String? = null,
) {
    fun toJson() = Json.obj("id" to id, "product_id" to productId, "price_string" to priceString, "price_micros" to priceMicros,
        "currency" to currency, "period" to period, "trial_days" to trialDays, "trial_eligible" to trialEligible, "title" to title)
}

data class PurchaseContext(val placement: String, val paywallId: String, val campaignId: String?, val audienceId: String?,
                           val experimentId: String?, val variantId: String?)

sealed class PurchaseResult {
    data class Purchased(val transactionId: String? = null, val periodType: String? = null) : PurchaseResult()
    object Cancelled : PurchaseResult()
    data class Failed(val error: String) : PurchaseResult()
}

/**
 * How paywalls buy. An app on RevenueCat implements this with three calls (see
 * the README), so RevenueCat records the purchase and its webhook brings the
 * revenue to Fictura. Callbacks may be called on any thread.
 */
interface PurchaseController {
    fun packages(productIds: List<String>, done: (List<FicturaPackage>) -> Unit)
    fun purchase(activity: android.app.Activity, pkg: FicturaPackage, context: PurchaseContext, done: (PurchaseResult) -> Unit)
    fun restore(done: (Boolean) -> Unit) = done(false)
    fun isSubscribed(done: (Boolean) -> Unit) = done(false)
}

data class PaywallInfo(
    val paywallId: String, val placement: String, val campaignId: String?, val audienceId: String?,
    val experimentId: String?, val variantId: String?, val featureGating: String, val presentationStyle: String,
    var closeReason: String? = null,
)

/** Callbacks for one `register` call, on the main thread. */
open class PlacementHandler {
    open fun onPresent(info: PaywallInfo) {}
    /** result: purchased, restored or declined. */
    open fun onDismiss(info: PaywallInfo, result: String) {}
    open fun onSkip(reason: String) {}
    open fun onError(error: String) {}
}

/** A paywall the presenter shows. */
class PresentRequest(
    val id: Int, val info: PaywallInfo, val html: String, val version: Int?, val pageConfig: JsonMap, val params: JsonMap,
    val onShown: () -> Unit, val onClose: (result: String, productId: String?, reason: String) -> Unit,
    val onPurchaseCancelled: (String) -> Unit, val onPurchaseFailed: (String, String) -> Unit,
)

fun interface Presenter {
    fun show(request: PresentRequest)
}

/** The placement runtime (src/placements/runtime.ts). */
class PlacementRuntime(private val core: Core, private val config: RemoteConfig, private val deviceFields: (Double, Double, Map<String, List<Double>>, Boolean) -> JsonMap) {
    private var seed: Int
    private var userId: String?
    private var installedAt: Double
    private var occurrences: MutableMap<String, List<Double>>
    private val assignments = LinkedHashMap<String, PlacementEngine.Stored>()
    private val pendingConfirms = ArrayList<Triple<String, String, String>>()
    private var lastCloseAt: Double?
    private val tentative = HashMap<String, String>()
    private var active: Int? = null
    private var nextId = 1
    private var confirming = false
    private val observed = HashMap<String, MutableSet<String>>()
    var firstOpen = false
    var presenter: Presenter? = null
    var controller: () -> PurchaseController? = { null }
    var random: () -> Double = Math::random
    private val work get() = core.work
    private val nowMs get() = core.now().toDouble()

    init {
        val s = core.store.getJson("placements_v1")?.obj
        seed = s?.get("seed")?.int ?: (Math.random() * 100).toInt() % 100
        userId = s?.get("userId")?.string
        installedAt = s?.get("installedAt")?.double ?: nowMs
        occurrences = Occurrences.prune(s?.get("occurrences")?.obj?.mapValues { (_, v) -> v.arr?.mapNotNull { it.double } ?: emptyList() } ?: emptyMap(), nowMs).toMutableMap()
        s?.get("assignments")?.obj?.forEach { (k, v) ->
            v["variant_id"]?.string?.let { assignments[k] = PlacementEngine.Stored(it, v["experiment_id"]?.string, v["placement"]?.string, v["confirmed"]?.bool) }
        }
        s?.get("pendingConfirms")?.arr?.forEach { c -> val o = c.obj ?: return@forEach; pendingConfirms.add(Triple(o["audience_id"]?.string ?: return@forEach, o["variant_id"]?.string ?: return@forEach, o["placement"]?.string ?: "")) }
        lastCloseAt = s?.get("lastCloseAt")?.double
        if (s == null) save()
    }

    private fun save() {
        core.store.setJson("placements_v1", Json.Obj(linkedMapOf(
            "seed" to Json.of(seed), "userId" to Json.of(userId), "installedAt" to Json.of(installedAt),
            "occurrences" to Json.of(occurrences),
            "assignments" to Json.Obj(assignments.mapValues { (_, a) -> Json.obj("variant_id" to a.variantId, "experiment_id" to a.experimentId, "placement" to a.placement, "confirmed" to a.confirmed) }),
            "pendingConfirms" to Json.Arr(pendingConfirms.map { Json.obj("audience_id" to it.first, "variant_id" to it.second, "placement" to it.third) }),
            "lastCloseAt" to Json.of(lastCloseAt),
        )))
    }

    val installedAtMs get() = installedAt
    fun occurrencesSnapshot(): Map<String, List<Double>> = HashMap(occurrences)

    fun identify(id: String) = work.execute {
        if (userId == id) return@execute
        userId = id; seed = PlacementEngine.seedForUser(id); assignments.clear(); save()
        val ids = core.ids()
        core.transport.send(core.api.request("api/v1/assignments", query = mapOf("revenuecat_id" to ids.revenuecatId), deviceId = ids.deviceId, accountId = ids.accountId)) { res ->
            if (!res.ok) return@send
            work.execute {
                Json.parse(res.body)?.get("assignments")?.arr?.forEach { a ->
                    val aud = a["audience_id"]?.string ?: return@forEach
                    val v = a["variant_id"]?.string ?: return@forEach
                    assignments[aud] = PlacementEngine.Stored(v, a["experiment_id"]?.string, a["placement"]?.string, true)
                }
                save()
            }
        }
    }

    fun reset() = work.execute { seed = (Math.random() * 100).toInt() % 100; userId = null; occurrences.clear(); assignments.clear(); pendingConfirms.clear(); tentative.clear(); save() }

    companion object {
        val NO_CHAIN = setOf("paywall_decline", "transaction_abandon", "transaction_fail")
        fun sanitize(input: Map<String, Any?>?): JsonMap {
            val out = LinkedHashMap<String, Json>()
            for ((k, raw) in input ?: emptyMap()) {
                if (out.size >= 50) break
                if (k.isEmpty() || k.startsWith("$")) continue
                when (val v = Json.of(raw)) {
                    is Json.Str -> out[k] = Json.Str(v.v.take(200))
                    is Json.Num, is Json.Bool -> out[k] = v
                    else -> {}
                }
            }
            return out
        }
    }

    fun evaluate(placement: String, params: JsonMap, subscribed: Boolean): PlacementEngine.Resolution {
        if (!config.isToolEnabled("paywalls")) return PlacementEngine.Resolution("placement_paused", skipReason = "placement_paused")
        val legacy = {
            val pw = config.paywalls[placement]?.obj
            if (pw == null) PlacementEngine.Resolution("placement_not_found", skipReason = "placement_not_found")
            else PlacementEngine.Resolution("paywall", paywallId = pw["id"]?.string, paywall = pw)
        }
        val cfg = config.current
        if (!PlacementEngine.hasPlacementsBlock(cfg)) return legacy()
        val uc = cfg["user_context"]?.obj
        val hint = uc?.get("seed_hint")?.int
        val s = if (userId == null && hint != null && hint in 0..99) hint else seed
        val a = HashMap<String, PlacementEngine.Stored>()
        tentative.forEach { (k, v) -> a[k] = PlacementEngine.Stored(v) }
        a.putAll(assignments)
        val now = nowMs
        val ctx = RuleContext(core.allAttributes(), s, userId != null || core.ids().accountId != null, params, placement,
            deviceFields(now, installedAt, occurrences, firstOpen), uc?.get("country")?.string, uc?.get("billing_state")?.string,
            uc?.get("attribution")?.obj, now, { emptyList() }, installedAt)
        val r = PlacementEngine.resolve(PlacementEngine.Input(cfg, placement, params, now, subscribed, ctx, occurrences, a, random))
        r.assignment?.takeIf { it.from == "random" }?.let { tentative[it.audienceId] = it.variantId }
        if (r.type == "placement_not_found" && config.paywalls[placement] != null) return legacy()
        return r
    }

    private fun commit(r: PlacementEngine.Resolution, placement: String, params: JsonMap) {
        occurrences[Occurrences.triggerKey(placement)] = Occurrences.record(occurrences[Occurrences.triggerKey(placement)], nowMs)
        r.assignment?.let { a ->
            tentative.remove(a.audienceId)
            val item = Triple(a.audienceId, a.variantId, a.placement)
            val local = assignments[a.audienceId]
            if (local != null && local.variantId == a.variantId) {
                if (local.confirmed != true && item !in pendingConfirms) pendingConfirms.add(item)
            } else {
                assignments[a.audienceId] = PlacementEngine.Stored(a.variantId, a.experimentId, a.placement, a.from == "server")
                if (a.from != "server") pendingConfirms.add(item)
            }
        }
        save()
        flushConfirms()
        val props = linkedMapOf<String, Json>(
            "placement" to Json.Str(placement), "result" to Json.Str(r.type), "campaign_id" to Json.of(r.campaignId),
            "audience_id" to Json.of(r.audienceId), "experiment_id" to Json.of(r.experimentId), "variant_id" to Json.of(r.variantId),
            "paywall_id" to Json.of(r.paywallId), "params" to Json.Obj(params))
        r.unmatched.forEach { (id, why) -> props["unmatched_$id"] = Json.Str(why) }
        core.track("placement_triggered", properties = props)
    }

    private fun request(placement: String, status: String, reason: String?) =
        core.track("paywall_presentation_request", properties = mapOf("placement" to Json.Str(placement), "status" to Json.Str(status), "status_reason" to Json.of(reason)))

    private fun recordAudience(id: String) {
        occurrences[Occurrences.audienceKey(id)] = Occurrences.record(occurrences[Occurrences.audienceKey(id)], nowMs)
        occurrences[Occurrences.PAYWALL_VIEW_KEY] = Occurrences.record(occurrences[Occurrences.PAYWALL_VIEW_KEY], nowMs)
        save()
    }

    fun flushConfirms() {
        if (confirming || pendingConfirms.isEmpty()) return
        confirming = true
        val items = pendingConfirms.take(25)
        val ids = core.ids()
        val body = Json.Obj(linkedMapOf<String, Json>("assignments" to Json.Arr(items.map { Json.obj("audience_id" to it.first, "variant_id" to it.second, "placement" to it.third) }))
            .apply { ids.revenuecatId?.let { put("revenuecat_id", Json.Str(it)) } })
        core.transport.send(core.api.request("api/v1/assignments/confirm", "POST", body = Json.stringify(body), deviceId = ids.deviceId, accountId = ids.accountId)) { res ->
            work.execute {
                confirming = false
                if (!res.ok) return@execute
                pendingConfirms.removeAll(items.toSet())
                items.forEach { i -> assignments[i.first]?.let { assignments[i.first] = it.copy(confirmed = true) } }
                Json.parse(res.body)?.get("assignments")?.arr?.forEach { ack ->
                    if (ack["status"]?.string != "kept") return@forEach
                    val aud = ack["audience_id"]?.string ?: return@forEach
                    val v = ack["variant_id"]?.string ?: return@forEach
                    assignments[aud]?.let { if (it.variantId != v) assignments[aud] = it.copy(variantId = v, confirmed = true) }
                }
                save()
                if (pendingConfirms.isNotEmpty()) flushConfirms()
            }
        }
    }

    private fun observe(name: String, keys: Collection<String>) {
        if (!Regex("^[a-z0-9_]{1,64}$").matches(name)) return
        val seen = observed[name]
        if (seen != null && seen.containsAll(keys)) return
        observed.getOrPut(name) { HashSet() }.addAll(keys)
        val ids = core.ids()
        val body = Json.obj("placements" to listOf(mapOf("name" to name, "params_keys" to keys.take(100))))
        core.transport.send(core.api.request("api/v1/placements/observe", "POST", body = Json.stringify(body), deviceId = ids.deviceId, accountId = ids.accountId)) { }
    }

    private fun subscribed(done: (Boolean) -> Unit) {
        val c = controller()
        if (c == null) done(config.current["entitlement"]?.get("is_active")?.bool == true) else try { c.isSubscribed(done) } catch (_: Exception) { done(false) }
    }

    /** `register`: decide, present, then run `feature` per gating. Callbacks on the main thread via `post`. */
    fun register(placement: String, params: JsonMap, handler: PlacementHandler?, feature: (() -> Unit)?, post: (() -> Unit) -> Unit, implicit: Boolean = false) {
        fun finish(kind: String, reason: String? = null, result: String? = null, close: String? = null, gating: String? = null, info: PaywallInfo? = null) = post {
            try {
                when (kind) {
                    "skipped" -> handler?.onSkip(reason ?: "no_audience_match")
                    "error" -> handler?.onError(reason ?: "error")
                    "dismissed" -> { info?.let { handler?.onDismiss(it, result ?: "declined") }; if (close == "webview_failed" && gating == "gated") handler?.onError("webview_failed") }
                }
                if (feature != null && PlacementEngine.shouldRunFeature(kind, result, close, gating)) feature()
            } catch (_: Exception) { }
        }
        work.execute { if (!implicit) observe(placement, params.keys) }
        config.refresh { has ->
            if (!has) { if (!implicit) { request(placement, "timeout", "no_config"); finish("error", "no_config") }; return@refresh }
            subscribed { sub ->
                work.execute {
                    if (implicit && (config.current["placements"]?.get(placement) == null || active != null)) return@execute
                    val r = evaluate(placement, params, sub)
                    commit(r, placement, params)
                    when (r.type) {
                        "paywall" -> when {
                            active != null -> { request(placement, "no_presentation", "paywall_already_presented"); finish("error", "paywall_already_presented") }
                            presenter == null -> { request(placement, "no_presentation", "no_presenter"); finish("error", "no_presenter") }
                            else -> { request(placement, "presentation", null); present(r, placement, params, handler, ::finish) }
                        }
                        "paywall_not_available" -> { request(placement, "no_presentation", "paywall_not_available"); finish("error", "paywall_not_available") }
                        "holdout" -> { r.audienceId?.let { recordAudience(it) }; request(placement, "no_presentation", "holdout"); finish("skipped", "holdout") }
                        else -> { val reason = r.skipReason ?: "no_audience_match"; request(placement, "no_presentation", reason); finish("skipped", reason) }
                    }
                }
            }
        }
    }

    private fun present(r: PlacementEngine.Resolution, placement: String, params: JsonMap, handler: PlacementHandler?,
                        finish: (String, String?, String?, String?, String?, PaywallInfo?) -> Unit) {
        val pw = r.paywall ?: emptyMap()
        val cfg = pw["config"]?.obj ?: emptyMap()
        val info = PaywallInfo(r.paywallId ?: "", placement, r.campaignId, r.audienceId, r.experimentId, r.variantId,
            pw["feature_gating"]?.string ?: "non_gated", pw["presentation_style"]?.string ?: "fullscreen")
        val id = nextId++
        active = id
        var shown = false
        var closed = false
        val req = PresentRequest(id, info, cfg["html"]?.string ?: "", pw["version"]?.int, cfg, params,
            onShown = { work.execute { if (!shown) { shown = true; r.audienceId?.let { recordAudience(it) } } }; handler?.let { h -> h.onPresent(info) } },
            onClose = { result, _, reason ->
                work.execute {
                    if (closed) return@execute
                    closed = true
                    if (active == id) active = null
                    info.closeReason = reason
                    finish("dismissed", null, result, reason, info.featureGating, info)
                    if (result == "declined" && reason == "manual" && placement !in NO_CHAIN) {
                        work.schedule({ register("paywall_decline", mapOf("paywall_id" to Json.Str(info.paywallId), "source_placement" to Json.Str(placement)), null, null, { it() }, true) }, 400, TimeUnit.MILLISECONDS)
                    }
                }
            },
            onPurchaseCancelled = { pkg -> chain("transaction_abandon", info, pkg, null) },
            onPurchaseFailed = { pkg, e -> chain("transaction_fail", info, pkg, e) })
        presenter!!.show(req)
    }

    private fun chain(name: String, info: PaywallInfo, pkg: String, error: String?) = work.execute {
        if (info.placement in NO_CHAIN || config.current["placements"]?.get(name) == null) return@execute
        val p = linkedMapOf<String, Json>("paywall_id" to Json.Str(info.paywallId), "source_placement" to Json.Str(info.placement), "product_id" to Json.Str(pkg))
        error?.let { p["error"] = Json.Str(it) }
        work.schedule({ register(name, p, null, null, { it() }, true) }, 400, TimeUnit.MILLISECONDS)
    }

    fun onLaunch(isFirstOpen: Boolean) = work.execute {
        firstOpen = isFirstOpen
        val sessionStart = lastCloseAt?.let { nowMs - it > 3_600_000 } ?: true
        if (isFirstOpen) register("app_install", emptyMap(), null, null, { it() }, true)
        register("app_launch", emptyMap(), null, null, { it() }, true)
        if (sessionStart) register("session_start", emptyMap(), null, null, { it() }, true)
    }

    fun onBackground() = work.execute { lastCloseAt = nowMs; save(); flushConfirms() }
    fun onForeground() = work.execute { lastCloseAt?.let { if (nowMs - it > 3_600_000) register("session_start", emptyMap(), null, null, { it() }, true) } }
    fun clearActive() = work.execute { active = null }
}
