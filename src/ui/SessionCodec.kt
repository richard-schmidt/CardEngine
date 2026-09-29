package ccgui

import ccg.Answer
import ccg.answerFromJson
import ccg.answerToJson

// ---------------------------------------------------------------------------
// A PlaySession, as text: (seed, deck picks, answers) -- a few hundred bytes
// rather than a serialised GameState. Here in src/ui because surviving process
// death is a UI concern and it is testable on the host JVM.
//
// THE FORMAT is dull on purpose: a version, then fields separated by the RS and
// US control characters -- escaped anyway, since "cannot occur" has been wrong
// before and a corrupt save that throws is worse.
// ---------------------------------------------------------------------------

/** The session format this build writes. An older one is lifted to it
 *  by SESSION_MIGRATIONS, one step per version, header fields to header
 *  fields; a newer one is refused (null), never guessed at. Appending a
 *  field with a default needs no bump -- see `encode`.
 *
 *  2: the engine answers forced questions itself, so a session holds no
 *  answer for one. A version-1 session may hold a bot's answer to one, and
 *  which answers those were is only known by replaying the old way, so it
 *  has no migration: it reads as no session, and the game starts fresh.
 *  Format 2 also writes each answer as `answerToJson` instead of this
 *  file's own per-case records. */
const val SESSION_VERSION = 2
private val SESSION_MIGRATIONS: Map<Int, (List<String>) -> List<String>> = emptyMap()
private val RS = Char(30)   // between answers
private val US = Char(31)   // between fields

private fun esc(s: String): String = buildString {
    for (c in s) when (c) {
        '\\' -> append("\\\\")
        RS -> append("\\r")
        US -> append("\\u")
        else -> append(c)
    }
}

private fun unesc(s: String): String = buildString {
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c != '\\' || i == s.lastIndex) { append(c); i++; continue }
        when (s[i + 1]) {
            '\\' -> append('\\')
            'r' -> append(RS)
            'u' -> append(US)
            else -> append(s[i + 1])
        }
        i += 2
    }
}

private fun fields(vararg f: String) = f.joinToString(US.toString()) { esc(it) }

/** One answer as a record: `answerToJson`, the corpus's and the agent's form
 *  too, escaped so it cannot contain a separator. */
private fun encodeOne(a: Answer): String = esc(answerToJson(a))

private fun decodeOne(rec: String): Answer? = runCatching { answerFromJson(unesc(rec)) }.getOrNull()

/** The whole session as one string. Safe in a Bundle, a file or a clipboard --
 *  nothing in it is Android-shaped. */
fun PlaySession.encode(): String =
    fields(
        SESSION_VERSION.toString(), seed.toString(), p0Deck.toString(), p1Deck.toString(), generation.toString(),
        // Later fields are APPENDED, not versioned: earlier fields keep their
        // index, so an older session reads back with the tail defaulted.
        if (p0Random) "1" else "0",
        if (p1Random) "1" else "0",
        pilot ?: "",
        if (pilotRandom) "1" else "0",
        bundle ?: "",
        if (debugged) "1" else "0",
        if (sandbox) "1" else "0",
    ) + RS + answers.joinToString(RS.toString()) { encodeOne(it) }

/** Read one back. Null for anything this build cannot reconstruct exactly: a
 *  session missing one answer replays to a WRONG game, and a clean restart is
 *  recoverable where a corrupted resume is not. */
fun decodePlaySession(text: String): PlaySession? {
    if (text.isEmpty()) return null
    val parts = text.split(RS)
    var head = parts.firstOrNull()?.split(US) ?: return null
    val from = head.getOrNull(0)?.let(::unesc)?.toIntOrNull() ?: return null
    if (from > SESSION_VERSION) return null
    for (v in from until SESSION_VERSION) head = SESSION_MIGRATIONS[v]?.invoke(head) ?: return null
    val seed = head.getOrNull(1)?.toIntOrNull() ?: return null
    val p0 = head.getOrNull(2)?.toIntOrNull() ?: return null
    val p1 = head.getOrNull(3)?.toIntOrNull() ?: return null
    val gen = head.getOrNull(4)?.toIntOrNull() ?: return null
    // Absent tail -> defaults, which is what makes an older session readable.
    // `null` is still reserved for what CANNOT be honestly reconstructed; a
    // preference that was never written is honestly its default.
    val p0Rand = head.getOrNull(5) == "1"
    val p1Rand = head.getOrNull(6) == "1"
    val pilot = head.getOrNull(7)?.let(::unesc)?.ifEmpty { null }
    val pilotRand = head.getOrNull(8) == "1"
    val bundle = head.getOrNull(9)?.let(::unesc)?.ifEmpty { null }
    val debugged = head.getOrNull(10) == "1"
    val sandbox = head.getOrNull(11) == "1"
    val answers = parts.drop(1).filter { it.isNotEmpty() }.map { decodeOne(it) ?: return null }
    return PlaySession(
        seed = seed, p0Deck = p0, p1Deck = p1, answers = answers, generation = gen,
        p0Random = p0Rand, p1Random = p1Rand, pilot = pilot, pilotRandom = pilotRand, bundle = bundle,
        debugged = debugged, sandbox = sandbox,
    )
}
