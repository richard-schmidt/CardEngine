package ccg

// ---------------------------------------------------------------------------
// The declarable combat model. Two axes:
//
//   style        DECLARED    -- declare attackers, then blockers, then stepped
//                               damage (MTG, LoR).
//                FREE        -- every body with the step's field acts, free
//                               targeting, simultaneous.
//                INDIVIDUAL  -- `PriorityAction.Attack` resolves ONE attack at a
//                               time (Hearthstone, One Piece, Yu-Gi-Oh).
//
//   damageModel  ACCUMULATE  -- damage marks the body; SBA kills at toughness.
//                COMPARE     -- one stat comparison decides destroy / survive;
//                               the difference may go to a player.
//
// Keyword hooks read derived Characteristics and are model-agnostic.
// ---------------------------------------------------------------------------

/** A game's combat, in the language: the program a combat phase runs,
 *  and the one a single `PriorityAction.Attack` runs -- null where attacks
 *  are declared some other way. Null [program]: the game has no combat. */
data class Combat(val program: Effect?, val attack: Effect? = null)

/** What an unknown preset names: no combat at all (the game is refused). */
val NO_COMBAT_PROGRAM = Combat(null)

/** What a combat attack points at. */
sealed interface CombatTarget {
    data class Player(val id: PlayerId) : CombatTarget
    data class Obj(val id: ObjectId) : CombatTarget
}

enum class CombatStyle { DECLARED, FREE, INDIVIDUAL }

enum class DamageModel { ACCUMULATE, COMPARE }

/** What happens in a COMPARE step when the two stats are equal. */
enum class TieResult { BOTH_DESTROYED, ATTACKER_WINS, NOTHING }

/** One combat sub-phase. */
data class CombatStep(
    val name: String,
    /** derived field read as this step's ATTACKER output ("power"/"fast"/"slow"). */
    val damageField: String,
    val damageModel: DamageModel = DamageModel.ACCUMULATE,
    /** COMPARE: the attacker's stat; null -> `damageField`. */
    val attackerField: String? = null,
    /** COMPARE: the defender's stat (a `defense`-mode permanent overrides to
     *  its "defense" field). */
    val defenderField: String = "toughness",
    /** COMPARE: does the defender strike back / can the attacker lose? One Piece
     *  = false. */
    val returnDamage: Boolean = true,
    /** COMPARE: route the stat difference to the losing side's controller
     *  (Yu-Gi-Oh). */
    val excessToController: Boolean = false,
    /** COMPARE: outcome on a tie. */
    val onTie: TieResult = TieResult.BOTH_DESTROYED,
    /** open a priority window after this step's attacks are announced. */
    val responseWindow: Boolean = false,
    /** only creatures WITH this keyword act this step (first strike). */
    val requiresKeyword: String? = null,
    /** creatures WITH this keyword sit this step out -- unless they also have
     *  `exemptKeyword` (double strike acts in both). */
    val excludesKeyword: String? = null,
    val exemptKeyword: String? = null,
)

/** A block-legality rule: an attacker matching [attacker] may be blocked only
 *  by a blocker matching [blocker]. ALL of a config's rules must allow a
 *  pairing (`BlockRule.allows`, in Eval.kt). Both filters are read from the
 *  DEFENDING player's side -- "you" is the blocker's controller -- with
 *  `SUBJECT` the permanent being tested. The canned shapes below are
 *  shortcuts into it, not kinds of it. */
data class BlockRule(val attacker: PermFilter, val blocker: PermFilter) {
    companion object {
        /** "an attacker with `attackerKw` can only be blocked by a blocker
         *  with `blockerKw`" (LoR Elusive; flying / reach). */
        fun needsKeyword(attackerKw: String, blockerKw: String) =
            BlockRule(withKeyword(attackerKw), withKeyword(blockerKw))
        /** "an attacker with `attackerKw` can only be blocked by one whose
         *  `field` is at least `min`" (LoR Fearsome, on power). */
        fun needsStat(attackerKw: String, field: String, min: Int) =
            BlockRule(withKeyword(attackerKw), PermFilter(where = IntExpr.TargetField(BoundTarget(SUBJECT), field) gte min))
        /** "a permanent with `keyword` can't block at all." */
        fun cantBlock(keyword: String) =
            BlockRule(PermFilter(), PermFilter(where = not(BoolExpr.HasKeyword(BoundTarget(SUBJECT), keyword))))

        private fun withKeyword(kw: String) = PermFilter(where = BoolExpr.HasKeyword(BoundTarget(SUBJECT), kw))
    }
}

