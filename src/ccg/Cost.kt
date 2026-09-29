package ccg

// ---------------------------------------------------------------------------
// Costs.
//
//   * `Player.pool` -- a typed resource map. "" is generic; named keys are
//     typed. Generic cost is payable from any resource, a typed cost needs its
//     key. `AddMana` fills it; it empties at phase boundaries (see GameParams).
//   * `Cost` -- a card's or ability's cost. `Cost()` is free.
//   * `ActivatedAbility { cost, effect, oncePerTurn }`. `{T}: add {G}` is one;
//     a Planeswalker `+1` / `-3` adds / removes `loyalty` counters.
//   * `CostMod` -- a static that shifts a filter of costs ("your spells cost {1}
//     less"), applied by `effectiveCost`.
// ---------------------------------------------------------------------------

/** A cost. Every field defaults to "nothing", so `Cost()` is free. */
data class Cost(
    /** resource key -> amount. "" = generic (any resource pays it). */
    val mana: Map<String, Int> = emptyMap(),
    /** an {X} in the cost -- the chosen X adds to the generic amount (and, for
     *  a spell, substitutes into the effect's own X in the same breath). */
    val usesX: Boolean = false,
    /** an effect run as part of paying -- "sacrifice a creature", "discard a
     *  card". Uses `BoundTarget(SELF)` for the paying permanent. */
    val additional: Effect? = null,
    val tapSource: Boolean = false,
    val sacrificeSource: Boolean = false,
    val payLife: Int = 0,
    /** remove N counters of a kind from the source (loyalty ability minus). */
    val removeCounters: Pair<String, Int>? = null,
    /** OTHER ways to pay this cost -- "pay {3} OR pay 3 life". The payable
     *  options (this cost, then each alternative) are offered as a mode choice
     *  when more than one is payable; only the chosen one is paid. ONE LEVEL
     *  ONLY: an alternative's own `alternatives` are ignored. */
    val alternatives: List<Cost> = emptyList(),
    /** Pay counters off a PERMANENT you control -- the permanent-sourced twin of
     *  `payLife`, for games whose loss condition lives on the board. A COST, so
     *  it is refused when short (paying to exactly 0 is legal, past it is not),
     *  where an `additional` effect would drive the payer below zero. With
     *  several possible payers the controller picks one. */
    val payFrom: CounterPayment? = null,
) {
    val isFree: Boolean
        get() = mana.isEmpty() && !usesX && additional == null && !tapSource &&
            !sacrificeSource && payLife == 0 && removeCounters == null && alternatives.isEmpty() &&
            payFrom == null

    /** This cost without its alternatives -- the shape actually paid. */
    fun basic(): Cost = if (alternatives.isEmpty()) this else copy(alternatives = emptyList())
}

/** "Remove `amount` counters of kind `counter` from a permanent matching
 *  `filter`." The filter is evaluated against the paying player, so
 *  `PermFilter(types = setOf("Station")).yours()` reads "a Station you
 *  control". */
data class CounterPayment(
    val counter: String,
    val amount: Int,
    val filter: PermFilter,
)

/** MTG's mana-ability rule: an ability that ONLY produces resources resolves at
 *  once, so a source can be tapped and spent inside one priority window.
 *  Detected structurally from the effect; nothing to author. */
fun isManaAbility(e: Effect): Boolean = when (e) {
    is Effect.AddMana -> true
    is Effect.Sequence -> e.steps.isNotEmpty() && e.steps.all { isManaAbility(it) }
    else -> false
}

/** An activated ability of a permanent: pay `cost`, get `effect`. */
data class ActivatedAbility(
    val cost: Cost,
    val effect: Effect,
    val name: String = "",
    /** a Planeswalker loyalty ability -- at most one per turn per permanent. */
    val oncePerTurn: Boolean = false,
    /** Does activating this open a response window? `true` puts the effect on
     *  the stack; `false` resolves it at once -- the same axis, and the same
     *  word, as `TypeDef.usesStack`. Not WHEN you may activate (that is
     *  `TypeDef.instantSpeed` / `PhaseSpec.sorcerySpeed`). Mana abilities
     *  resolve at once regardless (`isManaAbility`). */
    val usesStack: Boolean = true,
)

/** A cost-modifying static. `delta` shifts the matching cost; negative reduces (generic
 *  floors at 0). */
data class CostMod(
    /** whose actions this affects, relative to the mod's controller. null = anyone's. */
    val who: PlayerRef? = null,
    /** only actions whose object has one of these types. empty = any. */
    val types: Set<String> = emptySet(),
    val delta: Map<String, Int> = emptyMap(),
)

data class ActiveCostMod(
    val source: ObjectId,
    val controller: PlayerId,
    val mod: CostMod,
    val duration: Duration = Duration.Permanent,
    val startTurn: Int = 0,
)

/** Apply every matching `CostMod` in `state` to `base` for `player` casting /
 *  activating an object of `types`. Generic amount floors at 0; a shift can't
 *  create a resource requirement that wasn't there. */
fun effectiveCost(state: GameState, player: PlayerId, base: Cost, types: Set<String>): Cost {
    if (state.costMods.isEmpty()) return base
    var mana = base.mana
    for (acm in state.costMods) {
        val m = acm.mod
        val applies = (m.who == null || EvalContext(state, acm.controller, acm.source).names(m.who, player)) &&
            (m.types.isEmpty() || types.any { it in m.types })
        if (!applies) continue
        for ((k, d) in m.delta) {
            val cur = mana[k] ?: 0
            if (cur == 0 && d > 0) continue // don't invent a requirement
            mana = mana + (k to (cur + d).coerceAtLeast(0))
        }
    }
    return base.copy(mana = mana.filterValues { it != 0 })
}

/** Total mana in the cost, generic and typed alike -- what a `CardFilter`
 *  bound compares against. `{X}` counts as 0, as it does everywhere else off
 *  the stack. */
fun Cost.manaValue(): Int = mana.values.sum()
