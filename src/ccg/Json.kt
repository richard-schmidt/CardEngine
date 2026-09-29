package ccg

// ---------------------------------------------------------------------------
// A tiny hand-rolled JSON value tree + recursive-descent reader. Zero
// dependencies, so it stays under test.sh's stdlib-only core guard. Every
// codec in the engine reads through it.
// ---------------------------------------------------------------------------

internal sealed interface Json {
    data class Obj(val members: Map<String, Json>) : Json
    data class Arr(val items: List<Json>) : Json
    data class Str(val value: String) : Json
    data class Num(val value: Double) : Json
    data class Bool(val value: Boolean) : Json
    data object Null : Json

    companion object {
        fun parse(text: String): Json {
            val r = JsonReader(text)
            val v = r.readValue()
            r.skipWs()
            require(r.atEnd()) { "trailing content at offset ${r.pos}" }
            return v
        }
    }
}

internal fun Json.obj(): Map<String, Json> = (this as? Json.Obj)?.members ?: error("expected a JSON object, got $this")
internal fun Json.arr(): List<Json> = (this as? Json.Arr)?.items ?: error("expected a JSON array, got $this")
internal fun Json.str(): String = (this as? Json.Str)?.value ?: error("expected a JSON string, got $this")
internal fun Json.int(): Int = ((this as? Json.Num)?.value ?: error("expected a JSON number, got $this")).toInt()

/** A fractional number. `int()` truncates, which is right for a count and wrong
 *  for a scale or an offset (art rects are fractions of an image). */
internal fun Json.flt(): Float = ((this as? Json.Num)?.value ?: error("expected a JSON number, got $this")).toFloat()
internal fun Json.bool(): Boolean = (this as? Json.Bool)?.value ?: error("expected a JSON boolean, got $this")

internal fun Map<String, Json>.req(key: String): Json = this[key] ?: error("missing required key '$key'")
internal fun Map<String, Json>.strOr(key: String, default: String): String = this[key]?.str() ?: default
internal fun Map<String, Json>.intOr(key: String, default: Int): Int = this[key]?.int() ?: default
internal fun Map<String, Json>.boolOr(key: String, default: Boolean): Boolean = this[key]?.bool() ?: default

/** `this`, written back out: compact, keys in the order they were read. */
internal fun Json.compact(): String = when (this) {
    is Json.Obj -> members.entries.joinToString(",", "{", "}") { "${jstr(it.key)}:${it.value.compact()}" }
    is Json.Arr -> items.joinToString(",", "[", "]") { it.compact() }
    is Json.Str -> jstr(value)
    is Json.Num -> if (value % 1.0 == 0.0 && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString()
    is Json.Bool -> value.toString()
    Json.Null -> "null"
}

/** `this` for a reader and a diff: a value that fits in `width` columns stays
 *  on its line, anything longer opens one member per line, two-space indent.
 *  Parses back to the same tree as `compact()`. */
internal fun Json.pretty(width: Int = 100): String = buildString {
    fun put(v: Json, indent: String, used: Int) {
        val flat = v.compact()
        val open = when (v) { is Json.Obj -> v.members.isNotEmpty(); is Json.Arr -> v.items.isNotEmpty(); else -> false }
        if (!open || used + flat.length <= width) { append(flat); return }
        val inner = "$indent  "
        val (l, r) = if (v is Json.Obj) '{' to '}' else '[' to ']'
        append(l)
        val entries: List<Pair<String, Json>> =
            if (v is Json.Obj) v.members.entries.map { "${jstr(it.key)}: " to it.value } else (v as Json.Arr).items.map { "" to it }
        entries.forEachIndexed { i, (key, value) ->
            append('\n').append(inner).append(key)
            put(value, inner, inner.length + key.length + 1)
            if (i < entries.lastIndex) append(',')
        }
        append('\n').append(indent).append(r)
    }
    put(this@pretty, "", 0)
    append('\n')
}

/** Minimal JSON string escaping for the hand-built writers. */
internal fun jstr(s: String): String = buildString {
    append('"')
    for (ch in s) when (ch) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> append(ch)
    }
    append('"')
}

