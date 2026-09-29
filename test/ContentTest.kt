package ccg

import ccgui.HeuristicPilot
import ccgui.ObservedPilot
import ccgui.PassingPilot
import ccgui.PlaySession
import ccgui.SeatedPilots
import ccgui.opening
import ccgui.ALL_POLICIES
import ccgui.Policy
import ccgui.PolicyPilot
import ccgui.Proactive

// ---------------------------------------------------------------------------
// Tests over the BUNDLED CONTENT -- EPR Skirmish and the Core, the games
// actually played. The bundles are pure `ccg` data (no Compose), so test.sh
// compiles them into their own output (not the core, so the layer guards are
// unaffected) and a bug seen in play can be reproduced here in seconds.
//
// Belongs here: claims about the SHIPPED GAMES a device would otherwise
// discover. Not here: engine mechanics (CoreTest, on minimal fixtures).
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// CALIBRATION FIXTURES, top level so checks and scratch probes build the same
// decks. Added to a COPY of EPR Skirmish; the shipped doc stays frozen.
// ---------------------------------------------------------------------------

private val LANES = listOf("lane-1", "lane-2", "lane-3")

internal val CALIB_SLOTS = mapOf(
    "Station" to listOf("Relay Kestrel-9"),
    "Leader" to listOf("Ilsa Vray"),
    "Flagship" to listOf("Kestrel Vanguard", "Concord Picket", "Kestrel Paragon"),
)

/** EXPERIMENTAL multi-target answers (the shipped EPRS removal is all
 *  single-target), for the measured question "is Reactive weak because the
 *  pool is thin?". Never added to EPR Skirmish. */
internal val AOE_CARDS: List<CardDoc> = listOf(
    // A symmetric sweeper: cheap, and it hurts you too, so a body-heavy deck
    // cannot simply jam it as well.
    CardDoc(
        faces = listOf(FaceDoc(
            "Flak Screen", setOf("Manoeuvre"),
            castEffect = Effect.ForEach(
                PermFilter(types = setOf("Ship")),
                Effect.DealDamage(lit(1), BoundTarget(EACH)),
            ),
        )),
        cost = Cost(mana = mapOf("" to 2)),
    ),
    // The one-sided sweeper, priced above the symmetric one.
    CardDoc(
        faces = listOf(FaceDoc(
            "Cinder Wash", setOf("Manoeuvre"),
            castEffect = Effect.ForEach(
                PermFilter(types = setOf("Ship")).theirs(),
                Effect.DealDamage(lit(2), BoundTarget(EACH)),
            ),
        )),
        cost = Cost(mana = mapOf("" to 3)),
    ),
    // THE CARD THE NEW ZONE SCOPE EXISTS FOR, and the shape originally asked
    // for: "when this Ship enters, deal 2 to each Ship on the Lane". Written
    // with `inThisZone()` it would hit only this player's own half of the
    // lane, which is why `SameDefAsSource` had to exist first.
    CardDoc(
        faces = listOf(FaceDoc(
            "Bulwark Tender", setOf("Ship"),
            fields = mapOf("fast" to 1, "hull" to 2),
            triggers = listOf(TriggerDoc.SelfEnters(
                Effect.ForEach(
                    PermFilter(types = setOf("Ship")).inThisZoneEitherSide().otherThanThis(),
                    Effect.DealDamage(lit(2), BoundTarget(EACH)),
                ),
            )),
        )),
        cost = Cost(mana = mapOf("" to 2)),
    ),
    // Efficient single-target removal. The shipped rate is 2 damage for {2};
    // this is 3 for {1}, so "answers are underpriced" is separated from
    // "answers cannot hit more than one thing".
    CardDoc(
        faces = listOf(FaceDoc(
            "Pinpoint Lance", setOf("Manoeuvre"),
            castEffect = Effect.Choose(
                PermFilter(types = setOf("Ship")),
                Effect.DealDamage(lit(3), BoundTarget(CHOSEN)),
            ),
        )),
        cost = Cost(mana = mapOf("" to 1)),
    ),
)

/** EXPERIMENTAL STICKY BODIES -- cheap hulls that survive a hit, over-statted on
 *  purpose to test whether a board that FILLS changes anything. */
