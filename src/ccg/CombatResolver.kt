package ccg

// ---------------------------------------------------------------------------
// Combat: the three styles a `CombatConfig` chooses (DECLARED, FREE,
// INDIVIDUAL) and the damage waves they deal.
//
// Combat re-enters the engine (a damage step opens a priority window; hits
// raise events; declarations ask). `CombatHost` names that re-entry,
// implemented by a private adapter in Engine.kt.
// ---------------------------------------------------------------------------

/** What combat needs from the engine that runs it. */
internal interface CombatHost {
    /** Expire durations, run state-based actions, drain triggers. */
    fun settle(s: GameState): GameState

    /** Raise `event`, settle, and give priority if anything went on the stack. */
    fun boundary(event: GameEvent, state: GameState): GameState

    /** Open a priority window and return the state it closes in. */
    fun window(initial: GameState): GameState

    /** Ask a question (answers are checked by the caller). */
    fun ask(q: Question): Answer

    /** Apply one event through shields, replacements and its default consequence. */
    fun applyEvent(event: GameEvent, state: GameState, skipPrevention: Boolean = false): GameState

    /** Run every replacement against an event, as `applyEvent` does, without
     *  its consequence: the event as it would finally happen (null: replaced
     *  outright) and the state. */
    fun replaced(event: GameEvent, state: GameState): Pair<GameEvent?, GameState>

    /** Move a permanent off the battlefield to `destination`. */
    fun leaves(state: GameState, id: ObjectId, destination: HiddenZone): GameState

    /** Run a combat verb's body. */
    fun interpret(effect: Effect, state: GameState, controller: PlayerId): GameState
}

/** One creature's swing in a simultaneous-damage step.
 *
 *  PRIVATE to combat -- an implementation detail of a damage wave, never part
 *  of the client contract. */
private data class Swing(
    val target: CombatTarget, val amount: Int, val deathtouch: Boolean, val source: ObjectId,
)

