package ccg

// ---------------------------------------------------------------------------
// Evaluation. Everything that READS a running game on behalf of the effect
// language lives here: expressions, filters, event patterns, characteristic
// transforms, replacement docs, durations. The AST files hold data only (a
// test.sh guard keeps `GameState` / `EvalContext` out of them); a new node gets
// its data class there and its meaning here.
// ---------------------------------------------------------------------------

/** The context an expression evaluates in. `source` is the permanent the effect
 *  originates from ("this"), null for a spell from hand.
 *
 *  `subject` is the permanent being DERIVED inside `characteristicsOf`, null
 *  elsewhere -- kept apart from `source` so a static's values read the card it
 *  is printed on, not the permanent it changes. `Self*` reads mean `source`;
 *  only `CharOp.Bands` (about the affected permanent's counters) reads
 *  `subject`. */
data class EvalContext(
    val state: GameState,
    val controller: PlayerId,
    val source: ObjectId? = null,
    /** Ids whose derived characteristics are being computed further up the call
     *  stack; re-entry for one of them returns the base. Threaded, not global. */
    val visiting: Set<ObjectId> = emptySet(),
    val subject: ObjectId? = null,
    /** The opponent an `EachOpponent` verb is running for: the
     *  interpreter runs such a verb once per opponent with this bound. */
    val each: PlayerId? = null,
    /** Variables an attack verb binds while it reads reach and damage:
     *  `ATTACKER`, `TARGET`. Empty anywhere else. */
    val bound: Map<String, ObjectId> = emptyMap(),
    /** The player being attacked, while an attack verb reads reach. */
    val defending: PlayerId? = null,
)

/** A target id as read here: `SUBJECT` is the permanent a `where` is testing,
 *  and an unbound `SELF` is the effect's source -- so `TargetField(SELF)` is
 *  exactly what `SelfField` always meant, wherever it is read (a type's
 *  `diesWhen` and a static's filter are evaluated without `bindSelf`). */
fun EvalContext.resolve(t: BoundTarget): ObjectId = when (val r = t.ref) {
    is Ref.Obj -> r.id
    is Ref.Var -> when (r.name) {
        SUBJECT -> subject
        SELF -> source
        else -> bound[r.name]
    } ?: NO_OBJECT
}

/** The player `ref` names here, or null when it names nobody: `Chosen`
 *  outside the `AsPlayer` that asks for it, or the controller / owner of
 *  something no longer in play. A null player does nothing, reads 0 and
 *  matches nothing -- never "you" by default. */
fun EvalContext.playerId(ref: PlayerRef): PlayerId? = when (ref) {
    PlayerRef.You -> controller
    PlayerRef.Opponent -> state.opponentOf(controller)
    PlayerRef.EachOpponent -> each ?: state.opponentsOf(controller).singleOrNull()
    PlayerRef.Active -> state.activePlayer
    PlayerRef.Chosen -> null
    PlayerRef.Defending -> defending
    is PlayerRef.Seat -> ref.id.takeIf { it in state.players }
    is PlayerRef.ControllerOf -> state.battlefield[resolve(ref.target)]?.controller
    is PlayerRef.OwnerOf -> state.battlefield[resolve(ref.target)]?.ownerId
}

/** Every player `ref` names here: each opponent for `EachOpponent` (unless
 *  one is bound), else the one `playerId` gives, if any. */
fun EvalContext.playerIds(ref: PlayerRef): List<PlayerId> =
    if (ref == PlayerRef.EachOpponent && each == null) state.opponentsOf(controller) else listOfNotNull(playerId(ref))

/** Does `ref`, in a MATCHING slot (a filter's controller, a "whose"), cover
 *  `player`? `Opponent` and `EachOpponent` there are any opponent. */
fun EvalContext.names(ref: PlayerRef, player: PlayerId): Boolean = when (ref) {
    PlayerRef.Opponent, PlayerRef.EachOpponent -> player != controller && player in state.turnOrder && player !in state.losers
    else -> playerId(ref) == player
}

// -- expressions -----------------------------------------------------------

