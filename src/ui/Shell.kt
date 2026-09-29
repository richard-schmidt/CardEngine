package ccgui

import ccg.PlayerId

// ---------------------------------------------------------------------------
// The app shell's navigation, as data.
//
// One root -- the Shelf -- and a stack of routes above it. Every Back rule
// lives HERE, where UiTest can see it; the Compose shell draws the top route
// and hands Back to `Nav.back`.
//
// The rules, in one place:
//   - a rail tap is SIDEWAYS: the game's frames become [Overview, module],
//     so a module's root goes Back to Overview;
//   - entering a game from the Shelf straight into a module (Play, Continue)
//     goes Back to the Shelf -- Back retraces the steps actually taken;
//   - a drill-in or a cross-jump PUSHES, so Back returns to where you tapped;
//   - leaving the table asks first (the game is parked, not lost);
//   - leaving a game's workspace with unsaved edits asks first;
//   - only the Shelf closes the app.
// ---------------------------------------------------------------------------

/** The workspace rail, in order. Pool is a view inside Cards; Test is a lens
 *  on Play. */
enum class Module(val key: String, val label: String) {
    OVERVIEW("overview", "Overview"),
    RULES("rules", "Rules"),
    CARDS("cards", "Cards"),
    DECKS("decks", "Decks"),
    PLAY("play", "Play"),
}

/** Where inside a module a route points. */
sealed interface Focus {
    /** A card's editor (Cards). */
    data class Card(val index: Int) : Focus

    /** One Rules section, by its name (the section enum lives in the app). */
    data class Section(val name: String) : Focus

    /** The table: a game on the board (Play). Without it, Play is its setup. */
    data object Table : Focus
}

sealed interface Route {
    data object Shelf : Route
    data class Game(val id: String, val module: Module, val focus: Focus? = null) : Route
}

/** Who sits at a table. How you LOOK at it is the lens (`PlayMode`), a
 *  separate choice. */
enum class TableKind(val label: String) {
    /** You at P0, the bot at P1. */
    VS_AI("vs AI"),

    /** Both seats by hand: every hand is open (there is no handoff screen
     *  yet), so this table is always in the Debug lens. */
    HOTSEAT("both seats"),
}

/** The lens a new table opens in. */
fun TableKind.defaultLens(): PlayMode = when (this) {
    TableKind.VS_AI -> PlayMode.PLAYER
    TableKind.HOTSEAT -> PlayMode.PLAYTEST
}

/** May the lens be switched? A both-seats table has no Play lens to switch
 *  to until there is a handoff between seats. */
fun TableKind.lensSwitchable(): Boolean = this == TableKind.VS_AI

/** Undo for a PERSON: back to just before the last answer anyone but
 *  [bot] gave, so the bot's replies since go too -- dropping only the last
 *  answer against a bot drops the bot's reply, which it gives again at once.
 *  [askedBy] names who gave each answer, in order. Null: nothing to undo. */
fun undoPoint(askedBy: List<PlayerId>, bot: PlayerId?): Int? =
    askedBy.indexOfLast { it != bot }.takeIf { it >= 0 }

/** The shell's shape: the rail beside the content and two panes where the
 *  width allows (landscape), or the rail below (portrait). The play
 *  surface's own rule, so the shell and the board never disagree. */
enum class ShellShape { PORTRAIT, LANDSCAPE }

fun shellShapeFor(widthDp: Int, heightDp: Int): ShellShape =
    if (playSurfaceFor(widthDp, heightDp) == PlaySurface.RAILED) ShellShape.LANDSCAPE else ShellShape.PORTRAIT

/** Why Back stops to ask. */
enum class Guard {
    /** Leaving the table: the game is parked under Continue. */
    LEAVE_TABLE,

    /** Leaving a game's workspace with unsaved edits. */
    UNSAVED,
}

sealed interface Back {
    data class Go(val nav: Nav) : Back

    /** Ask [guard]; on yes, carry on with [then] -- which may ask again
     *  (leaving the table of a game with unsaved edits asks both). */
    data class Ask(val guard: Guard, val then: Back) : Back

    /** The Shelf: the system closes the app. */
    data object Exit : Back
}

data class Nav(val stack: List<Route>) {
    init { require(stack.firstOrNull() == Route.Shelf) { "the Shelf is always the bottom of the stack" } }

    val top: Route get() = stack.last()

    /** The game the top route is in, or null on the Shelf. */
    val gameId: String? get() = (top as? Route.Game)?.id

    /** Enter game [id] from wherever you are, at [module] (Overview by
     *  default). Any other game's frames go: one game is open at a time. */
    fun openGame(id: String, module: Module = Module.OVERVIEW, focus: Focus? = null): Nav =
        Nav(listOf(Route.Shelf, Route.Game(id, module, focus)))

