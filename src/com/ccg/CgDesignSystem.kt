package com.ccg

// ---------------------------------------------------------------------------
// CardEngine design system: the token object (`Cg`) and generic primitives,
// plus card-tile components. "Arcane Violet" accent over near-black neutrals;
// FontFamily.Monospace for data and labels, SansSerif for prose.
// ---------------------------------------------------------------------------

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import ccg.ArtSlot
import ccg.CardLayout
import ccg.CounterTrack
import ccg.StatCorner
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Cg {
    // surfaces (DTv2 neutrals, unchanged)
    val bg = Color(0xFF0D1117)
    val surface = Color(0xFF151B23)
    val surfaceAlt = Color(0xFF1B212B)
    val raised = Color(0xFF232A36)
    val border = Color(0xFF2E3646)
    val borderMuted = Color(0xFF3A4356)

    // text ramp
    val ink = Color(0xFFE6E8EB)
    val ink2 = Color(0xFFC7CDD8)
    val muted = Color(0xFF8891A3)
    val dim = Color(0xFF6B7688)

    // accent -- Arcane Violet by default; inside a game, the game's own.
    // One snapshot state, so every reader recomposes when a game is opened.
    val violet = Color(0xFF9B7BF0)
    private var seed by mutableStateOf(violet)
    val accent: Color get() = seed
    val accentDim: Color get() = if (seed == violet) Color(0xFF6F5AAE) else lerp(seed, Color.Black, 0.3f)
    val accentLight: Color get() = if (seed == violet) Color(0xFFB79CFF) else lerp(seed, Color.White, 0.28f)
    val accentWash: Color get() = seed.copy(alpha = 0.14f)

    /** Wear [c] as the accent (the shell sets it from the open game). */
    fun wear(c: Color) { if (seed != c) seed = c }

    // "wire" -- cool analogous teal, reserved for ONE meaning: composed / wired
    // up / ready. Not a second accent (5.6).
    val wire = Color(0xFF4FD6C9)
    val wireWash = Color(0x1F4FD6C9)

    // Neutral structure only. It must not carry meaning: green, teal and violet already do.
    val steel = Color(0xFF8A97B8)

    // a fourth elevation step above `raised` -- the spine, popovers (5.6).
    val floating = Color(0xFF262D3B)

    // semantic (from DTv2)
    val go = Color(0xFF39D98A)
    val warn = Color(0xFFFBC02D)
    val danger = Color(0xFFFF5C5C)
    val life = Color(0xFFF2919B)

    // Real typeface for the one voice that carries the most meaning -- data,
    // labels, the engine log, code (5.6 deferred item). `sans` (prose) stays
    // the documented SansSerif substitution.
    val mono: FontFamily = FontFamily(
        Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
        Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
        Font(R.font.jetbrains_mono_semibold, FontWeight.SemiBold),
    )
    val sans: FontFamily = FontFamily.SansSerif

    val gradientAction: Brush get() = Brush.linearGradient(listOf(accentLight, accent))
    val gradientFlat: Brush = Brush.linearGradient(listOf(surfaceAlt, surfaceAlt))
}

@Composable
fun CgTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Cg.bg,
            surface = Cg.surface,
            primary = Cg.accent,
            onPrimary = Cg.bg,
            secondary = Cg.accent,
            error = Cg.danger,
            onBackground = Cg.ink,
            onSurface = Cg.ink,
            surfaceVariant = Cg.surfaceAlt,
            onSurfaceVariant = Cg.muted,
            outline = Cg.border,
        ),
        content = content,
    )
}

/** Screen root: near-black + one ambient violet glow in the top-right. */
@Composable
fun CgScreenBackground(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Cg.bg)) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 80.dp, y = (-90).dp)
                .size(240.dp)
                .background(
                    Brush.radialGradient(listOf(Cg.accent.copy(alpha = 0.13f), Cg.accent.copy(alpha = 0f))),
                    CircleShape,
                ),
        )
        content()
    }
}

@Composable
fun CgTopBar(
    title: String,
    dirty: Boolean = false,
    /** When set, a real Save affordance is drawn (accent while `dirty`, muted
     *  when clean). This is the only place the named game file is written --
     *  navigation never persists on its own. */
    onSave: (() -> Unit)? = null,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Column {
        Row(
            // The accent washes in from the left: inside a game, its colour.
            Modifier.fillMaxWidth().heightIn(min = 52.dp)
                .background(Brush.horizontalGradient(listOf(lerp(Cg.surface, Cg.accent, 0.2f), Cg.surface, Cg.surface)))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading?.invoke(this)
            Text(title, color = Cg.ink, fontFamily = Cg.sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1)
            if (dirty) {
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(7.dp).background(Cg.accent, CircleShape))
            }
            Spacer(Modifier.weight(1f))
            if (onSave != null) {
                Text(
                    "Save",
                    color = if (dirty) Cg.accentLight else Cg.dim,
                    fontFamily = Cg.mono,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onSave)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            trailing()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Cg.border))
    }
}

/** The bottom navigation spine. Five sockets in dependency order,
 *  each with an optional readiness dot; a 2px accent indicator slides along the
 *  top edge (the `CgSectionTabs` pattern, finally on screen). */
