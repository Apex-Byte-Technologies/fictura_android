package co.fictura.sdk

/**
 * A small JSON model, parser and writer with no Android dependency, so the
 * pipeline and the placement engine run in plain JVM unit tests.
 */
sealed class Json {
    data class Str(val v: String) : Json()
    data class Num(val v: Double, val isInt: Boolean) : Json()
    data class Bool(val v: Boolean) : Json()
    data class Arr(val v: List<Json>) : Json()
    data class Obj(val v: Map<String, Json>) : Json()
    object Null : Json()

    val string: String? get() = (this as? Str)?.v
    val bool: Boolean? get() = (this as? Bool)?.v
    val double: Double? get() = (this as? Num)?.v
    val int: Int? get() = (this as? Num)?.v?.let { if (it.isFinite()) it.toLong().toInt() else null }
    val long: Long? get() = (this as? Num)?.v?.let { if (it.isFinite()) it.toLong() else null }
    val obj: Map<String, Json>? get() = (this as? Obj)?.v
    val arr: List<Json>? get() = (this as? Arr)?.v
    operator fun get(key: String): Json? = obj?.get(key)

    /** The plain Kotlin value, for app code. */
    val any: Any?
        get() = when (this) {
            is Str -> v
            is Num -> if (isInt && v >= Long.MIN_VALUE && v <= Long.MAX_VALUE) v.toLong() else v
            is Bool -> v
            is Arr -> v.map { it.any }
            is Obj -> v.mapValues { it.value.any }
            Null -> null
        }

    override fun toString(): String = stringify(this)

    companion object {
        fun of(v: Any?): Json = when (v) {
            null -> Null
            is Json -> v
            is String -> Str(v)
            is Boolean -> Bool(v)
            is Int -> Num(v.toDouble(), true)
            is Long -> Num(v.toDouble(), true)
            is Short -> Num(v.toDouble(), true)
            is Byte -> Num(v.toDouble(), true)
            is Double -> if (v.isFinite()) Num(v, false) else Null
            is Float -> if (v.isFinite()) Num(v.toDouble(), false) else Null
            is Number -> Num(v.toDouble(), false)
            is Map<*, *> -> Obj(v.entries.associate { (k, x) -> k.toString() to of(x) })
            is Iterable<*> -> Arr(v.map { of(it) })
            is Array<*> -> Arr(v.map { of(it) })
            else -> Str(v.toString())
        }

        fun obj(vararg pairs: Pair<String, Any?>): Obj = Obj(pairs.associate { it.first to of(it.second) })

        fun stringify(j: Json): String = StringBuilder().also { write(j, it) }.toString()

        private fun write(j: Json, sb: StringBuilder) {
            when (j) {
                is Str -> quote(j.v, sb)
                is Num -> if (j.isInt && j.v == Math.floor(j.v) && Math.abs(j.v) < 9.007199254740992E15) sb.append(j.v.toLong()) else sb.append(fmt(j.v))
                is Bool -> sb.append(j.v)
                Null -> sb.append("null")
                is Arr -> {
                    sb.append('[')
                    j.v.forEachIndexed { i, x -> if (i > 0) sb.append(','); write(x, sb) }
                    sb.append(']')
                }
                is Obj -> {
                    sb.append('{')
                    var first = true
                    for ((k, x) in j.v) {
                        if (!first) sb.append(',')
                        first = false
                        quote(k, sb)
                        sb.append(':')
                        write(x, sb)
                    }
                    sb.append('}')
                }
            }
        }

        private fun fmt(d: Double): String {
            if (!d.isFinite()) return "null"
            if (d == Math.floor(d) && Math.abs(d) < 1e15) return d.toLong().toString()
            return d.toString()
        }

        private fun quote(s: String, sb: StringBuilder) {
            sb.append('"')
            for (c in s) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    else -> if (c < ' ' || c == ' ' || c == ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
                }
            }
            sb.append('"')
        }

        fun parse(text: String?): Json? = try {
            if (text == null) null else Parser(text).run { val v = value(); ws(); if (i != s.length) null else v }
        } catch (_: Exception) {
            null
        }
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Json {
            ws()
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> Str(str())
                't' -> { expect("true"); Bool(true) }
                'f' -> { expect("false"); Bool(false) }
                'n' -> { expect("null"); Null }
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("bad json at $i")
            }
        }
        fun expect(w: String) { require(s.startsWith(w, i)); i += w.length }
        fun obj(): Json {
            i++
            val m = LinkedHashMap<String, Json>()
            ws()
            if (s[i] == '}') { i++; return Obj(m) }
            while (true) {
                ws(); val k = str(); ws(); require(s[i] == ':'); i++
                m[k] = value(); ws()
                if (s[i] == ',') { i++; continue }
                require(s[i] == '}'); i++; return Obj(m)
            }
        }
        fun arr(): Json {
            i++
            val l = ArrayList<Json>()
            ws()
            if (s[i] == ']') { i++; return Arr(l) }
            while (true) {
                l.add(value()); ws()
                if (s[i] == ',') { i++; continue }
                require(s[i] == ']'); i++; return Arr(l)
            }
        }
        fun str(): String {
            require(s[i] == '"'); i++
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = s[i++]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                        'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                        'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> sb.append(e)
                    }
                    else -> sb.append(c)
                }
            }
        }
        fun num(): Json {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(start, i)
            val isInt = !t.contains('.') && !t.contains('e') && !t.contains('E')
            return Num(t.toDouble(), isInt)
        }
    }
}

typealias JsonMap = Map<String, Json>
