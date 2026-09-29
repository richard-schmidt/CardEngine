package com.ccg

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import ccgui.Back
import ccgui.PILOT_PROFILES
import ccgui.PlayMode
import ccgui.TableKind
import ccgui.lensSwitchable
import androidx.compose.runtime.LaunchedEffect
import ccgui.nextGame
import ccgui.ParkedGame
import ccgui.Scenario
import ccgui.asCorpusCase
import ccgui.decked
import ccgui.scenarioFileName
import ccg.Corpus
import ccg.corpusToJson
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import ccgui.rerolled
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ccg.GameDoc
import ccg.build
import ccgui.sections
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import ccgui.Module
import ccgui.Focus

// ---------------------------------------------------------------------------
// The Play module: the setup (resume, a new game, the card tools) and the
// table.
// ---------------------------------------------------------------------------

private val PLAY_TABS = listOf("Game", "Sandbox")
private val TABLE_KINDS = TableKind.entries.map { it.label }

/** Play's front: a game already on the table to resume, a new game to set up
 *  (which table, whose decks, which opponent), and the card tools. The one
 *  setup for every game -- the Player's deck screen and the debugger's
 *  deck cycling both became this. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaySetupScreen(vm: CreatorViewModel, store: GameStore, onTable: () -> Unit) {
    val doc = vm.game
    val s = vm.session
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp)) {
            CgSegmented(PLAY_TABS, PLAY_TABS[vm.playTab]) { t -> vm.playTab = PLAY_TABS.indexOf(t).coerceAtLeast(0) }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (vm.playTab) {
                1 -> SandboxBody(vm, store, onTable)
                else -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (s.inProgress) {
                        Text("ON THE TABLE", color = Cg.dim, fontFamily = Cg.mono, fontSize = 9.sp)
                        CgButton("▶  Resume — ${vm.tableKind.label}, ${s.answers.size} moves" + if (s.debugged) " (debugged)" else "") { onTable() }
                        Spacer(Modifier.height(6.dp))
                    }
                    Text("NEW GAME", color = Cg.dim, fontFamily = Cg.mono, fontSize = 9.sp)
                    CgSegmented(TABLE_KINDS, vm.setupKind.label) { k ->
                        vm.setupKind = TableKind.entries.first { it.label == k }
                    }
                    Text(
                        when (vm.setupKind) {
                            TableKind.VS_AI -> "You play P0 against the bot at P1. Switch to the Debug lens at any time to see both hands and use the tools; the game is then marked as debugged."
                            TableKind.HOTSEAT -> "Both seats by hand, every hand open, with the debug tools."
                        },
                        color = Cg.dim, fontSize = 11.sp,
                    )
                    if (doc.decks.isEmpty()) {
                        Text("This game declares no decks -- both seats will play plain tokens.", color = Cg.dim, fontFamily = Cg.mono, fontSize = 11.sp)
                    }
                    // Cycling, like the table's own deck buttons. A random seat
                    // shows the deck it is CURRENTLY holding (the roll happens
                    // on Start).
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CgButton(seatDeckLabel("P0", doc, s.p0Deck, s.p0Random), Modifier.weight(1f), enabled = !s.p0Random) {
                            vm.session = s.cycleP0(doc.decks.size)
                        }
                        CgButton(seatDeckLabel("P1", doc, s.p1Deck, s.p1Random), Modifier.weight(1f), enabled = !s.p1Random) {
                            vm.session = s.cycleP1(doc.decks.size)
                        }
                    }
                    CgCheck("P0 deck: random each game", s.p0Random) { vm.session = s.copy(p0Random = it) }
                    CgCheck("P1 deck: random each game", s.p1Random) { vm.session = s.copy(p1Random = it) }
                    if (vm.setupKind == TableKind.VS_AI) {
                        // A profile is a whole plan, not a difficulty.
                        Text(
                            "Proactive races, Reactive answers, Attrition grinds.",
                            color = Cg.dim, fontSize = 11.sp,
                        )
                        CgButton("Opponent: ${s.pilot ?: PILOT_PROFILES.first()}", enabled = !s.pilotRandom) {
                            vm.session = s.cyclePilot(PILOT_PROFILES)
                        }
                        CgCheck("Opponent: random each game", s.pilotRandom) { vm.session = s.copy(pilotRandom = it) }
                    }
                    Spacer(Modifier.height(4.dp))
                    // The moment a new game starts, so the sticky picks resolve
                    // here: the platform roll is handed to the pure `rerolled`.
                    // A game already on the table is replaced.
                    CgButton(if (s.inProgress) "▶  Start a new game (replaces the one on the table)" else "▶  Start") {
                        vm.startTable(
                            vm.setupKind,
                            s.nextGame().decked(doc.decks.size).rerolled(doc.decks.size, PILOT_PROFILES, kotlin.random.Random.nextInt()),
                        )
                        onTable()
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

/** A seat's deck button label. A randomised seat names the deck it is holding
 *  AND says it will change. */