internal val STICKY_CARDS: List<CardDoc> = listOf(
    CardDoc(
        faces = listOf(FaceDoc("Hull Drone", setOf("Ship"), fields = mapOf("fast" to 1, "hull" to 4))),
        cost = Cost(mana = mapOf("" to 1)),
        entersWith = listOf(CounterDef("hull", lit(4))),
    ),
    CardDoc(
        faces = listOf(FaceDoc("Ablative Hulk", setOf("Ship"), fields = mapOf("fast" to 2, "slow" to 1, "hull" to 6))),
        cost = Cost(mana = mapOf("" to 2)),
        entersWith = listOf(CounterDef("hull", lit(6))),
    ),
)

/** MOBILE bodies -- stat-identical to `STICKY_CARDS` but able to reposition,
 *  generated per PRICE so price is the only variable. Three abilities because
 *  `MovePermanent` names a fixed destination. `oncePerTurn` is per ability
 *  index; left off, since the pilots only move on a strict improvement. */
private fun mobileCards(tag: String, price: Cost, oncePerTurn: Boolean = false): List<CardDoc> = listOf(
    "Drift Drone" to Triple(1, 4, mapOf("fast" to 1, "hull" to 4)),
    "Skirmish Tender" to Triple(2, 6, mapOf("fast" to 2, "slow" to 1, "hull" to 6)),
).map { (baseName, spec) ->
    val (mana, hull, fields) = spec
    CardDoc(
        faces = listOf(FaceDoc(
            "$baseName $tag", setOf("Ship"), fields = fields,
            activated = LANES.map { lane ->
                ActivatedAbility(
                    cost = price,
                    effect = Effect.MovePermanent(BoundTarget(SELF), ZoneRef(lane)),
                    name = "reposition to $lane",
                    oncePerTurn = oncePerTurn,
                )
            },
        )),
        cost = Cost(mana = mapOf("" to mana)),
        entersWith = listOf(CounterDef("hull", lit(hull))),
    )
}

/** The three prices under test. `[free]` is once per turn, NOT unlimited: a
 *  non-mana ability goes on the stack, so an unlimited free one grows the stack
 *  forever (each activation is real progress to the priority loop) and never
 *  resolves. Every repeatable ability needs a limiter -- a content rule. */
internal val MOBILE_CARDS: List<CardDoc> =
    mobileCards("[tap]", Cost(tapSource = true)) +
        mobileCards("[free]", Cost(), oncePerTurn = true) +
        mobileCards("[mana]", Cost(mana = mapOf("" to 1)))

private fun mobileDeck(tag: String) = DeckDoc(
    "CALIBRATION mobile $tag",
    entries = listOf(
        DeckEntry("Drift Drone $tag", 12),
        DeckEntry("Skirmish Tender $tag", 12),
        DeckEntry("Immolate", 4),
    ),
    slots = CALIB_SLOTS,
)

private val MOBILE_TAP_DECK = mobileDeck("[tap]")
private val MOBILE_FREE_DECK = mobileDeck("[free]")
private val MOBILE_MANA_DECK = mobileDeck("[mana]")

/** Lever 1: DENSITY -- many cheap bodies, almost no removal, from the shipped
 *  pool only. Tests whether the board fills when nothing answers it. */
private val DENSE_DECK = DeckDoc(
    "CALIBRATION dense bodies",
    entries = listOf(
        DeckEntry("Salvage Scout", 8),
        DeckEntry("Ashbearer", 8),
        DeckEntry("Acolyte Shell", 8),
        DeckEntry("Immolate", 4),
    ),
    slots = CALIB_SLOTS,
)

/** Lever 2: STICKINESS -- the same shape, but the bodies survive removal and
 *  combat. One variable against DENSE: hull, not count. */
private val STICKY_DECK = DeckDoc(
    "CALIBRATION sticky bodies",
    entries = listOf(
        DeckEntry("Hull Drone", 12),
        DeckEntry("Ablative Hulk", 12),
        DeckEntry("Immolate", 4),
    ),
    slots = CALIB_SLOTS,
)

private val BODIES_DECK = DeckDoc(
    "CALIBRATION bodies",
    entries = listOf(
        DeckEntry("Salvage Scout", 8),
        DeckEntry("Ashbearer", 8),
        DeckEntry("Acolyte Shell", 8),
        DeckEntry("Pyre Acolyte", 4),
    ),
    slots = CALIB_SLOTS,
)