private class JsonReader(private val s: String) {
    var pos = 0

    fun atEnd(): Boolean = pos >= s.length
    fun skipWs() { while (pos < s.length && s[pos].isWhitespace()) pos++ }

    private fun peek(): Char {
        check(pos < s.length) { "unexpected end of JSON at offset $pos" }
        return s[pos]
    }

    private fun expect(c: Char) {
        skipWs()
        require(pos < s.length && s[pos] == c) { "expected '$c' at offset $pos" }
        pos++
    }

    fun readValue(): Json {
        skipWs()
        return when (peek()) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> Json.Str(readString())
            't', 'f' -> readBool()
            'n' -> { readLiteral("null"); Json.Null }
            else -> readNumber()
        }
    }

    private fun readObject(): Json.Obj {
        expect('{')
        skipWs()
        val m = LinkedHashMap<String, Json>()
        if (peek() == '}') { pos++; return Json.Obj(m) }
        while (true) {
            skipWs()
            val key = readString()
            expect(':')
            m[key] = readValue()
            skipWs()
            when (peek()) {
                ',' -> pos++
                '}' -> { pos++; return Json.Obj(m) }
                else -> error("expected ',' or '}' at offset $pos")
            }
        }
    }

    private fun readArray(): Json.Arr {
        expect('[')
        skipWs()
        val a = ArrayList<Json>()
        if (peek() == ']') { pos++; return Json.Arr(a) }
        while (true) {
            a += readValue()
            skipWs()
            when (peek()) {
                ',' -> pos++
                ']' -> { pos++; return Json.Arr(a) }
                else -> error("expected ',' or ']' at offset $pos")
            }
        }
    }

    private fun readString(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            require(pos < s.length) { "unterminated string" }
            when (val c = s[pos++]) {
                '"' -> return sb.toString()
                '\\' -> {
                    require(pos < s.length) { "unterminated escape" }
                    when (val e = s[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            require(pos + 4 <= s.length) { "bad \\u escape at offset ${pos - 2}" }
                            sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                            pos += 4
                        }
                        else -> error("bad escape '\\$e' at offset ${pos - 1}")
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun readNumber(): Json.Num {
        val start = pos
        if (pos < s.length && s[pos] == '-') pos++
        while (pos < s.length && (s[pos].isDigit() || s[pos] == '.' || s[pos] == 'e' || s[pos] == 'E' || s[pos] == '+' || s[pos] == '-')) pos++
        val raw = s.substring(start, pos)
        return Json.Num(raw.toDoubleOrNull() ?: error("bad number '$raw' at offset $start"))
    }

    private fun readBool(): Json.Bool =
        if (s.startsWith("true", pos)) {
            pos += 4; Json.Bool(true)
        } else {
            readLiteral("false"); Json.Bool(false)
        }

    private fun readLiteral(lit: String) {
        require(s.startsWith(lit, pos)) { "expected '$lit' at offset $pos" }
        pos += lit.length
    }
}

/** THE wire spelling of an engine enum: its name in lowercase
 *  ("library_bottom"), one convention for every enum. */
fun <E : Enum<E>> enumStr(e: E): String = e.name.lowercase()

/** Read an enum written by `enumStr`, or by an older file that wrote the Kotlin
 *  identifier, or one of the `legacy` spellings a hand-written codec used
 *  ("perPlayer"). Anything else is a load error, never a silent default. */
inline fun <reified E : Enum<E>> enumOf(s: String, vararg legacy: Pair<String, E>): E =
    enumValues<E>().firstOrNull { it.name.lowercase() == s || it.name == s }
        ?: legacy.firstOrNull { it.first == s }?.second
        ?: error("unknown ${E::class.simpleName} '$s'")
