package co.fictura.sdk

/** Campaign rule inputs (src/placements/rules.ts). A missing field never matches, except for `not_set`. */
data class RuleContext(
    val user: JsonMap,
    val seed: Int,
    val isLoggedIn: Boolean,
    val params: JsonMap,
    val eventName: String,
    val device: JsonMap,
    val serverCountry: String?,
    val serverBillingState: String?,
    val serverAttribution: JsonMap?,
    val now: Double,
    val occurrences: (String) -> List<Double>,
    val installedAt: Double?,
)

object PlacementRules {
    private const val MINUTE = 60_000.0
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR
    private val VERSION_FIELDS = setOf("device.app_version", "device.sdk_version")

    fun resolve(field: String, c: RuleContext): Json? {
        fun own(o: JsonMap?, k: String) = o?.get(k)?.takeIf { it != Json.Null }
        return when {
            field.isEmpty() -> null
            field == "event_name" -> c.eventName.takeIf { it.isNotEmpty() }?.let { Json.Str(it) }
            field == "user.seed" -> Json.of(c.seed)
            field == "user.is_logged_in" -> Json.Bool(c.isLoggedIn)
            field.startsWith("user.") -> own(c.user, field.substring(5))
            field.startsWith("params.") -> own(c.params, field.substring(7))
            field.startsWith("device.") -> own(c.device, field.substring(7))
            field == "server.country" -> c.serverCountry?.let { Json.Str(it) }
            field == "server.billing_state" -> c.serverBillingState?.let { Json.Str(it) }
            field.startsWith("server.attribution.") -> own(c.serverAttribution, field.substring(19))
            field.startsWith("computed.") -> computed(field.substring(9), c)
            else -> null
        }
    }

    private fun computed(path: String, c: RuleContext): Json? {
        val dot = path.indexOf('.')
        if (dot <= 0) return null
        val kind = path.substring(0, dot)
        val placement = path.substring(dot + 1).ifEmpty { return null }
        val times = c.occurrences(placement)
        val last = times.lastOrNull()
        fun since(u: Double) = last?.let { Json.of(Math.floor((c.now - it) / u).toLong()) }
        fun within(ms: Double) = Json.of(times.count { c.now - it <= ms })
        return when (kind) {
            "minutes_since" -> since(MINUTE)
            "hours_since" -> since(HOUR)
            "days_since" -> since(DAY)
            "placements_in_hour" -> within(HOUR)
            "placements_in_day" -> within(DAY)
            "placements_in_week" -> within(7 * DAY)
            "placements_in_month" -> within(30 * DAY)
            "placements_since_install" -> Json.of(times.count { it >= (c.installedAt ?: 0.0) })
            else -> null
        }
    }

    fun asNumber(v: Json): Double? = when (v) {
        is Json.Num -> v.v.takeIf { it.isFinite() }
        is Json.Str -> v.v.trim().takeIf { it.isNotEmpty() && Regex("^-?\\d+(\\.\\d+)?$").matches(it) }?.toDouble()
        else -> null
    }

    fun asBool(v: Json): Boolean? = when {
        v is Json.Bool -> v.v
        v is Json.Str && v.v == "true" -> true
        v is Json.Str && v.v == "false" -> false
        else -> null
    }

    /** JavaScript's String(v) for scalars. */
    fun jsString(v: Json): String = when (v) {
        is Json.Str -> v.v
        is Json.Bool -> v.v.toString()
        is Json.Num -> if (v.v == Math.floor(v.v) && Math.abs(v.v) < 1e15) v.v.toLong().toString() else v.v.toString()
        Json.Null -> "null"
        is Json.Arr -> v.v.joinToString(",") { jsString(it) }
        is Json.Obj -> "[object Object]"
    }

    private fun sameKind(a: Json, b: Json) = (a is Json.Str && b is Json.Str) || (a is Json.Bool && b is Json.Bool) || (a is Json.Num && b is Json.Num)

    fun looseEqual(a: Json, e: Json): Boolean {
        if (sameKind(a, e)) return if (a is Json.Num && e is Json.Num) a.v == e.v else a == e
        val na = asNumber(a); val ne = asNumber(e)
        if (na != null && ne != null) return na == ne
        val ba = asBool(a); val be = asBool(e)
        if (ba != null && be != null) return ba == be
        return jsString(a) == jsString(e)
    }