enum class CgDot { NONE, OK, WARN, WIRE }

data class CgSpineItem(
    val key: String,
    val label: String,
    val dot: CgDot = CgDot.NONE,
    val icon: CgIconKind? = null,
)

@Composable
fun CgSpine(
    items: List<CgSpineItem>,
    selectedKey: String,
    dimmed: Boolean = false,
    onSelect: (String) -> Unit,
) {
    val sel = items.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0)
    val alpha by animateFloatAsState(if (dimmed) 0.35f else 1f, label = "cgSpineDim")
    Column(Modifier.alpha(alpha)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Cg.border))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val w = maxWidth / items.size.coerceAtLeast(1)
            val off by animateDpAsState(w * sel, tween(240), label = "cgSpineInd")
            Box(Modifier.offset(x = off).width(w).height(2.dp).background(Cg.accent))
        }
        Row(Modifier.fillMaxWidth().background(Cg.floating)) {
            items.forEachIndexed { i, item ->
                val active = i == sel
                val c by animateColorAsState(if (active) Cg.accentLight else Cg.dim, label = "cgSpineCol")
                val dotColor = when (item.dot) {
                    CgDot.OK -> Cg.go
                    CgDot.WARN -> Cg.warn
                    CgDot.WIRE -> Cg.wire
                    CgDot.NONE -> Color.Transparent
                }
                Column(
                    Modifier.weight(1f).heightIn(min = 54.dp)
                        .clickable(enabled = !dimmed) { onSelect(item.key) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (item.icon != null) {
                        CgIcon(item.icon, tint = c, size = 17.dp)
                    } else {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(dotColor))
                    }
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        if (item.icon != null && item.dot != CgDot.NONE) {
                            Box(Modifier.size(5.dp).clip(CircleShape).background(dotColor))
                        }
                        Text(
                            item.label.uppercase(),
                            color = c,
                            fontFamily = Cg.mono,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

/** The spine stood on its side: the rail at the left edge in
 *  landscape, where height is scarce and width is not. Same items, same dots,
 *  a bar marking the selected one. */
@Composable
fun CgSideRail(
    items: List<CgSpineItem>,
    selectedKey: String,
    onSelect: (String) -> Unit,
) {
    Row(Modifier.fillMaxHeight()) {
        Column(
            Modifier.fillMaxHeight().width(64.dp).background(Cg.floating).padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items.forEach { item ->
                val active = item.key == selectedKey
                val c by animateColorAsState(if (active) Cg.accentLight else Cg.dim, label = "cgRailCol")
                val dotColor = when (item.dot) {
                    CgDot.OK -> Cg.go
                    CgDot.WARN -> Cg.warn
                    CgDot.WIRE -> Cg.wire
                    CgDot.NONE -> Color.Transparent
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { onSelect(item.key) }, verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(3.dp).height(28.dp).background(if (active) Cg.accent else Color.Transparent))
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box {
                            if (item.icon != null) CgIcon(item.icon, tint = c, size = 17.dp)
                            if (item.dot != CgDot.NONE) {
                                Box(Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-2).dp).size(6.dp).clip(CircleShape).background(dotColor))
                            }
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(item.label.uppercase(), color = c, fontFamily = Cg.mono, fontSize = 8.sp, maxLines = 1)
                    }
                }
            }
        }
        Box(Modifier.fillMaxHeight().width(1.dp).background(Cg.border))
    }
}

/** Icons built from one vocabulary -- a rounded square (a module), a notch (a
 *  socket), bars (fields / a stack), a diamond (a pip). The set is the
 *  leitmotiv, drawn (5.6 spine + the Rules section list). */
enum class CgIconKind {
    GAMES, RULES, CARDS, DECKS, PLAY,
    TYPES, ZONES, COUNTERS, TURN, COMBAT, RESOURCES, DECKRULES,
}

@Composable
fun CgIcon(kind: CgIconKind, modifier: Modifier = Modifier, tint: Color = Cg.accentLight, size: Dp = 18.dp) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val w = s * 0.10f
        val stroke = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun rr(x: Float, y: Float, ww: Float, hh: Float, rad: Float = s * 0.14f) =
            drawRoundRect(
                tint, Offset(x * s, y * s), androidx.compose.ui.geometry.Size(ww * s, hh * s),
                CornerRadius(rad, rad), style = stroke,
            )
        fun ln(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, Offset(x1 * s, y1 * s), Offset(x2 * s, y2 * s), w, cap = StrokeCap.Round)
        fun dot(x: Float, y: Float, r: Float = 0.09f) = drawCircle(tint, r * s, Offset(x * s, y * s))
        fun poly(vararg pts: Float) {
            val p = Path().apply {
                moveTo(pts[0] * s, pts[1] * s)
                var i = 2
                while (i < pts.size) { lineTo(pts[i] * s, pts[i + 1] * s); i += 2 }
                close()
            }
            drawPath(p, tint)
        }
        when (kind) {
            CgIconKind.GAMES -> { rr(.12f, .12f, .34f, .34f); rr(.54f, .12f, .34f, .34f); rr(.12f, .54f, .34f, .34f); rr(.54f, .54f, .34f, .34f) }
            CgIconKind.RULES -> { rr(.14f, .14f, .72f, .72f); ln(.14f, .54f, .86f, .54f); ln(.6f, .14f, .6f, .32f); ln(.6f, .32f, .86f, .32f) }
            CgIconKind.CARDS -> {
                rr(.12f, .3f, .5f, .58f)
                ln(.32f, .3f, .32f, .12f); ln(.32f, .12f, .88f, .12f); ln(.88f, .12f, .88f, .68f); ln(.88f, .68f, .7f, .68f)
            }
            CgIconKind.DECKS -> { rr(.32f, .16f, .36f, .68f); ln(.16f, .26f, .16f, .74f); ln(.84f, .26f, .84f, .74f) }
            CgIconKind.PLAY -> {
                drawCircle(tint, s * 0.38f, Offset(s * 0.5f, s * 0.5f), style = stroke)
                poly(.43f, .34f, .68f, .5f, .43f, .66f)
            }
            CgIconKind.TYPES -> { rr(.14f, .14f, .58f, .58f); ln(.55f, .14f, .86f, .45f); dot(.32f, .32f) }
            CgIconKind.ZONES -> { rr(.12f, .28f, .76f, .44f); ln(.5f, .28f, .5f, .4f); ln(.5f, .6f, .5f, .72f) }
            CgIconKind.COUNTERS -> { rr(.16f, .16f, .68f, .68f); ln(.5f, .34f, .5f, .66f); ln(.34f, .5f, .66f, .5f) }
            CgIconKind.TURN -> {
                drawArc(tint, 35f, 275f, false, Offset(s * .16f, s * .16f), androidx.compose.ui.geometry.Size(s * .68f, s * .68f), style = stroke)
                ln(.78f, .12f, .86f, .3f); ln(.86f, .3f, .66f, .32f)
            }
            CgIconKind.COMBAT -> { ln(.28f, .16f, .46f, .5f); ln(.46f, .5f, .28f, .84f); ln(.72f, .16f, .54f, .5f); ln(.54f, .5f, .72f, .84f) }
            CgIconKind.RESOURCES -> { poly(.5f, .14f, .72f, .42f, .5f, .7f, .28f, .42f); ln(.24f, .84f, .76f, .84f) }
            CgIconKind.DECKRULES -> { rr(.3f, .14f, .34f, .5f); ln(.16f, .24f, .16f, .58f); ln(.84f, .24f, .84f, .58f); ln(.36f, .8f, .46f, .88f); ln(.46f, .88f, .66f, .7f) }
        }
    }
}

