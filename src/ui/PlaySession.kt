package ccgui

import ccg.Answer
import ccg.GameDoc
import ccg.GameState
import ccg.Rules
import ccg.DeckPick
import ccg.startGame
import ccg.Run
import ccg.PlayerId
import ccg.undeckedSeats

// ---------------------------------------------------------------------------
// A play session, stated completely: **a game in progress is fully described
// by (bundle, seed, deck picks, answers)**. Everything else is DERIVED by
// replay. Here in `src/ui` so it is tested.
//
// That buys save / resume (four small values), reproducible bug reports (an
// answer list), and opponents as just another answer source.
//
// Not part of it: which tab is showing, focus, the sandbox and zones toggles --
// how you LOOK at the session, not what it is.
// ---------------------------------------------------------------------------

/** The engine's own default seed, so a fresh session is reproducible. */
const val DEFAULT_SEED: Int = -1640531527 // 0x9E3779B9

data class PlaySession(
    val seed: Int = DEFAULT_SEED,
    val p0Deck: Int = 0,
    val p1Deck: Int = 0,
    /** Every answer given, in order, the bot's included; replaying them
     *  rebuilds the exact game.
     *  Typed and serialisable, carrying REFERENCES (a card instance and its
     *  zone), never objects. */
    val answers: List<Answer> = emptyList(),
    /** Bumped whenever the session must be rebuilt from the start rather than
     *  merely extended -- a reshuffle, an undo, a deck change. The UI keys its
     *  game construction on this, so it knows the difference between "one more
     *  answer" and "a different game". */
    val generation: Int = 0,
    /** "Surprise me" for this seat's deck: a STICKY PREFERENCE, not a deck index.
     *  The resolved pick lands in `p0Deck`/`p1Deck`, so everything downstream
     *  reads a concrete integer and a saved session names the decks actually
     *  played; the flag only says what to do when the next game starts. */
    val p0Random: Boolean = false,
    val p1Random: Boolean = false,
    /** Which pilot profile plays the AI seat (null = default). Its answers
     *  are recorded in `answers` like a person's, so this only decides
     *  how the game goes on from here, never how it replays. */
    val pilot: String? = null,
    /** "Surprise me" for the opponent profile -- same sticky shape as the
     *  deck flags above. */
    val pilotRandom: Boolean = false,
    /** The `bundleDigest` of the game these answers were recorded against
     * . Null until the session first plays. */
    val bundle: String? = null,
    /** The Debug lens was on while a bot held a seat: someone
     *  saw the bot's hand, or undid a move. Such a game is a test, never a
     *  result. A property of THIS game, so `nextGame` drops it. */
    val debugged: Boolean = false,
    /** A sandbox bench: started from the Sandbox tab, Try it or a
     *  scenario. Every priority question is asked (`Run.pauses`), so the
     *  table always stops where an edit can go in. The kind of table, like
     *  the decks: `nextGame` keeps it, `decked` leaves it. */
    val sandbox: Boolean = false,
) {
    /** At least one move has been made -- drives the "game in progress" dot. */
    val inProgress: Boolean get() = answers.isNotEmpty()

    /** Extend the session. Does NOT bump the generation: the game is the same
     *  game, one answer further on. */
    fun answered(a: Answer): PlaySession = copy(answers = answers + a)

    fun undone(): PlaySession =
        if (answers.isEmpty()) this else copy(answers = answers.dropLast(1), generation = generation + 1)

    /** Back to the first [n] answers: undo to a chosen point (`undoPoint`). */
    fun undoneTo(n: Int): PlaySession =
        if (n >= answers.size) this else copy(answers = answers.take(n.coerceAtLeast(0)), generation = generation + 1)

    /** The same deal, from the top -- drop every answer, keep the seed. The
     *  distinction from `reshuffled()` is real: this replays the SAME opening
     *  hands, which is what you want when a line of play went wrong and you
     *  mean to try a different one. */
    fun restarted(): PlaySession =
        if (answers.isEmpty()) this else copy(answers = emptyList(), generation = generation + 1)

    /** A different deal of the same decks. */
    fun reshuffled(): PlaySession =
        copy(seed = seed * 31 + 0x5BF03635, answers = emptyList(), generation = generation + 1)

    /** A different deck is a DIFFERENT GAME, so the answers must go with it --
     *  replaying old answers into a new deal is how a session corrupts itself. */
    fun withDecks(p0: Int = p0Deck, p1: Int = p1Deck): PlaySession =
        if (p0 == p0Deck && p1 == p1Deck) {
            this
        } else {
            copy(p0Deck = p0, p1Deck = p1, answers = emptyList(), generation = generation + 1)
        }

    /** A different opponent is a DIFFERENT GAME, for exactly the reason the
     *  pilot is part of the session at all: the bot's answers are replayed by
     *  re-asking the bot, so changing who is asked invalidates every answer
     *  already recorded. Same shape as `withDecks`, same reason. */
    fun withPilot(name: String?): PlaySession =
        if (name == pilot) this else copy(pilot = name, answers = emptyList(), generation = generation + 1)

    /** Cycle a seat's deck -- the affordance shape the hotseat's top bar uses.
     *  A count of 0 or 1 leaves it alone rather than dividing by zero. */
    fun cycleP0(deckCount: Int): PlaySession =
        if (deckCount <= 1) this else withDecks(p0 = (p0Deck + 1) % deckCount)

    fun cycleP1(deckCount: Int): PlaySession =
        if (deckCount <= 1) this else withDecks(p1 = (p1Deck + 1) % deckCount)

    /** Cycle the opponent profile through the offered list, wrapping. Null IS the
     *  default profile, so cycling reads its position rather than its absence. */
    fun cyclePilot(profiles: List<String>): PlaySession {
        if (profiles.isEmpty()) return this
        val at = profiles.indexOf(pilot ?: DEFAULT_PILOT)
        return withPilot(profiles[(at + 1) % profiles.size])
    }

    companion object {
        /** A fresh session for a bundle: seat 1 takes the SECOND deck when the
         *  game has one, so a two-faction bundle opens as a real matchup rather
         *  than a mirror. */
        fun forGame(deckCount: Int): PlaySession =
            PlaySession(p1Deck = if (deckCount > 1) 1 else 0)
    }
}

