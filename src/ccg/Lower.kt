package ccg

// ---------------------------------------------------------------------------
// Lowering. The authoring language has SURFACE forms an author reaches for
// ("this card's power", "+1/+1", "when this enters") and a CORE the
// interpreter runs. Each surface form is defined here, once, as the core it
// means; `CardDoc.build()` / `GameDoc.compile()` hand the engine only core.
//
//     SelfField / SelfCounter / SelfDamage  ->  Target*(SELF)
//     HasType                               ->  IsType(SELF)
//     LifeOf                                ->  PlayerCounter(the damage counter)
//     PlusPT / SetPT                        ->  PlusField / SetField, per field
//     named TriggerDoc kinds                ->  TriggerDoc.On(pattern)
//     ReturnFromDiscard                     ->  SearchZone(graveyard -> hand / into play, exactly)
//     DamageOpponent                        ->  DealDamage(player = Opponent)
//     the named ReplacementDoc kinds        ->  ReplacementDoc.Replace(pattern, instead)
//     a DECLARED / INDIVIDUAL CombatConfig  ->  its combat and attack programs
//
// Lowering reaches every part of a card and of the rules, the templates for
// new objects included (an emblem's statics, a token's granted abilities),
// so the interpreter has no surface form to handle: it refuses one
// (`SurfaceFormReached`). The forms themselves are listed once, below, by
// `isSurface` -- what the lowering rewrites and the detector reports.
// ---------------------------------------------------------------------------

/** One surface expression, as core. `damageCounter` is the game's: `LifeOf`
 *  reads it, and in a game whose players take no damage (null) it reads 0. */
internal fun IntExpr.lowerNode(damageCounter: String?): IntExpr = when (this) {
    is IntExpr.SelfField -> IntExpr.TargetField(BoundTarget(SELF), key)
    is IntExpr.SelfCounter -> IntExpr.TargetCounter(BoundTarget(SELF), kind)
    IntExpr.SelfDamage -> IntExpr.TargetDamage(BoundTarget(SELF))
    is IntExpr.LifeOf -> if (damageCounter != null) IntExpr.PlayerCounter(who, damageCounter) else IntExpr.Lit(0)
    else -> this
}

internal fun BoolExpr.lowerNode(): BoolExpr = when (this) {
    is BoolExpr.HasType -> BoolExpr.IsType(BoundTarget(SELF), types)
    else -> this
}

internal fun CharOp.lowerOp(): List<CharOp> = when (this) {
    is CharOp.PlusPT -> listOf(CharOp.PlusField("power", power), CharOp.PlusField("toughness", toughness))
    is CharOp.SetPT -> listOf(CharOp.SetField("power", power), CharOp.SetField("toughness", toughness))
    else -> listOf(this)
}

internal fun Effect.lowerNode(): Effect = when (this) {
    is Effect.ReturnFromDiscard -> Effect.SearchZone(
        who, HiddenZone.GRAVEYARD, if (toBattlefield) HiddenZone.GRAVEYARD else HiddenZone.HAND, count,
        thenShuffle = false, upTo = false, intoPlay = toBattlefield,
    )
    is Effect.DamageOpponent -> Effect.DealDamage(amount, null, PlayerRef.Opponent)
    else -> this
}

/** A named replacement kind, as the general form: the damage-shaped ones match `Damaged` in their
 *  step, the death one `Dies` on the permanent as it is in play. */
