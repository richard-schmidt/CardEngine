package cge

import ccg.GameDoc
import ccg.PlayerId
import ccg.gameDocFromJson
import ccgui.ALL_POLICIES
import ccgui.HeuristicPilot
import ccgui.PassingPilot
import ccgui.PlaySession
import ccgui.PolicyPilot
import ccgui.SeatedPilots
import ccgui.opening
import java.io.File

// ---------------------------------------------------------------------------
// The deck x deck matrix, run adversarially: every ordered deck pairing under
// every pilot pairing over many seeds. Reports, rather than one flattering number:
//
//   * per-matchup win rate for the FIRST-NAMED deck,
//   * SKEW -- mean distance from an even 50%,
//   * SEAT advantage (on the play vs on the draw),
//   * MIRROR spreads -- how uneven the PILOTS are,
//   * a BEST-vs-BEST arm ("the deck is weak" vs "our bot cannot pilot it").
//
// Non-wins are reported separately (mutual KO vs unfinished), never folded
// into either side.
// ---------------------------------------------------------------------------

private class Cell {
    var p0Wins = 0
    var p1Wins = 0
    /** Both seats lost the SAME step -- a simultaneous double-KO. Decisive,
     *  fast, and unlike a deadlock: these resolve by turn 4-8, not at the
     *  turn cap. */
    var mutual = 0
    /** Neither seat ever lost -- ran out the turn cap. Kept apart from `mutual`:
     *  they are different games, not one "draws" number. */
    var unfinished = 0
    val draws: Int get() = mutual + unfinished
    val n: Int get() = p0Wins + p1Wins + draws
    /** Decided games only: a draw is not evidence for either deck. */
    fun rate(): Double? {
        val d = p0Wins + p1Wins
        return if (d == 0) null else 100.0 * p0Wins / d
    }
}

private fun playOne(
    doc: GameDoc,
    rules: ccg.Rules,
    a: Int,
    b: Int,
    seed: Int,
    p0: ccg.PlayerInput,
    p1: ccg.PlayerInput,
    maxTurns: Int,
): Set<PlayerId>? {
    val start = PlaySession(p0Deck = a, p1Deck = b, seed = seed).opening(doc, rules)
    val pilots = SeatedPilots(mapOf("P0" to p0, "P1" to p1), fallback = PassingPilot())
    return runCatching { ccg.playToEnd(rules, start, pilots, maxTurns) }.getOrNull()?.losers
}

private fun pilotFor(name: String, seat: PlayerId, rules: ccg.Rules): ccg.PlayerInput =
    if (name == "Heuristic") HeuristicPilot(seat, rules)
    else PolicyPilot(seat, rules, ALL_POLICIES.first { it.name == name })

