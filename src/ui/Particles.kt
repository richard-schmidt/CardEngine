package ccgui

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ---------------------------------------------------------------------------
// PARTICLES: the math, here where test.sh can see it (a burst that silently
// emits zero particles looks exactly like one switched off). Compose supplies a
// Canvas and a clock.
//
// DETERMINISTIC from a seed: a test can assert an exact spray, and a
// recomposition mid-flight cannot reshuffle the burst.
// ---------------------------------------------------------------------------

/** One particle's constants. Position is a pure function of these plus time. */
data class Particle(
    /** Radians, 0 = right, increasing clockwise on screen (y grows downward). */
    val angle: Float,
    /** Initial speed, in fractions of the emitter's size per second. */
    val speed: Float,
    /** Radius in dp at birth. */
    val size: Float,
    /** How long it lives, 0..1 of the burst's own duration -- so a burst ends
     *  ragged rather than every spark stopping on the same frame. */
    val life: Float,
)

/** Mulberry32, the same generator `ccg.Rng` uses, so "deterministic" means the
 *  same thing everywhere in this project. Local because `src/ui` may not reach
 *  into the engine's internals for a cosmetic. */
private fun rnd(state: Int): Pair<Float, Int> {
    var z = state + 0x6D2B79F5
    var t = z
    t = (t xor (t ushr 15)) * (t or 1)
    t = t xor (t + (t xor (t ushr 7)) * (t or 61))
    val v = ((t xor (t ushr 14)) ushr 8) and 0xFFFFFF
    z = t
    return (v.toFloat() / 0xFFFFFF.toFloat()) to z
}

/**
 * A burst of [count] particles over [spreadDeg] degrees around [towardDeg]. Sizes
 * and speeds vary, so it reads as debris rather than an expanding shape.
 */
fun particleBurst(
    seed: Int,
    count: Int,
    towardDeg: Float = 0f,
    spreadDeg: Float = 360f,
    minSpeed: Float = 0.6f,
    maxSpeed: Float = 2.2f,
    minSize: Float = 1.5f,
    maxSize: Float = 3.5f,
): List<Particle> {
    if (count <= 0) return emptyList()
    var st = seed
    return List(count) {
        val (a, s1) = rnd(st); st = s1
        val (b, s2) = rnd(st); st = s2
        val (c, s3) = rnd(st); st = s3
        val (d, s4) = rnd(st); st = s4
        val deg = towardDeg - spreadDeg / 2f + spreadDeg * a
        Particle(
            angle = (deg * PI.toFloat() / 180f),
            speed = minSpeed + (maxSpeed - minSpeed) * b,
            size = minSize + (maxSize - minSize) * c,
            // Never below a third of the burst, or a third of the spray is
            // invisible and the burst reads as thinner than it was asked for.
            life = 0.35f + 0.65f * d,
        )
    }
}

/** Where a particle is at [t] (0..1 of the burst), as a fraction of the
 *  emitter's size, and how opaque. Null once it has expired -- the caller skips
 *  it rather than drawing a zero-alpha dot every frame. */
fun particleAt(p: Particle, t: Float, gravity: Float = 1.4f): Triple<Float, Float, Float>? {
    if (t < 0f || t >= p.life) return null
    // Local time, so a short-lived particle completes its OWN arc rather than a
    // truncated slice of a longer one.
    val u = t / p.life
    // Drag: fast at birth, nearly stopped at death. `1 - (1-u)^2` is the
    // integral of a linearly decaying speed, which is what debris does.
    val travel = p.speed * (1f - (1f - u) * (1f - u))
    val x = cos(p.angle) * travel
    val y = sin(p.angle) * travel + gravity * u * u * 0.5f
    // Holds full opacity for the first third, then fades. A spark that starts
    // fading immediately never looks like it was thrown.
    val alpha = if (u < 0.33f) 1f else 1f - (u - 0.33f) / 0.67f
    return Triple(x, y, alpha.coerceIn(0f, 1f))
}

/** How many particles a hit of [amount] earns: sub-linear (a board of linear
 *  bursts is unreadable) and bounded at both ends. */
fun particleCountFor(amount: Int, scale: Float = 1f): Int {
    if (amount <= 0) return 0
    val n = 4 + (kotlin.math.sqrt(amount.toFloat()) * 4f)
    return (n * scale).toInt().coerceIn(3, 40)
}