internal fun ReplacementDoc.lowerNode(): ReplacementDoc = when (this) {
    // Only while this is still in play: a totem already gone guards nothing.
    is ReplacementDoc.DamageToSacrificeSelf -> ReplacementDoc.Replace(
        EventPattern.Damaged(filter.and(countOf(PermFilter(pinned = BoundTarget(SELF))) gte 1), step = onlyStep),
        Effect.Destroy(BoundTarget(SELF)),
    )
    is ReplacementDoc.PreventDamageTo -> ReplacementDoc.Replace(EventPattern.Damaged(filter, step = onlyStep), Effect.NoOp)
    // Spending the charge is removing it; `CounterCrosses` sees that either way.
    is ReplacementDoc.DamageToRemoveCounter -> ReplacementDoc.Replace(
        EventPattern.Damaged(filter.and(IntExpr.TargetCounter(BoundTarget(SUBJECT), counter) gte 1), step = onlyStep),
        Effect.RemoveCounter(counter, lit(1), BoundTarget(TRIGGER)),
    )
    is ReplacementDoc.DeathToExile -> ReplacementDoc.Replace(
        EventPattern.Dies(types = emptySet(), filter = filter),
        Effect.SendTo(BoundTarget(TRIGGER), HiddenZone.EXILE),
    )
    is ReplacementDoc.Replace -> this
}

/** `this`, also requiring `cond` of each candidate. */
private fun PermFilter.and(cond: BoolExpr): PermFilter = copy(where = where?.let { BoolExpr.And(listOf(it, cond)) } ?: cond)

// -- the surface forms --------------------------------------------------------
// Each is one clause of a `lowerNode` / `lowerOp` above.

fun IntExpr.isSurface(): Boolean = this is IntExpr.SelfField || this is IntExpr.SelfCounter || this == IntExpr.SelfDamage || this is IntExpr.LifeOf
fun BoolExpr.isSurface(): Boolean = this is BoolExpr.HasType
fun Effect.isSurface(): Boolean = this is Effect.ReturnFromDiscard || this is Effect.DamageOpponent
fun CharOp.isSurface(): Boolean = this is CharOp.PlusPT || this is CharOp.SetPT
fun ReplacementDoc.isSurface(): Boolean = this !is ReplacementDoc.Replace

/** A surface form reached the interpreter: something ran a tree that did not
 *  come through `GameDoc.compile()`. */
class SurfaceFormReached(form: Any) : IllegalStateException(
    "surface form ${form::class.simpleName} reached the interpreter -- run what compile() builds, which lowers it",
)

private class Lowering(private val damageCounter: String?, private val params: GameParams? = null) : Subst() {
    /** A game parameter is filled here, where the game is known; with
     *  no params (a card built on its own) it is left as authored. */
    override fun leaf(e: IntExpr): IntExpr =
        if (e is IntExpr.Param && params != null) IntExpr.Lit(params.param(e.name) ?: 0) else e
    override fun rewrite(e: IntExpr) = e.lowerNode(damageCounter)
    override fun rewrite(e: BoolExpr) = e.lowerNode()
    // The templates for new objects are not walked by `subst` (nothing binds
    // into them), but they run like any other tree, so they are lowered too.
    override fun rewrite(e: Effect) = intoTemplates(e, this).lowerNode()
    override fun rewriteOps(ops: List<CharOp>) = ops.flatMap { it.lowerOp() }
    override fun rewrite(r: ReplacementDoc) = r.lowerNode()
}

/** `e` with `s` applied inside its templates: an emblem's statics, a token's
 *  granted abilities. */
private fun intoTemplates(e: Effect, s: Subst): Effect = when (e) {
    is Effect.CreateEmblem -> e.copy(statics = e.statics.substAll(s))
    is Effect.CreateToken -> e.copy(chars = e.chars.copy(granted = e.chars.granted.map { it.substAll(s) }))
    else -> e
}

// -- every part of a card, under one substitution ----------------------------

private fun StaticSpec.substAll(s: Subst): StaticSpec =
    copy(filter = filter.subst(s), ops = s.rewriteOps(ops.map { it.subst(s) }), condition = condition?.subst(s))

private fun Cost.substAll(s: Subst): Cost = copy(
    additional = additional?.subst(s),
    payFrom = payFrom?.let { it.copy(filter = it.filter.subst(s)) },
    alternatives = alternatives.map { it.substAll(s) },
)

private fun ActivatedAbility.substAll(s: Subst): ActivatedAbility = copy(cost = cost.substAll(s), effect = effect.subst(s))

private fun EventPattern.substAll(s: Subst): EventPattern = when (this) {
    is EventPattern.Damaged -> copy(filter = filter.subst(s))
    is EventPattern.Dies -> copy(filter = filter?.subst(s))
    else -> this
}