/** "You cannot reach their [screened] zone while they still hold [by]."
 *
 *  Without a screen a standoff zone strictly dominates: a fleet sitting in the
 *  back reaches everything and is reached by nothing short-ranged. The screen
 *  gives the forward zone a job. */
data class ScreenRule(val screened: String, val by: String)

/** The two stats a body fights with, by range. See [CombatConfig.rangeFields]. */
data class RangeFields(val close: String, val far: String)

/** A combat stance the game declares, which `Effect.SetCombatMode` puts a
 *  permanent in. A defender in one fights a COMPARE step with [defendsWith]
 *  (its toughness when it has no such field) instead of the step's defender
 *  field, and is not traded: an attacker it outlasts survives, its controller
 *  taking the difference when the step sends excess on (Yu-Gi-Oh's defense
 *  position). No stance at all is the ordinary way to fight. */
data class StanceDef(val name: String, val defendsWith: String)

/**
 * WHICH attack is being made. With [CombatConfig.attacksPerRange] a ship fires
 * once per range stat it carries, so "which stat" is a property of the attack,
 * and geometry is asked per attack rather than per body.
 *
 * `null` as a parameter means "no range system, or a question about the body as
 * a whole". Never defaulted: a default lets a caller silently keep the old rule.
 */
enum class AttackRange { CLOSE, FAR }

data class CombatConfig(
    val style: CombatStyle,
    val steps: List<CombatStep>,
    val blockRules: List<BlockRule> = emptyList(),
    /** INDIVIDUAL: if the defender controls any creature with this keyword, an
     *  attack must target one of them (Hearthstone taunt). */
    val mustTargetKeyword: String? = null,
    /** INDIVIDUAL: a creature with this keyword can't be attacked (stealth). */
    val cantTargetKeyword: String? = null,
    /** INDIVIDUAL: an Obj target must be exhausted (One Piece -- only rested
     *  characters, plus the always-legal Leader / player). */
    val onlyExhaustedTargets: Boolean = false,
    /** INDIVIDUAL: the defender may redirect an attack to a creature with this
     *  keyword (One Piece / Hearthstone-ish Blocker). */
    val blockerKeyword: String? = null,
    /** INDIVIDUAL: flat damage dealt when a player is hit, instead of the
     *  attacker's stat (One Piece: 1 life card). null -> use the stat. */
    val playerHitAmount: Int? = null,
    /** FREE style: a player is a legal target only for an attacker whose own
     *  lane def has no enemy occupant in the defender's same-named lane.
     *  Gates Obj -> Player only. Meaningless without per-player lanes. */
    val laneLockedPlayerTargets: Boolean = false,
    /** FREE / INDIVIDUAL: a board target must sit in the SAME lane def as the
     *  attacker -- position decides whom you fight, not just when. Compared by
     *  def (`lane-2@P0` opposes `lane-2@P1`); an attacker in a shared zone is
     *  unrestricted. */
    val laneLockedBoardTargets: Boolean = false,
    /** The artillery screen, or null for none. See [ScreenRule]. */
    val screen: ScreenRule? = null,
    /** A card carrying [crossLaneKeyword] may aim at the defending player (so at
     *  the Station) even while its own lane is opposed: brawlers break the line,
     *  artillery shoots over it. Unlike [screen], which `reach` cannot bypass --
     *  a ship can hide behind a line, a Station cannot. */
    val longRangeHitsFace: Boolean = false,
    /** Two damage stats chosen by RANGE rather than by wave: `close` against the
     *  same zone def, `far` across it or at the face. Null: each step reads its
     *  own `damageField`. Keeps a two-number stat line meaningful in one wave --
     *  a 4/1 is a brawler, a 2/4 artillery. */
    val rangeFields: RangeFields? = null,
    /** Damage past what a body can absorb carries to its controller (so to the
     *  Station). A chump blocker then delays damage instead of nullifying it.
     *  `CombatStep.excessToController` is the COMPARE-model equivalent. */
    val overflowToController: Boolean = false,
    /** The keyword that exempts an attacker from `laneLockedBoardTargets`: cross-
     *  lane reach as a priced card property. The bundle names the word; null =
     *  no exemption. */
    val crossLaneKeyword: String? = null,
    /** FREE / INDIVIDUAL: an EXHAUSTED permanent cannot act in combat. FREE style
     *  otherwise has no exhaustion check, which would make any "exhaust to do X"
     *  cost free. A flag, not a fix in place, so EPR Skirmish (which taps for
     *  abilities) keeps its meaning. DECLARED refuses exhausted attackers anyway. */
    val exhaustedCannotAct: Boolean = false,
    /** A ship fires ONCE FOR EACH range stat it carries, at independent targets,
     *  in one simultaneous wave. A flag rather than implied by lanes, because
     *  attack count is a balance decision. */
    val attacksPerRange: Boolean = false,
    /** The stances this combat knows. A name `SetCombatMode` uses that is not
     *  here is an `unknown-stance` error. */
    val stances: List<StanceDef> = emptyList(),
)

