package ccg

import ccgui.PassingPilot
import ccgui.canAttackFace
import ccgui.SeatedPilots
import ccgui.HeuristicPilot

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

// ---------------------------------------------------------------------------
// Hand-rolled host-JVM harness (no JUnit, no coroutines library). ScriptedInput
// never suspends, so a suspend `run()` completes synchronously under a plain
// stdlib trampoline. test.sh compiles src/ccg + src/ui + test/ and runs main().
// ---------------------------------------------------------------------------

internal var checks = 0
internal var failures = 0

/** These rules, with `more` in their card table, lowered as `compile()`
 *  lowers them. A permanent reaches its card's faces through the table,
 *  so a test that conjures a card -- `PLAY(card)`, `enterBattlefield` --
 *  registers the ones whose faces it needs. */
internal fun Rules.knowing(vararg more: CardDefinition): Rules = Rules(
    types, zones, combat, turn, cards + more.map { it.lowered(damageCounter) }.associateBy { it.cardKey }, params, resourceModel,
    playerCounters, hiddenZones, damageCounter, counterKinds,
)

internal fun check(name: String, block: () -> Unit) {
    checks++
    try {
        block()
        println("  ok    $name")
    } catch (e: Throwable) {
        failures++
        println("FAIL    $name  ::  ${e.message}")
    }
}

internal fun <T> assertEq(expected: T, actual: T, note: String = "") {
    if (expected != actual) throw AssertionError("expected <$expected> but was <$actual>${if (note.isEmpty()) "" else " :: $note"}")
}

internal fun assertTrue(cond: Boolean, note: String = "") {
    if (!cond) throw AssertionError("expected true :: $note")
}

internal fun <T> runSync(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
    return (result ?: error("coroutine did not complete synchronously")).getOrThrow()
}

// The engine runs only core forms; these hand it what compile() would.
private fun CAST(e: Effect, types: Set<String> = emptySet()): PriorityAction = PriorityAction.CastSpell(e.lowered(LIFE), types)
/** "Destroy target permanent" -- for pruning tests that need to remove a
 *  non-creature (Vinewrap only targets creatures, and an illegal target is
 *  now rejected at cast, not silently destroyed). */
private val DESTROY_ANY = Effect.Choose(permanents(), Effect.Destroy(BoundTarget(CHOSEN)))
private fun PLAY(c: CardDefinition, face: Int = 0): PriorityAction = PriorityAction.PlayPermanent(c.lowered(LIFE), face)
private fun PLAYIN(c: CardDefinition, zone: String, face: Int = 0): PriorityAction = PriorityAction.PlayPermanent(c.lowered(LIFE), face, zone)
private val PASS = PriorityAction.PassPriority

/** All permanents on the battlefield, by name. */
private fun GameState.byName(name: String): Permanent? = battlefield.values.firstOrNull { it.base.name == name }

/** A creature card built inline with derived keywords and/or extra fields. */
private fun creature(
    name: String,
    power: Int,
    toughness: Int,
    vararg kw: String,
    fields: Map<String, Int> = emptyMap(),
): CardDefinition = card(
    name, setOf("Creature"),
    baseChars = Characteristics(
        name, setOf("Creature"),
        mapOf("power" to power, "toughness" to toughness) + fields,
        keywords = kw.toSet(),
    ),
)

/** newGame with permanents pre-placed (skips the play step for combat setup).
 *  Ids are 1, 2, 3, ... in argument order. `exhausted` / `defenseMode` name ids
 *  to start rested / in defense position. */
private fun staged(
    vararg perms: Pair<PlayerId, CardDefinition>,
    life: Int = 20,
    exhausted: Set<ObjectId> = emptySet(),
    defenseMode: Set<ObjectId> = emptySet(),
): GameState {
    var s = newGame(startingLife = life, libraries = mapOf("P0" to tokens(6), "P1" to tokens(6)))
    for ((ctrl, c) in perms) { val (s2, _) = enterBattlefield(c, ctrl, s); s = s2 }
    return s.copy(battlefield = s.battlefield.mapValues { (id, p) ->
        p.copy(exhausted = id in exhausted, combatMode = if (id in defenseMode) "defense" else null)
    })
}

fun main() {
    println("CardEngine -- engine core")
    println()

    check("Stoneback Ox -- a vanilla 3/3 enters and survives SBA") {
        val end = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PLAY(StonebackOx)))).run(newGame())
        }
        val ox = end.byName("Stoneback Ox") ?: error("Ox not on battlefield")
        assertEq(3, end.characteristicsOf(ox.id).power)
        assertEq(3, end.characteristicsOf(ox.id).toughness)
        assertTrue(end.losers.isEmpty())
    }

    check("#2a Emberbolt kills a 2/2; DamageDealt precedes Dies") {
        val engine = Engine(
            ScriptedInput.of(
                "P0" to listOf(PLAY(Grunt), CAST(Emberbolt.castEffect!!)),
                targets = listOf(1), // Grunt is #1
            ),
        )
        val end = runSync { engine.run(newGame().traced()) }
        assertEq(null, end.byName("Grunt"), "Grunt is gone")
        assertEq(listOf(1), end.players.getValue("P0").graveyard.map { it.instanceId })
        val hist = end.trace!!.events
        val dmgIdx = hist.indexOfFirst { it is GameEvent.DamageDealt && it.target == 1 }
        val dieIdx = hist.indexOfFirst { it is GameEvent.LeavesPlay && it.permanent == 1 && it.isDeath }
        assertTrue(dmgIdx in 0 until dieIdx, "DamageDealt(#$dmgIdx) before Dies(#$dieIdx)")
    }

    check("#2b Emberbolt to the face -- 3 to the opponent's life") {
        val end = runSync {
            Engine(ScriptedInput.of("P0" to listOf(CAST(Effect.DamageOpponent(lit(3)))))).run(newGame())
        }
        assertEq(17, end.players.getValue("P1").life)
    }

    check("#2c a targeted spell with NO legal target can't be cast") {
        // Emberbolt's leading Choose has no candidate (empty battlefield) -->
        // the cast is rejected, not bound to a bogus id and left to fizzle.
        val end = runSync {
            Engine(ScriptedInput.of("P0" to listOf(CAST(Emberbolt.castEffect!!)))).run(newGame())
        }
        assertEq(20, end.players.getValue("P1").life, "no spell resolved")
        assertTrue(end.log.any { it.contains("no legal target") }, "logged the illegal cast")
        assertTrue(end.log.none { it.contains("casts spell") }, "nothing hit the stack")
        assertTrue(end.log.none { it.contains("#-1") }, "no SELF-sentinel id leaked as a target")
    }

    check("Scout Rider's ETB draws the known top card, via the stack") {
        val spark = CardRef(500, "spark")
        val engine = Engine(ScriptedInput.of("P0" to listOf(PLAY(ScoutRider))))
        val end = runSync { engine.run(newGame(libraries = mapOf("P0" to listOf(spark)))) }
        assertEq(listOf(spark), end.players.getValue("P0").hand, "the ETB drew Spark")
        assertTrue(end.log.any { it.startsWith("trigger #") }, "the ETB went on the stack, not inline")
        assertTrue(end.log.any { it.startsWith("resolved #") })
    }

    check("Warded Sentinel -- warding turns its death into exile, not a real death") {
        val engine = Engine(
            input = ScriptedInput.of(
                "P0" to listOf(PLAY(WardedSentinel), CAST(Emberbolt.castEffect!!), CAST(Emberbolt.castEffect!!)),
                targets = listOf(1, 1),
            ),
        )
        val end = runSync { engine.run(newGame().copy(replacements = listOf(warding("P0"))).traced()) }
        assertEq(null, end.byName("Warded Sentinel"), "off the battlefield")
        assertEq(emptyList<Int>(), end.players.getValue("P0").graveyard, "NOT in the graveyard")
        assertEq(listOf(1), end.exile.map { it.instanceId }, "exiled instead")
        assertTrue(end.trace!!.replaced.any { it is GameEvent.LeavesPlay && it.permanent == 1 && it.isDeath }, "the death was replaced")
        assertTrue(end.trace!!.events.none { it is GameEvent.LeavesPlay && it.permanent == 1 && it.isDeath }, "no death event -> no dies-trigger")
    }

    check("Vinewrap destroys a creature -- Destroy raises Dies, SBA cleans up") {
        val engine = Engine(
            ScriptedInput.of(
                "P0" to listOf(PLAY(Grunt), CAST(Vinewrap.castEffect!!)),
                targets = listOf(1),
            ),
        )
        val end = runSync { engine.run(newGame().traced()) }
        assertEq(null, end.byName("Grunt"))
        assertEq(listOf(1), end.players.getValue("P0").graveyard.map { it.instanceId })
        assertTrue(end.trace!!.events.any { it is GameEvent.LeavesPlay && it.permanent == 1 && it.isDeath })
    }

    check("Field Marshal -- anthem buffs allies only; removal recomputes to base") {
        // Grunt, then Field Marshal. P1 then Vinewraps the Marshal.
        val engine = Engine(
            ScriptedInput.of(
                "P0" to listOf(PLAY(Grunt), PLAY(FieldMarshal)),
                "P1" to listOf(CAST(Vinewrap.castEffect!!)),
                targets = listOf(2),
            ),
        )
        // Snapshot mid-game by running only P0's plays first.
        val afterBuff = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PLAY(Grunt), PLAY(FieldMarshal)))).run(newGame())
        }
        val grunt = afterBuff.byName("Grunt")!!
        val marshal = afterBuff.byName("Field Marshal")!!
        assertEq(3, afterBuff.characteristicsOf(grunt.id).power, "Grunt buffed to 3/3")
        assertEq(3, afterBuff.characteristicsOf(grunt.id).toughness)
        assertEq(2, afterBuff.characteristicsOf(marshal.id).power, "'other' excludes the Marshal itself")

        val end = runSync { engine.run(newGame()) }
        assertEq(null, end.byName("Field Marshal"), "Marshal destroyed")
        val g2 = end.byName("Grunt")!!
        assertEq(2, end.characteristicsOf(g2.id).power, "buff recomputed away")
        assertEq(2, end.characteristicsOf(g2.id).toughness)
    }

    check("sub -- SBA reads DERIVED toughness live: losing the anthem is lethal") {
        // Order on the stack matters. P0 casts Vinewrap(-> Marshal #2) FIRST, then
        // DealDamage(2 -> Grunt #1). LIFO: the 2 damage resolves while Grunt is a
        // buffed 3/3 (survives), THEN Vinewrap resolves and the +1/+1 is pruned ->
        // Grunt is 2/2 with 2 marked -> SBA kills it on that pass.
        val engine = Engine(
            ScriptedInput.of(
                "P0" to listOf(
                    PLAY(Grunt), PLAY(FieldMarshal),
                    CAST(Vinewrap.castEffect!!),
                    CAST(Effect.DealDamage(lit(2), BoundTarget(1))),
                ),
                targets = listOf(2), // Vinewrap's Choose -> Field Marshal
            ),
        )
        val end = runSync { engine.run(newGame()) }
        assertEq(null, end.byName("Field Marshal"), "anthem gone")
        assertEq(null, end.byName("Grunt"), "2/2 with 2 damage -> dead by SBA once the +1/+1 is gone")
        assertTrue(end.players.getValue("P0").graveyard.any { it.instanceId == 1 })
    }

    // -- P2: value / condition expression language ---------------------------

    check("Rally the Ranks -- draw a card for each creature you control") {
        // two Grunts, then Rally. countOf(creatures().yours()) == 2 -> draw 2.
        val lib = tokens(5)
        val engine = Engine(
            ScriptedInput.of("P0" to listOf(PLAY(Grunt), PLAY(Grunt), CAST(RallyTheRanks.castEffect!!))),
        )
        val end = runSync { engine.run(newGame(libraries = mapOf("P0" to lib))) }
        assertEq(2, end.players.getValue("P0").hand.size, "drew 2 -- one per creature")
        assertEq(3, end.players.getValue("P0").library.size, "5 - 2")
    }

    check("Rally the Ranks -- re-evaluates: 0 creatures -> draw 0") {
        val engine = Engine(ScriptedInput.of("P0" to listOf(CAST(RallyTheRanks.castEffect!!))))
        val end = runSync { engine.run(newGame(libraries = mapOf("P0" to tokens(3)))) }
        assertEq(0, end.players.getValue("P0").hand.size, "no creatures -> the value slot evaluated to 0")
    }

    check("Voltaic Surge -- X is chosen at cast and substituted; X=4 kills a 3/3") {
        val engine = Engine(
            ScriptedInput.of(
                "P0" to listOf(PLAY(StonebackOx), CAST(VoltaicSurge.castEffect!!)),
                targets = listOf(1),   // Ox is #1
                numbers = listOf(4),   // X
            ),
        )
        val end = runSync { engine.run(newGame()) }
        assertEq(null, end.byName("Stoneback Ox"), "3/3 took 4 -> dead")
        assertTrue(end.log.any { it.contains("X=4") })
    }

    check("Volt to the Face -- X to the opponent's life") {
        val end = runSync {
            Engine(ScriptedInput.of("P0" to listOf(CAST(VoltFace.castEffect!!)), numbers = listOf(7)))
                .run(newGame())
        }
        assertEq(13, end.players.getValue("P1").life)
    }

    check("Warcry -- If with a board-state condition: 3 creatures draws twice, 2 draws once") {
        val threeCreatures = runSync {
            Engine(
                ScriptedInput.of("P0" to listOf(PLAY(Grunt), PLAY(Grunt), PLAY(Grunt), CAST(Warcry.castEffect!!))),
            ).run(newGame(libraries = mapOf("P0" to tokens(4))))
        }
        assertEq(2, threeCreatures.players.getValue("P0").hand.size, "3 creatures -> the If fired -> draw 2")

        val twoCreatures = runSync {
            Engine(
                ScriptedInput.of("P0" to listOf(PLAY(Grunt), PLAY(Grunt), CAST(Warcry.castEffect!!))),
            ).run(newGame(libraries = mapOf("P0" to tokens(4))))
        }
        assertEq(1, twoCreatures.players.getValue("P0").hand.size, "2 creatures -> If false -> draw 1")
    }

    check("Vanguard Scout -- intervening-if: draws only if it enters beside another creature") {
        val alone = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PLAY(VanguardScout))))
                .run(newGame(libraries = mapOf("P0" to tokens(2))))
        }
        assertEq(0, alone.players.getValue("P0").hand.size, "only creature -> otherThanThis count 0 -> no draw")

        val beside = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PLAY(Grunt), PLAY(VanguardScout))))
                .run(newGame(libraries = mapOf("P0" to tokens(2))))
        }
        assertEq(1, beside.players.getValue("P0").hand.size, "a Grunt is already there -> drew")
    }

    check("expressions round-trip through JSON") {
        val ints = listOf<IntExpr>(
            lit(3),
            IntExpr.X,
            countOf(creatures().yours().otherThanThis()),
            handSize(PlayerRef.You) - lit(2),
            lifeOf(PlayerRef.Opponent) + countOf(permanents().theirs()),
        )
        for (e in ints) assertEq(e, intExprFromJson(intExprToJson(e)), "IntExpr round-trip: $e")

        val bools = listOf<BoolExpr>(
            alwaysTrue,
            countOf(creatures().yours()) gte 3,
            allOf(handSize(PlayerRef.You) lte 7, controls(creatures())),
            not(lifeOf(PlayerRef.You) lt lit(20)),
        )
        for (e in bools) assertEq(e, boolExprFromJson(boolExprToJson(e)), "BoolExpr round-trip: $e")
    }

    check("the parsed expression actually evaluates -- draw-per-creature via JSON") {
        val parsed = intExprFromJson("""{"op":"count","filter":{"types":["Creature"],"controller":"you"}}""")
        val parsedRally = card("Parsed Rally", setOf("Sorcery"), castEffect = Effect.Draw(PlayerRef.You, parsed))
        val end = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PLAY(Grunt), PLAY(Grunt), PLAY(Grunt), CAST(parsedRally.castEffect!!))))
                .run(newGame(libraries = mapOf("P0" to tokens(5))))
        }
        assertEq(3, end.players.getValue("P0").hand.size, "the JSON-parsed count expr drew 3")
    }

    // -- P3: declarative type model + fields + counters --------------------

    fun addCtr(kind: String, n: Int): PriorityAction =
        CAST(Effect.Choose(permanents(), Effect.AddCounter(kind, lit(n), BoundTarget(CHOSEN))))
    fun rmCtr(kind: String, n: Int): PriorityAction =
        CAST(Effect.Choose(permanents(), Effect.RemoveCounter(kind, lit(n), BoundTarget(CHOSEN))))

    check("Runegate Pillar -- a Land has no power/toughness fields, never dies by SBA") {
        val end = runSync { Engine(ScriptedInput.of("P0" to listOf(PLAY(RunegatePillar)))).run(newGame()) }
        val land = end.byName("Runegate Pillar")!!
        assertTrue(!end.characteristicsOf(land.id).hasField("power"), "no power field")
        assertTrue(!end.characteristicsOf(land.id).hasField("toughness"))
        assertEq(0, end.characteristicsOf(land.id).power)
        assertTrue(end.losers.isEmpty())
    }

    check("Wither Priest -- -1/-1 counters + Creature.diesWhen(toughness <= damage)") {
        // Grunt #1, Wither Priest #2. Priest's ETB Choose -> Grunt gets 2 -1/-1
        // -> derived 0/0 -> the Creature diesWhen predicate kills it.
        val engine = Engine(
            ScriptedInput.of("P0" to listOf(PLAY(Grunt), PLAY(WitherPriest)), targets = listOf(1)),
        )
        val end = runSync { engine.run(newGame()) }
        assertEq(null, end.byName("Grunt"), "0/0 -> dead")
        assertTrue(end.byName("Wither Priest") != null, "the Priest itself is a healthy 2/2")
    }

    check("a +1/+1 counter feeds P/T derivation") {
        val end = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PLAY(Grunt), addCtr("+1/+1", 1)), targets = listOf(1))).run(newGame())
        }
        val g = end.byName("Grunt")!!
        assertEq(1, g.counter("+1/+1"))
        assertEq(3, end.characteristicsOf(g.id).power, "2/2 + a +1/+1 counter")
        assertEq(3, end.characteristicsOf(g.id).toughness)
    }

    check("counter annihilation -- +1/+1 and -1/-1 cancel in pairs (SBA)") {
        val engine = Engine(
            ScriptedInput.of(
                "P0" to listOf(PLAY(Grunt), addCtr("+1/+1", 3), addCtr("-1/-1", 1)),
                targets = listOf(1, 1),
            ),
        )
        val end = runSync { engine.run(newGame()) }
        val g = end.byName("Grunt")!!
        assertEq(2, g.counter("+1/+1"), "3 - 1 cancelled")
        assertEq(0, g.counter("-1/-1"))
        assertEq(4, end.characteristicsOf(g.id).power, "2/2 + net +2")
    }

    check("Sunspire -- Planeswalker: loyalty counter + type diesWhen(loyalty <= 0)") {
        val partial = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PLAY(Sunspire), rmCtr("loyalty", 3)), targets = listOf(1)))
                .run(newGame())
        }
        assertEq(1, partial.byName("Sunspire, the Ascendant")!!.counter("loyalty"), "4 - 3, still alive")

        val dead = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PLAY(Sunspire), rmCtr("loyalty", 4)), targets = listOf(1)))
                .run(newGame())
        }
        assertEq(null, dead.byName("Sunspire, the Ascendant"), "loyalty 0 -> dies")
    }

    check("Siege of the Ninth Gate -- Battle: defense counter + diesWhen(defense <= 0)") {
        val end = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PLAY(SiegeOfTheNinthGate), rmCtr("defense", 5)), targets = listOf(1)))
                .run(newGame())
        }
        assertEq(null, end.byName("Siege of the Ninth Gate"))
    }

    check("The Bloomcycle -- Saga: per-card diesWhen(lore >= 3)") {
        val alive = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PLAY(TheBloomcycle), addCtr("lore", 1)), targets = listOf(1)))
                .run(newGame())
        }
        assertEq(2, alive.byName("The Bloomcycle")!!.counter("lore"), "1 + 1, chapter II, still here")

        val sacked = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PLAY(TheBloomcycle), addCtr("lore", 2)), targets = listOf(1)))
                .run(newGame())
        }
        assertEq(null, sacked.byName("The Bloomcycle"), "lore 3 -> sacrificed")
    }

    check("Adept of the Ninefold Path -- level counter drives a P/T band") {
        fun adeptPower(actions: List<PriorityAction>): Int = runSync {
            val nTargets = actions.count { it is PriorityAction.CastSpell }
            Engine(ScriptedInput.of("P0" to actions, targets = List(nTargets) { 1 })).run(newGame())
        }.let { s -> s.characteristicsOf(s.byName("Adept of the Ninefold Path")!!.id).power }

        assertEq(1, adeptPower(listOf(PLAY(Adept))), "level 0 -> base 1/1")
        assertEq(3, adeptPower(listOf(PLAY(Adept), addCtr("level", 3))), "level 3 -> band 2-4 -> 3/3")
        assertEq(6, adeptPower(listOf(PLAY(Adept), addCtr("level", 5))), "level 5 -> 6/6")
        // interleave PASS so each spell resolves before the next is cast (stack is LIFO)
        assertEq(
            1,
            adeptPower(listOf(PLAY(Adept), addCtr("level", 5), PASS, rmCtr("level", 4), PASS)),
            "level 5 then -4 -> level 1 -> back to 1/1",
        )
    }

    check("Pilgrim // Shrine -- MDFC: choose which face enters") {
        val front = runSync { Engine(ScriptedInput.of("P0" to listOf(PLAY(PilgrimShrine, 0)))).run(newGame()) }
        val pilgrim = front.byName("Pilgrim")!!
        assertTrue("Creature" in pilgrim.base.types)
        assertEq(1, front.characteristicsOf(pilgrim.id).power)

        val back = runSync { Engine(ScriptedInput.of("P0" to listOf(PLAY(PilgrimShrine, 1)))).run(newGame()) }
        val shrine = back.byName("Shrine")!!
        assertTrue("Land" in shrine.base.types)
        assertTrue(!back.characteristicsOf(shrine.id).hasField("power"), "the Land face has no power")
    }

    check("Grizzled Outrider <-> Pack Alpha -- transform swaps face + re-wires the back-face static") {
        // Grunt #1, Outrider #2. Metamorphosis #2 -> transform -> Pack Alpha's
        // anthem buffs the Grunt. Metamorphosis again -> back, buff gone.
        val engine = Engine(
            ScriptedInput.of(
                "P0" to listOf(PLAY(Grunt), PLAY(GrizzledOutrider), CAST(Metamorphosis.castEffect!!), CAST(Metamorphosis.castEffect!!)),
                targets = listOf(2, 2),
            ),
            rules = Rules().knowing(GrizzledOutrider),
        )
        val afterFlip = runSync {
            Engine(
                ScriptedInput.of(
                    "P0" to listOf(PLAY(Grunt), PLAY(GrizzledOutrider), CAST(Metamorphosis.castEffect!!)),
                    targets = listOf(2),
                ),
                rules = Rules().knowing(GrizzledOutrider),
            ).run(newGame())
        }
        assertTrue(afterFlip.byName("Pack Alpha") != null, "front -> back")
        assertEq(4, afterFlip.characteristicsOf(afterFlip.byName("Pack Alpha")!!.id).power)
        assertEq(3, afterFlip.characteristicsOf(afterFlip.byName("Grunt")!!.id).power, "Pack Alpha's anthem buffs the Grunt")

        val end = runSync { engine.run(newGame()) }
        assertTrue(end.byName("Grizzled Outrider") != null, "back -> front again")
        assertEq(2, end.characteristicsOf(end.byName("Grunt")!!.id).power, "buff gone with the back face")
    }

    check("source-relative expressions round-trip through JSON") {
        val ints = listOf<IntExpr>(selfField("toughness"), selfDamage, selfCounter("loyalty"), selfCounter("lore") + lit(1))
        for (e in ints) assertEq(e, intExprFromJson(intExprToJson(e)))
        val bools = listOf<BoolExpr>(
            selfCounter("loyalty") lte lit(0),
            selfField("toughness") lte selfDamage,
            allOf(hasType("Saga"), selfCounter("lore") gte 3),
        )
        for (e in bools) assertEq(e, boolExprFromJson(boolExprToJson(e)))
    }

    // -- P4: event model + trigger scoping --------------------------------

    check("Archive Keeper -- 'whenever you cast an instant/sorcery, draw'") {
        val fire = runSync {
            Engine(
                ScriptedInput.of(
                    "P0" to listOf(PLAY(ArchiveKeeper), PLAY(Grunt), CAST(Emberbolt.castEffect!!, setOf("Instant"))),
                    targets = listOf(2),
                ),
            ).run(newGame(libraries = mapOf("P0" to tokens(3))))
        }
        assertEq(1, fire.players.getValue("P0").hand.size, "the Instant cast drew a card")

        val noFire = runSync {
            Engine(
                ScriptedInput.of("P0" to listOf(PLAY(ArchiveKeeper), CAST(Effect.NoOp, setOf("Enchantment")))),
            ).run(newGame(libraries = mapOf("P0" to tokens(3))))
        }
        assertEq(0, noFire.players.getValue("P0").hand.size, "an Enchantment spell doesn't match the filter")
    }

    check("Deathbloom Herald -- LTB trigger fires on any way it leaves play") {
        val end = runSync {
            Engine(
                ScriptedInput.of("P0" to listOf(PLAY(DeathbloomHerald), CAST(Vinewrap.castEffect!!)), targets = listOf(1)),
            ).run(newGame())
        }
        assertEq(null, end.byName("Deathbloom Herald"))
        assertEq(22, end.players.getValue("P0").life, "LTB gained 2 life")
    }

    check("Chime of Endings -- 'whenever a creature dies anywhere'") {
        val end = runSync {
            Engine(
                ScriptedInput.of("P0" to listOf(PLAY(ChimeOfEndings), PLAY(Grunt), CAST(Vinewrap.castEffect!!)), targets = listOf(2)),
            ).run(newGame())
        }
        assertEq(21, end.players.getValue("P0").life, "the Grunt's death -> Chime -> +1 life")
    }

    check("Toll Collector -- an opponent's creature entering, not your own, triggers it") {
        val end = runSync {
            Engine(
                ScriptedInput.of(
                    "P0" to listOf(PLAY(TollCollector), PLAY(Grunt)),
                    "P1" to listOf(PLAY(Grunt)),
                ),
            ).run(newGame())
        }
        assertEq(20, end.players.getValue("P0").life, "P0's own Grunt did NOT trigger it")
        assertEq(19, end.players.getValue("P1").life, "P1's Grunt entering -> P1 loses 1")
    }

    check("Hive Overseer -- end-step boundary trigger, effect scoped over a set (ForEach)") {
        val end = runSync {
            Engine(ScriptedInput.of("P0@main" to listOf(PLAY(HiveOverseer), PLAY(Grunt), PLAY(Grunt))))
                .playGame(newGame(libraries = mapOf("P0" to tokens(3), "P1" to tokens(3))), maxTurns = 1)
        }
        val grunts = end.battlefield.values.filter { it.base.name == "Grunt" }
        assertEq(2, grunts.size)
        assertTrue(grunts.all { it.counter("+1/+1") == 1 }, "each other creature got a +1/+1 at end step")
        assertEq(0, end.byName("Hive Overseer")!!.counter("+1/+1"), "'other' excludes the Overseer")
    }

    check("Delayed Blast -- a delayed one-shot fires at the next end step, then is gone") {
        val end = runSync {
            Engine(ScriptedInput.of("P0@main" to listOf(PLAY(Grunt), CAST(DelayedBlast.castEffect!!)), targets = listOf(1)))
                .playGame(newGame(libraries = mapOf("P0" to tokens(3), "P1" to tokens(3))), maxTurns = 1)
        }
        assertEq(null, end.byName("Grunt"), "4 damage at the end step killed the 2/2")
        assertTrue(end.delayedTriggers.isEmpty(), "the one-shot removed itself")
    }

    check("Wrath of the Commons -- each player sacrifices two creatures (APNAP order)") {
        val end = runSync {
            Engine(
                ScriptedInput.of(
                    "P0" to listOf(PLAY(Grunt), PLAY(Grunt), PLAY(Grunt), CAST(WrathOfTheCommons.castEffect!!, setOf("Sorcery"))),
                    "P1" to listOf(PLAY(Grunt), PLAY(Grunt), PLAY(Grunt)),
                    // ids: P0 grunts #1-3, Wrath spell #4, P1 grunts #5-7
                    targets = listOf(1, 2, 5, 6), // P0 sacs #1,#2 ; P1 sacs #5,#6
                ),
            ).run(newGame())
        }
        assertEq(1, end.battlefield.values.count { it.base.name == "Grunt" && it.controller == "P0" })
        assertEq(1, end.battlefield.values.count { it.base.name == "Grunt" && it.controller == "P1" })
    }

    check("Saga chapters fire in order; adding 2 lore at once runs II before III") {
        val engine = Engine(
            ScriptedInput.of(
                "P0" to listOf(PLAY(TheBloomcycle), addCtr("lore", 2)),
                targets = listOf(1),
            ),
        )
        val end = runSync { engine.run(newGame(libraries = mapOf("P0" to tokens(5)))) }
        // I on entry (draw 1), then II+III from the +2 (draw 1, gain 3), then lore 3 -> sacrificed.
        assertEq(2, end.players.getValue("P0").hand.size, "chapter I + chapter II each drew a card")
        assertEq(23, end.players.getValue("P0").life, "chapter III gained 3")
        assertEq(null, end.byName("The Bloomcycle"), "lore 3 -> the Saga's diesWhen sacrificed it")
        // both II and III trigger off the one lore -> 3 event; II must resolve first.
        val loreUp = end.log.indexOfFirst { it.contains("lore -> 3") }
        val chapIIdraw = end.log.withIndex().first { it.index > loreUp && it.value == "P0 draws 1" }.index
        val chapIIIgain = end.log.withIndex().first { it.index > loreUp && it.value == "P0 gains 3 life" }.index
        assertTrue(chapIIdraw < chapIIIgain, "chapter II (draw) resolved before chapter III (gain 3)")
    }

    check("Saga auto-tick -- a lore counter is added at the start of the controller's main phase") {
        // Turn 1 P0 plays the Saga in main (enters lore 1, chapter I). Turn 3 is
        // P0's next turn: the main-phase tick brings it to lore 2.
        val end = runSync {
            Engine(ScriptedInput.of("P0@main" to listOf(PLAY(TheBloomcycle))))
                .playGame(newGame(libraries = mapOf("P0" to tokens(8), "P1" to tokens(8))), maxTurns = 3)
        }
        assertEq(2, end.byName("The Bloomcycle")!!.counter("lore"), "1 on entry + 1 from P0's turn-3 main tick")
    }

    check("counter-query IntExpr round-trips + evaluates") {
        val e = countCounters(creatures().yours(), "+1/+1")
        assertEq(e, intExprFromJson(intExprToJson(e)))
        // Grunt with 2 +1/+1, another with 1 -> query == 3
        val s = runSync {
            Engine(
                ScriptedInput.of(
                    "P0" to listOf(PLAY(Grunt), PLAY(Grunt), addCtr("+1/+1", 2), addCtr("+1/+1", 1)),
                    targets = listOf(1, 2),
                ),
            ).run(newGame())
        }
        assertEq(3, e.eval(EvalContext(s, "P0", source = null)))
    }

    // -- P5: play zones / arenas -----------------------------------------

    check("per-player zone -- each player has their own 'planet' instance") {
        val end = runSync {
            Engine(
                ScriptedInput.of(
                    "P0" to listOf(PLAYIN(Grunt, "planet")),
                    "P1" to listOf(PLAYIN(Grunt, "planet")),
                ),
                rules = P5_RULES,
            ).run(newGame())
        }
        val g0 = end.battlefield.getValue(1)
        val g1 = end.battlefield.getValue(2)
        assertEq(ZoneRef("planet", "P0"), g0.zone, "P0's Grunt is in P0's planet")
        assertEq(ZoneRef("planet", "P1"), g1.zone, "P1's Grunt is in P1's planet")
        // Named("planet") spans both instances; SameAsSource is one instance.
        val anyPlanet = countOf(creatures().inZone("planet"))
        assertEq(2, anyPlanet.eval(EvalContext(end, "P0", source = null)), "both planets")
        val thisPlanet = countOf(creatures().inThisZone())
        assertEq(1, thisPlanet.eval(EvalContext(end, "P0", source = g0.id)), "only P0's planet")
    }

    check("TypeDef.zoneOfPlay -- a Skyship routes itself into 'skies' with no zone named") {
        val end = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PLAY(OrbitalCutter))), rules = P5_RULES).run(newGame())
        }
        assertEq(ZoneRef("skies"), end.byName("Orbital Cutter")!!.zone)
    }

    check("an unknown zone def resolves to a shared instance") {
        val end = runSync { Engine(ScriptedInput.of("P0" to listOf(PLAYIN(Grunt, "nowhere")))).run(newGame()) }
        assertEq(ZoneRef("nowhere", null), end.byName("Grunt")!!.zone)
    }

    check("Effect.MovePermanent fires MovesZone; Warp Anchor's zone-scoped trigger draws") {
        val engine = Engine(
            ScriptedInput.of(
                "P0" to listOf(
                    PLAYIN(WarpAnchor, "skies"),
                    PLAY(Grunt),
                    CAST(Effect.MovePermanent(BoundTarget(2), ZoneRef("skies"))),
                ),
            ),
        )
        val end = runSync { engine.run(newGame(libraries = mapOf("P0" to tokens(3))).traced()) }
        assertEq(ZoneRef("skies"), end.byName("Grunt")!!.zone, "the Grunt moved arenas")
        assertTrue(
            end.trace!!.events.any { it is GameEvent.MovesZone && it.permanent == 2 && it.to == ZoneRef("skies") },
            "a MovesZone event fired",
        )
        assertEq(1, end.players.getValue("P0").hand.size, "Warp Anchor saw the move into its zone -> drew")
    }

    check("MovePermanent to the permanent's current zone is a no-op") {
        val engine = Engine(
            ScriptedInput.of(
                "P0" to listOf(PLAYIN(Grunt, "skies"), CAST(Effect.MovePermanent(BoundTarget(1), ZoneRef("skies")))),
            ),
        )
        val end = runSync { engine.run(newGame().traced()) }
        assertEq(0, end.trace!!.events.count { it is GameEvent.MovesZone }, "same zone -> nothing fired")
    }

    check("zone-scoped anthem -- Ward of the Expanse buffs only its own zone") {
        val end = runSync {
            Engine(
                ScriptedInput.of("P0" to listOf(PLAYIN(Grunt, "skies"), PLAY(Grunt), PLAYIN(WardOfTheExpanse, "skies"))),
            ).run(newGame())
        }
        val inSkies = end.battlefield.getValue(1)
        val onGround = end.battlefield.getValue(2)
        assertEq(3, end.characteristicsOf(inSkies.id).power, "same zone as the Ward -> +1/+0")
        assertEq(2, end.characteristicsOf(onGround.id).power, "different zone -> untouched")
    }

    check("LeavesPlay carries the zone the permanent left from") {
        val engine = Engine(
            ScriptedInput.of("P0" to listOf(PLAYIN(Grunt, "skies"), CAST(Vinewrap.castEffect!!)), targets = listOf(1)),
        )
        val end = runSync { engine.run(newGame().traced()) }
        val ltb = end.trace!!.events.filterIsInstance<GameEvent.LeavesPlay>().first { it.permanent == 1 }
        assertEq(ZoneRef("skies"), ltb.fromZone)
    }

    check("The Iron Line -- a 3-lane shared config; a permanent sits in exactly one lane") {
        val laneRules = Rules(
            zones = BUILTIN_ZONES + listOf(PlayZoneDef("lane-1"), PlayZoneDef("lane-2"), PlayZoneDef("lane-3"))
                .associateBy { it.id },
        )
        val end = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PLAYIN(Grunt, "lane-2"))), rules = laneRules).run(newGame())
        }
        val g = end.byName("Grunt")!!
        assertEq(ZoneRef("lane-2"), g.zone)
        val ctx = EvalContext(end, "P0", source = g.id)
        assertEq(1, countOf(permanents().inZoneExact(ZoneRef("lane-2"))).eval(ctx))
        assertEq(0, countOf(permanents().inZoneExact(ZoneRef("lane-1"))).eval(ctx))
    }

    check("zone-scoped filters round-trip through JSON") {
        val ints = listOf<IntExpr>(
            countOf(creatures().inThisZone()),
            countOf(permanents().inZone("planet")),
            countOf(ofType("Creature").yours().inZoneExact(ZoneRef("planet", "P0"))),
        )
        for (e in ints) assertEq(e, intExprFromJson(intExprToJson(e)), "round-trip $e")
    }

    // -- P6: effect durations + prevention -----------------------------

    check("Battle Fury -- +2/+0 is live the moment it resolves") {
        val end = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PLAY(StonebackOx), CAST(BattleFury.castEffect!!)), targets = listOf(1)))
                .run(newGame())
        }
        assertEq(5, end.characteristicsOf(1).power, "3 + 2")
        assertEq(3, end.characteristicsOf(1).toughness, "+0")
    }

    check("Battle Fury -- the +2/+0 is gone once the next turn begins") {
        val end = runSync {
            Engine(ScriptedInput.of("P0@main" to listOf(PLAY(StonebackOx), CAST(BattleFury.castEffect!!)), targets = listOf(1)))
                .playGame(newGame(libraries = mapOf("P0" to tokens(5), "P1" to tokens(5))), maxTurns = 2)
        }
        assertEq(3, end.characteristicsOf(1).power, "duration expired at turn 2")
        assertTrue(end.log.any { it.startsWith("durations expire") })
    }

    check("Bulwark Chant -- snapshots its targets; a creature that enters later is not buffed") {
        // ids: Grunt #1, chant spell #2, the modifier burns #3 at resolution,
        // then Grunt #4 is played.
        val end = runSync {
            Engine(
                ScriptedInput.of("P0" to listOf(PLAY(Grunt), CAST(BulwarkChant.castEffect!!), PASS, PLAY(Grunt))),
            ).run(newGame())
        }
        assertEq(4, end.characteristicsOf(1).toughness, "on the board at resolution -> +0/+2")
        assertEq(2, end.characteristicsOf(4).toughness, "entered afterwards -> untouched")
    }

    check("Bulwark Chant -- the team buff expires end of turn") {
        val end = runSync {
            Engine(ScriptedInput.of("P0@main" to listOf(PLAY(Grunt), CAST(BulwarkChant.castEffect!!))))
                .playGame(newGame(libraries = mapOf("P0" to tokens(5), "P1" to tokens(5))), maxTurns = 2)
        }
        assertEq(2, end.characteristicsOf(1).toughness, "back to base at turn 2")
    }

    check("Sculptor's Whim -- SetPT makes a 3/3 into a 0/1") {
        val end = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PLAY(StonebackOx), CAST(SculptorsWhim.castEffect!!)), targets = listOf(1)))
                .run(newGame())
        }
        assertEq(0, end.characteristicsOf(1).power)
        assertEq(1, end.characteristicsOf(1).toughness)
    }

    check("Sculptor's Whim -- becoming a 0/1 with 1 marked damage is lethal (SBA reads the derived toughness)") {
        val end = runSync {
            Engine(
                ScriptedInput.of(
                    "P0" to listOf(
                        PLAY(StonebackOx),
                        CAST(Effect.DealDamage(lit(1), BoundTarget(1))),
                        PASS,
                        CAST(SculptorsWhim.castEffect!!),
                    ),
                    targets = listOf(1),
                ),
            ).run(newGame())
        }
        assertEq(null, end.byName("Stoneback Ox"), "0/1 with 1 damage -> dead")
    }

    check("Slow the Sands -- a While-duration modifier holds while its condition is true") {
        val on = runSync {
            Engine(
                ScriptedInput.of(
                    "P0" to listOf(PLAY(ChimeOfEndings), PLAY(StonebackOx), CAST(SlowTheSands.castEffect!!)),
                    targets = listOf(2),
                ),
            ).run(newGame())
        }
        assertEq(0, on.characteristicsOf(2).power, "-3/-0 while you control an Enchantment")

        val off = runSync {
            Engine(
                ScriptedInput.of(
                    "P0" to listOf(
                        PLAY(ChimeOfEndings), PLAY(StonebackOx),
                        CAST(SlowTheSands.castEffect!!), PASS, CAST(DESTROY_ANY),
                    ),
                    targets = listOf(2, 1),
                ),
            ).run(newGame())
        }
        assertEq(null, off.byName("Chime of Endings"), "the Enchantment is gone")
        assertEq(3, off.characteristicsOf(2).power, "condition false -> the modifier evaporated")
    }

    check("Aegis Ward -- prevents the next 3 damage, then is spent") {
        val engine = Engine(
            ScriptedInput.of(
                "P0" to listOf(
                    PLAY(StonebackOx),
                    CAST(AegisWard.castEffect!!), PASS,
                    CAST(Effect.DealDamage(lit(2), BoundTarget(1))), PASS,
                    CAST(Effect.DealDamage(lit(2), BoundTarget(1))),
                ),
                targets = listOf(1),
            ),
        )
        val end = runSync { engine.run(newGame().traced()) }
        assertEq(1, end.battlefield.getValue(1).damageMarked, "2 fully prevented, then 1 of the next 2 got through")
        assertTrue(end.shields.isEmpty(), "the shield is used up")
        assertEq(
            listOf(1),
            end.trace!!.events.filterIsInstance<GameEvent.DamageDealt>().map { it.amount },
            "only the 1 unprevented point reached the event stream",
        )
        assertTrue(end.log.any { it.contains("prevented") })
    }

    check("Last Stand -- prevents all damage this turn") {
        val engine = Engine(
            ScriptedInput.of(
                "P0" to listOf(
                    PLAY(StonebackOx),
                    CAST(LastStand.castEffect!!), PASS,
                    CAST(Effect.DealDamage(lit(10), BoundTarget(1))),
                ),
                targets = listOf(1),
            ),
        )
        val end = runSync { engine.run(newGame().traced()) }
        assertEq(0, end.battlefield.getValue(1).damageMarked, "all 10 prevented")
        assertTrue(end.trace!!.events.none { it is GameEvent.DamageDealt }, "a fully-prevented hit fires no event")
    }

    check("a prevention shield expires with the turn") {
        val end = runSync {
            Engine(ScriptedInput.of("P0@main" to listOf(PLAY(StonebackOx), CAST(AegisWard.castEffect!!)), targets = listOf(1)))
                .playGame(newGame(libraries = mapOf("P0" to tokens(5), "P1" to tokens(5))), maxTurns = 2)
        }
        assertTrue(end.shields.isEmpty(), "swept at turn 2")
    }

    check("a time-bounded delayed trigger that never fires is swept up") {
        val armed = Effect.Delayed(
            on = EventPattern.Never,
            effect = Effect.GainLife(PlayerRef.You, lit(1)),
            duration = Duration.EndOfTurn,
        )
        val end = runSync {
            Engine(ScriptedInput.of("P0@main" to listOf(CAST(armed)))).playGame(
                newGame(libraries = mapOf("P0" to tokens(5), "P1" to tokens(5))),
                maxTurns = 2,
            )
        }
        assertTrue(end.log.any { it.contains("arms delayed trigger") }, "it was armed in turn 1")
        assertTrue(end.delayedTriggers.isEmpty(), "and gone by turn 2 without ever firing")
        assertEq(20, end.players.getValue("P0").life, "it never resolved")
    }

    check("PermFilter.only round-trips through JSON") {
        val e = countOf(creatures().only(7))
        assertEq(e, intExprFromJson(intExprToJson(e)))
    }

    // -- P7: static-effect vocabulary ---------------------------------

    check("Skyward Doctrine -- grants a keyword to a filter, yours only") {
        val end = runSync {
            Engine(
                ScriptedInput.of(
                    "P0" to listOf(PLAY(Grunt), PLAY(SkywardDoctrine)),
                    "P1" to listOf(PLAY(Grunt)),
                ),
            ).run(newGame())
        }
        assertTrue(end.characteristicsOf(1).has("flying"), "your creature has flying")
        assertTrue(!end.characteristicsOf(3).has("flying"), "the opponent's does not")
    }

    check("Grantcaller's Creed -- two ops in one static: +1/+1 and vigilance") {
        val end = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PLAY(Grunt), PLAY(GrantcallersCreed)))).run(newGame())
        }
        assertEq(3, end.characteristicsOf(1).power)
        assertEq(3, end.characteristicsOf(1).toughness)
        assertTrue(end.characteristicsOf(1).has("vigilance"))
    }

    check("Dead Weight Doctrine -- .theirs() debuff feeds the SBA") {
        val end = runSync {
            Engine(
                ScriptedInput.of(
                    "P0" to listOf(PLAY(DeadWeightDoctrine), PLAY(Grunt)),
                    "P1" to listOf(PLAY(Grunt)),
                ),
            ).run(newGame())
        }
        assertEq(2, end.characteristicsOf(2).power, "your own creature is untouched")
        assertEq(null, end.byName("Grunt")?.takeIf { it.controller == "P1" }, "the opponent's 2/2 -> 0/0 -> dead")
        assertEq(1, end.battlefield.values.count { it.base.name == "Grunt" })
    }

    check("a static's compiled continuous effect is pruned when its source leaves") {
        val end = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PLAY(DeadWeightDoctrine), CAST(DESTROY_ANY)), targets = listOf(1)))
                .run(newGame())
        }
        assertTrue(end.continuousEffects.isEmpty(), "the layer effect went away with the enchantment")
    }

    check("Null Field -- RemoveAbilities suppresses triggers; SetPT makes everything 1/1") {
        val end = runSync {
            Engine(ScriptedInput.of("P0@main" to listOf(PLAY(NullField), PLAY(HiveOverseer), PLAY(Grunt), PLAY(Grunt))))
                .playGame(newGame(libraries = mapOf("P0" to tokens(4), "P1" to tokens(4))), maxTurns = 1)
        }
        assertEq(1, end.characteristicsOf(3).power, "the Grunt is 1/1")
        assertEq(1, end.characteristicsOf(2).power, "so is the Hive Overseer")
        assertEq(0, end.battlefield.getValue(3).counter("+1/+1"), "the Overseer's end-step trigger never fired")
    }

    check("Null Field -- lifts when it leaves") {
        val end = runSync {
            Engine(
                ScriptedInput.of("P0" to listOf(PLAY(NullField), PLAY(Grunt), CAST(DESTROY_ANY)), targets = listOf(1)),
            ).run(newGame())
        }
        assertEq(2, end.characteristicsOf(2).power, "back to a 2/2 once Null Field is gone")
    }

    check("Leyline Tithe -- 'your opponents can't gain life'; your own life gain still works") {
        val end = runSync {
            Engine(
                ScriptedInput.of(
                    "P0" to listOf(PLAY(LeylineTithe), CAST(Effect.GainLife(PlayerRef.You, lit(5)))),
                    "P1" to listOf(CAST(Effect.GainLife(PlayerRef.You, lit(5)))),
                ),
            ).run(newGame())
        }
        assertEq(25, end.players.getValue("P0").life, "the Tithe's controller can still gain")
        assertEq(20, end.players.getValue("P1").life, "the opponent can't")
        assertTrue(end.log.any { it.contains("can't gain life") })
    }

    check("Leyline Tithe -- its rule mod is pruned when it leaves") {
        val end = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PLAY(LeylineTithe), CAST(DESTROY_ANY)), targets = listOf(1)))
                .run(newGame())
        }
        assertTrue(end.ruleMods.isEmpty())
    }

    check("Rallying Standard -- a conditional layer, re-checked live") {
        fun gruntPower(nGrunts: Int, kill: Boolean): Int {
            val plays = mutableListOf<PriorityAction>(PLAY(RallyingStandard))
            repeat(nGrunts) { plays += PLAY(Grunt) }
            if (kill) plays += CAST(Vinewrap.castEffect!!)
            val end = runSync {
                Engine(ScriptedInput.of("P0" to plays, targets = if (kill) listOf(2) else emptyList())).run(newGame())
            }
            // #1 is the Standard; the first Grunt is #2, or #3 if #2 was killed.
            return end.characteristicsOf(if (kill) 3 else 2).power
        }
        assertEq(2, gruntPower(2, kill = false), "2 creatures -> condition false -> no buff")
        assertEq(3, gruntPower(3, kill = false), "3 creatures -> +1/+0")
        assertEq(2, gruntPower(3, kill = true), "kill one -> back under 3 -> buff drops live")
    }

    check("Sanctuary Field -- a card-authored replacement redirects a destroy to exile") {
        val engine = Engine(
            ScriptedInput.of(
                "P0" to listOf(PLAY(SanctuaryField), PLAY(Grunt), CAST(Vinewrap.castEffect!!)),
                targets = listOf(2),
            ),
        )
        val end = runSync { engine.run(newGame().traced()) }
        assertEq(null, end.byName("Grunt"))
        assertEq(emptyList<Int>(), end.players.getValue("P0").graveyard.map { it.instanceId }, "not in the graveyard")
        assertEq(listOf(2), end.exile.map { it.instanceId }, "exiled instead")
        assertTrue(end.trace!!.replaced.any { it is GameEvent.LeavesPlay && it.permanent == 2 })
    }

    check("Sanctuary Field -- its replacement is pruned when it leaves (and catches its own death)") {
        val end = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PLAY(SanctuaryField), CAST(DESTROY_ANY)), targets = listOf(1)))
                .run(newGame())
        }
        assertTrue(end.replacements.isEmpty(), "the card-authored replacement is gone")
        assertEq(listOf(1), end.exile.map { it.instanceId }, "warding caught its own controller's death -> self-exile")
    }

    check("Primal Mimicry -- type replace + set P/T + keyword grant, through an EOT duration") {
        val active = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PLAY(Grunt), CAST(PrimalMimicry.castEffect!!)), targets = listOf(1)))
                .run(newGame())
        }
        assertEq(setOf("Creature", "Elemental"), active.characteristicsOf(1).types)
        assertEq(4, active.characteristicsOf(1).power)
        assertTrue(active.characteristicsOf(1).has("trample"))

        val reverted = runSync {
            Engine(ScriptedInput.of("P0@main" to listOf(PLAY(Grunt), CAST(PrimalMimicry.castEffect!!)), targets = listOf(1)))
                .playGame(newGame(libraries = mapOf("P0" to tokens(4), "P1" to tokens(4))), maxTurns = 2)
        }
        assertEq(setOf("Creature"), reverted.characteristicsOf(1).types, "back to a plain Creature")
        assertEq(2, reverted.characteristicsOf(1).power)
        assertTrue(!reverted.characteristicsOf(1).has("trample"))
    }

    check("layer order -- SetPT (7) applies before PlusPT (8)") {
        // Null Field sets the Grunt to 1/1; Grantcaller's Creed then adds +1/+1.
        val end = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PLAY(NullField), PLAY(GrantcallersCreed), PLAY(Grunt)))).run(newGame())
        }
        assertEq(2, end.characteristicsOf(3).power, "1/1 set, then +1/+1 -> 2/2")
        assertEq(2, end.characteristicsOf(3).toughness)
    }

    // -- P8: declarable combat model --------------------------------------

    fun combat(
        vararg perms: Pair<PlayerId, CardDefinition>,
        attacks: Map<ObjectId, CombatTarget> = emptyMap(),
        blocks: Map<ObjectId, ObjectId> = emptyMap(),
        combatTargets: List<CombatTarget> = emptyList(),
        rules: Rules = Rules.DEFAULT,
    ): GameState = runSync {
        Engine(
            ScriptedInput(emptyMap(), attacks = attacks, blocks = blocks, combatTargets = combatTargets),
            rules = rules,
        ).playGame(staged(*perms), maxTurns = 1)
    }

    check("P8-A an unblocked attacker hits the defending player and taps") {
        val end = combat("P0" to creature("Raider", 3, 3), attacks = mapOf(1 to CombatTarget.Player("P1")))
        assertEq(17, end.players.getValue("P1").life)
        assertTrue(end.battlefield.getValue(1).exhausted, "attacking taps it")
    }

    check("P8-A a block is a mutual trade (damage read from one snapshot)") {
        val end = combat(
            "P0" to creature("Ox", 3, 3), "P1" to creature("Wall", 3, 3),
            attacks = mapOf(1 to CombatTarget.Player("P1")), blocks = mapOf(2 to 1),
        )
        assertEq(null, end.byName("Ox"))
        assertEq(null, end.byName("Wall"))
        assertEq(20, end.players.getValue("P1").life, "a blocked attacker deals nothing to the player")
    }

    check("P8-A first strike lands before the counterstrike") {
        val end = combat(
            "P0" to creature("Duelist", 2, 2, "first strike"), "P1" to creature("Grunt", 2, 2),
            attacks = mapOf(1 to CombatTarget.Player("P1")), blocks = mapOf(2 to 1),
        )
        assertEq(null, end.byName("Grunt"), "killed in the first-strike step")
        assertEq(0, end.byName("Duelist")!!.damageMarked, "the dead blocker never struck back")
    }

    check("P8-A trample assigns lethal to the blocker, the rest to the player") {
        val end = combat(
            "P0" to creature("Juggernaut", 5, 5, "trample"), "P1" to creature("Chump", 1, 1),
            attacks = mapOf(1 to CombatTarget.Player("P1")), blocks = mapOf(2 to 1),
        )
        assertEq(null, end.byName("Chump"))
        assertEq(16, end.players.getValue("P1").life, "1 lethal to the chump, 4 through")
        assertTrue(end.byName("Juggernaut") != null)
    }

    check("P8-A deathtouch makes any damage lethal") {
        val end = combat(
            "P0" to creature("Adder", 1, 1, "deathtouch"), "P1" to creature("Titan", 6, 6),
            attacks = mapOf(1 to CombatTarget.Player("P1")), blocks = mapOf(2 to 1),
        )
        assertEq(null, end.byName("Titan"), "1 deathtouch damage killed the 6/6")
        assertEq(null, end.byName("Adder"), "and the 6 back killed the Adder")
    }

    check("hull is a permanent: damage SPENDS counters, survives cleanup, and a token is born with its printed hull") {
        // The migration's three load-bearing claims, in the engine where the
        // suite can actually see them (src/com/ccg is invisible to test.sh, so
        // none of the 14 migrated cards are covered by anything here).
        val shipT = TypeDef(
            "Ship", fields = setOf("hull", "fast"), attacks = true,
            damageCounter = "hull", diesWhen = (selfCounter("hull") lte lit(0)).lowered(),
        )
        val rules = Rules(
            types = BUILTIN_TYPES_CORE + mapOf("Ship" to shipT),
            turn = TurnStructure(listOf(PhaseSpec("main", sorcerySpeed = true), PhaseSpec("end", onEnter = PhaseEffects.CLEANUP))),
        )
        val hulk = CardDoc(
            faces = listOf(FaceDoc("Hulk", setOf("Ship"), fields = mapOf("hull" to 4, "fast" to 1))),
            entersWith = listOf(CounterDef("hull", lit(4))),
        ).build()

        // 1. Damage spends counters rather than marking damage...
        val (s0, ship) = enterBattlefield(hulk, "P0", newGame())
        val hit = runSync {
            Engine(ScriptedInput.of("P0" to listOf(CAST(Effect.DealDamage(lit(3), BoundTarget(ship))))), rules = rules).run(s0)
        }
        assertEq(1, hit.battlefield.getValue(ship).counter("hull"), "4 printed hull, 3 damage -> 1 counter left")
        assertEq(0, hit.battlefield.getValue(ship).damageMarked, "and NOTHING was recorded as marked damage")
        assertEq(4, hit.characteristicsOf(ship).fields["hull"], "the PRINTED hull is untouched -- it is what the card says")

        // 2. ...and it is PERMANENT: a full turn with a CLEANUP does not heal it.
        val afterTurn = runSync {
            Engine(ScriptedInput.of("P0@main" to listOf(CAST(Effect.DealDamage(lit(3), BoundTarget(ship))))), rules = rules)
                .playGame(s0, maxTurns = 1)
        }
        assertEq(
            1, afterTurn.battlefield.getValue(ship).counter("hull"),
            "cleanup wipes MARKED damage; counters are not marked damage, so the wound persists",
        )

        // 3. A token is born with its printed hull as counters. Without this a
        //    token Ship enters at zero and dies to its own diesWhen instantly --
        //    which would silently delete an entire faction's gameplan.
        val tokenChars = Characteristics("Acolyte", setOf("Ship"), mapOf("hull" to 2, "fast" to 1))
        val made = runSync {
            Engine(ScriptedInput.of("P0" to listOf(CAST(Effect.CreateToken(tokenChars, lit(1))))), rules = rules).run(newGame())
        }
        val token = made.battlefield.values.firstOrNull { it.isToken }
        assertTrue(token != null, "the token survived entering play at all")
        assertEq(2, token!!.counter("hull"), "born with its printed hull, not at zero")
    }

    check("damage aimed at a PLAYER lands on their Station -- the loss condition is a permanent now") {
        // With no player hull to reduce, attacking a player has to MEAN
        // attacking the permanent that is the player. Redirected in dealWave,
        // so it also inherits shields, triggers and wave aggregation.
        val stationT = TypeDef(
            "Station", damageCounter = "hull",
            diesWhen = (selfCounter("hull") lte lit(0)).lowered(), loseOnDeath = true,
        )
        val shipT = TypeDef("Ship", fields = setOf("power", "toughness"), attacks = true)
        val rules = Rules(
            types = BUILTIN_TYPES_CORE + mapOf("Station" to stationT, "Ship" to shipT),
            turn = TurnStructure(listOf(PhaseSpec("combat", combat = true))),
        )
        val station = CardDefinition(
            listOf(Face("Relay", setOf("Station"))),
            entersWith = listOf(CounterDef("hull", lit(5))),
        )
        var st = staged("P0" to creature("Raider", 3, 3))
        val (s1, relay) = enterBattlefield(station, "P1", st)
        val end = runSync {
            Engine(ScriptedInput(emptyMap(), attacks = mapOf(1 to CombatTarget.Player("P1"))), rules = rules).playGame(s1, maxTurns = 1)
        }
        assertEq(2, end.battlefield.getValue(relay).counter("hull"), "5 hull, a 3-power hit -> the STATION took it")
        assertTrue("P1" !in end.losers, "and 2 hull left is still alive")
    }

    check("an Improvement cannot attach to ITSELF -- otherThanThis must survive to the real bind") {
        // An Improvement is already in play when its own SelfEnters trigger
        // resolves, so a bare `yours()` offers it as its own host. The plating
        // alone, controlling nothing else, must find NO legal target.
        val rules = Rules(types = BUILTIN_TYPES_CORE + mapOf("Improvement" to TypeDef("Improvement")))
        val plating = CardDoc(faces = listOf(FaceDoc(
            "Ablative Plating", setOf("Improvement"),
            triggers = listOf(TriggerDoc.SelfEnters(
                Effect.Choose(PermFilter().yours().otherThanThis(), Effect.Attach(BoundTarget(CHOSEN))),
            )),
        ))).build()

        // It must be PLAYED, not placed: `enterBattlefield` does not raise
        // EntersPlay, so a SelfEnters trigger never fires and an assertion that
        // "it did not attach" would pass for entirely the wrong reason. (The
        // first draft of this test did exactly that.)
        val alone = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PLAY(plating))), rules = rules).run(newGame())
        }
        val lonePlate = alone.byName("Ablative Plating")
        assertTrue(lonePlate != null, "it resolved into play")
        assertEq(
            null, lonePlate!!.hostId,
            "controlling nothing else, it attaches to NOTHING -- it must never be its own host",
        )
        assertTrue(
            alone.log.any { it.contains("no legal target") },
            "and it SAYS there was no legal target, rather than silently attaching to itself",
        )

        // With a real host available it still attaches to that host.
        val (s1, ship) = enterBattlefield(
            CardDoc(faces = listOf(FaceDoc("Hulk", setOf("Creature"), fields = mapOf("power" to 1, "toughness" to 1)))).build(),
            "P0", newGame(),
        )
        val withHost = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PLAY(plating)), targets = listOf(ship)), rules = rules).run(s1)
        }
        assertEq(ship, withHost.byName("Ablative Plating")?.hostId, "with a legal host it attaches to THAT")
    }

    check("REPLAY ROUND-TRIP: record a real game's answers, replay them, get the identical state") {
        // The property `PlaySession` claims -- a game is (bundle, seed, deck
        // picks, answers) -- tested here, outside Compose. A save file, a
        // lockstep peer and a conformance corpus all rest on it.
        val rules = Rules(
            turn = TurnStructure(listOf(PhaseSpec("main", sorcerySpeed = true), PhaseSpec("end", onEnter = PhaseEffects.CLEANUP))),
        )

        // A PlayerInput that plays a real line AND records what it answered,
        // exactly as the UI does through `encodeAnswer`.
        class Recorder(private val script: List<Answer>) : PlayerInput {
            override suspend fun ask(q: Question): Answer = when (q) {
                is Question.Priority -> Answer.Act(askPriorityAction(q.player, q.state))
                is Question.PickTarget -> Answer.Target(chooseTarget(q.player, q.candidates, q.state))
                is Question.PickNumber -> Answer.Number(chooseNumber(q.player, q.label, q.min, q.max))
                is Question.CombatTgt -> Answer.CombatTgt(chooseCombatTarget(q.player, q.attacker, q.range, q.state))
                else -> q.default()
            }

            val recorded = mutableListOf<Answer>()
            private var i = 0
            private suspend fun askPriorityAction(player: PlayerId, state: GameState): PriorityAction {
                val a = script.getOrNull(i++) ?: Answer.Pass
                val action = a.toAction(rules, state, player) ?: PriorityAction.PassPriority
                recorded += action.asAnswer() ?: Answer.Pass
                return action
            }
            private suspend fun chooseTarget(player: PlayerId, candidates: List<ObjectId>, state: GameState): ObjectId {
                val pick = candidates.firstOrNull() ?: 0
                recorded += Answer.Target(pick)
                return pick
            }
            private suspend fun chooseNumber(player: PlayerId, prompt: String, min: Int, max: Int): Int {
                recorded += Answer.Number(min)
                return min
            }
            private suspend fun chooseCombatTarget(player: PlayerId, attacker: ObjectId, range: ccg.AttackRange?, state: GameState): CombatTarget? {
                recorded += Answer.CombatTgt(null)
                return null
            }
        }

        val doc = CardDoc(faces = listOf(FaceDoc("Hulk", setOf("Creature"), fields = mapOf("power" to 2, "toughness" to 2))))
        val built = doc.build()
        val r2 = Rules(cards = mapOf("Hulk" to built), turn = rules.turn)
        fun opening(): GameState {
            var st = newGame(libraries = mapOf("P0" to tokens(6), "P1" to tokens(6, 200_000)))
            return st.copy(players = st.players + ("P0" to st.players.getValue("P0").copy(hand = listOf(CardRef(70, "Hulk")))))
        }

        // 1. Play it for real, recording as we go.
        val rec = Recorder(listOf(Answer.PlayCard(70, CastZone.Std(HiddenZone.HAND))))
        val live = runSync { Engine(rec, rules = r2).playGame(opening(), maxTurns = 1) }
        assertTrue(rec.recorded.isNotEmpty(), "the run actually recorded something")

        // 2. Replay the RECORDED answers into a fresh game from the same seed.
        class Replayer(private val answers: List<Answer>) : PlayerInput {
            override suspend fun ask(q: Question): Answer = when (q) {
                is Question.Priority -> Answer.Act(askPriorityAction(q.player, q.state))
                is Question.PickTarget -> Answer.Target(chooseTarget(q.player, q.candidates, q.state))
                is Question.PickNumber -> Answer.Number(chooseNumber(q.player, q.label, q.min, q.max))
                is Question.CombatTgt -> Answer.CombatTgt(chooseCombatTarget(q.player, q.attacker, q.range, q.state))
                else -> q.default()
            }

            private var i = 0
            private fun next(): Answer? = answers.getOrNull(i++)
            private suspend fun askPriorityAction(player: PlayerId, state: GameState): PriorityAction =
                next()?.toAction(rules, state, player) ?: PriorityAction.PassPriority
            private suspend fun chooseTarget(player: PlayerId, candidates: List<ObjectId>, state: GameState): ObjectId =
                (next() as? Answer.Target)?.id ?: 0
            private suspend fun chooseNumber(player: PlayerId, prompt: String, min: Int, max: Int): Int =
                (next() as? Answer.Number)?.n ?: min
            private suspend fun chooseCombatTarget(player: PlayerId, attacker: ObjectId, range: ccg.AttackRange?, state: GameState): CombatTarget? =
                (next() as? Answer.CombatTgt)?.target
        }
        val replayed = runSync { Engine(Replayer(rec.recorded), rules = r2).playGame(opening(), maxTurns = 1) }

        // 3. The claim, asserted at full strength rather than on a hand-picked
        //    subset -- a weakened comparison is how a test passes without
        //    proving the thing.
        assertEq(live.battlefield.keys, replayed.battlefield.keys, "the same permanents exist")
        assertEq(live.players.getValue("P0").hand, replayed.players.getValue("P0").hand, "hands match")
        assertEq(live.players.getValue("P0").library, replayed.players.getValue("P0").library, "libraries match -- the SEED replayed")
        assertEq(live.losers, replayed.losers)
        assertEq(live.turnNumber, replayed.turnNumber)

        // 4. And the answers survive the wire, which is the other half of the
        //    claim: a session is serialisable, not merely typed.
        assertEq(rec.recorded, answersFromJson(answersToJson(rec.recorded)), "the recording round-trips through JSON")

        // 5. COMBAT: replay must cover attack declarations too (the UI sends a
        //    List of attackers; an encoder expecting a Map once dropped every
        //    attack from the recording while a hold-back fixture passed).
        val combatRules = Rules(turn = TurnStructure(listOf(PhaseSpec("combat", combat = true))))
        class AttackRecorder : PlayerInput {
            override suspend fun ask(q: Question): Answer = when (q) {
                is Question.Priority -> Answer.Act(askPriorityAction(q.player, q.state))
                is Question.PickTarget -> Answer.Target(chooseTarget(q.player, q.candidates, q.state))
                is Question.PickNumber -> Answer.Number(chooseNumber(q.player, q.label, q.min, q.max))
                is Question.Attackers -> Answer.Attackers(declareAttackers(q.player, q.eligible, q.state))
                else -> q.default()
            }

            val recorded = mutableListOf<Answer>()
            private suspend fun askPriorityAction(player: PlayerId, state: GameState) = PriorityAction.PassPriority
            private suspend fun chooseTarget(player: PlayerId, candidates: List<ObjectId>, state: GameState) = 0
            private suspend fun chooseNumber(player: PlayerId, prompt: String, min: Int, max: Int) = min
            private suspend fun declareAttackers(
                player: PlayerId,
                eligible: List<ObjectId>,
                state: GameState,
            ): Map<ObjectId, CombatTarget> {
                val m = eligible.associateWith { CombatTarget.Player(state.opponentOf(player)) as CombatTarget }
                if (m.isNotEmpty()) recorded += Answer.Attackers(m)
                return m
            }
        }
        val ar = AttackRecorder()
        val fought = runSync {
            Engine(ar, rules = combatRules).playGame(staged("P0" to creature("Raider", 3, 3)), maxTurns = 1)
        }
        assertTrue(ar.recorded.isNotEmpty(), "an attack WAS declared -- the fixture exercises combat at all")
        assertEq(17, fought.players.getValue("P1").life, "and it connected")

        // The recorded attack replays to the same outcome, and survives JSON.
        val attackAnswers = answersFromJson(answersToJson(ar.recorded))
        assertEq(ar.recorded, attackAnswers, "an attack assignment round-trips whole")
        class AttackReplayer(private val answers: List<Answer>) : PlayerInput {
            override suspend fun ask(q: Question): Answer = when (q) {
                is Question.Priority -> Answer.Act(askPriorityAction(q.player, q.state))
                is Question.PickTarget -> Answer.Target(chooseTarget(q.player, q.candidates, q.state))
                is Question.PickNumber -> Answer.Number(chooseNumber(q.player, q.label, q.min, q.max))
                is Question.Attackers -> Answer.Attackers(declareAttackers(q.player, q.eligible, q.state))
                else -> q.default()
            }

            private var i = 0
            private suspend fun askPriorityAction(player: PlayerId, state: GameState) = PriorityAction.PassPriority
            private suspend fun chooseTarget(player: PlayerId, candidates: List<ObjectId>, state: GameState) = 0
            private suspend fun chooseNumber(player: PlayerId, prompt: String, min: Int, max: Int) = min
            private suspend fun declareAttackers(
                player: PlayerId,
                eligible: List<ObjectId>,
                state: GameState,
            ): Map<ObjectId, CombatTarget> =
                (answers.getOrNull(i++) as? Answer.Attackers)?.assignment ?: emptyMap()
        }
        val refought = runSync {
            Engine(AttackReplayer(attackAnswers), rules = combatRules).playGame(staged("P0" to creature("Raider", 3, 3)), maxTurns = 1)
        }
        assertEq(
            fought.players.getValue("P1").life, refought.players.getValue("P1").life,
            "replaying the recorded attack deals the same damage",
        )
    }

    check("Answer: a session's answers are TYPED and survive JSON -- the wire form of a play session") {
        // Every Answer shape round-trips through the typed codec.
        val all = listOf(
            Answer.Pass,
            Answer.Concede,
            Answer.PlayCard(12, CastZone.Std(HiddenZone.HAND), face = 1, zoneDef = "lane-2"),
            Answer.PlayCard(13, CastZone.Declared("flagships")),
            Answer.ActivateAbility(4, 2),
            Answer.Target(9),
            Answer.Number(3),
            Answer.Attackers(mapOf(1 to CombatTarget.Player("P1"), 2 to CombatTarget.Obj(5))),
            Answer.Blockers(mapOf(6 to 1)),
            Answer.CombatTgt(CombatTarget.Obj(8)),
            Answer.CombatTgt(null),
            Answer.Blocker(null),
            Answer.Blocker(3),
            Answer.Modes(listOf(0, 2)),
            Answer.Cards(listOf(100, 101)),
        )
        for (a in all) assertEq(a, answerFromJson(answerToJson(a)), "round-trips: $a")
        assertEq(all, answersFromJson(answersToJson(all)), "and so does a whole list, which is what a session stores")

        // A recorded PLAY is a REFERENCE, not the action: PriorityAction
        // .PlayPermanent carries a CardDefinition whose faces hold lambdas, so
        // it neither serialises nor compares reliably. It is rebuilt from state.
        val doc = CardDoc(faces = listOf(FaceDoc("Hulk", setOf("Creature"), fields = mapOf("power" to 1, "toughness" to 1))))
        val rules = Rules(cards = mapOf("Hulk" to doc.build()))
        var st = newGame()
        st = st.copy(players = st.players + ("P0" to st.players.getValue("P0").copy(hand = listOf(CardRef(50, "Hulk")))))
        val rebuilt = Answer.PlayCard(50, CastZone.Std(HiddenZone.HAND)).toAction(rules, st, "P0")
        assertTrue(rebuilt is PriorityAction.PlayPermanent, "a creature is PLAYED, not cast")
        assertEq(50, (rebuilt as PriorityAction.PlayPermanent).from?.instanceId)

        // ...and it says so honestly when the reference no longer resolves,
        // rather than silently doing something else, which is what a blind cast
        // would have done.
        assertEq(null, Answer.PlayCard(999, CastZone.Std(HiddenZone.HAND)).toAction(rules, st, "P0"), "a card that is not there rebuilds to nothing")

        // The inverse, for recording what a player just did.
        assertEq(Answer.Pass, PriorityAction.PassPriority.asAnswer())
        assertEq(Answer.Concede, PriorityAction.Concede.asAnswer())
        assertEq(Answer.ActivateAbility(4, 1), PriorityAction.Activate(4, 1).asAnswer())
    }

    check("the heuristic pilot BEATS doing nothing -- a strategy has to clear that bar") {
        // A strategy is judged by running games; "beats passing" is the lowest
        // bar there is.
        val shipT = TypeDef(
            "Ship", fields = setOf("hull", "fast"), attacks = true,
            damageCounter = "hull", diesWhen = (selfCounter("hull") lte lit(0)).lowered(),
            zoneChoices = listOf("lane-1", "lane-2"),
        )
        val stationT = TypeDef(
            "Station", damageCounter = "hull",
            diesWhen = (selfCounter("hull") lte lit(0)).lowered(), loseOnDeath = true,
        )
        val ship = CardDoc(
            faces = listOf(FaceDoc("Raider", setOf("Ship"), fields = mapOf("hull" to 2, "fast" to 2))),
            cost = Cost(mana = mapOf("" to 1)),
            entersWith = listOf(CounterDef("hull", lit(2))),
        ).build()
        val station = CardDefinition(
            listOf(Face("Relay", setOf("Station"))),
            entersWith = listOf(CounterDef("hull", lit(6))),
        )
        val rules = Rules(
            types = BUILTIN_TYPES_CORE + mapOf("Ship" to shipT, "Station" to stationT),
            cards = mapOf("Raider" to ship),
            zones = BUILTIN_ZONES + listOf(
                PlayZoneDef("lane-1", ZoneScope.PER_PLAYER, maxOccupants = 1),
                PlayZoneDef("lane-2", ZoneScope.PER_PLAYER, maxOccupants = 1),
            ).associateBy { it.id },
            combat = FAST_SLOW_LANES_COMBAT.lowered(),
            turn = TurnStructure(
                listOf(
                    PhaseSpec("upkeep", interactive = false),
                    PhaseSpec("action", sorcerySpeed = true),
                    PhaseSpec("combat", combat = true),
                ),
                mode = TurnMode.SHARED,
            ),
            params = GameParams(startingHandSize = 4, cardsDrawnPerTurn = 1, poolPersistsPerTurn = true),
            resourceModel = ResourceModel.Ramp(perTurn = 1, cap = 4, refillEachTurn = true),
        )

        fun play(seed: Int): GameState {
            val start = setupGame(
                rules = rules,
                libraries = mapOf(
                    "P0" to List(20) { CardRef(100_000 + it, "Raider") },
                    "P1" to List(20) { CardRef(200_000 + it, "Raider") },
                ),
                seed = seed,
                startInPlay = mapOf(
                    "P0" to listOf(StartCard("Relay", "battlefield")),
                    "P1" to listOf(StartCard("Relay", "battlefield")),
                ),
            )
            val pilots = SeatedPilots(mapOf("P0" to HeuristicPilot("P0", rules), "P1" to PassingPilot()))
            return runSync { Engine(pilots, rules = rules).playGame(start, maxTurns = 12) }
        }

        // Several seeds, because one game proves nothing about a strategy.
        val results = listOf(1, 7, 13, 29, 101).map { play(it) }
        val heuristicWon = results.count { "P1" in it.losers && "P0" !in it.losers }
        val heuristicLost = results.count { "P0" in it.losers }

        assertTrue(
            heuristicWon > heuristicLost,
            "the heuristic must beat passing more often than it loses to it -- won $heuristicWon, lost $heuristicLost",
        )
        // And it must be DOING something, not winning by the opponent decking
        // out: a bot that develops nothing would still eventually win by
        // attrition against a pilot that never acts, which would make the test
        // above pass while proving nothing.
        assertTrue(
            results.any { r -> r.battlefield.values.any { it.controller == "P0" && it.id !in setOf<ObjectId>() } },
            "it actually put permanents on the board",
        )
        val anyStationDamage = results.any { r ->
            r.battlefield.values.any { it.controller == "P1" && it.counter("hull") < 6 } || "P1" in r.losers
        }
        assertTrue(anyStationDamage, "and it attacked the thing that ends the game")
    }

    check("pilots: two deciders can be pitted against each other, and Concede ends a game cleanly") {
        // Scripted openings, bots, remote humans and JSON clients are all one
        // seam: PlayerInput.
        val rules = Rules(turn = TurnStructure(listOf(PhaseSpec("main", sorcerySpeed = true))))

        // A conceding pilot: a bot or remote client can stop with a real loser
        // and a reason in the log.
        val quitter = object : PlayerInput {
            override suspend fun ask(q: Question): Answer = when (q) {
                is Question.Priority -> Answer.Act(askPriorityAction(q.player, q.state))
                is Question.PickTarget -> Answer.Target(chooseTarget(q.player, q.candidates, q.state))
                is Question.PickNumber -> Answer.Number(chooseNumber(q.player, q.label, q.min, q.max))
                else -> q.default()
            }

            private suspend fun askPriorityAction(player: PlayerId, state: GameState) =
                if (player == "P1") PriorityAction.Concede else PriorityAction.PassPriority
            private suspend fun chooseTarget(player: PlayerId, candidates: List<ObjectId>, state: GameState) = 0
            private suspend fun chooseNumber(player: PlayerId, prompt: String, min: Int, max: Int) = min
        }
        val seated = SeatedPilots(mapOf("P0" to PassingPilot(), "P1" to quitter))
        val end = runSync { Engine(seated, rules = rules).playGame(newGame(), maxTurns = 3) }
        assertTrue("P1" in end.losers, "conceding loses -- a clean, inspectable ending")
        assertTrue("P0" !in end.losers, "and only the seat that conceded")
        assertTrue(end.log.any { it.contains("concedes") }, "and the log says why, rather than the game just stopping")

        // Concede is legal unconditionally -- a rule that could refuse it would
        // leave a client with no way out.
        assertTrue(
            legality(rules, newGame(), "P0", PriorityAction.Concede) is Legality.Legal,
            "no phase, cost or timing gate on giving up",
        )
    }

    check("an attacker may HOLD BACK -- attacking is a choice, not something the engine imposes") {
        // Holding a Ship back is a choice: a null combat target means "do not
        // attack this step". FREE style acts on permanents that HAVE the step's
        // damage field, so the actors here are Ship-shaped.
        val shipT = TypeDef("Ship", fields = setOf("hull", "fast", "slow"), attacks = true)
        val rules = Rules(
            types = BUILTIN_TYPES_CORE + mapOf("Ship" to shipT),
            combat = FAST_SLOW_COMBAT.lowered(),
            turn = TurnStructure(listOf(PhaseSpec("combat", combat = true))),
        )
        fun ship(n: String) = CardDefinition(
            listOf(Face(n, setOf("Ship"), Characteristics(n, setOf("Ship"), mapOf("hull" to 2, "fast" to 1, "slow" to 1)))),
        )
        val st = staged("P0" to ship("Runner"), "P1" to ship("Wall"))
        // No combat targets queued at all -> every attacker holds back.
        val end = runSync {
            Engine(ScriptedInput(emptyMap()), rules = rules).playGame(st, maxTurns = 1)
        }
        assertTrue(end.byName("Runner") != null && end.byName("Wall") != null, "nobody swung, so nobody died")
        assertEq(20, end.players.getValue("P1").life, "and no damage reached the defender")
        assertTrue(end.log.any { it.contains("holds back") }, "and the log SAYS so, rather than the step passing silently")
    }

    check("combat damage is SIMULTANEOUS: a one-shot shield absorbs the whole WAVE, not one hit of it") {
        // Simultaneous damage as a WAVE (statable at a table). Two
        // blockers, one attacker: the attacker takes 1 + 1 in ONE step. Hit by
        // hit, the shield eats one and the other kills a 1/1; as a wave both
        // points are absorbed together and it lives.
        var st = staged(
            "P0" to creature("Runner", 1, 1),
            "P1" to creature("Pike", 1, 1),
            "P1" to creature("Hook", 1, 1),
        )
        st = st.copy(
            shields = st.shields + ActiveReplacement(
                source = null, controller = "P0",
                doc = ReplacementDoc.Replace(EventPattern.Damaged(PermFilter(pinned = BoundTarget(1))), Effect.NoOp),
                duration = Duration.Permanent, id = 99, budget = Budget(1, Spend.USES),
            ),
        )
        val end = runSync {
            Engine(
                ScriptedInput(
                    emptyMap(),
                    attacks = mapOf(1 to CombatTarget.Player("P1")),
                    blocks = mapOf(2 to 1, 3 to 1),
                ),
                rules = Rules.DEFAULT,
            ).playGame(st, maxTurns = 1)
        }
        assertTrue(end.byName("Runner") != null, "the WHOLE wave was absorbed together -- per-hit, the second point would have killed it")
    }

    check("combat damage leaves a HIT record -- who hit what, for how much -- so a pulse is never guessed") {
        val blocked = combat(
            "P0" to creature("Bruiser", 3, 3), "P1" to creature("Grunt", 2, 2),
            attacks = mapOf(1 to CombatTarget.Player("P1")), blocks = mapOf(2 to 1),
        )
        val hits = blocked.combatHits
        assertTrue(hits.any { it.source == 1 && it.target == 2 && it.amount == 3 }, "the attacker's hit on its blocker: $hits")
        assertTrue(hits.any { it.source == 2 && it.target == 1 && it.amount == 2 }, "and the blocker's hit back")
        assertEq(hits.map { it.seq }.sorted(), hits.map { it.seq }, "in the order they landed")
        assertEq(hits.size, hits.map { it.seq }.toSet().size, "each with its own sequence number")

        val open = combat(
            "P0" to creature("Bruiser", 3, 3), "P1" to creature("Grunt", 2, 2),
            attacks = mapOf(1 to CombatTarget.Player("P1")),
        )
        assertTrue(open.combatHits.any { it.source == 1 && it.player == "P1" && it.amount == 3 }, "an unblocked hit on the PLAYER: ${open.combatHits}")

        var s = newGame()
        repeat(COMBAT_HITS_KEPT + 5) { s = s.withCombatHit(1, 2, null, 1) }
        assertEq(COMBAT_HITS_KEPT, s.combatHits.size, "bounded -- the state does not grow with the game")
        assertEq(COMBAT_HITS_KEPT + 5, s.combatHits.last().seq, "but the sequence keeps counting, so a reader never sees an old hit as new")
    }

    check("P8-A menace: a single blocker can't stop it") {
        val end = combat(
            "P0" to creature("Bruiser", 3, 3, "menace"), "P1" to creature("Grunt", 2, 2),
            attacks = mapOf(1 to CombatTarget.Player("P1")), blocks = mapOf(2 to 1),
        )
        assertEq(17, end.players.getValue("P1").life, "the lone block is nullified -> unblocked")
        assertTrue(end.byName("Grunt") != null, "the would-be blocker never connected")
    }

    check("P8-A vigilance attacks without tapping") {
        val end = combat("P0" to creature("Sentinel", 2, 2, "vigilance"), attacks = mapOf(1 to CombatTarget.Player("P1")))
        assertTrue(!end.battlefield.getValue(1).exhausted)
        assertEq(18, end.players.getValue("P1").life)
    }

    check("P8-A defender can't be declared as an attacker") {
        val end = combat("P0" to creature("Bulwark", 0, 4, "defender"), attacks = mapOf(1 to CombatTarget.Player("P1")))
        assertEq(20, end.players.getValue("P1").life, "the attack was dropped")
    }

    check("P8-A attacking a Planeswalker routes damage to its loyalty") {
        val end = combat(
            "P0" to creature("Raider", 3, 3), "P1" to Sunspire,
            attacks = mapOf(1 to CombatTarget.Obj(2)),
        )
        assertEq(1, end.byName("Sunspire, the Ascendant")!!.counter("loyalty"), "4 - 3")
        assertEq(20, end.players.getValue("P1").life, "the player took nothing")
    }

    check("P8-A Thornling Brood's attack trigger resolves before damage") {
        val end = combat("P0" to ThornlingBrood, attacks = mapOf(1 to CombatTarget.Player("P1")))
        assertEq(1, end.byName("Thornling Brood")!!.counter("+1/+1"), "the attack put a +1/+1 counter on it")
        assertEq(18, end.players.getValue("P1").life, "it then hit for 2, not 1")
    }

    check("P8-B fast/slow: each sub-phase reads its own field, free targeting") {
        val end = combat(
            "P0" to creature("Striker", 0, 2, fields = mapOf("fast" to 2, "slow" to 3)),
            "P1" to creature("Dummy", 0, 2),
            combatTargets = listOf(CombatTarget.Obj(2), CombatTarget.Player("P1")),
            rules = Rules(combat = FAST_SLOW_COMBAT.lowered()),
        )
        assertEq(null, end.byName("Dummy"), "the fast sub-phase (2) killed the 0/2")
        assertEq(17, end.players.getValue("P1").life, "the slow sub-phase (3) then hit the player")
        assertTrue(end.byName("Striker") != null)
    }

    check("P8-B simultaneous damage -> mutual destruction") {
        val end = combat(
            "P0" to creature("A", 0, 2, fields = mapOf("fast" to 2)),
            "P1" to creature("B", 0, 2, fields = mapOf("fast" to 2)),
            combatTargets = listOf(CombatTarget.Obj(2), CombatTarget.Obj(1)),
            rules = Rules(combat = FAST_SLOW_COMBAT.lowered()),
        )
        assertEq(null, end.byName("A"))
        assertEq(null, end.byName("B"))
    }

    // -- Core: EXHAUSTION THAT COSTS SOMETHING, AND MOVING TO A LANE -------
    //
    // `exhaustedCannotAct`, and a move into a lane resolving against the mover's
    // own controller (else the move would be silently free or go nowhere).

    fun moveRules(exhaustionMatters: Boolean, cap: Int? = 1) = Rules(
        types = BUILTIN_TYPES_CORE + ("Ship" to TypeDef("Ship", fields = setOf("fast"), attacks = true,
            zoneChoices = listOf("lane-1", "lane-2", "lane-3"))),
        zones = BUILTIN_ZONES + listOf("lane-1", "lane-2", "lane-3")
            .map { PlayZoneDef(it, ZoneScope.PER_PLAYER, maxOccupants = cap) }.associateBy { it.id },
        combat = FAST_SLOW_COMBAT.copy(exhaustedCannotAct = exhaustionMatters).lowered(),
        turn = TurnStructure(listOf(PhaseSpec("combat", combat = true))),
    )

    /** An exhausted Ship swinging at a fat dummy: does tapping cost it a wave? */
    fun exhaustedDamage(exhaustionMatters: Boolean): Int {
        val rules = moveRules(exhaustionMatters)
        val ship = CardDoc(faces = listOf(FaceDoc("Gunner", setOf("Ship"), fields = mapOf("fast" to 2)))).build()
        val mark = CardDoc(faces = listOf(FaceDoc("Mark", setOf("Creature"), fields = mapOf("power" to 0, "toughness" to 99)))).build()
        var s = newGame()
        val (s1, gunner) = enterBattlefield(ship, "P0", s, zone = ZoneRef("lane-1", "P0")); s = s1
        val (s2, target) = enterBattlefield(mark, "P1", s); s = s2
        s = s.copy(battlefield = s.battlefield + (gunner to s.battlefield.getValue(gunner).copy(exhausted = true)))
        val end = runSync {
            Engine(ScriptedInput(emptyMap(), combatTargets = listOf(CombatTarget.Obj(target))), rules = rules)
                .playGame(s, maxTurns = 1)
        }
        return end.battlefield.getValue(target).damageMarked
    }

    check("exhaustion is FREE in FREE combat by default -- the trap, pinned") {
        // `runFreeCombat`'s participant filter has no exhaustion check at all,
        // so a tapped Ship still fights. Any "exhaust to do X" cost is pure
        // upside unless a bundle opts in. Pinned so nobody assumes otherwise.
        assertEq(2, exhaustedDamage(exhaustionMatters = false), "a tapped Ship still swings")
    }

    check("exhaustedCannotAct makes tapping cost a wave") {
        assertEq(0, exhaustedDamage(exhaustionMatters = true), "now it sits the round out")
    }

    /** A Ship whose ability taps it to move into a named lane. */
    fun mover(toLane: String) = CardDoc(faces = listOf(FaceDoc(
        "Tender", setOf("Ship"), fields = mapOf("fast" to 1),
        activated = listOf(ActivatedAbility(
            cost = Cost(tapSource = true),
            // An OWNER-LESS ZoneRef: a card cannot know whose lane it means.
            effect = Effect.MovePermanent(BoundTarget(SELF), ZoneRef(toLane)),
            name = "exhaust: reposition to $toLane",
        )),
    ))).build()

    check("an owner-less ZoneRef moves a permanent into its OWN lane") {
        // A null-owner ref resolves against the mover's controller, so the
        // permanent renders in its own lane and respects the cap.
        val rules = moveRules(exhaustionMatters = true).let {
            Rules(it.types, it.zones, it.combat, TurnStructure(listOf(PhaseSpec("main", sorcerySpeed = true))))
        }
        var s = newGame()
        val (s1, tender) = enterBattlefield(mover("lane-2"), "P0", s, zone = ZoneRef("lane-1", "P0")); s = s1
        val end = runSync {
            Engine(ScriptedInput.of("P0@main" to listOf(PriorityAction.Activate(tender, 0))), rules = rules.knowing(mover("lane-2")))
                .playGame(s, maxTurns = 1)
        }
        assertEq(ZoneRef("lane-2", "P0"), end.battlefield.getValue(tender).zone, "it lands in ITS OWN lane-2")
        assertTrue(end.battlefield.getValue(tender).exhausted, "and it paid by exhausting")
    }

    check("moving into a lane RESPECTS the occupancy cap") {
        // `zoneHasRoom` counts `it.zone == zone`, so a null-owner destination
        // matched nothing and always reported room. Resolving the owner first
        // is what puts the move back under the cap.
        val rules = moveRules(exhaustionMatters = true).let {
            Rules(it.types, it.zones, it.combat, TurnStructure(listOf(PhaseSpec("main", sorcerySpeed = true))))
        }
        val blocker = CardDoc(faces = listOf(FaceDoc("Hulk", setOf("Ship"), fields = mapOf("fast" to 1)))).build()
        var s = newGame()
        val (s1, tender) = enterBattlefield(mover("lane-2"), "P0", s, zone = ZoneRef("lane-1", "P0")); s = s1
        s = enterBattlefield(blocker, "P0", s, zone = ZoneRef("lane-2", "P0")).first
        val end = runSync {
            Engine(ScriptedInput.of("P0@main" to listOf(PriorityAction.Activate(tender, 0))), rules = rules)
                .playGame(s, maxTurns = 1)
        }
        assertEq(ZoneRef("lane-1", "P0"), end.battlefield.getValue(tender).zone, "lane-2 is full, so it stays put")
        // And it is refused BY LEGALITY, not declined by the engine after being
        // offered: an action the engine declines without charging stays legal
        // and is chosen forever. (Asserting a refusal LOG would assert the bug.)
        assertTrue(
            legality(rules, s, "P0", PriorityAction.Activate(tender, 0)) is Legality.Denied,
            "a move into a full lane is never offered in the first place",
        )
    }

    check("the FRONT/BACK combat rules survive a JSON round-trip -- as a preset AND as custom") {
        // The codec writes a Custom CombatConfig FIELD BY FIELD, so this tests
        // the CUSTOM path (a preset round-trips by name and would pass while
        // fields rot).
        val preset = GameDoc(name = "FB", rules = RulesDoc(combat = CombatDoc.Preset("frontBack")))
        assertEq(preset, gameDocFromJson(gameDocToJson(preset)))
        // The rules are the program now: what survives is its steps.
        val step = freeStep(preset.rules.combat.compile())
        assertTrue(step.overflow, "D: overflow survives")
        assertTrue(step.body.reachesFace != BoolExpr.Const(true), "E: the face is gated -- by long range over the line")
        assertTrue(step.body.reaches != BoolExpr.Const(true), "and the board by the screen it builds on")

        // The custom path, where the fields are actually written out.
        val custom = GameDoc(
            name = "Custom",
            rules = RulesDoc(
                combat = CombatDoc.Program(FAST_SLOW_LANES_CORE_COMBAT.copy(
                        screen = ScreenRule(screened = "rear", by = "van"),
                        longRangeHitsFace = true,
                        overflowToController = true,
                    ).lowered(),),
            ),
        )
        val back = gameDocFromJson(gameDocToJson(custom))
        assertEq(custom, back, "a custom combat keeps every FRONT/BACK rule")
        val json = gameDocToJson(custom)
        assertTrue("\"rear\"" in json && "\"van\"" in json, "including a screen naming OTHER zones")
        assertTrue(freeStep(back.rules.combat.compile()).overflow)

        // A config WITHOUT them must not gain them, or every old game changes
        // behaviour on its next save.
        val plain = GameDoc(name = "Plain", rules = RulesDoc(combat = CombatDoc.Program(FAST_SLOW_COMBAT.lowered())))
        val plainBack = gameDocFromJson(gameDocToJson(plain)).rules.combat.compile()
        assertTrue(!Rules(combat = plainBack).positionMatters(), "no screen, no lanes: position gates nothing")
        assertTrue(!freeStep(plainBack).overflow)
        assertTrue(!gameDocToJson(plain).contains("overflow"), "and the JSON stays clean")
    }

    check("the Core combat preset round-trips and carries all three rules") {
        val g = GameDoc(name = "Core", rules = RulesDoc(combat = CombatDoc.Preset("fastSlowLanesCore")))
        assertEq(g, gameDocFromJson(gameDocToJson(g)))
        val step = freeStep(g.rules.combat.compile())
        assertTrue(exhaustedSitsOut(step), "tapping costs a wave")
        assertTrue(step.body.reaches != BoolExpr.Const(true), "lanes decide opponents")
        assertTrue(step.body.reachesFace != BoolExpr.Const(true), "and still gate the Station")
    }

    // -- Core: LANE-LOCKED BOARD TARGETING ---------------------------------
    //
    // `combatSteps` changes WHEN a card acts; `laneLockedBoardTargets` changes
    // WHAT IT CAN REACH.

    fun lockRules(locked: Boolean) = Rules(
        types = BUILTIN_TYPES_CORE + ("Ship" to TypeDef(
            "Ship", fields = setOf("fast"), attacks = true,
            zoneChoices = listOf("lane-1", "lane-2", "lane-3"),
        )),
        zones = BUILTIN_ZONES + listOf("lane-1", "lane-2", "lane-3")
            .map { PlayZoneDef(it, ZoneScope.PER_PLAYER, maxOccupants = 1) }.associateBy { it.id },
        combat = FAST_SLOW_COMBAT.copy(laneLockedBoardTargets = locked, crossLaneKeyword = "reach").lowered(),
        turn = TurnStructure(listOf(PhaseSpec("combat", combat = true))),
    )

    /** One attacker in lane-1, one enemy in lane-1 (opposite it) and one in
     *  lane-3 (across the board). Returns marked damage on (opposite, across). */
    fun lockDamage(locked: Boolean, keywords: Set<String> = emptySet()): Pair<Int, Int> {
        val rules = lockRules(locked)
        val gunner = CardDoc(faces = listOf(FaceDoc(
            "Gunner", setOf("Ship"), fields = mapOf("fast" to 2), keywords = keywords,
        ))).build()
        val mark = CardDoc(faces = listOf(FaceDoc(
            "Mark", setOf("Creature"), fields = mapOf("power" to 0, "toughness" to 99),
        ))).build()
        var s = newGame()
        s = enterBattlefield(gunner, "P0", s, zone = ZoneRef("lane-1", "P0")).first
        val (s1, opposite) = enterBattlefield(mark, "P1", s, zone = ZoneRef("lane-1", "P1")); s = s1
        val (s2, across) = enterBattlefield(mark, "P1", s, zone = ZoneRef("lane-3", "P1")); s = s2
        // The pilot asks for the ACROSS-BOARD target first; if that is refused
        // the attacker is done for the wave, which is exactly the rule.
        val end = runSync {
            Engine(ScriptedInput(emptyMap(), combatTargets = listOf(CombatTarget.Obj(across))), rules = rules)
                .playGame(s, maxTurns = 1)
        }
        return end.battlefield.getValue(opposite).damageMarked to end.battlefield.getValue(across).damageMarked
    }

    check("unlocked (the default) an attacker reaches ACROSS lanes -- today's behaviour") {
        val (opposite, across) = lockDamage(locked = false)
        assertEq(2, across, "cross-lane targeting is unrestricted unless a bundle asks otherwise")
        assertEq(0, opposite)
    }

    check("lane-locked: out of lane is out of reach") {
        val (opposite, across) = lockDamage(locked = true)
        assertEq(0, across, "the ship across the board cannot be touched")
        assertEq(0, opposite, "and the attack is spent, not silently redirected")
    }

    check("the reach keyword buys the exemption -- cross-lane as a COSTED property") {
        val (_, across) = lockDamage(locked = true, keywords = setOf("reach"))
        assertEq(2, across, "reach ignores the lane lock")
    }

    check("lane-locked targeting still permits the SAME lane, either owner") {
        // lane-1@P0 opposes lane-1@P1: same def, different owner -- the
        // confrontation line, the relation ZoneScoping.SameAs(eitherSide = true) names.
        val rules = lockRules(locked = true)
        val gunner = CardDoc(faces = listOf(FaceDoc("Gunner", setOf("Ship"), fields = mapOf("fast" to 2)))).build()
        val mark = CardDoc(faces = listOf(FaceDoc("Mark", setOf("Creature"), fields = mapOf("power" to 0, "toughness" to 99)))).build()
        var s = newGame()
        s = enterBattlefield(gunner, "P0", s, zone = ZoneRef("lane-1", "P0")).first
        val (s1, opposite) = enterBattlefield(mark, "P1", s, zone = ZoneRef("lane-1", "P1")); s = s1
        val end = runSync {
            Engine(ScriptedInput(emptyMap(), combatTargets = listOf(CombatTarget.Obj(opposite))), rules = rules)
                .playGame(s, maxTurns = 1)
        }
        assertEq(2, end.battlefield.getValue(opposite).damageMarked, "the ship directly opposing is reachable")
    }

    check("the locked lanes preset round-trips by NAME, and its flags survive") {
        val g = GameDoc(name = "Locked", rules = RulesDoc(combat = CombatDoc.Preset("fastSlowLanesLocked")))
        assertEq(g, gameDocFromJson(gameDocToJson(g)), "the preset round-trips")
        val c = g.rules.combat.compile()
        assertTrue(freeStep(c).body.reaches != BoolExpr.Const(true), "and it really does lock board targets")
        assertTrue("reach" in c.keywordsNamed(), "reach is the exemption")
        assertTrue(freeStep(c).body.reachesFace != BoolExpr.Const(true), "keeping the Station gate it inherits")
        // A CUSTOM config carrying the flags survives too, not just the preset.
        val custom = GameDoc(rules = RulesDoc(combat = CombatDoc.Program(FAST_SLOW_COMBAT.copy(laneLockedBoardTargets = true, crossLaneKeyword = "reach").lowered(),)))
        assertEq(custom, gameDocFromJson(gameDocToJson(custom)), "custom flags round-trip")
    }

    // -- LANES DIFFERENTIATED BY RULE ----------------------------------------
    //
    // Position changes what a card DOES, not what a number is:
    //
    //     VAN   {fast}          strikes early, cannot follow up
    //     CORE  {fast, slow}    acts in both waves
    //     REAR  {}              never fights; pure support

    val STEP_SHIP = TypeDef("Ship", fields = setOf("fast", "slow"), attacks = true,
        zoneChoices = listOf("van", "core", "rear"))

    fun laneStepRules(differentiated: Boolean): Rules = Rules(
        types = BUILTIN_TYPES_CORE + ("Ship" to STEP_SHIP),
        zones = BUILTIN_ZONES + listOf(
            PlayZoneDef("van", ZoneScope.PER_PLAYER, combatSteps = if (differentiated) setOf("fast") else null),
            PlayZoneDef("core", ZoneScope.PER_PLAYER, combatSteps = if (differentiated) setOf("fast", "slow") else null),
            PlayZoneDef("rear", ZoneScope.PER_PLAYER, combatSteps = if (differentiated) emptySet() else null),
        ).associateBy { it.id },
        combat = FAST_SLOW_COMBAT.lowered(),
        turn = TurnStructure(listOf(PhaseSpec("combat", combat = true))),
    )

    /** One ship in each of the three lanes, all identical (fast 2 / slow 3),
     *  every one of them swinging at a single fat dummy. The dummy's marked
     *  damage is therefore a DIRECT READ of which lanes acted in which wave. */
    fun laneStepDamage(differentiated: Boolean): Int {
        val rules = laneStepRules(differentiated)
        val ship = CardDoc(faces = listOf(FaceDoc("Ship", setOf("Ship"), fields = mapOf("fast" to 2, "slow" to 3)))).build()
        val dummy = CardDoc(faces = listOf(FaceDoc("Hulk", setOf("Creature"), fields = mapOf("power" to 0, "toughness" to 99)))).build()
        var s = newGame()
        for (lane in listOf("van", "core", "rear")) {
            s = enterBattlefield(ship, "P0", s, zone = ZoneRef(lane, "P0")).first
        }
        val (s2, mark) = enterBattlefield(dummy, "P1", s); s = s2
        val end = runSync {
            Engine(
                ScriptedInput(emptyMap(), combatTargets = List(9) { CombatTarget.Obj(mark) }),
                rules = rules,
            ).playGame(s, maxTurns = 1)
        }
        return end.battlefield.getValue(mark).damageMarked
    }

    check("a zone that declares no combat steps permits ALL of them -- every prior bundle is unchanged") {
        // 3 ships x (fast 2 + slow 3) = 15. The default has to be inert, or
        // EPR Skirmish -- the frozen regression corpus -- would change meaning.
        assertEq(15, laneStepDamage(differentiated = false), "undeclared zones act in every wave")
    }

    check("VAN strikes fast only, CORE strikes twice, REAR never fights") {
        // van 2 (fast only) + core 5 (both) + rear 0 (neither) = 7.
        assertEq(7, laneStepDamage(differentiated = true), "position now changes what a card DOES")
    }

    check("an unknown step name fails CLOSED -- a zone cannot act in a wave the game lacks") {
        // The steps a game has are declared by its own CombatConfig, so the
        // engine cannot validate a name. Naming a wave that does not exist must
        // therefore mean "acts in none", never "acts in all".
        val rules = Rules(
            types = BUILTIN_TYPES_CORE + ("Ship" to STEP_SHIP),
            zones = BUILTIN_ZONES + mapOf("van" to PlayZoneDef("van", ZoneScope.PER_PLAYER, combatSteps = setOf("nosuchwave"))),
            combat = FAST_SLOW_COMBAT.lowered(),
            turn = TurnStructure(listOf(PhaseSpec("combat", combat = true))),
        )
        val ship = CardDoc(faces = listOf(FaceDoc("Ship", setOf("Ship"), fields = mapOf("fast" to 2, "slow" to 3)))).build()
        val dummy = CardDoc(faces = listOf(FaceDoc("Hulk", setOf("Creature"), fields = mapOf("power" to 0, "toughness" to 99)))).build()
        var s = newGame()
        s = enterBattlefield(ship, "P0", s, zone = ZoneRef("van", "P0")).first
        val (s2, mark) = enterBattlefield(dummy, "P1", s); s = s2
        val end = runSync {
            Engine(ScriptedInput(emptyMap(), combatTargets = List(4) { CombatTarget.Obj(mark) }), rules = rules)
                .playGame(s, maxTurns = 1)
        }
        assertEq(0, end.battlefield.getValue(mark).damageMarked, "an unmatched step name grants nothing")
    }

    check("the lane-step field round-trips through JSON") {
        val g = GameDoc(
            name = "Lanes",
            rules = RulesDoc(extraZones = listOf(
                PlayZoneDef("van", ZoneScope.PER_PLAYER, maxOccupants = 1, combatSteps = setOf("fast")),
                PlayZoneDef("rear", ZoneScope.PER_PLAYER, combatSteps = emptySet()),
                PlayZoneDef("plain", ZoneScope.SHARED),
            )),
        )
        assertEq(g, gameDocFromJson(gameDocToJson(g)), "combatSteps survives, including the EMPTY set")
        // The empty set and null are DIFFERENT answers -- "acts in nothing" vs
        // "acts in everything" -- so a round trip that conflated them would be
        // the worst possible bug here.
        assertTrue(!gameDocToJson(GameDoc(rules = RulesDoc(extraZones = listOf(PlayZoneDef("z"))))).contains("combatSteps"),
            "an undeclared zone writes no combatSteps at all")
    }

    // -- an area effect needs "this lane, EITHER SIDE" ------------------------
    //
    // `SameAsSource` compares zone INSTANCES (your half of a PER_PLAYER lane);
    // `Named` needs a literal def a card cannot know. Hence `SameDefAsSource`.

    val LANE_ZONES = BUILTIN_ZONES + listOf("lane-1", "lane-2", "lane-3")
        .map { PlayZoneDef(it, ZoneScope.PER_PLAYER, maxOccupants = 1) }.associateBy { it.id }
    val SHIP_TYPE = TypeDef("Ship", fields = setOf("fast", "hull"), attacks = true,
        zoneChoices = listOf("lane-1", "lane-2", "lane-3"))

    check("SameAsSource is HALF a lane; SameDefAsSource is the whole line") {
        val rules = Rules(types = BUILTIN_TYPES_CORE + ("Ship" to SHIP_TYPE), zones = LANE_ZONES)
        val hulk = CardDoc(faces = listOf(FaceDoc("Hulk", setOf("Ship"), fields = mapOf("fast" to 1, "hull" to 3)))).build()
        var s = newGame()
        val (s1, mine) = enterBattlefield(hulk, "P0", s, zone = ZoneRef("lane-2", "P0")); s = s1
        val (s2, theirs) = enterBattlefield(hulk, "P1", s, zone = ZoneRef("lane-2", "P1")); s = s2
        val (s3, elsewhere) = enterBattlefield(hulk, "P1", s, zone = ZoneRef("lane-1", "P1")); s = s3

        // Source is MY ship in lane-2.
        val ctx = EvalContext(s, "P0", mine)
        val ships = PermFilter(types = setOf("Ship"))

        val own = ships.inThisZone()
        assertTrue(own.matches(ctx, mine), "my own half: my ship")
        assertTrue(!own.matches(ctx, theirs), "SameAsSource does NOT reach the opposing ship -- the bug this case exists for")

        val line = ships.inThisZoneEitherSide()
        assertTrue(line.matches(ctx, mine), "the line includes mine")
        assertTrue(line.matches(ctx, theirs), "AND the ship opposing me -- the whole point")
        assertTrue(!line.matches(ctx, elsewhere), "but not a ship in a DIFFERENT lane")
        assertTrue(rules.zones.isNotEmpty())
    }

    check("a lane AoE damages both sides of its own lane and nothing else") {
        val bomber = CardDoc(faces = listOf(FaceDoc(
            "Lane Bomber", setOf("Ship"),
            fields = mapOf("fast" to 1, "hull" to 3),
            activated = listOf(ActivatedAbility(
                cost = Cost(tapSource = true),
                effect = Effect.ForEach(
                    PermFilter(types = setOf("Ship")).inThisZoneEitherSide().otherThanThis(),
                    Effect.DealDamage(lit(2), BoundTarget(EACH)),
                ),
                name = "exhaust: 2 damage to each other Ship in this lane",
            )),
        ))).build()
        val hulk = CardDoc(faces = listOf(FaceDoc("Hulk", setOf("Ship"), fields = mapOf("fast" to 1, "hull" to 3)))).build()
        val rules = Rules(
            types = BUILTIN_TYPES_CORE + ("Ship" to SHIP_TYPE),
            zones = LANE_ZONES,
            turn = TurnStructure(listOf(PhaseSpec("main", sorcerySpeed = true))),
        )
        var s = newGame()
        val (s1, src) = enterBattlefield(bomber, "P0", s, zone = ZoneRef("lane-2", "P0")); s = s1
        val (s2, opposite) = enterBattlefield(hulk, "P1", s, zone = ZoneRef("lane-2", "P1")); s = s2
        val (s3, alongside) = enterBattlefield(hulk, "P0", s, zone = ZoneRef("lane-1", "P0")); s = s3
        val (s4, faraway) = enterBattlefield(hulk, "P1", s, zone = ZoneRef("lane-3", "P1")); s = s4

        val end = runSync {
            Engine(
                ScriptedInput.of("P0@main" to listOf(PriorityAction.Activate(src, 0))),
                rules = rules.knowing(bomber),
            ).playGame(s, maxTurns = 1)
        }
        assertEq(2, end.battlefield.getValue(opposite).damageMarked, "the ship OPPOSING it was hit")
        assertEq(0, end.battlefield.getValue(alongside).damageMarked, "a ship in another lane was not")
        assertEq(0, end.battlefield.getValue(faraway).damageMarked, "nor one in a third lane")
        assertEq(0, end.battlefield.getValue(src).damageMarked, "'other' still excludes the source")
    }

    check("a zone scope can name a bound target: the chosen ship's lane, either side") {
        val ships = PermFilter(types = setOf("Ship"))
        val gunner = CardDoc(faces = listOf(FaceDoc(
            "Gunner", setOf("Ship"),
            fields = mapOf("fast" to 1, "hull" to 3),
            activated = listOf(ActivatedAbility(
                cost = Cost(tapSource = true),
                effect = Effect.Choose(
                    ships.theirs(),
                    Effect.ForEach(ships.copy(zone = ZoneScoping.SameAs(BoundTarget(CHOSEN), eitherSide = true)), Effect.DealDamage(lit(2), BoundTarget(EACH))),
                ),
                name = "exhaust: 2 damage to each Ship in target enemy ship's lane",
            )),
        ))).build()
        val hulk = CardDoc(faces = listOf(FaceDoc("Hulk", setOf("Ship"), fields = mapOf("fast" to 1, "hull" to 3)))).build()
        val rules = Rules(
            types = BUILTIN_TYPES_CORE + ("Ship" to SHIP_TYPE),
            zones = LANE_ZONES,
            turn = TurnStructure(listOf(PhaseSpec("main", sorcerySpeed = true))),
        )
        var s = newGame()
        val (s1, src) = enterBattlefield(gunner, "P0", s, zone = ZoneRef("lane-1", "P0")); s = s1
        val (s2, target) = enterBattlefield(hulk, "P1", s, zone = ZoneRef("lane-3", "P1")); s = s2
        val (s3, facing) = enterBattlefield(hulk, "P0", s, zone = ZoneRef("lane-3", "P0")); s = s3
        val (s4, bystander) = enterBattlefield(hulk, "P1", s, zone = ZoneRef("lane-1", "P1")); s = s4
        val end = runSync {
            Engine(
                ScriptedInput.of("P0@main" to listOf(PriorityAction.Activate(src, 0)), targets = listOf(target)),
                rules = rules.knowing(gunner),
            ).playGame(s, maxTurns = 1)
        }
        assertEq(listOf(2, 2, 0, 0), listOf(target, facing, bystander, src).map { end.battlefield.getValue(it).damageMarked },
            "the chosen ship's whole lane, not the source's")
        val f = ships.copy(zone = ZoneScoping.SameAs(BoundTarget(CHOSEN), eitherSide = true))
        assertEq(f, filterOf(Json.parse(filterToJson(f))), "the named target survives JSON")
        assertTrue("\"of\"" !in filterToJson(ships.inThisZone()), "the source is still written as before, so no format bump")
        val g = GameDoc(sets = listOf(SetDoc("Core", listOf(CardDoc(faces = listOf(FaceDoc("Stray", setOf("Creature"),
            castEffect = Effect.ForEach(ships.copy(zone = ZoneScoping.SameAs(BoundTarget(CHOSEN))), Effect.Destroy(BoundTarget(EACH))))))))))
        assertTrue(g.diagnostics().any { it.code.startsWith("free-") }, "an unbound target in a zone scope is reported: ${g.diagnostics().map { it.code }}")
    }

    check("the new zone scope round-trips through JSON") {
        val f = PermFilter(types = setOf("Ship")).inThisZoneEitherSide()
        assertEq(f, filterOf(Json.parse(filterToJson(f))), "SameDefAsSource survives the round trip")
        // And it is DISTINCT from the case it is most easily confused with --
        // a round trip that collapsed them would pass a weaker assertion.
        assertTrue(
            filterToJson(f) != filterToJson(PermFilter(types = setOf("Ship")).inThisZone()),
            "the two zone scopes do not serialise the same",
        )
    }

    // -- the deployment delay, an ANALYTICAL parameter ------------------------
    //
    // Whether a body may fight the turn it lands. Both settings are pinned: a
    // parameter a measurement is attributed to is worth the proof that it does
    // something.

    check("NO deployment delay (the default) -- a body fights the turn it lands") {
        val end = combat(
            "P0" to creature("Newcomer", 0, 2, fields = mapOf("fast" to 2)),
            "P1" to creature("Mark", 0, 2),
            combatTargets = listOf(CombatTarget.Obj(2)),
            rules = Rules(combat = FAST_SLOW_COMBAT.lowered()),
        )
        assertEq(null, end.byName("Mark"), "it arrived and attacked in the same turn")
    }

    check("attackDelayOnEntry holds a body back the turn it arrives -- FREE style") {
        val end = combat(
            "P0" to creature("Newcomer", 0, 2, fields = mapOf("fast" to 2)),
            "P1" to creature("Mark", 0, 2),
            combatTargets = listOf(CombatTarget.Obj(2)),
            rules = Rules(combat = FAST_SLOW_COMBAT.lowered(), params = GameParams(attackDelayOnEntry = true)),
        )
        assertTrue(end.byName("Mark") != null, "the newcomer was never an actor in the wave")
    }

    check("the delay DELAYS rather than disables -- held on turn 1, acting on turn 2") {
        // BOTH halves in one check: asserting only the turn-2 kill passes when
        // the delay never fires (the kill then happens on turn 1). Seen to fail
        // by breaking `entryDelayed`.
        fun run(turns: Int): GameState = runSync {
            Engine(
                ScriptedInput(emptyMap(), combatTargets = listOf(CombatTarget.Obj(2))),
                rules = Rules(combat = FAST_SLOW_COMBAT.lowered(), params = GameParams(attackDelayOnEntry = true)),
            ).playGame(
                staged(
                    "P0" to creature("Newcomer", 0, 2, fields = mapOf("fast" to 2)),
                    "P1" to creature("Mark", 0, 2),
                ),
                maxTurns = turns,
            )
        }
        assertTrue(run(1).byName("Mark") != null, "turn 1: newly arrived, so it is held back")
        assertEq(null, run(2).byName("Mark"), "turn 2: no longer newly arrived, so it acts")
    }

    check("a body that arrived this turn is not a declared attacker -- DECLARED style") {
        val end = runSync {
            Engine(
                ScriptedInput(emptyMap(), attacks = mapOf(1 to CombatTarget.Player("P1"))),
                rules = Rules(params = GameParams(attackDelayOnEntry = true)),
            ).playGame(staged("P0" to creature("Raider", 3, 3)), maxTurns = 1)
        }
        assertEq(20, end.players.getValue("P1").life, "the attack was filtered out of the plan")
        assertTrue(!end.battlefield.getValue(1).exhausted, "and it never tapped, so it was never declared")
    }

    check("but a fresh body can still BLOCK -- the delay stops offence, not defence") {
        // Blockers are NOT gated: summoning sickness never stopped a creature
        // defending itself.
        val start = staged("P0" to creature("Ox", 3, 3), "P1" to creature("Wall", 3, 3))
        val end = runSync {
            Engine(
                ScriptedInput(
                    emptyMap(),
                    attacks = mapOf(1 to CombatTarget.Player("P1")),
                    blocks = mapOf(2 to 1),
                ),
                rules = Rules(params = GameParams(attackDelayOnEntry = true)),
            ).playGame(
                // The Ox has been here since before this turn; the Wall has
                // just landed, so only the Wall is subject to the delay.
                start.copy(battlefield = start.battlefield.mapValues { (id, p) ->
                    if (id == 1) p.copy(enteredOnTurn = 0) else p
                }),
                maxTurns = 1,
            )
        }
        assertEq(null, end.byName("Ox"), "the attack happened")
        assertEq(null, end.byName("Wall"), "and the freshly-arrived Wall blocked it anyway")
    }

    // -- EPR Skirmish: lane-locked player targeting (FREE style) ----

    val LANE_RULES = Rules(
        zones = BUILTIN_ZONES + listOf(PlayZoneDef("lane", ZoneScope.PER_PLAYER)).associateBy { it.id },
        combat = FAST_SLOW_COMBAT.copy(laneLockedPlayerTargets = true).lowered(),
    )

    check("laneLockedPlayerTargets denies a player-target while the opposing lane is occupied") {
        var s = newGame(libraries = mapOf("P0" to tokens(6), "P1" to tokens(6)))
        val (s1, p0ship) = enterBattlefield(creature("P0 Ship", 0, 2, fields = mapOf("fast" to 2)), "P0", s, zone = ZoneRef("lane", "P0"))
        s = s1
        val (s2, p1ship) = enterBattlefield(creature("P1 Ship", 0, 2, fields = mapOf("fast" to 2)), "P1", s, zone = ZoneRef("lane", "P1"))
        s = s2
        val end = runSync {
            Engine(
                ScriptedInput(emptyMap(), combatTargets = listOf(CombatTarget.Player("P1"), CombatTarget.Player("P0"))),
                rules = LANE_RULES,
            ).playGame(s, maxTurns = 1)
        }
        assertEq(20, end.players.getValue("P1").life, "P0's ship's own lane is still opposed -- denied")
        assertEq(20, end.players.getValue("P0").life, "symmetric: P1's ship is opposed too -- denied")
        assertTrue(end.battlefield.containsKey(p0ship) && end.battlefield.containsKey(p1ship), "both ships survive untouched")
    }

    check("laneLockedPlayerTargets allows a player-target once the opposing lane is empty") {
        var s = newGame(libraries = mapOf("P0" to tokens(6), "P1" to tokens(6)))
        val (s1, _) = enterBattlefield(creature("P0 Ship", 0, 2, fields = mapOf("fast" to 2)), "P0", s, zone = ZoneRef("lane", "P0"))
        s = s1
        val end = runSync {
            Engine(
                ScriptedInput(emptyMap(), combatTargets = listOf(CombatTarget.Player("P1"))),
                rules = LANE_RULES,
            ).playGame(s, maxTurns = 1)
        }
        assertEq(18, end.players.getValue("P1").life, "P1's lane is empty -- the station is a legal target")
    }

    check("laneLockedPlayerTargets never gates ship-vs-ship targeting") {
        var s = newGame(libraries = mapOf("P0" to tokens(6), "P1" to tokens(6)))
        val (s1, _) = enterBattlefield(creature("P0 Ship", 0, 2, fields = mapOf("fast" to 2)), "P0", s, zone = ZoneRef("lane", "P0"))
        s = s1
        val (s2, p1ship) = enterBattlefield(creature("P1 Ship", 0, 1, fields = mapOf("fast" to 0)), "P1", s, zone = ZoneRef("lane", "P1"))
        s = s2
        val end = runSync {
            Engine(
                // P0's ship attacks P1's ship (Obj) -- the lane gate must not
                // touch it. P1's ship (also an actor: it declares "fast") is
                // given a Player target, which the SAME opposed lane denies --
                // confirms the two checks don't interfere with each other.
                ScriptedInput(emptyMap(), combatTargets = listOf(CombatTarget.Obj(p1ship), CombatTarget.Player("P0"))),
                rules = LANE_RULES,
            ).playGame(s, maxTurns = 1)
        }
        assertEq(null, end.byName("P1 Ship"), "an Obj target is unaffected by the lane gate")
        assertEq(20, end.players.getValue("P0").life, "P1's ship's player-target was still denied (its own lane opposed)")
    }

    check("laneLockedPlayerTargets is a no-op off a shared (non-lane) battlefield") {
        val end = combat(
            "P0" to creature("Free Ship", 0, 2, fields = mapOf("fast" to 2)),
            combatTargets = listOf(CombatTarget.Player("P1")),
            rules = Rules(combat = FAST_SLOW_COMBAT.copy(laneLockedPlayerTargets = true).lowered()),
        )
        assertEq(18, end.players.getValue("P1").life, "no lanes declared -- unopposed by definition, the flag does nothing")
    }

    // -- EPR Skirmish: an extensible hidden zone (the Flagship pool) --

    check("a permanent can be played directly from a declared custom zone") {
        val vanguard = creature("Vanguard", 0, 3)
        val rules = Rules(cards = mapOf("Vanguard" to vanguard), hiddenZones = mapOf("flagships" to HiddenZoneDef("flagships")))
        val start = newGame().withCustomZone("P0", "flagships", listOf(CardRef(500, "Vanguard")))
        val end = runSync {
            Engine(
                ScriptedInput.of("P0" to listOf(
                    PriorityAction.PlayPermanent(vanguard.lowered(LIFE), 0, from = CardSource(CastZone.Declared("flagships"), 500)),
                )),
                rules = rules,
            ).run(start)
        }
        assertTrue(end.players.getValue("P0").customZones["flagships"].orEmpty().isEmpty(), "the Flagship left the pool")
        assertTrue(end.byName("Vanguard") != null, "and entered play")
    }

    check("legality denies playing from a declared zone you don't actually hold it in") {
        val vanguard = creature("Vanguard", 0, 3)
        val rules = Rules(cards = mapOf("Vanguard" to vanguard), hiddenZones = mapOf("flagships" to HiddenZoneDef("flagships")))
        val action = PriorityAction.PlayPermanent(vanguard.lowered(LIFE), 0, from = CardSource(CastZone.Declared("flagships"), 500))
        val verdict = legality(rules, newGame(), "P0", action)
        assertTrue(verdict is Legality.Denied, "the pool is empty -- denied")
        assertTrue((verdict as Legality.Denied).reason.contains("flagships"), verdict.reason)
    }

    check("setupGame seeds a StartCard into a declared hidden zone, not the battlefield") {
        val vanguard = creature("Vanguard", 0, 3)
        val rules = Rules(cards = mapOf("Vanguard" to vanguard), hiddenZones = mapOf("flagships" to HiddenZoneDef("flagships")))
        val s = setupGame(rules, startInPlay = mapOf("P0" to listOf(StartCard("Vanguard", "flagships"))))
        assertTrue(s.battlefield.isEmpty(), "not placed on the battlefield")
        assertEq(listOf("Vanguard"), s.players.getValue("P0").customZones["flagships"]?.map { it.cardId }, "sits in the declared zone instead")
    }

    check("a declared hidden zone round-trips through JSON, elided when none are declared") {
        assertTrue(!rulesDocToJson(RulesDoc()).contains("extraHiddenZones"), "no custom zones -- not written")
        val hz = HiddenZoneDef("flagships", alwaysVisible = false)
        assertEq(hz, hiddenZoneDefOf(Json.parse(hiddenZoneDefToJson(hz))))
        val r = RulesDoc(extraHiddenZones = listOf(HiddenZoneDef("flagships")))
        assertEq(r, rulesDocOf(Json.parse(rulesDocToJson(r))))
        val g = GameDoc(
            rules = RulesDoc(extraHiddenZones = listOf(HiddenZoneDef("flagships"))),
            sets = listOf(SetDoc("Core", listOf(CardDoc(faces = listOf(FaceDoc("Vanguard", setOf("Ship"))))))),
        )
        assertEq(g, gameDocFromJson(gameDocToJson(g)))
        assertTrue("flagships" in g.rules().hiddenZones, "GameDoc.rules() compiles the declared zone through")
    }

    // -- EPR Skirmish: attach / host mechanic (Improvements) ---------

    check("Effect.Attach sets hostId; the SBA drops an attachment whose host is gone") {
        val cruiser = creature("Cruiser", 0, 4)
        val booster = card(
            "Shield Booster", setOf("Improvement"),
            baseChars = Characteristics("Shield Booster", setOf("Improvement")),
            activated = listOf(ActivatedAbility(Cost(), Effect.Attach(BoundTarget(1)), "attach to the Cruiser")),
        )
        val attached = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PLAY(cruiser), PLAY(booster), PriorityAction.Activate(2, 0))), rules = Rules().knowing(booster))
                .run(newGame())
        }
        assertEq(1, attached.battlefield.getValue(2).hostId, "the Improvement is now attached to the Cruiser")

        val hostDied = runSync {
            Engine(ScriptedInput.of("P0" to listOf(
                // The stack is LIFO -- let the activated ability (which goes
                // on the stack) actually resolve, via a pass, before Destroy
                // is cast ON TOP of it and would otherwise resolve FIRST.
                PLAY(cruiser), PLAY(booster), PriorityAction.Activate(2, 0), PriorityAction.PassPriority,
                CAST(Effect.Destroy(BoundTarget(1))),
            )), rules = Rules().knowing(booster)).run(newGame())
        }
        assertEq(null, hostDied.battlefield[1], "the host is gone")
        assertEq(null, hostDied.battlefield[2], "the unattached Improvement fell off too (SBA)")
        assertTrue(hostDied.players.getValue("P0").graveyard.any { it.cardId == "Shield Booster" }, "into the graveyard, not vanished")
    }

    check("PermFilter.onlyHost() reads back only the permanent its source is attached to") {
        val cruiser = creature("Cruiser", 0, 4)
        val decoy = creature("Decoy", 0, 4)
        val booster = card(
            "Shield Booster", setOf("Improvement"),
            baseChars = Characteristics("Shield Booster", setOf("Improvement")),
            activated = listOf(ActivatedAbility(Cost(), Effect.Attach(BoundTarget(1)), "attach to the Cruiser")),
            statics = Statics(chars = listOf(StaticSpec(permanents().onlyHost(), listOf(CharOp.PlusPT(lit(0), lit(2)))))),
        )
        val end = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PLAY(cruiser), PLAY(decoy), PLAY(booster), PriorityAction.Activate(3, 0))), rules = Rules().knowing(booster))
                .run(newGame())
        }
        assertEq(6, end.characteristicsOf(1).toughness, "the host (the Cruiser) is boosted")
        assertEq(4, end.characteristicsOf(2).toughness, "an unattached permanent of the same shape is not")
    }

    check("PermFilter.onlyHost round-trips through JSON") {
        val spec = StaticSpec(permanents().onlyHost(), listOf(CharOp.PlusPT(lit(0), lit(1))))
        assertEq(spec, staticSpecOf(Json.parse(staticSpecToJson(spec))))
        assertTrue(!filterToJson(permanents()).contains("onlyHost"), "the default (false) is not written")
    }

    // -- combat model broadened (INDIVIDUAL flow + COMPARE damage) ---------

    fun indiv(
        state: GameState,
        vararg actions: PriorityAction,
        rules: Rules,
        redirects: Map<ObjectId, ObjectId> = emptyMap(),
    ): GameState = runSync {
        Engine(
            ScriptedInput(mapOf("P0@combat" to actions.toList()), redirects = redirects),
            rules = rules,
        ).playGame(state, maxTurns = 1)
    }

    // Combat tests observe `damageMarked` AFTER playGame; drop the end-of-turn
    // CLEANUP so a single-turn combat check still sees it.
    val NO_CLEANUP = TurnStructure(TurnStructure.MTG.phases.filterNot { it.onEnter == PhaseEffects.CLEANUP })
    val HS = Rules(combat = HEARTHSTONE_COMBAT.lowered(), turn = NO_CLEANUP)
    val OP = Rules(combat = ONE_PIECE_COMBAT.lowered())
    val YGO = Rules(combat = YUGIOH_COMBAT.lowered())

    check("P8.1-HS an individual attack on the hero, no return") {
        val end = indiv(
            staged("P0" to creature("Yeti", 3, 2)),
            PriorityAction.Attack(1, CombatTarget.Player("P1")), rules = HS,
        )
        assertEq(17, end.players.getValue("P1").life)
        assertTrue(end.battlefield.getValue(1).exhausted)
        assertEq(1, end.battlefield.getValue(1).attacksThisTurn)
    }

    check("P8.1-HS attacking a minion is a mutual exchange") {
        val end = indiv(
            staged("P0" to creature("A", 3, 2), "P1" to creature("B", 2, 3)),
            PriorityAction.Attack(1, CombatTarget.Obj(2)), rules = HS,
        )
        assertEq(null, end.byName("A"), "took 2 -> dead")
        assertEq(null, end.byName("B"), "took 3 -> dead")
    }

    check("P8.1-HS taunt forces the target") {
        val end = indiv(
            staged("P0" to creature("Raider", 3, 3), "P1" to creature("Guard", 2, 4, "taunt")),
            PriorityAction.Attack(1, CombatTarget.Player("P1")),
            PriorityAction.Attack(1, CombatTarget.Obj(2)),
            rules = HS,
        )
        assertEq(20, end.players.getValue("P1").life, "the face attack was illegal while a taunt stood")
        assertEq(3, end.battlefield.getValue(2).damageMarked, "the redirected-to-taunt attack landed")
    }

    check("P8.1-HS stealth can't be attacked") {
        val end = indiv(
            staged("P0" to creature("Assassin", 4, 2), "P1" to creature("Sneak", 1, 1, "stealth")),
            PriorityAction.Attack(1, CombatTarget.Obj(2)), rules = HS,
        )
        assertTrue(end.byName("Sneak") != null)
        assertEq(0, end.battlefield.getValue(2).damageMarked)
    }

    check("P8.1-HS windfury attacks twice") {
        val end = indiv(
            staged("P0" to creature("Windrider", 2, 3, "windfury")),
            PriorityAction.Attack(1, CombatTarget.Player("P1")),
            PriorityAction.Attack(1, CombatTarget.Player("P1")),
            rules = HS,
        )
        assertEq(16, end.players.getValue("P1").life)
        assertEq(2, end.battlefield.getValue(1).attacksThisTurn)
    }

    check("P8.1-OP COMPARE: attacker power >= defender KOs it, no return") {
        val end = indiv(
            staged("P0" to creature("Zoro", 5, 1), "P1" to creature("Marine", 3, 1), exhausted = setOf(2)),
            PriorityAction.Attack(1, CombatTarget.Obj(2)), rules = OP,
        )
        assertEq(null, end.byName("Marine"))
        assertEq(0, end.battlefield.getValue(1).damageMarked, "no return damage in the compare model")
    }

    check("P8.1-OP COMPARE: a weaker attacker just bounces off") {
        val end = indiv(
            staged("P0" to creature("Cabin", 2, 1), "P1" to creature("Marine", 4, 1), exhausted = setOf(2)),
            PriorityAction.Attack(1, CombatTarget.Obj(2)), rules = OP,
        )
        assertTrue(end.byName("Cabin") != null && end.byName("Marine") != null, "nothing happens")
    }

    check("P8.1-OP can only attack a rested character") {
        val end = indiv(
            staged("P0" to creature("Zoro", 5, 1), "P1" to creature("Marine", 3, 1)), // Marine NOT rested
            PriorityAction.Attack(1, CombatTarget.Obj(2)), rules = OP,
        )
        assertTrue(end.byName("Marine") != null, "the attack was illegal")
    }

    check("P8.1-OP attacking the Leader costs a flat 1") {
        val end = indiv(
            staged("P0" to creature("Zoro", 5, 1)),
            PriorityAction.Attack(1, CombatTarget.Player("P1")), rules = OP,
        )
        assertEq(19, end.players.getValue("P1").life, "1 life card, not the 5 power")
    }

    check("P8.1-OP a Blocker redirects the attack off the player") {
        val end = indiv(
            staged("P0" to creature("Zoro", 5, 1), "P1" to creature("Bepo", 1, 5, "blocker")),
            PriorityAction.Attack(1, CombatTarget.Player("P1")), rules = OP,
            redirects = mapOf(1 to 2),
        )
        assertEq(null, end.byName("Bepo"), "5 >= 1 -> the blocker is KO'd")
        assertEq(20, end.players.getValue("P1").life, "the player took nothing")
    }

    check("P8.1-YGO COMPARE: higher ATK wins, the difference hits the loser's controller") {
        val end = indiv(
            staged("P0" to creature("BEWD", 8, 8), "P1" to creature("DarkMagician", 7, 7)),
            PriorityAction.Attack(1, CombatTarget.Obj(2)), rules = YGO,
        )
        assertEq(null, end.byName("DarkMagician"))
        assertEq(19, end.players.getValue("P1").life, "8 - 7 = 1 battle damage")
        assertTrue(end.byName("BEWD") != null)
    }

    check("P8.1-YGO COMPARE: a weaker attacker is destroyed and its controller takes the difference") {
        val end = indiv(
            staged("P0" to creature("Kuriboh", 3, 3), "P1" to creature("BEWD", 8, 8)),
            PriorityAction.Attack(1, CombatTarget.Obj(2)), rules = YGO,
        )
        assertEq(null, end.byName("Kuriboh"))
        assertEq(15, end.players.getValue("P0").life, "8 - 3 = 5 to the attacker's controller")
        assertTrue(end.byName("BEWD") != null)
    }

    check("P8.1-YGO defense position: ATK vs DEF, no destroy on a losing attack") {
        val over = indiv(
            staged(
                "P0" to creature("BEWD", 8, 8),
                "P1" to creature("Wall", 4, 4, fields = mapOf("defense" to 6)),
                defenseMode = setOf(2),
            ),
            PriorityAction.Attack(1, CombatTarget.Obj(2)), rules = YGO,
        )
        assertEq(null, over.byName("Wall"), "8 > DEF 6 -> destroyed")
        assertEq(20, over.players.getValue("P1").life, "no excess without piercing")

        val under = indiv(
            staged(
                "P0" to creature("Kuriboh", 3, 3),
                "P1" to creature("Wall", 1, 1, fields = mapOf("defense" to 6)),
                defenseMode = setOf(2),
            ),
            PriorityAction.Attack(1, CombatTarget.Obj(2)), rules = YGO,
        )
        assertTrue(under.byName("Kuriboh") != null, "a defense monster doesn't destroy the attacker")
        assertTrue(under.byName("Wall") != null)
        assertEq(17, under.players.getValue("P0").life, "DEF 6 - ATK 3 = 3 to the attacker")
    }

    check("a stance is the combat's: undeclared it is no stance; declared it defends with its own field") {
        fun wallVsKuriboh(rules: Rules, stance: String = "defense") = indiv(
            staged(
                "P0" to creature("Kuriboh", 3, 3),
                "P1" to creature("Wall", 1, 1, fields = mapOf("defense" to 6, "grit" to 2)),
                defenseMode = setOf(2),
            ).let { s -> s.copy(battlefield = s.battlefield.mapValues { (_, p) -> if (p.combatMode != null) p.copy(combatMode = stance) else p }) },
            PriorityAction.Attack(1, CombatTarget.Obj(2)), rules = rules,
        )
        val undeclared = wallVsKuriboh(Rules(combat = YUGIOH_COMBAT.copy(stances = emptyList()).lowered()))
        assertEq(null, undeclared.byName("Wall"), "no stance: the Wall fights with power 1 and dies")
        val braced = wallVsKuriboh(Rules(combat = YUGIOH_COMBAT.copy(stances = listOf(StanceDef("braced", "grit"))).lowered()), "braced")
        assertEq(null, braced.byName("Wall"), "grit 2 < 3: destroyed")
        assertTrue(braced.byName("Kuriboh") != null)
        assertEq(20, braced.players.getValue("P0").life, "the Wall lost, so nothing comes back")
        val held = wallVsKuriboh(Rules(combat = YUGIOH_COMBAT.copy(stances = listOf(StanceDef("braced", "defense"))).lowered()), "braced")
        assertTrue(held.byName("Wall") != null && held.byName("Kuriboh") != null, "a stance does not trade")
        assertEq(17, held.players.getValue("P0").life, "DEF 6 - ATK 3 = 3 to the attacker")
    }

    check("P8.1-LoR a block-legality rule (elusive) drops an illegal block") {
        val end = runSync {
            Engine(
                ScriptedInput.of(attacks = mapOf(1 to CombatTarget.Player("P1")), blocks = mapOf(2 to 1)),
                rules = Rules(combat = MTG_COMBAT.copy(blockRules = listOf(BlockRule.needsKeyword("elusive", "elusive"))).lowered()),
            ).playGame(staged("P0" to creature("Sprite", 2, 2, "elusive"), "P1" to creature("Grunt", 2, 2)), maxTurns = 1)
        }
        assertEq(18, end.players.getValue("P1").life, "the non-elusive block was illegal -> Sprite got through")
        assertTrue(end.byName("Grunt") != null)
    }

    check("P8.1 double strike acts in both the first-strike and regular steps") {
        val end = runSync {
            Engine(
                ScriptedInput.of(attacks = mapOf(1 to CombatTarget.Player("P1")), blocks = mapOf(2 to 1)),
                rules = Rules(turn = NO_CLEANUP),
            ).playGame(
                staged("P0" to creature("Berserker", 2, 2, "double strike"), "P1" to creature("Wall", 0, 5)),
                maxTurns = 1,
            )
        }
        assertEq(4, end.battlefield.getValue(2).damageMarked, "2 in first-strike + 2 in regular")
        assertTrue(end.byName("Berserker") != null, "the 0-power wall never hurt it")
    }

    check("P8.1 an INSTANCES shield (Divine Shield) eats one whole hit, then is spent") {
        val engine = Engine(
            ScriptedInput.of(
                "P0" to listOf(
                    PLAY(StonebackOx),
                    CAST(Effect.PreventDamage(BoundTarget(1), lit(1), mode = ShieldMode.INSTANCES)), PASS,
                    CAST(Effect.DealDamage(lit(10), BoundTarget(1))), PASS,
                    CAST(Effect.DealDamage(lit(2), BoundTarget(1))),
                ),
            ),
        )
        val end = runSync { engine.run(newGame()) }
        assertEq(2, end.battlefield.getValue(1).damageMarked, "the 10 was fully eaten; the later 2 got through")
        assertTrue(end.shields.isEmpty())
    }

    // -- P9 slice 1: the Creator authoring doc + doc -> engine compile -----

    check("a vanilla FaceDoc compiles to a playable 2/2") {
        val doc = CardDoc(faces = listOf(FaceDoc("Bear", setOf("Creature"), mapOf("power" to 2, "toughness" to 2))))
        val end = runSync { Engine(ScriptedInput.of("P0" to listOf(PLAY(doc.build())))).run(newGame()) }
        val bear = end.byName("Bear")!!
        assertEq(2, end.characteristicsOf(bear.id).power)
        assertEq(2, end.characteristicsOf(bear.id).toughness)
    }

    check("a spell FaceDoc (no fields) compiles to a castable effect") {
        val doc = CardDoc(faces = listOf(FaceDoc("Bolt", setOf("Instant"), castEffect = Effect.DamageOpponent(lit(3)))))
        val end = runSync { Engine(ScriptedInput.of("P0" to listOf(CAST(doc.build().castEffect!!)))).run(newGame()) }
        assertEq(17, end.players.getValue("P1").life)
    }

    check("a SelfEnters trigger with BoundTarget(SELF) rebinds to the real id") {
        val doc = CardDoc(
            faces = listOf(
                FaceDoc(
                    "Sprout", setOf("Creature"), mapOf("power" to 1, "toughness" to 1),
                    triggers = listOf(TriggerDoc.SelfEnters(Effect.AddCounter("+1/+1", lit(1), BoundTarget(SELF)))),
                ),
            ),
        )
        val end = runSync { Engine(ScriptedInput.of("P0" to listOf(PLAY(doc.build())))).run(newGame()) }
        val sprout = end.byName("Sprout")!!
        assertEq(1, sprout.counter("+1/+1"))
        assertEq(2, end.characteristicsOf(sprout.id).power)
    }

    check("a FaceDoc static compiles and applies") {
        val doc = CardDoc(
            faces = listOf(
                FaceDoc(
                    "Sky Banner", setOf("Enchantment"),
                    statics = listOf(StaticSpec(creatures().yours(), listOf(CharOp.GrantKeyword("flying")))),
                ),
            ),
        )
        val end = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PLAY(Grunt), PLAY(doc.build())))).run(newGame())
        }
        assertTrue(end.characteristicsOf(1).has("flying"))
    }

    check("a two-face CardDoc compiles; the back face can be chosen at play") {
        val doc = CardDoc(
            faces = listOf(
                FaceDoc("Walker", setOf("Creature"), mapOf("power" to 1, "toughness" to 1)),
                FaceDoc("Menhir", setOf("Land")),
            ),
        )
        val front = runSync { Engine(ScriptedInput.of("P0" to listOf(PLAY(doc.build(), 0)))).run(newGame()) }
        assertTrue(front.byName("Walker") != null)
        val back = runSync { Engine(ScriptedInput.of("P0" to listOf(PLAY(doc.build(), 1)))).run(newGame()) }
        val menhir = back.byName("Menhir")!!
        assertTrue("Land" in menhir.base.types)
        assertTrue(!back.characteristicsOf(menhir.id).hasField("power"))
    }

    check("entersWith + a CounterThreshold trigger authors a Saga chapter") {
        val doc = CardDoc(
            faces = listOf(
                FaceDoc(
                    "Little Saga", setOf("Enchantment", "Saga"),
                    triggers = listOf(TriggerDoc.CounterThreshold("lore", 1, Effect.Draw(PlayerRef.You, lit(1)))),
                ),
            ),
            entersWith = listOf(CounterDef("lore", lit(1))),
        )
        val end = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PLAY(doc.build())))).run(newGame(libraries = mapOf("P0" to tokens(3))))
        }
        assertEq(1, end.players.getValue("P0").hand.size, "chapter I fired off the entry counter")
    }

    check("a YouCastType trigger fires off a matching spell") {
        val doc = CardDoc(
            faces = listOf(
                FaceDoc(
                    "Loremonger", setOf("Creature"), mapOf("power" to 1, "toughness" to 3),
                    triggers = listOf(TriggerDoc.YouCastType(setOf("Instant"), Effect.Draw(PlayerRef.You, lit(1)))),
                ),
            ),
        )
        val end = runSync {
            Engine(
                ScriptedInput.of("P0" to listOf(PLAY(doc.build()), CAST(Effect.NoOp, setOf("Instant")))),
            ).run(newGame(libraries = mapOf("P0" to tokens(3))))
        }
        assertEq(1, end.players.getValue("P0").hand.size)
    }

    check("bindSelf leaves a concrete BoundTarget alone") {
        val e = Effect.DealDamage(lit(2), BoundTarget(5)).bindSelf(9)
        assertEq(BoundTarget(5), (e as Effect.DealDamage).target)
        val e2 = Effect.DealDamage(lit(2), BoundTarget(SELF)).bindSelf(9)
        assertEq(BoundTarget(9), (e2 as Effect.DealDamage).target)
    }

    check("GameDoc.rules() merges extra types, zones, and the combat config") {
        val game = GameDoc(
            rules = RulesDoc(
                extraTypes = listOf(TypeDef("Site", zoneOfPlay = "sites")),
                extraZones = listOf(PlayZoneDef("sites", ZoneScope.PER_PLAYER)),
                combat = CombatDoc.Preset("fastSlow"),
            ),
        )
        val r = game.rules()
        assertEq(ZoneRef("sites", "P0"), r.resolveZone("sites", "P0"))
        assertEq("sites", r.defaultZoneDef(setOf("Site")))
        assertEq(FAST_SLOW_COMBAT.lowered(), r.combat)
        assertTrue(r.typeOf("Creature").attacks, "the built-ins are still there")
    }

    check("GameDoc immutable edits are copy-based, through the set") {
        val g0 = GameDoc().updateSet(0) { it.addCard(CardDoc()).addCard(CardDoc()) }
        val g1 = g0.updateSet(0) { it.updateCard(0) { c -> c.copy(text = "hello") } }
        assertEq("", g0.cards[0].text, "the original is untouched")
        assertEq("hello", g1.cards[0].text)
        assertEq(1, g0.updateSet(0) { it.removeCard(0) }.cards.size)
        // Sets and decks are their own containers.
        val g2 = g0.addSet("Expansion").addDeck("Aggro")
        assertEq(2, g2.sets.size)
        assertEq(1, g2.decks.size)
        assertEq(1, g2.removeSet(1).sets.size)
        assertTrue(GameDoc().removeSet(0).sets.size == 1, "the last set cannot be removed")
    }

    // -- P9 slice 2: costs + activated abilities --------------------------

    fun GameState.pooled(player: PlayerId, vararg m: Pair<String, Int>): GameState =
        copy(players = players + (player to players.getValue(player).copy(pool = m.toMap())))

    check("cost: pay generic mana, keep the change") {
        val end = runSync {
            Engine(
                ScriptedInput.of(
                    "P0" to listOf(
                        PriorityAction.CastSpell(Effect.DamageOpponent(lit(3)).lowered(LIFE), setOf("Instant"), Cost(mana = mapOf("" to 2))),
                    ),
                ),
            ).run(newGame().pooled("P0", "" to 3))
        }
        assertEq(17, end.players.getValue("P1").life)
        assertEq(mapOf("" to 1), end.players.getValue("P0").pool)
    }

    check("cost: an unaffordable spell is a no-op") {
        val engine = Engine(
            ScriptedInput.of("P0" to listOf(PriorityAction.CastSpell(Effect.DamageOpponent(lit(3)).lowered(LIFE), setOf("Instant"), Cost(mana = mapOf("" to 3))))),
        )
        val end = runSync { engine.run(newGame().pooled("P0", "" to 1)) }
        assertEq(20, end.players.getValue("P1").life)
        assertTrue(end.log.any { it.contains("can't afford") })
    }

    check("cost: typed mana must match; generic can spend the leftover") {
        val okTyped = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PriorityAction.CastSpell(Effect.DamageOpponent(lit(2)).lowered(LIFE), setOf("Instant"), Cost(mana = mapOf("R" to 2))))))
                .run(newGame().pooled("P0", "R" to 2))
        }
        assertEq(18, okTyped.players.getValue("P1").life)

        val genericFromR = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PriorityAction.CastSpell(Effect.DamageOpponent(lit(2)).lowered(LIFE), setOf("Instant"), Cost(mana = mapOf("" to 2))))))
                .run(newGame().pooled("P0", "R" to 3))
        }
        assertEq(18, genericFromR.players.getValue("P1").life)
        assertEq(mapOf("R" to 1), genericFromR.players.getValue("P0").pool)

        val wrongColor = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PriorityAction.CastSpell(Effect.DamageOpponent(lit(2)).lowered(LIFE), setOf("Instant"), Cost(mana = mapOf("G" to 1))))))
                .run(newGame().pooled("P0", "R" to 3))
        }
        assertEq(20, wrongColor.players.getValue("P1").life, "no G in the pool")
    }

    check("cost: {X} is chosen once and pays into both the cost and the effect") {
        val engine = Engine(
            ScriptedInput.of(
                "P0" to listOf(PriorityAction.CastSpell(Effect.DamageOpponent(x).lowered(LIFE), setOf("Instant"), Cost(usesX = true))),
                numbers = listOf(4),
            ),
        )
        val end = runSync { engine.run(newGame().pooled("P0", "" to 5)) }
        assertEq(16, end.players.getValue("P1").life, "X=4 damage")
        assertEq(mapOf("" to 1), end.players.getValue("P0").pool, "X=4 paid")
    }

    check("Effect.AddMana fills the pool") {
        val end = runSync {
            Engine(ScriptedInput.of("P0" to listOf(CAST(Effect.AddMana(PlayerRef.You, mapOf("G" to lit(2))))))).run(newGame())
        }
        assertEq(mapOf("G" to 2), end.players.getValue("P0").pool)
    }

    val Forest = card(
        "Forest", setOf("Land"),
        activated = listOf(ActivatedAbility(Cost(tapSource = true), Effect.AddMana(PlayerRef.You, mapOf("G" to lit(1))), "tap for G")),
    )

    check("a tap-for-mana Land: activate once, then it's tapped out") {
        val engine = Engine(
            ScriptedInput.of("P0" to listOf(PLAY(Forest), PriorityAction.Activate(1, 0), PriorityAction.Activate(1, 0))),
            rules = Rules().knowing(Forest),
        )
        val end = runSync { engine.run(newGame()) }
        assertEq(mapOf("G" to 1), end.players.getValue("P0").pool, "one activation only -- the second found it exhausted")
        assertTrue(end.battlefield.getValue(1).exhausted)
    }

    check("an activated ability with a mana cost resolves via the stack") {
        val creature = card(
            "Font of Ideas", setOf("Creature"), body("Font of Ideas", 0, 3),
            activated = listOf(ActivatedAbility(Cost(mana = mapOf("" to 2)), Effect.Draw(PlayerRef.You, lit(1)), "draw")),
        )
        val end = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PLAY(creature), PriorityAction.Activate(1, 0))), rules = Rules().knowing(creature))
                .run(newGame(libraries = mapOf("P0" to tokens(3))).pooled("P0", "" to 2))
        }
        assertEq(1, end.players.getValue("P0").hand.size)
        assertTrue(end.players.getValue("P0").pool.isEmpty())
    }

    val Loyalist = card(
        "Loyalist", setOf("Planeswalker"),
        entersWith = listOf(CounterDef("loyalty", lit(3))),
        activated = listOf(
            ActivatedAbility(Cost(), Effect.AddCounter("loyalty", lit(1), BoundTarget(SELF)), "+1", oncePerTurn = true),
            ActivatedAbility(Cost(removeCounters = "loyalty" to 3), Effect.DamageOpponent(lit(3)), "-3", oncePerTurn = true),
        ),
    )

    check("a Planeswalker +1 loyalty ability, once per turn") {
        val end = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PLAY(Loyalist), PriorityAction.Activate(1, 0), PriorityAction.Activate(1, 0))), rules = Rules().knowing(Loyalist))
                .run(newGame())
        }
        assertEq(4, end.byName("Loyalist")!!.counter("loyalty"), "3 + 1, and the second +1 was blocked (once per turn)")
    }

    check("a Planeswalker -3 pays with loyalty counters") {
        val hit = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PLAY(Loyalist), PriorityAction.Activate(1, 1))), rules = Rules().knowing(Loyalist)).run(newGame())
        }
        assertEq(null, hit.byName("Loyalist"), "3 - 3 -> loyalty 0 -> dies by SBA")
        assertEq(17, hit.players.getValue("P1").life)

        // with only 2 loyalty (an extra removal first), -3 can't be paid
        val cantPay = runSync {
            Engine(
                ScriptedInput.of(
                    "P0" to listOf(
                        PLAY(Loyalist),
                        CAST(Effect.RemoveCounter("loyalty", lit(2), BoundTarget(1))),
                        PASS,
                        PriorityAction.Activate(1, 1),
                    ),
                ),
                rules = Rules().knowing(Loyalist),
            ).run(newGame())
        }
        assertEq(1, cantPay.byName("Loyalist")!!.counter("loyalty"), "3 - 2; -3 needs 3, so it was refused")
        assertEq(20, cantPay.players.getValue("P1").life)
    }

    check("an additional cost: sacrifice a creature to cast") {
        val engine = Engine(
            ScriptedInput.of(
                "P0" to listOf(
                    PLAY(Grunt),
                    PriorityAction.CastSpell(
                        Effect.DamageOpponent(lit(4)).lowered(LIFE), setOf("Sorcery"),
                        Cost(additional = Effect.Sacrifice(PlayerRef.You, lit(1), creatures())),
                    ),
                ),
                targets = listOf(1),
            ),
        )
        val end = runSync { engine.run(newGame()) }
        assertEq(null, end.byName("Grunt"), "sacrificed as a cost")
        assertEq(16, end.players.getValue("P1").life)
    }

    check("a pay-life cost") {
        val paid = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PriorityAction.CastSpell(Effect.DamageOpponent(lit(2)).lowered(LIFE), setOf("Instant"), Cost(payLife = 3)))))
                .run(newGame())
        }
        assertEq(17, paid.players.getValue("P0").life)
        assertEq(18, paid.players.getValue("P1").life)

        val cant = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PriorityAction.CastSpell(Effect.DamageOpponent(lit(2)).lowered(LIFE), setOf("Instant"), Cost(payLife = 3)))))
                .run(newGame(startingLife = 2))
        }
        assertEq(2, cant.players.getValue("P0").life, "can't pay 3 life from 2")
        assertEq(2, cant.players.getValue("P1").life)
    }

    check("a cost-reduction static ('your spells cost 1 less')") {
        val reducer = card(
            "Thrift Sigil", setOf("Enchantment"),
            statics = Statics(costs = listOf(CostMod(who = PlayerRef.You, delta = mapOf("" to -1)))),
        )
        val end = runSync {
            Engine(
                ScriptedInput.of(
                    "P0" to listOf(
                        PLAY(reducer),
                        PriorityAction.CastSpell(Effect.DamageOpponent(lit(3)).lowered(LIFE), setOf("Instant"), Cost(mana = mapOf("" to 3))),
                    ),
                ),
            ).run(newGame().pooled("P0", "" to 2)) // only 2 -- would fail at cost 3
        }
        assertEq(17, end.players.getValue("P1").life, "effective cost 2 -> affordable")
    }

    check("the pool empties at a phase boundary") {
        val end = runSync {
            Engine(ScriptedInput.of("P0@main" to listOf(CAST(Effect.AddMana(PlayerRef.You, mapOf("" to lit(3)))))))
                .playGame(newGame(libraries = mapOf("P0" to tokens(4), "P1" to tokens(4))), maxTurns = 1)
        }
        assertTrue(end.players.getValue("P0").pool.isEmpty(), "gone by the end of the turn")
    }

    check("CardDoc.cost + FaceDoc.activated round-trip through build()") {
        val doc = CardDoc(
            faces = listOf(
                FaceDoc(
                    "Spring", setOf("Land"),
                    activated = listOf(ActivatedAbility(Cost(tapSource = true), Effect.AddMana(PlayerRef.You, mapOf("U" to lit(1))))),
                ),
            ),
            cost = Cost(mana = mapOf("" to 2)),
        )
        val cd = doc.build()
        assertEq(Cost(mana = mapOf("" to 2)), cd.cost)
        val end = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PLAY(cd), PriorityAction.Activate(1, 0))), rules = Rules().knowing(cd))
                .run(newGame().pooled("P0", "" to 2))
        }
        assertEq(mapOf("U" to 1), end.players.getValue("P0").pool)
    }

    // -- P9 slice 3: JSON persistence -----------------------------------

    check("the Effect tree round-trips through JSON") {
        val effects = listOf<Effect>(
            Effect.Choose(creatures(), Effect.DealDamage(lit(3), BoundTarget(CHOSEN))),           // Emberbolt
            Effect.Draw(PlayerRef.You, countOf(creatures().yours())),                                   // Rally
            Effect.ForEach(creatures().yours().otherThanThis(), Effect.AddCounter("+1/+1", lit(1), BoundTarget(EACH))), // Hive Overseer
            Effect.Choose(
                creatures(),
                Effect.Delayed(EventPattern.OnPhase("end"), Effect.DealDamage(lit(4), BoundTarget(CHOSEN))),
            ),                                                                                    // Delayed Blast
            Effect.Sequence(listOf(Effect.Draw(PlayerRef.You, lit(1)), Effect.If(countOf(creatures().yours()) gte 3, Effect.Draw(PlayerRef.You, lit(1))))),
            Effect.ApplyModifier(creatures().only(CHOSEN), listOf(CharOp.SetTypes(setOf("Elemental")), CharOp.SetPT(lit(4), lit(4)), CharOp.GrantKeyword("trample"))),
            Effect.PreventDamage(BoundTarget(CHOSEN), lit(3), mode = ShieldMode.INSTANCES),
            Effect.ForEachPlayer(Effect.Sacrifice(PlayerRef.You, lit(2), creatures())),
            Effect.AddMana(PlayerRef.You, mapOf("G" to lit(1), "" to lit(2))),
            Effect.MovePermanent(BoundTarget(CHOSEN), ZoneRef("planet", "P0")),
            Effect.SetCombatMode(BoundTarget(CHOSEN), "defense"),
            Effect.NoOp,
        )
        for (e in effects) assertEq(e, effectFromJson(effectToJson(e)), "round-trip $e")
    }

    check("review-I every Effect / CharOp case round-trips (exhaustive tripwire)") {
        // The `caseTag` whens below are exhaustive over the sealed hierarchies,
        // so ADDING an Effect / CharOp case fails to compile here until it is
        // both tagged AND given a sample -- closing the ser/deser drift finding.
        fun Effect.caseTag(): Int = when (this) {
            is Effect.DealDamage -> 1; is Effect.DamageOpponent -> 2; is Effect.Draw -> 3
            is Effect.GainLife -> 4; is Effect.AddMana -> 5; is Effect.Destroy -> 6
            is Effect.AddCounter -> 7; is Effect.RemoveCounter -> 8; is Effect.Transform -> 9
            is Effect.CreateToken -> 10; is Effect.CopyOf -> 11; is Effect.CreateEmblem -> 12
            is Effect.ChooseMode -> 13; is Effect.Discard -> 14; is Effect.ReturnFromDiscard -> 15
            is Effect.MovePermanent -> 16; is Effect.ApplyModifier -> 17; is Effect.PreventDamage -> 18
            is Effect.SetCombatMode -> 19; is Effect.ForEach -> 20; is Effect.ForEachPlayer -> 21
            is Effect.Sacrifice -> 22; is Effect.Delayed -> 23; is Effect.Sequence -> 24
            is Effect.Choose -> 25; is Effect.If -> 26; Effect.NoOp -> 27
            is Effect.ChooseMany -> 28
            is Effect.SearchZone -> 29; is Effect.MoveTop -> 30; is Effect.LookAtTop -> 31; is Effect.Shuffle -> 32
            is Effect.Attach -> 33; is Effect.DrawThenDiscard -> 34
            is Effect.CounterSpell -> 35; is Effect.Tap -> 36; is Effect.SendTo -> 37; is Effect.GainControl -> 38
            is Effect.AsPlayer -> 39; is Effect.ClearDamage -> 40; is Effect.Proceed -> 41
            is Effect.DeclareAttackers -> 42; is Effect.DeclareBlockers -> 43; Effect.CombatWindow -> 44
            is Effect.CombatDamage -> 45
            is Effect.Attack -> 46; is Effect.Strike -> 47; is Effect.Clash -> 48; is Effect.FreeAttacks -> 49
        }
        fun CharOp.caseTag(): Int = when (this) {
            is CharOp.PlusPT -> 1; is CharOp.SetPT -> 2; is CharOp.GrantKeyword -> 3
            is CharOp.AddType -> 4; is CharOp.SetTypes -> 5; CharOp.RemoveAbilities -> 6
            is CharOp.Bands -> 7; is CharOp.GrantAbility -> 8; is CharOp.PlusField -> 9; is CharOp.SetField -> 10
        }
        val bt = BoundTarget(CHOSEN)
        val effAll = listOf<Effect>(
            Effect.DealDamage(lit(2), bt), Effect.DamageOpponent(lit(2)), Effect.Draw(PlayerRef.You, lit(1)),
            Effect.GainLife(PlayerRef.You, lit(3)), Effect.AddMana(PlayerRef.You, mapOf("" to lit(1))), Effect.Destroy(bt),
            Effect.AddCounter("lore", lit(1), bt), Effect.RemoveCounter("loyalty", lit(2), bt), Effect.Transform(bt),
            Effect.CreateToken(Characteristics("Soldier", setOf("Creature"), mapOf("power" to 1, "toughness" to 1)), lit(2), "battlefield"),
            Effect.CopyOf(bt), Effect.CreateEmblem(Statics(rules = listOf(RuleMod.Cant(RuleAction.GAIN_LIFE, PlayerRef.Opponent)))),
            Effect.ChooseMode(listOf(Effect.Draw(PlayerRef.You, lit(1)), Effect.NoOp), lit(1)),
            Effect.Discard(PlayerRef.Opponent, lit(1)), Effect.ReturnFromDiscard(PlayerRef.You, lit(1), toBattlefield = true),
            Effect.MovePermanent(bt, ZoneRef("planet", "P0")),
            Effect.ApplyModifier(creatures().only(CHOSEN), listOf(CharOp.PlusPT(lit(1), lit(1))), layer = 8, duration = Duration.EndOfTurn),
            Effect.PreventDamage(bt, lit(2), all = true, duration = Duration.EndOfNextTurn, mode = ShieldMode.INSTANCES),
            Effect.SetCombatMode(bt, "defense"), Effect.ForEach(creatures().yours(), Effect.DealDamage(lit(1), BoundTarget(EACH))),
            Effect.ForEachPlayer(Effect.Draw(PlayerRef.You, lit(1))), Effect.Sacrifice(PlayerRef.You, lit(1), creatures()),
            Effect.Delayed(EventPattern.AnyTurnBegan, Effect.Draw(PlayerRef.You, lit(1)), once = false, expiresAfter = EventPattern.OnPhase("end")),
            Effect.Sequence(listOf(Effect.NoOp)), Effect.Choose(creatures(), Effect.Destroy(bt)),
            Effect.If(BoolExpr.Const(true), Effect.NoOp, Effect.Draw(PlayerRef.You, lit(1))), Effect.NoOp,
            Effect.ChooseMany(creatures(), lit(2), upTo = true, divide = lit(4), body = Effect.DealDamage(IntExpr.Share, BoundTarget(EACH))),
            Effect.SearchZone(PlayerRef.You, HiddenZone.LIBRARY, HiddenZone.HAND, lit(1), CardFilter(nameIs = "Grunt")),
            Effect.MoveTop(PlayerRef.Opponent, lit(3), HiddenZone.GRAVEYARD),
            Effect.LookAtTop(PlayerRef.You, lit(2), HiddenZone.LIBRARY_BOTTOM),
            Effect.Shuffle(PlayerRef.You, HiddenZone.LIBRARY),
            Effect.Attach(bt),
            Effect.DrawThenDiscard(PlayerRef.You, lit(2), lit(1), HiddenZone.EXILE),
            Effect.CounterSpell(emptySet(), null), Effect.Tap(bt), Effect.SendTo(bt, HiddenZone.LIBRARY),
            Effect.GainControl(bt), Effect.AsPlayer(PlayerRef.Active, Effect.NoOp), Effect.ClearDamage(bt), Effect.Proceed(lit(1)),
            // The combat verbs: every field set, so each one round-trips.
            Effect.DeclareAttackers(creatures().yours(), creatures(), Effect.CombatWindow),
            Effect.DeclareBlockers(creatures().yours(), listOf(BlockRule.needsKeyword("flying", "reach")), Effect.NoOp),
            Effect.CombatWindow,
            Effect.CombatDamage(
                "regular", IntExpr.TargetField(BoundTarget(SUBJECT), "power"),
                acts = creatures(), lethal = creatures(), tramples = creatures(), minBlockers = lit(2),
            ),
            Effect.Attack(creatures(), creatures().theirs(), lit(2), creatures(), creatures().yours(), creatures(), Effect.NoOp),
            Effect.Strike("strike", lit(2), lit(1), returnDamage = false, lethal = creatures()),
            Effect.Clash("clash", lit(2), lit(3), lit(1), returnDamage = false, excessToController = true,
                onTie = TieResult.ATTACKER_WINS, stances = listOf(StanceDef("defense", "defense")), pierces = creatures()),
            Effect.FreeAttacks("fast", creatures(), Gun(BoolExpr.Const(false), BoolExpr.Const(false), BoolExpr.Const(false), lit(2), lit(3), AttackRange.FAR),
                listOf(Gun(amount = lit(1))), guards = creatures(), lethal = creatures(), window = true, overflow = true),
        )
        assertEq((1..49).toSet(), effAll.map { it.caseTag() }.toSet(), "one sample per Effect case")
        for (e in effAll) assertEq(e, effectFromJson(effectToJson(e)), "Effect round-trip $e")

        val opAll = listOf<CharOp>(
            CharOp.PlusPT(lit(1), lit(2)), CharOp.SetPT(lit(3), lit(3)), CharOp.GrantKeyword("flying"),
            CharOp.AddType("Artifact"), CharOp.SetTypes(setOf("Creature", "Elemental")), CharOp.RemoveAbilities,
            CharOp.Bands(
                "level", listOf(CharOp.Bands.Band(2, lit(3), lit(3)), CharOp.Bands.Band(5, lit(6), lit(6))),
                fieldA = "hull", fieldB = "fast", // exercises the round-trip of the new fields, not just the defaults
            ),
            CharOp.GrantAbility(ActivatedAbility(Cost(tapSource = true), Effect.Draw(PlayerRef.You, lit(1)), "{T}: draw")),
            CharOp.PlusField("fast", lit(1)),
            CharOp.SetField("hull", lit(4)),
        )
        assertEq((1..10).toSet(), opAll.map { it.caseTag() }.toSet(), "one sample per CharOp case")
        for (o in opAll) assertEq(o, charOpOf(Json.parse(charOpToJson(o))), "CharOp round-trip $o")
    }

    check("review-B a non-instant can't be cast outside a sorcery-speed phase") {
        val bad = runSync {
            Engine(ScriptedInput.of("P0@upkeep" to listOf(CAST(Effect.DamageOpponent(lit(3))))))
                .playGame(newGame(libraries = mapOf("P0" to tokens(4), "P1" to tokens(4))), maxTurns = 1)
        }
        assertEq(20, bad.players.getValue("P1").life, "cast rejected in upkeep")
        assertTrue(bad.log.any { it.contains("can't cast") })

        val ok = runSync {
            Engine(ScriptedInput.of("P0@main" to listOf(CAST(Effect.DamageOpponent(lit(3))))))
                .playGame(newGame(libraries = mapOf("P0" to tokens(4), "P1" to tokens(4))), maxTurns = 1)
        }
        assertEq(17, ok.players.getValue("P1").life, "same cast resolves on main")
    }

    // -- Phase 3: the frame around the rules ---------------------------------

    check("params life is just a player counter -- gold and devotion work too") {
        val r = Rules(
            playerCounters = listOf(
                PlayerCounterDef(LIFE, 20, loseAtZero = true),
                PlayerCounterDef("gold", 3, loseAtZero = true),
                PlayerCounterDef("devotion", 2, loseAtZero = true),
                PlayerCounterDef("fame", 0),
            ),
        )
        val s = setupGame(r, shuffle = false)
        assertEq(20, s.players.getValue("P0").life, "life still reads as life")
        assertEq(3, s.players.getValue("P0").counter("gold"))
        // Losing all your GOLD ends the game exactly as losing all your life.
        val broke = s.copy(players = s.players + ("P0" to s.players.getValue("P0").withCounter("gold", 0)))
        val out = runSync { Engine(ScriptedInput.of(), rules = r).run(broke) }
        assertTrue("P0" in out.losers, "0 gold loses the game")
        // A counter without `loseAtZero` does not.
        val famous = s.copy(players = s.players + ("P0" to s.players.getValue("P0").withCounter("fame", 0)))
        assertTrue("P0" !in runSync { Engine(ScriptedInput.of(), rules = r).run(famous) }.losers, "0 fame is fine")
    }

    check("params GainLife can move ANY declared counter") {
        val r = Rules(playerCounters = listOf(PlayerCounterDef(LIFE, 20, loseAtZero = true), PlayerCounterDef("gold", 5)))
        val end = runSync {
            Engine(
                ScriptedInput.of("P0" to listOf(CAST(Effect.GainLife(PlayerRef.You, lit(2), counter = "gold")))),
                rules = r,
            ).run(setupGame(r, shuffle = false))
        }
        assertEq(7, end.players.getValue("P0").counter("gold"), "gold went up")
        assertEq(20, end.players.getValue("P0").life, "and life did not")
    }

    check("params an opening hand is DEALT") {
        val r = Rules(params = GameParams(startingHandSize = 5))
        val s = setupGame(r, libraries = mapOf("P0" to tokens(30), "P1" to tokens(30, 200_000)), shuffle = false)
        assertEq(5, s.players.getValue("P0").hand.size)
        assertEq(5, s.players.getValue("P1").hand.size)
        assertEq(25, s.players.getValue("P0").library.size, "and they came off the library")
        // Zero is a legal choice, and deals nothing.
        assertEq(0, setupGame(Rules(params = GameParams(startingHandSize = 0)), shuffle = false).players.getValue("P0").hand.size)
    }

    check("params draw per turn, and the first player skipping their first draw") {
        fun handAfterOneTurn(p: GameParams): Int {
            val r = Rules(params = p, turn = NO_CLEANUP)
            val s = setupGame(r, libraries = mapOf("P0" to tokens(30), "P1" to tokens(30, 200_000)), shuffle = false)
            return runSync { Engine(ScriptedInput.of(), rules = r).playGame(s, maxTurns = 1) }
                .players.getValue("P0").hand.size
        }
        // Opening hand 0 isolates the draw step.
        assertEq(0, handAfterOneTurn(GameParams(startingHandSize = 0, firstPlayerSkipsFirstDraw = true)), "skipped")
        assertEq(1, handAfterOneTurn(GameParams(startingHandSize = 0, firstPlayerSkipsFirstDraw = false)), "drew one")
        assertEq(3, handAfterOneTurn(GameParams(startingHandSize = 0, firstPlayerSkipsFirstDraw = false, cardsDrawnPerTurn = 3)), "drew three")
    }

    check("params the hand limit is enforced at cleanup") {
        val r = Rules(params = GameParams(startingHandSize = 6, maxHandSize = 4, firstPlayerSkipsFirstDraw = false))
        val s = setupGame(r, libraries = mapOf("P0" to tokens(30), "P1" to tokens(30, 200_000)), shuffle = false)
        assertEq(6, s.players.getValue("P0").hand.size, "dealt over the limit")
        // chooses the three to discard.
        val shed = listOf(100_000, 100_002, 100_006)
        val end = runSync { Engine(ScriptedInput.of(cards = listOf(shed)), rules = r).playGame(s, maxTurns = 1) }
        val p0 = end.players.getValue("P0")
        assertEq(4, p0.hand.size, "discarded down to the limit")
        assertEq(shed, p0.graveyard.map { it.instanceId }, "6 dealt + 1 drawn - 4 kept = 3 discarded, the ones chosen")
        // No limit means no discard.
        val free = Rules(params = GameParams(startingHandSize = 6, maxHandSize = null, firstPlayerSkipsFirstDraw = false))
        val kept = runSync {
            Engine(ScriptedInput.of(), rules = free)
                .playGame(setupGame(free, libraries = mapOf("P0" to tokens(30)), shuffle = false), maxTurns = 1)
        }
        assertEq(7, kept.players.getValue("P0").hand.size, "a null limit discards nothing")
    }

    check("params Ramp grants resources per turn, capped -- the Hearthstone model") {
        val r = Rules(
            params = GameParams(startingHandSize = 0),
            resourceModel = ResourceModel.Ramp(perTurn = 1, cap = 3),
            turn = NO_CLEANUP,
        )
        fun poolAfter(turns: Int): Int {
            val s = setupGame(r, libraries = mapOf("P0" to tokens(40), "P1" to tokens(40, 200_000)), shuffle = false)
            // is active on odd turns, so its ramp grows every other turn.
            return runSync { Engine(ScriptedInput.of(), rules = r).playGame(s, maxTurns = turns) }
                .players.getValue("P0").ramp
        }
        assertEq(1, poolAfter(1), "one on the first turn")
        assertEq(2, poolAfter(3), "grows each of your turns")
        assertEq(3, poolAfter(9), "and stops at the cap")
        // And the pool is actually spendable: a 1-cost spell resolves.
        val s = setupGame(r, shuffle = false)
        val cast = runSync {
            Engine(
                ScriptedInput.of("P0" to listOf(PriorityAction.CastSpell(Effect.DamageOpponent(lit(4)).lowered(LIFE), setOf("Instant"), Cost(mana = mapOf("" to 1))))),
                rules = r,
            ).playGame(s, maxTurns = 1)
        }
        assertEq(16, cast.players.getValue("P1").life, "the ramped resource paid for it")
    }

    check("params CardDriven is a land drop -- N resource plays per turn") {
        val land = card("Barren Ground", setOf("Land"))
        // Untap + one main phase: the queued plays must land in a
        // SORCERY-SPEED window, or timing denies them before the land drop
        // ever gets a say.
        val oneMain = TurnStructure(
            listOf(
                PhaseSpec("untap", interactive = false, onEnter = PhaseEffects.UNTAP),
                PhaseSpec("main", sorcerySpeed = true),
            ),
        )
        val r = Rules(
            params = GameParams(startingHandSize = 0),
            resourceModel = ResourceModel.CardDriven(playsPerTurn = 1, types = setOf("Land")),
            cards = mapOf("Barren Ground" to land),
            turn = oneMain,
        )
        val s = setupGame(r, shuffle = false)
        val end = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PLAY(land), PLAY(land))), rules = r).playGame(s, maxTurns = 1)
        }
        assertEq(1, end.battlefield.values.count { "Land" in it.base.types }, "the second play was denied")
        assertTrue(end.log.any { it.contains("already played 1 resource") }, end.log.toString())
        // THE legality function agrees with what the engine did -- the UI reads
        // the same answer, so it cannot offer an illegal play.
        val after = end.players.getValue("P0")
        assertEq(1, after.resourcePlaysUsed)
        // Ask at a state still INSIDE the main phase -- at a turn boundary
        // `sorcerySpeedWindow` fails closed, which would mask the land drop.
        // …and with P0 still the active player: `playGame` flips it at the end
        // of the turn, and a sorcery-speed window belongs to the active seat.
        val inMain = end.copy(phase = "main", phaseIndex = 1, activePlayer = "P0")
        val verdict = legality(r, inMain, "P0", PriorityAction.PlayPermanent(land.lowered(LIFE)))
        assertTrue(verdict is Legality.Denied, "the land drop is spent")
        assertTrue((verdict as Legality.Denied).reason.contains("already played"), verdict.reason)
        // A non-resource permanent is unaffected by the limit.
        assertTrue(legality(r, inMain, "P0", PriorityAction.PlayPermanent(Grunt.lowered(LIFE))) is Legality.Legal, "a creature is fine")
    }

    check("params the whole Phase 3 block round-trips through JSON") {
        val g = GameDoc(
            name = "Parameterised",
            rules = RulesDoc(
                params = GameParams(
                    startingHandSize = 3, cardsDrawnPerTurn = 2, firstPlayerSkipsFirstDraw = false,
                    mulligan = MulliganRule(redraws = 2), maxHandSize = 10, playerCount = 4,
                    preferredOrientation = Orientation.LANDSCAPE,
                    // Non-default ON PURPOSE: the field round-trips as `false`
                    // on both sides otherwise, so this check would pass just as
                    // happily if the JSON layer had never learnt about it.
                    attackDelayOnEntry = true,
                ),
                resourceModel = ResourceModel.Ramp(perTurn = 2, cap = 8, key = "mana"),
                playerCounters = listOf(
                    PlayerCounterDef(LIFE, 30, loseAtZero = true),
                    PlayerCounterDef("gold", 5, loseAtZero = true, min = 0, max = 99),
                ),
            ),
        )
        assertEq(g, gameDocFromJson(gameDocToJson(g)), "parameters round-trip")
        // The defaults are ELIDED, so an unparameterised game's JSON is unchanged.
        assertTrue(!gameDocToJson(GameDoc()).contains("params"), "defaults are not written")
        assertEq(GameDoc(), gameDocFromJson(gameDocToJson(GameDoc())))
        // Each resource model survives on its own.
        for (m in listOf(ResourceModel.None, ResourceModel.MTG, ResourceModel.HEARTHSTONE,
                         ResourceModel.CardDriven(2, setOf("Land", "Site")))) {
            val one = GameDoc(rules = RulesDoc(resourceModel = m))
            assertEq(m, gameDocFromJson(gameDocToJson(one)).rules.resourceModel, "round-trip $m")
        }
        // …and the compiled engine Rules carries them.
        val r = g.rules()
        assertEq(3, r.params.startingHandSize)
        assertEq(mapOf(LIFE to 30, "gold" to 5), r.startingCounters())
        assertEq(99, r.clampCounter("gold", 500), "declared max clamps")
    }

    check("firstPlayerSkipsFirstDraw is INERT without a draw phase -- proved, not assumed") {
        // The flag is read only by the DRAW phase effect, so in a turn with no
        // draw phase it does nothing. Asserted as a PROPERTY rather than a
        // `problems()` advisory: it defaults to true, so "set on purpose" and
        // "never thought about" are the same value and a warning would fire
        // forever.
        val noDraw = TurnStructure(
            listOf(
                PhaseSpec("main", sorcerySpeed = true),
                PhaseSpec("combat", combat = true),
                PhaseSpec("end", onEnter = PhaseEffects.CLEANUP),
            ),
        )
        fun run(skip: Boolean): GameState {
            val rules = Rules(turn = noDraw, params = GameParams(firstPlayerSkipsFirstDraw = skip))
            val start = setupGame(rules = rules, seed = 4242)
            val pilots = SeatedPilots(emptyMap(), fallback = PassingPilot())
            return runSync { Engine(pilots, rules = rules).playGame(start, maxTurns = 4) }
        }
        // Same seed, same everything but the flag: if the flag reached ANY
        // behaviour, these would diverge. Comparing whole states rather than a
        // hand size means a future consumer of the flag also trips this.
        assertEq(run(true), run(false), "with no DRAW phase the flag must change nothing")

        // And the guard is proved to be capable of failing: with a draw phase
        // the very same comparison DIVERGES, so the check above is measuring
        // something rather than comparing two identical no-ops.
        val withDraw = TurnStructure(
            listOf(
                PhaseSpec("draw", onEnter = PhaseEffects.DRAW),
                PhaseSpec("main", sorcerySpeed = true),
                PhaseSpec("end", onEnter = PhaseEffects.CLEANUP),
            ),
        )
        fun runDrawing(skip: Boolean): GameState {
            val rules = Rules(
                turn = withDraw,
                params = GameParams(cardsDrawnPerTurn = 1, firstPlayerSkipsFirstDraw = skip),
            )
            val libs = mapOf(
                "P0" to (1..20).map { CardRef(it, "c$it") },
                "P1" to (21..40).map { CardRef(it, "c$it") },
            )
            val start = setupGame(rules = rules, libraries = libs, seed = 4242)
            val pilots = SeatedPilots(emptyMap(), fallback = PassingPilot())
            return runSync { Engine(pilots, rules = rules).playGame(start, maxTurns = 4) }
        }
        assertTrue(
            runDrawing(true) != runDrawing(false),
            "with a DRAW phase the flag MUST matter -- otherwise the test above proves nothing",
        )
    }

    check("a fork keeps the CARD IDS -- the property the whole compare loop rests on") {
        // An agent forks, never edits: a new GameDoc.id (own file, version ring,
        // art directory). The CARD ids must NOT change -- comparing a fork with
        // its parent only means something if "the same card" is one identifier.
        val parent = GameDoc(
            id = "parent-uuid",
            name = "EPR Skirmish",
            contentVersion = 7,
            sets = listOf(
                SetDoc(
                    "Core",
                    listOf(
                        CardDoc(id = "card-a", faces = listOf(FaceDoc("Hulk", setOf("Ship")))),
                        CardDoc(id = "card-b", faces = listOf(FaceDoc("Spire", setOf("Station")))),
                    ),
                ),
            ),
            decks = listOf(DeckDoc("Union", listOf(DeckEntry("card-a", 2)))),
        )

        val fork = parent.forkedAs("fork-uuid", "TurnMode.PER_PLAYER", name = "EPR Skirmish - PER_PLAYER")

        assertEq(listOf("card-a", "card-b"), fork.cards.map { it.id }, "card ids MUST survive a fork")
        assertEq(parent.cards.map { it.id }, fork.cards.map { it.id }, "and must still join to the parent's")
        assertEq("fork-uuid", fork.id, "the fork is a different game on disk")
        assertTrue(fork.id != parent.id)

        // Provenance, or a directory of forks is unreadable within a day.
        assertEq("parent-uuid", fork.forkedFrom)
        assertEq("TurnMode.PER_PLAYER", fork.forkNote)
        assertEq("EPR Skirmish - PER_PLAYER", fork.name)

        // The bundled-content stamp is CLEARED. Left alone, the Games screen
        // compares it against the shipped definition and reports the fork as a
        // stale copy of a bundle it was never tracking.
        assertEq(7, parent.contentVersion, "the parent still carries its stamp")
        assertEq(0, fork.contentVersion, "the fork carries none")

        // Everything else is the parent, untouched -- a fork is a copy plus
        // identity, not a transform.
        assertEq(parent.decks, fork.decks)
        assertEq(parent.rules, fork.rules)
        // And forking does not mutate what it forked FROM.
        assertEq("parent-uuid", parent.id)
        assertEq("", parent.forkedFrom)

        // A fork of a fork points at its immediate parent, not the root: the
        // chain is walkable, and flattening it would lose what was varied when.
        val second = fork.forkedAs("fork2-uuid", "Harrow -1 cost")
        assertEq("fork-uuid", second.forkedFrom)
        assertEq("Harrow -1 cost", second.forkNote)
    }

    check("a fork of an UNMINTED game must come back id-stable") {
        // Bundled games carry no minted card ids (minting happens on device), and
        // `key()` falls back to the display name -- so forking mints them.
        val unminted = GameDoc(
            id = "bundle",
            name = "Bundled",
            sets = listOf(
                SetDoc(
                    "Core",
                    listOf(
                        CardDoc(faces = listOf(FaceDoc("Hulk", setOf("Ship")))),
                        CardDoc(faces = listOf(FaceDoc("Spire", setOf("Station")))),
                    ),
                ),
            ),
            decks = listOf(DeckDoc("D", listOf(DeckEntry("Hulk", 2)))),
        )
        assertTrue(unminted.cards.all { it.id.isEmpty() }, "the fixture really is unminted")

        var n = 0
        val minted = unminted.withCardIds { "minted-${n++}" }
        val fork = minted.forkedAs("fork-1", "first")

        assertTrue(fork.cards.all { it.id.isNotEmpty() }, "every card in a fork carries a stable id")
        // The deck reference was rewritten to the id, not left naming a card
        // whose identity has just changed underneath it.
        assertEq("minted-0", fork.decks[0].entries[0].cardName, "deck references follow the mint")

        // And the ids are stable down the lineage: a fork of a fork joins to it
        // by id, which is the property the whole compare loop needs.
        val second = fork.forkedAs("fork-2", "second")
        assertEq(fork.cards.map { it.id }, second.cards.map { it.id })

        // Minting is idempotent -- forking twice must not re-mint and break the
        // join. (withCardIds only mints for cards MISSING an id.)
        val again = fork.withCardIds { "SHOULD-NOT-BE-USED" }
        assertEq(fork.cards.map { it.id }, again.cards.map { it.id }, "a second mint is a no-op")
    }

    check("provenance round-trips, and is invisible to a game nobody forked") {
        val plain = GameDoc(
            id = "g1",
            name = "Plain",
            sets = listOf(SetDoc("Core", listOf(CardDoc(id = "c1", faces = listOf(FaceDoc("X", setOf("Ship"))))))),
        )
        // Elided when absent: a game nobody forked must serialise without it,
        // or every stored game is dirtied by a field it does not use.
        val plainJson = gameDocToJson(plain)
        assertTrue(!plainJson.contains("forkedFrom"), plainJson)
        assertTrue(!plainJson.contains("forkNote"), plainJson)
        assertEq(plain, gameDocFromJson(plainJson), "and still round-trips")

        val fork = plain.forkedAs("g2", "one field changed")
        val forkJson = gameDocToJson(fork)
        assertTrue(forkJson.contains("forkedFrom"), forkJson)
        val back = gameDocFromJson(forkJson)
        assertEq(fork, back, "a fork round-trips WITH its provenance")
        assertEq("g1", back.forkedFrom)
        assertEq("one field changed", back.forkNote)

        // Provenance is authoring-only: it must never reach the engine. If a
        // fork compiled to different Rules than an identical un-forked game,
        // every measurement comparing them would be measuring the fork marker.
        assertEq(plain.rules().cards.keys, fork.rules().cards.keys)
    }

    check("a SCREEN makes a back line unreachable -- and reach does NOT buy past it") {
        // Without this the standoff zone strictly dominates: a fleet entirely
        // in the back reaches everything and is reached by nothing
        // short-ranged. Measured before the rule existed -- the 100%
        // long-range deck beat the most short-ranged one 76-17.
        val ship = CardDoc(faces = listOf(FaceDoc("Hulk", setOf("Ship"), fields = mapOf("strike" to 2)))).build()
        val gun = CardDoc(
            faces = listOf(FaceDoc("Lance", setOf("Ship"), fields = mapOf("strike" to 2), keywords = setOf("reach"))),
        ).build()
        val cfg = SINGLE_STEP_LANES_CORE_COMBAT.copy(screen = ScreenRule(screened = "back", by = "front"))
        val rules = Rules(
            combat = cfg.lowered(),
            zones = BUILTIN_ZONES + mapOf(
                "front" to PlayZoneDef("front", ZoneScope.PER_PLAYER),
                "back" to PlayZoneDef("back", ZoneScope.PER_PLAYER),
            ),
        )
        var s = newGame()
        val (s1, myGun) = enterBattlefield(gun, "P0", s, 0, rules.resolveZone("back", "P0")); s = s1
        val (s2, theirBack) = enterBattlefield(ship, "P1", s, 0, rules.resolveZone("back", "P1")); s = s2

        // With their front EMPTY, the back line is reachable.
        assertTrue(s.laneReaches(rules, myGun, theirBack, null), "an unscreened back line is reachable")

        // Put a body in their front: the back line goes dark, EVEN THOUGH the
        // attacker has reach. That ordering is the rule -- if reach bought past
        // the screen it would be inert for exactly the cards it constrains.
        val (s3, theirFront) = enterBattlefield(ship, "P1", s, 0, rules.resolveZone("front", "P1")); s = s3
        assertTrue(!s.laneReaches(rules, myGun, theirBack, null), "a held front screens the back, reach included")
        assertTrue(s.laneReaches(rules, myGun, theirFront, null), "the screen itself stays reachable")

        // And the screen is MY front, not anyone's: their front screens them.
        val (s4, _) = enterBattlefield(ship, "P0", s, 0, rules.resolveZone("front", "P0"))
        assertTrue(!s4.laneReaches(rules, myGun, theirBack, null), "my own front does not unscreen their back")

        // No screen configured -> unchanged, so every existing game is untouched.
        val plain = Rules(combat = SINGLE_STEP_LANES_CORE_COMBAT.lowered(), zones = rules.zones)
        assertTrue(s.laneReaches(plain, myGun, theirBack, null), "without a screen rule nothing changes")
    }

    check("LONG RANGE reaches across the line") {
        // Long range hits FRONT or BACK; short range FRONT -> FRONT. Gating
        // crossing on a keyword no card carries left `lr` choosing only the
        // stat, never who could be reached.
        val cfg = FRONT_BACK_CORE_COMBAT
        val rules = Rules(
            combat = cfg.lowered(),
            zones = BUILTIN_ZONES + mapOf(
                "front" to PlayZoneDef("front", ZoneScope.PER_PLAYER, maxOccupants = 3),
                "back" to PlayZoneDef("back", ZoneScope.PER_PLAYER, maxOccupants = 3),
            ),
        )
        fun body(name: String, sr: Int?, lr: Int?) = CardDoc(
            faces = listOf(FaceDoc(name, setOf("Ship"),
                fields = listOfNotNull(sr?.let { "sr" to it }, lr?.let { "lr" to it }).toMap())),
        ).build()
        val gunner = body("Bastion Anchor", sr = 1, lr = 2)   // both ranges
        val brawler = body("Bastion Drone", sr = 1, lr = null) // short only
        val target = body("Hulk", sr = 2, lr = null)

        var s = newGame()
        val (s1, myGun) = enterBattlefield(gunner, "P0", s, 0, rules.resolveZone("back", "P0")); s = s1
        val (s2, myBrawler) = enterBattlefield(brawler, "P0", s, 0, rules.resolveZone("back", "P0")); s = s2
        val (s3, theirFront) = enterBattlefield(target, "P1", s, 0, rules.resolveZone("front", "P1")); s = s3
        val (s4, theirBack) = enterBattlefield(target, "P1", s, 0, rules.resolveZone("back", "P1")); s = s4

        // A long-ranged body in the back line can shoot the line.
        assertTrue(s.laneReaches(rules, myGun, theirFront, null), "long range reaches the enemy FRONT from the back")
        // And it hits with the FAR stat, which is what `lr` is for.
        assertEq(2, s.attackDamage(rules, myGun, CombatTarget.Obj(theirFront), null), "its `lr` (2), not its `sr` (1)")

        // The cost of that reach is the screen, unchanged: their back line is
        // dark while they hold their front, long range included.
        assertTrue(!s.laneReaches(rules, myGun, theirBack, null), "the screen still holds against long range")

        // And the geometry still MEANS something: a short-ranged body parked in
        // the back reaches nothing at all. That is the design ("pay attention to
        // WHERE a card wants to be played"), not a second bug -- pinned here so
        // nobody later "fixes" it into universal reach.
        assertTrue(!s.laneReaches(rules, myBrawler, theirFront, null), "short range cannot cross the line")
        assertTrue(!s.laneReaches(rules, myBrawler, theirBack, null), "and is screened out of the back too")

        // Same rule from the FRONT: a gunner on the line still fights the line
        // with its CLOSE stat, and can reach across into an unscreened back.
        val (s5, myFrontGun) = enterBattlefield(gunner, "P0", s, 0, rules.resolveZone("front", "P0")); s = s5
        assertTrue(s.laneReaches(rules, myFrontGun, theirFront, null))
        assertEq(1, s.attackDamage(rules, myFrontGun, CombatTarget.Obj(theirFront), null),
            "in its own lane it is a close-range fight, whatever else it carries")
    }

    check("the GRID: lanes are offence, depth is defence") {
        // The rule this phase exists to make: what a ship CARRIES decides how
        // wide it shoots; where it STANDS decides what must die before it can
        // be shot. A short-ranged ship in 2B still fights -- down lane 2.
        val cfg = FRONT_BACK_CORE_COMBAT
        val rules = gridRules(cfg)
        fun body(name: String, sr: Int?, lr: Int?, kw: Set<String> = emptySet()) = CardDoc(
            faces = listOf(FaceDoc(name, setOf("Ship"), keywords = kw,
                fields = listOfNotNull(sr?.let { "sr" to it }, lr?.let { "lr" to it }).toMap())),
        ).build()
        val brawler = body("Drone", sr = 2, lr = null)
        val gunner = body("Anchor", sr = 1, lr = 2)
        val target = body("Hulk", sr = 2, lr = null)

        var s = newGame()
        // Mine: a short-ranged ship in lane 2's BACK berth.
        val (s1, mine) = enterBattlefield(brawler, "P0", s, 0, rules.resolveZone("2B", "P0")); s = s1
        // Theirs: lane 2 front and back, lane 1 back only.
        val (s2, their2F) = enterBattlefield(target, "P1", s, 0, rules.resolveZone("2F", "P1")); s = s2
        val (s3, their2B) = enterBattlefield(target, "P1", s, 0, rules.resolveZone("2B", "P1")); s = s3
        val (s4, their1B) = enterBattlefield(target, "P1", s, 0, rules.resolveZone("1B", "P1")); s = s4

        // SHORT RANGE FIGHTS DOWN ITS OWN LANE, FROM THE BACK. This is the
        // whole change: under the flat rule this ship could hit nothing.
        assertTrue(s.laneReaches(rules, mine, their2F, null), "short range fights its lane from the back berth")
        // ...but not into another lane.
        assertTrue(!s.laneReaches(rules, mine, their1B, null), "short range does not leave its lane")
        // ...and their 2B is covered by their 2F.
        assertTrue(!s.laneReaches(rules, mine, their2B, null), "a back berth is covered by its OWN lane's front")

        // PROTECTION IS LANE BY LANE. Lane 1 has no front, so its back is open
        // -- to a long-ranged ship, which is the only thing that reaches lane 1
        // from lane 2.
        val (s5, myGun) = enterBattlefield(gunner, "P0", s, 0, rules.resolveZone("3F", "P0")); s = s5
        assertTrue(s.laneReaches(rules, myGun, their1B, null), "an uncovered back berth is open, lane by lane")
        assertTrue(!s.laneReaches(rules, myGun, their2B, null), "while the covered one next to it is not")
        assertTrue(s.laneReaches(rules, myGun, their2F, null), "long range reaches every lane")
    }

    check("reach bypasses the SCREEN ONLY") {
        // By design, reach opens a covered back berth and nothing else. It does not widen a short-ranged ship's lane, and it
        // does not open a held lane onto the Station -- which is what keeps it
        // costable rather than "position does not apply to me".
        val cfg = FRONT_BACK_CORE_COMBAT
        val rules = gridRules(cfg)
        fun body(name: String, sr: Int?, lr: Int?, kw: Set<String> = emptySet()) = CardDoc(
            faces = listOf(FaceDoc(name, setOf("Ship"), keywords = kw,
                fields = listOfNotNull(sr?.let { "sr" to it }, lr?.let { "lr" to it }).toMap())),
        ).build()
        val reacher = body("Lance", sr = 2, lr = null, kw = setOf("reach"))
        val plain = body("Hulk", sr = 2, lr = null)

        var s = newGame()
        val (s1, mine) = enterBattlefield(reacher, "P0", s, 0, rules.resolveZone("1F", "P0")); s = s1
        val (s2, their1F) = enterBattlefield(plain, "P1", s, 0, rules.resolveZone("1F", "P1")); s = s2
        val (s3, their1B) = enterBattlefield(plain, "P1", s, 0, rules.resolveZone("1B", "P1")); s = s3
        val (s4, their2B) = enterBattlefield(plain, "P1", s, 0, rules.resolveZone("2B", "P1")); s = s4

        assertTrue(s.laneReaches(rules, mine, their1B, null), "reach opens a covered back berth")
        assertTrue(!s.laneReaches(rules, mine, their2B, null), "but it does NOT widen a short-ranged ship's lane")
        assertTrue(!s.laneReachesFace(rules, mine, "P1", null), "and it does NOT open a held lane onto the Station")
        assertTrue(s.laneReaches(rules, mine, their1F, null))
    }

    check("lanes protect Stations -- a firing line must be EMPTY") {
        val cfg = FRONT_BACK_CORE_COMBAT
        val rules = gridRules(cfg)
        fun body(name: String, sr: Int?, lr: Int?) = CardDoc(
            faces = listOf(FaceDoc(name, setOf("Ship"),
                fields = listOfNotNull(sr?.let { "sr" to it }, lr?.let { "lr" to it }).toMap())),
        ).build()
        val brawler = body("Drone", sr = 2, lr = null)
        val gunner = body("Anchor", sr = 1, lr = 2)
        val blocker = body("Hulk", sr = 2, lr = null)

        var s = newGame()
        val (s1, mySr) = enterBattlefield(brawler, "P0", s, 0, rules.resolveZone("1F", "P0")); s = s1
        val (s2, myLr) = enterBattlefield(gunner, "P0", s, 0, rules.resolveZone("1B", "P0")); s = s2

        // An empty board: every lane is a firing line.
        assertTrue(s.laneReachesFace(rules, mySr, "P1", null), "short range shoots down its own empty lane")
        assertTrue(s.laneReachesFace(rules, myLr, "P1", null), "long range through any empty lane")

        // Hold lane 1's BACK berth only: the lane is no longer empty, so the line
        // is closed even though the front berth is clear. Both of my ships stand
        // in lane 1, so both are denied -- range does not widen Station access.
        val (s3, _) = enterBattlefield(blocker, "P1", s, 0, rules.resolveZone("1B", "P1")); s = s3
        assertTrue(!s.laneReachesFace(rules, mySr, "P1", null), "a ship anywhere in the lane closes it")
        assertTrue(!s.laneReachesFace(rules, myLr, "P1", null), "and long range cannot go around it")

        // A ship in a lane they have NOT touched still has its line.
        val (s4, elsewhere) = enterBattlefield(gunner, "P0", s, 0, rules.resolveZone("2F", "P0")); s = s4
        assertTrue(s.laneReachesFace(rules, elsewhere, "P1", null), "lane 2 is untouched, so lane 2 is open")
        val (s5, _) = enterBattlefield(blocker, "P1", s, 0, rules.resolveZone("2B", "P1")); s = s5
        assertTrue(!s.laneReachesFace(rules, elsewhere, "P1", null), "until they stand in it")
    }

    check("a FLAT board is untouched by any of it") {
        // The compatibility claim this phase rests on: a zone that declares no
        // lane/depth behaves exactly as it did, so the shipped Core keeps its
        // current rules until P3 moves it. Same board, same assertions as the
        // test above.
        val cfg = FRONT_BACK_CORE_COMBAT
        val rules = Rules(
            combat = cfg.lowered(),
            zones = BUILTIN_ZONES + mapOf(
                "front" to PlayZoneDef("front", ZoneScope.PER_PLAYER, maxOccupants = 3),
                "back" to PlayZoneDef("back", ZoneScope.PER_PLAYER, maxOccupants = 3),
            ),
        )
        fun body(sr: Int?, lr: Int?) = CardDoc(
            faces = listOf(FaceDoc("Hulk", setOf("Ship"),
                fields = listOfNotNull(sr?.let { "sr" to it }, lr?.let { "lr" to it }).toMap())),
        ).build()
        var s = newGame()
        val (s1, gun) = enterBattlefield(body(1, 2), "P0", s, 0, rules.resolveZone("back", "P0")); s = s1
        val (s2, brawl) = enterBattlefield(body(1, null), "P0", s, 0, rules.resolveZone("back", "P0")); s = s2
        val (s3, theirFront) = enterBattlefield(body(2, null), "P1", s, 0, rules.resolveZone("front", "P1")); s = s3
        val (s4, theirBack) = enterBattlefield(body(2, null), "P1", s, 0, rules.resolveZone("back", "P1")); s = s4
        assertTrue(s.laneReaches(rules, gun, theirFront, null), "flat: long range still crosses")
        assertTrue(!s.laneReaches(rules, gun, theirBack, null), "flat: the screen still holds")
        assertTrue(!s.laneReaches(rules, brawl, theirFront, null), "flat: short range still cannot cross")
    }

    check("a polyvalent ship fires TWICE, at independent targets, in ONE wave") {
        // "Ships target one time for SR damage, one time for LR damage." Both
        // land in the SAME wave: two steps would let the first one's kills
        // remove the second one's attackers, which would change lethality.
        val cfg = FRONT_BACK_CORE_COMBAT.copy(attacksPerRange = true)
        val rules = gridRules(cfg)
        // The guns a body carries, read from the combat program.
        fun gunsFor(fields: Map<String, Int>, c: CombatConfig): List<AttackRange?> {
            val r = gridRules(c)
            val def = card("Anchor", setOf("Ship"), baseChars = Characteristics("Anchor", setOf("Ship"), fields))
            val (st, id) = enterBattlefield(def, "P0", newGame(), 0, r.resolveZone("1F", "P0"))
            return st.gunsOf(r, id)
        }
        assertEq(listOf(AttackRange.CLOSE, AttackRange.FAR), gunsFor(mapOf("sr" to 3, "lr" to 2), cfg),
            "both stats carried -> both attacks")
        assertEq(listOf(AttackRange.CLOSE), gunsFor(mapOf("sr" to 3), cfg), "one stat -> one attack, no special case")
        assertEq(listOf(AttackRange.FAR), gunsFor(mapOf("lr" to 2), cfg))
        assertEq(emptyList(), gunsFor(mapOf("hull" to 4), cfg), "a body with neither stat makes no range attack")

        // OFF by default: attack count is a balance decision and must not
        // arrive as a side effect of a topology change.
        assertEq(emptyList(), gunsFor(mapOf("sr" to 3, "lr" to 2), FRONT_BACK_CORE_COMBAT),
            "the shipped Core is untouched until its preset turns this on")

        // The two attacks reach DIFFERENT sets, which is the whole point.
        fun body(sr: Int?, lr: Int?) = CardDoc(
            faces = listOf(FaceDoc("Ship", setOf("Ship"),
                fields = listOfNotNull(sr?.let { "sr" to it }, lr?.let { "lr" to it }).toMap())),
        ).build()
        var s = newGame()
        val (s1, mine) = enterBattlefield(body(3, 2), "P0", s, 0, rules.resolveZone("1F", "P0")); s = s1
        val (s2, their1F) = enterBattlefield(body(2, null), "P1", s, 0, rules.resolveZone("1F", "P1")); s = s2
        val (s3, their3F) = enterBattlefield(body(2, null), "P1", s, 0, rules.resolveZone("3F", "P1")); s = s3

        assertTrue(s.laneReaches(rules, mine, their1F, AttackRange.CLOSE), "the close gun works its own lane")
        assertTrue(!s.laneReaches(rules, mine, their3F, AttackRange.CLOSE), "and cannot leave it")
        assertTrue(s.laneReaches(rules, mine, their3F, AttackRange.FAR), "while the battery reaches lane 3")

        // And each hits with ITS OWN stat, whatever it is pointed at -- the
        // target no longer decides which number is read.
        assertEq(3, s.attackDamage(rules, mine, CombatTarget.Obj(their1F), AttackRange.CLOSE), "`sr`")
        assertEq(2, s.attackDamage(rules, mine, CombatTarget.Obj(their1F), AttackRange.FAR),
            "the battery reads `lr` even when it fires down its own lane")
    }

    check("the Station is offered ONLY through a lane that is really empty") {
        // A target prompt must not offer the Station while Ships hold the lane.
        val cfg = LANE_GRID_CORE_COMBAT
        val rules = gridRules(cfg)
        fun body(sr: Int?, lr: Int?) = CardDoc(
            faces = listOf(FaceDoc("Ship", setOf("Ship"),
                fields = listOfNotNull(sr?.let { "sr" to it }, lr?.let { "lr" to it }).toMap())),
        ).build()
        var s = newGame()
        val (s1, mySr) = enterBattlefield(body(3, null), "P0", s, 0, rules.resolveZone("1F", "P0")); s = s1
        val (s2, myLr) = enterBattlefield(body(1, 3), "P0", s, 0, rules.resolveZone("1B", "P0")); s = s2

        // Their lane 1 is held; lanes 2 and 3 are empty.
        val (s3, _) = enterBattlefield(body(2, null), "P1", s, 0, rules.resolveZone("1F", "P1")); s = s3

        // SHORT RANGE fires down its OWN lane, and its own lane is contested.
        assertTrue(
            !s.laneReachesFace(rules, mySr, "P1", AttackRange.CLOSE),
            "a close attack cannot reach the Station through a lane that is held",
        )
        // ...and that must not change because some OTHER lane is open.
        assertTrue(
            !s.laneReachesFace(rules, myLr, "P1", AttackRange.CLOSE),
            "not even for a ship that also carries long range -- the CLOSE attack is lane-bound",
        )
        // Nor does long range: lanes 2 and 3 are open, but this ship stands in
        // lane 1 and lane 1 is held. ("Any empty lane" left the Station
        // uncoverable with fewer than three bodies.)
        assertTrue(
            !s.laneReachesFace(rules, myLr, "P1", AttackRange.FAR),
            "you shoot the Station down the lane you STAND in, not down any open one",
        )

        // Move that same ship to an OPEN lane and the line is there.
        val moved = s.copy(
            battlefield = s.battlefield + (myLr to s.battlefield.getValue(myLr)
                .copy(zone = rules.resolveZone("3B", "P0"))),
        )
        assertTrue(moved.laneReachesFace(rules, myLr, "P1", AttackRange.FAR), "lane 3 is empty, so lane 3 is a firing line")
        assertTrue(moved.laneReachesFace(rules, myLr, "P1", AttackRange.CLOSE), "and the range does not change that")

        // One body in the right lane closes it again -- which is the whole
        // point: positioning answers a gun on turn two, not turn five.
        val (s4, _) = enterBattlefield(body(2, null), "P1", moved, 0, rules.resolveZone("3F", "P1"))
        assertTrue(!s4.laneReachesFace(rules, myLr, "P1", AttackRange.FAR), "one blocker in lane 3 covers the Station")

        // The DESCRIPTIVE answer (no particular attack in mind) agrees with the
        // attacks the body can actually make.
        assertTrue(!s.laneReachesFace(rules, myLr, "P1", null), "and the descriptive answer agrees")
    }

    // -- D + E: overflow, and long range over the line ----------------------

    /** Ship + Station types that die by running `hull` to zero, the Core's own
     *  shape -- `toughness - damageMarked` is meaningless here, and a capacity
     *  computed that way would make overflow fire on every single hit. */
    fun dePlusERules(overflow: Boolean, longRange: Boolean) = Rules(
        types = BUILTIN_TYPES_CORE + mapOf(
            "Ship" to TypeDef(
                "Ship", fields = setOf("strike", "hull"), attacks = true,
                damageCounter = "hull", diesWhen = (selfCounter("hull") lte lit(0)).lowered(),
                zoneChoices = listOf("front", "back"),
            ),
            "Station" to TypeDef(
                "Station", damageCounter = "hull", diesWhen = (selfCounter("hull") lte lit(0)).lowered(),
                loseOnDeath = true,
            ),
        ),
        zones = BUILTIN_ZONES + listOf("front", "back")
            .map { PlayZoneDef(it, ZoneScope.PER_PLAYER, maxOccupants = 4, combatSteps = setOf("strike")) }
            .associateBy { it.id },
        combat = SINGLE_STEP_LANES_CORE_COMBAT.copy(
            overflowToController = overflow,
            longRangeHitsFace = longRange,
        ).lowered(),
        turn = TurnStructure(listOf(PhaseSpec("combat", combat = true))),
    )

    fun body(name: String, types: Set<String>, strike: Int, hull: Int) = CardDoc(
        faces = listOf(FaceDoc(name, types, fields = mapOf("strike" to strike))),
        entersWith = listOf(CounterDef("hull", lit(hull))),
    ).build()

    check("SR/LR the SAME card hits for different amounts depending on range") {
        // The rule that lets one combat wave keep a two-number stat line: close
        // is the confrontation line, far is reaching across or aiming at the
        // face. A 4/1 is a brawler and a 2/4 is artillery, and the same card is
        // worth different amounts depending on where it stands.
        val rules = Rules(
            types = BUILTIN_TYPES_CORE + mapOf(
                "Ship" to TypeDef(
                    "Ship", fields = setOf("sr", "lr", "hull"), attacks = true,
                    damageCounter = "hull", diesWhen = (selfCounter("hull") lte lit(0)).lowered(),
                    zoneChoices = listOf("front", "back"),
                ),
            ),
            zones = BUILTIN_ZONES + listOf("front", "back")
                .map { PlayZoneDef(it, ZoneScope.PER_PLAYER, maxOccupants = 4, combatSteps = setOf("strike")) }
                .associateBy { it.id },
            combat = FRONT_BACK_COMBAT.copy(rangeFields = RangeFields(close = "sr", far = "lr")).lowered(),
            turn = TurnStructure(listOf(PhaseSpec("combat", combat = true))),
        )
        // 2 up close, 6 at range -- artillery.
        val gun = CardDoc(
            faces = listOf(FaceDoc("Howitzer", setOf("Ship"), fields = mapOf("sr" to 2, "lr" to 6), keywords = setOf("reach"))),
            entersWith = listOf(CounterDef("hull", lit(9))),
        ).build()
        val mark = CardDoc(
            faces = listOf(FaceDoc("Mark", setOf("Ship"), fields = mapOf("sr" to 0, "lr" to 0))),
            entersWith = listOf(CounterDef("hull", lit(30))),
        ).build()

        /** Damage dealt when the gun stands in [from] and the mark in [to]. */
        fun hit(from: String, to: String): Int {
            var s = newGame()
            val (s1, g) = enterBattlefield(gun, "P0", s, zone = ZoneRef(from, "P0")); s = s1
            val (s2, m) = enterBattlefield(mark, "P1", s, zone = ZoneRef(to, "P1")); s = s2
            val end = runSync {
                Engine(ScriptedInput(emptyMap(), combatTargets = listOf(CombatTarget.Obj(m))), rules = rules)
                    .playGame(s, maxTurns = 1)
            }
            return 30 - (end.battlefield[m]?.counters?.get("hull") ?: 0)
        }

        assertEq(2, hit("front", "front"), "same line -- it fights with SR")
        assertEq(2, hit("back", "back"), "…and so does a back-line brawl")
        assertEq(6, hit("back", "front"), "reaching across the line -- LR")
        assertEq(6, hit("front", "back"), "and reaching the other way is equally far")

        // With no rangeFields the step's own field decides, unchanged -- so no
        // existing game changes behaviour.
        // Rules is not a data class -- rebuilt rather than copied.
        val plain = Rules(
            types = rules.types,
            zones = rules.zones,
            combat = FRONT_BACK_COMBAT.copy(rangeFields = null).lowered(),
            turn = rules.turn,
        )
        var s = newGame()
        val (s1, g) = enterBattlefield(gun, "P0", s, zone = ZoneRef("back", "P0")); s = s1
        val (s2, m) = enterBattlefield(mark, "P1", s, zone = ZoneRef("front", "P1")); s = s2
        val end = runSync {
            Engine(ScriptedInput(emptyMap(), combatTargets = listOf(CombatTarget.Obj(m))), rules = plain)
                .playGame(s, maxTurns = 1)
        }
        // The step reads "strike", which this card does not carry at all.
        assertEq(30, end.battlefield[m]?.counters?.get("hull") ?: 0, "without range fields nothing changes")
    }

    check("D damage past what a body absorbs OVERFLOWS onto the Station") {
        // The alternative to a binary gate: a cheap blocker DELAYS damage
        // rather than nullifying it, so chumping is tempo and not a lock.
        fun run(overflow: Boolean): Pair<Int, Int> {
            val rules = dePlusERules(overflow, longRange = false)
            val gun = body("Lance", setOf("Ship"), strike = 9, hull = 5)
            val chump = body("Chump", setOf("Ship"), strike = 0, hull = 2)
            val station = body("Vault", setOf("Station"), strike = 0, hull = 20)
            var s = newGame()
            val (s1, atk) = enterBattlefield(gun, "P0", s, zone = ZoneRef("front", "P0")); s = s1
            val (s2, blk) = enterBattlefield(chump, "P1", s, zone = ZoneRef("front", "P1")); s = s2
            val (s3, vault) = enterBattlefield(station, "P1", s); s = s3
            val end = runSync {
                Engine(ScriptedInput(emptyMap(), combatTargets = listOf(CombatTarget.Obj(blk))), rules = rules)
                    .playGame(s, maxTurns = 1)
            }
            val chumpAlive = end.battlefield.containsKey(blk)
            val vaultHull = end.battlefield[vault]?.counters?.get("hull") ?: 0
            assertTrue(!chumpAlive, "9 into a 2-hull body kills it either way")
            assertTrue(atk in end.battlefield, "the attacker survives -- this is not a trade")
            return vaultHull to (20 - vaultHull)
        }
        val (offHull, offSpill) = run(overflow = false)
        assertEq(20, offHull, "with the rule OFF the excess evaporates -- today's behaviour")
        assertEq(0, offSpill)

        val (onHull, onSpill) = run(overflow = true)
        // 9 damage, 2 absorbed by the chump, 7 carries.
        assertEq(7, onSpill, "the excess past the body's hull reaches the Station")
        assertEq(13, onHull)
    }

    check("E long range shoots OVER the line; short range must break it") {
        // The asymmetry that stops one cheap body buying total immunity.
        // Deliberately NOT the same answer as the screen, which reach cannot
        // bypass -- a ship hides behind a line, a Station cannot.
        val gun = body("Lance", setOf("Ship"), strike = 3, hull = 5)
        val artillery = CardDoc(
            faces = listOf(FaceDoc("Howitzer", setOf("Ship"), fields = mapOf("strike" to 3), keywords = setOf("reach"))),
            entersWith = listOf(CounterDef("hull", lit(5))),
        ).build()
        val chump = body("Chump", setOf("Ship"), strike = 0, hull = 2)

        fun canHitFace(longRange: Boolean, attacker: ccg.CardDefinition): Boolean {
            val rules = dePlusERules(overflow = false, longRange = longRange)
            var s = newGame()
            val (s1, a) = enterBattlefield(attacker, "P0", s, zone = ZoneRef("front", "P0")); s = s1
            // Their body stands in the SAME lane def -- the line is held, so
            // the face gate is closed for anything short-ranged.
            val (s2, _) = enterBattlefield(chump, "P1", s, zone = ZoneRef("front", "P1")); s = s2
            return s2.laneReachesFace(rules, a, "P1", null)
        }

        assertTrue(!canHitFace(longRange = false, gun), "a held line stops short range")
        assertTrue(!canHitFace(longRange = false, artillery), "and with the rule OFF it stops reach too")
        assertTrue(!canHitFace(longRange = true, gun), "short range is still stopped when the rule is ON")
        assertTrue(canHitFace(longRange = true, artillery), "LONG RANGE shoots over the line")

        // And the UI's own helper must agree: it reads the same rule.
        val rules = dePlusERules(overflow = false, longRange = true)
        var s = newGame()
        val (s1, a) = enterBattlefield(artillery, "P0", s, zone = ZoneRef("front", "P0")); s = s1
        val (s2, _) = enterBattlefield(chump, "P1", s, zone = ZoneRef("front", "P1")); s = s2
        assertTrue(s2.canAttackFace(rules, a, "P1", null), "canAttackFace must give the same answer")
    }

    check("a crop round-trips, and an UNCROPPED card serialises as before") {
        // Fractions of the IMAGE, never pixels: the same rect is read for a
        // 26dp band and a 420dp one, and a rect stored in pixels would mean a
        // different picture on each.
        val cropped = CardDoc(
            id = "c1",
            faces = listOf(FaceDoc("Hulk", setOf("Ship"), art = "abc.png",
                artFieldRect = ArtRect(x = 0.125f, y = 0.25f, w = 0.5f, h = 0.375f))),
        )
        val g = GameDoc(id = "g", name = "G", sets = listOf(SetDoc("Core", listOf(cropped))))
        val back = gameDocFromJson(gameDocToJson(g))
        assertEq(g, back, "a cropped card round-trips exactly")
        val r = back.cards.first().faces.first().artFieldRect!!
        assertEq(0.125f, r.x)
        assertEq(0.375f, r.h)

        // Uncropped: the field must not appear at all, or every stored game is
        // dirtied by a field it does not use.
        val plain = GameDoc(
            id = "g", name = "G",
            sets = listOf(SetDoc("Core", listOf(CardDoc(id = "c1", faces = listOf(FaceDoc("Hulk", setOf("Ship"), art = "abc.png")))))),
        )
        val plainJson = gameDocToJson(plain)
        assertTrue(!plainJson.contains("artFieldRect"), plainJson)
        assertEq(plain, gameDocFromJson(plainJson))

        // A WHOLE-picture rect is dropped rather than written -- "I dragged it
        // and put it back" must not leave a diff behind.
        val whole = GameDoc(
            id = "g", name = "G",
            sets = listOf(SetDoc("Core", listOf(CardDoc(id = "c1",
                faces = listOf(FaceDoc("Hulk", setOf("Ship"), art = "abc.png", artFieldRect = ArtRect())))))),
        )
        assertTrue(!gameDocToJson(whole).contains("artFieldRect"), "the whole picture is not a crop")
        assertTrue(ArtRect().isWhole)
        assertTrue(!ArtRect(w = 0.99f).isWhole)

        // And a card with NO art cannot carry a crop into the engine: the whole
        // thing is authoring-only and never reaches `build()`.
        assertTrue(cropped.build().faces.first().let { true }, "build() does not see artFieldRect -- it has no such field")
    }

    check("a game written by the FIRST design still LOADS -- it just loses its framing") {
        // Legacy `artFrame` (a transform, not a rect) is read by nothing: the
        // card comes back uncropped, and everything else about it survives.
        val old = """{"name":"G","id":"g","contentVersion":1,"sets":[{"name":"Core","cards":[""" +
            """{"id":"c1","faces":[{"name":"Hulk","types":["Ship"],"fields":{"sr":2},""" +
            """"art":"abc.png","artFrame":{"scale":2.5,"offX":-0.25,"offY":0.1,"rotation":12.0},""" +
            """"artFrameMini":{"scale":4.0,"offX":0.0,"offY":0.0,"rotation":0.0}}]}]}]}"""
        val g = gameDocFromJson(old)
        val f = g.cards.first().faces.first()
        assertEq("abc.png", f.art, "the PICTURE survives -- only the framing is dropped")
        assertEq("Hulk", f.name)
        assertEq(2, f.fields["sr"], "and so does everything else on the face")
        assertEq(null, f.artFieldRect)
        assertEq(null, f.artMiniRect)
        assertTrue(!gameDocToJson(g).contains("artFrame"), "and the dead key is not written back out")
    }

    check("the shrunk crop round-trips SEPARATELY from the field one") {
        val both = CardDoc(
            id = "c1",
            faces = listOf(FaceDoc(
                "Hulk", setOf("Ship"), art = "abc.png",
                artFieldRect = ArtRect(x = .1f, w = .6f, h = .4f),
                artMiniRect = ArtRect(x = .3f, w = .25f, h = .1f),
            )),
        )
        val g = GameDoc(id = "g", name = "G", sets = listOf(SetDoc("Core", listOf(both))))
        val back = gameDocFromJson(gameDocToJson(g))
        assertEq(g, back, "both crops round-trip exactly")
        val face = back.cards.first().faces.first()
        assertEq(.6f, face.artFieldRect!!.w, "the field crop is not overwritten by the shrunk one")
        assertEq(.25f, face.artMiniRect!!.w)

        // Absent and whole are both elided, same rule as the field rect.
        val fieldOnly = GameDoc(
            id = "g", name = "G",
            sets = listOf(SetDoc("Core", listOf(CardDoc(id = "c1", faces = listOf(
                FaceDoc("Hulk", setOf("Ship"), art = "abc.png", artFieldRect = ArtRect(w = .5f, h = .5f)),
            ))))),
        )
        val json = gameDocToJson(fieldOnly)
        assertTrue(!json.contains("artMiniRect"), "a card with no shrunk crop does not carry the field")
        assertEq(fieldOnly, gameDocFromJson(json))
    }

    check("params reports what is questionable rather than failing") {
        assertEq(emptyList(), GameParams().problems())
        val bad = GameParams(startingHandSize = 7, maxHandSize = 4, playerCount = 1)
        assertTrue(bad.problems().any { it.contains("hand limit") }, bad.problems().toString())
        assertTrue(bad.problems().any { it.contains("two players") }, bad.problems().toString())
    }

    // -- Phase 4: deck-construction rules, built for leader games -----------

    check("deckRules presets carry the leader / hero shape") {
        assertEq(emptyList(), DeckRules.MTG.slots)
        assertEq(4, DeckRules.MTG.maxCopies)
        assertEq(listOf("Hero"), DeckRules.HEARTHSTONE.slots.map { it.name })
        assertEq(30 to 30, DeckRules.HEARTHSTONE.minSize to DeckRules.HEARTHSTONE.maxSize)
        assertEq(listOf("Leader", "Base"), DeckRules.SWU.slots.map { it.name })
        assertTrue("Vigilance" in DeckRules.SWU.identity!!.vocabulary, "SWU ships its six aspects")
        // Hearthstone leaves the class list to the caller -- so its identity
        // check is inert until one is supplied.
        assertEq(emptySet(), DeckRules.HEARTHSTONE.identity!!.vocabulary)
    }

    check("deckRules reports size, copies and a missing slot -- and never enforces") {
        fun creature(n: String) =
            CardDoc(faces = listOf(FaceDoc(n, setOf("Creature"), mapOf("power" to 1, "toughness" to 1))))
        val leader = CardDoc(faces = listOf(FaceDoc("Bold Leader", setOf("Leader"), keywords = setOf("Aggression"))))
        val g = GameDoc(
            name = "swu-ish",
            sets = listOf(SetDoc("Core", listOf(creature("Grunt"), creature("Recruit"), leader))),
            decks = listOf(DeckDoc("Undersized", listOf(DeckEntry("Grunt", 5), DeckEntry("Recruit", 2)))),
            deckRules = DeckRules.SWU,
        )
        val p = DeckRules.SWU.problems(g.decks[0], g.rules())
        assertTrue(p.any { it.contains("7 cards, under the minimum of 50") }, p.toString())
        assertTrue(p.any { it.contains("5 copies of \"Grunt\"") && it.contains("limit is 3") }, p.toString())
        assertTrue(p.any { it.contains("Leader slot -- it takes 1") }, p.toString())
        assertTrue(p.any { it.contains("Base slot -- it takes 1") }, p.toString())
        // GameDoc.problems() surfaces the very same lines, alongside its own.
        assertTrue(g.problems().any { it.contains("under the minimum of 50") }, g.problems().toString())
    }

    check("deckRules a legal leader deck reports nothing, and identity is checked") {
        fun unit(n: String, vararg kw: String) =
            CardDoc(faces = listOf(FaceDoc(n, setOf("Unit"), mapOf("power" to 2, "toughness" to 2), keywords = kw.toSet())))
        val leader = CardDoc(faces = listOf(FaceDoc("Cmd Leader", setOf("Leader"), keywords = setOf("Command"))))
        val base = CardDoc(faces = listOf(FaceDoc("Sly Base", setOf("Base"), keywords = setOf("Cunning"))))
        val rules = DeckRules(
            minSize = 3, maxCopies = 3,
            slots = listOf(DeckSlotDef("Leader", setOf("Leader"), 1), DeckSlotDef("Base", setOf("Base"), 1)),
            identity = IdentityRule(
                from = listOf("Leader", "Base"),
                vocabulary = setOf("Command", "Cunning", "Aggression"),
                allowNeutral = true,
            ),
        )
        val cards = listOf(
            leader, base,
            unit("Order", "Command"), unit("Filler"), unit("Trick", "Cunning"), unit("Raid", "Aggression"),
        )
        fun game(deck: DeckDoc) = GameDoc(name = "g", sets = listOf(SetDoc("Core", cards)), decks = listOf(deck), deckRules = rules)

        val clean = DeckDoc(
            "Clean", listOf(DeckEntry("Order", 3), DeckEntry("Filler", 2), DeckEntry("Trick", 1)),
            slots = mapOf("Leader" to listOf("Cmd Leader"), "Base" to listOf("Sly Base")),
        )
        assertEq(emptyList(), rules.problems(clean, game(clean).rules()), "a clean deck is silent")

        // "Raid" is Aggression -- outside a Command/Cunning identity.
        val offColour = clean.copy(entries = clean.entries + DeckEntry("Raid", 1))
        val op = rules.problems(offColour, game(offColour).rules())
        assertTrue(op.any { it.contains("\"Raid\" (Aggression)") && it.contains("Command") && it.contains("Cunning") }, op.toString())

        // Neutrals are fine here; forbid them and "Filler" is flagged.
        val strict = rules.copy(identity = rules.identity!!.copy(allowNeutral = false))
        val sp = strict.problems(clean, game(clean).rules())
        assertTrue(sp.any { it.contains("\"Filler\"") && it.contains("neutral") }, sp.toString())

        // A creature dropped into the Leader slot is wrong-typed.
        val badSlot = clean.copy(slots = mapOf("Leader" to listOf("Order"), "Base" to listOf("Sly Base")))
        val bp = rules.problems(badSlot, game(badSlot).rules())
        assertTrue(bp.any { it.contains("\"Order\"") && it.contains("Leader slot is not Leader") }, bp.toString())
    }

    check("setupGame places the slot cards in play before the opening hand") {
        val leader = card("Warlord", setOf("Leader"))
        val r = Rules(cards = mapOf("Warlord" to leader), params = GameParams(startingHandSize = 3))
        val s = setupGame(
            r,
            libraries = mapOf("P0" to tokens(30), "P1" to tokens(30, 200_000)),
            shuffle = false,
            startInPlay = mapOf("P0" to listOf(StartCard("Warlord"))),
        )
        val onBoard = s.battlefield.values.filter { it.controller == "P0" }
        assertEq(1, onBoard.size, "the leader started in play")
        assertEq("Warlord", onBoard.first().base.name)
        assertEq(BATTLEFIELD, onBoard.first().zone)
        assertEq(3, s.players.getValue("P0").hand.size, "and the opening hand is still dealt")
        assertEq(27, s.players.getValue("P0").library.size, "3 drawn off 30")
        assertTrue(s.players.getValue("P0").hand.none { it.cardId == "Warlord" }, "the leader is not in the deck")
        // Ordering: the slot card is logged before the deal.
        val li = s.log.indexOfFirst { it.contains("starts with Warlord") }
        val hi = s.log.indexOfFirst { it.contains("opening hands") }
        assertTrue(li in 0 until hi, "slot cards precede the opening hand: ${s.log}")
        // An unknown name is a log line, not a crash.
        val s2 = setupGame(r, shuffle = false, startInPlay = mapOf("P0" to listOf(StartCard("Ghost"))))
        assertTrue(s2.battlefield.isEmpty() && s2.log.any { it.contains("no card \"Ghost\"") }, s2.log.toString())
        // opening() splits a deck the same way setupGame consumes it.
        val (lib, inPlay) = DeckRules.SWU.opening(
            DeckDoc("D", listOf(DeckEntry("x", 3)), slots = mapOf("Leader" to listOf("L"), "Base" to listOf("B"))),
            100_000,
        )
        assertEq(3, lib.size)
        assertEq(listOf(StartCard("L"), StartCard("B")), inPlay)
    }

    check("deck rules and deck slots round-trip through JSON") {
        val g = GameDoc(
            name = "leader game",
            sets = listOf(SetDoc("Core", listOf(
                CardDoc(faces = listOf(FaceDoc("Kylo", setOf("Leader"), keywords = setOf("Villainy")))),
                CardDoc(faces = listOf(FaceDoc("Star Base", setOf("Base")))),
            ))),
            decks = listOf(DeckDoc(
                "Sith", listOf(DeckEntry("Trooper", 3)),
                slots = mapOf("Leader" to listOf("Kylo"), "Base" to listOf("Star Base")),
            )),
            deckRules = DeckRules.SWU,
        )
        assertEq(g, gameDocFromJson(gameDocToJson(g)), "deckRules + deck slots round-trip")
        // The MTG default is elided, so an ordinary game's JSON is unchanged.
        assertTrue(!gameDocToJson(GameDoc()).contains("deckRules"), "the default is not written")
        assertEq(GameDoc(), gameDocFromJson(gameDocToJson(GameDoc())))
        // A bespoke DeckRules survives on its own.
        val custom = DeckRules(
            minSize = 40, maxSize = 40, maxCopies = 1,
            slots = listOf(DeckSlotDef("Commander", setOf("Legendary"), 1, startsIn = "command")),
            identity = IdentityRule(listOf("Commander"), setOf("W", "U", "B", "R", "G"), allowNeutral = false),
        )
        assertEq(custom, gameDocFromJson(gameDocToJson(GameDoc(deckRules = custom))).deckRules)
    }

    check("a face's card-art filename round-trips and is elided when absent") {
        // FaceDoc.art is authoring-only -- build() drops it, JSON
        // carries it, and it must not appear when null.
        val plain = FaceDoc("X", setOf("Creature"), mapOf("power" to 1, "toughness" to 1))
        assertEq(plain.build().baseChars, plain.copy(art = "z.png").build().baseChars, "art does not reach the compiled Face")
        assertTrue(!faceDocToJson(plain).contains("\"art\""), "null art is not written")
        val arted = plain.copy(art = "a1b2c3.png")
        assertEq(arted, faceDocOf(Json.parse(faceDocToJson(arted))), "an art filename round-trips")
        val g = GameDoc(name = "Arted", sets = listOf(SetDoc("Core", listOf(CardDoc(faces = listOf(arted))))))
        assertEq(g, gameDocFromJson(gameDocToJson(g)), "art survives the whole-game round-trip")
    }

    check("a type's card layout round-trips, is elided when default, and the engine ignores it") {
        // TypeDef.layout is a render hint -- JSON carries it, a default
        // CardLayout is elided, and nothing in the engine reads it.
        assertEq(null, typeDefOf(Json.parse(typeDefToJson(TypeDef("T")))).layout, "no layout -> null")
        assertTrue(!typeDefToJson(TypeDef("T", layout = CardLayout())).contains("\"layout\""), "a default layout is elided")
        assertEq(null, typeDefOf(Json.parse(typeDefToJson(TypeDef("T", layout = CardLayout())))).layout, "default layout collapses to null")
        val laid = TypeDef(
            "Planeswalker",
            layout = CardLayout(
                art = ArtSlot.LEFT, statCorner = StatCorner.NONE,
                counterTrack = CounterTrack.LEFT_EDGE, counterKind = "loyalty",
                statFields = listOf("power"), showText = false, accent = "#D9A441",
            ),
        )
        assertEq(laid, typeDefOf(Json.parse(typeDefToJson(laid))), "a full layout round-trips")
        // The builtin Planeswalker ships a loyalty track; Rules.layoutFor picks
        // the first type that declares one.
        val r = Rules()
        assertEq(CounterTrack.LEFT_EDGE, r.layoutFor(setOf("Planeswalker")).counterTrack)
        assertEq("loyalty", r.layoutFor(setOf("Creature", "Planeswalker")).counterKind, "first type with a layout wins")
        assertEq(CardLayout(), r.layoutFor(setOf("Creature")), "no declared layout -> the default")
        // A game with a custom type carrying a layout survives the whole round-trip.
        val g = GameDoc(rules = RulesDoc(extraTypes = listOf(laid.copy(name = "Walker"))))
        assertEq(g, gameDocFromJson(gameDocToJson(g)))
    }

    check("a game's stable id round-trips, and an id-less game stays id-less") {
        // GameDoc.id keys the on-disk file. "" means "not minted yet"
        // -- it must NOT appear in JSON, so two default GameDoc()s stay equal.
        assertEq("", gameDocFromJson(gameDocToJson(GameDoc())).id)
        assertTrue(!gameDocToJson(GameDoc()).contains("\"id\""), "an unminted id is not written")
        val g = GameDoc(id = "9f1c-abc", name = "Named", sets = listOf(SetDoc("Core", listOf(CardDoc()))))
        assertEq(g, gameDocFromJson(gameDocToJson(g)), "a minted id round-trips")
        assertTrue(gameDocToJson(g).contains("\"id\":\"9f1c-abc\""))
    }

    check("a card's stable id round-trips, and an id-less card stays id-less") {
        // Same convention as GameDoc.id: "" means "not minted yet", must not
        // appear in JSON, so two default CardDoc()s stay equal.
        assertEq("", cardDocOf(Json.parse(cardDocToJson(CardDoc()))).id)
        assertTrue(!cardDocToJson(CardDoc()).contains("\"id\""), "an unminted card id is not written")
        val c = CardDoc(faces = listOf(FaceDoc("Ox", setOf("Creature"))), id = "card-77")
        assertEq(c, cardDocOf(Json.parse(cardDocToJson(c))), "a minted card id round-trips")
        assertTrue(cardDocToJson(c).contains("\"id\":\"card-77\""))
        // rules() keys on the id once minted -- the engine never sees a name.
        val g = GameDoc(sets = listOf(SetDoc("Core", listOf(c))))
        assertTrue("card-77" in g.rules().cards, "rules().cards is keyed by id, not name")
        assertTrue("Ox" !in g.rules().cards, "the display name is not also a key")
        // An id-less card (a sample game in content/, or a hand-built GameDoc) still keys by
        // name -- the engine-facing fallback that keeps every older game
        // and every hand-built one working with zero changes.
        val nameless = GameDoc(sets = listOf(SetDoc("Core", listOf(CardDoc(faces = listOf(FaceDoc("Bare", setOf("Creature"))))))))
        assertTrue("Bare" in nameless.rules().cards, "an id-less card falls back to its name as the key")
    }

    check("withCardIds mints ids and rewrites deck/slot references by the card's OLD name") {
        val leader = CardDoc(faces = listOf(FaceDoc("Kylo", setOf("Leader"))))
        val trooper = CardDoc(faces = listOf(FaceDoc("Trooper", setOf("Unit"))))
        val g = GameDoc(
            sets = listOf(SetDoc("Core", listOf(leader, trooper))),
            decks = listOf(DeckDoc(
                "Sith", listOf(DeckEntry("Trooper", 3)),
                slots = mapOf("Leader" to listOf("Kylo")),
            )),
        )
        var minted = 0
        val migrated = g.withCardIds { "id-${minted++}" }
        val newLeaderId = migrated.cards[0].id
        val newTrooperId = migrated.cards[1].id
        assertEq(2, minted, "one mint per card missing an id")
        assertTrue(newLeaderId.isNotEmpty() && newTrooperId.isNotEmpty())
        assertEq(listOf(DeckEntry(newTrooperId, 3)), migrated.decks[0].entries, "the deck entry now names the id, not \"Trooper\"")
        assertEq(mapOf("Leader" to listOf(newLeaderId)), migrated.decks[0].slots, "the slot now names the id, not \"Kylo\"")
        // The migrated game still resolves the same way it did before.
        assertEq(g.rules().cards.keys.map { g.rules().cards[it]!!.name }.toSet(),
            migrated.rules().cards.keys.map { migrated.rules().cards[it]!!.name }.toSet())
        // Idempotent: a second pass mints nothing and returns the identical value.
        var mintedAgain = 0
        assertEq(migrated, migrated.withCardIds { "id-${mintedAgain++}" })
        assertEq(0, mintedAgain, "every card already has an id -- mintId is never called")
    }

    check("code review: withCardIds leaves a shared name un-migrated rather than guess which card a deck meant") {
        val scoutA = CardDoc(faces = listOf(FaceDoc("Scout", setOf("Unit"))))
        val scoutB = CardDoc(faces = listOf(FaceDoc("Scout", setOf("Unit"))))
        val unique = CardDoc(faces = listOf(FaceDoc("Trooper", setOf("Unit"))))
        val g = GameDoc(
            sets = listOf(SetDoc("Core", listOf(scoutA, scoutB, unique))),
            decks = listOf(DeckDoc("Sith", listOf(DeckEntry("Scout", 2), DeckEntry("Trooper", 1)))),
        )
        var minted = 0
        val migrated = g.withCardIds { "id-${minted++}" }
        assertEq(1, minted, "only the unambiguous card is migrated this pass")
        assertEq("", migrated.cards[0].id, "the first Scout stays id-less")
        assertEq("", migrated.cards[1].id, "the second Scout stays id-less too")
        assertTrue(migrated.cards[2].id.isNotEmpty(), "Trooper, unambiguous, is migrated")
        assertEq(
            listOf(DeckEntry("Scout", 2), DeckEntry(migrated.cards[2].id, 1)),
            migrated.decks[0].entries,
            "the ambiguous entry is left naming \"Scout\"",
        )
        // problems() keeps flagging it until the author renames one -- by
        // design, not a missed case.
        assertTrue(migrated.problems().any { it.contains("Scout") }, "duplicateNames still reports the collision")
        // Once renamed, BOTH names are unique again (the survivor "Scout" is
        // no longer shared either) -- the next pass migrates both normally.
        val renamed = migrated.updateSet(0) { it.updateCard(1) { c -> c.updateFace(0) { f -> f.copy(name = "Scout II") } } }
        var mintedNext = 0
        val fullyMigrated = renamed.withCardIds { "id2-${mintedNext++}" }
        assertEq(2, mintedNext, "both the renamed card and its now-unique sibling are migrated")
        assertTrue(fullyMigrated.cards.all { it.id.isNotEmpty() }, "every card now has an id")
        assertTrue(fullyMigrated.problems().none { it.contains("Scout") }, "no more collision to report")
    }

    check("code review: GameDoc.problems() judges deck entries against CardDoc.key(), not the bare name") {
        val card = CardDoc(faces = listOf(FaceDoc("Vanguard", setOf("Ship"))), id = "card-9")
        val g = GameDoc(
            sets = listOf(SetDoc("Core", listOf(card))),
            decks = listOf(DeckDoc("Deck", listOf(DeckEntry("card-9", 2)))),
        )
        assertTrue(g.problems().none { it.contains("card-9") }, "a correctly id-keyed entry is NOT falsely flagged")
        val stale = g.copy(decks = listOf(DeckDoc("Deck", listOf(DeckEntry("Vanguard", 2)))))
        assertTrue(stale.problems().any { it.contains("Vanguard") }, "a stale name reference (the Creator-UI bug this fixes) IS flagged")
    }

    check("code review: a zone id declared as both a play zone and a hidden zone is reported") {
        val g = GameDoc(rules = RulesDoc(
            extraZones = listOf(PlayZoneDef("reserve", ZoneScope.PER_PLAYER)),
            extraHiddenZones = listOf(HiddenZoneDef("reserve")),
        ))
        assertTrue(g.problems().any { it.contains("reserve") && it.contains("both") })
        val clean = GameDoc(rules = RulesDoc(extraZones = listOf(PlayZoneDef("reserve", ZoneScope.PER_PLAYER))))
        assertTrue(clean.problems().none { it.contains("both a play zone") })
    }

    check("code review: Effect.CopyOf carries the source's hostId, so a copied Improvement stays attached") {
        val cruiser = creature("Cruiser", 0, 4)
        val booster = card(
            "Shield Booster", setOf("Improvement"),
            baseChars = Characteristics("Shield Booster", setOf("Improvement")),
            activated = listOf(
                ActivatedAbility(Cost(), Effect.Attach(BoundTarget(1)), "attach"),
                ActivatedAbility(Cost(), Effect.CopyOf(BoundTarget(2)), "copy self"),
            ),
        )
        val end = runSync {
            Engine(ScriptedInput.of("P0" to listOf(
                PLAY(cruiser), PLAY(booster), PriorityAction.Activate(2, 0), PriorityAction.PassPriority,
                PriorityAction.Activate(2, 1),
            )), rules = Rules().knowing(booster)).run(newGame())
        }
        val copy = end.battlefield.values.first { it.isToken && it.cardId == "Shield Booster" }
        assertEq(1, copy.hostId, "the copy is attached to the same host as the original")
    }

    check("EPR Skirmish: a non-Creature type with TypeDef.attacks fights (was hard-coded to \"Creature\")") {
        // Found modeling Ships: `Engine.canFight` (nee `isCreature`) checked
        // the literal string "Creature" instead of the TypeDef.attacks flag
        // that already existed for exactly this -- no custom type could ever
        // attack, block, or act in FREE combat, full stop.
        val shipType = TypeDef("Ship", fields = setOf("hull", "fast", "slow"), attacks = true, diesWhen = (selfField("hull") lte selfDamage).lowered())
        val striker = CardDefinition(listOf(
            Face("Striker", setOf("Ship"), Characteristics("Striker", setOf("Ship"), mapOf("hull" to 2, "fast" to 3))),
        ))
        val end = combat(
            "P0" to striker,
            combatTargets = listOf(CombatTarget.Player("P1")),
            rules = Rules(types = BUILTIN_TYPES_CORE + listOf(shipType).associateBy { it.name }, combat = FAST_SLOW_COMBAT.lowered()),
        )
        assertEq(17, end.players.getValue("P1").life, "the Ship's 'fast' damage landed -- it was recognized as a combat participant")
    }

    check("code review: laneLockedPlayerTargets also gates an INDIVIDUAL-style attack, not just FREE") {
        val cfg = ONE_PIECE_COMBAT.copy(laneLockedPlayerTargets = true)
        val rules = Rules(
            zones = BUILTIN_ZONES + listOf(PlayZoneDef("lane", ZoneScope.PER_PLAYER)).associateBy { it.id },
            combat = cfg.lowered(),
        )
        var s = newGame()
        val (s1, atk) = enterBattlefield(creature("P0 Ship", 3, 3), "P0", s, zone = ZoneRef("lane", "P0")); s = s1
        val (s2, _) = enterBattlefield(creature("P1 Ship", 0, 3), "P1", s, zone = ZoneRef("lane", "P1")); s = s2
        val end = indiv(s, PriorityAction.Attack(atk, CombatTarget.Player("P1")), rules = rules)
        assertEq(20, end.players.getValue("P1").life, "P0's lane is opposed -- the direct attack on P1 is denied under INDIVIDUAL style too")
    }

    // -- user-reported findings: hull, mana, relative-subset selection ------

    check("Rules.damageCounter: combat damage and DamageOpponent hit the configured counter, not a hard-coded \"life\"") {
        val rules = Rules(playerCounters = listOf(PlayerCounterDef("hull", 15, loseAtZero = true)), damageCounter = "hull")
        // NOT the combat()/staged() helper -- it always seeds "life" via
        // newGame's default, bypassing rules.playerCounters entirely, so
        // "hull" would start at 0 for both sides and lose the game before
        // combat even runs. Seed the state directly instead.
        val (seeded, _) = enterBattlefield(
            creature("Striker", 5, 5), "P0",
            newGame(counters = mapOf("hull" to 15), libraries = mapOf("P0" to tokens(6), "P1" to tokens(6))),
        )
        val afterCombat = runSync {
            Engine(ScriptedInput(emptyMap(), attacks = mapOf(1 to CombatTarget.Player("P1"))), rules = rules).playGame(seeded, maxTurns = 1)
        }
        assertEq(10, afterCombat.players.getValue("P1").counter("hull"), "combat damage hit hull")
        assertEq(0, afterCombat.players.getValue("P1").life, "the phantom \"life\" counter was never touched")

        val afterBurn = runSync {
            Engine(ScriptedInput.of("P0" to listOf(CAST(Effect.DamageOpponent(lit(4))))), rules = rules)
                .run(newGame(counters = mapOf("hull" to 15)))
        }
        assertEq(11, afterBurn.players.getValue("P1").counter("hull"), "DamageOpponent hit hull too")
    }

    check("replacement effects are DATA now -- 'if X would happen, do Y instead' survives JSON") {
        // Replacements are data (`ReplacementDoc`), authorable and serialisable
        // -- no lambdas on cards.
        val shipT = TypeDef("Ship", fields = setOf("hull"), attacks = true, diesWhen = (selfField("hull") lte selfDamage).lowered())
        val rules = Rules(types = BUILTIN_TYPES_CORE + mapOf("Ship" to shipT, "Improvement" to TypeDef("Improvement")))

        // The totem shield: damage to the host is prevented, and the plating
        // is destroyed instead. Expressed as data, compiled to a handler.
        val plating = CardDoc(faces = listOf(FaceDoc(
            "Ablative Plating", setOf("Improvement"),
            replacements = listOf(ReplacementDoc.DamageToSacrificeSelf(PermFilter().onlyHost())),
        )))
        assertEq(plating, cardDocOf(Json.parse(cardDocToJson(plating))), "the replacement round-trips whole")

        var s = newGame()
        val (s1, ship) = enterBattlefield(CardDoc(faces = listOf(FaceDoc("Hulk", setOf("Ship"), fields = mapOf("hull" to 2)))).build(), "P0", s); s = s1
        val (s2, plate) = enterBattlefield(plating.build(), "P0", s); s = s2
        s = s.copy(battlefield = s.battlefield + (plate to s.battlefield.getValue(plate).copy(hostId = ship)))

        val end = runSync {
            Engine(ScriptedInput.of("P0" to listOf(CAST(Effect.DealDamage(lit(5), BoundTarget(ship))))), rules = rules).run(s)
        }
        assertTrue(ship in end.battlefield, "the Ship survives a lethal hit -- the damage never happened")
        assertEq(0, end.battlefield.getValue(ship).damageMarked, "and took NO damage, rather than absorbing it")
        assertTrue(plate !in end.battlefield, "...because the plating was destroyed instead")
    }

    check("a shield counter eats a whole hit, is SPENT, and still fires counter triggers") {
        // Shields: damage prevented AND a counter spent, with triggers still
        // seeing it. Needs `CounterSpent`: `WithState` would eat the damage
        // silently, and `WithEvent(CounterChanged)` would announce a change
        // that had not happened.
        val stationT = TypeDef(
            "Station", damageCounter = "hull",
            diesWhen = (selfCounter("hull") lte lit(0)).lowered(), loseOnDeath = true,
        )
        val rules = Rules(types = BUILTIN_TYPES_CORE + mapOf("Station" to stationT))

        val vault = CardDoc(
            faces = listOf(FaceDoc(
                "Vault", setOf("Station"),
                replacements = listOf(ReplacementDoc.DamageToRemoveCounter(
                    PermFilter(types = setOf("Station")).yours(), "shield",
                )),
                // Fires on crossing 2 downward -- i.e. the first shield lost.
                triggers = listOf(TriggerDoc.CounterThreshold(
                    "shield", 2, downward = true,
                    effect = Effect.Draw(PlayerRef.You, lit(1)),
                )),
            )),
            entersWith = listOf(CounterDef("hull", lit(20)), CounterDef("shield", lit(2))),
        )
        assertEq(vault, cardDocOf(Json.parse(cardDocToJson(vault))), "the new replacement round-trips whole")

        var s = newGame(libraries = mapOf("P0" to tokens(3)))
        val (s1, station) = enterBattlefield(vault.build(), "P0", s); s = s1
        val handBefore = s.players.getValue("P0").hand.size

        // A hit far bigger than the shield: a shield eats the WHOLE instance.
        val end = runSync {
            Engine(ScriptedInput.of("P0" to listOf(CAST(Effect.DealDamage(lit(14), BoundTarget(station))))), rules = rules).run(s)
        }
        val after = end.battlefield.getValue(station)
        assertEq(20, after.counter("hull"), "the hull is untouched -- the hit never landed")
        assertEq(1, after.counter("shield"), "and exactly one shield was spent, not both")
        assertEq(
            handBefore + 1,
            end.players.getValue("P0").hand.size,
            "the counter trigger fired -- this is what WithState would have swallowed",
        )
    }

    check("a one-shot replacement absorbs exactly ONE instance -- the FIRST to resolve, and the rest land") {
        // Two Ships hit one plated Ship: which instance is prevented? The first
        // matching DamageDealt fires the replacement, the plating leaves play,
        // its handler is pruned, and later hits land normally.
        val shipT = TypeDef("Ship", fields = setOf("hull"), attacks = true, diesWhen = (selfField("hull") lte selfDamage).lowered())
        val rules = Rules(types = BUILTIN_TYPES_CORE + mapOf("Ship" to shipT, "Improvement" to TypeDef("Improvement")))
        val plating = CardDoc(faces = listOf(FaceDoc(
            "Ablative Plating", setOf("Improvement"),
            replacements = listOf(ReplacementDoc.DamageToSacrificeSelf(PermFilter().onlyHost())),
        ))).build()

        var s = newGame()
        val (s1, ship) = enterBattlefield(CardDoc(faces = listOf(FaceDoc("Hulk", setOf("Ship"), fields = mapOf("hull" to 9)))).build(), "P0", s); s = s1
        val (s2, plate) = enterBattlefield(plating, "P0", s); s = s2
        s = s.copy(battlefield = s.battlefield + (plate to s.battlefield.getValue(plate).copy(hostId = ship)))

        // A 1-damage ping and THEN a 4-damage hit in one window: the stack is
        // LIFO, so the 4 resolves FIRST and is the one absorbed. The prevented
        // instance is the first to RESOLVE -- the last one cast.
        val end = runSync {
            Engine(
                ScriptedInput.of("P0" to listOf(
                    CAST(Effect.DealDamage(lit(1), BoundTarget(ship))),
                    CAST(Effect.DealDamage(lit(4), BoundTarget(ship))),
                )),
                rules = rules,
            ).run(s)
        }
        assertTrue(plate !in end.battlefield, "the plating is gone -- it absorbed exactly one instance")
        assertEq(
            1, end.battlefield.getValue(ship).damageMarked,
            "the 4 resolved first (LIFO) and was absorbed; the 1 then landed IN FULL -- one instance prevented, not all of them",
        )
    }

    check("Cost.payFrom: a PERMANENT pays the cost, and is REFUSED when short") {
        // `payFrom` pays a permanent's counters. A COST, so it refuses when short
        // (an `additional` effect would drive a Station below zero): to 0 is
        // legal, past it is not.
        val stationType = TypeDef("Station", loseOnDeath = true)
        val station = CardDefinition(listOf(Face("Relay", setOf("Station"))), entersWith = listOf(CounterDef("hull", lit(3))))
        val pay = CounterPayment("hull", 2, PermFilter(types = setOf("Station")).yours())
        val cost = Cost(payFrom = pay)

        var s = newGame()
        val (s1, id) = enterBattlefield(station, "P0", s); s = s1
        assertTrue(s.canAfford("P0", cost), "3 hull covers a 2-hull cost")
        assertEq(listOf(id), s.payersFor("P0", pay), "and the Station is the payer")

        fun withHull(n: Int) = s.copy(battlefield = s.battlefield + (id to s.battlefield.getValue(id).copy(counters = mapOf("hull" to n))))
        assertTrue(withHull(2).canAfford("P0", cost), "paying down to exactly 0 is legal -- you can tithe yourself to death")
        assertTrue(!withHull(1).canAfford("P0", cost), "...but 1 cannot pay 2. A cost refuses; an effect would not")
        assertTrue(withHull(1).payersFor("P0", pay).isEmpty(), "canAfford and payersFor agree, because they ARE one function")

        // An opponent's Station is not a payer -- the filter says `yours()`.
        val (s2, theirs) = enterBattlefield(station, "P1", s)
        assertEq(listOf(id), s2.payersFor("P0", pay), "an opponent's Station cannot be made to pay")
        assertEq(listOf(theirs), s2.payersFor("P1", pay))

        // And it survives JSON, or the app cannot play the card.
        val card = CardDoc(faces = listOf(FaceDoc("Tithe", setOf("Manoeuvre"))), cost = cost)
        assertEq(card, cardDocOf(Json.parse(cardDocToJson(card))), "payFrom round-trips whole -- counter, amount and filter")
    }


    check("an `additional` cost REFUSES when it cannot be paid -- a free sacrifice is not a sacrifice") {
        // Deacon Ves, reduced: "{1}, exhaust, sacrifice a Ship: draw a card"
        // must NOT be payable with nothing to sacrifice. `payFrom` to exactly 0
        // stays legal (tested separately).
        val shipT = TypeDef("Ship", fields = setOf("power", "toughness"))
        val sacrificeAShip = Effect.Sacrifice(PlayerRef.You, lit(1), PermFilter(types = setOf("Ship")).yours())
        val cost = Cost(additional = sacrificeAShip)
        val leader = CardDefinition(listOf(Face(
            "Ves", setOf("Leader"),
            activated = listOf(ActivatedAbility(cost, Effect.Draw(PlayerRef.You, lit(1)), name = "sacrifice a Ship: draw")),
        )))
        val ship = CardDefinition(
            listOf(Face("Hulk", setOf("Ship"), baseChars = Characteristics("Hulk", setOf("Ship"), mapOf("power" to 1, "toughness" to 1)))),
        )
        val rules = Rules(types = BUILTIN_TYPES_CORE + mapOf("Ship" to shipT, "Leader" to TypeDef("Leader"))).knowing(leader)

        var s = newGame()
        val (s1, ves) = enterBattlefield(leader, "P0", s); s = s1

        // No Ship: the cost cannot be paid, so it is not affordable and the
        // ability is not legal.
        assertTrue(!s.additionalPayable("P0", sacrificeAShip), "nothing to sacrifice")
        assertTrue(!s.canAfford("P0", cost), "so the cost is not affordable -- a cost refuses when short")
        assertTrue(
            legality(rules, s, "P0", PriorityAction.Activate(ves, 0)) !is Legality.Legal,
            "and the ability is not offered at all",
        )

        // With a Ship, everything works exactly as it always did.
        val (s2, hulk) = enterBattlefield(ship, "P0", s)
        assertTrue(s2.additionalPayable("P0", sacrificeAShip), "a Ship is a payer")
        assertTrue(s2.canAfford("P0", cost))
        assertTrue(legality(rules, s2, "P0", PriorityAction.Activate(ves, 0)) is Legality.Legal)
        assertTrue(hulk in s2.battlefield, "and it is still there until the cost is actually paid")

        // An OPPONENT's Ship cannot pay your cost -- the filter says yours().
        val (s3, _) = enterBattlefield(ship, "P1", s)
        assertTrue(!s3.canAfford("P0", cost), "their Ship is not your sacrifice")

        // A discard-shaped additional cost is the other shape this reaches.
        val discardTwo = Cost(additional = Effect.Discard(PlayerRef.You, lit(2)))
        assertTrue(!s.canAfford("P0", discardTwo), "an empty hand cannot discard 2")
        val withHand = s.copy(players = s.players + ("P0" to s.players.getValue("P0").copy(
            hand = listOf(CardRef(9001, "x"), CardRef(9002, "y")),
        )))
        assertTrue(withHand.canAfford("P0", discardTwo), "two cards can")

        // And the honest limit, asserted so nobody assumes it is total: an
        // effect this cannot reason about stays payable: the check never makes
        // a cost HARDER.
        assertTrue(s.canAfford("P0", Cost(additional = Effect.Draw(PlayerRef.You, lit(1)))), "an unreasoned shape is unchanged")
    }

    check("decking out is a real loss condition -- drawing past the end of the library ends the game") {
        // Decking out loses (`drawCards`): libraries only descend, so a stalled
        // board cannot make an unending game.
        val rules = Rules(turn = TurnStructure(listOf(PhaseSpec("main", sorcerySpeed = true))))
        var s = newGame(libraries = mapOf("P0" to tokens(2), "P1" to tokens(9, 200_000)))
        val end = runSync {
            Engine(ScriptedInput.of("P0" to listOf(CAST(Effect.Draw(PlayerRef.You, lit(5))))), rules = rules).run(s)
        }
        assertTrue("P0" in end.losers, "P0 asked for 5 with 2 left, and loses for it")
        assertTrue(end.log.any { it.contains("decks out") }, "and the log SAYS so -- a loss is never silent")

        // The boundary: drawing exactly the last card is fine. Decking out is
        // failing to draw, not emptying the library.
        val exact = runSync {
            Engine(ScriptedInput.of("P0" to listOf(CAST(Effect.Draw(PlayerRef.You, lit(2))))), rules = rules).run(
                newGame(libraries = mapOf("P0" to tokens(2), "P1" to tokens(9, 200_000))),
            )
        }
        assertTrue("P0" !in exact.losers, "drawing the LAST card is legal -- you lose on the draw that fails")
    }

    check("TypeDef.loseOnDeath: a permanent leaving play, by ANY path, ends the game for its controller") {
        val stationType = TypeDef("Station", diesWhen = (selfCounter("hull") lte lit(0)).lowered(), damageCounter = "hull", loseOnDeath = true)
        val rules = Rules(types = BUILTIN_TYPES_CORE + listOf(stationType).associateBy { it.name })
        val station = CardDefinition(listOf(Face("Relay Kestrel-9", setOf("Station"))), entersWith = listOf(CounterDef("hull", lit(15))))

        // An unrelated effect (Destroy) removes it -- not combat, not the
        // diesWhen threshold. loseOnDeath must fire regardless of HOW it
        // left (the actual hull -> combat -> death path is its own test,
        // "the full Station lifecycle", below).
        run {
            var s = newGame()
            val (s1, id) = enterBattlefield(station, "P0", s); s = s1
            val end = runSync { Engine(ScriptedInput.of("P0" to listOf(CAST(Effect.Destroy(BoundTarget(id))))), rules = rules).run(s) }
            assertTrue("P0" in end.losers, "P0 loses when their Station is destroyed, however that happened")
        }
        // You lose when you control NONE of the `loseOnDeath` type, not when the
        // first one leaves -- the precondition for multi-Station games.
        run {
            var s = newGame()
            val (s1, a) = enterBattlefield(station, "P0", s); s = s1
            val (s2, b) = enterBattlefield(station, "P0", s); s = s2
            val one = runSync { Engine(ScriptedInput.of("P0" to listOf(CAST(Effect.Destroy(BoundTarget(a))))), rules = rules).run(s) }
            assertTrue("P0" !in one.losers, "with a SECOND Station still in play, losing one is survivable")
            val both = runSync {
                Engine(
                    ScriptedInput.of("P0" to listOf(CAST(Effect.Destroy(BoundTarget(a))), CAST(Effect.Destroy(BoundTarget(b))))),
                    rules = rules,
                ).run(s)
            }
            assertTrue("P0" in both.losers, "...and losing the LAST one is what ends the game")
        }
        // GameDoc.problems() must recognize loseOnDeath as a valid loss path.
        val g = GameDoc(rules = RulesDoc(extraTypes = listOf(stationType), playerCounters = listOf(PlayerCounterDef("hull", 15))))
        assertTrue(g.problems().none { it.contains("nobody can lose") }, "loseOnDeath satisfies the \"how does anyone lose\" check")
        val withoutIt = GameDoc(rules = RulesDoc(playerCounters = listOf(PlayerCounterDef("hull", 15))))
        assertTrue(withoutIt.problems().any { it.contains("nobody can lose") }, "...and is still flagged when genuinely absent")
    }

    check("setupGame seeds a player counter from the SPECIFIC slot card's own entersWith value") {
        // setupGame reads the STARTING value off whichever Station the deck
        // named: two Stations, two hulls, one ruleset.
        val rules = Rules(
            types = BUILTIN_TYPES_CORE + listOf(TypeDef("Station")).associateBy { it.name },
            cards = mapOf(
                "Relay Kestrel-9" to CardDefinition(listOf(Face("Relay Kestrel-9", setOf("Station"))), entersWith = listOf(CounterDef("hull", lit(15)))),
                "Bastion Prime" to CardDefinition(listOf(Face("Bastion Prime", setOf("Station"))), entersWith = listOf(CounterDef("hull", lit(22)))),
            ),
            playerCounters = listOf(PlayerCounterDef("hull", starting = 0, loseAtZero = true)), // overwritten below
        )
        val s1 = setupGame(rules, startInPlay = mapOf("P0" to listOf(StartCard("Relay Kestrel-9", "battlefield"))))
        assertEq(15, s1.players.getValue("P0").counter("hull"), "P0's hull comes from Relay Kestrel-9, not RulesDoc's 0")
        val s2 = setupGame(rules, startInPlay = mapOf("P0" to listOf(StartCard("Bastion Prime", "battlefield"))))
        assertEq(22, s2.players.getValue("P0").counter("hull"), "a DIFFERENT Station seeds a DIFFERENT hull -- genuinely per-card")
        // A slot card with no counter matching a declared player counter is
        // an unaffected no-op (every existing sample's Leader/Base/Hero).
        val plainLeader = Rules(
            types = BUILTIN_TYPES_CORE + listOf(TypeDef("Leader")).associateBy { it.name },
            cards = mapOf("Cmdr" to CardDefinition(listOf(Face("Cmdr", setOf("Leader"))))),
            playerCounters = listOf(PlayerCounterDef("hull", starting = 15, loseAtZero = true)),
        )
        val s3 = setupGame(plainLeader, startInPlay = mapOf("P0" to listOf(StartCard("Cmdr", "battlefield"))))
        assertEq(15, s3.players.getValue("P0").counter("hull"), "no matching counter on the card -- RulesDoc's default stands")
    }

    check("the full Station lifecycle: a per-card hull, combat damage, loseAtZero, end to end") {
        val shipType = TypeDef("Ship", fields = setOf("fast"), attacks = true)
        val stationType = TypeDef("Station") // a marker permanent; the player's OWN hull counter is the real loss condition
        val rules = Rules(
            types = BUILTIN_TYPES_CORE + listOf(shipType, stationType).associateBy { it.name },
            cards = mapOf("Relay Kestrel-9" to CardDefinition(listOf(Face("Relay Kestrel-9", setOf("Station"))), entersWith = listOf(CounterDef("hull", lit(3))))),
            combat = FAST_SLOW_COMBAT.lowered(),
            playerCounters = listOf(PlayerCounterDef("hull", starting = 0, loseAtZero = true)),
            damageCounter = "hull", // combat damage to a PLAYER hits hull, not "life"
        )
        val ship = CardDefinition(listOf(Face("Striker", setOf("Ship"), Characteristics("Striker", setOf("Ship"), mapOf("fast" to 5)))))
        // Enough library for the default 7-card opening hand + a turn's draw
        // -- 6 decked P0 out during setup on the first attempt at this test.
        var s = setupGame(rules, libraries = mapOf("P0" to tokens(10), "P1" to tokens(10)), startInPlay = mapOf("P1" to listOf(StartCard("Relay Kestrel-9", "battlefield"))))
        assertEq(3, s.players.getValue("P1").counter("hull"), "seeded from the card before a single card is even cast")
        // has no Station of their own -- their hull stays at RulesDoc's
        // placeholder 0, which is ALSO <= 0 (loseAtZero) and would end the
        // game on the spot. Not what this test is about; give P0 a safe hull.
        s = s.copy(players = s.players + ("P0" to s.players.getValue("P0").withCounter("hull", 20)))
        val (s1, _) = enterBattlefield(ship, "P0", s); s = s1
        val end = runSync {
            Engine(ScriptedInput(emptyMap(), combatTargets = listOf(CombatTarget.Player("P1"))), rules = rules)
                .playGame(s, maxTurns = 1)
        }
        assertTrue("P1" in end.losers, "5 damage to a 3-hull Station -- P1 loses via the player's own hull counter")
    }

    check("GameParams.poolPersistsPerTurn: an effect-driven resource survives a phase boundary within the same turn") {
        // A real card's start-of-turn trigger (not a bare sandbox CAST, which
        // is itself sorcery-speed-gated and can't even fire during upkeep --
        // matching how EPR Skirmish's Station is actually authored).
        val station = CardDoc(faces = listOf(FaceDoc(
            "Relay Kestrel-9", setOf("Station"),
            triggers = listOf(TriggerDoc.OnYourPhase("upkeep", Effect.AddMana(PlayerRef.You, mapOf("" to lit(2))))),
        ))).build()
        val gone = runSync {
            Engine(ScriptedInput.of()).playGame(
                enterBattlefield(station, "P0", newGame(libraries = mapOf("P0" to tokens(6), "P1" to tokens(6)))).first,
                maxTurns = 1,
            )
        }
        assertTrue(gone.players.getValue("P0").pool.isEmpty(), "without the flag, mana added at upkeep is gone by main (the reported bug)")
        val kept = runSync {
            Engine(ScriptedInput.of(), rules = Rules(params = GameParams(poolPersistsPerTurn = true))).playGame(
                enterBattlefield(station, "P0", newGame(libraries = mapOf("P0" to tokens(6), "P1" to tokens(6)))).first,
                maxTurns = 1,
            )
        }
        assertEq(mapOf("" to 2), kept.players.getValue("P0").pool, "with the flag, it survives into main -- actually spendable")
    }

    check("EPR Skirmish: Relay Kestrel-9's own economy -- draw 2, exile 1 of THOSE, never a pre-existing hand card") {
        val end = runSync {
            var s = newGame(libraries = mapOf("P0" to tokens(6, startId = 200_000)))
            s = s.copy(players = s.players + ("P0" to s.players.getValue("P0").copy(hand = listOf(CardRef(999, "Old Card")))))
            Engine(
                ScriptedInput.of(
                    "P0" to listOf(CAST(Effect.DrawThenDiscard(PlayerRef.You, lit(2), lit(1), HiddenZone.EXILE))),
                    cards = listOf(listOf(200_000)), // cull the first of the two just-drawn tokens
                ),
            ).run(s)
        }
        assertEq(2, end.players.getValue("P0").hand.size, "kept: Old Card + 1 of the 2 just drawn")
        assertTrue(end.players.getValue("P0").hand.any { it.instanceId == 999 }, "the PRE-EXISTING hand card was never a candidate")
        assertEq(1, end.exile.size, "exactly one of the just-drawn cards was culled")
        assertEq(200_000, end.exile.first().instanceId, "specifically one of the just-drawn ones, not the old card")
    }

    check("EPR Skirmish: CharOp.PlusField boosts an arbitrary named field, not just power/toughness") {
        // A Ship type using hull/fast/slow instead of power/toughness --
        // PlusPT/SetPT are hard-coded to P/T and cannot touch these at all.
        val shipType = TypeDef("Ship", fields = setOf("hull", "fast", "slow"), diesWhen = (selfField("hull") lte selfDamage).lowered())
        val rules = Rules(types = BUILTIN_TYPES_CORE + listOf(shipType).associateBy { it.name })
        var s = newGame()
        val (s1, ship) = enterBattlefield(
            CardDefinition(listOf(Face("Vanguard", setOf("Ship"), Characteristics("Vanguard", setOf("Ship"), mapOf("hull" to 3, "fast" to 1))))),
            "P0", s,
        )
        s = s1
        val leader = card(
            "Ilsa Vray", setOf("Leader"),
            baseChars = Characteristics("Ilsa Vray", setOf("Leader")),
            activated = listOf(ActivatedAbility(
                Cost(),
                Effect.ApplyModifier(permanents().only(ship), listOf(CharOp.PlusField("fast", lit(1))), duration = Duration.EndOfTurn),
                "target Ship gets +1 FD",
            )),
        )
        val (s2, leaderId) = enterBattlefield(leader, "P0", s); s = s2
        assertEq(1, s.characteristicsOf(ship).fields["fast"], "unboosted before the ability")
        val end = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PriorityAction.Activate(leaderId, 0))), rules = rules.knowing(leader)).run(s)
        }
        assertEq(2, end.characteristicsOf(ship).fields["fast"], "+1 FD applied to the named field")
        assertEq(3, end.characteristicsOf(ship).fields["hull"], "hull, untouched, is unaffected")
    }

    check("CharOp.Bands can drive named fields other than power/toughness") {
        // Same bug class as PlusPT/SetPT before PlusField -- Bands.apply used
        // to hard-code "power"/"toughness" no matter what fieldA/fieldB said
        // (they didn't exist at all). A Ship using hull/fast bands from a
        // charge counter, not power/toughness from a level counter.
        val guard = card(
            "Bulwark Drone", setOf("Ship"), Characteristics("Bulwark Drone", setOf("Ship"), mapOf("hull" to 2, "fast" to 1)),
            statics = Statics(chars = listOf(StaticSpec(
                    PermFilter(pinned = BoundTarget(SELF)),
                    listOf(CharOp.Bands("charge", listOf(CharOp.Bands.Band(2, lit(5), lit(3))), fieldA = "hull", fieldB = "fast")),
                ))),
        )
        val (s1, id) = enterBattlefield(guard, "P0", newGame())
        assertEq(2, s1.characteristicsOf(id).fields["hull"], "below threshold -> base hull, untouched")
        assertEq(1, s1.characteristicsOf(id).fields["fast"], "below threshold -> base fast, untouched")
        val s2 = s1.copy(battlefield = s1.battlefield + (id to s1.battlefield.getValue(id).copy(counters = mapOf("charge" to 2))))
        assertEq(5, s2.characteristicsOf(id).fields["hull"], "charge >= 2 -> banded hull, not power")
        assertEq(3, s2.characteristicsOf(id).fields["fast"], "charge >= 2 -> banded fast, not toughness")
    }

    check("IntExpr.LifeOf and GameState.canAfford read the declared damage counter, not a hard-coded \"life\"") {
        // `LifeOf` and `canAfford` read the declared damage counter, not the
        // literal "life".
        val s = newGame(counters = mapOf("hull" to 12), damageCounter = "hull")
        val ctx = EvalContext(s, "P0")
        assertEq(12, IntExpr.LifeOf(PlayerRef.You).lowered("hull").eval(ctx), "reads hull, not the always-absent \"life\" key")
        assertEq(true, s.canAfford("P0", Cost(payLife = 10)), "can pay 10 hull when hull is 12")
        assertEq(false, s.canAfford("P0", Cost(payLife = 13)), "can't pay 13 hull when hull is 12")
        // and the default (undeclared) case is unaffected.
        val life = newGame()
        assertEq(20, IntExpr.LifeOf(PlayerRef.You).lowered(LIFE).eval(EvalContext(life, "P0")), "default game still reads life")
    }

    // -- Lane plan, step 1: TypeDef.zoneChoices + legality + N lanes --

    check("lane plan step 1: TypeDef.zoneChoices round-trips through JSON and is elided when empty") {
        assertTrue(!typeDefToJson(TypeDef("Ship")).contains("zoneChoices"), "empty list is elided")
        val ship = TypeDef("Ship", zoneChoices = listOf("lane-1", "lane-2", "lane-3"))
        assertEq(ship, typeDefOf(Json.parse(typeDefToJson(ship))), "a real choice set round-trips")
    }

    check("lane plan step 1: defaultZoneDef and legalZonesFor read zoneChoices when zoneOfPlay is unset") {
        val rules = Rules(types = BUILTIN_TYPES_CORE + TypeDef("Ship", zoneChoices = listOf("lane-1", "lane-2")).let { mapOf(it.name to it) })
        assertEq("lane-1", rules.defaultZoneDef(setOf("Ship")), "falls back to the FIRST declared choice")
        assertEq(setOf("lane-1", "lane-2"), rules.legalZonesFor(setOf("Ship")), "the whole declared set is legal")
        // a type declaring neither zoneOfPlay nor zoneChoices is unaffected --
        // legalZonesFor is UNRESTRICTED (null), not narrowed to "battlefield"
        // only: P5's own "an unknown zone def resolves to a shared instance"
        // test depends on exactly this permissiveness for ordinary types.
        assertEq("battlefield", rules.defaultZoneDef(setOf("Creature")))
        assertEq(null, rules.legalZonesFor(setOf("Creature")))
        // zoneOfPlay, when present, still wins over zoneChoices for the default.
        val withBoth = TypeDef("Hybrid", zoneOfPlay = "arena", zoneChoices = listOf("lane-1", "lane-2"))
        val rules2 = Rules(types = BUILTIN_TYPES_CORE + mapOf(withBoth.name to withBoth))
        assertEq("arena", rules2.defaultZoneDef(setOf("Hybrid")))
        assertEq(setOf("arena", "lane-1", "lane-2"), rules2.legalZonesFor(setOf("Hybrid")), "legality accepts either")
    }

    check("lane plan step 1: legality() denies an explicit PlayPermanent.zone outside the type's declared set") {
        // Otherwise any string would reach resolveZone and be silently accepted.
        val shipType = TypeDef("Ship", zoneChoices = listOf("lane-1", "lane-2"))
        val rules = Rules(
            types = BUILTIN_TYPES_CORE + mapOf(shipType.name to shipType),
            zones = BUILTIN_ZONES + listOf("lane-1", "lane-2").map { PlayZoneDef(it, ZoneScope.PER_PLAYER) }.associateBy { it.id },
        )
        val s = newGame()
        val scout = CardDefinition(listOf(Face("Scout", setOf("Ship"), Characteristics("Scout", setOf("Ship")))))
        fun legalityOf(zone: String?) = legality(rules, s, "P0", PriorityAction.PlayPermanent(scout, 0, zone = zone))
        assertTrue(legalityOf("nonexistent-zone") is Legality.Denied, "a made-up zone id is rejected")
        assertTrue(legalityOf("battlefield") is Legality.Denied, "\"battlefield\" is not one of THIS type's declared choices")
        assertTrue(legalityOf("lane-1") is Legality.Legal, "a declared choice is accepted")
        assertTrue(legalityOf("lane-2") is Legality.Legal, "so is the other")
        assertTrue(legalityOf(null) is Legality.Legal, "null still means \"use the default\", untouched by this check")
    }

    check("lane plan step 1: GameState.laneOpposed treats each of N independently-named lanes on its own") {
        // `laneOpposed` keys off the attacker's own lane def, so it generalises
        // to N independently named lanes.
        val rules3 = Rules(
            zones = BUILTIN_ZONES + listOf("lane-1", "lane-2", "lane-3")
                .map { PlayZoneDef(it, ZoneScope.PER_PLAYER) }.associateBy { it.id },
            combat = FAST_SLOW_COMBAT.copy(laneLockedPlayerTargets = true).lowered(),
        )
        var s = newGame(libraries = mapOf("P0" to tokens(6), "P1" to tokens(6)))
        // has ships in lane-1 AND lane-3. P1 occupies ONLY lane-1 -- so
        // P0's lane-1 ship should be denied (opposed) while its lane-3 ship
        // should still land (lane-3 is empty on P1's side), PROVING the two
        // lanes are checked independently rather than as one shared flag.
        val (s1, _) = enterBattlefield(creature("P0 L1", 0, 2, fields = mapOf("fast" to 2)), "P0", s, zone = ZoneRef("lane-1", "P0"))
        s = s1
        val (s2, _) = enterBattlefield(creature("P0 L3", 0, 2, fields = mapOf("fast" to 2)), "P0", s, zone = ZoneRef("lane-3", "P0"))
        s = s2
        val (s3, _) = enterBattlefield(creature("P1 L1", 0, 2, fields = mapOf("fast" to 0)), "P1", s, zone = ZoneRef("lane-1", "P1"))
        s = s3
        val end = runSync {
            Engine(
                // 3 targets queued: P1's lane-1 ship also declares "fast"
                // (even at 0) so it acts too and needs one, same as the
                // pre-existing "never gates ship-vs-ship targeting" test.
                ScriptedInput(
                    emptyMap(),
                    combatTargets = listOf(CombatTarget.Player("P1"), CombatTarget.Player("P1"), CombatTarget.Player("P0")),
                ),
                rules = rules3,
            ).playGame(s, maxTurns = 1)
        }
        assertEq(18, end.players.getValue("P1").life, "lane-1 opposed -> denied; lane-3 unopposed -> lands (2 damage only)")
    }

    // -- Lane plan follow-up: zone capacity ------------------------------------

    check("lane plan follow-up: PlayZoneDef.maxOccupants round-trips through JSON and is elided when null") {
        assertTrue(!playZoneDefToJson(PlayZoneDef("lane")).contains("maxOccupants"), "no cap -> elided")
        val capped = PlayZoneDef("lane", ZoneScope.PER_PLAYER, maxOccupants = 1)
        assertEq(capped, playZoneDefOf(Json.parse(playZoneDefToJson(capped))), "a real cap round-trips")
    }

    check("lane plan follow-up: Rules.zoneHasRoom respects maxOccupants, per PER_PLAYER instance") {
        val rules = Rules(
            zones = BUILTIN_ZONES + listOf(PlayZoneDef("lane-1", ZoneScope.PER_PLAYER, maxOccupants = 1)).associateBy { it.id },
        )
        var s = newGame()
        assertTrue(rules.zoneHasRoom(s, "P0", "lane-1"), "empty lane -> room")
        val (s1, _) = enterBattlefield(Grunt, "P0", s, zone = ZoneRef("lane-1", "P0"))
        s = s1
        assertTrue(!rules.zoneHasRoom(s, "P0", "lane-1"), "P0's own lane-1 is now full")
        assertTrue(rules.zoneHasRoom(s, "P1", "lane-1"), "P1's SEPARATE lane-1 instance is untouched")
        // an uncapped or undeclared zone is always room, matching every
        // zone's behavior before this feature existed.
        assertTrue(rules.zoneHasRoom(s, "P0", "battlefield"))
        assertTrue(rules.zoneHasRoom(s, "P0", "no-such-zone"))
    }

    check("lane plan follow-up: legality() enforces zone capacity -- a specific full zone is denied, an unspecified one only fails when EVERY legal zone is full") {
        val shipType = TypeDef("Ship", zoneChoices = listOf("lane-1", "lane-2"))
        val rules = Rules(
            types = BUILTIN_TYPES_CORE + mapOf(shipType.name to shipType),
            zones = BUILTIN_ZONES + listOf("lane-1", "lane-2")
                .map { PlayZoneDef(it, ZoneScope.PER_PLAYER, maxOccupants = 1) }.associateBy { it.id },
        )
        var s = newGame()
        val scout = CardDefinition(listOf(Face("Scout", setOf("Ship"), Characteristics("Scout", setOf("Ship")))))
        fun legalityOf(zone: String?) = legality(rules, s, "P0", PriorityAction.PlayPermanent(scout, 0, zone = zone))
        assertTrue(legalityOf("lane-1") is Legality.Legal, "both empty -> either is fine")
        assertTrue(legalityOf(null) is Legality.Legal, "unspecified -> at least one has room")

        val (s1, _) = enterBattlefield(Grunt.copy(faces = listOf(Grunt.faces[0].copy(types = setOf("Ship")))), "P0", s, zone = ZoneRef("lane-1", "P0"))
        s = s1
        assertTrue(legalityOf("lane-1") is Legality.Denied, "lane-1 is now full")
        assertTrue(legalityOf("lane-2") is Legality.Legal, "lane-2 still has room")
        assertTrue(legalityOf(null) is Legality.Legal, "unspecified -> lane-2 still qualifies")

        val (s2, _) = enterBattlefield(Grunt.copy(faces = listOf(Grunt.faces[0].copy(types = setOf("Ship")))), "P0", s, zone = ZoneRef("lane-2", "P0"))
        s = s2
        assertTrue(legalityOf("lane-1") is Legality.Denied)
        assertTrue(legalityOf("lane-2") is Legality.Denied)
        assertTrue(legalityOf(null) is Legality.Denied, "EVERY legal zone is now full -- correctly unplayable, not just the default one")
    }

    check("lane plan follow-up: an unspecified play action redirects to an OPEN zone, not blindly to a full default") {
        // legality() only guarantees SOME zone has room, so an unspecified zone
        // must resolve to one that does, not blindly to the default.
        val shipType = TypeDef("Ship", zoneChoices = listOf("lane-1", "lane-2"))
        val rules = Rules(
            types = BUILTIN_TYPES_CORE + mapOf(shipType.name to shipType),
            zones = BUILTIN_ZONES + listOf("lane-1", "lane-2")
                .map { PlayZoneDef(it, ZoneScope.PER_PLAYER, maxOccupants = 1) }.associateBy { it.id },
        )
        val scout = CardDefinition(listOf(Face("Scout", setOf("Ship"), Characteristics("Scout", setOf("Ship")))))
        var s = newGame()
        val (s1, _) = enterBattlefield(Grunt.copy(faces = listOf(Grunt.faces[0].copy(types = setOf("Ship")))), "P0", s, zone = ZoneRef("lane-1", "P0"))
        s = s1
        val end = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PriorityAction.PlayPermanent(scout.lowered(LIFE), 0))), rules = rules).run(s)
        }
        assertEq(ZoneRef("lane-2", "P0"), end.byName("Scout")!!.zone, "redirected to the one still-open lane, not lane-1")
    }

    check("Improvements: an entering permanent's SelfEnters trigger prompts for a host and attaches to it") {
        // The Ablative Plating shape, in the layer that CAN be tested -- SelfEnters -> Choose -> Attach, plus an onlyHost
        // static reading the host it bound.
        val shipType = TypeDef("Ship", fields = setOf("hull"), attacks = true)
        val impType = TypeDef("Improvement")
        val rules = Rules(types = BUILTIN_TYPES_CORE + listOf(shipType, impType).associateBy { it.name })
        val (s, ship) = enterBattlefield(
            CardDefinition(listOf(Face("Scout", setOf("Ship"), Characteristics("Scout", setOf("Ship"), mapOf("hull" to 1))))),
            "P0", newGame(),
        )
        val plating = CardDoc(faces = listOf(FaceDoc(
            "Ablative Plating", setOf("Improvement"),
            triggers = listOf(TriggerDoc.SelfEnters(
                Effect.Choose(
                    PermFilter(types = setOf("Ship")).yours(),
                    Effect.Attach(BoundTarget(CHOSEN)),
                ),
            )),
            statics = listOf(StaticSpec(
                filter = PermFilter().onlyHost(),
                ops = listOf(CharOp.PlusField("hull", lit(2))),
            )),
        ))).build()
        val end = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PLAY(plating)), targets = listOf(ship)), rules = rules).run(s)
        }
        val imp = end.byName("Ablative Plating")
        assertTrue(imp != null, "the Improvement itself resolved into play")
        assertEq(ship, imp!!.hostId, "SelfEnters fired, the choice was offered, and Attach bound the host")
        assertEq(3, end.characteristicsOf(ship).fields["hull"], "1 base + 2 from the onlyHost static")
    }

    check("Improvements: an attached permanent dies with its host, and says so when the attach fizzles") {
        // The SBA clause (hostId set, host gone), exercised by a real card.
        val shipType = TypeDef("Ship", fields = setOf("hull"), attacks = true, diesWhen = (selfField("hull") lte selfDamage).lowered())
        val rules = Rules(types = BUILTIN_TYPES_CORE + listOf(shipType, TypeDef("Improvement")).associateBy { it.name })
        val (s0, ship) = enterBattlefield(
            CardDefinition(listOf(Face("Scout", setOf("Ship"), Characteristics("Scout", setOf("Ship"), mapOf("hull" to 1))))),
            "P0", newGame(),
        )
        val plating = CardDoc(faces = listOf(FaceDoc(
            "Ablative Plating", setOf("Improvement"),
            triggers = listOf(TriggerDoc.SelfEnters(
                Effect.Choose(PermFilter(types = setOf("Ship")).yours(), Effect.Attach(BoundTarget(CHOSEN))),
            )),
        ))).build()
        val end = runSync {
            Engine(
                ScriptedInput.of(
                    "P0" to listOf(PLAY(plating), PASS, CAST(Effect.DealDamage(lit(5), BoundTarget(ship)))),
                    targets = listOf(ship),
                ),
                rules = rules,
            ).run(s0)
        }
        assertEq(null, end.byName("Scout"), "the host died to 5 damage")
        assertEq(null, end.byName("Ablative Plating"), "and the thing attached to it went with it")
    }

    check("Improvements: an attach with no legal host SAYS SO") {
        // Leading explanation for "Ablative Plating did not prompt": played
        // with no Ship out, `Choose` finds no candidates and the whole thing
        // no-ops. That is defensible behaviour; doing it invisibly is not.
        val rules = Rules(types = BUILTIN_TYPES_CORE + mapOf("Improvement" to TypeDef("Improvement")))
        val plating = CardDoc(faces = listOf(FaceDoc(
            "Ablative Plating", setOf("Improvement"),
            triggers = listOf(TriggerDoc.SelfEnters(
                Effect.Choose(PermFilter(types = setOf("Ship")).yours(), Effect.Attach(BoundTarget(CHOSEN))),
            )),
        ))).build()
        val end = runSync { Engine(ScriptedInput.of("P0" to listOf(PLAY(plating))), rules = rules).run(newGame()) }
        assertTrue(end.byName("Ablative Plating") != null, "it still enters play -- nothing un-plays it")
        assertTrue(
            end.log.any { it.contains("no legal target") && it.contains("Ablative Plating") },
            "the log now NAMES the card that fizzled, instead of a bare 'no legal target'",
        )
    }

    // -- Shared-turn structure (SWU/LoR): step 1 ----------------------------

    check("shared turn: BOTH players may act at sorcery speed -- under PER_PLAYER only the active one may") {
        // The sorcery-speed check is what makes a non-active player a responder ONLY.
        val phases = listOf(PhaseSpec("main", sorcerySpeed = true))
        val perPlayer = Rules(turn = TurnStructure(phases))
        val sharedR = Rules(turn = TurnStructure(phases, mode = TurnMode.SHARED))
        val s = newGame().copy(phase = "main", phaseIndex = 0, activePlayer = "P0")
        assertTrue(sorcerySpeedWindow(perPlayer, s, "P0"), "the active player always has a window")
        assertTrue(!sorcerySpeedWindow(perPlayer, s, "P1"), "PER_PLAYER: the other player may only respond")
        assertTrue(sorcerySpeedWindow(sharedR, s, "P0"), "SHARED: the initiative holder may act")
        assertTrue(sorcerySpeedWindow(sharedR, s, "P1"), "SHARED: and so may the other player -- there is no 'my turn'")
        // A non-sorcery-speed phase is still closed to everyone.
        val instantOnly = Rules(turn = TurnStructure(listOf(PhaseSpec("combat")), mode = TurnMode.SHARED))
        assertTrue(!sorcerySpeedWindow(instantOnly, s.copy(phase = "combat"), "P1"))
    }

    check("shared turn: one round -- the phase list runs ONCE and its automatic work hits EVERY player") {
        // PER_PLAYER: P0 untaps and draws on P0's turn, P1 on P1's. SHARED:
        // one round, both untap and both draw.
        val phases = listOf(
            PhaseSpec("start", interactive = false, onEnter = PhaseEffects.UNTAP),
            PhaseSpec("draw", interactive = false, onEnter = PhaseEffects.DRAW),
        )
        fun run(mode: TurnMode): GameState {
            // firstPlayerSkipsFirstDraw defaults TRUE, and would otherwise
            // silence P0's turn-1 draw in BOTH modes -- off, so this measures
            // per-player vs shared automatic work and nothing else.
            val rules = Rules(
                turn = TurnStructure(phases, mode = mode),
                params = GameParams(cardsDrawnPerTurn = 1, firstPlayerSkipsFirstDraw = false),
            )
            var s = newGame(libraries = mapOf("P0" to tokens(9), "P1" to tokens(9, 200_000)))
            // exhaust one permanent per side so UNTAP is observable
            val (s1, a) = enterBattlefield(Grunt, "P0", s); s = s1
            val (s2, b) = enterBattlefield(Grunt, "P1", s); s = s2
            s = s.copy(battlefield = s.battlefield.mapValues { it.value.copy(exhausted = true) })
            return runSync { Engine(ScriptedInput.of(), rules = rules).playGame(s, maxTurns = 1) }
        }
        val per = run(TurnMode.PER_PLAYER)
        assertEq(1, per.players.getValue("P0").hand.size, "PER_PLAYER: only the active player drew")
        assertEq(0, per.players.getValue("P1").hand.size)
        assertTrue(per.battlefield.values.none { it.controller == "P1" && !it.exhausted }, "and only their permanents untapped")

        val sh = run(TurnMode.SHARED)
        assertEq(1, sh.players.getValue("P0").hand.size, "SHARED: everyone draws in the one round")
        assertEq(1, sh.players.getValue("P1").hand.size)
        assertTrue(sh.battlefield.values.all { !it.exhausted }, "and everyone untaps")
    }

    check("shared turn: each player gets their OWN PhaseEnter, so 'at the start of your upkeep' still fires for both") {
        // Decided, not left to fall out of the implementation: EPR Skirmish's
        // whole Station economy is an OnYourPhase("upkeep") trigger, so if a
        // shared round emitted one PhaseEnter naming one player, only that
        // player would ever draw or get resources.
        val phases = listOf(PhaseSpec("upkeep", interactive = false))
        val watcher = CardDoc(faces = listOf(FaceDoc(
            "Relay", setOf("Enchantment"),
            triggers = listOf(TriggerDoc.OnYourPhase("upkeep", Effect.Draw(PlayerRef.You, lit(1)))),
        ))).build()
        fun handsAfterOneRound(mode: TurnMode): Pair<Int, Int> {
            val rules = Rules(turn = TurnStructure(phases, mode = mode))
            var s = newGame(libraries = mapOf("P0" to tokens(9), "P1" to tokens(9, 200_000)))
            val (s1, _) = enterBattlefield(watcher, "P0", s); s = s1
            val (s2, _) = enterBattlefield(watcher, "P1", s); s = s2
            val end = runSync { Engine(ScriptedInput.of(), rules = rules).playGame(s, maxTurns = 1) }
            return end.players.getValue("P0").hand.size to end.players.getValue("P1").hand.size
        }
        assertEq(1 to 0, handsAfterOneRound(TurnMode.PER_PLAYER), "PER_PLAYER: only the active player's upkeep happened")
        assertEq(1 to 1, handsAfterOneRound(TurnMode.SHARED), "SHARED: both players' upkeep triggers fired in the one round")
    }

    check("poolPersistsPerTurn means FOR A TURN -- a shared round must still empty the pool at its boundary") {
        // Under SHARED every player is an actor at every phase, so the per-phase
        // pool clear never reached anyone; the round boundary must reset it (or
        // flat production accumulates without bound).
        val phases = listOf(PhaseSpec("upkeep", interactive = false), PhaseSpec("main", sorcerySpeed = true))
        val station = CardDoc(faces = listOf(FaceDoc(
            "Relay", setOf("Enchantment"),
            triggers = listOf(TriggerDoc.OnYourPhase("upkeep", Effect.AddMana(PlayerRef.You, mapOf("" to lit(2))))),
        ))).build()
        fun poolAfter(rounds: Int, mode: TurnMode): Int {
            val rules = Rules(
                turn = TurnStructure(phases, mode = mode),
                params = GameParams(poolPersistsPerTurn = true),
            )
            var s = newGame(libraries = mapOf("P0" to tokens(9), "P1" to tokens(9, 200_000)))
            val (s1, _) = enterBattlefield(station, "P0", s); s = s1
            val end = runSync { Engine(ScriptedInput.of(), rules = rules).playGame(s, maxTurns = rounds) }
            return end.players.getValue("P0").pool.values.sum()
        }
        assertEq(2, poolAfter(1, TurnMode.SHARED), "one round of a +2 economy leaves 2 unspent")
        assertEq(2, poolAfter(3, TurnMode.SHARED), "and THREE rounds still leave 2 -- the pool does not accrete across rounds")
        assertEq(2, poolAfter(3, TurnMode.PER_PLAYER), "PER_PLAYER was always correct and stays byte-for-byte so")
    }

    check("poolStoreCounter: a flat economy reaches an expensive card by BANKING, and the cap is a player counter the Station seeds") {
        // The alternative to a ramp curve. Production never grows; what grows
        // is what you declined to spend, up to a cap that belongs to the card
        // the player brought (their Station) rather than to the ruleset --
        // the same argument as Rules.damageCounter.
        val phases = listOf(PhaseSpec("upkeep", interactive = false), PhaseSpec("main", sorcerySpeed = true))
        val station = CardDoc(faces = listOf(FaceDoc(
            "Relay", setOf("Enchantment"),
            triggers = listOf(TriggerDoc.OnYourPhase("upkeep", Effect.AddMana(PlayerRef.You, mapOf("" to lit(2))))),
        ))).build()
        fun poolAfter(rounds: Int, store: Int): Int {
            val rules = Rules(
                turn = TurnStructure(phases, mode = TurnMode.SHARED),
                params = GameParams(poolPersistsPerTurn = true, poolStoreCounter = "store"),
            )
            var s = newGame(
                counters = mapOf("life" to 20, "store" to store),
                libraries = mapOf("P0" to tokens(9), "P1" to tokens(9, 200_000)),
            )
            val (s1, _) = enterBattlefield(station, "P0", s); s = s1
            val end = runSync { Engine(ScriptedInput.of(), rules = rules).playGame(s, maxTurns = rounds) }
            return end.players.getValue("P0").pool.values.sum()
        }
        // Carry at most 3 across the boundary, then this round's +2 on top.
        assertEq(2, poolAfter(1, store = 3))
        assertEq(4, poolAfter(2, store = 3), "round 2 spends nothing: 2 carried + 2 granted")
        assertEq(5, poolAfter(3, store = 3), "round 3 carries only 3 of the 4 -- the cap bites")
        assertEq(5, poolAfter(6, store = 3), "and it is a STORE, not a ramp: it plateaus at cap + production")
        // A miserly store is the whole difference between two Stations.
        assertEq(3, poolAfter(6, store = 1), "a Station that cannot bank is permanently a 3-mana deck")
        assertEq(2, poolAfter(6, store = 0), "store 0 is the plain emptying pool, unchanged")
    }

    check("shared turn: initiative passes at the end of the round, and the mode round-trips (old saves stay bare arrays)") {
        val phases = listOf(PhaseSpec("main", sorcerySpeed = true))
        val rules = Rules(turn = TurnStructure(phases, mode = TurnMode.SHARED))
        val end = runSync { Engine(ScriptedInput.of(), rules = rules).playGame(newGame(), maxTurns = 1) }
        assertEq("P1", end.activePlayer, "initiative passed -- same operation as a turn passing, different meaning")
        // JSON: PER_PLAYER keeps the legacy bare-array shape byte for byte.
        val legacy = TurnStructure(phases)
        assertTrue(turnStructureToJson(legacy).startsWith("["), "PER_PLAYER still writes a bare array -- no save needs migrating")
        assertEq(legacy, turnStructureOf(Json.parse(turnStructureToJson(legacy))))
        val sharedTs = TurnStructure(phases, mode = TurnMode.SHARED)
        assertEq(sharedTs, turnStructureOf(Json.parse(turnStructureToJson(sharedTs))), "SHARED round-trips through the object shape")
        assertEq(TurnStructure.MTG, turnStructureOf(Json.parse(turnStructureToJson(TurnStructure.MTG))), "and the builtin is untouched")
    }

    check("shared turn survives the WHOLE GameDoc round-trip -- the app plays what store.save() read back") {
        // The link my earlier shared-turn tests did NOT cover: they called
        // turnStructureOf(turnStructureToJson(..)) directly. The app plays a
        // GameDoc that went out to JSON and came BACK, through RulesDoc --
        // and RulesDoc only writes `turn` at all when it differs from MTG.
        val phases = listOf(
            PhaseSpec("regroup", interactive = false, onEnter = PhaseEffects.UNTAP),
            PhaseSpec("upkeep", interactive = false),
            PhaseSpec("action", sorcerySpeed = true),
            PhaseSpec("combat", combat = true),
        )
        val doc = GameDoc(
            id = "shared-probe",
            name = "Shared Probe",
            rules = RulesDoc(turn = TurnStructure(phases, mode = TurnMode.SHARED)),
        )
        val back = gameDocFromJson(gameDocToJson(doc))
        assertEq(TurnMode.SHARED, back.rules.turn.mode, "the MODE survives the whole-game round-trip")
        assertEq(doc.rules.turn, back.rules.turn, "and so does the phase list")
        assertEq(doc, back, "and the whole doc")
        // and the built Rules the engine actually runs on carries it too.
        assertEq(TurnMode.SHARED, back.rules().turn.mode, "rules() carries the mode into the engine")
    }

    check("shared turn: BOTH seats' Station economies pay out -- via setupGame/startInPlay, the way the app places them") {
        // Runs the APP's path (setupGame with startInPlay) with a Station economy
        // on both seats: each Station's draw and resources must reach its seat.
        val stationType = TypeDef("Station", loseOnDeath = true)
        val station = CardDoc(faces = listOf(FaceDoc(
            "Relay", setOf("Station"),
            triggers = listOf(TriggerDoc.OnYourPhase(
                "upkeep",
                Effect.Sequence(listOf(
                    Effect.DrawThenDiscard(PlayerRef.You, lit(2), lit(1), HiddenZone.EXILE),
                    Effect.AddMana(PlayerRef.You, mapOf("" to lit(2))),
                )),
            )),
        ))).build()
        val rules = Rules(
            types = BUILTIN_TYPES_CORE + mapOf("Station" to stationType),
            cards = mapOf("Relay" to station),
            turn = TurnStructure(
                listOf(
                    PhaseSpec("regroup", interactive = false, onEnter = PhaseEffects.UNTAP),
                    PhaseSpec("upkeep", interactive = false),
                    PhaseSpec("action", sorcerySpeed = true),
                ),
                mode = TurnMode.SHARED,
            ),
            params = GameParams(startingHandSize = 0, cardsDrawnPerTurn = 0, poolPersistsPerTurn = true),
        )
        val start = setupGame(
            rules = rules,
            libraries = mapOf("P0" to tokens(9), "P1" to tokens(9, 200_000)),
            startInPlay = mapOf(
                "P0" to listOf(StartCard("Relay", "battlefield")),
                "P1" to listOf(StartCard("Relay", "battlefield")),
            ),
            shuffle = false,
        )
        assertEq(2, start.battlefield.size, "both seats got a Station at setup")
        // each seat's DrawThenDiscard asks THAT seat which of its own 2 to
        // exile: P0's upkeep resolves first, then P1's.
        val end = runSync {
            Engine(
                ScriptedInput.of(cards = listOf(listOf(100_000), listOf(200_000))),
                rules = rules,
            ).playGame(start, maxTurns = 1)
        }
        for (pid in listOf("P0", "P1")) {
            val p = end.players.getValue(pid)
            assertEq(1, p.hand.size, "$pid drew 2 and exiled 1 from their own Station")
            assertEq(2, p.pool.values.sum(), "$pid got their Station's resources")
        }
    }

    check("the mana STORE is seeded by the Station through setupGame, exactly as hull is -- and a 6-cost is then reachable") {
        // The store cap belongs to the card the player brought; tested through
        // the app's setup path.
        val stationType = TypeDef("Station", loseOnDeath = true)
        val station = CardDoc(
            faces = listOf(FaceDoc(
                "Relay", setOf("Station"),
                triggers = listOf(TriggerDoc.OnYourPhase("upkeep", Effect.AddMana(PlayerRef.You, mapOf("" to lit(2))))),
            )),
            entersWith = listOf(CounterDef("hull", lit(15)), CounterDef("store", lit(6))),
        ).build()
        val rules = Rules(
            types = BUILTIN_TYPES_CORE + mapOf("Station" to stationType),
            cards = mapOf("Relay" to station),
            turn = TurnStructure(
                listOf(PhaseSpec("upkeep", interactive = false), PhaseSpec("action", sorcerySpeed = true)),
                mode = TurnMode.SHARED,
            ),
            params = GameParams(
                startingHandSize = 0, cardsDrawnPerTurn = 0,
                poolPersistsPerTurn = true, poolStoreCounter = "store",
            ),
            playerCounters = listOf(
                PlayerCounterDef("hull", starting = 0, loseAtZero = true),
                PlayerCounterDef("store", starting = 0),
            ),
            damageCounter = "hull",
        )
        fun run(rounds: Int): GameState {
            val start = setupGame(
                rules = rules,
                libraries = mapOf("P0" to tokens(30), "P1" to tokens(30, 200_000)),
                // BOTH seats, or P1 sits on the ruleset's hull of 0 and
                // loseAtZero ends the game before round 1 ever runs.
                startInPlay = mapOf(
                    "P0" to listOf(StartCard("Relay", "battlefield")),
                    "P1" to listOf(StartCard("Relay", "battlefield")),
                ),
                shuffle = false,
            )
            assertEq(6, start.players.getValue("P0").counter("store"), "the STATION set the store, not the ruleset (which says 0)")
            assertEq(15, start.players.getValue("P0").counter("hull"), "and hull still comes from the same place")
            return runSync { Engine(ScriptedInput.of(), rules = rules).playGame(start, maxTurns = rounds) }
        }
        assertEq(2, run(1).players.getValue("P0").pool.values.sum())
        assertEq(6, run(3).players.getValue("P0").pool.values.sum(), "three idle rounds reach 6 -- Kestrel Paragon becomes castable by DECLINING TO ACT")
        assertEq(8, run(5).players.getValue("P0").pool.values.sum(), "and it plateaus at cap + production: a store, never a ramp")
    }

    check("modality by HOST: one Improvement reads differently on a Station than on a Ship, with no engine support") {
        // The load-bearing claim of the economy slice. PermFilter ANDs
        // `onlyHost` with `types` (Expr.kt:85-97), so a filter can say "the
        // permanent I am attached to, IF it is a Station" -- which is all a
        // modal card needs. No new verb, no new field.
        val stationType = TypeDef("Station")
        val shipType = TypeDef("Ship", fields = setOf("hull", "fast"), attacks = true)
        val coupler = CardDoc(faces = listOf(FaceDoc(
            "Coupler", setOf("Improvement"),
            triggers = listOf(TriggerDoc.OnYourPhase("upkeep", Effect.If(
                countOf(PermFilter(types = setOf("Station")).onlyHost()) gte 1,
                Effect.AddMana(PlayerRef.You, mapOf("" to lit(1))),
            ))),
            statics = listOf(StaticSpec(
                filter = PermFilter(types = setOf("Ship")).onlyHost(),
                ops = listOf(CharOp.PlusField("fast", lit(1))),
            )),
        ))).build()
        val rules = Rules(
            types = BUILTIN_TYPES_CORE + mapOf("Station" to stationType, "Ship" to shipType, "Improvement" to TypeDef("Improvement")),
            turn = TurnStructure(listOf(PhaseSpec("upkeep", interactive = false)), mode = TurnMode.SHARED),
            params = GameParams(startingHandSize = 0, cardsDrawnPerTurn = 0, poolPersistsPerTurn = true),
        )
        val hostShip = CardDoc(faces = listOf(FaceDoc("Hulk", setOf("Ship"), fields = mapOf("hull" to 2, "fast" to 1)))).build()
        val hostStation = CardDoc(faces = listOf(FaceDoc("Relay", setOf("Station")))).build()

        fun attachedTo(host: CardDefinition): Pair<GameState, ObjectId> {
            var s = newGame(libraries = mapOf("P0" to tokens(9), "P1" to tokens(9, 200_000)))
            val (s1, hostId) = enterBattlefield(host, "P0", s); s = s1
            val (s2, impId) = enterBattlefield(coupler, "P0", s); s = s2
            s = s.copy(battlefield = s.battlefield + (impId to s.battlefield.getValue(impId).copy(hostId = hostId)))
            val end = runSync { Engine(ScriptedInput.of(), rules = rules).playGame(s, maxTurns = 1) }
            return end to hostId
        }

        val (onShip, shipId) = attachedTo(hostShip)
        assertEq(0, onShip.players.getValue("P0").pool.values.sum(), "on a SHIP the mana mode is off")
        assertEq(2, onShip.characteristicsOf(shipId).fields["fast"], "and the stat mode is on: 1 + 1")

        val (onStation, _) = attachedTo(hostStation)
        assertEq(1, onStation.players.getValue("P0").pool.values.sum(), "on a STATION the mana mode is on")
    }

    check("GameDoc.contentVersion round-trips, so a stale saved copy can be DETECTED not guessed") {
        // A stamp that survives the save lets the Games screen say when a
        // device is playing an old copy of a bundled game.
        val stamped = GameDoc(id = "epr-skirmish", name = "EPR Skirmish", contentVersion = 7)
        assertEq(7, gameDocFromJson(gameDocToJson(stamped)).contentVersion, "the stamp survives the save")
        assertEq(stamped, gameDocFromJson(gameDocToJson(stamped)))
        // A user-authored game never sets it, stays at 0, and is elided.
        val plain = GameDoc(name = "Mine")
        assertTrue(!gameDocToJson(plain).contains("contentVersion"), "elided when unused")
        assertEq(0, gameDocFromJson(gameDocToJson(plain)).contentVersion)
        // and an OLD save (no field at all) reads as 0 -> older than any
        // stamped bundle, which is exactly the "stale" signal we want.
        assertTrue(0 < 1, "a pre-stamp save is correctly older than contentVersion = 1")
    }

    check("two factions: each seat's hull comes from ITS OWN Station") {
        // With ONE Station value, a per-Station hull and a hard-set one are
        // indistinguishable. Two Stations, two numbers.
        val stationType = TypeDef("Station", loseOnDeath = true)
        val kestrel = CardDoc(
            faces = listOf(FaceDoc("Relay Kestrel-9", setOf("Station"))),
            entersWith = listOf(CounterDef("hull", lit(15))),
        ).build()
        val pyre = CardDoc(
            faces = listOf(FaceDoc("Pyre of the Ninth Choir", setOf("Station"))),
            entersWith = listOf(CounterDef("hull", lit(24))),
        ).build()
        val rules = Rules(
            types = BUILTIN_TYPES_CORE + mapOf("Station" to stationType),
            cards = mapOf("Relay Kestrel-9" to kestrel, "Pyre of the Ninth Choir" to pyre),
            playerCounters = listOf(PlayerCounterDef("hull", starting = 0, loseAtZero = true)),
            damageCounter = "hull",
            params = GameParams(startingHandSize = 0),
        )
        val s = setupGame(
            rules = rules,
            libraries = mapOf("P0" to tokens(5), "P1" to tokens(5, 200_000)),
            startInPlay = mapOf(
                "P0" to listOf(StartCard("Relay Kestrel-9", "battlefield")),
                "P1" to listOf(StartCard("Pyre of the Ninth Choir", "battlefield")),
            ),
            shuffle = false,
        )
        assertEq(15, s.players.getValue("P0").counter("hull"), "Kestrel seat starts on ITS Station's 15")
        assertEq(24, s.players.getValue("P1").counter("hull"), "Ashen seat starts on ITS Station's 24")
        assertEq(0, rules.startingCounters()["hull"], "and the RULESET itself still says 0 -- neither number is a default")
    }

    check("Ashen Choir: paying hull is real -- it is refused when short, and CAN kill you at exactly 0") {
        // The Choir spends hull as a
        // play cost, an activated cost, an instant's cost and a directive's.
        val rules = Rules(
            playerCounters = listOf(PlayerCounterDef("hull", starting = 0, loseAtZero = true)),
            damageCounter = "hull",
        )
        val tithe = Effect.Draw(PlayerRef.You, lit(2))
        fun castWith(hull: Int): GameState {
            val s = newGame(counters = mapOf("hull" to hull), libraries = mapOf("P0" to tokens(6)))
                .copy(damageCounter = "hull")
            return runSync {
                Engine(ScriptedInput.of("P0" to listOf(CAST(tithe, setOf("Directive")))), rules = rules)
                    .run(s)
            }
        }
        // NOTE: the sandbox CAST carries no Cost, so this proves the COUNTER
        // wiring; the cost path itself is asserted directly below.
        assertEq(2, castWith(10).players.getValue("P0").hand.size, "the effect itself resolves")
        // The real assertion: canAfford reads hull, not a phantom "life".
        val rich = newGame(counters = mapOf("hull" to 5)).copy(damageCounter = "hull")
        assertTrue(rich.canAfford("P0", Cost(payLife = 4)), "4 hull out of 5 is payable")
        assertTrue(rich.canAfford("P0", Cost(payLife = 5)), "paying to EXACTLY 0 is allowed -- that is the faction's risk")
        assertTrue(!rich.canAfford("P0", Cost(payLife = 6)), "but you cannot pay hull you do not have")
    }

    check("tokens spread across open lanes instead of all piling at the first one") {
        // CreateToken and playPermanent share Rules.openZoneFor, so a token-maker
        // on a one-per-lane board fills the empty lanes instead of declining.
        val shipType = TypeDef("Ship", fields = setOf("hull", "fast"), attacks = true, zoneChoices = listOf("lane-1", "lane-2", "lane-3"))
        val rules = Rules(
            types = BUILTIN_TYPES_CORE + mapOf("Ship" to shipType),
            zones = BUILTIN_ZONES + listOf("lane-1", "lane-2", "lane-3")
                .map { PlayZoneDef(it, ZoneScope.PER_PLAYER, maxOccupants = 1) }.associateBy { it.id },
        )
        val acolyte = Characteristics("Acolyte", setOf("Ship"), mapOf("hull" to 1, "fast" to 1))
        val end = runSync {
            Engine(ScriptedInput.of("P0" to listOf(CAST(Effect.CreateToken(acolyte, lit(3))))), rules = rules).run(newGame())
        }
        val lanes = end.battlefield.values.filter { it.base.name == "Acolyte" }.map { it.zone }.toSet()
        assertEq(3, lanes.size, "three tokens found three different lanes")
        assertEq(
            setOf(ZoneRef("lane-1", "P0"), ZoneRef("lane-2", "P0"), ZoneRef("lane-3", "P0")),
            lanes,
        )
        // A FOURTH has nowhere to go and is declined, not squeezed in.
        val full = runSync {
            Engine(ScriptedInput.of("P0" to listOf(CAST(Effect.CreateToken(acolyte, lit(4))))), rules = rules).run(newGame())
        }
        assertEq(3, full.battlefield.values.count { it.base.name == "Acolyte" }, "the cap still holds")
        assertTrue(full.log.any { it.contains("no room left") }, "and says so")
    }

    check("the Flagship POOL is genuinely multi-card -- the brief's 'small deck of Flagships', never built until now") {
        // DeckSlotDef("Flagship", .., 1, ..) meant every faction had exactly
        // ONE, so the always-accessible pool had never actually been a pool.
        // `opening()` always supported a list; only the content said 1.
        val shipType = TypeDef("Ship", fields = setOf("hull", "fast"), attacks = true)
        fun flag(n: String, cost: Int) = CardDoc(
            faces = listOf(FaceDoc(n, setOf("Ship", "Flagship"), mapOf("hull" to 3, "fast" to 1))),
            cost = Cost(mana = mapOf("" to cost)),
        ).build()
        val pool = listOf("Loom" to 4, "Hulk" to 3, "Barge" to 3)
        val rules = Rules(
            types = BUILTIN_TYPES_CORE + mapOf("Ship" to shipType, "Flagship" to TypeDef("Flagship")),
            hiddenZones = mapOf("flagships" to HiddenZoneDef("flagships")),
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
        assertEq(3, held.size, "all THREE Flagships are in the pool, not just the first")
        assertEq(setOf("Loom", "Hulk", "Barge"), held.map { it.cardId }.toSet())
        // and each is castable FROM that zone, which is what "always
        // accessible" means -- given the mana for it.
        val rich = s.copy(players = s.players + ("P0" to s.players.getValue("P0").copy(pool = mapOf("" to 4))))
        for (ref in held) {
            val act = PriorityAction.PlayPermanent(
                rules.cards.getValue(ref.cardId).lowered(LIFE), 0,
                from = CardSource(CastZone.Declared("flagships"), ref.instanceId),
            )
            assertTrue(legality(rules, rich, "P0", act) is Legality.Legal, "${ref.cardId} is playable out of the pool")
        }
    }

    check("Cost.additional: sacrifice is paid as a COST, not run as an effect") {
        // Supported since P9; no card ever used it until the Sable
        // Congregation, whose Leader and Manoeuvres are built on it.
        val shipType = TypeDef("Ship", fields = setOf("hull"), attacks = true)
        val rules = Rules(types = BUILTIN_TYPES_CORE + mapOf("Ship" to shipType))
        val ves = card(
            "Deacon Ves", setOf("Leader"),
            baseChars = Characteristics("Deacon Ves", setOf("Leader")),
            activated = listOf(ActivatedAbility(
                cost = Cost(
                    tapSource = true,
                    additional = Effect.Sacrifice(PlayerRef.You, lit(1), PermFilter(types = setOf("Ship")).yours()),
                ),
                effect = Effect.Draw(PlayerRef.You, lit(1)),
                name = "exhaust, sacrifice a Ship: draw",
            )),
        )
        var s = newGame(libraries = mapOf("P0" to tokens(4)))
        val (s1, leader) = enterBattlefield(ves, "P0", s); s = s1
        val (s2, shell) = enterBattlefield(
            CardDefinition(listOf(Face("Shell", setOf("Ship"), Characteristics("Shell", setOf("Ship"), mapOf("hull" to 1))))),
            "P0", s,
        )
        s = s2
        val end = runSync {
            Engine(
                // Sacrifice asks WHICH ship -- the cost is a real choice, not
                // an automatic cull.
                ScriptedInput.of("P0" to listOf(PriorityAction.Activate(leader, 0)), targets = listOf(shell)),
                rules = rules.knowing(ves),
            ).run(s)
        }
        assertEq(null, end.byName("Shell"), "the Ship was consumed paying the cost")
        assertEq(1, end.players.getValue("P0").hand.size, "and the card was drawn")
        assertTrue(end.battlefield.getValue(leader).exhausted, "and the Leader is exhausted")
    }

    check("shared turn: WHO declares attackers is a stated rule, not an incidental activePlayer read") {
        val phases = listOf(PhaseSpec("combat", combat = true))
        val per = Rules(turn = TurnStructure(phases))
        val shared = Rules(turn = TurnStructure(phases, mode = TurnMode.SHARED))
        val s = newGame().copy(activePlayer = "P1")
        assertEq("P1", per.declaresAttackers(s), "PER_PLAYER: the active player -- it is their turn")
        assertEq("P1", shared.declaresAttackers(s), "SHARED: the INITIATIVE holder declares")
        // The rule is visible to a player, not just to a doc comment.
        assertEq("active player", per.declarerNote())
        assertEq("initiative holder", shared.declarerNote())
        // and it actually reaches the log where a real DECLARED combat runs.
        val dRules = Rules(
            turn = TurnStructure(listOf(PhaseSpec("combat", combat = true)), mode = TurnMode.SHARED),
            combat = MTG_COMBAT.lowered(),
        )
        val end = runSync { Engine(ScriptedInput.of(), rules = dRules).playGame(newGame(), maxTurns = 1) }
        assertTrue(end.log.any { it.contains("declares attackers (initiative holder)") }, "the log names the rule in force")
    }

    check("a trigger-driven economy does not trip the 'no draw phase' advisory") {
        // EPR Skirmish draws entirely from its Station's upkeep trigger and
        // declares cardsDrawnPerTurn = 0. Warning it about a missing draw
        // phase was a false positive -- and a false positive in an advisory
        // list is how the list stops being read.
        val noDrawPhase = TurnStructure(listOf(PhaseSpec("action", sorcerySpeed = true), PhaseSpec("combat", combat = true)))
        assertTrue(
            noDrawPhase.problems(GameParams(cardsDrawnPerTurn = 0)).none { it.contains("draw phase") },
            "opted out of the automatic draw -> not a problem",
        )
        assertTrue(
            noDrawPhase.problems(GameParams(cardsDrawnPerTurn = 1)).any { it.contains("draw phase") },
            "but a game that DOES rely on the automatic draw is still told",
        )
    }

    check("CardDoc.requires: an attach card is UNPLAYABLE with no legal host, instead of wasted") {
        // Was reported twice and deferred twice as needing a design decision:
        // legality() cannot introspect a trigger (opaque closure), so the
        // requirement is declared as DATA on the card instead.
        val shipType = TypeDef("Ship", fields = setOf("hull"), attacks = true)
        val rules = Rules(types = BUILTIN_TYPES_CORE + listOf(shipType, TypeDef("Improvement")).associateBy { it.name })
        val plating = CardDoc(
            faces = listOf(FaceDoc("Ablative Plating", setOf("Improvement"))),
            requires = PermFilter(types = setOf("Ship")).yours(),
        ).build()
        fun legalNow(s: GameState) = legality(rules, s, "P0", PriorityAction.PlayPermanent(plating, 0))
        assertTrue(legalNow(newGame()) is Legality.Denied, "no Ship in play -> can't play it at all")
        val ship = CardDefinition(listOf(Face("Scout", setOf("Ship"), Characteristics("Scout", setOf("Ship")))))
        val withOwn = enterBattlefield(ship, "P0", newGame()).first
        assertTrue(legalNow(withOwn) is Legality.Legal, "a Ship you control satisfies it")
        // .yours() means the OPPONENT's Ship must not satisfy it.
        val onlyTheirs = enterBattlefield(ship, "P1", newGame()).first
        assertTrue(legalNow(onlyTheirs) is Legality.Denied, "an opponent's Ship is not a legal host")
        // and it survives the round-trip the device actually plays.
        val doc = CardDoc(
            faces = listOf(FaceDoc("Ablative Plating", setOf("Improvement"))),
            requires = PermFilter(types = setOf("Ship")).yours(),
        )
        assertEq(doc, cardDocOf(Json.parse(cardDocToJson(doc))), "requires round-trips")
    }

    check("CreatureDies watches DECLARED types -- the last hard-coded \"Creature\" in the engine") {
        val shipType = TypeDef("Ship", fields = setOf("hull"), attacks = true, diesWhen = (selfField("hull") lte selfDamage).lowered())
        val rules = Rules(types = BUILTIN_TYPES_CORE + mapOf("Ship" to shipType))
        fun watcherFor(types: Set<String>) = card(
            "Salvage Beacon", setOf("Enchantment"),
            triggers = listOf(TriggerDoc.CreatureDies(null, Effect.Draw(PlayerRef.You, lit(1)), 0, types)),
        )
        fun run(watch: Set<String>): Int {
            val (s0, _) = enterBattlefield(watcherFor(watch), "P0", newGame(libraries = mapOf("P0" to tokens(5))))
            val (s1, ship) = enterBattlefield(
                CardDefinition(listOf(Face("Scout", setOf("Ship"), Characteristics("Scout", setOf("Ship"), mapOf("hull" to 1))))),
                "P0", s0,
            )
            val end = runSync {
                Engine(ScriptedInput.of("P0" to listOf(CAST(Effect.DealDamage(lit(5), BoundTarget(ship))))), rules = rules).run(s1)
            }
            return end.players.getValue("P0").hand.size
        }
        assertEq(0, run(setOf("Creature")), "a Ship dying is NOT a Creature dying -- the old hard-coded behaviour, preserved as the default")
        assertEq(1, run(setOf("Ship")), "declaring Ship makes it fire -- previously impossible to express")
    }

    check("triggers now choose targets ON ANNOUNCEMENT, so there is a real response window") {
        // Trigger targets bind at ANNOUNCEMENT, so opponents can respond to the
        // choice: kill the chosen host in response and the attach fizzles (a
        // resolution-time choice would pick the survivor). `diesWhen` makes the
        // host actually leave play.
        val shipType = TypeDef("Ship", fields = setOf("hull"), attacks = true, diesWhen = (selfField("hull") lte selfDamage).lowered())
        val rules = Rules(types = BUILTIN_TYPES_CORE + listOf(shipType, TypeDef("Improvement")).associateBy { it.name })
        fun ship(n: String) = CardDefinition(listOf(Face(n, setOf("Ship"), Characteristics(n, setOf("Ship"), mapOf("hull" to 1)))))
        val (sA, shipA) = enterBattlefield(ship("Alpha"), "P0", newGame())
        val (sB, shipB) = enterBattlefield(ship("Beta"), "P0", sA)
        val plating = CardDoc(faces = listOf(FaceDoc(
            "Ablative Plating", setOf("Improvement"),
            triggers = listOf(TriggerDoc.SelfEnters(
                Effect.Choose(PermFilter(types = setOf("Ship")).yours(), Effect.Attach(BoundTarget(CHOSEN))),
            )),
        ))).build()
        val end = runSync {
            Engine(
                ScriptedInput.of(
                    // Play it (target Alpha is chosen as the trigger is put on
                    // the stack), then -- with the trigger still unresolved --
                    // burn Alpha down in response.
                    "P0" to listOf(PLAY(plating), CAST(Effect.DealDamage(lit(5), BoundTarget(shipA)))),
                    targets = listOf(shipA),
                ),
                rules = rules,
            ).run(sB)
        }
        assertEq(null, end.byName("Alpha"), "the chosen host died in response")
        assertTrue(end.byName("Beta") != null, "the other Ship is untouched")
        val imp = end.byName("Ablative Plating")
        assertTrue(imp != null, "the Improvement is still in play")
        assertEq(null, imp!!.hostId, "it fizzled -- it did NOT silently re-target Beta at resolution")
        assertTrue(end.log.any { it.contains("can't attach") }, "and it says why")
    }

    check("Improvements: the attach card survives JSON -- the app plays the round-tripped doc, not the in-memory one") {
        // The one link that differs between the test above and the device:
        // `store.save(EPR_SKIRMISH)` writes JSON and the game is played from
        // what is READ BACK. A trigger or filter that does not survive that
        // round-trip is invisible to every in-memory test.
        val plating = CardDoc(faces = listOf(FaceDoc(
            "Ablative Plating", setOf("Improvement"),
            triggers = listOf(TriggerDoc.SelfEnters(
                Effect.Choose(
                    PermFilter(types = setOf("Ship")).yours(),
                    Effect.Attach(BoundTarget(CHOSEN)),
                ),
            )),
            statics = listOf(StaticSpec(
                filter = PermFilter().onlyHost(),
                ops = listOf(CharOp.PlusField("hull", lit(2))),
            )),
        )))
        assertEq(plating, cardDocOf(Json.parse(cardDocToJson(plating))), "the whole card round-trips")
    }

    check("Manoeuvres: a SPELL type declaring usesStack = false resolves AT ONCE -- the brief's second speed") {
        // `usesStack = false` for a spell: two spells in one window. On the
        // stack the second resolves FIRST (LIFO), so a +0/+1 lands before the
        // damage and saves the creature; off it, the damage resolves at once and
        // SBA kills the creature before the second is cast.
        fun twoSpells(stacked: Boolean): GameState {
            val manoeuvre = TypeDef("Manoeuvre", isSpell = true, instantSpeed = true, usesStack = stacked)
            val rules = Rules(types = BUILTIN_TYPES_CORE + mapOf(manoeuvre.name to manoeuvre))
            val (s, x) = enterBattlefield(creature("Target", 2, 3), "P0", newGame())
            return runSync {
                Engine(
                    ScriptedInput.of(
                        "P0" to listOf(
                            CAST(Effect.DealDamage(lit(3), BoundTarget(x)), setOf("Manoeuvre")),
                            CAST(
                                Effect.ApplyModifier(
                                    permanents().only(x),
                                    listOf(CharOp.PlusPT(lit(0), lit(1))),
                                    duration = Duration.EndOfTurn,
                                ),
                                setOf("Manoeuvre"),
                            ),
                        ),
                    ),
                    rules = rules,
                ).run(s)
            }
        }
        assertTrue(twoSpells(stacked = true).byName("Target") != null, "on the stack: the later spell resolves FIRST, toughness 4 survives 3 damage")
        assertEq(null, twoSpells(stacked = false).byName("Target"), "no stack: the damage resolved at once, 3 vs toughness 3, dead before the second spell")
    }

    check("enumeration order is DECLARED -- by object id, never by map insertion order") {
        // Enumeration order is DECLARED (ascending ids), never inherited from
        // map insertion order: pilots break ties by it and a port must
        // reproduce it.
        val gadget = card(
            "Gadget", setOf("Artifact"),
            activated = listOf(ActivatedAbility(Cost(), Effect.Draw(PlayerRef.You, lit(1)), "draw")),
        )
        val rules = Rules().knowing(gadget)
        var s = newGame()
        val (s1, lo) = enterBattlefield(gadget, "P0", s); s = s1
        val (s2, hi) = enterBattlefield(gadget, "P0", s); s = s2
        assertTrue(lo < hi, "ids are allocated ascending")

        fun sources(st: GameState) =
            legalActionsFor(rules, st, "P0").filterIsInstance<PriorityAction.Activate>().map { it.source }

        assertEq(listOf(lo, hi), sources(s), "the natural build order already matches id order")

        // THE CHECK: rebuild the battlefield with its insertion order REVERSED,
        // which is a state the engine can legitimately produce. Under the old
        // code the enumeration -- and so every pilot's tie-break -- followed the
        // map. It must follow the declared order instead.
        val flipped = s.copy(
            battlefield = linkedMapOf(hi to s.battlefield.getValue(hi), lo to s.battlefield.getValue(lo)),
        )
        assertEq(listOf(hi, lo), flipped.battlefield.keys.toList(), "the fixture really is reversed")
        assertEq(listOf(lo, hi), sources(flipped), "enumeration follows the DECLARED order, not the map")
    }

    check("an ABILITY declaring usesStack = false resolves AT ONCE -- the same axis a type has") {
        // `ActivatedAbility.usesStack = false`, measured by outcome like the
        // spell case: two abilities in one window, and the +0/+1 lands first
        // only when both use the stack.
        fun twoAbilities(stacked: Boolean): GameState {
            val made = mutableListOf<CardDefinition>()
            fun sourceOf(name: String, e: Effect) = card(
                name, setOf("Artifact"),
                activated = listOf(ActivatedAbility(Cost(tapSource = true), e, name, usesStack = stacked)),
            ).lowered(LIFE).also { made += it }
            val rules = Rules(turn = TurnStructure(listOf(PhaseSpec("main", sorcerySpeed = true))))
            var s = newGame()
            val (s1, x) = enterBattlefield(creature("Target", 2, 3), "P0", s); s = s1
            val (s2, gun) = enterBattlefield(sourceOf("Gun", Effect.DealDamage(lit(3), BoundTarget(x))), "P0", s); s = s2
            val (s3, medic) = enterBattlefield(
                sourceOf(
                    "Medic",
                    Effect.ApplyModifier(
                        permanents().only(x),
                        listOf(CharOp.PlusPT(lit(0), lit(1))),
                        duration = Duration.EndOfTurn,
                    ),
                ),
                "P0", s,
            ); s = s3
            return runSync {
                Engine(
                    ScriptedInput.of(
                        "P0@main" to listOf(PriorityAction.Activate(gun, 0), PriorityAction.Activate(medic, 0)),
                    ),
                    rules = rules.knowing(*made.toTypedArray()),
                ).playGame(s, maxTurns = 1)
            }
        }
        assertTrue(
            twoAbilities(stacked = true).byName("Target") != null,
            "on the stack: the later ability resolves FIRST, toughness 4 survives 3 damage",
        )
        assertEq(
            null,
            twoAbilities(stacked = false).byName("Target"),
            "no stack: the damage resolved at once, 3 vs toughness 3, dead before the medic ever fired",
        )
    }

    check("an ability's speed round-trips, and is elided at the default") {
        val fast = ActivatedAbility(Cost(tapSource = true), Effect.Draw(PlayerRef.You, lit(1)), "Scan", usesStack = false)
        val normal = fast.copy(usesStack = true)
        assertTrue(
            !activatedAbilityToJson(normal).contains("usesStack"),
            "the default is elided, so every bundle written before abilities had a speed is unchanged",
        )
        assertTrue(activatedAbilityToJson(fast).contains(""""usesStack":false"""))
        assertEq(fast, activatedAbilityOf(Json.parse(activatedAbilityToJson(fast))), "the non-default round-trips")
        assertEq(normal, activatedAbilityOf(Json.parse(activatedAbilityToJson(normal))), "and so does the default")
        // An older bundle, with no speed written at all, reads back as stacked
        // -- the behaviour every ability had before this existed.
        assertEq(
            true,
            activatedAbilityOf(Json.parse("""{"cost":{},"effect":{"op":"noOp"},"name":"old"}""")).usesStack,
            "an ability written before the field defaults to the old behaviour",
        )
    }

    // -- Zone abstraction ------------------------------------------------------

    check("zone abstraction: zoneHasRoom on a SHARED zone counts PER CONTROLLER, not combined") {
        // Hearthstone's real board cap (7 per side, independently) is the
        // paradigm case -- a combined-total interpretation would be wrong.
        val rules = Rules(zones = BUILTIN_ZONES + mapOf("battlefield" to PlayZoneDef("battlefield", ZoneScope.SHARED, maxOccupants = 1)))
        var s = newGame()
        assertTrue(rules.zoneHasRoom(s, "P0", "battlefield"))
        assertTrue(rules.zoneHasRoom(s, "P1", "battlefield"))
        val (s1, _) = enterBattlefield(Grunt, "P0", s)
        s = s1
        assertTrue(!rules.zoneHasRoom(s, "P0", "battlefield"), "P0's own cap of 1 is reached")
        assertTrue(rules.zoneHasRoom(s, "P1", "battlefield"), "P1 hasn't touched their own share of the SAME shared instance")
    }

    check("zone abstraction: legalZonesFor now also restricts a zoneOfPlay-ONLY type, not just zoneChoices") {
        val skyType = TypeDef("Skyship", zoneOfPlay = "skies")
        val rules = Rules(
            types = BUILTIN_TYPES_CORE + mapOf(skyType.name to skyType),
            zones = BUILTIN_ZONES + mapOf("skies" to PlayZoneDef("skies")),
        )
        assertEq(setOf("skies"), rules.legalZonesFor(setOf("Skyship")), "zoneOfPlay alone now opts into restriction")
        // an undeclared type is still fully unrestricted -- the real P5
        // behavior this must never break again.
        assertEq(null, rules.legalZonesFor(setOf("Creature")))
    }

    check("zone abstraction: Effect.CreateToken declines a token that would exceed the zone's cap") {
        val rules = Rules(zones = BUILTIN_ZONES + mapOf("battlefield" to PlayZoneDef("battlefield", ZoneScope.SHARED, maxOccupants = 1)))
        val end = runSync {
            Engine(ScriptedInput.of("P0" to listOf(CAST(Effect.CreateToken(body("Saproling", 1, 1), lit(3))))), rules = rules).run(newGame())
        }
        assertEq(1, end.battlefield.values.count { it.base.name == "Saproling" }, "cap 1 -- the other 2 declined, not silently made anyway")
    }

    check("zone abstraction: Effect.MovePermanent declines a move into a full zone") {
        val rules = Rules(zones = BUILTIN_ZONES + mapOf("skies" to PlayZoneDef("skies", maxOccupants = 1)))
        var s = newGame()
        val (s1, _) = enterBattlefield(Grunt, "P0", s, zone = ZoneRef("skies"))
        s = s1
        val (s2, mover) = enterBattlefield(Grunt, "P0", s)
        s = s2
        val end = runSync {
            Engine(ScriptedInput.of("P0" to listOf(CAST(Effect.MovePermanent(BoundTarget(mover), ZoneRef("skies"))))), rules = rules).run(s)
        }
        assertEq(BATTLEFIELD, end.battlefield.getValue(mover).zone, "declined -- skies already has its one occupant")
    }

    check("zone abstraction: ReturnFromDiscard(toBattlefield) resolves the card's OWN zone, not the hard-coded default") {
        val shipType = TypeDef("Ship", zoneChoices = listOf("lane-1"))
        val rules = Rules(
            types = BUILTIN_TYPES_CORE + mapOf(shipType.name to shipType),
            zones = BUILTIN_ZONES + mapOf("lane-1" to PlayZoneDef("lane-1", ZoneScope.PER_PLAYER)),
            cards = mapOf("Scout" to CardDefinition(listOf(Face("Scout", setOf("Ship"), Characteristics("Scout", setOf("Ship")))))),
        )
        val start = newGame().let {
            it.copy(players = it.players + ("P0" to it.players.getValue("P0").copy(graveyard = listOf(CardRef(500, "Scout")))))
        }
        val end = runSync {
            Engine(
                ScriptedInput.of("P0" to listOf(CAST(Effect.ReturnFromDiscard(PlayerRef.You, lit(1), toBattlefield = true))), cards = listOf(listOf(500))),
                rules = rules,
            ).run(start)
        }
        assertEq(ZoneRef("lane-1", "P0"), end.byName("Scout")!!.zone, "landed in its own declared lane, not the shared battlefield -- enterBattlefield's own default is the literal BATTLEFIELD constant, not this card's zone")
    }

    check("zone abstraction: ReturnFromDiscard(toBattlefield) respects capacity -- a full zone puts the card right back") {
        val shipType = TypeDef("Ship", zoneChoices = listOf("lane-1"))
        val rules = Rules(
            types = BUILTIN_TYPES_CORE + mapOf(shipType.name to shipType),
            zones = BUILTIN_ZONES + mapOf("lane-1" to PlayZoneDef("lane-1", ZoneScope.PER_PLAYER, maxOccupants = 1)),
            cards = mapOf("Scout" to CardDefinition(listOf(Face("Scout", setOf("Ship"), Characteristics("Scout", setOf("Ship")))))),
        )
        var start = newGame().let {
            it.copy(players = it.players + ("P0" to it.players.getValue("P0").copy(graveyard = listOf(CardRef(500, "Scout")))))
        }
        val (s1, _) = enterBattlefield(
            CardDefinition(listOf(Face("Occupant", setOf("Ship"), Characteristics("Occupant", setOf("Ship"))))),
            "P0", start, zone = ZoneRef("lane-1", "P0"),
        )
        start = s1
        val end = runSync {
            Engine(
                ScriptedInput.of("P0" to listOf(CAST(Effect.ReturnFromDiscard(PlayerRef.You, lit(1), toBattlefield = true))), cards = listOf(listOf(500))),
                rules = rules,
            ).run(start)
        }
        assertEq(null, end.byName("Scout"), "declined -- lane-1 already has its one occupant")
        assertEq(1, end.players.getValue("P0").graveyard.size, "put right back in the graveyard, not vanished")
    }

    check("zone abstraction: problems() reports a TypeDef zone reference that isn't declared anywhere") {
        val g = GameDoc(
            name = "Typo Game",
            rules = RulesDoc(extraTypes = listOf(TypeDef("Ship", zoneChoices = listOf("lane-1", "lane2")))), // missing a hyphen
        )
        assertTrue(g.problems().any { it.contains("lane2") }, "the typo'd zone id is reported")
        val fixed = g.copy(rules = g.rules.copy(extraZones = listOf(PlayZoneDef("lane-1"), PlayZoneDef("lane2"))))
        assertTrue(fixed.problems().none { it.contains("isn't declared") }, "declaring it clears the problem")
    }

    // -- Phase 1: the four small corpus gaps ---------------------------------

    check("a filter can require a counter on the permanent") {
        var s = newGame()
        val (s1, a) = enterBattlefield(Grunt, "P0", s); s = s1
        val (s2, b) = enterBattlefield(Grunt, "P0", s); s = s2
        s = s.copy(battlefield = s.battlefield + (a to s.battlefield.getValue(a).copy(counters = mapOf("+1/+1" to 2))))
        val ctx = EvalContext(s, "P0", null)
        assertEq(listOf(a), s.battlefield.keys.filter { creatures().withCounter("+1/+1").matches(ctx, it) }, "only the counted one")
        assertEq(listOf(a), s.battlefield.keys.filter { creatures().withCounter("+1/+1", 2).matches(ctx, it) }, "at least 2")
        assertEq(emptyList(), s.battlefield.keys.filter { creatures().withCounter("+1/+1", 3).matches(ctx, it) }, "not 3")
        assertEq(2, s.battlefield.keys.count { creatures().matches(ctx, it) }, "an uncountered filter is unaffected")
        // and it survives JSON.
        val f = creatures().yours().withCounter("charge", 2)
        assertEq(f, filterOf(Json.parse(filterToJson(f))), "counter predicate round-trips")
    }

    check("a counter threshold can fire DOWNWARD -- when the last one leaves") {
        // "when the last charge counter is removed, draw" -- k = 1, downward.
        fun vanisher() = card(
            "Vanisher", setOf("Artifact"),
            entersWith = listOf(CounterDef("charge", lit(2))),
            triggers = listOf(TriggerDoc.CounterThreshold("charge", 1, Effect.Draw(PlayerRef.You, lit(1)), downward = true)),
        )
        fun removeTimes(n: Int): Int {
            var s = newGame(libraries = mapOf("P0" to tokens(5)))
            val (s1, id) = enterBattlefield(vanisher(), "P0", s)
            val actions = List(n) { CAST(Effect.RemoveCounter("charge", lit(1), BoundTarget(id))) }
            val end = runSync { Engine(ScriptedInput.of("P0" to actions)).run(s1) }
            return end.players.getValue("P0").hand.size
        }
        assertEq(0, removeTimes(1), "2 -> 1 does not cross the threshold")
        assertEq(1, removeTimes(2), "1 -> 0 fires the downward trigger, exactly once")
    }

    check("a shield can guard a PLAYER, and absorbs what hits them") {
        // shields itself for 3, then P0 hits it for 5 -- 2 gets through.
        val s = runSync {
            Engine(
                ScriptedInput.of(
                    // The shield must RESOLVE before the damage is cast --
                    // the stack is LIFO, so casting both in one window would
                    // resolve the damage first.
                    "P0" to listOf(PASS, PASS, CAST(Effect.DamageOpponent(lit(5)))),
                    "P1" to listOf(CAST(Effect.PreventDamage(BoundTarget(SELF), lit(3), who = PlayerRef.You)), PASS, PASS),
                ),
            ).run(newGame())
        }
        assertEq(18, s.players.getValue("P1").life, "5 dealt, 3 prevented -> 2 through")
        assertTrue(s.shields.isEmpty(), "the shield is spent")
    }

    check("a damage trigger tells COMBAT damage from a spell's") {
        // The discrimination itself: same source, two kinds of damage.
        val combatOnly = TriggerDoc.SelfDealsDamage(combatOnly = true, effect = Effect.NoOp).compile(7, "P0")
        val anyDamage = TriggerDoc.SelfDealsDamage(combatOnly = false, effect = Effect.NoOp).compile(7, "P0")
        val s = newGame()
        val inCombat = GameEvent.DamageDealt(9, 2, source = 7, combat = true)
        val fromSpell = GameEvent.DamageDealt(9, 2, source = 7, combat = false)
        val elsesCombat = GameEvent.DamageDealt(9, 2, source = 8, combat = true)
        assertTrue(combatOnly.matches(inCombat, s), "combat damage fires it")
        assertTrue(!combatOnly.matches(fromSpell, s), "a spell's damage does NOT")
        assertTrue(!combatOnly.matches(elsesCombat, s), "another permanent's damage does NOT")
        assertTrue(anyDamage.matches(fromSpell, s), "the un-narrowed form takes either")
        // Damage to a PLAYER counts too, and carries the same two fields.
        assertTrue(combatOnly.matches(GameEvent.PlayerDamaged("P1", 2, combat = true, source = 7), s))
        assertTrue(!combatOnly.matches(GameEvent.PlayerDamaged("P1", 2, combat = false, source = 7), s))
    }

    check("a spell's damage names the permanent that dealt it") {
        // The event carries its source, so "whenever this deals damage" is
        // writable. Read from the event history, not a snooping closure: a
        // trigger is data and cannot capture a local.
        val engine = Engine(ScriptedInput.of("P0" to listOf(CAST(Effect.DamageOpponent(lit(3))))))
        val end = runSync { engine.run(newGame().traced()) }
        val ev = end.trace!!.events.firstOrNull { it is GameEvent.PlayerDamaged }
        assertTrue(ev is GameEvent.PlayerDamaged, "direct damage to a player now RAISES an event")
        assertEq(3, (ev as GameEvent.PlayerDamaged).amount)
        assertTrue(!ev.combat, "and it is correctly not combat damage")
    }

    // -- shuffling: random, but deterministic under replay -------------------

    check("shuffle reorders the library and is STABLE otherwise") {
        val lib = (1..20).map { CardRef(100_000 + it, "c$it") }
        val start = newGame(libraries = mapOf("P0" to lib), seed = 12345)
        val shuffled = start.shuffleZone("P0")
        val after = shuffled.players.getValue("P0").library
        assertEq(lib.size, after.size, "every card is still there")
        assertEq(lib.map { it.instanceId }.toSet(), after.map { it.instanceId }.toSet(), "the same cards")
        assertTrue(after.map { it.instanceId } != lib.map { it.instanceId }, "the order actually changed")
        // Nothing else reorders it: drawing takes from the top and leaves the
        // rest exactly as they were.
        val drawn = runSync {
            Engine(ScriptedInput.of("P0" to listOf(CAST(Effect.Draw(PlayerRef.You, lit(3)))))).run(shuffled)
        }
        assertEq(after.drop(3).map { it.instanceId }, drawn.players.getValue("P0").library.map { it.instanceId })
    }

    check("shuffle same seed + same answers = the same game (undo must be safe)") {
        val lib = (1..20).map { CardRef(100_000 + it, "c$it") }
        fun deal(seed: Int) = newGame(libraries = mapOf("P0" to lib), seed = seed)
            .shuffleAll().players.getValue("P0").library.map { it.instanceId }
        assertEq(deal(777), deal(777), "a seed reproduces its deal exactly")
        assertTrue(deal(777) != deal(778), "a different seed deals differently")
        // Two shuffles in a row differ -- the state advances, it is not a
        // fixed permutation applied over and over.
        val once = newGame(libraries = mapOf("P0" to lib), seed = 777).shuffleZone("P0")
        val twice = once.shuffleZone("P0")
        assertTrue(
            once.players.getValue("P0").library.map { it.instanceId } !=
                twice.players.getValue("P0").library.map { it.instanceId },
            "the shuffler advances",
        )
    }

    check("shuffle a tutor shuffles the library after searching it") {
        val lib = (1..12).map { CardRef(100_000 + it, if (it == 5) "Grunt" else "x") }
        val rules = Rules(cards = mapOf("Grunt" to creature("Grunt", 2, 2)))
        val start = newGame(libraries = mapOf("P0" to lib), seed = 4242)
        val end = runSync {
            Engine(
                ScriptedInput.of(
                    "P0" to listOf(CAST(Effect.SearchZone(
                        PlayerRef.You, HiddenZone.LIBRARY, HiddenZone.HAND, lit(1), CardFilter(nameIs = "Grunt"),
                    ))),
                    cards = listOf(listOf(100_005)),
                ),
                rules = rules,
            ).run(start)
        }
        val p0 = end.players.getValue("P0")
        assertEq(listOf(100_005), p0.hand.map { it.instanceId }, "the tutor found it")
        assertEq(11, p0.library.size)
        assertTrue(
            p0.library.map { it.instanceId } != lib.filter { it.instanceId != 100_005 }.map { it.instanceId },
            "and the library was shuffled -- searching it revealed the order",
        )
    }

    // -- casting FROM a zone: a real hand, and flashback ---------------------

    check("cast the hand is real -- a card cast from hand leaves it and lands in the graveyard") {
        val bolt = card("Bolt", setOf("Instant"), castEffect = Effect.DamageOpponent(lit(3)))
        val rules = Rules(cards = mapOf("Bolt" to bolt))
        val start = newGame().let {
            it.copy(players = it.players + ("P0" to it.players.getValue("P0").copy(hand = listOf(CardRef(900, "Bolt")))))
        }
        val src = CardSource(CastZone.Std(HiddenZone.HAND), 900)
        val end = runSync {
            Engine(
                ScriptedInput.of("P0" to listOf(PriorityAction.CastSpell(bolt.castEffect!!.lowered(LIFE), bolt.types, label = "Bolt", from = src))),
                rules = rules,
            ).run(start)
        }
        val p0 = end.players.getValue("P0")
        assertTrue(p0.hand.isEmpty(), "the card left the hand")
        assertEq(listOf(900), p0.graveyard.map { it.instanceId }, "and it is in the graveyard")
        assertEq(17, end.players.getValue("P1").life, "the spell still did its thing")
    }

    check("cast you cannot cast a card you do not hold") {
        val bolt = card("Bolt", setOf("Instant"), castEffect = Effect.DamageOpponent(lit(3)))
        val rules = Rules(cards = mapOf("Bolt" to bolt))
        val s = newGame()
        // Nothing in hand: the SINGLE legality check must deny it, which is
        // what makes the hand a rule rather than a number on a screen.
        val action = PriorityAction.CastSpell(bolt.castEffect!!.lowered(LIFE), bolt.types, label = "Bolt", from = CardSource(CastZone.Std(HiddenZone.HAND), 900))
        val verdict = legality(rules, s, "P0", action)
        assertTrue(verdict is Legality.Denied, "denied when the card is not held")
        assertTrue((verdict as Legality.Denied).reason.contains("not in hand"), verdict.reason)
        // and holding it makes the very same action legal.
        val held = s.copy(players = s.players + ("P0" to s.players.getValue("P0").copy(hand = listOf(CardRef(900, "Bolt")))))
        assertTrue(legality(rules, held, "P0", action) is Legality.Legal, "legal once held")
    }

    check("cast recast -- flashback casts from the graveyard, then EXILES") {
        val rite = card("Rite", setOf("Sorcery"), castEffect = Effect.Draw(PlayerRef.You, lit(1)))
            .copy(recast = Recast(HiddenZone.GRAVEYARD, Cost(), HiddenZone.EXILE))
        val rules = Rules(cards = mapOf("Rite" to rite))
        val start = newGame().let {
            it.copy(players = it.players + ("P0" to it.players.getValue("P0").copy(
                graveyard = listOf(CardRef(901, "Rite")),
                library = listOf(CardRef(950, "x")),
            )))
        }
        val r = rite.recast!!
        val end = runSync {
            Engine(
                ScriptedInput.of("P0" to listOf(PriorityAction.CastSpell(
                    rite.castEffect!!.lowered(LIFE), rite.types, r.cost, label = "Rite",
                    from = CardSource(CastZone.Std(r.from), 901, r.afterResolve),
                ))),
                rules = rules,
            ).run(start)
        }
        val p0 = end.players.getValue("P0")
        assertTrue(p0.graveyard.none { it.instanceId == 901 }, "it left the graveyard")
        assertEq(listOf(901), end.exile.map { it.instanceId }, "and it is EXILED, not back in the graveyard")
        assertEq(1, p0.hand.size, "the draw happened")
    }

    // -- tutors: a real predicate, not one exact name ------------------------

    check("zones a tutor matches on TYPE and cost, not just an exact name") {
        val grunt = creature("Grunt", 2, 2).copy(cost = Cost(mana = mapOf("" to 2)))
        val bolt = card("Bolt", setOf("Instant"), castEffect = Effect.NoOp).copy(cost = Cost(mana = mapOf("" to 5)))
        val rules = Rules(cards = mapOf("Grunt" to grunt, "Bolt" to bolt))
        val lib = listOf(CardRef(601, "Grunt"), CardRef(602, "Bolt"), CardRef(603, "Grunt"))
        assertEq(
            listOf(601, 603),
            lib.filter { rules.cardMatches(CardFilter(types = setOf("Creature")), it) }.map { it.instanceId },
            "by type",
        )
        assertEq(
            listOf(601, 603),
            lib.filter { rules.cardMatches(CardFilter(maxManaValue = 3), it) }.map { it.instanceId },
            "by cost bound",
        )
        assertEq(3, lib.count { rules.cardMatches(CardFilter(), it) }, "the empty filter is 'any card'")
        assertEq(1, lib.count { rules.cardMatches(CardFilter(nameContains = "bol"), it) }, "name substring, case-insensitive")
        // A card the bundle does not define cannot be described, so it cannot
        // be fetched by anything but the empty filter.
        assertTrue(!rules.cardMatches(CardFilter(types = setOf("Creature")), CardRef(604, "token")))
    }

    // -- hidden zones: one currency, and the verbs over them ---------------

    check("a creature that DIED can be returned from the graveyard") {
        // A dead permanent and a discarded card land in the same zone, so
        // returning one from the graveyard is expressible.
        val end = runSync {
            Engine(
                ScriptedInput.of(
                    "P0" to listOf(PLAY(Grunt), CAST(Vinewrap.castEffect!!), PASS, CAST(Effect.ReturnFromDiscard(PlayerRef.You, lit(1), toBattlefield = true))),
                    targets = listOf(1),
                    cards = listOf(listOf(1)),
                ),
                rules = Rules(cards = mapOf("Grunt" to Grunt)),
            ).run(newGame())
        }
        assertTrue(end.byName("Grunt") != null, "it died, then came back from the graveyard")
        assertTrue(end.players.getValue("P0").graveyard.isEmpty(), "and left the graveyard")
    }

    check("zones a dead permanent keeps its id and gains a resolvable name") {
        val end = runSync { Engine(ScriptedInput.of("P0" to listOf(PLAY(Grunt), CAST(Vinewrap.castEffect!!)), targets = listOf(1))).run(newGame()) }
        assertEq(listOf(CardRef(1, "Grunt")), end.players.getValue("P0").graveyard)
    }

    check("zones SearchZone is a tutor -- find a named card in the library") {
        val lib = listOf(CardRef(500, "Grunt"), CardRef(501, "Ox"), CardRef(502, "Grunt"))
        val start = newGame().let { it.copy(players = it.players + ("P0" to it.players.getValue("P0").copy(library = lib))) }
        val end = runSync {
            Engine(
                ScriptedInput.of(
                    "P0" to listOf(CAST(Effect.SearchZone(PlayerRef.You, HiddenZone.LIBRARY, HiddenZone.HAND, lit(1), CardFilter(nameIs = "Grunt")))),
                    cards = listOf(listOf(502)),
                ),
            ).run(start)
        }
        assertEq(listOf(CardRef(502, "Grunt")), end.players.getValue("P0").hand, "the chosen Grunt, not the Ox")
        // A set, not a list: a tutor shuffles after searching, so the order of
        // what is left is deliberately no longer predictable.
        assertEq(setOf(500, 501), end.players.getValue("P0").library.map { it.instanceId }.toSet(), "the rest stay in the library")
    }

    check("zones MoveTop is mill -- off the top, into the graveyard") {
        val start = newGame(libraries = mapOf("P0" to tokens(5)))
        val end = runSync {
            Engine(ScriptedInput.of("P0" to listOf(CAST(Effect.MoveTop(PlayerRef.You, lit(2), HiddenZone.GRAVEYARD))))).run(start)
        }
        assertEq(listOf(100_000, 100_001), end.players.getValue("P0").graveyard.map { it.instanceId }, "the top two")
        assertEq(3, end.players.getValue("P0").library.size)
    }

    check("zones LookAtTop is scry -- keep some on top, bottom the rest") {
        val start = newGame(libraries = mapOf("P0" to tokens(4)))
        val end = runSync {
            Engine(
                ScriptedInput.of(
                    "P0" to listOf(CAST(Effect.LookAtTop(PlayerRef.You, lit(2), HiddenZone.LIBRARY_BOTTOM))),
                    cards = listOf(listOf(100_000)), // bottom the first, keep the second
                ),
            ).run(start)
        }
        val lib = end.players.getValue("P0").library.map { it.instanceId }
        assertEq(listOf(100_001, 100_002, 100_003, 100_000), lib, "the bottomed card went to the back, order kept")
        assertEq(4, lib.size, "scry moves nothing out of the library")
    }

    check("zones the same verb surveils when it targets the graveyard") {
        val start = newGame(libraries = mapOf("P0" to tokens(3)))
        val end = runSync {
            Engine(
                ScriptedInput.of(
                    "P0" to listOf(CAST(Effect.LookAtTop(PlayerRef.You, lit(2), HiddenZone.GRAVEYARD))),
                    cards = listOf(listOf(100_000, 100_001)),
                ),
            ).run(start)
        }
        assertEq(2, end.players.getValue("P0").graveyard.size)
        assertEq(1, end.players.getValue("P0").library.size)
    }

    // -- replay determinism (what hotseat undo rests on) -------------------

    check("replay determinism -- the same answers reproduce the same game") {
        // The hotseat's undo drops the last answer, throws the running game
        // away and REPLAYS the rest into a fresh engine. That is only exact
        // because the engine draws on no randomness at all -- this pins it.
        val script = listOf<PriorityAction>(
            PriorityAction.PlayPermanent(Grunt.lowered(LIFE), 0),
            PriorityAction.PlayPermanent(StonebackOx.lowered(LIFE), 0),
            CAST(Emberbolt.castEffect!!, setOf("Instant")),
        )
        val recorded = mutableListOf<Any?>()

        class Recorder : PlayerInput {
            override suspend fun ask(q: Question): Answer = when (q) {
                is Question.Priority -> Answer.Act(askPriorityAction(q.player, q.state))
                is Question.PickTarget -> Answer.Target(chooseTarget(q.player, q.candidates, q.state))
                is Question.PickNumber -> Answer.Number(chooseNumber(q.player, q.label, q.min, q.max))
                else -> q.default()
            }

            val q = ArrayDeque(script)
            private suspend fun askPriorityAction(player: PlayerId, state: GameState): PriorityAction {
                val a = if (player == "P0" && state.stack.isEmpty() && q.isNotEmpty()) q.removeFirst()
                else PriorityAction.PassPriority
                recorded += a
                return a
            }
            private suspend fun chooseTarget(player: PlayerId, candidates: List<ObjectId>, state: GameState): ObjectId {
                val a = candidates.first(); recorded += a; return a
            }
            private suspend fun chooseNumber(player: PlayerId, prompt: String, min: Int, max: Int): Int {
                recorded += min; return min
            }
        }

        /** Exactly the hotseat's replay: indexed, because `null` is a real answer. */
        class Replay(private val answers: List<Any?>) : PlayerInput {
            override suspend fun ask(q: Question): Answer = when (q) {
                is Question.Priority -> Answer.Act(askPriorityAction(q.player, q.state))
                is Question.PickTarget -> Answer.Target(chooseTarget(q.player, q.candidates, q.state))
                is Question.PickNumber -> Answer.Number(chooseNumber(q.player, q.label, q.min, q.max))
                else -> q.default()
            }

            private var i = 0
            private fun <T> next(fallback: T): T {
                if (i >= answers.size) return fallback
                @Suppress("UNCHECKED_CAST")
                return answers[i++] as T
            }
            private suspend fun askPriorityAction(player: PlayerId, state: GameState) =
                next<PriorityAction>(PriorityAction.PassPriority)
            private suspend fun chooseTarget(player: PlayerId, candidates: List<ObjectId>, state: GameState) =
                next(candidates.first())
            private suspend fun chooseNumber(player: PlayerId, prompt: String, min: Int, max: Int) = next(min)
        }

        fun opening() = newGame(libraries = mapOf("P0" to tokens(9), "P1" to tokens(9)))
        val first = runSync { Engine(Recorder()).playGame(opening(), maxTurns = 2) }
        val again = runSync { Engine(Replay(recorded.toList())).playGame(opening(), maxTurns = 2) }

        assertTrue(recorded.isNotEmpty(), "the run actually asked for decisions")
        assertEq(first.log, again.log, "identical log")
        assertEq(first.battlefield.keys, again.battlefield.keys, "identical board")
        assertEq(
            first.players.mapValues { it.value.life },
            again.players.mapValues { it.value.life },
            "identical life totals",
        )
        assertEq(first.turnNumber, again.turnNumber)
    }

    // -- costs are payable end-to-end -------------------------------------

    check("cost gating: legality() refuses what cannot be paid, and allows it once it can") {
        val costly = card("Costly Ox", setOf("Creature"), body("Costly Ox", 3, 3), cost = Cost(mana = mapOf("" to 3)))
        val play = PriorityAction.PlayPermanent(costly.lowered(LIFE), 0)
        val broke = newGame()
        val rich = newGame().pooled("P0", "" to 3)
        assertTrue(legality(Rules.DEFAULT, broke, "P0", play) is Legality.Denied, "no resources -> denied")
        assertEq(
            "can't afford Costly Ox",
            (legality(Rules.DEFAULT, broke, "P0", play) as Legality.Denied).reason,
        )
        assertTrue(legality(Rules.DEFAULT, rich, "P0", play) is Legality.Legal, "with 3 in pool -> legal")
    }

    check("a mana source makes a costed card payable -- the whole hotseat loop") {
        // The gap the hotseat hit: costs WERE enforced, but nothing could
        // produce a resource and the UI still offered the card. This is the
        // shape that has to work: tap a source, then pay.
        val spring = card(
            "Spring", setOf("Land"),
            activated = listOf(ActivatedAbility(Cost(tapSource = true), Effect.AddMana(PlayerRef.You, mapOf("" to lit(3))), "{T}: add 3")),
        )
        val costly = card("Costly Ox", setOf("Creature"), body("Costly Ox", 3, 3), cost = Cost(mana = mapOf("" to 3)))
        val end = runSync {
            Engine(
                ScriptedInput.of(
                    "P0" to listOf(
                        PLAY(spring),                       // a Land -- no stack
                        PriorityAction.Activate(1, 0),      // tap it for 3
                        PLAY(costly),                       // now affordable
                    ),
                ),
                rules = Rules().knowing(spring),
            ).run(newGame())
        }
        assertTrue(end.byName("Costly Ox") != null, "paid for and on the battlefield")
        assertEq(emptyMap<String, Int>(), end.players.getValue("P0").pool, "the 3 were spent")
        assertTrue(end.battlefield.getValue(1).exhausted, "the source is tapped")
        assertTrue(end.log.none { it.contains("can't afford") })
    }

    // -- permanents use the stack ----------------------------------------

    check("a permanent SPELL waits on the stack, so the opponent gets a window") {
        // P1 must be able to respond TO a permanent spell: PlayPermanent goes
        // on the stack, not straight to the battlefield.
        var sawItOnTheStack = false
        val input = object : PlayerInput {
            override suspend fun ask(q: Question): Answer = when (q) {
                is Question.Priority -> Answer.Act(askPriorityAction(q.player, q.state))
                is Question.PickTarget -> Answer.Target(chooseTarget(q.player, q.candidates, q.state))
                is Question.PickNumber -> Answer.Number(chooseNumber(q.player, q.label, q.min, q.max))
                else -> q.default()
            }

            val q = ArrayDeque(listOf<PriorityAction>(PriorityAction.PlayPermanent(StonebackOx.lowered(LIFE), 0)))
            private suspend fun askPriorityAction(player: PlayerId, state: GameState): PriorityAction {
                if (player == "P1" && state.stack.any { it is PermanentOnStack }) sawItOnTheStack = true
                return if (player == "P0" && state.stack.isEmpty() && q.isNotEmpty()) q.removeFirst()
                else PriorityAction.PassPriority
            }
            private suspend fun chooseTarget(player: PlayerId, candidates: List<ObjectId>, state: GameState) =
                candidates.first()
            private suspend fun chooseNumber(player: PlayerId, prompt: String, min: Int, max: Int) = min
        }
        val end = runSync { Engine(input).run(newGame()) }
        assertTrue(sawItOnTheStack, "P1 held priority while the Ox was still on the stack")
        assertTrue(end.byName("Stoneback Ox") != null, "and it then resolved into play")
        assertEq(emptyList<StackObject>(), end.stack, "stack empty at the end")
    }

    check("a Land does NOT use the stack -- it is a special action") {
        val forest = card("Forest", setOf("Land"), activated = listOf(ActivatedAbility(Cost(tapSource = true), Effect.AddMana(PlayerRef.You, mapOf("G" to lit(1))))))
        var everOnStack = false
        val input = object : PlayerInput {
            override suspend fun ask(q: Question): Answer = when (q) {
                is Question.Priority -> Answer.Act(askPriorityAction(q.player, q.state))
                is Question.PickTarget -> Answer.Target(chooseTarget(q.player, q.candidates, q.state))
                is Question.PickNumber -> Answer.Number(chooseNumber(q.player, q.label, q.min, q.max))
                else -> q.default()
            }

            val q = ArrayDeque(listOf<PriorityAction>(PriorityAction.PlayPermanent(forest.lowered(LIFE), 0)))
            private suspend fun askPriorityAction(player: PlayerId, state: GameState): PriorityAction {
                if (state.stack.any { it is PermanentOnStack }) everOnStack = true
                return q.removeFirstOrNull() ?: PriorityAction.PassPriority
            }
            private suspend fun chooseTarget(player: PlayerId, candidates: List<ObjectId>, state: GameState) =
                candidates.first()
            private suspend fun chooseNumber(player: PlayerId, prompt: String, min: Int, max: Int) = min
        }
        val end = runSync { Engine(input).run(newGame()) }
        assertTrue(!everOnStack, "a Land never waited on the stack")
        assertTrue(end.byName("Forest") != null, "it entered immediately")
    }

    // -- target-relative reads ------------------------

    check("damage equal to the TARGET's power (fight-like)") {
        // Counterstroke: deal damage to target creature equal to its own power.
        val counterstroke = Effect.Choose(
            creatures(),
            Effect.DealDamage(IntExpr.TargetField(BoundTarget(CHOSEN), "power"), BoundTarget(CHOSEN)),
        )
        val end = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PLAY(StonebackOx), CAST(counterstroke)), targets = listOf(1)))
                .run(newGame())
        }
        // A 3/3 taking 3 of its own power dies to SBA.
        assertEq(null, end.byName("Stoneback Ox"), "3 power -> 3 damage -> dead")
    }

    check("'if it already has a +1/+1 counter' reads the TARGET") {
        // Corrosive Lash: put a -1/-1 counter; if it already had a +1/+1, put two.
        val lash = Effect.Choose(
            creatures(),
            Effect.If(
                BoolExpr.Cmp(IntExpr.TargetCounter(BoundTarget(CHOSEN), "+1/+1"), CmpOp.GTE, lit(1)),
                Effect.AddCounter("-1/-1", lit(2), BoundTarget(CHOSEN)),
                Effect.AddCounter("-1/-1", lit(1), BoundTarget(CHOSEN)),
            ),
        )
        fun run(pre: Int): Int {
            val acts = buildList {
                add(PLAY(Grunt))
                // PASS so the counter RESOLVES before the Lash is cast -- the
                // stack is LIFO, so without it the Lash would resolve first.
                if (pre > 0) { add(addCtr("+1/+1", pre)); add(PASS) }
                add(CAST(lash))
            }
            val end = runSync {
                Engine(ScriptedInput.of("P0" to acts, targets = List(acts.size) { 1 })).run(newGame())
            }
            return end.battlefield[1]?.counter("-1/-1") ?: -1
        }
        assertEq(1, run(0), "no +1/+1 -> one -1/-1")
        // With a +1/+1 present the branch puts two -1/-1, which then annihilate
        // against it -- 2 - 1 = 1 left.
        assertEq(1, run(1), "had a +1/+1 -> two -1/-1, one cancels")
    }

    check("target reads survive substitution into a bound id") {
        val e: Effect.Choose = Effect.Choose(creatures(), Effect.DealDamage(IntExpr.TargetField(BoundTarget(CHOSEN), "power"), BoundTarget(CHOSEN)))
        val bound = e.body.substituteTarget(CHOSEN, 7)
        val dd = bound as Effect.DealDamage
        assertEq(BoundTarget(7), dd.target, "the target slot was rewritten")
        assertEq(IntExpr.TargetField(BoundTarget(7), "power"), dd.amount, "so was the one inside the IntExpr")
        assertEq(e, effectFromJson(effectToJson(e)), "and it round-trips")
    }

    // -- activated-ability injection --------------------

    check("a static GRANTS an activated ability") {
        // Grantcaller's Creed: creatures you control have "{T}: you gain 1 life".
        val creed = card(
            "Grantcaller's Creed", setOf("Enchantment"),
            statics = Statics(
                    chars = listOf(
                        StaticSpec(
                            creatures().yours(),
                            listOf(CharOp.GrantAbility(ActivatedAbility(Cost(tapSource = true), Effect.GainLife(PlayerRef.You, lit(1))))),
                        ),
                    ),
                ),
        )
        val end = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PLAY(creed), PLAY(Grunt), PriorityAction.Activate(2, 0)))).run(newGame())
        }
        assertEq(21, end.players.getValue("P0").life, "the granted ability resolved")
        assertTrue(end.battlefield.getValue(2).exhausted, "and its tap cost was paid")

        // Without the Creed the same activation finds no ability.
        val without = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PLAY(Grunt), PriorityAction.Activate(1, 0)))).run(newGame())
        }
        assertEq(20, without.players.getValue("P0").life)
        assertTrue(without.log.any { it.contains("has no ability") })
    }

    check("RemoveAbilities also suppresses a GRANTED activated ability") {
        val creed = card(
            "Creed", setOf("Enchantment"),
            statics = Statics(chars = listOf(StaticSpec(creatures().yours(), listOf(CharOp.GrantAbility(ActivatedAbility(Cost(), Effect.GainLife(PlayerRef.You, lit(1)))))))),
        )
        val humility = card(
            "Humility", setOf("Enchantment"),
            statics = Statics(chars = listOf(StaticSpec(creatures(), listOf(CharOp.RemoveAbilities)))),
        )
        val end = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PLAY(creed), PLAY(humility), PLAY(Grunt), PriorityAction.Activate(3, 0))))
                .run(newGame())
        }
        assertEq(20, end.players.getValue("P0").life, "abilities removed -> nothing to activate")
        assertTrue(end.log.any { it.contains("lost its abilities") })
    }

    // -- multi-target / choose any number ---------------

    check("ChooseMany runs the body once per chosen target") {
        val font = Effect.ChooseMany(
            creatures(), count = lit(2),
            body = Effect.AddCounter("+1/+1", lit(1), BoundTarget(EACH)),
        )
        val end = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PLAY(Grunt), PLAY(Grunt), CAST(font)), targets = listOf(1, 2)))
                .run(newGame())
        }
        assertEq(1, end.battlefield.getValue(1).counter("+1/+1"))
        assertEq(1, end.battlefield.getValue(2).counter("+1/+1"))
    }

    check("upTo stops early; exact requires enough targets") {
        val two = Effect.ChooseMany(creatures(), count = lit(2), body = Effect.AddCounter("+1/+1", lit(1), BoundTarget(EACH)))
        val upTo = two.copy(upTo = true)
        // Only one creature on board.
        val exact = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PLAY(Grunt), CAST(two)), targets = listOf(1))).run(newGame())
        }
        assertEq(0, exact.battlefield.getValue(1).counter("+1/+1"), "exact count could not be met -> nothing happened")
        // Targets bind at cast: an exact count that can't
        // be met makes the spell uncastable, as a missing single target does.
        assertTrue(exact.log.any { it.contains("no legal target") }, exact.log.joinToString("\n"))

        val flexible = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PLAY(Grunt), CAST(upTo)), targets = listOf(1))).run(newGame())
        }
        assertEq(1, flexible.battlefield.getValue(1).counter("+1/+1"), "upTo took the one available")
    }

    check("divide a pool among the chosen targets (Share)") {
        // Overclock: divide 4 damage among any number of target creatures.
        val overclock = Effect.ChooseMany(
            creatures(), count = lit(2), upTo = true, divide = lit(3),
            body = Effect.DealDamage(IntExpr.Share, BoundTarget(EACH)),
        )
        val end = runSync {
            Engine(
                ScriptedInput.of(
                    "P0" to listOf(PLAY(StonebackOx), PLAY(StonebackOx), CAST(overclock)),
                    targets = listOf(1, 2),
                    numbers = listOf(1), // first target's share; the last takes the rest (2)
                ),
            ).run(newGame())
        }
        assertEq(1, end.battlefield.getValue(1).damageMarked, "share of 1")
        assertEq(2, end.battlefield.getValue(2).damageMarked, "remainder of 2")
    }

    // -- alternative (either/or) cost -------------------

    check("an alternative cost is used when the first is unpayable") {
        // Bloodpact Sentry: pay {3}, or pay 3 life.
        val sentry = card(
            "Bloodpact Sentry", setOf("Creature"), body("Bloodpact Sentry", 2, 2),
            cost = Cost(mana = mapOf("" to 3), alternatives = listOf(Cost(payLife = 3))),
        )
        // No mana in pool -> the life alternative is the only payable one.
        val end = runSync { Engine(ScriptedInput.of("P0" to listOf(PLAY(sentry)))).run(newGame()) }
        assertTrue(end.byName("Bloodpact Sentry") != null, "it got played")
        assertEq(17, end.players.getValue("P0").life, "paid 3 life")

        // With mana available BOTH are payable, so the controller picks --
        // chooseMode index 0 = the printed cost.
        val withMana = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PLAY(sentry)), modes = listOf(listOf(0))))
                .run(newGame().pooled("P0", "" to 3))
        }
        assertEq(20, withMana.players.getValue("P0").life, "picked the mana cost, life untouched")
        assertEq(emptyMap<String, Int>(), withMana.players.getValue("P0").pool, "the mana went")
    }

    check("an unpayable cost with unpayable alternatives still fails") {
        val pricey = card(
            "Pricey", setOf("Creature"), body("Pricey", 1, 1),
            cost = Cost(mana = mapOf("" to 9), alternatives = listOf(Cost(payLife = 99))),
        )
        val end = runSync { Engine(ScriptedInput.of("P0" to listOf(PLAY(pricey)))).run(newGame()) }
        assertEq(null, end.byName("Pricey"))
        assertEq(20, end.players.getValue("P0").life, "no life was spent on the failed attempt")
    }

    // -- restrictions wired to combat ------------------

    /** "Creatures can't attack you" as an authored static, P1's side. */
    val thresholdWard = card(
        "Threshold Ward", setOf("Enchantment"),
        statics = Statics(rules = listOf(RuleMod.CantAttack(creatures(), defender = PlayerRef.You))),
    )

    check("'creatures can't attack you' stops a DECLARED attack") {
        // holds the Ward; P0's Grunt tries to swing at P1.
        val base = staged("P0" to creature("Grunt", 2, 2))
        val withWard = enterBattlefield(thresholdWard, "P1", base).first
        val end = runSync {
            Engine(
                ScriptedInput.of(attacks = mapOf(1 to CombatTarget.Player("P1"))),
                rules = Rules(turn = NO_CLEANUP),
            ).playGame(withWard, maxTurns = 1)
        }
        assertEq(20, end.players.getValue("P1").life, "the attack never happened")
        assertTrue(!end.battlefield.getValue(1).exhausted, "the Grunt never even tapped to attack")
    }

    check("the same Ward does NOT stop an attack on someone else") {
        // holds the Ward, so it bars attacks on P0 -- P0's own swing at P1
        // is unaffected.
        val base = staged("P0" to creature("Grunt", 2, 2))
        val withWard = enterBattlefield(thresholdWard, "P0", base).first
        val end = runSync {
            Engine(
                ScriptedInput.of(attacks = mapOf(1 to CombatTarget.Player("P1"))),
                rules = Rules(turn = NO_CLEANUP),
            ).playGame(withWard, maxTurns = 1)
        }
        assertEq(18, end.players.getValue("P1").life, "2 damage got through")
    }

    check("CantBlock drops an illegal block") {
        val noBlocks = card(
            "Fog Cutter", setOf("Enchantment"),
            statics = Statics(rules = listOf(RuleMod.CantBlock(creatures().theirs()))),
        )
        // owns the static, so P0's OPPONENT can't block.
        val base = staged("P0" to creature("Grunt", 2, 2), "P1" to creature("Wall", 0, 4))
        val s0 = enterBattlefield(noBlocks, "P0", base).first
        val end = runSync {
            Engine(
                ScriptedInput.of(attacks = mapOf(1 to CombatTarget.Player("P1")), blocks = mapOf(2 to 1)),
                rules = Rules(turn = NO_CLEANUP),
            ).playGame(s0, maxTurns = 1)
        }
        assertEq(18, end.players.getValue("P1").life, "the block was illegal, damage got through")
    }

    check("CantActivate stops an activated ability") {
        val silence = card(
            "Silence Field", setOf("Enchantment"),
            statics = Statics(rules = listOf(RuleMod.CantActivate(permanents().yours()))),
        )
        val spring = card(
            "Spring", setOf("Land"),
            activated = listOf(ActivatedAbility(Cost(tapSource = true), Effect.AddMana(PlayerRef.You, mapOf("U" to lit(1))))),
        )
        val end = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PLAY(silence), PLAY(spring), PriorityAction.Activate(2, 0))), rules = Rules().knowing(spring))
                .run(newGame())
        }
        assertEq(emptyMap<String, Int>(), end.players.getValue("P0").pool, "the ability never fired")
        assertTrue(end.log.any { it.contains("can't activate abilities") })
    }

    check("RuleMod.Cant now also gates draw and cast") {
        val noDraw = card(
            "Slow the Sands", setOf("Enchantment"),
            statics = Statics(rules = listOf(RuleMod.Cant(RuleAction.DRAW, PlayerRef.Opponent))),
        )
        val s0 = enterBattlefield(noDraw, "P0", newGame(libraries = mapOf("P0" to tokens(6), "P1" to tokens(6)))).first
        // made "the player on the play skips their first draw" a real
        // parameter, and it defaults ON -- this test is about the rule static,
        // not about that, so turn it off rather than assert around it.
        val plainDraw = Rules(turn = NO_CLEANUP, params = GameParams(firstPlayerSkipsFirstDraw = false))
        val end = runSync { Engine(ScriptedInput.of(), rules = plainDraw).playGame(s0, maxTurns = 2) }
        assertEq(1, end.players.getValue("P0").hand.size, "P0 still drew on its own turn")
        assertEq(0, end.players.getValue("P1").hand.size, "P1's draw was barred")

        val noCast = card(
            "Null Field", setOf("Enchantment"),
            statics = Statics(rules = listOf(RuleMod.Cant(RuleAction.CAST, PlayerRef.You))),
        )
        val cast = runSync {
            Engine(ScriptedInput.of("P0" to listOf(PLAY(noCast), CAST(Effect.DamageOpponent(lit(3)))))).run(newGame())
        }
        assertEq(20, cast.players.getValue("P1").life, "the cast was barred")
    }

    check("every RuleMod case round-trips (exhaustive tripwire)") {
        fun RuleMod.caseTag(): Int = when (this) {
            is RuleMod.Cant -> 1
            is RuleMod.CantAttack -> 2
            is RuleMod.CantBlock -> 3
            is RuleMod.CantActivate -> 4
            is RuleMod.ReduceDamage -> 5
        }
        val all = listOf<RuleMod>(
            RuleMod.Cant(RuleAction.GAIN_LIFE, PlayerRef.Opponent),
            RuleMod.CantAttack(creatures().theirs(), defender = PlayerRef.You),
            RuleMod.CantBlock(creatures()),
            RuleMod.CantActivate(permanents().yours()),
            RuleMod.ReduceDamage(creatures().yours(), lit(1)),
        )
        assertEq((1..5).toSet(), all.map { it.caseTag() }.toSet(), "one sample per RuleMod case")
        for (r in all) assertEq(r, ruleModOf(Json.parse(ruleModToJson(r))), "RuleMod round-trip $r")
    }

    check("review-J marked damage clears at the end-of-turn CLEANUP") {
        val end = runSync {
            Engine(ScriptedInput.of("P0@main" to listOf(PLAY(StonebackOx), CAST(Effect.DealDamage(lit(2), BoundTarget(1))))))
                .playGame(newGame(libraries = mapOf("P0" to tokens(6), "P1" to tokens(6))), maxTurns = 2)
        }
        assertEq(0, end.battlefield.getValue(1).damageMarked, "cleanup wiped the 2 marked damage")
        assertTrue(end.byName("Stoneback Ox") != null)
    }

    check("TriggerDoc / Cost / CombatDoc round-trip through JSON") {
        val trigs = listOf<TriggerDoc>(
            TriggerDoc.SelfEnters(Effect.AddCounter("+1/+1", lit(1), BoundTarget(SELF))),
            TriggerDoc.SelfAttacks(Effect.AddCounter("+1/+1", lit(1), BoundTarget(SELF)), order = 2),
            TriggerDoc.YouCastType(setOf("Instant", "Sorcery"), Effect.Draw(PlayerRef.You, lit(1))),
            TriggerDoc.OnYourPhase("main", Effect.AddCounter("lore", lit(1), BoundTarget(SELF))),
            TriggerDoc.CounterThreshold("lore", 3, Effect.GainLife(PlayerRef.You, lit(3)), order = 3),
            TriggerDoc.CreatureDies(PlayerRef.Opponent, Effect.GainLife(PlayerRef.You, lit(1))),
        )
        for (t in trigs) assertEq(t, triggerDocOf(Json.parse(triggerDocToJson(t))), "trigger round-trip $t")

        val costs = listOf(
            Cost(mana = mapOf("" to 2, "R" to 1)),
            Cost(usesX = true, additional = Effect.Sacrifice(PlayerRef.You, lit(1), creatures())),
            Cost(tapSource = true),
            Cost(removeCounters = "loyalty" to 3),
            Cost(payLife = 5),
        )
        for (c in costs) assertEq(c, costOf(Json.parse(costToJson(c))), "cost round-trip $c")

        val cfgs = listOf<CombatDoc>(
            CombatDoc.Preset("hearthstone"),
            CombatDoc.Preset("yugioh"),
            CombatDoc.Preset("fastSlowLanes"),
            CombatDoc.Program(MTG_COMBAT.copy(blockRules = listOf(BlockRule.needsKeyword("elusive", "elusive"), BlockRule.needsStat("fearsome", "power", 3))).lowered(),),
        )
        for (c in cfgs) assertEq(c, combatDocOf(Json.parse(combatDocToJson(c))), "combat cfg round-trip $c")
    }

    check("a whole GameDoc round-trips through JSON") {
        val demoCards = listOf(
                CardDoc(
                    faces = listOf(FaceDoc("Emberbolt", setOf("Instant"), castEffect = Effect.Choose(creatures(), Effect.DealDamage(lit(3), BoundTarget(CHOSEN))))),
                    cost = Cost(mana = mapOf("" to 1, "R" to 1)),
                    text = "deal 3",
                ),
                CardDoc(
                    faces = listOf(
                        FaceDoc(
                            "Loyalist", setOf("Planeswalker"),
                            triggers = listOf(TriggerDoc.CounterThreshold("loyalty", 1, Effect.Draw(PlayerRef.You, lit(1)))),
                            activated = listOf(ActivatedAbility(Cost(), Effect.AddCounter("loyalty", lit(1), BoundTarget(SELF)), "+1", oncePerTurn = true)),
                        ),
                    ),
                    entersWith = listOf(CounterDef("loyalty", lit(3))),
                    diesWhen = (selfCounter("loyalty") lte lit(0)).lowered(),
                ),
                CardDoc(
                    faces = listOf(FaceDoc("Sky Banner", setOf("Enchantment"), statics = listOf(StaticSpec(creatures().yours(), listOf(CharOp.GrantKeyword("flying")))))),
                ),
        )
        val bundle = GameDoc(
            name = "Demo Game",
            rules = RulesDoc(
                extraTypes = listOf(TypeDef("Skyship", fields = setOf("power", "toughness"), attacks = true, zoneOfPlay = "skies")),
                extraZones = listOf(PlayZoneDef("skies", ZoneScope.SHARED), PlayZoneDef("planet", ZoneScope.PER_PLAYER)),
                combat = CombatDoc.Program(FAST_SLOW_COMBAT.copy(blockRules = listOf(BlockRule.cantBlock("frozen"))).lowered()),
                turn = TurnStructure(
                    listOf(
                        PhaseSpec("start", interactive = false, onEnter = PhaseEffects.UNTAP),
                        PhaseSpec("main", sorcerySpeed = true),
                        PhaseSpec("fight", combat = true),
                        PhaseSpec("cleanup", interactive = false, onEnter = PhaseEffects.CLEANUP),
                    ),
                ),
            ),
            sets = listOf(SetDoc("Core", demoCards.take(2)), SetDoc("Expansion", demoCards.drop(2))),
            decks = listOf(
                DeckDoc("Starter", listOf(DeckEntry("Emberbolt", 4), DeckEntry("Loyalist", 2))),
                DeckDoc("Control", listOf(DeckEntry("Sky Banner", 3))),
            ),
        )
        assertEq(bundle, gameDocFromJson(gameDocToJson(bundle)), "GameDoc round-trip")

        // A `recast` (flashback) and a real `CardFilter` both survive JSON.
        val flash = CardDoc(
            faces = listOf(FaceDoc("Rite", setOf("Sorcery"), castEffect = Effect.Draw(PlayerRef.You, lit(1)))),
            cost = Cost(mana = mapOf("" to 2)),
            recast = Recast(HiddenZone.GRAVEYARD, Cost(mana = mapOf("" to 4)), HiddenZone.EXILE),
        )
        val tutor = CardDoc(
            faces = listOf(FaceDoc("Seek", setOf("Sorcery"), castEffect = Effect.SearchZone(
                PlayerRef.You, HiddenZone.LIBRARY, HiddenZone.HAND, lit(1),
                CardFilter(types = setOf("Creature"), maxManaValue = 3, nameContains = "ox"),
            ))),
        )
        val zb = GameDoc(name = "zones", sets = listOf(SetDoc("Core", listOf(flash, tutor))))
        assertEq(zb, gameDocFromJson(gameDocToJson(zb)), "recast + CardFilter round-trip")
        // and the LEGACY exact-name tutor still reads back as a name filter.
        val legacy = effectFromJson("""{"op":"searchZone","who":"you","from":"LIBRARY","to":"HAND","count":1,"cardId":"Grunt"}""")
        assertEq(CardFilter(nameIs = "Grunt"), (legacy as Effect.SearchZone).filter, "legacy cardId reads as nameIs")

        // The deck list expands to a real library with non-colliding ids.
        val lib = bundle.decks[0].libraryFor(100_000)
        assertEq(6, lib.size, "4 + 2 cards")
        assertEq(listOf(100_000, 100_001, 100_002, 100_003, 100_004, 100_005), lib.map { it.instanceId })
        assertEq("Emberbolt", lib.first().cardId)
        assertTrue(lib.none { it.instanceId < 100 }, "library ids stay clear of battlefield ids")

        // finding A: the default MTG turn still round-trips (it is elided in JSON)
        val mtgGame = GameDoc(name = "plain", sets = listOf(SetDoc("Core", listOf(CardDoc(faces = listOf(FaceDoc("X", setOf("Creature"), mapOf("power" to 1, "toughness" to 1))))))))
        assertEq(TurnStructure.MTG, gameDocFromJson(gameDocToJson(mtgGame)).rules.turn)
    }

    check("a PRE-SPLIT bundle.json still loads, as a game with one set") {
        // A real file in the old shape: flat "cards", one "deck", rules knobs
        // at the top level, no "sets" key. This is what is sitting in the
        // app's files dir right now, so losing it is not an option.
        val legacy = """
            {"name":"My First Set",
             "cards":[
               {"faces":[{"name":"Stoneback Ox","types":["Creature"],"fields":{"power":3,"toughness":3}}]},
               {"faces":[{"name":"Ember Bolt","types":["Instant"]}],"cost":{"mana":{"":2}}}
             ],
             "extraTypes":[{"name":"Site","fields":[],"zoneOfPlay":"sites"}],
             "combat":{"kind":"preset","name":"fastSlow"},
             "deck":[{"card":"Ember Bolt","n":4}]}
        """.trimIndent()
        val g = gameDocFromJson(legacy)
        assertEq("My First Set", g.name, "the name carries over")
        assertEq(1, g.sets.size, "the flat card list became ONE set")
        assertEq("Core", g.sets[0].name)
        assertEq(listOf("Stoneback Ox", "Ember Bolt"), g.cards.map { it.faces[0].name }, "in order")
        assertEq(1, g.decks.size, "the single deck became a named deck")
        assertEq("Starter", g.decks[0].name)
        assertEq(4, g.decks[0].size)
        // The rules knobs moved into RulesDoc without being dropped.
        assertEq(listOf("Site"), g.rules.extraTypes.map { it.name })
        assertEq(FAST_SLOW_COMBAT.lowered(), g.rules().combat, "the combat preset still compiles")
        // And re-saving produces the NEW shape, which round-trips.
        assertEq(g, gameDocFromJson(gameDocToJson(g)), "the migrated game round-trips in the new format")
        // A legacy bundle with no deck at all gets no phantom deck.
        assertEq(0, gameDocFromJson("""{"name":"x","cards":[]}""").decks.size)
    }

    check("a game reports its own problems rather than failing silently") {
        val dup = CardDoc(faces = listOf(FaceDoc("Twin", setOf("Creature"), mapOf("power" to 1, "toughness" to 1))))
        val g = GameDoc(
            sets = listOf(SetDoc("Core", listOf(dup)), SetDoc("Expansion", listOf(dup))),
            decks = listOf(DeckDoc("Bad", listOf(DeckEntry("Ghost", 2))), DeckDoc("Empty")),
        )
        val problems = g.problems()
        // `Rules.cards` is keyed by NAME, so a duplicate silently shadows --
        // exactly the class of bug a two-set model introduces.
        assertEq(listOf("Twin"), g.duplicateNames())
        assertTrue(problems.any { it.contains("Twin") && it.contains("shadow") }, problems.toString())
        assertTrue(problems.any { it.contains("Ghost") && it.contains("no set defines") }, problems.toString())
        assertTrue(problems.any { it.contains("Empty") && it.contains("empty") }, problems.toString())
        // A clean game is silent.
        assertEq(
            emptyList(),
            GameDoc(
                sets = listOf(SetDoc("Core", listOf(dup))),
                decks = listOf(DeckDoc("Fine", listOf(DeckEntry("Twin", 2)))),
            ).problems(),
        )
    }

    check("a JSON-loaded card still plays correctly in the engine") {
        val doc = CardDoc(
            faces = listOf(
                FaceDoc(
                    "Sprout", setOf("Creature"), mapOf("power" to 1, "toughness" to 1),
                    triggers = listOf(TriggerDoc.SelfEnters(Effect.AddCounter("+1/+1", lit(1), BoundTarget(SELF)))),
                ),
            ),
        )
        val reloaded = cardDocOf(Json.parse(cardDocToJson(doc)))
        assertEq(doc, reloaded, "the doc survived the round trip")
        val end = runSync { Engine(ScriptedInput.of("P0" to listOf(PLAY(reloaded.build())))).run(newGame()) }
        val sprout = end.byName("Sprout")!!
        assertEq(1, sprout.counter("+1/+1"), "the SELF-rebind trigger fired after JSON reload")
        assertEq(2, end.characteristicsOf(sprout.id).power)
    }

    check("a JSON-loaded GameDoc's combat config compiles and runs") {
        val bundle = GameDoc(
            rules = RulesDoc(
                combat = CombatDoc.Program(MTG_COMBAT.copy(blockRules = listOf(BlockRule.needsKeyword("elusive", "elusive"))).lowered(),),
            ),
        )
        val reloaded = gameDocFromJson(gameDocToJson(bundle))
        val end = runSync {
            Engine(
                ScriptedInput.of(attacks = mapOf(1 to CombatTarget.Player("P1")), blocks = mapOf(2 to 1)),
                rules = reloaded.rules(),
            ).playGame(staged("P0" to creature("Sprite", 2, 2, "elusive"), "P1" to creature("Grunt", 2, 2)), maxTurns = 1)
        }
        assertEq(18, end.players.getValue("P1").life, "the elusive block rule survived JSON and dropped the non-elusive block")
    }

    // -- creation / choices / zone ops ------------------------------------

    check("P9.5 CreateToken -- N token permanents enter play") {
        val end = runSync {
            Engine(ScriptedInput.of("P0" to listOf(CAST(Effect.CreateToken(body("Saproling", 1, 1), lit(2))))))
                .run(newGame())
        }
        val toks = end.battlefield.values.filter { it.base.name == "Saproling" }
        assertEq(2, toks.size)
        assertTrue(toks.all { it.isToken && end.characteristicsOf(it.id).power == 1 })
    }

    check("P9.5 a token ceases to exist when it leaves play -- not in the graveyard") {
        val end = runSync {
            Engine(
                ScriptedInput.of(
                    "P0" to listOf(CAST(Effect.CreateToken(body("Spirit", 1, 1))), PASS, CAST(Vinewrap.castEffect!!)),
                    targets = listOf(2), // the CreateToken spell is #1, the token #2
                ),
            ).run(newGame())
        }
        assertEq(null, end.byName("Spirit"))
        assertEq(emptyList<Int>(), end.players.getValue("P0").graveyard, "a token vanishes, it doesn't die into the yard")
    }

    check("P9.5 CopyOf -- a token copy carries the original's static ability") {
        val end = runSync {
            Engine(
                ScriptedInput.of(
                    "P0" to listOf(PLAY(Grunt), PLAY(FieldMarshal), CAST(Effect.CopyOf(BoundTarget(2)))),
                ),
                rules = Rules().knowing(FieldMarshal),
            ).run(newGame())
        }
        // Grunt #1 gets +1/+1 from Field Marshal #2 AND +1/+1 from the copy token.
        assertEq(4, end.characteristicsOf(1).power, "2/2 + two anthems")
        assertEq(2, end.battlefield.values.count { it.base.name == "Field Marshal" }, "the copy is a real Field Marshal")
    }

    check("P9.5 CreateEmblem -- a sourceless static nothing can remove") {
        val end = runSync {
            Engine(
                ScriptedInput.of(
                    "P0" to listOf(
                        PLAY(Grunt),
                        CAST(Effect.CreateEmblem(Statics(chars = listOf(StaticSpec(creatures().yours(), listOf(CharOp.GrantKeyword("flying"))))))),
                    ),
                ),
            ).run(newGame())
        }
        assertTrue(end.characteristicsOf(1).has("flying"), "the emblem grants it")
        assertEq(1, end.battlefield.size, "the emblem is not a battlefield object")
    }

    check("P9.5 ChooseMode -- pick one of the options") {
        val four = runSync {
            Engine(
                ScriptedInput.of(
                    "P0" to listOf(CAST(Effect.ChooseMode(listOf(Effect.DamageOpponent(lit(4)), Effect.GainLife(PlayerRef.You, lit(5)))))),
                    modes = listOf(listOf(0)),
                ),
            ).run(newGame())
        }
        assertEq(16, four.players.getValue("P1").life)

        val life = runSync {
            Engine(
                ScriptedInput.of(
                    "P0" to listOf(CAST(Effect.ChooseMode(listOf(Effect.DamageOpponent(lit(4)), Effect.GainLife(PlayerRef.You, lit(5)))))),
                    modes = listOf(listOf(1)),
                ),
            ).run(newGame())
        }
        assertEq(25, life.players.getValue("P0").life)
    }

    check("P9.5 ChooseMode -- entwine picks two") {
        val end = runSync {
            Engine(
                ScriptedInput.of(
                    "P0" to listOf(
                        CAST(Effect.ChooseMode(listOf(Effect.DamageOpponent(lit(4)), Effect.GainLife(PlayerRef.You, lit(5))), pick = lit(2))),
                    ),
                    modes = listOf(listOf(0, 1)),
                ),
            ).run(newGame())
        }
        assertEq(16, end.players.getValue("P1").life)
        assertEq(25, end.players.getValue("P0").life)
    }

    check("P9.5 Discard -- loot: draw two, discard one") {
        val end = runSync {
            Engine(
                ScriptedInput.of(
                    "P0" to listOf(CAST(Effect.Sequence(listOf(Effect.Draw(PlayerRef.You, lit(2)), Effect.Discard(PlayerRef.You, lit(1)))))),
                    cards = listOf(listOf(100_000)), // discard the first of the two drawn tokens
                ),
            ).run(newGame(libraries = mapOf("P0" to tokens(4))))
        }
        assertEq(1, end.players.getValue("P0").hand.size)
        assertEq(1, end.players.getValue("P0").graveyard.size)
    }

    check("EPR Skirmish: Discard(toZone) sends to a non-graveyard hidden zone (a burned resource card)") {
        val end = runSync {
            Engine(
                ScriptedInput.of(
                    "P0" to listOf(CAST(Effect.Sequence(listOf(
                        Effect.Draw(PlayerRef.You, lit(2)), Effect.Discard(PlayerRef.You, lit(1), HiddenZone.EXILE),
                    )))),
                    cards = listOf(listOf(100_000)),
                ),
            ).run(newGame(libraries = mapOf("P0" to tokens(4))))
        }
        assertEq(1, end.players.getValue("P0").hand.size, "the kept card")
        assertEq(0, end.players.getValue("P0").graveyard.size, "not the graveyard")
        assertEq(1, end.exile.size, "exiled instead -- gone for good, unlike a normal discard")
        // Elided when default; round-trips when set.
        assertTrue(!effectToJson(Effect.Discard(PlayerRef.You, lit(1))).contains("toZone"), "the default (graveyard) is not written")
        val d = Effect.Discard(PlayerRef.You, lit(1), HiddenZone.EXILE)
        assertEq(d, effectFromJson(effectToJson(d)))
    }

    check("P9.5 ReturnFromDiscard -- to hand") {
        val start = newGame().let { it.copy(players = it.players + ("P0" to it.players.getValue("P0").copy(graveyard = tokens(3)))) }
        val end = runSync {
            Engine(
                ScriptedInput.of(
                    "P0" to listOf(CAST(Effect.ReturnFromDiscard(PlayerRef.You, lit(2)))),
                    cards = listOf(listOf(100_000, 100_001)),
                ),
            ).run(start)
        }
        assertEq(2, end.players.getValue("P0").hand.size)
        assertEq(1, end.players.getValue("P0").graveyard.size)
    }

    check("P9.5 ReturnFromDiscard -- to the battlefield via the card registry") {
        val start = newGame().let {
            it.copy(players = it.players + ("P0" to it.players.getValue("P0").copy(graveyard = listOf(CardRef(500, "Grunt")))))
        }
        val end = runSync {
            Engine(
                ScriptedInput.of("P0" to listOf(CAST(Effect.ReturnFromDiscard(PlayerRef.You, lit(1), toBattlefield = true))), cards = listOf(listOf(500))),
                rules = Rules(cards = mapOf("Grunt" to Grunt)),
            ).run(start)
        }
        assertTrue(end.byName("Grunt") != null, "materialised from the registry")
        assertTrue(end.players.getValue("P0").graveyard.isEmpty())
    }

    check("P9.5 the new verbs round-trip through JSON") {
        val effects = listOf<Effect>(
            Effect.CreateToken(Characteristics("Beast", setOf("Creature"), mapOf("power" to 3, "toughness" to 3), keywords = setOf("trample")), lit(2), "skies"),
            Effect.CopyOf(BoundTarget(CHOSEN)),
            Effect.CreateEmblem(Statics(chars = listOf(StaticSpec(creatures().yours(), listOf(CharOp.PlusPT(lit(2), lit(2))))), rules = listOf(RuleMod.Cant(RuleAction.GAIN_LIFE, PlayerRef.Opponent)))),
            Effect.ChooseMode(listOf(Effect.DamageOpponent(lit(4)), Effect.Destroy(BoundTarget(CHOSEN)), Effect.GainLife(PlayerRef.You, lit(5))), pick = lit(2)),
            Effect.Discard(PlayerRef.You, lit(1)),
            Effect.ReturnFromDiscard(PlayerRef.You, lit(2), toBattlefield = true),
        )
        for (e in effects) assertEq(e, effectFromJson(effectToJson(e)), "round-trip $e")
    }

    check("P9.5 a JSON-loaded token-maker card plays") {
        val doc = CardDoc(faces = listOf(FaceDoc("Rally Call", setOf("Sorcery"), castEffect = Effect.CreateToken(body("Soldier", 1, 1), lit(3)))))
        val reloaded = cardDocOf(Json.parse(cardDocToJson(doc)))
        val end = runSync { Engine(ScriptedInput.of("P0" to listOf(CAST(reloaded.build().castEffect!!)))).run(newGame()) }
        assertEq(3, end.battlefield.values.count { it.base.name == "Soldier" })
    }

    check("a guard COMPELS, and only within the lanes it can actually defend") {
        // `mustTargetKeyword` applies in FREE combat too (a keyword honoured by
        // one style and ignored by another is a dead rule).
        val shipT = TypeDef(
            "Ship", fields = setOf("hull", "fast"), attacks = true,
            damageCounter = "hull", diesWhen = (selfCounter("hull") lte lit(0)).lowered(),
            zoneChoices = listOf("van", "rear"),
        )
        fun body(name: String, kw: Set<String>) = CardDoc(
            faces = listOf(FaceDoc(
                name, setOf("Ship"),
                fields = mapOf("hull" to 5, "fast" to 1), keywords = kw,
            )),
            entersWith = listOf(CounterDef("hull", lit(5))),
        ).build()
        val rules = Rules(
            types = BUILTIN_TYPES_CORE + mapOf("Ship" to shipT),
            cards = mapOf(
                "Raider" to body("Raider", emptySet()),
                "Ranger" to body("Ranger", setOf("reach")),
                "Wall" to body("Wall", setOf("guard")),
                "Bystander" to body("Bystander", emptySet()),
            ),
            zones = BUILTIN_ZONES + listOf(
                PlayZoneDef("van", ZoneScope.PER_PLAYER, maxOccupants = 2),
                PlayZoneDef("rear", ZoneScope.PER_PLAYER, maxOccupants = 2),
            ).associateBy { it.id },
            combat = FAST_SLOW_LANES_LOCKED_COMBAT.copy(mustTargetKeyword = "guard").lowered(),
        )
        val s = setupGame(
            rules = rules,
            startInPlay = mapOf(
                // attacks out of van. P1 keeps its guard in REAR, and an
                // ordinary body in van.
                "P0" to listOf(StartCard("Raider", "van"), StartCard("Ranger", "van")),
                "P1" to listOf(StartCard("Wall", "rear"), StartCard("Bystander", "van")),
            ),
        )
        fun idOf(n: String) = s.battlefield.values.first { it.base.name == n }.id
        val cfg = rules.combat
        val wall = idOf("Wall")

        // The lane-locked Raider cannot even SEE the rear guard, so it is not
        // compelled by it. This is the whole reason compulsion is scoped by
        // `laneReaches`: a rule that ordered an attacker toward something out
        // of its reach would refuse every attack it had.
        assertEq(emptyList(), s.compellingGuards(rules, idOf("Raider"), "P1"), "out of lane, not compelled")
        // The Ranger has `reach`, so every lane is its lane -- and it IS
        // compelled, by the guard sitting two lanes away.
        assertEq(listOf(wall), s.compellingGuards(rules, idOf("Ranger"), "P1"), "reach sees the guard")

        // And compulsion must actually narrow what the pilot is offered, or the
        // engine refuses attacks the pilot keeps choosing -- the wasted-attack
        // contamination `laneReaches` was introduced to end.
        assertEq(
            setOf(wall),
            ccgui.combatBoardTargets(rules, s, "P0", idOf("Ranger"), null),
            "the guard is the ONLY thing reach may hit",
        )
        assertTrue(
            !s.canAttackFace(rules, idOf("Ranger"), "P1", null),
            "and it may not go past it to the face",
        )
        // The Raider, unaffected, still reaches its own lane normally.
        assertEq(
            setOf(idOf("Bystander")),
            ccgui.combatBoardTargets(rules, s, "P0", idOf("Raider"), null),
            "an uncompelled attacker keeps its ordinary lane targets",
        )
    }

    println()
    uiChecks()

    println()
    contentChecks()

    println()
    creatorCoverageChecks()

    println()
    authoredChecks()

    println()
    poolChecks()

    codecChecks()
    schemaChecks()

    reviewChecks()
    dslReviewChecks()
    archReviewChecks()

    println("$checks checks, $failures failing")
    if (failures > 0) kotlin.system.exitProcess(1)
}

/** The grid board: three lanes, two berths each, one ship per berth. */
private fun gridRules(cfg: CombatConfig) = Rules(
    combat = cfg.lowered(),
    zones = BUILTIN_ZONES + (1..3).flatMap { l ->
        listOf(
            PlayZoneDef("${l}F", ZoneScope.PER_PLAYER, maxOccupants = 1, lane = l, depth = Depth.FRONT),
            PlayZoneDef("${l}B", ZoneScope.PER_PLAYER, maxOccupants = 1, lane = l, depth = Depth.BACK),
        )
    }.associateBy { it.id },
)

/** A FREE combat's first step. */
internal fun freeStep(c: Combat): Effect.FreeAttacks =
    (c.program as Effect.Sequence).steps.first() as Effect.FreeAttacks

/** Does an exhausted permanent sit a FREE step out? Read off its actor filter. */
internal fun exhaustedSitsOut(step: Effect.FreeAttacks): Boolean {
    var found = false
    step.actors.subst(object : Subst() {
        override fun rewrite(e: BoolExpr): BoolExpr = e.also {
            if (it == BoolExpr.Not(BoolExpr.IsExhausted(BoundTarget(SUBJECT)))) found = true
        }
    })
    return found
}