fun IntExpr.eval(ctx: EvalContext): Int = when (this) {
    is IntExpr.Lit -> value
    IntExpr.X -> 0
    is IntExpr.CountPerms -> ctx.state.battlefield.keys.count { filter.matches(ctx, it) }
    is IntExpr.CountCounters -> ctx.state.battlefield.entries.filter { filter.matches(ctx, it.key) }.sumOf { (_, p) ->
        if (kind != null) (p.counters[kind] ?: 0) else p.counters.values.sum()
    }
    is IntExpr.HandSize -> ctx.playerIds(who).sumOf { ctx.state.players[it]?.hand?.size ?: 0 }
    is IntExpr.PlayerCounter -> ctx.playerIds(who).sumOf { ctx.state.players[it]?.counter(name) ?: 0 }
    is IntExpr.ZoneSize -> ctx.playerIds(who).sumOf { ctx.state.cardsIn(it, zone).size }
    is IntExpr.Bin -> {
        val x = a.eval(ctx); val y = b.eval(ctx)
        when (op) {
            BinOp.ADD -> x + y; BinOp.SUB -> x - y; BinOp.MUL -> x * y
            BinOp.DIV -> if (y == 0) 0 else Math.floorDiv(x, y)
            BinOp.MIN -> minOf(x, y); BinOp.MAX -> maxOf(x, y)
        }
    }
    // Lowered away by compile(); see Lower.kt.
    is IntExpr.SelfField, IntExpr.SelfDamage, is IntExpr.SelfCounter, is IntExpr.LifeOf -> throw SurfaceFormReached(this)
    is IntExpr.TargetField -> {
        val id = ctx.resolve(target)
        ctx.state.battlefield[id]?.let { ctx.state.characteristicsOf(id, ctx.visiting).fields[key] } ?: 0
    }
    is IntExpr.TargetCounter -> ctx.state.battlefield[ctx.resolve(target)]?.counters?.get(kind) ?: 0
    is IntExpr.TargetDamage -> ctx.state.battlefield[ctx.resolve(target)]?.damageMarked ?: 0
    IntExpr.Share -> 0
    IntExpr.EventAmount -> 0
    is IntExpr.Param -> 0
    IntExpr.TurnNumber -> ctx.state.turnNumber
    is IntExpr.SeatOf -> ctx.playerId(who)?.let { ctx.state.turnOrder.indexOf(it) } ?: -1
    is IntExpr.LaneOf -> ctx.zoneDefOf(target)?.lane ?: -1
    is IntExpr.Cond -> if (cond.eval(ctx)) then.eval(ctx) else otherwise.eval(ctx)
}

/** Where the zone a bound target stands in sits on the board, if it is in
 *  play and its zone declares a place. */
private fun EvalContext.zoneDefOf(t: BoundTarget): ZoneGeometry? =
    state.battlefield[resolve(t)]?.zone?.let { z -> state.zoneGeometry[z.def] }

fun BoolExpr.eval(ctx: EvalContext): Boolean = when (this) {
    is BoolExpr.Const -> value
    is BoolExpr.Cmp -> {
        val x = a.eval(ctx); val y = b.eval(ctx)
        when (op) {
            CmpOp.EQ -> x == y; CmpOp.NE -> x != y
            CmpOp.LT -> x < y; CmpOp.LTE -> x <= y
            CmpOp.GT -> x > y; CmpOp.GTE -> x >= y
        }
    }
    is BoolExpr.And -> terms.all { it.eval(ctx) }
    is BoolExpr.Or -> terms.any { it.eval(ctx) }
    is BoolExpr.Not -> !term.eval(ctx)
    is BoolExpr.HasKeyword -> {
        val id = ctx.resolve(target)
        id in ctx.state.battlefield && ctx.state.characteristicsOf(id, ctx.visiting).has(keyword)
    }
    is BoolExpr.IsType -> {
        val id = ctx.resolve(target)
        id in ctx.state.battlefield && ctx.state.characteristicsOf(id, ctx.visiting).types.containsAll(types)
    }
    is BoolExpr.IsExhausted -> ctx.state.battlefield[ctx.resolve(target)]?.exhausted ?: false
    is BoolExpr.IsToken -> ctx.state.battlefield[ctx.resolve(target)]?.isToken ?: false
    is BoolExpr.HasType -> throw SurfaceFormReached(this)
    is BoolExpr.HasField -> {
        val id = ctx.resolve(target)
        id in ctx.state.battlefield && ctx.state.characteristicsOf(id, ctx.visiting).hasField(key)
    }
    is BoolExpr.AtDepth -> ctx.zoneDefOf(target)?.depth == depth
    is BoolExpr.ZoneOwner -> {
        val owner = ctx.state.battlefield[ctx.resolve(target)]?.zone?.owner
        if (who == null) owner == null else owner != null && owner == ctx.playerId(who)
    }
}

// -- filters and patterns -------------------------------------------------

fun PermFilter.matches(ctx: EvalContext, id: ObjectId): Boolean {
    if (pinned != null && id != ctx.resolve(pinned)) return false
    if (excludesSource && id == ctx.source) return false
    if (onlyHost) {
        val hostId = ctx.source?.let { ctx.state.battlefield[it]?.hostId }
        if (hostId == null || id != hostId) return false
    }
    val perm = ctx.state.battlefield[id] ?: return false
    if (controller != null && !ctx.names(controller, perm.controller)) return false
    if (!zone.matches(ctx, perm.zone)) return false
    if (hasCounter != null && (perm.counters[hasCounter] ?: 0) < minCount) return false
    // Only a type test needs DERIVED characteristics; `permanents()` (no
    // types) is the common filter and must not pay for a layer fold.
    if (types.isNotEmpty()) {
        val derived = ctx.state.characteristicsOf(id, ctx.visiting).types
        if (!types.all { it in derived }) return false
    }
    return where?.eval(ctx.copy(subject = id)) ?: true
}