private val ANSWERS_DECK = DeckDoc(
    "CALIBRATION answers",
    entries = listOf(
        DeckEntry("Immolate", 4),
        DeckEntry("Point Defence Volley", 4),
        DeckEntry("Rite of Unmaking", 4),
        DeckEntry("Strip the Hulk", 4),
        DeckEntry("Tithe of Shells", 4),
        DeckEntry("Evasive Burn", 4),
        DeckEntry("Requisition Audit", 4),
    ),
    slots = CALIB_SLOTS,
)

/** The SAME shell as `ANSWERS_DECK`, with half its single-target cards traded
 *  for the experimental suite. One variable: what an answer can reach. */
private val ANSWERS_AOE_DECK = DeckDoc(
    "CALIBRATION answers+AoE",
    entries = listOf(
        DeckEntry("Flak Screen", 4),
        DeckEntry("Cinder Wash", 4),
        DeckEntry("Bulwark Tender", 4),
        DeckEntry("Pinpoint Lance", 4),
        DeckEntry("Immolate", 4),
        DeckEntry("Point Defence Volley", 4),
        DeckEntry("Requisition Audit", 4),
    ),
    slots = CALIB_SLOTS,
)

/** A BALANCED deck, half bodies and half shipped single-target answers: an
 *  all-answers deck has no route to a Station, so the comparison below changes
 *  ONE thing (which answers) inside a deck that can win. */
private val MIXED_SHIPPED_DECK = DeckDoc(
    "CALIBRATION mixed (shipped answers)",
    entries = listOf(
        DeckEntry("Salvage Scout", 6),
        DeckEntry("Ashbearer", 4),
        DeckEntry("Acolyte Shell", 4),
        DeckEntry("Immolate", 4),
        DeckEntry("Point Defence Volley", 4),
        DeckEntry("Rite of Unmaking", 3),
        DeckEntry("Tithe of Shells", 3),
    ),
    slots = CALIB_SLOTS,
)

/** The same 14 bodies, and 14 answers with the experimental suite in place of
 *  the weakest single-target cards. */
private val MIXED_AOE_DECK = DeckDoc(
    "CALIBRATION mixed (AoE answers)",
    entries = listOf(
        DeckEntry("Salvage Scout", 6),
        DeckEntry("Ashbearer", 4),
        DeckEntry("Acolyte Shell", 4),
        DeckEntry("Flak Screen", 4),
        DeckEntry("Cinder Wash", 3),
        DeckEntry("Bulwark Tender", 3),
        DeckEntry("Pinpoint Lance", 4),
    ),
    slots = CALIB_SLOTS,
)

/** Separating REACH from RATE. `MIXED_AOE_DECK` changed both at once — the
 *  experimental suite hits more things AND is priced better (Pinpoint Lance is
 *  3 damage for {1}; the shipped rate is 2 for {2}). So "better answers win"
 *  would have been unattributable. These two change one each. */
private val MIXED_REACH_DECK = DeckDoc(
    "CALIBRATION mixed (reach only)",
    entries = listOf(
        DeckEntry("Salvage Scout", 6),
        DeckEntry("Ashbearer", 4),
        DeckEntry("Acolyte Shell", 4),
        DeckEntry("Flak Screen", 4),
        DeckEntry("Cinder Wash", 3),
        DeckEntry("Bulwark Tender", 3),
        DeckEntry("Immolate", 4),
    ),
    slots = CALIB_SLOTS,
)

private val MIXED_RATE_DECK = DeckDoc(
    "CALIBRATION mixed (rate only)",
    entries = listOf(
        DeckEntry("Salvage Scout", 6),
        DeckEntry("Ashbearer", 4),
        DeckEntry("Acolyte Shell", 4),
        DeckEntry("Pinpoint Lance", 4),
        DeckEntry("Immolate", 4),
        DeckEntry("Point Defence Volley", 3),
        DeckEntry("Tithe of Shells", 3),
    ),
    slots = CALIB_SLOTS,
)

