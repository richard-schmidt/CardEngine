package ccgui

import ccg.CardDoc
import ccg.Diagnostic
import ccg.GameDoc

// ---------------------------------------------------------------------------
// Where a diagnostic takes the author.
//
// `GameDoc.diagnostics()` says which card, which face and which part of it;
// this turns that into the Creator's own places -- a card to open and a block
// to mark. Decided here rather than in Compose so test.sh can see it.
// ---------------------------------------------------------------------------

/** The part of the card editor a diagnostic points at. Mirrors the Creator's
 *  behaviour blocks, plus [FACE] for the face's own fields (name, types,
 *  keywords) and anything with no block of its own. */
enum class CardArea { FACE, CAST, TRIGGERS, STATICS, ACTIVATED, ENTERS, RECAST, REPLACEMENTS }

/** The editor area a card diagnostic's `path` names. */
fun cardAreaOf(path: String): CardArea = when (path.substringBefore('[').substringBefore('.')) {
    "castEffect" -> CardArea.CAST
    "triggers" -> CardArea.TRIGGERS
    // The Statics block edits all three lists.
    "statics", "ruleMods", "costMods" -> CardArea.STATICS
    "activated" -> CardArea.ACTIVATED
    "entersWith" -> CardArea.ENTERS
    "recast" -> CardArea.RECAST
    "replacements" -> CardArea.REPLACEMENTS
    else -> CardArea.FACE
}

/** A card in the Creator: its set, its index in that set, and a face. */
data class CardLocation(val set: Int, val card: Int, val face: Int)

/** Where tapping [d] takes the author, or null for a game-level diagnostic
 *  (or one naming a card no set holds). */
fun locate(game: GameDoc, d: Diagnostic): CardLocation? {
    val key = d.cardKey ?: return null
    game.sets.forEachIndexed { si, s ->
        s.cards.forEachIndexed { ci, c -> if (c.key() == key) return CardLocation(si, ci, d.face ?: 0) }
    }
    return null
}

/** The diagnostics about [face] of [card], by the area that shows them. One
 *  with no face (a duplicate name) belongs to every face. */
fun diagnosticsByArea(all: List<Diagnostic>, card: CardDoc, face: Int): Map<CardArea, List<Diagnostic>> =
    all.filter { it.cardKey == card.key() && (it.face == null || it.face == face) }.groupBy { cardAreaOf(it.path) }
