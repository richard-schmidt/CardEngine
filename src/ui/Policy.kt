package ccgui

import ccg.placed
import ccg.fieldsStruckWith
import ccg.positionMatters
import ccg.canReachFace
import ccg.canReach
import ccg.default
import ccg.Answer
import ccg.Question
import ccg.laneNumbers
import ccg.AttackRange
import ccg.damageFields
import ccg.CombatTarget
import ccg.GameState
import ccg.EvalContext
import ccg.IntExpr
import ccg.SELF
import ccg.Effect
import ccg.ObjectId
import ccg.ModeOption
import ccg.PlayerId
import ccg.PlayerInput
import ccg.PriorityAction
import ccg.Rules
import ccg.ZoneScope
import ccg.legalActionsFor

// ---------------------------------------------------------------------------
// POLICY PILOTS -- the instrument.
//
// A policy-free bot plays every deck the same way, so it measures decks and
// throws plans away; an archetype is a deck PLUS A PLAN. Hence three policies.
//
// ALL THREE PILOTS ARE THE SAME PILOT. `PolicyPilot` owns enumeration,
// tie-breaking, lane mechanics, trade arithmetic and every engine interaction;
// a `Policy` supplies only WHAT IS WANTED. Anything they do not differ in
// cannot be the source of a difference the matrix reports.
//
// `HeuristicPilot` is kept as a FIXTURE (older measurements came from it),
// except for mode choice, which it shares with the policies.
//
// REDACTION: `PolicyPilot` redacts on entry to every decision
// (`state.asSeenBy(seat, rules)`, deferring to `Viewpoint`), so a `Policy`
// structurally cannot read a zone its seat may not see. Counts and ids survive;
// identities do not. Residual leaks: see `Redaction.kt`.
// ---------------------------------------------------------------------------

/** The board facts a policy may reason about. Its `state` is already redacted
 *  (built from `state.asSeenBy(seat, rules)`), so a policy reaching past the
 *  helpers still finds masked identities. */
class PolicyView(
    val rules: Rules,
    val state: GameState,
    val seat: PlayerId,
) {
    val opponent: PlayerId = state.opponentOf(seat)

    /** How many bodies `who` holds in one play-zone def (a lane). */
    fun countIn(def: String, who: PlayerId): Int {
        val ref = rules.resolveZone(def, who)
        return state.battlefield.values.count { it.zone == ref && it.controller == who }
    }

    fun mineIn(def: String): Int = countIn(def, seat)
    fun theirsIn(def: String): Int = countIn(def, opponent)

    fun myBodies(): Int = state.battlefield.values.count { it.controller == seat }
    fun theirBodies(): Int = state.battlefield.values.count { it.controller == opponent }

    /** The per-player play zones this bundle declares -- the lanes. Read from
     *  the rules rather than named, so a policy still says nothing about any
     *  particular game. */
    fun laneDefs(): List<String> =
        rules.zones.values.filter { it.scope == ZoneScope.PER_PLAYER }.map { it.id }.sorted()

    /** Lanes where I stand opposite them. Denial is symmetric under
     *  `laneLockedPlayerTargets`: a lane I contest is a lane neither of us can
     *  attack a Station through, mine included. */
    fun lanesIContest(): Int = laneDefs().count { mineIn(it) > 0 && theirsIn(it) > 0 }

    /** A body's total printed stats, and its stats-per-mana RATE. Competence,
     *  not strategy: any pilot valuing bodies must compare two of them; what
     *  each policy does with the number is its own business. Reads the same
     *  fields as `hpOf`/`punchOf`, so pilots agree about what a Ship is. */
    fun statsOf(a: PriorityAction.PlayPermanent): Int {
        val f = a.card.faces.getOrNull(a.face) ?: a.card.faces.firstOrNull() ?: return 0
        val ch = f.baseChars ?: return 0
        return (ch.fields["hull"] ?: ch.toughness) +
            (ch.fields["fast"] ?: ch.power) + (ch.fields["slow"] ?: 0)
    }

    /** Stats per mana. Scaled by 10 so it is comparable against the small
     *  integer weights the policies already use. */
    fun rateOf(a: PriorityAction.PlayPermanent): Int {
        val cost = a.card.cost.mana.values.sum()
        return if (cost <= 0) statsOf(a) * 10 else statsOf(a) * 10 / cost
    }

    /** What this permanent can take before it dies, and what it hits for.
     *  Reads `hull` / `fast`, falling back to `toughness` / `power` -- the one
     *  game-specific leak in this file, shared with `HeuristicPilot` so the
     *  comparison has one variable. A bundle should declare which fields mean
     *  damage and endurance. */
    fun hpOf(id: ObjectId): Int =
        state.characteristicsOf(id).let { it.fields["hull"] ?: it.toughness }

    fun punchOf(id: ObjectId): Int =
        state.characteristicsOf(id).let { it.fields["fast"] ?: it.power }
}