fun ZoneScoping.matches(ctx: EvalContext, permZone: ZoneRef): Boolean = when (this) {
    ZoneScoping.Any -> true
    is ZoneScoping.SameAs -> ctx.state.battlefield[ctx.resolve(of)]?.zone
        .let { z -> z != null && if (eitherSide) z.def == permZone.def else z == permZone }
    is ZoneScoping.Named -> permZone.def == def
    is ZoneScoping.Exact -> permZone == ref
}

fun EventPattern.matches(event: GameEvent, ctx: EvalContext): Boolean {
    val self = ctx.source
    fun isWhose(who: PlayerRef?, player: PlayerId) = who == null || ctx.names(who, player)
    fun anyOf(types: Set<String>, have: Set<String>) = types.isEmpty() || have.any { it in types }
    return when (this) {
        is EventPattern.OnPhase -> event is GameEvent.PhaseEnter && event.phase == phase && isWhose(whose, event.activePlayer)
        EventPattern.AnyTurnBegan -> event is GameEvent.TurnBegan
        is EventPattern.OnCombatStep -> event is GameEvent.CombatStep && (step == null || step == event.step) &&
            isWhose(whose, event.activePlayer)
        EventPattern.Never -> false
        EventPattern.SelfEnters -> event is GameEvent.EntersPlay && self != null && event.permanent == self
        EventPattern.SelfLeaves -> event is GameEvent.LeavesPlay && self != null && event.permanent == self
        EventPattern.SelfAttacks -> event is GameEvent.Attacks && self != null && event.attacker == self
        is EventPattern.Enters -> event is GameEvent.EntersPlay && isWhose(whose, event.controller) &&
            !(other && event.permanent == self) &&
            (types.isEmpty() || event.permanent in ctx.state.battlefield &&
                anyOf(types, ctx.state.characteristicsOf(event.permanent, ctx.visiting).types))
        is EventPattern.Cast -> event is GameEvent.SpellCast && isWhose(whose, event.caster) && anyOf(types, event.types)
        is EventPattern.CounterCrosses -> event is GameEvent.CounterChanged && self != null && event.permanent == self &&
            event.kind == kind && if (downward) event.new < k && k <= event.old else event.old < k && k <= event.new
        is EventPattern.SelfDealsDamage -> self != null && when (event) {
            is GameEvent.DamageDealt -> event.source == self && (!combatOnly || event.combat)
            is GameEvent.PlayerDamaged -> event.source == self && (!combatOnly || event.combat)
            else -> false
        }
        is EventPattern.Dies -> event is GameEvent.LeavesPlay && event.isDeath && anyOf(types, event.types) &&
            isWhose(whose, event.controller) && (filter == null || filter.matches(ctx, event.permanent))
        EventPattern.MovesIntoThisZone -> event is GameEvent.MovesZone && self != null && event.permanent != self &&
            event.to == ctx.state.battlefield[self]?.zone
        is EventPattern.Damaged -> event is GameEvent.DamageDealt && (!combatOnly || event.combat) &&
            (step == null || step == event.step) && filter.matches(ctx, event.target)
        is EventPattern.PlayerDamaged -> event is GameEvent.PlayerDamaged && (!combatOnly || event.combat) &&
            isWhose(whose, event.player)
    }
}

/** Does this rule allow `blocker` to block `attacker`? Read on derived
 *  characteristics, from the defending side. */
fun BlockRule.allows(state: GameState, attacker: ObjectId, blocker: ObjectId): Boolean {
    val defender = state.battlefield[blocker]?.controller ?: return false
    val ctx = EvalContext(state, defender)
    return !this.attacker.matches(ctx, attacker) || this.blocker.matches(ctx, blocker)
}

// -- characteristic transforms --------------------------------------------

fun CharOp.apply(ctx: EvalContext, c: Characteristics): Characteristics = when (this) {
    is CharOp.PlusPT, is CharOp.SetPT -> throw SurfaceFormReached(this)
    is CharOp.PlusField -> c.bump(field, amount.eval(ctx))
    is CharOp.SetField -> c.set(field, value.eval(ctx))
    is CharOp.GrantKeyword -> c.copy(keywords = c.keywords + keyword)
    is CharOp.AddType -> c.copy(types = c.types + type)
    is CharOp.SetTypes -> c.copy(types = types)
    CharOp.RemoveAbilities -> c.copy(abilitiesRemoved = true)
    is CharOp.GrantAbility -> c.copy(granted = c.granted + ability)
    is CharOp.Bands -> {
        // The AFFECTED permanent's counters -- `subject`, not `source`.
        val n = ctx.subject?.let { ctx.state.battlefield[it]?.counters?.get(counter) } ?: 0
        steps.filter { n >= it.at }.maxByOrNull { it.at }
            ?.let { c.set(fieldA, it.power.eval(ctx)).set(fieldB, it.toughness.eval(ctx)) } ?: c
    }
}

