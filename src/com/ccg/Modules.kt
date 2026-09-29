package com.ccg

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ccgui.Focus
import ccgui.Module
import ccgui.Nav
import ccgui.Route

// ---------------------------------------------------------------------------
// The module contract.
//
// A module is one file declaring one `ModuleSpec`, plus one line in
// `MODULES`. The shell draws the rest: the rail from `module`, `icon` and
// `badge`, the body from `content`. Where a module may GO is not its own
// business -- it asks its scope, and the scope asks `ccgui.Nav`, where the
// Back rules are tested (UiTest). ArchReviewTest checks that every
// `ccgui.Module` has exactly one spec here.
// ---------------------------------------------------------------------------

/** What a module is handed: the open game, where it stands, and the moves it
 *  may make. */
internal class ModuleScope(
    val vm: CreatorViewModel,
    val store: GameStore,
    val route: Route.Game,
    private val onPush: (Route) -> Unit,
    private val onNav: ((Nav) -> Nav) -> Unit,
    private val onBack: () -> Unit,
    /** Restore a past Save into the working copy (behind the unsaved guard). */
    val restore: (GameStore.GameVersion) -> Unit,
    /** Landscape: a module with a list and a detail shows both. */
    val twoPane: Boolean = false,
) {
    val id: String get() = route.id

    /** A drill-in or a cross-jump: Back returns here. */
    fun push(module: Module, focus: Focus? = null) = onPush(Route.Game(id, module, focus))

    /** A list's item opened as its detail: `Nav.openDetail`, so card after
     *  card in two panes is one Back, not one each. */
    fun openDetail(module: Module, focus: Focus) = onNav { it.openDetail(Route.Game(id, module, focus)) }

    /** Sideways, like a rail tap. */
    fun switchTo(module: Module) = onNav { it.switchModule(module) }

    /** Replace this screen (Play's setup becoming its table). */
    fun replaceWith(module: Module, focus: Focus? = null) = onNav { it.replaceTop(Route.Game(id, module, focus)) }

    /** The shell's Back, guards included. */
    fun back() = onBack()

    /** A cross-jump to a card's editor. */
    fun openCard(set: Int, card: Int, face: Int = 0) {
        vm.selectSet(set)
        vm.select(card)
        vm.selectFace(face)
        openDetail(Module.CARDS, Focus.Card(card))
    }
}

/** Landscape's list-and-detail: the list at the left, the detail (or
 *  [empty] when nothing is open) at the right. */
@Composable
internal fun TwoPanes(list: @Composable () -> Unit, detail: (@Composable () -> Unit)?, empty: String) {
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(0.42f).fillMaxHeight()) { list() }
        Box(Modifier.fillMaxHeight().width(1.dp).background(Cg.border))
        Box(Modifier.weight(0.58f).fillMaxHeight()) {
            if (detail != null) detail()
            else Text(empty, color = Cg.dim, fontFamily = Cg.mono, fontSize = 11.sp, modifier = Modifier.align(Alignment.Center))
        }
    }
}

internal class ModuleSpec(
    val module: Module,
    val icon: CgIconKind,
    /** The rail's dot: something needs attention (WARN) or is live (WIRE). */
    val badge: (CreatorViewModel) -> CgDot = { CgDot.NONE },
    val content: @Composable ModuleScope.() -> Unit,
)

/** The rail, in `ccgui.Module` order. */
internal val MODULES: List<ModuleSpec> = listOf(OverviewModule, RulesModule, CardsModule, DecksModule, PlayModule)

internal fun specOf(m: Module): ModuleSpec = MODULES.first { it.module == m }
