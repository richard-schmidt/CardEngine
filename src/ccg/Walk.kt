package ccg

// ---------------------------------------------------------------------------
// The one traversal of the effect language.
//
// Operations that must reach every value slot of an effect tree -- fix X at
// cast time, fill a divided Share per target, bind a target variable to an id,
// fill game params, ask whether X appears -- are each a `Subst` (a leaf rule
// and a scoping rule). The traversal is written ONCE with exhaustive `when`s
// and no `else`, so a new node does not compile until it says what it holds.
//
// SCOPING. A binder rebinds its variable for its BODY only; its filter and
// counts are evaluated outside it and always rewritten.
//
//     CHOSEN <- Choose          EACH <- ForEach, ChooseMany
//               (or whatever name the binder declares)
//     Share  <- ChooseMany with a `divide`
//     SELF   <- GrantAbility (a granted ability's SELF is its activator)
//     TRIGGER, EventAmount <- Delayed (its body reads the event that fires it)
//     SUBJECT <- a PermFilter's `where` (the candidate being tested)
//     X      <- nothing
//
// An inner binder shadows an outer one of the same name.
//
// NOT walked: `CreateToken.chars` and `CreateEmblem.statics` are templates for
// a new object; nothing from the creating effect binds into them.
//
// Substitution rather than an environment because a resolved `Delayed`,
// `ApplyModifier`, `PreventDamage` or granted ability is STORED and evaluated
// later elsewhere, so its values must be captured at creation.
// ---------------------------------------------------------------------------

/** The node kinds that rebind a variable for their body. */
internal enum class Binder { CHOOSE, EACH_LOOP, DIVIDE, GRANT, EVENT, WHERE, ATTACK }

/** One variable being substituted (or searched for) throughout a tree. */
internal abstract class Subst {
    /** Rewrite an `X`, `Share` or `EventAmount` leaf -- the only expression
     *  leaves that are variables. Everything else is structure. */
    open fun leaf(e: IntExpr): IntExpr = e

    /** Rewrite a target -- a `BoundTarget`, or a filter's pin. */
    open fun target(ref: Ref): Ref = ref

    /** A read of the effect's SOURCE that is not a target id: `SelfField`,
     *  `SelfCounter`, `SelfDamage`, `HasType`, `Attach`, an `onlyHost` filter.
     *  Only the scope checker cares. */
    open fun readsSelf() {}

    /** Does `binder`, binding the target variable `name` (null: none), rebind
     *  this variable, so its body must be left alone? */
    open fun shadowedBy(binder: Binder, name: String?): Boolean = false

    /** A NAME the tree refers to -- a type, a field, a counter kind ... --
     *  which the compiler resolves against the game's declarations. */
    open fun name(kind: NameKind, value: String) {}

    /** The tree damages a player, or reads or spends the counter player
     *  damage lands on (`DamageOpponent`, `LifeOf`, `Cost.payLife`, a
     *  `PlayerDamaged` pattern) -- which name that is, is the GAME's
     *  `damageCounter`, so the compiler supplies it. */
    open fun usesDamageCounter() {}

    /** A `PlayerRef.Chosen` anywhere but the `AsPlayer` that asks for it --
     *  it names nobody there. Only the scope checker cares. */
    open fun chosenPlayer() {}

    /** A verb's OWN player slot -- the player it acts on. The slot
     *  `EachOpponent` fans out over, and where a bare `Opponent` is ambiguous
     *  with 3+ players. Matching slots (a filter's controller, a "whose") are
     *  not reported: there `Opponent` is any opponent. */
    open fun actsOn(r: PlayerRef) {}

    /** An expression's player slot -- the player whose total it reads. */
    open fun reads(r: PlayerRef) {}

    /** False: don't walk into child effects -- only this node's own slots. */
    open val deep: Boolean get() = true

    /** Walk the templates for NEW objects too (`CreateToken.chars`,
     *  `CreateEmblem.statics`). Nothing binds into them, so substitution
     *  leaves them alone; the name check must still see what they name. */
    open val intoTemplates: Boolean get() = false

    /** Replace a node, after its children have been walked. Identity for
     *  every substitution; the LOWERING is the one that uses these. */
    open fun rewrite(e: IntExpr): IntExpr = e
    open fun rewrite(e: BoolExpr): BoolExpr = e
    open fun rewrite(e: Effect): Effect = e
    /** A list of ops, because one surface op may lower to several. */
    open fun rewriteOps(ops: List<CharOp>): List<CharOp> = ops
    open fun rewrite(r: ReplacementDoc): ReplacementDoc = r
}

