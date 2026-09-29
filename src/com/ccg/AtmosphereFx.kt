package com.ccg

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import ccg.CombatHit
import ccg.ObjectId
import ccg.PlayerId
import ccgui.JuiceConfig

private val STAR_TINTS = listOf(Color(0xFFFFFFFF), Color(0xFFD6E2FF), Color(0xFFE2D6FF))

/** Near-white: every saturated hue on the board already means something. */
private val PULSE = Color(0xFFD6E2FF)

/** The clock is read in the draw lambda, so drifting never recomposes the board. */
@Composable
fun Modifier.starfield(drift: Boolean): Modifier {
    val stars = remember { ccgui.starField() }
    val density = LocalDensity.current.density
    val clock: State<Float> = if (drift) {
        rememberInfiniteTransition(label = "sky").animateFloat(
            initialValue = 0f,
            targetValue = 600f,
            animationSpec = infiniteRepeatable(tween(600_000, easing = LinearEasing), RepeatMode.Restart),
            label = "skyClock",
        )
    } else {
        remember { mutableStateOf(0f) }
    }
    return this.drawBehind {
        val w = size.width
        val h = size.height
        drawRect(Cg.bg)
        drawRect(
            Brush.radialGradient(
                listOf(Cg.accent.copy(alpha = 0.06f), Color.Transparent),
                center = Offset(w * 0.12f, h * 0.10f), radius = w * 0.8f,
            ),
        )
        drawRect(
            Brush.radialGradient(
                listOf(Color(0xFF5A82D2).copy(alpha = 0.05f), Color.Transparent),
                center = Offset(w * 0.90f, h * 0.92f), radius = w * 0.75f,
            ),
        )
        val t = clock.value
        for (s in stars) {
            val (x, y, a) = if (drift) ccgui.starAt(s, t) else Triple(s.x, s.y, s.alpha)
            val c = Offset(x * w, y * h)
            val tint = STAR_TINTS[s.tint]
            if (s.glow) {
                val r = 6f * density
                drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = a * 0.5f), Color.Transparent), center = c, radius = r), radius = r, center = c)
            }
            drawCircle(tint.copy(alpha = a), radius = s.radiusDp * density, center = c)
        }
    }
}

/** Dotted substrate and cover links, drawn behind the board from every seat's lane rects. */
@Composable
fun BoxScope.CoverLinks(
    laneRects: Map<Pair<PlayerId, String>, Rect>,
    links: List<Pair<String, String>>,
    seats: List<PlayerId>,
) {
    if (laneRects.isEmpty()) return
    val density = LocalDensity.current.density
    androidx.compose.foundation.layout.Box(
        Modifier.matchParentSize().drawWithCache {
            val rects = laneRects.values.toList()
            val area = Rect(
                rects.minOf { it.left }, rects.minOf { it.top },
                rects.maxOf { it.right }, rects.maxOf { it.bottom },
            )
            val step = 9f * density
            val dot = 0.6f * density
            val dots = Path()
            var y = area.top + step / 2
            while (y < area.bottom) {
                var x = area.left + step / 2
                while (x < area.right) {
                    dots.addOval(Rect(x - dot, y - dot, x + dot, y + dot))
                    x += step
                }
                y += step
            }
            val line = Cg.steel.copy(alpha = 0.34f)
            val node = Cg.steel.copy(alpha = 0.6f)
            val segments = buildList {
                for (seat in seats) for ((back, front) in links) {
                    val b = laneRects[seat to back] ?: continue
                    val f = laneRects[seat to front] ?: continue
                    val backAbove = b.top < f.top
                    val y1 = if (backAbove) b.bottom else b.top
                    val y2 = if (backAbove) f.top else f.bottom
                    add(Offset(b.left + b.width * 0.78f, y1) to Offset(f.left + f.width * 0.78f, y2))
                }
            }
            onDrawBehind {
                drawPath(dots, Cg.steel.copy(alpha = 0.18f))
                for ((a, b) in segments) {
                    drawLine(line, a, b, strokeWidth = 1.2f * density)
                    drawCircle(node, radius = 1.6f * density, center = a)
                    drawCircle(node, radius = 1.6f * density, center = b)
                }
            }
        },
    )
}

/** One streak per landed hit. A hit on a player lands on their badge; a hit whose source already left play is skipped. */
@Composable
fun BoxScope.AttackPulses(
    hits: List<CombatHit>,
    bounds: Map<ObjectId, Rect>,
    stationOf: Map<PlayerId, ObjectId>,
    cfg: JuiceConfig,
    reducedMotion: Boolean,
) {
    if (!cfg.enabled || reducedMotion || hits.isEmpty()) return
    hits.forEachIndexed { i, hit ->
        androidx.compose.runtime.key(hit.seq) { Pulse(hit, i, bounds, stationOf) }
    }
}

@Composable
private fun BoxScope.Pulse(
    hit: CombatHit,
    index: Int,
    bounds: Map<ObjectId, Rect>,
    stationOf: Map<PlayerId, ObjectId>,
) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(70L * index)
        anim.animateTo(1f, tween(420, easing = LinearEasing))
    }
    val density = LocalDensity.current.density
    Canvas(Modifier.matchParentSize()) {
        val t = anim.value
        if (t <= 0f || t >= 1f) return@Canvas
        val from = hit.source?.let { bounds[it] } ?: return@Canvas
        val targetId = hit.target ?: hit.player?.let { stationOf[it] } ?: return@Canvas
        val to = bounds[targetId] ?: return@Canvas
        val a = from.center
        val b = to.center
        val travel = (t / 0.7f).coerceAtMost(1f)
        val ease = 1f - (1f - travel) * (1f - travel)
        val head = Offset(a.x + (b.x - a.x) * ease, a.y + (b.y - a.y) * ease)
        val tailT = (ease - 0.28f).coerceAtLeast(0f)
        val tail = Offset(a.x + (b.x - a.x) * tailT, a.y + (b.y - a.y) * tailT)
        val fade = if (t < 0.7f) 1f else 1f - (t - 0.7f) / 0.3f
        drawLine(PULSE.copy(alpha = 0.22f * fade), tail, head, strokeWidth = 7f * density, cap = StrokeCap.Round)
        drawLine(PULSE.copy(alpha = 0.95f * fade), tail, head, strokeWidth = 2.2f * density, cap = StrokeCap.Round)
        drawCircle(PULSE.copy(alpha = 0.9f * fade), radius = 3f * density, center = head)
    }
}
