package cge

import ccg.Bundled
import ccg.GameDoc
import ccg.gameDocFromJson
import java.io.File

// ---------------------------------------------------------------------------
// What games exist, for EVERY agent-side tool -- one definition, so `cge` and
// `balance` cannot disagree about what a name means.
//
// Public, not `internal`: each kotlinc invocation in build-agent.sh is its own
// MODULE, and `internal` does not cross it.
// ---------------------------------------------------------------------------

fun home(): String = System.getenv("HOME") ?: "."

/** Where the user's exports land -- the SAME folder `test.sh` plays. Read-only
 *  as far as this agent is concerned. */
fun testDir(): File = File(System.getenv("CGE_TEST_DIR") ?: home() + "/cge-test")

/** Where FORKS live -- not [testDir], which is test.sh's authored corpus (a fork
 *  there would silently become a test case). The only directory this agent
 *  writes to. */
fun forkDir(): File = File(System.getenv("CGE_FORK_DIR") ?: home() + "/cge-forks")

/** Every game an agent may open: the two bundled ones, the user's exports, and
 *  its own forks. Forks are loaded LAST and under a `fork:` prefix, so a fork
 *  can never shadow the game it was derived from -- comparing a variant against
 *  the wrong parent is the one failure this whole feature exists to avoid. */
fun catalogue(): Map<String, GameDoc> = buildMap {
    put("core", Bundled.game("core-bundle"))
    put("epr", Bundled.game("epr-skirmish"))
    fun load(dir: File, prefix: String) {
        dir.listFiles { f: File -> f.isFile && f.name.endsWith(".json") }?.sortedBy { it.name }?.forEach { f ->
            runCatching { gameDocFromJson(f.readText()) }.getOrNull()?.let {
                put(prefix + f.name.removeSuffix(".json"), it)
            }
        }
    }
    load(testDir(), "")
    load(forkDir(), "fork:")
}

