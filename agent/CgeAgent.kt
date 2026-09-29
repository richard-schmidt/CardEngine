package cge

import ccg.bundleDigest
import ccg.Answer
import ccg.withDeclaredCounters
import ccg.CastZone
import ccg.CombatTarget
import ccg.GameDoc
import ccg.gameDocFromJson
import ccg.gameDocToJson
import ccgui.AgentView
import ccgui.PlaySession
import ccgui.advance
import ccgui.varyTurnMode
import ccgui.varyAttackDelay
import ccgui.varyCardCost
import ccgui.varyCardField
import ccgui.varyAbilityCost
import ccgui.varyEntersWith
import ccgui.decodePlaySession
import ccgui.encode
import java.io.File
import java.util.Base64

// ---------------------------------------------------------------------------
// The headless play surface: one JSON request in, one JSON response out.
// Outside `src/` so it stays out of the APK, and thin: every decision lives in
// `ccgui.AgentPlay` (tested); this file moves values across the process
// boundary. THE SESSION IS THE STATE -- each request carries it and each
// response returns the next, so nothing is remembered between calls (replay is
// exact).
// ---------------------------------------------------------------------------

private fun q(s: String): String = buildString {
    append('"')
    for (c in s) when (c) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
    }
    append('"')
}

private fun arr(xs: List<String>): String = xs.joinToString(",", "[", "]")

// The session TOKEN is base64url of the codec string: the codec's ASCII
// separators (0x1F/0x1E) do not survive a transcript or a shell argument.
// Wrapped at the process boundary only; the tested codec is untouched.
private fun tokenOf(s: PlaySession): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(s.encode().toByteArray())

private fun sessionOf(token: String?): PlaySession? = runCatching {
    decodePlaySession(String(Base64.getUrlDecoder().decode(token ?: return null)))
}.getOrNull()

private fun viewJson(v: AgentView, session: PlaySession): String = buildString {
    append("{")
    append(""""ok":true,""")
    append(""""session":${q(tokenOf(session))},""")
    append(""""over":${v.over},""")
    append(""""player":${v.player?.let { q(it) } ?: "null"},""")
    append(""""question":${q(v.question)},""")
    append(""""turn":${v.turn},""")
    append(""""phase":${q(v.phase)},""")
    append(""""losers":${arr(v.losers.map { q(it) })},""")
    append(""""since":${arr(v.since.map { q(it) })},""")
    append(""""seats":""")
    append(arr(v.seats.map { s ->
        """{"id":${q(s.id)},"library":${s.library},"graveyard":${s.graveyard},""" +
            """"hand":${arr(s.hand.map { q(it) })},""" +
            """"counters":{${s.counters.entries.joinToString(",") { "${q(it.key)}:${it.value}" }}},""" +
            """"pool":{${s.pool.entries.joinToString(",") { "${q(it.key)}:${it.value}" }}},""" +
            """"board":${arr(s.board.map { q(it) })}}"""
    }))
    append(""","options":""")
    append(arr(v.options.map { """{"id":${q(it.id)},"label":${q(it.label)}}""" }))
    append("}")
}

private fun err(m: String): String = """{"ok":false,"error":${q(m)}}"""

