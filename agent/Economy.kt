package cge

import ccg.Cost
import ccg.GameDoc
import ccg.GameState
import ccg.PlayerId
import ccg.PlayerInput
import ccg.PriorityAction
import ccg.Rules
import ccg.manaValue
import ccgui.ALL_POLICIES
import ccgui.HeuristicPilot
import ccgui.PassingPilot
import ccgui.PlaySession
import ccgui.PolicyPilot
import ccgui.SeatedPilots
import ccgui.opening

// ---------------------------------------------------------------------------
// `agent/economy <game> [seeds]` -- is the economy starving anyone, and where.
// `agent/balance` cannot answer it (no policy scores "this gives me mana
// later"), so this measures the economy directly, needing pilots only to try
// to cast things:
//
//   * mana held when it matters,
//   * how much of the HAND could not be paid for,
//   * which COSTS are reachable,
//   * how much was payable ONLY through a cost alternative.
//
// Sampled once per player per round, at their own action phase.
// ---------------------------------------------------------------------------

private class Tally {
    var samples = 0
    var poolTotal = 0
    var poolMax = 0
    var handTotal = 0
    var unaffordable = 0
    /** Hand cards affordable at all, by printed mana value. */
    val offeredByMv = HashMap<Int, Int>()
    val affordableByMv = HashMap<Int, Int>()
    /** Affordable ONLY through a cost alternative -- i.e. hull paid for it. */
    var onlyViaAlternative = 0
    /** The priciest card held, and whether it was payable. */
    var topHeld = 0
    var topPayable = 0
    /** Rounds where NOTHING in hand was payable. */
    var fullyStranded = 0
    var emptyHand = 0
}

/** Records the economy at this seat's action phase, then defers entirely to the
 *  real pilot. It must not change a single decision -- if it did, the numbers
 *  would be about a game nobody plays. */
private class Watcher(
    private val seat: PlayerId,
    private val rules: Rules,
    private val inner: PlayerInput,
    private val tally: Tally,
) : PlayerInput {

    private var pendingTurn = -1
    private var pendingPool = -1
    private var pending: GameState? = null

    /** The PEAK of the round, not its first window: the Station's action-phase
     *  income is still on the stack at the first window, so sampling there reads
     *  0 mana and an unpayable hand for every deck. */
    override suspend fun ask(q: ccg.Question): ccg.Answer {
        if (q is ccg.Question.Priority) observe(q.player, q.state)
        return inner.ask(q)
    }

    private fun observe(player: PlayerId, state: GameState) {
        if (player == seat && state.phase == "action") {
            if (state.turnNumber != pendingTurn) {
                flush()
                pendingTurn = state.turnNumber
                pendingPool = -1
            }
            val pool = state.players[seat]?.pool?.values?.sum() ?: 0
            if (pool > pendingPool) {
                pendingPool = pool
                pending = state
            }
        }
    }

    /** Commit the round being held. Also called once after the game, or the
     *  final round would be silently dropped from every tally. */
    fun flush() {
        pending?.let { sample(it) }
        pending = null
        pendingPool = -1
    }

    private fun sample(state: GameState) {
        val p = state.players[seat] ?: return
        val pool = p.pool.values.sum()
        tally.samples++
        tally.poolTotal += pool
        if (pool > tally.poolMax) tally.poolMax = pool
        tally.handTotal += p.hand.size
        if (p.hand.isEmpty()) {
            tally.emptyHand++
            return
        }

        var anyPayable = false
        var top = -1
        var topOk = false
        for (ref in p.hand) {
            val def = rules.cards[ref.cardId] ?: continue
            val cost: Cost = def.cost
            val mv = cost.manaValue()
            tally.offeredByMv[mv] = (tally.offeredByMv[mv] ?: 0) + 1

            val payable = state.canAfford(seat, cost)
            if (payable) {
                anyPayable = true
                tally.affordableByMv[mv] = (tally.affordableByMv[mv] ?: 0) + 1
                // Did the BASIC cost carry it, or only an alternative? That
                // difference matters: a discount that is never the deciding
                // factor is not warping anything.
                if (cost.alternatives.isNotEmpty() && !state.canAfford(seat, cost.basic())) {
                    tally.onlyViaAlternative++
                }
            } else {
                tally.unaffordable++
            }
            if (mv > top) { top = mv; topOk = payable }
        }
        if (!anyPayable) tally.fullyStranded++
        if (top >= 0) {
            tally.topHeld++
            if (topOk) tally.topPayable++
        }
    }
}

private fun pilotFor(name: String, seat: PlayerId, rules: Rules): PlayerInput =
    if (name == "Heuristic") HeuristicPilot(seat, rules)
    else PolicyPilot(seat, rules, ALL_POLICIES.first { it.name == name })