/** The kinds of name an authored tree can refer to. */
enum class NameKind(val noun: String) {
    TYPE("type"),
    FIELD("field"),
    COUNTER("counter kind"),
    PLAYER_COUNTER("player counter"),
    PHASE("phase"),
    ZONE("zone"),
    STEP("combat step"),
    STANCE("stance"),
    PARAM("game parameter"),
}

private class FillX(private val v: Int) : Subst() {
    override fun leaf(e: IntExpr) = if (e == IntExpr.X) IntExpr.Lit(v) else e
}

/** Fill every `IntExpr.Param` from the game's params. */
private class FillParams(private val params: GameParams) : Subst() {
    override fun leaf(e: IntExpr) = if (e is IntExpr.Param) IntExpr.Lit(params.param(e.name) ?: 0) else e
}

/** This tree with the game's params filled in -- see `IntExpr.Param`. */
fun Effect.withParams(params: GameParams): Effect = subst(FillParams(params))

/** Does any node of this tree satisfy `pred`? */
fun Effect.anyNode(pred: (Effect) -> Boolean): Boolean {
    var found = false
    subst(object : Subst() {
        override fun rewrite(e: Effect): Effect { if (pred(e)) found = true; return e }
    })
    return found
}

private class FindX : Subst() {
    var found = false
    override fun leaf(e: IntExpr): IntExpr { if (e == IntExpr.X) found = true; return e }
}

private class FillShare(private val n: Int) : Subst() {
    override fun leaf(e: IntExpr) = if (e == IntExpr.Share) IntExpr.Lit(n) else e
    override fun shadowedBy(binder: Binder, name: String?) = binder == Binder.DIVIDE
}

/** Bind the variable `name` to the object `real`. Any binder that binds the
 *  same name shadows it -- the ordinary lexical rule, now that names differ. */
private open class BindTarget(private val name: String, private val real: ObjectId) : Subst() {
    override fun target(ref: Ref) = if (ref == Ref.Var(name)) Ref.Obj(real) else ref
    override fun shadowedBy(binder: Binder, name: String?) = name == this.name
}

/** Bind the triggering event: `TRIGGER` to the permanent it is about (left
 *  unbound, so affecting nothing, when there is none) and `EventAmount` to its
 *  amount. */
private class BindEvent(private val event: GameEvent) : Subst() {
    override fun leaf(e: IntExpr) = if (e == IntExpr.EventAmount) IntExpr.Lit(event.amount) else e
    override fun target(ref: Ref) = if (ref == Ref.Var(TRIGGER)) event.subject?.let { Ref.Obj(it) } ?: ref else ref
    override fun shadowedBy(binder: Binder, name: String?) = binder == Binder.EVENT || name == TRIGGER
}

// -- the traversal ----------------------------------------------------------