/** WHAT A POLICY WANTS -- the only thing that differs between the three pilots.
 *  Judged by whether you can read a choice and say "that is what such a player
 *  would do", not by whether it wins (the strongest pilot answers a solved
 *  metagame, the opposite question). */
interface Policy {
    val name: String

    /** How much this policy wants to take this action now. `<= 0` means "I
     *  would rather pass", which is a real strategic position and not a
     *  degenerate one -- holding resources open IS the reactive plan. */
    fun value(a: PriorityAction, v: PolicyView): Int

    /** How much this policy wants to deploy into this lane. Consulted when more
     *  than one lane is open; the pilot always names the lane (see `withLane`). */
    fun lane(def: String, v: PolicyView): Int

    /** With an unopposed lane, go for the Station -- or hold and trade? */
    fun pressesFace(v: PolicyView): Boolean

    /** Attack into a trade that does not kill anything? */
    val tradesDown: Boolean

    /** Block everything one-for-one, or only where the block kills? */
    val blocksEagerly: Boolean
}

private fun costOf(a: PriorityAction): Int = when (a) {
    is PriorityAction.PlayPermanent -> a.card.cost.mana.values.sum()
    is PriorityAction.CastSpell -> a.cost.mana.values.sum()
    else -> 0
}

/** PROACTIVE -- tempo. Cheap bodies, early, into a lane the opponent is not
 *  holding, then straight at the Station. The clock IS the plan, so it accepts
 *  bad trades to keep the pressure on. */
object Proactive : Policy {
    override val name = "Proactive"
    override fun value(a: PriorityAction, v: PolicyView): Int = when (a) {
        // CHEAPER is better: the curve is the plan, not the card quality.
        is PriorityAction.PlayPermanent -> 100 - costOf(a) * 6
        is PriorityAction.CastSpell -> 40 - costOf(a) * 4
        is PriorityAction.Activate -> 25
        else -> 0
    }
    // An unopposed lane is Station access, and Station access is the clock.
    override fun lane(def: String, v: PolicyView): Int = if (v.theirsIn(def) == 0) 100 else 10
    override fun pressesFace(v: PolicyView): Boolean = true
    override val tradesDown = true
    override val blocksEagerly = false
}

/** REACTIVE -- answers. Effects over bodies, contest the lanes the opponent
 *  uses, turn to the Station once stabilised. Not "control": with no late game
 *  (the six-turn decision) there is no inevitability to wait for. */
object Reactive : Policy {
    override val name = "Reactive"
    override fun value(a: PriorityAction, v: PolicyView): Int = when (a) {
        // The answer is the plan. Cheap answers first, so more of them fit.
        is PriorityAction.CastSpell -> 100 - costOf(a) * 5
        is PriorityAction.Activate -> 60
        // A body is worth more when behind on board, and WHICH body is judged by
        // its rate: when Reactive deploys it takes the most it can get. (A
        // constant-minus-cost value made it refuse the top of the curve.)
        // Answers still outrank bodies (spells 80-95, bodies 25-60), which keeps
        // it distinct from Attrition.
        is PriorityAction.PlayPermanent ->
            (if (v.theirBodies() > v.myBodies()) 55 else 25) + v.rateOf(a)
        else -> 0
    }
    /** Deny the first threat, then keep a lane clear to win through. Denial is
     *  SYMMETRIC under lane-locked Station access: contesting every lane would
     *  leave no lane to attack through. */
    override fun lane(def: String, v: PolicyView): Int = when {
        v.theirsIn(def) > 0 && v.lanesIContest() == 0 -> 100
        v.theirsIn(def) > 0 -> 20
        else -> 60
    }

