package ccg

import ccgui.CardArea
import ccgui.HeuristicPilot
import ccgui.PassingPilot
import ccgui.PlaySession
import ccgui.SeatedPilots
import ccgui.opening
import ccgui.boundTo
import ccgui.CardLocation
import ccgui.cardAreaOf
import ccgui.diagnosticsByArea
import ccgui.locate
import ccgui.encode
import ccgui.lbl
import ccgui.recordCorpusCase
import ccgui.replayCorpusCase
import ccgui.TABLE_MAX_TURNS
import ccgui.undecked
import ccgui.asCorpusCase
import ccgui.tableRun

// ---------------------------------------------------------------------------
// Regressions from an engine architecture review. Each section is one
// finding; each check pins it so the fix cannot quietly come undone.
// ---------------------------------------------------------------------------

/** Scripted per player, and records every question in the order it is
 *  asked -- some checks are about ORDER, not only about outcome. */
private class Recording(
    private val actions: Map<PlayerId, List<PriorityAction>>,
    modes: List<List<Int>> = emptyList(),
    private val number: Int = 0,
) : PlayerInput {
    override suspend fun ask(q: Question): Answer = when (q) {
        is Question.Priority -> Answer.Act(askPriorityAction(q.player, q.state))
        is Question.PickTarget -> Answer.Target(chooseTarget(q.player, q.candidates, q.state))
        is Question.PickNumber -> Answer.Number(chooseNumber(q.player, q.label, q.min, q.max))
        is Question.PickMode -> Answer.Modes(chooseMode(q.player, q.options, q.pick, q.state))
        else -> q.default()
    }

    private val queues = actions.mapValues { ArrayDeque(it.value) }
    private val modeQueue = ArrayDeque(modes)
    /** "<kind> <player>", in the order asked. */
    val asked = mutableListOf<String>()
    /** The top of the stack as each opponent first saw it. */
    val seenOnStack = mutableListOf<Effect>()
    /** The mode lists offered, as sizes. */
    val offered = mutableListOf<Int>()

    private suspend fun askPriorityAction(player: PlayerId, state: GameState): PriorityAction {
        (state.stack.lastOrNull() as? SpellOnStack)?.let { if (it.controller != player) seenOnStack += it.effect }
        return queues[player]?.removeFirstOrNull() ?: PriorityAction.PassPriority
    }
    private suspend fun chooseTarget(player: PlayerId, candidates: List<ObjectId>, state: GameState): ObjectId {
        asked += "target $player"; return candidates.first()
    }
    private suspend fun chooseNumber(player: PlayerId, prompt: String, min: Int, max: Int): Int {
        asked += "number $player"; return number.coerceIn(min, max)
    }
    private suspend fun chooseMode(player: PlayerId, options: List<ModeOption>, pick: Int, state: GameState): List<Int> {
        asked += "mode $player"; offered += options.size
        return modeQueue.removeFirstOrNull() ?: (0 until pick).toList()
    }
}

private fun GameState.pool(pid: PlayerId, n: Int): GameState =
    copy(players = players + (pid to players.getValue(pid).copy(pool = mapOf("" to n))))

private fun beast(name: String, power: Int) = card(
    name, setOf("Creature"),
    baseChars = Characteristics(name, setOf("Creature"), mapOf("power" to power, "toughness" to power)),
)

private fun board(vararg perms: Pair<PlayerId, CardDefinition>): GameState {
    var s = newGame(libraries = mapOf("P0" to tokens(6), "P1" to tokens(6)))
    for ((ctrl, c) in perms) s = enterBattlefield(c, ctrl, s).first
    return s
}

private fun instant(e: Effect, cost: Cost = Cost()) = PriorityAction.CastSpell(e.lowered(LIFE), setOf("Instant"), cost)

