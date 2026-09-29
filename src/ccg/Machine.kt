package ccg

// ---------------------------------------------------------------------------
// Pending work as data.
//
// A game between two questions is a `GameState` whose `pending` holds a stack
// of FRAMES, bottom first: the turn structure (`Frame.Game`), then one
// `Frame.Window` per priority window open inside it. Nothing else remembers
// where the game is -- no coroutine, no callback, no history outside the
// state -- so a question's state is enough to carry on from
// (`Engine.answer`).
//
// Each frame is code plus a CHECKPOINT: the state its code last restarted
// from, and a LOG of what came back to it since -- the answers it was given,
// and the end states of the windows nested inside it. Resuming a frame runs
// its code again from the checkpoint, handing it the log in order; where the
// log runs out the code stops again (`Yield`). The engine is deterministic, so
// the run retraces itself exactly. Checkpoints move forward as the game does:
//
//   - a window restarts at each priority question: its state and the loop's
//     counters (`PriorityWindow`) are all that loop needs, so its log never
//     holds more than one priority answer and what that action asked;
//   - the game frame restarts after each window the turn opens (`TurnPoint`),
//     so its log holds only what the turn asked since -- a trigger's target,
//     a discard at end of turn, and the declarations and windows of combat.
//
// So resuming never re-runs more than one step of the game.
// ---------------------------------------------------------------------------

/** What came back to a frame's code at a point where it had to stop. */
sealed interface Resumed {
    /** The answer to the question it asked. */
    data class Answered(val answer: Answer) : Resumed

    /** A window it opened (inside combat, or inside an action) closed in
     *  this state. */
    data class WindowClosed(val state: GameState) : Resumed
}

/** One piece of pending work: see the file header. [start] never carries
 *  `pending` or `priority` itself. */
sealed interface Frame {
    val start: GameState
    val log: List<Resumed>

    /** The turn structure: from the start of the game when [after] is null,
     *  else from just after the window the turn opened at [after]. */
    data class Game(
        override val start: GameState,
        val after: TurnPoint?,
        val maxTurns: Int,
        override val log: List<Resumed> = emptyList(),
    ) : Frame

    /** A priority window: fresh (settle, then the loop) while [window] is
     *  null, else at the question asked with those counters. [at] is set for
     *  a window the turn itself opened. */
    data class Window(
        override val start: GameState,
        val window: PriorityWindow?,
        val at: TurnPoint?,
        override val log: List<Resumed> = emptyList(),
    ) : Frame

    fun logged(r: Resumed): Frame = when (this) {
        is Game -> copy(log = log + r)
        is Window -> copy(log = log + r)
    }
}

/** Where a run stands: the state (carrying its `pending` frames) and the
 *  question it waits on, or null when the game has ended. */
class Paused internal constructor(val state: GameState, val question: Question?)

/** How the engine's code stops: control flow, not an error, so it carries no
 *  stack trace. Thrown only by `ReplayYielder`, caught only by the machine. */
internal sealed class Yield : RuntimeException(null, null, false, false) {
    class Ask(val q: Question) : Yield()

    /** A priority question: the window's frame moves its checkpoint here. */
    class Checkpoint(val q: Question, val state: GameState, val window: PriorityWindow) : Yield()

    /** A window opens: a new frame goes on top. */
    class Window(val state: GameState, val at: TurnPoint?) : Yield()
}

/** Where the engine's code gets answers and window results. */
internal interface Yielder {
    /** The answer to [q]. [checkpoint]: a priority question's state (without
     *  its window) and window, where a window's frame can restart. */
    fun ask(q: Question, checkpoint: Pair<GameState, PriorityWindow>? = null): Answer

    /** Run a priority window from [s] and return the state it closes in. */
    fun window(s: GameState, at: TurnPoint?): GameState
}

/** A frame's log, replayed: each stop takes the next entry; past the end,
 *  the code stops (`Yield`). A forced question is answered here, never
 *  logged. */
internal class ReplayYielder(private val log: List<Resumed>, private val forced: (Question) -> Boolean) : Yielder {
    private var i = 0

    /** The last state the code showed, for a question that carries none. */
    var seen: GameState? = null
        private set

    override fun ask(q: Question, checkpoint: Pair<GameState, PriorityWindow>?): Answer {
        q.state?.let { seen = it }
        if (forced(q)) return q.default()
        if (i < log.size) {
            return (log[i++] as? Resumed.Answered ?: error("replay: a question where the log has a window")).answer
        }
        throw if (checkpoint != null) Yield.Checkpoint(q, checkpoint.first, checkpoint.second) else Yield.Ask(q)
    }

    override fun window(s: GameState, at: TurnPoint?): GameState {
        seen = s
        if (i < log.size) {
            check(at == null) { "replay: the turn's own window is a checkpoint, never in a log" }
            return (log[i++] as? Resumed.WindowClosed ?: error("replay: a window where the log has an answer")).state
        }
        throw Yield.Window(s, at)
    }
}