private fun RuleMod.substAll(s: Subst): RuleMod = when (this) {
    is RuleMod.Cant -> this
    is RuleMod.CantAttack -> copy(filter = filter.subst(s))
    is RuleMod.CantBlock -> copy(filter = filter.subst(s))
    is RuleMod.CantActivate -> copy(filter = filter.subst(s))
    is RuleMod.ReduceDamage -> copy(filter = filter.subst(s), amount = amount.subst(s), condition = condition?.subst(s))
}

private fun ReplacementDoc.substAll(s: Subst): ReplacementDoc = s.rewrite(
    when (this) {
        is ReplacementDoc.DamageToSacrificeSelf -> copy(filter = filter.subst(s))
        is ReplacementDoc.PreventDamageTo -> copy(filter = filter.subst(s))
        is ReplacementDoc.DamageToRemoveCounter -> copy(filter = filter.subst(s))
        is ReplacementDoc.DeathToExile -> copy(filter = filter.subst(s))
        is ReplacementDoc.Replace -> copy(pattern = pattern.substAll(s), instead = instead.subst(s))
    },
)

private fun Statics.substAll(s: Subst): Statics = copy(
    chars = chars.map { it.substAll(s) },
    rules = rules.map { it.substAll(s) },
    replacements = replacements.map { it.substAll(s) },
)

/** Every surface form in what `walk` visits, by name, templates included.
 *  Empty for anything `compile()` built. */
internal fun surfaceForms(walk: (Subst) -> Unit): List<String> {
    val found = mutableListOf<String>()
    walk(object : Subst() {
        override fun rewrite(e: IntExpr): IntExpr = e.also { if (it.isSurface()) found += it::class.simpleName!! }
        override fun rewrite(e: BoolExpr): BoolExpr = e.also { if (it.isSurface()) found += it::class.simpleName!! }
        override fun rewrite(e: Effect): Effect = e.also { if (it.isSurface()) found += it::class.simpleName!!; intoTemplates(it, this) }
        override fun rewriteOps(ops: List<CharOp>): List<CharOp> = ops.also { os -> os.forEach { if (it.isSurface()) found += it::class.simpleName!! } }
        override fun rewrite(r: ReplacementDoc): ReplacementDoc = r.also { if (it.isSurface()) found += "ReplacementDoc.${it::class.simpleName}" }
    })
    return found
}

/** Every keyword a card of this game tests for: a `HasKeyword` anywhere in
 *  its effects, statics, triggers, costs or filters ("creatures with flying
 *  get +1/+1" gives flying a rule). */
internal fun GameDoc.keywordsCardsRead(): Set<String> {
    val found = mutableSetOf<String>()
    val s = object : Subst() {
        override fun rewrite(e: BoolExpr): BoolExpr = e.also { if (it is BoolExpr.HasKeyword) found += it.keyword }
    }
    for (c in cards) c.build().substAll(s)
    return found
}

/** Every part of a built card under `s`. Each trigger comes back as `On`,
 *  the one kind the engine reads. */
private fun CardDefinition.substAll(s: Subst): CardDefinition = copy(
    faces = faces.map { f ->
        f.copy(
            castEffect = f.castEffect?.subst(s),
            triggers = f.triggers.map { t -> TriggerDoc.On(t.pattern.substAll(s), t.effect.subst(s), t.order) },
            statics = f.statics.substAll(s),
            activated = f.activated.map { it.substAll(s) },
        )
    },
    entersWith = entersWith.map { it.copy(initial = it.initial.subst(s)) },
    diesWhen = diesWhen?.subst(s),
    cost = cost.substAll(s),
    recast = recast?.let { it.copy(cost = it.cost.substAll(s)) },
    requires = requires?.subst(s),
)

/** A card built without the compiler (a test's), lowered as `compile()`
 *  would lower it. */
fun CardDefinition.lowered(dc: String? = null, params: GameParams? = null): CardDefinition = substAll(Lowering(dc, params))

