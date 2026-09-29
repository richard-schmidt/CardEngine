package ccg

// ---------------------------------------------------------------------------
// Regressions from a structural review. Each check pins a defect it found,
// so the fix cannot quietly come undone.
// ---------------------------------------------------------------------------

private fun castNow(e: Effect): PriorityAction = PriorityAction.CastSpell(e.lowered(LIFE), emptySet())

internal fun reviewChecks() {
    println()
    println("Structural review -- regressions")

    check("a card that dies is filed under its KEY, so a minted-id card can come back") {
        // `Rules.cards` is keyed by `CardDoc.key()` -- the minted id once
        // GameStore has saved the game once. Filed under the display name,
        // every graveyard-reading effect would lose the card.
        val doc = GameDoc(sets = listOf(SetDoc(cards = listOf(CardDoc(
            faces = listOf(FaceDoc("Grunt", setOf("Creature"), mapOf("power" to 2, "toughness" to 2))),
            id = "c-grunt",
        )))))
        val rules = doc.rules()
        val (s0, id) = enterBattlefield(rules.cards.getValue("c-grunt"), "P0", newGame())
        val end = runSync {
            Engine(
                ScriptedInput.of(
                    "P0" to listOf(castNow(Effect.Sequence(listOf(
                        Effect.Destroy(BoundTarget(id)),
                        Effect.ReturnFromDiscard(PlayerRef.You, lit(1), toBattlefield = true),
                    )))),
                    cards = listOf(listOf(id)),
                ),
                rules = rules,
            ).run(s0)
        }
        assertTrue(end.battlefield.values.any { it.base.name == "Grunt" }, "Grunt is back:\n${end.log.takeLast(6).joinToString("\n")}")
    }

    // -- one traversal, lexically scoped ----------------------------------
    // Before the rewrite each of these was a separate hand-kept walk, and each
    // missed a slot the others covered.

    check("divide X +1/+1 among targets -- a Share inside ApplyModifier is filled, not evaluated unbound") {
        val (s1, a) = enterBattlefield(Grunt, "P0", newGame())
        val (s2, b) = enterBattlefield(Grunt, "P0", s1)
        val divide = Effect.ChooseMany(
            creatures(), lit(2), divide = lit(3),
            body = Effect.ApplyModifier(PermFilter().only(EACH), listOf(CharOp.PlusPT(IntExpr.Share, lit(0)))),
        )
        val end = runSync {
            Engine(ScriptedInput.of("P0" to listOf(castNow(divide)), targets = listOf(a, b), numbers = listOf(2))).run(s2)
        }
        assertEq(4, end.characteristicsOf(a).power, "2 + a share of 2")
        assertEq(3, end.characteristicsOf(b).power, "2 + the remaining 1")
    }

    check("a bound target reaches every slot: mana amounts, filters, durations") {
        val t = BoundTarget(CHOSEN)
        val mana = Effect.AddMana(PlayerRef.You, mapOf("" to IntExpr.TargetField(t, "power"))).substituteTarget(CHOSEN, 7)
        assertEq(Effect.AddMana(PlayerRef.You, mapOf("" to IntExpr.TargetField(BoundTarget(7), "power"))), mana, "AddMana amount")
        val only = PermFilter().only(CHOSEN)
        for ((e, want) in listOf(
            Effect.ForEach(only, Effect.NoOp) to Effect.ForEach(PermFilter().only(7), Effect.NoOp),
            Effect.Sacrifice(PlayerRef.You, lit(1), only) to Effect.Sacrifice(PlayerRef.You, lit(1), PermFilter().only(7)),
            Effect.Choose(only, Effect.NoOp) to Effect.Choose(PermFilter().only(7), Effect.NoOp),
            Effect.ChooseMany(only, body = Effect.NoOp) to Effect.ChooseMany(PermFilter().only(7), body = Effect.NoOp),
        )) assertEq(want, e.substituteTarget(CHOSEN, 7), "filter of ${e::class.simpleName}")
        val until = Effect.ApplyModifier(
            permanents(), listOf(CharOp.GrantKeyword("swift")),
            duration = Duration.While(IntExpr.TargetDamage(t) eq lit(0)),
        ).substituteTarget(CHOSEN, 7) as Effect.ApplyModifier
        assertEq(Duration.While(IntExpr.TargetDamage(BoundTarget(7)) eq lit(0)), until.duration, "a While duration")
    }

    check("X reaches a While duration, and a Share reaches mana") {
        val until = Effect.ApplyModifier(permanents(), emptyList(), duration = Duration.While(handSize(PlayerRef.You) lt x))
        assertTrue(until.usesX(), "usesX sees the duration")
        assertEq(Duration.While(handSize(PlayerRef.You) lt lit(3)), (until.substituteX(3) as Effect.ApplyModifier).duration)
        val mana = Effect.AddMana(PlayerRef.You, mapOf("" to IntExpr.Share)).substituteShare(2)
        assertEq(Effect.AddMana(PlayerRef.You, mapOf("" to lit(2))), mana, "AddMana share")
    }

    check("binders scope their variable: an inner loop's EACH is its own") {
        // "For each chosen enemy, put a counter on EACH of your creatures" --
        // the outer binding must stop at the inner ForEach.
        val inner = Effect.ForEach(creatures().yours(), Effect.AddCounter("+1/+1", lit(1), BoundTarget(EACH)))
        val outer = Effect.Sequence(listOf(Effect.DealDamage(lit(1), BoundTarget(EACH)), inner))
        assertEq(
            Effect.Sequence(listOf(Effect.DealDamage(lit(1), BoundTarget(9)), inner)),
            outer.substituteTarget(EACH, 9),
            "the outer EACH is bound, the inner loop's is untouched",
        )
        val nestedChoose = Effect.Choose(creatures(), Effect.Destroy(BoundTarget(CHOSEN)))
        assertEq(nestedChoose, nestedChoose.substituteTarget(CHOSEN, 9), "a nested Choose owns its CHOSEN")
        val nestedDivide = Effect.ChooseMany(creatures(), divide = lit(2), body = Effect.DealDamage(IntExpr.Share, BoundTarget(EACH)))
        assertEq(nestedDivide, nestedDivide.substituteShare(5), "a nested divide owns its Share")
    }

    check("a granted ability's SELF is the grantee, not whoever granted it") {
        val grant = Effect.ApplyModifier(
            creatures().yours(),
            listOf(CharOp.GrantAbility(ActivatedAbility(Cost(tapSource = true), Effect.AddCounter("+1/+1", lit(1), BoundTarget(SELF))))),
        )
        val trigger = Effect.Sequence(listOf(Effect.Destroy(BoundTarget(SELF)), grant))
        assertEq(
            Effect.Sequence(listOf(Effect.Destroy(BoundTarget(4)), grant)),
            trigger.bindSelf(4),
            "the trigger's own SELF binds; the granted ability keeps SELF for whoever activates it",
        )
    }

    // -- the engine's vocabulary, and what nothing reads ------------------

    check("a keyword nothing in the game reads is reported, one the rules read is not") {
        fun gameWith(vararg kws: String) = GameDoc(sets = listOf(SetDoc(cards = listOf(CardDoc(
            faces = listOf(FaceDoc("Bear", setOf("Creature"), mapOf("power" to 2, "toughness" to 2), keywords = kws.toSet())),
        )))))
        val p = gameWith("lifelink", "trample", "first strike").problems()
        assertTrue(p.any { "\"lifelink\"" in it }, "lifelink does nothing in this engine: $p")
        assertTrue(p.none { "\"trample\"" in it }, "trample is an engine keyword")
        assertTrue(p.none { "first strike" in it }, "the MTG combat preset reads first strike")
        val hs = gameWith("taunt").copy(rules = RulesDoc(combat = CombatDoc.Preset("hearthstone")))
        assertTrue(hs.problems().none { "taunt" in it }, "Hearthstone combat reads taunt")
    }

    check("a Cant naming an action the engine can't forbid does not load (it used to load and do nothing)") {
        val bad = runCatching { ruleModOf(Json.parse("""{"op":"cant","action":"scry"}""")) }
        assertTrue(bad.isFailure && "'scry'" in bad.exceptionOrNull()!!.message!!, bad.toString())
        assertEq(RuleMod.Cant(RuleAction.DRAW), ruleModOf(Json.parse("""{"op":"cant","action":"draw"}""")), "the JSON spelling is unchanged")
    }

    check("more than two players is reported -- the engine would ignore it") {
        assertTrue(GameParams(playerCount = 3).problems().any { "two-player" in it })
        assertTrue(GameParams().problems().none { "two-player" in it })
    }

    check("\"piercing\" is trample by the vocabulary table, not by a second inline check") {
        assertTrue(Characteristics(keywords = setOf("piercing")).has(Keyword.TRAMPLE))
        assertTrue(!Characteristics(keywords = setOf("piercing")).has(Keyword.DEATHTOUCH))
    }
}
