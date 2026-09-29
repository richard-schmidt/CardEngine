package ccg

// ---------------------------------------------------------------------------
// The value / condition expression language: two small sealed ASTs, `IntExpr`
// and `BoolExpr`, evaluated (Eval.kt) against an `EvalContext` (state,
// resolving controller, source). Every value slot of an `Effect` is an
// `IntExpr`; `If` takes a `BoolExpr`. New node, no evaluator rewrite.
// ---------------------------------------------------------------------------

/** A predicate over battlefield permanents, used by count / control reads. */
data class PermFilter(
    val types: Set<String> = emptySet(),
    /** null = either player's. */
    val controller: PlayerRef? = null,
    /** exclude the effect's own source permanent ("other creatures you control"). */
    val excludesSource: Boolean = false,
    /** Which play zone(s) count. `Any` while a bundle declares one zone;
     *  load-bearing once it declares arenas / lanes / "Planets". */
    val zone: ZoneScoping = ZoneScoping.Any,
    /** Pin to one chosen object -- the resolved target of a targeted modifier
     *  ("target creature gets +2/+0"). null = not pinned. A target:
     *  `only(CHOSEN)` before the choice is bound. */
    val pinned: BoundTarget? = null,
    /** "each permanent WITH a +1/+1 counter on it". `minCount` defaults
     *  to 1, so naming a kind alone means "has at least one". */
    val hasCounter: String? = null,
    val minCount: Int = 1,
    /** Matches ONLY the permanent the SOURCE is attached to (`hostId`). No
     *  source or no host matches nothing. ANDed with everything else. */
    val onlyHost: Boolean = false,
    /** Anything else about the candidate, as a condition: "with flying",
     *  "non-Ship", "power 3 or greater", "exhausted". Evaluated per candidate
     *  with `SUBJECT` meaning it. Null = no further test. */
    val where: BoolExpr? = null,
) {
    fun yours(): PermFilter = copy(controller = PlayerRef.You)
    fun theirs(): PermFilter = copy(controller = PlayerRef.Opponent)
    fun otherThanThis(): PermFilter = copy(excludesSource = true)
    fun only(id: ObjectId): PermFilter = copy(pinned = BoundTarget(id))
    fun only(name: String): PermFilter = copy(pinned = BoundTarget(name))
    fun withCounter(kind: String, n: Int = 1): PermFilter = copy(hasCounter = kind, minCount = n)
    /** Same zone instance as the effect's source ("units on this Planet"). */
    fun inThisZone(): PermFilter = copy(zone = ZoneScoping.SameAs())
    /** The same zone def as the source, EITHER side -- "each Ship in this
     *  lane", the whole line rather than your own half of it. */
    fun inThisZoneEitherSide(): PermFilter = copy(zone = ZoneScoping.SameAs(eitherSide = true))
    /** Any instance of a named def (either player's copy). */
    fun inZone(def: String): PermFilter = copy(zone = ZoneScoping.Named(def))
    /** One specific instance. */
    fun inZoneExact(ref: ZoneRef): PermFilter = copy(zone = ZoneScoping.Exact(ref))
    /** The permanent this static's own source (e.g. an Improvement) is
     *  attached to. */
    fun onlyHost(): PermFilter = copy(onlyHost = true)
}

// -- integer expressions ----------------------------------------------------

/** `DIV` is floor division, and 0 for a zero divisor -- evaluation is total.
 *  `MIN` / `MAX` pick the smaller or larger side. */
enum class BinOp { ADD, SUB, MUL, DIV, MIN, MAX }

sealed interface IntExpr {
    data class Lit(val value: Int) : IntExpr

    /** The chosen X of an X-spell. Substituted to a `Lit` at cast time.
     *  Unbound -- an X in a trigger, which nothing chooses -- it reads 0:
     *  evaluation is total, and `FaceDoc.scopeProblems` reports the cause. */
    data object X : IntExpr

    data class CountPerms(val filter: PermFilter) : IntExpr

    /** Total counters (of `kind`, or all kinds when null) across permanents
     *  matching `filter`. */
    data class CountCounters(val filter: PermFilter, val kind: String? = null) : IntExpr

    data class HandSize(val who: PlayerRef) : IntExpr

    /** Reads the game's damage counter (`GameState.damageCounter`) -- "life"
     *  only if the game declares no other name; 0 when players take no damage. */
    data class LifeOf(val who: PlayerRef) : IntExpr

