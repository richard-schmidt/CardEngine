package cge

import ccg.gameDocToJson

// ---------------------------------------------------------------------------
// `agent/dump <game>` -- a game as JSON, on stdout.
//
// The other half of the export bridge: `sync-authored.sh` pulls a Creator
// export INTO the shell, and this pushes a bundled game out in the same format.
//
// It resolves through `catalogue()` like every other tool here, so `dump core`
// and `dump Core` are the bundled and the device copy, and a plain `diff`
// says where they diverge.
// ---------------------------------------------------------------------------

fun main(args: Array<String>) {
    val games = catalogue()
    val name = args.firstOrNull()

    if (name == null) {
        println("usage: dump <game>")
        println()
        println("games:")
        for (k in games.keys) println("  $k")
        return
    }

    val doc = games[name]
    if (doc == null) {
        System.err.println("no such game: $name")
        System.err.println("known: " + games.keys.joinToString(", "))
        kotlin.system.exitProcess(1)
    }

    println(gameDocToJson(doc))
}
