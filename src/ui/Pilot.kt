package ccgui

import ccg.Answer
import ccg.AttackRange
import ccg.CardRef
import ccg.ModeOption
import ccg.CombatTarget
import ccg.GameState
import ccg.Legality
import ccg.ObjectId
import ccg.PlayerId
import ccg.PlayerInput
import ccg.PriorityAction
import ccg.Question
import ccg.default
import ccg.Rules
import ccg.legality
import ccg.legalActionsFor

// ---------------------------------------------------------------------------
// Pilots -- something that decides, for a seat, through the one seam:
// `PlayerInput`. Add a transport and you have networked play; add strategy and
// you have an opponent; the seam does not change. Here in `src/ui` because bot
// strategy is judged by running thousands of games, which `test.sh` can do.
//
// `PlayerInput` receives the full `GameState`. `PolicyPilot` redacts on entry
// (`Redaction.kt`); the pilots in this file read only public facts by
// discipline.
// ---------------------------------------------------------------------------

/** A pilot that always passes -- the honest baseline. Every strategy should be
 *  measured against it, because "beats doing nothing" is the lowest bar there
 *  is and a surprising amount of play fails to clear it. */
class PassingPilot : PlayerInput {
    /** Every question's default: pass, first candidate, minimum, no attack,
     *  and HOLD BACK in FREE combat -- a pilot must answer every question the
     *  engine can ask, not the ones it expects. */
    override suspend fun ask(q: Question): Answer = q.default()
}

/** Plays a queued opening for ONE seat (either side) and passes with everything
 *  else. It PEEKS and asks `legality()` before committing, so a refused action
 *  is not dequeued and lost; passing drains the stack and the queue continues in
 *  the next legal window. */
class ScriptedPilot(
    private val seat: PlayerId,
    opening: List<PriorityAction>,
    private val rules: Rules,
    /** Attack with everything, at the opposing player, whenever combat asks.
     *  Crude on purpose -- a real strategy is a later question, and a bad
     *  default that is DECLARED is better than one that hides. */
    private val allOut: Boolean = true,
) : PlayerInput {
    private val queue = ArrayDeque(opening)

    override suspend fun ask(q: Question): Answer = when (q) {
        is Question.Priority -> Answer.Act(priority(q.player, q.state))
        // DECLARED style: attack with everything, at the opposing player.
        is Question.Attackers -> Answer.Attackers(
            if (q.player == seat && allOut) q.eligible.associateWith { CombatTarget.Player(q.state.opponentOf(q.player)) }
            else emptyMap(),
        )
        // FREE style (EPR Skirmish's two waves): swing at the opposing player,
        // which the engine redirects onto their Station. Holding back is the
        // other seat's default.
        is Question.CombatTgt -> Answer.CombatTgt(
            if (q.player == seat && allOut) CombatTarget.Player(q.state.opponentOf(q.player)) else null,
        )
        // The first candidate for a target, the minimum for a number.
        else -> q.default()
    }

    private fun priority(player: PlayerId, state: GameState): PriorityAction {
        if (player != seat) return PriorityAction.PassPriority
        val next = queue.firstOrNull() ?: return PriorityAction.PassPriority
        if (legality(rules, state, player, next) !is Legality.Legal) return PriorityAction.PassPriority
        queue.removeFirst()
        return next
    }
}

/** Route each seat to its own pilot: the engine asks ONE `PlayerInput`, so
 *  pitting two deciders against each other needs dispatch on the seat asked. */
class SeatedPilots(
    private val bySeat: Map<PlayerId, PlayerInput>,
    private val fallback: PlayerInput = PassingPilot(),
) : PlayerInput {
    private fun of(p: PlayerId) = bySeat[p] ?: fallback

    override suspend fun ask(q: Question): Answer = of(q.player).ask(q)
    override fun seesAll(player: PlayerId): Boolean = of(player).seesAll(player)
}

/** A pilot that actually tries: a HEURISTIC, not a search -- a sparring partner
 *  whose scoring you can read in one sitting and argue with.
 *
 *  GAME-AGNOSTIC: it never names a type, faction or keyword, only structural
 *  facts (`legalActionsFor`, costs, zone room, `combatBoardTargets`). It is not
 *  redacted, but touches only the battlefield, its own actions and public zone
 *  shapes. The fixture older measurements came from. */