/** Every surface form left in a built card -- including a trigger that is not
 *  `On`, which is what lowering makes of every named kind. */
fun CardDefinition.surfaceForms(): List<String> =
    surfaceForms { s -> substAll(s) } +
        faces.flatMap { f -> f.triggers.filterNot { it is TriggerDoc.On }.map { "TriggerDoc.${it::class.simpleName}" } }

/** Every surface form anywhere in the rules: cards, types, phases. */
fun Rules.surfaceForms(): List<String> =
    cards.values.flatMap { it.surfaceForms() } +
        surfaceForms { s -> types.values.forEach { it.diesWhen?.subst(s) }; turn.phases.forEach { it.onEnter.subst(s) } }

// -- whole trees --------------------------------------------------------------

fun Effect.lowered(damageCounter: String? = null, params: GameParams? = null): Effect = subst(Lowering(damageCounter, params))
fun IntExpr.lowered(damageCounter: String? = null, params: GameParams? = null): IntExpr = subst(Lowering(damageCounter, params))
fun BoolExpr.lowered(damageCounter: String? = null, params: GameParams? = null): BoolExpr = subst(Lowering(damageCounter, params))

internal fun PermFilter.lowered(dc: String?, params: GameParams? = null): PermFilter = subst(Lowering(dc, params))
internal fun StaticSpec.lowered(dc: String?, params: GameParams? = null): StaticSpec = substAll(Lowering(dc, params))
internal fun Cost.lowered(dc: String?, params: GameParams? = null): Cost = substAll(Lowering(dc, params))
internal fun ActivatedAbility.lowered(dc: String?, params: GameParams? = null): ActivatedAbility = substAll(Lowering(dc, params))
internal fun RuleMod.lowered(dc: String?, params: GameParams? = null): RuleMod = substAll(Lowering(dc, params))
internal fun ReplacementDoc.lowered(dc: String?, params: GameParams? = null): ReplacementDoc = substAll(Lowering(dc, params))

/** Every trigger becomes `On(pattern)`: the named kinds are spellings of a
 *  pattern, which is all the engine ever reads of them. */
internal fun TriggerDoc.lowered(dc: String?, params: GameParams? = null): TriggerDoc =
    Lowering(dc, params).let { l -> TriggerDoc.On(pattern.substAll(l), effect.subst(l), order) }

internal fun TypeDef.lowered(dc: String?, params: GameParams? = null): TypeDef = copy(diesWhen = diesWhen?.lowered(dc, params))

// -- combat --------------------------------------------------------------
// A `CombatConfig` is a surface form of a combat PROGRAM: the flags say which
// verbs run, and with which filters. Keyword rules (defender, vigilance,
// menace, deathtouch, trample) are filters here.

/** `SUBJECT` carries `k`, under any of its spellings. */
private fun has(k: Keyword): BoolExpr = has(*k.names)

private fun has(vararg names: String): BoolExpr =
    names.map { BoolExpr.HasKeyword(BoundTarget(SUBJECT), it) }.let { if (it.size == 1) it[0] else BoolExpr.Or(it) }

private fun where(e: BoolExpr) = PermFilter(where = e)

/** Which permanents act in a step keyed by keywords (first strike: only those
 *  WITH it; the regular step: those without it; double strike: both). Null
 *  when the step names none -- everything acts. */
private fun CombatStep.actsFilter(): PermFilter? {
    val needs = requiresKeyword?.let { has(it) }
    val barred = excludesKeyword?.let { not(has(it)) }
    val gate = listOfNotNull(needs, barred).takeIf { it.isNotEmpty() }?.let { if (it.size == 1) it[0] else BoolExpr.And(it) }
        ?: return null
    return where(exemptKeyword?.let { BoolExpr.Or(listOf(has(it), gate)) } ?: gate)
}

/** The combat a config means, as programs. The config is a library
 *  form: the presets are built from it, and old saves are migrated through it. */
fun CombatConfig.lowered(): Combat = Combat(program(), attackProgram())