private fun playOne(
    doc: GameDoc,
    rules: Rules,
    a: Int,
    b: Int,
    seed: Int,
    p0: PlayerInput,
    p1: PlayerInput,
    maxTurns: Int,
) {
    val start = PlaySession(p0Deck = a, p1Deck = b, seed = seed).opening(doc, rules)
    val pilots = SeatedPilots(mapOf("P0" to p0, "P1" to p1), fallback = PassingPilot())
    // Ends by itself or by `maxTurns`; what it saw is in the pilots.
    runCatching { ccg.playToEnd(rules, start, pilots, maxTurns) }
}

private fun pct(n: Int, d: Int) = if (d == 0) "  n/a" else "%5.1f".format(100.0 * n / d)

fun main(args: Array<String>) {
    val games = catalogue()
    val name = args.firstOrNull { !it.startsWith("--") }
    if (name == null) {
        println("usage: economy <game> [seeds]")
        println()
        println("games:")
        for (k in games.keys) println("  $k")
        return
    }
    val doc = games[name] ?: run {
        System.err.println("no such game: $name")
        System.err.println("known: " + games.keys.joinToString(", "))
        kotlin.system.exitProcess(1)
    }
    val seeds = args.drop(1).firstOrNull { it.toIntOrNull() != null }?.toInt() ?: 12
    val rules = doc.compile().playable()
    val deckNames = doc.decks.map { it.name }
    val pilots = listOf("Proactive", "Reactive", "Attrition", "Heuristic")

    // One tally per DECK, pooled over pilots and seats: the question is whether
    // a deck can pay for its own cards, not which policy is thriftiest.
    val byDeck = deckNames.indices.associateWith { Tally() }

    for (a in deckNames.indices) for (b in deckNames.indices) {
        for (pa in pilots) for (pb in pilots) {
            for (s in 0 until seeds) {
                val seed = 1000 + s * 7919
                val p0 = Watcher("P0", rules, pilotFor(pa, "P0", rules), byDeck[a]!!)
                val p1 = Watcher("P1", rules, pilotFor(pb, "P1", rules), byDeck[b]!!)
                runCatching { playOne(doc, rules, a, b, seed, p0, p1, maxTurns = 30) }
                // The last round is still held pending when the game ends.
                p0.flush(); p1.flush()
            }
        }
    }

    println("ECONOMY -- $name   (sampled once per player per round, at their action phase)")
    println()
    println("%-20s %8s %8s %8s %9s %9s %9s".format("deck", "mana", "max", "hand", "unpayable", "stranded", "top ok"))
    for (i in deckNames.indices) {
        val t = byDeck[i]!!
        val handCards = t.offeredByMv.values.sum()
        println(
            "%-20s %8.2f %8d %8.2f %8s%% %8s%% %8s%%".format(
                deckNames[i].take(20),
                t.poolTotal.toDouble() / maxOf(1, t.samples),
                t.poolMax,
                t.handTotal.toDouble() / maxOf(1, t.samples),
                pct(t.unaffordable, handCards),
                pct(t.fullyStranded, t.samples),
                pct(t.topPayable, t.topHeld),
            ),
        )
    }
    println()
    println("  mana      = generic+typed pool held at the action phase, averaged over rounds")
    println("  max       = the most any player of this deck ever held at once")
    println("  hand      = cards in hand at that moment")
    println("  unpayable = share of HAND CARDS that could not be paid for, any route")
    println("  stranded  = share of ROUNDS where NOTHING in hand was payable")
    println("  top ok    = share of rounds where the priciest card held was payable")

    println()
    println("REACHABILITY BY PRINTED COST -- what the curve actually costs to hold")
    val allMv = byDeck.values.flatMap { it.offeredByMv.keys }.distinct().sorted()
    print("%-20s".format("deck"))
    for (mv in allMv) print("%8s".format("{$mv}"))
    println()
    for (i in deckNames.indices) {
        val t = byDeck[i]!!
        print("%-20s".format(deckNames[i].take(20)))
        for (mv in allMv) {
            val off = t.offeredByMv[mv] ?: 0
            print("%8s".format(if (off == 0) "  -" else pct(t.affordableByMv[mv] ?: 0, off) + "%"))
        }
        println()
    }
    println("  share of the times a card of that cost was IN HAND that it was payable.")

    println()
    println("THE ALTERNATIVE COST -- how often hull, not mana, is what paid")
    for (i in deckNames.indices) {
        val t = byDeck[i]!!
        val affordable = t.affordableByMv.values.sum()
        println(
            "  %-20s %6d of %6d payable cards were payable ONLY via an alternative  (%s%%)".format(
                deckNames[i].take(20), t.onlyViaAlternative, affordable, pct(t.onlyViaAlternative, affordable),
            ),
        )
    }
    println()
    println("  A discount that is never the deciding route is not warping anything;")
    println("  one that carries most of the top end is the economy, whatever the")
    println("  mana line says.")
}