internal fun archReviewChecks() {
    println()
    println("Architecture review -- regressions")

    // -- X before targets -----------------------------------------------
    // "Destroy target creature with power X or less." X was asked AFTER the
    // target was bound, so the filter read X = 0.
    val powerAtMostX = creatures().copy(where = IntExpr.TargetField(BoundTarget(SUBJECT), "power") lte x)
    val smallFry = Effect.Choose(powerAtMostX, Effect.Destroy(BoundTarget(CHOSEN)))

    check("a target filter that reads X sees the chosen X") {
        val s0 = board("P1" to beast("Ox", 3)).pool("P0", 3)
        val input = Recording(mapOf("P0" to listOf(instant(smallFry, Cost(usesX = true)))), number = 3)
        val end = runSync { Engine(input).run(s0) }
        assertTrue(end.battlefield.isEmpty(), "X = 3 reaches the 3-power Ox:\n${end.log.joinToString("\n")}")
        assertEq(listOf("number P0", "target P0"), input.asked, "X is asked, then the target")
    }

    check("legality offers an X spell whose targets need X > 0") {
        val s0 = board("P1" to beast("Ox", 3)).pool("P0", 3)
        val verdict = legality(Rules.DEFAULT, s0, "P0", instant(smallFry, Cost(usesX = true)))
        assertEq(Legality.Legal, verdict, "at X = 0 nothing matches, but X = 3 does")
    }

    check("an X that leaves no target refuses the cast and pays nothing") {
        val s0 = board("P1" to beast("Ox", 3)).pool("P0", 3)
        val input = Recording(mapOf("P0" to listOf(instant(smallFry, Cost(usesX = true)))), number = 1)
        val end = runSync { Engine(input).run(s0) }
        assertEq(1, end.battlefield.size, "nothing destroyed")
        assertEq(3, end.players.getValue("P0").pool[""], "nothing paid")
    }

    // -- ForEachPlayer is APNAP ----------------------------------------
    check("ForEachPlayer starts from the active player, not the caster") {
        val eachSacrifices = Effect.ForEachPlayer(Effect.Sacrifice(PlayerRef.You, lit(1), creatures()))
        val s0 = board("P0" to beast("Ox", 2), "P1" to beast("Elk", 2))
        assertEq("P0", s0.activePlayer)
        val input = Recording(mapOf("P1" to listOf(instant(eachSacrifices))))
        val end = runSync { Engine(input).run(s0) }
        assertEq(listOf("target P0", "target P1"), input.asked, "P1 cast it on P0's turn:\n${end.log.joinToString("\n")}")
        assertTrue(end.battlefield.isEmpty(), "and both sacrificed")
    }

    // -- player damage is the event's consequence ----------------------
    val bolt = Effect.DamageOpponent(lit(5))

    check("a replacement that swallows player damage leaves life untouched") {
        val swallow = held("P1", ReplacementDoc.Replace(EventPattern.PlayerDamaged(), Effect.NoOp))
        val end = runSync { Engine(Recording(mapOf("P0" to listOf(instant(bolt))))).run(board().copy(replacements = listOf(swallow))) }
        assertEq(20, end.players.getValue("P1").counter(LIFE), end.log.joinToString("\n"))
    }

    check("a player shield takes off exactly what it prevents") {
        val shield = ActiveReplacement(
            source = null, controller = "P1", doc = ReplacementDoc.Replace(EventPattern.PlayerDamaged(PlayerRef.You), Effect.NoOp),
            duration = Duration.Permanent, id = 900, budget = Budget(2, Spend.POINTS),
        )
        val s0 = board().copy(shields = listOf(shield))
        val end = runSync { Engine(Recording(mapOf("P0" to listOf(instant(bolt))))).run(s0) }
        assertEq(17, end.players.getValue("P1").counter(LIFE), end.log.joinToString("\n"))
        assertTrue(end.shields.isEmpty(), "the shield is spent")
    }

    check("a trigger on player damage sees the life already lost") {
        // "Whenever you are dealt damage, draw a card per 5 life you are
        // missing." The trigger resolves after the event either way; what
        // matters is that the loss is recorded once and only once.
        val end = runSync { Engine(Recording(mapOf("P0" to listOf(instant(bolt), instant(bolt))))).run(board()) }
        assertEq(10, end.players.getValue("P1").counter(LIFE), end.log.joinToString("\n"))
    }

    // -- modes and many-targets bind at announcement -------------------
    val destroyOne = Effect.Choose(creatures(), Effect.Destroy(BoundTarget(CHOSEN)))
    val draw = Effect.Draw(PlayerRef.You, lit(1))
    val modal = Effect.ChooseMode(listOf(destroyOne, draw))

    check("a mode is chosen when the spell is cast, before anyone responds") {
        val s0 = board("P1" to beast("Ox", 2))
        val input = Recording(mapOf("P0" to listOf(instant(modal))), modes = listOf(listOf(0)))
        val end = runSync { Engine(input).run(s0) }
        assertEq(listOf("mode P0", "target P0"), input.asked, "mode, then its target")
        assertTrue(input.seenOnStack.isNotEmpty() && input.seenOnStack.none { it is Effect.ChooseMode },
            "P1 saw the chosen mode, not the menu: ${input.seenOnStack}")
        assertTrue(end.battlefield.isEmpty(), "and it resolved")
    }

    check("a mode with no legal target is not offered") {
        val input = Recording(mapOf("P0" to listOf(instant(modal))))
        runSync { Engine(input).run(board()) }
        assertEq(listOf(1), input.offered, "only 'draw' could bind")
    }

    check("a modal spell with no bindable mode can't be cast") {
        val bothTarget = Effect.ChooseMode(listOf(destroyOne, Effect.Choose(creatures(), Effect.Tap(BoundTarget(CHOSEN)))))
        assertTrue(legality(Rules.DEFAULT, board(), "P0", instant(bothTarget)) is Legality.Denied)
    }

    check("modes come before X: a mode without X asks no X") {
        val xOrDraw = Effect.ChooseMode(listOf(Effect.DamageOpponent(x), draw))
        val input = Recording(mapOf("P0" to listOf(instant(xOrDraw))), modes = listOf(listOf(1)))
        runSync { Engine(input).run(board()) }
        assertEq(listOf("mode P0"), input.asked)
    }

    check("ChooseMany binds every target at cast") {
        val twoDie = Effect.ChooseMany(creatures(), count = lit(2), body = Effect.Destroy(BoundTarget(EACH)))
        val s0 = board("P1" to beast("Ox", 2), "P1" to beast("Elk", 2))
        val input = Recording(mapOf("P0" to listOf(instant(twoDie))))
        val end = runSync { Engine(input).run(s0) }
        assertTrue(input.seenOnStack.isNotEmpty() && input.seenOnStack.none { it is Effect.ChooseMany },
            "P1 saw the targets: ${input.seenOnStack}")
        assertEq(listOf("target P0", "target P0"), input.asked)
        assertTrue(end.battlefield.isEmpty())
    }

    check("ChooseMany needing more targets than exist can't be cast") {
        val twoDie = Effect.ChooseMany(creatures(), count = lit(2), body = Effect.Destroy(BoundTarget(EACH)))
        assertTrue(legality(Rules.DEFAULT, board("P1" to beast("Ox", 2)), "P0", instant(twoDie)) is Legality.Denied)
    }

    check("a divided total is split at cast, the last target taking the rest") {
        val split = Effect.ChooseMany(
            creatures(), count = lit(2), divide = lit(5),
            body = Effect.DealDamage(IntExpr.Share, BoundTarget(EACH)),
        )
        val s0 = board("P1" to beast("Ox", 9), "P1" to beast("Elk", 9))
        val input = Recording(mapOf("P0" to listOf(instant(split))), number = 2)
        val end = runSync { Engine(input).run(s0) }
        assertEq(listOf(2, 3), end.battlefield.values.sortedBy { it.id }.map { it.damageMarked })
        assertEq(listOf("target P0", "target P0", "number P0"), input.asked)
    }

    // -- one compile path ----------------------------------------------
    val bundled = listOf(EPR_SKIRMISH, CORE_BUNDLE)

    check("compile() builds the same rules as rules(), and reports what diagnostics() reports") {
        for (g in bundled) {
            val c = g.compile()
            // `Rules` is not a data class; compare what it is made of.
            val r = g.rules()
            assertEq(r.cards, c.rules.cards, g.name)
            assertEq(r.types, c.rules.types, g.name)
            assertEq(r.zones, c.rules.zones, g.name)
            assertEq(r.turn, c.rules.turn, g.name)
            assertEq(g.diagnostics(), c.diagnostics, g.name)
            assertEq(g.problems(), c.diagnostics.map { it.message }, g.name)
        }
    }

    // -- diagnostics are data, and say where ---------------------------
    val xTrigger = CardDoc(
        faces = listOf(FaceDoc("Mislaid", setOf("Artifact"), triggers = listOf(TriggerDoc.SelfEnters(Effect.Draw(PlayerRef.You, x))))),
        id = "mislaid",
    )
    val flawed = GameDoc(
        sets = listOf(SetDoc("A", listOf(CardDoc(id = "ok"))), SetDoc("B", listOf(xTrigger))),
        decks = listOf(DeckDoc("D", entries = listOf(DeckEntry("nobody")))),
    )

    check("a card diagnostic names the card, the face and the part") {
        val d = flawed.diagnostics().single { it.code == "free-x" }
        assertEq(Diagnostic(DiagCode.FREE_X, "mislaid", 0, "triggers[0]", d.message), d)
        assertTrue(d.message.startsWith("\"Mislaid\" trigger 1"), d.message)
    }

    check("a game diagnostic names the game's part and no card") {
        val d = flawed.diagnostics().single { it.code == "unknown-card" }
        assertEq(null, d.cardKey)
        assertEq("decks[0]", d.path)
    }

    check("a card diagnostic leads to its card; a game one leads nowhere") {
        val ds = flawed.diagnostics()
        assertEq(CardLocation(1, 0, 0), locate(flawed, ds.single { it.code == "free-x" }))
        assertEq(null, locate(flawed, ds.single { it.code == "unknown-card" }))
    }

    check("each path lands in the editor block that edits it") {
        assertEq(CardArea.CAST, cardAreaOf("castEffect"))
        assertEq(CardArea.TRIGGERS, cardAreaOf("triggers[3]"))
        assertEq(CardArea.STATICS, cardAreaOf("ruleMods[0]"))
        assertEq(CardArea.STATICS, cardAreaOf("statics[1]"))
        assertEq(CardArea.ACTIVATED, cardAreaOf("activated[0]"))
        assertEq(CardArea.FACE, cardAreaOf("keywords"))
        assertEq(CardArea.FACE, cardAreaOf(""))
        val byArea = diagnosticsByArea(flawed.diagnostics(), xTrigger, 0)
        assertEq(setOf(CardArea.TRIGGERS), byArea.keys)
        assertEq(emptyMap<CardArea, List<Diagnostic>>(), diagnosticsByArea(flawed.diagnostics(), xTrigger, 1), "not on another face")
    }

    check("every card diagnostic in the bundled games leads to its card") {
        // The invariant, not the cases: a path or key the Creator cannot
        // follow is a link that goes nowhere.
        for (g in bundled + flawed) {
            g.diagnostics().filter { it.cardKey != null }.forEach { d ->
                assertTrue(locate(g, d) != null, "${g.name}: $d")
            }
        }
    }

    // -- every name resolves against a declaration ----------------------
    fun unknowns(g: GameDoc) = g.diagnostics().filter { it.code.startsWith("unknown-") && it.code != "unknown-card" }

    check("the bundled games declare their counters and name nothing undeclared") {
        for (g in bundled + SAMPLE_GAMES) {
            assertTrue(g.rules.counterKinds != null, "${g.name} declares its counter kinds")
            assertEq(emptyList<Diagnostic>(), unknowns(g), g.name)
        }
    }

    // One misspelling of each kind of name, each in a place a real card puts it.
    val typos = CardDoc(
        id = "typos",
        faces = listOf(FaceDoc(
            "Typos", setOf("Instant"),
            castEffect = Effect.Sequence(listOf(
                Effect.ForEach(ofType("Shp"), Effect.AddCounter("sheild", lit(1), BoundTarget(EACH))),
                Effect.GainLife(PlayerRef.You, IntExpr.SelfField("powr"), counter = "gld"),
                Effect.Choose(creatures(), Effect.Sequence(listOf(
                    Effect.MovePermanent(BoundTarget(CHOSEN), ZoneRef("lnae")),
                    Effect.PreventDamage(BoundTarget(CHOSEN), lit(1), onlyStep = "fsat"),
                    Effect.SetCombatMode(BoundTarget(CHOSEN), "defence"),
                ))),
            )),
            triggers = listOf(TriggerDoc.OnYourPhase("mian", Effect.NoOp)),
        )),
    )
    val typoGame = GameDoc(
        sets = listOf(SetDoc("S", listOf(typos))),
        rules = RulesDoc(counterKinds = listOf(CounterKindDef("shield"))),
    )

    check("every kind of name is checked: one typo of each is reported") {
        assertEq(
            setOf("unknown-type", "unknown-field", "unknown-counter", "unknown-player-counter",
                "unknown-phase", "unknown-zone", "unknown-step", "unknown-stance"),
            unknowns(typoGame).map { it.code }.toSet(),
            unknowns(typoGame).joinToString("\n") { it.message },
        )
    }

    check("an unknown name says which card, which part, and what it named") {
        val d = unknowns(typoGame).single { it.code == "unknown-phase" }
        assertEq(Diagnostic(DiagCode.UNKNOWN_PHASE, "typos", 0, "triggers[0]", d.message), d)
        assertTrue("\"mian\"" in d.message, d.message)
        assertEq("castEffect", unknowns(typoGame).single { it.code == "unknown-counter" }.path)
    }

    check("a type or field a card carries is declared by that card") {
        // "Flagship" in EPR Skirmish is a tag no TypeDef declares; a filter on
        // it is not a typo.
        val tagged = CardDoc(id = "t", faces = listOf(FaceDoc("Scout Ship", setOf("Creature", "Scout"), fields = mapOf("range" to 2))))
        val reader = CardDoc(id = "r", faces = listOf(FaceDoc(
            "Seeker", setOf("Instant"),
            castEffect = Effect.ForEach(ofType("Scout"), Effect.GainLife(PlayerRef.You, IntExpr.TargetField(BoundTarget(EACH), "range"))),
        )))
        assertEq(emptyList<Diagnostic>(), unknowns(GameDoc(sets = listOf(SetDoc("S", listOf(tagged, reader))), rules = RulesDoc(counterKinds = emptyList()))))
    }

    check("a token's template is checked too") {
        val maker = CardDoc(id = "m", faces = listOf(FaceDoc(
            "Maker", setOf("Instant"),
            castEffect = Effect.CreateToken(Characteristics("Drone", setOf("Drnoe"))),
        )))
        val ds = unknowns(GameDoc(sets = listOf(SetDoc("S", listOf(maker))), rules = RulesDoc(counterKinds = emptyList())))
        assertEq(listOf("unknown-type"), ds.map { it.code }, ds.toString())
    }

    check("a game that never declared counters is not checked for them, and declares what it uses once") {
        val legacy = typoGame.copy(rules = typoGame.rules.copy(counterKinds = null))
        assertTrue(unknowns(legacy).none { it.code == "unknown-counter" })
        val migrated = legacy.withDeclaredCounters()
        assertEq(listOf(CounterKindDef("sheild")), migrated.rules.counterKinds, "what it used, builtins left out")
        assertEq(migrated, migrated.withDeclaredCounters(), "idempotent")
        // From then on a NEW misspelling is reported, not absorbed.
        val later = migrated.copy(sets = migrated.sets + SetDoc("T", listOf(CardDoc(id = "x", faces = listOf(FaceDoc(
            "Later", setOf("Instant"), castEffect = Effect.AddCounter("shleid", lit(1), BoundTarget(SELF)),
        ))))))
        assertEq(listOf("shleid"), unknowns(later).filter { it.code == "unknown-counter" }.map { it.message.substringAfter("counter kind \"").substringBefore('"') })
    }

    check("a declaration round-trips, and 'never declared' stays distinct from 'declares none'") {
        for (ks in listOf(null, emptyList(), listOf(CounterKindDef("shield")))) {
            val g = GameDoc(rules = RulesDoc(counterKinds = ks))
            assertEq(ks, gameDocFromJson(gameDocToJson(g)).rules.counterKinds)
        }
    }

    // -- player damage: a game may have none ---------------------------------
    // EPR Skirmish and Core damaged players into "life", a counter neither
    // declares; their loss condition is the Station. Now they say "none".
    val burn = CardDoc(id = "burn", faces = listOf(FaceDoc("Burn", setOf("Instant"), castEffect = Effect.DamageOpponent(lit(2)))))
    val drain = CardDoc(id = "drain", cost = Cost(payLife = 2), faces = listOf(FaceDoc(
        "Drain", setOf("Instant"), castEffect = Effect.Draw(PlayerRef.You, IntExpr.LifeOf(PlayerRef.You)),
    )))

    check("damage counter: EPR Skirmish and Core have no player damage, and use none") {
        for (g in bundled) {
            assertEq(null, g.rules.damageCounter, g.name)
            assertEq(emptyList<Diagnostic>(), g.diagnostics().filter { it.code == "no-player-damage" }, g.name)
        }
    }

    check("damage counter: an undeclared one is reported, at the rules") {
        val g = GameDoc(rules = RulesDoc(playerCounters = listOf(PlayerCounterDef("store", 0)), counterKinds = emptyList()))
        val d = unknowns(g).single()
        assertEq(Diagnostic(DiagCode.UNKNOWN_PLAYER_COUNTER, null, null, "rules.damageCounter", d.message), d)
        assertEq(emptyList<Diagnostic>(), unknowns(g.copy(rules = g.rules.copy(damageCounter = null))), "none needs no counter")
    }

    check("damage counter: with none, every card that damages a player or reads their damage is reported") {
        val g = GameDoc(sets = listOf(SetDoc("S", listOf(burn, drain))), rules = RulesDoc(damageCounter = null, counterKinds = emptyList()))
        val ds = g.diagnostics().filter { it.code == "no-player-damage" }
        assertEq(setOf("burn" to "castEffect", "drain" to "cost", "drain" to "castEffect"), ds.map { it.cardKey to it.path }.toSet(), ds.toString())
        // ...and with a counter, none of them is a problem.
        assertEq(emptyList<Diagnostic>(), g.copy(rules = RulesDoc(counterKinds = emptyList())).diagnostics().filter { it.code == "no-player-damage" })
    }

    check("damage counter: with none, player damage does nothing, payLife can't be paid, LifeOf reads 0") {
        val rules = Rules(playerCounters = listOf(PlayerCounterDef("store", 5)), damageCounter = null)
        val s = newGame(counters = mapOf("store" to 5), damageCounter = null)
        val after = runSync { Engine(ScriptedInput.of("P0" to listOf(PriorityAction.CastSpell(Effect.DamageOpponent(lit(4)).lowered(LIFE), emptySet()))), rules = rules).run(s) }
        assertEq(mapOf("store" to 5), after.players.getValue("P1").counters, "no counter touched, none invented")
        assertTrue(after.log.any { "takes no damage" in it }, after.log.toString())
        assertEq(false, s.canAfford("P0", Cost(payLife = 1)), "payLife")
        assertEq(true, s.canAfford("P0", Cost()), "a cost without payLife is unaffected")
        assertEq(IntExpr.Lit(0), IntExpr.LifeOf(PlayerRef.You).lowered(null), "LifeOf lowers to 0 when players take no damage")
    }

    check("damage counter: none round-trips, distinct from the default") {
        for (dc in listOf(null, LIFE, "hull")) {
            assertEq(dc, gameDocFromJson(gameDocToJson(GameDoc(rules = RulesDoc(damageCounter = dc)))).rules.damageCounter)
        }
    }

    // -- compile() never throws ------------------------------------------
    check("an unknown combat preset is an error, not a throw, and the rest is still reported") {
        val broken = flawed.copy(rules = flawed.rules.copy(combat = CombatDoc.Preset("no-such-preset")))
        val c = broken.compile()
        assertEq(NO_COMBAT_PROGRAM, c.rules.combat)
        val d = c.diagnostics.single { it.code == "unknown-combat-preset" }
        assertEq(Severity.ERROR, d.severity)
        assertEq("rules.combat", d.path)
        assertTrue(c.diagnostics.any { it.code == "free-x" }, "the other checks still ran")
        // Its steps are unknowable, so a step name is not ALSO reported against no steps.
        val stepped = typoGame.copy(rules = typoGame.rules.copy(combat = CombatDoc.Preset("no-such-preset")))
        assertTrue(stepped.diagnostics().none { it.code == "unknown-step" }, "one error for one cause")
    }

    // -- only a game without errors is played ---------------------------
    check("severity is decided by the code: names and scope are errors, the rest warnings") {
        assertTrue(unknowns(typoGame).isNotEmpty())
        unknowns(typoGame).forEach { assertEq(Severity.ERROR, it.severity, it.code) }
        assertEq(Severity.ERROR, flawed.diagnostics().single { it.code == "free-x" }.severity)
        assertEq(Severity.ERROR, flawed.diagnostics().single { it.code == "unknown-card" }.severity)
        val warnings = listOf(
            DiagCode.DECK_RULES, DiagCode.DEAD_KEYWORD, DiagCode.DUPLICATE_NAME, DiagCode.EMPTY_DECK,
            DiagCode.NO_LOSS, DiagCode.TURN, DiagCode.PARAMS, DiagCode.COUNTER_CANCELS_ITSELF,
        )
        assertEq(warnings.toSet(), DiagCode.entries.filter { it.severity == Severity.WARNING }.toSet(), "the warnings, and only these")
        // A card declared into a zone that is also hidden starts in the
        // hidden one -- the game plays differently from what was authored.
        assertEq(Severity.ERROR, DiagCode.ZONE_COLLISION.severity)
        assertEq(DiagCode.entries.size, DiagCode.entries.map { it.id }.toSet().size, "each code has its own name")
    }

    check("every bundled game compiles without errors, so every one can start") {
        for (g in bundled + SAMPLE_GAMES) {
            val c = g.compile()
            assertEq(emptyList<Diagnostic>(), c.errors, g.name)
            assertTrue(c.runnable != null, g.name)
        }
    }

    check("a game with an error is refused, naming the error") {
        val c = typoGame.compile()
        assertEq(null, c.runnable)
        val e = runCatching { c.playable() }.exceptionOrNull()
        assertTrue(e is NotPlayable, "playable() throws NotPlayable, got $e")
        assertEq(c.errors, (e as NotPlayable).errors)
        assertTrue(e.message!!.contains(c.errors.first().message), e.message!!)
        // The agent's front door asks the same question.
        val viaAgent = runCatching { ccgui.advance(typoGame, PlaySession()) }.exceptionOrNull()
        assertTrue(viaAgent is NotPlayable, "the agent loop refuses it too, got $viaAgent")
    }

    check("warnings never block: an illegal deck still plays") {
        val g = CORE_BUNDLE.copy(deckRules = DeckRules(minSize = 500))
        val c = g.compile()
        assertTrue(c.diagnostics.any { it.code == "deck-rules" }, "the premise: the deck is illegal")
        assertTrue(c.runnable != null, "and the game starts anyway")
    }

    // -- the engine is handed only the core -----------------------------
    check("no surface form survives compile, in any bundled game") {
        for (g in bundled + SAMPLE_GAMES) {
            val found = g.compile().rules.surfaceForms()
            assertEq(emptyList<String>(), found.distinct(), g.name)
        }
    }

    check("the detector fires: an unlowered tree has surface forms, its lowering has none") {
        val raw = Effect.Sequence(listOf(
            Effect.GainLife(PlayerRef.You, IntExpr.SelfField("power")),
            Effect.If(BoolExpr.HasType(setOf("Ship")), Effect.GainLife(PlayerRef.You, IntExpr.LifeOf(PlayerRef.Opponent))),
            Effect.ApplyModifier(creatures(), listOf(CharOp.PlusPT(lit(1), lit(1)), CharOp.SetPT(lit(2), lit(2)))),
            Effect.ReturnFromDiscard(PlayerRef.You, lit(1)),
            Effect.DamageOpponent(lit(2)),
        ))
        assertEq(listOf("SelfField", "HasType", "LifeOf", "PlusPT", "SetPT", "ReturnFromDiscard", "DamageOpponent"), surfaceForms { s -> raw.subst(s) }.distinct())
        assertEq(emptyList<String>(), surfaceForms { s -> raw.lowered(LIFE).subst(s) })
    }

    check("each surface form lowers to the core it means") {
        assertEq(IntExpr.TargetField(BoundTarget(SELF), "hull"), IntExpr.SelfField("hull").lowered())
        assertEq(IntExpr.PlayerCounter(PlayerRef.Opponent, "hull"), IntExpr.LifeOf(PlayerRef.Opponent).lowered("hull"))
        assertEq(IntExpr.Lit(0), IntExpr.LifeOf(PlayerRef.You).lowered(), "players take no damage: it reads 0")
        assertEq(BoolExpr.IsType(BoundTarget(SELF), setOf("Ship")), BoolExpr.HasType(setOf("Ship")).lowered())
        assertEq(
            Effect.ApplyModifier(creatures(), listOf(CharOp.PlusField("power", lit(1)), CharOp.PlusField("toughness", lit(2)), CharOp.SetField("power", lit(3)), CharOp.SetField("toughness", lit(4)))),
            Effect.ApplyModifier(creatures(), listOf(CharOp.PlusPT(lit(1), lit(2)), CharOp.SetPT(lit(3), lit(4)))).lowered(),
        )
        assertEq(
            Effect.SearchZone(PlayerRef.You, HiddenZone.GRAVEYARD, HiddenZone.HAND, lit(2), thenShuffle = false, upTo = false),
            Effect.ReturnFromDiscard(PlayerRef.You, lit(2)).lowered(),
        )
        assertEq(
            Effect.SearchZone(PlayerRef.You, HiddenZone.GRAVEYARD, HiddenZone.GRAVEYARD, lit(2), thenShuffle = false, upTo = false, intoPlay = true),
            Effect.ReturnFromDiscard(PlayerRef.You, lit(2), toBattlefield = true).lowered(),
        )
        val built = CardDoc(faces = listOf(FaceDoc("T", setOf("Creature"), triggers = listOf(TriggerDoc.OnYourPhase("main", Effect.NoOp))))).build()
        assertEq(listOf<TriggerDoc>(TriggerDoc.On(EventPattern.OnPhase("main", PlayerRef.You), Effect.NoOp)), built.faces[0].triggers)
    }

    // -- lowering is complete; the interpreter runs only core -------------
    check("a surface form that reaches the interpreter is refused, not delegated") {
        val raw = PriorityAction.CastSpell(Effect.DamageOpponent(lit(2)), setOf("Instant"))
        val e = runCatching { runSync { Engine(Recording(mapOf("P0" to listOf(raw)))).run(newGame()) } }.exceptionOrNull()
        assertTrue(e is SurfaceFormReached, "got $e")
        val lowered = runSync { Engine(Recording(mapOf("P0" to listOf(instant(Effect.DamageOpponent(lit(2))))))).run(newGame()) }
        assertEq(18, lowered.players.getValue("P1").counter(LIFE), "its lowering runs")
    }

    check("templates and event filters are lowered too: an emblem's statics, a token's abilities, a Damaged filter") {
        val pump = CharOp.PlusPT(lit(1), lit(1))
        val emblem = Effect.CreateEmblem(Statics(chars = listOf(StaticSpec(creatures(), listOf(pump)))))
        val token = Effect.CreateToken(Characteristics("Drone", setOf("Creature"), granted = listOf(ActivatedAbility(Cost(), Effect.DamageOpponent(lit(1))))))
        val watcher = CardDoc(id = "w", faces = listOf(FaceDoc("Watcher", setOf("Artifact"),
            castEffect = Effect.Sequence(listOf(emblem, token)),
            triggers = listOf(TriggerDoc.On(EventPattern.Damaged(PermFilter(where = BoolExpr.HasType(setOf("Creature")))), Effect.NoOp)),
        )))
        val raw = watcher.build(LIFE).let { built -> built.copy(faces = listOf(watcher.faces[0].let { f ->
            built.faces[0].copy(castEffect = f.castEffect, triggers = f.triggers)
        })) }
        assertEq(setOf("PlusPT", "DamageOpponent", "HasType"), raw.surfaceForms().toSet(), "the detector sees into them")
        assertEq(emptyList<String>(), watcher.build(LIFE).surfaceForms(), "and compile leaves none")
    }

    check("an unbound SELF reads the source, so a lowered read means what it did") {
        // A type's diesWhen is evaluated with no bindSelf at all.
        val (s0, id) = enterBattlefield(beast("Ox", 3), "P0", newGame())
        val s1 = s0.copy(battlefield = s0.battlefield + (id to s0.battlefield.getValue(id).copy(damageMarked = 3)))
        val ctx = EvalContext(s1, "P0", id)
        val dies = BUILTIN_TYPES.getValue("Creature").diesWhen!!
        assertTrue(runCatching { dies.eval(ctx) }.exceptionOrNull() is SurfaceFormReached, "the interpreter refuses the surface form")
        assertEq(true, dies.lowered().eval(ctx))
        assertEq(0, IntExpr.TargetField(BoundTarget(SELF), "power").eval(EvalContext(s1, "P0", null)), "no source: 0, as SelfField read")
    }

    // -- named variables ---------------------------------------------------
    check("binders of the same kind nest: the inner body reads both picks") {
        // "Choose an opponent's creature A and one of yours B: B deals damage
        // equal to its power to A." Inexpressible with one CHOSEN sentinel.
        val s = board("P0" to beast("Ox", 3), "P1" to beast("Rat", 1), "P1" to beast("Elk", 5))
        val fight = Effect.Choose(creatures().theirs(), binds = "a", body = Effect.Choose(creatures().yours(), binds = "b",
            body = Effect.DealDamage(IntExpr.TargetField(BoundTarget("b"), "power"), BoundTarget("a"))))
        val end = runSync { Engine(Recording(mapOf("P0" to listOf(instant(fight))))).run(s) }
        val names = end.inPlay.map { end.characteristicsOf(it.id).name }
        assertEq(listOf("Ox", "Elk"), names, "the Rat (A) took the Ox's (B) 3: " + end.log.takeLast(4))
    }

    check("an inner binder of the SAME name shadows the outer, as before") {
        val s = board("P0" to beast("Ox", 3), "P1" to beast("Rat", 1))
        val e = Effect.Choose(creatures().theirs(), Effect.Choose(creatures().yours(), Effect.DealDamage(lit(3), BoundTarget(CHOSEN))))
        val end = runSync { Engine(Recording(mapOf("P0" to listOf(instant(e))))).run(s) }
        assertEq(listOf("Rat"), end.inPlay.map { end.characteristicsOf(it.id).name }, "the inner pick, the Ox, took it")
    }

    check("a variable is written by name; the old negative ids still load") {
        assertTrue("\"target\":\"chosen\"" in effectToJson(Effect.Destroy(BoundTarget(CHOSEN))))
        val legacy = """{"op":"choose","filter":{},"body":{"op":"sequence","steps":[{"op":"destroy","target":-2},{"op":"tap","target":-1,"untap":false},{"op":"destroy","target":12}]}}"""
        assertEq(
            Effect.Choose(PermFilter(), Effect.Sequence(listOf(Effect.Destroy(BoundTarget(CHOSEN)), Effect.Tap(BoundTarget(SELF)), Effect.Destroy(BoundTarget(12))))),
            effectFromJson(legacy),
        )
        val named = Effect.ForEach(creatures(), Effect.Destroy(BoundTarget("x")), binds = "x")
        assertEq(named, effectFromJson(effectToJson(named)))
        assertTrue("binds" !in effectToJson(Effect.ForEach(creatures(), Effect.NoOp)), "a default name is not written")
    }

    check("a name nothing binds is reported; the binder that declares it binds it") {
        fun codes(e: Effect) = FaceDoc("T", setOf("Instant"), castEffect = e).scopeDiagnostics().map { it.code }
        assertEq(listOf("free-named"), codes(Effect.Destroy(BoundTarget("a"))))
        assertEq(emptyList<String>(), codes(Effect.Choose(creatures(), Effect.Destroy(BoundTarget("a")), binds = "a")))
        assertEq(listOf("free-named"), codes(Effect.Choose(creatures(), Effect.Destroy(BoundTarget("a")), binds = "b")))
        assertEq(listOf("free-chosen-target"), codes(Effect.ForEach(creatures(), Effect.Destroy(BoundTarget(CHOSEN)), binds = "c")))
    }

    // -- one player reference ---------------------------------------------
    check("a player position takes any PlayerRef, resolved where it is read") {
        // "Choose an opponent's creature; its controller draws a card."
        val s = board("P0" to beast("Ox", 3), "P1" to beast("Rat", 1))
        val e = Effect.Choose(creatures().theirs(), Effect.Draw(PlayerRef.ControllerOf(BoundTarget(CHOSEN)), lit(1)))
        val end = runSync { Engine(Recording(mapOf("P0" to listOf(instant(e))))).run(s) }
        assertEq(0 to 1, end.players.getValue("P0").hand.size to end.players.getValue("P1").hand.size)
        // In an expression, and in a filter, relative to the source.
        val rat = s.inPlayIds.single { s.battlefield.getValue(it).controller == "P1" }
        val p1 = s.players.getValue("P1")
        val ctx = EvalContext(s.copy(activePlayer = "P1", players = s.players + ("P1" to p1.copy(hand = p1.library.take(2)))), "P0", rat)
        assertEq(2 to 0, IntExpr.HandSize(PlayerRef.Active).eval(ctx) to IntExpr.HandSize(PlayerRef.You).eval(ctx))
        assertEq(1, IntExpr.CountPerms(PermFilter(controller = PlayerRef.ControllerOf(BoundTarget(SELF)))).eval(ctx), "the Rat's controller's")
        assertEq(1, IntExpr.CountPerms(PermFilter(controller = PlayerRef.You)).eval(ctx), "you: P0")
    }

    check("a player that names nobody does nothing, and says so") {
        val s = board("P0" to beast("Ox", 3))
        val gone = Effect.Draw(PlayerRef.OwnerOf(BoundTarget(9999)), lit(1))
        val end = runSync { Engine(Recording(mapOf("P0" to listOf(instant(gone))))).run(s) }
        assertEq(listOf(0, 0), end.turnOrder.map { end.players.getValue(it).hand.size })
        assertTrue(end.log.any { "no such player" in it }, end.log.takeLast(3).toString())
    }

    check("JSON: you / opponent stay bare words, any other player is an object") {
        assertTrue("\"who\":\"you\"" in effectToJson(Effect.Draw(PlayerRef.You, lit(1))))
        for (r in listOf(PlayerRef.Opponent, PlayerRef.Active, PlayerRef.OwnerOf(BoundTarget(SELF)))) {
            val e = Effect.Discard(r, lit(1))
            assertEq(e, effectFromJson(effectToJson(e)))
        }
        val f = PermFilter(controller = PlayerRef.ControllerOf(BoundTarget(CHOSEN)))
        assertEq(f, filterOf(Json.parse(filterToJson(f))))
    }

    check("the chosen player outside \"target player ...\" is reported") {
        fun codes(e: Effect) = FaceDoc("T", setOf("Instant"), castEffect = e).scopeDiagnostics().map { it.code }
        assertEq(listOf("free-chosen-player"), codes(Effect.Draw(PlayerRef.Chosen, lit(1))))
        assertEq(emptyList<String>(), codes(Effect.AsPlayer(PlayerRef.Chosen, Effect.Draw(PlayerRef.You, lit(1)))))
    }

    // -- damage to a player is DealDamage ----------------------------------
    check("DealDamage can hit a player, through the same event; DamageOpponent is it") {
        val s = board()
        fun lifeAfter(e: Effect) = runSync { Engine(Recording(mapOf("P0" to listOf(instant(e))))).run(s) }
            .let { end -> end.turnOrder.map { end.players.getValue(it).life } }
        assertEq(listOf(20, 17), lifeAfter(Effect.DealDamage(lit(3), null, PlayerRef.Opponent)))
        assertEq(listOf(17, 20), lifeAfter(Effect.DealDamage(lit(3), null, PlayerRef.You)))
        assertEq(listOf(20, 17), lifeAfter(Effect.DamageOpponent(lit(3))), "the surface form, unlowered")
        assertEq(Effect.DealDamage(lit(3), null, PlayerRef.Opponent), Effect.DamageOpponent(lit(3)).lowered())
        // A shield on the player sees it: it is the same PlayerDamaged event.
        val shielded = Effect.Sequence(listOf(
            Effect.PreventDamage(BoundTarget(SELF), lit(2), who = PlayerRef.You, duration = Duration.Permanent),
            Effect.DealDamage(lit(3), null, PlayerRef.You),
        ))
        assertEq(listOf(19, 20), lifeAfter(shielded))
    }

    check("DealDamage names a permanent or a player, never both or neither; both round-trip") {
        assertTrue(runCatching { Effect.DealDamage(lit(1), null, null) }.isFailure)
        assertTrue(runCatching { Effect.DealDamage(lit(1), BoundTarget(CHOSEN), PlayerRef.You) }.isFailure)
        for (e in listOf(Effect.DealDamage(lit(1), BoundTarget(CHOSEN)), Effect.DealDamage(lit(1), null, PlayerRef.Active))) {
            assertEq(e, effectFromJson(effectToJson(e)))
        }
        assertEq("""{"op":"dealDamage","amount":1,"target":7}""", effectToJson(Effect.DealDamage(lit(1), BoundTarget(7))), "a permanent's JSON is unchanged")
    }

    check("player damage in a game without it is reported, like DamageOpponent") {
        val zap = CardDoc(id = "zap", faces = listOf(FaceDoc("Zap", setOf("Instant"), castEffect = Effect.DealDamage(lit(2), null, PlayerRef.Opponent))))
        val g = GameDoc(sets = listOf(SetDoc("S", listOf(zap))), rules = RulesDoc(damageCounter = null, counterKinds = emptyList()))
        assertEq(listOf("zap"), g.diagnostics().filter { it.code == "no-player-damage" }.map { it.cardKey })
    }

    // -- counter TARGET spell -------------------------------------------------
    // casts two instants; P0 answers with a counterspell and picks the
    // FIRST (bottom) one -- the old rule could only ever take the top one.
    fun counterGame(pick: (List<ObjectId>) -> ObjectId): GameState {
        val s = newGame().let { g ->
            g.copy(players = g.players + ("P1" to g.players.getValue("P1").copy(hand = listOf(CardRef(90, "Bolt"), CardRef(91, "Zap")))))
        }
        fun from(id: Int) = CardSource(CastZone.Std(HiddenZone.HAND), id)
        val input = object : PlayerInput by Recording(emptyMap()) {
            override suspend fun ask(q: Question): Answer = when (q) {
                is Question.Priority -> Answer.Act(askPriorityAction(q.player, q.state))
                is Question.PickTarget -> Answer.Target(chooseTarget(q.player, q.candidates, q.state))
                else -> q.default()
            }

            val queues = mapOf(
                "P1" to ArrayDeque(listOf<PriorityAction>(
                    PriorityAction.CastSpell(Effect.DamageOpponent(lit(5)).lowered(LIFE), setOf("Instant"), from = from(90)),
                    PriorityAction.CastSpell(Effect.DamageOpponent(lit(1)).lowered(LIFE), setOf("Instant"), from = from(91)),
                )),
                // passes as the active player; P1 casts both (keeping
                // priority), then passes, and P0 answers.
                "P0" to ArrayDeque(listOf<PriorityAction>(PriorityAction.PassPriority, instant(Effect.CounterSpell(setOf("Instant"))))),
            )
            private suspend fun askPriorityAction(player: PlayerId, state: GameState) = queues[player]?.removeFirstOrNull() ?: PriorityAction.PassPriority
            private suspend fun chooseTarget(player: PlayerId, candidates: List<ObjectId>, state: GameState) = pick(candidates)
        }
        return runSync { Engine(input).run(s) }
    }

    check("a counterspell counters the spell its caster picked, not the most recent") {
        val offered = mutableListOf<List<ObjectId>>()
        val end = counterGame { c -> offered += c; c.first() }
        assertEq(2, offered.single().size, "both of P1's spells were offered")
        assertEq(19, end.players.getValue("P0").life, "the 5 was countered, the 1 resolved: " + end.log.takeLast(8))
        assertEq(listOf(CardRef(90, "Bolt")), end.players.getValue("P1").graveyard.filter { it.instanceId == 90 })
        val top = counterGame { it.last() }
        assertEq(15, top.players.getValue("P0").life, "picking the top one counters the 1")
    }

    check("a stack item is a named target in the prompt") {
        val s = newGame().copy(stack = listOf(SpellOnStack(7, "P1", Effect.NoOp, label = "Bolt")))
        assertEq("#7 Bolt (on the stack)", s.lbl(7))
    }

    // -- the save format is versioned ---------------------------------------
    val noLife = RulesDoc(playerCounters = listOf(PlayerCounterDef("store", 0)))
    fun asFormat2(g: GameDoc) = gameDocToJson(g).replace("\"formatVersion\":$FORMAT_VERSION", "\"v\":2")

    check("a game file says which format wrote it") {
        assertTrue(gameDocToJson(GameDoc()).startsWith("{\"formatVersion\":$FORMAT_VERSION,"))
        assertEq(8, FORMAT_VERSION, "one bump per format change, each with its migration")
    }

    check("2 to 3: a format-2 game with no life counter meant no player damage") {
        // The device copies of EPR Skirmish and Core.
        assertTrue("damageCounter" !in asFormat2(GameDoc(rules = noLife)), "format 2 left the default out")
        assertEq(null, gameDocFromJson(asFormat2(GameDoc(rules = noLife))).rules.damageCounter)
        assertEq(LIFE, gameDocFromJson(asFormat2(GameDoc())).rules.damageCounter, "a game WITH life keeps it")
        assertEq("hull", gameDocFromJson(asFormat2(GameDoc(rules = noLife.copy(damageCounter = "hull")))).rules.damageCounter)
        // In format 3 absent means the default, whatever the counters.
        assertEq(LIFE, gameDocFromJson(gameDocToJson(GameDoc(rules = noLife))).rules.damageCounter)
    }

    check("1 to 2: the pre-split bundle still loads, through the chain") {
        val v1 = """{"name":"Old","cards":[{"faces":[{"name":"Bolt","types":["Instant"]}]}],"deck":[{"card":"Bolt","n":4}],"counters":[{"name":"store","starting":0,"loseAtZero":false}]}"""
        val g = gameDocFromJson(v1)
        assertEq(listOf("Bolt"), g.cards.map { it.faces[0].name })
        assertEq(listOf(DeckEntry("Bolt", 4)), g.decks.single().entries)
        assertEq(null, g.rules.damageCounter, "and on up through 2 to 3")
    }

    check("a newer format is refused with a reason, not misread") {
        val r = runCatching { gameDocFromJson("{\"formatVersion\":${FORMAT_VERSION + 1},\"sets\":[]}") }
        assertTrue(r.isFailure && "newer build" in r.exceptionOrNull()!!.message!!, r.toString())
        val session = ccgui.PlaySession(seed = 1, p0Deck = 0, p1Deck = 0)
        assertEq(session, ccgui.decodePlaySession(session.encode()), "the current one reads back")
        assertEq(null, ccgui.decodePlaySession("${ccgui.SESSION_VERSION + 1}" + session.encode().drop(1)))
    }

    // -- RuleMod fields typed ---------------------------------------------
    check("a damage reduction is an expression, read in its card's context") {
        // "Your creatures take 1 less damage for each creature you control."
        val ward = card("Ward", setOf("Enchantment"), statics = Statics(rules = listOf(
            RuleMod.ReduceDamage(creatures().yours(), IntExpr.CountPerms(creatures().yours())),
        )))
        var s = newGame()
        val (s1, a) = enterBattlefield(beast("Ox", 3), "P0", s); s = s1
        s = enterBattlefield(beast("Elk", 2), "P0", s).first
        val (s2, theirs) = enterBattlefield(beast("Rat", 1), "P1", s); s = s2
        assertEq(0, s.damageReduction(a), "no Ward yet")
        s = enterBattlefield(ward, "P0", s).first
        assertEq(2, s.damageReduction(a), "two creatures you control")
        assertEq(0, s.damageReduction(theirs), "the filter reads the Ward's controller")
        val shrink = card("Shrink", setOf("Enchantment"), statics = Statics(rules = listOf(RuleMod.ReduceDamage(creatures(), lit(-3)))))
        assertEq(2, enterBattlefield(shrink, "P0", s).first.damageReduction(a), "a negative reduction adds no damage")
    }

    // -- one announcement ------------------------------------------------
    check("casting, activating and triggering announce through one routine") {
        val src = java.io.File("src/ccg/Engine.kt").readText()
        assertEq(3, Regex("""\bannounce\(""").findAll(src).count() - 1, "castSpell, activateAbility and drainTriggers call it")
        // Its cost step: an effect paid for anywhere else would skip the
        // modes -> X -> targets order. playPermanent announces no effect.
        assertEq(listOf("announce", "playPermanent"), Regex("""(?<!fun )payCost\(""").findAll(src).map { m ->
            Regex("""fun (\w+)\(""").findAll(src.substring(0, m.range.first)).last().groupValues[1]
        }.toList(), "who pays a cost")
        // Its refusals (NoTarget, CantPay) are reached only when `legality`
        // and the engine disagree -- `legality` refuses a target-less action
        // first -- so the order is pinned by the announcement checks, not here.
    }

    // -- the engine holds no running state ---------------------------------
    check("an Engine keeps nothing between calls; a run's record is in the state it returns") {
        val bolt = instant(Effect.DamageOpponent(lit(3)))
        val engine = Engine(Recording(mapOf("P0" to listOf(bolt))))
        val traced = runSync { engine.run(board().traced()) }
        assertEq(1, traced.trace!!.events.count { it is GameEvent.PlayerDamaged }, "the traced run recorded its damage")
        val plain = runSync { Engine(Recording(mapOf("P0" to listOf(bolt)))).run(board()) }
        assertEq(null, plain.trace, "an untraced game records nothing")
        for (f in Engine::class.java.declaredFields) {
            f.isAccessible = true
            // `y` is where the frame being run gets its answers; set per
            // frame, and idle again once a call returns.
            if (f.name == "y") {
                val idle = Engine::class.java.getDeclaredField("idle").also { it.isAccessible = true }.get(engine)
                assertTrue(f.get(engine) === idle, "Engine.y is idle between calls")
                continue
            }
            assertTrue(java.lang.reflect.Modifier.isFinal(f.modifiers), "Engine.${f.name} is a var")
            val v = f.get(engine)
            assertTrue(v !is java.util.ArrayList<*> && v !is java.util.HashMap<*, *> && v !is java.util.HashSet<*>,
                "Engine.${f.name} holds a mutable ${v?.javaClass?.simpleName}")
        }
    }

    // -- phase work is an effect ---------------------------------------------
    check("the hand limit asks which cards to discard") {
        // One cleanup phase, a limit of 2, five cards in hand: P0 CHOOSES the
        // three that go.
        val rules = Rules(turn = TurnStructure(listOf(PhaseSpec("end", onEnter = PhaseEffects.CLEANUP))), params = GameParams(maxHandSize = 2))
        val hand = tokens(5)
        val s0 = newGame().let { s -> s.copy(players = s.players + ("P0" to s.players.getValue("P0").copy(hand = hand))) }
        val pick = listOf(hand[0].instanceId, hand[2].instanceId, hand[4].instanceId)
        val end = runSync { Engine(ScriptedInput.of(cards = listOf(pick)), rules = rules).playGame(s0, maxTurns = 1) }
        assertEq(listOf(hand[1], hand[3]), end.players.getValue("P0").hand, "kept what was not chosen")
        assertEq(pick, end.players.getValue("P0").graveyard.map { it.instanceId })
    }

    check("a phase effect runs once for each player the phase acts for") {
        val gain = PhaseSpec("upkeep", onEnter = Effect.GainLife(PlayerRef.You, lit(1)))
        fun lifeAfter(mode: TurnMode) = runSync {
            Engine(ScriptedInput.of(), rules = Rules(turn = TurnStructure(listOf(gain), mode))).playGame(newGame(), maxTurns = 1)
        }.let { s -> s.turnOrder.map { s.players.getValue(it).counter(LIFE) } }
        assertEq(listOf(21, 20), lifeAfter(TurnMode.PER_PLAYER), "the active player's turn")
        assertEq(listOf(21, 21), lifeAfter(TurnMode.SHARED), "a shared round acts for everyone")
    }

    check("a game parameter is filled by the compiler, and an unknown one reported") {
        val reader = CardDoc(faces = listOf(FaceDoc("Study", setOf("Instant"), castEffect = Effect.Draw(PlayerRef.You, param("cardsDrawnPerTurn")))))
        val g = GameDoc(sets = listOf(SetDoc("S", listOf(reader))), rules = RulesDoc(params = GameParams(cardsDrawnPerTurn = 3)))
        val built = g.compile().rules.cards.values.single().faces[0].castEffect
        assertEq(Effect.Draw(PlayerRef.You, lit(3)), built)
        assertEq(0, IntExpr.Param("cardsDrawnPerTurn").eval(EvalContext(newGame(), "P0")), "unfilled, it reads 0, as X does")
        val typo = GameDoc(sets = listOf(SetDoc("S", listOf(CardDoc(faces = listOf(FaceDoc("T", setOf("Instant"),
            castEffect = Effect.Draw(PlayerRef.You, param("cardsDrawnPerTrun"))))))))).diagnostics()
        assertTrue(typo.any { it.code == "unknown-param" }, typo.map { it.code }.toString())
    }

    check("4 to 5: a phase's named work becomes its effect") {
        // SIMPLE, not MTG: the default turn is not written at all.
        val g = GameDoc(rules = RulesDoc(turn = TurnStructure.SIMPLE))
        var v4 = gameDocToJson(g).replace("\"formatVersion\":$FORMAT_VERSION", "\"formatVersion\":4")
        for ((name, e) in listOf("untap" to PhaseEffects.UNTAP, "draw" to PhaseEffects.DRAW, "cleanup" to PhaseEffects.CLEANUP)) {
            v4 = v4.replace("\"onEnter\":" + effectToJson(e), "\"onEnter\":\"$name\"")
        }
        assertTrue("\"onEnter\":\"draw\"" in v4, "the specimen is in the format-4 shape")
        assertEq(TurnStructure.SIMPLE, gameDocFromJson(v4).rules.turn)
    }

    // -- stances are declared ---------------------------------------------
    fun stanceGame(combat: CombatDoc, mode: String) = GameDoc(
        sets = listOf(SetDoc("S", listOf(CardDoc(id = "brace", faces = listOf(FaceDoc(
            "Brace", setOf("Instant"),
            castEffect = Effect.Choose(creatures(), Effect.SetCombatMode(BoundTarget(CHOSEN), mode)),
        )))))),
        rules = RulesDoc(combat = combat),
    )

    check("a stance resolves against the combat's declarations, not a builtin") {
        fun stanceErrors(g: GameDoc) = g.diagnostics().filter { it.code == "unknown-stance" }
        assertEq(emptyList<Diagnostic>(), stanceErrors(stanceGame(CombatDoc.Preset("yugioh"), "defense")), "the preset declares it")
        assertEq(1, stanceErrors(stanceGame(CombatDoc.Preset("mtg"), "defense")).size, "MTG has no stances")
        // A stance means something only where stats are compared (a Clash).
        val custom = CombatDoc.Program(YUGIOH_COMBAT.copy(stances = listOf(StanceDef("braced", "toughness"))).lowered())
        assertEq(emptyList<Diagnostic>(), stanceErrors(stanceGame(custom, "braced")))
        assertEq(Severity.ERROR, stanceErrors(stanceGame(custom, "defense")).single().severity)
    }

    check("a stance's field is a use, checked like a combat step's") {
        val custom = CombatDoc.Program(YUGIOH_COMBAT.copy(stances = listOf(StanceDef("braced", "grti"))).lowered())
        val d = stanceGame(custom, "braced").diagnostics().single { it.code == "unknown-field" }
        assertEq("rules.combat.attack", d.path, "the attack program's clash names the field")
    }

    check("stances round-trip, and a format-5 custom combat keeps the defense stance it had") {
        val custom = GameDoc(rules = RulesDoc(combat = CombatDoc.Program(YUGIOH_COMBAT.copy(stances = listOf(StanceDef("braced", "grit"))).lowered())))
        assertEq(custom.rules.combat, gameDocFromJson(gameDocToJson(custom)).rules.combat)
        // A format-5 custom compare combat with no stances: it knew "defense".
        val v5 = customAt(YUGIOH_COMBAT.copy(stances = emptyList()), 5).replace(Regex(",\"stances\":\\[[^\\]]*\\]"), "")
        assertTrue("stances" !in v5, "the specimen is in the format-5 shape")
        assertEq(setOf("defense"), gameDocFromJson(v5).rules.combat.compile().stanceNames())
        assertEq(emptySet<String>(), gameDocFromJson(customAt(YUGIOH_COMBAT.copy(stances = emptyList()), 6)).rules.combat.compile().stanceNames(),
            "format 6 means what it says")
        val preset = GameDoc(rules = RulesDoc(combat = CombatDoc.Preset("mtg")))
        val p5 = gameDocToJson(preset).replace("\"formatVersion\":$FORMAT_VERSION", "\"formatVersion\":5")
        assertEq(CombatDoc.Preset("mtg"), gameDocFromJson(p5).rules.combat, "a preset declares its own")
    }

    // -- questions carry the asked player's view ----------------------------
    check("a question carries its player's view: no opponent's hand, no library, counts kept") {
        val g = EPR_SKIRMISH
        val rules = g.compile().playable()
        val start = PlaySession(seed = 7).opening(g, rules)
        fun asked(all: Boolean): List<Question> {
            val seen = mutableListOf<Question>()
            val input = object : PlayerInput {
                override suspend fun ask(q: Question): Answer = q.default().also { seen += q }
                override fun seesAll(player: PlayerId) = all
            }
            runSync { Engine(input, rules = rules).playGame(start, maxTurns = 2) }
            return seen
        }
        val qs = asked(false).filter { it.state != null }
        assertTrue(qs.map { it.player }.toSet() == setOf("P0", "P1"), "both seats were asked")
        for (q in qs) {
            val s = q.state!!
            for ((pid, p) in s.players) {
                assertTrue(p.library.all { it.cardId == HIDDEN_CARD }, "${q.player} can't read $pid's library")
                if (pid != q.player) assertTrue(p.hand.all { it.cardId == HIDDEN_CARD }, "${q.player} can't read $pid's hand")
                if (pid == q.player) assertTrue(p.hand.none { it.cardId == HIDDEN_CARD }, "their own hand is face up")
            }
        }
        val full = asked(true).mapNotNull { it.state }
        assertTrue(full.all { s -> s.players.values.all { p -> p.library.none { it.cardId == HIDDEN_CARD } } }, "a debugger's input sees everything")
    }

    check("eval reads a view as it reads the game: only sizes of hidden zones") {
        val g = EPR_SKIRMISH
        val rules = g.compile().playable()
        val s = PlaySession(seed = 7).opening(g, rules)
        val v = s.viewFor("P0", rules)
        val exprs = listOf(
            IntExpr.HandSize(PlayerRef.Opponent), IntExpr.HandSize(PlayerRef.You),
            IntExpr.ZoneSize(PlayerRef.Opponent, HiddenZone.LIBRARY), IntExpr.ZoneSize(PlayerRef.You, HiddenZone.GRAVEYARD),
        )
        assertEq(exprs.map { it.eval(EvalContext(s, "P0")) }, exprs.map { it.eval(EvalContext(v, "P0")) })
        assertTrue(v.players.getValue("P1").hand.all { it.cardId == HIDDEN_CARD } && v.players.getValue("P1").hand.size == s.players.getValue("P1").hand.size)
    }

    check("a view does not tell the opponent's hidden cards apart: ids and RNG are not leaked") {
        val g = EPR_SKIRMISH
        val rules = g.compile().playable()
        val s = PlaySession(seed = 7).opening(g, rules)
        // The same game with P1's hidden cards dealt differently -- other
        // cards under other ids, in other places -- and another RNG.
        val p1 = s.players.getValue("P1")
        val dealt = (p1.library + p1.hand).reversed()
        val other = s.copy(
            players = s.players + ("P1" to p1.copy(library = dealt.take(p1.library.size), hand = dealt.drop(p1.library.size))),
            rngState = s.rngState + 1,
        )
        assertTrue(other.players.getValue("P1").hand != p1.hand, "the two games differ in P1's hand")
        assertEq(s.viewFor("P0", rules), other.viewFor("P0", rules), "but P0 sees the same")
        val real = s.players.values.flatMap { it.library + it.hand }.map { it.instanceId }.toSet()
        val masked = s.viewFor("P0", rules).players.values.flatMap { it.library + it.hand }.filter { it.cardId == HIDDEN_CARD }
        assertTrue(masked.none { it.instanceId in real }, "a masked ref carries no real id")
        assertEq(masked.size, masked.map { it.instanceId }.toSet().size, "and each is distinct within the view")
    }

    // -- bot answers are recorded ------------------------------------------
    check("a session records the bot's answers, and replays without the bot") {
        val g = EPR_SKIRMISH
        val rules = g.compile().playable()
        val start = PlaySession(seed = 7).opening(g, rules)
        // The Hotseat's loop: whoever answers, the session keeps `recorded()`.
        val bots = listOf("P0", "P1").associateWith { HeuristicPilot(it, rules) }
        var st = step(rules, Run(start, maxTurns = 16))
        while (!st.over) st = st.next(runSync { bots.getValue(st.full!!.player).ask(st.question!!) }.recorded())
        val log = st.run.answers
        assertTrue(log.isNotEmpty() && log.none { it is Answer.Act }, "recorded, as references: ${log.take(3)}")
        log.forEach { answerToJson(it) } // every one of them saveable
        // Stepped from the answers alone: nothing of the game can come from a bot.
        val replayed = step(rules, Run(start, log, maxTurns = 16))
        assertTrue(replayed.over, "the answers carry the game to its end, asking nobody")
        assertEq(st.state.canonical(), replayed.state.canonical(), "the same game, answer for answer")
    }

    // -- one replacement form ----------------------------------------------
    fun warded(vararg r: ReplacementDoc) = CardDoc(faces = listOf(FaceDoc(
        "Ward", setOf("Creature"), fields = mapOf("power" to 1, "toughness" to 9), replacements = r.toList(),
    ))).build()
    fun hit(n: Int) = instant(Effect.Choose(creatures().theirs(), Effect.DealDamage(lit(n), BoundTarget(CHOSEN))))

    check("the named replacement kinds lower to the one general form") {
        val kinds = listOf(
            ReplacementDoc.DamageToSacrificeSelf(PermFilter().onlyHost(), "fast"), ReplacementDoc.PreventDamageTo(creatures()),
            ReplacementDoc.DamageToRemoveCounter(creatures(), "shield"), ReplacementDoc.DeathToExile(creatures()),
        )
        val built = warded(*kinds.toTypedArray())
        assertTrue(built.faces.single().statics.replacements.all { it is ReplacementDoc.Replace }, "${built.faces.single().statics.replacements}")
        assertEq(emptyList<String>(), built.surfaceForms(), "no surface replacement reaches the engine")
        assertEq(listOf("ReplacementDoc.PreventDamageTo"), CardDefinition(listOf(Face("x", setOf("Creature"),
            statics = Statics(replacements = listOf(ReplacementDoc.PreventDamageTo(creatures())))))).surfaceForms(), "and the detector sees one")
    }

    check("an authored replacement lets the event through, changed") {
        val ward = warded(ReplacementDoc.Replace(EventPattern.Damaged(PermFilter().only(SELF)), Effect.Proceed(IntExpr.EventAmount - 1)))
        val s0 = board("P1" to ward)
        val id = s0.inPlayIds.single()
        val end = runSync { Engine(ScriptedInput.of("P0" to listOf(hit(3), hit(1)), targets = listOf(id, id))).run(s0) }
        assertEq(2, end.battlefield.getValue(id).damageMarked, "3 became 2, and 1 became nothing")
    }

    check("a replacement applies once per chain -- one that re-deals the damage does not loop") {
        val again = ReplacementDoc.Replace(EventPattern.Damaged(PermFilter().only(SELF)), Effect.DealDamage(IntExpr.EventAmount + 1, BoundTarget(TRIGGER)))
        val s0 = board("P1" to warded(again))
        val id = s0.inPlayIds.single()
        val end = runSync { Engine(ScriptedInput.of("P0" to listOf(hit(2)), targets = listOf(id))).run(s0) }
        assertEq(3, end.battlefield.getValue(id).damageMarked, "replaced once, by 3")
    }

    check("a shield is a held replacement with a budget, spent as it absorbs") {
        val s0 = board("P1" to beast("Ox", 9))
        val ox = s0.inPlayIds.single()
        val shield = instant(Effect.Choose(creatures(), Effect.PreventDamage(BoundTarget(CHOSEN), lit(3), duration = Duration.Permanent)))
        val mid = runSync { Engine(ScriptedInput.of("P1" to listOf(shield), targets = listOf(ox))).run(s0) }
        val held = mid.shields.single()
        assertTrue(held.doc is ReplacementDoc.Replace && held.budget == Budget(3, Spend.POINTS), "$held")
        val end = runSync { Engine(ScriptedInput.of("P0" to listOf(hit(2), hit(2), hit(2)), targets = listOf(ox, ox, ox))).run(mid) }
        assertEq(3, end.battlefield.getValue(ox).damageMarked, "2 prevented, then 1 of 2, then none: 0 + 1 + 2")
        assertTrue(end.shields.isEmpty(), "the spent shield is gone")
    }

    check("what a replacement can't do is an error, and the game won't start") {
        fun codes(f: FaceDoc) = GameDoc(sets = listOf(SetDoc("Core", listOf(CardDoc(faces = listOf(f)))))).diagnostics()
            .filter { it.severity == Severity.ERROR }.map { it.code }
        val asks = FaceDoc("A", setOf("Creature"), replacements = listOf(ReplacementDoc.Replace(
            EventPattern.Damaged(), Effect.Choose(creatures(), Effect.Destroy(BoundTarget(CHOSEN))),
        )))
        assertEq(listOf("replacement-asks"), codes(asks))
        val cast = FaceDoc("B", setOf("Creature"), replacements = listOf(ReplacementDoc.Replace(EventPattern.Cast(), Effect.NoOp)))
        assertEq(listOf("replacement-pattern"), codes(cast))
        val stray = FaceDoc("C", setOf("Instant"), castEffect = Effect.Proceed())
        assertEq(listOf("proceed-outside-replacement"), codes(stray))
        val late = FaceDoc("D", setOf("Creature"), triggers = listOf(TriggerDoc.On(EventPattern.Dies(filter = creatures()), Effect.NoOp)))
        assertEq(listOf("dies-filter-on-trigger"), codes(late))
        val later = FaceDoc("E", setOf("Creature"), replacements = listOf(ReplacementDoc.Replace(
            EventPattern.Damaged(), Effect.Delayed(EventPattern.AnyTurnBegan, Effect.Choose(creatures(), Effect.Destroy(BoundTarget(CHOSEN)))),
        )))
        assertEq(emptyList<String>(), codes(later), "a Delayed body runs later, and may ask")
    }

    // -- block rules are filters -------------------------------------------
    check("a format-6 block rule kind migrates to the filters that mean it") {
        val ops = """[{"op":"needsKeyword","attackerKw":"elusive","blockerKw":"elusive"},""" +
            """{"op":"needsPower","attackerKw":"fearsome","minPower":3},{"op":"cantBlock","keyword":"frozen"}]"""
        val v6 = customAt(MTG_COMBAT.copy(blockRules = listOf(BlockRule.cantBlock("x"))), 6)
            .replace(Regex("\"blockRules\":\\[.*?\\](?=,\"stances\"|\\})"), "\"blockRules\":$ops")
        assertTrue("needsPower" in v6, "the specimen is in the format-6 shape: $v6")
        assertEq(
            listOf(BlockRule.needsKeyword("elusive", "elusive"), BlockRule.needsStat("fearsome", "power", 3), BlockRule.cantBlock("frozen")),
            gameDocFromJson(v6).rules.combat.compile().blockRules(),
        )
        val bad = v6.replace("needsPower", "needsLuck")
        assertTrue(runCatching { gameDocFromJson(bad) }.isFailure, "an unknown kind is refused, not guessed")
    }

    check("a block rule reads any stat the game names; the engine names none") {
        fun brute(name: String, grit: Int) = card(
            name, setOf("Creature"),
            baseChars = Characteristics(name, setOf("Creature"), mapOf("power" to 1, "toughness" to 1, "grit" to grit)),
        )
        val s = board("P0" to brute("Ogre", 1), "P1" to brute("Wall", 4), "P1" to brute("Twig", 1))
        val (ogre, wall, twig) = s.inPlayIds.sorted()
        val s2 = s.copy(battlefield = s.battlefield + (ogre to s.battlefield.getValue(ogre).let { p -> p.copy(base = p.base.copy(keywords = p.base.keywords + "fearsome")) }))
        val rule = BlockRule.needsStat("fearsome", "grit", 3)
        assertEq(listOf(true, false), listOf(wall, twig).map { rule.allows(s2, ogre, it) }, "grit 4 blocks, grit 1 cannot")
        assertEq(true, rule.allows(s, ogre, twig), "an attacker the rule doesn't cover is anyone's to block")
        // The defending side is "you": a rule that only your own permanents may block with.
        val mine = BlockRule(PermFilter(), PermFilter(controller = PlayerRef.You))
        assertEq(true, mine.allows(s, ogre, wall), "the blocker's controller is you")
        val body = java.io.File("src/ccg/Eval.kt").readText().substringAfter("fun BlockRule.allows").substringBefore("\n}\n")
        assertTrue("\"power\"" !in body, "the hardcoded power read is gone: $body")
    }

    check("a block rule's names are checked, and its keywords are the game's") {
        val cfg = MTG_COMBAT.copy(blockRules = listOf(BlockRule(PermFilter(types = setOf("Dragoon")), PermFilter(where = BoolExpr.HasKeyword(BoundTarget(SUBJECT), "reach")))))
        val doc = GameDoc(rules = RulesDoc(combat = CombatDoc.Program(cfg.lowered())))
        val d = doc.diagnostics().filter { it.code == "unknown-type" }
        assertEq(listOf("rules.combat"), d.map { it.path }, "an undeclared type in a block rule is an error, located in the combat")
        assertTrue("reach" in doc.keywordsWithRules(), "a keyword a block rule tests has a rule")
    }

    check("a keyword has a rule only where the game reads it: its combat, or a card") {
        val combat = CombatDoc.Preset("hearthstone")
        val unread = Keyword.allNames.first { it !in combat.compile().keywordsNamed() }
        fun game(vararg faces: FaceDoc) = GameDoc(rules = RulesDoc(combat = combat), sets = listOf(SetDoc(cards = faces.map { CardDoc(faces = listOf(it)) })))
        val bearer = FaceDoc("Bearer", setOf("Creature"), fields = mapOf("power" to 1, "toughness" to 1), keywords = setOf(unread))
        val dead = game(bearer).diagnostics().filter { it.code == "dead-keyword" }
        assertEq(1, dead.size, "\"$unread\" was the engine's word once; nothing in this game reads it")
        val anthem = FaceDoc("Anthem", setOf("Creature"), fields = mapOf("power" to 1, "toughness" to 1),
            statics = listOf(StaticSpec(PermFilter(where = BoolExpr.HasKeyword(BoundTarget(SUBJECT), unread)), listOf(CharOp.PlusPT(lit(1), lit(1))))))
        val read = game(bearer, anthem)
        assertTrue(unread in read.keywordsWithRules(), "a card that tests for it gives it a rule")
        assertTrue(read.diagnostics().none { it.code == "dead-keyword" }, "and the bearer is no longer warned")
    }

    // -- counter cancellation is declared ---------------------------------
    check("a declared pair cancels; +1/+1 and -1/-1 still do; neither is named by the engine") {
        fun add(kind: String, n: Int) = instant(Effect.Choose(permanents(), Effect.AddCounter(kind, lit(n), BoundTarget(CHOSEN))))
        val doc = GameDoc(rules = RulesDoc(counterKinds = listOf(CounterKindDef("charge", cancels = "drain"), CounterKindDef("drain"))))
        val rules = doc.compile().rules
        assertEq(listOf("+1/+1" to "-1/-1", "charge" to "drain"), rules.cancellations)
        // 5/5: the order the stack resolves them in must not kill it first.
        val s0 = board("P0" to beast("Ox", 5))
        val ox = s0.inPlayIds.single()
        val acts = listOf(add("charge", 3), add("drain", 1), add("+1/+1", 1), add("-1/-1", 2), add("shield", 2), add("drain", 2))
        val end = runSync { Engine(ScriptedInput.of("P0" to acts, targets = List(acts.size) { ox }), rules = rules).run(s0) }
        val p = end.battlefield.getValue(ox)
        assertEq(mapOf("-1/-1" to 1, "shield" to 2, "drain" to 0).filterValues { it > 0 }, p.counters, "3 charge - 3 drain; 1 +1/+1 - 1 -1/-1; shield untouched")
        val undeclared = runSync { Engine(ScriptedInput.of("P0" to acts, targets = List(acts.size) { ox })).run(s0) }
        assertEq(3, undeclared.battlefield.getValue(ox).counter("charge"), "without the declaration, charge and drain coexist")
        val self = GameDoc(rules = RulesDoc(counterKinds = listOf(CounterKindDef("charge", cancels = "charge")))).diagnostics()
        assertTrue(self.any { it.code == "counter-cancels-itself" }, "a kind cancelling itself is reported")
        assertEq(listOf("+1/+1" to "-1/-1"), Rules(counterKinds = BUILTIN_COUNTER_DEFS + CounterKindDef("charge", cancels = "charge")).cancellations, "and ignored")
        val typo = GameDoc(rules = RulesDoc(counterKinds = listOf(CounterKindDef("charge", cancels = "drian")))).diagnostics()
        assertTrue(typo.any { it.code == "unknown-counter" && "drian" in it.message }, "a cancelled kind nobody declares is reported: ${typo.map { it.code }}")
        assertEq(false, java.io.File("src/ccg/Engine.kt").readText().contains("\"+1/+1\""), "the engine names no counter kind")
    }

    // -- block rules are data ---------------------------------------------
    check("a config's block rules are data: they survive CombatDoc.of and a save") {
        val rules = listOf(BlockRule.needsKeyword("elusive", "elusive"), BlockRule.cantBlock("frozen"))
        val cfg = MTG_COMBAT.copy(blockRules = rules)
        val doc = CombatDoc.of(cfg.lowered())
        assertEq(rules, doc.compile().blockRules(), "was Custom(cfg without its block rules): a hand-built rule was lost")
        val game = GameDoc(rules = RulesDoc(combat = doc))
        assertEq(rules, gameDocFromJson(gameDocToJson(game)).rules.combat.compile().blockRules(), "and through JSON")
        assertTrue("\"rules\":[{" in gameDocToJson(game), "saved inside the program")
        // Format 3 wrote them beside the base; such a file still loads them.
        val v3 = customAt(cfg, 3)
            .replace(Regex(",\"blockRules\":(\\[.*?\\])\\}\\}")) { m -> "},\"blockRuleSpecs\":${m.groupValues[1]}}" }
        assertTrue("blockRuleSpecs" in v3, "the format-3 specimen is in the old shape: $v3")
        assertEq(rules, gameDocFromJson(v3).rules.combat.compile().blockRules(), "3 to 4 moves them in")
        assertEq(CombatDoc.Preset("mtg"), CombatDoc.of(MTG_COMBAT.lowered()), "a preset is still saved by name")
        // And the engine reads them: a frozen creature can't block, an elusive
        // attacker only meets an elusive blocker.
        val s = board("P0" to beast("Ghost", 2), "P1" to beast("Ice", 2), "P1" to beast("Seer", 2))
        val (ghost, ice, seer) = s.inPlayIds.sorted()
        fun GameState.kw(id: ObjectId, vararg k: String) =
            copy(battlefield = battlefield + (id to battlefield.getValue(id).let { p -> p.copy(base = p.base.copy(keywords = p.base.keywords + k)) }))
        val s2 = s.kw(ghost, "elusive").kw(seer, "elusive").kw(ice, "elusive", "frozen")
        assertEq(listOf(true, false), listOf(seer, ice).map { b -> rules.all { it.allows(s2, ghost, b) } }, "Seer may block; frozen Ice may not")
        assertEq(false, rules.all { it.allows(s.kw(ghost, "elusive"), ghost, seer) }, "an unkeyworded blocker cannot stop an elusive attacker")
    }

    // -- a permanent holds its card's key ---------------------------------
    check("a permanent holds a card key; its faces come from the rules' table") {
        val gadget = card("Gadget", setOf("Artifact"), activated = listOf(ActivatedAbility(Cost(), Effect.Draw(PlayerRef.You, lit(1)), "draw")))
        val (s, id) = enterBattlefield(gadget, "P0", newGame())
        assertEq("Gadget", s.battlefield.getValue(id).cardId)
        assertEq(1, s.abilitiesOf(id, Rules(cards = mapOf("Gadget" to gadget))).size, "resolved through the table")
        assertEq(0, s.abilitiesOf(id, Rules()).size, "and only through it: the state carries no definition")
        // A copy names the same card; a token from bare characteristics names none.
        val rules = Rules(cards = mapOf("Gadget" to gadget))
        val end = runSync {
            Engine(ScriptedInput.of("P0" to listOf(
                PriorityAction.CastSpell(Effect.CopyOf(BoundTarget(id)), setOf("Instant")), PriorityAction.PassPriority,
                PriorityAction.CastSpell(Effect.CreateToken(body("Soldier", 1, 1)), setOf("Instant")),
            )), rules = rules).run(s)
        }
        val tokens = end.battlefield.values.filter { it.isToken }.sortedBy { it.id }
        assertEq(listOf("Gadget", null), tokens.map { it.cardId }, "the copy's key, then the bare token's")
        assertEq(1, end.abilitiesOf(tokens[0].id, rules).size, "the copy has the card's ability")
        assertEq("Soldier", tokens[1].cardKey, "a bare token still names itself")
    }

    // -- no two-player assumptions ---------------------------------------
    check("the turn passes to the next seat in turn order that has not lost") {
        val s = GameState(players = listOf("P0", "P1", "P2").associateWith { Player(it) }, turnOrder = listOf("P0", "P1", "P2"), activePlayer = "P0")
        assertEq(listOf("P1", "P2", "P0"), s.turnOrder.map { s.nextInTurn(it) })
        val out = s.copy(losers = setOf("P1"))
        assertEq("P2", out.nextInTurn("P0"), "a player who has lost is skipped")
        assertEq("P0", newGame().nextInTurn("P1"), "two players: the other one, as before")
    }

    fun threePlayers() = GameState(
        players = listOf("P0", "P1", "P2").associateWith { Player(it, hand = List(it.last().digitToInt() + 1) { i -> CardRef(900 + i, "x") }) },
        turnOrder = listOf("P0", "P1", "P2"), activePlayer = "P0",
    )

    check("a verb on each opponent runs once per opponent, in turn order; 'you' stays the controller") {
        // P0 holds 1 card, P1 2, P2 3.
        fun lifeAfter(e: Effect) = runSync { Engine(Recording(mapOf("P0" to listOf(instant(e))))).run(threePlayers()) }
            .let { s -> s.turnOrder.map { s.players.getValue(it).life } }
        assertEq(listOf(20, 19, 19), lifeAfter(Effect.DealDamage(IntExpr.HandSize(PlayerRef.You), null, PlayerRef.EachOpponent)), "your hand, to each of them")
        assertEq(listOf(20, 18, 17), lifeAfter(Effect.DealDamage(IntExpr.HandSize(PlayerRef.EachOpponent), null, PlayerRef.EachOpponent)), "each one's own hand")
        val order = runSync { Engine(Recording(mapOf("P0" to listOf(instant(Effect.Draw(PlayerRef.EachOpponent, lit(1))))))).run(threePlayers().traced()) }
        assertEq(listOf("P1", "P2"), order.log.filter { " draws " in it }.map { it.substringBefore(" draws") }, "in turn order from the controller")
        assertEq(listOf(20, 19, 20), lifeAfter(Effect.DealDamage(lit(1), null, PlayerRef.Opponent)), "a bare opponent is still one player")
    }

    check("where it matches, opponent is any opponent; where it is read, each opponent sums") {
        var s = threePlayers()
        for (p in listOf("P0", "P1", "P2")) s = enterBattlefield(beast("Bear $p", 2), p, s).first
        val ctx = EvalContext(s, "P0")
        assertEq(2, IntExpr.CountPerms(creatures().theirs()).eval(ctx), "creatures your opponents control: both of theirs")
        assertEq(5, IntExpr.HandSize(PlayerRef.EachOpponent).eval(ctx), "2 + 3")
        assertEq(2, IntExpr.HandSize(PlayerRef.Opponent).eval(ctx), "the first other seat, as before")
        assertEq(listOf("P2", "P0"), s.opponentsOf("P1"))
        assertEq(listOf("P2"), s.copy(losers = setOf("P0")).opponentsOf("P1"), "a player who lost is nobody's opponent")
    }

    check("with three players a loss does not end the game; the loser is nobody's opponent") {
        val kill = Effect.DealDamage(lit(20), null, PlayerRef.Seat("P1"))
        val end = runSync { Engine(Recording(mapOf("P0" to listOf(instant(kill))))).run(threePlayers()) }
        assertEq(setOf("P1"), end.losers, end.log.joinToString("\n"))
        assertTrue(!end.isOver, "two seats are left")
        assertEq("P2", end.opponentOf("P0"), "the first opponent still in the game")
        assertEq(listOf("P2"), end.opponentsOf("P0"))
        assertTrue(!EvalContext(end, "P0").names(PlayerRef.Opponent, "P1"), "a player who lost matches no 'opponent'")
        assertTrue(newGame().copy(losers = setOf("P1")).isOver, "two players: the first loss ends it, as before")
    }

    check("a game of three plays on until one seat is left") {
        // Empty libraries: each player decks out at their first draw.
        val end = runSync { Engine(PlayerInput { it.default() }).playGame(threePlayers(), maxTurns = 10) }
        assertEq(2, end.losers.size, end.log.joinToString("\n"))
        assertTrue(end.isOver)
        assertEq(2, end.log.count { it.endsWith("decks out") }, "each loser decked out in their own turn")
    }

    check("a shield on another player guards that player, not every opponent") {
        // P0 shields P1 for 3, then deals 2 to each opponent. Matching any
        // opponent, the shield took P1's 2 and 1 of P2's from one budget.
        val shieldThenHit = Effect.Sequence(listOf(
            Effect.PreventDamage(BoundTarget(SELF), lit(3), who = PlayerRef.Opponent, duration = Duration.Permanent),
            Effect.DealDamage(lit(2), null, PlayerRef.EachOpponent),
        ))
        val end = runSync { Engine(Recording(mapOf("P0" to listOf(instant(shieldThenHit))))).run(threePlayers()) }
        assertEq(listOf(20, 20, 18), end.turnOrder.map { end.players.getValue(it).life }, end.log.joinToString("\n"))
        assertEq(1, end.shields.single().budget?.remaining, "P1's hit spent 2 of its 3")
        assertEq(PlayerRef.Seat("P2"), playerRefOf(Json.parse(playerRefToJson(PlayerRef.Seat("P2")))), "the pinned seat round-trips")
    }

    check("the fan-out finds a verb's own slot, and only its own") {
        assertEq(PlayerRef.EachOpponent, Effect.Draw(PlayerRef.EachOpponent, lit(1)).actsOn())
        assertEq(PlayerRef.EachOpponent, Effect.AsPlayer(PlayerRef.EachOpponent, Effect.Draw(PlayerRef.You, lit(1))).actsOn())
        assertEq(null, Effect.Sequence(listOf(Effect.Draw(PlayerRef.EachOpponent, lit(1)))).actsOn(), "a step fans out itself")
        assertEq(null, Effect.DealDamage(IntExpr.HandSize(PlayerRef.EachOpponent), BoundTarget(1)).actsOn(), "a read is not a slot")
    }

    check("with 3+ players a bare opponent that acts or is read is an error; each opponent is not") {
        fun codes(e: Effect, players: Int) = GameDoc(
            sets = listOf(SetDoc("S", listOf(CardDoc(faces = listOf(FaceDoc("Hex", setOf("Instant"), castEffect = e)))))),
            rules = RulesDoc(params = GameParams(playerCount = players)),
        ).diagnostics().filter { it.code == "ambiguous-opponent" }
        val draw = Effect.Draw(PlayerRef.Opponent, lit(1))
        assertEq(Severity.ERROR, codes(draw, 3).single().severity)
        assertEq(1, codes(Effect.GainLife(PlayerRef.You, IntExpr.HandSize(PlayerRef.Opponent)), 4).size, "a read, too")
        assertEq(1, codes(Effect.DamageOpponent(lit(2)), 3).size, "and the surface form that means it")
        assertEq(0, codes(draw, 2).size, "two players: one opponent, no ambiguity")
        assertEq(0, codes(Effect.Draw(PlayerRef.EachOpponent, lit(1)), 3).size)
        assertEq(0, codes(Effect.ForEach(creatures().theirs(), Effect.Destroy(BoundTarget(EACH))), 3).size, "a filter matches any opponent")
    }

    // -- the RNG against an independent implementation -------------------
    // `test/golden/mulberry32.py` is written from the published algorithm, not
    // from Rng.kt; the vector it printed is what a port checks its Rng against.
    check("Rng reproduces the recorded Mulberry32 vector, and shuffle with it") {
        val lines = java.io.File("test/golden/rng.txt").readLines().filter { it.isNotBlank() }
        var outputs = 0; var shuffles = 0
        for (l in lines) {
            val f = l.split(" ")
            if (f[0] == "shuffle") {
                val (seed, n) = f[1].toInt() to f[2].toInt()
                assertEq(f.drop(3).map { it.toInt() }, shuffle((0 until n).toList(), Rng(seed)), "shuffle seed $seed"); shuffles++
            } else {
                val rng = Rng(f[0].toInt())
                assertEq(f.drop(1).map { it.toLong() }, f.drop(1).map { (rng.nextFloat() * 4294967296.0).toLong() }, "seed ${f[0]}"); outputs++
            }
        }
        assertTrue(outputs >= 8 && shuffles >= 2, "the vector is all there: $outputs seeds, $shuffles shuffles")
    }

    // -- the canonical digest ----------------------------------------------
    check("sha256Hex is SHA-256") {
        assertEq("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex("abc"))
        for (s in listOf("", "a".repeat(55), "a".repeat(56), "a".repeat(64), "é∑ multi-block ".repeat(40))) {
            val want = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
                .joinToString("") { "%02x".format(it) }
            assertEq(want, sha256Hex(s), "length ${s.length}")
        }
    }

    check("the digest ignores the log, the trace and map insertion order") {
        val g = EPR_SKIRMISH
        val rules = g.compile().playable()
        val start = PlaySession(seed = 7).opening(g, rules)
        val pilots = SeatedPilots(mapOf("P0" to HeuristicPilot("P0", rules), "P1" to HeuristicPilot("P1", rules)), fallback = PassingPilot())
        val end = runSync { Engine(pilots, rules = rules).playGame(start, maxTurns = 16) }
        assertTrue(end.battlefield.size >= 2, "a specimen with a board")
        val reshuffled = end.copy(
            log = listOf("anything"), trace = EventTrace(),
            battlefield = end.battlefield.entries.reversed().associate { it.key to it.value },
            players = end.players.entries.reversed().associate { it.key to it.value.copy(counters = it.value.counters.entries.reversed().associate { e -> e.key to e.value }) },
        )
        assertEq(end.canonical(), reshuffled.canonical())
        assertTrue(end.digest() != end.copy(rngState = end.rngState + 1).digest(), "and it does read the state")
        // Pinned: a change here changes every recorded corpus digest.
        assertEq("$E2_PINNED", end.digest(), "EPR, seed 7, heuristic both seats, 16 turns")
    }

    check("the digest reads derived characteristics: same holder, different op, different digest") {
        val g = EPR_SKIRMISH
        val rules = g.compile().playable()
        val start = PlaySession(seed = 7).opening(g, rules)
        val pilots = SeatedPilots(mapOf("P0" to HeuristicPilot("P0", rules), "P1" to HeuristicPilot("P1", rules)), fallback = PassingPilot())
        val end = runSync { Engine(pilots, rules = rules).playGame(start, maxTurns = 16) }
        val id = end.battlefield.keys.min()
        fun with(op: CharOp) = end.copy(continuousEffects = end.continuousEffects +
            ContinuousEffect(source = id, controller = end.battlefield.getValue(id).controller, layer = 6, timestamp = 999, op = op, affected = setOf(id)))
        val flying = with(CharOp.GrantKeyword("flying")).canonical()
        val reach = with(CharOp.GrantKeyword("reach")).canonical()
        assertTrue(flying != reach, "the holders print alike, so only the derived line tells them apart")
        assertTrue(flying.lines().any { it.startsWith("  chars #$id ") && "flying" in it }, "and it says what the permanent became")
    }

    // Every field of the state is either in the canonical text or deliberately
    // left out. A new field fails here until someone decides which.
    check("every state field is digested or deliberately excluded") {
        fun fields(c: Class<*>) = c.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
        assertEq(setOf("players", "battlefield", "stack", "exile", "rngState", "continuousEffects", "triggeredAbilities",
            "delayedTriggers", "shields", "ruleMods", "replacements", "costMods", "turnOrder", "activePlayer", "losers",
            "phase", "phaseIndex", "turnNumber", "nextObjectId", "log", "damageCounter", "combatHits", "combat", "pendingEvents",
            "controlChanges", "trace", "derivedCache", "zoneGeometry", "priority", "pending"), fields(GameState::class.java),
            "GameState (phase: display only; log, trace: not the game; derivedCache: a memo; zoneGeometry: the bundle's; pending: the engine's own continuation)")
        assertEq(setOf("id", "library", "hand", "graveyard", "pool", "counters", "ramp", "resourcePlaysUsed", "customZones"),
            fields(Player::class.java), "Player")
        assertEq(setOf("id", "controller", "base", "damageMarked", "exhausted", "counters", "face", "cardId", "diesWhen", "zone",
            "attacksThisTurn", "combatMode", "activatedThisTurn", "isToken", "hostId", "enteredOnTurn", "owner"),
            fields(Permanent::class.java), "Permanent (base, diesWhen: derived from the card)")
    }

    // -- the conformance corpus --------------------------------------------
    check("the committed corpus is what the engine records now (regenerate: CGE_WRITE_CORPUS=1)") {
        val stale = mutableListOf<String>()
        for (g in BUNDLED) {
            val rules = g.compile().playable()
            val cases = pairings(g).map { (a, b, seed) ->
                val seats = SeatedPilots(mapOf("P0" to HeuristicPilot("P0", rules), "P1" to HeuristicPilot("P1", rules)), fallback = PassingPilot())
                recordCorpusCase(g, rules, listOf(DeckPick(a), DeckPick(b)), seed, maxTurns = 16, seats)
            }
            val text = corpusToJson(Corpus(g, cases))
            val file = java.io.File(CORPUS_DIR, corpusSlug(g.name) + ".json")
            if (System.getenv("CGE_WRITE_CORPUS") == "1") { file.parentFile.mkdirs(); file.writeText(text) }
            if (!file.exists() || file.readText() != text) stale += file.path
        }
        assertTrue(stale.isEmpty(), "regenerate deliberately, with a reason, or it becomes a rubber stamp: $stale")
    }

    check("every corpus case replays from its file alone, digest for digest") {
        val files = java.io.File(CORPUS_DIR).listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
        assertEq(BUNDLED.map { corpusSlug(it.name) + ".json" }.sorted(), files.map { it.name }, "one file per bundled game")
        var cases = 0; var questions = 0
        for (f in files) {
            val c = corpusFromJson(f.readText())
            val rules = c.bundle.compile().playable()
            for (k in c.cases) {
                val (seen, end) = replayCorpusCase(c.bundle, rules, k)
                val at = seen.indices.firstOrNull { it >= k.digests.size || seen[it] != k.digests[it] }
                assertTrue(at == null && seen.size == k.digests.size, "${f.name} ${k.seats} seed ${k.seed}: first disagreement at question $at")
                assertEq(k.end, end, "${f.name} ${k.seats} seed ${k.seed}: the end state")
                cases++; questions += seen.size
            }
            if (c.bundle.name == EPR_SKIRMISH.name) {
                assertEq(E2_PINNED, c.cases.single { it.seats == listOf(0, 0) && it.seed == 7 }.end, "the recorder plays the game bare pilots play")
            }
        }
        assertEq(46, cases, "the replay golden's games, all of them")
        assertTrue(questions > 1000, "and they ask things: $questions questions")
    }

    // -- what the corpus exercises -----------------------------------------
    check("CorpusCoverage.md's measured section is what the corpus runs now (regenerate: CGE_WRITE_CORPUS=1)") {
        fun leaves(c: Class<*>): List<Class<*>> = if (c.isSealed) c.permittedSubclasses.flatMap { leaves(it) } else listOf(c)
        val allEffects = leaves(Effect::class.java).map { it.simpleName }.sorted()
        val surface = listOf("DamageOpponent", "ReturnFromDiscard")
        val runs = sortedMapOf<String, MutableMap<String, Int>>() // effect -> game -> runs
        val rows = mutableListOf<String>()
        val files = java.io.File(CORPUS_DIR).listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
        for (f in files) {
            val c = corpusFromJson(f.readText())
            val g = c.bundle
            val rules = g.compile().playable()
            val zones = sortedSetOf<String>()
            val ran = sortedMapOf<String, Int>()
            var questions = 0
            for (k in c.cases) {
                var st = step(rules, Run(startGame(g, rules, k.seats.map { DeckPick(it) }, k.seed).traced(), maxTurns = k.maxTurns))
                var i = 0
                while (true) {
                    val s = st.state
                    for (p in s.players.values) {
                        if (p.hand.isNotEmpty()) zones += "hand"
                        if (p.library.isNotEmpty()) zones += "library"
                        if (p.graveyard.isNotEmpty()) zones += "graveyard"
                        for ((z, cards) in p.customZones) if (cards.isNotEmpty()) zones += z
                    }
                    if (s.exile.isNotEmpty()) zones += "exile"
                    if (s.stack.isNotEmpty()) zones += "stack"
                    for (perm in s.battlefield.values) zones += "in play: ${perm.zone.def}"
                    if (st.over) break
                    st = st.next(k.answers[i++]); questions++
                }
                for ((e, n) in st.state.trace!!.effects) ran[e] = (ran[e] ?: 0) + n
            }
            val combat = when (val cd = g.rules.combat) { is CombatDoc.Preset -> cd.name; is CombatDoc.Program -> "its own program" }
            rows += "| ${g.name} | $combat | ${c.cases.size} | $questions | ${ran.size} | ${zones.joinToString(", ")} |"
            for ((e, n) in ran) runs.getOrPut(e) { sortedMapOf() }[f.name.removeSuffix(".json")] = n
        }
        val core = allEffects - surface.toSet()
        val never = core.filter { it !in runs }
        val text = buildString {
            appendLine("| game | combat | cases | questions | effect cases run | zones used |")
            appendLine("|---|---|---|---|---|---|")
            rows.forEach { appendLine(it) }
            appendLine()
            appendLine("**${core.count { it in runs }} of ${core.size} core `Effect` cases run in at least one recorded game.**")
            appendLine("Never run: ${never.joinToString(", ") { "`$it`" }.ifEmpty { "none" }}.")
            appendLine("Surface forms, lowered before the engine sees them, so never run as themselves: ${surface.joinToString(", ") { "`$it`" }}.")
            appendLine()
            appendLine("| effect | runs | games |")
            appendLine("|---|---|---|")
            for ((e, byGame) in runs) appendLine("| `$e` | ${byGame.values.sum()} | ${byGame.keys.joinToString(", ")} |")
        }
        val begin = "<!-- measured:begin -->"; val end = "<!-- measured:end -->"
        val doc = java.io.File("CorpusCoverage.md")
        val old = doc.readText()
        val a = old.indexOf(begin); val b = old.indexOf(end)
        assertTrue(a >= 0 && b > a, "CorpusCoverage.md has the measured markers")
        val now = old.substring(0, a + begin.length) + "\n" + text + old.substring(b)
        if (System.getenv("CGE_WRITE_CORPUS") == "1") doc.writeText(now)
        assertTrue(doc.readText() == now, "the measured section is stale; regenerate with CGE_WRITE_CORPUS=1")
        assertTrue(runs.keys.all { it in allEffects }, "every traced name is an Effect case: ${runs.keys - allEffects.toSet()}")
        assertTrue(runs.size >= 10, "the corpus runs a real spread of verbs: ${runs.keys}")
    }

    // -- the start of a game is core's, and takes seats as a list ---------
    check("startGame seats a list: seat i is P<i>, ids from (i+1)*100000, and a missing deck plays tokens") {
        val g = CORE_BUNDLE
        val rules = g.compile().playable()
        val s = startGame(g, rules, listOf(DeckPick(0), DeckPick(1), DeckPick(99)), seed = 5, shuffle = false)
        assertEq(listOf("P0", "P1", "P2"), s.turnOrder, "one player per seat, in seat order")
        for ((i, pid) in s.turnOrder.withIndex()) {
            val ids = s.players.getValue(pid).let { it.library + it.hand }.map { it.instanceId }
            assertTrue(ids.isNotEmpty() && ids.all { it in (i + 1) * 100_000 until (i + 2) * 100_000 }, "$pid's cards sit in its own id range: ${ids.take(3)}")
        }
        assertTrue(s.players.getValue("P2").let { it.library + it.hand }.all { it.cardId == "token" }, "no deck 99: tokens stand in")
        val stations = s.battlefield.values.groupBy { it.controller }.mapValues { it.value.size }
        assertTrue((stations["P0"] ?: 0) > 0 && (stations["P1"] ?: 0) > 0 && "P2" !in stations, "a deck's slot cards start in play; tokens have none: $stations")
        assertEq(ccgui.PlaySession(seed = 5, p0Deck = 0, p1Deck = 1).opening(g, rules).digest(),
            startGame(g, rules, listOf(DeckPick(0), DeckPick(1)), seed = 5).digest(), "the Player's opening IS startGame")
    }

    check("a version-1 corpus (p0Deck / p1Deck) still reads, as the same cases") {
        val f = java.io.File(CORPUS_DIR).listFiles { x -> x.name.endsWith(".json") }!!.sortedBy { it.name }.first()
        val v2 = f.readText()
        val v1 = v2.replaceFirst("\"corpusVersion\":2", "\"corpusVersion\":1")
            .replace(Regex("\\{\"seats\":\\[(-?\\d+),(-?\\d+)\\],")) { m -> "{\"p0Deck\":${m.groupValues[1]},\"p1Deck\":${m.groupValues[2]}," }
        assertTrue(v1 != v2 && "p0Deck" in v1, "the test really built a version-1 file")
        assertEq(corpusFromJson(v2).cases, corpusFromJson(v1).cases)
    }

    // -- one rule for which questions reach a player ------------------------
    check("a session recorded the Player's way replays unchanged through the agent and the corpus runner") {
        for (g in listOf(EPR_SKIRMISH, CORE_BUNDLE)) {
            val rules = g.compile().playable()
            val seats = listOf(DeckPick(0), DeckPick(1))
            // The Player's recording: bots on both seats, each answer recorded
            // as a reference, exactly as the Hotseat's loop keeps them.
            val bots = listOf("P0", "P1").associateWith { ccgui.HeuristicPilot(it, rules) }
            var st = step(rules, Run(startGame(g, rules, seats, 7), maxTurns = 16))
            while (!st.over) st = st.next(runSync { bots.getValue(st.full!!.player).ask(st.question!!) }.recorded())
            val recorded = st.run.answers
            val live = st.state
            // The agent's replay.
            val session = ccgui.PlaySession(seed = 7, p0Deck = 0, p1Deck = 1, answers = recorded)
            val agent = ccgui.advance(g, session, maxTurns = 16)
            assertTrue(agent.over, "${g.name}: the agent finds the game over")
            assertEq(live.turnNumber to live.losers.toList(), agent.turn to agent.losers, "${g.name}: the agent reaches the Player's end")
            // The corpus: the same pilots record the same answers, and the
            // corpus runner replays the Player's answers to the same end.
            val case = ccgui.recordCorpusCase(g, rules, seats, 7, 16,
                ccgui.SeatedPilots(mapOf("P0" to ccgui.HeuristicPilot("P0", rules), "P1" to ccgui.HeuristicPilot("P1", rules)), fallback = ccgui.PassingPilot()))
            assertEq(case.answers, recorded, "${g.name}: the Player's session IS the corpus case's answers")
            val (seen, end) = ccgui.replayCorpusCase(g, rules, case.copy(answers = recorded))
            assertEq(live.digest(), end, "${g.name}: the corpus runner reaches it too")
            assertEq(recorded.size, seen.size, "${g.name}: one question per recorded answer")
        }
    }

    // -- one way to run a game ---------------------------------------
    check("resuming the parked engine and replaying from the start agree at every question") {
        val g = EPR_SKIRMISH
        val rules = g.compile().playable()
        val start = startGame(g, rules, listOf(DeckPick(0), DeckPick(1)), 7)
        val bots = listOf("P0", "P1").associateWith { ccgui.HeuristicPilot(it, rules) }
        var st = step(rules, Run(start, maxTurns = 16))
        var n = 0
        while (!st.over) {
            if (n % 5 == 0) {
                val fresh = step(rules, st.run)
                assertEq(st.state.digest(), fresh.state.digest(), "question $n: the same position")
                assertEq(st.full, fresh.full, "question $n: the same question")
            }
            st = st.next(runSync { bots.getValue(st.full!!.player).ask(st.question!!) }.recorded())
            n++
        }
        assertTrue(n > 30, "a real game: $n questions")
        assertEq(st.state.digest(), step(rules, st.run).state.digest(), "and the same end")
    }

    check("a position answered twice branches; the second answer replays") {
        val g = CORE_BUNDLE
        val rules = g.compile().playable()
        val at = step(rules, Run(startGame(g, rules, listOf(DeckPick(0), DeckPick(1)), 7), maxTurns = 8))
        assertTrue(at.full is Question.Priority, "the first question is a priority window")
        val conceded = at.next(Answer.Concede)
        val passed = at.next(Answer.Pass)
        assertEq(listOf(at.full!!.player), conceded.state.losers.toList(), "the first branch conceded")
        assertTrue(at.full!!.player !in passed.state.losers, "the second did not: it replayed, not resumed the used engine")
        assertEq(at.run.answers.size + 1, passed.run.answers.size, "each branch is its own run")
    }

    // Combat asked through the constructor PARAMETER `input`, which shadowed
    // the viewing property in Engine's initializer: its questions carried the
    // referee's state and were asked even with nothing to choose.
    check("combat questions go out like any other: the asked seat's view, and never when forced") {
        val g = BUNDLED.first { it.name.startsWith("Sparkfield") }
        val rules = g.compile().playable()
        val start = startGame(g, rules, listOf(DeckPick(0), DeckPick(0)), 20260925)
        val seen = mutableListOf<Question>()
        val pilots = listOf("P0", "P1").associateWith { ccgui.HeuristicPilot(it, rules) }
        val probe = PlayerInput { q -> seen += q; pilots.getValue(q.player).ask(q) }
        runSync { Engine(probe, rules, answersForced = true).playGame(start, maxTurns = 16) }
        val combat = seen.filter { it is Question.Attackers || it is Question.Blockers }
        assertTrue(combat.isNotEmpty(), "the game has combat questions: ${seen.size} questions")
        for (q in combat) {
            val opp = q.state!!.turnOrder.first { it != q.player }
            assertTrue(q.state!!.players.getValue(opp).hand.all { it.cardId == HIDDEN_CARD }, "${q::class.simpleName}: the opponent's hand is masked")
            assertTrue(q.state!!.pending.isEmpty() && q.state!!.rngState == 0, "${q::class.simpleName}: a view, not the referee's state")
            assertTrue(!q.forced(rules), "${q::class.simpleName}: asked only with something to choose")
        }
    }

    // -- the priority loop is data -------------------------------------
    check("checkpoint: every recorded game picks up from its priority windows, digest for digest") {
        var resumed = 0
        for (f in java.io.File(CORPUS_DIR).listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }) {
            val c = corpusFromJson(f.readText())
            val rules = c.bundle.compile().playable()
            for (k in c.cases.take(2)) {
                // Walk the case, noting its checkpoints: the turn's own windows.
                val start = startGame(c.bundle, rules, k.seats.map { DeckPick(it) }, k.seed)
                val points = mutableListOf<Pair<GameState, Int>>()
                var st = step(rules, Run(start, maxTurns = k.maxTurns))
                var i = 0
                while (!st.over) {
                    if (st.state.priority?.at != null) points += st.state to i
                    st = st.next(k.answers[i++])
                }
                assertTrue(points.isNotEmpty(), "${f.name} ${k.seats}: the game has checkpoints")
                // Early, middle, late: resumed from there, the rest of the
                // game is the recorded one.
                for ((cp, at) in listOf(points.first(), points[points.size / 2], points.last())) {
                    var r = step(rules, Run(cp, maxTurns = k.maxTurns))
                    var j = at
                    while (!r.over) {
                        assertEq(k.digests[j], r.full!!.state?.digest(), "${f.name} ${k.seats} seed ${k.seed}: from question $at, question $j")
                        r = r.next(k.answers[j++])
                    }
                    assertEq(k.answers.size, j, "${f.name}: resumed at $at, it asks exactly the recorded questions")
                    assertEq(k.end, r.state.digest(), "${f.name}: resumed at $at, the same end")
                    resumed++
                }
            }
        }
        assertTrue(resumed >= 30, "resumed $resumed times")
    }

    check("frames: a question's state alone carries the game on; compacted() is that state and no history") {
        val g = EPR_SKIRMISH
        val rules = g.compile().playable()
        val bots = listOf("P0", "P1").associateWith { ccgui.HeuristicPilot(it, rules) }
        var st = step(rules, Run(startGame(g, rules, listOf(DeckPick(0), DeckPick(1)), 7), maxTurns = 16))
        repeat(30) { if (!st.over) st = st.next(runSync { bots.getValue(st.full!!.player).ask(st.question!!) }.recorded()) }
        assertTrue(!st.over && st.state.pending.isNotEmpty(), "a question's state holds its pending work")
        val short = st.compacted()
        assertTrue(short.answers.isEmpty() && short.start === st.state, "compacted: this state, no answers")
        val again = step(rules, short)
        assertEq(st.state.digest(), again.state.digest(), "the same position")
        assertEq(st.full, again.full, "the same question")
        // And the game goes on from it exactly as from the whole history.
        val a = runSync { bots.getValue(st.full!!.player).ask(st.question!!) }.recorded()
        assertEq(st.next(a).state.digest(), again.next(a).state.digest(), "one answer on, from either")
    }

    check("frames: a window opened while something resolves is a frame of its own") {
        // A spell whose effect opens a window: its questions are asked with
        // the outer window's frame below the inner one's.
        val inner = Recording(mapOf("P0" to listOf(instant(Effect.CombatWindow))))
        val shapes = mutableListOf<List<Frame>>()
        val probe = object : PlayerInput {
            override fun seesAll(player: PlayerId) = true
            override suspend fun ask(q: Question): Answer { q.state?.let { shapes += it.pending }; return inner.ask(q) }
        }
        runSync { Engine(probe).run(board()) }
        val nested = shapes.filter { it.size == 2 }
        assertTrue(nested.isNotEmpty(), "questions asked inside the nested window: ${shapes.map { it.size }}")
        for (f in nested) {
            assertTrue(f.all { it is Frame.Window } && (f[1] as Frame.Window).at == null, "outer window, then the inner one")
            assertTrue(f[0].log.size <= 2, "the outer window waits with its priority answer, not the game: ${f[0].log.size}")
        }
    }

    check("frames: across the corpus no frame's log outgrows one step of the game") {
        var questions = 0; var nested = 0; var longest = 0
        for (f in java.io.File(CORPUS_DIR).listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }) {
            val c = corpusFromJson(f.readText())
            val rules = c.bundle.compile().playable()
            for (k in c.cases) {
                var st = step(rules, Run(startGame(c.bundle, rules, k.seats.map { DeckPick(it) }, k.seed), maxTurns = k.maxTurns))
                var i = 0
                while (!st.over) {
                    val frames = st.state.pending
                    assertTrue(frames.first() is Frame.Game, "${f.name}: the game frame is at the bottom")
                    if (frames.drop(1).any { it is Frame.Window && it.at == null }) nested++
                    longest = maxOf(longest, frames.maxOf { it.log.size })
                    questions++
                    st = st.next(k.answers[i++])
                }
                assertTrue(st.state.pending.isEmpty() && st.state.priority == null, "${f.name}: the end state is between questions")
            }
        }
        // The recorded games never ask inside a nested window (their combat
        // windows are all forced); the scripted check below does.
        assertTrue(longest <= 40, "the longest log is $longest entries over $questions questions: frames restart, they do not accumulate")
    }

    check("checkpoint: a window opened inside something else is not one; a state between questions carries none") {
        val g = EPR_SKIRMISH
        val rules = g.compile().playable()
        val start = startGame(g, rules, listOf(DeckPick(0), DeckPick(1)), 7)
        assertEq(null, start.priority, "a fresh game is not in a window")
        val c = corpusFromJson(java.io.File(CORPUS_DIR, corpusSlug(g.name) + ".json").readText())
        val k = c.cases.first()
        var st = step(rules, Run(startGame(g, rules, k.seats.map { DeckPick(it) }, k.seed), maxTurns = k.maxTurns))
        var i = 0
        var nested = 0; var top = 0
        while (!st.over) {
            val q = st.full!!
            val w = q.state?.priority
            if (q is Question.Priority) { assertTrue(w != null, "a priority question carries its window"); if (w!!.at == null) nested++ else top++ }
            else assertEq(null, w, "only a priority question carries a window: ${q::class.simpleName}")
            st = st.next(k.answers[i++])
        }
        assertEq(null, st.state.priority, "the end state is between questions")
        assertTrue(top > 0, "top-level windows: $top (nested: $nested)")
    }

    check("a question's state is the asked seat's view; full is the referee's") {
        val g = EPR_SKIRMISH
        val rules = g.compile().playable()
        val st = step(rules, Run(startGame(g, rules, listOf(DeckPick(0), DeckPick(1)), 7)))
        val p = st.full!!.player
        val opp = st.state.turnOrder.first { it != p }
        assertTrue(st.state.players.getValue(opp).hand.isNotEmpty(), "a specimen with a hand to hide")
        assertTrue(st.question!!.state!!.players.getValue(opp).hand.all { it.cardId == HIDDEN_CARD }, "the opponent's hand is masked in the question")
        assertTrue(st.full!!.state!!.players.getValue(opp).hand.none { it.cardId == HIDDEN_CARD }, "and open in the full one")
        assertEq(st.state, st.full!!.state, "state is the full state")
    }

    check("under answersForced no seat is asked a question it can only answer one way") {
        val rules = EPR_SKIRMISH.compile().playable()
        var asked = 0
        val probe = PlayerInput { q ->
            asked++
            assertTrue(!q.forced(rules), "asked a forced ${q::class.simpleName}")
            q.default()
        }
        // seesAll, so `forced` here reads the same full state the gate did.
        val seeing = object : PlayerInput by probe { override fun seesAll(player: PlayerId) = true }
        runSync { Engine(seeing, rules = rules, answersForced = true).playGame(startGame(EPR_SKIRMISH, rules, listOf(DeckPick(0), DeckPick(1)), 3), maxTurns = 6) }
        assertTrue(asked > 0, "and real questions were still asked: $asked")
    }

    // -- one codec for an Answer ---------------------------------------------
    check("a saved session writes each answer as answerToJson, and reads every kind back") {
        val all = listOf(
            Answer.Pass, Answer.Concede,
            Answer.PlayCard(100_004, CastZone.Declared("flag\u001eships"), 1, "lane:2"),
            Answer.PlayCard(7, CastZone.Std(HiddenZone.HAND)),
            Answer.ActivateAbility(3, 1), Answer.Target(9), Answer.Number(4),
            Answer.Attackers(mapOf(5 to CombatTarget.Player("P1"), 6 to CombatTarget.Obj(8))),
            Answer.Blockers(mapOf(8 to 5)), Answer.CombatTgt(null), Answer.CombatTgt(CombatTarget.Obj(2)),
            Answer.Blocker(null), Answer.Blocker(4), Answer.Modes(listOf(0, 2)), Answer.Cards(listOf(1, 2, 3)),
            Answer.Edit(TableEdit.Conjure("bolt", "P0", EditZone.HAND)), Answer.Edit(TableEdit.Move(4, EditZone.EXILE)),
            Answer.Edit(TableEdit.SetCounter("life", 7, player = "P1")), Answer.Edit(TableEdit.Draw("P0", 1)),
        )
        val session = ccgui.PlaySession(seed = 3, p0Deck = 0, p1Deck = 1, answers = all)
        assertEq(all, ccgui.decodePlaySession(session.encode())?.answers, "every kind round-trips")
        assertTrue(answerToJson(Answer.ActivateAbility(3, 1)) in session.encode(), "the record is the corpus's own form")
    }

    // -- a session is bound to the game it was recorded against -------------
    check("a session names its game: stamped when fresh, kept when it matches, restarted when the game changed") {
        val g = EPR_SKIRMISH
        val d = g.bundleDigest()
        val edited = g.copy(name = g.name + " (edited)")
        assertTrue(edited.bundleDigest() != d, "any edit changes the digest")
        assertEq(d, gameDocFromJson(gameDocToJson(g)).bundleDigest(), "and a save/load round trip does not")
        val fresh = ccgui.PlaySession()
        assertEq(ccgui.Bound(fresh.copy(bundle = d), 0), fresh.boundTo(d), "a fresh session is stamped, not restarted")
        val played = fresh.copy(bundle = d).answered(Answer.Pass).answered(Answer.Pass)
        assertEq(ccgui.Bound(played, 0), played.boundTo(d), "the same game: untouched")
        val moved = played.boundTo(edited.bundleDigest())
        assertEq(2, moved.dropped, "another game: its answers are dropped, and counted")
        assertTrue(moved.session.answers.isEmpty() && moved.session.generation == played.generation + 1 && moved.session.bundle == edited.bundleDigest(), "and it restarts under a new generation, bound to the new game")
        assertEq(2, played.copy(bundle = null).boundTo(d).dropped, "answers of unknown origin are not trusted either")
        assertEq(played, ccgui.decodePlaySession(played.encode()), "the codec carries the bundle")
    }

    check("the agent refuses a session recorded against another version of the game") {
        val g = EPR_SKIRMISH
        val s = ccgui.PlaySession(bundle = g.bundleDigest())
        assertTrue(!ccgui.advance(g, s).over, "its own game plays")
        val r = runCatching { ccgui.advance(g.copy(name = "other"), s) }
        assertTrue(r.isFailure && "different version" in (r.exceptionOrNull()?.message ?: ""), "another one is refused, loudly: ${r.exceptionOrNull()}")
    }

    // -- the bundled games ARE the content files --------------------------
    // content/ is the source; there is no Kotlin copy. A file must be exactly
    // what the codec writes for what it decodes to, so an edit is reviewable
    // and a codec change shows up as a diff here.
    check("each content file is the codec's own form of itself (normalise: CGE_WRITE_CONTENT=1)") {
        val stale = mutableListOf<String>()
        for (name in Bundled.index.values.flatten()) {
            val file = java.io.File(CONTENT_DIR, "$name.json")
            val text = Json.parse(gameDocToJson(gameDocFromJson(file.readText()))).pretty()
            if (System.getenv("CGE_WRITE_CONTENT") == "1") file.writeText(text)
            if (file.readText() != text) stale += file.path
        }
        assertTrue(stale.isEmpty(), "not in canonical form: $stale")
        val files = java.io.File(CONTENT_DIR).listFiles { f -> f.name.endsWith(".json") && f.name != "index.json" }.orEmpty().map { it.name }.sorted()
        assertEq(Bundled.index.values.flatten().map { "$it.json" }.sorted(), files, "index.json lists every content file, and only those")
        assertEq(listOf("builtin", "samples"), Bundled.index.keys.toList(), "the Creator installs these two lists")
    }

    check("every content file validates against the schema, and names its game by its file") {
        val schema = java.io.File("docs/schema/cardengine.schema.json").readText()
        for (name in Bundled.index.values.flatten()) {
            val text = java.io.File(CONTENT_DIR, "$name.json").readText()
            assertEq(emptyList<String>(), validate(schema, "GameDoc", Json.parse(text)), "$name: schema")
            assertEq(name, corpusSlug(gameDocFromJson(text).name), "$name.json holds the game its name slugs to")
        }
        assertEq(Bundled.all.size, Bundled.all.map { it.id }.toSet().size, "bundled ids are unique")
    }

    check("the combat presets are the library file, in canonical form (normalise: CGE_WRITE_CONTENT=1)") {
        val file = java.io.File(CONTENT_DIR, "combat/presets.json")
        val text = Json.parse(combatPresetsToJson(combatPresetsOf(file.readText()))).pretty()
        if (System.getenv("CGE_WRITE_CONTENT") == "1") file.writeText(text)
        assertTrue(file.readText() == text, "not in canonical form: ${file.path}")
        assertEq(COMBAT_PRESETS, combatPresetsOf(file.readText()), "the file is the library")
        val schema = java.io.File("docs/schema/cardengine.schema.json").readText()
        for ((name, c) in COMBAT_PRESETS) {
            assertEq(emptyList<String>(), validate(schema, "Combat", Json.parse(combatToJson(c))), "$name: schema")
        }
    }

    check("the test fixtures' CombatConfigs still lower to their library presets") {
        val fixtures = linkedMapOf(
            "mtg" to MTG_COMBAT, "fastSlow" to FAST_SLOW_COMBAT, "fastSlowLanes" to FAST_SLOW_LANES_COMBAT,
            "fastSlowLanesLocked" to FAST_SLOW_LANES_LOCKED_COMBAT, "fastSlowLanesCore" to FAST_SLOW_LANES_CORE_COMBAT,
            "singleStepLanesCore" to SINGLE_STEP_LANES_CORE_COMBAT, "screenedSingleStep" to SCREENED_SINGLE_STEP_COMBAT,
            "frontBack" to FRONT_BACK_COMBAT, "frontBackCore" to FRONT_BACK_CORE_COMBAT, "laneGridCore" to LANE_GRID_CORE_COMBAT,
            "hearthstone" to HEARTHSTONE_COMBAT, "onePiece" to ONE_PIECE_COMBAT, "yugioh" to YUGIOH_COMBAT,
        )
        assertEq(COMBAT_PRESETS.keys.toList(), fixtures.keys.toList(), "one fixture per preset")
        for ((name, cfg) in fixtures) assertEq(COMBAT_PRESETS.getValue(name), cfg.lowered(), "$name")
    }

    // -- combat in the language ------------------------------------------
    check("DECLARED combat is a program of combat verbs; the engine names no keyword on that path") {
        val program = Rules(combat = MTG_COMBAT.lowered()).combatProgram
        assertTrue(program is Effect.DeclareAttackers, "mtg lowers to DeclareAttackers: $program")
        val verbs = mutableListOf<String>()
        program!!.subst(object : Subst() {
            override fun rewrite(e: Effect): Effect = e.also { verbs += it::class.simpleName!! }
        })
        assertEq(listOf("CombatDamage", "CombatDamage", "DeclareAttackers", "DeclareBlockers"), verbs.filter { it != "Sequence" && it != "CombatWindow" }.sorted())
        assertEq(2, verbs.count { it == "CombatWindow" }, "a window after attackers and after blockers")
        // It round-trips, so it can be saved and authored like any effect.
        assertEq(program, effectFromJson(effectToJson(program)))
        // INDIVIDUAL combat is a window; each attack runs the attack program (slice 2).
        assertEq(Effect.CombatWindow, Rules(combat = HEARTHSTONE_COMBAT.lowered()).combatProgram)
        for (c in listOf(HEARTHSTONE_COMBAT, ONE_PIECE_COMBAT, YUGIOH_COMBAT)) {
            val a = Rules(combat = c.lowered()).attackProgram
            assertTrue(a is Effect.Attack, "$a")
            assertEq(a, effectFromJson(effectToJson(a!!)))
        }
        assertTrue((YUGIOH_COMBAT.attackProgram() as Effect.Attack).then.let { it is Effect.Sequence && it.steps.single() is Effect.Clash })
        assertTrue((HEARTHSTONE_COMBAT.attackProgram() as Effect.Attack).then.let { it is Effect.Sequence && it.steps.single() is Effect.Strike })
        // FREE: one FreeAttacks per step (slice 3), every config a program now.
        val free = Rules(combat = FAST_SLOW_COMBAT.lowered()).combatProgram
        assertTrue(free is Effect.Sequence && free.steps.all { it is Effect.FreeAttacks } && free.steps.size == 2, "$free")
        assertEq(null, Rules(combat = MTG_COMBAT.lowered()).attackProgram)
    }

    check("a card may use the combat verbs; a replacement may not wait on one") {
        fun codes(f: FaceDoc) = GameDoc(sets = listOf(SetDoc("Core", listOf(CardDoc(faces = listOf(f)))))).diagnostics()
            .filter { it.severity == Severity.ERROR }.map { it.code }
        assertEq(emptyList<String>(), codes(FaceDoc("W", setOf("Instant"), castEffect = MTG_COMBAT.program()!!)))
        val held = FaceDoc("R", setOf("Creature"), replacements = listOf(ReplacementDoc.Replace(EventPattern.Damaged(), Effect.CombatWindow)))
        assertEq(listOf("replacement-asks"), codes(held))
    }

    check("an extra combat from a card: its attack hits, and the outer combat state comes back") {
        val s0 = board("P0" to beast("Ox", 3))
        val ox = s0.inPlayIds.single()
        val extra = instant(MTG_COMBAT.program()!!)
        val end = runSync {
            Engine(ScriptedInput.of("P0" to listOf(extra), attacks = mapOf(ox to CombatTarget.Player("P1")))).run(s0)
        }
        assertEq(17, end.players.getValue("P1").life, "the extra combat's attack dealt 3")
        assertTrue(end.battlefield.getValue(ox).exhausted, "attacking exhausted it")
        assertEq(null, end.combat)
    }

    check("a trigger can fire at a combat step, before that step's damage") {
        // "At the start of the regular damage step of your combat, this gets +2 power."
        val pumped = card("Pumper", setOf("Creature"),
            baseChars = Characteristics("Pumper", setOf("Creature"), mapOf("power" to 1, "toughness" to 5)),
            triggers = listOf(TriggerDoc.On(EventPattern.OnCombatStep("regular", PlayerRef.You),
                Effect.ApplyModifier(creatures().only(SELF), listOf(CharOp.PlusField("power", lit(2)))))),
        )
        val s0 = board("P0" to pumped)
        val me = s0.inPlayIds.single()
        val input = PlayerInput { q ->
            when (q) {
                is Question.Attackers -> Answer.Attackers(mapOf(me to CombatTarget.Player("P1")))
                else -> q.default()
            }
        }
        val rules = Rules(turn = TurnStructure(listOf(PhaseSpec("combat", combat = true))))
        val end = runSync { Engine(input, rules).playGame(s0, maxTurns = 1) }
        assertEq(17, end.players.getValue("P1").life, "1 power + 2 from the step trigger")
        // Declare steps are events too, and the names resolve without an unknown-step error.
        val doc = GameDoc(sets = listOf(SetDoc("Core", listOf(CardDoc(faces = listOf(FaceDoc("T", setOf("Creature"),
            triggers = listOf(TriggerDoc.On(EventPattern.OnCombatStep(DECLARE_BLOCKERS_STEP), Effect.NoOp)))))))))
        assertEq(emptyList<String>(), doc.diagnostics().filter { it.severity == Severity.ERROR }.map { it.code })
    }

    check("an attack (INDIVIDUAL) runs the attack program: taunt, then the exchange, then the combat state is gone") {
        val rules = Rules(combat = HEARTHSTONE_COMBAT.lowered())
        val guard = card("Wall", setOf("Creature"),
            baseChars = Characteristics("Wall", setOf("Creature"), mapOf("power" to 1, "toughness" to 4), keywords = setOf("taunt")))
        val s0 = board("P0" to beast("Ox", 3), "P1" to guard)
        val (ox, wall) = s0.inPlayIds
        val face = PriorityAction.Attack(ox, CombatTarget.Player("P1"))
        val refused = runSync { Engine(ScriptedInput.of("P0" to listOf(face)), rules).run(s0) }
        assertEq(20, refused.players.getValue("P1").life, "a taunt guard forbids the face")
        val hit = PriorityAction.Attack(ox, CombatTarget.Obj(wall))
        val end = runSync { Engine(ScriptedInput.of("P0" to listOf(hit)), rules).run(s0) }
        assertEq(3, end.battlefield.getValue(wall).damageMarked, "the attacker dealt its power")
        assertEq(1, end.battlefield.getValue(ox).damageMarked, "the guard struck back")
        assertEq(null, end.combat)
        assertEq(1, end.battlefield.getValue(ox).attacksThisTurn)
    }

    check("a clash (COMPARE): the higher stat destroys the lower, the difference to its controller") {
        val rules = Rules(combat = YUGIOH_COMBAT.lowered())
        val s0 = board("P0" to beast("Big", 5), "P1" to beast("Small", 2))
        val (big, small) = s0.inPlayIds
        val end = runSync { Engine(ScriptedInput.of("P0" to listOf(PriorityAction.Attack(big, CombatTarget.Obj(small)))), rules).run(s0) }
        assertTrue(small !in end.battlefield, "5 beats 2")
        assertEq(17, end.players.getValue("P1").life, "the difference, 3, went to its controller")
        assertTrue(big in end.battlefield)
    }

    check("reach is authored: a free-combat step whose own rule no preset has") {
        // "Each of your creatures may attack something no stronger than itself."
        val atk = IntExpr.TargetField(BoundTarget(ATTACKER), "power")
        val tgt = IntExpr.TargetField(BoundTarget(TARGET), "power")
        val step = Effect.FreeAttacks(
            "duel", creatures().yours(),
            body = Gun(reaches = tgt lte atk, reachesFace = BoolExpr.Const(false), amount = atk),
        )
        val s0 = board("P0" to beast("Ox", 3), "P1" to beast("Cub", 2), "P1" to beast("Bull", 5))
        val (ox, cub, bull) = s0.inPlayIds
        fun swing(at: CombatTarget) = runSync {
            Engine(ScriptedInput.of("P0" to listOf(instant(step)), combatTargets = listOf(at))).run(s0)
        }
        assertTrue(cub !in swing(CombatTarget.Obj(cub)).battlefield, "a weaker target is in reach, and dies")
        assertEq(0, swing(CombatTarget.Obj(bull)).battlefield.getValue(bull).damageMarked, "a stronger one is out of reach")
        assertEq(20, swing(CombatTarget.Player("P1")).players.getValue("P1").life, "and the face never is")
        // The expressions are plain language: they round-trip, and the scope
        // checker knows the step binds the attacker and the target.
        assertEq(step, effectFromJson(effectToJson(step)))
        val doc = GameDoc(sets = listOf(SetDoc("Core", listOf(CardDoc(faces = listOf(FaceDoc("Duel", setOf("Instant"), castEffect = step)))))))
        assertEq(emptyList<String>(), doc.diagnostics().filter { it.severity == Severity.ERROR }.map { it.code })
    }

    check("the presets' reach is expressions: the lane grid, written out") {
        val grid = Rules(combat = LANE_GRID_CORE_COMBAT.lowered()).combatProgram as Effect.Sequence
        val free = grid.steps.single() as Effect.FreeAttacks
        assertEq(listOf(AttackRange.CLOSE, AttackRange.FAR), free.guns.map { it.label }, "one gun per range stat")
        assertTrue(free.overflow, "overflow is on the step, not a flag the engine reads")
        // Nothing in the engine names a lane rule any more: the program is data.
        assertEq(free, effectFromJson(effectToJson(free)))
    }

    check("declared combat: attacks and blocks are state while it runs, and gone after") {
        val s0 = board("P0" to beast("Ox", 3), "P1" to beast("Cub", 2))
        val (ox, cub) = s0.inPlayIds
        val seen = mutableListOf<CombatState?>()
        val input = PlayerInput { q ->
            when (q) {
                is Question.Attackers -> Answer.Attackers(mapOf(ox to CombatTarget.Player("P1")))
                is Question.Blockers -> Answer.Blockers(mapOf(cub to ox))
                is Question.Priority -> { seen += q.state.combat; Answer.Pass }
                else -> q.default()
            }
        }
        // A turn that is one combat phase.
        val rules = Rules(turn = TurnStructure(listOf(PhaseSpec("combat", combat = true))))
        val end = runSync { Engine(input, rules).playGame(s0, maxTurns = 1) }
        assertEq(null, end.combat, "combat state is cleared when combat ends")
        assertTrue(cub !in end.battlefield, "the 2-toughness blocker died to 3")
        assertEq(2, end.battlefield.getValue(ox).damageMarked, "the blocker struck back")
        assertTrue(seen.any { it?.attacks == listOf(ox to CombatTarget.Player("P1")) }, "the declared attack was state in the window: $seen")
        assertTrue(seen.any { it?.blocks == listOf(cub to ox) }, "the block was state in the next window: $seen")
    }

    // -- lowering changes no game --------------------------------------
    // The golden file was written by the engine before lowering existed,
    // so it is a record of behaviour, not of this code's opinion of itself.
    // Rewrite it only for a change that is MEANT to alter play -- and say so
    // in that commit.
    check("every bundled game replays to the recorded end state") {
        val golden = java.io.File(REPLAY_GOLDEN)
        val now = replayCorpus()
        if (System.getenv("CGE_WRITE_REPLAY") == "1") {
            golden.parentFile.mkdirs(); golden.writeText(now.joinToString("\n", postfix = "\n"))
            println("  wrote $REPLAY_GOLDEN (${now.size} games)")
        }
        val want = golden.readLines().filter { it.isNotBlank() }
        assertEq(want.size, now.size, "the corpus changed shape")
        val diff = want.zip(now).filter { (a, b) -> a != b }
        assertTrue(diff.isEmpty(), "${diff.size} of ${now.size} games ended differently: " +
            diff.take(5).joinToString("; ") { (a, b) -> "${a.substringBeforeLast('|')}: ${a.substringAfterLast('|')} -> ${b.substringAfterLast('|')}" })
    }

    // -- The app shell. src/com is invisible to the compiler
    //    here, so these read its source: the module contract and the one
    //    place Back is decided.

    check("every rail module has exactly one ModuleSpec, and MODULES lists them in rail order") {
        val app = java.io.File("src/com/ccg").listFiles()!!.filter { it.name.endsWith(".kt") }
        val specs = Regex("""internal val (\w+) = ModuleSpec\(\s*Module\.(\w+)""")
        val byName = app.flatMap { f -> specs.findAll(f.readText()).map { it.groupValues[1] to it.groupValues[2] }.toList() }
        assertEq(ccgui.Module.entries.map { it.name }.sorted(), byName.map { it.second }.sorted(), "one spec per module, none extra")
        val registry = java.io.File("src/com/ccg/Modules.kt").readText()
            .substringAfter("val MODULES").substringAfter("listOf(").substringBefore(")")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val module = byName.toMap()
        assertEq(ccgui.Module.entries.map { it.name }, registry.map { module[it] }, "MODULES in ccgui.Module order")
    }

    check("Back is decided by ccgui.Nav: BackHandler only in the shell and the table's transient overlays") {
        // Hotseat.kt: the card focus and the tools drawer, view state
        // that closes before Back reaches the shell.
        // Creator.kt: the shell, and the jump search over it.
        val allowed = mapOf("Creator.kt" to 2, "Hotseat.kt" to 2)
        val uses = java.io.File("src/com/ccg").listFiles()!!.filter { it.name.endsWith(".kt") }.associate { f ->
            f.name to f.readLines().count { l -> Regex("""\bBackHandler\s*[({]""").containsMatchIn(l) && !l.trimStart().startsWith("//") }
        }.filterValues { it > 0 }
        assertEq(allowed, uses, "a new Back behaviour is a case in Nav.back plus a UiTest check, not a BackHandler")
    }

    check("the jump search names only Rules sections the app has") {
        val src = java.io.File("src/com/ccg/RulesModule.kt").readText()
        val enumBody = src.substringAfter("enum class RulesSection").substringBefore("}")
        val names = Regex("""\b([A-Z]+)\(""").findAll(enumBody).map { it.groupValues[1] }.toSet()
        assertEq(names, ccgui.jumpSections(EPR_SKIRMISH).map { it.name }.toSet(), "every section, and no other")
    }

    // -- Sandbox edits are answers ---------------------------------------------

    /** EPR at its first priority question, run as a sandbox or not. */
    fun sandboxAt(sandbox: Boolean): Triple<Rules, GameState, Stepped> {
        val g = EPR_SKIRMISH
        val rules = g.compile().playable()
        val start = PlaySession(seed = 7).opening(g, rules)
        var st = step(rules, Run(start, maxTurns = 16, sandbox = sandbox))
        while (!st.over && st.full !is Question.Priority) st = st.next(st.full!!.default())
        assertTrue(!st.over, "a priority question comes")
        return Triple(rules, start, st)
    }

    check("an edit at a priority question edits the table and asks the same question again") {
        val (rules, start, st) = sandboxAt(true)
        val who = st.full!!.player
        val key = rules.cards.keys.first()
        val hand0 = st.state.players.getValue(who).hand.size
        val after = st.next(Answer.Edit(TableEdit.Conjure(key, who, EditZone.HAND)))
        assertTrue(after.full is Question.Priority && after.full!!.player == who, "the same seat is asked again")
        val hand = after.state.players.getValue(who).hand
        assertEq(hand0 + 1, hand.size)
        assertEq(key, hand.last().cardId)
        // The rest of the table edits, one after another.
        val onBoard = after.next(Answer.Edit(TableEdit.Conjure(key, who, EditZone.BATTLEFIELD)))
        val perm = onBoard.state.battlefield.values.last { it.cardId == key }
        val life = onBoard.next(Answer.Edit(TableEdit.SetCounter(LIFE, 3, player = who)))
        assertEq(3, life.state.players.getValue(who).counters[LIFE])
        val lib0 = life.state.players.getValue(who).library.size
        val drawn = life.next(Answer.Edit(TableEdit.Draw(who, 2)))
        assertEq(lib0 - 2, drawn.state.players.getValue(who).library.size)
        val gone = drawn.next(Answer.Edit(TableEdit.Move(perm.id, EditZone.EXILE)))
        assertTrue(perm.id !in gone.state.battlefield, "exiled from play")
        // An edit is an answer: the run replays from its answers alone.
        val replayed = step(rules, gone.run)
        assertEq(gone.state.canonical(), replayed.state.canonical(), "edits replay exactly")
        assertEq(gone.run.answers, answersFromJson(answersToJson(gone.run.answers)), "and save as data")
    }

    check("an edit outside a sandbox run is refused -- pilots, agents and the corpus cannot send one") {
        val (rules, _, st) = sandboxAt(false)
        val r = runCatching { st.next(Answer.Edit(TableEdit.Draw(st.full!!.player, 1))) }
        assertTrue(r.isFailure && r.exceptionOrNull()!!.message!!.contains("sandbox"), "refused: ${r.exceptionOrNull()}")
    }

    check("a move takes a card from any zone to any other, and a permanent leaves play by the game's own rule") {
        val (rules, _, st) = sandboxAt(true)
        val who = st.full!!.player
        val p0 = st.state.players.getValue(who)
        val card = p0.hand.first()
        // hand -> graveyard -> library top -> exile -> hand: the same card, the same id
        var at = st
        for (z in listOf(EditZone.GRAVEYARD, EditZone.LIBRARY_TOP, EditZone.EXILE)) {
            at = at.next(Answer.Edit(TableEdit.Move(card.instanceId, z)))
            assertTrue(at.full is Question.Priority && at.full!!.player == who, "asked again after $z")
        }
        val p1 = at.state.players.getValue(who)
        assertTrue(card !in p1.hand && card !in p1.graveyard && card !in p1.library, "not in any of its old zones")
        assertEq(card, at.state.exile.last(), "in exile, the same instance")
        // out of exile names its owner: without one it is nothing to do
        val nobody = at.next(Answer.Edit(TableEdit.Move(card.instanceId, EditZone.HAND)))
        assertEq(at.state.exile, nobody.state.exile, "an ownerless move out of exile does nothing")
        at = at.next(Answer.Edit(TableEdit.Move(card.instanceId, EditZone.BATTLEFIELD, owner = who)))
        assertTrue(card !in at.state.exile, "left exile")
        val perm = at.state.battlefield.values.last { it.cardId == card.cardId && it.controller == who }
        // a permanent, back to its owner's hand by the game's leaving
        at = at.next(Answer.Edit(TableEdit.Move(perm.id, EditZone.HAND)))
        assertTrue(perm.id !in at.state.battlefield, "left play")
        assertTrue(at.state.players.getValue(who).hand.any { it.cardId == card.cardId }, "back in hand")
        // to play from play is nothing to do
        val still = at.state.battlefield.keys.first()
        val same = at.next(Answer.Edit(TableEdit.Move(still, EditZone.BATTLEFIELD)))
        assertEq(at.state.battlefield, same.state.battlefield)
        assertEq(at.state.canonical(), step(rules, at.run).state.canonical(), "moves replay exactly")
    }

    check("an empty table: a seat with no deck starts with nothing and never decks out") {
        // A game whose turn draws from the library (EPR's program never draws
        // from an empty one, so it could not see the rule either way).
        val g = Bundled.game("sample-mtg")
        val rules = g.compile().playable()
        val picks = listOf(DeckPick(DeckPick.NONE), DeckPick(0))
        val start = startGame(g, rules, picks, 7)
        assertTrue(start.players.getValue("P0").let { it.library.isEmpty() && it.hand.isEmpty() }, "P0 has nothing")
        assertTrue(start.players.getValue("P1").hand.isNotEmpty(), "P1 dealt its deck")
        assertEq(setOf("P0"), undeckedSeats(picks))
        // Passing every question: P0's draws come up empty, and it plays on.
        var st = step(rules, Run(start, maxTurns = 6, undecked = undeckedSeats(picks)))
        while (!st.over) st = st.next(st.full!!.default())
        assertTrue("P0" !in st.state.losers, "no deck, no deck-out: ${st.state.losers}")
        assertTrue(st.state.log.any { it.contains("P0 has no deck to draw from") }, "the empty draw is logged")
        // The same table without the rule: the guard fires.
        var bare = step(rules, Run(start, maxTurns = 6))
        while (!bare.over) bare = bare.next(bare.full!!.default())
        assertTrue("P0" in bare.state.losers, "without it, P0 decks out")
    }

    check("a bench asks every priority question; a game asks only the ones with a choice") {
        // Both seats empty: nobody has anything to do, so the forced-question rule answers every
        // question -- and an edit could never go in.
        val g = EPR_SKIRMISH
        val rules = g.compile().playable()
        val bench = PlaySession(seed = 7, p0Deck = DeckPick.NONE, p1Deck = DeckPick.NONE, sandbox = true)
        val game = bench.copy(sandbox = false)
        assertTrue(step(rules, game.tableRun(game.opening(g, rules))).over, "a plain run of an empty table asks nothing")
        val st = step(rules, bench.tableRun(bench.opening(g, rules)))
        assertTrue(st.full is Question.Priority, "the bench stops at the first priority question")
        val after = st.next(Answer.Edit(TableEdit.Conjure(rules.cards.keys.first(), st.full!!.player, EditZone.HAND)))
        assertTrue(after.full is Question.Priority && after.full!!.player == st.full!!.player, "and takes an edit there")
    }

    check("a table's session exports as a conformance case, open while the game goes on") {
        val g = EPR_SKIRMISH
        val rules = g.compile().playable()
        val base = PlaySession(seed = 7, p0Deck = DeckPick.NONE, p1Deck = DeckPick.NONE, sandbox = true)
        var st = step(rules, base.tableRun(base.opening(g, rules)))
        while (st.full !is Question.Priority) st = st.next(st.full!!.default())
        val key = rules.cards.keys.first()
        st = st.next(Answer.Edit(TableEdit.Conjure(key, st.full!!.player, EditZone.HAND)))
        repeat(5) { st = st.next(st.full!!.default()) }
        val session = base.copy(answers = st.run.answers)
        val case = session.asCorpusCase(g, rules)
        assertTrue(case.open && case.sandbox && case.pauses, "a game still going on a bench is an open sandbox case")
        assertEq(session.answers, case.answers)
        val back = corpusFromJson(corpusToJson(Corpus(g, listOf(case)))).cases.single()
        assertEq(case, back, "the case survives its file")
        val (seen, end) = replayCorpusCase(g, rules, back)
        assertEq(case.digests, seen, "a port's runner sees the same states")
        assertEq(case.end, end)
        // An edit changes a digest: a runner that dropped it would diverge.
        val unedited = session.copy(answers = session.answers.filterNot { it is Answer.Edit }).asCorpusCase(g, rules)
        assertTrue(unedited.digests != case.digests.drop(1).take(unedited.digests.size) || unedited.end != case.end, "the edit shows in the digests")
        // A recorded game's line is unchanged: no sandbox or open keys.
        val plain = CorpusCase(listOf(0, 0), 1, 2, emptyList(), emptyList(), "x")
        assertTrue(!corpusToJson(Corpus(g, listOf(plain))).contains("sandbox"), "only set flags are written")
    }

    check("an edit that names nothing changes nothing but the log") {
        val (_, _, st) = sandboxAt(true)
        val after = st.next(Answer.Edit(TableEdit.Conjure("no-such-card", st.full!!.player, EditZone.HAND)))
        assertEq(st.state.players, after.state.players)
        assertTrue(after.state.log.last().contains("nothing to do"), after.state.log.last())
    }
}

