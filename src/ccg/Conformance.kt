package ccg

// ---------------------------------------------------------------------------
// The conformance corpus: recorded games a second
// implementation of the rules replays and diffs. One file per bundle:
//
//   {"corpusVersion":2,"bundle":<the game, as a save file>,"cases":[
//   {"seats":[0,1],"seed":7,"maxTurns":16,"answers":[...],"digests":[...],"end":"..."},
//   ...]}
//
// A runner sets the game up with `startGame(bundle, seats, seed)`, then at EVERY question
// digests the full state (`GameState.digest`) -- null when the question
// carries none -- checks it against `digests[i]`, and answers `answers[i]`.
// When the game ends its state must digest to `end`. A mismatch at i names
// the first answer after which the two engines disagree.
// ---------------------------------------------------------------------------

/** 2: seats are a list of deck picks. Version 1 named `p0Deck`/`p1Deck`
 *  and still reads. */
const val CORPUS_VERSION = 2

/** One recorded game. `digests[i]` is the state as question i was asked. */
data class CorpusCase(
    /** Each seat's deck, in seat order: `DeckPick`s, as indices. */
    val seats: List<Int>,
    val seed: Int,
    val maxTurns: Int,
    val answers: List<Answer>,
    val digests: List<String?>,
    val end: String,
    /** A sandbox table's recording (a scenario): its answers may be
     *  table edits, which only a sandbox run takes. */
    val sandbox: Boolean = false,
    /** The recording stops at a question, not at the game's end: `end` is the
     *  state as the question after the last answer is asked. */
    val open: Boolean = false,
    /** Recorded on a sandbox bench, where every priority question is asked
     *  (`Run.pauses`). */
    val pauses: Boolean = false,
)

data class Corpus(val bundle: GameDoc, val cases: List<CorpusCase>)

/** The corpus file: the bundle on its own line, then one case per line, so a
 *  regenerated corpus diffs by game. */
fun corpusToJson(c: Corpus): String = buildString {
    append("{\"corpusVersion\":$CORPUS_VERSION,\n\"bundle\":").append(gameDocToJson(c.bundle)).append(",\n\"cases\":[")
    c.cases.forEachIndexed { i, k ->
        append(if (i == 0) "\n" else ",\n")
        append("{\"seats\":${k.seats.joinToString(",", "[", "]")},\"seed\":${k.seed},\"maxTurns\":${k.maxTurns},")
        append(k.answers.joinToString(",", "\"answers\":[", "],") { answerToJson(it) })
        append(k.digests.joinToString(",", "\"digests\":[", "],") { d -> d?.let { jstr(it) } ?: "null" })
        append("\"end\":${jstr(k.end)}")
        // Only when set, so a recorded game's line is as it always was.
        if (k.sandbox) append(",\"sandbox\":true")
        if (k.open) append(",\"open\":true")
        if (k.pauses) append(",\"pauses\":true")
        append("}")
    }
    append("\n]}\n")
}

fun corpusFromJson(text: String): Corpus {
    val o = Json.parse(text).obj()
    val v = o.req("corpusVersion").int()
    require(v in 1..CORPUS_VERSION) { "corpus version $v; this engine reads up to $CORPUS_VERSION" }
    return Corpus(
        bundle = gameDocOf(o.req("bundle")),
        cases = o.req("cases").arr().map { j ->
            val k = j.obj()
            CorpusCase(
                seats = if (v == 1) listOf(k.req("p0Deck").int(), k.req("p1Deck").int()) else k.req("seats").arr().map { it.int() },
                seed = k.req("seed").int(), maxTurns = k.req("maxTurns").int(),
                answers = k.req("answers").arr().map { answerOf(it) },
                digests = k.req("digests").arr().map { if (it == Json.Null) null else it.str() },
                end = k.req("end").str(),
                sandbox = k["sandbox"]?.bool() ?: false,
                open = k["open"]?.bool() ?: false,
                pauses = k["pauses"]?.bool() ?: false,
            )
        },
    )
}