internal fun Effect.subst(s: Subst): Effect {
    fun i(e: IntExpr) = e.subst(s)
    fun t(b: BoundTarget) = b.subst(s)
    fun f(p: PermFilter) = p.subst(s)
    fun d(x: Duration) = x.subst(s)
    fun e(x: Effect) = if (s.deep) x.subst(s) else x
    fun p(r: PlayerRef) = r.subst(s).also { s.actsOn(r) }
    fun scoped(body: Effect, vararg binders: Pair<Binder, String?>) =
        if (!s.deep || binders.any { (b, n) -> s.shadowedBy(b, n) }) body else body.subst(s)
    return s.rewrite(when (this) {
        is Effect.DealDamage -> copy(amount = i(amount), target = target?.let(::t), player = player?.let(::p))
            .also { if (player != null) s.usesDamageCounter() }
        is Effect.DamageOpponent -> copy(amount = i(amount)).also { s.usesDamageCounter(); s.actsOn(PlayerRef.Opponent) }
        is Effect.Draw -> copy(who = p(who), count = i(count))
        is Effect.GainLife -> copy(who = p(who), amount = i(amount)).also { s.name(NameKind.PLAYER_COUNTER, counter) }
        is Effect.AddMana -> copy(who = p(who), mana = mana.mapValues { i(it.value) })
        is Effect.Destroy -> copy(target = t(target))
        is Effect.AddCounter -> copy(count = i(count), target = t(target)).also { s.name(NameKind.COUNTER, kind) }
        is Effect.RemoveCounter -> copy(count = i(count), target = t(target)).also { s.name(NameKind.COUNTER, kind) }
        is Effect.Transform -> copy(target = t(target))
        is Effect.CreateToken -> copy(count = i(count)).also {
            zone?.let { z -> s.name(NameKind.ZONE, z) }
            if (s.intoTemplates) chars.names(s)
        }
        is Effect.CopyOf -> copy(target = t(target))
        is Effect.CreateEmblem -> this.also { if (s.intoTemplates) statics.names(s) }
        is Effect.ChooseMode -> copy(options = options.map(::e), pick = i(pick))
        is Effect.Discard -> copy(who = p(who), count = i(count))
        is Effect.DrawThenDiscard -> copy(who = p(who), draw = i(draw), discard = i(discard))
        is Effect.ReturnFromDiscard -> copy(who = p(who), count = i(count))
        is Effect.SearchZone -> copy(who = p(who), count = i(count))
        is Effect.Shuffle -> copy(who = p(who))
        is Effect.MoveTop -> copy(who = p(who), count = i(count))
        is Effect.LookAtTop -> copy(who = p(who), count = i(count))
        is Effect.MovePermanent -> copy(target = t(target)).also { s.name(NameKind.ZONE, toZone.def) }
        is Effect.ApplyModifier -> copy(filter = f(filter), ops = s.rewriteOps(ops.map { it.subst(s) }), duration = d(duration))
        is Effect.PreventDamage -> copy(target = t(target), amount = i(amount), duration = d(duration), who = who?.let(::p))
            .also { onlyStep?.let { st -> s.name(NameKind.STEP, st) } }
        is Effect.SetCombatMode -> copy(target = t(target)).also { mode?.let { m -> s.name(NameKind.STANCE, m) } }
        is Effect.Proceed -> copy(amount = amount?.let { i(it) })
        is Effect.Attach -> copy(host = t(host)).also { s.readsSelf() }
        is Effect.ForEach -> copy(filter = f(filter), body = scoped(body, Binder.EACH_LOOP to binds))
        is Effect.ForEachPlayer -> copy(body = e(body))
        is Effect.Sacrifice -> copy(who = p(who), count = i(count), filter = f(filter))
        is Effect.Delayed -> copy(effect = scoped(effect, Binder.EVENT to TRIGGER), duration = d(duration))
            .also { on.names(s); expiresAfter?.names(s) }
        is Effect.Sequence -> copy(steps = steps.map(::e))
        is Effect.Choose -> copy(filter = f(filter), body = scoped(body, Binder.CHOOSE to binds))
        is Effect.ChooseMany -> copy(
            filter = f(filter), count = i(count), divide = divide?.let(::i),
            body = if (divide != null) scoped(body, Binder.EACH_LOOP to binds, Binder.DIVIDE to null) else scoped(body, Binder.EACH_LOOP to binds),
        )
        is Effect.If -> copy(cond = cond.subst(s), then = e(then), otherwise = e(otherwise))
        is Effect.CounterSpell -> copy(whose = whose?.subst(s), target = target?.let(::t)).also { types.forEach { s.name(NameKind.TYPE, it) } }
        is Effect.Tap -> copy(target = t(target))
        is Effect.ClearDamage -> copy(target = t(target))
        is Effect.SendTo -> copy(target = t(target))
        is Effect.GainControl -> copy(target = t(target), duration = d(duration))
        // Its own `Chosen` is the question, not a use of an answer.
        is Effect.AsPlayer -> copy(who = if (who == PlayerRef.Chosen) who else p(who), body = e(body))
        is Effect.DeclareAttackers -> copy(eligible = f(eligible), staysReady = staysReady?.let(::f), then = e(then))
        is Effect.DeclareBlockers -> copy(
            eligible = f(eligible), rules = rules.map { BlockRule(f(it.attacker), f(it.blocker)) }, then = e(then),
        )
        Effect.CombatWindow -> this
        // `amount` and `minBlockers` are read per fighter, with SUBJECT bound
        // to it -- the scope a filter's `where` gives SUBJECT.
        is Effect.Attack -> copy(
            attacker = f(attacker), targets = f(targets),
            attacksPerTurn = if (s.shadowedBy(Binder.WHERE, SUBJECT)) attacksPerTurn else i(attacksPerTurn),
            mustTarget = mustTarget?.let(::f), redirect = redirect?.let(::f), staysReady = staysReady?.let(::f), then = e(then),
            reaches = inAttack(s, reaches), reachesFace = inAttack(s, reachesFace),
        )
        is Effect.FreeAttacks -> copy(
            actors = f(actors), body = body.subst(s), guns = guns.map { it.subst(s) },
            guards = guards?.let(::f), lethal = lethal?.let(::f),
        ).also { s.name(NameKind.STEP, step) }
        is Effect.Strike -> {
            fun perFighter(x: IntExpr) = if (s.shadowedBy(Binder.WHERE, SUBJECT)) x else i(x)
            copy(amount = perFighter(amount), faceAmount = faceAmount?.let(::perFighter), lethal = lethal?.let(::f))
        }
        is Effect.Clash -> {
            fun perFighter(x: IntExpr) = if (s.shadowedBy(Binder.WHERE, SUBJECT)) x else i(x)
            copy(
                attackStat = perFighter(attackStat), defendStat = perFighter(defendStat), faceAmount = faceAmount?.let(::perFighter),
                pierces = pierces?.let(::f),
            ).also { stances.forEach { st -> s.name(NameKind.FIELD, st.defendsWith) } }
        }
        is Effect.CombatDamage -> {
            fun perFighter(x: IntExpr) = if (s.shadowedBy(Binder.WHERE, SUBJECT)) x else i(x)
            copy(
                amount = perFighter(amount), acts = acts?.let(::f), lethal = lethal?.let(::f), tramples = tramples?.let(::f),
                minBlockers = perFighter(minBlockers),
            ).also { s.name(NameKind.STEP, step) }
        }
        Effect.NoOp -> this
    })
}