// -- reach: read from the game's program, never from a flag ---------------
// How far an attack reaches is authored: expressions over the attacker, the
// target and the defending player (lanes, depth, zones, keywords, stats). The
// engine, the pilots and the board all ask these functions, and they all read
// the same expressions.

/** How attacks reach in a game: the body attack, the guns, and what counts
 *  as a guard -- taken from its combat program's first `FreeAttacks`, or its
 *  attack program's `Attack`. Null: nothing restricts reach. */
data class Reach(val body: Gun, val guns: List<Gun>, val guards: PermFilter?) {
    /** The attack a question is about: a gun by its label, or the body. */
    fun gun(range: AttackRange?): Gun = range?.let { r -> guns.firstOrNull { it.label == r } } ?: body
}

internal fun reachOf(combatProgram: Effect?, attackProgram: Effect?): Reach? {
    var found: Reach? = null
    val look = object : Subst() {
        override fun rewrite(e: Effect): Effect = e.also {
            if (found == null && it is Effect.FreeAttacks) found = Reach(it.body, it.guns, it.guards)
        }
    }
    combatProgram?.subst(look)
    if (found != null) return found
    val a = attackProgram as? Effect.Attack ?: return null
    return Reach(Gun(reaches = a.reaches, reachesFace = a.reachesFace, amount = lit(0)), emptyList(), a.mustTarget)
}

/** Where an attack verb reads reach and damage: `ATTACKER` and `TARGET`
 *  bound, and the defending player. */
fun GameState.attackCtx(ap: PlayerId, attacker: ObjectId, target: ObjectId?, defending: PlayerId?): EvalContext =
    EvalContext(this, ap, bound = if (target == null) mapOf(ATTACKER to attacker) else mapOf(ATTACKER to attacker, TARGET to target), defending = defending)

/** May `attacker` reach `target` on the board? `range` names the gun; null
 *  is the body as a whole. The one answer the engine, pilots and UI read. */
fun GameState.laneReaches(rules: Rules, attacker: ObjectId, target: ObjectId, range: AttackRange?): Boolean {
    val gun = rules.reach?.gun(range) ?: return true
    val ap = battlefield[attacker]?.controller ?: return true
    return gun.reaches.eval(placed(rules).attackCtx(ap, attacker, target, battlefield[target]?.controller))
}

/** May `attacker` aim past the board at the defending PLAYER? */
fun GameState.laneReachesFace(rules: Rules, attacker: ObjectId, defender: PlayerId, range: AttackRange?): Boolean {
    val gun = rules.reach?.gun(range) ?: return true
    val ap = battlefield[attacker]?.controller ?: return true
    return gun.reachesFace.eval(placed(rules).attackCtx(ap, attacker, null, defender))
}

/** How much `attacker` deals to `target` with the gun `range` (null: its
 *  body attack) -- the amount the combat program reads. 0 where nothing is
 *  authored. */
fun GameState.attackDamage(rules: Rules, attacker: ObjectId, target: CombatTarget, range: AttackRange?): Int {
    val gun = rules.reach?.gun(range) ?: return 0
    val s = placed(rules)
    val ap = s.battlefield[attacker]?.controller ?: return 0
    return when (target) {
        is CombatTarget.Obj -> gun.amount.eval(s.attackCtx(ap, attacker, target.id, s.defenderOf(target)))
        is CombatTarget.Player -> gun.faceAmount.eval(s.attackCtx(ap, attacker, null, target.id))
    }
}

/** Can `attacker` reach `target` with ANY attack it makes -- each gun it
 *  carries, or its body when it carries none? What a pilot asks of a
 *  position; the engine asks per attack. */
fun GameState.canReach(rules: Rules, attacker: ObjectId, target: ObjectId): Boolean {
    val guns = gunsOf(rules, attacker)
    return if (guns.isEmpty()) laneReaches(rules, attacker, target, null) else guns.any { laneReaches(rules, attacker, target, it) }
}

/** The same for the defending player. */
fun GameState.canReachFace(rules: Rules, attacker: ObjectId, defender: PlayerId): Boolean {
    val guns = gunsOf(rules, attacker)
    return if (guns.isEmpty()) laneReachesFace(rules, attacker, defender, null) else guns.any { laneReachesFace(rules, attacker, defender, it) }
}

/** Does position gate targeting at all in this game -- is any reach written
 *  as more than "always"? */