/** A combat whose programs are written in surface forms, as core. */
fun Combat.lowered(damageCounter: String?, params: GameParams?): Combat =
    Combat(program?.lowered(damageCounter, params), attack?.lowered(damageCounter, params))

/** The combat program this config means, or null for a style not yet in the
 *  language. DECLARED: declare attackers, a window, declare blockers, a
 *  window, then each damage step. */
fun CombatConfig.program(): Effect? = when (style) {
    CombatStyle.DECLARED -> {
        val ready = BoolExpr.Not(BoolExpr.IsExhausted(BoundTarget(SUBJECT)))
        Effect.DeclareAttackers(
            eligible = PermFilter(controller = PlayerRef.You, where = BoolExpr.And(listOf(ready, not(has(Keyword.DEFENDER))))),
            staysReady = where(has(Keyword.VIGILANCE)),
            then = Effect.Sequence(listOf(
                Effect.CombatWindow,
                Effect.DeclareBlockers(
                    eligible = PermFilter(controller = PlayerRef.You, where = ready),
                    rules = blockRules,
                    then = Effect.Sequence(listOf(Effect.CombatWindow) + steps.map { st ->
                        Effect.CombatDamage(
                            step = st.name,
                            amount = IntExpr.TargetField(BoundTarget(SUBJECT), st.damageField),
                            acts = st.actsFilter(),
                            lethal = where(has(Keyword.DEATHTOUCH)),
                            tramples = where(has(Keyword.TRAMPLE)),
                            // Menace: blocked by one alone, it is unblocked.
                            minBlockers = lit(1) + IntExpr.CountPerms(PermFilter(pinned = BoundTarget(SUBJECT), where = has(Keyword.MENACE))),
                            overflow = overflowToController,
                        )
                    }),
                ),
            )),
        )
    }
    // Attacks are priority actions, each running `attackProgram`: combat is a
    // window to make them in.
    CombatStyle.INDIVIDUAL -> Effect.CombatWindow
    // Every fighter picks its target, step by step, and they strike together.
    CombatStyle.FREE -> Effect.Sequence(steps.map { st ->
        Effect.FreeAttacks(
            step = st.name,
            actors = PermFilter(where = andOf(
                // A body fights if it carries any stat this combat reads -- even
                // at 0, which is why this is HasField and not a read. With range
                // stats a step's own field is never read, so it is not named.
                (rangeFields?.let { listOf(it.close, it.far) } ?: damageFields().toList())
                    .map { f -> BoolExpr.HasField(S, f) }.let { if (it.size == 1) it[0] else BoolExpr.Or(it) },
                BoolExpr.Not(BoolExpr.IsExhausted(S)).takeIf { exhaustedCannotAct },
            )),
            body = bodyGun(st),
            guns = guns(),
            guards = mustTargetKeyword?.let { where(has(it)) },
            lethal = where(has(Keyword.DEATHTOUCH)),
            window = st.responseWindow,
            overflow = overflowToController,
        )
    })
}

// -- reach, written out ---------------------------------------------------
// What `laneLocked*`, `screen`, `crossLaneKeyword`, `longRangeHitsFace`,
// `rangeFields` and `attacksPerRange` mean, as expressions over the attacker,
// the target and the defending player. A game may write its own instead.

private val A = BoundTarget(ATTACKER)
private val T = BoundTarget(TARGET)
private val S = BoundTarget(SUBJECT)
private val TRUE: BoolExpr = BoolExpr.Const(true)
private val FALSE: BoolExpr = BoolExpr.Const(false)

private fun andOf(vararg terms: BoolExpr?): BoolExpr =
    terms.filterNotNull().filter { it != TRUE }.let { if (it.isEmpty()) TRUE else if (it.size == 1) it[0] else BoolExpr.And(it) }

private fun orOf(vararg terms: BoolExpr?): BoolExpr =
    terms.filterNotNull().filter { it != FALSE }.let { if (it.isEmpty()) FALSE else if (it.size == 1) it[0] else BoolExpr.Or(it) }

private fun exists(f: PermFilter): BoolExpr = IntExpr.CountPerms(f) gte 1