class HeuristicPilot(
    private val seat: PlayerId,
    private val rules: Rules,
) : PlayerInput {

    override suspend fun ask(q: Question): Answer = when (q) {
        is Question.Priority -> Answer.Act(priority(q.player, q.state))
        is Question.PickTarget -> Answer.Target(target(q.candidates, q.state))
        is Question.PickNumber -> Answer.Number(q.max)
        is Question.Attackers -> Answer.Attackers(
            if (q.player != seat) emptyMap() else q.eligible.associateWith { CombatTarget.Player(q.state.opponentOf(q.player)) },
        )
        is Question.CombatTgt -> Answer.CombatTgt(combatTarget(q.player, q.attacker, q.range, q.state))
        is Question.Blockers -> Answer.Blockers(blockers(q.player, q.eligibleBlockers, q.attackers))
        is Question.PickMode -> Answer.Modes(bestModes(q.options, q.pick, q.state, seat))
        else -> q.default()
    }

    private fun priority(player: PlayerId, state: GameState): PriorityAction {
        if (player != seat) return PriorityAction.PassPriority
        val options = legalActionsFor(rules, state, player)
        if (options.isEmpty()) return PriorityAction.PassPriority
        // Ties broken by the order the enumeration produced, which is stable --
        // so the bot is DETERMINISTIC and a game against it replays exactly.
        return options.maxByOrNull { score(it, state) } ?: PriorityAction.PassPriority
    }

    /** Why the bot does what it does: develop the board first, then spend on
     *  effects. "Bigger costs more" is the only proxy for "matters more" without
     *  knowing the game, and a badly costed card then shows as a bad choice. */
    private fun score(a: PriorityAction, state: GameState): Int = when (a) {
        is PriorityAction.PlayPermanent -> {
            val cost = a.card.cost.mana.values.sum()
            // Committing a body is the primary play, and a bigger one is
            // usually the better one at equal legality.
            40 + cost * 3
        }
        is PriorityAction.CastSpell -> {
            val cost = a.cost.mana.values.sum()
            // Effects are worth less than development to a bot that cannot
            // read what the effect DOES -- it can only see what it cost.
            20 + cost * 2
        }
        // Free value: an ability that is legal has already had its cost checked.
        is PriorityAction.Activate -> 25
        else -> 0
    }

    private fun target(candidates: List<ObjectId>, state: GameState): ObjectId {
        val cands = candidates
        // Prefer something the opponent controls; failing that, take anything
        // rather than fizzle.
        return cands.firstOrNull { state.battlefield[it]?.controller != seat }
            ?: cands.firstOrNull() ?: 0
    }

    private fun combatTarget(player: PlayerId, attacker: ObjectId, range: AttackRange?, state: GameState): CombatTarget? {
        if (player != seat) return null
        val opp = state.opponentOf(player)
        // Going for the loss condition is the plan; the engine refuses it when
        // the lane is still contested, so try it and fall back rather than
        // duplicating the rule here (which is how legality opinions drift).
        if (state.canAttackFace(rules, attacker, opp, range)) return CombatTarget.Player(opp)
        val mine = state.characteristicsOf(attacker)
        val targets = combatBoardTargets(rules, state, player, attacker, range)
        if (targets.isEmpty()) return null
        // Kill something if a kill is available, else hit the biggest thing --
        // trading up is the only edge a bot this simple can reliably find.
        val killable = targets.filter { id ->
            val c = state.characteristicsOf(id)
            val hp = c.fields["hull"] ?: c.toughness
            hp > 0 && (mine.fields["fast"] ?: mine.power) >= hp
        }
        val pick = killable.maxByOrNull { state.characteristicsOf(it).let { c -> c.fields["hull"] ?: c.toughness } }
            ?: targets.maxByOrNull { state.characteristicsOf(it).let { c -> c.fields["hull"] ?: c.toughness } }
        return pick?.let { CombatTarget.Obj(it) }
    }

    private fun blockers(player: PlayerId, eligibleBlockers: List<ObjectId>, attackers: List<ObjectId>): Map<ObjectId, ObjectId> {
        if (player != seat || eligibleBlockers.isEmpty() || attackers.isEmpty()) return emptyMap()
        // Block one-for-one in order. Crude, but a bot that never blocks loses
        // to anything, and a bot that blocks badly at least creates decisions.
        return eligibleBlockers.zip(attackers).toMap()
    }

    // Modes: the same `bestModes` as `PolicyPilot` -- the one deliberate break
    // in this pilot's fixture status, since a pilot that always picks mode 0
    // never measures a modal card's other modes. Applied in `ask`
    // (`Question.PickMode`).

}

/** What an opponent just did, in words -- the content of a "beat".
 *
 *  Null for anything not worth stopping the game over: passing is not an event,
 *  it is the absence of one. */
fun beatText(state: GameState, player: PlayerId, action: PriorityAction): String? = when (action) {
    PriorityAction.PassPriority -> null
    PriorityAction.Concede -> "$player concedes"
    is PriorityAction.CastSpell -> "$player casts ${action.label.ifBlank { "a spell" }}"
    is PriorityAction.PlayPermanent ->
        "$player plays ${action.card.faces.getOrNull(action.face)?.name ?: action.card.name}"
    is PriorityAction.Activate ->
        "$player activates ${state.battlefield[action.source]?.base?.name ?: "an ability"}"
    else -> null
}

/** Wraps another pilot and RECORDS what it decided, before the engine acts --
 *  half of what makes a response window a BEAT.
 *
 *  The waiting happens elsewhere, when the human is next asked something: the
 *  observer fires before the action is applied, so pausing here would hold a
 *  board that does not yet show it. Beats QUEUE, since a pilot may act several
 *  times while the viewer has nothing to do. */
class ObservedPilot(
    private val inner: PlayerInput,
    private val onAction: (PlayerId, PriorityAction, String) -> Unit,
) : PlayerInput {

    override suspend fun ask(q: Question): Answer {
        val answer = inner.ask(q)
        // A beat needs the action itself; a reference-only answer (a replay)
        // is reported by whatever rebuilds it, not here.
        if (q is Question.Priority) (answer as? Answer.Act)?.action?.let { action ->
            beatText(q.state, q.player, action)?.let { onAction(q.player, action, it) }
        }
        return answer
    }
}
