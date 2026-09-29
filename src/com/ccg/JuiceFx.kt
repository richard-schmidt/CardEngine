package com.ccg

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ccg.ObjectId
import ccgui.BoardFx
import ccgui.JuiceConfig
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

// ---------------------------------------------------------------------------
// Board motion, the Compose half. WHAT it reacts to is `ccgui.boardFx` (pure,
// tested); HOW it looks is here, and only a person looking at the device can
// check it. Nothing is load-bearing: `JuiceConfig.NONE` (the toggle, or the
// OS reduced-motion setting) turns it all off and the board stays correct.
// ---------------------------------------------------------------------------

/** A tile taking a hit: a decaying shake plus a colour wash. Keyed on [seq],
 *  not [damage], so two consecutive identical hits both play. Amplitude scales
 *  with the hit and is clamped, so small hits still read as hits. */
@Composable
fun impactMotion(damage: Int, seq: Int, cfg: JuiceConfig, reducedMotion: Boolean): Modifier {
    if (!cfg.enabled || reducedMotion || damage == 0) return Modifier
    val anim = remember { Animatable(0f) }
    LaunchedEffect(seq) {
        anim.snapTo(0f)
        anim.animateTo(1f, tween(cfg.impactMs, easing = LinearEasing))
    }
    // Healing is not a hit. Same motion, opposite colour -- and NOT a different
    // shape, because the engine treats them as one signed quantity and the
    // board should not invent a distinction the rules do not have.
    val tint = if (damage > 0) Cg.danger else Cg.wire
    val scaled = cfg.impactShakeDp * (0.55f + 0.45f * (min(abs(damage), 5) / 5f))
    return Modifier
        .graphicsLayer {
            val p = anim.value
            translationX = sin(p * cfg.impactShakeCycles * 2f * PI.toFloat()) * scaled.dp.toPx() * (1f - p)
        }
        .drawWithContent {
            drawContent()
            val a = (1f - anim.value) * cfg.impactFlash
            if (a > 0f) drawRect(tint.copy(alpha = a * 0.5f))
        }
}

/** A counter ticking on a tile: a scale pop, direction-blind -- a counter's
 *  meaning is the author's, so up and down are not coloured good and bad
 * . */
@Composable
fun counterPop(delta: Int, seq: Int, cfg: JuiceConfig, reducedMotion: Boolean): Modifier {
    if (!cfg.enabled || reducedMotion || delta == 0) return Modifier
    val anim = remember { Animatable(1f) }
    LaunchedEffect(seq) {
        anim.snapTo(1f)
        anim.animateTo(cfg.counterPopScale, tween(cfg.counterMs / 3))
        anim.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 620f))
    }
    return Modifier.graphicsLayer { scaleX = anim.value; scaleY = anim.value }
}

/** One damage numeral, rising off the tile it belongs to and fading out. */
@Composable
private fun BoxScope.Numeral(rect: Rect, amount: Int, cfg: JuiceConfig) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(Unit) { anim.animateTo(1f, tween(cfg.numeralMs, easing = LinearEasing)) }
    val density = LocalDensity.current
    val xDp = with(density) { rect.center.x.toDp() }
    val yDp = with(density) { rect.top.toDp() }
    Text(
        text = if (amount > 0) "-$amount" else "+${-amount}",
        color = (if (amount > 0) Cg.danger else Cg.wire).copy(alpha = (1f - anim.value).coerceIn(0f, 1f)),
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .offset(x = xDp - 14.dp, y = yDp)
            .graphicsLayer {
                translationY = -anim.value * cfg.numeralRiseDp.dp.toPx()
                // A late, small drift outward reads as weight rather than as a
                // label sliding: the number is thrown off the card, not printed
                // above it.
                scaleX = 1f + 0.12f * anim.value
                scaleY = scaleX
            },
    )
}

/** Every damage numeral this transition earned, in the arc overlay's
 *  coordinate space (reusing the arcs' `bounds`, so the two cannot drift). A
 *  permanent with no rectangle yet draws nothing rather than a numeral at the
 *  origin. */
