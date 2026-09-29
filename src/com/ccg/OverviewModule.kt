package com.ccg

import ccgui.coverCard
import ccgui.argbOf
import ccgui.accentOrNull
import ccgui.ACCENTS
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import ccgui.CardLocation
import ccgui.locate
import ccgui.Module
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ccg.problems
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

// ---------------------------------------------------------------------------
// The Overview module: a game's front page (name, export, history, issues,
// readiness, Play).
// ---------------------------------------------------------------------------

/** The game's front page: its name and file actions, how ready it is, what is
 *  wrong (each issue naming a card opens it), and the way to play it. */
@Composable
private fun OverviewScreen(
    vm: CreatorViewModel,
    store: GameStore,
    onRestore: (GameStore.GameVersion) -> Unit,
    onOpenCard: (CardLocation) -> Unit,
    onModule: (Module) -> Unit,
) {
    val g = vm.game
    val problems = remember(g) { g.diagnostics() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        GameHeader(vm, store, onRestore, onOpenCard, expanded = true)
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CgReadinessRow(
                listOf(
                    CgReadinessItem("${g.cards.size} card" + if (g.cards.size == 1) "" else "s"),
                    CgReadinessItem("${g.sets.size} set" + if (g.sets.size == 1) "" else "s"),
                    CgReadinessItem("${g.decks.size} deck" + if (g.decks.size == 1) "" else "s"),
                    CgReadinessItem(
                        if (problems.isEmpty()) "ready" else "${problems.size} issue" + if (problems.size == 1) "" else "s",
                        if (problems.isEmpty()) CgDot.OK else CgDot.WARN,
                    ),
                ),
            )
            CgButton(if (vm.session.inProgress) "▶  Play (a game is on the table)" else "▶  Play") { onModule(Module.PLAY) }
        }
    }
}

/** The top of Overview: the editable game name, an export menu,
 *  a history menu (past Saves, tap one to restore it into the
 *  working copy), and a tap-to-expand problems list (was the home screen's
 *  job). */
@Composable
private fun GameHeader(
    vm: CreatorViewModel,
    store: GameStore,
    onRestore: (GameStore.GameVersion) -> Unit,
    /** a diagnostic about a card opens that card's editor. */
    onOpenCard: (CardLocation) -> Unit,
    /** the issues open from the start (Overview, where they are the point). */
    expanded: Boolean = false,
) {
    var showProblems by remember { mutableStateOf(expanded) }
    val problems = vm.game.diagnostics()
    val ctx = LocalContext.current
    val g = vm.game
    val exportZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) store.exportBundle(g, uri, ctx.contentResolver)
    }
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) store.exportJson(g, uri, ctx.contentResolver)
    }
    Column(
        Modifier.fillMaxWidth().background(Cg.bg).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { CgField("game name", g.name) { vm.renameGame(it) } }
            MenuPill("export", listOf("bundle .ceg.zip", "JSON")) { p ->
                val stem = store.exportName(g.name)
                if (p.startsWith("bundle")) exportZip.launch("$stem.ceg.zip") else exportJson.launch("$stem.json")
            }
            HistoryPill(store, g.id, onRestore)
            if (problems.isNotEmpty()) {
                Text(
                    "⚠ ${problems.size}",
                    color = Cg.warn, fontFamily = Cg.mono, fontSize = 11.sp,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp))
                        .border(1.dp, Cg.warn, RoundedCornerShape(6.dp))
                        .clickable { showProblems = !showProblems }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
        IdentityRow(vm)
        if (showProblems && problems.isNotEmpty()) {
            problems.forEach { d ->
                // One naming a card is a link to it; a game-level one
                // stays plain text.
                val at = locate(vm.game, d)
                Text(
                    (if (at != null) "→  " else "⚠  ") + d.message,
                    color = if (at != null) Cg.ink else Cg.ink2, fontFamily = Cg.mono, fontSize = 10.sp,
                    modifier = if (at != null) Modifier.clickable { onOpenCard(at) } else Modifier,
                )
            }
        }
    }
}

internal val OverviewModule = ModuleSpec(Module.OVERVIEW, CgIconKind.GAMES) {
    OverviewScreen(
        vm, store, restore,
        onOpenCard = { at -> openCard(at.set, at.card, at.face) },
        onModule = ::switchTo,
    )
}

/** The game's colour and cover card: what it wears on the Shelf and
 *  in its workspace. "auto" leaves the choice to `ccgui.accentOf` /
 *  `ccgui.coverCard`. */
@Composable
private fun IdentityRow(vm: CreatorViewModel) {
    val g = vm.game
    val own = accentOrNull(g.accent)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("colour", color = Cg.dim, fontFamily = Cg.mono, fontSize = 10.sp)
        Text(
            "auto", color = if (own == null) Cg.ink else Cg.dim, fontFamily = Cg.mono, fontSize = 10.sp,
            modifier = Modifier.clip(RoundedCornerShape(6.dp))
                .border(1.dp, if (own == null) Cg.accent else Cg.border, RoundedCornerShape(6.dp))
                .clickable { vm.updateGame { it.copy(accent = "") } }
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )
        for (hex in ACCENTS) {
            val on = own == hex
            Box(
                Modifier.size(22.dp).clip(CircleShape)
                    .border(2.dp, if (on) Cg.ink else Color.Transparent, CircleShape)
                    .padding(3.dp).clip(CircleShape).background(Color(argbOf(hex)))
                    .clickable { vm.updateGame { it.copy(accent = hex) } },
            )
        }
    }
    // The cover on its own line: a card's name can be long.
    Row(verticalAlignment = Alignment.CenterVertically) {
        val cover = coverCard(g)?.faces?.firstOrNull()?.name
        val names = g.cards.mapNotNull { c -> c.faces.firstOrNull()?.name?.let { it to c.key() } }
        MenuPill("cover: " + (cover ?: "none") + if (g.cover.isEmpty()) " (auto)" else "", listOf("auto") + names.map { it.first }) { pick ->
            val key = names.firstOrNull { it.first == pick }?.second.orEmpty()
            vm.updateGame { it.copy(cover = key) }
        }
    }
}