/** Left-edge horizontal swipe -> onBack. Minimal shell-level back gesture;
 *  no-op when disabled. Only a drag STARTING within ~28dp of the left edge
 *  and travelling > ~72dp to the right counts, so it stays clear of content. */
fun Modifier.cgEdgeBackGesture(enabled: Boolean, onBack: () -> Unit): Modifier =
    // `enabled` is a pointerInput KEY -- flipping it (e.g. the hotseat going
    // immersive) must cancel the running detector, not leave a stale one that
    // still fires. Conditionally omitting the modifier is not enough.
    this.pointerInput(enabled, onBack) {
        if (!enabled) return@pointerInput
        val edge = 28.dp.toPx()
        val trigger = 72.dp.toPx()
        var startX = 0f
        var acc = 0f
        detectHorizontalDragGestures(
            onDragStart = { startX = it.x; acc = 0f },
            onHorizontalDrag = { _, d -> acc += d },
            onDragEnd = { if (startX <= edge && acc > trigger) onBack() },
        )
    }

/** N equal-width tabs + a sliding accent indicator (ported from DtSectionTabs). */
@Composable
fun CgSectionTabs(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().background(Cg.surface)) {
            tabs.forEachIndexed { i, label ->
                val active = i == selected
                val color by animateColorAsState(if (active) Cg.accentLight else Cg.dim, label = "cgTabColor")
                Box(
                    Modifier.weight(1f).heightIn(min = 42.dp).clickable { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label.uppercase(),
                        color = color,
                        fontFamily = Cg.mono,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val tabWidth = maxWidth / tabs.size.coerceAtLeast(1)
            val indicatorOffset by animateDpAsState(tabWidth * selected, tween(240), label = "cgTabIndicator")
            Box(Modifier.offset(x = indicatorOffset).width(tabWidth).height(2.dp).background(Cg.accent))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Cg.border))
    }
}

/** Direction-aware slide+fade between tab bodies (ported from DtTabAnimatedContent). */
@Composable
fun CgTabAnimatedContent(selectedTab: Int, label: String, content: @Composable (Int) -> Unit) {
    AnimatedContent(
        targetState = selectedTab,
        label = label,
        transitionSpec = {
            val forward = targetState > initialState
            (fadeIn(tween(200)) + slideInHorizontally(tween(200)) { w -> if (forward) w / 6 else -w / 6 })
                .togetherWith(fadeOut(tween(120)))
        },
    ) { idx -> content(idx) }
}