/** EPR Skirmish plus the calibration decks: 3 = bodies, 4 = answers,
 *  5 = answers+AoE, 6 = mixed/shipped, 7 = mixed/AoE, 8 = mixed/reach-only,
 *  9 = mixed/rate-only, 10 = dense, 11 = sticky, 12/13/14 = mobile tap/free/mana. The shipped decks keep
 *  indices 0-2 and are untouched. */
internal fun calibrationDoc(): GameDoc {
    val doc = EPR_SKIRMISH
    return doc.copy(
        sets = doc.sets + SetDoc("Experimental answers", AOE_CARDS) +
            SetDoc("Experimental bodies", STICKY_CARDS + MOBILE_CARDS),
        decks = doc.decks + listOf(
            BODIES_DECK, ANSWERS_DECK, ANSWERS_AOE_DECK,
            MIXED_SHIPPED_DECK, MIXED_AOE_DECK, MIXED_REACH_DECK, MIXED_RATE_DECK,
            DENSE_DECK, STICKY_DECK, MOBILE_TAP_DECK, MOBILE_FREE_DECK, MOBILE_MANA_DECK,
        ),
    )
}

internal fun contentChecks() {

    check("engine a DECLINED action is a pass -- the priority loop cannot spin") {
        // An ability whose target cannot exist is declined BEFORE its cost is
        // paid, so its source stays legal and is offered again -- a log line
        // alone must not count as progress.
        val leader = CardDoc(faces = listOf(FaceDoc(
            "Ilsa-shaped Leader", setOf("Leader"),
            activated = listOf(ActivatedAbility(
                cost = Cost(tapSource = true),
                effect = Effect.Choose(
                    PermFilter(types = setOf("Ship")).yours(),
                    Effect.DealDamage(lit(1), BoundTarget(CHOSEN)),
                ),
                name = "exhaust: 1 damage to a Ship you control",
            )),
        ))).build()
        val rules = Rules(
            types = BUILTIN_TYPES_CORE + ("Ship" to TypeDef("Ship", fields = setOf("power", "toughness"))),
            turn = TurnStructure(listOf(PhaseSpec("main", sorcerySpeed = true))),
        ).knowing(leader)
        var s = newGame()
        val (s1, boss) = enterBattlefield(leader, "P0", s); s = s1

        // With no Ship in play the ability must not even be OFFERED -- legality
        // now asks the same `canBindChoices` the engine binds with.
        val act = PriorityAction.Activate(boss, 0)
        assertTrue(legality(rules, s, "P0", act) is Legality.Denied, "an untargetable ability is not legal")
        assertTrue(legalActionsFor(rules, s, "P0").isEmpty(), "so it is not enumerated either")

        // And belt AND braces: even a client that offers it anyway must not
        // hang the game. A pilot that insists on this action every time it is
        // asked terminates, because an action that changed nothing but the log
        // counts as a pass.
        val stubborn = object : PlayerInput {
            override suspend fun ask(q: Question): Answer = when (q) {
                is Question.Priority -> Answer.Act(askPriorityAction(q.player, q.state))
                is Question.PickTarget -> Answer.Target(chooseTarget(q.player, q.candidates, q.state))
                is Question.PickNumber -> Answer.Number(chooseNumber(q.player, q.label, q.min, q.max))
                is Question.CombatTgt -> Answer.CombatTgt(chooseCombatTarget(q.player, q.attacker, q.range, q.state))
                else -> q.default()
            }

            private suspend fun askPriorityAction(player: PlayerId, state: GameState): PriorityAction =
                if (player == "P0") act else PriorityAction.PassPriority
            private suspend fun chooseTarget(player: PlayerId, candidates: List<ObjectId>, state: GameState): ObjectId =
                candidates.firstOrNull() ?: 0
            private suspend fun chooseNumber(player: PlayerId, prompt: String, min: Int, max: Int): Int = min
            private suspend fun chooseCombatTarget(player: PlayerId, attacker: ObjectId, range: ccg.AttackRange?, state: GameState): CombatTarget? = null
        }
        val end = runSync { Engine(stubborn, rules = rules).playGame(s, maxTurns = 2) }
        assertTrue(end.turnNumber >= 1, "the game finished instead of hanging")

        // The RETRY matters: the HS taunt suite attacks the face (refused by the
        // engine, permitted by legality) and then the taunt. Treating the first
        // refusal as a pass would end that phase early. Only actions legality
        // permits and the engine then declines can reach this rule at all.

        // With a Ship out it is legal again -- the guard refuses the impossible,
        // not the merely unusual.
        val ship = CardDoc(faces = listOf(FaceDoc("Hulk", setOf("Ship"), fields = mapOf("power" to 1, "toughness" to 1)))).build()
        val (s2, _) = enterBattlefield(ship, "P0", s)
        assertTrue(legality(rules, s2, "P0", act) is Legality.Legal, "a target exists, so the ability is offered")
    }

    check("content EPR Skirmish builds, and every declared deck is playable") {
        val doc = EPR_SKIRMISH
        val rules = doc.rules()
        assertTrue(doc.decks.size >= 3, "three factions ship")
        for ((i, deck) in doc.decks.withIndex()) {
            // Every card a deck names must exist in the bundle. A rename that
            // dangles an entry is silent otherwise -- the deck just gets
            // smaller.
            for (e in deck.entries) {
                assertTrue(doc.cards.any { it.faces.first().name == e.cardName }, "deck $i names a real card: ${e.cardName}")
            }
            for ((slot, names) in deck.slots) {
                for (n in names) {
                    assertTrue(doc.cards.any { it.faces.first().name == n }, "deck $i slot $slot names a real card: $n")
                }
            }
        }
        assertTrue(rules.cards.isNotEmpty())
    }

    // -- the device report ---------------------------------------------------

    check("content EVERY faction pairing plays to a finish -- the priority loop must settle") {
        // Every ORDERED pairing runs to a finish without the priority loop
        // spinning (a pilot can choose the same do-nothing action forever; a
        // human seat cannot). Ordered, because seats differ in Station, economy
        // and initiative.
        val doc = EPR_SKIRMISH
        val rules = doc.rules()
        for (a in doc.decks.indices) {
            for (b in doc.decks.indices) {
                val session = PlaySession(p0Deck = a, p1Deck = b, seed = 20260906)
                val start = session.opening(doc, rules)
                // Both seats flown, and every action recorded, so a spin NAMES
                // itself.
                val trail = mutableListOf<String>()
                fun fly(seat: PlayerId) =
                    ObservedPilot(HeuristicPilot(seat, rules)) { _, _, text -> trail += text }
                val pilots = SeatedPilots(
                    mapOf("P0" to fly("P0"), "P1" to fly("P1")),
                    fallback = PassingPilot(),
                )
                val outcome = runCatching {
                    runSync { Engine(pilots, rules = rules).playGame(start, maxTurns = 12) }
                }
                // A spin does not throw: it ends the game as a draw, and
                // says so in the log. Either is this failure.
                val spun = outcome.getOrNull()?.log?.lastOrNull { "no end within" in it }
                val why = outcome.exceptionOrNull() ?: spun?.let { IllegalStateException(it) }
                val tail = trail.takeLast(6).joinToString(" | ")
                assertTrue(
                    why == null,
                    "deck $a (${doc.decks[a].name}) vs deck $b (${doc.decks[b].name}): ${why?.message}" +
                        "  [${trail.size} actions; last: $tail]",
                )
            }
        }
    }


    // -- THE CORE BUNDLE ---------------------------------------------------
    //
    // The live design; EPR Skirmish stays frozen beside it.

    check("core the Core builds, and every declared deck is playable") {
        val doc = CORE_BUNDLE
        val rules = doc.rules()
        assertTrue(rules.cards.isNotEmpty())
        assertEq(3, doc.decks.size, "three factions ship")
        for (deck in doc.decks) {
            assertTrue(deck.entries.sumOf { it.count } >= 24, "${deck.name} is a real deck")
            for (e in deck.entries) {
                assertTrue(doc.cards.any { it.faces.first().name == e.cardName },
                    "${deck.name} names a real card: ${e.cardName}")
            }
            for ((slot, names) in deck.slots) for (n in names) {
                assertTrue(doc.cards.any { it.faces.first().name == n },
                    "${deck.name} slot $slot names a real card: $n")
            }
        }
    }

    check("core its STRUCTURE is the measured one, not a default") {
        // Each of these was chosen because a measurement said so, and each
        // would be easy to lose silently in a later edit.
        val doc = CORE_BUNDLE
        val rules = doc.rules()
        assertTrue(rules.params.attackDelayOnEntry, "deployment is a commitment (+48% occupancy)")
        // The combat's decisions, read off its program.
        val step = freeStep(rules.combat)
        assertTrue(step.body.reaches != BoolExpr.Const(true), "position decides whom you fight")
        assertTrue(exhaustedSitsOut(step), "tapping costs a combat wave")
        assertTrue("reach" in rules.combat.keywordsNamed(), "and reach is the priced exemption")
        // THE GRID: three lanes of two berths, one ship each. Pinned to the
        // DECISION rather than to whatever the zones hold.
        val berths = rules.zones.values.filter { it.scope == ccg.ZoneScope.PER_PLAYER }
        assertEq(
            setOf("1F", "1B", "2F", "2B", "3F", "3B"),
            berths.map { it.id }.toSet(),
            "six berths, named so a player can say where a ship is",
        )
        assertTrue(berths.all { it.onGrid }, "every berth knows its lane AND its depth, or the geometry falls back to flat")
        assertEq(listOf(1, 2, 3), rules.laneNumbers(), "three lanes")
        assertTrue(berths.all { it.maxOccupants == 1 }, "one ship a berth")
        assertTrue(berths.all { it.combatSteps == null }, "a zone does not gate waves -- range decides the attack")
        assertEq(listOf(AttackRange.CLOSE, AttackRange.FAR), step.guns.map { it.label }, "sr down your own lane, lr across the field")
        assertEq(listOf(IntExpr.TargetField(BoundTarget(ATTACKER), "sr"), IntExpr.TargetField(BoundTarget(ATTACKER), "lr")),
            step.guns.map { it.amount }, "each gun strikes with its own stat")
        assertEq(1, (rules.combat.program as Effect.Sequence).steps.size, "and it is ONE wave")
        // Overflow ("trample") is OFF by decision. Asserted false rather than
        // deleted, so re-enabling it is visible.
        assertTrue(!step.overflow, "trample is off (rule D cut at v15)")
        // SIX berths a side, as a TOTAL: capacity is the measured decision (at
        // one berth a lane the board saturates and surplus mana buys only burn).
        // A test pinned to what the code does rather than what was decided once
        // froze a lost decision as intent.
        assertEq(
            6,
            rules.zones.values.filter { it.scope == ccg.ZoneScope.PER_PLAYER }.sumOf { it.maxOccupants ?: 0 },
            "six berths a side -- the measured capacity, however it is divided",
        )
        // The Flagship pool is BACK (it was cut while the cost curve was
        // inverted, when an always-available expensive card really was just a
        // late game). Three per deck, always available -- where a slow deck's
        // consistent tools live.
        assertEq(3, doc.deckRules.slots.first { it.name == "Flagship" }.count, "three Flagships per deck")
        assertTrue(rules.hiddenZones.containsKey("flagships"), "and the pool they live in")
    }

    check("core the lane area spell hits BOTH sides of the lane it names") {
        // The card that corrected my own claim that a lane area effect needs a
        // permanent source. `Enfilade` is a Manoeuvre: `ChooseMode` picks the
        // lane, and `inZone` matches either player's copy of it, consulting no
        // source at all.
        val doc = CORE_BUNDLE
        val rules = doc.rules()
        val enfilade = doc.cards.first { it.faces.first().name == "Enfilade" }.build()
        val face = enfilade.faces.first()
        // Only an action phase: combat would damage these Ships too and the
        // assertion would be reading two effects at once.
        val r = Rules(
            types = rules.types,
            zones = rules.zones,
            turn = TurnStructure(listOf(PhaseSpec("action", sorcerySpeed = true))),
        )
        // Hull 40 so the fixture SURVIVES any costing of a sweep (a dead Ship is
        // gone from the map and the property becomes unreadable).
        val hull = CardDoc(
            faces = listOf(FaceDoc("Mark", setOf("Ship"), fields = mapOf("sr" to 1, "hull" to 40))),
            entersWith = listOf(CounterDef("hull", lit(40))),
        ).build()
        var s = newGame()
        val (s1, mine) = enterBattlefield(hull, "P0", s, zone = ZoneRef("1F", "P0")); s = s1
        val (s2, theirs) = enterBattlefield(hull, "P1", s, zone = ZoneRef("1F", "P1")); s = s2
        // a LANE is two berths now, so the sweep must reach the back one
        // as well.
        val (s2b, theirBack) = enterBattlefield(hull, "P1", s, zone = ZoneRef("1B", "P1")); s = s2b
        val (s3, elsewhere) = enterBattlefield(hull, "P1", s, zone = ZoneRef("2F", "P1")); s = s3
        // Cast FREE on purpose: the claim under test is where the damage
        // lands, not what the card costs, and wiring a real pool through a
        // round boundary would put the economy in the middle of a targeting
        // test. `enfilade.cost` is exercised by the deck-playability check.
        val cast = PriorityAction.CastSpell(face.castEffect!!.lowered(LIFE), face.types, Cost())
        // Mode index 0 of lane 1 / 2 / 3 -- lane 1, meaning berths 1F AND 1B.
        val end = runSync {
            Engine(
                ScriptedInput.of("P0@action" to listOf(cast), modes = listOf(listOf(0))),
                rules = r,
            ).playGame(s, maxTurns = 1)
        }
        // A PROPERTY, not a damage number: symmetric within the named lane,
        // nothing outside it. A test that fails on every balance tweak gets
        // retuned, not read.
        val mineHull = end.battlefield.getValue(mine).counter("hull")
        val theirHull = end.battlefield.getValue(theirs).counter("hull")
        assertTrue(mineHull < 40, "the chosen lane was hit")
        assertEq(mineHull, theirHull, "it is NOT one-sided -- both sides of the lane took the same")
        assertTrue(
            end.battlefield.getValue(theirBack).counter("hull") < 40,
            "a LANE is both berths: the back one is swept too",
        )
        assertEq(40, end.battlefield.getValue(elsewhere).counter("hull"), "a Ship in another LANE is untouched")
    }

    check("core EVERY pairing plays to a finish under every policy") {
        val doc = CORE_BUNDLE
        val rules = doc.rules()
        for (a in doc.decks.indices) for (b in doc.decks.indices) {
            for (p in ALL_POLICIES) {
                val start = PlaySession(p0Deck = a, p1Deck = b, seed = 20260908).opening(doc, rules)
                val trail = mutableListOf<String>()
                val pilots = SeatedPilots(
                    mapOf(
                        "P0" to ObservedPilot(PolicyPilot("P0", rules, p)) { _, _, t -> trail += t },
                        "P1" to ObservedPilot(PolicyPilot("P1", rules, p)) { _, _, t -> trail += t },
                    ),
                    fallback = PassingPilot(),
                )
                val why = runCatching {
                    runSync { Engine(pilots, rules = rules).playGame(start, maxTurns = 20) }
                }.exceptionOrNull()
                assertTrue(
                    why == null,
                    "deck $a vs $b under ${p.name}: ${why?.message}" +
                        "  [${trail.size} actions; last: ${trail.takeLast(4).joinToString(" | ")}]",
                )
            }
        }
    }

    // -- THE CALIBRATION CONTROL -----------------------------------------------
    //
    // Two deliberately archetyped decks from EPRS's pool with the same Station,
    // Leader and Flagships: 28 cheap bodies against 28 answers. Its job is to be
    // able to FAIL -- if three policies cannot tell THESE apart, the pilots are
    // broken, not the game.


    check("content the experimental answers stay OUT of the shipped game -- the corpus is frozen") {
        // The experimental cards must never leak into the shipped doc, or every
        // prior measurement silently describes a different game.
        val shipped = EPR_SKIRMISH
        for (c in AOE_CARDS + STICKY_CARDS + MOBILE_CARDS) {
            val name = c.faces.first().name
            assertTrue(
                shipped.sets.none { s -> s.cards.any { it.faces.first().name == name } },
                "$name must not be in the shipped pool",
            )
        }
        assertEq(3, shipped.decks.size, "the shipped game still ships exactly three decks")
        assertEq(15, calibrationDoc().decks.size, "and the test bundle adds twelve more")
    }

    check("content the calibration decks are real -- every card they name exists in the pool") {
        val doc = calibrationDoc()
        for (deck in doc.decks.takeLast(12)) {
            assertTrue(deck.entries.sumOf { it.count } >= 24, "${deck.name} is a real deck, not a stub")
            for (e in deck.entries) {
                assertTrue(
                    doc.cards.any { it.faces.first().name == e.cardName },
                    "${deck.name} names a real card: ${e.cardName}",
                )
            }
            for ((slot, names) in deck.slots) for (n in names) {
                assertTrue(
                    doc.cards.any { it.faces.first().name == n },
                    "${deck.name} slot $slot names a real card: $n",
                )
            }
        }
    }

    check("content EVERY policy pilot plays to a finish -- new deciders must not spin the loop") {
        // The same hazard the HeuristicPilot pairing check exists for, re-run
        // for three new deciders: an action a policy keeps choosing that the
        // engine keeps declining is an infinite priority loop. Three policies
        // times the two calibration decks, both seatings.
        val doc = calibrationDoc()
        val rules = doc.rules()
        for (p in ALL_POLICIES) {
            for ((a, b) in listOf(3 to 4, 4 to 3, 3 to 5, 5 to 3)) {
                val start = PlaySession(p0Deck = a, p1Deck = b, seed = 20260908).opening(doc, rules)
                val trail = mutableListOf<String>()
                val pilots = SeatedPilots(
                    mapOf(
                        "P0" to ObservedPilot(PolicyPilot("P0", rules, p)) { _, _, t -> trail += t },
                        "P1" to ObservedPilot(PolicyPilot("P1", rules, p)) { _, _, t -> trail += t },
                    ),
                    fallback = PassingPilot(),
                )
                val why = runCatching {
                    runSync { Engine(pilots, rules = rules).playGame(start, maxTurns = 12) }
                }.exceptionOrNull()
                assertTrue(
                    why == null,
                    "${p.name}: deck $a vs $b: ${why?.message}" +
                        "  [${trail.size} actions; last: ${trail.takeLast(5).joinToString(" | ")}]",
                )
            }
        }
    }

    check("content every MOBILE deck plays to a finish -- a repeatable ability must be bounded") {
        // The defect this pins, found by measurement: a non-mana activated
        // ability goes on the stack, so an unlimited free one is activated
        // without bound -- 20,000 times by a Ship that never moved, because
        // the stack never resolved. Every repeatable ability needs a limiter.
        val doc = calibrationDoc()
        val rules = doc.rules()
        for (deck in listOf(12, 13, 14)) {
            for (p in ALL_POLICIES) {
                val start = PlaySession(p0Deck = deck, p1Deck = 11, seed = 20260906).opening(doc, rules)
                val pilots = SeatedPilots(
                    mapOf("P0" to PolicyPilot("P0", rules, p), "P1" to PolicyPilot("P1", rules, p)),
                    fallback = PassingPilot(),
                )
                val why = runCatching {
                    runSync { Engine(pilots, rules = rules).playGame(start, maxTurns = 20) }
                }.exceptionOrNull()
                assertTrue(why == null, "deck $deck under ${p.name}: ${why?.message}")
            }
        }
    }

    check("content the pilots DISCRIMINATE -- three policies must not play the same game") {
        // THE control. Same decks, same seed, same opponent policy; only P0's
        // policy varies. If all three produce an identical game, the policies
        // are decorative and every number the matrix reports is noise.
        val doc = calibrationDoc()
        val rules = doc.rules()
        fun signature(p: Policy): String {
            val start = PlaySession(p0Deck = 3, p1Deck = 4, seed = 20260908).opening(doc, rules)
            val pilots = SeatedPilots(
                mapOf("P0" to PolicyPilot("P0", rules, p), "P1" to PolicyPilot("P1", rules, Proactive)),
                fallback = PassingPilot(),
            )
            val end = runSync { Engine(pilots, rules = rules).playGame(start, maxTurns = 12) }
            val mine = end.battlefield.values.count { it.controller == "P0" }
            return "turns=${end.turnNumber} losers=${end.losers.sorted()} board=$mine log=${end.log.size}"
        }
        val sigs = ALL_POLICIES.associate { it.name to signature(it) }
        assertTrue(
            sigs.values.toSet().size > 1,
            "all three policies produced the SAME game -- the instrument does not discriminate: $sigs",
        )
    }
}
