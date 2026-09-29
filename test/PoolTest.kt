package ccg

import ccgui.CROWD_THRESHOLD
import ccgui.CardStat
import ccgui.DeckRole
import ccgui.cardStat
import ccgui.crowdGroups
import ccgui.deckRoles
import ccgui.distanceBetween
import ccgui.poolReport
import ccgui.sections

// ---------------------------------------------------------------------------
// The pool instrument. Two halves:
//
//  1. FIXTURES with hand-chosen numbers, for the metric itself.
//  2. THE REAL CORE on exact values -- possible while the pool is small enough
//     to audit card by card.
//
// These are the KOTLIN `CORE_BUNDLE`'s numbers; a Creator-edited copy is a
// different game.
// ---------------------------------------------------------------------------

/** A Ship-shaped test type, so `declaresFields` is true and the crowding
 *  metric has a line to compare. */
private val SHIP_RULES = RulesDoc(
    extraTypes = listOf(TypeDef("Ship", fields = setOf("sr", "lr", "hull"))),
)

private fun ship(
    name: String,
    mana: Int,
    sr: Int = 0,
    lr: Int = 0,
    hull: Int = 0,
    keywords: Set<String> = emptySet(),
    triggers: List<TriggerDoc> = emptyList(),
    cost: Cost = Cost(mana = mapOf("" to mana)),
): CardDoc = CardDoc(
    faces = listOf(
        FaceDoc(
            name = name,
            types = setOf("Ship"),
            fields = buildMap {
                if (sr != 0) put("sr", sr)
                if (lr != 0) put("lr", lr)
                if (hull != 0) put("hull", hull)
            },
            keywords = keywords,
            triggers = triggers,
        ),
    ),
    cost = cost,
    id = name,
)

private fun stat(c: CardDoc): CardStat = c.cardStat(SHIP_RULES, DeckRole.MAIN)