    /** Close once STABILISED, not once their board is empty (bodies keep
     *  arriving, so "empty" meant never). A defect fix in the policy's own
     *  terms, not a knob tuned until the win rate moved. */
    override fun pressesFace(v: PolicyView): Boolean = v.myBodies() >= v.theirBodies()
    override val tradesDown = false
    override val blocksEagerly = true
}

/** ATTRITION -- grind. The most card per card, force concentrated where it is
 *  already winning, and no attack that does not kill something. Its lane rule
 *  ("reinforce the lane I am winning") needs lanes that hold more than one body. */
object Attrition : Policy {
    override val name = "Attrition"
    override fun value(a: PriorityAction, v: PolicyView): Int = when (a) {
        // BIGGER is better: value per card is the plan, tempo is not.
        is PriorityAction.PlayPermanent -> 60 + costOf(a) * 8
        is PriorityAction.CastSpell -> 35 + costOf(a) * 4
        // A repeatable ability is the definition of grinding value.
        is PriorityAction.Activate -> 70
        else -> 0
    }
    // Local superiority: stack where I already stand. Inert at maxOccupants 1.
    override fun lane(def: String, v: PolicyView): Int =
        v.mineIn(def) * 40 + if (v.theirsIn(def) > 0) 5 else 0
    override fun pressesFace(v: PolicyView): Boolean = v.myBodies() > v.theirBodies()
    override val tradesDown = false
    override val blocksEagerly = true
}

val ALL_POLICIES: List<Policy> = listOf(Proactive, Reactive, Attrition)

/** THE SHARED SKELETON. Every pilot mechanic lives here so that no difference
 *  the matrix reports can come from anywhere but the `Policy` it was handed. */
