package com.ccg

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ccg.GameDoc
import ccgui.JumpHit
import ccgui.JumpKind
import ccgui.JumpTo
import ccgui.jumpSearch

// ---------------------------------------------------------------------------
// The jump search: a card, a rules section, a deck or an issue, from
// anywhere in a game's workspace. What matches is `ccgui.jumpSearch`; where a
// hit goes is the shell's (it pushes, so Back returns here). Back while the
// search is open closes it -- the shell's second BackHandler.
// ---------------------------------------------------------------------------

@Composable
internal fun JumpSearchOverlay(game: GameDoc, onPick: (JumpTo) -> Unit, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val hits = remember(game, query) { jumpSearch(game, query) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize().background(Cg.bg.copy(alpha = 0.97f))) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val shape = RoundedCornerShape(10.dp)
            Box(
                Modifier.weight(1f).clip(shape).background(Cg.raised).border(1.dp, Cg.accent, shape)
                    .padding(horizontal = 12.dp, vertical = 11.dp),
            ) {
                if (query.isEmpty()) Text("⌕  card, rule, deck, issue…", color = Cg.dim, fontFamily = Cg.mono, fontSize = 13.sp)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Cg.ink, fontFamily = Cg.mono, fontSize = 13.sp),
                    cursorBrush = SolidColor(Cg.accentLight),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { hits.firstOrNull()?.let { onPick(it.to) } }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            TopAction("close") { onClose() }
        }
        if (query.isNotBlank() && hits.isEmpty()) {
            Text(
                "nothing in ${game.name} is called that", color = Cg.dim, fontFamily = Cg.mono, fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // A heading before each kind's first hit. Keyed by position: two
            // issues may say the same thing.
            hits.forEachIndexed { i, h ->
                if (i == 0 || hits[i - 1].kind != h.kind) {
                    item(key = "k-${h.kind}") {
                        Text(
                            h.kind.label.uppercase(), color = Cg.dim, fontFamily = Cg.mono, fontSize = 9.sp, letterSpacing = 1.5.sp,
                            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp, start = 4.dp),
                        )
                    }
                }
                item(key = i) { HitRow(h) { onPick(h.to) } }
            }
        }
    }
}

@Composable
private fun HitRow(h: JumpHit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Cg.surface).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            h.label, color = if (h.kind == JumpKind.ISSUE) Cg.warn else Cg.ink, fontSize = 14.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Text(h.detail, color = Cg.dim, fontFamily = Cg.mono, fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
    }
}
