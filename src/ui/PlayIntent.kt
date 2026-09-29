package ccgui

import ccg.ActivatedAbility
import ccg.CardRef
import ccg.ObjectId
import ccg.legalActionsFor
import ccg.CardSource
import ccg.CastZone
import ccg.GameState
import ccg.playActionFor
import ccg.Legality
import ccg.PlayerId
import ccg.PriorityAction
import ccg.Rules
import ccg.legality

// ---------------------------------------------------------------------------
// What a tap on a castable card should DO -- the decision as data, outside
// Compose. THREE affordances can commit a play (the hand tile's
// second tap, the "▶ Play?" pill, the prompt bar's chip); the lane policy was
// once written into only one of them and the picker silently never appeared.
// One policy, one place, called by all three, covered by `UiTest.kt`.
// ---------------------------------------------------------------------------

/** The outcome of a tap on a castable card. */
sealed interface PlayIntent {
    /** Not playable (illegal, or nothing there) -- the tap does nothing. */
    data object Ignore : PlayIntent

    /** First tap on a legal card: select it, wait for confirmation. */
    data object Arm : PlayIntent

    /** Tap while a zone choice is pending: back out. */
    data object Cancel : PlayIntent

    /** Submit this action to the engine as-is. */
    data class Commit(val action: PriorityAction) : PlayIntent

    /** Ask which zone first -- `zones` is already narrowed to those with room
     *  and always holds at least two entries (one or none is a `Commit`). */
    data class ChooseZone(val action: PriorityAction.PlayPermanent, val zones: List<String>) : PlayIntent
}

/** The play zones this action could still enter, narrowed to those with room
 *  under `PlayZoneDef.maxOccupants`, or null when the card's types impose no
 *  restriction at all (every pre-lane-plan type -- no choice to offer). */
fun openZonesFor(
    rules: Rules,
    state: GameState,
    player: PlayerId,
    action: PriorityAction,
): List<String>? {
    if (action !is PriorityAction.PlayPermanent) return null
    val face = action.card.faces.getOrElse(action.face) { action.card.faces[0] }
    val declared = rules.legalZonesFor(face.types) ?: return null
    return declared.filter { rules.zoneHasRoom(state, player, it) }
}

/** Commit an already-selected play: submit it, or ask for a zone first. Used by
 *  EVERY finalising affordance. A single open zone is committed EXPLICITLY (the
 *  engine's default is the first declared zone, which may be full). */
fun commitIntent(
    rules: Rules,
    state: GameState,
    player: PlayerId,
    action: PriorityAction,
): PlayIntent {
    val open = openZonesFor(rules, state, player, action) ?: return PlayIntent.Commit(action)
    val perm = action as? PriorityAction.PlayPermanent ?: return PlayIntent.Commit(action)
    return when (open.size) {
        0 -> PlayIntent.Commit(action) // nowhere to go; `legality` refuses it
        1 -> PlayIntent.Commit(perm.copy(zone = open.single()))
        else -> PlayIntent.ChooseZone(perm, open)
    }
}

/** A tap on a castable card tile. `armed` = already selected by an earlier
 *  tap; `choosingZone` = this same card is currently showing a zone picker. */
fun tapIntent(
    rules: Rules,
    state: GameState,
    player: PlayerId,
    action: PriorityAction?,
    armed: Boolean,
    choosingZone: Boolean,
): PlayIntent = when {
    action == null || legality(rules, state, player, action) !is Legality.Legal -> PlayIntent.Ignore
    choosingZone -> PlayIntent.Cancel
    armed -> commitIntent(rules, state, player, action)
    else -> PlayIntent.Arm
}

/** Is this action legal right now? The single predicate behind "can I play
 *  this" -- the green ring on a castable tile and the pool's own fold state
 *  both ask it, so the two can never disagree. */
fun isPlayableNow(rules: Rules, state: GameState, player: PlayerId, action: PriorityAction): Boolean =
    legality(rules, state, player, action) is Legality.Legal

/** Is anything in this castable zone playable right now? Lights the folded
 *  pool chip (a Flagship pool), asking `legality()` through `playActionFor` --
 *  the same action constructor the tiles use. */
fun anyPlayableIn(
    rules: Rules,
    state: GameState,
    player: PlayerId,
    refs: List<CardRef>,
    zone: CastZone,
): Boolean = refs.any { ref ->
    val built = rules.cards[ref.cardId] ?: return@any false
    isPlayableNow(rules, state, player, playActionFor(rules, built, CardSource(zone, ref.instanceId)))
}

// ---------------------------------------------------------------------------
// Acting from the board: what a permanent could DO. The board is the one place
// you act -- play, attack and activate alike.
// ---------------------------------------------------------------------------

/** One thing a permanent on the board could do right now, and what to call it. */
data class PermMove(val action: PriorityAction.Activate, val label: String)

/** An activated ability, in words: its author-given `name`, else built from the
 *  cost and the effect ("ability 2" says nothing). */
fun abilityLabel(a: ActivatedAbility): String = a.name.ifBlank {
    val c = costSummary(a.cost)
    val e = effectSummary(a.effect)
    when {
        c.isBlank() -> e
        e.isBlank() -> c
        else -> "$c: $e"
    }
}

/** The activations the PROMPT BAR must carry: those whose source the board draws
 *  no tile for. Subtracting the board's rendered set means whatever the board
 *  stops drawing, the bar carries -- no ability can become unreachable. */
fun activationsWithoutTile(
    rules: Rules,
    state: GameState,
    player: PlayerId,
    /** Whose board is on screen -- the layout is built for a VIEWER, and a
     *  seat only ever activates its own permanents, so this is normally the
     *  same seat that is being asked. */
    viewer: PlayerId = player,
): Map<ObjectId, List<PermMove>> {
    val drawn = boardLayout(rules, state, viewer).renderedIds()
    return activationsBy(rules, state, player).filterKeys { it !in drawn }
}

/** Every activation this player could make, grouped by source permanent, from
 *  `legalActionsFor` (the engine's own enumeration, not a second answer to "may
 *  this happen"). Computed once per state, not per tile. */
fun activationsBy(
    rules: Rules,
    state: GameState,
    player: PlayerId,
): Map<ObjectId, List<PermMove>> =
    legalActionsFor(rules, state, player)
        .filterIsInstance<PriorityAction.Activate>()
        .groupBy { it.source }
        .mapValues { (src, acts) ->
            val abilities = state.abilitiesOf(src, rules)
            acts.map { a ->
                // An index with no ability behind it should be impossible --
                // `legalActionsFor` built it BY indexing `abilitiesOf` -- but a
                // label is not worth a crash, and "ability 3" at least names it.
                PermMove(a, abilities.getOrNull(a.index)?.let(::abilityLabel)?.ifBlank { null }
                    ?: "ability ${a.index + 1}")
            }
        }
