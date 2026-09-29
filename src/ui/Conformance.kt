package ccgui

import ccg.CorpusCase
import ccg.DeckPick
import ccg.startGame
import ccg.GameDoc
import ccg.PlayerInput
import ccg.Rules
import ccg.Run
import ccg.step
import ccg.undeckedSeats
import ccg.recorded
import ccg.digest
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

// ---------------------------------------------------------------------------
// Recording and replaying conformance-corpus cases. The format
// is `ccg.Corpus` (Conformance.kt); this is the Kotlin engine's half of it -- the recorder, and
// the reference runner a port's runner does what this one does. Both run
// through `ccg.step`, as every client does.
// ---------------------------------------------------------------------------

/** Play `g` from (picks, seed) with `seats` answering -- each seat on its
 *  own view, as play would -- and record it: every answer as the run holds
 *  it, and the FULL state's digest as each question is asked. */
fun recordCorpusCase(g: GameDoc, rules: Rules, decks: List<DeckPick>, seed: Int, maxTurns: Int, seats: PlayerInput): CorpusCase {
    val start = startGame(g, rules, decks, seed)
    val digests = mutableListOf<String?>()
    val end = played {
        var s = step(rules, Run(start, maxTurns = maxTurns, undecked = undeckedSeats(decks)))
        while (!s.over) {
            val q = s.full!!
            digests += q.state?.digest()
            s = s.next(seats.ask(if (seats.seesAll(q.player)) q else s.question!!).recorded())
        }
        s
    }
    return CorpusCase(decks.map { it.deck }, seed, maxTurns, end.run.answers, digests, end.state.digest())
}

/** Replay `case` from its answers alone, digesting as a port's runner must:
 *  the digests seen at each question, then the end. Running out of answers
 *  means the game went somewhere the recording did not -- unless the case is
 *  OPEN, which ends at the question its answers stop at. */
fun replayCorpusCase(g: GameDoc, rules: Rules, case: CorpusCase): Pair<List<String?>, String> {
    val picks = case.seats.map { DeckPick(it) }
    val start = startGame(g, rules, picks, case.seed)
    val seen = mutableListOf<String?>()
    var s = step(rules, Run(start, maxTurns = case.maxTurns, sandbox = case.sandbox, undecked = undeckedSeats(picks), pauses = case.pauses))
    while (!s.over) {
        val a = case.answers.getOrNull(seen.size)
        if (a == null && case.open) return seen to s.state.digest()
        seen += s.full!!.state?.digest()
        s = s.next(a ?: error("diverged: question ${seen.size - 1} has no recorded answer"))
    }
    return seen to s.state.digest()
}

/** A table's session as a conformance case (a scenario): its answers,
 *  replayed as the table plays them (`PlaySession.tableRun`) -- edits taken, a seat
 *  with no deck never decking out, a bench asking every priority question --
 *  digesting at each question. A game still going is an
 *  OPEN case, ending at the question the answers stop at. */
fun PlaySession.asCorpusCase(g: GameDoc, rules: Rules, maxTurns: Int = TABLE_MAX_TURNS): CorpusCase {
    val picks = listOf(DeckPick(p0Deck), DeckPick(p1Deck))
    val digests = mutableListOf<String?>()
    var s = step(rules, tableRun(opening(g, rules), maxTurns))
    for (a in answers) {
        if (s.over) break
        digests += s.full!!.state?.digest()
        s = s.next(a)
    }
    return CorpusCase(picks.map { it.deck }, seed, maxTurns, answers.take(digests.size), digests, s.state.digest(), sandbox = true, open = !s.over, pauses = sandbox)
}

/** How many turns a person's table runs before it calls the game. */
const val TABLE_MAX_TURNS: Int = 40

/** A recording, run to its end: no seat here ever suspends. */
private fun <T> played(block: suspend () -> T): T {
    var out: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { out = it })
    return (out ?: error("a seat suspended")).getOrThrow()
}
