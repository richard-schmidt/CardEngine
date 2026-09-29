package ccg

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

// ---------------------------------------------------------------------------
// THE way to run a game:
//
//     step(rules, run)      -> where `run` stands: its question, or its end
//     stepped.next(answer)  -> the same, one answer further on
//
// A `Run` is data -- a position and every answer since -- and a client holds
// no coroutine and no callback: it reads the question, decides (a person, a
// pilot, a file), and hands the answer back.
//
// A question's state carries the engine's pending work (`GameState.pending`,
// Machine.kt), so `next` goes on from that state alone: it re-runs one frame
// from its checkpoint, never the game. A `Run` may start from such a state
// too; `compacted()` restates a run that way.
//
// The engine runs with `answersForced`: a question with nothing to choose
// is answered inside and never returned, so no run records an answer for one.
// Only the scripted playtest, which plays cards from no zone, turns it off.
// ---------------------------------------------------------------------------

/** A game in progress, completely: the position it started from (a fresh
 *  game, or a question's state) and every answer given since, in the order
 *  the engine asked. [answersForced] off asks even questions with nothing to
 *  choose -- only for a scripted run that acts beyond what `legalActionsFor`
 *  enumerates. */
data class Run(
    val start: GameState,
    val answers: List<Answer> = emptyList(),
    val maxTurns: Int = 60,
    val answersForced: Boolean = true,
    /** A sandbox run: `Answer.Edit` is accepted at a priority
     *  question. Only a person's table sets it; pilots, agents and the corpus
     *  never do, so an edit in their runs is refused. */
    val sandbox: Boolean = false,
    /** Seats dealt no deck: an empty library is not a loss for them
     *  (`undeckedSeats`). */
    val undecked: Set<PlayerId> = emptySet(),
    /** Ask every priority question, even a forced one: a sandbox bench,
     *  where an edit is always possible (`PlaySession.sandbox`). */
    val pauses: Boolean = false,
)

/** Where a run stands. [state] is the full state (the referee's, with its
 *  pending work); [question] is what the asked player may see; [full]
 *  is the same question with the full state, for a referee -- the corpus, the
 *  Playtest debugger. */
class Stepped internal constructor(
    val run: Run,
    private val rules: Rules,
    private val engine: Engine,
    private val at: Paused,
) {
    val state: GameState get() = at.state
    val full: Question? get() = at.question

    /** The game has ended (or hit `maxTurns`): nobody is asked anything. */
    val over: Boolean get() = at.question == null

    val question: Question? by lazy {
        full?.let { q -> q.state?.let { q.withState(it.viewFor(q.player, rules)) } ?: q }
    }

    /** The run one answer further on, the answer kept as given. A client
     *  that saves the run hands it `answer.recorded()`, so what it replays is
     *  what it played; an in-process `Answer.Act` (the scripted playtest's
     *  conjured cards) plays, but names nothing a file could hold. */
    fun next(answer: Answer): Stepped {
        check(!over) { "the game is over; there is nothing to answer" }
        return Stepped(run.copy(answers = run.answers + answer), rules, engine, engine.answer(at.state, answer))
    }

    /** This run, restated from where it stands: stepping it reaches the same
     *  position without the history before it. */
    fun compacted(): Run = if (over) run else run.copy(start = at.state, answers = emptyList())
}

/** `run`, stepped from its start to its first unanswered question or its end.
 *  Answers past the end are ignored. */
fun step(rules: Rules, run: Run): Stepped {
    val engine = Engine(PlayerInput { Answer.Pass }, rules, answersForced = run.answersForced, sandbox = run.sandbox, undecked = run.undecked, pauses = run.pauses)
    var at = engine.begin(run.start, run.maxTurns)
    for (a in run.answers) {
        if (at.question == null) break
        at = engine.answer(at.state, a)
    }
    return Stepped(run, rules, engine, at)
}

/** Play `start` out with `input` deciding every question, through `step`.
 *  `input` is asked with the asked player's view unless it `seesAll`. For the
 *  batch tools (balance, probes) and the corpus recorder. */
suspend fun playOut(rules: Rules, start: GameState, input: PlayerInput, maxTurns: Int = 60, answersForced: Boolean = true): GameState {
    var s = step(rules, Run(start, maxTurns = maxTurns, answersForced = answersForced))
    while (!s.over) {
        val q = if (input.seesAll(s.full!!.player)) s.full!! else s.question!!
        s = s.next(input.ask(q))
    }
    return s.state
}

/** `playOut` for an input that never suspends (every pilot): the whole game,
 *  now. Throws what the engine throws. */
fun playToEnd(rules: Rules, start: GameState, input: PlayerInput, maxTurns: Int = 60, answersForced: Boolean = true): GameState {
    var out: Result<GameState>? = null
    val body: suspend () -> GameState = { playOut(rules, start, input, maxTurns, answersForced) }
    body.startCoroutine(Continuation(EmptyCoroutineContext) { out = it })
    return (out ?: error("the input suspended; call playOut from a coroutine")).getOrThrow()
}

/** An answer as a run records it: a priority action as the reference it
 *  names (`asAnswer`), a pass when it names none. */
fun Answer.recorded(): Answer = if (this is Answer.Act) action.asAnswer() ?: Answer.Pass else this
