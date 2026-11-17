package co.fictura.sdk

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Small values kept between launches. */
interface KeyValueStore {
    fun get(key: String): String?
    fun set(key: String, value: String?)
}

class MemoryStore : KeyValueStore {
    private val m = HashMap<String, String>()
    @Synchronized override fun get(key: String) = m[key]
    @Synchronized override fun set(key: String, value: String?) { if (value == null) m.remove(key) else m[key] = value }
}

/** One file per key in the app's no-backup directory. */
class FileStore(dir: File) : KeyValueStore {
    private val root = dir.apply { mkdirs() }
    private fun f(key: String) = File(root, key.replace(Regex("[^A-Za-z0-9._-]"), "_"))
    @Synchronized override fun get(key: String): String? = try { f(key).takeIf { it.exists() }?.readText() } catch (_: Exception) { null }
    @Synchronized override fun set(key: String, value: String?) {
        try {
            val file = f(key)
            if (value == null) file.delete() else {
                val tmp = File(root, file.name + ".tmp")
                tmp.writeText(value)
                tmp.renameTo(file)
            }
        } catch (_: Exception) { /* best effort */ }
    }
}

fun KeyValueStore.getJson(key: String): Json? = Json.parse(get(key))
fun KeyValueStore.setJson(key: String, value: Json?) = set(key, value?.let { Json.stringify(it) })

/** One HTTP exchange. Tests replace it. */
fun interface Transport {
    fun send(request: Request, done: (Response) -> Unit)
}

data class Request(val url: String, val method: String, val headers: Map<String, String>, val body: String?)

/** status 0 means the request never got an answer (offline, DNS, TLS, timeout). */
data class Response(val status: Int, val headers: Map<String, String>, val body: String?) {
    val ok get() = status in 200..299
    fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

class HttpTransport : Transport {
    private val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "fictura-http").apply { isDaemon = true } }
    override fun send(request: Request, done: (Response) -> Unit) {
        pool.execute {
            val res = try {
                val c = URL(request.url).openConnection() as HttpURLConnection
                c.requestMethod = request.method
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                c.useCaches = false
                request.headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                if (request.body != null) {
                    c.doOutput = true
                    c.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
                }
                val status = c.responseCode
                val stream = if (status in 200..399) c.inputStream else c.errorStream
                val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) }
                val headers = c.headerFields.filterKeys { it != null }.mapValues { it.value.joinToString(",") }
                c.disconnect()
                Response(status, headers, body)
            } catch (_: Exception) {
                Response(0, emptyMap(), null)
            }
            try { done(res) } catch (_: Exception) { }
        }
    }
}

/** Builds requests to Fictura's API with the headers every call carries. */
class Api(val base: String, val apiKey: String) {
    fun request(path: String, method: String = "GET", query: Map<String, String?> = emptyMap(), body: String? = null,
                deviceId: String? = null, accountId: String? = null): Request {
        val qs = query.filterValues { it != null }.toSortedMap().entries.joinToString("&") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        val headers = linkedMapOf("Content-Type" to "application/json", "x-api-key" to apiKey, "x-fictura-sdk" to SdkInfo.HEADER)
        deviceId?.let { headers["X-Device-ID"] = it }
        accountId?.let { headers["X-Account-ID"] = it }
        return Request(base.trimEnd('/') + "/" + path.trimStart('/') + (if (qs.isEmpty()) "" else "?$qs"), method, headers, body)
    }
}

data class EventIds(val deviceId: String, val accountId: String?, val revenuecatId: String?)

/**
 * The event pipeline: identity, attributes, sessions, the persistent queue and
 * delivery, in the React Native SDK's exact batch format. All state lives on one
 * single-thread executor.
 */