/** A slot read with `ATTACKER` and `TARGET` bound: a walk looking for
 *  either name stops at it, the way a binder's body stops a walk for its name. */
private fun inAttack(s: Subst, x: BoolExpr): BoolExpr =
    if (s.shadowedBy(Binder.ATTACK, ATTACKER) || s.shadowedBy(Binder.ATTACK, TARGET)) x else x.subst(s)

private fun inAttack(s: Subst, x: IntExpr): IntExpr =
    if (s.shadowedBy(Binder.ATTACK, ATTACKER) || s.shadowedBy(Binder.ATTACK, TARGET)) x else x.subst(s)

internal fun Gun.subst(s: Subst): Gun = copy(
    carried = inAttack(s, carried), reaches = inAttack(s, reaches), reachesFace = inAttack(s, reachesFace),
    amount = inAttack(s, amount), faceAmount = inAttack(s, faceAmount),
)

internal fun IntExpr.subst(s: Subst): IntExpr = s.rewrite(when (this) {
    IntExpr.X, IntExpr.Share, IntExpr.EventAmount -> s.leaf(this)
    is IntExpr.Param -> s.leaf(this).also { s.name(NameKind.PARAM, name) }
    is IntExpr.Lit, IntExpr.TurnNumber -> this
    is IntExpr.SeatOf -> copy(who = who.subst(s)).also { s.reads(who) }
    is IntExpr.HandSize -> copy(who = who.subst(s)).also { s.reads(who) }
    is IntExpr.ZoneSize -> copy(who = who.subst(s)).also { s.reads(who) }
    is IntExpr.LifeOf -> copy(who = who.subst(s)).also { s.usesDamageCounter(); s.reads(who) }
    is IntExpr.PlayerCounter -> copy(who = who.subst(s)).also { s.name(NameKind.PLAYER_COUNTER, name); s.reads(who) }
    is IntExpr.SelfField -> this.also { s.readsSelf(); s.name(NameKind.FIELD, key) }
    is IntExpr.SelfCounter -> this.also { s.readsSelf(); s.name(NameKind.COUNTER, kind) }
    IntExpr.SelfDamage -> this.also { s.readsSelf() }
    is IntExpr.CountPerms -> copy(filter = filter.subst(s))
    is IntExpr.CountCounters -> copy(filter = filter.subst(s)).also { kind?.let { k -> s.name(NameKind.COUNTER, k) } }
    is IntExpr.Bin -> copy(a = a.subst(s), b = b.subst(s))
    is IntExpr.TargetField -> copy(target = target.subst(s)).also { s.name(NameKind.FIELD, key) }
    is IntExpr.TargetCounter -> copy(target = target.subst(s)).also { s.name(NameKind.COUNTER, kind) }
    is IntExpr.TargetDamage -> copy(target = target.subst(s))
    is IntExpr.LaneOf -> copy(target = target.subst(s))
    is IntExpr.Cond -> copy(cond = cond.subst(s), then = then.subst(s), otherwise = otherwise.subst(s))
})

