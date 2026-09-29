package ccg

// Seeded Mulberry32 -- deterministic: same seed -> same sequence, so a whole
// game replays exactly from a recorded seed + input log. Its state lives in
// `GameState.rngState`.

class Rng(seed: Int) {
    private var state: Int = seed

    /** The advanced state, to be stored back on the `GameState`. Randomness in
     *  a replay-based engine has to live IN the state, not beside it -- undo
     *  re-runs the whole game from the recorded answers, so a shuffle that
     *  read a hidden global would deal a different deck each time you undid. */
    val seedState: Int get() = state

    /** Next float in [0, 1). */
    fun nextFloat(): Double {
        state += 0x6D2B79F5
        var t = state
        t = (t xor (t ushr 15)) * (t or 1)
        t = t xor (t + (t xor (t ushr 7)) * (t or 61))
        val r = (t xor (t ushr 14)) and 0xFFFFFFFF.toInt()
        return (r.toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0
    }

    fun nextInt(bound: Int): Int = (nextFloat() * bound).toInt()
}

/** Fisher-Yates using an Rng -- pure, returns a new list. */
fun <T> shuffle(items: List<T>, rng: Rng): List<T> {
    val a = items.toMutableList()
    for (i in a.indices.reversed()) {
        val j = rng.nextInt(i + 1)
        val tmp = a[i]; a[i] = a[j]; a[j] = tmp
    }
    return a
}
