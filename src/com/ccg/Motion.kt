package com.ccg

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// Lightweight board motion: a spring entrance for a tile that just appeared
// and a one-shot nudge for a state flip (declaring an attacker). Neither is
// load-bearing; under reduced motion every tile still renders correctly.
// Plain @Composables returning a [Modifier], called once per tile at a stable
// call site.
// ---------------------------------------------------------------------------

/** True when the OS "remove animations" setting is on (Settings ->
 *  Accessibility, which drives the same animator-duration-scale Developer
 *  Options also expose). Read once per composition scope -- it practically
 *  never flips mid-session, and this is board flourish, not correctness. */
@Composable
fun rememberReducedMotion(): Boolean {
    val ctx = LocalContext.current
    return remember {
        runCatching {
            Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}

/** A spring fade+slide-up entrance, played once when this call site enters
 *  composition. [play] = false for a tile already on the board when the session
 *  (re)opened, so only a genuine arrival animates. */
@Composable
fun entranceMotion(
    play: Boolean,
    reducedMotion: Boolean,
    fromY: Dp = 22.dp,
    /** The spring is tunable so the arrival can be dialled in on the device.
     *  Defaulted, so the other call sites (a stack item dropping in, a tile
     *  arriving in the Playtest tab) keep their values. */
    cfg: ccgui.JuiceConfig = ccgui.JuiceConfig.DEFAULT,
): Modifier {
    if (!play || reducedMotion || !cfg.enabled) return Modifier
    val anim = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        anim.animateTo(1f, spring(dampingRatio = cfg.arrivalDamping, stiffness = cfg.arrivalStiffness))
    }
    return Modifier.graphicsLayer {
        val away = 1f - anim.value
        translationY = away * fromY.toPx()
        alpha = anim.value
        // A little rotation on the way in, so a card lands rather than slides.
        rotationZ = away * cfg.arrivalSpinDeg
    }
}

/** A one-shot up-and-settle bounce each time [trigger] flips to true. The
 *  settle makes it a nudge rather than a lift (selection lifts separately). */
@Composable
fun nudgeMotion(trigger: Boolean, reducedMotion: Boolean, amount: Dp = 10.dp): Modifier {
    if (reducedMotion) return Modifier
    val anim = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger) {
            anim.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 700f))
            anim.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 380f))
        }
    }
    return Modifier.graphicsLayer { translationY = -anim.value * amount.toPx() }
}