/** A game file of format [version] whose combat is the custom config [cfg],
 *  written the way formats up to 7 wrote it. */
private fun customAt(cfg: CombatConfig, version: Int): String =
    gameDocToJson(GameDoc()).replace("\"formatVersion\":$FORMAT_VERSION", "\"formatVersion\":$version")
        .replace("{\"kind\":\"preset\",\"name\":\"mtg\"}", "{\"kind\":\"custom\",\"base\":${combatConfigToJson(cfg)}}")

/** The block rules a combat's declare-blockers step holds. */
private fun Combat.blockRules(): List<BlockRule> {
    var found = emptyList<BlockRule>()
    program?.subst(object : Subst() {
        override fun rewrite(e: Effect): Effect = e.also { if (it is Effect.DeclareBlockers) found = it.rules }
    })
    return found
}

private const val E2_PINNED = "965204e65a650352"

private val BUNDLED: List<GameDoc> get() = Bundled.all

/** Every ordered deck pairing, two seeds: the games these checks play. */
private fun pairings(g: GameDoc): List<Triple<Int, Int, Int>> =
    g.decks.indices.flatMap { a -> g.decks.indices.flatMap { b -> listOf(20260925, 7).map { Triple(a, b, it) } } }

private const val CORPUS_DIR = "corpus"
private const val CONTENT_DIR = "content"
private fun corpusSlug(name: String) = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

