package ccg

import ccgui.withEffect

// ---------------------------------------------------------------------------
// Regressions from a DSL/interpreter review. Each section is one finding;
// each check pins it so the fix cannot quietly come undone.
// ---------------------------------------------------------------------------

/** Scripted, and counts every question it is asked -- some checks care as
 *  much about what the engine ASKS as about what it does. */
private class Asking(
    actions: List<PriorityAction>,
    modes: List<List<Int>> = emptyList(),
    private val cards: List<List<ObjectId>> = emptyList(),
    /** Overrides for the checks which answer WRONG on purpose. */
    private val target: ((List<ObjectId>) -> ObjectId)? = null,
    private val number: Int? = null,
) : PlayerInput {
    override suspend fun ask(q: Question): Answer = when (q) {
        is Question.Priority -> Answer.Act(askPriorityAction(q.player, q.state))
        is Question.PickTarget -> Answer.Target(chooseTarget(q.player, q.candidates, q.state))
        is Question.PickNumber -> Answer.Number(chooseNumber(q.player, q.label, q.min, q.max))
        is Question.PickMode -> Answer.Modes(chooseMode(q.player, q.options, q.pick, q.state))
        is Question.PickCards -> Answer.Cards(chooseCards(q.player, q.candidates, q.count, q.state, q.atMost))
        else -> q.default()
    }

    private val actionQueue = ArrayDeque(actions)
    private val modeQueue = ArrayDeque(modes)
    private val cardQueue = ArrayDeque(cards)
    val asked = mutableMapOf<String, Int>()
    private fun bump(k: String) { asked[k] = (asked[k] ?: 0) + 1 }

    private suspend fun askPriorityAction(player: PlayerId, state: GameState): PriorityAction =
        if (player == "P0") actionQueue.removeFirstOrNull() ?: PriorityAction.PassPriority else PriorityAction.PassPriority
    private suspend fun chooseTarget(player: PlayerId, candidates: List<ObjectId>, state: GameState): ObjectId {
        bump("target"); return target?.invoke(candidates) ?: candidates.first()
    }
    private suspend fun chooseNumber(player: PlayerId, prompt: String, min: Int, max: Int): Int { bump("number"); return number ?: min }
    private suspend fun chooseMode(player: PlayerId, options: List<ModeOption>, pick: Int, state: GameState): List<Int> {
        bump("mode"); return modeQueue.removeFirst()
    }
    private suspend fun chooseCards(
        player: PlayerId, candidates: List<CardRef>, count: Int, state: GameState, atMost: Boolean,
    ): List<ObjectId> {
        bump("cards"); return cardQueue.removeFirstOrNull() ?: candidates.take(count).map { it.instanceId }
    }
}

private fun GameState.withPool(pid: PlayerId, n: Int): GameState =
    copy(players = players + (pid to players.getValue(pid).copy(pool = mapOf("" to n))))

private fun GameState.lifeOf(pid: PlayerId): Int = players.getValue(pid).counter(LIFE)

private fun GameState.withHand(pid: PlayerId, cards: List<CardRef>): GameState =
    copy(players = players + (pid to players.getValue(pid).copy(hand = cards)))