fun main(args: Array<String>) {
    val cmd = args.getOrNull(0) ?: "help"
    val games = catalogue()

    fun docOf(name: String?): GameDoc? = games[name ?: "core"]

    val out = when (cmd) {
        // GAMES and FORKS are listed separately.
        // Forks are working material and there will be many; mixing them into
        // the games list makes the thing an agent actually needs to pick from
        // unreadable, and the two are asked about for different reasons.
        // Resolution is unaffected -- every verb still accepts a fork id.
        "games" -> """{"ok":true,"games":${arr(games.filterKeys { !it.startsWith(ccgui.FORK_PREFIX) }.map { (k, g) ->
            """{"id":${q(k)},"name":${q(g.name)},"decks":${arr(g.decks.map { q(it.name) })}}"""
        })}}"""

        "forks" -> """{"ok":true,"forks":${arr(games.filterKeys { it.startsWith(ccgui.FORK_PREFIX) }.map { (k, g) ->
            // Provenance is reported with every fork: a list of variants with
            // no parent and no note is unreadable within a day, which is the
            // whole reason those fields exist.
            """{"id":${q(k)},"name":${q(g.name)},"forkedFrom":${q(g.forkedFrom)},"note":${q(g.forkNote)}}"""
        })}}"""

        // delete-fork <fork> -- explicit, never automatic (a cap would drop a
        // result nobody had written down yet).
        "delete-fork" -> {
            val target = args.getOrNull(1)
            val stem = target?.let { ccgui.forkStemOf(it) }
            when {
                target == null -> err("delete-fork <fork id>")
                stem == null -> err("\"$target\" is not a fork id -- only forks can be deleted, and only by id")
                else -> {
                    val f = File(forkDir(), "$stem.json")
                    if (!f.exists()) err("no fork at ${f.path}")
                    else if (!f.delete()) err("could not delete ${f.path}")
                    else """{"ok":true,"deleted":${q(target)},"path":${q(f.path)}}"""
                }
            }
        }

        // fork <game> <note>  -- derive a variant to iterate on.
        //
        // An agent does not edit a game, it forks it. Writes ONLY into
        // `forkDir()`, never into the user's exports and never into app-private
        // storage (which Termux cannot reach in any case).
        "fork" -> {
            val srcName = args.getOrNull(1)
            val doc = docOf(srcName)
            val note = args.drop(2).joinToString(" ").ifBlank { "unspecified" }
            when {
                doc == null -> err("no such game: $srcName")
                else -> {
                    val newId = java.util.UUID.randomUUID().toString()
                    // A filesystem-safe handle. The UUID is the identity; this
                    // is only what the file is called.
                    val stem = (srcName ?: "core").removePrefix("fork:").replace(Regex("[^A-Za-z0-9_-]"), "-") +
                        "-" + newId.take(8)
                    // MINT CARD IDS FIRST: bundled games carry none (minted on
                    // device by GameStore.save()), and `key()` would fall back
                    // to the display name. `withCardIds` mints only missing ids
                    // and rewrites deck references, so a fork of a game with ids
                    // keeps them. (A fork of a BUNDLED game still joins its
                    // parent by name.) Likewise a game that never declared its
                    // counter kinds declares the ones it uses.
                    val minted = doc.withCardIds { java.util.UUID.randomUUID().toString() }.withDeclaredCounters()
                    val fork = minted.forkedAs(newId, note, name = doc.name + " [" + note + "]")
                    val dir = forkDir()
                    dir.mkdirs()
                    val f = File(dir, "$stem.json")
                    runCatching { f.writeText(gameDocToJson(fork)) }.fold(
                        onSuccess = {
                            """{"ok":true,"fork":${q("fork:$stem")},"id":${q(newId)},""" +
                                """"forkedFrom":${q(fork.forkedFrom)},"note":${q(note)},"path":${q(f.path)}}"""
                        },
                        onFailure = { err("could not write the fork: ${it.message}") },
                    )
                }
            }
        }

        "new" -> {
            val doc = docOf(args.getOrNull(1))
            if (doc == null) err("no such game: ${args.getOrNull(1)}") else {
                val s = PlaySession(
                    seed = args.getOrNull(4)?.toIntOrNull() ?: ccgui.DEFAULT_SEED,
                    p0Deck = args.getOrNull(2)?.toIntOrNull() ?: 0,
                    p1Deck = args.getOrNull(3)?.toIntOrNull() ?: 0,
                    bundle = doc.bundleDigest(),
                )
                viewJson(advance(doc, s), s)
            }
        }

        // vary <fork> <variation> <args...> -- apply ONE named change (see
        // `ccgui` Variation.kt). "This is not a fork" is refused by
        // `ccgui.refuseUnlessFork`, in tested code.
        "vary" -> {
            val target = args.getOrNull(1)
            val doc = docOf(target)
            val what = args.getOrNull(2)
            val rest = args.drop(3)
            fun applied(v: ccgui.Varied): String = when (v) {
                is ccgui.Varied.Refused -> err(v.why)
                is ccgui.Varied.Ok -> {
                    // Through the tested helper, not removePrefix: this turns
                    // a caller-supplied id into a FILENAME, and that is exactly
                    // where a traversal gets in.
                    val stem = target?.let { ccgui.forkStemOf(it) }
                    val f = if (stem == null) null else File(forkDir(), "$stem.json")
                    if (f == null || !f.exists()) {
                        err("\"$target\" is not a fork -- fork it first, then vary the fork")
                    } else {
                        runCatching { f.writeText(gameDocToJson(v.doc)) }.fold(
                            onSuccess = {
                                """{"ok":true,"varied":${q(v.what)},"before":${q(v.before)},""" +
                                    """"after":${q(v.after)},"fork":${q(target ?: "")}}"""
                            },
                            onFailure = { e -> err("could not write the fork: ${e.message}") },
                        )
                    }
                }
            }
            fun manaOf(spec: String): Map<String, Int> = spec.split(",").filter { it.isNotBlank() }
                .associate { part ->
                    val k = part.substringBefore("=", "").let { if (it == "any" || it == "generic") "" else it }
                    k to (part.substringAfter("=", "0").toIntOrNull() ?: 0)
                }
            when {
                doc == null -> err("no such game: $target")
                what == null -> err("vary <fork> <variation> -- one of: ${ccgui.VARIATIONS.joinToString(" | ")}")
                what == "turn_mode" -> applied(doc.varyTurnMode(rest.getOrNull(0) ?: ""))
                what == "attack_delay" -> applied(doc.varyAttackDelay(rest.getOrNull(0)?.toBooleanStrictOrNull() ?: false))
                what == "card_cost" -> {
                    val card = rest.getOrNull(0)
                    if (card == null) err("card_cost <card> <resource>=<n>[,...]")
                    else applied(doc.varyCardCost(card, manaOf(rest.getOrNull(1) ?: "")))
                }
                what == "ability_cost" -> {
                    val card = rest.getOrNull(0)
                    val idx = rest.getOrNull(1)?.toIntOrNull()
                    if (card == null || idx == null) err("ability_cost <card> <index> <resource>=<n>[,...]")
                    else applied(doc.varyAbilityCost(card, idx, manaOf(rest.getOrNull(2) ?: "")))
                }
                what == "enters_with" -> {
                    val card = rest.getOrNull(0)
                    val kind = rest.getOrNull(1)
                    val n = rest.getOrNull(2)?.toIntOrNull()
                    if (card == null || kind == null || n == null) err("enters_with <card> <counter> <n>")
                    else applied(doc.varyEntersWith(card, kind, n))
                }
                what == "card_field" -> {
                    val card = rest.getOrNull(0)
                    val field = rest.getOrNull(1)
                    val n = rest.getOrNull(2)?.toIntOrNull()
                    if (card == null || field == null || n == null) err("card_field <card> <field> <n>")
                    else applied(doc.varyCardField(card, field, n))
                }
                else -> err("no variation \"$what\" -- one of: ${ccgui.VARIATIONS.joinToString(" | ")}")
            }
        }

        "state" -> {
            val doc = docOf(args.getOrNull(1))
            val s = sessionOf(args.getOrNull(2))
            // An optional 4th argument names the seat to report FOR, hiding
            // the other hand. Omitted = both hands, which is what self-play
            // needs and what a game against a person must not use.
            val viewer = args.getOrNull(3)?.takeIf { it.isNotBlank() }
            if (doc == null) err("no such game") else if (s == null) err("bad session") else
                viewJson(advance(doc, s, viewer = viewer), s)
        }

        "act" -> {
            val doc = docOf(args.getOrNull(1))
            val s = sessionOf(args.getOrNull(2))
            val id = args.getOrNull(3)
            when {
                doc == null -> err("no such game")
                s == null -> err("bad session")
                id == null -> err("no option id")
                else -> {
                    val v = advance(doc, s)
                    if (v.over) err("the game is over") else {
                        val a = v.prompt?.let { ccgui.answerFor(doc.compile().rules, it, id) }
                        if (a == null) err("no such option: $id -- call state for the current options")
                        else {
                            val next = s.answered(a)
                            viewJson(advance(doc, next), next)
                        }
                    }
                }
            }
        }

        "cards" -> {
            val doc = docOf(args.getOrNull(1))
            if (doc == null) err("no such game") else
                """{"ok":true,"cards":${arr(doc.cards.map { c ->
                    val f = c.faces.first()
                    """{"name":${q(f.name)},"types":${arr(f.types.map { q(it) })},""" +
                        """"cost":${q(ccgui.costSummary(c.cost))},""" +
                        """"fields":{${f.fields.entries.joinToString(",") { "${q(it.key)}:${it.value}" }}},""" +
                        """"rules":${arr(ccgui.ruleboxOf(c, doc.compile().rules).map { q(it) })}}"""
                })}}"""
        }

        else -> err(
            "commands: games | forks | fork <game> <note> | vary <fork> <variation> ... | " +
                "delete-fork <fork> | " +
                "new <game> <p0deck> <p1deck> [seed] | state <game> <session> | " +
                "act <game> <session> <optionId> | cards <game>   ||   variations: " +
                ccgui.VARIATIONS.joinToString(" | "),
        )
    }
    println(out)
}
