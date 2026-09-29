package ccgui

import ccg.GameState
import ccg.PlayerId
import ccg.Rules
import ccg.viewFor

// ---------------------------------------------------------------------------
// stateAsSeenBy -- turning "the pilot does not look" into "the pilot cannot".
//
// Not in the engine: the engine is the REFEREE and sees everything; redaction
// belongs to a client standing for one player. It defers to `Viewpoint`, the
// one visibility policy -- a pilot redacting differently from the UI would pass
// every test and quietly measure a different game.
//
// WHAT IT DOES NOT HIDE:
//  - the battlefield, graveyards and exile (public in any card game);
//  - COUNTS anywhere: a masked zone keeps its length and instance ids, so a
//    chosen target id still resolves. Only identities go;
//  - the RNG inside `GameState` (library contents are masked, so it buys no
//    lookahead unless a pilot simulated future shuffles);
//  - `Question.PickCards`, whose candidates the engine hands over as real refs.
// ---------------------------------------------------------------------------

/** This state as `view` is entitled to see it: in the Player, exactly the
 *  engine's `viewFor` (one rule); in the Playtest debugger, everything. */
fun GameState.asSeenBy(view: Viewpoint): GameState =
    if (view.mode == PlayMode.PLAYTEST) this else viewFor(view.viewer, view.declaredPublic)

/** The seat's-eye view a PILOT gets: `PlayMode.PLAYER`, and the bundle's own
 *  public zones honoured. */
fun GameState.asSeenBy(seat: PlayerId, rules: Rules): GameState = viewFor(seat, rules)