@Composable
fun BoxScope.NumeralOverlay(fx: BoardFx, bounds: Map<ObjectId, Rect>, seq: Int, cfg: JuiceConfig, reducedMotion: Boolean) {
    if (!cfg.enabled || reducedMotion || fx.damage.isEmpty()) return
    for ((id, amount) in fx.damage) {
        val rect = bounds[id] ?: continue
        // Keyed by the transition AND the object, so a second hit on the same
        // tile is a second numeral rather than a restarted one.
        androidx.compose.runtime.key(seq, id) { Numeral(rect, amount, cfg) }
    }
}

/**
 * The impact spray: one `Canvas`, one `Animatable` per burst, `drawCircle` in a
 * loop. The maths is `ccgui.particleBurst` / `particleAt` (tested). Anchored on
 * the same `bounds` as the arcs and numerals.
 */
@Composable
fun BoxScope.ImpactParticles(
    fx: BoardFx,
    bounds: Map<ObjectId, Rect>,
    seq: Int,
    cfg: JuiceConfig,
    reducedMotion: Boolean,
) {
    if (!cfg.enabled || reducedMotion || cfg.particleScale <= 0f || fx.damage.isEmpty()) return
    for ((id, amount) in fx.damage) {
        if (amount <= 0) continue
        val rect = bounds[id] ?: continue
        // Keyed by transition AND object, like the numerals: a second hit on
        // the same tile is a second burst, not a restarted one.
        androidx.compose.runtime.key(seq, id) { Burst(rect, amount, seq, cfg) }
    }
}

@Composable
private fun BoxScope.Burst(rect: Rect, amount: Int, seq: Int, cfg: JuiceConfig) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        anim.snapTo(0f)
        anim.animateTo(1f, tween(cfg.particleMs, easing = LinearEasing))
    }
    val t = anim.value
    if (t >= 1f) return
    // The seed is the tile and the transition, so the SAME hit always throws
    // the same debris -- a recomposition mid-flight must not reshuffle it.
    val parts = remember { ccgui.particleBurst(seq * 31 + rect.left.toInt(), ccgui.particleCountFor(amount, cfg.particleScale)) }
    val density = LocalDensity.current
    Canvas(Modifier.matchParentSize()) {
        val cx = rect.center.x
        val cy = rect.center.y
        // Speeds are fractions of the emitter, so a burst on a small tile is
        // proportionally smaller -- the alternative is a constant pixel spray
        // that swamps a 34dp berth and looks lost on a 420dp card.
        val unit = minOf(rect.width, rect.height).coerceAtLeast(1f)
        for (p in parts) {
            val at = ccgui.particleAt(p, t) ?: continue
            val (dx, dy, a) = at
            drawCircle(
                color = Cg.danger.copy(alpha = a * 0.85f),
                radius = with(density) { p.size.dp.toPx() } * (1f - t * 0.4f),
                center = androidx.compose.ui.geometry.Offset(cx + dx * unit, cy + dy * unit),
            )
        }
    }
}

/** The clock advancing: one band of light across the board -- the smallest
 *  gesture for a frequent event, drawn over the board and eating no input. */
@Composable
fun BoxScope.TurnSweep(seq: Int, play: Boolean, cfg: JuiceConfig, reducedMotion: Boolean) {
    if (!cfg.enabled || reducedMotion) return
    var lastPlayed by remember { mutableStateOf(-1) }
    val anim = remember { Animatable(0f) }
    LaunchedEffect(seq) {
        if (play && seq != lastPlayed) {
            lastPlayed = seq
            anim.snapTo(0f)
            anim.animateTo(1f, tween(cfg.turnSweepMs, easing = LinearEasing))
        }
    }
    val p = anim.value
    if (p <= 0f || p >= 1f) return
    Box(
        Modifier
            .matchParentSize()
            .drawWithContent {
                val bandW = size.width * 0.35f
                val x = -bandW + (size.width + bandW * 2f) * p
                // Fades in and out with the pass so it never starts or ends as
                // a hard edge parked at the screen border.
                val a = (1f - abs(p - 0.5f) * 2f).coerceIn(0f, 1f) * 0.5f
                drawRect(
                    color = Cg.accentLight.copy(alpha = a),
                    topLeft = androidx.compose.ui.geometry.Offset(x, 0f),
                    size = androidx.compose.ui.geometry.Size(bandW, size.height),
                )
            },
    )
}