    /** Any named player counter of `who` -- "your store", "the opponent's
     *  gold". `LifeOf` reads only the game's damage counter. */
    data class PlayerCounter(val who: PlayerRef, val name: String) : IntExpr

    /** How many cards are in one of `who`'s hidden zones -- "for each card in
     *  your graveyard". Exile is shared, so `who` is ignored for it. */
    data class ZoneSize(val who: PlayerRef, val zone: HiddenZone) : IntExpr

    data class Bin(val op: BinOp, val a: IntExpr, val b: IntExpr) : IntExpr

    // -- source-relative reads: resolve against ctx.source -----------

    /** A derived field of the effect's source permanent ("this creature's
     *  power"). 0 if there is no source, it has left play, or the field is
     *  undeclared. Do NOT use inside a ContinuousEffect.apply -- it would
     *  recurse through characteristicsOf. */
    data class SelfField(val key: String) : IntExpr

    /** Marked damage on the source permanent. */
    data object SelfDamage : IntExpr

    /** How many counters of `kind` are on the source permanent. Safe inside
     *  ContinuousEffect.apply -- reads the raw Permanent, not characteristicsOf. */
    data class SelfCounter(val kind: String) : IntExpr

    // -- target-relative reads ------------------------------------------------
    // `SelfField` / `SelfCounter` read the SOURCE; these read a BOUND TARGET
    // ("damage equal to the target's power"). The variable is rewritten by the
    // traversal, which reaches expression slots.

    /** A derived field of a bound target ("the target creature's power").
     *  0 if it has left play or the field is undeclared. */
    data class TargetField(val target: BoundTarget, val key: String) : IntExpr

    /** How many counters of `kind` are on a bound target. Reads the raw
     *  `Permanent`, so it is safe inside a `ContinuousEffect.apply`. */
    data class TargetCounter(val target: BoundTarget, val kind: String) : IntExpr

    /** Marked damage on a bound target. */
    data class TargetDamage(val target: BoundTarget) : IntExpr

    /** This iteration's share inside a `ChooseMany(divide = …)` body.
     *  Substituted to a `Lit` per chosen target, exactly as `X` is
     *  substituted once at cast time. Unbound, it reads 0, like `X`. */
    data object Share : IntExpr

    /** "That much": the triggering event's amount (`GameEvent.amount`) -- the
     *  damage dealt, the counters added. Substituted to a `Lit` when a trigger
     *  fires, exactly as `X` is at cast time; unbound, it reads 0. */
    data object EventAmount : IntExpr

    /** A number the GAME declares, by name ("cardsDrawnPerTurn", "maxHandSize",
     *  "startingHandSize", "firstPlayerSkipsFirstDraw" as 1/0). Filled from the
     *  game's params where the rules are known (the compiler for cards, the
     *  engine for phase effects); unfilled reads 0; an unset hand limit reads
     *  `NO_LIMIT`. An unknown name is reported. */
    data class Param(val name: String) : IntExpr

    /** The turn (or round) number, from 1. */
    data object TurnNumber : IntExpr

    /** `who`'s seat in the turn order, from 0 -- "the first player" is seat 0.
     *  -1 when `who` names nobody. */
    data class SeatOf(val who: PlayerRef) : IntExpr

    // -- geometry and choice: what combat reach is written in ----------

    /** The lane of the zone a bound target stands in (`PlayZoneDef.lane`), or
     *  -1 when that zone declares none or the target is not in play. */
    data class LaneOf(val target: BoundTarget) : IntExpr

    /** [then] when [cond] holds, else [otherwise]. */
    data class Cond(val cond: BoolExpr, val then: IntExpr, val otherwise: IntExpr) : IntExpr
}

// The substitution walks over these trees live in Walk.kt.

// -- boolean expressions --------------------------------------------------

enum class CmpOp { EQ, NE, LT, LTE, GT, GTE }

sealed interface BoolExpr {
    data class Const(val value: Boolean) : BoolExpr

    data class Cmp(val a: IntExpr, val op: CmpOp, val b: IntExpr) : BoolExpr

    data class And(val terms: List<BoolExpr>) : BoolExpr

    data class Or(val terms: List<BoolExpr>) : BoolExpr

    data class Not(val term: BoolExpr) : BoolExpr

    /** `target` has `keyword` (derived) -- with `SUBJECT`, "a creature with
     *  flying". */
    data class HasKeyword(val target: BoundTarget, val keyword: String) : BoolExpr

