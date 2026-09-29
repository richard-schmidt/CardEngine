package ccgui

import ccg.GameState
import ccg.ObjectId
import ccg.PlayerId

// ---------------------------------------------------------------------------
// WHAT JUST HAPPENED, derived from the state itself.
//
// A recap is a DIFF of two states, not a narration of engine calls, because:
//  - it is total: anything that changes the board shows up, including verbs
//    that do not exist yet (triggers, the economy, draws -- not only priority
//    actions);
//  - it needs no second record: `GameState` is immutable data, so
//    "before" and "after" are two values the UI already holds;
//  - it knows the OBJECT IDS, so the board can light what the sentence is
//    about.
//
// Deliberately omitted: phase changes (the header carries them), exhaust/untap
// (constant, low information), and a hand SHRINKING (always explained by
// something else in the same recap).
//
// It cannot leak hidden information: it reads only public shape -- the
// battlefield, graveyards, counters, and how many cards a hand holds.
// ---------------------------------------------------------------------------

/** Ordering, most consequential first. A recap is read in a second or two, so
 *  the first line has to be the one that matters. */
enum class ChangeKind { LOST, ENTERED, LEFT, DAMAGE, COUNTER, CARDS, TURN }

/** One thing that changed, in a sentence, plus the board objects it is ABOUT
 *  so they can be lit while it is read. */
data class Change(
    val kind: ChangeKind,
    val text: String,
    val subjects: Set<ObjectId> = emptySet(),
    /** How much, signed, where "how much" means something -- DAMAGE (+ took,
     *  - healed) and COUNTER (net delta); zero elsewhere. A number so the motion
     *  layer need not parse the sentence. New fields are appended. */
    val magnitude: Int = 0,
    /** Whose counter moved, for player-counter changes with no [subjects]. Says
     *  WHOSE; [magnitude] says which way (no counter is privileged as
     *  health). */
    val player: PlayerId? = null,
)

/** A permanent's name, or `#id` if it has none here. Total on purpose: a recap
 *  runs over a permanent that has just LEFT play, so half its lookups are
 *  expected to miss. */
private fun GameState.nameOf(id: ObjectId): String =
    characteristicsOf(id).name.ifEmpty { "#$id" }

/** Where a permanent went. The zones consulted are all public ones, which is
 *  why this can be said out loud. */
private fun fateOf(after: GameState, id: ObjectId): String = when {
    after.players.values.any { p -> p.graveyard.any { it.instanceId == id } } -> "is destroyed"
    after.exile.any { it.instanceId == id } -> "is exiled"
    after.players.values.any { p -> p.hand.any { it.instanceId == id } } -> "returns to hand"
    // A token ceases to exist rather than going anywhere.
    else -> "leaves play"
}

private fun signed(n: Int): String = if (n > 0) "+$n" else "$n"

