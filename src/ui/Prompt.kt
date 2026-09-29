package ccgui

import ccg.Question
import ccg.Answer
import ccg.CardRef
import ccg.CombatTarget
import ccg.GameState
import ccg.ModeOption
import ccg.ObjectId
import ccg.PlayerId
import ccg.PriorityAction
import ccg.Rules
import ccg.asAnswer

// ---------------------------------------------------------------------------
// What the engine is ASKING, and what answering by touching the board means:
// which tiles may be tapped, what a tap means, and how a raw UI answer becomes
// a recordable `Answer`. Decisions, not drawing, so they live here where
// `test.sh` can see them.
// ---------------------------------------------------------------------------

// `Question` -- what the engine asks -- lives in ccg.


/** Record a raw UI answer as a typed `Answer` -- the encode half (`Answer.raw()`
 *  decodes). It needs the question because one raw value means different things
 *  (an `ObjectId` is a target for PickTarget, a blocker for Redirect). A
 *  priority action is recorded by REFERENCE (`asAnswer()`). */
@Suppress("UNCHECKED_CAST")
fun encodeAnswer(prompt: Question, raw: Any?): Answer? = when (prompt) {
    // A sandbox edit is already an answer; an action names one.
    is Question.Priority -> (raw as? Answer.Edit) ?: (raw as? PriorityAction)?.asAnswer()
    is Question.PickTarget -> (raw as? ObjectId)?.let { Answer.Target(it) }
    is Question.PickNumber -> (raw as? Int)?.let { Answer.Number(it) }
    // The UI sends a LIST of attackers (the override decides what they
    // attack); a richer client may send the map. Both accepted; the map is
    // recorded.
    is Question.Attackers -> when (raw) {
        is Map<*, *> -> Answer.Attackers(raw as Map<ObjectId, CombatTarget>)
        is List<*> -> Answer.Attackers(
            (raw as List<ObjectId>).associateWith { CombatTarget.Player(prompt.state.opponentOf(prompt.player)) },
        )
        else -> null
    }
    is Question.Blockers -> (raw as? Map<ObjectId, ObjectId>)?.let { Answer.Blockers(it) }
    is Question.CombatTgt -> Answer.CombatTgt(raw as? CombatTarget)
    is Question.Redirect -> Answer.Blocker(raw as? ObjectId)
    is Question.PickMode -> (raw as? List<Int>)?.let { Answer.Modes(it) }
    is Question.PickCards -> (raw as? List<ObjectId>)?.let { Answer.Cards(it) }
}

fun boardTapTargets(rules: Rules, prompt: Question?, s: GameState): Set<ObjectId> = when (prompt) {
    is Question.PickTarget -> prompt.candidates.filter { it in s.battlefield }.toSet()
    // The defender's Station is a tap target too, so a Station is targeted in
    // the same grammar as a Ship (ring, pill and arc all read this set). Both
    // narrowings the pilots use are asked here -- `laneReaches` for boards and
    // `canAttackFace` for the face -- so the UI never offers what the engine
    // refuses.
    is Question.CombatTgt -> {
        val defender = s.opponentOf(prompt.player)
        combatBoardTargets(rules, s, prompt.player, prompt.attacker, prompt.range) +
            setOfNotNull(
                playerTargetAnchor(rules, s, defender)
                    .takeIf { s.canAttackFace(rules, prompt.attacker, defender, prompt.range) },
            )
    }
    is Question.Redirect -> prompt.candidates.filter { it in s.battlefield }.toSet()
    else -> emptySet()
}

/** What tapping `id` MEANS for this prompt. The same tap is
 *  `CombatTarget.Player` on the permanent standing for the player and
 *  `CombatTarget.Obj` elsewhere -- invisible to the tile, so resolved here beside
 *  the set that offered it. */
fun tapAnswer(rules: Rules, prompt: Question, id: ObjectId): Any = when (prompt) {
    is Question.CombatTgt -> {
        val defender = prompt.state.opponentOf(prompt.player)
        if (id == playerTargetAnchor(rules, prompt.state, defender)) CombatTarget.Player(defender)
        else CombatTarget.Obj(id)
    }
    else -> id   // PickTarget, Redirect
}

// ---------------------------------------------------------------------------
// Arcs: drawn ON ARM from the acting card to
// everything it could reach. `arcsFor` calls `boardTapTargets`, the function the
// taps use, so an arc cannot promise a target the tap would refuse.
// ---------------------------------------------------------------------------

/** One end of an arc: a card, a lane slot (picking where a card goes is the same
 *  question in the same grammar), or the hand. */
sealed interface ArcEnd {
    /** A permanent, which has a tile on the board. */
    data class Obj(val id: ObjectId) : ArcEnd

    /** A play zone -- a lane a card could enter. Not a permanent: the target
     *  is the empty space, which is why the grid draws real empty slots. */
    data class Zone(val zone: String) : ArcEnd

    /** Off-board: the viewer's own hand, docked outside the board's scroll, so
     *  an arc from it starts at the board's bottom edge. */
    data object Hand : ArcEnd
}

/** One thing, and everything it could reach. */
data class ArcSpec(val source: ArcEnd, val targets: Set<ArcEnd>) {
    /** The permanents among the targets -- i.e. what the TAPS answer. Kept as
     *  an accessor so a test can pin that arcs and taps agree without caring
     *  how an end is modelled. */
    val targetIds: Set<ObjectId> get() = targets.filterIsInstance<ArcEnd.Obj>().map { it.id }.toSet()
}

/** Where to draw arcs from and to, or null when nothing is acting.
 *  `armedSource` is the tile already tapped once -- the only way to know the
 *  source when a prompt names several (a blocker armed, arcs to every attacker
 *  it could cover). A prompt naming its own attacker needs no arming. */
fun arcsFor(
    rules: Rules,
    prompt: Question?,
    s: GameState,
    armedSource: ObjectId? = null,
    /** A play waiting on a zone choice. When one is pending it IS the question
     *  on screen, so it takes precedence over anything the prompt would ask --
     *  the same precedence the prompt bar already gave it. */
    pendingPlay: PriorityAction.PlayPermanent? = null,
): ArcSpec? {
    // The lane pick, answered by tapping a lit slot. `openZonesFor` is the same
    // query a commit accepts; null (no declared choice) means no arcs.
    if (pendingPlay != null && prompt is Question.Priority) {
        val zones = openZonesFor(rules, s, prompt.player, pendingPlay).orEmpty()
        return if (zones.isEmpty()) null else ArcSpec(ArcEnd.Hand, zones.map { ArcEnd.Zone(it) }.toSet())
    }
    val source = when (prompt) {
        is Question.CombatTgt -> prompt.attacker
        is Question.Redirect -> prompt.attacker
        // Blockers has no single source until one is armed; PickTarget's source
        // is a spell on the stack rather than a permanent, so it has no tile to
        // draw from and gets no arcs rather than a wrong one.
        is Question.Blockers -> armedSource
        else -> null
    } ?: return null
    if (source !in s.battlefield) return null

    val targets = when (prompt) {
        // The attackers this armed blocker could be assigned to.
        is Question.Blockers -> prompt.attackers.filter { it in s.battlefield }.toSet()
        // THE tap answer, not a recomputation of it.
        else -> boardTapTargets(rules, prompt, s)
    } - source

    return if (targets.isEmpty()) null else {
        ArcSpec(ArcEnd.Obj(source), targets.map { ArcEnd.Obj(it) }.toSet())
    }
}
