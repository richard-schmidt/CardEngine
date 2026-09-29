package ccg

// ---------------------------------------------------------------------------
// Static-effect vocabulary.
//
//   * CharOp     -- an authorable characteristic transform (+P/+T, set P/T,
//                   grant a keyword or ability, add / replace types, remove
//                   abilities). Each op knows its own layer.
//   * StaticSpec -- a permanent static ability on a card: { filter, ops,
//                   condition?, layer? }; `condition` makes it conditional.
//   * RuleMod    -- a game-rule static that is not a characteristic ("your
//                   opponents can't gain life"), queried via GameState.forbids.
//   * ReplacementDoc -- a card-authored replacement effect carried in play.
//
// All live on a Face and are compiled into GameState at enter-play,
// re-compiled on transform, pruned on leave-play and by Duration.
// ---------------------------------------------------------------------------

/** An authorable characteristic transform. Layers follow MTG's order loosely:
 *  type changes (4) < keyword grants / ability removal (6) < set P/T (7) <
 *  +P/+T (8). `characteristicsOf` folds by (layer, timestamp). */
sealed interface CharOp {
    data class PlusPT(val power: IntExpr = lit(0), val toughness: IntExpr = lit(0)) : CharOp
    data class SetPT(val power: IntExpr, val toughness: IntExpr) : CharOp
    data class GrantKeyword(val keyword: String) : CharOp
    data class AddType(val type: String) : CharOp
    data class SetTypes(val types: Set<String>) : CharOp
    data object RemoveAbilities : CharOp

    /** Grant an ACTIVATED ability. Lands in
     *  `Characteristics.granted`, which `Engine.activateAbility` reads after
     *  the face's printed abilities -- so it is a derived characteristic like
     *  a granted keyword, folded by the same layer machinery. */
    data class GrantAbility(val ability: ActivatedAbility) : CharOp

    /** Set two fields from a counter count (level bands): the highest `at`
     *  threshold the affected permanent's `counter` reaches wins; below the
     *  lowest, no change. Reads the raw counter, so it is safe inside
     *  `characteristicsOf`. */
    data class Bands(
        val counter: String,
        val steps: List<Band>,
        /** Which two fields each step's `power`/`toughness` slots write
         *  (defaults: power / toughness). */
        val fieldA: String = "power",
        val fieldB: String = "toughness",
    ) : CharOp {
        data class Band(val at: Int, val power: IntExpr, val toughness: IntExpr)
    }

    /** Boost an ARBITRARY named field by `amount` (hull, fast, ...) -- the
     *  general form; `PlusPT` is the P/T shorthand and lowers to this. */
    data class PlusField(val field: String, val amount: IntExpr = lit(0)) : CharOp

    /** Set an ARBITRARY named field to `value` -- the general form of
     *  `SetPT`, as `PlusField` is of `PlusPT`. Same layer as `SetPT`, so
     *  lowering one to the other changes no ordering. */
    data class SetField(val field: String, val value: IntExpr) : CharOp

    val defaultLayer: Int
        get() = when (this) {
            is SetTypes, is AddType -> 4
            is GrantKeyword, is GrantAbility, RemoveAbilities -> 6
            is SetPT, is SetField, is Bands -> 7
            is PlusPT, is PlusField -> 8
        }
}

/** A permanent static ability. Compiled to one `ContinuousEffect` per op. */
data class StaticSpec(
    val filter: PermFilter,
    val ops: List<CharOp>,
    /** null = always on; else applied only while this holds (evaluated in the
     *  static's controller's context, re-checked every `characteristicsOf`). */
    val condition: BoolExpr? = null,
    /** override every op's default layer (rare). */
    val layer: Int? = null,
)

/** A game-rule static -- not a per-permanent characteristic.
 *
 *  `Cant` is PLAYER-scoped. The combat/ability cases are PERMANENT-scoped: a
 *  `PermFilter` evaluated in the MOD CONTROLLER's context (`theirs()` = my
 *  opponents') with the mod's source as `ctx.source` (`otherThanThis()` works). */
sealed interface RuleMod {
    /** `who` (relative to the mod's controller) can't take `action`. null
     *  `who` = nobody can. A `RuleAction`, not a string, so an action the
     *  engine cannot forbid cannot be written. JSON spells it by
     *  `RuleAction.key`. */
    data class Cant(val action: RuleAction, val who: PlayerRef? = null) : RuleMod

    /** Permanents matching `filter` can't attack; `defender` (relative to the
     *  mod's controller) narrows it to "can't attack THAT player". Attacking a
     *  permanent counts as attacking its controller. */
    data class CantAttack(val filter: PermFilter, val defender: PlayerRef? = null) : RuleMod

    /** Permanents matching `filter` can't be declared as blockers. */
    data class CantBlock(val filter: PermFilter) : RuleMod

    /** Permanents matching `filter` can't have their activated abilities used. */
    data class CantActivate(val filter: PermFilter) : RuleMod

    /** "Permanents matching `filter` take `amount` less damage." When hit points
     *  are a counter, a `PlusField("hull")` anthem is decorative (death reads
     *  the counter); "takes 1 less" is the anthem that works. A rule about
     *  damage, so a RuleMod rather than a CharOp. */
    data class ReduceDamage(
        val filter: PermFilter,
        /** Read in the mod's controller's context, with its card as the
         *  source -- "1 less for each Ship you control". Below 0 counts
         *  as 0: a reduction never adds damage. */
        val amount: IntExpr,
        /** Live precondition, as `StaticSpec.condition` ("while this is
         *  attached"), so an unattached Improvement does not buff for free. */
        val condition: BoolExpr? = null,
    ) : RuleMod
}

/** Everything static a Face contributes while its permanent is in play. */
data class Statics(
    val chars: List<StaticSpec> = emptyList(),
    val rules: List<RuleMod> = emptyList(),
    val replacements: List<ReplacementDoc> = emptyList(),
    val costs: List<CostMod> = emptyList(),
)

data class ActiveRuleMod(
    val source: ObjectId,
    val controller: PlayerId,
    val mod: RuleMod,
    val duration: Duration = Duration.Permanent,
    val startTurn: Int = 0,
)