/** A tappable section row. A module that docks: a coloured left rule
 *  says how (accent = a structural section, wire = a status), a mono summary
 *  under the title, an optional readiness dot. Used for the Rules section list;
 *  grows a `content` slot in the 5.6 polish pass. */
@Composable
fun CgModule(
    title: String,
    summary: String? = null,
    dock: Color = Cg.accent,
    dot: CgDot = CgDot.NONE,
    icon: CgIconKind? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Cg.surface)
            .border(1.dp, Cg.border, RoundedCornerShape(8.dp)).clickable(onClick = onClick)
            .padding(start = 10.dp, top = 12.dp, bottom = 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(width = 4.dp, height = 30.dp).clip(RoundedCornerShape(2.dp)).background(dock))
        if (icon != null) CgIcon(icon, tint = Cg.accentLight, size = 18.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = Cg.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (summary != null) Text(summary, color = Cg.dim, fontFamily = Cg.mono, fontSize = 10.sp)
        }
        if (dot != CgDot.NONE) {
            val dc = when (dot) {
                CgDot.OK -> Cg.go
                CgDot.WARN -> Cg.warn
                CgDot.WIRE -> Cg.wire
                CgDot.NONE -> Color.Transparent
            }
            Box(Modifier.size(7.dp).clip(CircleShape).background(dc))
        }
        Text("›", color = Cg.accentLight, fontSize = 18.sp)
    }
}

/** A compact status strip -- a few dot-labelled pills. Used on the Games list
 *  rows (5.5) and, later, along the spine inside a game. */
data class CgReadinessItem(val label: String, val dot: CgDot = CgDot.NONE)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CgReadinessRow(items: List<CgReadinessItem>, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { item ->
            Row(
                Modifier.clip(RoundedCornerShape(999.dp)).border(1.dp, Cg.borderMuted, RoundedCornerShape(999.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                val c = when (item.dot) {
                    CgDot.OK -> Cg.go
                    CgDot.WARN -> Cg.warn
                    CgDot.WIRE -> Cg.wire
                    CgDot.NONE -> Cg.dim
                }
                Box(Modifier.size(6.dp).clip(RoundedCornerShape(2.dp)).background(c))
                Text(item.label, color = Cg.ink2, fontFamily = Cg.mono, fontSize = 10.sp, softWrap = false)
            }
        }
    }
}

@Composable
fun CgCard(
    modifier: Modifier = Modifier,
    border: Color = Cg.border,
    /** Padding inside the frame. Defaulted, so every existing caller is
     *  unchanged; the play surface in landscape passes a smaller one, where
     *  the frame is chrome competing with the lanes for the scarce dimension. */
    inset: Dp = 12.dp,
    /** Spacing between children, same reasoning. */
    gap: Dp = 8.dp,
    /** Before `content`, the trailing lambda. */
    background: Color = Cg.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .padding(inset),
        verticalArrangement = Arrangement.spacedBy(gap),
        content = content,
    )
}

/** Collapsible titled section. Effect blocks pass initiallyOpen = list.isNotEmpty(). */
@Composable
fun CgGroup(
    title: String,
    modifier: Modifier = Modifier,
    initiallyOpen: Boolean = true,
    summary: String? = null,
    onDelete: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var open by remember { mutableStateOf(initiallyOpen) }
    var confirming by remember { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Cg.surface)
            .border(1.dp, Cg.border, RoundedCornerShape(8.dp))
            .animateContentSize(),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (open) "▾" else "▸", color = Cg.dim, fontSize = 11.sp, fontFamily = Cg.mono)
            Spacer(Modifier.width(8.dp))
            Text(
                title.uppercase(),
                color = Cg.accentLight,
                fontSize = 10.sp,
                fontFamily = Cg.mono,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
            )
            if (summary != null) {
                Spacer(Modifier.width(8.dp))
                Text(summary, color = Cg.dim, fontSize = 10.sp, fontFamily = Cg.mono, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            if (onDelete != null) {
                Text(
                    "Delete",
                    color = Cg.danger,
                    fontSize = 10.sp,
                    fontFamily = Cg.mono,
                    modifier = Modifier.clickable { confirming = true }.padding(4.dp),
                )
            }
        }
        if (open) {
            Column(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    }
    if (confirming && onDelete != null) {
        CgDeleteConfirmDialog(
            title = "Delete “$title”?",
            body = "This removes it from the bundle.",
            onConfirm = { confirming = false; onDelete() },
            onDismiss = { confirming = false },
        )
    }
}

// -- focus dim (ported from DTv2's rememberFocusDimState / dtDimUnlessFocused) --

class FocusDimState {
    var focused by mutableStateOf<Any?>(null)
}

@Composable
fun rememberFocusDimState(): FocusDimState = remember { FocusDimState() }

fun Modifier.cgFocusField(state: FocusDimState, key: Any): Modifier =
    this.onFocusChanged { if (it.isFocused) state.focused = key else if (state.focused == key) state.focused = null }

@Composable
fun Modifier.cgDimUnlessFocused(state: FocusDimState, key: Any?): Modifier {
    val active = state.focused
    val dimmed = active != null && active != key
    val a by animateFloatAsState(if (dimmed) 0.4f else 1f, label = "cgDim")
    return this.alpha(a)
}

// -- inputs --

@Composable
fun CgField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    placeholder: String = "",
    onChange: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().then(modifier), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label.uppercase(), color = Cg.dim, fontSize = 9.sp, fontFamily = Cg.mono, letterSpacing = 0.8.sp)
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Cg.surfaceAlt)
                .border(1.dp, Cg.border, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 9.dp),
        ) {
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(placeholder, color = Cg.dim, fontSize = 13.sp, fontFamily = if (mono) Cg.mono else Cg.sans)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = Cg.ink, fontSize = 13.sp, fontFamily = if (mono) Cg.mono else Cg.sans),
                cursorBrush = SolidColor(Cg.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Label-less compact field for inline rows (attribute keys, step names, verb operands). */
@Composable
fun CgMiniField(value: String, modifier: Modifier = Modifier, mono: Boolean = true, onChange: (String) -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(5.dp)).background(Cg.surfaceAlt)
            .border(1.dp, Cg.border, RoundedCornerShape(5.dp)).padding(horizontal = 8.dp, vertical = 7.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Cg.ink, fontSize = 12.sp, fontFamily = if (mono) Cg.mono else Cg.sans),
            cursorBrush = SolidColor(Cg.accent),
        )
    }
}

