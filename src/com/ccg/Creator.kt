package com.ccg

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import ccgui.CardArea
import ccgui.CardLocation
import ccgui.diagnosticsByArea
import ccgui.locate
import ccgui.LandingIntent
import ccgui.LandingPage
import ccgui.bundleState
import ccgui.landingBodyTap
import ccgui.landingPageAfterImport
import ccgui.landingPages
import ccgui.landingStartPage
import ccgui.Back
import ccgui.Focus
import ccgui.Guard
import ccgui.Module
import ccgui.Nav
import ccgui.ParkedGame
import ccgui.PILOT_PROFILES
import ccgui.decked
import ccgui.kind
import ccgui.session
import ccgui.edits
import ccgui.reopened
import ccg.bundleDigest
import ccgui.PlayMode
import ccgui.Route
import ccgui.TableKind
import ccgui.ShellShape
import ccgui.shellShapeFor
import androidx.compose.ui.platform.LocalConfiguration
import ccgui.defaultLens
import ccgui.nextGame
import ccgui.rerolled
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.animation.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import ccgui.JumpTo
import ccgui.accentOf
import ccgui.argbOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import ccg.Keyword
import ccg.keywordsWithRules
import ccg.ActivatedAbility
import ccg.BoundTarget
import ccg.GameParams
import ccg.CharOp
import ccg.HiddenZoneDef
import ccg.permanents
import ccg.BUILTIN_TYPES
import ccg.BlockRule
import ccg.DamageModel
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import ccg.ScreenRule
import ccg.CmpOp
import ccg.BoolExpr
import ccg.fieldsForTypes
import ccgui.editableFields
import ccgui.PHASES
import ccg.ResourceModel
import ccg.Orientation
import ccg.PlayerCounterDef
import ccg.BUILTIN_COUNTER_KINDS
import ccg.CounterKindDef
import ccg.usedCounterKinds
import ccg.DEFAULT_PLAYER_COUNTERS
import ccg.GameDoc
import ccg.SetDoc
import ccg.DeckDoc
import ccg.RulesDoc
import ccg.CHOSEN
import ccg.CardDoc
import ccg.Diagnostic
import ccg.ArtSlot
import ccg.CardLayout
import ccg.Bundled
import ccg.CombatDoc
import ccg.stepNames
import ccg.stanceNames
import ccg.CounterTrack
import ccg.StatCorner
import ccg.layoutFor
import ccg.Cost
import ccg.CounterDef
import ccg.DeckEntry
import ccg.DeckRules
import ccg.DeckSlotDef
import ccg.IdentityRule
import ccg.HiddenZone
import ccg.Recast
import ccg.zoneName
import ccgui.HIDDEN_ZONES
import ccgui.zoneOfName
import ccg.Effect
import ccg.FaceDoc
import ccg.IntExpr
import ccg.PhaseEffects
import ccg.PhaseSpec
import ccg.PlayZoneDef
import ccg.SELF
import ccg.TurnMode
import ccg.TurnStructure
import ccg.problems
import ccg.TypeDef
import ccg.PlayerRef
import ccg.ZoneScope
import ccg.gameDocFromJson
import ccg.gameDocToJson
import ccg.build
import ccg.creatures
import ccg.lit
import ccgui.costSummary
import ccgui.ruleboxOf
import ccgui.poolReport
import ccgui.sections
import ccg.Rules

// ---------------------------------------------------------------------------
// The Creator: an IMMUTABLE GameDoc edited by `copy(...)` in a ViewModel
// (neverEqualPolicy, so a structurally-equal copy still recomposes).
// ---------------------------------------------------------------------------

internal val BUILTIN_TYPE_NAMES = listOf(
    "Creature", "Instant", "Sorcery", "Enchantment", "Artifact", "Land", "Planeswalker", "Saga", "Battle",
)
internal val BODY_FIELDS = listOf("power", "toughness", "fast", "slow", "defense")

class CreatorViewModel : ViewModel() {
    /** The WORKING copy of the game being edited. `neverEqualPolicy` so a
     *  structurally-equal copy still recomposes -- the v2 recompose-hazard
     *  class is gone. Nothing persists this to disk except an explicit Save
     *; `saved` is the last-persisted snapshot. */
    var game by mutableStateOf(seedGame(), neverEqualPolicy())
        private set

    /** The last persisted state. `dirty` is a real structural comparison
     *  (data-class `equals`, unaffected by `neverEqualPolicy`). */
    private var saved by mutableStateOf(game)
    val dirty: Boolean get() = game != saved

