package ccgui

import ccg.CardDoc
import ccg.Diagnostic
import ccg.GameDoc

// ---------------------------------------------------------------------------
// A game's identity and the jump search.
//
// Each game wears its own colour and cover, taken from its data, so the Shelf
// is a row of boxes rather than a list of names. The jump search reaches a
// card, a rules section, a deck or an issue without knowing which module it
// lives in. Both are decided here, where UiTest sees them; Compose draws them.
// ---------------------------------------------------------------------------

/** The colours a game may wear. Each reads on the app's near-black, and none
 *  is a colour that already means something (wire teal, go green, warn
 *  yellow, danger red). The first is the app's own violet. */
val ACCENTS: List<String> = listOf("#9B7BF0", "#5B9CF5", "#E26BB5", "#F0845A", "#7C84F2", "#C77DDB")

const val DEFAULT_ACCENT = "#9B7BF0"

private val HEX = Regex("#[0-9A-Fa-f]{6}")

/** A `#rrggbb` colour, or null for anything else. */
fun accentOrNull(s: String): String? = s.trim().takeIf { HEX.matches(it) }?.uppercase()

/** The accent a game without one of its own wears: one of [ACCENTS], picked
 *  by its id, so it never changes when other games come and go. */
fun autoAccent(seed: String): String = ACCENTS[((seed.hashCode() % ACCENTS.size) + ACCENTS.size) % ACCENTS.size]

/** [g]'s colour: its own when it names a valid one, else picked by its id. */
fun accentOf(g: GameDoc): String = accentOrNull(g.accent) ?: autoAccent(g.id.ifEmpty { g.name })

/** [hex] as the 0xAARRGGBB the app draws with (opaque). */
fun argbOf(hex: String): Long = 0xFF000000L or (accentOrNull(hex) ?: DEFAULT_ACCENT).drop(1).toLong(16)

/** The card on [g]'s box: the one it names, else the first with a picture,
 *  else the first card. Null for a game with no cards. */
fun coverCard(g: GameDoc): CardDoc? {
    val cards = g.cards
    return cards.firstOrNull { g.cover.isNotEmpty() && it.key() == g.cover }
        ?: cards.firstOrNull { it.faces.firstOrNull()?.art != null }
        ?: cards.firstOrNull()
}

// -- The jump search --------------------------------------------------------

enum class JumpKind(val label: String) { RULES("Rules"), CARD("Cards"), DECK("Decks"), ISSUE("Issues") }

/** Where a hit takes you. The shell pushes it, so Back returns to the search's
 *  starting place. */
sealed interface JumpTo {
    data class Card(val at: CardLocation) : JumpTo
    /** A Rules section, by the app's section name. */
    data class Section(val name: String) : JumpTo
    data class Deck(val index: Int) : JumpTo
    /** A game-level issue: the Overview, where the issues are listed. */
    data object Overview : JumpTo
}

data class JumpHit(val kind: JumpKind, val label: String, val detail: String, val to: JumpTo)

/** A Rules section as the search sees it: its name (the app's
 *  `RulesSection`), its title, and the words from the game that find it. */
data class JumpSection(val name: String, val title: String, val words: List<String> = emptyList())

/** The Rules sections, with what in THIS game each one holds: searching a
 *  phase's name finds the turn structure, a counter's finds Counters. The
 *  names are the app's `RulesSection` entries (ArchReviewTest keeps them in
 *  step). */
fun jumpSections(g: GameDoc): List<JumpSection> {
    val r = g.rules
    return listOf(
        JumpSection("GAME", "The game", listOf(g.name)),
        JumpSection("TURN", "Turn structure", r.turn.phases.map { it.name } + "phase"),
        JumpSection("RESOURCES", "Resources", listOf("mana", "resource", "pool")),
        JumpSection("COUNTERS", "Counters", r.playerCounters.map { it.name } + (r.counterKinds?.map { it.name } ?: emptyList())),
        JumpSection("TYPES", "Types & fields", r.extraTypes.flatMap { listOf(it.name) + it.fields }),
        JumpSection("ZONES", "Play zones", r.extraZones.map { it.id } + "zone"),
        JumpSection("COMBAT", "Combat", listOf("attack", "block", "damage")),
        JumpSection("DECK", "Deck construction", listOf("deck", "legality", "copies")),
    )
}

/** How well [text] matches [q] (lower case): 0 it starts with it, 1 a word in
 *  it does, 2 it contains it; null not at all. */
private fun score(text: String, q: String): Int? {
    val t = text.lowercase()
    return when {
        t.startsWith(q) -> 0
        t.split(' ', '-', '_', '·', '/').any { it.startsWith(q) } -> 1
        q in t -> 2
        else -> null
    }
}

/** Everything in [g] that [query] names, best first within each kind, kinds in
 *  [JumpKind] order, at most [perKind] of each. A match on a hit's label beats
 *  one on its other words (a card's types and keywords, a section's
 *  contents). A blank query finds nothing. */
fun jumpSearch(
    g: GameDoc,
    query: String,
    sections: List<JumpSection> = jumpSections(g),
    issues: List<Diagnostic> = g.diagnostics(),
    perKind: Int = 8,
): List<JumpHit> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    val found = mutableListOf<Pair<Int, JumpHit>>()
    fun offer(hit: JumpHit, label: String, other: List<String>) {
        val s = score(label, q) ?: other.mapNotNull { score(it, q) }.minOrNull()?.let { 3 + it } ?: return
        found += s to hit
    }
    for (sec in sections) offer(JumpHit(JumpKind.RULES, sec.title, "Rules", JumpTo.Section(sec.name)), sec.title, sec.words)
    g.sets.forEachIndexed { si, set ->
        set.cards.forEachIndexed { ci, c ->
            val f = c.faces.firstOrNull() ?: return@forEachIndexed
            val detail = (f.types.sorted() + if (g.sets.size > 1) listOf(set.name) else emptyList()).joinToString(" · ")
            // Every face's name finds the card, at the face it names.
            c.faces.forEachIndexed { fi, face ->
                if (fi == 0 || score(face.name, q) != null) {
                    offer(
                        JumpHit(JumpKind.CARD, face.name, detail, JumpTo.Card(CardLocation(si, ci, fi))),
                        face.name,
                        if (fi == 0) f.types.toList() + f.keywords else emptyList(),
                    )
                }
            }
        }
    }
    g.decks.forEachIndexed { i, d ->
        offer(JumpHit(JumpKind.DECK, d.name, "${d.size} cards", JumpTo.Deck(i)), d.name, d.entries.map { it.cardName })
    }
    for (d in issues) {
        val to = locate(g, d)?.let { JumpTo.Card(it) } ?: JumpTo.Overview
        offer(JumpHit(JumpKind.ISSUE, d.message, d.code, to), d.message, listOf(d.code))
    }
    return found
        .sortedWith(compareBy({ it.second.kind.ordinal }, { it.first }, { it.second.label.lowercase() }))
        .groupBy { it.second.kind }.values.flatMap { it.take(perKind) }.map { it.second }
}
