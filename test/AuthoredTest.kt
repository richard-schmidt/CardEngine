package ccg

import ccgui.ALL_POLICIES
import ccgui.ObservedPilot
import ccgui.PassingPilot
import ccgui.PlaySession
import ccgui.PolicyPilot
import ccgui.SeatedPilots
import ccgui.opening
import ccgui.replayCorpusCase
import java.io.File

// ---------------------------------------------------------------------------
// Games authored in the Creator, played headless. Creator edits live in app-private
// `games/<id>.json`, which the host cannot read, so the bridge is a FILE DROP:
// export from the Creator, `sync-authored.sh` (called by test.sh) copies into
// ~/cge-test, and every game there runs here. One-directional on purpose:
// nothing writes back to the device.
// ---------------------------------------------------------------------------

/** Where a synced export lands. `$HOME/cge-test`, resolved rather than
 *  hard-coded so this still works if the harness is ever run elsewhere. */
private fun authoredDir(): File =
    File(System.getenv("CGE_TEST_DIR") ?: (System.getenv("HOME") ?: ".") + "/cge-test")

internal fun authoredChecks() {
    scenarioCaseChecks()
    val dir = authoredDir()
    val files = dir.listFiles { f: File -> f.isFile && f.name.endsWith(".json") }
        ?.sortedBy { it.name }
        .orEmpty()

    // AN EMPTY FOLDER IS NOT A FAILURE, but it must not be SILENT either.
    // A harness that says nothing when it found nothing is indistinguishable
    // from one that ran and passed, which is how a drop folder quietly stops
    // being used and nobody notices for a month.
    if (files.isEmpty()) {
        println("  --    no authored games in ${dir.path} (export from the Creator, then sync)")
        return
    }

    for (f in files) {
        // PARSING IS ITS OWN CHECK. A file that will not parse is a real
        // failure and names itself -- this is the only place a Creator save's
        // round-trip meets a reader outside the app.
        var doc: GameDoc? = null
        check("authored ${f.name} parses as a game") {
            doc = runCatching { gameDocFromJson(f.readText()) }.getOrElse {
                throw AssertionError("${f.name}: ${it.message}")
            }
        }
        val g = doc ?: continue

        // DELEGATED to `GameDoc.problems()`, THE validator, rather than restated:
        // a restated card-name check once failed a correctly id-migrated deck.
        check("authored ${f.name} -- the game validates (GameDoc.problems)") {
            assertTrue(g.rules().cards.isNotEmpty(), "${f.name} has no cards at all")
            val problems = g.problems()
            assertTrue(
                problems.isEmpty(),
                "${f.name}: ${problems.joinToString("; ")}",
            )
        }

        // Every ordered pairing under every policy, played to a finish -- what
        // catches an action legality permits and the engine then declines (a
        // frozen game on a device; here, a named deck, policy and trail).
        // Skipped, not failed, for a game with fewer than two decks.
        if (g.decks.size < 2) {
            check("authored ${f.name} -- pairings") {
                println("        (only ${g.decks.size} deck(s) -- nothing to pair)")
            }
            continue
        }

        check("authored ${f.name} -- EVERY deck pairing plays to a finish under every policy") {
            val rules = g.rules()
            for (a in g.decks.indices) for (b in g.decks.indices) {
                for (p in ALL_POLICIES) {
                    val start = PlaySession(p0Deck = a, p1Deck = b, seed = 20260909).opening(g, rules)
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
                        "${g.decks[a].name} vs ${g.decks[b].name} under ${p.name}: ${why?.message}" +
                            "  [${trail.size} actions; last: ${trail.takeLast(4).joinToString(" | ")}]",
                    )
                }
            }
        }
    }
}

/** Sandbox scenarios exported as conformance cases: each a bundle
 *  and a recording, synced into `cases/`. A case must replay to the states it
 *  recorded -- a scenario someone kept is a position the engine has promised
 *  to reach again. A case the engine no longer reaches names the question. */
private fun scenarioCaseChecks() {
    val files = File(authoredDir(), "cases").listFiles { f: File -> f.isFile && f.name.endsWith(".json") }
        ?.sortedBy { it.name }.orEmpty()
    if (files.isEmpty()) {
        println("  --    no scenario cases in ${authoredDir().path}/cases (Play › Sandbox › export, then sync)")
        return
    }
    for (f in files) {
        check("scenario case ${f.name} replays to the states it recorded") {
            val corpus = corpusFromJson(f.readText())
            val rules = corpus.bundle.compile().playable()
            corpus.cases.forEachIndexed { i, case ->
                val (seen, end) = replayCorpusCase(corpus.bundle, rules, case)
                val at = case.digests.indices.firstOrNull { case.digests[it] != seen.getOrNull(it) }
                assertTrue(at == null && seen.size == case.digests.size, "case $i diverges at question ${at ?: seen.size}")
                assertEq(case.end, end, "case $i ends where it did")
            }
        }
    }
}