fun runBalance(doc: GameDoc, seeds: Int, maxTurns: Int, pilots: List<String>) {
    val rules = doc.compile().playable()
    val decks = doc.decks.map { it.name }
    val n = decks.size
    println("GAME: ${doc.name}   decks: ${decks.joinToString(", ")}")
    println("pilots: ${pilots.joinToString(", ")}   seeds/cell: $seeds   maxTurns: $maxTurns")

    // matchup[a][b] aggregated over every pilot pairing and seed
    val matchup = Array(n) { Array(n) { Cell() } }
    // per pilot-pair, for the best-vs-best and mirror arms
    val byPilots = HashMap<Pair<String, String>, Array<Array<Cell>>>()
    var seatP0 = 0
    var seatP1 = 0
    var failures = 0

    for (pa in pilots) for (pb in pilots) {
        val grid = Array(n) { Array(n) { Cell() } }
        byPilots[pa to pb] = grid
        for (a in 0 until n) for (b in 0 until n) {
            repeat(seeds) { s ->
                val seed = 20260909 + s * 7919
                val losers = playOne(doc, rules, a, b, seed,
                    pilotFor(pa, "P0", rules), pilotFor(pb, "P1", rules), maxTurns)
                if (losers == null) { failures++; return@repeat }
                val p0Lost = "P0" in losers
                val p1Lost = "P1" in losers
                val cells = listOf(matchup[a][b], grid[a][b])
                when {
                    p0Lost && !p1Lost -> { cells.forEach { it.p1Wins++ }; seatP1++ }
                    p1Lost && !p0Lost -> { cells.forEach { it.p0Wins++ }; seatP0++ }
                    p0Lost && p1Lost -> cells.forEach { it.mutual++ }
                    else -> cells.forEach { it.unfinished++ }
                }
            }
        }
    }

    fun pct(d: Double?) = d?.let { "%5.1f".format(it) } ?: "  --"

    println("\n== MATCHUP MATRIX -- win rate for the ROW deck, on the play ==")
    print("%-26s".format("row \\ column"))
    decks.forEach { print("%12s".format(it.take(11))) }
    println("      n")
    for (a in 0 until n) {
        print("%-26s".format(decks[a].take(25)))
        for (b in 0 until n) print("%12s".format(pct(matchup[a][b].rate())))
        println("  %5d".format((0 until n).sumOf { matchup[a][it].n }))
    }

    // SKEW over non-mirror cells only: a mirror is 50% by construction and
    // including it flatters the number.
    val off = (0 until n).flatMap { a -> (0 until n).mapNotNull { b -> if (a != b) matchup[a][b] else null } }
    val skews = off.mapNotNull { it.rate()?.let { r -> kotlin.math.abs(r - 50.0) } }
    val mutualTotal = (0 until n).sumOf { a -> (0 until n).sumOf { b -> matchup[a][b].mutual } }
    val unfinishedTotal = (0 until n).sumOf { a -> (0 until n).sumOf { b -> matchup[a][b].unfinished } }
    val drawsTotal = mutualTotal + unfinishedTotal
    val gamesTotal = (0 until n).sumOf { a -> (0 until n).sumOf { b -> matchup[a][b].n } }
    println("\nSKEW (mean distance from an even 50, non-mirror cells): %.1f points".format(skews.average()))
    println("worst cell: %.1f points off even".format(skews.maxOrNull() ?: 0.0))
    // Mutual double-KO (a decisive, fast game) and unfinished (turn cap) are
    // reported separately.
    println("games: $gamesTotal   mutual KO: $mutualTotal (%.1f%%)   unfinished at cap: $unfinishedTotal (%.1f%%)".format(
        100.0 * mutualTotal / gamesTotal, 100.0 * unfinishedTotal / gamesTotal))
    if (failures > 0) println("ENGINE FAILURES: $failures  -- these are bugs, not balance")
    println("seat: P0 (on the play) won $seatP0, P1 won $seatP1  -> %.1f%% for the play".format(
        100.0 * seatP0 / (seatP0 + seatP1).coerceAtLeast(1)))

    // MIRRORS measure the PILOTS, not the decks: in a mirror both sides have
    // the same cards, so any departure from 50% is seat advantage plus pilot
    // noise. A wide spread across decks means pilot competence is uneven and
    // every averaged number below is softer than it looks.
    println("\n== MIRRORS (deck vs itself) -- this measures PILOT evenness ==")
    for (a in 0 until n) println("  %-26s %s".format(decks[a].take(25), pct(matchup[a][a].rate())))

    // BEST vs BEST: the same deck matrix, restricted to the pilot pairing that
    // is strongest overall. "The deck is weak" and "two of three policies
    // misplay it" look identical in an average and different here.
    val bestPilot = pilots.maxByOrNull { p ->
        val g = byPilots[p to p]!!
        (0 until n).sumOf { a -> (0 until n).sumOf { b -> g[a][b].p0Wins } }
    } ?: pilots.first()
    println("\n== BEST vs BEST ($bestPilot on both seats) ==")
    val g = byPilots[bestPilot to bestPilot]!!
    print("%-26s".format("row \\ column"))
    decks.forEach { print("%12s".format(it.take(11))) }
    println()
    for (a in 0 until n) {
        print("%-26s".format(decks[a].take(25)))
        for (b in 0 until n) print("%12s".format(pct(g[a][b].rate())))
        println()
    }
    val offBest = (0 until n).flatMap { a -> (0 until n).mapNotNull { b -> if (a != b) g[a][b] else null } }
    val skewBest = offBest.mapNotNull { it.rate()?.let { r -> kotlin.math.abs(r - 50.0) } }
    println("SKEW under $bestPilot only: %.1f points".format(skewBest.average()))

    // Standard error, so a reader knows what is noise. Per cell, decided games.
    val perCell = off.map { it.p0Wins + it.p1Wins }.filter { it > 0 }
    if (perCell.isNotEmpty()) {
        val nAvg = perCell.average()
        println("\nn per non-mirror cell ~%.0f  ->  SE ~%.1f points; treat anything under ~%.0f as noise"
            .format(nAvg, 100.0 * 0.5 / kotlin.math.sqrt(nAvg), 2 * 100.0 * 0.5 / kotlin.math.sqrt(nAvg)))
    }
}

fun main(args: Array<String>) {
    val name = args.getOrNull(0) ?: "core"
    val seeds = args.getOrNull(1)?.toIntOrNull() ?: 8
    val maxTurns = args.getOrNull(2)?.toIntOrNull() ?: 30
    // Resolved through the SHARED catalogue, so `balance` and `cge` agree on
    // what a name means -- including `fork:` ids.
    val doc = catalogue()[name]
    if (doc == null) {
        println("no such game: $name   (known: ${catalogue().keys.joinToString(", ")})")
        return
    }
    // A pilot list can be given to restrict the arm -- one name on both seats
    // is the best-vs-best test, and it needs FAR more seeds than the full
    // matrix because a single pilot pairing has 1/16th the games per cell.
    val pilots = args.drop(3).takeIf { it.isNotEmpty() }
        ?: listOf("Proactive", "Reactive", "Attrition", "Heuristic")
    runBalance(doc, seeds, maxTurns, pilots)
}