    /** `target` has all of `types` (derived) -- negated, "a non-Ship". */
    data class IsType(val target: BoundTarget, val types: Set<String>) : BoolExpr

    /** `target` is exhausted (tapped). */
    data class IsExhausted(val target: BoundTarget) : BoolExpr

    /** `target` is a token. */
    data class IsToken(val target: BoundTarget) : BoolExpr

    /** The source permanent has (all of) these types. */
    data class HasType(val types: Set<String>) : BoolExpr

    // -- geometry ---------------------------------------------------

    /** A bound target declares the field [key] -- even at 0 ("has a slow
     *  stat"), which reading the field cannot tell from not having it. */
    data class HasField(val target: BoundTarget, val key: String) : BoolExpr

    /** A bound target stands in a zone at [depth] (`PlayZoneDef.depth`). */
    data class AtDepth(val target: BoundTarget, val depth: Depth) : BoolExpr

    /** A bound target stands in [who]'s own zone -- or, with [who] null, in no
     *  player's zone: a shared zone, or not on the battlefield at all. */
    data class ZoneOwner(val target: BoundTarget, val who: PlayerRef?) : BoolExpr
}

// -- authoring DSL --------------------------------------------------------
// A card author builds
// expressions with these; they read close to the rules text.

fun lit(n: Int): IntExpr = IntExpr.Lit(n)
val x: IntExpr = IntExpr.X

fun countOf(filter: PermFilter): IntExpr = IntExpr.CountPerms(filter)
fun countCounters(filter: PermFilter, kind: String? = null): IntExpr = IntExpr.CountCounters(filter, kind)
fun handSize(who: PlayerRef): IntExpr = IntExpr.HandSize(who)
fun lifeOf(who: PlayerRef): IntExpr = IntExpr.LifeOf(who)
fun param(name: String): IntExpr = IntExpr.Param(name)

// source-relative reads
fun selfField(key: String): IntExpr = IntExpr.SelfField(key)
val selfDamage: IntExpr = IntExpr.SelfDamage
fun selfCounter(kind: String): IntExpr = IntExpr.SelfCounter(kind)
fun hasType(vararg t: String): BoolExpr = BoolExpr.HasType(t.toSet())

operator fun IntExpr.plus(o: IntExpr): IntExpr = IntExpr.Bin(BinOp.ADD, this, o)
operator fun IntExpr.minus(o: IntExpr): IntExpr = IntExpr.Bin(BinOp.SUB, this, o)
operator fun IntExpr.times(o: IntExpr): IntExpr = IntExpr.Bin(BinOp.MUL, this, o)
operator fun IntExpr.plus(n: Int): IntExpr = this + lit(n)
operator fun IntExpr.minus(n: Int): IntExpr = this - lit(n)

infix fun IntExpr.eq(o: IntExpr): BoolExpr = BoolExpr.Cmp(this, CmpOp.EQ, o)
infix fun IntExpr.ne(o: IntExpr): BoolExpr = BoolExpr.Cmp(this, CmpOp.NE, o)
infix fun IntExpr.lt(o: IntExpr): BoolExpr = BoolExpr.Cmp(this, CmpOp.LT, o)
infix fun IntExpr.lte(o: IntExpr): BoolExpr = BoolExpr.Cmp(this, CmpOp.LTE, o)
infix fun IntExpr.gt(o: IntExpr): BoolExpr = BoolExpr.Cmp(this, CmpOp.GT, o)
infix fun IntExpr.gte(o: IntExpr): BoolExpr = BoolExpr.Cmp(this, CmpOp.GTE, o)
infix fun IntExpr.gte(n: Int): BoolExpr = this gte lit(n)
infix fun IntExpr.lte(n: Int): BoolExpr = this lte lit(n)

fun allOf(vararg terms: BoolExpr): BoolExpr = BoolExpr.And(terms.toList())
fun anyOf(vararg terms: BoolExpr): BoolExpr = BoolExpr.Or(terms.toList())
fun not(term: BoolExpr): BoolExpr = BoolExpr.Not(term)
val alwaysTrue: BoolExpr = BoolExpr.Const(true)

/** "you control at least one <filter>". */
fun controls(filter: PermFilter): BoolExpr = countOf(filter.yours()) gte 1

// filter builders
fun creatures(): PermFilter = PermFilter(types = setOf("Creature"))
fun permanents(): PermFilter = PermFilter()
fun ofType(vararg t: String): PermFilter = PermFilter(types = t.toSet())