/** A seat is out: a full-board wash, once, drawn over the board without taking
 *  input (the final position must be readable immediately). */
@Composable
fun BoxScope.EndFlourish(seq: Int, ended: Boolean, cfg: JuiceConfig, reducedMotion: Boolean) {
    if (!cfg.enabled || reducedMotion) return
    var lastPlayed by remember { mutableStateOf(-1) }
    val anim = remember { Animatable(0f) }
    LaunchedEffect(seq) {
        if (ended && seq != lastPlayed) {
            lastPlayed = seq
            anim.snapTo(0f)
            anim.animateTo(1f, tween(cfg.endFlourishMs, easing = LinearEasing))
        }
    }
    val p = anim.value
    if (p <= 0f || p >= 1f) return
    Box(
        Modifier
            .matchParentSize()
            .drawWithContent {
                val a = (1f - abs(p - 0.35f) * 1.6f).coerceIn(0f, 1f) * 0.42f
                drawRect(color = Color.White.copy(alpha = a))
            },
    )
}

// ---------------------------------------------------------------------------
// The tuning sheet: motion feel never lands on the first guess and a rebuild
// costs minutes, so the values are dialled live over the real board. It prints
// its config as a Kotlin constructor call to paste into `JuiceConfig`.
// ---------------------------------------------------------------------------

/** One labelled slider over a Float field of [JuiceConfig]. */
@Composable
private fun Knob(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
        Text(
            "$label   ${"%.2f".format(value)}",
            color = Cg.ink2,
            fontSize = 12.sp,
        )
        androidx.compose.material3.Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
        )
    }
}

/** The tuning sheet: every motion constant, live, over the real board. A
 *  measuring instrument, not a settings screen -- nothing should depend on it. */
