package cge

import ccg.GameDoc
import ccg.PlayerId
import ccg.TurnMode
import ccgui.ALL_POLICIES
import ccgui.HeuristicPilot
import ccgui.PassingPilot
import ccgui.PolicyPilot
import ccg.DeckPick
import ccg.startGame
import ccgui.SeatedPilots
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// The seat-advantage probe: vary ONE thing and see whether the seat rate
// moves. A mirror here means the same deck AND the same pilot on both seats --
// the only arrangement where the seat is the sole difference (Balance.kt's
// mirror row averages over mixed pilot pairings).
// ---------------------------------------------------------------------------

private class Tally {
    var p0 = 0
    var p1 = 0
    var draws = 0
    val decided: Int get() = p0 + p1
    fun rate(): Double? = if (decided == 0) null else 100.0 * p0 / decided
    /** Standard error of [rate] in points, for a fair coin at this n. */
    fun se(): Double = if (decided == 0) 0.0 else 100.0 * 0.5 / sqrt(decided.toDouble())
}

private fun play(
    doc: GameDoc,
    rules: ccg.Rules,
    deck: Int,
    seed: Int,
    pilot: String,
    maxTurns: Int,
    /** Turn order. Reversing it puts "P1" on the play WITHOUT changing which
     *  seat holds which deck -- which is the control that separates "the seat
     *  on the play is disadvantaged" from "the seat named P1 is favoured by
     *  something in setup". In a mirror the decks are identical, so this
     *  changes exactly one thing: who acts first. */
    order: List<PlayerId> = listOf("P0", "P1"),
): Set<PlayerId>? {
    val start = startGame(doc, rules, listOf(DeckPick(deck), DeckPick(deck)), seed, order = order)
    fun mk(seat: PlayerId): ccg.PlayerInput =
        if (pilot == "Heuristic") HeuristicPilot(seat, rules)
        else PolicyPilot(seat, rules, ALL_POLICIES.first { it.name == pilot })
    val pilots = SeatedPilots(mapOf("P0" to mk("P0"), "P1" to mk("P1")), fallback = PassingPilot())
    return runCatching { ccg.playToEnd(rules, start, pilots, maxTurns) }.getOrNull()?.losers
}

/** One arm: every mirror, every pilot, same pilot on both seats.
 *
 *  Tallies by POSITION, not by seat name: `p0` counts wins for whoever is on
 *  the play under [order]. That is what makes the reversed-order arm
 *  comparable to the others rather than a mirror image of one. */
private fun arm(
    label: String,
    doc: GameDoc,
    seeds: Int,
    maxTurns: Int,
    pilots: List<String>,
    order: List<PlayerId> = listOf("P0", "P1"),
): Tally {
    val rules = doc.compile().playable()
    val total = Tally()
    val perDeck = doc.decks.indices.map { Tally() }
    val perPilot = pilots.map { Tally() }
    var failures = 0

    for (d in doc.decks.indices) for ((pi, p) in pilots.withIndex()) repeat(seeds) { s ->
        val losers = play(doc, rules, d, s, p, maxTurns, order)
        if (losers == null) { failures++; return@repeat }
        val t = listOf(total, perDeck[d], perPilot[pi])
        val onThePlay = order[0]
        when {
            losers.size != 1 -> t.forEach { it.draws++ }
            // The seat on the play won iff the OTHER seat is the loser.
            !losers.contains(onThePlay) -> t.forEach { it.p0++ }
            else -> t.forEach { it.p1++ }
        }
    }

    println("\n== ARM: $label ==")
    println("  overall  %5.1f%% for the play   (n=%d decided, %d draws, SE ~%.1f pts)"
        .format(total.rate() ?: 0.0, total.decided, total.draws, total.se()))
    if (failures > 0) println("  ENGINE FAILURES: $failures -- bugs, not balance")
    for (d in doc.decks.indices) {
        println("    %-24s %5.1f%%  (n=%d)".format(doc.decks[d].name.take(23), perDeck[d].rate() ?: 0.0, perDeck[d].decided))
    }
    for ((pi, p) in pilots.withIndex()) {
        println("    pilot %-18s %5.1f%%  (n=%d)".format(p, perPilot[pi].rate() ?: 0.0, perPilot[pi].decided))
    }
    return total
}

fun main(args: Array<String>) {
    val seeds = args.getOrNull(0)?.toIntOrNull() ?: 24
    val maxTurns = args.getOrNull(1)?.toIntOrNull() ?: 30
    val pilots = listOf("Proactive", "Reactive", "Attrition", "Heuristic")
    val core = ccg.Bundled.game("core-bundle")

    println("SEAT PROBE -- mirrors only, SAME pilot on both seats.")
    println("seeds/cell: $seeds  maxTurns: $maxTurns  pilots: ${pilots.joinToString(", ")}")
    println("A mirror with one pilot is the only arrangement where the SEAT is the only difference.")

    // The control. Anything the arms below show is meaningless if this does not
    // reproduce the recorded ~42-45%.
    val base = arm("baseline (as shipped: TurnMode.SHARED)", core, seeds, maxTurns, pilots)

    // THE hypothesis, varied on its own. If the seat gap is caused by the
    // non-initiative seat acting on more information, then a turn structure
    // where players do not interleave inside a phase should shrink or kill it.
    val perPlayer = core.copy(
        rules = core.rules.copy(turn = core.rules.turn.copy(mode = TurnMode.PER_PLAYER)),
    )
    val pp = arm("TurnMode.PER_PLAYER (one field changed)", perPlayer, seeds, maxTurns, pilots)

    // A second, independent lever. `attackDelayOnEntry` is what makes a lane
    // choice a commitment rather than a reaction -- if the gap is about acting
    // on information, removing the commitment should move it too. If BOTH
    // levers move it, "information" is a live explanation; if NEITHER does, the
    // recorded cause is wrong and something symmetric-looking is not.
    val noDelay = core.copy(
        rules = core.rules.copy(params = core.rules.params.copy(attackDelayOnEntry = false)),
    )
    val nd = arm("attackDelayOnEntry = false (one field changed)", noDelay, seeds, maxTurns, pilots)

    // The reversed-order control cannot fail in a symmetric mirror (the reversed
    // game is the forward game relabelled), so it says only one thing: nothing
    // in setup is keyed to the seat NAME. Kept, labelled as such.
    val rev = arm("REVERSED order: P1 on the play (control)", core, seeds, maxTurns, pilots, listOf("P1", "P0"))

    println("\n== READ ==")
    fun line(label: String, t: Tally) {
        val d = (t.rate() ?: 50.0) - (base.rate() ?: 50.0)
        println("  %-42s %5.1f%%   shift vs baseline: %+5.1f pts (SE ~%.1f)"
            .format(label, t.rate() ?: 0.0, d, sqrt(t.se() * t.se() + base.se() * base.se())))
    }
    line("baseline", base)
    line("PER_PLAYER", pp)
    line("attackDelayOnEntry = false", nd)
    line("reversed order (control)", rev)
    println()
    println("  CONTROL: the reversed arm is EXPECTED to be identical -- in a mirror it is")
    println("  a pure relabeling. Identical means 'no seat-NAME-keyed asymmetry in setup',")
    println("  and nothing more. It cannot tell you the effect follows initiative.")
    println("\n  A shift smaller than ~2x its SE is not a result.")
}