class Core(
    val api: Api,
    val store: KeyValueStore,
    val transport: Transport,
    val deviceId: String,
    private val everyEvent: JsonMap,
    val now: () -> Long = System::currentTimeMillis,
    private val random: () -> Double = Math::random,
    val maxBatch: Int = 25,
    private val flushIntervalMs: Long = 4000,
    private val sessionIdleMs: Long = 30 * 60 * 1000,
    private val appVersion: String? = null,
    private val country: () -> String? = { null },
) {
    @Volatile private var workThread: Thread? = null
    val work: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "fictura-core").apply { isDaemon = true; workThread = this }
    }

    private data class Queued(val name: String, val status: String, val props: JsonMap, val uuid: String, val timestamp: String, val size: Int, val ids: EventIds)

    private var queue = ArrayList<Queued>()
    private val inflight = HashSet<String>()
    private val dropped = LinkedHashMap<String, Int>()
    private var seq = 0
    private var accountId: String? = null
    private var revenuecatId: String? = null
    private var masterUserId: String? = null
    private val superProps = LinkedHashMap<String, Json>()
    private var attrSet = LinkedHashMap<String, Json>()
    private var attrOnce = LinkedHashMap<String, Json>()
    private var pendingOnce = ArrayList<String>()
    private var pendingUnset = ArrayList<String>()
    private var attrDirty = false
    private var attrVersion = 0
    private var sessionId: String? = null
    private var sessionStart = 0L
    private var sessionLast = 0L
    private var optedOut = false
    private var disabled = emptySet<String>()
    private var sampling = emptyMap<String, Double>()
    private var screen: String? = null
    private var attempt = 0
    private var retryTask: ScheduledFuture<*>? = null
    private var flushTask: ScheduledFuture<*>? = null
    private var sending = false
    private val waiters = ArrayList<() -> Unit>()
    private var batchOverride: Int? = null
    private var pendingAttribution: JsonMap? = null
    var onConversion: ((JsonMap) -> Unit)? = null
    var onEvent: ((String) -> Unit)? = null

    init {
        restore()
    }

    // ── Persistence ─────────────────────────────────────────────────────────

    private fun restore() {
        store.getJson("queue_v1")?.arr?.forEach { j ->
            val o = j.obj ?: return@forEach
            val ids = o["ids"]?.obj
            queue.add(Queued(o["event_name"]?.string ?: return@forEach, o["status"]?.string ?: "info", o["properties"]?.obj ?: emptyMap(),
                o["uuid"]?.string ?: return@forEach, o["timestamp"]?.string ?: return@forEach, o["size"]?.int ?: 0,
                EventIds(ids?.get("device_id")?.string ?: deviceId, ids?.get("account_id")?.string, ids?.get("revenuecat_id")?.string)))
        }
        store.getJson("dropped_v1")?.obj?.forEach { (k, v) -> v.int?.let { dropped[k] = it } }
        seq = store.get("seq_v1")?.toIntOrNull() ?: 0
        store.getJson("identity_v1")?.obj?.let {
            accountId = it["account_id"]?.string; revenuecatId = it["revenuecat_id"]?.string; masterUserId = it["master"]?.string
        }
        store.getJson("super_v1")?.obj?.let { superProps.putAll(it) }
        store.getJson("attributes_v1")?.obj?.let { a ->
            a["set"]?.obj?.let { attrSet.putAll(it) }
            a["once"]?.obj?.let { attrOnce.putAll(it) }
            a["pending_once"]?.arr?.mapNotNull { it.string }?.let { pendingOnce.addAll(it) }
            a["pending_unset"]?.arr?.mapNotNull { it.string }?.let { pendingUnset.addAll(it) }
            attrDirty = a["dirty"]?.bool == true
            attrVersion = a["version"]?.int ?: 0
        }
        store.getJson("session_v1")?.obj?.let { s ->
            sessionId = s["id"]?.string; sessionStart = s["startedAt"]?.long ?: 0; sessionLast = s["lastActivityAt"]?.long ?: 0
        }
        optedOut = store.get("optout_v1") == "1"
        pendingAttribution = store.getJson("attribution_v1")?.obj
        if (optedOut) queue.clear()
    }

    private fun save() {
        store.setJson("queue_v1", Json.Arr(queue.map {
            Json.Obj(linkedMapOf("event_name" to Json.Str(it.name), "status" to Json.Str(it.status), "properties" to Json.Obj(it.props),
                "uuid" to Json.Str(it.uuid), "timestamp" to Json.Str(it.timestamp), "size" to Json.of(it.size),
                "ids" to Json.obj("device_id" to it.ids.deviceId, "account_id" to it.ids.accountId, "revenuecat_id" to it.ids.revenuecatId)))
        }))
        store.setJson("dropped_v1", Json.of(dropped))
        store.set("seq_v1", seq.toString())
        sessionId?.let { store.setJson("session_v1", Json.obj("id" to it, "startedAt" to sessionStart, "lastActivityAt" to sessionLast)) }
    }

    private fun saveIdentity() = store.setJson("identity_v1", Json.obj("account_id" to accountId, "revenuecat_id" to revenuecatId, "master" to masterUserId))
    private fun saveAttrs() = store.setJson("attributes_v1", Json.Obj(linkedMapOf(
        "set" to Json.Obj(attrSet), "once" to Json.Obj(attrOnce), "pending_once" to Json.of(pendingOnce),
        "pending_unset" to Json.of(pendingUnset), "dirty" to Json.Bool(attrDirty), "version" to Json.of(attrVersion))))

    /** Runs on the core thread and waits (for tests and the public getters). */
    fun <T> sync(block: () -> T): T =
        // Already on the core thread (the placement runtime shares it): waiting on it would deadlock.
        if (Thread.currentThread() === workThread) block() else work.submit<T> { block() }.get()

    // ── Identity ────────────────────────────────────────────────────────────

    private val idsNow get() = EventIds(deviceId, accountId, revenuecatId)
    fun ids(): EventIds = sync { idsNow }
    val masterUser: String? get() = sync { masterUserId }

    fun identify(id: String): Boolean {
        val clean = legalId(id) ?: return false
        work.execute { if (clean != accountId) { accountId = clean; saveIdentity() } }
        return true
    }

    fun setRevenueCatId(id: String?) = work.execute { revenuecatId = id?.let { legalId(it) }; saveIdentity() }

    fun reset() = work.execute {
        accountId = null; revenuecatId = null; masterUserId = null; saveIdentity()
        superProps.clear(); store.setJson("super_v1", Json.Obj(superProps))
        attrSet.clear(); attrOnce.clear(); pendingOnce.clear(); pendingUnset.clear(); attrDirty = false; attrVersion++; saveAttrs()
        startSession(now())
    }

    fun register(props: JsonMap) = work.execute { superProps.putAll(props); store.setJson("super_v1", Json.Obj(superProps)) }
    fun unregister(key: String) = work.execute { superProps.remove(key); store.setJson("super_v1", Json.Obj(superProps)) }
    fun setScreen(name: String?) = work.execute { screen = name }
    val currentScreen: String? get() = sync { screen }

    fun applyEventSettings(disabled: Set<String>, sampling: Map<String, Double>) = work.execute { this.disabled = disabled; this.sampling = sampling }

    fun setOptOut(out: Boolean) = work.execute {
        optedOut = out
        store.set("optout_v1", if (out) "1" else null)
        if (out) { queue.clear(); save() }
    }

    val isOptedOut: Boolean get() = sync { optedOut }

    fun setPendingAttribution(params: JsonMap) = work.execute {
        if (pendingAttribution == null && params.isNotEmpty() && store.get("attribution_sent_v1") == null) {
            pendingAttribution = params
            store.setJson("attribution_v1", Json.Obj(params))
        }
    }

    // ── Attributes ──────────────────────────────────────────────────────────

    private fun attrKeyOk(k: String) = !k.startsWith("$") && Regex("^[A-Za-z0-9_]{1,40}$").matches(k)
    private fun attrValueOk(v: Json) = when (v) {
        is Json.Bool -> true
        is Json.Num -> v.v.isFinite()
        is Json.Str -> v.v.length <= 1024
        else -> false
    }
    private fun roomFor(k: String) = attrSet.containsKey(k) || attrOnce.containsKey(k) || attrSet.size + attrOnce.size < 100
    private fun attrsChanged() { attrDirty = true; attrVersion++; saveAttrs() }

    fun setAttributes(attrs: JsonMap) = work.execute {
        var changed = false
        for ((k, v) in attrs) {
            if (!attrKeyOk(k) || !attrValueOk(v) || !roomFor(k)) continue
            if (pendingUnset.remove(k)) changed = true
            if (attrOnce.remove(k) != null) { pendingOnce.remove(k); changed = true }
            if (attrSet[k] != v) { attrSet[k] = v; changed = true }
        }
        if (changed) attrsChanged()
    }

    fun setOnce(attrs: JsonMap) = work.execute {
        var changed = false
        for ((k, v) in attrs) {
            if (!attrKeyOk(k) || !attrValueOk(v) || attrSet.containsKey(k) || attrOnce.containsKey(k) || !roomFor(k)) continue
            if (pendingUnset.remove(k)) attrSet[k] = v else { attrOnce[k] = v; pendingOnce.add(k) }
            changed = true
        }
        if (changed) attrsChanged()
    }

    fun unsetAttribute(key: String) = work.execute {
        if (!attrKeyOk(key)) return@execute
        attrSet.remove(key); attrOnce.remove(key); pendingOnce.remove(key)
        if (!pendingUnset.contains(key)) pendingUnset.add(key)
        attrsChanged()
    }

    fun allAttributes(): JsonMap = sync { LinkedHashMap(attrOnce).apply { putAll(attrSet) } }

    // ── Sessions (src/session.ts) ───────────────────────────────────────────

    private fun expired(t: Long) = sessionId == null || t - sessionLast > sessionIdleMs || t - sessionStart > 24L * 60 * 60 * 1000
    private fun startSession(t: Long): String {
        sessionId = Ids.uuidv7(t); sessionStart = t; sessionLast = t
        save()
        return sessionId!!
    }

    private fun sessionForEvent(t: Long): String {
        val id = if (expired(t)) startSession(t) else sessionId!!
        if (t > sessionLast) sessionLast = t
        return id
    }

    fun currentSessionId(): String? = sync { if (expired(now())) null else sessionId }

    // ── Events ──────────────────────────────────────────────────────────────

    private fun drop(reason: String, n: Int = 1) { if (n > 0) dropped[reason] = (dropped[reason] ?: 0) + n }

    fun track(name: String, status: String = "info", properties: JsonMap = emptyMap(), at: Long = now()) = work.execute { enqueue(name, status, properties, at) }

    private fun enqueue(rawName: String, status: String, properties: JsonMap, at: Long) {
        val name = rawName.trim()
        if (name.isEmpty() || optedOut || name in disabled) return
        val rate = sampling[name]
        if (rate != null && !Sampling.keep(deviceId, name, rate)) return
        val merged = LinkedHashMap<String, Json>(everyEvent)
        merged.putAll(superProps)
        merged.putAll(properties)
        if (!properties.containsKey("\$screen_name")) {
            val s = screen
            if (s != null) merged["\$screen_name"] = Json.Str(s) else merged.remove("\$screen_name")
        }
        merged["\$session_id"] = Json.Str(sessionForEvent(at))
        if (rate != null && rate < 1) merged["\$sample_rate"] = Json.of(rate)
        merged["\$seq"] = Json.of(0)
        val keep = listOf("\$session_id", "\$seq", "\$screen_name", "\$sample_rate", "\$network_type") + everyEvent.keys + properties.keys
        val capped = LinkedHashMap(Delivery.capProperties(merged, keep))
        capped["\$seq"] = Json.of(++seq)
        val e = Queued(name, status, capped, Ids.uuidv4(), Iso.format(at), 0, idsNow)
        val size = Delivery.utf8Length(Json.stringify(wire(e)))
        if (size > Delivery.MAX_EVENT_BYTES) { drop("oversized"); return }
        queue.add(e.copy(size = size))
        if (queue.size > 1000) {
            var over = queue.size - 1000
            val n = over
            queue.removeAll { if (over > 0 && it.uuid !in inflight) { over--; true } else false }
            drop("queue_full", n - over)
        }
        save()
        try { onEvent?.invoke(name) } catch (_: Exception) { }
        if (queue.size - inflight.size >= maxBatch) flushOnWork(null)
        if (flushTask == null) {
            flushTask = work.scheduleWithFixedDelay({ flushOnWork(null) }, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun wire(e: Queued) = Json.Obj(linkedMapOf("event_name" to Json.Str(e.name), "status" to Json.Str(e.status),
        "properties" to Json.Obj(e.props), "uuid" to Json.Str(e.uuid), "timestamp" to Json.Str(e.timestamp)))

    val pendingCount: Int get() = sync { queue.size }

    // ── Delivery ────────────────────────────────────────────────────────────

    /** Sends everything queued; `done` runs when the queue is empty or a retry is scheduled. */
    fun flush(done: (() -> Unit)? = null) = work.execute { flushOnWork(done) }

    fun writeNow() = work.execute { save() }

    private fun flushOnWork(done: (() -> Unit)?) {
        if (done != null) waiters.add(done)
        if (sending) return
        if (retryTask != null) { finish(); return }
        sending = true
        sendNext()
    }

    private fun finish() {
        sending = false
        val w = ArrayList(waiters); waiters.clear()
        w.forEach { try { it() } catch (_: Exception) { } }
    }

    private fun sendNext() {
        val cutoff = now() - Delivery.MAX_EVENT_AGE_MS
        val before = queue.size
        queue.removeAll { it.uuid !in inflight && (Iso.parse(it.timestamp) ?: Long.MAX_VALUE) < cutoff }
        drop("expired", before - queue.size)
        val first = queue.firstOrNull { it.uuid !in inflight } ?: return finish()
        val pinned = first.ids
        val current = pinned == idsNow
        val attribution = if (current) pendingAttribution else null
        val attrs = if (current && attrDirty && !optedOut) attributePayload() else null
        val reported = LinkedHashMap(dropped)
        val total = reported.values.sum()
        val env = LinkedHashMap<String, Json>()
        env["revenuecat_id"] = Json.of(pinned.revenuecatId)
        attribution?.let { env["attribution"] = Json.Obj(it) }
        attrs?.set?.let { env["attributes"] = Json.Obj(it) }
        attrs?.once?.let { env["attributes_set_once"] = Json.Obj(it) }
        attrs?.unset?.let { env["attributes_unset"] = Json.of(it) }
        env["platform"] = Json.Str("android")
        appVersion?.let { env["app_version"] = Json.Str(it) }
        country()?.let { env["country"] = Json.Str(it) }
        if (total > 0) { env["client_dropped"] = Json.of(total); env["client_dropped_reasons"] = Json.of(reported) }
        env["sent_at"] = Json.Str(Iso.format(now()))
        var budget = Delivery.MAX_BODY_BYTES - Delivery.utf8Length(Json.stringify(Json.Obj(env + ("events" to Json.Arr(emptyList())))))
        val limit = minOf(batchOverride ?: maxBatch, maxBatch)
        val batch = ArrayList<Queued>()
        for (e in queue) {
            if (e.uuid in inflight) continue
            if (batch.size >= limit || e.ids != pinned) break
            if (batch.isNotEmpty() && e.size + 1 > budget) break
            budget -= e.size + 1
            batch.add(e)
        }
        if (batch.isEmpty()) return finish()
        val uuids = batch.map { it.uuid }.toSet()
        inflight.addAll(uuids)
        env["events"] = Json.Arr(batch.map { wire(it) })
        val req = api.request("api/v1/events/log", "POST", body = Json.stringify(Json.Obj(env)), deviceId = pinned.deviceId, accountId = pinned.accountId)
        transport.send(req) { res -> work.execute { handle(res, uuids, reported, attribution != null, attrs, current) } }
    }

    private data class AttrPayload(val version: Int, val set: JsonMap?, val once: JsonMap?, val unset: List<String>?)

    private fun attributePayload(): AttrPayload? {
        val once = pendingOnce.mapNotNull { k -> attrOnce[k]?.let { k to it } }.toMap()
        val p = AttrPayload(attrVersion, attrSet.takeIf { it.isNotEmpty() }?.let { LinkedHashMap(it) }, once.takeIf { it.isNotEmpty() },
            pendingUnset.takeIf { it.isNotEmpty() }?.let { ArrayList(it) })
        return if (p.set == null && p.once == null && p.unset == null) null else p
    }

    private fun handle(res: Response, uuids: Set<String>, reported: Map<String, Int>, hadAttribution: Boolean, attrs: AttrPayload?, current: Boolean) {
        inflight.removeAll(uuids)
        if (res.status == 0) { scheduleRetry(Delivery.backoffMs(attempt + 1, random())); return finish() }
        if (!res.ok) {
            if (res.status == 429 || res.status >= 500) {
                val after = Delivery.retryAfterMs(res.header("retry-after"), now())
                scheduleRetry(after?.coerceIn(1000, Delivery.RETRY_CAP_MS) ?: Delivery.backoffMs(attempt + 1, random()))
                return finish()
            }
            if (res.status == 413) {
                if (uuids.size > 1) batchOverride = maxOf(1, uuids.size / 2) else { queue.removeAll { it.uuid in uuids }; drop("oversized") }
                return sendNext()
            }
            queue.removeAll { it.uuid in uuids }
            drop("rejected_4xx", uuids.size)
            save()
            return sendNext()
        }
        queue.removeAll { it.uuid in uuids }
        for ((k, n) in reported) {
            val left = (dropped[k] ?: 0) - n
            if (left > 0) dropped[k] = left else dropped.remove(k)
        }
        attempt = 0
        batchOverride = batchOverride?.let { if (it * 2 >= maxBatch) null else it * 2 }
        val data = Json.parse(res.body)
        if (hadAttribution) {
            pendingAttribution = null
            store.set("attribution_v1", null)
            store.set("attribution_sent_v1", "1")
        }
        if (current) data?.get("conversion")?.obj?.let { c -> try { onConversion?.invoke(c) } catch (_: Exception) { } }
        if (attrs != null) {
            if (attrs.version == attrVersion) { attrDirty = false; pendingOnce.clear(); pendingUnset.clear() }
            saveAttrs()
        }
        if (current) data?.get("master_user_id")?.string?.takeIf { it.isNotEmpty() && it != masterUserId }?.let { masterUserId = it; saveIdentity() }
        save()
        sendNext()
    }

    private fun scheduleRetry(delayMs: Long) {
        attempt++
        retryTask?.cancel(false)
        save()
        retryTask = work.schedule({ retryTask = null; flushOnWork(null) }, delayMs, TimeUnit.MILLISECONDS)
    }
}