private const val REPLAY_GOLDEN = "test/golden/replay.txt"

/** Every bundled game, every ordered deck pairing, two seeds, the heuristic
 *  pilot on both seats: one line per game, ending in a digest of how it ended. */
private fun replayCorpus(): List<String> = buildList {
    for (g in BUNDLED) {
        val rules = g.compile().playable()
        for ((a, b, seed) in pairings(g)) {
            val start = PlaySession(p0Deck = a, p1Deck = b, seed = seed).opening(g, rules)
            val pilots = SeatedPilots(mapOf("P0" to HeuristicPilot("P0", rules), "P1" to HeuristicPilot("P1", rules)), fallback = PassingPilot())
            val end = runCatching { runSync { Engine(pilots, rules = rules).playGame(start, maxTurns = 16) } }
            add("${g.name}|$a|$b|$seed|" + (end.getOrNull()?.let { digest(endState(it)) } ?: "threw ${end.exceptionOrNull()?.javaClass?.simpleName}"))
        }
    }
}

/** How a game ended, as text: everything but the log and the effect trees
 *  (which lowering is ALLOWED to reshape -- that is its job). */
// Frozen with the golden it wrote: the core's `canonical()` digests more
// of the state, and switching the golden to it would lose the pre-lowering record.
private fun endState(s: GameState): String = buildString {
    append("turn=${s.turnNumber} active=${s.activePlayer} losers=${s.losers.sorted()}\n")
    for (pid in s.turnOrder) {
        val p = s.players.getValue(pid)
        append("$pid counters=${p.counters.toSortedMap()} pool=${p.pool.toSortedMap()} ")
        append("hand=${p.hand.map { it.instanceId }} lib=${p.library.map { it.instanceId }} gy=${p.graveyard.map { it.instanceId }}\n")
    }
    append("exile=${s.exile.map { it.instanceId }}\n")
    for (perm in s.battlefield.values.sortedBy { it.id }) {
        append("#${perm.id} ${perm.cardKey} f${perm.face} ${perm.controller} ${perm.zone} c=${perm.counters.toSortedMap()} ")
        append("d=${perm.damageMarked} x=${perm.exhausted} m=${perm.combatMode} t=${perm.isToken} h=${perm.hostId}\n")
    }
}

private fun digest(text: String): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(16)

