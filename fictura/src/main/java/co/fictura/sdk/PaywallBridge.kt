package co.fictura.sdk

/** What a showing paywall needs from the screen hosting it (a WebView on Android). */
interface PaywallHost {
    fun evaluate(js: String)
    /** result: purchased, restored or declined. */
    fun close(result: String, productId: String?, reason: String)
    fun openUrl(url: String, inApp: Boolean)
    fun haptic(kind: String)
    fun alert(message: String)
    fun purchase(pkg: FicturaPackage, context: PurchaseContext, done: (PurchaseResult) -> Unit)
    fun requestPermission(name: String, done: (Boolean) -> Unit) = done(false)
}

/**
 * One showing of a served HTML paywall: its events, purchase and restore, and the
 * messages from the page's `window.__FICTURA__` bridge (src/paywall/WebviewPaywall.tsx).
 * No Android types, so it is unit-tested on the JVM.
 */
class PaywallBridge(
    private val core: Core,
    private val controller: PurchaseController?,
    val request: PresentRequest,
    private val slotMap: Map<String, String>,
    private val pageValues: JsonMap,
    private val webCheckoutUrl: String?,
    private val presentPlacement: (String) -> Unit,
    private val customAction: ((String, Map<String, Any?>) -> Map<String, Any?>?)? = null,
) {
    val sessionId = Ids.uuidv4()
    var host: PaywallHost? = null
    @Volatile var packages: List<FicturaPackage> = emptyList()
        private set
    private var ready = false
    private var shownAt: Long? = null
    private var dismissed = false
    @Volatile private var busy = false
    val html: String = substituteParams(request.html, request.params)

    private val info get() = request.info

    fun event(name: String, status: String = "info", props: JsonMap = emptyMap()) {
        val all = linkedMapOf<String, Json>(
            "placement" to Json.Str(info.placement), "paywall_id" to Json.Str(info.paywallId), "paywall_version" to Json.of(request.version),
            "campaign_id" to Json.of(info.campaignId), "audience_id" to Json.of(info.audienceId), "experiment_id" to Json.of(info.experimentId),
            "variant_id" to Json.of(info.variantId), "presented_by" to Json.Str("placement"), "paywall_session_id" to Json.Str(sessionId), "screen" to Json.Null)
        all.putAll(props)
        core.track(name, status, all)
    }

    fun logDismissed(reason: String, result: String) {
        if (dismissed) return
        dismissed = true
        event("paywall_dismissed", props = mapOf("close_reason" to Json.Str(reason), "result" to Json.Str(result),
            "time_on_paywall_ms" to Json.of(shownAt?.let { maxOf(0L, core.now() - it) } ?: 0L)))
    }

    /** `paywall_shown` (the exposure) fires once the paywall is on screen. */
    fun markShown() {
        if (shownAt != null) return
        shownAt = core.now()
        event("paywall_shown")
        request.onShown()
    }

    fun loadPackages(done: () -> Unit) {
        val c = controller ?: return done()
        val ids = slotMap.values.toSet().sorted()
        try {
            c.packages(ids) { found ->
                packages = found
                if (ready) host?.evaluate(packagesUpdateScript())
                if (found.isNotEmpty()) {
                    val body = Json.obj("platform" to "android", "products" to found.take(50).map { mapOf("product_id" to it.productId, "period" to it.period, "package_id" to it.id) })
                    core.transport.send(core.api.request("api/v1/sdk/products-seen", "POST", body = Json.stringify(body))) { }
                }
                done()
            }
        } catch (_: Exception) {
            done()
        }
    }

    fun slots(): Map<String, FicturaPackage> {
        fun base(id: String) = id.substringBefore(':')
        val out = LinkedHashMap<String, FicturaPackage>()
        for ((slot, pid) in slotMap) (packages.firstOrNull { it.productId == pid } ?: packages.firstOrNull { base(it.productId) == base(pid) })?.let { out[slot] = it }
        return out
    }

    private val trialEligible: Boolean?
        get() = packages.filter { it.trialDays != null }.map { it.trialEligible }.takeIf { it.isNotEmpty() }?.let { flags -> flags.any { it != false } }

    private fun lit(j: Json) = Json.stringify(j).replace("</", "<\\/")
    private fun pkgs() = Json.Arr(packages.map { it.toJson() })
    private fun slotsJson() = Json.Obj(slots().mapValues { it.value.toJson() })

    /** Runs before the design's own scripts. The page's messages reach `FicturaAndroid.postMessage`. */
    fun injectedScript(binder: String): String {
        val p = pageValues
        return """
window.ReactNativeWebView = window.ReactNativeWebView || { postMessage: function (s) { if (window.FicturaAndroid) window.FicturaAndroid.postMessage(typeof s === 'string' ? s : JSON.stringify(s)); } };
window.__FICTURA__ = {
  packages: ${lit(pkgs())},
  slots: ${lit(slotsJson())},
  assets: ${lit(p["assets"] ?: Json.Obj(emptyMap()))},
  copy: ${lit(p["copy"] ?: Json.Obj(emptyMap()))},
  colors: ${lit(p["colors"] ?: Json.Obj(emptyMap()))},
  ineligible_plans: ${lit(p["ineligible_plans"] ?: Json.Arr(emptyList()))},
  legal: ${lit(p["legal"] ?: Json.obj("termsUrl" to null, "privacyUrl" to null))},
  trial_eligible: ${lit(Json.of(trialEligible))},
  placement: ${lit(Json.Str(info.placement))},
  webCheckoutAvailable: ${webCheckoutUrl != null},
  device: ${lit(p["device"] ?: Json.Obj(emptyMap()))},
  user: ${lit(p["user"] ?: Json.Obj(emptyMap()))},
  params: ${lit(Json.Obj(request.params))},
  capabilities: ${lit(Json.of(CAPABILITIES))},
  openUrl: function (url, mode) { this._send({ type: 'open_url', url: url, mode: mode }); },
  haptic: function (kind) { this._send({ type: 'haptic', kind: kind }); },
  requestReview: function () { this._send({ type: 'review' }); },
  setAttribute: function (key, value) { this._send({ type: 'set_attribute', key: key, value: value }); },
  presentPlacement: function (name) { this._send({ type: 'present_placement', placement: name }); },
  customAction: function (name, payload) { return this._call('custom_action', [name, payload]); },
  requestPermission: function (p) { return this._call('request_permission', [p]); },
  _calls: {}, _seq: 0,
  _call: function (name, args) { var self = this, id = 'c' + (++self._seq); return new Promise(function (resolve) { self._calls[id] = resolve; self._send({ type: 'call', id: id, name: name, args: args }); }); },
  _reply: function (id, value) { var r = this._calls[id]; delete this._calls[id]; if (r) r(value); },
  purchase: function (id) { this._send({ type: 'purchase', package_id: id }); },
  openWebCheckout: function (id) { this._send({ type: 'web_checkout', package_id: id }); },
  restore: function () { this._send({ type: 'restore' }); },
  close: function () { this._send({ type: 'close' }); },
  track: function (event, properties) { this._send({ type: 'track', event: event, properties: properties }); },
  _send: function (m) { window.ReactNativeWebView.postMessage(JSON.stringify(m)); }
};
$binder
window.__FICTURA__._send({ type: 'ready' });
""".trimIndent()
    }

    fun packagesUpdateScript() = """(function(){try{if(!window.__FICTURA__)return;window.__FICTURA__.packages=${lit(pkgs())};window.__FICTURA__.slots=${lit(slotsJson())};window.__FICTURA__.trial_eligible=${lit(Json.of(trialEligible))};if(typeof window.FICTURA_ON_PACKAGES==='function')window.FICTURA_ON_PACKAGES();window.dispatchEvent(new Event('fictura:packages'));if(typeof window.__FICTURA__.bind==='function')window.__FICTURA__.bind();}catch(e){}})();"""

    private fun toPage(js: String) = host?.evaluate("(function(){try{$js}catch(e){}})();")
    private fun purchaseResult(s: String) = toPage("window.dispatchEvent(new CustomEvent('fictura:purchase_result',{detail:{status:${lit(Json.Str(s))}}}));")

    /** One message from the page. Unknown types are ignored. */
    fun handle(raw: String) {
        val msg = Json.parse(raw)?.obj ?: return
        when (msg["type"]?.string) {
            "ready" -> if (!ready) { ready = true; markShown(); if (packages.isNotEmpty()) host?.evaluate(packagesUpdateScript()) }
            "purchase" -> purchase(msg["package_id"]?.string, "native")
            "web_checkout" -> purchase(msg["package_id"]?.string, "web")
            "restore" -> restore()
            "close" -> { logDismissed("manual", "declined"); host?.close("declined", null, "manual") }
            "track" -> msg["event"]?.string?.let { val (n, p) = webEvent(it, msg["properties"]?.obj ?: emptyMap()); event(n, props = p) }
            "open_url" -> msg["url"]?.string?.takeIf { linkOk(it) }?.let { host?.openUrl(it, msg["mode"]?.string == "in_app" && it.startsWith("http", true)) }
            "haptic" -> host?.haptic(msg["kind"]?.string ?: "light")
            "set_attribute" -> {
                val k = msg["key"]?.string ?: return
                val v = msg["value"] ?: return
                if (Regex("^[A-Za-z][A-Za-z0-9_]{0,63}$").matches(k) && (v is Json.Str || v is Json.Num || v is Json.Bool)) core.setAttributes(mapOf(k to v))
            }
            "present_placement" -> msg["placement"]?.string?.takeIf { Regex("^[A-Za-z0-9_.:-]{1,64}$").matches(it) }?.let {
                logDismissed("manual", "declined"); host?.close("declined", null, "for_next_paywall"); presentPlacement(it)
            }
            "call" -> {
                val id = msg["id"]?.string ?: ""
                val args = msg["args"]?.arr ?: emptyList()
                val reply = { v: Json -> toPage("window.__FICTURA__._reply(${lit(Json.Str(id))},${lit(v)});") }
                when {
                    msg["name"]?.string == "custom_action" && args.firstOrNull()?.string != null && customAction != null -> {
                        val out = try { customAction.invoke(args[0].string!!, (args.getOrNull(1)?.obj ?: emptyMap()).mapValues { it.value.any }) } catch (_: Exception) { null }
                        reply(customAnswer(out))
                    }
                    msg["name"]?.string == "request_permission" && args.firstOrNull()?.string != null ->
                        host?.requestPermission(args[0].string!!) { reply(Json.Bool(it)) } ?: reply(Json.Bool(false))
                    else -> reply(Json.Bool(false))
                }
            }
        }
    }

    private fun purchase(packageId: String?, path: String) {
        val id = packageId ?: packages.firstOrNull()?.id
        event("paywall_cta_tapped", props = mapOf("package_id" to Json.of(id), "path" to Json.Str(path)))
        if (busy) return
        busy = true
        if ((path == "web" || controller == null) && webCheckoutUrl != null) {
            event("web_checkout_opened", props = mapOf("package_id" to Json.of(id)))
            host?.openUrl(webCheckoutUrl, false)
            busy = false
            return
        }
        val pkg = packages.firstOrNull { it.id == id }
        if (controller == null || pkg == null) { busy = false; purchaseResult("failed"); return }
        val offer = pkg.trialDays != null && pkg.trialEligible != false
        val base: Map<String, Json> = mapOf("attempt_id" to Json.Str(Ids.uuidv4()), "product_id" to Json.Str(pkg.productId), "package_id" to Json.Str(pkg.id))
        event("purchase_started", props = base + ("offer_type" to Json.of(if (offer) "free_trial" else null)))
        val started = core.now()
        val ctx = PurchaseContext(info.placement, info.paywallId, info.campaignId, info.audienceId, info.experimentId, info.variantId)
        host?.purchase(pkg, ctx) { result ->
            val latency = Json.of(maxOf(0L, core.now() - started))
            when (result) {
                is PurchaseResult.Purchased -> {
                    val p = LinkedHashMap(base)
                    result.transactionId?.let { p["transaction_id"] = Json.Str(it) }
                    result.periodType?.let { p["period_type"] = Json.Str(it) }
                    p["price_local"] = Json.of(pkg.priceMicros / 1_000_000.0); p["currency"] = Json.Str(pkg.currency)
                    p["is_trial"] = Json.Bool(offer); p["latency_ms"] = latency
                    event("purchase_completed", "success", p)
                    purchaseResult("purchased")
                    logDismissed("purchased", "purchased")
                    host?.close("purchased", pkg.productId, "manual")
                }
                PurchaseResult.Cancelled -> {
                    event("purchase_cancelled", props = base + ("latency_ms" to latency))
                    purchaseResult("cancelled"); request.onPurchaseCancelled(pkg.id)
                }
                is PurchaseResult.Failed -> {
                    event("purchase_failed", "failure", base + mapOf("outcome" to Json.Str("unknown"), "error" to Json.Str(result.error), "latency_ms" to latency))
                    purchaseResult("failed"); request.onPurchaseFailed(pkg.id, result.error)
                }
            }
            busy = false
        } ?: run { busy = false }
    }

    private fun restore() {
        val c = controller ?: return
        event("restore_started")
        c.restore { restored ->
            event("restore_completed", if (restored) "success" else "info", mapOf("restored" to Json.Bool(restored)))
            if (restored) { logDismissed("restored", "restored"); host?.close("restored", null, "manual") }
            else host?.alert("We couldn't find an active subscription to restore.")
        }
    }

    companion object {
        val CAPABILITIES = listOf("purchase_result", "open_url", "custom_action", "set_attribute", "request_permission", "present_placement", "haptic")

        fun substituteParams(html: String, params: JsonMap): String {
            if (!html.contains("{{")) return html
            return Regex("\\{\\{\\s*params\\.([A-Za-z0-9_\\-]+)\\s*\\}\\}").replace(html) { m ->
                val v = params[m.groupValues[1]]
                if (v == null || v == Json.Null) "" else PlacementRules.jsString(v).replace("&", "&amp;").replace("<", "&lt;")
                    .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
            }
        }

        fun linkOk(url: String) = url.length <= 600 && Regex("^[a-z][a-z0-9+.-]{1,30}:", RegexOption.IGNORE_CASE).containsMatchIn(url) &&
            !Regex("^(javascript|data|vbscript|file|blob):", RegexOption.IGNORE_CASE).containsMatchIn(url)

        fun webEvent(name: String, props: JsonMap): Pair<String, JsonMap> = when (name) {
            "paywall_screen_viewed", "paywall_page_viewed", "exit_offer_shown", "exit_offer_accepted", "exit_offer_declined" -> name to (props + ("source" to Json.Str("web")))
            "paywall_plan_selected", "paywall_product_selected" -> "paywall_product_selected" to (props + ("source" to Json.Str("web")))
            "paywall_trial_toggled" -> "paywall_interacted" to (props + mapOf("component_type" to Json.Str("switch"), "component_name" to Json.Str("trial_toggle"), "source" to Json.Str("web")))
            else -> "paywall_web_$name" to props
        }

        fun customAnswer(out: Map<String, Any?>?): Json {
            if (out == null) return Json.Bool(false)
            val values = LinkedHashMap<String, Json>()
            (out["values"] as? Map<*, *>)?.entries?.take(20)?.forEach { (k, v) ->
                val key = k.toString()
                if (!Regex("^[a-z][a-z0-9_]{0,39}$").matches(key)) return@forEach
                when (val j = Json.of(v)) { is Json.Str -> values[key] = Json.Str(j.v.take(200)); is Json.Num, is Json.Bool -> values[key] = j; else -> {} }
            }
            return Json.obj("ok" to (out["ok"] != false), "values" to Json.Obj(values))
        }
    }
}