/** Everything that changed between two states, in reading order. */
fun recap(before: GameState, after: GameState): List<Change> {
    val out = mutableListOf<Change>()

    // -- someone is out ----------------------------------------------------
    for (p in after.losers - before.losers) {
        out += Change(ChangeKind.LOST, "$p is out of the game", player = p)
    }

    // -- the board ---------------------------------------------------------
    val gone = before.battlefield.keys - after.battlefield.keys
    val arrived = after.battlefield.keys - before.battlefield.keys

    for (id in arrived.sorted()) {
        val perm = after.battlefield[id] ?: continue
        val where = perm.zone.def.takeIf { it != "battlefield" }?.let { " in $it" } ?: ""
        out += Change(
            ChangeKind.ENTERED,
            "${perm.controller}'s ${after.nameOf(id)} enters play$where",
            setOf(id),
        )
    }
    for (id in gone.sorted()) {
        val perm = before.battlefield[id] ?: continue
        out += Change(
            ChangeKind.LEFT,
            "${perm.controller}'s ${before.nameOf(id)} ${fateOf(after, id)}",
            setOf(id),
        )
    }

    // -- what survived, and how it changed ---------------------------------
    for ((id, now) in after.battlefield) {
        val was = before.battlefield[id] ?: continue
        val name = after.nameOf(id)

        if (now.damageMarked != was.damageMarked) {
            val d = now.damageMarked - was.damageMarked
            out += Change(
                ChangeKind.DAMAGE,
                if (d > 0) "$name takes $d damage" else "$name heals ${-d}",
                setOf(id),
                magnitude = d,
            )
        }
        // Counters carry the load in a game whose LOSS CONDITION is one
        // (EPR Skirmish's hull), so a bare delta is not enough -- the reader
        // needs the number it landed on.
        for (kind in (was.counters.keys + now.counters.keys).sorted()) {
            val a = was.counters[kind] ?: 0
            val b = now.counters[kind] ?: 0
            if (a == b) continue
            out += Change(ChangeKind.COUNTER, "$name $kind $a → $b", setOf(id), magnitude = b - a)
        }
        if (now.zone != was.zone) {
            out += Change(ChangeKind.ENTERED, "$name moves to ${now.zone.def}", setOf(id))
        }
    }

    // -- the players -------------------------------------------------------
    for ((pid, now) in after.players) {
        val was = before.players[pid] ?: continue
        for (kind in (was.counters.keys + now.counters.keys).sorted()) {
            val a = was.counters[kind] ?: 0
            val b = now.counters[kind] ?: 0
            if (a == b) continue
            out += Change(
                ChangeKind.COUNTER,
                "$pid $kind $a → $b (${signed(b - a)})",
                magnitude = b - a,
                player = pid,
            )
        }
        val drawn = now.hand.size - was.hand.size
        if (drawn > 0) {
            // The COUNT, never the cards. A recap that named a drawn card would
            // undo the hiding the viewpoint just built.
            out += Change(ChangeKind.CARDS, "$pid draws $drawn card${if (drawn == 1) "" else "s"}")
        }
    }

    // -- the clock ---------------------------------------------------------
    if (after.turnNumber != before.turnNumber) {
        out += Change(ChangeKind.TURN, "round ${after.turnNumber}")
    }

    return out.sortedBy { it.kind.ordinal }
}

/** The opening position, said out loud: there is no "before" at game start,
 *  where a player is most lost. Public shape only, as `recap`. */
fun openingRecap(state: GameState, viewer: PlayerId): List<Change> {
    val out = mutableListOf<Change>()
    // The viewer's own side last, so the list reads down the table towards them
    // -- the same order the board rows are in.
    val seats = state.turnOrder.filter { it != viewer } + state.turnOrder.filter { it == viewer }
    for (pid in seats) {
        val p = state.players[pid] ?: continue
        val mine = state.battlefield.values.filter { it.controller == pid }.sortedBy { it.id }
        for (perm in mine) {
            val where = perm.zone.def.takeIf { it != "battlefield" }?.let { " in $it" } ?: ""
            out += Change(ChangeKind.ENTERED, "$pid starts with ${state.nameOf(perm.id)}$where", setOf(perm.id))
        }
        val counters = p.counters.filterValues { it != 0 }.entries.sortedBy { it.key }
        if (counters.isNotEmpty()) {
            out += Change(ChangeKind.COUNTER, "$pid: " + counters.joinToString(", ") { "${it.key} ${it.value}" })
        }
        val pools = p.customZones.filterValues { it.isNotEmpty() }.entries.sortedBy { it.key }
        for ((zone, cards) in pools) {
            out += Change(ChangeKind.CARDS, "$pid has ${cards.size} in $zone")
        }
        out += Change(ChangeKind.CARDS, "$pid holds ${p.hand.size} card${if (p.hand.size == 1) "" else "s"}")
    }
    // NOT sorted by kind: the grouping that matters here is the SEAT, so that
    // each side reads as one block rather than all the boards, then all the
    // counters, then all the hands.
    return out
}
