package ccg

// ---------------------------------------------------------------------------
// The engine: a LIFO stack, a priority rotation where any action restarts the
// round, an event queue feeding triggered abilities back onto the stack,
// replacement handlers with first refusal, and an SBA fixpoint after each
// resolution. `playGame` runs the turn loop and fires the phase/turn events.
//
// Combat is CombatResolver.kt, which re-enters this engine only through
// `CombatHost`. The core imports only the Kotlin stdlib.
//
// Nothing here suspends. The engine's code asks through a
// `Yielder` (Machine.kt): the machine (`begin` / `answer`) runs it as frames
// that stop at a question and restart from a checkpoint, so pending work is
// data in the state. `playGame` / `run` are a thin loop over that machine for
// a `PlayerInput`.
// ---------------------------------------------------------------------------



/** Replacements come only from the game: `GameState.replacements`, which a
 *  card's statics put there, and `GameState.shields`. A test states its own
 *  as `ActiveReplacement` data in the state it starts from. */
class Engine private constructor(
    input: PlayerInput,
    private val rules: Rules,
    /** Set only on the engine that runs one replacement's `instead`
     *  (`runInstead`): the chain it belongs to, and where `Proceed` answers. */
    private val insteadRun: InsteadRun?,
    /** A recorded session's rule: a question with nothing to choose
     *  (`Question.forced`) is answered with its default and never asked, so
     *  no session records an answer for one. Off for scripted and test
     *  inputs, which may act beyond what `legalActionsFor` enumerates. */
    answersForced: Boolean,
    /** A sandbox run: `Answer.Edit` is accepted at a priority
     *  question. Off for every recorded game, pilot and agent run. */
    private val sandbox: Boolean = false,
    /** Seats dealt no deck (`DeckPick.NONE`, the sandbox's empty table):
     *  drawing from their empty library is not a loss. */
    private val undecked: Set<PlayerId> = emptySet(),
    /** A sandbox bench's rule: every priority question is asked, even one
     *  with nothing but a pass -- an edit is always possible there, so none is
     *  forced (an empty table would otherwise play itself to its end). */
    private val pauses: Boolean = false,
) {
    constructor(
        input: PlayerInput, rules: Rules = Rules.DEFAULT, answersForced: Boolean = false, sandbox: Boolean = false,
        undecked: Set<PlayerId> = emptySet(), pauses: Boolean = false,
    ) : this(input, rules, null, answersForced, sandbox, undecked, pauses)

    /** Every question goes out through here: its state is the asked player's
     *  view, never the referee's. */
    private val input: PlayerInput = ViewingInput(input, rules, answersForced)

    /** The forced-question rule (`answersForced`), applied by the machine before a question stops anything. */
    private val forced: (Question) -> Boolean =
        if (answersForced) { q -> q.forced(rules) && !(pauses && q is Question.Priority) } else { _ -> false }

    // What a run did is recorded in the state it returns, when the caller
    // asked for it -- `GameState.traced()`. The mutable exceptions: `insteadRun`,
    // which lives only as long as one `instead`, and `y`, where the code
    // currently running gets its answers -- the machine sets it per frame.
    private val idle: Yielder = DirectYielder { error("the engine asked outside a run") }
    private var y: Yielder = idle

    /** A yielder that answers at once, from [asker], and runs a nested window
     *  to its end in place: a replacement's `instead`, which may not wait. */
    private inner class DirectYielder(private val asker: (Question) -> Answer) : Yielder {
        override fun ask(q: Question, checkpoint: Pair<GameState, PriorityWindow>?): Answer = asker(q)
        override fun window(s: GameState, at: TurnPoint?): GameState = runWindow(Frame.Window(s, null, at))
    }

    private companion object {
        const val MAX_STEPS = 20_000
    }

    /** Combat, which re-enters this engine only through `CombatHost`. */
    private val combat = CombatResolver(
        object : CombatHost {
            override fun settle(s: GameState) = this@Engine.settle(s)
            override fun boundary(event: GameEvent, state: GameState) = this@Engine.boundary(event, state)
            override fun window(initial: GameState) = y.window(initial, null)
            override fun ask(q: Question) = y.ask(q)
            override fun applyEvent(event: GameEvent, state: GameState, skipPrevention: Boolean) =
                this@Engine.applyEvent(event, state, skipPrevention = skipPrevention)
            override fun replaced(event: GameEvent, state: GameState) = this@Engine.replaced(event, state)
            override fun leaves(state: GameState, id: ObjectId, destination: HiddenZone) = this@Engine.leaves(state, id, destination)
            override fun interpret(effect: Effect, state: GameState, controller: PlayerId) =
                this@Engine.interpret(effect, state, controller)
        },
        rules,
    )

    // ======================================================================
    // Running a game. `begin` / `answer` are the machine; the
    // rest of this block drives it for a `PlayerInput`.
    // ======================================================================

    /** Start [state] -- or, if it is a question's state (`pending` set), pick
     *  it up at that question -- and run to the first question or the end. */
    fun begin(state: GameState, maxTurns: Int = 60): Paused =
        machine(state.pending.ifEmpty { listOf(Frame.Game(state.bare(), null, maxTurns)) }, null)

    /** [paused], a question's state, with [answer] given: run on to the next
     *  question or the end. */
    fun answer(paused: GameState, answer: Answer): Paused {
        require(paused.pending.isNotEmpty()) { "not a question's state: nothing is waiting for an answer" }
        if (answer is Answer.Edit) return edited(paused, answer.edit)
        return machine(paused.pending, Resumed.Answered(answer))
    }

    /** A sandbox edit at a priority question (Sandbox.kt): the window's
     *  checkpoint IS the question's state, so the edit goes there and the
     *  window picks up at the same question over the edited table. */
    private fun edited(paused: GameState, edit: TableEdit): Paused {
        require(sandbox) { "a table edit outside a sandbox run" }
        val top = paused.pending.last()
        require(top is Frame.Window && top.window != null && top.log.isEmpty() && paused.priority != null) {
            "a table edit only at a priority question"
        }
        val s0 = top.start
        val next = applyEdit(s0, edit)?.logged(edit.describe()) ?: s0.logged("${edit.describe()}: nothing to do")
        return machine(paused.pending.dropLast(1) + top.copy(start = next), null)
    }

    /** [edit] on [s], or null when it names nothing. Leaving play and drawing
     *  are the game's own (what watches them still sees them); the rest is
     *  list surgery (`GameState.editZones`). */
    private fun applyEdit(s: GameState, edit: TableEdit): GameState? = when {
        edit is TableEdit.Move && edit.id in s.battlefield -> edit.to.leavingTo()?.let { leaves(s, edit.id, it) }
        edit is TableEdit.Draw -> if (edit.player in s.players) drawCards(s, edit.player, edit.n).first else null
        else -> s.editZones(edit, rules)
    }

    /** The whole game, with `input` deciding. */
    suspend fun playGame(initial: GameState, maxTurns: Int = 60): GameState = drive(begin(initial, maxTurns))

    /** One free priority window from `initial`, to its end (for tests). */
    suspend fun run(initial: GameState): GameState =
        drive(machine(listOf(Frame.Window(initial.bare().placed(rules), null, null)), null))

    private suspend fun drive(first: Paused): GameState {
        var p = first
        while (true) {
            val q = p.question ?: return p.state
            p = answer(p.state, input.ask(q))
        }
    }

    private fun GameState.bare(): GameState = if (priority == null && pending.isEmpty()) this else copy(priority = null, pending = emptyList())

    /** Run [initial]'s top frame, handing it [input] first, until something
     *  asks a question or the bottom frame ends. See Machine.kt. */
    private fun machine(initial: List<Frame>, input: Resumed?): Paused =
        // Between calls the engine holds nothing: the frame's yielder
        // goes with the call.
        try { frameLoop(initial, input) } finally { y = idle }

    private fun frameLoop(initial: List<Frame>, input: Resumed?): Paused {
        val frames = initial.toMutableList()
        var next = input
        while (true) {
            var top = frames.last()
            next?.let { top = top.logged(it); frames[frames.lastIndex] = top }
            next = null
            val yr = ReplayYielder(top.log, forced)
            y = yr
            val result = try {
                when (val f = top) {
                    is Frame.Game -> runGame(f)
                    is Frame.Window -> runWindow(f)
                }
            } catch (stop: Yield) {
                when (stop) {
                    is Yield.Window -> { frames += Frame.Window(stop.state, null, stop.at); continue }
                    is Yield.Ask -> return paused(frames, stop.q, yr.seen ?: top.start)
                    is Yield.Checkpoint -> {
                        frames[frames.lastIndex] = Frame.Window(stop.state, stop.window, (top as Frame.Window).at)
                        return paused(frames, stop.q, stop.state)
                    }
                }
            }
            frames.removeAt(frames.lastIndex)
            val parent = frames.lastOrNull() ?: return Paused(result, null)
            // A window the turn opened moves the game's checkpoint past it;
            // any other window is an entry in its opener's log.
            val w = top as? Frame.Window
            if (parent is Frame.Game && w?.at != null) frames[frames.lastIndex] = parent.copy(start = result, after = w.at, log = emptyList())
            else next = Resumed.WindowClosed(result)
        }
    }

    /** A question's state carries the frames, so it is enough to go on from. */
    private fun paused(frames: List<Frame>, q: Question, shown: GameState): Paused {
        val s = (q.state ?: shown).copy(pending = frames.toList())
        return Paused(s, if (q.state != null) q.withState(s) else q)
    }

    /** A priority window's code: fresh, it settles and then loops; restarted
     *  at a question (`f.window`), it goes straight back to that question --
     *  the loop's counters come from the frame, not from locals. [Frame.Window.at]
     *  marks the turn's own window in a phase. */
    private fun runWindow(f: Frame.Window): GameState {
        val at = f.at
        val from = f.window
        val initial = f.start
        var state = if (from != null) initial else settle(initial)
        val order = state.turnOrder
        var idx = from?.holder ?: order.indexOf(state.activePlayer).coerceAtLeast(0)
        var passes = from?.passes ?: 0
        var steps = from?.steps ?: 0
        // Consecutive actions that changed nothing, by ANYONE. See the
        // no-progress rule below.
        var stalled = from?.stalled ?: 0
        // A resumed window starts AT its question: the checks before it ran
        // when it was first asked.
        var resuming = from != null
        while (true) {
            if (!resuming) {
                if (state.isOver) return state
                // An unending loop ends the game as a DRAW -- everyone loses --
                // rather than throwing out of a game in progress. A loop
                // two cards can make together is a game state, not an engine
                // fault; the log says which it was.
                if (++steps > MAX_STEPS) {
                    return state.copy(losers = state.turnOrder.toSet())
                        .logged("the game is a draw -- no end within $MAX_STEPS priority steps")
                }
            }
            resuming = false
            val player = order[idx % order.size]
            // A player who has lost is out of the game: passes, unasked.
            // No other auto-pass here: the engine cannot know what a client may offer
            // (test harnesses and the sandbox play cards that live in no zone).
            // Auto-pass belongs where the affordances are known -- the UI and
            // the pilots, via `hasAnyAction`.
            val window = PriorityWindow(idx % order.size, passes, stalled, steps, at)
            val answer = if (player in state.losers) Answer.Pass
                else y.ask(Question.Priority(player, state.copy(priority = window)), checkpoint = state to window)
            var action = answer.toAction(rules, state, player) ?: run {
                // A reference that names nothing legal here, or an answer to
                // some other question: the default, which is a pass.
                state = state.logged("$player answered $answer to priority -- a pass instead")
                PriorityAction.PassPriority
            }
            // An illegal action counts as a PASS. A `PlayerInput` that offers
            // one is buggy (both shipped ones consult `legality` first), and
            // re-asking would spin until MAX_STEPS -- progress beats politeness.
            (legality(rules, state, player, action) as? Legality.Denied)?.let {
                state = state.logged("$player: ${it.reason}")
                action = PriorityAction.PassPriority
            }
            val before = state
            var passed = false
            when (action) {
                PriorityAction.Concede -> {
                    state = state.copy(losers = state.losers + player).logged("$player concedes")
                    if (state.isOver) return state
                    passed = true
                }
                PriorityAction.PassPriority -> passed = true
                is PriorityAction.CastSpell ->
                    state = settle(castSpell(state, player, action.effect, action.spellTypes, action.cost, action.label, action.from))
                is PriorityAction.PlayPermanent ->
                    state = settle(playPermanent(state, player, action.card, action.face, action.zone, action.from))
                is PriorityAction.Attack ->
                    state = settle(attack(state, action.attacker, action.target))
                is PriorityAction.Activate ->
                    state = settle(activateAbility(state, player, action.source, action.index))
            }
            // An action that changed nothing but the log is not progress. Every
            // verb's refusal path returns `state.logged(...)`, and a log line is
            // a state change, so without this rule a refused action can be
            // offered and refused forever -- for any refusal path, including
            // ones not written yet.
            //
            // But a refusal is not simply a pass: clients may probe (attack the
            // face, get refused by a taunt, then attack the taunt). So a
            // no-progress action keeps priority until `order.size + 1` of them
            // happen consecutively, which leaves every seat one free probe. The
            // log is excluded from the comparison because refusals write to it.
            if (!passed) {
                if (state.copy(log = before.log) == before) {
                    // Counted across seats, not per player: two clients
                    // alternating refusals must still reach the threshold.
                    stalled++
                    if (stalled > order.size) { passed = true; stalled = 0 }
                } else {
                    stalled = 0
                }
            }
            if (passed) {
                passes++; idx++
                if (passes >= order.size) {
                    if (state.stack.isEmpty()) return state
                    state = settle(resolveTop(state)); passes = 0
                }
            } else {
                // Acting RETAINS priority (idx is untouched) -- unchanged. A
                // first refusal lands here too, which is what gives a client
                // its one retry.
                passes = 0
            }
        }
    }

    /** One attack (INDIVIDUAL combat): the game's attack program, run by
     *  the attacker's controller with the attack as the combat under way --
     *  and the combat that was under way before it, after. */
    private fun attack(s0: GameState, attacker: ObjectId, target: CombatTarget): GameState {
        val program = rules.attackProgram ?: return s0.logged("Attack action ignored (this game declares its attacks in combat)")
        val ap = s0.battlefield[attacker]?.controller ?: return s0
        val s = s0.copy(combat = CombatState(attacks = listOf(attacker to target)))
        return interpret(program, s, ap).copy(combat = s0.combat)
    }

    /** expire durations, then drain triggers + SBA, once (the priority loop
     *  resolves the resulting stack). */
    private fun settle(s: GameState): GameState = drainTriggers(stateBasedActions(expireDurations(s)))

    /** Drop continuous effects, delayed triggers, shields and rule/replacement
     *  statics whose `Duration` is done. Turn-relative cases fire once a later
     *  turn's first `settle` runs; `While` cases are re-checked every sweep.
     *  One pass. */
    private fun expireDurations(s: GameState): GameState {
        // Nothing bounded is in play -- the common case. Skip six filter
        // allocations on every settle.
        if (s.continuousEffects.isEmpty() && s.delayedTriggers.isEmpty() && s.shields.isEmpty() &&
            s.ruleMods.isEmpty() && s.replacements.isEmpty() && s.costMods.isEmpty() && s.controlChanges.isEmpty()
        ) {
            return s
        }
        val turn = s.turnNumber
        // A `While` reads its effect's SOURCE, so "+5/+0 while this has a
        // charge counter" can see its own counter.
        fun gone(d: Duration, start: Int, who: PlayerId, source: ObjectId?): Boolean =
            d.isExpired(start, turn, EvalContext(s, who, source = source))

        val ce = s.continuousEffects.filterNot { gone(it.duration, it.startTurn, it.controller, it.source) }
        val dt = s.delayedTriggers.filterNot { gone(it.duration, it.startTurn, it.controller, it.source) }
        val sh = s.shields.filterNot { gone(it.duration, it.startTurn, it.controller, it.source) }
        val rm = s.ruleMods.filterNot { gone(it.duration, it.startTurn, it.controller, it.source) }
        val rp = s.replacements.filterNot { gone(it.duration, it.startTurn, it.controller, it.source) }
        val cm = s.costMods.filterNot { gone(it.duration, it.startTurn, it.controller, it.source) }
        val (ended, cc) = s.controlChanges.partition { gone(it.duration, it.startTurn, it.controller, it.source) }
        if (ce.size == s.continuousEffects.size && dt.size == s.delayedTriggers.size && sh.size == s.shields.size &&
            rm.size == s.ruleMods.size && rp.size == s.replacements.size && cm.size == s.costMods.size && ended.isEmpty()
        ) {
            return s
        }
        var out = s.copy(
            continuousEffects = ce, delayedTriggers = dt, shields = sh,
            ruleMods = rm, replacements = rp, costMods = cm, controlChanges = cc,
        ).logged("durations expire (turn $turn)")
        // A borrowed permanent goes back -- if the borrower still has it.
        for (c in ended) {
            if (out.battlefield[c.permanent]?.controller == c.controller) out = out.withController(c.permanent, c.previous)
        }
        return out
    }

    // ======================================================================
    // playGame -- folds over the DECLARED turn structure (`rules.turn`).
    // Nothing about the phase set is baked in here.
    // ======================================================================

    /** The game frame's code: the whole game, or its rest from just after a
     *  window the turn opened (the frame's checkpoint). */
    private fun runGame(f: Frame.Game): GameState {
        val at = f.after ?: return turnsFrom(settle(f.start.placed(rules)), 0, f.maxTurns)
        val maxTurns = f.maxTurns
        var s = f.start
        if (s.isOver) return s
        // Then whatever followed that window in the turn.
        if (at.ap !in s.losers || at.phase == TurnPoint.TURN_BEGAN) {
            s = when {
                at.phase == TurnPoint.TURN_BEGAN -> phasesFrom(s, at, 0)
                at.stage == TurnPoint.MAIN -> phasesFrom(s, at, at.phase + 1)
                else -> phasesFrom(s, at, at.phase, afterBoundary = at.stage)
            }
            if (s.isOver) return s
        }
        return turnsFrom(endTurn(s, at.ap), at.turns + 1, maxTurns)
    }

    /** The turn loop, from the start of turn [turns0] of this run. */
    private fun turnsFrom(s0: GameState, turns0: Int, maxTurns: Int): GameState {
        var s = s0
        var turns = turns0
        while (!s.isOver && turns < maxTurns) {
            val ap = s.activePlayer
            // PER_PLAYER: `ap` takes a private turn. SHARED: one round both
            // players are in, `ap` holds only the initiative, so each phase's
            // work applies to everyone and each player gets their own
            // PhaseEnter ("at the start of your upkeep" fires for both).
            val shared = rules.turn.mode == TurnMode.SHARED
            val actors = if (shared) s.turnOrder.filter { it !in s.losers } else listOf(ap)
            // NO_PHASE, not FREE_PRIORITY: the turn-begin boundary window is
            // inside a structured turn, so timing rules still apply there.
            s = s.copy(turnNumber = turns + 1, phase = "", phaseIndex = NO_PHASE)
            // A turn's pool is a turn's pool: reset at the round boundary (under
            // SHARED every player is an actor at every phase, so the per-phase
            // clear never reaches them). `poolStoreCounter` makes the reset a
            // CAP rather than a wipe, per resource key.
            val storeKey = rules.params.poolStoreCounter
            s = s.copy(players = s.players.mapValues { (id, p) ->
                if (id !in actors || p.pool.isEmpty()) {
                    p
                } else {
                    val cap = storeKey?.let { p.counter(it) } ?: 0
                    if (cap <= 0) p.copy(pool = emptyMap())
                    else p.copy(pool = p.pool.mapValues { (_, v) -> minOf(v, cap) })
                }
            })
            // A new turn for the actors' permanents: attacks and once-a-turn
            // abilities count from zero (turn bookkeeping, not phase work).
            s = s.copy(battlefield = s.battlefield.mapValues { (_, p) ->
                if (p.controller in actors && (p.attacksThisTurn != 0 || p.activatedThisTurn.isNotEmpty()))
                    p.copy(attacksThisTurn = 0, activatedThisTurn = emptySet()) else p
            })
            for (p in actors) s = grantResources(s, p)
            val turn = TurnPoint(turns, TurnPoint.TURN_BEGAN, ap, actors)
            s = boundary(
                GameEvent.TurnBegan(ap, s.turnNumber),
                s.logged(
                    if (shared) "=== round ${turns + 1} -- $ap has the initiative ==="
                    else "=== turn ${turns + 1} -- $ap ===",
                ),
                at = turn,
            )
            if (s.isOver) return s
            s = phasesFrom(s, turn, 0)
            if (s.isOver) return s
            s = endTurn(s, ap)
            turns++
        }
        return s
    }

    /** PER_PLAYER: the turn passes. SHARED: the INITIATIVE passes. Same
     *  operation, different meaning. */
    private fun endTurn(s: GameState, ap: PlayerId): GameState =
        s.copy(activePlayer = s.nextInTurn(ap), phase = "", phaseIndex = NO_PHASE)

    /** The turn's phases from [from] on. Returns when the game is over (the
     *  caller stops there) or when the active player has lost -- a player
     *  who loses in their own turn ends it; the rest play on.
     *  [afterBoundary] resumes phase [from] after that actor's `PhaseEnter`
     *  boundary, whose window has just closed. */
    private fun phasesFrom(s0: GameState, turn: TurnPoint, from: Int, afterBoundary: Int? = null): GameState {
        var s = s0
        val ap = turn.ap
        for (i in from until rules.turn.phases.size) {
            val ph = rules.turn.phases[i]
            val resumed = if (i == from) afterBoundary else null
            if (resumed == null) {
                if (ap in s.losers) return s
                s = onPhaseEnter(s.copy(phase = ph.name, phaseIndex = i), ph, turn.actors)
            }
            for (a in (resumed?.plus(1) ?: 0) until turn.actors.size) {
                val p = turn.actors[a]
                s = boundary(GameEvent.PhaseEnter(ph.name, p), s.logged("-- $p: ${ph.name} --"), at = turn.copy(phase = i, stage = a))
                if (s.isOver || ap in s.losers) return s
            }
            if (ph.interactive || ph.combat) {
                // Under SHARED the window is an alternating action phase
                // (SWU/LoR shape): sorcerySpeedWindow does not demand the
                // active player.
                // Combat is the game's combat program, run by the active
                // player; its windows open inside it, so they are not
                // checkpoints.
                s = when {
                    !ph.combat -> y.window(s, turn.copy(phase = i, stage = TurnPoint.MAIN))
                    else -> rules.combatProgram?.let { interpret(it, s, s.activePlayer) } ?: s
                }
                if (s.isOver || ap in s.losers) return s
            }
        }
        return s
    }

    /** The declared resource model's turn-start effect. `CardDriven` grants
     *  nothing here -- its whole mechanism is the per-turn PLAY limit, which
     *  `legality` enforces; it only resets that allowance. */
    private fun grantResources(s: GameState, ap: PlayerId): GameState {
        val p = s.players[ap] ?: return s
        return when (val m = rules.resourceModel) {
            ResourceModel.None -> s
            is ResourceModel.CardDriven -> s.copy(players = s.players + (ap to p.copy(resourcePlaysUsed = 0)))
            is ResourceModel.Ramp -> {
                val grown = minOf(m.cap, p.ramp + m.perTurn)
                val filled = if (m.refillEachTurn) p.pool + (m.key to grown) else p.pool
                s.copy(players = s.players + (ap to p.copy(ramp = grown, pool = filled)))
                    .logged("$ap has $grown resource(s)")
            }
        }
    }

    private fun onPhaseEnter(s: GameState, ph: PhaseSpec, actors: List<PlayerId>): GameState {
        // Pools empty at every phase boundary, except the active player's when
        // the game declares a per-turn pool (`GameParams.poolPersistsPerTurn`,
        // also what a Ramp's refill needs to survive until spent).
        val ramp = rules.resourceModel as? ResourceModel.Ramp
        val persistsForActive = rules.params.poolPersistsPerTurn || (ramp != null && ramp.refillEachTurn)
        val out = if (s.players.values.any { it.pool.isNotEmpty() }) {
            s.copy(
                players = s.players.mapValues { (id, p) ->
                    when {
                        p.pool.isEmpty() -> p
                        persistsForActive && id in actors -> p
                        else -> p.copy(pool = emptyMap())
                    }
                },
            )
        } else {
            s
        }
        // The phase's own work, once per player it acts for, with "you"
        // = that player. Its numbers are the game's params. What it raises is
        // drained by the `PhaseEnter` boundary that follows.
        if (ph.onEnter == Effect.NoOp) return out
        val work = ph.onEnter.withParams(rules.params)
        return actors.fold(out) { acc, ap -> interpret(work, acc, ap, null) }
    }

    /** An event, its triggers, and the window they open. [at] marks a
     *  boundary of the turn itself, whose window is a checkpoint. */
    private fun boundary(event: GameEvent, state: GameState, at: TurnPoint? = null): GameState {
        var s = settle(applyEvent(event, state))
        if (s.stack.isNotEmpty()) s = y.window(s, at)
        return s
    }

    // -- casting / playing -------------------------------------------------

    private fun castSpell(
        state: GameState,
        caster: PlayerId,
        effect: Effect,
        spellTypes: Set<String>,
        cost: Cost = Cost(),
        label: String = "",
        from: CardSource? = null,
    ): GameState {
        val named = label.ifEmpty { "spell" }
        if (state.forbids(RuleAction.CAST, caster)) return state.logged("$caster can't cast $named (rule static)")
        // One X choice pays the {X} in the cost AND fills the effect's X.
        val announced = announce(
            effect, caster, state, cost = effectiveCost(state, caster, cost, spellTypes), costUsesX = cost.usesX,
        ) { bound, paid, x ->
            val xNote = x?.let { " (X=$it)" } ?: ""
            // Spend the physical card: it leaves its zone as the spell goes on the
            // stack, and comes back in `afterResolve` when the spell finishes.
            val spent = from?.let { src -> paid.cardsInCast(caster, src.zone).firstOrNull { it.instanceId == src.instanceId } }
            val afterPay = if (spent == null) paid
                else paid.removeFromCast(caster, from!!.zone, setOf(spent.instanceId))
            val (sid, s0) = afterPay.allocId()
            s0.copy(
                stack = s0.stack + SpellOnStack(
                    sid, caster, bound, label = label,
                    card = spent, goesTo = from?.afterResolve ?: HiddenZone.GRAVEYARD, types = spellTypes,
                ),
            ).logged("$caster casts $named #$sid$xNote" + (from?.let { " from ${zoneName(it.zone)}" } ?: ""))
        }
        val s1 = when (announced) {
            Announced.NoTarget -> return state.logged("$caster can't cast $named -- no legal target")
            Announced.CantPay -> return state.logged("$caster can't afford the spell")
            is Announced.Put -> announced.state
        }
        val cast = applyEvent(GameEvent.SpellCast(caster, spellTypes), s1)
        // A spell type declaring `usesStack = false` resolves at once, with no
        // response window. Pushed and immediately popped so it goes through the
        // same `resolveTop` as everything else; safe because `applyEvent` never
        // pushes (triggers drain later, in `settle`).
        return if (rules.usesStack(spellTypes)) cast else resolveTop(cast)
    }

    /** Pay `cost`, or one of its `alternatives`. CHOOSE, THEN PAY ONCE: the
     *  payable options come from `canAfford` (side-effect free, the same check
     *  `legality` uses); the controller picks if several; only that one is
     *  paid. Paying speculatively would raise events and ask questions for
     *  options not taken. If `canAfford` passes an option `payOne` refuses, the
     *  cast is refused as unaffordable, never paid twice. */
    private fun payCost(
        state: GameState,
        player: PlayerId,
        cost: Cost,
        source: ObjectId?,
        chosenX: Int = 0,
    ): GameState? {
        if (cost.alternatives.isEmpty()) return payOne(state, player, cost, source, chosenX)
        val options = listOf(cost.basic()) + cost.alternatives.map { it.basic() }
        val payable = options.indices.filter { state.canAfford(player, options[it].withX(chosenX), source) }
        val chosen = when (payable.size) {
            0 -> return null
            1 -> payable.single()
            else -> {
                // The ALTERNATIVES themselves, so the client can show what
                // each way of paying actually costs instead of "option 1".
                val (pick, note) = askModes(player, payable.map { ModeOption.OfCost(options[it]) }, 1, state)
                return payOne(state.noted(note), player, options[payable[pick.single()]], source, chosenX)
            }
        }
        return payOne(state, player, options[chosen], source, chosenX)
    }

    /** This cost with a chosen X folded into its generic mana -- the amount
     *  `payOne` will actually ask for, in the form `canAfford` can check. */
    private fun Cost.withX(x: Int): Cost =
        if (!usesX) this else copy(mana = mana + ("" to (mana[""] ?: 0) + x), usesX = false)

    /** Try to pay `cost` from `player` (and from `source` for tap/sac/counter
     *  parts). Returns the post-payment state, or null if unpayable. */
    private fun payOne(
        state: GameState,
        player: PlayerId,
        cost: Cost,
        source: ObjectId?,
        chosenX: Int = 0,
    ): GameState? {
        if (cost.isFree) return state
        var s = state
        var p = s.players.getValue(player)

        // -- mana (typed first, then generic from any leftover) --
        val due = cost.mana.toMutableMap()
        if (cost.usesX) due[""] = (due[""] ?: 0) + chosenX
        var pool = p.pool.toMutableMap()
        for ((k, amt) in due) {
            if (k.isEmpty()) continue
            if ((pool[k] ?: 0) < amt) return null
            pool[k] = (pool[k] ?: 0) - amt
        }
        val generic = due[""] ?: 0
        if (generic > 0) {
            if (pool.values.sum() < generic) return null
            var rem = generic
            // Generic mana first, then typed mana in name order -- a DECLARED
            // order, not the pool map's insertion order. Spending generic
            // first also keeps the typed mana a later cost may need.
            for (k in pool.keys.sortedWith(compareBy({ it.isNotEmpty() }, { it }))) {
                if (rem == 0) break
                val take = minOf(rem, pool.getValue(k)); pool[k] = pool.getValue(k) - take; rem -= take
            }
        }
        p = p.copy(pool = pool.filterValues { it != 0 })

        // -- life --
        if (cost.payLife > 0) {
            val dc = rules.damageCounter ?: return null
            if (p.counter(dc) < cost.payLife) return null
            p = p.addCounter(dc, -cost.payLife)
        }
        s = s.copy(players = s.players + (player to p))

        // -- a permanent pays (Cost.payFrom) --
        // The same payersFor() canAfford consulted, so "may I?" and "which
        // one?" cannot drift.
        cost.payFrom?.let { pay ->
            val payers = s.payersFor(player, pay, source)
            if (payers.isEmpty()) return null
            val chosen = if (payers.size == 1) {
                payers.first()
            } else {
                // The candidate list IS payersFor, so the player is never
                // offered a payer that would then be refused.
                val (payer, note) = askTarget(player, s.payersFor(player, pay, source), s)
                s = s.noted(note)
                payer
            }
            if ((s.battlefield[chosen]?.counter(pay.counter) ?: 0) < pay.amount) return null
            s = mutateCounter(s, chosen, pay.counter, -pay.amount)
                .logged("$player pays ${pay.amount} ${pay.counter} from #$chosen")
        }

        // -- source: tap / remove counters / sacrifice --
        if (source != null) {
            val perm = s.battlefield[source] ?: return null
            if (cost.tapSource) {
                if (perm.exhausted) return null
                s = s.copy(battlefield = s.battlefield + (source to perm.copy(exhausted = true)))
            }
            cost.removeCounters?.let { (kind, n) ->
                if ((s.battlefield[source]?.counter(kind) ?: 0) < n) return null
                s = mutateCounter(s, source, kind, -n)
            }
        }

        // -- an additional effect (sacrifice / discard); SELF -> source --
        // Checked against the state as it is NOW: earlier parts of this payment
        // may have removed the permanent to be sacrificed. Null discards every
        // payment above; the caller treats it as unpayable.
        cost.additional?.let { add ->
            val bound = if (source != null) add.bindSelf(source) else add
            if (!s.additionalPayable(player, bound, source)) return null
            s = interpret(bound, s, player, source)
        }
        if (cost.sacrificeSource && source != null && source in s.battlefield) {
            s = leaves(s.logged("$player sacrifices #$source (cost)"), source, HiddenZone.GRAVEYARD)
        }
        return s
    }

    private fun activateAbility(state: GameState, player: PlayerId, source: ObjectId, index: Int): GameState {
        val perm = state.battlefield[source] ?: return state.logged("activate: #$source is gone")
        if (perm.controller != player) return state.logged("activate: #$source isn't $player's")
        val derived = state.characteristicsOf(source)
        if (derived.abilitiesRemoved) return state.logged("activate: #$source has lost its abilities")
        // Printed abilities first, then the ones a static GRANTED --
        // `granted` is a derived characteristic, so layers order them.
        val ability = state.abilitiesOf(source, rules).getOrNull(index)
            ?: return state.logged("activate: #$source has no ability #$index")
        if (ability.oncePerTurn && index in perm.activatedThisTurn) {
            return state.logged("activate: #$source already used ability #$index this turn")
        }
        if (state.activateBarred(source)) {
            return state.logged("activate: #$source can't activate abilities (rule static)")
        }
        val note = "$player activates #$source ability #$index" + (ability.name.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: "")
        val announced = announce(
            ability.effect.bindSelf(source), player, state, source,
            cost = effectiveCost(state, player, ability.cost, rules.faceOf(perm)?.types ?: derived.types), costUsesX = ability.cost.usesX,
        ) { bound, paid, _ ->
            val s = paid.battlefield[source]?.let {
                paid.copy(battlefield = paid.battlefield + (source to it.copy(activatedThisTurn = it.activatedThisTurn + index)))
            } ?: paid
            // Resolves AT ONCE, with no response window: a mana ability (see
            // `isManaAbility` -- this behaviour hard-coded for the one case that
            // always needs it), or an ability whose author declared it that
            // way. One branch for both, because they are the same rule.
            if (isManaAbility(bound) || !ability.usesStack) {
                interpret(bound, s.logged(note), player, source)
            } else {
                val (aid, s2) = s.allocId()
                s2.copy(
                    stack = s2.stack + TriggeredAbilityOnStack(aid, player, bound, GameEvent.SpellCast(player, emptySet()), source = source),
                ).logged(note)
            }
        }
        return when (announced) {
            Announced.NoTarget -> state.logged("activate: #$source ability #$index has no legal target")
            Announced.CantPay -> state.logged("$player can't pay for #$source ability #$index")
            is Announced.Put -> announced.state
        }
    }

    // ======================================================================
    // Answers. Whatever a `PlayerInput` returns is checked HERE before use:
    // answers are what replay feeds back against edited content,
    // what the agent sends, and what a port produces. An invalid answer
    // becomes a DETERMINISTIC DEFAULT plus a log line, never a re-ask (a
    // re-ask can spin). Each helper returns the answer and the note to log
    // (null when the answer was fine).
    // ======================================================================

    /** One of `candidates`, which the caller has checked is non-empty.
     *  Default: the first candidate. The question IS the list: the same one
     *  the player is shown is the one the answer is checked against. */
    private fun askTarget(player: PlayerId, candidates: List<ObjectId>, state: GameState): Pair<ObjectId, String?> {
        val answer = (y.ask(Question.PickTarget(player, candidates, state)) as? Answer.Target)?.id
        if (answer != null && answer in candidates) return answer to null
        val fallback = candidates.first()
        return fallback to "$player answered #$answer, which is not a legal choice -- #$fallback instead"
    }

    /** A number in `min..max`. Default: the answer clamped into range. */
    private fun askNumber(player: PlayerId, prompt: String, min: Int, max: Int): Pair<Int, String?> {
        val answer = (y.ask(Question.PickNumber(player, prompt, min, max)) as? Answer.Number)?.n
        if (answer != null && answer in min..max) return answer to null
        val fallback = (answer ?: min).coerceIn(min, max)
        return fallback to "$player answered $prompt=$answer, outside $min..$max -- $fallback instead"
    }

    /** Exactly `pick` distinct indices into `options` (`pick` clamped to what
     *  there is). Default: the first `pick`. */
    private fun askModes(
        player: PlayerId, options: List<ModeOption>, pick: Int, state: GameState,
    ): Pair<List<Int>, String?> {
        val n = pick.coerceIn(0, options.size)
        if (n == 0) return emptyList<Int>() to null
        val answer = (y.ask(Question.PickMode(player, options, n, state)) as? Answer.Modes)?.picks
        if (answer != null && answer.size == n && answer.distinct().size == n && answer.all { it in options.indices }) return answer to null
        val fallback = (0 until n).toList()
        return fallback to "$player answered modes $answer for pick $n of ${options.size} -- $fallback instead"
    }

    /** Put `cards`, already taken out of `from`, onto the battlefield under
     *  `pid`: each into its own type's zone of play, respecting capacity, and
     *  back into `from` when there is no room. */
    private fun intoPlay(state: GameState, pid: PlayerId, cards: List<CardRef>, from: HiddenZone): GameState {
        var s = state
        for (ref in cards) {
            val def = rules.cards[ref.cardId]
            if (def == null) {
                s = s.addTo(pid, from, listOf(ref)).logged("$pid: no def for '${ref.cardId}' -- can't put it onto the battlefield")
                continue
            }
            val zoneDef = rules.defaultZoneDef(def.types)
            if (!rules.zoneHasRoom(s, pid, zoneDef)) {
                s = s.addTo(pid, from, listOf(ref)).logged("$pid can't put ${def.name} onto the battlefield -- $zoneDef is full")
                continue
            }
            val zone = rules.resolveZone(zoneDef, pid)
            val (s2, id) = enterBattlefield(def, pid, s, zone = zone)
            s = applyEvent(GameEvent.EntersPlay(id, pid, zone), s2.logged("$pid puts ${def.name} #$id onto the battlefield from ${zoneName(from)}"))
        }
        return s
    }

    /** Distinct cards from `candidates`: exactly `count`, or at most `count`
     *  when `atMost`. Default: the first `count`. */
    private fun askCards(
        player: PlayerId, candidates: List<CardRef>, count: Int, state: GameState, atMost: Boolean = false,
    ): Pair<Set<ObjectId>, String?> {
        val n = count.coerceIn(0, candidates.size)
        val answer = (y.ask(Question.PickCards(player, candidates, n, state, atMost)) as? Answer.Cards)?.ids
        val ids = candidates.map { it.instanceId }.toSet()
        val sizeOk = answer != null && if (atMost) answer.size <= n else answer.size == n
        if (answer != null && sizeOk && answer.distinct().size == answer.size && answer.all { it in ids }) return answer.toSet() to null
        val fallback = candidates.take(n).map { it.instanceId }
        return fallback.toSet() to "$player answered cards $answer (want ${if (atMost) "at most " else ""}$n of the offered) -- $fallback instead"
    }

    private fun GameState.noted(note: String?): GameState = if (note == null) this else logged(note)

    /** How announcing went. `Put`: the state after the effect was put where
     *  it goes. Otherwise the action is refused, and nothing was paid. */
    private sealed interface Announced {
        data object NoTarget : Announced
        data object CantPay : Announced
        data class Put(val state: GameState) : Announced
    }

    /** THE announcement -- a spell cast, an ability activated, a trigger put on
     *  the stack -- in rules order: MODES, then X, then TARGETS, then the COST,
     *  then `put`, which places the bound effect and receives the paid state and
     *  X (null when none was chosen). Modes come first because the mode decides
     *  whether there is an X; X before targets because a filter may read it.
     *  A required target or mode with nothing to bind refuses before anything is
     *  paid. `cost` null = nothing to pay; `choosesX = false` for a trigger. */
    private fun announce(
        effect: Effect, chooser: PlayerId, state: GameState, source: ObjectId? = null,
        cost: Cost? = null, costUsesX: Boolean = false, choosesX: Boolean = true,
        put: (bound: Effect, paid: GameState, x: Int?) -> GameState,
    ): Announced {
        var (e, s) = bindModes(effect, chooser, state, source) ?: return Announced.NoTarget
        val askX = choosesX && (costUsesX || e.usesX())
        var x = 0
        if (askX) {
            val (n, note) = askNumber(chooser, "X", 0, MAX_X)
            x = n
            s = s.noted(note)
            if (e.usesX()) e = e.substituteX(x)
        }
        val (bound, s2) = bindChoices(e, chooser, s, source) ?: return Announced.NoTarget
        val paid = if (cost == null) s2 else payCost(s2, chooser, cost, source, x) ?: return Announced.CantPay
        return Announced.Put(put(bound, paid, x.takeIf { askX }))
    }

    /** Bind every `ChooseMode` in a leading position -- the positions
     *  `bindChoices` walks, minus the bodies of `Choose`/`ChooseMany` (those
     *  bind after their target). Only options that can bind their own targets
     *  are offered, and the announcement fails when too few remain -- the same
     *  test `canBindChoices` applies, so legality never offers what this refuses. */
    private fun bindModes(
        effect: Effect, chooser: PlayerId, state: GameState, source: ObjectId? = null,
    ): Pair<Effect, GameState>? = when (effect) {
        is Effect.ChooseMode -> {
            val open = effect.options.filter { canBindChoices(it, chooser, state, source) }
            val want = effect.pick.eval(EvalContext(state, chooser, source)).coerceIn(0, effect.options.size)
            if (open.size < want) {
                null
            } else {
                val (picks, note) = askModes(chooser, open.map { ModeOption.OfEffect(it) }, want, state)
                var s = state.noted(note)
                val chosen = picks.map { i ->
                    val (b, s2) = bindModes(open[i], chooser, s, source) ?: return null
                    s = s2
                    b
                }
                (chosen.singleOrNull() ?: Effect.Sequence(chosen)) to s
            }
        }
        is Effect.Sequence -> {
            var s = state
            val steps = effect.steps.map { step ->
                val (b, s2) = bindModes(step, chooser, s, source) ?: return null
                s = s2
                b
            }
            Effect.Sequence(steps) to s
        }
        // Lenient, as in `bindChoices`: a branch that can't bind keeps its
        // `ChooseMode`, which is then asked if and when the branch runs.
        is Effect.If -> {
            val then = bindModes(effect.then, chooser, state, source)
            val otherwise = bindModes(effect.otherwise, chooser, then?.second ?: state, source)
            Effect.If(effect.cond, then?.first ?: effect.then, otherwise?.first ?: effect.otherwise) to
                (otherwise?.second ?: then?.second ?: state)
        }
        else -> effect to state
    }

    /** Resolve a leading `Choose` (and any inside a leading `Sequence`) at cast
     *  time so the stack holds a choice-free tree. `null` when a required
     *  `Choose` has no legal target: the cast is illegal. An `If`'s branches
     *  stay lenient and bind when they run. */
    private fun bindChoices(
        effect: Effect, chooser: PlayerId, state: GameState, source: ObjectId? = null,
    ): Pair<Effect, GameState>? =
        // Returns the state too, but only ever to carry the log line of an
        // answer `askTarget` had to correct.
        when (effect) {
            is Effect.Choose -> {
                // `chooseCandidates` is the one definition of "who could be
                // picked", shared with `legality` -- so an action cannot be
                // offered and then declined for want of a target.
                val candidates = chooseCandidates(effect.filter, chooser, state, source)
                if (candidates.isEmpty()) {
                    null
                } else {
                    val (chosen, note) = askTarget(chooser, candidates, state)
                    bindChoices(effect.body.substituteTarget(effect.binds, chosen), chooser, state.noted(note), source)
                }
            }
            is Effect.Sequence -> {
                var s = state
                val steps = effect.steps.map { step ->
                    val (b, s2) = bindChoices(step, chooser, s, source) ?: return null
                    s = s2
                    b
                }
                Effect.Sequence(steps) to s
            }
            is Effect.If -> {
                val then = bindChoices(effect.then, chooser, state, source)
                val otherwise = bindChoices(effect.otherwise, chooser, then?.second ?: state, source)
                Effect.If(effect.cond, then?.first ?: effect.then, otherwise?.first ?: effect.otherwise) to
                    (otherwise?.second ?: then?.second ?: state)
            }
            // Counter TARGET spell: which one is picked now, like any target.
            is Effect.CounterSpell -> if (effect.target != null) effect to state else {
                val candidates = counterCandidates(effect, chooser, state, source)
                if (candidates.isEmpty()) null
                else askTarget(chooser, candidates, state).let { (id, note) -> effect.copy(target = BoundTarget(id)) to state.noted(note) }
            }
            // A mode inside a target's body: asked now the target is known.
            is Effect.ChooseMode -> {
                val (moded, s) = bindModes(effect, chooser, state, source) ?: return null
                bindChoices(moded, chooser, s, source)
            }
            // Every target and every share is chosen now, and the stack holds
            // one bound body per target. Each body binds leniently: a
            // nested choice that finds nothing is left to resolution, as a
            // branch of an `If` is, so one target's body can't sink the rest.
            is Effect.ChooseMany -> {
                val ctx = EvalContext(state, chooser, source)
                val want = effect.count.eval(ctx).coerceAtLeast(0)
                val candidates = chooseCandidates(effect.filter, chooser, state, source)
                if (!effect.upTo && candidates.size < want) {
                    null
                } else {
                    var s = state
                    val chosen = mutableListOf<ObjectId>()
                    repeat(minOf(want, candidates.size)) {
                        val (c, note) = askTarget(chooser, candidates.filterNot { it in chosen }, s)
                        chosen += c
                        s = s.noted(note)
                    }
                    // The last target takes the remainder, so the total is
                    // always honoured.
                    val shares: List<Int> = effect.divide?.let { total ->
                        var rem = total.eval(ctx).coerceAtLeast(0)
                        chosen.mapIndexed { i, _ ->
                            if (i == chosen.lastIndex) rem
                            else askNumber(chooser, "share", 0, rem).let { (n, note) -> s = s.noted(note); rem -= n; n }
                        }
                    } ?: emptyList()
                    val bodies = chosen.mapIndexed { i, id ->
                        var body = effect.body.substituteTarget(effect.binds, id)
                        if (shares.isNotEmpty()) body = body.substituteShare(shares[i])
                        bindChoices(body, chooser, s, source)?.let { (b, s2) -> s = s2; b } ?: body
                    }
                    Effect.Sequence(bodies) to s
                }
            }
            else -> effect to state
        }

    /** Pay for a permanent and put it ON THE STACK so opponents get a window. A
     *  type with `usesStack = false` (Land) enters at once as a special action.
     *  The id is allocated here, so it is one identity from cast to battlefield. */
    private fun playPermanent(
        state: GameState,
        controller: PlayerId,
        card: CardDefinition,
        face: Int,
        zoneDef: String? = null,
        from: CardSource? = null,
    ): GameState {
        val f = card.faces.getOrElse(face) { card.faces[0] }
        var statePaid = payCost(state, controller, effectiveCost(state, controller, card.cost, f.types), source = null)
            ?: return state.logged("$controller can't afford ${f.name}")
        // The card leaves hand (or a declared zone) to become the permanent.
        if (from != null) statePaid = statePaid.removeFromCast(controller, from.zone, setOf(from.instanceId))
        if (rules.resourceModel.limitsPlayOf(f.types)) {
            statePaid.players[controller]?.let { pl ->
                statePaid = statePaid.copy(
                    players = statePaid.players + (controller to pl.copy(resourcePlaysUsed = pl.resourcePlaysUsed + 1)),
                )
            }
        }
        // An unspecified zone resolves to one with room, not blindly to the
        // default: `legality()` only guarantees SOME legal zone has room.
        val zone = rules.resolveZone(rules.openZoneFor(statePaid, controller, f.types, zoneDef), controller)
        val (id, s0) = statePaid.allocId()
        val onStack = PermanentOnStack(id, controller, card, face, zone, f.name)
        if (!rules.usesStack(f.types)) return resolvePermanent(s0, onStack)
        val s1 = s0.copy(stack = s0.stack + onStack).logged("$controller casts ${f.name} #$id")
        return applyEvent(GameEvent.SpellCast(controller, f.types), s1)
    }

    /** A permanent spell resolves: it enters play. */
    private fun resolvePermanent(state: GameState, top: PermanentOnStack): GameState {
        val f = top.card.faces.getOrElse(top.face) { top.card.faces[0] }
        val (s0, id) = enterBattlefield(top.card, top.controller, state, top.face, top.zone, reuseId = top.id)
        var s = applyEvent(
            GameEvent.EntersPlay(id, top.controller, top.zone),
            s0.logged("${top.controller} plays ${f.name} #$id" + if (top.zone != BATTLEFIELD) " -> ${top.zone}" else ""),
        )
        // Counters placed as it entered count as "added" -- a Saga's chapter I,
        // an "enters with N +1/+1", etc. fire off these.
        for (cd in top.card.entersWith) {
            val amt = s.battlefield[id]?.counter(cd.kind) ?: 0
            if (amt > 0) s = applyEvent(GameEvent.CounterChanged(id, cd.kind, 0, amt), s)
        }
        return s
    }

    // -- resolution ------------------------------------------------------

    private fun resolveTop(state: GameState): GameState {
        val top = state.stack.lastOrNull() ?: return state
        // Pop BEFORE interpreting, so a resolving spell cannot see itself.
        val popped = state.copy(stack = state.stack.dropLast(1))
        if (top is PermanentOnStack) return resolvePermanent(popped, top)
        val tag = (top as? SpellOnStack)?.label?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""
        var s = interpret(top.effect, popped, top.controller, top.source).logged("resolved #${top.id}$tag")
        // A spell cast off a real card puts that card away as it finishes.
        val spell = top as? SpellOnStack
        if (spell?.card != null) {
            s = s.addTo(spell.controller, spell.goesTo, listOf(spell.card))
                .logged("${spell.label.ifEmpty { "the card" }} -> ${zoneName(spell.goesTo)}")
        }
        return s
    }

    private fun interpret(
        effect: Effect,
        state: GameState,
        controller: PlayerId,
        source: ObjectId? = null,
        /** The opponent this run of an `EachOpponent` verb is for. */
        each: PlayerId? = null,
    ): GameState {
        // A verb acting on each opponent runs once per opponent, in turn order,
        // with that one bound; "you" is still the controller throughout.
        if (each == null && effect.actsOn() == PlayerRef.EachOpponent) {
            var s = state
            for (o in state.opponentsOf(controller)) s = interpret(effect, s, controller, source, each = o)
            return s
        }
        // Which verbs ran, when the caller asked (`traced()`).
        @Suppress("NAME_SHADOWING") val state = state.recordEffect(effect)
        val ctx = EvalContext(state, controller, source, each = each)
        // A verb's amount is never negative: read as-is, "deal -3" would heal
        // and "put -2 counters" would remove them. Only `GainLife` stays signed, because "gain -3" is how
        // an author writes "lose 3".
        fun amount(e: IntExpr) = e.eval(ctx).coerceAtLeast(0)
        // A PlayerRef that names nobody here -- the effect does nothing.
        fun nobody() = state.logged("$controller: no such player -- that effect does nothing")
        return when (effect) {
            is Effect.DealDamage -> when (val tgt = effect.target?.id) {
                // A player. Routed through the event so a shield can
                // absorb it and a trigger can see it; the loss itself is the
                // event's default consequence -- applied AFTER shields and
                // replacements, never before.
                null -> {
                    val pid = ctx.playerId(effect.player!!) ?: return nobody()
                    applyEvent(GameEvent.PlayerDamaged(pid, amount(effect.amount), combat = false, source = source), state)
                }
                !in state.battlefield -> state.logged("fizzle: target #$tgt is gone")
                else -> applyEvent(GameEvent.DamageDealt(tgt, amount(effect.amount), source = source), state)
            }
            // Lowered away by compile(); see Lower.kt.
            is Effect.DamageOpponent, is Effect.ReturnFromDiscard -> throw SurfaceFormReached(effect)
            is Effect.Draw -> drawCards(state, ctx.playerId(effect.who) ?: return nobody(), effect.count.eval(ctx)).first
            is Effect.GainLife -> {
                val pid = ctx.playerId(effect.who) ?: return nobody()
                if (state.forbids(RuleAction.GAIN_LIFE, pid)) state.logged("$pid can't gain life (rule static)")
                else {
                    val p = state.players.getValue(pid)
                    val n = effect.amount.eval(ctx)
                    state.copy(players = state.players + (pid to p.addCounter(effect.counter, n)))
                        .logged("$pid gains $n ${effect.counter}")
                }
            }
            is Effect.AddMana -> {
                val pid = ctx.playerId(effect.who) ?: return nobody()
                val p = state.players.getValue(pid)
                val amounts = effect.mana.mapValues { (_, e) -> e.eval(ctx).coerceAtLeast(0) }
                val np = amounts.entries.fold(p.pool) { acc, (k, v) -> acc + (k to (acc[k] ?: 0) + v) }
                state.copy(players = state.players + (pid to p.copy(pool = np)))
                    .logged("$pid adds ${amounts.entries.joinToString("+") { "${it.value}${it.key.ifEmpty { "*" }}" }}")
            }
            is Effect.Destroy ->
                if (effect.target.id in state.battlefield) leaves(state, effect.target.id, HiddenZone.GRAVEYARD) else state
            is Effect.AddCounter -> mutateCounter(state, effect.target.id, effect.kind, amount(effect.count))
            is Effect.RemoveCounter -> mutateCounter(state, effect.target.id, effect.kind, -amount(effect.count))
            is Effect.Transform -> transform(state, effect.target.id)
            is Effect.CreateToken -> {
                var s = state
                // Capacity applies to every path that places a permanent, not
                // just PlayPermanent: tokens that do not fit are not made
                // (logged), and the rest use whatever room is left.
                repeat(effect.count.eval(ctx).coerceAtLeast(0)) {
                    // Resolved PER TOKEN, and through the shared decision, so
                    // each one finds its own open lane instead of all of them
                    // piling at the first declared zone and being declined.
                    val zoneDef = rules.openZoneFor(s, controller, effect.chars.types, effect.zone)
                    val zone = rules.resolveZone(zoneDef, controller)
                    if (!rules.zoneHasRoom(s, controller, zoneDef)) {
                        s = s.logged("$controller can't create another ${effect.chars.name} -- no room left")
                        return@repeat
                    }
                    val (tid, s1) = s.allocId()
                    // A token has no `entersWith`, so its printed damage-counter
                    // field seeds the counter; otherwise it would be born at 0
                    // and die to its own `diesWhen`.
                    val tokenCounters = rules.damageCounterFor(effect.chars.types)
                        ?.let { k -> effect.chars.fields[k]?.let { mapOf(k to it) } }
                        ?: emptyMap()
                    s = s1.copy(
                        battlefield = s1.battlefield +
                            (tid to Permanent(tid, controller, effect.chars, counters = tokenCounters, zone = zone, isToken = true)),
                    ).logged("$controller creates token #$tid (${effect.chars.name})")
                    s = applyEvent(GameEvent.EntersPlay(tid, controller, zone), s)
                }
                s
            }
            is Effect.CopyOf -> {
                val src = state.battlefield[effect.target.id] ?: return state
                val (tid, s1) = state.allocId()
                var s = s1.copy(
                    // A copy of an attached permanent enters attached to the
                    // same host (as a copied Aura does), so its onlyHost()
                    // statics still apply.
                    battlefield = s1.battlefield + (tid to Permanent(tid, controller, src.base, face = src.face, cardId = src.cardId, isToken = true, hostId = src.hostId)),
                ).logged("$controller creates a token copy of #${effect.target.id} -> #$tid")
                rules.faceOf(src)?.let { face -> s = s.wireFace(face, tid, controller) }
                applyEvent(GameEvent.EntersPlay(tid, controller, src.zone), s)
            }
            is Effect.CreateEmblem -> {
                // A synthetic negative id: never on the battlefield, so its
                // compiled statics are never pruned by leave-play or duration.
                val eid = -(state.nextObjectId)
                val s = state.copy(nextObjectId = state.nextObjectId + 1)
                wireStatics(s, effect.statics, eid, controller).logged("$controller gets an emblem (#$eid)")
            }
            is Effect.ChooseMode -> {
                val (picks, note) = askModes(controller, effect.options.map { ModeOption.OfEffect(it) }, effect.pick.eval(ctx), state)
                var s = state.noted(note)
                for (i in picks) effect.options.getOrNull(i)?.let { s = interpret(it, s, controller, source) }
                s
            }
            is Effect.Discard -> {
                val pid = ctx.playerId(effect.who) ?: return nobody()
                val p = state.players.getValue(pid)
                val n = effect.count.eval(ctx).coerceAtMost(p.hand.size)
                if (n <= 0) return state
                val (chosen, note) = askCards(pid, p.hand, n, state)
                val kept = p.hand.filterNot { it.instanceId in chosen }
                val moved = p.hand.filter { it.instanceId in chosen }
                state.noted(note).copy(players = state.players + (pid to p.copy(hand = kept)))
                    .addTo(pid, effect.toZone, moved)
                    .logged("$pid discards ${moved.size} to ${zoneName(effect.toZone)}")
            }
            is Effect.DrawThenDiscard -> {
                val pid = ctx.playerId(effect.who) ?: return nobody()
                val (afterDraw, drawn) = drawCards(state, pid, effect.draw.eval(ctx))
                if (pid in afterDraw.losers) return afterDraw
                val d = effect.discard.eval(ctx).coerceAtMost(drawn.size)
                if (d <= 0) return afterDraw
                // Candidates are ONLY the just-drawn refs -- the whole point:
                // a card already in hand before this effect ran is never a
                // valid choice here, unlike a plain Discard after a Draw.
                val (chosen, note) = askCards(pid, drawn, d, afterDraw)
                val p = afterDraw.players.getValue(pid)
                val kept = p.hand.filterNot { it.instanceId in chosen }
                val moved = p.hand.filter { it.instanceId in chosen }
                afterDraw.noted(note).copy(players = afterDraw.players + (pid to p.copy(hand = kept)))
                    .addTo(pid, effect.toZone, moved)
                    .logged("$pid sends $d of the ${drawn.size} just drawn to ${zoneName(effect.toZone)}")
            }
            is Effect.SearchZone -> {
                val pid = ctx.playerId(effect.who) ?: return nobody()
                val pool = state.cardsIn(pid, effect.from).filter { rules.cardMatches(effect.filter, it) }
                val n = effect.count.eval(ctx).coerceAtMost(pool.size)
                if (n <= 0) {
                    val looked = state.logged("$pid searches ${zoneName(effect.from)} -- nothing matches")
                    if (effect.thenShuffle) looked.shuffleZone(pid, effect.from) else looked
                } else {
                    val (picked, note) = askCards(pid, pool, n, state, atMost = effect.upTo)
                    val moved = pool.filter { it.instanceId in picked }
                    val taken = state.noted(note).removeFrom(pid, effect.from, moved.map { it.instanceId }.toSet())
                    val after = if (effect.intoPlay) intoPlay(taken, pid, moved, effect.from)
                        else taken.addTo(pid, effect.to, moved)
                            .logged("$pid searches ${zoneName(effect.from)} -> ${moved.size} to ${zoneName(effect.to)}")
                    if (effect.thenShuffle) after.shuffleZone(pid, effect.from) else after
                }
            }
            is Effect.Shuffle -> {
                val pid = ctx.playerId(effect.who) ?: return nobody()
                state.shuffleZone(pid, effect.zone).logged("$pid shuffles ${zoneName(effect.zone)}")
            }
            is Effect.MoveTop -> {
                val pid = ctx.playerId(effect.who) ?: return nobody()
                val lib = state.cardsIn(pid, HiddenZone.LIBRARY)
                val moved = lib.take(effect.count.eval(ctx).coerceAtLeast(0))
                if (moved.isEmpty()) {
                    state
                } else {
                    state.removeFrom(pid, HiddenZone.LIBRARY, moved.map { it.instanceId }.toSet())
                        .addTo(pid, effect.to, moved)
                        .logged("$pid moves ${moved.size} from the top of the library to ${zoneName(effect.to)}")
                }
            }
            is Effect.LookAtTop -> {
                val pid = ctx.playerId(effect.who) ?: return nobody()
                val looked = state.cardsIn(pid, HiddenZone.LIBRARY).take(effect.count.eval(ctx).coerceAtLeast(0))
                if (looked.isEmpty()) {
                    state
                } else {
                    // "any number of them" -- atMost, so the seat may keep all.
                    val (picked, note) = askCards(pid, looked, looked.size, state, atMost = true)
                    val moved = looked.filter { it.instanceId in picked }
                    state.noted(note).removeFrom(pid, HiddenZone.LIBRARY, moved.map { it.instanceId }.toSet())
                        .addTo(pid, effect.to, moved)
                        .logged("$pid looks at ${looked.size} -> ${moved.size} to ${zoneName(effect.to)}")
                }
            }
            is Effect.MovePermanent -> {
                val perm = state.battlefield[effect.target.id]
                // A card cannot know whose lane it means, so an owner-less
                // `ZoneRef` resolves against the moving permanent's own
                // controller (otherwise it renders nowhere and bypasses caps).
                val dest = if (perm != null && effect.toZone.owner == null) {
                    rules.resolveZone(effect.toZone.def, perm.controller)
                } else {
                    effect.toZone
                }
                when {
                    perm == null || perm.zone == dest -> state
                    // Moving respects capacity, like every placement path.
                    !rules.zoneHasRoom(state, perm.controller, dest) ->
                        state.logged("#${perm.id} can't move to $dest -- it's full")
                    else -> applyEvent(
                        GameEvent.MovesZone(perm.id, perm.controller, perm.zone, dest),
                        state.copy(battlefield = state.battlefield + (perm.id to perm.copy(zone = dest)))
                            .logged("#${perm.id} moves ${perm.zone} -> $dest"),
                    )
                }
            }
            is Effect.ApplyModifier -> {
                // Snapshot the affected set now -- a one-shot pump locks its
                // targets, unlike a static ability's live filter... Burns an id
                // for a unique, monotonic timestamp + a stable `source` handle.
                val snapshot = state.inPlayIds.filter { effect.filter.matches(ctx, it) }.toSet()
                val (mid, s) = state.allocId()
                // ...and its values: see `CharOp.locked`.
                val ces = effect.ops.map { it.locked(ctx) }.map { op ->
                    ContinuousEffect(
                        source = source ?: mid,
                        controller = controller,
                        layer = effect.layer ?: op.defaultLayer,
                        timestamp = mid.toLong(),
                        op = op,
                        affected = snapshot,
                        duration = effect.duration,
                        startTurn = s.turnNumber,
                    )
                }
                s.copy(continuousEffects = s.continuousEffects + ces)
                    .logged("$controller applies ${effect.ops.size} modifier op(s) to ${snapshot.size} permanent(s)")
            }
            // A shield is a replacement the STATE holds: damage to what
            // it guards is prevented, up to its budget; a REDUCTION lets each
            // hit through, smaller, and is never spent.
            is Effect.PreventDamage -> {
                val shielded = effect.who?.let { ctx.playerId(it) ?: return nobody() }
                val (shid, s) = state.allocId()
                val n = amount(effect.amount)
                val pattern = when {
                    shielded == null -> EventPattern.Damaged(PermFilter(pinned = BoundTarget(effect.target.id)), step = effect.onlyStep)
                    // Damage to a player is never dealt "in a step".
                    effect.onlyStep != null -> EventPattern.Never
                    // The one player it guards: `Opponent` would match any.
                    else -> EventPattern.PlayerDamaged(if (shielded == controller) PlayerRef.You else PlayerRef.Seat(shielded))
                }
                val instead = if (effect.mode == ShieldMode.REDUCTION && !effect.all) Effect.Proceed(IntExpr.EventAmount - n) else Effect.NoOp
                val budget = when {
                    effect.all || effect.mode == ShieldMode.REDUCTION -> null
                    effect.mode == ShieldMode.INSTANCES -> Budget(n, Spend.USES)
                    else -> Budget(n, Spend.POINTS)
                }
                s.copy(
                    shields = s.shields + ActiveReplacement(
                        source = source,
                        controller = controller,
                        doc = ReplacementDoc.Replace(pattern, instead),
                        duration = effect.duration,
                        startTurn = s.turnNumber,
                        id = shid,
                        budget = budget,
                    ),
                ).logged(
                    "$controller shields " +
                        (shielded ?: "#${effect.target.id}") + " " +
                        if (effect.mode == ShieldMode.INSTANCES) "($n instance(s))"
                        else if (effect.all) "(all damage)" else "($n)",
                )
            }
            is Effect.Proceed -> insteadRun?.let { run ->
                run.proceeded = Proceeded(effect.amount?.let { amount(it) })
                state
            } ?: state.logged("nothing to let happen here -- \"proceed\" means something only inside a replacement")
            is Effect.SetCombatMode ->
                state.battlefield[effect.target.id]?.let {
                    state.copy(battlefield = state.battlefield + (it.id to it.copy(combatMode = effect.mode)))
                        .logged("#${it.id} combat mode -> ${effect.mode ?: "cleared"}")
                } ?: state
            // Attach the resolving effect's own SOURCE
            // permanent (an Improvement) to `host`. A no-op with no source or
            // an already-gone host -- same fizzle stance as DealDamage.
            is Effect.Attach -> {
                val self = source?.let { state.battlefield[it] }
                // Every fizzle logs; a silent failed attach is undiagnosable.
                when {
                    self == null ->
                        state.logged("attach fizzled -- the attaching permanent is no longer in play")
                    effect.host.id !in state.battlefield ->
                        state.logged("#${self.id} can't attach -- #${effect.host.id} is no longer in play")
                    else -> state.copy(battlefield = state.battlefield + (self.id to self.copy(hostId = effect.host.id)))
                        .logged("#${self.id} attaches to #${effect.host.id}")
                }
            }
            is Effect.ForEach -> {
                val ids = state.inPlayIds.filter { effect.filter.matches(ctx, it) }
                var s = state
                for (id in ids) s = interpret(effect.body.substituteTarget(effect.binds, id), s, controller, source)
                s
            }
            is Effect.ForEachPlayer -> {
                // APNAP: the active player first, then round the turn order.
                val start = state.turnOrder.indexOf(state.activePlayer).coerceAtLeast(0)
                val apnap = state.turnOrder.drop(start) + state.turnOrder.take(start)
                var s = state
                for (pid in apnap) s = interpret(effect.body, s, pid, source)
                s
            }
            is Effect.Sacrifice -> {
                val pid = ctx.playerId(effect.who) ?: return nobody()
                var s = state
                repeat(effect.count.eval(ctx)) {
                    val c2 = EvalContext(s, pid, source)
                    val candidates = s.inPlayIds.filter { effect.filter.matches(c2, it) && s.battlefield[it]?.controller == pid }
                    if (candidates.isEmpty()) return@repeat
                    val (chosen, note) = askTarget(pid, candidates, s)
                    s = leaves(s.noted(note).logged("$pid sacrifices #$chosen"), chosen, HiddenZone.GRAVEYARD)
                }
                s
            }
            is Effect.Delayed -> {
                val (did, s) = state.allocId()
                s.copy(
                    delayedTriggers = s.delayedTriggers + DelayedTrigger(
                        did, controller, effect.on, effect.effect, effect.once, effect.expiresAfter,
                        duration = effect.duration, startTurn = s.turnNumber, source = source,
                    ),
                ).logged("$controller arms delayed trigger #$did")
            }
            is Effect.Sequence -> {
                var s = state
                for (step in effect.steps) s = interpret(step, s, controller, source)
                s
            }
            is Effect.Choose -> {
                val candidates = state.inPlayIds.filter { effect.filter.matches(ctx, it) }
                if (candidates.isEmpty()) {
                    val who = source?.let { state.battlefield[it] }?.let { " for ${state.characteristicsOf(it.id).name} #${it.id}" } ?: ""
                    state.logged("$controller: no legal target$who -- that effect does nothing")
                } else {
                    val (chosen, note) = askTarget(controller, candidates, state)
                    interpret(effect.body.substituteTarget(effect.binds, chosen), state.noted(note), controller, source)
                }
            }
            is Effect.ChooseMany -> {
                fun candidatesIn(st: GameState) = st.inPlayIds.filter { effect.filter.matches(EvalContext(st, controller, source), it) }
                val want = effect.count.eval(ctx).coerceAtLeast(0)
                var s = state
                val chosen = mutableListOf<ObjectId>()
                // Pick one at a time so each choice sees the others already
                // taken (and so `upTo` can stop early when candidates run out).
                repeat(want) {
                    val left = candidatesIn(s).filterNot { it in chosen }
                    if (left.isEmpty()) return@repeat
                    val (c, note) = askTarget(controller, left, s)
                    chosen += c
                    s = s.noted(note)
                }
                if (!effect.upTo && chosen.size < want) {
                    return s.logged("$controller: not enough legal targets (${chosen.size} of $want)")
                }
                // Divide the pool, if any: ask for each share, last one takes
                // the remainder so the total is always honoured.
                val shares: List<Int> = effect.divide?.let { total ->
                    var rem = total.eval(ctx).coerceAtLeast(0)
                    chosen.mapIndexed { i, _ ->
                        if (i == chosen.lastIndex) rem
                        else askNumber(controller, "share", 0, rem).let { (n, note) -> s = s.noted(note); rem -= n; n }
                    }
                } ?: emptyList()
                chosen.forEachIndexed { i, id ->
                    var body = effect.body.substituteTarget(effect.binds, id)
                    if (shares.isNotEmpty()) body = body.substituteShare(shares[i])
                    s = interpret(body, s, controller, source)
                }
                s
            }
            is Effect.AsPlayer -> {
                var s = state
                // The one place `Chosen` is asked; every other ref resolves
                // the way it does anywhere (EvalContext.playerId).
                val who: PlayerId? = when (val r = effect.who) {
                    PlayerRef.Chosen -> {
                        val (pick, note) = askModes(controller, state.turnOrder.map { ModeOption.OfPlayer(it) }, 1, state)
                        s = s.noted(note)
                        state.turnOrder.getOrNull(pick.firstOrNull() ?: 0)
                    }
                    else -> ctx.playerId(r)
                }
                if (who == null) s.logged("$controller: no such player -- that effect does nothing")
                else interpret(effect.body, s, who, source)
            }
            is Effect.CounterSpell -> {
                // The chosen spell, if it is still there; unbound, the
                // most recent match -- the stack is bottom-first and this
                // spell is already popped, so that is the LAST one.
                val ids = counterCandidates(effect, controller, state, source)
                val id = effect.target?.id?.takeIf { it in ids } ?: if (effect.target == null) ids.lastOrNull() else null
                val hit = id?.let { i -> state.stack.first { it.id == i } }
                    ?: return state.logged(if (effect.target != null) "fizzle: #${effect.target.id} is no longer on the stack" else "$controller: no spell to counter")
                val s = state.copy(stack = state.stack.filterNot { it.id == hit.id }).logged("#${hit.id} is countered")
                when (hit) {
                    is SpellOnStack -> hit.card?.let { s.addTo(hit.controller, HiddenZone.GRAVEYARD, listOf(it)) } ?: s
                    is PermanentOnStack ->
                        s.addTo(hit.controller, HiddenZone.GRAVEYARD, listOf(CardRef(hit.id, hit.card.key.ifEmpty { hit.card.name })))
                    else -> s
                }
            }
            is Effect.ClearDamage -> {
                val perm = state.battlefield[effect.target.id] ?: return state
                if (perm.damageMarked == 0) state else state.copy(battlefield = state.battlefield + (perm.id to perm.copy(damageMarked = 0)))
            }
            is Effect.Tap -> {
                val perm = state.battlefield[effect.target.id] ?: return state
                if (perm.exhausted == !effect.untap) return state
                state.copy(battlefield = state.battlefield + (perm.id to perm.copy(exhausted = !effect.untap)))
                    .logged("#${perm.id} ${if (effect.untap) "readies" else "is exhausted"}")
            }
            is Effect.SendTo -> leaves(state, effect.target.id, effect.to)
            is Effect.GainControl -> {
                val perm = state.battlefield[effect.target.id] ?: return state
                if (perm.controller == controller) return state
                val s = state.withController(perm.id, controller)
                if (effect.duration == Duration.Permanent) {
                    s
                } else {
                    s.copy(controlChanges = s.controlChanges + ControlChange(perm.id, controller, perm.controller, effect.duration, s.turnNumber, source))
                }
            }
            is Effect.If ->
                if (effect.cond.eval(ctx)) interpret(effect.then, state, controller, source)
                else interpret(effect.otherwise, state, controller, source)
            // Combat: the verbs live with the rest of combat.
            is Effect.DeclareAttackers -> combat.declareAttackers(effect, state, controller)
            is Effect.DeclareBlockers -> combat.declareBlockers(effect, state, controller)
            Effect.CombatWindow -> combat.combatWindow(state)
            is Effect.CombatDamage -> combat.combatDamage(effect, state, controller)
            is Effect.Attack -> combat.attack(effect, state, controller)
            is Effect.Strike -> combat.strike(effect, state, controller)
            is Effect.Clash -> combat.clash(effect, state, controller)
            is Effect.FreeAttacks -> combat.freeAttacks(effect, state, controller)
            Effect.NoOp -> state
        }
    }

    // -- events --------------------------------------------------------

    private fun applyEvent(
        event: GameEvent,
        state: GameState,
        /** Every replacement that has already replaced an event in THIS chain:
         *  one applies at most once per chain (MTG 616.5). A card's by
         *  identity, so two copies of one card are two; a held one by id. */
        applied: List<Any> = emptyList(),
        /** Simultaneous combat damage: `dealWave` already ran the replacements
         *  once against the whole wave, so its per-source events must not run
         *  them again (a REDUCTION shield would apply per hit). */
        skipPrevention: Boolean = false,
    ): GameState {
        val (happens, s0) = if (skipPrevention) event to state else replaced(event, state, applied)
        if (happens == null) return s0 // replaced outright -- invisible to triggers
        val consequence = defaultConsequence(happens, s0)
        // After the replacements, so a prevented hit is never drawn.
        val next = when {
            happens is GameEvent.DamageDealt && happens.combat && happens.amount > 0 ->
                consequence.withCombatHit(happens.source, happens.target, null, happens.amount)
            happens is GameEvent.PlayerDamaged && happens.combat && happens.amount > 0 ->
                consequence.withCombatHit(happens.source, null, happens.player, happens.amount)
            else -> consequence
        }
        return next.copy(pendingEvents = next.pendingEvents + happens)
    }

    /** Every replacement that applies to `event`, one at a time and each at
     *  most once per chain (MTG 616.5): the state's HELD ones first (shields),
     *  then the cards'. Returns the event as it finally happens -- null
     *  when something replaced it outright -- and the state.
     *  A held replacement only ever meets an event with an amount above 0. */
    private fun replaced(event: GameEvent, state: GameState, applied: List<Any> = emptyList()): Pair<GameEvent?, GameState> {
        val done = applied + insteadRun?.chain.orEmpty()
        fun has(k: Any) = done.any { it === k || (k is String && it == k) }
        for (h in state.shields) {
            val key = "held#${h.id}"
            if (has(key) || event.amount <= 0 || h.budget?.remaining == 0) continue
            val doc = h.doc as? ReplacementDoc.Replace ?: throw SurfaceFormReached(h.doc)
            if (!doc.pattern.matches(event, EvalContext(state, h.controller, h.source))) continue
            val (s1, proceeded) = runInstead(doc.instead.bindEvent(event).let { e -> h.source?.let { e.bindSelf(it) } ?: e }, state, h, done + key)
            var next: GameEvent? = proceeded?.let { p -> p.amount?.let { event.withAmount(it) } ?: event }
            var s2 = s1
            h.budget?.let { b ->
                var left = b.remaining
                when (b.spends) {
                    Spend.USES -> left -= 1
                    Spend.POINTS -> {
                        // It takes away no more than it has left.
                        val taken = (event.amount - (next?.amount ?: 0)).coerceAtLeast(0)
                        if (taken > left) next = event.withAmount(event.amount - left)
                        left -= minOf(taken, left)
                    }
                }
                s2 = s2.copy(
                    shields = s2.shields.mapNotNull { if (it.id != h.id) it else if (left <= 0) null else it.copy(budget = b.copy(remaining = left)) },
                )
            }
            if (next != null && next.amount <= 0) next = null
            val prevented = event.amount - (next?.amount ?: 0)
            if (prevented > 0) {
                val whom = (event as? GameEvent.PlayerDamaged)?.player ?: "#${event.subject}"
                s2 = s2.logged("prevented $prevented to $whom")
            }
            return if (next == null) null to s2 else replaced(next, s2, applied + key)
        }
        for (h in state.replacements) {
            if (has(h)) continue
            val doc = h.doc as? ReplacementDoc.Replace ?: throw SurfaceFormReached(h.doc)
            if (!doc.pattern.matches(event, EvalContext(state, h.controller, h.source))) continue
            val (s1, proceeded) = runInstead(doc.instead.bindEvent(event).let { e -> h.source?.let { e.bindSelf(it) } ?: e }, state.recordReplaced(event), h, done + h)
            val next = proceeded?.let { p -> p.amount?.let { event.withAmount(it) } ?: event }
                ?.takeUnless { it.hasAmount && it.amount <= 0 }
            return if (next == null) null to s1 else replaced(next, s1, applied + h)
        }
        return event to state
    }

    /** Run a replacement's `instead` NOW, inside the synchronous event
     *  machinery: on an engine whose every question takes its default
     *  answer, so it cannot wait on anyone (the compiler reports a verb that
     *  would ask). Returns the state, and what `Proceed` said, if it ran. */
    private fun runInstead(effect: Effect, state: GameState, h: ActiveReplacement, chain: List<Any>): Pair<GameState, Proceeded?> {
        val run = InsteadRun(chain)
        val engine = Engine(PlayerInput { Answer.Pass }, rules, run, false, false, undecked)
        engine.y = engine.DirectYielder { Answer.Pass }
        return engine.interpret(effect, state, h.controller, h.source) to run.proceeded
    }

    /** Fire a `LeavesPlay` for a permanent going to `destination`. */
    private fun leaves(state: GameState, id: ObjectId, destination: HiddenZone): GameState {
        val perm = state.battlefield[id] ?: return state
        val types = state.characteristicsOf(id).types
        return applyEvent(GameEvent.LeavesPlay(id, perm.controller, destination, types, fromZone = perm.zone), state)
    }

    private fun defaultConsequence(event: GameEvent, state: GameState): GameState = when (event) {
        is GameEvent.DamageDealt -> {
            val perm = state.battlefield[event.target] ?: return state
            // The one place that decides how a hit is recorded: a type declaring
            // `damageCounter` spends counters (loyalty, a Ship's hull);
            // everything else marks damage. Raised as an event so triggers and
            // shields see both.
            val counter = rules.damageCounterFor(state.characteristicsOf(event.target).types)
            if (counter != null) {
                mutateCounter(state, event.target, counter, -event.amount)
                    .logged("#${event.target} loses ${event.amount} $counter")
            } else {
                state.copy(
                    battlefield = state.battlefield + (event.target to perm.copy(damageMarked = perm.damageMarked + event.amount)),
                ).logged("#${event.target} takes ${event.amount}")
            }
        }
        is GameEvent.LeavesPlay -> {
            if (event.permanent !in state.battlefield) return state
            val wasToken = state.battlefield.getValue(event.permanent).isToken
            var gone = state.leaveBattlefield(event.permanent)
            // A `loseOnDeath` type ends the game for its controller when one
            // leaves play by any path -- once they have no other permanent of
            // that type left.
            val flaggedTypes = event.types.filter { rules.typeOf(it).loseOnDeath }
            val stillHasOne = flaggedTypes.isNotEmpty() && gone.battlefield.values.any { p ->
                p.controller == event.controller &&
                    gone.characteristicsOf(p.id).types.any { it in flaggedTypes }
            }
            if (flaggedTypes.isNotEmpty() && !stillHasOne && event.controller !in gone.losers) {
                gone = gone.copy(losers = gone.losers + event.controller)
                    .logged("${event.controller} loses -- #${event.permanent} left play")
            }
            // Its OWNER's zones, not its controller's: a stolen card goes home.
            val ownerId = state.battlefield.getValue(event.permanent).ownerId
            when {
                // A token ceases to exist -- never touches a real zone.
                wasToken -> gone.logged("#${event.permanent} (token) ceases to exist")
                event.destination == HiddenZone.GRAVEYARD -> {
                    val owner = gone.players.getValue(ownerId)
                    // Keep the id as the instanceId so it stays traceable, and
                    // file it under its card KEY so it can come back.
                    val ref = CardRef(event.permanent, state.battlefield.getValue(event.permanent).cardKey)
                    gone.copy(players = gone.players + (ownerId to owner.copy(graveyard = owner.graveyard + ref)))
                        .logged("#${event.permanent} dies")
                }
                event.destination == HiddenZone.EXILE -> {
                    val ref = CardRef(event.permanent, state.battlefield.getValue(event.permanent).cardKey)
                    gone.copy(exile = gone.exile + ref).logged("#${event.permanent} is exiled")
                }
                // To hand or library: the card goes THERE.
                else -> {
                    val ref = CardRef(event.permanent, state.battlefield.getValue(event.permanent).cardKey)
                    gone.addTo(ownerId, event.destination, listOf(ref))
                        .logged("#${event.permanent} -> ${zoneName(event.destination)}")
                }
            }
        }
        // Player damage from any source is recorded here, after shields and
        // replacements have had their say.
        is GameEvent.PlayerDamaged -> {
            val p = state.players[event.player]
            val dc = rules.damageCounter
            if (p == null || event.amount <= 0) state
            // A game whose players take no damage: nothing to reduce.
            else if (dc == null) state.logged("${event.player} takes no damage")
            else state.copy(players = state.players + (event.player to p.addCounter(dc, -event.amount)))
                .logged("${event.player} takes ${event.amount}" + if (event.combat) " combat damage" else "")
        }
        // The one counter event that IS a state change -- see its doc comment.
        // Routed through `mutateCounter`, which fires `CounterChanged` after,
        // so a "when I lose a shield" trigger still sees it.
        is GameEvent.CounterSpent -> mutateCounter(state, event.permanent, event.kind, -event.amount)
        is GameEvent.EntersPlay,
        is GameEvent.MovesZone,
        is GameEvent.SpellCast,
        is GameEvent.CounterChanged,
        is GameEvent.TurnBegan,
        is GameEvent.PhaseEnter,
        is GameEvent.CombatStep,
        is GameEvent.Attacks,
        is GameEvent.Blocks,
        -> state
    }

    private fun mutateCounter(state: GameState, id: ObjectId, kind: String, delta: Int): GameState {
        val perm = state.battlefield[id] ?: return state.logged("counter: #$id is gone")
        val old = perm.counter(kind)
        val next = (old + delta).coerceAtLeast(0)
        if (next == old) return state
        val counters = if (next == 0) perm.counters - kind else perm.counters + (kind to next)
        val s = state.copy(battlefield = state.battlefield + (id to perm.copy(counters = counters))).logged("#$id $kind -> $next")
        return applyEvent(GameEvent.CounterChanged(id, kind, old, next), s)
    }

    private fun transform(state: GameState, id: ObjectId): GameState {
        val perm = state.battlefield[id] ?: return state
        val card = rules.cardOf(perm) ?: return state.logged("#$id has no card -- can't transform")
        if (card.faces.size < 2) return state.logged("#$id is single-faced")
        val nf = (perm.face + 1) % card.faces.size
        val face = card.faces[nf]
        val newBase = face.baseChars ?: Characteristics(face.name, face.types)
        var s = state.copy(
            battlefield = state.battlefield + (id to perm.copy(face = nf, base = newBase)),
            continuousEffects = state.continuousEffects.filterNot { it.source == id },
            triggeredAbilities = state.triggeredAbilities - id,
            ruleMods = state.ruleMods.filterNot { it.source == id },
            replacements = state.replacements.filterNot { it.source == id },
            costMods = state.costMods.filterNot { it.source == id },
        )
        s = s.wireFace(face, id, perm.controller)
        return s.logged("#$id transforms -> ${face.name}")
    }

    /** Returns the drawn refs alongside the new state:
     *  `DrawThenDiscard` needs to know exactly which cards were just drawn,
     *  so a follow-up cull can be scoped to THOSE, not the whole hand. */
    private fun drawCards(state: GameState, pid: PlayerId, count: Int): Pair<GameState, List<CardRef>> {
        if (count <= 0) return state to emptyList()
        if (state.forbids(RuleAction.DRAW, pid)) return state.logged("$pid can't draw (rule static)") to emptyList()
        val p = state.players.getValue(pid)
        val drawn = p.library.take(count)
        var s = state.copy(
            players = state.players + (pid to p.copy(library = p.library.drop(count), hand = p.hand + drawn)),
        ).logged("$pid draws $count")
        if (drawn.size < count) {
            s = if (pid in undecked) s.logged("$pid has no deck to draw from")
            else s.copy(losers = s.losers + pid).logged("$pid decks out")
        }
        return s to drawn
    }

    /** Match queued events against triggered abilities AND delayed triggers.
     *  Same-event matches go on the stack active player first, and within one
     *  player lowest-`order` last (so it resolves first). `once` delayed
     *  triggers and expired ones are removed. */
    private fun drainTriggers(state: GameState): GameState {
        var s = state
        while (s.pendingEvents.isNotEmpty()) {
            val event = s.pendingEvents.first()
            s = s.copy(pendingEvents = s.pendingEvents.drop(1)).recordEvent(event)

            /** `key` breaks every remaining tie in a DECLARED way: the source
             *  permanent's id, then the ability's place on its card; a delayed
             *  trigger by its own id, after every permanent's. */
            data class Hit(
                val controller: PlayerId, val effect: Effect, val order: Int, val source: ObjectId?,
                val key: Pair<Long, Int>, val delayed: Boolean = false,
            )
            val hits = mutableListOf<Hit>()

            for ((sourceId, abilities) in s.triggeredAbilities) {
                // Humility: a permanent whose derived characteristics have
                // abilities removed contributes no triggered abilities.
                if (sourceId in s.battlefield && s.characteristicsOf(sourceId).abilitiesRemoved) continue
                abilities.forEachIndexed { i, ab ->
                    if (ab.matches(event, s)) {
                        hits += Hit(ab.controller, ab.effect.bindEvent(event), ab.order, sourceId, sourceId.toLong() to i)
                    }
                }
            }
            val firedDelayed = mutableListOf<ObjectId>()
            val st = s
            fun DelayedTrigger.ctx() = EvalContext(st, controller, source = source)
            fun DelayedTrigger.expires() = expiresAfter?.matches(event, ctx()) ?: false
            for (dt in s.delayedTriggers) {
                if (dt.on.matches(event, dt.ctx())) {
                    hits += Hit(dt.controller, dt.effect.bindEvent(event), 0, dt.source, (Int.MAX_VALUE.toLong() + dt.id) to 0, delayed = true)
                    if (dt.once) firedDelayed += dt.id
                }
            }
            if (firedDelayed.isNotEmpty() || s.delayedTriggers.any { it.expires() }) {
                s = s.copy(delayedTriggers = s.delayedTriggers.filterNot { it.id in firedDelayed || it.expires() })
            }

            // APNAP (MTG 603.3b): the active player's triggers go on the stack
            // first, then each other player's in turn order -- so the LAST
            // player's resolve first. Within one player, lowest `order` last;
            // then by `key`.
            val seats = s.turnOrder.indexOf(s.activePlayer).coerceAtLeast(0)
                .let { i -> s.turnOrder.drop(i) + s.turnOrder.take(i) }
            for (hit in hits.sortedWith(compareBy<Hit>({ seats.indexOf(it.controller) }, { -it.order }, { it.key.first }, { it.key.second }))) {
                // Targets bind as the trigger goes ON the stack, so everyone
                // responds knowing what it points at -- the same rule as spells.
                // A trigger with no legal target is never put on the stack (and
                // says so). Nothing chooses a trigger's X: it reads 0 and
                // `FaceDoc.scopeProblems` reports it.
                val announced = announce(hit.effect, hit.controller, s, hit.source, choosesX = false) { bound, st, _ ->
                    val (tid, s2) = st.allocId()
                    s2.copy(stack = s2.stack + TriggeredAbilityOnStack(tid, hit.controller, bound, event, source = hit.source))
                        .logged("trigger #$tid" + if (hit.delayed) " (delayed)" else hit.source?.let { " from #$it" } ?: "")
                }
                if (announced !is Announced.Put) {
                    // Name the CARD, not just its id.
                    val who = hit.source
                        ?.let { sid -> s.battlefield[sid]?.let { " for ${s.characteristicsOf(sid).name} #$sid" } }
                        ?: ""
                    s = s.logged("no legal target$who -- the trigger is not put on the stack")
                    continue
                }
                s = announced.state
            }
        }
        // Now that this batch is processed, drop triggered abilities whose
        // source has left the battlefield (kept above so LTB triggers could fire).
        val stale = s.triggeredAbilities.keys.filter { it !in s.battlefield }
        if (stale.isNotEmpty()) s = s.copy(triggeredAbilities = s.triggeredAbilities - stale.toSet())
        return s
    }

    /** SBA fixpoint: counter annihilation (the declared `cancels` pairs),
     *  then per-permanent `diesWhen`, then player loss. */
    private fun stateBasedActions(state: GameState): GameState {
        var s = state
        while (true) {
            val annih = rules.cancellations.firstNotNullOfOrNull { (a, b) ->
                s.inPlay.firstOrNull { it.counter(a) > 0 && it.counter(b) > 0 }?.let { Triple(it, a, b) }
            }
            if (annih != null) {
                val (perm, a, b) = annih
                val k = minOf(perm.counter(a), perm.counter(b))
                val nc = (perm.counters + mapOf(a to perm.counter(a) - k, b to perm.counter(b) - k)).filterValues { it != 0 }
                s = s.copy(battlefield = s.battlefield + (perm.id to perm.copy(counters = nc)))
                    .logged("#${perm.id}: $k $a and $k $b counters cancel")
                continue
            }
            // Declarative: every counter the game declares `loseAtZero` on,
            // not a hardcoded life check -- so "lose at 0 devotion" is free.
            val newLosers = s.players.values.filter { p ->
                p.id !in s.losers && rules.playerCounters.any { it.loseAtZero && p.counter(it.name) <= 0 }
            }.map { it.id }
            val dead = s.inPlay.filter { perm ->
                // an attachment (Improvement) whose host is gone
                // falls off -- an Aura/Equipment with no legal host leaves
                // play, same SBA family as a lethal-damage death.
                (perm.hostId != null && perm.hostId !in s.battlefield) || run {
                    val ctx = EvalContext(s, perm.controller, source = perm.id)
                    rules.diesPredicates(perm).any { it.eval(ctx) }
                }
            }
            if (newLosers.isEmpty() && dead.isEmpty()) return s
            if (newLosers.isNotEmpty()) s = s.copy(losers = s.losers + newLosers).logged("loses: ${newLosers.joinToString()}")
            for (perm in dead) s = leaves(s, perm.id, HiddenZone.GRAVEYARD)
        }
    }
}

/** What `Effect.Proceed` said: let the replaced event happen, with [amount]
 *  when not null. */
internal data class Proceeded(val amount: Int?)

/** One replacement's `instead` being run: the chain it belongs to (so no
 *  replacement in it applies twice), and the `Proceed` it may answer with. */
internal class InsteadRun(val chain: List<Any>) {
    var proceeded: Proceeded? = null
}
