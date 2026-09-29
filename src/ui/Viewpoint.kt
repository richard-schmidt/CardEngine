package ccgui

import ccg.GameState
import ccg.PlayerId

// ---------------------------------------------------------------------------
// WHO is looking, and what they may see.
//
// The Player (one-sided) and the Creator's Playtest (a debugger) differ in
// arrangement; the POLICY -- which cards a seat may look at -- is one question
// asked from several places (hand lane, seat strip, zone explorer, pilots), so
// it lives here once.
//
// A COUNT is public in every card game; faces are not. So a hidden zone is
// shown WITHOUT its faces, never omitted.
// ---------------------------------------------------------------------------

enum class PlayMode {
    /** The Creator's Playtest tab: a debugger, honestly so. Everything is
     *  visible and every god-mode affordance is available, because the person
     *  looking is the person who wrote the cards. */
    PLAYTEST,

    /** The Player: one seat, one viewpoint, no affordance that a shipped game
     *  would not have. */
    PLAYER,
}

/** How much of a zone the viewer gets. */
enum class ZoneVisibility {
    /** Faces and all. */
    OPEN,

    /** The count only. Not hidden from the UI -- shown without its faces,
     *  because how many cards someone holds is public in every card game. */
    COUNT_ONLY,
}


/** A seat's-eye view of a game. `viewer` is FIXED for the life of a session:
 *  "whose board is this" and "who may see their hand" are one answer. */
data class Viewpoint(
    val mode: PlayMode,
    val viewer: PlayerId,
    /** Zones THIS GAME declares public (`HiddenZoneDef.alwaysVisible`, e.g. a
     *  Flagship pool). A parameter because visibility is a property of the
     *  bundle; `PUBLIC_ZONES` names only the zones every game has. Defaulted:
     *  without rules the answer is conservative. */
    val declaredPublic: Set<String> = emptySet(),
) {

    /** Undo, Restart, Shuffle, Sandbox. Playtest-only, by decision: keeping
     *  Undo in reach quietly changes how you play, which corrupts the
     *  playtesting it exists to serve. */
    val godMode: Boolean get() = mode == PlayMode.PLAYTEST

    /** `owner == null` means a zone shared by everyone (exile). */
    fun visibility(owner: PlayerId?, zone: String): ZoneVisibility = when {
        mode == PlayMode.PLAYTEST -> ZoneVisibility.OPEN
        // The engine's rule (`ccg.zoneOpenTo`), so the board, a pilot and a
        // question can never disagree about what a seat may see.
        ccg.zoneOpenTo(viewer, owner, zone, declaredPublic) -> ZoneVisibility.OPEN
        else -> ZoneVisibility.COUNT_ONLY
    }

    fun mayRead(owner: PlayerId?, zone: String): Boolean =
        visibility(owner, zone) == ZoneVisibility.OPEN

    /** The board's question: does this seat's hand render as cards, or as a
     *  count? */
    fun handFaceUp(owner: PlayerId): Boolean = mayRead(owner, "hand")

    /** Seats in the order the viewer should read them: opponents across the
     *  table, the viewer nearest the thumb -- the same ordering `boardLayout`
     *  already applies to rows, stated once so the strips and the lanes cannot
     *  disagree about which side of the table is yours. */
    fun seatOrder(state: GameState): List<PlayerId> =
        state.turnOrder.filter { it != viewer } + state.turnOrder.filter { it == viewer }
}