internal fun BoolExpr.subst(s: Subst): BoolExpr = s.rewrite(when (this) {
    is BoolExpr.Const -> this
    is BoolExpr.HasType -> this.also { s.readsSelf(); types.forEach { s.name(NameKind.TYPE, it) } }
    is BoolExpr.Cmp -> copy(a = a.subst(s), b = b.subst(s))
    is BoolExpr.And -> copy(terms = terms.map { it.subst(s) })
    is BoolExpr.Or -> copy(terms = terms.map { it.subst(s) })
    is BoolExpr.Not -> copy(term = term.subst(s))
    is BoolExpr.HasKeyword -> copy(target = target.subst(s))
    is BoolExpr.IsType -> copy(target = target.subst(s)).also { types.forEach { s.name(NameKind.TYPE, it) } }
    is BoolExpr.IsExhausted -> copy(target = target.subst(s))
    is BoolExpr.IsToken -> copy(target = target.subst(s))
    is BoolExpr.HasField -> copy(target = target.subst(s)).also { s.name(NameKind.FIELD, key) }
    is BoolExpr.AtDepth -> copy(target = target.subst(s))
    is BoolExpr.ZoneOwner -> copy(target = target.subst(s), who = who?.subst(s)).also { who?.let { s.reads(it) } }
})

internal fun PlayerRef.subst(s: Subst): PlayerRef = when (this) {
    PlayerRef.You, PlayerRef.Opponent, PlayerRef.EachOpponent, PlayerRef.Active, PlayerRef.Defending, is PlayerRef.Seat -> this
    PlayerRef.Chosen -> this.also { s.chosenPlayer() }
    is PlayerRef.ControllerOf -> copy(target = target.subst(s))
    is PlayerRef.OwnerOf -> copy(target = target.subst(s))
}

internal fun CharOp.subst(s: Subst): CharOp = when (this) {
    is CharOp.PlusPT -> copy(power = power.subst(s), toughness = toughness.subst(s))
    is CharOp.SetPT -> copy(power = power.subst(s), toughness = toughness.subst(s))
    is CharOp.PlusField -> copy(amount = amount.subst(s)).also { s.name(NameKind.FIELD, field) }
    is CharOp.SetField -> copy(value = value.subst(s)).also { s.name(NameKind.FIELD, field) }
    is CharOp.Bands -> copy(steps = steps.map { it.copy(power = it.power.subst(s), toughness = it.toughness.subst(s)) })
        .also { s.name(NameKind.COUNTER, counter); s.name(NameKind.FIELD, fieldA); s.name(NameKind.FIELD, fieldB) }
    is CharOp.GrantAbility -> {
        ability.cost.names(s)
        if (!s.deep || s.shadowedBy(Binder.GRANT, SELF)) this else copy(ability = ability.copy(effect = ability.effect.subst(s)))
    }
    is CharOp.AddType -> this.also { s.name(NameKind.TYPE, type) }
    is CharOp.SetTypes -> this.also { types.forEach { s.name(NameKind.TYPE, it) } }
    is CharOp.GrantKeyword, CharOp.RemoveAbilities -> this
}

internal fun PermFilter.subst(s: Subst): PermFilter {
    if (onlyHost) s.readsSelf()
    types.forEach { s.name(NameKind.TYPE, it) }
    hasCounter?.let { s.name(NameKind.COUNTER, it) }
    when (val z = zone) {
        is ZoneScoping.Named -> s.name(NameKind.ZONE, z.def)
        is ZoneScoping.Exact -> s.name(NameKind.ZONE, z.ref.def)
        else -> {}
    }
    val w = if (where == null || s.shadowedBy(Binder.WHERE, SUBJECT)) where else where.subst(s)
    val z = (zone as? ZoneScoping.SameAs)?.let { it.copy(of = it.of.subst(s)) } ?: zone
    return copy(controller = controller?.subst(s), pinned = pinned?.subst(s), where = w, zone = z)
}

internal fun BoundTarget.subst(s: Subst): BoundTarget = BoundTarget(s.target(ref))

internal fun Duration.subst(s: Subst): Duration = when (this) {
    is Duration.While -> copy(cond = cond.subst(s))
    Duration.Permanent, Duration.EndOfTurn, Duration.EndOfNextTurn -> this
}