internal class CombatResolver(
    private val host: CombatHost,
    private val rules: Rules,
) {
    /** May this permanent fight at all? Decided by its types' declared
     *  `TypeDef.attacks`, never by a hard-coded type name -- a Ship fights as
     *  readily as a Creature. */
    private fun GameState.canFight(id: ObjectId): Boolean = rules.fightsInCombat(characteristicsOf(id).types)

    /** May this permanent act in THIS combat step, given the zone it stands in?
     *  ONE definition for all three combat styles. A zone that declares no
     *  `combatSteps` permits every step. */
    private fun GameState.zonePermitsStep(id: ObjectId, step: String): Boolean {
        val def = battlefield[id]?.zone?.def ?: return true
        val allowed = rules.zones[def]?.combatSteps ?: return true
        return step in allowed
    }

    /** Did this permanent arrive too recently to act in combat? One definition
     *  for all three styles; off unless `GameParams.attackDelayOnEntry`. Not in
     *  `legality` (Attack is Legal there and refused on resolution), so there is
     *  no second opinion to drift. */
    private fun GameState.entryDelayed(id: ObjectId): Boolean =
        rules.params.attackDelayOnEntry &&
            (battlefield[id]?.enteredOnTurn ?: 0) == turnNumber


    // -- the combat verbs: DECLARED combat is a program of these -------

    /** `Effect.DeclareAttackers`, run for [ap]. */
    fun declareAttackers(e: Effect.DeclareAttackers, s0: GameState, ap: PlayerId): GameState {
        if (s0.isOver) return s0
        // Logged so who declares is visible to a player -- the difference
        // between a rule and an assumption (Rules.declaresAttackers).
        var s = s0.logged("$ap declares attackers (${rules.declarerNote()})")
        // The step begins: "at the beginning of the declare attackers step".
        s = host.boundary(GameEvent.CombatStep(DECLARE_ATTACKERS_STEP, ap), s)
        if (s.isOver) return s
        val ctx = EvalContext(s, ap)
        val eligible = s.inPlay
            .filter {
                e.eligible.matches(ctx, it.id) && s.canFight(it.id) &&
                    !s.entryDelayed(it.id) &&
                    !s.attackBarred(it.id)
            }
            .map { it.id }
        if (eligible.isEmpty()) return s
        // a mod may bar attacking one player specifically
        // ("creatures can't attack you"), which `eligible` cannot express.
        val plan = ((host.ask(Question.Attackers(ap, eligible, s)) as? Answer.Attackers)?.assignment ?: emptyMap())
            .filterKeys { it in eligible }
            .filterNot { (atk, def) -> s.attackBarred(atk, s.defenderOf(def)) }
        if (plan.isEmpty()) return s

        for ((atk, def) in plan) {
            if (e.staysReady?.matches(EvalContext(s, ap), atk) != true) {
                s = s.copy(battlefield = s.battlefield + (atk to s.battlefield.getValue(atk).copy(exhausted = true)))
            }
            s = host.boundary(GameEvent.Attacks(atk, def, ap), s)
            if (s.isOver) return s
        }
        s = s.copy(combat = CombatState(attacks = plan.toList()))
        // An extra combat (a card's DeclareAttackers) may run inside another's
        // window: the outer combat is what is under way again after it.
        return host.interpret(e.then, s, ap).copy(combat = s0.combat)
    }

    /** `Effect.DeclareBlockers`: each attacked opponent of [ap], in turn order. */
    fun declareBlockers(e: Effect.DeclareBlockers, s0: GameState, ap: PlayerId): GameState {
        if (s0.isOver) return s0
        var s = host.boundary(GameEvent.CombatStep(DECLARE_BLOCKERS_STEP, ap), s0)
        if (s.isOver) return s
        val attacks = s.combat?.attacks.orEmpty()
        val blocks = mutableListOf<Pair<ObjectId, ObjectId>>()
        for (defender in s.opponentsOf(ap)) {
            val attackers = attacks.filter { (_, t) -> s.defenderOf(t) == defender }.map { it.first }
            if (attackers.isEmpty()) continue
            val ctx = EvalContext(s, defender)
            val eligibleBlockers = s.inPlay
                .filter { e.eligible.matches(ctx, it.id) && s.canFight(it.id) && !s.blockBarred(it.id) }
                .map { it.id }
            val raw = ((host.ask(Question.Blockers(defender, eligibleBlockers, attackers, s)) as? Answer.Blockers)?.assignment ?: emptyMap())
                .filterKeys { it in eligibleBlockers }.filterValues { it in attackers }
                .filter { (b, a) -> e.rules.all { it.allows(s, a, b) } } // P8.1 block-legality
            for ((b, a) in raw) {
                s = host.boundary(GameEvent.Blocks(b, a), s)
                if (s.isOver) return s
            }
            blocks += raw.toList()
        }
        s = s.copy(combat = (s.combat ?: CombatState()).let { it.copy(blocks = it.blocks + blocks) })
        return host.interpret(e.then, s, ap)
    }

    /** `Effect.CombatWindow`. */
    fun combatWindow(s: GameState): GameState =
        if (s.isOver) s else host.window(s)

    /** `Effect.CombatDamage`: every amount is read from the pre-damage state
     *  `s0`, then applied, then one SBA pass (so trades are mutual). */
    fun combatDamage(e: Effect.CombatDamage, before: GameState, ap: PlayerId): GameState {
        if (before.isOver || before.combat == null) return before
        // The step begins, and what triggers on it resolves before any damage.
        val s0 = host.boundary(GameEvent.CombatStep(e.step, ap), before)
        if (s0.isOver) return s0
        val combat = s0.combat ?: return s0
        fun ctxOf(id: ObjectId) = EvalContext(s0, s0.battlefield[id]?.controller ?: ap, subject = id)
        fun PermFilter?.has(id: ObjectId) = this?.matches(EvalContext(s0, ap), id) == true
        fun acts(id: ObjectId): Boolean =
            s0.zonePermitsStep(id, e.step) && // lane acts in this wave?
                (e.acts == null || e.acts.matches(EvalContext(s0, ap), id))
        fun power(id: ObjectId) = e.amount.eval(ctxOf(id))

        val blockersOf: Map<ObjectId, List<ObjectId>> = combat.attacks.associate { (atk, _) ->
            val bs = combat.blocks.filter { it.second == atk }.map { it.first }
            // Blocked by fewer than it needs (menace): unblocked.
            atk to (if (bs.size < e.minBlockers.eval(ctxOf(atk))) emptyList() else bs)
        }

        // Was a local `Hit` data class identical to `Swing`. One type, so both
        // combat styles share `dealWave` and cannot drift apart.
        val hits = mutableListOf<Swing>()

        for ((atk, def) in combat.attacks) {
            if (atk !in s0.battlefield || !acts(atk)) continue
            val power = power(atk)
            val dt = e.lethal.has(atk)
            val tramples = e.tramples.has(atk)
            val blockers = blockersOf[atk].orEmpty().filter { it in s0.battlefield }
            if (blockers.isEmpty()) {
                if (power > 0) hits += Swing(def, power, dt, atk)
            } else {
                var rem = power
                for ((i, b) in blockers.withIndex()) {
                    val lethal = if (dt) 1
                    else (s0.characteristicsOf(b).toughness - s0.battlefield.getValue(b).damageMarked).coerceAtLeast(1)
                    val assign = when {
                        tramples -> minOf(rem, lethal)
                        i == blockers.lastIndex -> rem
                        else -> minOf(rem, lethal)
                    }.coerceAtLeast(0)
                    if (assign > 0) hits += Swing(CombatTarget.Obj(b), assign, dt, atk)
                    rem -= assign
                }
                if (tramples && rem > 0) hits += Swing(def, rem, dt, atk)
            }
        }
        // blockers strike the attacker they blocked
        for ((atk, blockers) in blockersOf) {
            for (b in blockers) {
                if (b !in s0.battlefield || atk !in s0.battlefield || !acts(b)) continue
                val power = power(b)
                if (power > 0) hits += Swing(CombatTarget.Obj(atk), power, e.lethal.has(b), b)
            }
        }

        return host.settle(dealWave(s0, hits, e.step, e.overflow))
    }

    /** `Effect.FreeAttacks`, run for [ap]: one FREE combat step. */
    fun freeAttacks(e: Effect.FreeAttacks, before: GameState, ap: PlayerId): GameState {
        if (before.isOver) return before
        var s = host.boundary(GameEvent.CombatStep(e.step, ap), before)
        if (s.isOver) return s
        val actors = s.inPlay
            .filter {
                s.canFight(it.id) && e.actors.matches(EvalContext(s, ap), it.id) &&
                    s.zonePermitsStep(it.id, e.step) &&
                    !s.entryDelayed(it.id) &&
                    !s.attackBarred(it.id)
            }
            .map { it.id }.sorted()
        if (actors.isEmpty()) return s
        // One entry per (attacker, gun): a ship with two guns fires twice, both
        // in the SAME wave, so the first shot's kills cannot remove the second
        // shot's attackers.
        val assign = mutableListOf<Triple<ObjectId, Gun, CombatTarget>>()
        for (a in actors) {
            if (a !in s.battlefield) continue
            val ctrl = s.battlefield.getValue(a).controller
            val carried = e.guns.filter { it.carried.eval(s.attackCtx(ctrl, a, null, null)) }.ifEmpty { listOf(e.body) }
            for (gun in carried) {
                if (a !in s.battlefield) break
                val t = (host.ask(Question.CombatTgt(ctrl, a, gun.label, s)) as? Answer.CombatTgt)?.target
                if (t == null) {
                    s = s.logged("#$a holds back${gun.label?.let { " (${it.name.lowercase()})" } ?: ""}")
                    continue
                }
                val def = s.defenderOf(t)
                val laneBlocked = t is CombatTarget.Player && def != null &&
                    !gun.reachesFace.eval(s.attackCtx(ctrl, a, null, def))
                val outOfLane = t is CombatTarget.Obj && !gun.reaches.eval(s.attackCtx(ctrl, a, t.id, def))
                val guards = if (def == null) emptyList() else s.guardsFor(e.guards, e.body, ctrl, a, def)
                val ignoresGuard = guards.isNotEmpty() && (t !is CombatTarget.Obj || t.id !in guards)
                if (s.attackBarred(a, def) || laneBlocked || outOfLane || ignoresGuard) {
                    val why = when {
                        laneBlocked -> "lane still opposed"
                        outOfLane -> "out of lane"
                        ignoresGuard -> "must attack a guard"
                        else -> "rule static"
                    }
                    s = s.logged("#$a can't attack ${def ?: "that"} ($why)")
                    continue
                }
                assign += Triple(a, gun, t)
                s = host.boundary(GameEvent.Attacks(a, t, ctrl), s)
                if (s.isOver) return s
            }
        }
        if (e.window) {
            s = host.window(s.copy(combat = CombatState(attacks = assign.map { it.first to it.third })))
                .copy(combat = before.combat)
            if (s.isOver) return s
        }
        val snap = s
        val hits = assign.filter { it.first in snap.battlefield }.map { (a, gun, target) ->
            val ctrl = snap.battlefield.getValue(a).controller
            val amount = when (target) {
                is CombatTarget.Obj -> gun.amount.eval(snap.attackCtx(ctrl, a, target.id, snap.defenderOf(target)))
                is CombatTarget.Player -> gun.faceAmount.eval(snap.attackCtx(ctrl, a, null, target.id))
            }
            Swing(target, amount, e.lethal?.matches(EvalContext(snap, ap), a) == true, a)
        }
        return host.settle(dealWave(s, hits, e.step, e.overflow))
    }

    // -- INDIVIDUAL style: one attack at a time, as verbs --------------

    /** `Effect.Attack`, run for [ap] over the attack `GameState.combat` holds. */
    fun attack(e: Effect.Attack, s0: GameState, ap: PlayerId): GameState {
        if (s0.isOver) return s0
        val (attacker, target) = s0.combat?.attacks?.singleOrNull() ?: return s0
        var s = s0
        val atkPerm = s.battlefield[attacker] ?: return s
        fun mine(f: PermFilter, id: ObjectId) = f.matches(EvalContext(s, ap), id)
        fun sub(x: IntExpr, id: ObjectId) = x.eval(EvalContext(s, ap, subject = id))
        if (!s.canFight(attacker) || !mine(e.attacker, attacker)) return s.logged("#$attacker can't attack")
        if (s.entryDelayed(attacker)) return s.logged("#$attacker arrived this turn")
        val defender = s.defenderOf(target)
        if (s.attackBarred(attacker, defender)) {
            return s.logged("#$attacker can't attack that (rule static)")
        }
        if (atkPerm.attacksThisTurn >= sub(e.attacksPerTurn, attacker)) return s.logged("#$attacker has no attacks left")

        // Guards: while the defender has one, only a guard may be attacked.
        fun guarded() = e.mustTarget != null && defender != null && s.battlefield.values.any {
            it.controller == defender && mine(e.mustTarget, it.id)
        }
        val legal = defender != null && defender != ap && defender in s.turnOrder && when (target) {
            // laneLockedPlayerTargets applies to INDIVIDUAL too. One attack, one
            // target, so the body's own answer is right.
            is CombatTarget.Player -> !guarded() && e.reachesFace.eval(s.attackCtx(ap, attacker, null, defender))
            is CombatTarget.Obj ->
                e.reaches.eval(s.attackCtx(ap, attacker, target.id, defender)) && mine(e.targets, target.id) &&
                    (!guarded() || mine(e.mustTarget!!, target.id))
        }
        if (!legal) return s.logged("#$attacker: illegal attack target")

        var actual = target
        if (e.redirect != null) {
            val excludeId = (target as? CombatTarget.Obj)?.id
            val theirs = EvalContext(s, defender!!)
            val blockers = s.inPlay.filter {
                it.id != excludeId && s.canFight(it.id) && e.redirect.matches(theirs, it.id)
            }.map { it.id }
            if (blockers.isNotEmpty()) {
                // Checked like every other answer: a blocker
                // outside `blockers` is refused and the attack goes through.
                val answer = (host.ask(Question.Redirect(defender, attacker, blockers, s, attacked = target)) as? Answer.Blocker)?.id
                when {
                    answer == null -> {}
                    answer in blockers -> actual = CombatTarget.Obj(answer)
                    else -> s = s.logged("$defender answered blocker #$answer, which can't block -- no block")
                }
            }
        }

        s = s.copy(
            battlefield = s.battlefield + (attacker to atkPerm.copy(
                attacksThisTurn = atkPerm.attacksThisTurn + 1,
                exhausted = atkPerm.exhausted || e.staysReady?.let { mine(it, attacker) } != true,
            )),
            combat = CombatState(attacks = listOf(attacker to actual)),
        )
        s = host.boundary(GameEvent.Attacks(attacker, actual, ap), s)
        if (s.isOver || attacker !in s.battlefield) return s
        return host.interpret(e.then, s, ap)
    }

    /** The attack a `Strike` / `Clash` resolves, once its step has begun --
     *  null when there is none, or the attacker has gone or its zone sits the
     *  step out. */
    private fun beginStep(step: String, before: GameState, ap: PlayerId): Pair<GameState, Pair<ObjectId, CombatTarget>>? {
        if (before.isOver) return null
        val attack = before.combat?.attacks?.singleOrNull() ?: return null
        if (attack.first !in before.battlefield || !before.zonePermitsStep(attack.first, step)) return null
        val s = host.boundary(GameEvent.CombatStep(step, ap), before)
        if (s.isOver || attack.first !in s.battlefield) return null
        return s to attack
    }

    /** `Effect.Strike`: the ACCUMULATE exchange of one attack. */
    fun strike(e: Effect.Strike, before: GameState, ap: PlayerId): GameState {
        val (snap, attack) = beginStep(e.step, before, ap) ?: return before
        val (attacker, actual) = attack
        fun sub(x: IntExpr, id: ObjectId) = x.eval(EvalContext(snap, ap, subject = id))
        fun lethal(id: ObjectId) = e.lethal?.matches(EvalContext(snap, ap), id) == true
        val amt = if (actual is CombatTarget.Player) (e.faceAmount ?: e.amount).let { sub(it, attacker) } else sub(e.amount, attacker)
        var s2 = dealCombatDamage(snap, actual, amt, lethal(attacker), attacker, e.step)
        if (e.returnDamage && actual is CombatTarget.Obj && actual.id in snap.battlefield) {
            val back = sub(e.amount, actual.id)
            if (back > 0) s2 = dealCombatDamage(s2, CombatTarget.Obj(attacker), back, lethal(actual.id), actual.id, e.step)
        }
        return host.settle(s2)
    }

    /** `Effect.Clash`: the COMPARE resolution of one attack. */
    fun clash(e: Effect.Clash, before: GameState, ap: PlayerId): GameState {
        val (s0, attack) = beginStep(e.step, before, ap) ?: return before
        val (atk, target) = attack
        fun sub(x: IntExpr, id: ObjectId) = x.eval(EvalContext(s0, ap, subject = id))
        val def = when (target) {
            is CombatTarget.Player -> {
                val amt = sub(e.faceAmount ?: e.attackStat, atk)
                return host.settle(dealCombatDamage(s0, target, amt, false, step = e.step))
            }
            is CombatTarget.Obj -> target.id
        }
        if (def !in s0.battlefield) return s0
        val cd = s0.characteristicsOf(def)
        val aStat = sub(e.attackStat, atk)
        val stance = s0.battlefield.getValue(def).combatMode?.let { m -> e.stances.firstOrNull { it.name == m } }
        val dStat = if (stance != null) (cd.fields[stance.defendsWith] ?: cd.toughness) else sub(e.defendStat, def)
        val piercing = e.pierces?.matches(EvalContext(s0, ap), atk) == true
        val aCtrl = s0.battlefield.getValue(atk).controller
        val dCtrl = s0.battlefield.getValue(def).controller
        var s = s0
        if (stance != null) {
            when {
                aStat > dStat -> {
                    s = host.leaves(s, def, HiddenZone.GRAVEYARD)
                    if (piercing && e.excessToController) {
                        s = dealCombatDamage(s, CombatTarget.Player(dCtrl), aStat - dStat, false, step = e.step)
                    }
                }
                aStat < dStat -> if (e.excessToController) {
                    s = dealCombatDamage(s, CombatTarget.Player(aCtrl), dStat - aStat, false, step = e.step)
                }
                else -> {}
            }
        } else {
            when {
                aStat > dStat -> {
                    s = host.leaves(s, def, HiddenZone.GRAVEYARD)
                    if (e.excessToController) s = dealCombatDamage(s, CombatTarget.Player(dCtrl), aStat - dStat, false, step = e.step)
                }
                aStat < dStat -> if (e.returnDamage) {
                    s = host.leaves(s, atk, HiddenZone.GRAVEYARD)
                    if (e.excessToController) s = dealCombatDamage(s, CombatTarget.Player(aCtrl), dStat - aStat, false, step = e.step)
                }
                else -> when (e.onTie) {
                    TieResult.BOTH_DESTROYED -> {
                        s = host.leaves(s, def, HiddenZone.GRAVEYARD); s = host.leaves(s, atk, HiddenZone.GRAVEYARD)
                    }
                    TieResult.ATTACKER_WINS -> s = host.leaves(s, def, HiddenZone.GRAVEYARD)
                    TieResult.NOTHING -> {}
                }
            }
        }
        return host.settle(s)
    }

    /** The permanent that IS a player where the loss condition lives on the
     *  board (a Station, Base, Hero). Player-targeted hits are redirected onto
     *  it, so every player-targeting rule keeps working and player damage gets
     *  shields, triggers and wave aggregation from the ordinary Obj path. */
    private fun anchorOf(state: GameState, player: PlayerId): ObjectId? =
        state.inPlay.firstOrNull { p ->
            p.controller == player &&
                state.characteristicsOf(p.id).types.any { rules.typeOf(it).loseOnDeath }
        }?.id

    /** SIMULTANEOUS combat damage. One combat step's damage is a WAVE:
     *  prevention and replacement see the whole bundle against a target ONCE,
     *  then the surviving damage is dealt per source, so "whenever this deals
     *  damage" still fires per attacker.
     *
     *  Hit-by-hit would let a one-shot shield absorb whichever hit came first --
     *  arbitrary and not adjudicable at a table. Partial prevention
     *  deals the remainder in collection order, which only decides which source
     *  is credited. Damage to a PLAYER stays per hit (its own event and shield
     *  path). */
    private fun dealWave(s0: GameState, swings: List<Swing>, step: String?, overflow: Boolean): GameState {
        var s = s0
        val redirected = swings.map { sw ->
            val t = sw.target
            if (t is CombatTarget.Player) {
                anchorOf(s, t.id)?.let { sw.copy(target = CombatTarget.Obj(it)) } ?: sw
            } else {
                sw
            }
        }
        for ((target, group) in redirected.groupBy { it.target }) {
            when (target) {
                is CombatTarget.Player ->
                    for (h in group) s = dealCombatDamage(s, target, h.amount, h.deathtouch, h.source, step)
                is CombatTarget.Obj -> {
                    if (target.id !in s.battlefield) continue
                    val chars = s.characteristicsOf(target.id)
                    // Deathtouch amplification is per HIT and computed once,
                    // here, so the wave total and the dealt amounts agree.
                    val amounts = group.map { if (it.deathtouch) maxOf(it.amount, chars.toughness) else it.amount }
                    val total = amounts.sum()
                    if (total <= 0) continue
                    val (s1, surviving) = absorbWave(s, target.id, total, step)
                    s = s1
                    // Overflow: damage past what this body can absorb carries to
                    // its controller (the Station). Capped here, before the
                    // distribution loop, so dealt + excess always equals
                    // `surviving`. Never overflows off the anchor itself.
                    val victimCtrl = s.battlefield[target.id]?.controller
                    val overflowing = overflow && victimCtrl != null &&
                        anchorOf(s, victimCtrl) != target.id
                    val capacity = if (overflowing) s.damageCapacity(rules, target.id) else surviving
                    val excess = (surviving - capacity).coerceAtLeast(0)
                    var left = surviving - excess
                    for ((i, h) in group.withIndex()) {
                        if (left <= 0) break
                        if (target.id !in s.battlefield) break
                        val amt = minOf(amounts[i], left)
                        left -= amt
                        if (amt > 0) {
                            s = host.applyEvent(
                                GameEvent.DamageDealt(target.id, amt, source = h.source, combat = true, step = step),
                                s,
                                skipPrevention = true,
                            )
                        }
                    }
                    if (excess > 0 && victimCtrl != null) {
                        // Through the SAME anchor redirect the wave used, so
                        // overflow lands where a face hit lands -- on the
                        // Station -- instead of on a player counter the board
                        // never shows.
                        val face = anchorOf(s, victimCtrl)?.let { CombatTarget.Obj(it) }
                            ?: CombatTarget.Player(victimCtrl)
                        s = s.logged("#${target.id} overflows $excess to $victimCtrl")
                        s = dealCombatDamage(s, face, excess, false, group.firstOrNull()?.source, step)
                    }
                }
            }
        }
        return s
    }

    /** Run shields and replacements ONCE against a whole wave. Returns the state
     *  with them spent, and how much damage survives to actually be dealt. */
    private fun absorbWave(s: GameState, target: ObjectId, total: Int, step: String?): Pair<GameState, Int> {
        // Continuous reduction first, THEN consumable shields -- so a shield is
        // spent on what an anthem could not already absorb, which is the
        // ordering that favours the defender and is the one worth stating.
        val reduced = (total - s.damageReduction(target)).coerceAtLeast(0)
        if (reduced <= 0) return s.logged("#$target takes no damage (reduced)") to 0
        val probe = GameEvent.DamageDealt(target, reduced, source = null, combat = true, step = step)
        val (survives, s1) = host.replaced(probe, s)
        return s1 to ((survives as? GameEvent.DamageDealt)?.amount ?: 0)
    }

    /** Route combat damage: to a player's life (+ a PlayerDamaged event); to a
     *  Planeswalker's loyalty / a Battle's defense counters; else a DamageDealt
     *  on the creature (deathtouch inflates it to lethal). */
    private fun dealCombatDamage(
        s: GameState, target: CombatTarget, amount: Int, deathtouch: Boolean, source: ObjectId? = null,
        /** WHICH combat step dealt it -- "fast"/"slow" in EPR Skirmish. Carried
         *  onto the event so a card can care which wave hit it; null for
         *  non-combat damage. */
        step: String? = null,
    ): GameState {
        if (amount <= 0) return s
        return when (target) {
            is CombatTarget.Player -> {
                // The life loss is the event's default consequence.
                if (target.id !in s.players) return s
                host.applyEvent(GameEvent.PlayerDamaged(target.id, amount, combat = true, source = source), s)
            }
            is CombatTarget.Obj -> {
                if (target.id !in s.battlefield) return s
                val chars = s.characteristicsOf(target.id)
                // Always through the event, so damage triggers and shields see
                // every hit; the DamageDealt handler alone decides whether
                // counters or marked damage record it.
                val eff = if (deathtouch) maxOf(amount, chars.toughness) else amount
                host.applyEvent(GameEvent.DamageDealt(target.id, eff, source = source, combat = true, step = step), s)
            }
        }
    }
}
