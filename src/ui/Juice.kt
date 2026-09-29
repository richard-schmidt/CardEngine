package ccgui

import ccg.ObjectId
import ccg.PlayerId

// ---------------------------------------------------------------------------
// Board juice: WHAT the board should react to, derived from `recap` (the one
// tested diff of two states) with no Compose. This decides that a tile was hit
// for 3; Compose decides what that LOOKS like.
// ---------------------------------------------------------------------------

/** Every tunable number the board's motion spends, in one place. A data class so
 *  the Player's tuning sheet can edit it live; settled values become the
 *  defaults. Milliseconds and dp; every field could be zero and the board would
 *  be correct, just inert. */
data class JuiceConfig(
    /** Master switch. Off = no motion at all, so the board can be compared
     *  with and without it. */
    val enabled: Boolean = true,
    // -- a card arriving in its lane --
    val arrivalTravelDp: Float = 64f,
    val arrivalStiffness: Float = 240f,
    val arrivalDamping: Float = 0.62f,
    val arrivalSpinDeg: Float = 8f,
    // -- damage landing --
    val impactShakeDp: Float = 7f,
    val impactShakeCycles: Float = 3f,
    val impactMs: Int = 420,
    val impactFlash: Float = 0.8f,
    /** How far the damage numeral flies before it fades. */
    val numeralRiseDp: Float = 46f,
    val numeralMs: Int = 900,
    // -- a counter ticking --
    val counterPopScale: Float = 1.22f,
    val counterMs: Int = 320,
    // -- the clock and the end --
    /** Particles. `particleScale` 0 turns them off without turning off
     *  the rest of the motion -- they are the most expensive thing here and the
     *  most likely to be somebody's "too much". */
    val particleScale: Float = 1f,
    val particleMs: Int = 640,
    val turnSweepMs: Int = 560,
    val endFlourishMs: Int = 1100,
) {
    companion object {
        val DEFAULT = JuiceConfig()
        /** Everything off. Not `enabled = false` -- that is the A/B switch; this
         *  is what the accessibility path uses, where the board must still be
         *  correct with no motion at all. */
        val NONE = JuiceConfig(enabled = false)
    }
}

/** What the board should REACT to for one transition. Signed and never
 *  interpreted: a counter delta of -3 is "down by three", not "damage". */
data class BoardFx(
    /** Permanents that just entered play. NOT consumed by the board: `BoardView`
     *  answers "is this tile new" with its own `newPermIds`. If this is ever
     *  consumed, delete `newPermIds` in the same change. */
    val arrivals: Set<ObjectId> = emptySet(),
    /** Permanents that just left. Not consumed yet: animating a departure needs
     *  the tile to outlive its removal (`AnimatedVisibility` over the lane), a
     *  real change to how lanes compose. A card still vanishes. */
    val departures: Set<ObjectId> = emptySet(),
    /** Damage marked on a permanent. Positive took it, negative healed. */
    val damage: Map<ObjectId, Int> = emptyMap(),
    /** Net counter movement on a permanent, signed. */
    val tileCounters: Map<ObjectId, Int> = emptyMap(),
    /** Net counter movement on a SEAT, signed. In EPR Skirmish this is the
     *  loss condition moving, and it is the single most consequential thing
     *  that happens on the board. */
    val seatCounters: Map<PlayerId, Int> = emptyMap(),
    /** Seats knocked out by this transition. */
    val eliminated: Set<PlayerId> = emptySet(),
    /** The round advanced. */
    val turnChanged: Boolean = false,
) {
    /** Nothing to play. Worth asking before starting any animation at all --
     *  most state transitions in a priority-passing game change nothing a
     *  player can see. */
    val isEmpty: Boolean
        get() = arrivals.isEmpty() && departures.isEmpty() && damage.isEmpty() &&
            tileCounters.isEmpty() && seatCounters.isEmpty() && eliminated.isEmpty() &&
            !turnChanged

    companion object {
        val NONE = BoardFx()
    }
}

/** Fold a recap into what the board should play. Built on the recap, so it
 *  inherits its omissions and its guarantee of never reading a hidden zone.
 *  Deltas accumulate (two hits on one permanent shake for the total). */
fun boardFx(changes: List<Change>): BoardFx {
    val arrivals = mutableSetOf<ObjectId>()
    val departures = mutableSetOf<ObjectId>()
    val damage = mutableMapOf<ObjectId, Int>()
    val tileCounters = mutableMapOf<ObjectId, Int>()
    val seatCounters = mutableMapOf<PlayerId, Int>()
    val eliminated = mutableSetOf<PlayerId>()
    var turnChanged = false

    for (c in changes) {
        when (c.kind) {
            ChangeKind.ENTERED -> arrivals += c.subjects
            ChangeKind.LEFT -> departures += c.subjects
            ChangeKind.DAMAGE ->
                for (id in c.subjects) damage[id] = (damage[id] ?: 0) + c.magnitude
            ChangeKind.COUNTER -> {
                for (id in c.subjects) tileCounters[id] = (tileCounters[id] ?: 0) + c.magnitude
                c.player?.let { seatCounters[it] = (seatCounters[it] ?: 0) + c.magnitude }
            }
            ChangeKind.LOST -> c.player?.let { eliminated += it }
            ChangeKind.TURN -> turnChanged = true
            // A draw count is a number on a seat strip, not a thing on the
            // board -- and the recap cannot say WHICH card, so there is
            // nothing to fly. Named rather than dropped silently.
            ChangeKind.CARDS -> Unit
        }
    }

    // A permanent that arrived and left in the same transition never settled
    // anywhere -- playing an entrance for it would animate a tile that is not
    // on the board by the time the animation ends.
    val churned = arrivals intersect departures
    arrivals -= churned
    departures -= churned

    return BoardFx(
        arrivals = arrivals,
        departures = departures,
        damage = damage.filterValues { it != 0 },
        tileCounters = tileCounters.filterValues { it != 0 },
        seatCounters = seatCounters.filterValues { it != 0 },
        eliminated = eliminated,
        turnChanged = turnChanged,
    )
}