// -- names only ---------------------------------------------------------
// Parts of an authored card that hold names but no variables: nothing is
// substituted into them, so they are walked only for what they name.

internal fun EventPattern.names(s: Subst) {
    when (this) {
        is EventPattern.OnPhase -> { s.name(NameKind.PHASE, phase); whose?.subst(s) }
        is EventPattern.OnCombatStep -> { step?.let { s.name(NameKind.STEP, it) }; whose?.subst(s) }
        is EventPattern.Enters -> { types.forEach { s.name(NameKind.TYPE, it) }; whose?.subst(s) }
        is EventPattern.Cast -> { types.forEach { s.name(NameKind.TYPE, it) }; whose?.subst(s) }
        is EventPattern.CounterCrosses -> s.name(NameKind.COUNTER, kind)
        is EventPattern.Dies -> { types.forEach { s.name(NameKind.TYPE, it) }; whose?.subst(s); filter?.subst(s) }
        is EventPattern.Damaged -> { filter.subst(s); step?.let { s.name(NameKind.STEP, it) } }
        is EventPattern.AnyTurnBegan, EventPattern.Never, EventPattern.SelfEnters, EventPattern.SelfLeaves,
        EventPattern.SelfAttacks, is EventPattern.SelfDealsDamage, EventPattern.MovesIntoThisZone,
        -> {}
        is EventPattern.PlayerDamaged -> { s.usesDamageCounter(); whose?.subst(s) }
    }
}

internal fun Cost.names(s: Subst) {
    if (payLife > 0) s.usesDamageCounter()
    removeCounters?.let { s.name(NameKind.COUNTER, it.first) }
    payFrom?.let { s.name(NameKind.COUNTER, it.counter); it.filter.subst(s) }
    additional?.subst(s)
    alternatives.forEach { it.names(s) }
}

internal fun Characteristics.names(s: Subst) {
    types.forEach { s.name(NameKind.TYPE, it) }
    fields.keys.forEach { s.name(NameKind.FIELD, it) }
}

internal fun Statics.names(s: Subst) {
    for (st in chars) { st.filter.subst(s); st.ops.forEach { it.subst(s) }; st.condition?.subst(s) }
    for (m in rules) m.names(s)
    for (r in replacements) r.names(s)
    for (c in costs) { c.types.forEach { s.name(NameKind.TYPE, it) }; c.who?.subst(s) }
}

internal fun RuleMod.names(s: Subst) {
    when (this) {
        is RuleMod.Cant -> who?.subst(s)
        is RuleMod.CantAttack -> { filter.subst(s); defender?.subst(s) }
        is RuleMod.CantBlock -> filter.subst(s)
        is RuleMod.CantActivate -> filter.subst(s)
        is RuleMod.ReduceDamage -> { filter.subst(s); amount.subst(s); condition?.subst(s) }
    }
}

internal fun ReplacementDoc.names(s: Subst) {
    when (this) {
        is ReplacementDoc.DamageToSacrificeSelf -> { filter.subst(s); onlyStep?.let { s.name(NameKind.STEP, it) } }
        is ReplacementDoc.PreventDamageTo -> { filter.subst(s); onlyStep?.let { s.name(NameKind.STEP, it) } }
        is ReplacementDoc.DamageToRemoveCounter -> {
            filter.subst(s); s.name(NameKind.COUNTER, counter); onlyStep?.let { s.name(NameKind.STEP, it) }
        }
        is ReplacementDoc.DeathToExile -> filter.subst(s)
        is ReplacementDoc.Replace -> { pattern.names(s); instead.subst(s) }
    }
}

// -- the four operations ----------------------------------------------------

/** Does any value slot in this tree reference the chosen X? */
fun Effect.usesX(): Boolean = FindX().also { subst(it) }.found
fun IntExpr.usesX(): Boolean = FindX().also { subst(it) }.found
internal fun BoolExpr.usesX(): Boolean = FindX().also { subst(it) }.found

/** Fix the chosen X throughout the tree -- done once, at cast time. */
fun Effect.substituteX(v: Int): Effect = subst(FillX(v))
fun IntExpr.substituteX(v: Int): IntExpr = subst(FillX(v))
fun BoolExpr.substituteX(v: Int): BoolExpr = subst(FillX(v))

/** Fill one target's share of a `ChooseMany(divide = …)` total. */
fun Effect.substituteShare(n: Int): Effect = subst(FillShare(n))
fun IntExpr.substituteShare(n: Int): IntExpr = subst(FillShare(n))
fun BoolExpr.substituteShare(n: Int): BoolExpr = subst(FillShare(n))