/** ANOTHER GAME OF THE SAME BUNDLE, with the picker as you left it: answers,
 *  seed and generation reset; the decks, "random each time" and the opponent are
 *  the player's preferences and carry over. An explicit construction rather than
 *  `copy(...)`, so a new field must be classified as game or preference here. */
fun PlaySession.nextGame(): PlaySession = PlaySession(
    // Preferences: what the picker shows, and what you said.
    p0Deck = p0Deck,
    p1Deck = p1Deck,
    p0Random = p0Random,
    p1Random = p1Random,
    pilot = pilot,
    pilotRandom = pilotRandom,
    sandbox = sandbox,
    // Everything else -- seed, answers, generation, `debugged` -- is the
    // game, and the game is over. Left at its default deliberately.
)

/** A DIFFERENT bundle: its own opening deck picks (a deck index means nothing
 *  across games), carrying only the preferences that are about how you like to
 *  play rather than about which game you are playing. */
fun PlaySession.nextBundle(deckCount: Int): PlaySession =
    PlaySession.forGame(deckCount).copy(
        p0Random = p0Random,
        p1Random = p1Random,
        pilot = pilot,
        pilotRandom = pilotRandom,
    )

/** One index out of `n` from a roll and a salt. Salted so the picks one roll
 *  drives do not move in lockstep; folded to non-negative by hand (Kotlin's `%`
 *  keeps the left side's sign). */
internal fun pickIndex(roll: Int, salt: Int, n: Int): Int {
    if (n <= 1) return 0
    val h = roll * 31 + salt * 0x5BF03635
    return ((h % n) + n) % n
}

/** RESOLVE the sticky random picks at the moment a new game starts, from a fresh
 *  platform roll. Not derived from `seed`: `restarted()` keeps the seed on
 *  purpose (same opening hands to retry a line). Pure, taking the roll as a
 *  parameter. */
fun PlaySession.rerolled(deckCount: Int, profiles: List<String>, roll: Int): PlaySession {
    var s = this
    if (p0Random) s = s.withDecks(p0 = pickIndex(roll, 1, deckCount))
    if (p1Random) s = s.withDecks(p1 = pickIndex(roll, 2, deckCount))
    if (pilotRandom && profiles.isNotEmpty()) s = s.withPilot(profiles[pickIndex(roll, 3, profiles.size)])
    return s
}

/** The opening position of this session's picks: `ccg.startGame`, the one
 *  place any client's picks become a game. */
fun PlaySession.opening(doc: GameDoc, rules: Rules): GameState =
    startGame(doc, rules, listOf(DeckPick(p0Deck), DeckPick(p1Deck)), seed)

/** The seats this session deals no deck (`DeckPick.NONE`): the empty table. */
fun PlaySession.undecked(): Set<PlayerId> = undeckedSeats(listOf(DeckPick(p0Deck), DeckPick(p1Deck)))

/** How a person's table runs this session from [start]: table edits are
 *  taken (the Debug drawer's, on any game), a seat with no deck never decks
 *  out, and a sandbox bench asks every priority question. */
fun PlaySession.tableRun(start: GameState, maxTurns: Int = TABLE_MAX_TURNS): Run =
    Run(start, maxTurns = maxTurns, sandbox = true, undecked = undecked(), pauses = sandbox)

/** This session, bound to the game about to replay it. A session that
 *  already names this game is unchanged. One with no answers yet is stamped with
 *  the game. One recorded against a different game (or an unknown one) restarts
 *  under a new generation: its answers belong to other rules, and replaying them
 *  would silently play a different game. `dropped` says how many were lost. */
data class Bound(val session: PlaySession, val dropped: Int)

fun PlaySession.boundTo(digest: String): Bound = when {
    bundle == digest -> Bound(this, 0)
    answers.isEmpty() -> Bound(copy(bundle = digest), 0)
    else -> Bound(copy(answers = emptyList(), generation = generation + 1, bundle = digest), answers.size)
}