private fun seatDeckLabel(seat: String, doc: GameDoc, deck: Int, random: Boolean): String {
    val name = if (deck == ccg.DeckPick.NONE) "no deck" else doc.decks.getOrNull(deck)?.name ?: "tokens"
    return if (random) "$seat: random (was $name)" else "$seat: $name"
}

/** The table: the one board, as the kind of game it was started as. The shell
 *  hides the rail and owns Back (it asks, then parks the game). */
@Composable
private fun TableScreen(
    vm: CreatorViewModel,
    /** the table renders authored art -- it is the surface an author checks
     *  their art ON. Declared before the trailing lambda. */
    store: GameStore,
    onLeave: () -> Unit,
) {
    val vsAi = vm.tableKind == TableKind.VS_AI
    // Seeing the bot's hand, or undoing, makes this game a test: marked once
    // the Debug lens is on and a move has been made.
    LaunchedEffect(vm.lens, vm.session.answers.size) {
        if (vsAi && vm.lens == PlayMode.PLAYTEST && vm.session.inProgress && !vm.session.debugged) {
            vm.session = vm.session.copy(debugged = true)
        }
    }
    HotseatBody(
        doc = vm.game,
        session = vm.session,
        onSession = { vm.session = it },
        view = vm.playView,
        // vs AI: the bot takes the seat opposite the viewer, and its answers
        // are recorded like a person's. Debug: both seats by hand.
        aiSeat = if (vsAi) "P1" else null,
        aiProfile = if (vsAi) vm.session.pilot else null,
        mode = vm.lens,
        store = store,
        onLens = if (vm.tableKind.lensSwitchable()) ({ vm.lens = it }) else null,
        onScenario = { name ->
            if (store.saveScenario(vm.game.id, Scenario(name, ParkedGame(vm.tableKind, vm.session)))) vm.scenariosSaved++
        },
        onExit = onLeave,
    )
}