    fun compareVersions(a: Json, b: Json): Int? {
        fun parse(v: Json): List<Long>? {
            var s = when (v) { is Json.Str -> v.v; is Json.Num -> jsString(v); else -> return null }.trim()
            if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1)
            val core = s.split('-', '+').firstOrNull()?.takeIf { it.isNotEmpty() } ?: return null
            return core.split('.').map { p -> if (p.isNotEmpty() && p.all { it in '0'..'9' }) p.toLong() else return null }
        }
        val x = parse(a) ?: return null
        val y = parse(b) ?: return null
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrNull(i) ?: 0) - (y.getOrNull(i) ?: 0)
            if (d != 0L) return if (d > 0) 1 else -1
        }
        return 0
    }

    private fun order(field: String, a: Json, e: Json): Int? {
        if (field in VERSION_FIELDS) return compareVersions(a, e)
        val x = asNumber(a) ?: return null
        val y = asNumber(e) ?: return null
        return x.compareTo(y).coerceIn(-1, 1)
    }

    fun evaluateCondition(cond: JsonMap, c: RuleContext): Boolean {
        val field = cond["field"]?.string ?: return false
        val op = cond["operator"]?.string ?: return false
        val actual = resolve(field, c)
        val value = cond["value"]
        when (op) {
            "is_set" -> return actual != null
            "not_set" -> return actual == null
            "is_true" -> return actual?.let { asBool(it) } == true
            "is_false" -> return actual?.let { asBool(it) } == false
        }
        if (actual == null) return false
        val scalar = value?.takeIf { it !is Json.Arr }
        return when (op) {
            "eq" -> scalar?.let { looseEqual(actual, it) } ?: false
            "neq" -> scalar?.let { !looseEqual(actual, it) } ?: false
            "gt", "gte", "lt", "lte" -> {
                val o = scalar?.let { order(field, actual, it) } ?: return false
                when (op) { "gt" -> o > 0; "gte" -> o >= 0; "lt" -> o < 0; else -> o <= 0 }
            }
            "contains", "not_contains" -> {
                if (scalar == null || actual !is Json.Str) return false
                val hit = actual.v.contains(jsString(scalar))
                if (op == "contains") hit else !hit
            }
            "in", "not_in" -> {
                val list = value?.arr ?: return false
                val hit = list.any { looseEqual(actual, it) }
                if (op == "in") hit else !hit
            }
            else -> false
        }
    }

    private fun isRule(v: Json) = v["conditions"]?.arr != null

    private fun measure(r: JsonMap, depth: Int = 1): Pair<Int, Int> {
        var d = depth; var n = 0
        for (c in r["conditions"]?.arr ?: emptyList()) {
            if (isRule(c)) { val m = measure(c.obj!!, depth + 1); d = maxOf(d, m.first); n += m.second } else n++
        }
        return d to n
    }

    private fun group(r: JsonMap, c: RuleContext): Boolean {
        val conds = r["conditions"]?.arr ?: emptyList()
        if (conds.isEmpty()) return true
        val op = r["op"]?.string
        if (op != "and" && op != "or") return false
        val results = conds.map { x -> val o = x.obj ?: return@map false; if (isRule(x)) group(o, c) else evaluateCondition(o, c) }
        return if (op == "and") results.all { it } else results.any { it }
    }

    fun evaluate(rule: Json?, c: RuleContext): Boolean {
        if (rule == null || rule == Json.Null) return true
        val o = rule.obj ?: return false
        if (!isRule(rule)) return false
        val (d, n) = measure(o)
        if (d > 3 || n > 25) return false
        return group(o, c)
    }
}

/** Frequency-limit history (src/placements/occurrences.ts). */
object Occurrences {
    private const val MAX = 500
    private const val TTL = 400.0 * 24 * 60 * 60 * 1000
    fun triggerKey(p: String) = "p:$p"
    fun audienceKey(id: String) = "a:$id"
    const val PAYWALL_VIEW_KEY = "pw"

