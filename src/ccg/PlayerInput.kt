package ccg

// ---------------------------------------------------------------------------
// The one seam for "something that decides": a scripted opening, a bot, a
// remote human and a JSON client all implement `PlayerInput`.
// ---------------------------------------------------------------------------

/** One thing a modal prompt is offering. DATA, not prose -- the client turns it
 *  into words with `ccgui.effectSummary` / `ccgui.costSummary`, because which
 *  words is a UI decision. */
sealed interface ModeOption {
    /** A mode of `Effect.ChooseMode` -- "destroy it" vs "draw two". */
    data class OfEffect(val effect: Effect) : ModeOption

    /** One of a `Cost.alternatives` -- "{2}" vs "pay 3 hull". */
    data class OfCost(val cost: Cost) : ModeOption

    /** A player -- "target player" (`PlayerRef.Chosen`). */
    data class OfPlayer(val player: PlayerId) : ModeOption
}

/** What the engine asks a player -- the question as DATA, so a remote client, a
 *  replay and a bot all read the same thing.
 *
 *  Answered with an `Answer` of the matching kind. A wrong-kind or illegal
 *  answer becomes `default()` plus a log line, never a re-ask. */
sealed interface Question {
    val player: PlayerId
    val state: GameState?

    /** Take an action, or pass. Answered by `Answer.Act` (in-process) or a
     *  priority reference (`Pass`, `PlayCard`, `ActivateAbility`, `Concede`). */
    data class Priority(override val player: PlayerId, override val state: GameState) : Question
    /** Pick one of `candidates` -- the list the answer is checked against. */
    data class PickTarget(override val player: PlayerId, val candidates: List<ObjectId>, override val state: GameState) : Question
    data class PickNumber(override val player: PlayerId, val label: String, val min: Int, val max: Int) : Question {
        override val state: GameState? get() = null
    }
    /** DECLARED combat: which of `eligible` attack, and what each attacks. */
    data class Attackers(override val player: PlayerId, val eligible: List<ObjectId>, override val state: GameState) : Question
    /** DECLARED combat: blocker -> attacker assignments. */
    data class Blockers(
        override val player: PlayerId,
        val eligibleBlockers: List<ObjectId>,
        val attackers: List<ObjectId>,
        override val state: GameState,
    ) : Question
    /** FREE combat: what one attacker hits, or null to hold it back. `range`
     *: WHICH of this ship's attacks is being aimed -- a
     *  polyvalent ship is asked once per range. Null: the game has no ranges. */
    data class CombatTgt(
        override val player: PlayerId,
        val attacker: ObjectId,
        val range: AttackRange?,
        override val state: GameState,
    ) : Question
    /** INDIVIDUAL combat with a `blockerKeyword`: redirect the attack on
     *  `attacked` to one of `candidates`, or null to let it through. */
    data class Redirect(
        override val player: PlayerId,
        val attacker: ObjectId,
        val candidates: List<ObjectId>,
        override val state: GameState,
        val attacked: CombatTarget? = null,
    ) : Question
    /** Pick `pick` of `options` (by index). Options are DATA, not prose -- see
     *  `ModeOption`. */
    data class PickMode(
        override val player: PlayerId,
        val options: List<ModeOption>,
        val pick: Int,
        override val state: GameState,
    ) : Question {
        val optionCount: Int get() = options.size
    }
    /** Pick card instance ids from `candidates` (a hidden zone). */
    data class PickCards(
        override val player: PlayerId,
        val candidates: List<CardRef>,
        val count: Int,
        override val state: GameState,
        /** `count` is an upper bound, not an exact number (scry). */
        val atMost: Boolean = false,
    ) : Question
}

/** The deterministic answer to `this`: pass, the first candidate, the minimum,
 *  no attack, no block, hold back, the first `pick` modes, the first `count`
 *  cards. The engine substitutes it for a wrong-kind answer; a pilot uses it
 *  for any question it has no opinion on. */
fun Question.default(): Answer = when (this) {
    is Question.Priority -> Answer.Pass
    is Question.PickTarget -> Answer.Target(candidates.firstOrNull() ?: NO_OBJECT)
    is Question.PickNumber -> Answer.Number(min)
    is Question.Attackers -> Answer.Attackers(emptyMap())
    is Question.Blockers -> Answer.Blockers(emptyMap())
    is Question.CombatTgt -> Answer.CombatTgt(null)
    is Question.Redirect -> Answer.Blocker(null)
    is Question.PickMode -> Answer.Modes((0 until pick.coerceIn(0, options.size)).toList())
    is Question.PickCards -> Answer.Cards(candidates.take(count).map { it.instanceId })
}

/** The one seam for "something that decides": one question, one answer.
 *  It was nine methods, one per question, which every client had to implement
 *  and a wire protocol would have had to mirror. */
fun interface PlayerInput {
    suspend fun ask(q: Question): Answer

    /** Is this input allowed to see everything when `player` is asked? False
     *  for every player and bot: its questions carry `viewFor(player)`.
     *  True only for a debugger standing for the author (the Playtest tab). */
    fun seesAll(player: PlayerId): Boolean = false
}

/** This question with `s` as its state (a question with none is itself). */
fun Question.withState(s: GameState): Question = when (this) {
    is Question.Priority -> copy(state = s)
    is Question.PickTarget -> copy(state = s)
    is Question.PickNumber -> this
    is Question.Attackers -> copy(state = s)
    is Question.Blockers -> copy(state = s)
    is Question.CombatTgt -> copy(state = s)
    is Question.Redirect -> copy(state = s)
    is Question.PickMode -> copy(state = s)
    is Question.PickCards -> copy(state = s)
}

/** A question with nothing to choose: a priority window where the seat
 *  has no legal action, or a combat or pick question with no candidates.
 *  THE rule for which questions reach a player -- and so which answers a
 *  session, an agent or the corpus records. Asked of the full state, never a
 *  view: a masked zone must not change what counts as a choice. */
fun Question.forced(rules: Rules): Boolean = when (this) {
    is Question.Priority -> !hasAnyAction(rules, state, player)
    is Question.Attackers -> eligible.isEmpty()
    is Question.Blockers -> eligibleBlockers.isEmpty() || attackers.isEmpty()
    is Question.Redirect -> candidates.isEmpty()
    is Question.PickCards -> candidates.isEmpty()
    else -> false
}

/** `input`, asked with the state each question's player may see: the
 *  engine's one gate between the referee's state and a player. Under
 *  [answersForced] a forced question is answered here with its default and
 *  never asked. */
internal class ViewingInput(private val input: PlayerInput, private val rules: Rules, private val answersForced: Boolean) : PlayerInput {
    private val declaredPublic = rules.publicZoneIds()
    override suspend fun ask(q: Question): Answer {
        if (answersForced && q.forced(rules)) return q.default()
        val s = q.state
        return input.ask(if (s == null || input.seesAll(q.player)) q else q.withState(s.viewFor(q.player, declaredPublic)))
    }
    override fun seesAll(player: PlayerId): Boolean = input.seesAll(player)
}