fun Rules.positionMatters(): Boolean {
    val r = reach ?: return false
    val always = BoolExpr.Const(true)
    return (listOf(r.body) + r.guns).any { it.reaches != always || it.reachesFace != always }
}

/** The stats a fighter strikes with in combat step [step] -- the fields the
 *  program reads off the attacker there -- or null when the program has no
 *  such step. */
fun Combat.fieldsStruckWith(step: String): Set<String>? {
    val found = mutableSetOf<String>()
    var seen = false
    fun reads(x: IntExpr, who: String) = x.subst(object : Subst() {
        override fun rewrite(e: IntExpr): IntExpr = e.also { if (it is IntExpr.TargetField && it.target.name == who) found += it.key }
    })
    val look = object : Subst() {
        override fun rewrite(e: Effect): Effect = e.also {
            when {
                it is Effect.FreeAttacks && it.step == step -> {
                    seen = true
                    (listOf(it.body) + it.guns).forEach { g -> reads(g.amount, ATTACKER); reads(g.faceAmount, ATTACKER) }
                }
                it is Effect.CombatDamage && it.step == step -> { seen = true; reads(it.amount, SUBJECT) }
                it is Effect.Strike && it.step == step -> { seen = true; reads(it.amount, SUBJECT) }
                it is Effect.Clash && it.step == step -> { seen = true; reads(it.attackStat, SUBJECT) }
            }
        }
    }
    program?.subst(look); attack?.subst(look)
    return found.takeIf { seen }
}

/** The guns `attacker` carries -- one attack each; empty: it makes its one
 *  body attack. */
fun GameState.gunsOf(rules: Rules, attacker: ObjectId): List<AttackRange?> {
    val reach = rules.reach ?: return emptyList()
    val s = placed(rules)
    val ap = s.battlefield[attacker]?.controller ?: return emptyList()
    return reach.guns.filter { it.carried.eval(s.attackCtx(ap, attacker, null, null)) }.map { it.label }
}

/** The guards `attacker` must deal with before it may hit anything else:
 *  the defender's permanents matching the game's guard filter that its body
 *  reaches -- so compulsion never soft-locks, and a guard defends only what
 *  it could be attacked from. Empty = no compulsion. */
fun GameState.compellingGuards(rules: Rules, attacker: ObjectId, defender: PlayerId): List<ObjectId> {
    val reach = rules.reach ?: return emptyList()
    val ap = battlefield[attacker]?.controller ?: return emptyList()
    return placed(rules).guardsFor(reach.guards, reach.body, ap, attacker, defender)
}

internal fun GameState.guardsFor(guards: PermFilter?, body: Gun, ap: PlayerId, attacker: ObjectId, defender: PlayerId): List<ObjectId> {
    guards ?: return emptyList()
    val ctx = EvalContext(this, ap)
    return battlefield.values
        .filter {
            it.controller == defender && guards.matches(ctx, it.id) &&
                body.reaches.eval(attackCtx(ap, attacker, it.id, defender))
        }
        .map { it.id }.sorted()
}

/** Every damage field a config can read -- the step fields plus both range
 *  stats. A pilot's placement heuristic reads it. */
fun CombatConfig.damageFields(): Set<String> =
    steps.map { it.damageField }.toSet() + setOfNotNull(rangeFields?.close, rangeFields?.far)

/** Every lane number the board declares, ascending. */
fun Rules.laneNumbers(): List<Int> =
    zones.values.filter { it.onGrid }.mapNotNull { it.lane }.distinct().sorted()

/** How much more damage [id] can absorb before it dies: the type's own
 *  `damageCounter` when it declares one (else overflow would fire on every hit),
 *  otherwise toughness minus marked damage. */
fun GameState.damageCapacity(rules: Rules, id: ObjectId): Int {
    val perm = battlefield[id] ?: return 0
    val chars = characteristicsOf(id)
    val kind = rules.damageCounterFor(chars.types)
    return if (kind != null) (perm.counters[kind] ?: 0).coerceAtLeast(0)
    else (chars.toughness - perm.damageMarked).coerceAtLeast(0)
}

/** How a prevention shield spends.
 *
 *  * `POINTS`     -- a pool of damage points, consumed as it absorbs.
 *  * `INSTANCES`  -- absorbs whole hits regardless of size, one charge each.
 *  * `REDUCTION`  -- "takes N less from each hit"; never consumed (`remaining`
 *                    is the per-hit reduction). */
enum class ShieldMode { POINTS, INSTANCES, REDUCTION }

/** No combat at all: what an unknown preset compiles to, in a game that is
 *  then refused as unplayable (`unknown-combat-preset`). Not a preset. */
val NO_COMBAT = CombatConfig(CombatStyle.DECLARED, emptyList())
