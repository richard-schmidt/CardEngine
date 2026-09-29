package ccg

// ---------------------------------------------------------------------------
// A PlayerInput that answers from fixed queues: the suite's scripted input,
// kept out of the core. The package stays `ccg` so tests read it as before.
// It never actually suspends: every answer is dequeued synchronously,
// so the harness can drive the suspend engine with a plain trampoline.
// ---------------------------------------------------------------------------

class ScriptedInput(
    actions: Map<PlayerId, List<PriorityAction>>,
    targets: List<ObjectId> = emptyList(),
    numbers: List<Int> = emptyList(),
    /** DECLARED combat: attacker id -> what it attacks. Returned once, filtered
     *  to whatever is actually eligible. */
    private val attacks: Map<ObjectId, CombatTarget> = emptyMap(),
    /** DECLARED combat: blocker id -> attacker id. */
    private val blocks: Map<ObjectId, ObjectId> = emptyMap(),
    /** FREE combat: one CombatTarget per acting creature, in id order per step. */
    combatTargets: List<CombatTarget> = emptyList(),
    /** INDIVIDUAL combat: attacker id -> the blocker it is redirected to. */
    private val redirects: Map<ObjectId, ObjectId> = emptyMap(),
    /** modal picks, in encounter order -- each entry is the list of
     *  chosen option indices for one `ChooseMode`. */
    modes: List<List<Int>> = emptyList(),
    /** card-instance picks for `chooseCards`, in encounter order. */
    cards: List<List<ObjectId>> = emptyList(),
) : PlayerInput {

    private val actionQueues: Map<PlayerId, ArrayDeque<PriorityAction>> =
        actions.mapValues { (_, list) -> ArrayDeque(list) }
    private val targetQueue = ArrayDeque(targets)
    private val numberQueue = ArrayDeque(numbers)
    private val combatTargetQueue = ArrayDeque(combatTargets)
    private val modeQueue = ArrayDeque(modes)
    private val cardPickQueue = ArrayDeque(cards)

    override suspend fun ask(q: Question): Answer = when (q) {
        is Question.Priority -> Answer.Act(priority(q.player, q.state))
        is Question.PickTarget -> Answer.Target(
            targetQueue.removeFirstOrNull()
                ?: error("ScriptedInput: no target queued for ${q.player} among candidates ${q.candidates}"),
        )
        is Question.PickNumber -> Answer.Number(
            numberQueue.removeFirstOrNull()
                ?: error("ScriptedInput: no number queued for ${q.player} (${q.label} in ${q.min}..${q.max})"),
        )
        is Question.Attackers -> Answer.Attackers(attacks.filterKeys { it in q.eligible })
        is Question.Blockers -> Answer.Blockers(
            blocks.filterKeys { it in q.eligibleBlockers }.filterValues { it in q.attackers },
        )
        // An exhausted queue means "hold back" rather than an error: a
        // scripted game that queues two targets for three attackers is
        // describing a third that does not swing, which is a legal thing to
        // describe.
        is Question.CombatTgt -> Answer.CombatTgt(combatTargetQueue.removeFirstOrNull())
        is Question.Redirect -> Answer.Blocker(redirects[q.attacker]?.takeIf { it in q.candidates })
        is Question.PickMode -> Answer.Modes(
            modeQueue.removeFirstOrNull()
                ?: error("ScriptedInput: no modal pick queued for ${q.player} (${q.pick} of ${q.options.size})"),
        )
        is Question.PickCards -> {
            val ids = q.candidates.map { it.instanceId }
            Answer.Cards(
                cardPickQueue.removeFirstOrNull()?.filter { it in ids }
                    ?: error("ScriptedInput: no card pick queued for ${q.player} (among $ids)"),
            )
        }
    }

    /** "P0@main" (phase-specific) wins over "P0" (general); out of actions =
     *  pass. While a permanent spell is on top of the stack this passes, so it
     *  resolves before the next scripted action ("Grunt is in play, NOW do X").
     *  Ordinary spells stack explicitly. */
    private fun priority(player: PlayerId, state: GameState): PriorityAction {
        if (state.stack.lastOrNull() is PermanentOnStack) return PriorityAction.PassPriority
        return (state.phase.takeIf { it.isNotEmpty() }?.let { actionQueues["$player@$it"]?.removeFirstOrNull() })
            ?: actionQueues[player]?.removeFirstOrNull()
            ?: PriorityAction.PassPriority
    }

    companion object {
        fun of(
            vararg perPlayer: Pair<PlayerId, List<PriorityAction>>,
            targets: List<ObjectId> = emptyList(),
            numbers: List<Int> = emptyList(),
            attacks: Map<ObjectId, CombatTarget> = emptyMap(),
            blocks: Map<ObjectId, ObjectId> = emptyMap(),
            combatTargets: List<CombatTarget> = emptyList(),
            redirects: Map<ObjectId, ObjectId> = emptyMap(),
            modes: List<List<Int>> = emptyList(),
            cards: List<List<ObjectId>> = emptyList(),
        ): ScriptedInput =
            ScriptedInput(perPlayer.toMap(), targets, numbers, attacks, blocks, combatTargets, redirects, modes, cards)
    }
}