/** Bind every `BoundTarget(name)` (and filter pinned to it) in scope to `real`. */
fun Effect.substituteTarget(name: String, real: ObjectId): Effect = subst(BindTarget(name, real))
fun IntExpr.substituteTarget(name: String, real: ObjectId): IntExpr = subst(BindTarget(name, real))
fun BoolExpr.substituteTarget(name: String, real: ObjectId): BoolExpr = subst(BindTarget(name, real))

/** Rename the variable `from` to `to` wherever it is in scope. */
private class RenameVar(private val from: String, private val to: String) : Subst() {
    override fun target(ref: Ref) = if (ref == Ref.Var(from)) Ref.Var(to) else ref
    override fun shadowedBy(binder: Binder, name: String?) = name == from
}
fun Effect.renameVar(from: String, to: String): Effect = subst(RenameVar(from, to))

/** `substituteTarget(SELF, self)` -- a trigger's or ability's own permanent. */
fun Effect.bindSelf(self: ObjectId): Effect = substituteTarget(SELF, self)

/** Does this tree refer to the variable `name` anywhere it is in scope (not
 *  under a binder that rebinds it)? */
fun Effect.refersTo(name: String): Boolean {
    var found = false
    subst(object : BindTarget(name, NO_OBJECT) {
        override fun target(ref: Ref): Ref { if (ref == Ref.Var(name)) found = true; return ref }
    })
    return found
}

/** `SELF` in a static ability -- "this creature gets +1/+1" -- bound to the
 *  permanent it is printed on. */
fun StaticSpec.bindSelf(self: ObjectId): StaticSpec {
    val b = BindTarget(SELF, self)
    return copy(filter = filter.subst(b), ops = ops.map { it.subst(b) }, condition = condition?.subst(b))
}

/** Bind `TRIGGER` and `EventAmount` to the event that fired this effect. */
fun Effect.bindEvent(event: GameEvent): Effect = subst(BindEvent(event))

// -- the scope checker -------------------------------------------------------
//
// Evaluation is total (an unbound X or Share reads 0); this reports the cause
// where the author sees it, in `GameDoc.problems()`. Built on the
// same traversal, so it agrees with substitution about scope by construction:
// a variable is free exactly when no binder would have filled it.

/** A variable an authored effect can read, and what binds it. */
enum class FreeVar(val phrase: String) {
    /** Bound by a cast or an activation -- never by a trigger or a static. */
    X("uses X, which nothing chooses here -- it reads 0"),
    /** Bound by a `ChooseMany` with a `divide`. */
    SHARE("uses a divided share outside a divide -- it reads 0"),
    /** Bound by a `Choose`. */
    CHOSEN_TARGET("refers to the chosen target outside any \"choose\" -- it affects nothing"),
    /** Bound only as an `AsPlayer`'s own player, where it is asked. */
    CHOSEN_PLAYER("refers to \"the chosen player\" outside a \"target player ...\" -- it names nobody"),
    /** Bound by a `ForEach` or `ChooseMany`. */
    EACH_TARGET("refers to \"each\" outside any for-each -- it affects nothing"),
    /** Bound wherever there is a source permanent: never for a spell. */
    SELF_READ("reads \"this card\", but a spell has none in play -- it reads 0 and affects nothing"),
    /** Bound by a trigger, or a delayed trigger's body. */
    EVENT("refers to the triggering event (\"it\" / \"that much\") outside a trigger -- it reads 0 and affects nothing"),
    /** Bound by a filter's `where`. */
    SUBJECT("refers to \"the permanent being tested\" outside a filter's where -- it matches nothing"),
    /** A target variable of any other name, bound by the `Choose` / for-each
     *  that declares it. */
    NAMED("refers to a named target that no enclosing \"choose\" or for-each binds -- it affects nothing"),
}

/** The variable names with a fixed meaning; any other is `FreeVar.NAMED`. */
private val DEFAULT_NAMES = setOf(SELF, CHOSEN, EACH, TRIGGER, SUBJECT)