    var setIndex by mutableStateOf(0)
        private set
    var selected by mutableStateOf(0)
        private set
    var deckIndex by mutableStateOf(0)
        private set

    val set: SetDoc get() = game.sets.getOrElse(setIndex) { game.sets.firstOrNull() ?: SetDoc() }
    val card: CardDoc get() = set.cards.getOrElse(selected) { set.cards.firstOrNull() ?: CardDoc() }
    /** WHICH face of the selected card is being edited. */
    var faceIndex by mutableStateOf(0)
        private set

    fun selectFace(i: Int) {
        faceIndex = i.coerceIn(0, (card.faces.size - 1).coerceAtLeast(0))
    }

    val face: FaceDoc get() = card.face(faceIndex.coerceIn(0, card.faces.size - 1))
    val deck: DeckDoc? get() = game.decks.getOrNull(deckIndex)

    fun selectSet(i: Int) {
        setIndex = i.coerceIn(0, (game.sets.size - 1).coerceAtLeast(0))
        selected = 0
    }

    fun select(i: Int) {
        selected = i.coerceIn(0, (set.cards.size - 1).coerceAtLeast(0))
        // A different card may have fewer faces than the one just left.
        faceIndex = 0
    }

    fun selectDeck(i: Int) {
        deckIndex = i.coerceIn(0, (game.decks.size - 1).coerceAtLeast(0))
    }

    fun addCard() {
        game = game.updateSet(setIndex) {
            it.addCard(CardDoc(faces = listOf(FaceDoc("New Card", setOf("Creature"), mapOf("power" to 1, "toughness" to 1)))))
        }
        selected = set.cards.size - 1
    }

    fun deleteCard(i: Int) {
        if (set.cards.size <= 1) return
        game = game.updateSet(setIndex) { it.removeCard(i) }
        select(selected)
    }

    fun updateFace(f: (FaceDoc) -> FaceDoc) {
        val i = faceIndex
        game = game.updateSet(setIndex) { it.updateCard(selected) { c -> c.updateFace(i, f) } }
    }

    fun addFace() {
        // Seeded from the CURRENT face's types: the back of a transforming card
        // is nearly always the same kind of thing, and a face seeded with
        // "Creature" in a game with no creatures would be unplayable.
        val seed = FaceDoc("${face.name} // back", face.types)
        game = game.updateSet(setIndex) { it.updateCard(selected) { c -> c.addFace(seed) } }
        faceIndex = (card.faces.size - 1).coerceAtLeast(0)
    }

    fun removeFace(i: Int) {
        game = game.updateSet(setIndex) { it.updateCard(selected) { c -> c.removeFace(i) } }
        selectFace(faceIndex)
    }

    fun updateCardDoc(f: (CardDoc) -> CardDoc) {
        game = game.updateSet(setIndex) { it.updateCard(selected, f) }
    }

    fun renameGame(name: String) {
        game = game.copy(name = name)
    }

    fun updateGame(f: (GameDoc) -> GameDoc) {
        game = f(game)
    }

    /** Edit the rules half without every call site spelling out the nesting. */
    fun updateRules(f: (RulesDoc) -> RulesDoc) {
        game = game.copy(rules = f(game.rules))
    }

    fun addSet() {
        game = game.addSet("Set ${game.sets.size + 1}")
        selectSet(game.sets.size - 1)
    }

    fun removeSet(i: Int) {
        game = game.removeSet(i)
        selectSet(setIndex)
    }

    fun addDeck() {
        game = game.addDeck("Deck ${game.decks.size + 1}")
        selectDeck(game.decks.size - 1)
    }

    fun removeDeck(i: Int) {
        game = game.removeDeck(i)
        selectDeck(deckIndex)
    }

    fun updateDeck(f: (DeckDoc) -> DeckDoc) {
        if (game.decks.isEmpty()) return
        game = game.updateDeck(deckIndex, f)
    }

    fun toJson(): String = gameDocToJson(game)

    /** The working copy is now the persisted state. Call after `store.save`. */
    fun markSaved() {
        saved = game
    }

    /** Like `markSaved()`, but also adopts `persisted` as the working copy:
     *  `GameStore.save()` may have minted card ids, and without adopting them
     *  every later Save would mint fresh ones. */
    fun adoptPersisted(persisted: GameDoc) {
        game = persisted
        saved = persisted
    }