/** Label + control on one line. */
@Composable
fun CgInlineField(label: String, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            label.uppercase(),
            color = Cg.dim,
            fontSize = 9.sp,
            fontFamily = Cg.mono,
            letterSpacing = 0.8.sp,
            modifier = Modifier.width(82.dp),
        )
        content()
    }
}

@Composable
fun CgStepper(
    value: Int,
    modifier: Modifier = Modifier,
    min: Int = Int.MIN_VALUE,
    max: Int = Int.MAX_VALUE,
    onChange: (Int) -> Unit,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CgStepChip("–") { if (value > min) onChange(value - 1) }
        Box(Modifier.widthIn(min = 30.dp), contentAlignment = Alignment.Center) {
            Text("$value", color = Cg.ink, fontSize = 13.sp, fontFamily = Cg.mono)
        }
        CgStepChip("+") { if (value < max) onChange(value + 1) }
    }
}

@Composable
private fun CgStepChip(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Cg.accentLight,
        fontSize = 15.sp,
        fontFamily = Cg.mono,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(Cg.surfaceAlt)
            .border(1.dp, Cg.border, RoundedCornerShape(5.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 2.dp),
    )
}

@Composable
fun CgPicker(value: String, options: List<String>, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.clip(RoundedCornerShape(6.dp)).background(Cg.surfaceAlt)
                .border(1.dp, Cg.border, RoundedCornerShape(6.dp))
                .clickable { open = true }.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(value, color = Cg.ink, fontSize = 12.sp, fontFamily = Cg.sans, maxLines = 1)
            Text("▾", color = Cg.dim, fontSize = 9.sp)
        }
        androidx.compose.material3.DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(Cg.surface),
        ) {
            options.forEach { o ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(o, color = Cg.ink, fontSize = 13.sp) },
                    onClick = { onPick(o); open = false },
                )
            }
        }
    }
}

/** Row of equal-weight tap segments -- turn model, resource perTurn, zone scope/overflow. */
@Composable
fun CgSegmented(
    options: List<String>,
    selected: String,
    modifier: Modifier = Modifier,
    labelFor: (String) -> String = { it },
    onSelect: (String) -> Unit,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { o ->
            val on = o == selected
            Box(
                Modifier.weight(1f).heightIn(min = 34.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (on) Cg.accentWash else Cg.surfaceAlt)
                    .border(1.dp, if (on) Cg.accent else Cg.border, RoundedCornerShape(6.dp))
                    .clickable { onSelect(o) }.padding(vertical = 7.dp, horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    labelFor(o),
                    color = if (on) Cg.accentLight else Cg.muted,
                    fontSize = 10.sp,
                    fontFamily = Cg.mono,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
fun CgCheck(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { onChange(!checked) }.padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(16.dp).clip(RoundedCornerShape(4.dp))
                .background(if (checked) Cg.accent else Color.Transparent)
                .border(1.dp, if (checked) Cg.accent else Cg.borderMuted, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Text("✓", color = Cg.bg, fontSize = 11.sp)
        }
        Text(label, color = Cg.muted, fontSize = 11.sp)
    }
}

@Composable
fun CgTag(text: String, color: Color = Cg.muted) {
    Text(
        text,
        color = color,
        fontFamily = Cg.mono,
        fontSize = 9.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Cg.surfaceAlt)
            .border(1.dp, Cg.border, RoundedCornerShape(999.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** Small clickable pill -- reorder arrows, the moveCard slot cycler. */
@Composable
fun CgPill(text: String, onClick: () -> Unit) {
    Text(
        text,
        color = Cg.accentLight,
        fontFamily = Cg.mono,
        fontSize = 10.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Cg.accentWash)
            .border(1.dp, Cg.accentDim, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun CgButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (enabled) Cg.gradientAction else Cg.gradientFlat)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 8.dp),
    ) {
        Text(text, color = if (enabled) Cg.bg else Cg.dim, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

@Composable
fun CgAddButton(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, Cg.accentDim, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text("+", color = Cg.accentLight, fontSize = 13.sp, fontFamily = Cg.mono)
        Text(label, color = Cg.accentLight, fontSize = 11.sp)
    }
}

@Composable
fun CgDeleteX(onClick: () -> Unit) {
    Text(
        "✕",
        color = Cg.dim,
        fontSize = 13.sp,
        modifier = Modifier.clip(CircleShape).clickable(onClick = onClick).padding(4.dp),
    )
}

/** The unsaved-changes guard. Shown when the user navigates away from
 *  a dirty game -- switch game, new game, exit to the games list. Saving is
 *  otherwise entirely explicit. */
@Composable
fun CgSaveGuardDialog(
    name: String,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Unsaved changes in “$name”", color = Cg.ink) },
        text = { Text("Save your changes before leaving?", color = Cg.muted) },
        confirmButton = { TextButton(onClick = onSave) { Text("Save", color = Cg.accentLight) } },
        dismissButton = {
            Row {
                TextButton(onClick = onDiscard) { Text("Discard", color = Cg.danger) }
                TextButton(onClick = onCancel) { Text("Cancel", color = Cg.ink2) }
            }
        },
        containerColor = Cg.surface,
    )
}

/** Ported from DtDeleteConfirmDialog. Wired app-wide via CgGroup's own Delete
 *  affordance and the Creator's card / bundle deletes + the dirty-guard. */
@Composable
fun CgDeleteConfirmDialog(
    title: String,
    body: String? = null,
    confirmLabel: String = "Delete",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = Cg.ink) },
        text = body?.let { { Text(it, color = Cg.muted) } },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel, color = Cg.danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Cg.ink2) } },
        containerColor = Cg.surface,
    )
}

