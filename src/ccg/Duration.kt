package ccg

// ---------------------------------------------------------------------------
// Effect durations. A `Duration` bounds continuous effects, delayed triggers
// and prevention shields; `expireDurations` (top of `settle`) drops the ones
// that are done. `startTurn` is stamped at creation and turn-relative cases
// compare it to `GameState.turnNumber`. A static ability keeps `Permanent`:
// it lives exactly as long as its source.
// ---------------------------------------------------------------------------

sealed interface Duration {
    /** Never expires by time (the pre-P6 default; still pruned when its source
     *  permanent leaves play). */
    data object Permanent : Duration

    /** Through the end of the turn it was created on -- gone once a later turn
     *  begins. */
    data object EndOfTurn : Duration

    /** Through the end of the turn AFTER the one it was created on ("until your
     *  next turn" cast on your own turn in a 2-player game). */
    data object EndOfNextTurn : Duration

    /** Lasts while `cond` holds; re-checked each `expireDurations` sweep. */
    data class While(val cond: BoolExpr) : Duration
}