    /** Throw away unsaved edits -- back to the last persisted state. */
    fun revert() {
        game = saved
        setIndex = 0
        selected = 0
        deckIndex = 0
        resetPlaytest(saved.decks.size)
    }

    fun loadGame(g: GameDoc) {
        game = g
        saved = g
        setIndex = 0
        selected = 0
        deckIndex = 0
        resetPlaytest(g.decks.size)
    }

    /** Crash restore: adopt `working` as the editing state while keeping
     *  `baseline` (the on-disk file) as the saved snapshot -- so the game opens
     *  dirty and an explicit Save persists the recovered work. */
    fun restoreWorking(working: GameDoc, baseline: GameDoc) {
        game = working
        saved = baseline
        setIndex = 0
        selected = 0
        deckIndex = 0
        resetPlaytest(working.decks.size)
    }

    fun loadJson(s: String) {
        runCatching { gameDocFromJson(s) }.onSuccess { loadGame(it) }
    }

    /** Replace the working copy with a historical version. `saved` stays the
     *  on-disk file, so this is an ordinary unsaved edit: Save commits it, and
     *  what it replaced becomes a version of its own (reversible). The caller
     *  owns the unsaved-changes guard, as for `loadGame`. */
    fun restoreVersion(v: GameDoc) {
        game = v
        setIndex = 0
        selected = 0
        deckIndex = 0
        resetPlaytest(v.decks.size)
    }

    private fun seedGame() = GameDoc(
        id = java.util.UUID.randomUUID().toString().take(12),
        name = "My First Game",
        sets = listOf(SetDoc("Core", SEED_CARDS)),
        decks = listOf(DeckDoc("Starter", SEED_CARDS.map { DeckEntry(it.key(), 4) })),
    )

    // -- Playtest state that must outlive navigation --------------------------
    // The hotseat is a deterministic replay, so the session is enough to
    // rebuild the game when the Playtest tab is re-entered. Reset only when
    // the game is swapped.
    /** Play's setup tab: 0 a game, 1 the single-card run, 2 the scripted deck. */
    var playTab by mutableStateOf(0)

    /** The kind of table the game on it was started as, and the kind the
     *  setup offers next. */
    var tableKind by mutableStateOf(TableKind.VS_AI)
    var setupKind by mutableStateOf(TableKind.VS_AI)

    /** How the table is being looked at: Play (one seat) or Debug
     *  (everything, and the tools). Switchable mid-game on a vs AI table. */
    var lens by mutableStateOf(PlayMode.PLAYER)

    /** Cards shows its list (0) or the Pool report (1). */
    var cardsView by mutableStateOf(0)

    /** THE play session (`ccgui.PlaySession`); this class is one consumer of it,
     *  the Player another. */
    var session by mutableStateOf(ccgui.PlaySession())

    /** The view toggles, in the same holder the Player uses -- so the Creator is
     *  one consumer of the play UI rather than its owner. A `val`: its FIELDS
     *  are the observable state, so it is reset in place, never replaced. */
    val playView = PlayView()

    /** A hotseat game is under way (at least one move made) -- drives the
     *  spine's "Playtest" live dot so it's visible from any tab. */
    val hotGameInProgress: Boolean get() = session.inProgress

    /** A new game on the table: [kind], from [session]. */
    fun startTable(kind: TableKind, session: ccgui.PlaySession) {
        tableKind = kind
        setupKind = kind
        lens = kind.defaultLens()
        this.session = session
        playView.reset()
    }

    /** "Try it": the card into your hand on the table, as a sandbox
     *  edit, in the Debug lens -- on the game already there, or a new
     *  both-seats game when there is none. */
    fun tryCard(key: String) {
        if (!session.inProgress) startTable(TableKind.HOTSEAT, session.nextGame().copy(sandbox = true))
        lens = PlayMode.PLAYTEST
        playView.queued += ccg.TableEdit.Conjure(key, playView.viewer ?: "P0", ccg.EditZone.HAND)
    }

    /** The Play setup's Sandbox tab, as it was left. */
    var sandboxSetup by mutableStateOf(ccgui.SandboxSetup())

    /** Bumped by every scenario saved, so the Sandbox tab's list reads again. */
    var scenariosSaved by mutableStateOf(0)

