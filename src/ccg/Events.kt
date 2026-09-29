package ccg

// ---------------------------------------------------------------------------
// The event stream. Triggered abilities listen for these and replacement
// handlers intercept them; events carry a `controller` / `who` where a trigger
// needs to scope by it. A trigger's scope is its condition inspecting the
// event; "for each ..." lives in the effect (ForEach / ForEachPlayer).
// ---------------------------------------------------------------------------

sealed interface GameEvent {

    /** A permanent entered a PLAY zone (`zone` is a concrete instance --
     *  a shared arena, or one player's lane / "Planet"). */
    data class EntersPlay(
        val permanent: ObjectId,
        val controller: PlayerId,
        val zone: ZoneRef = BATTLEFIELD,
    ) : GameEvent

    /** A permanent left a play zone. `fromZone` is the instance it was in;
     *  `destination` is where it went, and GRAVEYARD is a death. A zone-to-zone
     *  move is `MovesZone`, not this. */
    data class LeavesPlay(
        val permanent: ObjectId,
        val controller: PlayerId,
        val destination: HiddenZone,
        /** Its types when it left -- lets a "when a creature dies" trigger match
         *  after the permanent is already gone. */
        val types: Set<String>,
        val fromZone: ZoneRef = BATTLEFIELD,
    ) : GameEvent {
        val isDeath: Boolean get() = destination == HiddenZone.GRAVEYARD
    }

    /** A permanent moved between two play zones without leaving play. */
    data class MovesZone(
        val permanent: ObjectId,
        val controller: PlayerId,
        val from: ZoneRef,
        val to: ZoneRef,
    ) : GameEvent

    data class DamageDealt(
        val target: ObjectId,
        val amount: Int,
        /** WHO dealt it, when that is known -- needed for "whenever this deals
         *  damage". Null for damage with no permanent behind it. */
        val source: ObjectId? = null,
        /** Combat damage, as opposed to a spell's. `PlayerDamaged`
         *  carries the same flag on the
         *  permanent-damage side rather than a second event case. */
        val combat: Boolean = false,
        /** WHICH combat step dealt it -- "fast" / "slow" in EPR Skirmish's two
         *  waves. Null for non-combat damage. Added so a card can care which
         *  wave hit it ("prevent the next slow damage"); nothing could before,
         *  because the event simply did not carry the distinction. */
        val step: String? = null,
    ) : GameEvent

    /** A spell was put on the stack. `types` is the spell card's types. */
    data class SpellCast(val caster: PlayerId, val types: Set<String>) : GameEvent

    /** Counters of `kind` were added to (or removed from) a permanent. Carries
     *  both totals so a threshold trigger is stateless: chapter K fires iff
     *  `old < K <= new`. */
    data class CounterChanged(val permanent: ObjectId, val kind: String, val old: Int, val new: Int) : GameEvent

    /** SPEND `amount` of a counter. Unlike `CounterChanged` (a notification fired
     *  after the fact), this IS the change: `applyEvent` routes it through
     *  `mutateCounter`, which fires `CounterChanged`. How a replacement alters
     *  state and still lets triggers see it -- a shield really goes, and "when I
     *  lose a shield" really fires. */
    data class CounterSpent(val permanent: ObjectId, val kind: String, val amount: Int) : GameEvent

    /** The active player's turn began. */
    data class TurnBegan(val player: PlayerId, val turn: Int) : GameEvent

    /** A phase was entered. `activePlayer` is whose turn it is. */
    data class PhaseEnter(val phase: String, val activePlayer: PlayerId) : GameEvent

    /** A combat step began: declaring attackers ("declare-attackers"),
     *  declaring blockers ("declare-blockers"), or a damage step, by the name
     *  its `CombatDamage` gives it. `activePlayer` is whose combat it is. */
    data class CombatStep(val step: String, val activePlayer: PlayerId) : GameEvent

    /** A creature was declared as an attacker. `defender` is the player or
     *  permanent it is attacking. */
    data class Attacks(val attacker: ObjectId, val defender: CombatTarget, val controller: PlayerId) : GameEvent

    /** A creature was declared as a blocker. */
    data class Blocks(val blocker: ObjectId, val attacker: ObjectId) : GameEvent

    /** A player was dealt damage. */
    data class PlayerDamaged(
        val player: PlayerId,
        val amount: Int,
        val combat: Boolean = true,
        val source: ObjectId? = null,
    ) : GameEvent
}

/** The combat steps the declaring verbs raise (`GameEvent.CombatStep`). A
 *  damage step is named by its `CombatDamage`. */
const val DECLARE_ATTACKERS_STEP = "declare-attackers"
const val DECLARE_BLOCKERS_STEP = "declare-blockers"

/** The permanent this event is ABOUT -- what `TRIGGER` binds to when it fires
 *  a trigger. Null when it is about no permanent (a phase, a cast). For damage
 *  to a player it is the source, the only permanent involved. */
val GameEvent.subject: ObjectId?
    get() = when (this) {
        is GameEvent.EntersPlay -> permanent
        is GameEvent.LeavesPlay -> permanent
        is GameEvent.MovesZone -> permanent
        is GameEvent.DamageDealt -> target
        is GameEvent.CounterChanged -> permanent
        is GameEvent.CounterSpent -> permanent
        is GameEvent.Attacks -> attacker
        is GameEvent.Blocks -> blocker
        is GameEvent.PlayerDamaged -> source
        is GameEvent.SpellCast, is GameEvent.TurnBegan, is GameEvent.PhaseEnter, is GameEvent.CombatStep -> null
    }

/** Does this event carry an amount a replacement can change? */
val GameEvent.hasAmount: Boolean
    get() = this is GameEvent.DamageDealt || this is GameEvent.PlayerDamaged || this is GameEvent.CounterSpent

/** This event with `n` as its amount (`Effect.Proceed`); one with no amount
 *  is itself. */
fun GameEvent.withAmount(n: Int): GameEvent = when (this) {
    is GameEvent.DamageDealt -> copy(amount = n)
    is GameEvent.PlayerDamaged -> copy(amount = n)
    is GameEvent.CounterSpent -> copy(amount = n)
    // Listed, not `else`: a new event must say whether it has an amount.
    is GameEvent.CounterChanged, is GameEvent.EntersPlay, is GameEvent.LeavesPlay, is GameEvent.MovesZone,
    is GameEvent.SpellCast, is GameEvent.TurnBegan, is GameEvent.PhaseEnter, is GameEvent.Attacks, is GameEvent.Blocks,
    is GameEvent.CombatStep,
    -> this
}

/** "That much" -- what `IntExpr.EventAmount` binds to: the damage dealt, the
 *  counters added or removed, the counters spent. 0 for an event with no
 *  amount. */
val GameEvent.amount: Int
    get() = when (this) {
        is GameEvent.DamageDealt -> amount
        is GameEvent.PlayerDamaged -> amount
        is GameEvent.CounterChanged -> kotlin.math.abs(new - old)
        is GameEvent.CounterSpent -> amount
        is GameEvent.EntersPlay, is GameEvent.LeavesPlay, is GameEvent.MovesZone, is GameEvent.SpellCast,
        is GameEvent.TurnBegan, is GameEvent.PhaseEnter, is GameEvent.Attacks, is GameEvent.Blocks, is GameEvent.CombatStep -> 0
    }

