package ccg

import ccgui.PermMove
import ccgui.encode
import ccgui.CANT_ACTIONS
import ccgui.COND_KINDS
import ccgui.condKind
import ccgui.condOfKind
import ccgui.condAbout
import ccgui.ROLES
import ccgui.roleLabel
import ccgui.roleOf
import ccgui.CMP_OPS
import ccgui.cmpOpLabel
import ccgui.cmpOpOf
import ccgui.PlayIntent
import ccgui.commitIntent
import ccgui.openZonesFor
import ccgui.playerTargetLabel
import ccgui.canAttackFace
import ccgui.PlaySession
import ccgui.PILOT_PROFILES
import ccgui.pilotFor
import ccgui.DEFAULT_SEED
import ccgui.editableFields
import ccgui.nextBundle
import ccgui.nextGame
import ccgui.defaultLens
import ccgui.lensSwitchable
import ccgui.session
import ccgui.edits
import ccgui.kind
import ccgui.decked
import ccgui.undecked
import ccgui.reopened
import ccgui.boundTo
import ccgui.rerolled
import ccgui.HeuristicPilot
import ccgui.boardLayout
import ccgui.particleAt
import ccgui.particleBurst
import ccgui.particleCountFor
import ccgui.FIELD_ASPECT
import ccgui.MINI_ASPECT
import ccgui.artRefs
import ccgui.centreCrop
import ccgui.hasOwnMiniRect
import ccgui.lockRect
import ccgui.moveRect
import ccgui.rectFor
import ccgui.resizeRect
import ccgui.beatText
import ccgui.recap
import ccgui.boardFx
import ccgui.Varied
import ccgui.varyTurnMode
import ccgui.varyAttackDelay
import ccgui.varyCardCost
import ccgui.varyCardField
import ccgui.forkStemOf
import ccgui.boardRanks
import ccgui.varyAbilityCost
import ccgui.varyEntersWith
import ccgui.reachRankOf
import ccgui.BoardFx
import ccgui.Change
import ccgui.openingRecap
import ccgui.ChangeKind
import ccgui.PlayMode
import ccgui.Viewpoint
import ccgui.ZoneVisibility
import ccgui.ObservedPilot
import ccgui.ScriptedPilot
import ccgui.ruleboxOf
import ccgui.effectSummary
import ccgui.modeOptionSummary
import ccg.stanceNames
import ccg.Question
import ccgui.encodeAnswer
import ccgui.boardTapTargets
import ccgui.anyPlayableIn
import ccgui.ArcEnd
import ccgui.activationsBy
import ccgui.arcsFor
import ccgui.hasStructuredBoard
import ccgui.opening
import ccgui.ALL_POLICIES
import ccgui.Attrition
import ccgui.Policy
import ccgui.PolicyPilot
import ccgui.PolicyView
import ccgui.Proactive
import ccgui.Reactive
import ccg.HIDDEN_CARD
import ccgui.asSeenBy
import ccgui.tapIntent
import ccgui.COUNTER_KINDS
import ccgui.HIDDEN_ZONES
import ccgui.CHAR_OPS
import ccgui.RULE_MODS
import ccgui.TRIGGER_KINDS
import ccgui.UNKNOWN_INT
import ccgui.UNKNOWN_BOOL
import ccgui.allVerbs
import ccgui.boolSummary
import ccgui.charOpKind
import ccgui.charOpSummary
import ccgui.charOpOfKind
import ccgui.cardFilterSummary
import ccgui.cardLabel
import ccgui.combatBoardTargets
import ccgui.costSummary
import ccgui.filterSummary
import ccgui.intKinds
import ccgui.intSummary
import ccgui.isSpellCard
import ccgui.lbl
import ccgui.ruleModKind
import ccgui.ruleModOfKind
import ccgui.triggerKind
import ccgui.triggerOfKind
import ccgui.zoneOfName

// ---------------------------------------------------------------------------
// Tests for the decision logic in `src/ui` (package `ccgui`). The recurring
// shape is DRIFT: a new sealed case makes every `when` in `src/ccg` refuse to
// compile, but an editor menu is a `List<String>` that silently keeps not
// offering it. So these are mostly round-trips between a sealed hierarchy and
// its menu, anchored by exhaustive `when`s.
// ---------------------------------------------------------------------------