    fun record(list: List<Double>?, now: Double): List<Double> {
        val kept = (list ?: emptyList()).filter { now - it <= TTL }.toMutableList()
        kept.add(now); kept.sort()
        return if (kept.size > MAX) kept.takeLast(MAX) else kept
    }

    fun prune(map: Map<String, List<Double>>, now: Double) =
        map.mapValues { (_, l) -> l.filter { now - it <= TTL }.sorted().takeLast(MAX) }.filterValues { it.isNotEmpty() }

    fun limitAllows(limit: Json?, list: List<Double>?, now: Double): Boolean {
        if (limit == null || limit == Json.Null) return true
        val max = limit["occurrences"]?.double?.takeIf { it >= 0 } ?: return false
        val interval = limit["interval_minutes"]
        var minutes: Double? = null
        if (interval != null && interval != Json.Null) minutes = interval.double?.takeIf { it > 0 } ?: return false
        val l = list ?: emptyList()
        val count = if (minutes == null) l.size else l.count { it > now - minutes * 60_000 }
        return count < max
    }
}

/** Which audience, variant and paywall a placement shows (src/placements/engine.ts). */
object PlacementEngine {
    data class Stored(val variantId: String, val experimentId: String? = null, val placement: String? = null, val confirmed: Boolean? = null)
    data class Decision(val audienceId: String, val variantId: String, val experimentId: String?, val placement: String, val from: String)
    data class Resolution(
        val type: String,
        val source: String? = null,
        val campaignId: String? = null,
        val audienceId: String? = null,
        val experimentId: String? = null,
        val variantId: String? = null,
        val paywallId: String? = null,
        val paywall: JsonMap? = null,
        val unmatched: Map<String, String> = emptyMap(),
        val assignment: Decision? = null,
        val skipReason: String? = null,
    )

    class Input(
        val config: JsonMap, val placement: String, val params: JsonMap, val now: Double, val subscribed: Boolean,
        val context: RuleContext, val occurrences: Map<String, List<Double>>, val assignments: Map<String, Stored>, val random: () -> Double,
    )

    fun hasPlacementsBlock(config: JsonMap?) = config?.get("placements")?.obj != null

    private fun inSchedule(a: JsonMap, now: Double): Boolean {
        a["start_at"]?.string?.takeIf { it.isNotEmpty() }?.let { s -> val t = Iso.parse(s)?.toDouble() ?: return false; if (now < t) return false }
        a["end_at"]?.string?.takeIf { it.isNotEmpty() }?.let { s -> val t = Iso.parse(s)?.toDouble() ?: return false; if (now >= t) return false }
        return true
    }

    private fun entitlementAllows(f: String?, subscribed: Boolean) = when (f ?: "unsubscribed") {
        "all" -> true; "unsubscribed" -> !subscribed; "subscribed" -> subscribed; else -> false
    }

    fun mismatch(a: JsonMap, input: Input, c: RuleContext): String? {
        if (!inSchedule(a, input.now)) return "schedule"
        if (!entitlementAllows(a["entitlement_filter"]?.string, input.subscribed)) return "entitlement"
        if (!PlacementRules.evaluate(a["rule"], c)) return "rule"
        if (!Occurrences.limitAllows(a["frequency_limit"], input.occurrences[Occurrences.audienceKey(a["id"]?.string ?: "")], input.now)) return "limit"
        return null
    }

    fun chooseVariant(variants: List<JsonMap>, random: () -> Double): JsonMap? {
        val list = variants.filter { it["id"]?.string != null }
        if (list.isEmpty()) return null
        if (list.size == 1) return list[0]
        val w = list.map { val p = it["percentage"]?.double ?: 0.0; if (p.isFinite() && p > 0) p else 0.0 }
        val sum = w.sum()
        val r = random().coerceIn(0.0, 0.999999999)
        if (sum == 0.0) return list[Math.floor(r * list.size).toInt()]
        val t = r * sum
        var acc = 0.0
        for ((i, x) in w.withIndex()) { acc += x; if (t < acc) return list[i] }
        return list.last()
    }