    /** A new sandbox table from a preset: in the Debug lens, the
     *  preset's cards queued as edits for the first priority question. */
    fun startSandbox(setup: ccgui.SandboxSetup, roll: Int) {
        val base = session.nextGame().decked(game.decks.size).rerolled(game.decks.size, PILOT_PROFILES, roll)
        startTable(setup.kind(), setup.session(base))
        lens = PlayMode.PLAYTEST
        playView.queued += setup.edits()
    }

    /** A saved scenario back on the table, replayed under the game as it is. */
    fun openScenario(sc: ccgui.Scenario) {
        startTable(sc.table.kind, sc.reopened(game.bundleDigest(), session))
        lens = PlayMode.PLAYTEST
    }

    /** The game this game left on the table (`GameStore.park`). */
    fun adoptParked(p: ParkedGame) {
        tableKind = p.kind
        setupKind = p.kind
        // A game that was debugged comes back in the Debug lens it was left in.
        lens = if (p.session.debugged) PlayMode.PLAYTEST else p.kind.defaultLens()
        session = p.session
        playView.reset()
    }

    private fun resetPlaytest(decks: Int) {
        playTab = 0
        session = ccgui.PlaySession.forGame(decks)
        playView.reset()
    }

    init {
        // `game` is initialised above; safe to read here (init block last).
        session = ccgui.PlaySession.forGame(game.decks.size)
    }
}

private val SEED_CARDS = listOf(
    CardDoc(faces = listOf(FaceDoc("Stoneback Ox", setOf("Creature"), mapOf("power" to 3, "toughness" to 3)))),
    CardDoc(
        faces = listOf(
            FaceDoc(
                "Ember Bolt", setOf("Instant"),
                castEffect = Effect.Choose(creatures(), Effect.DealDamage(lit(3), BoundTarget(CHOSEN))),
            ),
        ),
    ),
    CardDoc(
        faces = listOf(FaceDoc("Sunspire", setOf("Planeswalker"))),
        entersWith = listOf(CounterDef("loyalty", lit(4))),
    ),
)

/** The workspace rail: one item per `MODULES` spec. */
private fun railItems(vm: CreatorViewModel): List<CgSpineItem> =
    MODULES.map { CgSpineItem(it.module.key, it.module.label, it.badge(vm), it.icon) }

/** The app shell: the Shelf, and a game's workspace above it. Every Back rule
 *  is `ccgui.Nav`'s (src/ui/Shell.kt, UiTest); this draws the top route and
 *  hands the hardware key, the edge-swipe and the top bar's chevron to
 *  `Nav.back`. [nav] is hoisted to `MainActivity`, which saves it. */