/** How a tile is highlighted on the board. PLAYABLE = "you may play
 *  this right now" (green); TARGET = "tap to choose me" (wire); SELECTED =
 *  currently picked (accent). */
enum class CgTileRing {
    NONE, TARGET, SELECTED, PLAYABLE,

    /** This card is what the response-window BEAT is talking about. Its own
     *  colour rather than a reused TARGET: a beat is not a prompt, nothing on
     *  the board is tappable while one is showing, and a ring that means "you
     *  may act on this" would be a lie for the second it is up. */
    BEAT,
}

/** `#rrggbb` -> Color, or null. */
fun hexColor(h: String?): Color? =
    h?.removePrefix("#")?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000L or it) }

/** The card face -- live preview in the Creator, a set/deck-list tile, the unit
 *  on the board. Arranged by the card's type layout; `footer` hosts
 *  inline pills, e.g. a permanent's activated abilities. `onLongPress` opens a
 *  focus view of the card; an interactive tile scales slightly while pressed. */
@Composable
fun CgCardTile(
    name: String,
    typeLabel: String,
    costLabel: String?,
    pt: String?,
    text: String,
    modifier: Modifier = Modifier,
    selectable: Boolean = false,
    onClick: (() -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    // -- board tiles --
    layout: CardLayout? = null,
    art: ImageBitmap? = null,
    /** WHICH RECTANGLE of [art] this tile shows. Null = a centred crop
     *  of the tile's own shape, which is what every card did before cropping
     *  existed. */
    artRect: ccg.ArtRect? = null,
    /** Applied to the art box itself: the Creator hangs its picker here, the
     *  Player passes nothing. A seam, not a mode flag, so the tile never grows
     *  an "am I in the Creator" branch. */
    artModifier: Modifier = Modifier,
    counters: Map<String, Int> = emptyMap(),
    compact: Boolean = false,
    ring: CgTileRing = CgTileRing.NONE,
    tapped: Boolean = false,
    dim: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    width: Dp? = null,
    lifted: Boolean = false,
    /** show the picture WHOLE, at its own shape, instead of a crop
     *  of the tile's band. Only the focused card asks for this -- it is the one
     *  place a card is looked at rather than scanned. Added last: a mid-list
     *  parameter compiles here and breaks at APK build four minutes later. */
    artWhole: Boolean = false,
    /** A third size tier, below `compact`, for the docked hand: its height is
     *  the scarcest dimension on a phone. Kept last in the parameter list. */
    dense: Boolean = false,
    /** A FIXED height for the tile, so a lane can divide its own height between
     *  however many tiles are in it instead of growing. Null = size to
     *  content, which is every caller that is not a lane cell. */
    height: Dp? = null,
    /** Shed the art box. It is the largest single consumer of a tile's height
     *  (46dp of an 88dp compact tile) and the least informative when the
     *  placeholder glyph is all there is. Driven by `ccgui.laneFill`. */
    hideArt: Boolean = false,
    /** Shed the type line too -- the last thing to go before the name. */
    hideType: Boolean = false,
    /** Let the cost/type line WRAP instead of ellipsising -- right for the focus
     *  view, where a compiled cost is a real sentence meant to be read. */
    wrapTypeLine: Boolean = false,
) {
    val eff = layout ?: CardLayout()
    val accent = hexColor(eff.accent) ?: Cg.accent
    val borderColor = when {
        ring == CgTileRing.TARGET -> Cg.wire
        ring == CgTileRing.BEAT -> Cg.accentLight
        ring == CgTileRing.PLAYABLE -> Cg.go
        ring == CgTileRing.SELECTED || selectable -> Cg.accent
        else -> Cg.borderMuted
    }
    val borderW = if (ring != CgTileRing.NONE) 2.dp else 1.dp
    val w = width ?: when {
        dense -> 84.dp
        compact -> 104.dp
        else -> 150.dp
    }
    // READABILITY AT SMALL SCALES. At a 27dp cell the tile's own
    // padding was 14dp of the 27 -- more than half the box spent on margin, so
    // the name had ~13dp and the stat corner was clipped by the fixed height.
    // A short cell gets its padding back as text.
    val pad = when {
        height != null && height < 34.dp -> 3.dp
        height != null && height < 46.dp -> 5.dp
        dense -> 5.dp
        compact -> 7.dp
        else -> 10.dp
    }
    val artH = when {
        dense -> 26.dp
        compact -> 46.dp
        else -> 78.dp
    }
    val showText = eff.showText && !compact && !dense && text.isNotBlank()

    // A slight "lift" while the tile is held / tapped, and a persistent one
    // while it is armed for confirmation.
    var pressed by remember { mutableStateOf(false) }
    val liftT by animateFloatAsState(
        if (pressed || lifted) 1f else 0f, label = "cgTileLift",
    )
    val tileScale = 1f + 0.06f * liftT
    val interactive = onClick != null || onLongPress != null

    // Counters worth drawing, and HOW MANY of each: pips stop meaning anything
    // past a few (a Station holds 24 hull).
    val trackCounts = counters.entries
        .filter { it.value > 0 && (eff.counterKind == null || it.key == eff.counterKind) }
        .map { it.key to it.value }
    val trackTotal = trackCounts.sumOf { it.second }
    // Above this, show the NUMBER instead of pips. Three is the point where
    // counting dots stops being faster than reading a digit.
    val trackAsNumber = trackTotal > 3

    @Composable
    fun ArtBox(m: Modifier) {
        Box(
            m.clip(RoundedCornerShape(5.dp)).background(Cg.surfaceAlt).then(artModifier),
            contentAlignment = Alignment.Center,
        ) {
            if (art != null) {
                // The crop is applied HERE, once, so the Creator's preview,
                // the art modal and the Player's board show the same picture.
                // `ArtLayer` is that one definition -- see CardArt.kt.
                ArtLayer(art, artRect, whole = artWhole)
            } else {
                Text("◈", color = Cg.dim, fontSize = if (compact) 14.sp else 20.sp)
            }
        }
    }

    // The stat box is an overlay, so the column reserves its corner (else the
    // type line is covered on a compact tile). `statCorner` stays declarative.
    val statReserve = if (pt != null && eff.statCorner != StatCorner.NONE) {
        if (compact) 30.dp else 38.dp
    } else {
        0.dp
    }

    // The same reserve for a BOTTOM_STRIP counter track, which would otherwise
    // print through the name of a card with no rules text (every Station).
    val bottomReserve = if (trackCounts.isNotEmpty() && eff.counterTrack == CounterTrack.BOTTOM_STRIP) {
        // Sized to the BADGE, not guessed at a gap: a counter chip is a bordered,
        // padded number, so the band it needs is its text line plus its own
        // padding and border on both sides. The first attempt used 14/18 and the
        // device still printed the hull through the type line.
        if (compact) 22.dp else 26.dp
    } else {
        0.dp
    }

    @Composable
    fun TypeLine() {
        // An EMPTY type line still costs a text line. On the landscape board
        // that is 14dp times four tile rows for a word that says "Ship" under
        // every card on a board where everything is a Ship. Skip it entirely
        // rather than render blank.
        if (typeLabel.isEmpty() && costLabel.isNullOrEmpty()) return
        if (hideType) return
        val size = if (compact) 9.sp else 11.sp
        Text(
            buildString { if (!costLabel.isNullOrEmpty()) append(costLabel).append("  "); append(typeLabel) },
            color = accent, fontSize = size, fontFamily = Cg.mono,
            maxLines = if (wrapTypeLine) 3 else 1,
            overflow = TextOverflow.Ellipsis,
            // Tightened to just over the glyph height: the default
            // line box adds ~40% leading, and between a name and its type line
            // that is dead space on every tile on the board at once.
            lineHeight = size * 1.15f,
            // ...and a long type line still ELLIPSISES rather than running
            // under the box it cannot see.
            modifier = Modifier.padding(end = statReserve),
        )
    }
    @Composable
    fun NameText() {
        val size = if (compact) 11.sp else 13.sp
        Text(
            name.ifBlank { "—" }, color = Cg.ink, fontWeight = FontWeight.SemiBold,
            fontSize = size, fontFamily = Cg.sans, maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Same tightening as the type line, and this is the half of the
            // gap that was actually large: a 13sp name carried an ~18dp line
            // box, so most of the space between a card's name and its type was
            // leading rather than the 5dp arrangement spacing.
            lineHeight = size * 1.15f,
        )
    }

    /** Name and type as ONE tight pair, so their gap shrinks without pulling
     *  the art and rules text with it. */
    @Composable
    fun NameAndType() {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) { NameText(); TypeLine() }
    }

    Box(
        modifier
            .width(w)
            // A lane cell's height is DIVIDED, not accumulated -- see
            // `ccgui.laneFill`. clipToBounds so a tile whose content still
            // wants more than its share is cut rather than drawn over the
            // neighbour below it.
            .then(if (height != null) Modifier.height(height).clipToBounds() else Modifier)
            .scale(tileScale)
            .rotate(if (tapped) 9f else 0f)
            .alpha(if (dim) 0.5f else 1f)
            .then(if (liftT > 0f) Modifier.shadow((12 * liftT).dp, RoundedCornerShape(10.dp), clip = false) else Modifier)
            .clip(RoundedCornerShape(10.dp))
            .background(if (pressed || lifted) Cg.floating else Cg.raised)
            .border(borderW, borderColor, RoundedCornerShape(10.dp))
            .then(
                if (interactive) Modifier.pointerInput(onClick, onLongPress) {
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            tryAwaitRelease()
                            pressed = false
                        },
                        onTap = { onClick?.invoke() },
                        onLongPress = { onLongPress?.invoke() },
                    )
                } else Modifier,
            )
            .padding(pad),
    ) {
        if (eff.art == ArtSlot.LEFT) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!hideArt) {
                    ArtBox(Modifier.width(if (compact) 34.dp else 46.dp).heightIn(min = if (compact) 52.dp else 96.dp))
                }
                Column(
                    Modifier.weight(1f).padding(bottom = bottomReserve),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    NameAndType()
                    if (showText) Text(text, color = Cg.muted, fontSize = 11.sp, lineHeight = 15.sp)
                }
            }
        } else {
            Column(
                Modifier.padding(bottom = bottomReserve),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (eff.art != ArtSlot.NONE && !hideArt) {
                    // The focused card shows the WHOLE picture in its own shape
                    // (capped, so tall art cannot push the rulebox off screen).
                    val natural = if (artWhole && art != null && art.height > 0) {
                        art.width.toFloat() / art.height.toFloat()
                    } else {
                        null
                    }
                    if (natural != null) {
                        ArtBox(Modifier.fillMaxWidth().aspectRatio(natural).heightIn(max = 300.dp))
                    } else {
                        ArtBox(Modifier.fillMaxWidth().height(artH))
                    }
                }
                NameAndType()
                if (showText) Text(text, color = Cg.muted, fontSize = 11.sp, lineHeight = 15.sp)
                if (footer != null) {
                    // Same reserve: the footer is the other row that reaches
                    // the stat corner.
                    Row(
                        Modifier.padding(end = statReserve),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { footer() }
                }
            }
        }

        // stat box in a corner
        if (pt != null && eff.statCorner != StatCorner.NONE) {
            Text(
                pt, color = Cg.ink, fontFamily = Cg.mono, fontSize = if (compact) 10.sp else 12.sp,
                modifier = Modifier
                    .align(if (eff.statCorner == StatCorner.BOTTOM_LEFT) Alignment.BottomStart else Alignment.BottomEnd)
                    .clip(RoundedCornerShape(4.dp)).background(Cg.surface)
                    .border(1.dp, Cg.border, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }

        // counter track
        if (trackCounts.isNotEmpty() && eff.counterTrack == CounterTrack.LEFT_EDGE) {
            Column(
                Modifier.align(Alignment.CenterStart),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) { CounterTrackContent(trackCounts, trackAsNumber, compact) }
        }
        if (trackCounts.isNotEmpty() && eff.counterTrack == CounterTrack.BOTTOM_STRIP) {
            Row(
                Modifier.align(Alignment.BottomCenter),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { CounterTrackContent(trackCounts, trackAsNumber, compact) }
        }
    }
}

/** A laned zone's slots as a row of cells. */
@Composable
fun CgLaneStrip(lanes: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(lanes.coerceAtMost(12)) { i ->
            Box(
                Modifier.weight(1f).heightIn(min = 28.dp).clip(RoundedCornerShape(5.dp))
                    .border(1.dp, Cg.borderMuted, RoundedCornerShape(5.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("$i", color = Cg.dim, fontSize = 10.sp, fontFamily = Cg.mono)
            }
        }
    }
}

/** A counter track's contents: pips while countable at a glance, a NUMBER above
 *  three. */
@Composable
private fun CounterTrackContent(counts: List<Pair<String, Int>>, asNumber: Boolean, compact: Boolean) {
    if (asNumber) {
        counts.forEach { (kind, n) ->
            Text(
                if (counts.size > 1) "${kind.take(1)}$n" else "$n",
                color = Cg.ink, fontFamily = Cg.mono,
                fontSize = if (compact) 9.sp else 11.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(3.dp)).background(Cg.surface)
                    .padding(horizontal = 3.dp),
            )
        }
    } else {
        counts.forEach { (_, n) ->
            repeat(n) { Box(Modifier.size(5.dp).clip(CircleShape).background(Cg.wire)) }
        }
    }
}