/** `t` stands in a zone with a place on the grid (a lane AND a depth). */
private fun onGrid(t: BoundTarget) = andOf(IntExpr.LaneOf(t) gte 0, orOf(BoolExpr.AtDepth(t, Depth.FRONT), BoolExpr.AtDepth(t, Depth.BACK)))
private fun sameLane(a: BoundTarget, b: BoundTarget) = IntExpr.LaneOf(a) eq IntExpr.LaneOf(b)
/** In no player's zone (a shared one), or not in play. */
private fun shared(t: BoundTarget) = BoolExpr.ZoneOwner(t, null)
/** `b` stands in the same zone def as `a`, either side. */
private fun sameDef(a: BoundTarget, b: BoundTarget) = exists(PermFilter(pinned = b, zone = ZoneScoping.SameAs(a, eitherSide = true)))

private fun CombatConfig.reachKeyword(): BoolExpr = crossLaneKeyword?.let { BoolExpr.HasKeyword(A, it) } ?: FALSE
private fun CombatConfig.longRange(): BoolExpr = rangeFields?.let { IntExpr.TargetField(A, it.far) gte 1 } ?: FALSE

/** May the attacker reach the target on the board, with the gun `range`
 *  (null: the body as a whole)? */
private fun CombatConfig.reachExpr(range: AttackRange?): BoolExpr {
    if (!laneLockedBoardTargets) return TRUE
    // On a grid: a back berth is covered by the front berth of its own lane
    // (reach bypasses the cover, and only that); the long gun reaches every
    // lane, the close one its own.
    val covered = exists(PermFilter(controller = PlayerRef.ControllerOf(T), where = andOf(sameLane(S, T), BoolExpr.AtDepth(S, Depth.FRONT))))
    val width = when (range) {
        AttackRange.FAR -> TRUE
        AttackRange.CLOSE -> sameLane(A, T)
        null -> orOf(longRange(), sameLane(A, T))
    }
    val grid = andOf(not(andOf(BoolExpr.AtDepth(T, Depth.BACK), covered, not(reachKeyword()))), width)
    // Flat: the screen first (reach does not bypass it), then reach, long
    // range, a shared zone on either side, or the same zone def.
    val screened = screen?.let { sc ->
        andOf(exists(PermFilter(pinned = T, zone = ZoneScoping.Named(sc.screened))),
            exists(PermFilter(controller = PlayerRef.ControllerOf(T), zone = ZoneScoping.Named(sc.by))))
    }
    val flat = andOf(screened?.let { not(it) }, orOf(reachKeyword(), longRange(), shared(A), shared(T), sameDef(A, T)))
    val both = andOf(onGrid(A), onGrid(T))
    return orOf(andOf(both, grid), andOf(not(both), flat))
}

/** May the attacker aim at the defending player, with the gun `range`? */
private fun CombatConfig.reachFaceExpr(@Suppress("UNUSED_PARAMETER") range: AttackRange?): BoolExpr {
    if (!laneLockedPlayerTargets) return TRUE
    // On a grid: down its own lane, while the defender holds nothing in it.
    val grid = andOf(IntExpr.LaneOf(A) gte 0,
        not(exists(PermFilter(controller = PlayerRef.Defending, where = andOf(sameLane(S, A), onGrid(S))))))
    // Flat: long range over the line where the game allows it; otherwise
    // only while the defender's copy of the attacker's lane is empty.
    val opposed = andOf(not(shared(A)), exists(PermFilter(zone = ZoneScoping.SameAs(A, eitherSide = true), where = BoolExpr.ZoneOwner(S, PlayerRef.Defending))))
    val flat = orOf(if (longRangeHitsFace) orOf(reachKeyword(), longRange()) else null, not(opposed))
    return orOf(andOf(onGrid(A), grid), andOf(not(onGrid(A)), flat))
}

/** The body's attack: its step's stat, or -- with range stats -- the close
 *  one against its own zone and the far one across it or at the face. */
