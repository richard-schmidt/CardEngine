package cge

import ccg.GameDoc
import ccg.PlayerId
import ccgui.ALL_POLICIES
import ccgui.HeuristicPilot
import ccgui.PassingPilot
import ccgui.PlaySession
import ccgui.PolicyPilot
import ccgui.SeatedPilots
import ccgui.opening

// ---------------------------------------------------------------------------
// One deadlocked game's final state -- board, hands, pools, whose turn.
//
// Finds the first seed, among the given deck/pilot arm, whose game does NOT
// finish by maxTurns (Engine.playGame's own definition of a fixed point:
// `s.losers.isEmpty() && turns == maxTurns`), then prints the position
// instead of just counting it. One game, read in full, rather than another
// aggregate number.
// ---------------------------------------------------------------------------

private fun pilotFor(name: String, seat: PlayerId, rules: ccg.Rules): ccg.PlayerInput =
    if (name == "Heuristic") HeuristicPilot(seat, rules)
    else PolicyPilot(seat, rules, ALL_POLICIES.first { it.name == name })

fun findAndDumpDeadlock(doc: GameDoc, a: Int, b: Int, p0Pilot: String, p1Pilot: String, seeds: Int, maxTurns: Int) {
    val rules = doc.compile().playable()
    for (s in 0 until seeds) {
        val seed = 20260909 + s * 7919
        val start = PlaySession(p0Deck = a, p1Deck = b, seed = seed).opening(doc, rules)
        val pilots = SeatedPilots(
            mapOf("P0" to pilotFor(p0Pilot, "P0", rules), "P1" to pilotFor(p1Pilot, "P1", rules)),
            fallback = PassingPilot(),
        )
        val state = runCatching { ccg.playToEnd(rules, start, pilots, maxTurns) }
            .getOrElse { err -> println("seed $seed: ENGINE FAILURE: $err"); continue }
        if (System.getenv("CGE_DUMP_VERBOSE") != null) {
            println("seed $seed: turn=${state.turnNumber} losers=${state.losers} " +
                "life=${state.players.mapValues { it.value.life }}")
        }
        if (state.losers.isNotEmpty()) continue // decided, not the case we want
        if (state.turnNumber < maxTurns) continue // ended early some other way

        println("DEADLOCK FOUND: seed=$seed  deck P0=${doc.decks[a].name}  deck P1=${doc.decks[b].name}  pilots=$p0Pilot/$p1Pilot")
        println("turn ${state.turnNumber}  phase=${state.phase}  activePlayer=${state.activePlayer}")
        println()
        for (pid in state.turnOrder) {
            val p = state.players.getValue(pid)
            println("== $pid ==  life=${p.life}  counters=${p.counters}  pool=${p.pool}")
            println("  hand (${p.hand.size}): ${p.hand.map { it.cardId }}")
            println("  library=${p.library.size}  graveyard=${p.graveyard.size}")
            if (p.customZones.isNotEmpty()) println("  customZones: ${p.customZones.mapValues { it.value.size }}")
        }
        println()
        println("battlefield (${state.battlefield.size} permanents):")
        state.battlefield.toSortedMap().forEach { (id, perm) ->
            val chars = state.characteristicsOf(id)
            println("  #$id  ${perm.controller}  zone=${perm.zone}  \"${chars.name}\"  types=${chars.types}" +
                "  fields=${chars.fields}  damage=${perm.damageMarked}  exhausted=${perm.exhausted}" +
                (if (perm.counters.isNotEmpty()) "  counters=${perm.counters}" else "") +
                (if (perm.hostId != null) "  hostOf=${perm.hostId}" else ""))
        }
        println()
        println("last 20 log lines:")
        state.log.takeLast(20).forEach { println("  $it") }
        return
    }
    println("no deadlock found in $seeds seeds for deck pair ($a,$b) under $p0Pilot/$p1Pilot")
}

fun main(args: Array<String>) {
    val name = args.getOrNull(0) ?: "core"
    val a = args.getOrNull(1)?.toIntOrNull() ?: 0
    val b = args.getOrNull(2)?.toIntOrNull() ?: 0
    val p0Pilot = args.getOrNull(3) ?: "Proactive"
    val p1Pilot = args.getOrNull(4) ?: p0Pilot
    val seeds = args.getOrNull(5)?.toIntOrNull() ?: 64
    val maxTurns = args.getOrNull(6)?.toIntOrNull() ?: 30
    val doc = catalogue()[name]
    if (doc == null) {
        println("no such game: $name   (known: ${catalogue().keys.joinToString(", ")})")
        return
    }
    findAndDumpDeadlock(doc, a, b, p0Pilot, p1Pilot, seeds, maxTurns)
}