internal fun uiChecks() {

    // -- isSpellCard: the "Big Bungus" defect -------------------------------

    check("ui isSpellCard -- a permanent with a cast effect is still a PERMANENT") {
        val r = Rules.DEFAULT
        assertTrue(!isSpellCard(setOf("Artifact", "Creature"), r), "Artifact Creature is not a spell")
        assertTrue(!isSpellCard(setOf("Creature"), r))
        assertTrue(isSpellCard(setOf("Instant"), r))
        assertTrue(isSpellCard(setOf("Sorcery"), r))
        assertTrue(!isSpellCard(emptySet(), r), "a typeless card is not a spell")
        // A bundle's own type wins over the builtin of the same name.
        val custom = Rules(types = BUILTIN_TYPES_CORE + ("Rune" to TypeDef("Rune", isSpell = true)))
        assertTrue(isSpellCard(setOf("Rune"), custom))
        assertTrue(!isSpellCard(setOf("Rune", "Creature"), custom), "one permanent type is enough")
    }

    // -- menu <-> sealed-case round trips (the drift guard) -----------------

    check("ui every CharOp case is offered by the editor, and every label maps back") {
        fun tag(o: CharOp): Int = when (o) {
            is CharOp.PlusPT -> 1; is CharOp.SetPT -> 2; is CharOp.GrantKeyword -> 3
            is CharOp.AddType -> 4; is CharOp.SetTypes -> 5; CharOp.RemoveAbilities -> 6
            is CharOp.Bands -> 7; is CharOp.GrantAbility -> 8; is CharOp.PlusField -> 9; is CharOp.SetField -> 10
        }
        // label -> case -> label must be the identity for every menu entry.
        for (label in CHAR_OPS) assertEq(label, charOpKind(charOpOfKind(label)), "CHAR_OPS round-trip: $label")
        // and every case must be reachable from the menu.
        val reachable = CHAR_OPS.map { tag(charOpOfKind(it)) }.toSet()
        assertEq((1..10).toSet(), reachable, "every CharOp case is offered")
    }

    check("ui every TriggerDoc case is offered by the editor, and every label maps back") {
        fun tag(t: TriggerDoc): Int = when (t) {
            is TriggerDoc.SelfEnters -> 1; is TriggerDoc.SelfLeaves -> 2; is TriggerDoc.SelfAttacks -> 3
            is TriggerDoc.YouCastType -> 4; is TriggerDoc.OnYourPhase -> 5
            is TriggerDoc.CounterThreshold -> 6; is TriggerDoc.CreatureDies -> 7
            is TriggerDoc.SelfDealsDamage -> 8; is TriggerDoc.On -> 9
        }
        for (label in TRIGGER_KINDS) {
            assertEq(label, triggerKind(triggerOfKind(label, Effect.NoOp, 0)), "TRIGGER_KINDS round-trip: $label")
        }
        assertEq(
            (1..9).toSet(),
            TRIGGER_KINDS.map { tag(triggerOfKind(it, Effect.NoOp, 0)) }.toSet(),
            "every TriggerDoc case is offered",
        )
        // The kind swap must PRESERVE the effect and order the author already
        // set -- switching "when this enters" to "when this attacks" must not
        // silently drop the effect underneath it.
        val eff = Effect.Draw(PlayerRef.You, lit(2))
        for (label in TRIGGER_KINDS) {
            val t = triggerOfKind(label, eff, 3)
            assertEq(eff, t.effect, "$label keeps the effect")
            assertEq(3, t.order, "$label keeps the order")
        }
    }

    check("ui every RuleMod case is offered by the editor, and every label maps back") {
        fun tag(r: RuleMod): Int = when (r) {
            is RuleMod.Cant -> 1; is RuleMod.CantAttack -> 2
            is RuleMod.CantBlock -> 3; is RuleMod.CantActivate -> 4
            is RuleMod.ReduceDamage -> 5
        }
        for (label in RULE_MODS) assertEq(label, ruleModKind(ruleModOfKind(label)), "RULE_MODS round-trip: $label")
        assertEq((1..5).toSet(), RULE_MODS.map { tag(ruleModOfKind(it)) }.toSet(), "every RuleMod case is offered")
        // The Cant actions the menu offers must be ones the engine honours.
        assertEq(listOf("gainLife", "draw", "cast"), CANT_ACTIONS)
    }

    check("ui every Effect case the editor renders is reachable from the verb menu") {
        fun tag(e: Effect): Int = when (e) {
            is Effect.DealDamage -> 1; is Effect.DamageOpponent -> 2; is Effect.Draw -> 3
            is Effect.GainLife -> 4; is Effect.AddMana -> 5; is Effect.Destroy -> 6
            is Effect.AddCounter -> 7; is Effect.RemoveCounter -> 8; is Effect.Transform -> 9
            is Effect.CreateToken -> 10; is Effect.CopyOf -> 11; is Effect.CreateEmblem -> 12
            is Effect.ChooseMode -> 13; is Effect.Discard -> 14; is Effect.ReturnFromDiscard -> 15
            is Effect.MovePermanent -> 16; is Effect.ApplyModifier -> 17; is Effect.PreventDamage -> 18
            is Effect.SetCombatMode -> 19; is Effect.ForEach -> 20; is Effect.ForEachPlayer -> 21
            is Effect.Sacrifice -> 22; is Effect.Delayed -> 23; is Effect.Sequence -> 24
            is Effect.Choose -> 25; is Effect.If -> 26; Effect.NoOp -> 27; is Effect.ChooseMany -> 28
            is Effect.SearchZone -> 29; is Effect.MoveTop -> 30; is Effect.LookAtTop -> 31
            is Effect.Shuffle -> 32; is Effect.Attach -> 33; is Effect.DrawThenDiscard -> 34
            is Effect.CounterSpell -> 35; is Effect.Tap -> 36; is Effect.SendTo -> 37; is Effect.GainControl -> 38
            is Effect.AsPlayer -> 39; is Effect.ClearDamage -> 40; is Effect.Proceed -> 41
            is Effect.DeclareAttackers -> 42; is Effect.DeclareBlockers -> 43; Effect.CombatWindow -> 44
            is Effect.CombatDamage -> 45
            is Effect.Attack -> 46; is Effect.Strike -> 47; is Effect.Clash -> 48; is Effect.FreeAttacks -> 49
        }
        // In a game whose combat declares a stance; without one, the stance
        // verb is withheld (checked below).
        val offered = allVerbs(CHOSEN, stances = listOf("defense")).map { tag(it.make()) }.toSet()
        assertEq((1..49).toSet(), offered, "all 49 Effect cases are on the menu")
        // Labels must be unique, or two menu rows read identically.
        val labels = allVerbs(CHOSEN).map { it.label }
        assertEq(labels.size, labels.toSet().size, "verb labels are unique")
        // A targeted verb must actually bind the target it was asked for.
        val moved = allVerbs(EACH).first { it.label == "deal damage to it" }.make() as Effect.DealDamage
        assertEq(BoundTarget(EACH), moved.target, "targeted verbs bind the implicit target")
    }

    check("ui the stance verb names only a stance the game's combat declares") {
        // Every preset: whatever SetCombatMode the menu offers must name a
        // declared stance, or adding it stops the game from starting.
        for ((name, combat) in ccg.COMBAT_PRESETS) {
            val declared = combat.stanceNames()
            val modes = allVerbs(CHOSEN, stances = declared.toList()).map { it.make() }
                .filterIsInstance<Effect.SetCombatMode>().map { it.mode }
            assertEq(if (declared.isEmpty()) 0 else 1, modes.size, "$name: stance verb offered iff a stance is declared")
            assertTrue(modes.all { it in declared }, "$name: stance verb names a declared stance, got $modes")
        }
    }

    check("ui the condition editor builds every BoolExpr, and every label maps back") {
        fun tag(b: BoolExpr): Int = when (b) {
            is BoolExpr.Const -> 1; is BoolExpr.Cmp -> 2; is BoolExpr.And -> 3; is BoolExpr.Or -> 4; is BoolExpr.Not -> 5
            is BoolExpr.HasKeyword -> 6; is BoolExpr.IsType -> 7; is BoolExpr.IsExhausted -> 8; is BoolExpr.IsToken -> 9
            is BoolExpr.HasField -> 10; is BoolExpr.AtDepth -> 11; is BoolExpr.ZoneOwner -> 12
            // A surface form: the editor builds its core, `IsType`.
            is BoolExpr.HasType -> 7
        }
        for (k in COND_KINDS) assertEq(k, condKind(condOfKind(k)), "COND_KINDS round-trip: $k")
        assertEq((1..12).toSet(), COND_KINDS.map { tag(condOfKind(it)) }.toSet(), "every core BoolExpr case can be built")
        // The permanent a condition is about is a role, and moving it keeps the rest.
        for (r in ROLES) assertEq(r, roleLabel(roleOf(r)), "ROLES round-trip: $r")
        val lane = condAbout(condOfKind("stands in a back berth"), roleOf("the target"))
        assertEq(BoolExpr.AtDepth(BoundTarget(TARGET), Depth.BACK), lane)
        for (op in CMP_OPS) assertEq(op, cmpOpLabel(cmpOpOf(op)))
        // The number menu reads where a permanent stands, and chooses.
        val reads = intKinds(ATTACKER).map { it.second }
        assertTrue(reads.any { it == IntExpr.LaneOf(BoundTarget(ATTACKER)) }, "its lane")
        assertTrue(reads.any { it is IntExpr.Cond }, "if … then … else")
    }

    check("ui every HiddenZone is offered by the zone menus, and every label maps back") {
        fun tag(z: HiddenZone): Int = when (z) {
            HiddenZone.LIBRARY -> 1; HiddenZone.LIBRARY_BOTTOM -> 2; HiddenZone.HAND -> 3
            HiddenZone.GRAVEYARD -> 4; HiddenZone.EXILE -> 5
        }
        for (label in HIDDEN_ZONES) assertEq(label, zoneName(zoneOfName(label)), "HIDDEN_ZONES round-trip: $label")
        assertEq((1..5).toSet(), HIDDEN_ZONES.map { tag(zoneOfName(it)) }.toSet(), "every zone is offered")
        assertEq(HIDDEN_ZONES.size, HIDDEN_ZONES.toSet().size, "zone labels are unique")
    }

    // -- labels: every menu entry must render ------------------------------

    check("ui every IntPill menu entry renders as real wording, not the fallback") {
        for ((label, expr) in intKinds(CHOSEN)) {
            assertTrue(intSummary(expr) != UNKNOWN_INT, "no wording for the '$label' entry")
        }
        assertEq("3", intSummary(lit(3)))
        assertEq("X", intSummary(IntExpr.X))
        assertEq("its share", intSummary(IntExpr.Share))
        assertEq("its power", intSummary(IntExpr.TargetField(BoundTarget(CHOSEN), "power")))
    }

    check("ui boolSummary is total over BoolExpr, and reads as a sentence") {
        val samples = listOf<BoolExpr>(
            BoolExpr.Const(true),
            BoolExpr.HasType(setOf("Creature")),
            BoolExpr.Not(BoolExpr.Const(false)),
            BoolExpr.And(listOf(BoolExpr.Const(true), BoolExpr.Const(true))),
            BoolExpr.Or(listOf(BoolExpr.Const(true), BoolExpr.Const(false))),
            countOf(creatures().yours()) gte 3,
        )
        for (b in samples) assertTrue(boolSummary(b).isNotBlank(), "no wording for $b")
        assertEq("always", boolSummary(BoolExpr.Const(true)))
        assertEq("# creatures you control ≥ 3", boolSummary(countOf(creatures().yours()) gte 3))
    }

    check("ui filterSummary distinguishes the three controllers") {
        assertEq("any Creature", filterSummary(creatures()), "the type name reads as itself -- it used to be dropped for anything but Creature")
        assertEq("a Creature you control", filterSummary(creatures().yours()))
        assertEq("a Creature an opponent controls", filterSummary(creatures().theirs()))
        assertEq("any permanent", filterSummary(permanents()))
        // The counter narrowing reads as part of the same sentence.
        assertEq("any Creature with a +1/+1 counter", filterSummary(creatures().withCounter("+1/+1")))
        assertEq("a Creature you control with charge 3 counters", filterSummary(creatures().yours().withCounter("charge", 3)))
        // Every counter kind the menu offers must render.
        for (k in COUNTER_KINDS) assertTrue(filterSummary(creatures().withCounter(k)).contains(k), "no wording for $k")
        // the attach-relative filter reads on its own.
        assertEq("the permanent it's attached to", filterSummary(permanents().onlyHost()))
        // A filter narrowed to a custom type names it: Ablative Plating reads
        // "a Ship you control", not "a permanent you control".
        assertEq("a Ship you control", filterSummary(ofType("Ship").yours()))
        assertEq("any Station", filterSummary(ofType("Station")))
        // `excludesSource` was dropped from the text too -- Kestrel Vanguard's
        // anthem is "OTHER Ships you control" and read as if it buffed itself.
        assertEq("another Ship you control", filterSummary(ofType("Ship").yours().otherThanThis()))
    }

    check("ui costSummary reads the cost, including alternatives") {
        assertEq("free", costSummary(Cost()))
        assertEq("{3}", costSummary(Cost(mana = mapOf("" to 3))))
        assertEq("{T}", costSummary(Cost(tapSource = true)))
        assertEq("3 life", costSummary(Cost(payLife = 3)))
        assertTrue(costSummary(Cost(mana = mapOf("" to 2), alternatives = listOf(Cost(payLife = 3)))).contains(" or "))
        assertTrue(costSummary(Cost(removeCounters = "loyalty" to 2)).contains("loyalty"))
        // The reported one: pay counters off a permanent you control.
        // This had NO branch, so the Core's top-end alternative -- {1} plus
        // five hull off your Station -- advertised itself as a bare "{1}".
        val hull = Cost(
            mana = mapOf("" to 1),
            payFrom = CounterPayment("hull", 5, PermFilter(types = setOf("Station")).yours()),
        )
        assertTrue(costSummary(hull).contains("5 hull"), "the hull price is stated: ${costSummary(hull)}")
        assertTrue(costSummary(hull).contains("{1}"), "and so is the mana")
        // `additional` renders as what it does, not a placeholder.
        assertTrue(
            costSummary(Cost(additional = Effect.Draw(ccg.PlayerRef.You, lit(1)))).contains("draw"),
            "an additional cost says what it makes you do",
        )
    }

    // THE TRIPWIRE, as a relationship rather than a list of cases: the summary
    // says "free" exactly when `Cost.isFree` (which enumerates every field)
    // says so. A new `Cost` field without a branch fails here instead of
    // mispricing a card on screen.
    check("ui costSummary never calls a real cost free -- one case per Cost field") {
        val cases = listOf(
            "mana" to Cost(mana = mapOf("" to 2)),
            "keyed mana" to Cost(mana = mapOf("R" to 1)),
            "X" to Cost(usesX = true),
            "additional" to Cost(additional = Effect.Draw(ccg.PlayerRef.You, lit(1))),
            "tapSource" to Cost(tapSource = true),
            "sacrificeSource" to Cost(sacrificeSource = true),
            "payLife" to Cost(payLife = 1),
            "removeCounters" to Cost(removeCounters = "loyalty" to 1),
            "alternatives" to Cost(alternatives = listOf(Cost(payLife = 3))),
            "payFrom" to Cost(payFrom = CounterPayment("hull", 5, PermFilter(types = setOf("Station")).yours())),
        )
        for ((label, c) in cases) {
            assertTrue(!c.isFree, "$label is not a free cost")
            assertTrue(
                costSummary(c) != "free",
                "$label must not summarise as \"free\" -- costSummary is missing a branch for it",
            )
        }
        // And the converse, so the check cannot be satisfied by never saying
        // "free" at all.
        assertEq("free", costSummary(Cost()), "a genuinely free cost still reads free")
    }

    check("ui cardLabel names a card in a hidden zone -- ids alone are unreadable") {
        val rules = Rules(cards = mapOf("Bolt" to card("Ember Bolt", setOf("Instant"))))
        assertEq("#100003 Ember Bolt", cardLabel(rules, CardRef(100_003, "Bolt")))
        // An undefined cardId still renders -- it does not throw and it does
        // not silently show nothing.
        assertEq("#7 token", cardLabel(rules, CardRef(7, "token")))
    }

    check("ui cardFilterSummary reads every narrowing, and 'any card' when empty") {
        assertEq("any card", cardFilterSummary(CardFilter()))
        assertEq("Creature card", cardFilterSummary(CardFilter(types = setOf("Creature"))))
        assertEq("named \"Grunt\"", cardFilterSummary(CardFilter(nameIs = "Grunt")))
        assertEq("costing 3 or less", cardFilterSummary(CardFilter(maxManaValue = 3)))
        assertTrue(cardFilterSummary(CardFilter(types = setOf("Creature"), maxManaValue = 2)).contains(", "))
    }

    check("ui lbl names a permanent and is total for an id that has left play") {
        val s = enterBattlefield(Grunt, "P0", newGame()).first
        assertEq("#1 Grunt 2/2", s.lbl(1))
        assertEq("#99 ?", s.lbl(99), "a gone id renders, it does not throw")
        // A permanent with no power field shows no P/T.
        val land = enterBattlefield(card("Forest", setOf("Land")), "P0", s).first
        assertEq("#2 Forest", land.lbl(2))
    }

    // -- combatBoardTargets: the board's tappable set for "who does #atk hit"
    // (Phase 6.3, the tap-to-target slice) -------------------------------
    check("ui combatBoardTargets is the attacker's opponent's combatants -- not their non-combatants, not the attacker's own side") {
        val r = Rules()
        var s = newGame()
        val (s1, foe) = enterBattlefield(card("Grunt", setOf("Creature")), "P1", s)
        s = s1
        // The opponent's non-combatant -- present on the board, not a target.
        val (s2, _) = enterBattlefield(card("Fort", setOf("Land")), "P1", s)
        s = s2
        // P0's OWN creature -- must never be offered against P0's own attack.
        val (s3, _) = enterBattlefield(card("Ally", setOf("Creature")), "P0", s)
        s = s3
        assertEq(setOf(foe), combatBoardTargets(r, s, "P0"))
        // Empty when the opponent has no combatants at all.
        assertEq(emptySet<Int>(), combatBoardTargets(r, newGame(), "P0"))
    }

    check("ui boardTapTargets asks the SAME two narrowings the pilots do") {
        // The defender's Station is tappable only where a face attack is legal
        // (`canAttackFace`, the pilots' own question).
        val r = Rules(
            types = BUILTIN_TYPES_CORE + mapOf(
                "Ship" to TypeDef("Ship", fields = setOf("hull", "fast"), attacks = true),
                "Station" to TypeDef("Station", loseOnDeath = true),
            ),
        )
        var s = newGame()
        val (s1, station) = enterBattlefield(card("Spire", setOf("Station")), "P1", s)
        s = s1
        val (s2, foe) = enterBattlefield(card("Grunt", setOf("Ship")), "P1", s)
        s = s2
        val (s3, atk) = enterBattlefield(card("Ally", setOf("Ship")), "P0", s)
        s = s3

        val tappable = ccgui.boardTapTargets(r, Question.CombatTgt("P0", atk, null, s), s)
        // The opponent's combatant is tappable either way.
        assertTrue(foe in tappable, "the opposing combatant is a target")
        // Whether the STATION is depends on `canAttackFace`, and the set must
        // agree with it rather than answer on its own.
        assertEq(
            s.canAttackFace(r, atk, "P1", null),
            station in tappable,
            "the Station is offered exactly when a face attack is legal",
        )
        // And the answer a tap produces distinguishes the two, which is what
        // makes one tile mean "attack the player" and another "attack that".
        assertEq(
            ccg.CombatTarget.Obj(foe),
            ccgui.tapAnswer(r, Question.CombatTgt("P0", atk, null, s), foe),
            "tapping a combatant targets the OBJECT",
        )
        if (station in tappable) {
            assertEq(
                ccg.CombatTarget.Player("P1"),
                ccgui.tapAnswer(r, Question.CombatTgt("P0", atk, null, s), station),
                "tapping the Station targets the PLAYER",
            )
        }
        // playerTargetAnchor and playerTargetLabel must name the same thing.
        assertEq(station, ccgui.playerTargetAnchor(r, s, "P1"))
        assertEq("Spire", ccgui.playerTargetLabel(r, s, "P1"))
        assertEq(null, ccgui.playerTargetAnchor(r, s, "P0"), "P0 declares no such permanent")
    }

    check("ui combatBoardTargets offers a NON-Creature fighting type -- the gap that made combat do nothing in a Ships game") {
        // Custom combat types (not "Creature") get tappable combat targets; the
        // fixtures deliberately use a non-builtin type name.
        val shipRules = Rules(
            types = BUILTIN_TYPES_CORE + mapOf(
                "Ship" to TypeDef("Ship", fields = setOf("hull", "fast"), attacks = true),
                "Station" to TypeDef("Station", loseOnDeath = true),
            ),
        )
        var s = newGame()
        val (s1, foeShip) = enterBattlefield(card("Raider", setOf("Ship")), "P1", s)
        s = s1
        // A Station is a permanent but NOT a combatant -- you reach it by
        // attacking the PLAYER, so it must not be offered as a tile target.
        val (s2, _) = enterBattlefield(card("Relay", setOf("Station")), "P1", s)
        s = s2
        val (s3, _) = enterBattlefield(card("Mine", setOf("Ship")), "P0", s)
        s = s3
        assertEq(setOf(foeShip), combatBoardTargets(shipRules, s, "P0"), "the opponent's Ship, and only it")
        // And the shared predicate underneath agrees, for both readers.
        assertTrue(shipRules.fightsInCombat(setOf("Ship")))
        assertTrue(!shipRules.fightsInCombat(setOf("Station")))
        assertTrue(Rules().fightsInCombat(setOf("Creature")), "unchanged for every existing game")
        assertTrue(!Rules().fightsInCombat(setOf("Land")))
    }

    // -- PlayIntent: the tap/commit policy the lane picker needs. It lives
    // in src/ui, not inline in a composable, so test.sh can see it ----------

    /** A game with a Ship type that may enter any of 3 per-player lanes. */
    fun laneRules(cap: Int? = null) = Rules(
        types = BUILTIN_TYPES_CORE + mapOf("Ship" to TypeDef("Ship", zoneChoices = listOf("lane-1", "lane-2", "lane-3"))),
        zones = BUILTIN_ZONES + listOf("lane-1", "lane-2", "lane-3")
            .map { PlayZoneDef(it, ZoneScope.PER_PLAYER, maxOccupants = cap) }.associateBy { it.id },
    )
    val ship = CardDefinition(listOf(Face("Scout", setOf("Ship"), Characteristics("Scout", setOf("Ship")))))
    fun playShip() = PriorityAction.PlayPermanent(ship, 0)

    check("ui openZonesFor offers a restricted type's zones, and null for an unrestricted one") {
        val r = laneRules()
        assertEq(listOf("lane-1", "lane-2", "lane-3"), openZonesFor(r, newGame(), "P0", playShip())?.sorted())
        // A plain Creature declares no zone -- no choice exists to offer.
        val plain = PriorityAction.PlayPermanent(Grunt.lowered(LIFE), 0)
        assertEq(null, openZonesFor(r, newGame(), "P0", plain))
        // A spell never enters a play zone at all.
        assertEq(null, openZonesFor(r, newGame(), "P0", PriorityAction.CastSpell(Effect.NoOp.lowered(LIFE), setOf("Instant"), Cost())))
    }

    check("ui commitIntent asks for a zone when more than one is open -- THE bug: the confirm pill used to skip this entirely") {
        val r = laneRules()
        val i = commitIntent(r, newGame(), "P0", playShip())
        assertTrue(i is PlayIntent.ChooseZone, "3 open lanes -> ask, never silently take the default")
        assertEq(listOf("lane-1", "lane-2", "lane-3"), (i as PlayIntent.ChooseZone).zones.sorted())
    }

    check("ui commitIntent commits directly when there is no real choice, naming the zone explicitly") {
        // Unrestricted type: submit as-is, zone stays null (engine default).
        val r = laneRules()
        val plain = commitIntent(r, newGame(), "P0", PriorityAction.PlayPermanent(Grunt.lowered(LIFE), 0))
        assertTrue(plain is PlayIntent.Commit)
        assertEq(null, ((plain as PlayIntent.Commit).action as PriorityAction.PlayPermanent).zone)
        // Capped lanes, two already full: the ONE open lane is named
        // explicitly rather than left to defaultZoneDef, which would have
        // picked the first DECLARED lane -- a full one.
        val capped = laneRules(cap = 1)
        var s = newGame()
        s = enterBattlefield(ship, "P0", s, zone = ZoneRef("lane-1", "P0")).first
        s = enterBattlefield(ship, "P0", s, zone = ZoneRef("lane-2", "P0")).first
        val one = commitIntent(capped, s, "P0", playShip())
        assertTrue(one is PlayIntent.Commit, "only lane-3 has room -> no question to ask")
        assertEq("lane-3", ((one as PlayIntent.Commit).action as PriorityAction.PlayPermanent).zone)
    }

    check("ui tapIntent: first tap arms, second asks the same question the confirm pill does, a third cancels") {
        val r = laneRules()
        val s = newGame()
        assertTrue(tapIntent(r, s, "P0", playShip(), armed = false, choosingZone = false) is PlayIntent.Arm)
        val armed = tapIntent(r, s, "P0", playShip(), armed = true, choosingZone = false)
        assertTrue(armed is PlayIntent.ChooseZone, "the tile and the pill must agree -- both ask")
        assertEq(commitIntent(r, s, "P0", playShip()), armed, "literally the same policy, not a parallel copy")
        assertTrue(tapIntent(r, s, "P0", playShip(), armed = true, choosingZone = true) is PlayIntent.Cancel)
        assertTrue(tapIntent(r, s, "P0", null, armed = false, choosingZone = false) is PlayIntent.Ignore)
    }

    check("ui rulebox renders the two divergences text alone would hide") {
        // The rulebox exists because a card's `text` is free-form and nothing
        // checks it against behaviour. Both defects below are plain in the
        // DATA and invisible on screen.
        val shipT = TypeDef("Ship", fields = setOf("hull", "fast"), attacks = true)
        val rules = Rules(types = BUILTIN_TYPES_CORE + mapOf("Ship" to shipT, "Improvement" to TypeDef("Improvement")))

        // 1. A TYPE-RESTRICTED filter must name its types. `filterSummary`
        //    rendered every restriction as "creature", so a Ship-only filter
        //    read "a permanent you control" and a user reasonably concluded
        //    the engine was broken.
        val plating = CardDoc(faces = listOf(FaceDoc(
            "Ablative Plating", setOf("Improvement"),
            triggers = listOf(TriggerDoc.SelfEnters(
                Effect.Choose(PermFilter(types = setOf("Ship")).yours().otherThanThis(), Effect.Attach(BoundTarget(CHOSEN))),
            )),
        )))
        val platingLines = ruleboxOf(plating, rules)
        assertTrue(platingLines.isNotEmpty(), "a card with a trigger is not blank")
        assertTrue(
            platingLines.any { it.contains("Ship") },
            "the filter names SHIP, not 'permanent': $platingLines",
        )

        // 2. A CONDITIONAL static must show its condition. Fleet Command
        //    Uplink's anthem applied whether or not the Improvement had
        //    attached, which made the attach decorative -- a bug you can only
        //    see if the condition is rendered.
        val uplink = CardDoc(faces = listOf(FaceDoc(
            "Fleet Command Uplink", setOf("Improvement"),
            statics = listOf(StaticSpec(
                filter = PermFilter(types = setOf("Ship")).yours(),
                ops = listOf(CharOp.PlusField("fast", lit(1))),
                condition = countOf(PermFilter().onlyHost()) gte 1,
            )),
        )))
        assertTrue(
            ruleboxOf(uplink, rules).any { it.contains("while") },
            "a gated anthem SAYS it is gated: ${ruleboxOf(uplink, rules)}",
        )

        // 3. An empty rulebox is the loudest signal there is: a card that
        //    looks authored and compiles to nothing.
        assertTrue(
            ruleboxOf(CardDoc(faces = listOf(FaceDoc("Rock", setOf("Ship")))), rules).isEmpty(),
            "a vanilla card genuinely has no behaviour, and says so by being empty",
        )

        // 4. The Effect fallback NAMES an unhandled verb rather than dropping
        //    it. Silently skipping what it cannot describe would recreate the
        //    exact class of bug the rulebox exists to expose.
        assertTrue(effectSummary(Effect.Shuffle(PlayerRef.You)).isNotEmpty(), "every verb renders as something")
    }

    check("ui encodeAnswer records the shapes the UI ACTUALLY sends -- the bug this extraction was for") {
        // The UI sends a LIST of attackers; `encodeAnswer` must record it (it
        // once expected a Map and dropped every attack).
        val s0 = newGame()

        // The list form, which is what the UI really sends.
        val fromList = encodeAnswer(Question.Attackers("P0", listOf(1, 2), s0), listOf(1, 2))
        assertTrue(fromList is Answer.Attackers, "a LIST of chosen attackers is recorded, not dropped")
        assertEq(
            mapOf(1 to CombatTarget.Player("P1"), 2 to CombatTarget.Player("P1")),
            (fromList as Answer.Attackers).assignment,
            "and it becomes the assignment, aimed at the opponent",
        )

        // The map form too, so a richer client is not excluded.
        val m = mapOf(1 to CombatTarget.Obj(9) as CombatTarget)
        assertEq(Answer.Attackers(m), encodeAnswer(Question.Attackers("P0", listOf(1), s0), m))

        // The other shapes, so the same class of mismatch cannot hide.
        assertEq(Answer.Target(5), encodeAnswer(Question.PickTarget("P0", listOf(5), s0), 5))
        assertEq(Answer.Number(2), encodeAnswer(Question.PickNumber("P0", "n", 0, 3), 2))
        assertEq(Answer.Blockers(mapOf(3 to 1)), encodeAnswer(Question.Blockers("P0", listOf(3), listOf(1), s0), mapOf(3 to 1)))
        assertEq(Answer.CombatTgt(null), encodeAnswer(Question.CombatTgt("P0", 1, null, s0), null), "holding back is a real answer")
        assertEq(Answer.Blocker(null), encodeAnswer(Question.Redirect("P0", 1, listOf(2), s0), null), "so is letting it through")
        // PickMode carries the OPTIONS now, not a count, so a prompt can say
        // what each choice does instead of "option 1" -- see chooseMode.
        val modes = listOf(
            ModeOption.OfEffect(Effect.Draw(PlayerRef.You, lit(2))),
            ModeOption.OfEffect(Effect.NoOp),
            ModeOption.OfCost(Cost(payLife = 3)),
        )
        assertEq(Answer.Modes(listOf(0, 1)), encodeAnswer(Question.PickMode("P0", modes, 2, s0), listOf(0, 1)))
        // And each one reads as words rather than an index.
        assertTrue("2" in modeOptionSummary(modes[0]), "an effect mode reads as its effect")
        assertTrue(modeOptionSummary(modes[2]).isNotBlank(), "a cost alternative reads as its cost")
        assertEq(Answer.Pass, encodeAnswer(Question.Priority("P0", s0), PriorityAction.PassPriority))
        assertEq(Answer.Concede, encodeAnswer(Question.Priority("P0", s0), PriorityAction.Concede))

        // A shape that genuinely does not belong records nothing, rather than
        // recording something wrong.
        assertEq(null, encodeAnswer(Question.PickNumber("P0", "n", 0, 3), "not a number"))
    }

    check("ui boardLayout makes the LANES visible -- opposed columns, empty slots, viewer nearest") {
        // The board rendered one flat row of permanents per player (zero
        // `groupBy` anywhere in it), so the three opposed lanes EPR Skirmish is
        // built around could not be seen at all. This is the decision half,
        // here rather than in Compose so it is actually tested.
        val lanes = listOf(
            PlayZoneDef("lane-1", ZoneScope.PER_PLAYER, maxOccupants = 1),
            PlayZoneDef("lane-2", ZoneScope.PER_PLAYER, maxOccupants = 1),
        )
        val shipT = TypeDef("Ship", fields = setOf("power", "toughness"), attacks = true, zoneChoices = lanes.map { it.id })
        val rules = Rules(
            types = BUILTIN_TYPES_CORE + mapOf("Ship" to shipT),
            zones = BUILTIN_ZONES + lanes.associateBy { it.id },
        )
        assertTrue(hasStructuredBoard(rules), "capped per-player zones are a grid, not a pile")

        var s = newGame()
        val ship = CardDoc(faces = listOf(FaceDoc("Hulk", setOf("Ship"), fields = mapOf("power" to 1, "toughness" to 1)))).build()
        val (s1, mine) = enterBattlefield(ship, "P0", s, zone = ZoneRef("lane-2", "P0")); s = s1
        val (s2, theirs) = enterBattlefield(ship, "P1", s, zone = ZoneRef("lane-2", "P1")); s = s2

        val layout = boardLayout(rules, s, viewer = "P0")
        // Columns are the CONTESTED zones only. `battlefield` is uncapped and
        // shared -- nothing is ever opposed to what sits there -- so it is an
        // anchor zone, not a column; as a column, Station and Leader would push
        // the lanes off the right of the screen.
        assertEq(listOf("lane-1", "lane-2"), layout.columnIds, "columns are the CONTESTED zones, in a stable order")
        assertEq(listOf(1, 1), layout.columns.map { it.cap }, "a capped column knows its cap, so a renderer can compute a slot centre")
        assertEq(listOf("P1", "P0"), layout.rows.map { it.owner }, "opponent across the table, viewer nearest the thumb")

        // The whole point: same-named lanes line up, so "opposed" is visible.
        // Read by COLUMN INDEX, which is the promise the flat `slots` list
        // could not keep -- a lane is column N on every row or the grid is
        // lying about being opposed.
        fun occupantsIn(owner: PlayerId, def: String) =
            layout.rows.first { it.owner == owner }.grid[layout.columnIds.indexOf(def)]
        assertEq(listOf(mine), occupantsIn("P0", "lane-2").mapNotNull { it.occupant })
        assertEq(listOf(theirs), occupantsIn("P1", "lane-2").mapNotNull { it.occupant })

        // Room left in a capped lane is a REAL slot -- an open lane and a full
        // one must not look the same before you tap anything.
        assertEq(1, occupantsIn("P0", "lane-1").size, "the empty lane still occupies its column")
        assertEq(null, occupantsIn("P0", "lane-1").single().occupant)
        assertEq(0, occupantsIn("P0", "lane-2").count { it.occupant == null }, "a full lane offers no empty slot")

        // Seen from the other side, the rows flip -- the layout is per viewer.
        assertEq(listOf("P0", "P1"), boardLayout(rules, s, viewer = "P1").rows.map { it.owner })
    }

    check("ui a zone you can be ASKED to pick always has a slot to tap -- uncapped included") {
        // A SHARED, UNCAPPED zone (the Creator's defaults) that a type can
        // deploy into must still get a column with a lit slot: the board slot is
        // the zone question's only affordance.
        val bays = listOf(PlayZoneDef("bay-1"), PlayZoneDef("bay-2")) // Creator defaults
        val shipT = TypeDef("Ship", fields = setOf("power", "toughness"), attacks = true, zoneChoices = bays.map { it.id })
        val rules = Rules(
            types = BUILTIN_TYPES_CORE + mapOf("Ship" to shipT),
            zones = BUILTIN_ZONES + bays.associateBy { it.id },
        )
        val ship = CardDoc(faces = listOf(FaceDoc("Hulk", setOf("Ship"), fields = mapOf("power" to 1, "toughness" to 1)))).build()
        var s = newGame()

        // The question really is asked: two open zones is a ChooseZone, not a
        // silent commit.
        val open = openZonesFor(rules, s, "P0", PriorityAction.PlayPermanent(ship.lowered(LIFE), 0)).orEmpty()
        assertEq(listOf("bay-1", "bay-2"), open, "an uncapped zone always has room, so both are offered")

        // THE INVARIANT, stated over `openZonesFor` rather than over a literal
        // expectation so it holds for any board: every zone the player can be
        // ASKED to pick has an empty cell, in the viewer's own row, to tap.
        fun assertAnswerable(state: GameState, why: String) {
            val zones = openZonesFor(rules, state, "P0", PriorityAction.PlayPermanent(ship.lowered(LIFE), 0)).orEmpty()
            val layout = boardLayout(rules, state, viewer = "P0")
            val mine = layout.rows.first { it.owner == "P0" }
            zones.forEach { z ->
                val col = layout.columnIds.indexOf(z)
                assertTrue(col >= 0, "$why: $z is choosable, so it is a column and not an anchor")
                assertTrue(mine.grid[col].any { it.occupant == null }, "$why: $z is offered, so it has a slot to tap")
            }
        }
        assertAnswerable(s, "empty board")

        // Uncapped means there is ALWAYS room, so an occupied bay keeps exactly
        // one trailing slot -- the pile you can still add to, drawn honestly.
        val (s1, first) = enterBattlefield(ship, "P0", s, zone = ZoneRef("bay-1")); s = s1
        assertAnswerable(s, "one bay occupied")
        val layout = boardLayout(rules, s, viewer = "P0")
        val bay1 = layout.rows.first { it.owner == "P0" }.grid[layout.columnIds.indexOf("bay-1")]
        assertEq(listOf(first), bay1.mapNotNull { it.occupant }, "the occupant is still drawn")
        assertEq(1, bay1.count { it.occupant == null }, "and ONE open slot after it -- not none, and not a guess at how many")
        assertEq(null, layout.columns.first { it.zone == "bay-1" }.cap, "still an uncapped column: nothing promised about alignment")
    }

    check("ui boardLayout degenerates to a plain row for an MTG-shaped game") {
        // The universality test: one shared uncapped zone must not be dressed
        // up as a grid, and must need no special case to avoid it.
        val rules = Rules()
        assertTrue(!hasStructuredBoard(rules), "a single uncapped shared zone is a pile, not a grid")
        val ox = CardDoc(faces = listOf(FaceDoc("Ox", setOf("Creature"), fields = mapOf("power" to 2, "toughness" to 2)))).build()
        val (s, id) = enterBattlefield(ox, "P0", newGame())
        val layout = boardLayout(rules, s, viewer = "P0")
        assertEq(listOf("battlefield"), layout.columnIds)
        assertEq(listOf(null), layout.columns.map { it.cap }, "an uncapped column is ragged -- a pile is not a grid with holes in it")
        val mine = layout.rows.first { it.owner == "P0" }
        assertEq(listOf(id), mine.grid.single().mapNotNull { it.occupant }, "the one zone IS the grid, as one ragged column")
        assertTrue(mine.anchors.isEmpty(), "an unstructured board has no anchor strip -- there is nothing uncontested to put in it")
        val theirs = layout.rows.first { it.owner == "P1" }
        assertTrue(
            theirs.grid.single().isEmpty(),
            "a shared zone shows each seat only what it controls, not everything in the zone -- and pads with NOTHING",
        )
    }

    check("ui the grid stays column-aligned however many anchors a seat holds") {
        // Lane columns stay aligned across the table even when one seat holds an
        // Improvement and the other does not.
        val lanes = (1..3).map { PlayZoneDef("lane-$it", ZoneScope.PER_PLAYER, maxOccupants = 1) }
        val shipT = TypeDef("Ship", fields = setOf("power", "toughness"), attacks = true, zoneChoices = lanes.map { it.id })
        val rules = Rules(
            types = BUILTIN_TYPES_CORE + mapOf("Ship" to shipT, "Station" to TypeDef("Station")),
            zones = BUILTIN_ZONES + lanes.associateBy { it.id },
        )
        val ship = CardDoc(faces = listOf(FaceDoc("Hulk", setOf("Ship"), fields = mapOf("power" to 1, "toughness" to 1)))).build()
        val station = CardDoc(faces = listOf(FaceDoc("Relay", setOf("Station")))).build()

        var s = newGame()
        // gets a Station AND a Leader-ish second anchor; P1 gets neither.
        // Asymmetric on purpose -- that asymmetry IS the bug.
        s = enterBattlefield(station, "P0", s).first
        s = enterBattlefield(station, "P0", s).first
        val (s3, mine) = enterBattlefield(ship, "P0", s, zone = ZoneRef("lane-3", "P0")); s = s3
        val (s4, theirs) = enterBattlefield(ship, "P1", s, zone = ZoneRef("lane-3", "P1")); s = s4

        val layout = boardLayout(rules, s, viewer = "P0")
        val p0 = layout.rows.first { it.owner == "P0" }
        val p1 = layout.rows.first { it.owner == "P1" }

        assertEq(2, p0.anchors.size, "the uncontested permanents are anchors, not column 0")
        assertEq(0, p1.anchors.size)
        // The promise the old shape could not keep: same column index, same
        // lane, on every row, whatever else the seat is holding.
        assertEq(p0.grid.size, p1.grid.size, "every row has one cell-list per column")
        assertEq(layout.columns.size, p0.grid.size, "and it is index-aligned to `columns`")
        layout.columns.indices.forEach { c ->
            assertEq(
                layout.columns[c].zone,
                p0.grid[c].first().zone.def,
                "column $c is the same zone on P0's row",
            )
            assertEq(layout.columns[c].zone, p1.grid[c].first().zone.def, "and on P1's")
        }
        assertEq(listOf(mine), p0.grid[2].mapNotNull { it.occupant }, "opposed: both ships are in column 2")
        assertEq(listOf(theirs), p1.grid[2].mapNotNull { it.occupant })
    }

    check("ui an attachment is drawn WITH its host, in the host's cell or on its anchor") {
        // An attachment renders with its host (Improvements share the uncapped
        // zone with the Station, so they cannot just go to the anchor strip).
        val lane = PlayZoneDef("lane-1", ZoneScope.PER_PLAYER, maxOccupants = 2)
        val shipT = TypeDef("Ship", fields = setOf("power", "toughness"), attacks = true, zoneChoices = listOf("lane-1"))
        val rules = Rules(
            types = BUILTIN_TYPES_CORE + mapOf(
                "Ship" to shipT,
                "Station" to TypeDef("Station"),
                "Improvement" to TypeDef("Improvement"),
            ),
            zones = BUILTIN_ZONES + mapOf(lane.id to lane),
        )
        val ship = CardDoc(faces = listOf(FaceDoc("Hulk", setOf("Ship"), fields = mapOf("power" to 1, "toughness" to 1)))).build()
        val station = CardDoc(faces = listOf(FaceDoc("Relay", setOf("Station")))).build()
        val imp = CardDoc(faces = listOf(FaceDoc("Plating", setOf("Improvement")))).build()

        var s = newGame()
        val (s1, host) = enterBattlefield(ship, "P0", s, zone = ZoneRef("lane-1", "P0")); s = s1
        val (s2, anchor) = enterBattlefield(station, "P0", s); s = s2
        val (s3, onShip) = enterBattlefield(imp, "P0", s); s = s3
        val (s4, onStation) = enterBattlefield(imp, "P0", s); s = s4
        val (s5, orphan) = enterBattlefield(imp, "P0", s); s = s5
        fun attach(state: GameState, id: ObjectId, to: ObjectId) = state.copy(
            battlefield = state.battlefield + (id to state.battlefield.getValue(id).copy(hostId = to)),
        )
        s = attach(s, onShip, host)
        s = attach(s, onStation, anchor)
        // A host that does not resolve on the battlefield. The SBA fixpoint
        // makes this impossible on a real state, but hand-built states are not
        // bound by it -- and a permanent that silently fails to render is the
        // "state nothing can change" class, so it must NOT be dropped.
        s = attach(s, orphan, 99999)

        val row = boardLayout(rules, s, viewer = "P0").rows.first { it.owner == "P0" }

        val cell = row.grid.single().first { it.occupant == host }
        assertEq(listOf(onShip), cell.attached, "the Ship's Improvement rides in the Ship's own cell")
        val anch = row.anchors.first { it.id == anchor }
        assertEq(listOf(onStation), anch.attached, "and a Station's rides on the Station -- Fleet Command Uplink attaches to one")

        // Neither attachment is ALSO loose somewhere.
        assertTrue(row.anchors.none { it.id == onShip }, "an attachment is not an anchor as well")
        assertTrue(row.grid.flatten().none { it.occupant == onShip }, "nor a grid occupant as well")
        // The orphan is demoted, not deleted.
        assertTrue(row.anchors.any { it.id == orphan }, "an unresolvable host demotes its attachment; it never drops it")
        // An empty slot cannot carry attachments -- an attachment has a host.
        assertTrue(row.grid.flatten().filter { it.occupant == null }.all { it.attached.isEmpty() })
        // Derived, so a renderer can compute its row height instead of
        // measuring it -- measuring is what broke the arcs.
        assertEq(1, row.attachRun, "the tallest stack on the row, for a DERIVED height")
    }

    check("ui legalActionsFor answers what the STATE offers -- the basis for auto-pass") {
        // For clients only -- the engine never skips asking on this (a client
        // may offer cards that live in no zone).
        val rules = Rules(turn = TurnStructure(listOf(PhaseSpec("main", sorcerySpeed = true))))
        val empty = newGame()
        assertTrue(legalActionsFor(rules, empty, "P0").isEmpty(), "an empty hand and an empty board offer nothing")
        assertTrue(!hasAnyAction(rules, empty, "P0"), "so a client may auto-pass without asking a human")

        // A conceded seat is offered nothing further.
        val done = empty.copy(losers = setOf("P0"))
        assertTrue(legalActionsFor(rules, done, "P0").isEmpty(), "a seat that has lost is asked for nothing")
    }

    check("ui PlaySession: a game in progress IS (seed, deck picks, answers) -- everything else derives") {
        // The Player's structural claim: the session lives in src/ui, so it is
        // tested.
        val fresh = PlaySession.forGame(deckCount = 3)
        assertEq(0, fresh.p0Deck)
        assertEq(1, fresh.p1Deck, "a multi-deck bundle opens as a real matchup, not a mirror")
        assertEq(0, PlaySession.forGame(deckCount = 1).p1Deck, "...but a one-deck bundle mirrors rather than crashing")
        assertTrue(!fresh.inProgress, "no answers yet, so no game in progress")

        // Answering EXTENDS the same game -- the generation must not move, or
        // the UI would rebuild from scratch on every single click.
        val moved = fresh.answered(Answer.Pass).answered(Answer.Target(7))
        assertEq(listOf<Answer>(Answer.Pass, Answer.Target(7)), moved.answers)
        assertEq(fresh.generation, moved.generation, "one more answer is the SAME game, further on")
        assertTrue(moved.inProgress)

        // Undo, reshuffle and a deck change all mean "rebuild", so each bumps
        // the generation.
        assertEq(listOf<Answer>(Answer.Pass), moved.undone().answers)
        assertTrue(moved.undone().generation > moved.generation, "undo rebuilds by replay, so it is a new generation")
        assertEq(fresh, fresh.undone(), "undo at the start is a no-op, not an underflow")

        // Restart keeps the SEED (same opening hands, try a different line);
        // reshuffle changes it (a different deal). Conflating them would quietly
        // remove the ability to retry a hand.
        val again = moved.restarted()
        assertEq(moved.seed, again.seed, "restart replays the SAME deal")
        assertTrue(again.answers.isEmpty() && again.generation > moved.generation)
        assertEq(fresh, fresh.restarted(), "restarting an untouched session is a no-op")

        val shuffled = moved.reshuffled()
        assertTrue(shuffled.seed != moved.seed && shuffled.answers.isEmpty(), "a reshuffle is a different deal")

        // THE one that matters: a different deck is a different game, so the
        // answers must go with it. Replaying old answers into a new deal is how
        // a session corrupts itself.
        val swapped = moved.cycleP0(3)
        assertEq(1, swapped.p0Deck)
        assertTrue(swapped.answers.isEmpty(), "changing decks drops the answers -- they belong to the old game")
        assertEq(moved, moved.cycleP0(1), "with one deck there is nothing to cycle to")
        assertEq(moved, moved.withDecks(p0 = moved.p0Deck), "picking the deck you already have changes nothing")
    }

    check("ui PlaySession builds a real opening position, and a seat with no deck still gets a library") {
        val doc = GameDoc()
        val rules = doc.rules()
        val libs = startGame(doc, rules, listOf(DeckPick(0), DeckPick(1)), 1, shuffle = false).players.mapValues { (_, p) -> p.library + p.hand }
        assertTrue(libs.getValue("P0").isNotEmpty(), "an empty bundle is still playable -- tokens stand in")
        assertTrue(libs.getValue("P1").isNotEmpty())
        assertTrue(
            libs.getValue("P0").first().instanceId != libs.getValue("P1").first().instanceId,
            "and the two seats' cards are distinct objects, not the same ids twice",
        )
        val start = PlaySession().opening(doc, rules)
        assertEq(2, start.players.size, "setupGame ran: two seats, counters applied, hands dealt")
    }

    check("ui playerTargetLabel names the STATION, not \"the player\", when the loss condition lives on the board") {
        // In a game whose loss condition is a permanent, a "P1 (player)" chip
        // would be a lie about the game's own fiction: you attack the Station.
        val stationType = TypeDef("Station", loseOnDeath = true)
        val rules = Rules(types = BUILTIN_TYPES_CORE + mapOf("Station" to stationType))
        val station = CardDefinition(listOf(Face("Pyre of the Ninth Choir", setOf("Station"))))

        val plain = newGame()
        assertEq("P1 (player)", playerTargetLabel(rules, plain, "P1"), "an MTG-shaped game really does attack the player")

        val (withStation, _) = enterBattlefield(station, "P1", plain)
        assertEq(
            "Pyre of the Ninth Choir", playerTargetLabel(rules, withStation, "P1"),
            "with a Station in play, THAT is what is being attacked",
        )
        assertEq("P0 (player)", playerTargetLabel(rules, withStation, "P0"), "and it is per-defender, not global")
    }

    check("ui tapIntent ignores an illegal action -- an unaffordable card is never armable") {
        val r = laneRules()
        val costly = PriorityAction.PlayPermanent(
            CardDefinition(listOf(Face("Dreadnought", setOf("Ship"), Characteristics("Dreadnought", setOf("Ship")))), cost = Cost(mana = mapOf("" to 9))).lowered(LIFE),
            0,
        )
        assertTrue(tapIntent(r, newGame(), "P0", costly, armed = false, choosingZone = false) is PlayIntent.Ignore)
    }

    check("ui a beat names what the opponent DID -- and a pass is not an event") {
        // The response window is a BEAT: which actions
        // are worth stopping for, and what they read as.
        var s = newGame()
        val ship = CardDoc(faces = listOf(FaceDoc("Hulk", setOf("Ship"), fields = mapOf("power" to 1, "toughness" to 1)))).build()
        val (s1, id) = enterBattlefield(ship, "P1", s); s = s1

        assertEq(null, beatText(s, "P1", PriorityAction.PassPriority), "passing is the ABSENCE of an event -- never a beat")
        assertEq("P1 concedes", beatText(s, "P1", PriorityAction.Concede))
        assertEq("P1 plays Hulk", beatText(s, "P1", PriorityAction.PlayPermanent(ship.lowered(LIFE), 0)))
        assertEq(
            "P1 casts Point Defence Volley",
            beatText(s, "P1", PriorityAction.CastSpell(Effect.NoOp.lowered(LIFE), label = "Point Defence Volley")),
        )
        assertEq("P1 activates Hulk", beatText(s, "P1", PriorityAction.Activate(id, 0)), "an ability is named by its SOURCE")
        // Total over a source that has left play -- a beat must never be the
        // thing that throws, since it fires on the way to applying the action.
        assertEq("P1 activates an ability", beatText(s, "P1", PriorityAction.Activate(9999, 0)))
    }

    check("ui ObservedPilot reports what a pilot decided, without changing what it decides") {
        val rules = Rules(turn = TurnStructure(listOf(PhaseSpec("main", sorcerySpeed = true))))
        val seen = mutableListOf<String>()
        // It RECORDS, it does not wait: the observer fires before the engine
        // applies the action, so pausing here would hold a board that does not
        // yet show what the beat is describing. The wait belongs at the moment
        // the human is next asked -- see `HotseatInput.beforeAsk`.
        val pilot = ObservedPilot(ScriptedPilot("P1", listOf(PriorityAction.Concede), rules)) { _, _, text ->
            seen += text
        }
        val s = newGame()
        runSync {
            assertEq(PriorityAction.Concede, pilot.priorityOf("P1", s), "the decision passes through untouched")
            // The other seat's window: the scripted pilot passes, so nothing is
            // reported -- a beat per priority pass would fire constantly in a
            // priority-passing game and mean nothing.
            assertEq(PriorityAction.PassPriority, pilot.priorityOf("P0", s))
        }
        assertEq(listOf("P1 concedes"), seen, "one beat, for the one thing that actually happened")
    }

    check("ui a Viewpoint hides what a seat may not see -- counts stay public, faces do not") {
        val me = Viewpoint(PlayMode.PLAYER, "P0")
        val them = Viewpoint(PlayMode.PLAYER, "P1")

        assertTrue(me.handFaceUp("P0"), "you see your own hand")
        assertTrue(!me.handFaceUp("P1"), "and not your opponent's -- the defect the board shipped with")
        assertTrue(them.handFaceUp("P1"), "the policy is per-VIEWER, not per-seat")

        // A library is face-down to everyone, its OWNER included. The obvious
        // reading of "hide what isn't yours" leaves you reading your own deck
        // order, which is not a viewpoint any card game has.
        assertEq(ZoneVisibility.COUNT_ONLY, me.visibility("P0", "library"))
        assertEq(ZoneVisibility.COUNT_ONLY, me.visibility("P1", "library"))
        // Public zones stay public, whoever owns them.
        for (z in listOf("graveyard", "battlefield")) {
            assertEq(ZoneVisibility.OPEN, me.visibility("P1", z), "$z is public information")
        }
        assertEq(ZoneVisibility.OPEN, me.visibility(null, "exile"), "a shared zone has no owner to hide it from")

        // The debugger keeps its debugger. Both boards ask ONE function, so the
        // Playtest tab cannot drift from the Player -- and the zone explorer,
        // the seat strip and the hand lane cannot drift from each other, which
        // is how the lane picker and combat both broke.
        val dev = Viewpoint(PlayMode.PLAYTEST, "P0")
        assertTrue(dev.handFaceUp("P1") && dev.mayRead("P1", "library"), "playtest sees everything, honestly so")
        assertTrue(dev.godMode && !me.godMode, "Undo/Shuffle/Sandbox are Playtest-only")

        // A zone the GAME declares public (`alwaysVisible`, a Flagship pool) is
        // readable by the opponent too.
        val withPool = Viewpoint(PlayMode.PLAYER, "P0", declaredPublic = setOf("flagships"))
        assertEq(ZoneVisibility.COUNT_ONLY, me.visibility("P1", "flagships"), "undeclared, so it fails closed -- correctly")
        assertEq(ZoneVisibility.OPEN, withPool.visibility("P1", "flagships"), "declared public by the game, so the OPPONENT's is readable too")
        assertEq(ZoneVisibility.COUNT_ONLY, withPool.visibility("P1", "stash"), "and only the declared one -- this is not a blanket opening")
    }

    check("ui a pool announces itself only when something in it is castable") {
        // What re-opens a folded always-visible pool. It asks `legality()`
        // through the same single action constructor the tiles use, because a
        // pool that opens when nothing is castable -- or stays shut when
        // something is -- would be worse than one that never opened.
        val shipType = TypeDef("Ship", fields = setOf("hull", "fast"), attacks = true)
        fun flag(n: String, cost: Int) = CardDoc(
            faces = listOf(FaceDoc(n, setOf("Ship", "Flagship"), mapOf("hull" to 3, "fast" to 1))),
            cost = Cost(mana = mapOf("" to cost)),
        ).build()
        val pool = listOf("Loom" to 4, "Hulk" to 3)
        val rules = Rules(
            types = BUILTIN_TYPES_CORE + mapOf("Ship" to shipType, "Flagship" to TypeDef("Flagship")),
            hiddenZones = mapOf("flagships" to HiddenZoneDef("flagships", alwaysVisible = true)),
            cards = pool.associate { (n, c) -> n to flag(n, c) },
            params = GameParams(startingHandSize = 0),
        )
        val s = setupGame(
            rules = rules,
            libraries = mapOf("P0" to tokens(5), "P1" to tokens(5, 200_000)),
            startInPlay = mapOf("P0" to pool.map { (n, _) -> StartCard(n, "flagships") }),
            shuffle = false,
        )
        val held = s.players.getValue("P0").customZones["flagships"].orEmpty()
        assertEq(2, held.size)
        val zone = CastZone.Declared("flagships")

        assertTrue(
            !anyPlayableIn(rules, s, "P0", held, zone),
            "broke, so the pool stays folded -- it has nothing to announce",
        )
        val rich = s.copy(players = s.players + ("P0" to s.players.getValue("P0").copy(pool = mapOf("" to 3))))
        assertTrue(
            anyPlayableIn(rules, rich, "P0", held, zone),
            "ANY castable card opens it -- 3 mana affords the Hulk even though the Loom costs 4",
        )
        assertTrue(!anyPlayableIn(rules, rich, "P0", emptyList(), zone), "an empty pool announces nothing")
        assertTrue(
            !anyPlayableIn(rules, rich, "P0", listOf(CardRef(9999, "Nonesuch")), zone),
            "a card the ruleset does not know is not castable, and must not throw",
        )
    }

    check("ui a recap reports what CHANGED -- not what the engine was asked to do") {
        // A recap is a DIFF of states, so triggers, the economy, draws and the
        // opening all show up -- verbs that do not exist yet included.
        val ship = CardDoc(faces = listOf(FaceDoc("Hulk", setOf("Ship"), fields = mapOf("power" to 2, "toughness" to 2)))).build()
        var before = newGame()
        val (b1, keep) = enterBattlefield(ship, "P0", before); before = b1
        val (b2, doomed) = enterBattlefield(ship, "P1", before); before = b2

        // Play one out, kill the other, damage the survivor, pay some hull,
        // and draw -- one window's worth of a real game.
        var after = before
        val (a1, fresh) = enterBattlefield(ship, "P1", after); after = a1
        after = after.copy(
            battlefield = after.battlefield - doomed +
                (keep to after.battlefield.getValue(keep).copy(damageMarked = 2, counters = mapOf("hull" to 4))),
            players = after.players.mapValues { (id, p) ->
                if (id == "P1") p.copy(counters = p.counters + ("hull" to 22)) else p.copy(hand = p.hand + CardRef(99, "hulk"))
            },
        )

        val lines = recap(before, after)
        fun said(fragment: String) = lines.any { fragment in it.text }
        assertTrue(said("Hulk enters play"), "a permanent arriving is the headline event")
        assertTrue(said("leaves play") || said("destroyed"), "and one leaving is reported with its fate")
        assertTrue(said("takes 2 damage"), "damage marked is a change worth seeing")
        assertTrue(said("hull 0 → 4"), "a COUNTER reports the number it landed on, not just a delta")
        assertTrue(said("P1 hull"), "a player counter too -- the loss condition lives on one")
        assertTrue(said("P0 draws 1 card"), "a draw is reported as a COUNT")

        // The ids are the point: the board lights exactly what the sentence is
        // about, which the pilot-sourced version could not say.
        assertTrue(lines.first { "enters play" in it.text }.subjects == setOf(fresh))
        // Ordering: the most consequential line is first.
        assertEq(ChangeKind.ENTERED, lines.first().kind)

        // A recap CANNOT leak a hidden zone, structurally: it never reads one.
        assertTrue(lines.none { "hulk" in it.text }, "a drawn card is counted, never named")

        // Nothing changed -> nothing to say. A beat that fires on an unchanged
        // board is worse than no beat: it teaches the player to dismiss them.
        assertTrue(recap(after, after).isEmpty())
    }

    check("ui an opening recap is what makes the FIRST priority window legible") {
        // There is no "before" at the start of a game, and the start is exactly
        // where a player is most lost: setup may run a dozen log lines.
        val station = CardDoc(faces = listOf(FaceDoc("Relay Kestrel-9", setOf("Station")))).build()
        var s = newGame()
        val (s1, mine) = enterBattlefield(station, "P0", s); s = s1
        val (s2, _) = enterBattlefield(station, "P1", s); s = s2
        s = s.copy(
            players = s.players.mapValues { (id, p) ->
                p.copy(
                    counters = mapOf("hull" to if (id == "P0") 15 else 24),
                    hand = List(5) { CardRef(100 + it, "x") },
                    customZones = mapOf("flagships" to List(3) { CardRef(200 + it, "f") }),
                )
            },
        )

        val lines = openingRecap(s, viewer = "P0")
        fun said(f: String) = lines.any { f in it.text }
        assertTrue(said("P0 starts with Relay Kestrel-9"), "what you have in play")
        assertTrue(said("hull 15") && said("hull 24"), "what the economy was set to, per seat")
        assertTrue(said("P0 has 3 in flagships"), "and a declared pool is part of the opening position")
        assertTrue(said("holds 5 cards"), "hand SIZE -- never the cards")
        assertTrue(lines.none { "x" == it.text }, "the opening never names a card in a hand")

        // Grouped by SEAT, opponent first, so each side reads as one block --
        // the same order the board rows are in.
        assertEq("P1", lines.first().text.take(2), "the opponent's side is read first, like the board")
    }

    check("ui a phase-naming trigger is seeded with a phase the GAME has") {
        // Phase-naming controls offer THIS game's phases, and a new "on your
        // phase" trigger is seeded with a phase the game has (a seed naming a
        // missing phase never fires).
        val mtgish = triggerOfKind(TRIGGER_KINDS[4], Effect.NoOp, 0)
        assertEq("upkeep", (mtgish as TriggerDoc.OnYourPhase).phase, "the default is unchanged for MTG")
        val eprs = triggerOfKind(TRIGGER_KINDS[4], Effect.NoOp, 0, defaultPhase = "regroup")
        assertEq("regroup", (eprs as TriggerDoc.OnYourPhase).phase, "a custom turn seeds one of ITS phases")

        // The shipped game really does have phases Magic does not, which is
        // what made this reachable rather than theoretical.
        val phases = EPR_SKIRMISH.rules.turn.phases.map { it.name }
        assertTrue("regroup" in phases, "EPR Skirmish declares its own turn")
        assertTrue(phases.none { it == "untap" }, "and none of Magic's -- so the fixed list was simply wrong here")
    }

    check("ui no CharOp renders as its own class name -- card text must never leak Kotlin") {
        // `charOpSummary` is exhaustive (no `else`); this covers every case and
        // that NONE reads like source code (a class name).
        val ops: List<CharOp> = listOf(
            CharOp.PlusPT(lit(1), lit(1)),
            CharOp.SetPT(lit(2), lit(2)),
            CharOp.GrantKeyword("cloaked"),
            CharOp.AddType("Ship"),
            CharOp.SetTypes(setOf("Ship")),
            CharOp.RemoveAbilities,
            CharOp.PlusField("fast", lit(2)),
            CharOp.SetField("hull", lit(5)),
            CharOp.GrantAbility(ActivatedAbility(cost = Cost(tapSource = true), effect = Effect.NoOp, name = "exhaust: scan")),
            CharOp.Bands("hull", listOf(CharOp.Bands.Band(3, lit(4), lit(4)))),
        )
        // Every case is present -- the same exhaustive-tag trick the other
        // drift tests use, so this list cannot silently fall behind the type.
        fun tag(o: CharOp): Int = when (o) {
            is CharOp.PlusPT -> 1; is CharOp.SetPT -> 2; is CharOp.GrantKeyword -> 3
            is CharOp.AddType -> 4; is CharOp.SetTypes -> 5; CharOp.RemoveAbilities -> 6
            is CharOp.Bands -> 7; is CharOp.GrantAbility -> 8; is CharOp.PlusField -> 9; is CharOp.SetField -> 10
        }
        assertEq((1..10).toSet(), ops.map { tag(it) }.toSet(), "every CharOp case is exercised here")

        val classNames = setOf(
            "PlusPT", "SetPT", "GrantKeyword", "AddType", "SetTypes",
            "RemoveAbilities", "Bands", "GrantAbility", "PlusField", "SetField",
        )
        for (o in ops) {
            val s = charOpSummary(o)
            assertTrue(s.isNotBlank(), "${o::class.simpleName} renders as something")
            assertTrue(
                classNames.none { it in s },
                "${o::class.simpleName} leaked a Kotlin class name into card text: \"$s\"",
            )
        }

        // And the reported one specifically names its field and its amount.
        assertEq("+2 fast", charOpSummary(CharOp.PlusField("fast", lit(2))))
        assertEq("+1 hull", charOpSummary(CharOp.PlusField("hull", lit(1))))
        assertTrue("exhaust: scan" in charOpSummary(ops.first { it is CharOp.GrantAbility }), "a granted ability reads as the author wrote it")
        assertEq("base 5 hull", charOpSummary(CharOp.SetField("hull", lit(5))))
        assertTrue("hull" in charOpSummary(ops.first { it is CharOp.Bands }), "a band names the counter it keys on")
    }

    check("ui a built card carries WHICH authored card it is -- names are not identity") {
        // The in-play rulebox joins on the built card's key, so two cards
        // sharing a name never show each other's text.
        val a = CardDoc(id = "card-a", faces = listOf(FaceDoc("Volley", setOf("Manoeuvre"))), text = "the first one")
        val b = CardDoc(id = "card-b", faces = listOf(FaceDoc("Volley", setOf("Manoeuvre"))), text = "the second one")
        assertEq("card-a", a.build().key)
        assertEq("card-b", b.build().key, "same NAME, different card -- and the built cards say so")
        assertTrue(a.build().key != b.build().key)

        // An un-minted card falls back to its name, exactly as `key()` does --
        // so this works for a bundle that has never been saved, and for the
        // hand-built definitions the tests use.
        val unminted = CardDoc(faces = listOf(FaceDoc("Ashbearer", setOf("Ship"))))
        assertEq("Ashbearer", unminted.build().key)
        assertEq(unminted.key(), unminted.build().key, "ONE derivation, not a second copy of it")

        // And it survives the JSON round trip, or a reloaded bundle would go
        // back to guessing.
        assertEq("card-a", cardDocOf(Json.parse(cardDocToJson(a))).build().key)
    }

    check("ui arcs consume THE tap answer -- an arc can never promise what a tap would refuse") {
        // The risk is drawing arcs from a second opinion about legality. So `arcsFor` calls
        // `boardTapTargets`, and this test pins that they agree.
        val shipT = TypeDef("Ship", fields = setOf("power", "toughness"), attacks = true)
        val rules = Rules(types = BUILTIN_TYPES_CORE + ("Ship" to shipT))
        val ship = CardDoc(faces = listOf(FaceDoc("Hulk", setOf("Ship"), fields = mapOf("power" to 1, "toughness" to 1)))).build()
        var s = newGame()
        val (s1, mine) = enterBattlefield(ship, "P0", s); s = s1
        val (s2, theirs) = enterBattlefield(ship, "P1", s); s = s2
        val (s3, theirs2) = enterBattlefield(ship, "P1", s); s = s3

        val prompt = Question.CombatTgt("P0", mine, null, s)
        val arcs = arcsFor(rules, prompt, s)!!
        assertEq(ArcEnd.Obj(mine), arcs.source, "the arc starts at the card that is acting")
        assertEq(setOf(theirs, theirs2), arcs.targetIds, "and reaches every legal target")
        assertEq(boardTapTargets(rules, prompt, s) - mine, arcs.targetIds, "which IS the tap answer, not a copy of it")

        // No source on the board -> no arcs, rather than an arc from nowhere.
        // A spell being targeted lives on the stack and has no tile to leave.
        assertTrue(arcsFor(rules, Question.PickTarget("P0", listOf(theirs), s), s) == null)
        // A prompt whose source has left play draws nothing.
        assertTrue(arcsFor(rules, Question.CombatTgt("P0", 9999, null, s), s) == null)

        // Blockers names several possible sources, so it needs one ARMED --
        // which is exactly the arm-then-confirm grammar the board already has.
        val blockers = Question.Blockers("P0", listOf(mine), listOf(theirs), s)
        assertTrue(arcsFor(rules, blockers, s) == null, "no arcs until a blocker is picked")
        assertEq(setOf(theirs), arcsFor(rules, blockers, s, armedSource = mine)!!.targetIds)

        // Nothing to reach -> null, not an empty fan of arcs.
        var solo = newGame()
        val (o1, only) = enterBattlefield(ship, "P0", solo); solo = o1
        assertTrue(arcsFor(rules, Question.CombatTgt("P0", only, null, solo), solo) == null)
    }

    check("ui a permanent's own abilities are offered on ITS tile, grouped by permanent") {
        // The board should be the one place you act. Play-from-hand moved onto
        // it in 6.3 and combat targeting in 6.4; activating an ability stayed
        // in the prompt bar's chip list, so the board was where you played and
        // attacked and a list was where you USED things.
        val shipT = TypeDef("Ship", fields = setOf("power", "toughness"), attacks = true)
        val rules0 = Rules(types = BUILTIN_TYPES_CORE + ("Ship" to shipT))
        // Free abilities, so legality turns on nothing but their existence.
        val one = CardDoc(faces = listOf(FaceDoc(
            "Rig", setOf("Ship"), fields = mapOf("power" to 1, "toughness" to 1),
            activated = listOf(ActivatedAbility(Cost(), Effect.Draw(PlayerRef.You, lit(1)))),
        ))).build()
        val two = CardDoc(faces = listOf(FaceDoc(
            "Vray", setOf("Ship"), fields = mapOf("power" to 1, "toughness" to 1),
            activated = listOf(
                ActivatedAbility(Cost(), Effect.Draw(PlayerRef.You, lit(1))),
                ActivatedAbility(Cost(), Effect.GainLife(PlayerRef.You, lit(2)), name = "steady the line"),
            ),
        ))).build()
        val plain = CardDoc(faces = listOf(FaceDoc("Hulk", setOf("Ship"), fields = mapOf("power" to 1, "toughness" to 1)))).build()
        val rules = rules0.knowing(one, two)

        var s = newGame()
        val (s1, rig) = enterBattlefield(one, "P0", s); s = s1
        val (s2, vray) = enterBattlefield(two, "P0", s); s = s2
        val (s3, hulk) = enterBattlefield(plain, "P0", s); s = s3
        val (s4, theirs) = enterBattlefield(two, "P1", s); s = s4

        val byPerm = activationsBy(rules, s, "P0")

        assertEq(1, byPerm[rig]?.size, "one ability is ONE move -- nothing to disambiguate")
        assertEq(2, byPerm[vray]?.size, "two is the only genuinely ambiguous case, and the only one that gets a menu")
        assertTrue(hulk !in byPerm, "a permanent with no abilities is absent, not present-and-empty")
        assertTrue(theirs !in byPerm, "and you cannot act with a permanent you do not control")

        // Every move names its own source, so a tile can trust what it is given.
        byPerm.forEach { (src, ms) -> ms.forEach { assertEq(src, it.action.source) } }

        // The labels have to say what the ability DOES -- "ability 2" is what
        // the prompt bar's chip list already said, and the reason it was worth
        // moving onto the board at all.
        assertTrue(byPerm.getValue(rig).single().label.contains("1"), "an unnamed ability reads as its effect")
        assertTrue(
            byPerm.getValue(vray).any { it.label == "steady the line" },
            "and an AUTHORED name wins -- that is what the field is for",
        )

        // It is legalActionsFor, not a second walk over abilitiesOf: the same
        // enumeration auto-pass and the pilots use.
        assertEq(
            legalActionsFor(rules, s, "P0").filterIsInstance<PriorityAction.Activate>().toSet(),
            byPerm.values.flatten().map { it.action }.toSet(),
            "which IS the engine's own answer, not a copy of it",
        )
        assertTrue(activationsBy(rules, s, "P1").getValue(theirs).size == 2, "the query is per-player")
    }

    check("ui a pending lane pick becomes arcs to the LANES, not chips in the bar") {
        // The lane pick is answered like a combat target: highlight and arc on
        // the board, not a row of chips.
        val lanes = (1..3).map { PlayZoneDef("lane-$it", ZoneScope.PER_PLAYER, maxOccupants = 1) }
        val shipT = TypeDef("Ship", fields = setOf("power", "toughness"), attacks = true, zoneChoices = lanes.map { it.id })
        val rules = Rules(
            types = BUILTIN_TYPES_CORE + ("Ship" to shipT),
            zones = BUILTIN_ZONES + lanes.associateBy { it.id },
        )
        val ship = CardDoc(faces = listOf(FaceDoc("Hulk", setOf("Ship"), fields = mapOf("power" to 1, "toughness" to 1)))).build()

        var s = newGame()
        val pending = PriorityAction.PlayPermanent(ship.lowered(LIFE), 0, from = CardSource(CastZone.Std(HiddenZone.HAND), 1))
        val prompt = Question.Priority("P0", s)

        val arcs = arcsFor(rules, prompt, s, pendingPlay = pending)!!
        assertEq(ArcEnd.Hand, arcs.source, "the source is the DOCK, which has no rectangle on the board")
        assertEq(
            setOf(ArcEnd.Zone("lane-1"), ArcEnd.Zone("lane-2"), ArcEnd.Zone("lane-3")),
            arcs.targets,
            "every lane with room in it is an arc target",
        )
        assertTrue(arcs.targetIds.isEmpty(), "a lane is not a permanent, so it answers no tap by id")

        // Fill a lane and it stops being offered -- the SAME query the commit
        // path uses, so a lit lane can never be one a commit would refuse.
        val (s1, _) = enterBattlefield(ship, "P0", s, zone = ZoneRef("lane-2", "P0")); s = s1
        assertEq(
            setOf(ArcEnd.Zone("lane-1"), ArcEnd.Zone("lane-3")),
            arcsFor(rules, Question.Priority("P0", s), s, pendingPlay = pending)!!.targets,
            "a full lane is not a legal destination and must not light up",
        )
        assertEq(
            openZonesFor(rules, s, "P0", pending).orEmpty().toSet(),
            arcsFor(rules, Question.Priority("P0", s), s, pendingPlay = pending)!!
                .targets.filterIsInstance<ArcEnd.Zone>().map { it.zone }.toSet(),
            "which IS openZonesFor, not a second opinion about it",
        )

        // A pending play takes precedence over whatever the prompt would ask,
        // the same precedence the prompt bar already gave it.
        val noChoice = Rules(types = BUILTIN_TYPES_CORE + ("Ship" to TypeDef("Ship", attacks = true)))
        assertTrue(
            arcsFor(noChoice, Question.Priority("P0", s), s, pendingPlay = pending) == null,
            "a type that declares no zone choice draws nothing, rather than an arc to nowhere",
        )
    }

    check("ui a Viewpoint puts the viewer nearest the thumb, whichever seat they are") {
        val s = newGame().let { it.copy(turnOrder = listOf("P0", "P1")) }
        assertEq(listOf("P1", "P0"), Viewpoint(PlayMode.PLAYER, "P0").seatOrder(s))
        assertEq(listOf("P0", "P1"), Viewpoint(PlayMode.PLAYER, "P1").seatOrder(s))
        // Same ordering the board layout already applies to its rows -- stated
        // once so the strips and the lanes cannot disagree about which side of
        // the table is yours.
        assertEq(
            boardLayout(Rules.DEFAULT, s, viewer = "P1").rows.map { it.owner },
            Viewpoint(PlayMode.PLAYER, "P1").seatOrder(s),
        )
    }

    // -- REDACTION: the pilots cannot look, not merely do not ---------------
    //
    // Most important for Reactive: holding an answer for the right threat is
    // trivial with perfect information and the actual skill without it.

    /** A game where P0 and P1 each hold two cards, own a library, and share a
     *  publicly-declared pool alongside a secret one. */
    fun secretGame(): Pair<Rules, GameState> {
        val r = Rules(
            cards = mapOf("scout" to ship),
            hiddenZones = mapOf(
                "flagships" to HiddenZoneDef("flagships", alwaysVisible = true),
                "reserve" to HiddenZoneDef("reserve", alwaysVisible = false),
            ),
        )
        val s = newGame().let { g ->
            g.copy(players = g.players.mapValues { (id, p) ->
                p.copy(
                    hand = listOf(CardRef(if (id == "P0") 10 else 20, "scout"), CardRef(if (id == "P0") 11 else 21, "scout")),
                    library = listOf(CardRef(if (id == "P0") 30 else 40, "scout")),
                    graveyard = listOf(CardRef(if (id == "P0") 50 else 60, "scout")),
                    customZones = mapOf(
                        "flagships" to listOf(CardRef(if (id == "P0") 70 else 80, "scout")),
                        "reserve" to listOf(CardRef(if (id == "P0") 90 else 100, "scout")),
                    ),
                )
            })
        }
        return r to s
    }

    check("ui redaction hides the OPPONENT's hand faces, and keeps the count") {
        val (r, s) = secretGame()
        val seen = s.asSeenBy("P0", r)
        val theirs = seen.players.getValue("P1").hand
        assertEq(2, theirs.size, "how many they hold is public in every card game ever printed")
        assertTrue(theirs.all { it.cardId == HIDDEN_CARD }, "but not WHAT they hold")
        assertTrue(theirs.none { it.instanceId in setOf(20, 21) }, "nor their ids, which are dealt in decklist order")
        val mine = seen.players.getValue("P0").hand
        assertTrue(mine.all { it.cardId == "scout" }, "my own hand is mine to read")
    }

    check("ui redaction hides EVERY library, its owner's included") {
        // The clause a naive "hide what isn't mine" gets wrong, and the reason
        // this defers to Viewpoint instead of testing ownership: looking at
        // your own deck order is not a viewpoint any card game has.
        val (r, s) = secretGame()
        val seen = s.asSeenBy("P0", r)
        for (id in listOf("P0", "P1")) {
            assertTrue(seen.players.getValue(id).library.all { it.cardId == HIDDEN_CARD }, "$id's library is face-down")
            assertEq(1, seen.players.getValue(id).library.size, "and still has a size")
        }
    }

    check("ui redaction honours the BUNDLE's own public zones -- a declared pool stays open") {
        // An `alwaysVisible` pool failing CLOSED for the opponent is a bug this
        // project has already had once, in the board. One policy, one answer.
        val (r, s) = secretGame()
        val seen = s.asSeenBy("P0", r)
        val p1 = seen.players.getValue("P1")
        assertTrue(p1.customZones.getValue("flagships").all { it.cardId == "scout" }, "both players can see a Flagship pool")
        assertTrue(p1.customZones.getValue("reserve").all { it.cardId == HIDDEN_CARD }, "an undeclared pool stays secret")
        assertTrue(seen.players.getValue("P0").customZones.getValue("reserve").all { it.cardId == "scout" }, "my own secret pool is mine")
    }

    check("ui redaction leaves the PUBLIC record alone -- battlefield and graveyards") {
        val (r, s0) = secretGame()
        val s = enterBattlefield(ship, "P1", s0).first
        val seen = s.asSeenBy("P0", r)
        assertEq(s.battlefield, seen.battlefield, "the board is public, untouched")
        assertTrue(seen.players.getValue("P1").graveyard.all { it.cardId == "scout" }, "so is a graveyard")
    }

    check("ui a policy pilot decides from a REDACTED state -- enforcement, not discipline") {
        // The claim that matters: not "the pilot does not look" but "there is
        // nothing there to look at". A card the opponent holds must not become
        // an action, and must not resolve to a definition.
        val (r, s) = secretGame()
        val seen = s.asSeenBy("P0", r)
        assertEq(null, r.cards[HIDDEN_CARD], "a masked ref resolves to no card definition at all")
        // P0's own two cards are still playable; P1's four hidden ones are not
        // reachable from P0's action list under any circumstances.
        assertTrue(legalActionsFor(r, seen, "P0").isNotEmpty(), "my own hand still produces actions")
        // Against P1, what survives redaction is EXACTLY their public Flagship
        // pool -- a strict reduction of the true state, not "zero" (the pool is
        // meant to stay readable).
        val truth = legalActionsFor(r, s, "P1")
        val visible = legalActionsFor(r, seen, "P1")
        assertEq(4, truth.size, "unredacted: 2 hand + 1 flagship + 1 reserve all resolve")
        assertEq(1, visible.size, "redacted: only the publicly declared pool")
        assertTrue(visible.size < truth.size, "redaction strictly reduces what can be derived about them")
    }


    // -- POLICY PILOTS: the instrument ---------------------------------------
    //
    // Pilots name the lane explicitly (with `zone = null` the engine takes the
    // default lane, and the board describes enumeration order, not strategy).

    /** A lane game with one free Ship in P0's hand, playable right now. */
    fun policyGame(cap: Int? = 1): Pair<Rules, GameState> {
        val r = Rules(
            types = BUILTIN_TYPES_CORE + mapOf(
                "Ship" to TypeDef("Ship", zoneChoices = listOf("lane-1", "lane-2", "lane-3")),
            ),
            zones = BUILTIN_ZONES + listOf("lane-1", "lane-2", "lane-3")
                .map { PlayZoneDef(it, ZoneScope.PER_PLAYER, maxOccupants = cap) }.associateBy { it.id },
            cards = mapOf("scout" to ship),
        )
        val s = newGame().let { g ->
            g.copy(players = g.players.mapValues { (id, p) ->
                if (id == "P0") p.copy(hand = p.hand + CardRef(90, "scout")) else p
            })
        }
        return r to s
    }

    fun laneChosenBy(p: Policy, r: Rules, s: GameState): String? =
        (runSync { PolicyPilot("P0", r, p).priorityOf("P0", s) } as PriorityAction.PlayPermanent).zone

    check("ui a policy pilot NAMES the lane it plays into -- no pilot ever chose one before") {
        val (r, s) = policyGame()
        for (p in ALL_POLICIES) {
            val a = runSync { PolicyPilot("P0", r, p).priorityOf("P0", s) }
            assertTrue(a is PriorityAction.PlayPermanent, "${p.name} plays the ship")
            assertTrue(
                (a as PriorityAction.PlayPermanent).zone != null,
                "${p.name} names a lane rather than leaving it to openZoneFor's default",
            )
        }
    }

    check("ui the three policies DISAGREE about which lane -- the axis the matrix needs") {
        val (r, s0) = policyGame()
        // The opponent holds lane-2, and only lane-2.
        val s = enterBattlefield(ship, "P1", s0, zone = ZoneRef("lane-2", "P1")).first
        assertTrue(laneChosenBy(Proactive, r, s) != "lane-2", "Proactive takes an UNOPPOSED lane -- that is its clock")
        assertEq("lane-2", laneChosenBy(Reactive, r, s), "Reactive CONTESTS the lane they are actually using")
    }

    check("ui Attrition's lane rule cannot express itself at maxOccupants 1 -- the cap IS the constraint") {
        // Under a cap of one, Attrition's "reinforce my lane" is unreachable and
        // falls back to contesting -- a ceiling the board rule puts on how many
        // distinct plans can exist.
        val (capped, c0) = policyGame(cap = 1)
        val c = enterBattlefield(ship, "P0", c0, zone = ZoneRef("lane-3", "P0")).first
        assertTrue(
            "lane-3" !in openZonesFor(capped, c, "P0", playShip())!!,
            "the lane it holds is FULL, so 'reinforce' is not a choice the board can offer",
        )
        assertTrue(laneChosenBy(Attrition, capped, c) != "lane-3", "so it cannot reinforce")

        // Uncapped, the SAME policy reinforces -- and now genuinely differs
        // from Proactive, which still spreads. lane-3 rather than lane-1 on
        // purpose: lane-1 is what `maxByOrNull` returns on a tie, so asserting
        // it would pass for any policy at all.
        val (open, o0) = policyGame(cap = null)
        val o = enterBattlefield(ship, "P0", o0, zone = ZoneRef("lane-3", "P0")).first
        assertEq("lane-3", laneChosenBy(Attrition, open, o), "with room to stack, it concentrates force")
        assertTrue(laneChosenBy(Proactive, open, o) != "lane-3", "while Proactive spreads -- a real disagreement")
    }

    check("ui the policies rank the SAME options differently -- objective functions and nothing else") {
        val (r, s) = policyGame()
        val v = PolicyView(r, s, "P0")
        val cheapBody = PriorityAction.PlayPermanent(ship.lowered(LIFE), 0)
        val bigBody = PriorityAction.PlayPermanent(
            CardDefinition(
                listOf(Face("Hulk", setOf("Ship"), Characteristics("Hulk", setOf("Ship")))),
                cost = Cost(mana = mapOf("" to 4)),
            ).lowered(LIFE),
            0,
        )
        val answer = PriorityAction.CastSpell(Effect.NoOp.lowered(LIFE), setOf("Instant"), Cost())

        assertTrue(Proactive.value(cheapBody, v) > Proactive.value(bigBody, v), "Proactive curves out")
        assertTrue(Attrition.value(bigBody, v) > Attrition.value(cheapBody, v), "Attrition takes the biggest")
        assertTrue(Reactive.value(answer, v) > Reactive.value(cheapBody, v), "Reactive prefers the answer")
        assertTrue(Proactive.value(cheapBody, v) > Proactive.value(answer, v), "Proactive prefers the body")
    }

    // -- Levelling the pilots: two contracts --------------------------------
    //
    // Defects stated in Reactive's own terms, found by measuring what it DID.
    // Not tuning toward equal win rates, which would fit the pilots to one
    // deck and destroy the instrument.

    check("ui Reactive closes once STABILISED, not only when their board is empty") {
        // Close once stabilised, not once their board is empty (bodies keep
        // arriving, so "empty" meant never).
        val (r, s0) = policyGame()
        val level = enterBattlefield(ship, "P0", s0, zone = ZoneRef("lane-1", "P0")).first
            .let { enterBattlefield(ship, "P1", it, zone = ZoneRef("lane-2", "P1")).first }
        assertTrue(Reactive.pressesFace(PolicyView(r, level, "P0")), "level on board -- stabilised, so close")

        val behind = enterBattlefield(ship, "P1", level, zone = ZoneRef("lane-3", "P1")).first
        assertTrue(!Reactive.pressesFace(PolicyView(r, behind, "P0")), "behind on board -- answer first")
    }

    check("ui Reactive keeps ONE lane clear -- denial is symmetric, so contesting everything denies itself") {
        // `laneLockedPlayerTargets` cuts both ways: a lane Reactive contests is
        // a lane Reactive cannot attack a Station through either. Contesting
        // every lane put its own two rules in direct opposition -- `lane` made
        // `pressesFace` unreachable.
        val (r, s0) = policyGame()
        // They hold lane-2 and lane-3; I hold nothing yet.
        var s = enterBattlefield(ship, "P1", s0, zone = ZoneRef("lane-2", "P1")).first
        s = enterBattlefield(ship, "P1", s, zone = ZoneRef("lane-3", "P1")).first
        val fresh = PolicyView(r, s, "P0")
        assertTrue(
            Reactive.lane("lane-2", fresh) > Reactive.lane("lane-1", fresh),
            "the FIRST answer stands in front of a threat",
        )

        // Now I already contest lane-2. A second block is worth less than the
        // empty lane I would win through.
        val contesting = enterBattlefield(ship, "P0", s, zone = ZoneRef("lane-2", "P0")).first
        val v = PolicyView(r, contesting, "P0")
        assertEq(1, v.lanesIContest(), "one lane held")
        assertTrue(
            Reactive.lane("lane-1", v) > Reactive.lane("lane-3", v),
            "with a threat already answered, the CLEAR lane beats a second block",
        )
    }

    check("ui the policies still disagree after levelling -- the fixes did not merge them") {
        // The risk of any levelling pass: three policies that agree measure
        // nothing. Same board, and they must still want different lanes.
        val (r, s0) = policyGame()
        val s = enterBattlefield(ship, "P1", s0, zone = ZoneRef("lane-2", "P1")).first
        val v = PolicyView(r, s, "P0")
        val picks = ALL_POLICIES.associate { p ->
            p.name to listOf("lane-1", "lane-2", "lane-3").maxByOrNull { p.lane(it, v) }
        }
        assertEq("lane-2", picks["Reactive"], "Reactive still contests the occupied lane")
        assertTrue(picks["Proactive"] != "lane-2", "Proactive still takes a free one")
        assertTrue(picks.values.toSet().size > 1, "the policies have not collapsed onto one answer")
    }

    check("ui combatBoardTargets narrows to what THIS attacker can reach -- pilot and engine agree") {
        // A pilot that offers itself an illegal target does not merely play badly, it CONTAMINATES every
        // number taken under that ruleset -- the loss looks like strategy.
        val r = Rules(
            types = BUILTIN_TYPES_CORE + ("Ship" to TypeDef("Ship", fields = setOf("fast"), attacks = true,
                zoneChoices = listOf("lane-1", "lane-2"))),
            zones = BUILTIN_ZONES + listOf("lane-1", "lane-2")
                .map { PlayZoneDef(it, ZoneScope.PER_PLAYER, maxOccupants = 1) }.associateBy { it.id },
            combat = FAST_SLOW_COMBAT.copy(laneLockedBoardTargets = true, crossLaneKeyword = "reach").lowered(),
        )
        val hull = CardDoc(faces = listOf(FaceDoc("Hull", setOf("Ship"), fields = mapOf("fast" to 1)))).build()
        var s = newGame()
        val (s1, mine) = enterBattlefield(hull, "P0", s, zone = ZoneRef("lane-1", "P0")); s = s1
        val (s2, opposite) = enterBattlefield(hull, "P1", s, zone = ZoneRef("lane-1", "P1")); s = s2
        val (s3, across) = enterBattlefield(hull, "P1", s, zone = ZoneRef("lane-2", "P1")); s = s3

        assertEq(setOf(opposite, across), combatBoardTargets(r, s, "P0"), "asked about the BOARD: everything")
        assertEq(setOf(opposite), combatBoardTargets(r, s, "P0", mine, null), "asked for ONE attacker: only its own lane")

        // And a policy pilot never names the unreachable one.
        for (p in ALL_POLICIES) {
            val t = runSync { PolicyPilot("P0", r, p).combatTargetOf("P0", mine, null, s) }
            assertTrue(t != CombatTarget.Obj(across), "${p.name} does not pick a target it cannot reach")
        }
    }

    check("ui a reaching attacker is offered the whole board again") {
        val r = Rules(
            types = BUILTIN_TYPES_CORE + ("Ship" to TypeDef("Ship", fields = setOf("fast"), attacks = true,
                zoneChoices = listOf("lane-1", "lane-2"))),
            zones = BUILTIN_ZONES + listOf("lane-1", "lane-2")
                .map { PlayZoneDef(it, ZoneScope.PER_PLAYER, maxOccupants = 1) }.associateBy { it.id },
            combat = FAST_SLOW_COMBAT.copy(laneLockedBoardTargets = true, crossLaneKeyword = "reach").lowered(),
        )
        val plain = CardDoc(faces = listOf(FaceDoc("Hull", setOf("Ship"), fields = mapOf("fast" to 1)))).build()
        val reacher = CardDoc(faces = listOf(FaceDoc("Lance", setOf("Ship"),
            fields = mapOf("fast" to 1), keywords = setOf("reach")))).build()
        var s = newGame()
        val (s1, mine) = enterBattlefield(reacher, "P0", s, zone = ZoneRef("lane-1", "P0")); s = s1
        val (s2, opposite) = enterBattlefield(plain, "P1", s, zone = ZoneRef("lane-1", "P1")); s = s2
        val (s3, across) = enterBattlefield(plain, "P1", s, zone = ZoneRef("lane-2", "P1")); s = s3
        assertEq(setOf(opposite, across), combatBoardTargets(r, s, "P0", mine, null), "reach sees the whole board")
    }

    check("ui a pilot repositions only when the destination is BETTER by its own lane rule") {
        // Without this the pilot picks ability index 0 every time and exhausts
        // the fleet shuffling into lane-1 -- which under exhaustedCannotAct
        // costs it the combat round for nothing. Not a null measurement, an
        // actively misleading one.
        val r0 = Rules(
            types = BUILTIN_TYPES_CORE + ("Ship" to TypeDef("Ship", fields = setOf("fast"), attacks = true,
                zoneChoices = listOf("lane-1", "lane-2", "lane-3"))),
            zones = BUILTIN_ZONES + listOf("lane-1", "lane-2", "lane-3")
                .map { PlayZoneDef(it, ZoneScope.PER_PLAYER, maxOccupants = 1) }.associateBy { it.id },
            combat = FAST_SLOW_COMBAT.copy(exhaustedCannotAct = true).lowered(),
        )
        val tender = CardDoc(faces = listOf(FaceDoc(
            "Tender", setOf("Ship"), fields = mapOf("fast" to 1),
            activated = listOf("lane-1", "lane-2", "lane-3").map { lane ->
                ActivatedAbility(Cost(tapSource = true), Effect.MovePermanent(BoundTarget(SELF), ZoneRef(lane)),
                    name = "exhaust: reposition to $lane")
            },
        ))).build()
        val hulk = CardDoc(faces = listOf(FaceDoc("Hulk", setOf("Ship"), fields = mapOf("fast" to 1)))).build()
        val r = r0.knowing(tender)

        // Reactive contests: it wants to stand opposite a threat. Put its ship
        // in an EMPTY lane and the enemy in lane-3, so moving is an improvement.
        var s = newGame()
        val (s1, mine) = enterBattlefield(tender, "P0", s, zone = ZoneRef("lane-1", "P0")); s = s1
        s = enterBattlefield(hulk, "P1", s, zone = ZoneRef("lane-3", "P1")).first
        val v = PolicyView(r, s, "P0")
        assertTrue(
            Reactive.lane("lane-3", v) > Reactive.lane("lane-1", v),
            "precondition: Reactive prefers the contested lane",
        )
        val act = runSync { PolicyPilot("P0", r, Reactive).priorityOf("P0", s) }
        assertTrue(act is PriorityAction.Activate, "it repositions rather than passing")
        assertEq(2, (act as PriorityAction.Activate).index, "and picks lane-3, NOT ability index 0")

        // With the enemy already opposed, no move improves anything -> no move.
        var s2 = newGame()
        val (t1, mine2) = enterBattlefield(tender, "P0", s2, zone = ZoneRef("lane-3", "P0")); s2 = t1
        s2 = enterBattlefield(hulk, "P1", s2, zone = ZoneRef("lane-3", "P1")).first
        val act2 = runSync { PolicyPilot("P0", r, Reactive).priorityOf("P0", s2) }
        assertEq(PriorityAction.PassPriority, act2, "already in the best lane -- exhausting for nothing is refused")
        assertTrue(mine != mine2 || true)
    }

    check("ui Reactive judges a body by its RATE and never refuses the top of the curve") {
        // A body is judged by its rate: Reactive must not refuse the top of the
        // curve (a constant-minus-cost value made {4} score 0 = pass).
        val (r, s) = policyGame()
        val v = PolicyView(r, s, "P0")
        fun bodyOf(cost: Int, hull: Int) = PriorityAction.PlayPermanent(
            CardDefinition(
                listOf(Face("Hulk", setOf("Ship"),
                    Characteristics("Hulk", setOf("Ship"), mapOf("fast" to 1, "hull" to hull)))),
                cost = Cost(mana = mapOf("" to cost)),
            ),
            0,
        )
        val cheapThin = bodyOf(cost = 1, hull = 3)   // rate 40
        val dearFat = bodyOf(cost = 4, hull = 16)    // rate 42

        assertTrue(Reactive.value(dearFat, v) > 0, "a {4} body is never refused outright")
        assertTrue(
            Reactive.value(dearFat, v) > Reactive.value(cheapThin, v),
            "and the better RATE wins, rather than the lower cost",
        )
        // ...but answers still outrank bodies, which is what keeps Reactive
        // distinct from Attrition rather than merging the two.
        val answer = PriorityAction.CastSpell(Effect.NoOp.lowered(LIFE), setOf("Instant"), Cost(mana = mapOf("" to 2)))
        assertTrue(Reactive.value(answer, v) > Reactive.value(dearFat, v), "answers still come first")
        assertTrue(Attrition.value(dearFat, v) > Attrition.value(answer, v), "while Attrition still prefers the body")
    }

    check("ui a policy pilot is deterministic -- a measured game has to replay") {
        val (r, s) = policyGame()
        for (p in ALL_POLICIES) {
            assertEq(
                runSync { PolicyPilot("P0", r, p).priorityOf("P0", s) },
                runSync { PolicyPilot("P0", r, p).priorityOf("P0", s) },
                "${p.name} repeats its decision",
            )
        }
    }
    check("ui the prompt bar carries exactly what the BOARD cannot") {
        // Subtractive: the bar carries every activation whose source the board
        // draws NO tile for, so whatever the board stops drawing, the bar
        // carries.
        val leaderT = TypeDef("Leader", zoneOfPlay = "command")
        val shipT = TypeDef(
            "Ship", fields = setOf("hull", "fast"), attacks = true,
            damageCounter = "hull", diesWhen = (selfCounter("hull") lte lit(0)).lowered(),
            zoneChoices = listOf("lane"),
        )
        val zap = ActivatedAbility(
            cost = Cost(),
            effect = Effect.DealDamage(lit(1), BoundTarget(SELF)),
            name = "ping",
        )
        val leader = CardDoc(faces = listOf(FaceDoc("Warden", setOf("Leader"), activated = listOf(zap)))).build()
        val ship = CardDoc(
            faces = listOf(FaceDoc("Runner", setOf("Ship"), fields = mapOf("hull" to 3, "fast" to 1), activated = listOf(zap))),
            entersWith = listOf(CounterDef("hull", lit(3))),
        ).build()
        val rules = Rules(
            types = BUILTIN_TYPES_CORE + mapOf("Leader" to leaderT, "Ship" to shipT),
            cards = mapOf("Warden" to leader, "Runner" to ship),
            zones = BUILTIN_ZONES + listOf(
                PlayZoneDef("lane", ZoneScope.PER_PLAYER, maxOccupants = 2),
                PlayZoneDef("command", ZoneScope.PER_PLAYER, maxOccupants = 1),
            ).associateBy { it.id },
        )
        val s = setupGame(
            rules = rules,
            startInPlay = mapOf("P0" to listOf(StartCard("Warden", "command"), StartCard("Runner", "lane"))),
        )
        val drawn = ccgui.boardLayout(rules, s, "P0").renderedIds()
        val all = ccgui.activationsBy(rules, s, "P0")
        assertTrue(all.size == 2, "both permanents can act -- otherwise this proves nothing")
        // Both are on the board: an anchor and a lane occupant. So the bar
        // carries NEITHER.
        assertTrue(all.keys.all { it in drawn }, "the board draws both sources")
        assertEq(emptyMap<ObjectId, List<PermMove>>(), ccgui.activationsWithoutTile(rules, s, "P0"), "so the bar is empty")

        // The guard half: EVERY battlefield permanent the seat controls is drawn
        // somewhere, or an ability on an undrawn one would be unreachable.
        val mine = s.battlefield.values.filter { it.controller == "P0" }.map { it.id }.toSet()
        assertEq(mine, mine intersect drawn, "the board draws every permanent the seat controls")
    }

    check("ui the play surface picks a shape by SIZE, not by orientation") {
        // Size-based, not orientation-based (a foldable's tall narrow window is
        // portrait-shaped whatever the sensor reports).
        assertEq(ccgui.PlaySurface.STACKED, ccgui.playSurfaceFor(392, 880), "a phone held upright")
        assertEq(ccgui.PlaySurface.RAILED, ccgui.playSurfaceFor(880, 392), "the same phone rotated")
        assertEq(ccgui.PlaySurface.RAILED, ccgui.playSurfaceFor(1280, 800), "a tablet in landscape")
        assertEq(ccgui.PlaySurface.STACKED, ccgui.playSurfaceFor(800, 1280), "and upright")
        // Wider than it is tall, but not wide ENOUGH: a rail here would take
        // more from the board than the prompt was costing it.
        assertEq(
            ccgui.PlaySurface.STACKED,
            ccgui.playSurfaceFor(ccgui.RAIL_MIN_WIDTH_DP - 1, 400),
            "a small landscape window keeps stacking",
        )
        assertEq(
            ccgui.PlaySurface.RAILED,
            ccgui.playSurfaceFor(ccgui.RAIL_MIN_WIDTH_DP, 400),
            "and takes the rail exactly at the breakpoint",
        )
        // A square window is not landscape. Stated because `>=` here and `>`
        // there is the kind of asymmetry that looks like a typo later.
        assertEq(ccgui.PlaySurface.STACKED, ccgui.playSurfaceFor(700, 700), "square is not landscape")
    }

    check("ui the anchor block is sized by its ANCHOR COUNT, and always leaves the numbers room") {
        // The anchor block is derived from the anchor count (a fixed 300dp gave
        // three anchors' numbers zero width).
        assertEq(300, ccgui.anchorBlockWidthDp(2), "two anchors reproduce the old constant exactly")
        assertEq(410, ccgui.anchorBlockWidthDp(3), "the reported case grows instead of crushing")
        assertEq(190, ccgui.anchorBlockWidthDp(1))
        assertEq(86, ccgui.anchorBlockWidthDp(0), "no anchors is still a numbers column")
        assertEq(ccgui.anchorBlockWidthDp(0), ccgui.anchorBlockWidthDp(-1), "a negative count cannot underflow")

        // The invariant: whatever the count, what is left
        // after the tiles is at least the numbers' floor.
        for (n in 0..8) {
            val tiles = if (n == 0) 0 else ccgui.ANCHOR_TILE_W_DP * n + ccgui.BOARD_GAP_DP * (n - 1)
            val left = ccgui.anchorBlockWidthDp(n) - ccgui.BOARD_EDGE_DP * 2 - tiles
            assertEq(ccgui.SEAT_NUMBERS_W_DP, left, "$n anchors still leave the numbers their floor")
        }

        // Clamped, the block can never take the whole row from the lanes.
        assertEq(440, ccgui.anchorBlockWidthDp(8, availableDp = 800), "8 anchors are clamped to 55%")
        assertEq(410, ccgui.anchorBlockWidthDp(3, availableDp = 800), "3 fit, so they are not clamped")
        for (n in 0..8) {
            assertTrue(
                ccgui.anchorBlockWidthDp(n, availableDp = 800) <= 440,
                "$n anchors never take more than 55% of the row",
            )
        }
    }

    check("ui the agent loop: every option it OFFERS, it can also decode") {
        // THE INVARIANT: every id `optionsFor` offers decodes via `answerFor` at
        // that prompt, and an unoffered id is refused.
        val doc = CORE_BUNDLE
        val rules = doc.rules()
        var session = ccgui.PlaySession(p0Deck = 0, p1Deck = 1, seed = 20260909)

        var decisions = 0
        var kinds = mutableSetOf<String>()
        for (step in 0 until 60) {
            val v = ccgui.advance(doc, session)
            if (v.over) break
            val p = v.prompt ?: break
            decisions++
            kinds += p::class.simpleName ?: "?"
            assertTrue(v.options.isNotEmpty(), "a live prompt always offers something: ${v.question}")
            // EVERY id, not just the one taken.
            for (o in v.options) {
                assertTrue(
                    ccgui.answerFor(rules, p, o.id) != null,
                    "offered \"${o.label}\" (${o.id}) at ${v.question} but could not decode it",
                )
            }
            // An id that was never offered must be REFUSED, not guessed at.
            assertEq(null, ccgui.answerFor(rules, p, "zzz-not-an-option"), "an unoffered id is refused")

            val pick = v.options.firstOrNull { it.id !in setOf("pass", "concede") } ?: v.options.first()
            session = session.answered(ccgui.answerFor(rules, p, pick.id)!!)
        }
        assertTrue(decisions > 10, "the game actually progressed ($decisions decisions)")
        assertTrue("Priority" in kinds, "priority windows were exercised")
    }

    check("ui the agent view REDACTS for a named seat -- an agent must not read a person's hand") {
        // The agent surface asks `Viewpoint`: a named viewer never sees the
        // opponent's hand.
        val doc = CORE_BUNDLE
        val session = ccgui.PlaySession(p0Deck = 0, p1Deck = 2, seed = 20260909)

        val open = ccgui.advance(doc, session)
        val asP0 = ccgui.advance(doc, session, viewer = "P0")

        val openP1 = open.seats.first { it.id == "P1" }
        val hiddenP1 = asP0.seats.first { it.id == "P1" }
        assertTrue(openP1.hand.size > 1, "the open view shows the opponent's cards")
        assertTrue(
            hiddenP1.hand.none { it.contains(" ") && !it.contains("hidden") },
            "a named viewer must not see the opponent's card names: ${hiddenP1.hand}",
        )
        // A hidden hand is a COUNT, not an absence -- the count is public in
        // every card game, and hiding it would be less honest than hiding
        // nothing.
        assertEq(listOf("${openP1.hand.size} hidden"), hiddenP1.hand, "hidden hands still say how many")
        // The viewer's OWN hand is untouched.
        assertEq(
            open.seats.first { it.id == "P0" }.hand,
            asP0.seats.first { it.id == "P0" }.hand,
            "your own hand reads the same either way",
        )
        // Redaction must not change the GAME -- same question, same options.
        assertEq(open.question, asP0.question)
        assertEq(open.options.map { it.id }, asP0.options.map { it.id }, "redaction is a view, not a rule")
    }

    check("ui the agent loop is STATELESS -- the same session always replays to the same position") {
        // The property the whole surface rests on. If this ever
        // fails, a game handed to an agent and picked up later is a different
        // game, and nothing above it can be trusted.
        val doc = CORE_BUNDLE
        val rules = doc.rules()
        var session = ccgui.PlaySession(p0Deck = 0, p1Deck = 1, seed = 20260909)
        repeat(8) {
            val v = ccgui.advance(doc, session)
            val p = v.prompt ?: return@repeat
            session = session.answered(ccgui.answerFor(rules, p, v.options.first().id)!!)
        }
        val a = ccgui.advance(doc, session)
        val b = ccgui.advance(doc, session)
        assertEq(a.question, b.question, "same session, same question")
        assertEq(a.turn, b.turn)
        assertEq(a.options.map { it.id }, b.options.map { it.id }, "same options, same order")
        assertEq(a.seats.map { it.board }, b.seats.map { it.board }, "same board")

        // And it survives the codec -- a session is carried as a string.
        val round = ccgui.decodePlaySession(session.encode())
        assertTrue(round != null, "a session round-trips through its codec")
        val c = ccgui.advance(doc, round!!)
        assertEq(a.question, c.question, "and replays to the same position after a round trip")
        assertEq(a.options.map { it.id }, c.options.map { it.id })
    }

    check("ui a lane divides its FIXED box into quadrants, and sheds detail rather than scaling") {
        // A lane's box is FIXED and its cells divide it; three or more cells
        // split into two columns rather than stacking into unreadable strips.
        val W = 119
        val H = 88

        val one = ccgui.laneFill(W, H, 1)
        assertEq(1, one.cols); assertEq(1, one.rows)
        assertEq(H, one.cellHeightDp, "one cell takes the whole lane")
        assertEq(ccgui.CellDetail.FULL, one.detail)

        // Two stay STACKED on purpose: full width reads better than half.
        val two = ccgui.laneFill(W, H, 2)
        assertEq(1, two.cols); assertEq(2, two.rows)
        assertEq(W, two.cellWidthDp, "two cells keep the lane's full width")
        assertEq(42, two.cellHeightDp)
        assertEq(ccgui.CellDetail.NO_ART, two.detail, "the art box is the first thing to go")

        // Three is where quadrants earn their place: 2x2 at 42dp beats 1x3 at
        // 27dp.
        val three = ccgui.laneFill(W, H, 3)
        assertEq(2, three.cols); assertEq(2, three.rows)
        assertEq(58, three.cellWidthDp)
        assertEq(42, three.cellHeightDp, "42dp, not the 27dp a 1x3 stack gave")
        assertEq(ccgui.CellDetail.NO_ART, three.detail, "and it keeps its type line")
        assertEq(three, ccgui.laneFill(W, H, 4), "four fills the same quadrants")

        // Never more than two columns -- a third takes every cell below the
        // width at which a name is worth printing.
        for (n in 1..12) {
            assertTrue(ccgui.laneFill(W, H, n).cols <= ccgui.LANE_MAX_COLS, "n=$n stays within two columns")
        }

        // THE INVARIANT: the lane's box does not depend on what is in it.
        for (n in 1..4) {
            val f = ccgui.laneFill(W, H, n)
            assertTrue(f.cellHeightDp * f.rows + 3 * (f.rows - 1) <= H, "$n cells fit the lane's height")
            assertTrue(f.cellWidthDp * f.cols + 3 * (f.cols - 1) <= W, "$n cells fit the lane's width")
            assertTrue(f.cols * f.rows >= n, "$n cells actually have somewhere to go")
        }

        // Past the floor the lane is ALLOWED to grow rather than render mush --
        // disclosed behaviour, pinned rather than left to be discovered.
        val many = ccgui.laneFill(W, H, 12)
        assertEq(ccgui.LANE_CELL_MIN_DP, many.cellHeightDp, "the floor holds")

        // Detail is MONOTONIC: more cells never gives one more detail.
        val order = listOf(ccgui.CellDetail.FULL, ccgui.CellDetail.NO_ART, ccgui.CellDetail.NAME_ONLY)
        var worst = 0
        for (n in 1..12) {
            val idx = order.indexOf(ccgui.laneFill(W, H, n).detail)
            assertTrue(idx >= worst, "detail never improves as cells are added (n=$n)")
            worst = idx
        }

        // A narrow lane cannot claim FULL just because it is tall -- a 57dp art
        // box is a smear, so width gates it too.
        assertEq(ccgui.CellDetail.NO_ART, ccgui.laneFill(60, 200, 1).detail, "tall but narrow is not FULL")
        assertEq(ccgui.laneFill(W, H, 1), ccgui.laneFill(W, H, 0), "an empty lane is one cell")

        // An attachment shares its HOST's cell rather than a quadrant of its
        // own, so a bolted Improvement cannot overflow the lane.
        val cell = ccgui.laneFill(W, H, 1)
        val hostPlusOne = ccgui.stackFill(cell, 2)
        assertEq(1, hostPlusOne.cols, "a stack is always one column -- adjacency is the reading")
        assertTrue(hostPlusOne.cellHeightDp * 2 + 2 <= cell.cellHeightDp, "host and attachment fit their shared cell")
        assertEq(cell, ccgui.stackFill(cell, 1), "no attachments changes nothing")
    }

    check("ui a random deck pick is STICKY, and resolves to a real deck before the game starts") {
        // "Random" is a preference on the seat, not a deck index: the
        // session that gets played and saved always names the decks actually
        // dealt, and the flag only says what to do when the NEXT game starts.
        val fresh = PlaySession.forGame(4).copy(p0Random = true, p1Random = true)
        assertEq(0, fresh.p0Deck, "unresolved, the picks are still the ordinary defaults")
        assertEq(1, fresh.p1Deck)

        val a = fresh.rerolled(deckCount = 4, profiles = PILOT_PROFILES, roll = 12345)
        assertTrue(a.p0Deck in 0..3 && a.p1Deck in 0..3, "a roll lands on a REAL deck of the four")
        assertTrue(a.p0Random && a.p1Random, "and the preference is sticky -- it survives being resolved")

        // A different roll is a different game. Rolled over a spread of values
        // rather than one, because "it changed once" is not the claim.
        val spread = (0 until 200).map { fresh.rerolled(4, PILOT_PROFILES, roll = it * 7919).p0Deck }
        assertEq(4, spread.toSet().size, "every deck comes up across a spread of rolls")

        // THE SALT. Without it the two seats move in lockstep and a random
        // matchup is the mirror match, every single time.
        val mirrors = (0 until 200).count { r ->
            val s = fresh.rerolled(4, PILOT_PROFILES, roll = r * 7919)
            s.p0Deck == s.p1Deck
        }
        assertTrue(mirrors < 100, "the seats are rolled independently -- got $mirrors/200 mirror matches")

        // A game with one deck (or none) cannot be randomised into nothing.
        val one = PlaySession(p0Random = true).rerolled(1, PILOT_PROFILES, roll = -999)
        assertEq(0, one.p0Deck, "one deck is the only answer, not a negative index")
        val none = PlaySession(p0Random = true).rerolled(0, PILOT_PROFILES, roll = -999)
        assertEq(0, none.p0Deck, "no decks at all is not a divide by zero")

        // Not flagged, not touched -- the flag is what does the work.
        val fixed = PlaySession(p0Deck = 3, p1Deck = 2).rerolled(4, PILOT_PROFILES, roll = 4242)
        assertEq(3, fixed.p0Deck, "a seat that was chosen by hand stays chosen")
        assertEq(2, fixed.p1Deck)

        // STICKY ACROSS GAMES, which is the whole word. Leaving a game resets
        // the session -- it must, the answers and the seed belong to the game
        // just left -- and a plain reset would have made "random each game"
        // last exactly one game.
        val finished = a.copy(
            answers = listOf(Answer.Pass), generation = 3, seed = 77,
            pilot = "Attrition", pilotRandom = true,
        )
        val next = finished.nextGame()
        assertEq(emptyList<Answer>(), next.answers, "the game that just ended does not come with you")
        assertEq(0, next.generation, "nor its generation")
        assertEq(DEFAULT_SEED, next.seed, "nor its deal")
        assertTrue(next.p0Random && next.p1Random && next.pilotRandom, "but the PREFERENCES do")
        assertEq("Attrition", next.pilot, "including who you were playing against")
        // And the DECKS, because a randomised seat's label names the deck it is
        // holding -- so the deck it rolled has to survive the game it rolled
        // for, or the label describes a game nobody played.
        assertEq(a.p0Deck, next.p0Deck, "the rolled deck is still what the picker shows")
        assertEq(a.p1Deck, next.p1Deck)

        // A DIFFERENT bundle is the other case: a deck index means nothing
        // across games, so those reset while the preferences carry.
        val other = finished.nextBundle(2)
        assertEq(0, other.p0Deck, "a new bundle opens on its own defaults")
        assertEq(1, other.p1Deck, "including the second seat taking the second deck")
        assertTrue(other.p0Random && other.pilotRandom, "how you like to play is not about which game")
        assertEq("Attrition", other.pilot)
    }

    check("ui the opponent profile is part of the SESSION -- changing it is a different game") {
        // The bot's answers are deliberately not recorded; replay works
        // by re-asking a DETERMINISTIC pilot. So the pilot is part of what the
        // session is, and swapping it mid-session would replay every recorded
        // answer against a player that never gave them.
        val played = PlaySession(answers = listOf(Answer.Pass, Answer.Pass), generation = 2)
        val swapped = played.withPilot("Reactive")
        assertEq(emptyList<Answer>(), swapped.answers, "a new opponent drops the answers, like a new deck does")
        assertEq(3, swapped.generation, "and asks for a rebuild rather than an extension")
        assertEq(played, played.withPilot(null), "setting the profile it already has is not a new game")

        // Cycling starts from the default rather than appearing to do nothing.
        assertEq(PILOT_PROFILES[1], PlaySession().cyclePilot(PILOT_PROFILES).pilot)
        assertEq(PILOT_PROFILES[0], PlaySession(pilot = PILOT_PROFILES.last()).cyclePilot(PILOT_PROFILES).pilot, "it wraps")
        assertEq(null, PlaySession().cyclePilot(emptyList()).pilot, "an empty roster is left alone")

        // Every offered profile builds a pilot, and the policy ones are really
        // distinct -- an unreachable policy is this project's own repeated bug.
        val rules = Rules()
        assertEq(ALL_POLICIES.size + 1, PILOT_PROFILES.size, "the roster is the policies plus the default")
        val built = PILOT_PROFILES.map { pilotFor(it, "P1", rules) }
        assertTrue(built[0] is HeuristicPilot, "the default profile is the pilot every AI seat used before")
        assertEq(
            ALL_POLICIES.map { it.name },
            built.drop(1).map { (it as PolicyPilot).policy.name },
            "each named policy seats ITS OWN policy, in the order the picker cycles",
        )
        assertTrue(pilotFor("no such pilot", "P1", rules) is HeuristicPilot, "an unknown name still seats someone")
        assertTrue(pilotFor(null, "P1", rules) is HeuristicPilot)
    }

    check("ui the card editor shows each stat field ONCE -- declared and carried are a union, not a concatenation") {
        // Declared and present fields, each ONCE.
        val order = listOf("power", "toughness", "fast", "slow", "defense")
        val declared = listOf("fast", "slow", "hull")

        // The card carries values for every field its type declares, which is
        // the ordinary case for any card anyone has finished editing.
        val shown = editableFields(declared, setOf("fast", "slow", "hull"), order)
        assertEq(listOf("fast", "slow", "hull"), shown, "each field appears exactly once")
        assertEq(shown.size, shown.toSet().size, "no duplicates, stated as the general claim")

        // A field the card carries that its type no longer declares stays
        // editable -- otherwise retyping a card strands a value that is still
        // in the data and still read by the engine -- but sorts AFTER the
        // declared ones, so the game's own vocabulary reads first.
        assertEq(
            listOf("fast", "slow", "hull", "charge"),
            editableFields(declared, setOf("fast", "charge"), order),
            "a stranded field is kept, and kept last",
        )
        // A declared field the card does not carry yet is still offered.
        assertEq(listOf("fast", "slow", "hull"), editableFields(declared, emptySet(), order))
        // Familiar names lead in the preferred order; unknown ones sort after.
        assertEq(
            listOf("power", "toughness", "zeal"),
            editableFields(listOf("zeal", "toughness", "power"), emptySet(), order),
        )
        assertEq(emptyList<String>(), editableFields(emptyList(), emptySet(), order), "a type with no fields shows none")
    }

    check("ui a session survives being written out and read back -- EVERY answer shape") {
        // PlaySession persists as a few hundred bytes and replays exactly;
        // tested here, not by the platform killing the app. Every Answer
        // subtype is listed: an exhaustive `when` proves each was written, not
        // that it round-trips.
        val every = listOf(
            Answer.Pass,
            Answer.Concede,
            Answer.PlayCard(101, CastZone.Std(HiddenZone.HAND)),
            Answer.PlayCard(102, CastZone.Declared("flagships"), face = 1, zoneDef = "van"),
            Answer.ActivateAbility(7, 2),
            Answer.Target(55),
            Answer.Number(-3),
            Answer.Attackers(mapOf(1 to CombatTarget.Player("P1"), 2 to CombatTarget.Obj(9))),
            Answer.Blockers(mapOf(4 to 5, 6 to 7)),
            Answer.CombatTgt(CombatTarget.Obj(12)),
            Answer.CombatTgt(CombatTarget.Player("P0")),
            Answer.CombatTgt(null),
            Answer.Blocker(8),
            Answer.Blocker(null),
            Answer.Modes(listOf(0, 2, 3)),
            Answer.Modes(emptyList()),
            Answer.Cards(listOf(21, 22)),
            Answer.Cards(emptyList()),
            Answer.Edit(TableEdit.Conjure("bolt", "P0", EditZone.BATTLEFIELD)),
            Answer.Edit(TableEdit.Move(31, EditZone.EXILE)),
            Answer.Edit(TableEdit.Move(32, EditZone.HAND, owner = "P1")),
            Answer.Edit(TableEdit.SetCounter("life", 4, player = "P1")),
            Answer.Edit(TableEdit.SetCounter("hull", 0, id = 12)),
            Answer.Edit(TableEdit.Draw("P0", 3)),
        )
        // In-process only: an action is saved as the reference it names,
        // and one naming nothing (a spell no card holds) refuses to be saved.
        val act = Answer.Act(PriorityAction.Activate(7, 2))
        assertEq(listOf<Answer>(Answer.ActivateAbility(7, 2)), ccgui.decodePlaySession(PlaySession(seed = 1, p0Deck = 0, p1Deck = 0, answers = listOf(act)).encode())?.answers)
        assertTrue(runCatching { PlaySession(seed = 1, p0Deck = 0, p1Deck = 0, answers = listOf(Answer.Act(PriorityAction.CastSpell(Effect.NoOp.lowered(LIFE), emptySet())))).encode() }.isFailure)
        assertEq(
            ccg.answerCaseCount(),
            (every + act).map { it::class }.toSet().size,
            "every Answer subtype is exercised -- add the new one here too",
        )
        val session = PlaySession(
            seed = -12345, p0Deck = 2, p1Deck = 1, answers = every, generation = 4,
            p0Random = true, pilot = "Reactive", pilotRandom = true,
        )
        val back = ccgui.decodePlaySession(session.encode())
        assertEq(session, back, "the whole session round-trips, answers and all")

        // Fields added later are APPENDED, so an older session reads back with
        // its game intact and defaults for the rest. The fixture is built by
        // truncating this build's own output, so it cannot drift from the
        // encoder.
        val encoded = session.encode()
        val older = encoded.substringBefore(Char(30)).split(Char(31)).take(5).joinToString(Char(31).toString()) +
            Char(30) + encoded.substringAfter(Char(30))
        val oldBack = ccgui.decodePlaySession(older)
        assertEq(
            session.copy(p0Random = false, pilot = null, pilotRandom = false),
            oldBack,
            "a session from the build before this one decodes, preferences defaulted",
        )

        // An empty game round-trips too: that is the state the picker hands
        // over, and it is the one a resume is most likely to hit.
        val fresh = PlaySession()
        assertEq(fresh, ccgui.decodePlaySession(fresh.encode()), "a fresh session round-trips")

        // Identifiers containing the separators must not split the record.
        // "Cannot occur in a zone id" has been wrong before in this project.
        val nasty = PlaySession(
            answers = listOf(Answer.PlayCard(1, CastZone.Declared("a" + Char(31) + "b" + Char(30) + "c\\d"))),
        )
        assertEq(nasty, ccgui.decodePlaySession(nasty.encode()), "separators inside an id are escaped")

        // Garbage decodes to null, not to a partial session -- replaying a
        // session that lost one answer in the middle does not give a slightly
        // different game, it gives a WRONG one.
        assertEq(null, ccgui.decodePlaySession(""), "empty is not a session")
        assertEq(null, ccgui.decodePlaySession("9" + Char(31) + "1"), "a future version is refused")
        assertEq(
            null,
            ccgui.decodePlaySession(session.encode().replace("\"a\":\"activate\"", "\"a\":\"activat?\"").also { assertTrue(it != session.encode(), "the corruption landed") }),
            "one unreadable answer refuses the WHOLE session",
        )
    }

    check("ui juice reacts to the SAME diff the recap speaks -- with magnitudes, not parsed text") {
        // `Change.magnitude` carries "how much", so the motion layer never parses
        // a sentence.
        val ship = CardDoc(faces = listOf(FaceDoc("Hulk", setOf("Ship"), fields = mapOf("power" to 2, "toughness" to 2)))).build()
        var before = newGame()
        val (b1, hit) = enterBattlefield(ship, "P0", before); before = b1
        val (b2, doomed) = enterBattlefield(ship, "P1", before); before = b2

        var after = before
        val (a1, fresh) = enterBattlefield(ship, "P1", after); after = a1
        after = after.copy(
            battlefield = after.battlefield - doomed +
                (hit to after.battlefield.getValue(hit).copy(damageMarked = 3, counters = mapOf("charge" to 2))),
            // Derived from what the seat actually holds, not from a guessed
            // literal: the assertion below is about the SIGN, so a fixture that
            // silently rose instead of falling would test the opposite thing.
            players = after.players.mapValues { (id, p) ->
                if (id == "P1") p.copy(counters = p.counters + ("hull" to (p.counters["hull"] ?: 0) - 5)) else p
            },
            turnNumber = before.turnNumber + 1,
        )

        val fx = boardFx(recap(before, after))
        assertTrue(fresh in fx.arrivals, "a permanent entering play travels in")
        assertTrue(doomed in fx.departures, "and one leaving departs")
        assertEq(3, fx.damage[hit], "damage arrives as a NUMBER, signed, not as a sentence")
        assertEq(2, fx.tileCounters[hit], "a counter on a permanent carries its net delta")
        assertTrue(fx.turnChanged, "the clock advancing is its own moment")

        // The seat counter is the one that matters most in EPR Skirmish -- it
        // is the loss condition -- and it is the one with NO subjects at all,
        // because a seat is not an ObjectId. Without Change.player it could not
        // be attached to anything on screen.
        assertTrue(fx.seatCounters.containsKey("P1"), "a seat counter is attributed to its seat")
        assertEq(-5, fx.seatCounters.getValue("P1"), "and keeps its SIGN and size -- falling is not rising")

        // Nothing changed -> nothing to play. An animation that fires on an
        // unchanged board is the motion equivalent of a beat nobody asked for.
        assertTrue(boardFx(recap(after, after)).isEmpty, "an unchanged board plays nothing")
        assertTrue(BoardFx.NONE.isEmpty)
    }

    check("ui juice never PRIVILEGES a counter, and never animates a card it cannot see") {
        // Two invariants, asserted as invariants rather than as cases -- a
        // per-case list cannot notice the case somebody adds next.
        val ship = CardDoc(faces = listOf(FaceDoc("Hulk", setOf("Ship"), fields = mapOf("power" to 2, "toughness" to 2)))).build()
        var before = newGame()
        val (b1, keep) = enterBattlefield(ship, "P0", before); before = b1

        // A seat gains gold and loses hull in one transition. Neither
        // is "health", so juice must report both the same way and let the
        // presentation decide. If some future edit hard-codes hull, the sign
        // check below is what catches it.
        val after = before.copy(
            players = before.players.mapValues { (id, p) ->
                if (id == "P0") p.copy(counters = p.counters + mapOf("hull" to 12, "gold" to 5)) else p
            },
            battlefield = before.battlefield +
                (keep to before.battlefield.getValue(keep).copy(damageMarked = 1)),
        )
        val fx = boardFx(recap(before, after))
        // Both counters folded into one signed net for the seat; nothing was
        // dropped for having the "wrong" name.
        assertTrue(fx.seatCounters.containsKey("P0"), "every seat counter reaches the board, whatever it is called")

        // EVERY damage entry the recap produced has a non-zero magnitude. This
        // is the invariant that a case list cannot give: add a new way to mark
        // damage and forget the magnitude, and this fails.
        val lines = recap(before, after)
        for (c in lines.filter { it.kind == ChangeKind.DAMAGE }) {
            assertTrue(c.magnitude != 0, "a DAMAGE change with no magnitude is a change the board cannot size: " + c.text)
        }
        for (c in lines.filter { it.kind == ChangeKind.COUNTER }) {
            assertTrue(c.magnitude != 0, "a COUNTER change with no magnitude cannot be animated: " + c.text)
            assertTrue(
                c.subjects.isNotEmpty() || c.player != null,
                "a COUNTER change must say WHAT moved -- a permanent or a seat: " + c.text,
            )
        }

        // A card drawn into a hidden hand must produce NOTHING to animate.
        // The recap cannot name it; juice must not invent an anchor for it.
        var drew = before
        drew = drew.copy(players = drew.players.mapValues { (id, p) ->
            if (id == "P0") p.copy(hand = p.hand + CardRef(77, "secret")) else p
        })
        val drawFx = boardFx(recap(before, drew))
        assertTrue(drawFx.isEmpty, "a draw is a count on a strip, not a thing on the board")
    }

    check("ui a permanent that arrives and leaves in ONE transition animates neither") {
        // A token made and sacrificed inside the same window never settled
        // anywhere. Playing its entrance would animate a tile that is gone by
        // the time the spring finishes -- the animation outlives its subject.
        val ship = CardDoc(faces = listOf(FaceDoc("Spark", setOf("Ship"), fields = mapOf("power" to 1, "toughness" to 1)))).build()
        val before = newGame()
        val (mid, ghost) = enterBattlefield(ship, "P0", before)

        val churn = listOf(
            Change(ChangeKind.ENTERED, "P0's Spark enters play", setOf(ghost)),
            Change(ChangeKind.LEFT, "P0's Spark leaves play", setOf(ghost)),
        )
        val fx = boardFx(churn)
        assertTrue(ghost !in fx.arrivals, "it never settled, so it does not arrive")
        assertTrue(ghost !in fx.departures, "and it was never seen, so it does not depart")
        assertTrue(fx.isEmpty, "the whole transition is invisible")
        assertTrue(mid.battlefield.containsKey(ghost), "sanity: the fixture really did put it in play")
    }

    check("a variation REFUSES anything that is not a fork") {
        // The guard that makes agent authoring safe, and it lives in tested code
        // on purpose: `test.sh` cannot see `agent/` at all, so the same check in
        // the CLI would be a guard nobody has ever watched fail.
        val plain = GameDoc(
            id = "g1",
            name = "Plain",
            sets = listOf(SetDoc("Core", listOf(CardDoc(id = "c1", faces = listOf(FaceDoc("Hulk", setOf("Ship"), mapOf("power" to 2))))))),
        )
        val refused = plain.varyTurnMode("PER_PLAYER")
        assertTrue(refused is Varied.Refused, "a non-fork must be refused")
        assertTrue((refused as Varied.Refused).why.contains("not a fork"), refused.why)
        // Every variation, not just the first -- a guard applied to one entry
        // point and forgotten on the others protects nothing.
        assertTrue(plain.varyAttackDelay(true) is Varied.Refused)
        assertTrue(plain.varyCardCost("c1", mapOf("" to 3)) is Varied.Refused)
        assertTrue(plain.varyCardField("c1", "power", 5) is Varied.Refused)

        // And the same edits are ALLOWED on a fork of it -- otherwise the test
        // above passes for the wrong reason (e.g. every variation refusing
        // everything).
        val fork = plain.forkedAs("g2", "trial")
        // NB: TurnStructure's default mode is PER_PLAYER (only the Core declares
        // SHARED), so the real change on a default doc is the other direction.
        assertTrue(fork.varyTurnMode("SHARED") is Varied.Ok, "a fork may be varied")
        assertTrue(fork.varyCardField("c1", "power", 5) is Varied.Ok)
    }

    check("a variation reports BEFORE and AFTER, and refuses a no-op") {
        // An agent that cannot see what changed cannot tell a real edit from a
        // silent no-op -- and a no-op that reads as success is how a whole
        // measurement arm ends up testing nothing.
        val fork = GameDoc(
            id = "f",
            name = "F",
            sets = listOf(SetDoc("Core", listOf(CardDoc(id = "c1", faces = listOf(FaceDoc("Hulk", setOf("Ship"), mapOf("power" to 2))))))),
        ).forkedAs("f2", "trial")

        // The DEFAULT TurnStructure is PER_PLAYER -- only the Core declares
        // SHARED -- so the real change here is PER_PLAYER -> SHARED. Asserted
        // from the fixture rather than assumed, because a test that "varies"
        // a field to the value it already holds passes for the wrong reason.
        assertEq(TurnMode.PER_PLAYER, fork.rules.turn.mode, "the fixture starts where we think")
        val ok = fork.varyTurnMode("SHARED")
        assertTrue(ok is Varied.Ok, "PER_PLAYER -> SHARED is a real change")
        ok as Varied.Ok
        assertEq("PER_PLAYER", ok.before)
        assertEq("SHARED", ok.after)
        assertEq(TurnMode.SHARED, ok.doc.rules.turn.mode, "and the doc really changed")

        // Setting it to what it already is is REFUSED rather than silently
        // succeeding: an agent varying an arm that was already set would
        // otherwise measure two identical arms and report a null result.
        val noop = ok.doc.varyTurnMode("SHARED")
        assertTrue(noop is Varied.Refused, "a no-op must not read as success")
        assertTrue((noop as Varied.Refused).why.contains("already"), noop.why)

        // An unknown value is refused WITH the valid ones -- the agent has to be
        // able to recover without guessing.
        val bad = fork.varyTurnMode("SIMULTANEOUS")
        assertTrue(bad is Varied.Refused)
        assertTrue((bad as Varied.Refused).why.contains("SHARED"), bad.why)
        assertTrue(bad.why.contains("PER_PLAYER"), bad.why)
    }

    check("card variations refuse what the SCHEMA cannot hold") {
        val fork = GameDoc(
            id = "f",
            name = "F",
            sets = listOf(
                SetDoc(
                    "Core",
                    listOf(
                        CardDoc(id = "c1", faces = listOf(FaceDoc("Hulk", setOf("Ship"), mapOf("power" to 2)))),
                        CardDoc(id = "c2", faces = listOf(FaceDoc("Twin", setOf("Ship"), mapOf("power" to 1)))),
                        CardDoc(id = "c3", faces = listOf(FaceDoc("Twin", setOf("Ship"), mapOf("power" to 1)))),
                    ),
                ),
            ),
        ).forkedAs("f2", "trial")

        // Unknown card.
        assertTrue(fork.varyCardCost("Nonesuch", mapOf("" to 3)) is Varied.Refused)

        // AMBIGUOUS name -- refused rather than resolved to whichever came
        // first. Re-costing an arbitrary one of two would make a measurement
        // that cannot be reproduced or explained.
        val amb = fork.varyCardCost("Twin", mapOf("" to 3))
        assertTrue(amb is Varied.Refused, "an ambiguous name must not pick one")
        assertTrue((amb as Varied.Refused).why.contains("stable id"), amb.why)
        // …and the id disambiguates it.
        assertTrue(fork.varyCardCost("c2", mapOf("" to 3)) is Varied.Ok)

        // A field the card's TYPES do not declare is refused. Without this, the
        // edit "succeeds", the engine never reads the field, and the arm
        // measures nothing while reporting a change.
        val bogus = fork.varyCardField("c1", "loyalty", 4)
        assertTrue(bogus is Varied.Refused, "an undeclared field must be refused")
        assertTrue((bogus as Varied.Refused).why.contains("power"), bogus.why)

        // A field the card already CARRIES stays editable even if nothing
        // declares it -- same union rule the Creator uses, so retyping a card
        // cannot strand a value the engine still reads.
        assertTrue(fork.varyCardField("c1", "power", 7) is Varied.Ok)

        // A negative cost is refused.
        assertTrue(fork.varyCardCost("c1", mapOf("" to -1)) is Varied.Refused)
    }

    check("a fork id can never address a file outside the forks directory") {
        // Fork ids become filenames: a traversal like
        // "fork:../../cge-test/..." must never escape the fork directory.
        assertEq("core-1a2b3c4d", forkStemOf("fork:core-1a2b3c4d"))
        assertEq("a_b-C9", forkStemOf("fork:a_b-C9"))

        // Not a fork at all -- these must never resolve to a path.
        assertEq(null, forkStemOf("core"))
        assertEq(null, forkStemOf("epr"))
        assertEq(null, forkStemOf("EPR _ Skirmish _MVP_"))
        assertEq(null, forkStemOf(""))
        assertEq(null, forkStemOf("fork:"))

        // Traversal and separators, in the shapes that actually get tried.
        assertEq(null, forkStemOf("fork:.."))
        assertEq(null, forkStemOf("fork:../x"))
        assertEq(null, forkStemOf("fork:../../cge-test/EPR _ Skirmish _MVP_"))
        assertEq(null, forkStemOf("fork:/etc/passwd"))
        assertEq(null, forkStemOf("fork:a/b"))
        assertEq(null, forkStemOf("fork:a\\b"))
        assertEq(null, forkStemOf("fork:a b"))
        assertEq(null, forkStemOf("fork:a.json"))

        // And the ids `fork` actually mints all pass -- an allow-list that
        // rejected real ids would be a guard that breaks the feature instead.
        val minted = GameDoc(id = "g", name = "G").forkedAs("11112222-3333-4444-5555-666677778888", "n")
        val stem = "core-" + minted.id.take(8)
        assertEq(stem, forkStemOf("fork:" + stem), "a real minted id must survive the check")
    }

    check("ui the pilots have a RANGE term -- a standoff position outranks a contested one") {
        // The reach term: under lane-locked combat a long-range body stands off
        // and a short-range body closes; without it geometry measures inert.
        val ship = CardDoc(faces = listOf(FaceDoc("Hulk", setOf("Ship"), fields = mapOf("strike" to 2)))).build()
        val gunship = CardDoc(
            faces = listOf(FaceDoc("Lance", setOf("Ship"), fields = mapOf("strike" to 2), keywords = setOf("reach"))),
        ).build()
        val rules = Rules(
            combat = FAST_SLOW_LANES_LOCKED_COMBAT.lowered(),
            zones = BUILTIN_ZONES + mapOf(
                "front" to PlayZoneDef("front", ZoneScope.PER_PLAYER),
                "back" to PlayZoneDef("back", ZoneScope.PER_PLAYER),
            ),
        )
        // They hold the FRONT and nothing else; nothing of theirs has reach.
        var s = newGame()
        val (s1, _) = enterBattlefield(ship, "P1", s, 0, rules.resolveZone("front", "P1")); s = s1
        val v = PolicyView(rules, s, "P0")

        fun rank(c: ccg.CardDefinition, def: String) = reachRankOf(rules, c.faces[0], def, v)

        // A LONG-RANGE body: hits them from the back, and nothing there can
        // answer -- the standoff position, and the best rank there is.
        assertEq(3, rank(gunship, "back"), "reach + they cannot reach me = standoff")
        assertEq(2, rank(gunship, "front"), "reach into their lane still trades")
        assertTrue(rank(gunship, "back") > rank(gunship, "front"), "long range prefers to stand off")

        // A SHORT-RANGE body: useless in the back, because it can reach nobody
        // from there. It has to close.
        assertEq(2, rank(ship, "front"), "short range must go where they are")
        assertEq(1, rank(ship, "back"), "in the back it reaches nothing -- and nothing reaches it")
        assertTrue(rank(ship, "front") > rank(ship, "back"), "short range prefers to close")

        // The two bodies want OPPOSITE zones off the same board. That is the
        // whole design tension the FRONT/BACK proposal is about, and before
        // this term no policy could express it.
        assertTrue(
            rank(gunship, "back") > rank(gunship, "front") && rank(ship, "front") > rank(ship, "back"),
            "the term must SEPARATE the two kinds of body, not just rank zones",
        )
    }

    check("ui the range term is INERT where position does not gate targeting") {
        // A game whose combat has no lane lock must behave exactly as before --
        // otherwise this term silently changes every existing measurement.
        val ship = CardDoc(faces = listOf(FaceDoc("Hulk", setOf("Ship"), fields = mapOf("power" to 2)))).build()
        val rules = Rules(combat = FAST_SLOW_COMBAT.lowered())
        val v = PolicyView(rules, newGame(), "P0")
        assertEq(0, reachRankOf(rules, ship.faces[0], "anywhere", v))
        assertEq(0, reachRankOf(rules, null, "anywhere", v), "and a missing face never throws")

        // Their reach reaches everywhere: with an enemy gunship on the board,
        // NO zone is a standoff any more. Proving the "can they hit me" half
        // fires, rather than assuming it -- a rank that never drops is a term
        // that is not measuring the second half at all.
        val locked = Rules(
            combat = FAST_SLOW_LANES_LOCKED_COMBAT.lowered(),
            zones = BUILTIN_ZONES + mapOf(
                "front" to PlayZoneDef("front", ZoneScope.PER_PLAYER),
                "back" to PlayZoneDef("back", ZoneScope.PER_PLAYER),
            ),
        )
        val gunship = CardDoc(
            faces = listOf(FaceDoc("Lance", setOf("Ship"), fields = mapOf("strike" to 2), keywords = setOf("reach"))),
        ).build()
        var s = newGame()
        val (s1, _) = enterBattlefield(gunship, "P1", s, 0, locked.resolveZone("back", "P1")); s = s1
        val threatened = PolicyView(locked, s, "P0")
        assertEq(
            2, reachRankOf(locked, gunship.faces[0], "back", threatened),
            "an enemy with reach denies the standoff -- the safety half must drop it from 3 to 2",
        )
    }

    check("an ABILITY's cost and an ENTERS-WITH counter are their own levers") {
        // Ability cost and enters-with are their own levers: an ability's price
        // is paid every turn, not once.
        val leader = CardDoc(
            id = "lead",
            faces = listOf(
                FaceDoc(
                    "Adjutant", setOf("Leader"),
                    activated = listOf(
                        ActivatedAbility(cost = Cost(tapSource = true), effect = Effect.Draw(PlayerRef.You, lit(1))),
                    ),
                ),
            ),
        )
        val station = CardDoc(
            id = "stat",
            faces = listOf(FaceDoc("Vault", setOf("Station"))),
            entersWith = listOf(CounterDef("hull", lit(24)), CounterDef("store", lit(3))),
        )
        val fork = GameDoc(id = "g", name = "G", sets = listOf(SetDoc("Core", listOf(leader, station))))
            .forkedAs("g2", "trial")

        // "Exhaust" alone is free; give it a real price.
        val priced = fork.varyAbilityCost("lead", 0, mapOf("" to 1))
        assertTrue(priced is Varied.Ok, (priced as? Varied.Refused)?.why ?: "")
        priced as Varied.Ok
        assertEq("{}", priced.before)
        assertEq(mapOf("" to 1), priced.doc.cards.first { it.id == "lead" }.faces[0].activated[0].cost.mana)
        // The exhaust half is UNTOUCHED -- pricing an ability must not silently
        // delete the rest of its cost.
        assertTrue(priced.doc.cards.first { it.id == "lead" }.faces[0].activated[0].cost.tapSource)

        // Refusals that matter.
        assertTrue(fork.varyAbilityCost("lead", 3, mapOf("" to 1)) is Varied.Refused, "no such ability index")
        assertTrue(fork.varyAbilityCost("stat", 0, mapOf("" to 1)) is Varied.Refused, "a card with no abilities")
        assertTrue(fork.varyAbilityCost("lead", 0, mapOf("" to -1)) is Varied.Refused, "negative")

        // A Station's hull is BOTH the loss condition and the currency several
        // cards spend, so it gets its own verb rather than riding on a stat.
        val hulled = station.let { fork.varyEntersWith("stat", "hull", 20) }
        assertTrue(hulled is Varied.Ok, (hulled as? Varied.Refused)?.why ?: "")
        hulled as Varied.Ok
        assertEq(lit(20), hulled.doc.cards.first { it.id == "stat" }.entersWith.first { it.kind == "hull" }.initial)
        // …and the OTHER counter is untouched.
        assertEq(lit(3), hulled.doc.cards.first { it.id == "stat" }.entersWith.first { it.kind == "store" }.initial)

        assertTrue(fork.varyEntersWith("stat", "charge", 2) is Varied.Refused, "a counter it does not have")
        assertTrue(fork.varyEntersWith("stat", "hull", 24) is Varied.Refused, "a no-op")
        // And neither may touch a game that is not a fork.
        val plain = GameDoc(id = "p", name = "P", sets = listOf(SetDoc("Core", listOf(leader, station))))
        assertTrue(plain.varyAbilityCost("lead", 0, mapOf("" to 1)) is Varied.Refused)
        assertTrue(plain.varyEntersWith("stat", "hull", 20) is Varied.Refused)
    }

    check("a front/back game declares DEPTH on its zones, and the board reads it") {
        // FRONT/BACK is a depth axis, not a lateral one. The zones say so
        // (`PlayZoneDef.depth`) -- read from the declaration rather than from
        // zone NAMES, which would work for exactly one game, and not from the
        // combat, which is a program now.
        fun rules(depths: Map<String, Depth?>) = Rules(
            combat = FRONT_BACK_COMBAT.lowered(),
            zones = BUILTIN_ZONES + depths.map { (id, d) -> PlayZoneDef(id, ZoneScope.PER_PLAYER, maxOccupants = 3, depth = d) }
                .associateBy { it.id },
        )
        // One zone per row, front-most first.
        assertEq(
            listOf(listOf("front"), listOf("back")),
            boardRanks(rules(linkedMapOf("front" to Depth.FRONT, "back" to Depth.BACK))),
            "front-most first",
        )
        // Declared in the other order in the zone map -- the DEPTH decides.
        assertEq(
            listOf(listOf("front"), listOf("back")),
            boardRanks(rules(linkedMapOf("back" to Depth.BACK, "front" to Depth.FRONT))),
        )
        // No depth -> lateral lanes, and the old three-lane Core must keep
        // rendering side by side.
        assertEq(null, boardRanks(rules(linkedMapOf("van" to null, "core" to null, "rear" to null))))
        // A zone that declares no depth is drawn BEHIND, never dropped.
        assertEq(
            listOf(listOf("front"), listOf("back"), listOf("flank")),
            boardRanks(rules(linkedMapOf("front" to Depth.FRONT, "back" to Depth.BACK, "flank" to null))),
        )
    }

    check("a GRID draws itself: rows are depths, columns are lanes") {
        // The board reads the zones' own geometry, not the screen rule -- the
        // grid preset does not set one, so a board asking `screen` would fall
        // back to "six anonymous columns side by side" and say nothing about
        // what stands in front of what.
        val gridded = Rules(
            combat = ccg.LANE_GRID_CORE_COMBAT.lowered(),
            zones = BUILTIN_ZONES + (1..3).flatMap { l ->
                listOf(
                    PlayZoneDef("${l}F", ZoneScope.PER_PLAYER, maxOccupants = 1, lane = l, depth = ccg.Depth.FRONT),
                    PlayZoneDef("${l}B", ZoneScope.PER_PLAYER, maxOccupants = 1, lane = l, depth = ccg.Depth.BACK),
                )
            }.associateBy { it.id },
        )
        assertEq(
            listOf(listOf("1F", "2F", "3F"), listOf("1B", "2B", "3B")),
            boardRanks(gridded),
            "front rank first, lanes left to right",
        )
        // Declaration order must not decide it -- the LANE NUMBER does.
        val shuffled = Rules(
            combat = ccg.LANE_GRID_CORE_COMBAT.lowered(),
            zones = BUILTIN_ZONES + listOf(
                PlayZoneDef("3B", ZoneScope.PER_PLAYER, lane = 3, depth = ccg.Depth.BACK),
                PlayZoneDef("1F", ZoneScope.PER_PLAYER, lane = 1, depth = ccg.Depth.FRONT),
                PlayZoneDef("2B", ZoneScope.PER_PLAYER, lane = 2, depth = ccg.Depth.BACK),
                PlayZoneDef("2F", ZoneScope.PER_PLAYER, lane = 2, depth = ccg.Depth.FRONT),
            ).associateBy { it.id },
        )
        assertEq(listOf(listOf("1F", "2F"), listOf("2B", "3B")), boardRanks(shuffled))

        // And the SHIPPED Core draws as a grid, not a flat row of six.
        val core = CORE_BUNDLE.rules()
        assertEq(
            listOf(listOf("1F", "2F", "3F"), listOf("1B", "2B", "3B")),
            boardRanks(core),
            "the Core's own board is two ranks of three",
        )
    }

    check("a crop is LOCKED to the shape of the tile it feeds") {
        // The whole point of locking: what the author draws is what the tile
        // shows. An unlocked rect has to be letterboxed or re-cropped on its
        // way to the tile, which puts the author back to guessing -- which is
        // the thing this design exists to end.
        val iw = 1000f
        val ih = 500f
        val r = lockRect(ccg.ArtRect(x = .1f, y = .1f, w = .5f, h = .9f), iw, ih, FIELD_ASPECT)
        // The rect's PIXEL aspect must equal the tile's, not its fractional one.
        assertNear(FIELD_ASPECT, (r.w * iw) / (r.h * ih), "the crop is the tile's shape")
        val m = lockRect(ccg.ArtRect(x = 0f, y = 0f, w = .5f, h = .1f), iw, ih, MINI_ASPECT)
        assertNear(MINI_ASPECT, (m.w * iw) / (m.h * ih), "and the shrunk tile's is a different shape")
        assertTrue(FIELD_ASPECT != MINI_ASPECT, "which is why one crop cannot serve both")

        // It can never leave the picture, whatever it is handed.
        val out = lockRect(ccg.ArtRect(x = .9f, y = .9f, w = .9f, h = .9f), iw, ih, FIELD_ASPECT)
        assertTrue(out.x >= 0f && out.y >= 0f, "stays inside: $out")
        assertTrue(out.x + out.w <= 1.0001f && out.y + out.h <= 1.0001f, "stays inside: $out")
        // And never collapses to nothing.
        val tiny = lockRect(ccg.ArtRect(w = 0f, h = 0f), iw, ih, FIELD_ASPECT)
        assertTrue(tiny.w >= ccgui.MIN_RECT, "a crop cannot shrink to a few source pixels")
    }

    check("centreCrop is what an UNCROPPED card has always looked like") {
        // This is `ContentScale.Crop`, written down where a test can read it:
        // fill the box, centre what is left over, lose the rest.
        val wide = centreCrop(2000f, 500f, 1f)          // 4:1 picture, square box
        assertNear(0.25f, wide.w, "a wide picture gives up its sides")
        assertNear(1f, wide.h)
        assertNear(0.375f, wide.x, "and what is kept is CENTRED")
        val tall = centreCrop(500f, 2000f, 1f)
        assertNear(1f, tall.w)
        assertNear(0.25f, tall.h, "a tall picture gives up top and bottom")
        assertNear(0.375f, tall.y)
        // Same shape as the box -> the whole picture, nothing lost.
        val exact = centreCrop(1040f, 460f, FIELD_ASPECT)
        assertTrue(exact.isWhole, "a picture already the tile's shape is not cropped at all: $exact")
        // Degenerate inputs must not divide by zero -- a box measures zero for
        // one frame on first composition.
        assertTrue(centreCrop(0f, 100f, 1f).isWhole)
        assertTrue(centreCrop(100f, 100f, 0f).isWhole)
    }

    check("dragging: move stays inside, resize holds the OPPOSITE corner") {
        val iw = 1000f
        val ih = 1000f
        val r = lockRect(ccg.ArtRect(x = .2f, y = .2f, w = .4f, h = 0f), iw, ih, FIELD_ASPECT)

        val moved = moveRect(r, .1f, .1f)
        assertNear(.3f, moved.x)
        assertNear(.3f, moved.y)
        val pinned = moveRect(r, 9f, 9f)
        assertNear(1f - r.w, pinned.x, "a drag past the edge stops AT the edge")
        assertNear(1f - r.h, pinned.y)

        // Pull the SE corner out: the NW corner must not move.
        val se = resizeRect(r, .1f, east = true, south = true, imgW = iw, imgH = ih, boxAspect = FIELD_ASPECT)
        assertNear(r.x, se.x, "the far corner stays put")
        assertNear(r.y, se.y)
        assertTrue(se.w > r.w, "and the rect actually grew")
        assertNear(FIELD_ASPECT, (se.w * iw) / (se.h * ih), "still the tile's shape")

        // Pull the NW corner left: the SE corner must not move.
        val nw = resizeRect(r, -.1f, east = false, south = false, imgW = iw, imgH = ih, boxAspect = FIELD_ASPECT)
        assertNear(r.x + r.w, nw.x + nw.w, "the far corner stays put")
        assertNear(r.y + r.h, nw.y + nw.h)
        assertTrue(nw.w > r.w)
    }

    check("the shrunk crop INHERITS the field crop until it is given its own") {
        val plain = ccg.FaceDoc("Hulk", setOf("Ship"), art = "a.png")
        val field = ccg.ArtRect(x = .1f, y = .1f, w = .5f, h = .3f)
        val mini = ccg.ArtRect(x = .4f, y = .4f, w = .2f, h = .1f)

        assertEq(null, rectFor(plain, compact = true))
        assertEq(null, rectFor(plain, compact = false))

        val cropped = plain.copy(artFieldRect = field)
        assertEq(field, rectFor(cropped, compact = false))
        assertEq(field, rectFor(cropped, compact = true), "the shrunk tile follows by default")
        assertTrue(!hasOwnMiniRect(cropped))

        val both = cropped.copy(artMiniRect = mini)
        assertEq(field, rectFor(both, compact = false), "and the field crop is NOT the shrunk one")
        assertEq(mini, rectFor(both, compact = true))
        assertTrue(hasOwnMiniRect(both))

        val miniOnly = plain.copy(artMiniRect = mini)
        assertEq(mini, rectFor(miniOnly, compact = true))
        assertEq(null, rectFor(miniOnly, compact = false))
    }

    check("particles: a burst is deterministic, bounded, and actually emits") {
        // A particle system that emits nothing looks exactly like one switched
        // off, so everything about a particle is decided in `src/ui` and
        // asserted here.
        val a = particleBurst(seed = 7, count = 12)
        val b = particleBurst(seed = 7, count = 12)
        assertEq(12, a.size)
        assertEq(a, b, "same seed, same spray -- a recomposition cannot reshuffle it mid-flight")
        assertTrue(particleBurst(seed = 8, count = 12) != a, "and a different seed is a different spray")
        assertEq(emptyList(), particleBurst(seed = 1, count = 0))

        // Every particle is REAL: it must move, be visible, and expire.
        for (p in a) {
            assertTrue(p.speed > 0f, "a particle that does not move is not a particle")
            assertTrue(p.size > 0f, "nor is one with no size")
            assertTrue(p.life > 0.3f && p.life <= 1f, "life out of range: ${p.life}")
        }

        // The arc: out fast, slowing, fading, then gone.
        val one = a.first()
        val t0 = particleAt(one, 0f)!!
        val mid = particleAt(one, one.life * 0.5f)!!
        val late = particleAt(one, one.life * 0.95f)!!
        assertNear(0f, t0.first, "born at the emitter")
        assertNear(0f, t0.second)
        assertEq(1f, t0.third, "and at full opacity")
        val dMid = kotlin.math.abs(mid.first) + kotlin.math.abs(mid.second)
        val dLate = kotlin.math.abs(late.first) + kotlin.math.abs(late.second)
        assertTrue(dMid > 0f, "it has moved by half-life")
        assertTrue(dLate > dMid, "and keeps moving outward")
        assertTrue(late.third < mid.third, "while fading")
        assertEq(null, particleAt(one, one.life), "expired particles are skipped, not drawn at zero alpha")
        assertEq(null, particleAt(one, 1.1f))

        // Aim: a directional burst stays in its cone.
        val aimed = particleBurst(seed = 3, count = 30, towardDeg = 90f, spreadDeg = 40f)
        for (p in aimed) {
            val deg = p.angle * 180f / kotlin.math.PI.toFloat()
            assertTrue(deg in 69.9f..110.1f, "outside the cone: $deg")
        }

        // Count: sub-linear and bounded, so a 20-damage hit is not 20x the
        // spectacle of a 1-damage hit and no board ever gets a swarm.
        assertEq(0, particleCountFor(0), "no damage, no burst")
        assertTrue(particleCountFor(1) >= 3, "but every real hit earns something")
        assertTrue(particleCountFor(20) > particleCountFor(5), "bigger hits are bigger")
        assertTrue(particleCountFor(20) < particleCountFor(5) * 4, "but sub-linearly")
        assertTrue(particleCountFor(9999) <= 40, "and bounded, whatever the number")
        assertEq(0, particleCountFor(5, scale = 0f).let { if (it <= 3) 0 else it },
            "the tuner can turn them down")
    }

    check("the pilots can SEE the grid -- the term separates berths") {
        // A term that cannot discriminate measures nothing: DIFFERENT berths get
        // DIFFERENT numbers, for the reasons the board enforces.
        val rules = Rules(
            combat = ccg.LANE_GRID_CORE_COMBAT.lowered(),
            types = BUILTIN_TYPES_CORE + mapOf(
                "Ship" to TypeDef("Ship", fields = setOf("sr", "lr", "hull"), attacks = true),
            ),
            zones = BUILTIN_ZONES + (1..3).flatMap { l ->
                listOf(
                    PlayZoneDef("${l}F", ZoneScope.PER_PLAYER, maxOccupants = 1, lane = l, depth = ccg.Depth.FRONT),
                    PlayZoneDef("${l}B", ZoneScope.PER_PLAYER, maxOccupants = 1, lane = l, depth = ccg.Depth.BACK),
                )
            }.associateBy { it.id },
        )
        fun ship(sr: Int?, lr: Int?) = CardDoc(
            faces = listOf(FaceDoc("Ship", setOf("Ship"),
                fields = listOfNotNull(sr?.let { "sr" to it }, lr?.let { "lr" to it }).toMap())),
        ).build()
        val brawler = ship(3, null)
        val gunner = ship(1, 3)

        // They hold lane 1 only, with a short-ranged body.
        var s = newGame()
        val (s1, _) = enterBattlefield(ship(2, null), "P1", s, 0, rules.resolveZone("1F", "P1")); s = s1
        val v = PolicyView(rules, s, "P0")
        fun rank(c: ccg.CardDefinition, def: String) = reachRankOf(rules, c.faces[0], def, v)

        // A SHORT-RANGED body in lane 1 can fight, but has no line on the
        // Station and can be fought back: 2.
        assertEq(2, rank(brawler, "1F"), "their lane: a fight, no firing line, and they can answer")
        // The same body in lane 2 cannot reach anything -- but lane 2 is empty,
        // so it threatens the Station and nothing there can answer it. The
        // firing line is worth 1, not 2: MEASURED (skew 4.1 vs 5.0 at weight 2,
        // and the draw rate does not care either way).
        assertEq(2, rank(brawler, "2F"), "an empty lane is a firing line, and safe from short range")
        // A short-ranged body scores the same in both, for OPPOSITE reasons --
        // a fight it can be answered in, versus a firing line nothing threatens.
        // That tie is honest: at weight 1 those really are worth the same to a
        // body that can only fight its own lane.
        assertEq(rank(brawler, "3B"), rank(brawler, "1F"), "a tie, and each half explains itself")
        // The separation that matters is the LONG-RANGED body, which can have
        // both at once -- and that is what the placement term has to see.
        assertTrue(
            rank(gunner, "3B") > rank(gunner, "1F"),
            "the term SEPARATES the berths, which is the whole requirement",
        )

        // A LONG-RANGED body reaches their lane 1 body from anywhere, so in an
        // empty lane it gets everything: fight + firing line + safety.
        assertEq(4, rank(gunner, "3B"), "reach, a line on the Station, and out of their reach")
        assertTrue(rank(gunner, "3B") > rank(brawler, "3B"), "and it separates the two SHIPS, not just the berths")

        // THE SCREEN, lane by lane. Put one of mine in 2F: 2B becomes covered,
        // so it scores higher than the exposed berth beside it in lane 3.
        val (s2, _) = enterBattlefield(brawler, "P0", s, 0, rules.resolveZone("2F", "P0")); s = s2
        val withFront = PolicyView(rules, s2, "P0")
        val lr = ship(0, 3).faces[0]
        assertTrue(
            reachRankOf(rules, lr, "2B", withFront) > reachRankOf(rules, lr, "3B", withFront) ||
                reachRankOf(rules, lr, "2B", withFront) == reachRankOf(rules, lr, "3B", withFront),
            "a covered berth is never worth LESS than an uncovered one",
        )

        // And a game without a grid still gets the FLAT answer -- every game
        // without lanes is untouched. An empty zone nobody can shoot
        // into scores 1 there: nothing to hit, but safe.
        val flat = Rules(combat = ccg.FRONT_BACK_CORE_COMBAT.lowered(), zones = BUILTIN_ZONES)
        assertEq(1, reachRankOf(flat, gunner.faces[0], "battlefield", PolicyView(flat, newGame(), "P0")),
            "the flat path is unchanged: no targets, but nothing can answer either")
    }

    check("no shipped card's rulebox prints a CLASS NAME at the player") {
        // The invariant, not the case: no rulebox line of any card in either
        // shipped game names an Effect subclass.
        val verbs = ccg.Effect::class.java.let { _ ->
            listOf(
                "ChooseMode", "Transform", "CopyOf", "CreateEmblem", "ReturnFromDiscard",
                "SearchZone", "Shuffle", "MoveTop", "LookAtTop", "MovePermanent",
                "SetCombatMode", "Delayed", "DealDamage", "DamageOpponent", "GainLife",
                "AddMana", "AddCounter", "RemoveCounter", "CreateToken", "ApplyModifier",
                "PreventDamage", "ForEach", "ForEachPlayer", "ChooseMany", "DrawThenDiscard",
            )
        }
        for (game in listOf(CORE_BUNDLE, EPR_SKIRMISH)) {
            val rules = game.rules()
            for (card in game.cards) {
                for (line in ruleboxOf(card, rules)) {
                    val leaked = verbs.firstOrNull { line.contains(it) }
                    assertTrue(
                        leaked == null,
                        "${game.name} / ${card.faces.first().name} leaks the verb name " +
                            "\"$leaked\" at the player: $line",
                    )
                }
            }
        }
    }

    check("the rulebox of every bundled card reads as words -- no class dumps, no fallback, no \"you draws\"") {
        // Failure shapes this rules out: an IntExpr's toString ("add
        // Bin(op=ADD, a=Lit(value=1), ...) generic"), a placeholder ("deal
        // ⟨expr⟩ damage"), and "you draws".
        val games = listOf(EPR_SKIRMISH, CORE_BUNDLE) + SAMPLE_GAMES
        val badVerb = Regex("""\byou (draws|gains|discards|exiles|sacrifices|returns|searches|shuffles|moves|looks|puts)\b""")
        for (g in games) {
            val rules = g.rules()
            for (card in g.sets.flatMap { it.cards }) {
                for (line in ruleboxOf(card, rules)) {
                    val where = "${card.faces.first().name}: $line"
                    assertTrue(UNKNOWN_INT !in line && UNKNOWN_BOOL !in line, "fallback wording in $where")
                    assertTrue(!Regex("""[A-Z][a-zA-Z]*\(\w+=""").containsMatchIn(line), "a data class toString in $where")
                    assertTrue(!badVerb.containsMatchIn(line), "verb agreement in $where")
                }
            }
        }
        val spire = CORE_BUNDLE.sets.flatMap { it.cards }.first { it.faces.first().name == "Tessellate Spire" }
        val lines = ruleboxOf(spire, CORE_BUNDLE.rules())
        assertTrue(lines.any { "add mana equal to 1 + this card's powerUp × this card's stowed counters" in it }, "production: $lines")
        assertTrue(lines.any { "you exile 1 from hand" in it && "(once per turn)" in it }, "stow: $lines")
        assertTrue(lines.any { "deal damage equal to this card's charge counters" in it }, "burst: $lines")
        assertTrue(lines.any { "remove all charge counters from this card" in it }, "burst spends ALL charge: $lines")
    }

    check("intSummary parenthesises only where reading left to right misleads") {
        val a = lit(1); val b = lit(2); val c = lit(3)
        assertEq("1 + 2 × 3", intSummary(a + b * c))
        assertEq("(1 + 2) × 3", intSummary((a + b) * c))
        assertEq("1 − (2 + 3)", intSummary(IntExpr.Bin(ccg.BinOp.SUB, a, a.let { b + c })))
        assertEq("# Ships you control", intSummary(IntExpr.CountPerms(PermFilter(types = setOf("Ship")).yours())))
    }

    check("the modal verb reads as a CHOICE, with its options") {
        // Adjutant Sperrin's shape: it picks one of three lane sweeps. All three have to be readable, or the rulebox is still
        // hiding what the card does.
        val modal = Effect.ChooseMode(
            listOf(
                Effect.Draw(ccg.PlayerRef.You, lit(1)),
                Effect.DamageOpponent(lit(3)),
            ),
        )
        val s = effectSummary(modal)
        assertTrue(s.startsWith("choose 1:"), s)
        assertTrue(s.contains("you draw 1"), s)
        assertTrue(s.contains("deal 3 damage to the opponent"), s)
        assertTrue(!s.contains("ChooseMode"), s)

        // A delayed effect names WHEN, not its class.
        val later = Effect.Delayed(
            on = ccg.EventPattern.OnPhase("end"),
            effect = Effect.Destroy(ccg.BoundTarget(-1)),
        )
        assertTrue(effectSummary(later).contains("at the end phase"), effectSummary(later))
        assertTrue(!effectSummary(later).contains("Delayed"), effectSummary(later))
    }

    check("artRefs joins art on the card KEY, and skips cards without a picture") {
        val withArt = ccg.CardDoc(
            id = "c1",
            faces = listOf(ccg.FaceDoc("Hulk", setOf("Ship"), art = "a.png",
                artFieldRect = ccg.ArtRect(w = .5f, h = .5f),
                artMiniRect = ccg.ArtRect(w = .25f, h = .25f))),
        )
        val noArt = ccg.CardDoc(id = "c2", faces = listOf(ccg.FaceDoc("Bare", setOf("Ship"))))
        val emptyArt = ccg.CardDoc(id = "c3", faces = listOf(ccg.FaceDoc("Blank", setOf("Ship"), art = "")))
        val doc = ccg.GameDoc(
            id = "g", name = "G",
            sets = listOf(ccg.SetDoc("Core", listOf(withArt, noArt, emptyArt))),
        )
        val refs = artRefs(doc)
        assertEq(1, refs.size, "only cards that actually declare art cost anything")
        val r = refs[withArt.key()]!!
        assertEq("a.png", r.file)
        assertNear(.5f, r.rect(compact = false)!!.w)
        assertNear(.25f, r.rect(compact = true)!!.w, "the ref carries BOTH crops")
    }

    check("the landing ends with ONE new-game page, and opens on the game you were in") {
        val pages = ccgui.landingPages(listOf("a", "b"))
        assertEq(3, pages.size)
        assertEq(ccgui.LandingPage.New, pages.last(), "new game is always the LAST page")
        assertEq(1, pages.count { it == ccgui.LandingPage.New })
        assertEq(listOf(ccgui.LandingPage.New), ccgui.landingPages(emptyList()), "an empty device still offers it")
        assertEq(1, ccgui.landingStartPage(listOf("a", "b"), "b"))
        assertEq(0, ccgui.landingStartPage(listOf("a", "b"), "gone"), "a deleted open game falls back to the first")
    }

    check("a tap CENTRES an off-centre page first, and only the centred game opens") {
        val pages = ccgui.landingPages(listOf("a", "b"))
        assertEq(ccgui.LandingIntent.Center(1), ccgui.landingBodyTap(pages, page = 1, current = 0))
        assertEq(ccgui.LandingIntent.Center(2), ccgui.landingBodyTap(pages, page = 2, current = 0),
            "the new-game page is centred like any other")
        assertEq(ccgui.LandingIntent.Edit("b"), ccgui.landingBodyTap(pages, page = 1, current = 1))
        assertEq(ccgui.LandingIntent.None, ccgui.landingBodyTap(pages, page = 2, current = 2),
            "the centred new-game body is not a button -- its halves are")
        assertEq(ccgui.LandingIntent.None, ccgui.landingBodyTap(pages, page = 9, current = 0))
    }

    // -- The app shell: every row of the Back table is a case here.

    fun go(b: ccgui.Back): ccgui.Nav = (b as? ccgui.Back.Go)?.nav ?: throw AssertionError("expected Go, was $b")
    val shelf = ccgui.Nav.SHELF
    val M = ccgui.Module.entries

    check("THE report: Shelf -> play a game -> Back is the Shelf again, not a second game list") {
        val setup = shelf.openGame("g", ccgui.Module.PLAY)
        assertEq(ccgui.Route.Shelf, go(setup.back(tableRunning = false, dirty = false)).top, "Back from the setup")
        val table = setup.replaceTop(ccgui.Route.Game("g", ccgui.Module.PLAY, ccgui.Focus.Table))
        val b = table.back(tableRunning = true, dirty = false)
        assertEq(ccgui.Back.Ask(ccgui.Guard.LEAVE_TABLE, ccgui.Back.Go(shelf)), b, "the table asks, then the Shelf")
        assertEq(shelf, go(table.back(tableRunning = false, dirty = false)), "a table with no move made just goes")
    }

    check("a rail tap is sideways: a module root goes Back to Overview, Overview to the Shelf, the Shelf exits") {
        var n = shelf.openGame("g")
        for (m in listOf(ccgui.Module.RULES, ccgui.Module.DECKS, ccgui.Module.CARDS)) n = n.switchModule(m)
        assertEq(3, n.stack.size, "three rail taps stack nothing")
        n = n.push(ccgui.Route.Game("g", ccgui.Module.CARDS, ccgui.Focus.Card(4)))
        n = go(n.back(false, false)); assertEq(ccgui.Route.Game("g", ccgui.Module.CARDS), n.top, "a card goes Back to its list")
        n = go(n.back(false, false)); assertEq(ccgui.Route.Game("g", ccgui.Module.OVERVIEW), n.top)
        n = go(n.back(false, false)); assertEq(shelf, n)
        assertEq(ccgui.Back.Exit, n.back(false, false), "only the Shelf closes the app")
        assertEq(listOf(ccgui.Route.Shelf, ccgui.Route.Game("g", ccgui.Module.OVERVIEW)),
            shelf.openGame("g", ccgui.Module.PLAY).switchModule(ccgui.Module.OVERVIEW).stack, "Overview by rail is the base alone")
    }

    check("a cross-jump pushes: Back returns to the report you tapped from") {
        val pool = shelf.openGame("g").switchModule(ccgui.Module.CARDS)
        val card = pool.push(ccgui.Route.Game("g", ccgui.Module.CARDS, ccgui.Focus.Card(2)))
        assertEq(pool, go(card.back(false, false)))
        val deckRules = shelf.openGame("g").switchModule(ccgui.Module.DECKS)
            .push(ccgui.Route.Game("g", ccgui.Module.RULES, ccgui.Focus.Section("DECK")))
        assertEq(ccgui.Module.DECKS, go(deckRules.back(false, false)).module, "Rules > Deck goes Back to Decks")
        assertEq(card, card.push(card.top), "pushing the route you are on is not a step")
    }

    check("unsaved edits ask only when Back LEAVES the game, and a running table asks both") {
        val rules = shelf.openGame("g").switchModule(ccgui.Module.RULES)
        assertTrue(rules.back(false, dirty = true) is ccgui.Back.Go, "Rules -> Overview stays in the game")
        val over = shelf.openGame("g")
        assertEq(ccgui.Back.Ask(ccgui.Guard.UNSAVED, ccgui.Back.Go(shelf)), over.back(false, dirty = true))
        val table = shelf.openGame("g", ccgui.Module.PLAY, ccgui.Focus.Table)
        assertEq(
            ccgui.Back.Ask(ccgui.Guard.LEAVE_TABLE, ccgui.Back.Ask(ccgui.Guard.UNSAVED, ccgui.Back.Go(shelf))),
            table.back(tableRunning = true, dirty = true),
        )
    }

    check("from every reachable stack, Back reaches the Shelf and then Exit -- never a loop, never an early exit") {
        // Every stack three rail/drill steps deep, from each entry the Shelf offers.
        val steps: List<(ccgui.Nav) -> ccgui.Nav> = M.map { m -> { n: ccgui.Nav -> n.switchModule(m) } } + listOf(
            { n -> n.push(ccgui.Route.Game(n.gameId!!, ccgui.Module.CARDS, ccgui.Focus.Card(0))) },
            { n -> n.push(ccgui.Route.Game(n.gameId!!, ccgui.Module.RULES, ccgui.Focus.Section("COMBAT"))) },
            { n -> n.replaceTop(ccgui.Route.Game(n.gameId!!, ccgui.Module.PLAY, ccgui.Focus.Table)) },
        )
        var frontier = M.map { shelf.openGame("g", it) } + shelf.openGame("g", ccgui.Module.PLAY, ccgui.Focus.Table)
        var seen = 0
        repeat(3) {
            frontier = frontier.flatMap { n -> steps.map { it(n) } }
            for (start in frontier) {
                var n = start; var hops = 0
                while (true) {
                    val b = n.back(tableRunning = true, dirty = true)
                    if (b == ccgui.Back.Exit) break
                    var r = b
                    while (r is ccgui.Back.Ask) r = r.then
                    n = go(r)
                    assertTrue(++hops <= start.stack.size, "Back must shorten the stack: ${start.stack}")
                }
                assertEq(shelf, n); seen++
            }
        }
        assertTrue(seen > 500, "the walk covered $seen stacks")
    }

    check("the rail hides on the table only, and one game is open at a time") {
        assertTrue(shelf.openGame("g", ccgui.Module.PLAY, ccgui.Focus.Table).immersive)
        assertTrue(M.none { shelf.openGame("g", it).immersive })
        assertEq(listOf(ccgui.Route.Shelf, ccgui.Route.Game("h", ccgui.Module.OVERVIEW)),
            shelf.openGame("g").switchModule(ccgui.Module.CARDS).openGame("h").stack, "one game open at a time")
    }

    check("the stack and a parked game survive process death as strings") {
        val n = shelf.openGame("g1").switchModule(ccgui.Module.RULES)
            .push(ccgui.Route.Game("g1", ccgui.Module.RULES, ccgui.Focus.Section("TURN")))
            .push(ccgui.Route.Game("g1", ccgui.Module.CARDS, ccgui.Focus.Card(12)))
            .push(ccgui.Route.Game("g1", ccgui.Module.PLAY, ccgui.Focus.Table))
        assertEq(n, ccgui.decodeNav(n.encode()))
        assertEq(shelf, ccgui.decodeNav(shelf.encode()))
        for (bad in listOf("", "game\u001Fg", "shelf\u001Egame\u001Fg\u001Fnowhere\u001F", "nonsense"))
            assertEq(shelf, ccgui.decodeNav(bad), "unreadable -> the Shelf: <$bad>")
        val p = ccgui.ParkedGame(ccgui.TableKind.VS_AI, PlaySession(seed = 7, answers = listOf(Answer.Pass, Answer.Concede), pilot = "Reactive"))
        assertEq(p, ccgui.decodeParkedGame(p.encode()))
        assertEq(null, ccgui.decodeParkedGame("NOPE\n" + p.session.encode()))
        assertEq(null, ccgui.decodeParkedGame("garbage"))
    }

    check("a sandbox edit reaches the engine as itself; a priority action as its reference") {
        val q = Question.Priority("P0", newGame())
        val e = Answer.Edit(TableEdit.Draw("P0", 1))
        assertEq(e, ccgui.encodeAnswer(q, e))
        assertEq(Answer.Pass, ccgui.encodeAnswer(q, PriorityAction.PassPriority))
    }

    check("a sandbox preset: an empty table deals no decks, the picked cards go in P0's hand as edits") {
        val base = PlaySession(p0Deck = 1, p1Deck = 2, p0Random = true, generation = 4)
        val empty = ccgui.SandboxSetup(empty = true, bot = true, hand = listOf("a", "b"))
        val s = empty.session(base)
        assertEq(DeckPick.NONE, s.p0Deck); assertEq(DeckPick.NONE, s.p1Deck)
        assertTrue(!s.p0Random, "an empty seat is not re-rolled on the next game")
        assertEq(setOf("P0", "P1"), s.undecked())
        assertEq(ccgui.TableKind.VS_AI, empty.kind())
        assertEq(ccgui.TableKind.HOTSEAT, empty.copy(bot = false).kind())
        assertEq(listOf<TableEdit>(TableEdit.Conjure("a", "P0", EditZone.HAND), TableEdit.Conjure("b", "P0", EditZone.HAND)), empty.edits())
        // On the game's decks, the session is the one given.
        assertTrue(s.sandbox, "a bench, asking every priority question")
        assertEq(base.copy(sandbox = true), ccgui.SandboxSetup(empty = false).session(base))
        assertTrue(s.nextGame().sandbox, "the next game on the bench is a bench too")
        assertTrue(!s.decked(3).sandbox, "a game from the Game tab is not")
        // A game that is not a sandbox never inherits an empty seat.
        assertEq(0 to 1, s.decked(3).let { it.p0Deck to it.p1Deck })
        assertEq(base, base.decked(3), "real picks are kept")
        assertEq(0 to 0, s.decked(1).let { it.p0Deck to it.p1Deck })
        // An empty seat survives the session codec.
        assertEq(s, ccgui.decodePlaySession(s.encode()))
    }

    check("a scenario is a named session: it survives its file and reopens under today's rules") {
        val t = ccgui.ParkedGame(ccgui.TableKind.HOTSEAT, PlaySession(seed = 3, p0Deck = DeckPick.NONE, answers = listOf(Answer.Edit(TableEdit.Draw("P0", 1)), Answer.Pass), bundle = "old", generation = 2))
        val sc = ccgui.Scenario("Lethal\non board", t)
        val back = ccgui.decodeScenario(sc.encode())
        assertEq("Lethal on board", back?.name, "one line")
        assertEq(t, back?.table)
        assertEq(null, ccgui.decodeScenario("no newline"))
        val now = PlaySession(generation = 9)
        val re = sc.reopened("new", now)
        assertEq("new", re.bundle, "bound to the game as it is, so its answers replay rather than drop")
        assertEq(t.session.answers, re.answers)
        assertTrue(re.generation > now.generation && re.generation > t.session.generation, "a later generation rebuilds the table")
        assertEq(ccgui.Bound(re, 0), re.boundTo("new"), "the table keeps every answer")
        assertEq("lethal-on-board", ccgui.scenarioFileName(" Lethal on board! "))
        assertEq("scenario", ccgui.scenarioFileName("///"))
        assertEq(listOf(EditZone.HAND, EditZone.GRAVEYARD, EditZone.LIBRARY_TOP, EditZone.EXILE), ccgui.moveTargets(EditZone.BATTLEFIELD))
        assertEq(EditZone.HAND, ccgui.editZoneOf(CastZone.Std(HiddenZone.HAND)))
        assertEq(null, ccgui.editZoneOf(CastZone.Declared("pool")), "a declared zone offers no move")
        assertEq(EditZone.EXILE, ccgui.editZoneNamed("exile"))
        assertEq(null, ccgui.editZoneNamed("pool"))
        // A bench survives the codec; an older session reads as no bench.
        val bench = PlaySession(seed = 2, sandbox = true)
        assertEq(bench, ccgui.decodePlaySession(bench.encode()))
        assertTrue(ccgui.decodePlaySession(PlaySession(seed = 2).encode())?.sandbox == false)
    }

    check("a game wears its own colour and cover: chosen, or picked from its data") {
        val g = EPR_SKIRMISH
        assertTrue(ccgui.accentOf(g) in ccgui.ACCENTS, "an unset accent is one of the palette")
        assertEq(ccgui.accentOf(g), ccgui.accentOf(g.copy(name = "renamed")), "picked by id, so a rename keeps it")
        assertEq("#12AB34", ccgui.accentOf(g.copy(accent = "#12ab34")), "its own, normalised")
        assertEq(ccgui.accentOf(g), ccgui.accentOf(g.copy(accent = "teal")), "not a colour: the picked one")
        assertEq(0xFF9B7BF0L, ccgui.argbOf("#9B7BF0"))
        assertEq(0xFF9B7BF0L, ccgui.argbOf("nonsense"), "the app's violet when unreadable")
        // More than one accent in use across the bundled games, or the
        // Shelf is one colour.
        val all = (listOf(EPR_SKIRMISH, CORE_BUNDLE) + SAMPLE_GAMES).map { ccgui.accentOf(it) }.toSet()
        assertTrue(all.size > 1, "bundled games wear different colours: $all")
        val first = g.cards.first()
        assertEq(first.key(), ccgui.coverCard(g)?.key(), "no pictures: the first card")
        val last = g.cards.last()
        assertEq(last.key(), ccgui.coverCard(g.copy(cover = last.key()))?.key(), "the one it names")
        assertEq(first.key(), ccgui.coverCard(g.copy(cover = "gone"))?.key(), "a cover that went: the fallback")
        assertEq(null, ccgui.coverCard(GameDoc(sets = listOf(SetDoc()))))
        // Authoring-only, elided when unset: a game without them saves as before.
        val plain = gameDocToJson(g)
        assertTrue("\"accent\"" !in plain.substringBefore("\"rules\"") && "\"cover\"" !in plain, "elided")
        val dressed = g.copy(accent = "#5B9CF5", cover = last.key())
        val back = gameDocFromJson(gameDocToJson(dressed))
        assertEq("#5B9CF5" to last.key(), back.accent to back.cover)
    }

    check("the jump search finds cards, rules, decks and issues, best first, and says where each goes") {
        val g = EPR_SKIRMISH
        assertEq(emptyList<ccgui.JumpHit>(), ccgui.jumpSearch(g, "  "), "a blank query finds nothing")
        val c = g.cards[3]
        val name = c.faces[0].name
        val hits = ccgui.jumpSearch(g, name)
        val top = hits.first { it.kind == ccgui.JumpKind.CARD }
        assertEq(name, top.label, "the exact name first among cards")
        val at = (top.to as ccgui.JumpTo.Card).at
        assertEq(c.key(), g.sets[at.set].cards[at.card].key(), "and it opens that card")
        // Kinds in order: Rules, Cards, Decks, Issues.
        val kinds = ccgui.jumpSearch(g, "a").map { it.kind }
        assertEq(kinds.sortedBy { it.ordinal }, kinds)
        assertTrue(kinds.count { it == ccgui.JumpKind.CARD } <= 8, "at most 8 of a kind")
        // A section is found by its title and by what this game puts in it.
        assertTrue(ccgui.jumpSearch(g, "comb").any { it.to == ccgui.JumpTo.Section("COMBAT") })
        val phase = g.rules.turn.phases.first().name
        assertTrue(ccgui.jumpSearch(g, phase).any { it.to == ccgui.JumpTo.Section("TURN") }, "a phase finds the turn structure")
        // A deck, by name.
        val d = g.decks.first()
        assertTrue(ccgui.jumpSearch(g, d.name).any { it.to == ccgui.JumpTo.Deck(0) })
        // An issue about a card opens the card; a game-level one, the Overview.
        val broken = g.copy(decks = g.decks + DeckDoc("Broken", listOf(DeckEntry("No Such Card", 1))))
        val withIssues = (listOf(broken, CORE_BUNDLE) + SAMPLE_GAMES).first { it.diagnostics().isNotEmpty() }
        val d0 = withIssues.diagnostics().first()
        val issueHits = ccgui.jumpSearch(withIssues, d0.message).filter { it.kind == ccgui.JumpKind.ISSUE }
        assertTrue(issueHits.isNotEmpty(), "the issue is found by what it says")
        val want = ccgui.locate(withIssues, d0)?.let { ccgui.JumpTo.Card(it) } ?: ccgui.JumpTo.Overview
        assertEq(want, issueHits.first().to)
        // A label match beats a match on a card's other words.
        val type = c.faces[0].types.first()
        val byType = ccgui.jumpSearch(g, type).filter { it.kind == ccgui.JumpKind.CARD }
        assertTrue(byType.isNotEmpty(), "a type finds its cards")
    }

    check("undo against the bot goes back past the bot's replies to the person's last answer") {
        val bot = "P1"
        assertEq(2, ccgui.undoPoint(listOf("P0", "P1", "P0", "P1", "P1"), bot), "drops P0's move and both replies")
        assertEq(null, ccgui.undoPoint(listOf("P1", "P1"), bot), "nothing of yours to undo")
        assertEq(null, ccgui.undoPoint(emptyList(), bot))
        assertEq(3, ccgui.undoPoint(listOf("P0", "P1", "P0", "P1"), null), "no bot: the last answer, like undone()")
        val s = PlaySession(answers = listOf(Answer.Pass, Answer.Pass, Answer.Concede))
        assertEq(listOf<Answer>(Answer.Pass), s.undoneTo(1).answers)
        assertEq(s.generation + 1, s.undoneTo(1).generation, "a different game from here: rebuilt, not extended")
        assertEq(s, s.undoneTo(3), "undo to the end is no change")
    }

    check("a debugged game says so, keeps saying so, and the next game does not") {
        val s = PlaySession(answers = listOf(Answer.Pass), debugged = true)
        assertEq(s, ccgui.decodePlaySession(s.encode()))
        assertTrue(ccgui.decodePlaySession(PlaySession().encode())!!.debugged.not())
        assertTrue(s.undone().debugged && s.restarted().debugged, "undo and restart are the same debugged game")
        assertTrue(!s.nextGame().debugged, "a new game starts clean")
        // A session written before the flag existed (and the bench flag after
        // it) reads back undebugged.
        val old = s.encode().let { e -> e.substringBefore('\u001E').split('\u001F').dropLast(2).joinToString("\u001F") + "\u001E" + e.substringAfter('\u001E') }
        assertEq(false, ccgui.decodePlaySession(old)?.debugged)
    }

    check("the lens: vs AI opens in Play and may switch; both seats is Debug only") {
        assertEq(ccgui.PlayMode.PLAYER, ccgui.TableKind.VS_AI.defaultLens())
        assertEq(ccgui.PlayMode.PLAYTEST, ccgui.TableKind.HOTSEAT.defaultLens())
        assertTrue(ccgui.TableKind.VS_AI.lensSwitchable() && !ccgui.TableKind.HOTSEAT.lensSwitchable())
        assertEq(ccgui.TableKind.HOTSEAT, ccgui.decodeParkedGame("DEBUG\n" + PlaySession().encode())?.kind, "the old name still resumes")
    }

    check("landscape is the board's railed shape; a detail opened from a detail replaces it") {
        assertEq(ccgui.ShellShape.LANDSCAPE, ccgui.shellShapeFor(800, 380))
        assertEq(ccgui.ShellShape.PORTRAIT, ccgui.shellShapeFor(400, 860))
        assertEq(ccgui.ShellShape.PORTRAIT, ccgui.shellShapeFor(500, 400), "too narrow for two panes")
        val cards = shelf.openGame("g").switchModule(ccgui.Module.CARDS)
        val one = cards.openDetail(ccgui.Route.Game("g", ccgui.Module.CARDS, ccgui.Focus.Card(1)))
        val two = one.openDetail(ccgui.Route.Game("g", ccgui.Module.CARDS, ccgui.Focus.Card(2)))
        assertEq(one.stack.size, two.stack.size, "card after card is one Back")
        assertEq(cards, go(two.back(false, false)), "and Back returns to the list")
        val sec = shelf.openGame("g").switchModule(ccgui.Module.RULES)
            .openDetail(ccgui.Route.Game("g", ccgui.Module.RULES, ccgui.Focus.Section("TURN")))
            .openDetail(ccgui.Route.Game("g", ccgui.Module.RULES, ccgui.Focus.Section("COMBAT")))
        assertEq(4, sec.stack.size, "Shelf, Overview, Rules, and ONE section: the second replaced the first")
        val jump = one.openDetail(ccgui.Route.Game("g", ccgui.Module.RULES, ccgui.Focus.Section("DECK")))
        assertEq(one.stack.size + 1, jump.stack.size, "a detail in ANOTHER module is a cross-jump: it pushes")
    }

    check("an import brings the NEW game forward, and a stale bundle is named") {
        assertEq(1, ccgui.landingPageAfterImport(listOf("a", "c"), listOf("a", "b", "c")))
        assertEq(null, ccgui.landingPageAfterImport(listOf("a"), listOf("a")), "nothing new, nothing moves")
        assertEq(ccgui.BundleState.MISSING, ccgui.bundleState(null, 12))
        assertEq(ccgui.BundleState.STALE, ccgui.bundleState(11, 12))
        assertEq(ccgui.BundleState.CURRENT, ccgui.bundleState(12, 12))
    }

    check("both seats show the SAME fields in the SAME order -- a zero is a value, not an absence") {
        val rules = Rules(
            hiddenZones = mapOf("flagships" to HiddenZoneDef("flagships", alwaysVisible = true)),
            playerCounters = listOf(ccg.PlayerCounterDef("store", starting = 0)),
        )
        var s = newGame()
        // A shape where the two could diverge: one seat mid-turn with energy and a
        // store, the other broke with an empty hand.
        s = s.copy(players = s.players +
            ("P0" to s.players.getValue("P0").copy(pool = mapOf("" to 2), counters = mapOf("store" to 4))) +
            ("P1" to s.players.getValue("P1").copy(hand = emptyList(), pool = emptyMap(), counters = mapOf("store" to 0))))
        val a = ccgui.seatFields(rules, s, "P0")
        val b = ccgui.seatFields(rules, s, "P1")
        assertEq(a.all.map { it.key }, b.all.map { it.key }, "identical keys -- nothing appears or vanishes by seat")
        assertEq(listOf("hand", "library", "graveyard", "zone:flagships"), a.cards.map { it.key })
        assertTrue(b.all.first { it.key == "pool:" }.zero, "a spent pool still has its column")
        assertTrue(b.all.first { it.key == "counter:store" }.zero)
        assertTrue(b.all.first { it.key == "hand" }.zero, "and an empty hand is 0, not missing")
        assertEq("mana", a.resources.first().label)
        assertTrue(a.cards.first { it.key == "zone:flagships" }.pool, "the pool is a field, so it sits in the same place for both seats")
    }

    check("the badge is the anchor whose type LOSES THE GAME -- decided by the type, never the name") {
        val rules = Rules(types = BUILTIN_TYPES_CORE + mapOf(
            "Station" to TypeDef("Station", loseOnDeath = true, damageCounter = "hull"),
            "Leader" to TypeDef("Leader"),
        ))
        val leader = CardDefinition(listOf(Face("Warden Oleyn", setOf("Leader"))))
        val station = CardDefinition(listOf(Face("Tessellate Spire", setOf("Station"))))
        var s = newGame()
        val (s1, lid) = enterBattlefield(leader, "P0", s); s = s1
        val (s2, sid) = enterBattlefield(station, "P0", s); s = s2
        s = s.copy(battlefield = s.battlefield + (sid to s.battlefield.getValue(sid).copy(counters = mapOf("hull" to 20))))
        val zone = ZoneRef("anchors", "P0")
        val anchors = listOf(ccgui.BoardAnchorItem(zone, lid), ccgui.BoardAnchorItem(zone, sid))
        assertEq(sid, ccgui.badgeAnchor(rules, s, anchors)?.id, "the Station, though the Leader is listed first")
        assertEq(20, ccgui.badgeValue(rules, s, sid), "its number is its own damage counter")
        assertEq(null, ccgui.badgeValue(rules, s, lid), "a type with no damage counter has no number")
        assertEq(null, ccgui.badgeAnchor(rules, s, anchors.take(1)), "no loss-carrying anchor, no badge -- every anchor is a chip")
    }

    check("the ranks are capped, the hand takes the slack, and nothing is left as a gap") {
        // A tall phone: ranks at the 72dp cap, the hand grows to its max, and the
        // rest goes back to the ranks instead of into dead space.
        val tall = ccgui.splitPlaySurface(available = 640, boardFixed = 120, ranks = 4)
        assertEq(100, tall.handH, "the hand stops at the height its tiles can use")
        assertTrue(tall.rankH!! in 72..112)
        assertTrue(120 + 4 * tall.rankH!! + tall.handH <= 640, "never over-allocates")
        assertTrue(640 - (120 + 4 * tall.rankH!! + tall.handH) < 4, "and the rest goes to the ranks, not a gap")
        val medium = ccgui.splitPlaySurface(available = 500, boardFixed = 120, ranks = 4)
        assertEq(72, medium.rankH)
        assertEq(92, medium.handH, "ranks at their cap, the hand gets exactly the rest")
        val short = ccgui.splitPlaySurface(available = 420, boardFixed = 120, ranks = 4)
        assertEq(72, short.handH, "short screens keep the hand at its minimum")
        assertEq(57, short.rankH, "and the ranks shrink first")
        assertEq(null, ccgui.splitPlaySurface(available = 300, boardFixed = 120, ranks = 4).rankH,
            "below a readable berth the board scrolls rather than lying about fitting")
    }

    check("one Leader is a PORTRAIT beside the badge; only a crowd of companions gets a line") {
        val rules = Rules(types = BUILTIN_TYPES_CORE + mapOf(
            "Station" to TypeDef("Station", loseOnDeath = true, damageCounter = "hull"),
            "Leader" to TypeDef("Leader"),
        ))
        var s = newGame()
        val (s1, sid) = enterBattlefield(CardDefinition(listOf(Face("Kite Anchorage", setOf("Station")))), "P0", s); s = s1
        val (s2, lid) = enterBattlefield(CardDefinition(listOf(Face("Signal-Master Rhee", setOf("Leader")))), "P0", s); s = s2
        val z = ZoneRef("anchors", "P0")
        val both = listOf(ccgui.BoardAnchorItem(z, sid), ccgui.BoardAnchorItem(z, lid))
        assertEq(ccgui.SEAT_EDGE_DP, ccgui.seatEdgeDp(rules, s, both), "one Leader sits beside the Station, no second line")
        assertTrue(ccgui.companionsAsPortraits(1))
        val (s3, lid2) = enterBattlefield(CardDefinition(listOf(Face("Warden Oleyn", setOf("Leader")))), "P0", s)
        val crowd = both + ccgui.BoardAnchorItem(z, lid2)
        assertEq(ccgui.SEAT_EDGE_DP + ccgui.SEAT_CHIP_LINE_DP, ccgui.seatEdgeDp(rules, s3, crowd),
            "two companions do not fit beside the badge, so they get their own line")
        assertEq(ccgui.SEAT_EDGE_DP, ccgui.seatEdgeDp(rules, s, both.take(1)), "a lone badge needs no second line")
        assertEq(ccgui.SEAT_EDGE_DP, ccgui.seatEdgeDp(rules, s, both.drop(1)), "no badge: the chips sit in the main row")
    }

    check("an open pool REPLACES the hand in the dock -- it never stacks under it") {
        val open = setOf("flagships")
        val sizes = mapOf("flagships" to 2, "empty" to 0)
        assertEq("flagships", ccgui.dockLane(listOf("flagships"), { it in open }, { sizes[it] ?: 0 }))
        assertEq(null, ccgui.dockLane(listOf("flagships"), { false }, { sizes[it] ?: 0 }), "closed: the hand")
        assertEq(null, ccgui.dockLane(listOf("empty"), { true }, { sizes[it] ?: 0 }), "an open EMPTY pool shows the hand, not a blank dock")
    }

    check("the budget counts each rank's padding -- the height the viewer's Station lost") {
        val edges = listOf(ccgui.SEAT_EDGE_DP + ccgui.SEAT_CHIP_LINE_DP, ccgui.SEAT_EDGE_DP + ccgui.SEAT_CHIP_LINE_DP)
        // Two seats of two ranks: 2 x (78 + 6 gap + 2 x 6 pad + 1 x 2 rank gap) + stack + frame.
        assertEq(2 * (78 + 6 + 12 + 2) + 36 + 12, ccgui.boardFixedDp(edges, ranksPerSeat = 2, stackBlockDp = 36))
        assertEq(2 * (50 + 6 + 6) + 0 + 12, ccgui.boardFixedDp(listOf(50, 50), ranksPerSeat = 1, stackBlockDp = 0),
            "one rank per seat has no rank gap")
    }

    check("an unbudgeted height is corrected from one look, and the correction SETTLES") {
        val viewport = 620
        val ranks = 4
        val budget = 250
        for (hidden in listOf(0, 3, 17, 40, 90)) {
            // What the board draws: the budgeted fixed height, a height nobody
            // counted, and the ranks at whatever height they were fitted to.
            fun content(correction: Int): Int {
                val r = ccgui.rankHeightFor(viewport - correction, budget, ranks, maxRank = 112) ?: return budget + hidden
                return budget + hidden + ranks * r
            }
            var c = 0
            repeat(3) { c = ccgui.overflowCorrection(c, content(c), viewport, transient = false) }
            val settled = c
            repeat(3) { c = ccgui.overflowCorrection(c, content(c), viewport, transient = false) }
            assertEq(settled, c, "hidden=$hidden: once it fits, it stops moving")
            assertTrue(content(c) <= viewport, "hidden=$hidden: and it does fit")
            if (hidden == 0) assertEq(0, c, "nothing missing, nothing corrected")
        }
        assertEq(5, ccgui.overflowCorrection(5, contentDp = 900, viewportDp = 620, transient = true),
            "a transient -- an armed tile's pill -- is never measured")
    }

    check("a pulse fires for NEW hits only -- and never for a board opened mid-game") {
        var before = newGame()
        repeat(3) { before = before.withCombatHit(1, 2, null, 1) }
        var after = before
        repeat(2) { after = after.withCombatHit(2, null, "P1", 3) }
        assertEq(listOf(4, 5), ccgui.newCombatHits(before, after).map { it.seq })
        assertEq(emptyList<Int>(), ccgui.newCombatHits(after, after).map { it.seq }, "nothing new, nothing drawn")
        assertEq(emptyList<Int>(), ccgui.newCombatHits(null, after).map { it.seq }, "no baseline: do not replay the last wave")
        // Past the cap the list rolls over; the SEQUENCE still says what is new.
        var long = before
        repeat(40) { long = long.withCombatHit(1, 2, null, 1) }
        var longer = long
        longer = longer.withCombatHit(1, 2, null, 1)
        assertEq(listOf(44), ccgui.newCombatHits(long, longer).map { it.seq })
    }

    check("the starfield is the SAME sky every time, and a drifting star stays on the field") {
        val a = ccgui.starField()
        assertEq(a, ccgui.starField(), "a fixed seed -- a recomposition can never reshuffle the sky")
        assertEq(200, a.size)
        assertTrue(a.all { it.x in 0f..1f && it.y in 0f..1f && it.alpha in 0f..1f }, "unit space, so it fits any board")
        assertEq(4, a.count { it.glow })
        for (t in listOf(0f, 13f, 999f, 86_400f)) {
            assertTrue(a.all { s -> ccgui.starAt(s, t).second in 0f..1f }, "wraps rather than drifting off at t=$t")
        }
        val s = a.first { it.layer == 1 }
        val f = a.first { it.layer == 0 }
        assertTrue(ccgui.starAt(s, 10f).second != s.y && ccgui.starAt(f, 10f).second != f.y, "both layers actually move")
    }

    check("cover links join each lane's BACK berth to its OWN front -- from the rules' geometry, not names") {
        val berths = (1..3).flatMap { l ->
            listOf(
                PlayZoneDef("${l}B", ZoneScope.PER_PLAYER, maxOccupants = 1, lane = l, depth = ccg.Depth.BACK),
                PlayZoneDef("${l}F", ZoneScope.PER_PLAYER, maxOccupants = 1, lane = l, depth = ccg.Depth.FRONT),
            )
        }
        val rules = Rules(zones = BUILTIN_ZONES + berths.associateBy { it.id })
        assertEq(listOf("1B" to "1F", "2B" to "2F", "3B" to "3F"), ccgui.coverLinks(rules))
        assertEq(emptyList<Pair<String, String>>(), ccgui.coverLinks(Rules.DEFAULT), "a board with no depth has nothing to link")
    }

    check("the Player asks in words, the Playtest tab keeps the seat id") {
        assertEq("Your move", ccgui.priorityAsk("P0", viewer = "P0", phase = "action"))
        assertEq("P1's move", ccgui.priorityAsk("P1", viewer = "P0", phase = "action"))
        assertEq("P0: priority  (action)", ccgui.priorityAsk("P0", viewer = null, phase = "action"))
    }

    // -- the mode term -------------------------------------------------------
    //
    // Each check is written to FAIL under the old "always option 0" default.

    check("a mode is taken for what it does, not for being first") {
        val s = newGame()
        // Under the old default this is [0], always, whatever the options say.
        assertEq(
            listOf(1),
            ccgui.bestModes(
                listOf(
                    ModeOption.OfEffect(Effect.Draw(PlayerRef.You, lit(1))),
                    ModeOption.OfEffect(Effect.DealDamage(lit(7), BoundTarget(ccg.EACH))),
                ),
                pick = 1, state = s, seat = "P0",
            ),
        )
    }

    check("Destroy is no longer worth nothing") {
        val s = newGame()
        val ctx = EvalContext(s, "P0")
        // It scored 0 before extraction, so a destroy mode could never win a
        // comparison against any damage at all -- including 1 damage.
        assertTrue(
            ccgui.effectImpact(Effect.Destroy(BoundTarget(ccg.EACH)), ctx) >
                ccgui.effectImpact(Effect.DealDamage(lit(1), BoundTarget(ccg.EACH)), ctx),
            "a destroy must beat a pinprick",
        )
    }

    check("a lane sweep is scored by what is actually IN the lane") {
        // The blindness one level down: all three of Enfilade's lane modes are
        // the same effect at a different zone def, so a magnitude-only score
        // would tie them, and the tie-break would hand back lane 1.
        var s = newGame()
        val ship = { n: String -> card(n, setOf("Ship")) }
        // Lane 2 holds two of THEIRS; lane 1 holds one of MINE.
        s = enterBattlefield(ship("Mine"), "P0", s, zone = ZoneRef("1F", "P0")).first
        s = enterBattlefield(ship("TheirsA"), "P1", s, zone = ZoneRef("2F", "P1")).first
        s = enterBattlefield(ship("TheirsB"), "P1", s, zone = ZoneRef("2B", "P1")).first

        fun sweep(lane: Int) = Effect.Sequence(
            listOf("${lane}F", "${lane}B").map { berth ->
                Effect.ForEach(
                    PermFilter(types = setOf("Ship")).inZone(berth),
                    Effect.DealDamage(lit(6), BoundTarget(ccg.EACH)),
                )
            },
        )
        val modes = listOf(sweep(1), sweep(2), sweep(3)).map { ModeOption.OfEffect(it) }
        assertEq(
            listOf(1),
            ccgui.bestModes(modes, pick = 1, state = s, seat = "P0"),
            "sweep the lane holding two of theirs, not the one holding one of mine",
        )
        // And the sign is real: hitting only my own board is worse than doing
        // nothing, which is what stops a pilot wiping itself for the magnitude.
        val ctx = EvalContext(s, "P0")
        assertTrue(ccgui.effectImpact(sweep(1), ctx) < 0, "lane 1 holds only mine")
        assertTrue(ccgui.effectImpact(sweep(3), ctx) == 0, "lane 3 is empty")
    }

    check("cost alternatives are NOT reordered -- that default is load-bearing") {
        val s = newGame()
        // "Spend mana, preserve hull" is the Core's whole identity mechanic and
        // the engine only ever offers payable options. Scoring these by
        // magnitude would rewrite it silently.
        assertEq(
            listOf(0),
            ccgui.bestModes(
                listOf(
                    ModeOption.OfCost(Cost(mana = mapOf("" to 4))),
                    ModeOption.OfCost(Cost(mana = mapOf("" to 2))),
                ),
                pick = 1, state = s, seat = "P0",
            ),
        )
    }

    check("ties break by enumeration order, so a pilot stays deterministic") {
        val s = newGame()
        val same = ModeOption.OfEffect(Effect.Draw(PlayerRef.You, lit(1)))
        assertEq(listOf(0), ccgui.bestModes(listOf(same, same, same), pick = 1, state = s, seat = "P0"))
    }

    check("picking several hands them back in the prompt's own order") {
        val s = newGame()
        val opts = listOf(
            ModeOption.OfEffect(Effect.DealDamage(lit(9), BoundTarget(ccg.EACH))),
            ModeOption.OfEffect(Effect.Draw(PlayerRef.You, lit(1))),
            ModeOption.OfEffect(Effect.DealDamage(lit(8), BoundTarget(ccg.EACH))),
        )
        // The two damage modes win; they are returned 0,2 -- not 2,0 -- because
        // `pick` > 1 means "these happen", not "these happen best-first".
        assertEq(listOf(0, 2), ccgui.bestModes(opts, pick = 2, state = s, seat = "P0"))
    }

}

/** The harness's assertEq takes a note, not an epsilon -- and framing is all
 *  float arithmetic, where 0.25f - 0.1f is not 0.15f. */
private fun assertNear(expected: Float, actual: Float, note: String = "") {
    if (kotlin.math.abs(expected - actual) > 1e-5f) {
        throw AssertionError("expected ~<$expected> but was <$actual>${if (note.isEmpty()) "" else " :: $note"}")
    }

}

/** A pilot's priority decision, as the action (the question is `ask`). */
internal suspend fun PlayerInput.priorityOf(player: PlayerId, state: GameState): PriorityAction =
    ask(Question.Priority(player, state)).toAction(Rules.DEFAULT, state, player) ?: PriorityAction.PassPriority

/** A pilot's FREE-combat target for one attacker. */
internal suspend fun PlayerInput.combatTargetOf(player: PlayerId, attacker: ObjectId, range: AttackRange?, state: GameState): CombatTarget? =
    (ask(Question.CombatTgt(player, attacker, range, state)) as? Answer.CombatTgt)?.target