    private fun pick(a: JsonMap, input: Input): Pair<JsonMap, String>? {
        val variants = a["variants"]?.arr?.mapNotNull { it.obj } ?: emptyList()
        val id = a["id"]?.string ?: ""
        input.assignments[id]?.let { s -> variants.firstOrNull { it["id"]?.string == s.variantId }?.let { return it to "stored" } }
        input.config["assignments_v2"]?.arr?.mapNotNull { it.obj }?.firstOrNull { it["audience_id"]?.string == id }?.let { s ->
            variants.firstOrNull { it["id"]?.string == s["variant_id"]?.string }?.let { return it to "server" }
        }
        return chooseVariant(variants, input.random)?.let { it to "random" }
    }

    fun resolve(input: Input): Resolution {
        val cfg = input.config
        val paywalls = cfg["campaign_paywalls"]?.obj ?: emptyMap()
        cfg["override"]?.obj?.let { ov ->
            val pid = ov["paywall_id"]?.string
            val until = Iso.parse(ov["expires_at"]?.string)?.toDouble()
            val pw = pid?.let { paywalls[it]?.obj }
            if (pid != null && pw != null && until != null && until > input.now) return Resolution("paywall", source = "override", paywallId = pid, paywall = pw)
        }
        val entry = cfg["placements"]?.get(input.placement)?.obj ?: return Resolution("placement_not_found", skipReason = "placement_not_found")
        if (entry["enabled"]?.bool == false) return Resolution("placement_paused", campaignId = entry["campaign_id"]?.string, skipReason = "placement_paused")
        val occ = input.occurrences
        val ctx = input.context.copy(params = input.params, eventName = input.placement, now = input.now,
            occurrences = { occ[Occurrences.triggerKey(it)] ?: emptyList() })
        val unmatched = LinkedHashMap<String, String>()
        fun matchIn(list: List<Json>?): JsonMap? {
            for (raw in list ?: emptyList()) {
                val a = raw.obj ?: continue
                val id = a["id"]?.string ?: continue
                val why = mismatch(a, input, ctx)
                if (why == null) return a else unmatched[id] = why
            }
            return null
        }
        var source = "campaign"
        var campaignId = entry["campaign_id"]?.string
        var audience = matchIn(entry["audiences"]?.arr)
        val def = cfg["default_campaign"]?.obj
        if (audience == null && def?.get("audiences")?.arr != null) {
            audience = matchIn(def["audiences"]?.arr)
            if (audience != null) { source = "default_campaign"; campaignId = def["campaign_id"]?.string }
        }
        val audienceId = audience?.get("id")?.string
        if (audience == null || audienceId == null) {
            return Resolution("no_audience_match", campaignId = entry["campaign_id"]?.string, unmatched = unmatched,
                skipReason = if (input.subscribed && unmatched.values.contains("entitlement")) "user_is_subscribed" else "no_audience_match")
        }
        val exp = audience["experiment_id"]?.string
        val base = Resolution("paywall_not_available", source, campaignId, audienceId, exp, unmatched = unmatched)
        val (variant, from) = pick(audience, input) ?: return base
        val variantId = variant["id"]?.string ?: return base
        val decision = Decision(audienceId, variantId, exp, input.placement, from)
        if (variant["type"]?.string == "holdout") return base.copy(type = "holdout", variantId = variantId, assignment = decision, skipReason = "holdout")
        val pid = variant["paywall_id"]?.string
        val pw = pid?.let { paywalls[it]?.obj } ?: return base.copy(variantId = variantId, paywallId = pid, assignment = decision)
        return base.copy(type = "paywall", variantId = variantId, paywallId = pid, paywall = pw, assignment = decision)
    }

    /** sha256(userId) folded mod 100 (src/placements/sha256.ts seedForUser). */
    fun seedForUser(userId: String): Int {
        var r = 0
        for (b in Ids.sha256(userId)) r = (r * 256 + (b.toInt() and 0xff)) % 100
        return r
    }

    fun shouldRunFeature(kind: String, result: String?, closeReason: String?, gating: String?): Boolean = when (kind) {
        "skipped" -> true
        "error" -> false
        else -> when {
            result == "purchased" || result == "restored" -> true
            closeReason == "for_next_paywall" -> false
            else -> gating != "gated"
        }
    }
}
