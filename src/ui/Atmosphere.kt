package ccgui

import ccg.CombatHit
import ccg.Depth
import ccg.GameState
import ccg.Rules
import ccg.ZoneScope

/** Hits newer than any [before] held; nothing without a baseline, so a mid-game board doesn't replay a wave. */
fun newCombatHits(before: GameState?, after: GameState): List<CombatHit> {
    if (before == null) return emptyList()
    val seen = before.combatHits.lastOrNull()?.seq ?: 0
    return after.combatHits.filter { it.seq > seen }
}

/** A star in unit coordinates. */
data class Star(
    val x: Float,
    val y: Float,
    val radiusDp: Float,
    val alpha: Float,
    val layer: Int,
    val phase: Float,
    val glow: Boolean,
    val tint: Int,
)

/** A fixed seed, so the sky never reshuffles between games or frames. */
fun starField(seed: Int = 1729, faint: Int = 170, medium: Int = 26, bright: Int = 4): List<Star> {
    var state = seed
    fun next(): Float {
        state += 0x6D2B79F5
        var z = state
        z = (z xor (z ushr 15)) * (z or 1)
        z = z xor (z + ((z xor (z ushr 7)) * (z or 61)))
        z = z xor (z ushr 14)
        return (z.toLong() and 0xFFFFFFFFL).toFloat() / 4294967296f
    }
    val out = ArrayList<Star>(faint + medium + bright)
    repeat(faint) {
        out += Star(next(), next(), 0.35f + next() * 0.5f, 0.25f + next() * 0.45f, if (next() < 0.6f) 0 else 1, next() * 6.283f, false, (next() * 3).toInt().coerceAtMost(2))
    }
    repeat(medium) {
        out += Star(next(), next(), 0.8f + next() * 0.6f, 0.45f + next() * 0.35f, 1, next() * 6.283f, false, (next() * 3).toInt().coerceAtMost(2))
    }
    repeat(bright) {
        out += Star(next(), next(), 1.3f, 0.9f, 1, next() * 6.283f, true, 0)
    }
    return out
}

/** (x, y, alpha) at drift time [t] seconds; wraps vertically. */
fun starAt(star: Star, t: Float): Triple<Float, Float, Float> {
    val speed = if (star.layer == 1) 0.0045f else 0.0017f
    val y = (star.y + t * speed) % 1f
    val twinkle = 0.8f + 0.2f * kotlin.math.sin(t * 0.8f + star.phase)
    return Triple(star.x, y, star.alpha * twinkle)
}

/** (back, front) zone pairs sharing a lane, from `PlayZoneDef.lane`/`depth`. */
fun coverLinks(rules: Rules): List<Pair<String, String>> {
    val grid = rules.zones.values.filter { it.scope == ZoneScope.PER_PLAYER && it.onGrid }
    val byLane = grid.groupBy { it.lane!! }
    return byLane.keys.sorted().mapNotNull { lane ->
        val zones = byLane.getValue(lane)
        val back = zones.firstOrNull { it.depth == Depth.BACK } ?: return@mapNotNull null
        val front = zones.firstOrNull { it.depth == Depth.FRONT } ?: return@mapNotNull null
        back.id to front.id
    }
}