@Composable
fun JuiceTuner(cfg: JuiceConfig, onChange: (JuiceConfig) -> Unit, onClose: () -> Unit) {
    androidx.compose.foundation.layout.Column(
        Modifier
            .fillMaxWidth()
            .background(Cg.surfaceAlt)
            .padding(vertical = 10.dp),
    ) {
        Text(
            "  Motion",
            color = Cg.ink,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        Text(
            "  Play a turn with these open. Damage is the one to watch — shake, numeral and particles all fire on it.",
            color = Cg.ink2,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 12.dp),
        )

        Knob("arrival travel (dp)", cfg.arrivalTravelDp, 0f..160f) { onChange(cfg.copy(arrivalTravelDp = it)) }
        Knob("arrival stiffness", cfg.arrivalStiffness, 40f..900f) { onChange(cfg.copy(arrivalStiffness = it)) }
        Knob("arrival damping", cfg.arrivalDamping, 0.15f..1f) { onChange(cfg.copy(arrivalDamping = it)) }
        Knob("arrival spin (deg)", cfg.arrivalSpinDeg, 0f..40f) { onChange(cfg.copy(arrivalSpinDeg = it)) }
        Knob("impact shake (dp)", cfg.impactShakeDp, 0f..28f) { onChange(cfg.copy(impactShakeDp = it)) }
        Knob("impact cycles", cfg.impactShakeCycles, 1f..8f) { onChange(cfg.copy(impactShakeCycles = it)) }
        Knob("impact ms", cfg.impactMs.toFloat(), 90f..1200f) { onChange(cfg.copy(impactMs = it.toInt())) }
        Knob("impact flash", cfg.impactFlash, 0f..1f) { onChange(cfg.copy(impactFlash = it)) }
        Knob("numeral rise (dp)", cfg.numeralRiseDp, 0f..140f) { onChange(cfg.copy(numeralRiseDp = it)) }
        Knob("numeral ms", cfg.numeralMs.toFloat(), 200f..2200f) { onChange(cfg.copy(numeralMs = it.toInt())) }
        Knob("particles", cfg.particleScale, 0f..2.5f) { onChange(cfg.copy(particleScale = it)) }
        Knob("particle ms", cfg.particleMs.toFloat(), 200f..1600f) { onChange(cfg.copy(particleMs = it.toInt())) }
        Knob("counter pop", cfg.counterPopScale, 1f..2f) { onChange(cfg.copy(counterPopScale = it)) }
        Knob("turn sweep ms", cfg.turnSweepMs.toFloat(), 150f..1800f) { onChange(cfg.copy(turnSweepMs = it.toInt())) }
        Knob("end flourish ms", cfg.endFlourishMs.toFloat(), 200f..3000f) { onChange(cfg.copy(endFlourishMs = it.toInt())) }

        // The paste-back line. The whole point of dialling values on a phone is
        // being able to get them off it again.
        Text(
            "  " + cfg.asKotlin(),
            color = Cg.wire,
            fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
        androidx.compose.foundation.layout.Row(Modifier.padding(horizontal = 12.dp)) {
            Chip("Reset", false) { onChange(JuiceConfig.DEFAULT.copy(enabled = cfg.enabled)) }
            androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
            Chip("Close", false) { onClose() }
        }
    }
}

/** The config as the Kotlin that would produce it, for pasting into
 *  `JuiceConfig`'s defaults once the numbers are right. */
private fun JuiceConfig.asKotlin(): String =
    "JuiceConfig(arrivalTravelDp = ${"%.0f".format(arrivalTravelDp)}f, " +
        "arrivalStiffness = ${"%.0f".format(arrivalStiffness)}f, " +
        "arrivalDamping = ${"%.2f".format(arrivalDamping)}f, " +
        "arrivalSpinDeg = ${"%.0f".format(arrivalSpinDeg)}f, " +
        "impactShakeDp = ${"%.1f".format(impactShakeDp)}f, " +
        "impactShakeCycles = ${"%.1f".format(impactShakeCycles)}f, " +
        "impactMs = $impactMs, " +
        "impactFlash = ${"%.2f".format(impactFlash)}f, " +
        "numeralRiseDp = ${"%.0f".format(numeralRiseDp)}f, " +
        "numeralMs = $numeralMs, " +
        "counterPopScale = ${"%.2f".format(counterPopScale)}f, " +
        "turnSweepMs = $turnSweepMs, " +
        "endFlourishMs = $endFlourishMs)"

/** A seat reacting to its own counters moving: a pop plus a wash sized by the
 *  change -- the same gesture as a permanent being hit. Direction colours the
 *  wash and nothing else ("up" is only up). */
@Composable
fun seatMotion(delta: Int, seq: Int, cfg: JuiceConfig, reducedMotion: Boolean): Modifier {
    if (!cfg.enabled || reducedMotion || delta == 0) return Modifier
    val anim = remember { Animatable(0f) }
    LaunchedEffect(seq) {
        anim.snapTo(0f)
        anim.animateTo(1f, tween(cfg.impactMs, easing = LinearEasing))
    }
    val tint = if (delta < 0) Cg.danger else Cg.wire
    val pop = 1f + (cfg.counterPopScale - 1f) * (1f - anim.value)
    return Modifier
        .graphicsLayer {
            scaleX = pop
            scaleY = pop
            translationX = sin(anim.value * cfg.impactShakeCycles * 2f * PI.toFloat()) *
                (cfg.impactShakeDp * 0.5f).dp.toPx() * (1f - anim.value)
        }
        .drawWithContent {
            drawContent()
            val a = (1f - anim.value) * cfg.impactFlash
            if (a > 0f) drawRect(tint.copy(alpha = a * 0.35f))
        }
}