fun poolChecks() {
    println("Pool instrument")

    // -- the metric ------------------------------------------------------

    check("a card is distance 0 from itself") {
        val a = stat(ship("A", 2, sr = 4, lr = 1, hull = 4))
        assertEq(0, distanceBetween(a, a))
    }

    check("one keyword apart is distance 2, which is what catches a minus-a-term pair") {
        // The real shape this threshold was calibrated on: at 2 mana the Core
        // holds `Kite Lance` (sr4 lr1 hull4, reach) and `Spar Cutter`, the same
        // line with no keyword.
        val lance = stat(ship("Kite Lance", 2, sr = 4, lr = 1, hull = 4, keywords = setOf("reach")))
        val cutter = stat(ship("Spar Cutter", 2, sr = 4, lr = 1, hull = 4))
        assertEq(2, distanceBetween(lance, cutter))
        assertTrue(distanceBetween(lance, cutter) <= CROWD_THRESHOLD, "must be caught at the default threshold")
    }

    check("a stat point is worth less than a term") {
        val base = stat(ship("Base", 2, lr = 2, hull = 7))
        val onePoint = stat(ship("OnePoint", 2, lr = 3, hull = 7))
        val oneTerm = stat(ship("OneTerm", 2, lr = 2, hull = 7, keywords = setOf("reach")))
        assertEq(1, distanceBetween(base, onePoint))
        assertEq(2, distanceBetween(base, oneTerm))
        assertTrue(
            distanceBetween(base, onePoint) < distanceBetween(base, oneTerm),
            "the register's position: a term differentiates more than a stat tweak",
        )
    }

    check("genuinely different lines are not crowded") {
        val cutter = stat(ship("Spar Cutter", 2, sr = 4, lr = 1, hull = 4))
        val bulwark = stat(ship("Aegis Bulwark", 2, lr = 1, hull = 8))
        assertEq(8, distanceBetween(cutter, bulwark))
        assertTrue(distanceBetween(cutter, bulwark) > CROWD_THRESHOLD, "a glass cannon is not a wall")
    }

    // -- grouping --------------------------------------------------------

    check("crowding reports groups, never single cards") {
        val alone = listOf(stat(ship("Alone", 2, sr = 9)))
        assertEq(emptyList<String>(), crowdGroups(alone).map { it.type })
    }

    check("a crowded slot is reported once, with every member") {
        val cards = listOf(
            stat(ship("A", 2, lr = 2, hull = 7)),
            stat(ship("B", 2, lr = 2, hull = 7, keywords = setOf("reach"))),
            stat(ship("C", 2, lr = 3, hull = 7)),
            stat(ship("Far", 2, sr = 9)),
        )
        val groups = crowdGroups(cards)
        assertEq(1, groups.size, "one crowded slot, not one group per pair")
        assertEq(listOf("A", "B", "C"), groups[0].members.sorted())
        assertTrue("Far" !in groups[0].members, "a distant card must not be swept in")
    }

    check("different costs are different slots") {
        val cards = listOf(
            stat(ship("Cheap", 1, lr = 2, hull = 7)),
            stat(ship("Dear", 4, lr = 2, hull = 7)),
        )
        assertEq(emptyList<Int>(), crowdGroups(cards).map { it.mana }, "same line at different cost is a curve, not a crowd")
    }

    check("a fieldless card is never crowded -- there is nothing to compare") {
        // Fieldless cards are not compared (their identity is inside an Effect
        // this metric cannot read; they would all score distance 0).
        val spellRules = RulesDoc(extraTypes = listOf(TypeDef("Manoeuvre", fields = emptySet())))
        val a = CardDoc(faces = listOf(FaceDoc("Pinpoint Lance", setOf("Manoeuvre"))), cost = Cost(mana = mapOf("" to 1)), id = "a")
        val b = CardDoc(faces = listOf(FaceDoc("Pinning Shot", setOf("Manoeuvre"))), cost = Cost(mana = mapOf("" to 1)), id = "b")
        val stats = listOf(a.cardStat(spellRules, DeckRole.MAIN), b.cardStat(spellRules, DeckRole.MAIN))
        assertEq(0, distanceBetween(stats[0], stats[1]), "the metric genuinely cannot tell them apart")
        assertEq(emptyList<String>(), crowdGroups(stats).map { it.type }, "so it must not claim to")
    }

    // -- deck roles ------------------------------------------------------

    check("a card drawn from the library outranks one that only fills a slot") {
        val c = ship("Both", 2, hull = 2)
        val g = GameDoc(
            sets = listOf(SetDoc(cards = listOf(c))),
            decks = listOf(
                DeckDoc(entries = listOf(DeckEntry("Both", 4)), slots = mapOf("Flagship" to listOf("Both"))),
            ),
        )
        assertEq(DeckRole.MAIN, g.deckRoles()["Both"], "drawable is the stronger fact")
    }

    check("a card in no deck is UNUSED, which is a finding rather than a leftover") {
        val g = GameDoc(sets = listOf(SetDoc(cards = listOf(ship("Orphan", 3, hull = 3)))), decks = emptyList())
        assertEq(DeckRole.UNUSED, g.deckRoles()["Orphan"])
    }

    // -- cost terms ------------------------------------------------------

    check("an alternative's own terms are counted -- the Core's identity mechanic is nested") {
        val c = ship(
            "Hull Payer", 4,
            cost = Cost(
                mana = mapOf("" to 4),
                alternatives = listOf(
                    Cost(mana = mapOf("" to 2), payFrom = CounterPayment("hull", 5, PermFilter(types = setOf("Station")))),
                ),
            ),
        )
        val s = stat(c)
        assertTrue("alternatives" in s.costTerms, "the top-level term")
        assertTrue("payFrom" in s.costTerms, "and the one nested inside it -- read only at the top level this vanishes")
    }

    check("stats per mana is undefined rather than zero where the question is meaningless") {
        val spellRules = RulesDoc(extraTypes = listOf(TypeDef("Doctrine", fields = emptySet())))
        val spell = CardDoc(faces = listOf(FaceDoc("Full Stop", setOf("Doctrine"))), cost = Cost(mana = mapOf("" to 3)), id = "s")
        assertEq(null, spell.cardStat(spellRules, DeckRole.MAIN).perMana, "a card with no line has no rate; 0.0 would read as the worst in the pool")
        assertEq(null, stat(ship("Free", 0, hull = 4)).perMana, "and a free card has no rate either")
    }

    // -- the real Core, exact ---------------------------------------------

    check("the Core measures as audited, card for card") {
        val r = CORE_BUNDLE.poolReport()
        assertEq(35, r.cards.size)
        assertEq(3, CORE_BUNDLE.decks.size)

        // The partition that makes the curve honest: split by role, the main
        // deck has an ordinary descending curve.
        assertEq(mapOf(1 to 2, 2 to 6, 3 to 7, 4 to 2, 5 to 2), r.curve[DeckRole.MAIN])
        assertEq(mapOf(0 to 6, 3 to 2, 4 to 2, 5 to 5), r.curve[DeckRole.SLOT])
        assertEq(mapOf(4 to 1), r.curve[DeckRole.UNUSED], "one card sits in no deck at all")
        assertEq(
            listOf("Culling Sweep"),
            r.cards.filter { it.role == DeckRole.UNUSED }.map { it.name },
        )

        // v16: every Station also gained the stow ability, so each moved up a
        // term -- Harrow Vault, which carries income + three shield-loss
        // triggers + a shield replacement + stow, is the 6-term card.
        assertEq(mapOf(0 to 14, 1 to 16, 2 to 2, 3 to 1, 4 to 1, 6 to 1), r.density)
        assertEq(
            14,
            r.cards.count { it.textTerms == 0 && "Ship" in it.types },
            "every blank card in this Core is a Ship",
        )
        assertEq(mapOf("reach" to 2), r.keywordUse, "one keyword in the whole game")
        // 15 -> 21: every Ship costs {1} more, so six more cards crossed into
        // the {3}+ band where the shield alternative is offered.
        assertEq(mapOf("alternatives" to 21, "payFrom" to 21), r.costTermUse)
        // powerUp joins them at v16: it is a declared numeric field on the
        // Station type, which is how this engine spells "a keyword with an
        // amount".
        assertEq(listOf("hull", "lr", "powerUp", "sr"), r.fieldSpread.keys.sorted())
    }

    check("both predicates ship, and they disagree -- which is the point") {
        val r = CORE_BUNDLE.poolReport()

        // The drafted predicate flags one card; the {1} Ship tax moved others
        // into the shield-alternative band (not pure generic mana).
        assertEq(1, r.fungible.size)
        assertEq(
            listOf("Bastion Drone"),
            r.fungible.mapNotNull { r.card(it)?.name }.sorted(),
        )
        assertTrue(
            r.fungible.all { r.card(it)?.textTerms == 0 },
            "by construction it can only ever flag a card with no text",
        )

        // The replacement. It finds the pair the drafted predicate cannot see:
        // Spar Cutter and Kite Lance carry the identical line and differ by one
        // keyword -- and the drafted predicate flags Spar Cutter alone, i.e.
        // the member of the pair with LESS text.
        val pair = r.crowded.firstOrNull { g -> g.members.mapNotNull { r.card(it)?.name }.sorted() == listOf("Kite Lance", "Spar Cutter") }
        assertTrue(pair != null, "the minus-a-term pair must be reported as a group")
        assertEq(2, pair!!.worst)
        assertTrue(
            r.card(pair.members.first { r.card(it)?.name == "Kite Lance" })!!.key !in r.fungible,
            "the drafted predicate misses Kite Lance while flagging its strictly-worse twin",
        )
    }

    check("the rate is reported, never judged") {
        val r = CORE_BUNDLE.poolReport()
        // 19 cards have both a line and a cost; the other 16 are anchors,
        // spells, or free, and are excluded rather than counted as zero.
        assertEq(19, r.rate.n)
        // v16 trimmed the {4} band by two stat points a card, which is what
        // moved this: 3.11 -> 3.01.
        assertTrue(r.rate.mean > 2.95 && r.rate.mean < 3.05, "mean was ${r.rate.mean}")
        // Signed distance, so a reader can see the shape without being told
        // which end is wrong.
        val signal = r.cards.first { it.name == "Signal Kite" }
        assertTrue(r.rate.distance[signal.key]!! < 0.0, "Signal Kite sits below the mean")
        // Measured on the Kotlin Core, never a number read off an export.
        val warden = r.cards.first { it.name == "Spire Warden" }
        assertTrue(r.rate.distance[warden.key]!! > 0.0, "Spire Warden sits above it")
        assertEq(
            r.rate.n,
            r.rate.distance.size,
            "every rated card carries a distance; none is silently dropped",
        )
    }

    // -- the display model, shared by agent/pool and the Creator Pool socket --

    check("sections lead with what names cards to edit") {
        val s = CORE_BUNDLE.poolReport().sections()
        assertEq(
            listOf("Crowded slots", "Carries no distinguishing term", "In no deck"),
            s.take(3).map { it.title },
            "the doc's rule: a view earns its place only if it changes a card, so the card-naming sections come first",
        )
        assertEq(7, s.size)
    }

    check("a section with nothing in it still appears") {
        // A section that vanishes when empty makes "nothing is crowded" and
        // "the measure did not run" look identical to the reader.
        val quiet = GameDoc(sets = listOf(SetDoc(cards = listOf(ship("Only", 2, hull = 4)))))
        val s = quiet.poolReport().sections()
        val crowd = s.first { it.title == "Crowded slots" }
        assertEq(emptyList<String>(), crowd.lines.map { it.text })
        assertTrue(crowd.note.isNotEmpty(), "and it still says what was measured")
    }

    check("every card line points at a card the report actually holds") {
        val r = CORE_BUNDLE.poolReport()
        val keys = r.cards.map { it.key }.toSet()
        val pointed = r.sections().flatMap { it.lines }.mapNotNull { it.cardKey }
        assertTrue(pointed.isNotEmpty(), "some lines are about cards")
        assertTrue(
            pointed.all { it in keys },
            "a line the Creator cannot resolve to a card is a row that taps to nothing",
        )
    }

    check("the crowded section names the minus-a-term pair in its own words") {
        val r = CORE_BUNDLE.poolReport()
        val crowd = r.sections().first { it.title == "Crowded slots" }
        val text = crowd.lines.joinToString("\n") { it.text }
        assertTrue("Kite Lance" in text && "Spar Cutter" in text, "both members are listed")
        assertTrue("reach" in text, "and the keyword that is the whole difference is shown")
    }

    check("the rate section reports and never judges") {
        val words = CORE_BUNDLE.poolReport().sections()
            .first { it.title == "Stats per mana" }
            .let { it.note + " " + it.lines.joinToString(" ") { l -> l.text } }
            .lowercase()
        for (verdict in listOf("violation", "illegal", "bad", "wrong", "too ", "should")) {
            assertTrue(verdict !in words, "a measure, not a verdict: found '$verdict'")
        }
    }

    check("crowding only ever names cards that have a line to compare") {
        val r = CORE_BUNDLE.poolReport()
        val named = r.crowded.flatMap { it.members }
        assertTrue(named.isNotEmpty(), "the Core is crowded somewhere")
        assertTrue(
            named.all { r.card(it)?.declaresFields == true },
            "a fieldless card must never appear in a crowd group",
        )
        assertTrue(
            r.crowded.all { it.members.size >= 2 },
            "a group of one is a card doing its job",
        )
    }
}