/** Finds `v` where it is free. For `NAMED`, `name` says which variable. */
private class FindFree(private val v: FreeVar, private val name: String? = null) : Subst() {
    var found = false
    private val varName: String? = when (v) {
        FreeVar.CHOSEN_TARGET -> CHOSEN
        FreeVar.EACH_TARGET -> EACH
        FreeVar.SELF_READ -> SELF
        FreeVar.EVENT -> TRIGGER
        FreeVar.SUBJECT -> SUBJECT
        FreeVar.NAMED -> name
        else -> null
    }
    override fun leaf(e: IntExpr): IntExpr {
        if ((v == FreeVar.X && e == IntExpr.X) || (v == FreeVar.SHARE && e == IntExpr.Share) ||
            (v == FreeVar.EVENT && e == IntExpr.EventAmount)
        ) found = true
        return e
    }
    override fun target(ref: Ref): Ref {
        if (varName != null && ref == Ref.Var(varName)) found = true
        return ref
    }
    override fun readsSelf() { if (v == FreeVar.SELF_READ) found = true }
    override fun chosenPlayer() { if (v == FreeVar.CHOSEN_PLAYER) found = true }
    override fun shadowedBy(binder: Binder, name: String?) = when (v) {
        // A granted ability is ACTIVATED, so it chooses its own X and has its
        // own source.
        FreeVar.X -> binder == Binder.GRANT
        FreeVar.SHARE -> binder == Binder.DIVIDE
        FreeVar.EVENT -> binder == Binder.EVENT
        FreeVar.CHOSEN_PLAYER -> false
        // A target variable: whatever binds its name.
        else -> name != null && name == varName
    }
}

/** Every variable name the tree mentions that has no fixed meaning. */
private class NamesUsed : Subst() {
    val names = sortedSetOf<String>()
    override fun target(ref: Ref): Ref { if (ref is Ref.Var && ref.name !in DEFAULT_NAMES) names += ref.name; return ref }
}

private fun free(bound: Set<FreeVar>, walk: (Subst) -> Unit): List<FreeVar> {
    val fixed = FreeVar.entries.filter { v -> v != FreeVar.NAMED && v !in bound && FindFree(v).also(walk).found }
    val named = FreeVar.NAMED !in bound && NamesUsed().also(walk).names.any { n -> FindFree(FreeVar.NAMED, n).also(walk).found }
    return if (named) fixed + FreeVar.NAMED else fixed
}

/** The variables this effect reads that its context does not bind. */
fun Effect.freeVars(bound: Set<FreeVar>): List<FreeVar> = free(bound) { subst(it) }

/** The same for a static ability: its filter, ops and condition. */
fun StaticSpec.freeVars(bound: Set<FreeVar>): List<FreeVar> = free(bound) { s ->
    filter.subst(s); ops.forEach { it.subst(s) }; condition?.subst(s)
}

/** Every out-of-scope read on this face, located: `cardKey` and `face`
 *  say which card, the path which part of it. */
fun FaceDoc.scopeDiagnostics(cardKey: String? = null, face: Int? = null): List<Diagnostic> = buildList {
    val onPermanent = setOf(FreeVar.SELF_READ)
    fun report(path: String, where: String, vars: List<FreeVar>) = vars.forEach {
        add(Diagnostic(it.code(), cardKey, face, path, "\"$name\" $where ${it.phrase}"))
    }
    castEffect?.let { report("castEffect", "cast effect", it.freeVars(setOf(FreeVar.X))) }
    triggers.forEachIndexed { i, t -> report("triggers[$i]", "trigger ${i + 1}", t.effect.freeVars(onPermanent + FreeVar.EVENT)) }
    activated.forEachIndexed { i, a -> report("activated[$i]", "ability ${i + 1}", a.effect.freeVars(onPermanent + FreeVar.X)) }
    statics.forEachIndexed { i, st -> report("statics[$i]", "static ${i + 1}", st.freeVars(onPermanent)) }
    replacements.forEachIndexed { i, r ->
        if (r is ReplacementDoc.Replace) report("replacements[$i]", "replacement ${i + 1}", r.instead.freeVars(onPermanent + FreeVar.EVENT))
    }
}

/** [scopeDiagnostics], as sentences. */
fun FaceDoc.scopeProblems(): List<String> = scopeDiagnostics().map { it.message }

/** The player this verb itself acts on, if it has such a slot -- read off the
 *  walk's `actsOn` hook, one node deep, so a new verb whose walk visits its
 *  player slot is covered without a list here. */
internal fun Effect.actsOn(): PlayerRef? {
    var found: PlayerRef? = null
    subst(object : Subst() {
        override val deep get() = false
        override fun actsOn(r: PlayerRef) { if (found == null) found = r }
    })
    return found
}