@Composable
fun CreatorScreen(
    vm: CreatorViewModel,
    store: GameStore,
    nav: Nav,
    onNav: (Nav) -> Unit,
) {
    // The authoring vocabulary, provided once for the whole Creator and read
    // ambiently by every filter/type/zone control. Derived from the DOC, not a
    // built ruleset, which costs a full compile.
    val vocab = AuthoringVocab(
        types = BUILTIN_TYPE_NAMES + vm.game.rules.extraTypes.map { it.name },
        zones = listOf("battlefield") + vm.game.rules.extraZones.map { it.id },
        // The kinds a PERMANENT can carry: builtins plus what this game
        // declares -- or, for a game never declared, what it uses. Not player
        // counters: "life" is not a counter to put on a card.
        counters = (BUILTIN_COUNTER_KINDS + (vm.game.rules.counterKinds?.map { it.name } ?: vm.game.usedCounterKinds())).distinct(),
        // Every field any declared type has -- builtins included, so a
        // Creature game still offers power/toughness.
        fields = (BUILTIN_TYPES.values + vm.game.rules.extraTypes)
            .flatMap { it.fields }.filter { it.isNotBlank() }.distinct(),
        // The game's OWN turn structure. A phase-naming control that offers
        // Magic's seven in a game with five of its own is not a shortcut, it is
        // wrong -- the trigger would name a phase that never happens.
        phases = vm.game.rules.turn.phases.map { it.name }.filter { it.isNotBlank() }
            .distinct().ifEmpty { PHASES.split(",") },
        playerCounters = vm.game.rules.playerCounters.map { it.name }.ifEmpty { listOf(ccg.LIFE) },
        stances = vm.game.rules.combat.compile().stanceNames().toList(),
        // Harvested from the cards, printed and GRANTED, so a keyword typed once
        // becomes a chip everywhere. Suggestions are the keywords something in
        // THIS game reads, never a fixed list of Magic's.
        keywords = (
            vm.game.keywordsWithRules().toList() +
                vm.game.cards.flatMap { c -> c.faces.flatMap { it.keywords } } +
                vm.game.cards.flatMap { c ->
                    c.faces.flatMap { f -> f.statics.flatMap { s -> s.ops } }
                        .filterIsInstance<CharOp.GrantKeyword>().map { it.keyword }
                }
            ).filter { it.isNotBlank() }.distinct(),
    )
    val top = nav.top
    val route = top as? Route.Game
    // A Back that stopped to ask, and what to do on yes.
    var asking by remember { mutableStateOf<Back.Ask?>(null) }
    // A navigation held back by the unsaved-changes guard (opening another
    // game from the Shelf).
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun saveNow() {
        val persisted = store.save(vm.game)
        store.gcArt(persisted)   // drop art files no card references any more
        vm.adoptPersisted(persisted)
    }
    fun navGuarded(action: () -> Unit) {
        if (vm.dirty) pending = action else action()
    }
    /** Load [doc] as the open game, with its parked table if it has one. */
    fun openDoc(doc: GameDoc) {
        vm.loadGame(doc)
        store.loadParked(doc.id)?.let { vm.adoptParked(it) }
    }
    /** Make [id] the open game (asking about unsaved edits to another one),
     *  then go to [then]. */
    fun withGame(id: String, then: () -> Unit) {
        if (vm.game.id == id) then()
        else navGuarded { store.load(id)?.let { openDoc(it); then() } }
    }
    fun follow(b: Back) {
        when (b) {
            is Back.Go -> onNav(b.nav)
            is Back.Ask -> asking = b
            Back.Exit -> {}   // the Shelf: BackHandler is disabled, the system finishes
        }
    }
    fun goBack() = follow(nav.back(tableRunning = vm.session.inProgress, dirty = vm.dirty))
    fun push(r: Route) = onNav(nav.push(r))

    // The table owns Back while a card is focused on it (its own handler is
    // composed later, so it wins); otherwise Back is the shell's everywhere
    // but the Shelf. The edge-swipe stands down on the table, where a lane
    // scroll near the edge must not fling you off the board.
    BackHandler(enabled = route != null) { goBack() }

    // The jump search, over the workspace. Back closes it first.
    var searching by remember { mutableStateOf(false) }
    if (searching && (route == null || nav.immersive)) searching = false
    BackHandler(enabled = searching) { searching = false }
    fun jump(to: JumpTo) {
        searching = false
        val id = route?.id ?: return
        when (to) {
            is JumpTo.Card -> {
                vm.selectSet(to.at.set)
                vm.select(to.at.card)
                vm.selectFace(to.at.face)
                push(Route.Game(id, Module.CARDS, Focus.Card(to.at.card)))
            }
            is JumpTo.Section -> push(Route.Game(id, Module.RULES, Focus.Section(to.name)))
            is JumpTo.Deck -> {
                vm.selectDeck(to.index)
                push(Route.Game(id, Module.DECKS))
            }
            JumpTo.Overview -> push(Route.Game(id, Module.OVERVIEW))
        }
    }

    // Inside a game the app wears the game's colour; on the Shelf, its own.
    val reduced = rememberReducedMotion()
    val wanted = if (route != null) Color(argbOf(accentOf(vm.game))) else Cg.violet
    LaunchedEffect(wanted) {
        if (reduced) Cg.wear(wanted)
        else Animatable(Cg.accent).animateTo(wanted, tween(380)) { Cg.wear(value) }
    }
    // Opening a game grows its workspace out of the Shelf; leaving settles
    // the Shelf back in. Keyed on the game, not the module.
    val place = route?.id
    val arrive = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(place) {
        if (!reduced) {
            arrive.snapTo(0f)
            arrive.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 420f))
        }
    }

    // A game on the table is parked on every change, so Continue finds it and
    // a process death does not lose it.
    LaunchedEffect(vm.session, vm.tableKind) {
        val id = vm.game.id
        if (vm.session.inProgress) store.park(id, ParkedGame(vm.tableKind, vm.session))
    }

    CompositionLocalProvider(LocalVocab provides vocab) {
    CgScreenBackground {
        // Portrait: the rail along the bottom. Landscape: stood on its side at
        // the left, and two panes where a module has a list and a detail.
        val cfg = LocalConfiguration.current
        val landscape = shellShapeFor(cfg.screenWidthDp, cfg.screenHeightDp) == ShellShape.LANDSCAPE
        val showRail = route != null && !nav.immersive
        fun selectModule(key: String) {
            Module.entries.firstOrNull { it.key == key }?.let { onNav(nav.switchModule(it)) }
        }
        Row(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    val a = arrive.value
                    alpha = 0.35f + 0.65f * a
                    val sc = if (place != null) 0.93f + 0.07f * a else 1.03f - 0.03f * a
                    scaleX = sc; scaleY = sc
                }
                .cgEdgeBackGesture(enabled = route != null && !nav.immersive) { goBack() },
        ) {
        if (showRail && landscape) CgSideRail(railItems(vm), route!!.module.key, ::selectModule)
        Column(Modifier.weight(1f).fillMaxHeight()) {
            if (route != null && !nav.immersive) CgTopBar(
                title = when (val f = route.focus) {
                    is Focus.Card -> vm.face.name
                    is Focus.Section -> "${rulesSection(f).title} — Rules"
                    else -> if (route.module == Module.OVERVIEW) vm.game.name else "${route.module.label} — ${vm.game.name}"
                },
                dirty = vm.dirty,
                onSave = { saveNow() },
                leading = { TopAction("‹") { goBack() } },
                trailing = { TopAction("⌕", fontSize = 20) { searching = true } },
            )
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when {
                    route == null -> GamesScreen(
                        vm, store,
                        onOpen = { meta -> withGame(meta.id) { onNav(nav.openGame(meta.id)) } },
                        onPlay = { id -> withGame(id) { onNav(nav.openGame(id, Module.PLAY)) } },
                        onContinue = { id -> withGame(id) { onNav(nav.openGame(id, Module.PLAY, Focus.Table)) } },
                        onNew = {
                            navGuarded {
                                val g = GameDoc(id = store.newId(), name = store.freeName())
                                openDoc(store.save(g))
                                onNav(nav.openGame(g.id, Module.RULES))
                            }
                        },
                    )
                    else -> specOf(route.module).content(
                        ModuleScope(
                            vm, store, route,
                            onPush = ::push,
                            onNav = { onNav(it(nav)) },
                            onBack = { goBack() },
                            restore = { v -> navGuarded { store.loadVersion(vm.game.id, v.savedAt)?.let(vm::restoreVersion) } },
                            twoPane = landscape,
                        ),
                    )
                }
                if (searching) JumpSearchOverlay(vm.game, onPick = ::jump, onClose = { searching = false })
            }
            if (showRail && !landscape) CgSpine(
                items = railItems(vm),
                selectedKey = route!!.module.key,
                onSelect = ::selectModule,
            )
        }
        }
    }

    asking?.let { ask ->
        fun yes() { asking = null; follow(ask.then) }
        when (ask.guard) {
            Guard.LEAVE_TABLE -> AlertDialog(
                onDismissRequest = { asking = null },
                title = { Text("Leave the table?", color = Cg.ink) },
                text = { Text("The game stays under Continue on the Shelf, and in Play.", color = Cg.muted) },
                confirmButton = { TextButton(onClick = { yes() }) { Text("Leave", color = Cg.accentLight) } },
                dismissButton = { TextButton(onClick = { asking = null }) { Text("Stay", color = Cg.muted) } },
                containerColor = Cg.surface,
            )
            Guard.UNSAVED -> CgSaveGuardDialog(
                name = vm.game.name,
                onSave = { saveNow(); yes() },
                onDiscard = { vm.revert(); yes() },
                onCancel = { asking = null },
            )
        }
    }
    pending?.let { act ->
        CgSaveGuardDialog(
            name = vm.game.name,
            onSave = { saveNow(); pending = null; act() },
            onDiscard = { vm.revert(); pending = null; act() },
            onCancel = { pending = null },
        )
    }
}
}


@Composable
internal fun TopAction(label: String, fontSize: Int = 11, onClick: () -> Unit) {
    // A thumb-sized target however short the label: "‹" and "⌕" are one glyph.
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)
            .sizeIn(minWidth = 44.dp, minHeight = 40.dp).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Cg.accentLight, fontFamily = Cg.mono, fontSize = fontSize.sp)
    }
}

@Composable
internal fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    Text(
        text.ifBlank { "—" },
        color = if (on) Cg.ink else Cg.ink2,
        fontFamily = Cg.mono,
        fontSize = 11.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (on) Cg.accentWash else Cg.surfaceAlt)
            .border(1.dp, if (on) Cg.accent else Cg.border, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    )
}
