package cge

import ccgui.sections
import ccgui.poolReport

// ---------------------------------------------------------------------------
// `agent/pool <game> [--json]` -- the pool instrument, at the shell.
//
// Reads a game or a fork through `catalogue()`, the
// same resolver `cge` and `balance` use, so the three can never disagree about
// what a name means.
//
// It PRINTS `ccgui.sections()` and formats nothing of its own. The Creator's
// Pool socket renders the same list. A CLI and a screen that format
// independently drift on the next edit; this way they cannot, and the ordering
// and wording stay where `test.sh` can see them.
//
// Deliberately NOT here: balance. `agent/balance` plays thousands of games and
// takes minutes -- it belongs at the shell, not behind a request/response tool,
// and pairing a pool read with a match simulation in one command would hide how
// different their costs are.
// ---------------------------------------------------------------------------

private fun esc(s: String): String = buildString {
    for (c in s) when (c) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\t' -> append("\\t")
        else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
    }
}

fun main(args: Array<String>) {
    val games = catalogue()
    val wantJson = args.any { it == "--json" }
    val name = args.firstOrNull { !it.startsWith("--") }

    if (name == null) {
        println("usage: pool <game> [--json]")
        println()
        println("games:")
        for (k in games.keys) println("  $k")
        return
    }

    val doc = games[name]
    if (doc == null) {
        // Naming the alternatives matters more than the error: a fork id is
        // twelve hex characters and nobody retypes one correctly from memory.
        System.err.println("no such game: $name")
        System.err.println("known: " + games.keys.joinToString(", "))
        kotlin.system.exitProcess(1)
    }

    val report = doc.poolReport()
    val sections = report.sections()

    if (wantJson) {
        print("""{"ok":true,"game":"${esc(name)}","cards":${report.cards.size},"sections":[""")
        print(
            sections.joinToString(",") { s ->
                """{"title":"${esc(s.title)}","note":"${esc(s.note)}","lines":[""" +
                    s.lines.joinToString(",") { l ->
                        """{"text":"${esc(l.text)}"""" +
                            (l.cardKey?.let { ""","card":"${esc(it)}"""" } ?: "") + "}"
                    } + "]}"
            },
        )
        println("]}")
        return
    }

    println("POOL -- $name  (${report.cards.size} cards, ${doc.decks.size} decks)")
    for (s in sections) {
        println()
        println("== ${s.title} ==")
        // The note is not chrome. Several of these measures are easy to
        // misread, and this is where the caveat lives.
        println("   ${s.note}")
        if (s.lines.isEmpty()) {
            println("   (none)")
        } else {
            for (l in s.lines) println("   ${l.text}")
        }
    }
}