class PolicyPilot(
    private val seat: PlayerId,
    private val rules: Rules,
    val policy: Policy,
) : PlayerInput {
    override suspend fun ask(q: Question): Answer = when (q) {
        is Question.Priority -> Answer.Act(askPriorityAction(q.player, q.state))
        is Question.PickTarget -> Answer.Target(chooseTarget(q.player, q.candidates, q.state))
        is Question.PickNumber -> Answer.Number(chooseNumber(q.player, q.label, q.min, q.max))
        is Question.Attackers -> Answer.Attackers(declareAttackers(q.player, q.eligible, q.state))
        is Question.Blockers -> Answer.Blockers(declareBlockers(q.player, q.eligibleBlockers, q.attackers, q.state))
        is Question.CombatTgt -> Answer.CombatTgt(chooseCombatTarget(q.player, q.attacker, q.range, q.state))
        is Question.PickMode -> Answer.Modes(chooseMode(q.player, q.options, q.pick, q.state))
        else -> q.default()
    }

    private suspend fun askPriorityAction(player: PlayerId, state: GameState): PriorityAction {
        if (player != seat) return PriorityAction.PassPriority
        val seen = state.asSeenBy(seat, rules)
        val options = legalActionsFor(rules, seen, player)
        if (options.isEmpty()) return PriorityAction.PassPriority
        val v = PolicyView(rules, seen, seat)
        // Ties broken by enumeration order, which is stable -- so every pilot
        // is DETERMINISTIC and a game replays exactly, same as HeuristicPilot.
        val best = options.maxByOrNull { score(it, v) } ?: return PriorityAction.PassPriority
        if (score(best, v) <= 0) return PriorityAction.PassPriority
        return withLane(best, v)
    }

    /** What this pilot thinks an action is worth: the policy's own `value`,
     *  except a REPOSITION (an ability moving this permanent to a named lane),
     *  scored by how much better the destination is under the policy's own
     *  `lane()` and refused when it is no improvement. Without that, every
     *  reposition scores the same and the fleet shuffles into lane-1. */
    private fun score(a: PriorityAction, v: PolicyView): Int {
        if (a is PriorityAction.Activate) {
            repositionGain(a, v)?.let { gain -> return if (gain > 0) gain else -1 }
            // Break ties between abilities by what they DO, not by object id
            // (enumeration order would pick whichever permanent entered first).
            // In the skeleton because every policy agrees on "bigger"; which
            // kind of thing to prefer stays in `Policy.value`.
            return policy.value(a, v) + impactOf(a, v)
        }
        return policy.value(a, v)
    }

    /** A rough size for an ability's effect -- only a tie-breaker, bounded well
     *  below a policy's own weights. The walk is `ccgui.effectImpact`, shared
     *  with mode choice; this finds the ability, binds the source and caps it. */
    private fun impactOf(a: PriorityAction.Activate, v: PolicyView): Int {
        val ability = v.state.abilitiesOf(a.source, v.rules).getOrNull(a.index) ?: return 0
        // Coerced into 0..9: `effectImpact` is signed (a sweep hitting more of my
        // ships is negative), but here the contract is a small positive
        // tie-breaker that cannot score an ability below passing.
        return effectImpact(ability.effect, EvalContext(v.state, seat, source = a.source))
            .coerceIn(0, 9)
    }

    /** Non-null iff this ability exhausts the source to move ITSELF somewhere;
     *  the value is the lane-score improvement, which may be negative. */
    private fun repositionGain(a: PriorityAction.Activate, v: PolicyView): Int? {
        val ability = v.state.abilitiesOf(a.source, v.rules).getOrNull(a.index) ?: return null
        val effect = ability.effect
        if (effect !is Effect.MovePermanent || effect.target.name != SELF) return null
        val from = v.state.battlefield[a.source]?.zone?.def ?: return null
        val to = effect.toZone.def
        if (to == from) return -1
        // BOTH endpoints are scored on a board with this permanent REMOVED:
        // "given the board without me, where do I most want to be?" -- the
        // deployment question `lane()` answers. On the live board a ship would
        // read its own presence as the threat already answered.
        val unplaced = PolicyView(rules, v.state.copy(battlefield = v.state.battlefield - a.source), seat)
        return policy.lane(to, unplaced) - policy.lane(from, unplaced)
    }

    /** Name the lane EXPLICITLY whenever there is one to name. With `zone = null`
     *  the engine takes the default lane, so the board would describe
     *  enumeration order rather than strategy. Routes through `openZonesFor`,
     *  the same narrowing the human UI uses. */
    private fun withLane(a: PriorityAction, v: PolicyView): PriorityAction {
        if (a !is PriorityAction.PlayPermanent) return a
        val open = openZonesFor(rules, v.state, seat, a) ?: return a
        if (open.isEmpty()) return a
        // Competence: never park a body where it cannot act. `combatSteps` gates
        // which waves a zone fights in and `lane()` scores occupancy only, so the
        // skeleton narrows to lanes where this ship can fight and the policy
        // picks among them. A body with no wave fields falls back to every lane.
        val fits = open.filter { def -> canActIn(a, def) }
        val afterWaves = if (fits.isEmpty()) open else fits
        // Competence: position also decides WHOM a body can reach, and `lane()`
        // scores occupancy only. Without this term range geometry measures as if
        // it did not exist.
        val best = afterWaves.maxOfOrNull { reachRank(a, it, v) }
        val choices = if (best == null) afterWaves else afterWaves.filter { reachRank(a, it, v) == best }
        // A single open zone is still named explicitly: the engine's default is
        // the first DECLARED zone, which may be the full one.
        val pick = choices.maxByOrNull { policy.lane(it, v) } ?: return a
        return a.copy(zone = pick)
    }

    /** Rank a zone by REACH ASYMMETRY (see `reachRankOf`). */
    private fun reachRank(a: PriorityAction.PlayPermanent, def: String, v: PolicyView): Int =
        reachRankOf(rules, a.card.faces.getOrNull(a.face) ?: a.card.faces.firstOrNull(), def, v)

    /** Does this body's stat line intersect the waves this lane permits? The
     *  stats each wave strikes with are read off the combat program. */
    private fun canActIn(a: PriorityAction.PlayPermanent, def: String): Boolean {
        val allowed = rules.zones[def]?.combatSteps ?: return true
        val face = a.card.faces.getOrNull(a.face) ?: a.card.faces.firstOrNull() ?: return true
        val fields = face.baseChars?.fields ?: return true
        return allowed.any { step ->
            val struck = rules.combat.fieldsStruckWith(step) ?: setOf(step)
            struck.any { fields.containsKey(it) }
        }
    }

    private suspend fun chooseTarget(player: PlayerId, candidates: List<ObjectId>, state: GameState): ObjectId {
        // Instance ids survive redaction, so a target chosen against the masked
        // state still resolves against the engine's real one -- the pilot picks
        // without learning what it picked when the zone is hidden.
        val seen = state.asSeenBy(seat, rules)
        val cands = candidates
        return cands.firstOrNull { seen.battlefield[it]?.controller != seat }
            ?: cands.firstOrNull() ?: 0
    }

    private suspend fun chooseNumber(player: PlayerId, prompt: String, min: Int, max: Int): Int = max

    /** Shared with `HeuristicPilot` so the two cannot disagree about what a
     *  mode is worth (`ModeChoice.kt`). */
    private suspend fun chooseMode(
        player: PlayerId,
        options: List<ModeOption>,
        pick: Int,
        state: GameState,
    ): List<Int> = bestModes(options, pick, state.asSeenBy(seat, rules), seat)

    private suspend fun declareAttackers(
        player: PlayerId,
        eligible: List<ObjectId>,
        state: GameState,
    ): Map<ObjectId, CombatTarget> {
        if (player != seat) return emptyMap()
        val seen = state.asSeenBy(seat, rules)
        val v = PolicyView(rules, seen, seat)
        val opp = seen.opponentOf(player)
        return eligible.filter { policy.tradesDown || canProfit(it, v) }
            .associateWith { CombatTarget.Player(opp) }
    }

    /** Is there anything this attacker can kill, or a face to hit? Shared, so
     *  "would I profit" means the same thing for all three policies -- only
     *  whether they REQUIRE profit differs. */
    private fun canProfit(attacker: ObjectId, v: PolicyView): Boolean {
        // "Is there anything worth attacking" is a question about the BODY, not
        // about one of its guns -- it gates whether the ship swings at all, and
        // a ship swings if EITHER attack has something. `null` says exactly
        // that; the per-attack question is asked in `chooseCombatTarget`.
        if (v.state.canAttackFace(rules, attacker, v.opponent, range = null) && policy.pressesFace(v)) return true
        val punch = v.punchOf(attacker)
        return combatBoardTargets(rules, v.state, seat, attacker, range = null).any { v.hpOf(it) in 1..punch }
    }

    private suspend fun chooseCombatTarget(player: PlayerId, attacker: ObjectId, range: AttackRange?, state: GameState): CombatTarget? {
        if (player != seat) return null
        val seen = state.asSeenBy(seat, rules)
        val v = PolicyView(rules, seen, seat)
        val opp = seen.opponentOf(player)
        // Going for the loss condition: the engine refuses it while the lane is
        // opposed, so ASK rather than duplicating the rule here (which is how
        // legality opinions drift).
        if (seen.canAttackFace(rules, attacker, opp, range) && policy.pressesFace(v)) return CombatTarget.Player(opp)
        val targets = combatBoardTargets(rules, seen, player, attacker, range)
        if (targets.isEmpty()) {
            return if (seen.canAttackFace(rules, attacker, opp, range)) CombatTarget.Player(opp) else null
        }
        val punch = v.punchOf(attacker)
        val killable = targets.filter { v.hpOf(it) in 1..punch }
        val pick = killable.maxByOrNull { v.hpOf(it) }
            ?: if (policy.tradesDown) targets.maxByOrNull { v.hpOf(it) } else null
        // Measured and reverted: falling through to the face when nothing is
        // killable looks strictly better and is WORSE (draws 10.8% -> 15.4%,
        // skew 4.1 -> 6.0). `pressesFace` already covers the policies that
        // should press.
        return pick?.let { CombatTarget.Obj(it) }
    }

    private suspend fun declareBlockers(
        player: PlayerId,
        eligibleBlockers: List<ObjectId>,
        attackers: List<ObjectId>,
        state: GameState,
    ): Map<ObjectId, ObjectId> {
        if (player != seat || eligibleBlockers.isEmpty() || attackers.isEmpty()) return emptyMap()
        val v = PolicyView(rules, state.asSeenBy(seat, rules), seat)
        if (policy.blocksEagerly) return eligibleBlockers.zip(attackers).toMap()
        // Otherwise block only where the block actually kills the attacker.
        return eligibleBlockers.zip(attackers)
            .filter { (b, atk) -> v.punchOf(b) >= v.hpOf(atk) }
            .toMap()
    }
}