private fun CombatConfig.bodyGun(st: CombatStep): Gun {
    val rf = rangeFields
    val reaches = reachExpr(null)
    val face = reachFaceExpr(null)
    if (rf == null) return Gun(reaches = reaches, reachesFace = face, amount = IntExpr.TargetField(A, st.damageField))
    val close = IntExpr.TargetField(A, rf.close)
    val far = IntExpr.TargetField(A, rf.far)
    return Gun(
        reaches = reaches,
        reachesFace = face,
        amount = IntExpr.Cond(shared(A), close, IntExpr.Cond(shared(T), far, IntExpr.Cond(sameDef(A, T), close, far))),
        faceAmount = IntExpr.Cond(shared(A), close, far),
    )
}

/** One gun per range stat a body carries, when the game fires once per range. */
private fun CombatConfig.guns(): List<Gun> {
    val rf = rangeFields ?: return emptyList()
    if (!attacksPerRange) return emptyList()
    fun gun(range: AttackRange, field: String) = IntExpr.TargetField(A, field).let { stat ->
        Gun(carried = stat gte 1, reaches = reachExpr(range), reachesFace = reachFaceExpr(range), amount = stat, label = range)
    }
    return listOf(gun(AttackRange.CLOSE, rf.close), gun(AttackRange.FAR, rf.far))
}

/** What one INDIVIDUAL attack runs: the attack checked and committed, then
 *  each step as an exchange (ACCUMULATE) or a comparison (COMPARE). */
fun CombatConfig.attackProgram(): Effect? {
    if (style != CombatStyle.INDIVIDUAL) return null
    val ready = BoolExpr.Not(BoolExpr.IsExhausted(BoundTarget(SUBJECT)))
    fun all(vararg t: BoolExpr?) = listOfNotNull(*t).takeIf { it.isNotEmpty() }?.let { if (it.size == 1) it[0] else BoolExpr.And(it) }
    val face = playerHitAmount?.let { lit(it) }
    return Effect.Attack(
        attacker = PermFilter(where = all(not(has(Keyword.DEFENDER)), ready.takeIf { exhaustedCannotAct })),
        targets = PermFilter(controller = PlayerRef.Opponent, where = all(
            cantTargetKeyword?.let { not(has(it)) },
            // One Piece: only a rested character, or the Leader.
            if (onlyExhaustedTargets) BoolExpr.Or(listOf(BoolExpr.IsExhausted(BoundTarget(SUBJECT)), BoolExpr.IsType(BoundTarget(SUBJECT), setOf(LEADER_TYPE)))) else null,
        )),
        // Windfury: twice a turn.
        attacksPerTurn = lit(1) + IntExpr.CountPerms(PermFilter(pinned = BoundTarget(SUBJECT), where = has(Keyword.WINDFURY))),
        mustTarget = mustTargetKeyword?.let { PermFilter(controller = PlayerRef.Opponent, where = has(it)) },
        redirect = blockerKeyword?.let { PermFilter(controller = PlayerRef.You, where = BoolExpr.And(listOf(ready, has(it)))) },
        staysReady = where(has(Keyword.VIGILANCE)),
        reaches = reachExpr(null),
        reachesFace = reachFaceExpr(null),
        then = Effect.Sequence(steps.map { st ->
            when (st.damageModel) {
                DamageModel.ACCUMULATE -> Effect.Strike(
                    step = st.name,
                    amount = IntExpr.TargetField(BoundTarget(SUBJECT), st.damageField),
                    faceAmount = face,
                    returnDamage = st.returnDamage,
                    lethal = where(has(Keyword.DEATHTOUCH)),
                )
                DamageModel.COMPARE -> Effect.Clash(
                    step = st.name,
                    attackStat = IntExpr.TargetField(BoundTarget(SUBJECT), st.attackerField ?: st.damageField),
                    defendStat = IntExpr.TargetField(BoundTarget(SUBJECT), st.defenderField),
                    faceAmount = face,
                    returnDamage = st.returnDamage,
                    excessToController = st.excessToController,
                    onTie = st.onTie,
                    stances = stances,
                    pierces = where(has(Keyword.TRAMPLE)),
                )
            }
        }),
    )
}