internal fun dslReviewChecks() {
    println()
    println("DSL review -- regressions")

    // -- pay one cost, once -------------------------------------------
    // Choose, then pay once: paying every alternative speculatively would
    // raise events and ask questions for options never taken.

    /** A permanent with 3 shields and "whenever this loses a shield, gain 10". */
    val bank = CardDoc(
        faces = listOf(FaceDoc(
            "Bank", setOf("Artifact"),
            triggers = listOf(3, 2, 1).map { k ->
                TriggerDoc.CounterThreshold("shield", k, Effect.GainLife(PlayerRef.You, lit(10)), downward = true)
            },
        )),
        entersWith = listOf(CounterDef("shield", lit(3))),
    ).build()
    val shieldOr3 = Cost(
        mana = mapOf("" to 3),
        alternatives = listOf(Cost(payFrom = CounterPayment("shield", 1, permanents().yours()))),
    )
    fun castFor(cost: Cost) = PriorityAction.CastSpell(Effect.NoOp, emptySet(), cost)

    check("paying the mana option spends no shield and fires no shield trigger") {
        val (s0, bankId) = enterBattlefield(bank, "P0", newGame())
        val input = Asking(listOf(castFor(shieldOr3)), modes = listOf(listOf(0)))
        val end = runSync { Engine(input).run(s0.withPool("P0", 3)) }
        assertEq(3, end.battlefield.getValue(bankId).counter("shield"), "the shield option was not taken")
        assertEq(20, end.players.getValue("P0").counter(LIFE), "no shield was lost, so nothing triggered:\n${end.log.joinToString("\n")}")
        assertEq(1, input.asked["mode"], "both ways were payable, so the player chose")
    }

    check("paying the shield option spends one and fires the trigger once") {
        val (s0, bankId) = enterBattlefield(bank, "P0", newGame())
        val end = runSync { Engine(Asking(listOf(castFor(shieldOr3)), modes = listOf(listOf(1)))).run(s0.withPool("P0", 3)) }
        assertEq(2, end.battlefield.getValue(bankId).counter("shield"))
        assertEq(30, end.players.getValue("P0").counter(LIFE), "one shield lost, one trigger")
    }

    check("a single payable option is paid without asking") {
        val (s0, bankId) = enterBattlefield(bank, "P0", newGame())
        val input = Asking(listOf(castFor(shieldOr3)))
        val end = runSync { Engine(input).run(s0.withPool("P0", 0)) }
        assertEq(2, end.battlefield.getValue(bankId).counter("shield"), "the mana option was unaffordable")
        assertEq(null, input.asked["mode"], "nothing to choose between")
    }

    check("an option not taken asks no question and costs nothing") {
        // "{1}, or discard a card": paying the mana must not ask which card
        // to discard.
        val discardOr1 = Cost(mana = mapOf("" to 1), alternatives = listOf(Cost(additional = Effect.Discard(PlayerRef.You, lit(1)))))
        val input = Asking(listOf(castFor(discardOr1)), modes = listOf(listOf(0)))
        val end = runSync { Engine(input).run(newGame().withPool("P0", 1).withHand("P0", tokens(2))) }
        assertEq(2, end.players.getValue("P0").hand.size, "the discard option was not taken")
        assertEq(null, input.asked["cards"], "and nobody was asked what to discard for it")
        assertEq(emptyMap<String, Int>(), end.players.getValue("P0").pool, "the mana was paid")
    }

    check("an event raised in a state the engine discards is gone with it") {
        // The structural half: pending events are PART of the state, so the
        // engine cannot fire a trigger for a state it did not keep.
        val (s0, bankId) = enterBattlefield(bank, "P0", newGame())
        val engine = Engine(Asking(listOf(castFor(shieldOr3)), modes = listOf(listOf(0))))
        val end = runSync { engine.run(s0.withPool("P0", 3).traced()) }
        assertTrue(
            end.trace!!.events.none { it is GameEvent.CounterChanged && it.permanent == bankId },
            "no shield change reached the history: ${end.trace!!.events}",
        )
    }

    check("on real Core cards: Ward Conduit paid with mana leaves Harrow Vault's sweep unfired") {
        // The device-shaped reproduction. Harrow Vault: "whenever this loses a
        // Shield, deal 6 to every Ship". Ward Conduit: {3}, or {1} + a shield.
        val core = CORE_BUNDLE
        val rules = core.rules()
        val docs = core.sets.flatMap { it.cards }
        fun def(name: String) = rules.cards.getValue(docs.first { it.faces.first().name == name }.key())
        fun place(s: GameState, card: CardDefinition) =
            enterBattlefield(card, "P0", s, zone = rules.resolveZone(rules.defaultZoneDef(card.types), "P0"))
        val (s1, vault) = place(newGame(counters = rules.startingCounters(), damageCounter = rules.damageCounter), def("Harrow Vault"))
        val ship = docs.first { "Ship" in it.faces.first().types && it.cost.alternatives.isEmpty() }
        val (s2, shipId) = place(s1, rules.cards.getValue(ship.key()))
        val conduit = def("Ward Conduit")
        val input = Asking(listOf(PriorityAction.PlayPermanent(conduit.lowered(LIFE), 0)), modes = listOf(listOf(0)))
        val end = runSync { Engine(input, rules = rules).run(s2.withPool("P0", 3)) }
        assertEq(1, input.asked["mode"], "both ways were payable")
        assertEq(3, end.battlefield.getValue(vault).counter("shield"), "paid with mana")
        assertTrue(shipId in end.battlefield, "P0's own Ship survives:\n${end.log.joinToString("\n")}")
    }

    // -- every answer is checked ----------------------------------------
    // An invalid answer becomes a deterministic default plus a log line, and is never re-asked.

    fun cast(e: Effect) = PriorityAction.CastSpell(e.lowered(LIFE), emptySet())
    val bear = card("Bear", setOf("Creature"), baseChars = Characteristics("Bear", setOf("Creature"), mapOf("power" to 2, "toughness" to 2)))

    check("a target outside the candidates is replaced by the first candidate") {
        var s = newGame()
        val (s1, mine) = enterBattlefield(bear, "P0", s); s = s1
        val (s2, theirs) = enterBattlefield(bear, "P1", s); s = s2
        val destroyMine = Effect.Choose(creatures().yours(), Effect.Destroy(BoundTarget(CHOSEN)))
        val input = Asking(listOf(cast(destroyMine)), target = { theirs })
        val end = runSync { Engine(input).run(s) }
        assertTrue(theirs in end.battlefield, "\"target creature YOU control\" never reaches theirs")
        assertTrue(mine !in end.battlefield, "the default -- the only candidate -- was destroyed")
        assertTrue(end.log.any { "not a legal choice" in it }, "and the correction is logged")
    }

    check("too many mode picks: exactly `pick` modes resolve") {
        val five = Effect.ChooseMode(List(5) { Effect.Draw(PlayerRef.You, lit(1)) }, pick = lit(1))
        val s = newGame().copy(players = newGame().players.mapValues { (_, p) -> p.copy(library = tokens(10)) })
        val end = runSync { Engine(Asking(listOf(cast(five)), modes = listOf(listOf(0, 1, 2, 3, 4)))).run(s) }
        assertEq(1, end.players.getValue("P0").hand.size, "one mode, one card drawn")
    }

    check("too many cards for \"discard 1\": exactly one is discarded") {
        val hand = tokens(5)
        val input = Asking(listOf(cast(Effect.Discard(PlayerRef.You, lit(1)))), cards = listOf(hand.map { it.instanceId }))
        val end = runSync { Engine(input).run(newGame().withHand("P0", hand)) }
        assertEq(4, end.players.getValue("P0").hand.size)
    }

    check("cards not offered are refused") {
        val hand = tokens(3)
        val input = Asking(listOf(cast(Effect.Discard(PlayerRef.You, lit(1)))), cards = listOf(listOf(999_999)))
        val end = runSync { Engine(input).run(newGame().withHand("P0", hand)) }
        assertEq(hand.drop(1), end.players.getValue("P0").hand, "the default -- the first card -- went")
    }

    check("X outside 0..99 is clamped, so a negative X cannot heal") {
        val input = Asking(listOf(cast(Effect.DamageOpponent(IntExpr.X))), number = -7)
        val end = runSync { Engine(input).run(newGame()) }
        assertEq(20, end.lifeOf("P1"), "X = 0, not -7:\n${end.log.joinToString("\n")}")
        assertTrue(end.log.any { "outside 0..99" in it })
    }

    // -- a scope checker, and total evaluation --------------------------

    check("an X in a trigger resolves as 0 instead of crashing the game") {
        val xOnEnter = CardDoc(faces = listOf(FaceDoc(
            "Xer", setOf("Artifact"), triggers = listOf(TriggerDoc.SelfEnters(Effect.DamageOpponent(IntExpr.X))),
        ))).build()
        val end = runSync { Engine(Asking(listOf(PriorityAction.PlayPermanent(xOnEnter.lowered(LIFE), 0)))).run(newGame()) }
        assertEq(20, end.lifeOf("P1"))
    }

    check("an unbound Share reads 0") {
        val end = runSync { Engine(Asking(listOf(cast(Effect.DamageOpponent(IntExpr.Share))))).run(newGame()) }
        assertEq(20, end.lifeOf("P1"))
    }

    check("problems() names each out-of-scope read") {
        val face = FaceDoc(
            "Loose", setOf("Sorcery"),
            castEffect = Effect.Sequence(listOf(
                Effect.Destroy(BoundTarget(CHOSEN)),
                Effect.Destroy(BoundTarget(EACH)),
                Effect.DamageOpponent(IntExpr.Share),
                Effect.GainLife(PlayerRef.You, selfCounter("charge")),
            )),
            triggers = listOf(TriggerDoc.SelfEnters(Effect.Draw(PlayerRef.You, IntExpr.X))),
        )
        val found = face.scopeProblems()
        for (what in listOf("chosen target", "\"each\"", "divided share", "\"this card\"", "trigger 1 uses X")) {
            assertTrue(found.any { what in it }, "missing \"$what\" in $found")
        }
        assertEq(5, found.size, "and nothing else: $found")
    }

    check("a bound read is not reported") {
        val face = FaceDoc(
            "Tight", setOf("Creature"),
            castEffect = Effect.Sequence(listOf(
                Effect.Choose(creatures(), Effect.Destroy(BoundTarget(CHOSEN))),
                Effect.ForEach(creatures(), Effect.AddCounter("+1/+1", lit(1), BoundTarget(EACH))),
                Effect.ChooseMany(creatures(), lit(2), divide = lit(4), body = Effect.DealDamage(IntExpr.Share, BoundTarget(EACH))),
                Effect.DamageOpponent(IntExpr.X),
            )),
            triggers = listOf(TriggerDoc.SelfEnters(Effect.GainLife(PlayerRef.You, selfCounter("charge")))),
            activated = listOf(ActivatedAbility(Cost(usesX = true), Effect.AddCounter("charge", IntExpr.X, BoundTarget(SELF)))),
        )
        assertEq(emptyList<String>(), face.scopeProblems())
    }

    check("no bundled game has an out-of-scope read") {
        val games = listOf(CORE_BUNDLE, EPR_SKIRMISH) + SAMPLE_GAMES
        val found = games.flatMap { g -> g.cards.flatMap { c -> c.faces.flatMap { it.scopeProblems() } }.map { "${g.name}: $it" } }
        assertEq(emptyList<String>(), found)
    }

    // -- no negative amounts ------------------------------------------

    check("negative damage deals nothing -- it does not heal") {
        val (s0, id) = enterBattlefield(bear, "P0", newGame())
        val hurt = s0.copy(battlefield = s0.battlefield + (id to s0.battlefield.getValue(id).copy(damageMarked = 1)))
        val end = runSync {
            Engine(Asking(listOf(
                cast(Effect.DealDamage(lit(-3), BoundTarget(id))),
                cast(Effect.DamageOpponent(lit(-5))),
            ))).run(hurt)
        }
        assertEq(1, end.battlefield.getValue(id).damageMarked, "marked damage is not reduced")
        assertEq(20, end.lifeOf("P1"), "the opponent is not healed")
    }

    check("a negative counter count adds or removes nothing") {
        val (s0, id) = enterBattlefield(bank, "P0", newGame())
        val end = runSync {
            Engine(Asking(listOf(
                cast(Effect.AddCounter("shield", lit(-2), BoundTarget(id))),
                cast(Effect.RemoveCounter("shield", lit(-2), BoundTarget(id))),
            ))).run(s0)
        }
        assertEq(3, end.battlefield.getValue(id).counter("shield"))
    }

    check("a negative prevention amount prevents nothing (it used to mean \"all\")") {
        val (s0, id) = enterBattlefield(bear, "P0", newGame())
        val end = runSync {
            Engine(Asking(listOf(
                cast(Effect.PreventDamage(BoundTarget(id), lit(-1))),
                cast(Effect.DealDamage(lit(5), BoundTarget(id))),
            ))).run(s0)
        }
        assertTrue(id !in end.battlefield, "5 damage kills the 2/2:\n${end.log.joinToString("\n")}")
    }

    check("GainLife stays signed: gaining -3 loses 3") {
        val end = runSync { Engine(Asking(listOf(cast(Effect.GainLife(PlayerRef.You, lit(-3)))))).run(newGame()) }
        assertEq(17, end.lifeOf("P0"))
    }

    // -- `source` is the card; `subject` is what is being derived --------

    fun permanentWith(name: String, types: Set<String>, counters: List<CounterDef> = emptyList(), f: (FaceDoc) -> FaceDoc) =
        CardDoc(faces = listOf(f(FaceDoc(name, types))), entersWith = counters).build()

    check("a static's value is counted from its controller's side") {
        // "Opponents' creatures get -X/-0, where X = creatures you control."
        val curse = permanentWith("Curse", setOf("Enchantment")) {
            it.copy(statics = listOf(StaticSpec(
                creatures().theirs(), listOf(CharOp.PlusPT(power = lit(0) - countOf(creatures().yours()))),
            )))
        }
        var s = newGame()
        s = enterBattlefield(curse, "P0", s).first
        s = enterBattlefield(bear, "P0", s).first
        s = enterBattlefield(bear, "P0", s).first
        val (s2, theirs) = enterBattlefield(bear, "P1", s)
        assertEq(0, s2.characteristicsOf(theirs).power, "X = P0's two creatures, not P1's one")
    }

    check("a static's selfCounter reads the card it is printed on") {
        val anthem = permanentWith("Banner", setOf("Enchantment"), listOf(CounterDef("charge", lit(3)))) {
            it.copy(statics = listOf(StaticSpec(creatures().yours(), listOf(CharOp.PlusPT(power = selfCounter("charge"))))))
        }
        val (s1, _) = enterBattlefield(anthem, "P0", newGame())
        val (s2, b) = enterBattlefield(bear, "P0", s1)
        assertEq(5, s2.characteristicsOf(b).power, "2 + the Banner's 3 charge, not the bear's 0")
    }

    check("Bands still reads the AFFECTED permanent's counters") {
        val leveler = permanentWith("Leveler", setOf("Enchantment")) {
            it.copy(statics = listOf(StaticSpec(
                creatures().yours(), listOf(CharOp.Bands("level", listOf(CharOp.Bands.Band(2, lit(7), lit(7))))),
            )))
        }
        val (s1, _) = enterBattlefield(leveler, "P0", newGame())
        val (s2, b) = enterBattlefield(bear, "P0", s1)
        val leveled = s2.copy(battlefield = s2.battlefield + (b to s2.battlefield.getValue(b).copy(counters = mapOf("level" to 2))))
        assertEq(7, leveled.characteristicsOf(b).power)
    }

    check("a While duration reads its source") {
        // "+5/+0 to your creatures while this has a charge counter."
        val charger = permanentWith("Charger", setOf("Creature"), listOf(CounterDef("charge", lit(2)))) {
            it.copy(
                fields = mapOf("power" to 1, "toughness" to 1),
                triggers = listOf(TriggerDoc.SelfEnters(Effect.ApplyModifier(
                    creatures().yours(), listOf(CharOp.PlusPT(power = lit(5))),
                    duration = Duration.While(selfCounter("charge") gte 1),
                ))),
            )
        }
        val end = runSync { Engine(Asking(listOf(PriorityAction.PlayPermanent(charger.lowered(LIFE), 0)))).run(newGame()) }
        val id = end.battlefield.values.single { it.base.name == "Charger" }.id
        assertEq(6, end.characteristicsOf(id).power, end.log.joinToString("\n"))
    }

    check("a delayed trigger keeps its source") {
        // "At the next turn, gain life equal to this card's charge counters."
        val battery = permanentWith("Battery", setOf("Artifact"), listOf(CounterDef("charge", lit(3)))) {
            it.copy(triggers = listOf(TriggerDoc.SelfEnters(
                Effect.Delayed(EventPattern.AnyTurnBegan, Effect.GainLife(PlayerRef.You, selfCounter("charge"))),
            )))
        }
        val s0 = newGame().copy(players = newGame().players.mapValues { (_, p) -> p.copy(library = tokens(10)) })
        // Plays the Battery at P0's first main phase, then passes.
        val input = object : PlayerInput {
            override suspend fun ask(q: Question): Answer = when (q) {
                is Question.Priority -> Answer.Act(askPriorityAction(q.player, q.state))
                is Question.PickTarget -> Answer.Target(chooseTarget(q.player, q.candidates, q.state))
                is Question.PickNumber -> Answer.Number(chooseNumber(q.player, q.label, q.min, q.max))
                else -> q.default()
            }

            var played = false
            private suspend fun askPriorityAction(player: PlayerId, state: GameState): PriorityAction =
                if (player == "P0" && !played && state.phase.startsWith("main")) {
                    played = true; PriorityAction.PlayPermanent(battery.lowered(LIFE), 0)
                } else PriorityAction.PassPriority
            private suspend fun chooseTarget(player: PlayerId, candidates: List<ObjectId>, state: GameState) = candidates.first()
            private suspend fun chooseNumber(player: PlayerId, prompt: String, min: Int, max: Int) = min
        }
        val end = runSync { Engine(input).playGame(s0, maxTurns = 2) }
        assertEq(23, end.lifeOf("P0"), end.log.joinToString("\n"))
    }

    // -- a one-shot modifier locks its values ---------------------------

    check("\"+X/+0 until end of turn, X = creatures\" is fixed as it resolves") {
        val (s1, b) = enterBattlefield(bear, "P0", newGame())
        val pump = Effect.ApplyModifier(creatures().yours(), listOf(CharOp.PlusPT(power = countOf(creatures()))))
        val end = runSync {
            Engine(Asking(listOf(cast(pump), PriorityAction.PassPriority, PriorityAction.PlayPermanent(bear.lowered(LIFE), 0), PriorityAction.PlayPermanent(bear.lowered(LIFE), 0)))).run(s1)
        }
        assertEq(3, end.battlefield.values.count { "Creature" in it.base.types }, "two more creatures entered")
        assertEq(3, end.characteristicsOf(b).power, "2 + the ONE creature there was at resolution")
    }

    // -- small robustness items -------------------------------------------

    check("an unending loop ends the game as a draw instead of throwing") {
        val forever = object : PlayerInput {
            override suspend fun ask(q: Question): Answer = when (q) {
                is Question.Priority -> Answer.Act(askPriorityAction(q.player, q.state))
                is Question.PickTarget -> Answer.Target(chooseTarget(q.player, q.candidates, q.state))
                is Question.PickNumber -> Answer.Number(chooseNumber(q.player, q.label, q.min, q.max))
                else -> q.default()
            }

            private suspend fun askPriorityAction(player: PlayerId, state: GameState): PriorityAction = cast(Effect.NoOp)
            private suspend fun chooseTarget(player: PlayerId, candidates: List<ObjectId>, state: GameState) = candidates.first()
            private suspend fun chooseNumber(player: PlayerId, prompt: String, min: Int, max: Int) = min
        }
        val end = runSync { Engine(forever).run(newGame()) }
        assertEq(setOf("P0", "P1"), end.losers, "everyone loses: a draw")
        assertTrue(end.log.last().contains("draw"), end.log.last())
    }

    check("a replacement applies at most once per event chain") {
        // A: +1 to any damage. B: double any damage. With a single `skip`,
        // A -> B -> A -> B ... never ended.
        fun damage(op: BinOp, n: Int) = held("P0", ReplacementDoc.Replace(
            EventPattern.Damaged(), Effect.Proceed(IntExpr.Bin(op, IntExpr.EventAmount, lit(n))),
        ))
        val wall = card("Wall", setOf("Creature"), baseChars = Characteristics("Wall", setOf("Creature"), mapOf("power" to 0, "toughness" to 50)))
        val (s0, id) = enterBattlefield(wall, "P0", newGame())
        val end = runSync {
            Engine(Asking(listOf(cast(Effect.DealDamage(lit(1), BoundTarget(id))))))
                .run(s0.copy(replacements = listOf(damage(BinOp.ADD, 1), damage(BinOp.MUL, 2))))
        }
        assertEq(4, end.battlefield.getValue(id).damageMarked, "(1 + 1) * 2, each once")
    }

    check("same-event triggers go on the stack active player first") {
        // Each player has "when a creature dies, gain 1". P1's was put into
        // play FIRST, so insertion order would stack P1's first; APNAP stacks
        // P0's (the active player's) first, so P1's resolves first.
        val mourner = CardDoc(faces = listOf(FaceDoc(
            "Mourner", setOf("Artifact"), triggers = listOf(TriggerDoc.CreatureDies(effect = Effect.GainLife(PlayerRef.You, lit(1)))),
        ))).build()
        var s = newGame()
        s = enterBattlefield(mourner, "P1", s).first
        s = enterBattlefield(mourner, "P0", s).first
        val (s1, victim) = enterBattlefield(bear, "P0", s)
        val end = runSync { Engine(Asking(listOf(cast(Effect.Destroy(BoundTarget(victim)))))).run(s1) }
        val gains = end.log.filter { "gains" in it }
        assertEq(listOf("P1 gains 1 life", "P0 gains 1 life"), gains, end.log.joinToString("\n"))
    }

    check("an unknown enum name in JSON is a load error, not a default") {
        assertEq(ShieldMode.REDUCTION, shieldModeOf("reduction"))
        assertTrue(runCatching { shieldModeOf("reductoin") }.isFailure, "a typo used to read back as INSTANCES")
    }

    check("leaving play to hand or library puts the card there") {
        var s = newGame()
        val (s1, a) = enterBattlefield(bear, "P0", s); s = s1
        val (s2, b) = enterBattlefield(bear, "P0", s); s = s2
        // "If it would die, return it to its owner's hand / library instead."
        fun bounce(id: ObjectId, to: HiddenZone) = held("P0", ReplacementDoc.Replace(
            EventPattern.Dies(filter = PermFilter(pinned = BoundTarget(id))), Effect.SendTo(BoundTarget(TRIGGER), to),
        ))
        val end = runSync {
            Engine(Asking(listOf(cast(Effect.Sequence(listOf(Effect.Destroy(BoundTarget(a)), Effect.Destroy(BoundTarget(b))))))))
                .run(s.copy(replacements = listOf(bounce(a, HiddenZone.HAND), bounce(b, HiddenZone.LIBRARY))))
        }
        assertEq(listOf(a), end.players.getValue("P0").hand.map { it.instanceId }, "bounced to hand")
        assertEq(b, end.players.getValue("P0").library.firstOrNull()?.instanceId, "and to the top of the library")
    }

    // -- triggers can read their event ------------------------------------
    // "That much", "it", "the creature that died" were all unsayable: a
    // trigger's effect was compiled once and ignored the event that fired it.

    val wall = card("Wall", setOf("Creature"), baseChars = Characteristics("Wall", setOf("Creature"), mapOf("power" to 0, "toughness" to 50)))
    fun watcher(t: TriggerDoc) = CardDoc(faces = listOf(FaceDoc("Watcher", setOf("Artifact"), triggers = listOf(t)))).build()

    check("\"whenever a creature you control is dealt damage, gain that much life\"") {
        val w = watcher(TriggerDoc.On(EventPattern.Damaged(creatures().yours()), Effect.GainLife(PlayerRef.You, IntExpr.EventAmount)))
        var s = enterBattlefield(w, "P0", newGame()).first
        val (s1, id) = enterBattlefield(wall, "P0", s); s = s1
        val end = runSync { Engine(Asking(listOf(cast(Effect.DealDamage(lit(3), BoundTarget(id)))))).run(s) }
        assertEq(23, end.lifeOf("P0"), end.log.joinToString("\n"))
    }

    check("\"whenever another creature enters, put a +1/+1 counter on it\"") {
        val w = watcher(TriggerDoc.On(
            EventPattern.Enters(setOf("Creature"), other = true),
            Effect.AddCounter("+1/+1", lit(1), BoundTarget(TRIGGER)),
        ))
        val s = enterBattlefield(w, "P0", newGame()).first
        val end = runSync { Engine(Asking(listOf(PriorityAction.PlayPermanent(bear.lowered(LIFE), 0)))).run(s) }
        val b = end.battlefield.values.first { it.base.name == "Bear" }
        assertEq(1, b.counter("+1/+1"), end.log.joinToString("\n"))
    }

    check("an event with no permanent leaves TRIGGER unbound: the effect does nothing") {
        val e = Effect.Destroy(BoundTarget(TRIGGER)).bindEvent(GameEvent.TurnBegan("P0", 2))
        assertEq(Effect.Destroy(BoundTarget(TRIGGER)), e)
        val dmg = GameEvent.DamageDealt(7, 4)
        assertEq(Effect.DealDamage(lit(4), BoundTarget(7)), Effect.DealDamage(IntExpr.EventAmount, BoundTarget(TRIGGER)).bindEvent(dmg))
    }

    check("a Delayed body reads the event that fires IT, not the trigger's") {
        val inner = Effect.GainLife(PlayerRef.You, IntExpr.EventAmount)
        val d = Effect.Delayed(EventPattern.PlayerDamaged(PlayerRef.Opponent), inner)
        assertEq(d, d.bindEvent(GameEvent.DamageDealt(7, 4)), "the outer event does not reach inside")
        val end = runSync {
            Engine(Asking(listOf(cast(d), PriorityAction.PassPriority, cast(Effect.DamageOpponent(lit(5)))))).run(newGame())
        }
        assertEq(25, end.lifeOf("P0"), end.log.joinToString("\n"))
    }

    check("the scope checker reports \"that much\" outside a trigger, and accepts it inside") {
        val doc = FaceDoc(
            "Odd", setOf("Instant"),
            castEffect = Effect.GainLife(PlayerRef.You, IntExpr.EventAmount),
            triggers = listOf(TriggerDoc.SelfDealsDamage(effect = Effect.GainLife(PlayerRef.You, IntExpr.EventAmount))),
        )
        val problems = doc.scopeProblems()
        assertEq(1, problems.size, problems.toString())
        assertTrue("cast effect" in problems[0] && "triggering event" in problems[0], problems[0])
    }

    check("one matcher: every named trigger kind IS its pattern") {
        // `OnYourPhase` and `On(OnPhase(p, You))` are the same trigger now,
        // and an empty type set means "any", as the Creator always said.
        val pairs = listOf(
            TriggerDoc.OnYourPhase("main", Effect.NoOp) to EventPattern.OnPhase("main", PlayerRef.You),
            TriggerDoc.CreatureDies(null, Effect.NoOp, types = emptySet()) to EventPattern.Dies(emptySet(), null),
            TriggerDoc.YouCastType(setOf("Instant"), Effect.NoOp) to EventPattern.Cast(setOf("Instant"), PlayerRef.You),
        )
        for ((t, p) in pairs) assertEq(p, t.pattern)
        val ctx = EvalContext(newGame(), "P0", source = 1)
        val died = GameEvent.LeavesPlay(9, "P1", HiddenZone.GRAVEYARD, setOf("Ship"))
        assertTrue(EventPattern.Dies(emptySet()).matches(died, ctx), "a Ship dying matches \"any type\"")
        assertTrue(!EventPattern.OnPhase("main", PlayerRef.You).matches(GameEvent.PhaseEnter("main", "P1"), ctx), "not on their turn")
        assertTrue(EventPattern.OnPhase("main").matches(GameEvent.PhaseEnter("main", "P1"), ctx), "whose = null is anyone's")
    }

    check("the Creator offers every EventPattern and keeps a trigger's settings on edit") {
        val all = ccgui.EVENT_KINDS.map { ccgui.eventOfKind(it) }
        assertEq(ccgui.EVENT_KINDS, all.map { ccgui.eventKind(it) }, "every label maps back")
        fun tag(p: EventPattern): Int = when (p) {
            is EventPattern.OnPhase -> 1; EventPattern.AnyTurnBegan -> 2; EventPattern.Never -> 3
            EventPattern.SelfEnters -> 4; EventPattern.SelfLeaves -> 5; EventPattern.SelfAttacks -> 6
            is EventPattern.Enters -> 7; is EventPattern.Cast -> 8; is EventPattern.CounterCrosses -> 9
            is EventPattern.SelfDealsDamage -> 10; is EventPattern.Dies -> 11; EventPattern.MovesIntoThisZone -> 12
            is EventPattern.Damaged -> 13; is EventPattern.PlayerDamaged -> 14; is EventPattern.OnCombatStep -> 15
        }
        assertEq((1..15).toSet(), all.map(::tag).toSet(), "every case is offered")
        all.forEach { assertTrue(ccgui.eventSummary(it).isNotBlank(), "$it has words") }
        // Editing the effect keeps the trigger's phase.
        val t = TriggerDoc.OnYourPhase("regroup", Effect.NoOp)
        assertEq("regroup", (t.withEffect(Effect.Draw(PlayerRef.You, lit(1))) as TriggerDoc.OnYourPhase).phase)
        val r = ccgui.retargetTrigger(Effect.Destroy(BoundTarget(SELF)), TRIGGER)
        assertEq(TRIGGER, ccgui.triggerSubjectOf(r))
        assertEq(SELF, ccgui.triggerSubjectOf(ccgui.retargetTrigger(r, SELF)))
    }

    // -- the running state is data ----------------------------------------
    // Triggers, statics, replacements, continuous effects and delayed
    // triggers each held closures, so no two states were ever equal and no
    // digest could be taken.

    check("the same game played twice ends in EQUAL states") {
        fun play(): GameState {
            val actions = listOf(
                PriorityAction.PlayPermanent(FieldMarshal.lowered(LIFE), 0), PriorityAction.PassPriority,  // a static
                PriorityAction.PlayPermanent(ScoutRider.lowered(LIFE), 0), PriorityAction.PassPriority,    // a trigger
                PriorityAction.PlayPermanent(SanctuaryField.lowered(LIFE), 0), PriorityAction.PassPriority, // a replacement
                cast(Effect.ApplyModifier(creatures().yours(), listOf(CharOp.PlusPT(lit(1), lit(0))))), PriorityAction.PassPriority,
                cast(Effect.Delayed(EventPattern.OnPhase("end"), Effect.Draw(PlayerRef.You, lit(1)))),
            )
            return runSync { Engine(Asking(actions)).run(newGame(libraries = mapOf("P0" to tokens(5)))) }
        }
        val a = play(); val b = play()
        assertTrue(a.continuousEffects.isNotEmpty() && a.triggeredAbilities.isNotEmpty() &&
            a.replacements.isNotEmpty() && a.delayedTriggers.isNotEmpty(), "every kind is in play:\n${a.log.joinToString("\n")}")
        assertEq(a, b, "structural equality is what a digest needs")
    }

    check("a static can name its own permanent (SELF) and it binds on entry") {
        val selfish = CardDoc(faces = listOf(FaceDoc(
            "Selfish", setOf("Creature"), fields = mapOf("power" to 1, "toughness" to 1),
            statics = listOf(StaticSpec(PermFilter(pinned = BoundTarget(SELF)), listOf(CharOp.PlusPT(lit(2), lit(0))))),
        ))).build()
        val (s1, id) = enterBattlefield(selfish, "P0", newGame())
        val (s2, other) = enterBattlefield(bear, "P0", s1)
        assertEq(3, s2.characteristicsOf(id).power, "this creature gets +2/+0")
        assertEq(2, s2.characteristicsOf(other).power, "and nothing else does")
    }

    check("an effect's controller is read when it is evaluated, not frozen in") {
        // Anthem: "creatures you control get +1/+1". Handing the EFFECT to P1
        // is one field now; a closure had P0 captured inside it.
        val (s1, _) = enterBattlefield(FieldMarshal.lowered(LIFE), "P0", newGame())
        val (s2, p1Bear) = enterBattlefield(bear, "P1", s1)
        assertEq(2, s2.characteristicsOf(p1Bear).power)
        val moved = s2.copy(continuousEffects = s2.continuousEffects.map { it.copy(controller = "P1") })
        assertEq(3, moved.characteristicsOf(p1Bear).power)
    }

    // -- declared order everywhere ---------------------------------------
    // Each of these used a map's or a set's iteration order, which is the
    // order things happened to be inserted: history, not a rule.

    /** The same state with its battlefield and trigger maps filled in REVERSE. */
    fun GameState.reversedMaps() = copy(
        battlefield = battlefield.entries.reversed().associate { it.key to it.value },
        triggeredAbilities = triggeredAbilities.entries.reversed().associate { it.key to it.value },
    )

    check("generic mana is paid from generic first, then typed mana by name") {
        val s = newGame().let { g ->
            g.copy(players = g.players + ("P0" to g.players.getValue("P0").copy(pool = linkedMapOf("R" to 1, "" to 1, "B" to 1))))
        }
        val end = runSync { Engine(Asking(listOf(PriorityAction.CastSpell(Effect.NoOp.lowered(LIFE), emptySet(), Cost(mana = mapOf("" to 2)))))).run(s) }
        // Insertion order would have spent R, then generic.
        assertEq(mapOf("R" to 1), end.players.getValue("P0").pool, end.log.joinToString("\n"))
    }

    check("a defaulted answer, and a for-each, follow id order, not insertion order") {
        var s = newGame()
        val ids = (1..3).map { val (s2, id) = enterBattlefield(bear, "P0", s); s = s2; id }
        val shuffled = s.reversedMaps()
        val end = runSync {
            Engine(Asking(listOf(cast(Effect.Choose(creatures(), Effect.Destroy(BoundTarget(CHOSEN))))), target = { -99 })).run(shuffled)
        }
        assertTrue(ids.first() !in end.battlefield && ids.drop(1).all { it in end.battlefield }, "the lowest id is the default")
        assertEq(ids, shuffled.inPlayIds)
    }

    check("same-controller, same-order triggers stack by source id") {
        fun mourner(n: Int) = CardDoc(faces = listOf(FaceDoc(
            "M$n", setOf("Artifact"), triggers = listOf(TriggerDoc.CreatureDies(effect = Effect.GainLife(PlayerRef.You, lit(n)))),
        ))).build()
        var s = newGame()
        s = enterBattlefield(mourner(1), "P0", s).first
        s = enterBattlefield(mourner(2), "P0", s).first
        val (s1, victim) = enterBattlefield(bear, "P0", s)
        fun gains(st: GameState) = runSync { Engine(Asking(listOf(cast(Effect.Destroy(BoundTarget(victim)))))).run(st) }
            .log.filter { "gains" in it }
        assertEq(gains(s1), gains(s1.reversedMaps()), "the map's fill order does not decide")
        assertEq(listOf("P0 gains 2 life", "P0 gains 1 life"), gains(s1), "the higher id went on last, so it resolves first")
    }

    check("where a card's types disagree, the type the GAME declares first wins") {
        val rules = Rules(types = BUILTIN_TYPES_CORE + listOf(
            TypeDef("Ship", zoneOfPlay = "lane"), TypeDef("Station", zoneOfPlay = "orbit"),
        ).associateBy { it.name })
        assertEq("lane", rules.defaultZoneDef(linkedSetOf("Station", "Ship")))
        assertEq("lane", rules.defaultZoneDef(linkedSetOf("Ship", "Station")))
    }

    // -- questions as data -----------------------------------------------

    check("a target question is its candidate list, and any answer from it stands") {
        var s = newGame()
        val ids = (1..3).map { val (s2, id) = enterBattlefield(bear, "P0", s); s = s2; id }
        var offered: List<ObjectId> = emptyList()
        val input = Asking(
            listOf(cast(Effect.Choose(creatures(), Effect.Destroy(BoundTarget(CHOSEN))))),
            target = { c -> offered = c; c.last() },
        )
        val end = runSync { Engine(input).run(s) }
        assertEq(ids, offered, "the client is handed the list, in id order")
        assertTrue(ids.last() !in end.battlefield && end.log.none { "not a legal choice" in it }, "and its pick stands")
    }

    // -- a written contract ----------------------------------------------
    // The schema itself is generated and checked in SchemaTest.kt.

    check("enums are written lowercase, and every older spelling still reads") {
        val e = Effect.MoveTop(PlayerRef.You, lit(1), HiddenZone.LIBRARY_BOTTOM)
        assertTrue("\"library_bottom\"" in effectToJson(e), effectToJson(e))
        assertEq(e, effectFromJson("""{"op":"moveTop","who":"you","count":1,"to":"LIBRARY_BOTTOM"}"""), "the Kotlin name, as files used to say")
        assertEq(ZoneScope.PER_PLAYER, zoneScopeOf("perPlayer"))
        assertEq(ZoneScope.PER_PLAYER, zoneScopeOf("per_player"))
        assertEq("per_player", zoneScopeStr(ZoneScope.PER_PLAYER))
        assertTrue(runCatching { zoneScopeOf("perplayer") }.isFailure, "anything else is still a load error")
        val shared = TurnStructure(listOf(PhaseSpec("main")), TurnMode.SHARED)
        assertEq(shared, turnStructureOf(Json.parse(turnStructureToJson(shared))))
        assertTrue(runCatching { turnStructureOf(Json.parse("""{"mode":"sharde","phases":[]}""")) }.isFailure,
            "an unknown turn mode used to read as per-player without a word")
    }

    // -- verbs for the stack and the board --------------------------------

    check("a counterspell counters the spell it answers, and its card is spent") {
        val bolt = CardRef(90, "Bolt")
        val s = newGame().let { g -> g.copy(players = g.players + ("P1" to g.players.getValue("P1").copy(hand = listOf(bolt)))) }
        val end = runSync {
            Engine(ScriptedInput.of(
                "P1" to listOf(PriorityAction.CastSpell(Effect.DamageOpponent(lit(5)).lowered(LIFE), setOf("Instant"), from = CardSource(CastZone.Std(HiddenZone.HAND), 90))),
                "P0" to listOf(PriorityAction.PassPriority, PriorityAction.CastSpell(Effect.CounterSpell(setOf("Instant")).lowered(LIFE), setOf("Instant"))),
                // The Bolt is the stack's first object (the counter targets it).
                targets = listOf(1),
            )).run(s)
        }
        assertEq(20, end.lifeOf("P0"), end.log.joinToString("\n"))
        assertEq(listOf(bolt), end.players.getValue("P1").graveyard, "the countered card went to its owner's graveyard")
        assertTrue(end.log.any { "is countered" in it })
    }

    check("a counterspell with nothing to answer can't be cast (it targets)") {
        val end = runSync { Engine(Asking(listOf(cast(Effect.CounterSpell())))).run(newGame()) }
        assertTrue(end.log.any { "no legal target" in it }, end.log.joinToString("\n"))
    }

    check("exhaust and ready") {
        val (s1, id) = enterBattlefield(bear, "P0", newGame())
        val tapped = runSync { Engine(Asking(listOf(cast(Effect.Tap(BoundTarget(id)))))).run(s1) }
        assertTrue(tapped.battlefield.getValue(id).exhausted)
        val readied = runSync { Engine(Asking(listOf(cast(Effect.Tap(BoundTarget(id), untap = true))))).run(tapped) }
        assertTrue(!readied.battlefield.getValue(id).exhausted)
    }

    check("return to hand goes to the OWNER's hand, even after a change of control") {
        val (s1, id) = enterBattlefield(bear, "P1", newGame())
        val stolen = runSync { Engine(Asking(listOf(cast(Effect.GainControl(BoundTarget(id)))))).run(s1) }
        assertEq("P0", stolen.battlefield.getValue(id).controller)
        val end = runSync { Engine(Asking(listOf(cast(Effect.SendTo(BoundTarget(id)))))).run(stolen) }
        assertEq(listOf(id), end.players.getValue("P1").hand.map { it.instanceId }, "back to P1, who owns it")
        assertTrue(end.players.getValue("P0").hand.isEmpty())
    }

    check("gaining control moves the permanent's abilities with it, and ends with its duration") {
        // Field Marshal: "other creatures you control get +1/+1". Stolen until
        // end of turn, it pumps the THIEF's creatures, then goes home.
        var s = newGame()
        val (s1, marshal) = enterBattlefield(FieldMarshal.lowered(LIFE), "P1", s); s = s1
        val (s2, mine) = enterBattlefield(bear, "P0", s); s = s2
        val (s3, theirs) = enterBattlefield(bear, "P1", s); s = s3
        val stolen = runSync { Engine(Asking(listOf(cast(Effect.GainControl(BoundTarget(marshal), Duration.EndOfTurn))))).run(s) }
        assertEq(3, stolen.characteristicsOf(mine).power, "the thief's bear is pumped")
        assertEq(2, stolen.characteristicsOf(theirs).power, "the owner's is not")
        val next = runSync { Engine(Asking(emptyList())).playGame(stolen, maxTurns = 2) }
        assertEq("P1", next.battlefield[marshal]?.controller, next.log.takeLast(30).joinToString("\n"))
        assertEq(2, next.characteristicsOf(mine).power)
    }

    // -- references, reads and filters ------------------------------------

    check("\"its controller loses 2\" hits the controller, whoever that is") {
        val (s1, id) = enterBattlefield(bear, "P1", newGame())
        val e = Effect.AsPlayer(PlayerRef.ControllerOf(BoundTarget(id)), Effect.GainLife(PlayerRef.You, lit(-2)))
        val end = runSync { Engine(Asking(listOf(cast(e)))).run(s1) }
        assertEq(18, end.lifeOf("P1")); assertEq(20, end.lifeOf("P0"))
    }

    check("\"target player draws two\" asks which player, and the active player is who it says") {
        val libs = mapOf("P0" to tokens(3), "P1" to tokens(3))
        val chosen = runSync {
            Engine(Asking(listOf(cast(Effect.AsPlayer(PlayerRef.Chosen, Effect.Draw(PlayerRef.You, lit(2))))), modes = listOf(listOf(1))))
                .run(newGame(libraries = libs))
        }
        assertEq(2, chosen.players.getValue("P1").hand.size, "P0 chose P1")
        val active = runSync { Engine(Asking(listOf(cast(Effect.AsPlayer(PlayerRef.Active, Effect.Draw(PlayerRef.You, lit(1))))))).run(newGame(libraries = libs)) }
        assertEq(1, active.players.getValue("P0").hand.size)
    }

    check("reads: any player counter, a zone's size, and DIV / MIN / MAX") {
        val g = newGame(counters = mapOf(LIFE to 20, "store" to 7))
        val s = g.copy(players = g.players + ("P0" to g.players.getValue("P0").copy(graveyard = tokens(5))))
        val ctx = EvalContext(s, "P0")
        assertEq(7, IntExpr.PlayerCounter(PlayerRef.You, "store").eval(ctx))
        assertEq(5, IntExpr.ZoneSize(PlayerRef.You, HiddenZone.GRAVEYARD).eval(ctx))
        assertEq(2, IntExpr.Bin(BinOp.DIV, lit(5), lit(2)).eval(ctx))
        assertEq(-3, IntExpr.Bin(BinOp.DIV, lit(-5), lit(2)).eval(ctx), "floor, not truncation")
        assertEq(0, IntExpr.Bin(BinOp.DIV, lit(5), lit(0)).eval(ctx), "a zero divisor reads 0, never throws")
        assertEq(3, IntExpr.Bin(BinOp.MIN, lit(3), lit(9)).eval(ctx))
        assertEq(9, IntExpr.Bin(BinOp.MAX, lit(3), lit(9)).eval(ctx))
    }

    check("a filter's where: \"creatures with flying\", \"non-Ship\", \"power 3 or more\", \"exhausted\"") {
        val subj = BoundTarget(SUBJECT)
        var s = newGame()
        fun put(name: String, p: Int, kw: Set<String> = emptySet(), types: Set<String> = setOf("Creature")): ObjectId {
            val (s2, id) = enterBattlefield(card(name, types, baseChars = Characteristics(name, types, mapOf("power" to p, "toughness" to 1), kw)), "P0", s)
            s = s2; return id
        }
        val bird = put("Bird", 1, setOf("flying"))
        val ogre = put("Ogre", 4)
        val ship = put("Ship", 3, types = setOf("Creature", "Ship"))
        s = s.copy(battlefield = s.battlefield + (ogre to s.battlefield.getValue(ogre).copy(exhausted = true)))
        fun matching(w: BoolExpr) = s.inPlayIds.filter { creatures().copy(where = w).matches(EvalContext(s, "P0"), it) }
        assertEq(listOf(bird), matching(BoolExpr.HasKeyword(subj, "flying")))
        assertEq(listOf(bird, ogre), matching(BoolExpr.Not(BoolExpr.IsType(subj, setOf("Ship")))))
        assertEq(listOf(ogre, ship), matching(BoolExpr.Cmp(IntExpr.TargetField(subj, "power"), CmpOp.GTE, lit(3))))
        assertEq(listOf(ogre), matching(BoolExpr.IsExhausted(subj)))
        // Through the engine: "destroy each creature with power 3 or more".
        val wrath = Effect.ForEach(creatures().copy(where = BoolExpr.Cmp(IntExpr.TargetField(subj, "power"), CmpOp.GTE, lit(3))), Effect.Destroy(BoundTarget(EACH)))
        val end = runSync { Engine(Asking(listOf(cast(wrath)))).run(s) }
        assertEq(listOf(bird), end.inPlayIds)
    }

    check("SUBJECT outside a where is reported, and the Creator's where presets round-trip") {
        val doc = FaceDoc("Odd", setOf("Instant"), castEffect = Effect.Destroy(BoundTarget(SUBJECT)))
        assertTrue(doc.scopeProblems().any { "being tested" in it }, doc.scopeProblems().toString())
        val inWhere = FaceDoc("Fine", setOf("Instant"), castEffect = Effect.ForEach(
            creatures().copy(where = BoolExpr.IsToken(BoundTarget(SUBJECT))), Effect.Destroy(BoundTarget(EACH))))
        assertEq(emptyList<String>(), inWhere.scopeProblems())
        for (k in ccgui.WHERE_KINDS) assertEq(k, ccgui.whereKind(ccgui.whereOfKind(k)), "WHERE_KINDS round-trip: $k")
        for (k in ccgui.PLAYER_REFS) assertEq(k, ccgui.playerRefKind(ccgui.playerRefOfKind(k, CHOSEN)))
        for (op in BinOp.entries) assertEq(op, ccgui.binOpOf(ccgui.binOpLabel(op)))
        val f = creatures().copy(where = BoolExpr.Cmp(IntExpr.TargetField(BoundTarget(SUBJECT), "power"), CmpOp.GTE, lit(3)))
        assertEq("any Creature where its power ≥ 3", ccgui.filterSummary(f))
    }
}