    /** A rail tap: sideways. The game's frames become Overview, plus
     *  [module]'s root when it is not Overview. */
    fun switchModule(module: Module, focus: Focus? = null): Nav {
        val id = gameId ?: return this
        val base = Route.Game(id, Module.OVERVIEW)
        return Nav(
            listOf(Route.Shelf) +
                if (module == Module.OVERVIEW && focus == null) listOf(base)
                else listOf(base, Route.Game(id, module, focus)),
        )
    }

    /** A drill-in or a cross-jump: Back returns here. */
    fun push(r: Route): Nav = if (r == top) this else Nav(stack + r)

    /** Open a detail from its list: a PUSH, except from another detail of the
     *  same module, which it REPLACES -- in two panes the list stays in view,
     *  and picking card after card must not stack a Back for each. */
    fun openDetail(r: Route.Game): Nav {
        val t = top as? Route.Game
        return if (t != null && t.id == r.id && t.module == r.module && t.focus != null && r.focus != null &&
            t.focus::class == r.focus::class
        ) replaceTop(r) else push(r)
    }

    /** Replace the top route: Play's setup becoming its table, and back. */
    fun replaceTop(r: Route): Nav = if (stack.size == 1) push(r) else Nav(stack.dropLast(1) + r)

    /** Back from here. [tableRunning]: a game is on the table and under way;
     *  [dirty]: the open game has unsaved edits. */
    fun back(tableRunning: Boolean, dirty: Boolean): Back {
        if (stack.size == 1) return Back.Exit
        val popped = Nav(stack.dropLast(1))
        val t = top
        if (t is Route.Game && t.focus == Focus.Table && tableRunning) return Back.Ask(Guard.LEAVE_TABLE, back(false, dirty))
        if (dirty && popped.gameId != gameId) return Back.Ask(Guard.UNSAVED, Back.Go(popped))
        return Back.Go(popped)
    }

    /** The shell's rail highlight: the top route's module, null on the Shelf. */
    val module: Module? get() = (top as? Route.Game)?.module

    /** The rail hides while a game is on the table (the board takes the whole
     *  screen, and the edge-swipe stands down for the lanes). */
    val immersive: Boolean get() = (top as? Route.Game)?.focus == Focus.Table

    companion object {
        val SHELF = Nav(listOf(Route.Shelf))
    }
}

// -- Saving it -------------------------------------------------------------
// The stack survives process death as one string (the app saves it with
// `rememberSaveable`). Unit and record separators, like the session codec;
// ids are store-minted and never contain them.

private const val NAV_RS = '\u001E'
private const val NAV_US = '\u001F'

fun Nav.encode(): String = stack.joinToString(NAV_RS.toString()) { r ->
    when (r) {
        Route.Shelf -> "shelf"
        is Route.Game -> listOf(
            "game", r.id, r.module.key,
            when (val f = r.focus) {
                null -> ""
                is Focus.Card -> "card:${f.index}"
                is Focus.Section -> "section:${f.name}"
                Focus.Table -> "table"
            },
        ).joinToString(NAV_US.toString())
    }
}

/** The stack back, or the Shelf alone for anything this build cannot read. */
fun decodeNav(text: String): Nav {
    val routes = text.split(NAV_RS).map { rec ->
        val f = rec.split(NAV_US)
        when (f[0]) {
            "shelf" -> Route.Shelf
            "game" -> {
                val module = Module.entries.firstOrNull { it.key == f.getOrNull(2) } ?: return Nav.SHELF
                val focus = f.getOrNull(3).orEmpty().let { s ->
                    when {
                        s.isEmpty() -> null
                        s == "table" -> Focus.Table
                        s.startsWith("card:") -> Focus.Card(s.removePrefix("card:").toIntOrNull() ?: return Nav.SHELF)
                        s.startsWith("section:") -> Focus.Section(s.removePrefix("section:"))
                        else -> return Nav.SHELF
                    }
                }
                Route.Game(f.getOrNull(1)?.ifEmpty { null } ?: return Nav.SHELF, module, focus)
            }
            else -> return Nav.SHELF
        }
    }
    return if (routes.firstOrNull() == Route.Shelf) Nav(routes) else Nav.SHELF
}

// -- A parked game ---------------------------------------------------------

/** A game left on the table: which kind of table, and its session. Saved per
 *  game id, so Continue on the Shelf reaches it and a process death does not
 *  lose it. */
data class ParkedGame(val kind: TableKind, val session: PlaySession)

fun ParkedGame.encode(): String = kind.name + "\n" + session.encode()

fun decodeParkedGame(text: String): ParkedGame? {
    val cut = text.indexOf('\n').takeIf { it > 0 } ?: return null
    // "DEBUG" is the old name for HOTSEAT; a table parked under it still resumes.
    val tag = text.substring(0, cut).let { if (it == "DEBUG") "HOTSEAT" else it }
    val kind = TableKind.entries.firstOrNull { it.name == tag } ?: return null
    return decodePlaySession(text.substring(cut + 1))?.let { ParkedGame(kind, it) }
}