/** This op with every value evaluated NOW and fixed as a literal -- what a
 *  one-shot `ApplyModifier` stores (MTG 611.2c); a static keeps its values
 *  live. A granted ability is left alone (evaluated when activated). `Bands`
 *  locks its values but still picks its band from the affected permanent. */
fun CharOp.locked(ctx: EvalContext): CharOp {
    fun fix(e: IntExpr): IntExpr = lit(e.eval(ctx))
    return when (this) {
        is CharOp.PlusPT -> CharOp.PlusPT(fix(power), fix(toughness))
        is CharOp.SetPT -> CharOp.SetPT(fix(power), fix(toughness))
        is CharOp.PlusField -> copy(amount = fix(amount))
        is CharOp.SetField -> copy(value = fix(value))
        is CharOp.Bands -> copy(steps = steps.map { it.copy(power = fix(it.power), toughness = fix(it.toughness)) })
        is CharOp.GrantKeyword, is CharOp.AddType, is CharOp.SetTypes, CharOp.RemoveAbilities, is CharOp.GrantAbility -> this
    }
}

// -- replacements and durations -------------------------------------------

/** Has an effect created on `startTurn` with this `duration` expired, given the
 *  current turn and an evaluation context for `While`? */
fun Duration.isExpired(startTurn: Int, currentTurn: Int, ctx: EvalContext): Boolean = when (this) {
    Duration.Permanent -> false
    Duration.EndOfTurn -> currentTurn > startTurn
    Duration.EndOfNextTurn -> currentTurn > startTurn + 1
    is Duration.While -> !cond.eval(ctx)
}

/** What a held replacement may still take away, and how it is spent: POINTS
 *  of an event's amount ("prevent the next 3 damage"), or USES, one per
 *  event it replaces ("the next 2 hits"). */
data class Budget(val remaining: Int, val spends: Spend)

enum class Spend { POINTS, USES }

/** A replacement in play: its (lowered) doc plus whose it is. A card's comes
 *  from its statics and lasts while the card does; a HELD one (in
 *  `GameState.shields`, made by `PreventDamage`) has its own [id], may carry
 *  a [budget], and lasts for its [duration]. The engine runs it
 *  (`Engine.replaced`); it is data, evaluated against `source` and
 *  `controller` as they are NOW. */
data class ActiveReplacement(
    val source: ObjectId?,
    val controller: PlayerId,
    val doc: ReplacementDoc,
    val duration: Duration = Duration.Permanent,
    val startTurn: Int = 0,
    val id: ObjectId? = null,
    /** Null: unlimited. A budget at 0 replaces nothing. */
    val budget: Budget? = null,
)

/** Fold a Face's `Statics` bundle into a GameState for permanent `self` --
 *  used by enter-play and transform alike. `SELF` in a static's filter, ops
 *  or condition is bound to `self` here ("this creature gets ..."). */
fun wireStatics(s: GameState, st: Statics, self: ObjectId, controller: PlayerId): GameState {
    var out = s
    val ces = st.chars.flatMap { compileStatic(it.bindSelf(self), self, controller) }
    if (ces.isNotEmpty()) out = out.copy(continuousEffects = out.continuousEffects + ces)
    if (st.rules.isNotEmpty()) {
        out = out.copy(ruleMods = out.ruleMods + st.rules.map { ActiveRuleMod(self, controller, it) })
    }
    if (st.replacements.isNotEmpty()) {
        out = out.copy(replacements = out.replacements + st.replacements.map { ActiveReplacement(self, controller, it) })
    }
    if (st.costs.isNotEmpty()) {
        out = out.copy(costMods = out.costMods + st.costs.map { ActiveCostMod(self, controller, it) })
    }
    return out
}

/** Compile a permanent `StaticSpec` into continuous effects -- one per op, at
 *  the op's layer, sharing the spec's filter + optional live condition. */
fun compileStatic(spec: StaticSpec, self: ObjectId, controller: PlayerId): List<ContinuousEffect> =
    spec.ops.map { op ->
        ContinuousEffect(
            source = self,
            controller = controller,
            layer = spec.layer ?: op.defaultLayer,
            timestamp = self.toLong(),
            op = op,
            filter = spec.filter,
            condition = spec.condition,
        )
    }
