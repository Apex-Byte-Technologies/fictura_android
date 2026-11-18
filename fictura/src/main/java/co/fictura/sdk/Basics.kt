package co.fictura.sdk

import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** What this SDK calls itself on the wire. */
object SdkInfo {
    const val VERSION = "0.1.0"
    const val LIB = "fictura-android"
    const val HEADER = "android/$VERSION"
    const val TELEMETRY_NAME = "fictura-android"
    const val PAYWALL_SCHEMA = 5
    const val CONFIG_CONTRACT = 2
}

object Ids {
    private val random = SecureRandom()

    fun uuidv4(): String = UUID.randomUUID().toString()

    /** 48 bits of Unix milliseconds, then random bits: sorts by time. */
    fun uuidv7(nowMs: Long = System.currentTimeMillis()): String {
        val b = ByteArray(16)
        random.nextBytes(b)
        var ms = maxOf(0L, nowMs)
        for (i in 5 downTo 0) { b[i] = (ms and 0xff).toByte(); ms = ms shr 8 }
        b[6] = ((b[6].toInt() and 0x0f) or 0x70).toByte()
        b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
        return format(b)
    }

    fun format(b: ByteArray): String {
        val h = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20, 32)}"
    }

    fun sha256(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
    fun sha256Hex(s: String): String = sha256(s).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
}

/** Dashboard sampling, as the app SDK (src/sampling.ts). */
object Sampling {
    fun bucket(deviceId: String, event: String): Double {
        val d = Ids.sha256("$deviceId:$event")
        val v = ((d[0].toLong() and 0xff) shl 24) or ((d[1].toLong() and 0xff) shl 16) or ((d[2].toLong() and 0xff) shl 8) or (d[3].toLong() and 0xff)
        return v.toDouble() / 4294967296.0
    }

    fun keep(deviceId: String?, event: String, rate: Double): Boolean {
        if (!(rate < 1)) return true
        if (!(rate > 0)) return false
        if (deviceId.isNullOrEmpty()) return true
        return bucket(deviceId, event) < rate
    }
}

/** Limits and retry rules shared with the React Native SDK (src/delivery.ts). */
object Delivery {
    const val MAX_STRING = 1024
    const val MAX_KEYS = 255
    const val MAX_EVENT_BYTES = 64 * 1024
    const val MAX_BODY_BYTES = 500_000
    const val MAX_EVENT_AGE_MS = 48L * 60 * 60 * 1000
    const val RETRY_CAP_MS = 5L * 60 * 1000

    fun backoffMs(attempt: Int, random: Double = Math.random()): Long {
        val exp = minOf(RETRY_CAP_MS.toDouble(), 1000.0 * Math.pow(2.0, maxOf(0, minOf(attempt - 1, 30)).toDouble()))
        val r = if (random >= 0 && random < 1) random else 0.5
        return Math.floor(r * exp).toLong()
    }

    fun retryAfterMs(value: String?, nowMs: Long): Long? {
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) return null
        if (Regex("^\\d+(\\.\\d+)?$").matches(v)) return Math.round(v.toDouble() * 1000)
        if (Regex("^[-+]?[\\d.]+$").matches(v)) return null
        return try {
            val f = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            maxOf(0L, f.parse(v)!!.time - nowMs)
        } catch (_: Exception) {
            null
        }
    }

    fun truncate(s: String, max: Int = MAX_STRING): String {
        if (s.length <= max) return s
        var out = s.substring(0, max)
        if (Character.isHighSurrogate(out.last())) out = out.dropLast(1)
        return out
    }

    private fun cap(v: Json, depth: Int): Json = when (v) {
        is Json.Str -> Json.Str(truncate(v.v))
        is Json.Arr -> if (depth >= 8) Json.Null else Json.Arr(v.v.map { cap(it, depth + 1) })
        is Json.Obj -> if (depth >= 8) Json.Null else Json.Obj(v.v.mapValues { cap(it.value, depth + 1) })
        else -> v
    }

    fun capProperties(props: JsonMap, keepFirst: List<String>): JsonMap {
        val order = LinkedHashSet<String>()
        keepFirst.filter { props.containsKey(it) }.forEach { order.add(it) }
        props.keys.forEach { order.add(it) }
        val out = LinkedHashMap<String, Json>()
        for (k in order.take(MAX_KEYS)) out[truncate(k, 255)] = cap(props.getValue(k), 1)
        return out
    }

    fun utf8Length(s: String): Int = s.toByteArray(Charsets.UTF_8).size
}

object Iso {
    private val fmt = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }

    fun format(ms: Long): String = fmt.get()!!.format(Date(ms))
    fun parse(s: String?): Long? = try {
        if (s == null) null else fmt.get()!!.parse(s)?.time
    } catch (_: Exception) {
        try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s!!)?.time
        } catch (_: Exception) {
            null
        }
    }
}

/** Placeholder ids (`guest`, `null`, `0`, …) would merge every user who shares them. */
fun legalId(raw: String?): String? {
    val v = raw?.trim() ?: return null
    if (v.length < 2 || v.length > 255) return null
    val illegal = setOf("guest", "null", "undefined", "none", "nil", "anonymous", "unknown", "0", "-1", "user", "test", "[object object]", "nan")
    return if (v.lowercase() in illegal) null else v
}