// ---------------------------------------------------------------------------
// WHO THE OPPONENT IS.
// ---------------------------------------------------------------------------

/** The default profile: the general-purpose pilot every AI seat used before an
 *  opponent could be chosen at all. Named rather than left as a null so the
 *  picker can show it, and so a saved session says what it played against. */
const val DEFAULT_PILOT: String = "Heuristic"

/** The opponent profiles the Player offers, in picker order. Derived from
 *  `ALL_POLICIES`, so a new policy cannot be measurable yet unreachable in the
 *  app. */
val PILOT_PROFILES: List<String> = listOf(DEFAULT_PILOT) + ALL_POLICIES.map { it.name }

/** Build the pilot a profile names. An unknown name falls back to the default
 *  rather than throwing: a session restored from an older build, or one naming
 *  a policy that has since been renamed, should seat SOMEONE. */
fun pilotFor(profile: String?, seat: PlayerId, rules: Rules): PlayerInput =
    ALL_POLICIES.firstOrNull { it.name == profile }
        ?.let { PolicyPilot(seat, rules, it) }
        ?: HeuristicPilot(seat, rules)

/** Rank a zone by REACH ASYMMETRY: can this body fight from there, and can the
 *  opponent fight it there? Asked of the game's own reach: the card is
 *  stood in the zone, hypothetically, and the program says what it reaches
 *  and what reaches it. Generic -- never "prefer the back row" or "prefer
 *  lane 2", which would manufacture the result being measured.
 *
 *  +2 it can fight from here (any attack it makes reaches one of theirs)
 *  +1 on a grid: it has a line on the defending player from here
 *  +1 nothing of theirs can fight it here
 *
 *  0 uniformly when position does not gate targeting. Top-level and pure,
 *  so it is tested. */
fun reachRankOf(rules: Rules, face: ccg.Face?, def: String, v: PolicyView): Int {
    if (!rules.positionMatters()) return 0
    if (face == null) return 0
    val (s, me) = ccg.enterBattlefield(ccg.CardDefinition(faces = listOf(face)), v.seat, v.state.placed(rules), 0, rules.resolveZone(def, v.seat))
    // Their bodies standing in their own zones -- the line; what stands on the
    // shared board (a Station) is reached through the player, not the lanes.
    val theirs = s.battlefield.values
        .filter { it.controller == v.opponent && it.zone.owner != null }
        .map { it.id }
    val canHit = theirs.any { s.canReach(rules, me, it) }
    val canBeHit = theirs.any { s.canReach(rules, it, me) }
    val line = rules.zones[def]?.onGrid == true && s.canReachFace(rules, me, v.opponent)
    return (if (canHit) 2 else 0) + (if (line) 1 else 0) + (if (canBeHit) 0 else 1)
}
