package com.ccg

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import kotlin.math.abs
import ccgui.BundleState
import ccgui.LandingIntent
import ccgui.LandingPage
import ccgui.bundleState
import ccgui.landingBodyTap
import ccgui.landingPageAfterImport
import ccgui.landingPages
import ccgui.landingStartPage
import ccgui.Back
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import ccgui.argbOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ccg.GameDoc
import ccg.Bundled
import ccg.problems
import ccg.build
import androidx.compose.runtime.setValue

// ---------------------------------------------------------------------------
// The Shelf: the app's one root -- every game, a new one, imports, and the
// games left on the table.
// ---------------------------------------------------------------------------

/** The landing: a snapping carousel of games ending on a new-game card. Tap decisions live in `ccgui.Landing`. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GamesScreen(
    vm: CreatorViewModel,
    store: GameStore,
    onOpen: (GameMeta) -> Unit,
    /** Play a game: its workspace, at Play's setup. */
    onPlay: (String) -> Unit,
    /** Back to a game left on the table. */
    onContinue: (String) -> Unit,
    onNew: () -> Unit,
) {
    var games by remember { mutableStateOf(store.list()) }
    // Games left on the table, read when the Shelf is shown.
    val parked = remember(games) { store.parked().filterValues { it.session.inProgress } }
    var confirmDelete by remember { mutableStateOf<GameMeta?>(null) }
    var pendingOverwrite by remember { mutableStateOf<GameDoc?>(null) }
    val ids = games.map { it.id }
    val pages = landingPages(ids)
    val pager = rememberPagerState(initialPage = landingStartPage(ids, vm.game.id)) { pages.size }
    val scope = rememberCoroutineScope()
    // Scroll after recomposition: inside the callback the pager doesn't have the new page yet.
    var bringForward by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(bringForward, pages.size) {
        val target = bringForward ?: return@LaunchedEffect
        if (target < pages.size) pager.animateScrollToPage(target)
        bringForward = null
    }

    /** Write the bundled content AND make the session agree with the disk (an
     *  open game would otherwise keep running the old rules). One function for
     *  the direct path and the confirm dialog. */
    fun applyRefresh(bundled: GameDoc) {
        store.save(bundled)
        games = store.list()
        if (vm.game.id == bundled.id) vm.loadGame(bundled)
        bringForward = games.indexOfFirst { it.id == bundled.id }.takeIf { it >= 0 }
    }

    // Confirm only when the on-disk copy differs (ignoring contentVersion).
    fun refreshGuarded(bundled: GameDoc) {
        val disk = store.load(bundled.id)
        val edited = disk != null && disk.copy(contentVersion = bundled.contentVersion) != bundled
        if (edited) pendingOverwrite = bundled else applyRefresh(bundled)
    }

    fun afterImport(before: List<String>) {
        games = store.list()
        landingPageAfterImport(before, games.map { it.id })?.let { bringForward = it }
    }
    val ctx = LocalContext.current
    val importBundle = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val before = games.map { it.id }
            store.importBundle(uri, ctx.contentResolver)
            afterImport(before)
        }
    }
    val importJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val before = games.map { it.id }
            store.importJson(uri, ctx.contentResolver)
            afterImport(before)
        }
    }

    // Loaded once per list change, not per frame.
    val builtins = remember { Bundled.builtin }
    val samples = remember { Bundled.samples }
    val states = remember(games) { builtins.associate { it.id to bundleState(store.load(it.id)?.contentVersion, it.contentVersion) } }
    val missingSamples = remember(games) { samples.count { store.load(it.id) == null } }
    fun bundleLabel(name: String, s: BundleState) = when (s) {
        BundleState.MISSING -> "add $name"
        BundleState.STALE -> "UPDATE $name (newer content in this build)"
        BundleState.CURRENT -> "refresh $name (up to date)"
    }
    val builtIn = buildList<Pair<String, () -> Unit>> {
        if (missingSamples > 0) {
            add("add $missingSamples sample game" + (if (missingSamples == 1) "" else "s") to {
                val before = games.map { it.id }
                samples.forEach { s -> if (store.load(s.id) == null) store.save(s) }
                afterImport(before)
            })
        }
        builtins.forEach { g -> add(bundleLabel(g.name, states.getValue(g.id)) to { refreshGuarded(g) }) }
    }
    val staleIds = states.filterValues { it == BundleState.STALE }.keys
    val sampleIds = remember { samples.map { it.id }.toSet() }
    fun kindOf(id: String) = when {
        builtins.any { it.id == id } -> "built-in"
        id in sampleIds -> "sample"
        else -> "yours"
    }

    fun tapBody(page: Int) {
        when (val t = landingBodyTap(pages, page, pager.currentPage)) {
            is LandingIntent.Center -> scope.launch { pager.animateScrollToPage(t.page) }
            is LandingIntent.Edit -> games.firstOrNull { it.id == t.id }?.let(onOpen)
            LandingIntent.None -> {}
        }
    }

    Column(Modifier.fillMaxSize()) {
        CgTopBar(
            title = "Games",
            trailing = {
                MenuPill(if (staleIds.isEmpty()) "built-in" else "built-in ⚠", builtIn.map { it.first }) { picked ->
                    builtIn.firstOrNull { it.first == picked }?.second?.invoke()
                }
            },
        )
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val pageW = (maxWidth * 0.82f).coerceAtMost(380.dp)
            val side = (maxWidth - pageW) / 2
            // Room for the IN PROGRESS rows under the dots, so they never
            // fall off the bottom of the screen.
            val contRows = games.count { it.id in parked }.coerceAtMost(3)
            val contH = if (contRows == 0) 0.dp else 36.dp + 58.dp * contRows
            val pageH = (maxHeight - 64.dp - contH).coerceIn(240.dp, 560.dp)
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                HorizontalPager(
                    state = pager,
                    modifier = Modifier.fillMaxWidth().height(pageH),
                    contentPadding = PaddingValues(horizontal = side),
                    pageSize = PageSize.Fixed(pageW),
                    pageSpacing = 14.dp,
                    beyondViewportPageCount = 1,
                    key = { i -> (pages.getOrNull(i) as? LandingPage.Game)?.id ?: "new-game" },
                ) { i ->
                    val focused = i == pager.currentPage
                    // Draw-phase only: no recomposition while swiping.
                    val pageMod = Modifier.fillMaxSize().graphicsLayer {
                        val d = abs((pager.currentPage - i) + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
                        val s = 1f - 0.07f * d
                        scaleX = s; scaleY = s
                        alpha = 1f - 0.45f * d
                    }
                    when (val p = pages.getOrNull(i)) {
                        is LandingPage.Game -> games.firstOrNull { it.id == p.id }?.let { meta ->
                            LandingGameCard(
                                meta = meta,
                                store = store,
                                kind = kindOf(meta.id),
                                focused = focused,
                                unsaved = meta.id == vm.game.id && vm.dirty,
                                stale = meta.id in staleIds,
                                onBodyTap = { tapBody(i) },
                                onEdit = { onOpen(meta) },
                                onPlaytest = { onPlay(meta.id) },
                                onUpdate = { builtins.firstOrNull { it.id == meta.id }?.let { refreshGuarded(it) } },
                                onDelete = if (games.size > 1) ({ confirmDelete = meta }) else null,
                                modifier = pageMod,
                            )
                        }
                        LandingPage.New -> LandingNewCard(
                            focused = focused,
                            onBodyTap = { tapBody(i) },
                            onNew = {
                                onNew()
                                games = store.list()
                            },
                            onImportBundle = {
                                importBundle.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                            },
                            onImportJson = {
                                importJson.launch(arrayOf("application/json", "text/plain", "*/*"))
                            },
                            modifier = pageMod,
                        )
                        null -> {}
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    pages.forEachIndexed { i, p ->
                        val on = i == pager.currentPage
                        val go = Modifier.clickable { scope.launch { pager.animateScrollToPage(i) } }
                        if (p == LandingPage.New) {
                            Text(
                                "+", color = if (on) Cg.accent else Cg.dim, fontFamily = Cg.mono, fontSize = 13.sp,
                                modifier = go.padding(horizontal = 5.dp),
                            )
                        } else {
                            Box(
                                Modifier
                                    .padding(horizontal = 3.dp)
                                    .size(if (on) 8.dp else 6.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(
                                        (p as? LandingPage.Game)?.let { g -> games.firstOrNull { it.id == g.id } }
                                            ?.let { Color(argbOf(it.accent)).copy(alpha = if (on) 1f else 0.45f) }
                                            ?: if (on) Cg.accent else Cg.borderMuted,
                                    )
                                    .then(go),
                            )
                        }
                    }
                }
                // CONTINUE: games left on the table, one tap back to the board.
                // Under the shelf, not above it -- this app opens for building.
                val cont = games.filter { it.id in parked }.take(3)
                if (cont.isNotEmpty()) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("IN PROGRESS", color = Cg.dim, fontFamily = Cg.mono, fontSize = 9.sp, letterSpacing = 1.5.sp)
                        for (meta in cont) {
                            val p = parked.getValue(meta.id)
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Cg.surface)
                                    .border(1.dp, Cg.border, RoundedCornerShape(10.dp))
                                    .clickable { onContinue(meta.id) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier.padding(end = 10.dp).size(width = 6.dp, height = 28.dp)
                                        .clip(RoundedCornerShape(3.dp)).background(Color(argbOf(meta.accent))),
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(meta.name, color = Cg.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${p.kind.label} · ${p.session.answers.size} moves" + if (p.session.debugged) " · debugged" else "", color = Cg.dim, fontFamily = Cg.mono, fontSize = 10.sp)
                                }
                                Text("resume ›", color = Cg.wire, fontFamily = Cg.mono, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
    // a destructive refresh says so, and only when it IS destructive.
    pendingOverwrite?.let { bundled ->
        CgDeleteConfirmDialog(
            title = "Replace your edited \"${bundled.name}\"?",
            body = "The copy on this device differs from the bundled one. " +
                "Refreshing overwrites your changes to this game with the built-in version.\n\n" +
                "The current copy is kept as a version first -- \"history\" in the game header " +
                "restores it -- so this is undoable, but it will not look like nothing happened.",
            confirmLabel = "Overwrite",
            onDismiss = { pendingOverwrite = null },
            onConfirm = { applyRefresh(bundled); pendingOverwrite = null },
        )
    }
    confirmDelete?.let { meta ->
        CgDeleteConfirmDialog(
            title = "Delete \"${meta.name}\"?",
            onDismiss = { confirmDelete = null },
            onConfirm = {
                store.delete(meta.id)
                games = store.list()
                if (vm.game.id == meta.id) {
                    store.list().firstOrNull()?.let { m -> store.load(m.id)?.let(vm::loadGame) }
                }
                confirmDelete = null
            },
        )
    }
}

/** A game's box: its cover -- the cover card's picture, or its name on
 *  the game's colour -- with the game's name over it, then what it holds and
 *  the two ways in. Off-centre, every tap centres the box first, so a
 *  half-visible button never fires. */
@Composable
private fun LandingGameCard(
    meta: GameMeta,
    store: GameStore,
    kind: String,
    focused: Boolean,
    unsaved: Boolean,
    stale: Boolean,
    onBodyTap: () -> Unit,
    onEdit: () -> Unit,
    onPlaytest: () -> Unit,
    onUpdate: () -> Unit,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(18.dp)
    val tint = Color(argbOf(meta.accent))
    val art = rememberCardArt(store, meta.id, meta.coverArt)
    Column(
        modifier
            .clip(shape)
            .background(if (focused) Cg.raised else Cg.surface)
            .border(1.dp, if (focused) tint.copy(alpha = 0.7f) else Cg.border, shape)
            .clickable(onClick = onBodyTap),
    ) {
        // The cover.
        Box(
            Modifier.fillMaxWidth().weight(1f)
                .background(Brush.linearGradient(listOf(lerp(tint, Cg.bg, 0.35f), lerp(tint, Cg.bg, 0.88f)))),
        ) {
            if (art != null) {
                ArtLayer(art, meta.coverRect, Modifier.graphicsLayer { alpha = 0.85f })
            } else if (meta.coverName != null) {
                // No picture: the cover card's name, set large and faint.
                Text(
                    meta.coverName, color = Color.White.copy(alpha = 0.10f), fontSize = 44.sp, lineHeight = 46.sp,
                    fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Clip,
                    modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
                )
            }
            // A scrim under the title, so it reads over any picture.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0.45f to Color.Transparent, 1f to Cg.bg.copy(alpha = 0.85f)),
                ),
            )
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    kind.uppercase(), color = Color.White.copy(alpha = 0.75f), fontFamily = Cg.mono, fontSize = 10.sp,
                    letterSpacing = 1.5.sp, modifier = Modifier.weight(1f),
                )
                if (focused && onDelete != null) {
                    Text(
                        "delete", color = Color.White.copy(alpha = 0.75f), fontFamily = Cg.mono, fontSize = 10.sp,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Cg.bg.copy(alpha = 0.45f))
                            .clickable(onClick = onDelete).padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
            }
            Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 18.dp, vertical = 14.dp)) {
                Text(
                    meta.name, color = Cg.ink, fontSize = 26.sp, lineHeight = 31.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
                if (meta.coverName != null && art != null) {
                    Text(meta.coverName, color = Cg.ink2.copy(alpha = 0.8f), fontFamily = Cg.mono, fontSize = 10.sp, maxLines = 1)
                }
            }
        }
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CgReadinessRow(
                listOf(
                    CgReadinessItem("${meta.cards} card" + if (meta.cards == 1) "" else "s"),
                    CgReadinessItem("${meta.sets} set" + if (meta.sets == 1) "" else "s"),
                    CgReadinessItem("${meta.decks} deck" + if (meta.decks == 1) "" else "s"),
                    CgReadinessItem(
                        if (meta.problems == 0) "ready" else "${meta.problems} issue" + if (meta.problems == 1) "" else "s",
                        if (meta.problems == 0) CgDot.OK else CgDot.WARN,
                    ),
                ),
            )
            if (stale) {
                Text(
                    "⚠ this build has newer content: update",
                    color = Cg.warn, fontFamily = Cg.mono, fontSize = 10.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .border(1.dp, Cg.warn.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .clickable(onClick = if (focused) onUpdate else onBodyTap)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            if (unsaved) Text("● unsaved edits", color = Cg.warn, fontFamily = Cg.mono, fontSize = 10.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val btn = RoundedCornerShape(11.dp)
                Box(
                    Modifier.weight(1f).clip(btn).border(1.dp, Cg.borderMuted, btn)
                        .clickable(onClick = if (focused) onEdit else onBodyTap)
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("edit", color = Cg.ink2, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                Box(
                    Modifier.weight(1f).clip(btn)
                        .background(Brush.linearGradient(listOf(lerp(tint, Color.White, 0.28f), tint)))
                        .clickable(onClick = if (focused) onPlaytest else onBodyTap)
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("▶ play", color = Cg.bg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

/** The last page: create on top, the two imports side by side below. */
@Composable
private fun LandingNewCard(
    focused: Boolean,
    onBodyTap: () -> Unit,
    onNew: () -> Unit,
    onImportBundle: () -> Unit,
    onImportJson: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .clip(shape)
            .background(if (focused) Cg.raised else Cg.surface)
            .border(1.dp, if (focused) Cg.accentDim else Cg.border, shape),
    ) {
        Box(
            Modifier.fillMaxWidth().weight(1f).clickable(onClick = if (focused) onNew else onBodyTap),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("+", color = Cg.accentLight, fontSize = 44.sp, fontFamily = Cg.mono)
                Text("new game", color = Cg.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("an empty ruleset to build on", color = Cg.dim, fontFamily = Cg.mono, fontSize = 10.sp)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Cg.border))
        Row(Modifier.fillMaxWidth().weight(1f)) {
            ImportHalf("bundle", ".ceg.zip — cards and art", if (focused) onImportBundle else onBodyTap)
            Box(Modifier.width(1.dp).fillMaxHeight().background(Cg.border))
            ImportHalf("JSON", ".json — rules and cards", if (focused) onImportJson else onBodyTap)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ImportHalf(what: String, detail: String, onClick: () -> Unit) {
    Box(
        Modifier.weight(1f).fillMaxHeight().clickable(onClick = onClick).padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("import", color = Cg.dim, fontFamily = Cg.mono, fontSize = 10.sp)
            Text(what, color = Cg.ink2, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = Cg.dim, fontFamily = Cg.mono, fontSize = 9.sp, textAlign = TextAlign.Center)
        }
    }
}
