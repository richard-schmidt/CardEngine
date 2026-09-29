package ccgui

import ccg.CastZone
import ccg.DeckPick
import ccg.EditZone
import ccg.HiddenZone
import ccg.TableEdit

// ---------------------------------------------------------------------------
// Starting a sandbox table, and keeping one. The engine
// half is `ccg.TableEdit` (src/ccg/Sandbox.kt); this is what the Play setup
// and the table's drawer decide, here so it is tested.
// ---------------------------------------------------------------------------

/** A new sandbox table: the presets that replaced the Playtest tab's Single
 *  card and Scripted runs. Both became a table you start from a position:
 *  the cards you want in hand, on an empty table or the game's decks, with or
 *  without the bot. */
data class SandboxSetup(
    /** No decks: empty libraries, nothing begins in play, and nobody decks
     *  out (`DeckPick.NONE`). */
    val empty: Boolean = true,
    /** The bot holds P1 (a vs AI table, opened in the Debug lens). Off, both
     *  seats are played by hand. */
    val bot: Boolean = false,
    /** Card keys put into P0's hand as the game opens, in order. */
    val hand: List<String> = emptyList(),
)

fun SandboxSetup.kind(): TableKind = if (bot) TableKind.VS_AI else TableKind.HOTSEAT

/** The session the table starts: [base] (a new game, its random picks already
 *  resolved), with no decks when the table is empty. */
fun SandboxSetup.session(base: PlaySession): PlaySession =
    if (empty) base.copy(p0Deck = DeckPick.NONE, p1Deck = DeckPick.NONE, p0Random = false, p1Random = false, sandbox = true)
    else base.copy(sandbox = true)

/** The edits that set the position up: answers like any other, so Undo can
 *  take one back and a saved scenario keeps them. */
fun SandboxSetup.edits(): List<TableEdit> = hand.map { TableEdit.Conjure(it, "P0", EditZone.HAND) }

/** A session for a game that is not a sandbox: no bench, and an empty seat
 *  (`DeckPick.NONE`) left behind by an empty table takes the opening pick
 *  `PlaySession.forGame` would give it. */
fun PlaySession.decked(deckCount: Int): PlaySession {
    val fresh = PlaySession.forGame(deckCount)
    return copy(
        sandbox = false,
        p0Deck = if (p0Deck == DeckPick.NONE) fresh.p0Deck else p0Deck,
        p1Deck = if (p1Deck == DeckPick.NONE) fresh.p1Deck else p1Deck,
    )
}

/** A sandbox table saved by name. A position is a session -- seed, decks and
 *  every answer, the edits included -- so reopening one is a replay, and it
 *  exports as a conformance case (`PlaySession.asCorpusCase`). */
data class Scenario(val name: String, val table: ParkedGame)

fun Scenario.encode(): String = name.replace('\n', ' ') + "\n" + table.encode()

fun decodeScenario(text: String): Scenario? {
    val cut = text.indexOf('\n').takeIf { it > 0 } ?: return null
    return decodeParkedGame(text.substring(cut + 1))?.let { Scenario(text.substring(0, cut), it) }
}

/** The scenario's session, to put on the table in place of [current]: bound
 *  to the game as it is NOW ([bundle]), so its answers replay under today's
 *  rules instead of being dropped as a stale session's are. A card edited
 *  since may change where they lead; the table's log shows where a replay
 *  stops. A later generation than [current], so the table rebuilds. */
fun Scenario.reopened(bundle: String, current: PlaySession): PlaySession =
    table.session.copy(bundle = bundle, generation = maxOf(current.generation, table.session.generation) + 1)

/** A scenario's file name: the name, down to what every file system takes. */
fun scenarioFileName(name: String): String =
    name.trim().lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")
        .replace(Regex("-+"), "-").trim('-').ifEmpty { "scenario" }.take(48)

/** What a move sheet on a card offers (the Debug lens's tap-to-move): every
 *  zone but the one the card is in. */
fun moveTargets(from: EditZone): List<EditZone> = EditZone.entries.filter { it != from }

/** The edit zone a card lane plays from: null for a declared zone (a
 *  Flagship pool), which the sandbox does not move cards out of. */
fun editZoneOf(zone: CastZone): EditZone? = when ((zone as? CastZone.Std)?.zone) {
    HiddenZone.HAND -> EditZone.HAND
    HiddenZone.GRAVEYARD -> EditZone.GRAVEYARD
    HiddenZone.EXILE -> EditZone.EXILE
    HiddenZone.LIBRARY, HiddenZone.LIBRARY_BOTTOM -> EditZone.LIBRARY_TOP
    null -> null
}

/** The same, for the zone browser's names. */
fun editZoneNamed(zone: String): EditZone? = when (zone) {
    "hand" -> EditZone.HAND
    "library" -> EditZone.LIBRARY_TOP
    "graveyard" -> EditZone.GRAVEYARD
    "exile" -> EditZone.EXILE
    else -> null
}