/** The Sandbox tab: a new sandbox table from a preset -- an empty
 *  table or the game's decks, both seats by hand or the bot at P1, the cards
 *  wanted in hand -- and the scenarios saved from one. It replaced the
 *  Single card and Scripted runs: both are now a table you start. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SandboxBody(vm: CreatorViewModel, store: GameStore, onTable: () -> Unit) {
    val doc = vm.game
    val setup = vm.sandboxSetup
    val s = vm.session
    val context = LocalContext.current
    var filter by remember { mutableStateOf("") }
    // Bumped by a delete, so the list reads the folder again.
    var tick by remember { mutableStateOf(0) }
    val scenarios = remember(tick, doc.id, vm.scenariosSaved) { store.scenarios(doc.id) }
    var exporting by remember { mutableStateOf<Scenario?>(null) }
    var deleting by remember { mutableStateOf<Pair<String, Scenario>?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    // A scenario, as a conformance case: the bundle and the recording in one
    // file, which test.sh replays once synced (~/cge-test/cases).
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val sc = exporting
        exporting = null
        if (uri != null && sc != null) {
            note = runCatching {
                val case = sc.table.session.asCorpusCase(doc, doc.compile().playable())
                val ok = store.exportText(corpusToJson(Corpus(doc, listOf(case))), uri, context.contentResolver)
                if (ok) "exported \"${sc.name}\": ${case.digests.size} questions" else "the export failed"
            }.getOrElse { "can't export \"${sc.name}\": ${it.message}" }
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "A test bench: any card of the game, no deck rules. On the table, the Debug drawer's Sandbox puts " +
                "cards anywhere, and holding a card offers to move it.",
            color = Cg.dim, fontSize = 11.sp,
        )
        Text("TABLE", color = Cg.dim, fontFamily = Cg.mono, fontSize = 9.sp)
        CgSegmented(SANDBOX_TABLES, SANDBOX_TABLES[if (setup.empty) 0 else 1]) { t ->
            vm.sandboxSetup = setup.copy(empty = t == SANDBOX_TABLES[0])
        }
        if (!setup.empty) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CgButton(seatDeckLabel("P0", doc, s.p0Deck, false), Modifier.weight(1f)) { vm.session = s.decked(doc.decks.size).cycleP0(doc.decks.size) }
                CgButton(seatDeckLabel("P1", doc, s.p1Deck, false), Modifier.weight(1f)) { vm.session = s.decked(doc.decks.size).cycleP1(doc.decks.size) }
            }
        }
        CgSegmented(SANDBOX_SEATS, SANDBOX_SEATS[if (setup.bot) 1 else 0]) { t ->
            vm.sandboxSetup = setup.copy(bot = t == SANDBOX_SEATS[1])
        }
        Text("IN P0'S HAND AT THE START", color = Cg.dim, fontFamily = Cg.mono, fontSize = 9.sp)
        CgField("find a card", filter) { filter = it }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            doc.cards.filter { filter.isBlank() || it.faces[0].name.contains(filter, ignoreCase = true) }.forEach { c ->
                val k = c.key()
                Chip(c.faces[0].name, k in setup.hand) {
                    vm.sandboxSetup = setup.copy(hand = if (k in setup.hand) setup.hand - k else setup.hand + k)
                }
            }
        }
        CgButton(if (s.inProgress) "▶  Open the sandbox (replaces the game on the table)" else "▶  Open the sandbox") {
            vm.startSandbox(setup, kotlin.random.Random.nextInt())
            onTable()
        }
        Spacer(Modifier.height(6.dp))
        Text("SCENARIOS", color = Cg.dim, fontFamily = Cg.mono, fontSize = 9.sp)
        if (scenarios.isEmpty()) {
            Text("None yet. On a table in the Debug lens: ⋯ › Save scenario.", color = Cg.dim, fontSize = 11.sp)
        }
        for ((stem, sc) in scenarios) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(sc.name, color = Cg.ink, fontSize = 13.sp)
                    Text("${sc.table.kind.label} · ${sc.table.session.answers.size} moves", color = Cg.dim, fontFamily = Cg.mono, fontSize = 10.sp)
                }
                Chip("open", false) { vm.openScenario(sc); onTable() }
                Chip("export", false) { exporting = sc; export.launch(scenarioFileName("${doc.name} $stem") + ".case.json") }
                Chip("✕", false) { deleting = stem to sc }
            }
        }
        note?.let { Text(it, color = Cg.warn, fontFamily = Cg.mono, fontSize = 11.sp) }
        Spacer(Modifier.height(24.dp))
    }
    deleting?.let { (stem, sc) ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${sc.name}\"?") },
            text = { Text("The scenario goes; an exported case stays where you saved it.") },
            confirmButton = { TextButton(onClick = { store.deleteScenario(doc.id, stem); deleting = null; tick++ }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

private val SANDBOX_TABLES = listOf("Empty table", "The game's decks")
private val SANDBOX_SEATS = listOf("Both seats by hand", "Bot at P1")

/** A selectable (long-press to copy) engine-log dump. */
@Composable
internal fun LogBlock(lines: List<String>) {
    Text("engine log — long-press to select / copy", color = Cg.muted, fontFamily = Cg.mono, fontSize = 10.sp)
    SelectionContainer {
        Column {
            lines.forEach { line -> Text(line, color = Cg.ink2, fontFamily = Cg.mono, fontSize = 12.sp) }
        }
    }
}

// ---------------------------------------------------------------------------
// The Pool socket. It renders `ccgui.sections()` and decides nothing else --
// order, wording and caveats live in `src/ui/PoolStats.kt`, shared with
// `agent/pool`. The only decision here is what a TAP does.
// ---------------------------------------------------------------------------

internal val PlayModule = ModuleSpec(
    Module.PLAY, CgIconKind.PLAY,
    badge = { vm -> if (vm.hotGameInProgress) CgDot.WIRE else CgDot.NONE },
) {
    when (route.focus) {
        Focus.Table -> TableScreen(vm, store, onLeave = ::back)
        else -> PlaySetupScreen(vm, store, onTable = { replaceWith(Module.PLAY, Focus.Table) })
    }
}
