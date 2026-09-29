package com.ccg

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import ccg.cardOf
import ccg.AttackRange
import ccg.CardDefinition
import ccg.CardDoc
import ccg.CombatTarget
import ccg.Diagnostic
import ccg.GameState
import ccg.ObjectId
import ccg.PlayerId
import ccg.Effect
import ccg.PlayerInput
import ccg.Run
import ccg.recorded
import ccg.step
import ccg.PriorityAction
import ccg.Rules
import ccg.Legality
import ccg.legality
import ccg.build
import ccg.newGame
import ccg.CardSource
import ccg.ArtSlot
import ccg.CastZone
import ccg.HiddenZone
import ccg.CardRef
import ccgui.undoPoint
import ccgui.tableRun
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.interaction.MutableInteractionSource
import ccgui.PlayIntent
import ccgui.cardLabel
import ccgui.combatBoardTargets
import ccg.Answer
import ccg.asAnswer
import ccg.raw
import ccg.playActionFor
import ccg.toAction
import ccg.Question
import ccg.default
import ccgui.encodeAnswer
import ccgui.boardTapTargets
import ccgui.canAttackFace
import ccgui.tapAnswer
import ccgui.pilotFor
import ccg.ModeOption
import ccgui.ObservedPilot
import ccgui.modeOptionSummary
import ccgui.activationsBy
import ccgui.PermMove
import ccgui.arcsFor
import ccgui.ArcEnd
import ccgui.ArcSpec
import ccgui.recap
import ccgui.openingRecap
import ccgui.ChangeKind
import ccgui.Change
import ccgui.JuiceConfig
import ccgui.BoardFx
import ccgui.boardFx
import ccgui.Viewpoint
import ccg.publicZoneIds
import ccgui.PlayMode
import ccgui.boardLayout
import ccgui.ruleboxOf
import ccgui.commitIntent
import ccgui.hasStructuredBoard
import ccgui.PlaySession
import ccg.hasAnyAction
import ccg.GameDoc
import ccg.TableEdit
import ccg.EditZone
import ccgui.editZoneOf
import ccgui.editZoneNamed
import ccgui.moveTargets
import ccg.label
import ccg.LIFE
import ccgui.opening
import ccgui.boundTo
import ccg.bundleDigest
import ccgui.costSummary
import ccgui.isSpellCard
import ccgui.lbl
import ccgui.openZonesFor
import ccgui.tapIntent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------
// Interactive hotseat. The game is a `ccg.step` loop in a coroutine on the
// Compose scope: each question goes to the bot's pilot or to `HotseatInput`,
// which publishes a prompt and parks on a `CompletableDeferred` the UI
// completes.
//
// A seat plays out of its real HAND, spending the card (`PriorityAction.from`);
// cards with a `recast` are also offered from the graveyard. The SANDBOX
// toggle offers the whole bundle, casting with no card behind the spell.
// ---------------------------------------------------------------------------



/**
 * A person's seat: `ask` raises the question as a prompt and parks until the
 * prompt bar calls `answer`. Nothing here records or replays: the game runs
 * through `ccg.step`, the session holds every answer, and UNDO is the session
 * minus its last answer, stepped again -- exact, any depth, no state
 * surgery.
 */
class HotseatInput(
    /** Run, and awaited, before the prompt is raised -- where the response
     *  BEAT is paid for. Everything the opponent did has been applied by now,
     *  so the board can show it before this seat is asked. */
    private val beforeAsk: suspend (Question) -> Unit = {},
    private val onPrompt: (Question?) -> Unit,
) {
    private var pending: CompletableDeferred<Any?>? = null

    /** Raise `q` and return a TYPED answer: a mismatch degrades to the
     *  question's default instead of a ClassCastException in a coroutine. */
    suspend fun ask(q: Question): Answer {
        beforeAsk(q)
        val d = CompletableDeferred<Any?>()
        pending = d
        onPrompt(q)
        val ans = d.await()
        pending = null
        onPrompt(null)
        // Typed: this is what makes a session serialisable, and what a
        // lockstep peer or a save file would carry.
        return encodeAnswer(q, ans) ?: q.default()
    }

    /** Completed by the prompt bar when the seated player answers. */
    fun answer(value: Any?) {
        pending?.complete(value)
    }
}

/** The pure-UI toggles of a play view -- how you are LOOKING at a session, as
 *  opposed to what the session IS (`ccgui.PlaySession`). Compose state, so it
 *  cannot live in src/ui. The Creator holds one on its ViewModel so it survives
 *  navigation; the Player holds one in a `remember`. */
class PlayView {
    /** Is the sandbox binder open? */
    var sandbox by mutableStateOf(false)

    /** Table edits waiting for a person's priority question -- the only
     *  place the engine takes one. The binder queues here when nobody is
     *  being asked, and so does "Try it" from a card. */
    val queued = mutableStateListOf<TableEdit>()
    var zones by mutableStateOf(false)
    /** Which zone the sheet is browsing -- set by tapping a
     *  seat-strip count, or left null when opened from the Zones toggle. */
    var zoneFocus by mutableStateOf<ZoneFocus?>(null)

    /** Is the game log open? Off by default: the log grows all game, and
     *  under the board it would scroll the board away on a phone. */
    var log by mutableStateOf(false)

    /** The motion settings. On the view holder so they can be flipped mid-game
     *  against the same board; a preference, not cleared by [reset]. */
    var juice by mutableStateOf(JuiceConfig.DEFAULT)

    /** Is the motion tuning sheet open? */
    var tuner by mutableStateOf(false)

    /** Is the Player's ⋯ menu open? Player only -- the Playtest tab keeps its
     *  actions in the open, where a debugger wants them. */
    var menu by mutableStateOf(false)

    /** WHOSE side of the table is the near one: a fixed seat, never derived from
     *  priority (the board would flip mid-round). Null = not chosen; the board
     *  falls back to the first seat in turn order. */
    var viewer by mutableStateOf<PlayerId?>(null)

    /** Pools the player explicitly folded or unfolded. A map because "not said
     *  anything yet" is a third state. */
    val poolOverride = mutableStateMapOf<String, Boolean>()

    /** Back to defaults, in place. The holder is a `val` wherever it is kept --
     *  its FIELDS are the observable state, so it never needs replacing. */
    fun reset() {
        sandbox = false
        queued.clear()
        zones = false
        zoneFocus = null
        viewer = null
        log = false
    }
}

/** What happened while you were not being asked, waiting to be READ -- the
 *  response window as a beat. Content is `ccgui.recap`,
 *  a diff of the state the viewer last saw against the one they are asked about
 *  (total by construction); the pilot's line is the HEADLINE, since a diff says
 *  what changed but not who chose it. A plain holder of observable fields,
 *  `remember`ed. */
class BeatQueue {
    /** Announcements from the opponent's pilot -- the CAUSE. */
    private val headlines = mutableStateListOf<String>()

    /** The last state this viewer was actually shown. */
    private var lastSeen: GameState? = null

    /** The opening position, waiting to be shown once. */
    private var opening: List<Change>? = null

    /** The beat on screen right now, or null. */
    var showing by mutableStateOf<Beat?>(null)
        private set

    fun push(text: String) {
        headlines += text
    }

    fun clear() {
        headlines.clear()
        showing = null
    }

    /** Forget the game entirely -- a restart, a deck swap, a new opening. */
    fun reset() {
        clear()
        lastSeen = null
        opening = null
    }

    /** Declare the position the game STARTS from: stash the opening recap, and
     *  set the mark to the setup state, so the first beat also covers what the
     *  engine did before this seat was first asked (triggers, draws, an
     *  opponent's ability). */
    fun open(state: GameState, viewer: PlayerId) {
        reset()
        opening = openingRecap(state, viewer)
        lastSeen = state
    }

    /** Move the mark without showing anything, and drop the opening: a replay's
     *  steps were already on screen the first time round. */
    fun markSeen(state: GameState) {
        lastSeen = state
        opening = null
    }

    /** Show what changed since this viewer last looked, then hand the board
     *  back. Suspends on the engine's own path, so the display duration is real.
     *  Time scales with how much there is to read, under a cap; the opening gets
     *  a longer budget. */
    suspend fun beat(state: GameState, viewer: PlayerId) {
        val isOpening = opening != null
        // The opening position first, then whatever the engine did between it
        // and this first question -- one beat, read top to bottom in the order
        // it happened.
        val changes = opening.orEmpty() + (lastSeen?.let { recap(it, state) } ?: emptyList())
        opening = null
        lastSeen = state
        val lines = headlines.toList()
        headlines.clear()
        if (changes.isEmpty() && lines.isEmpty()) return
        showing = Beat(lines, changes, isOpening)
        delay(
            if (isOpening) OPENING_MS
            else (BEAT_BASE_MS + BEAT_PER_LINE_MS * (lines.size + changes.size)).coerceAtMost(BEAT_MAX_MS),
        )
        showing = null
    }

    private companion object {
        const val BEAT_BASE_MS = 700L
        const val BEAT_PER_LINE_MS = 260L
        const val BEAT_MAX_MS = 3200L
        /** The opening is the one beat worth actually reading through. */
        const val OPENING_MS = 4500L
    }
}

/** What the board shows instead of a game that has errors. */
@Composable
private fun NotPlayableBody(errors: List<Diagnostic>, onExit: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("This game can't start", color = Cg.ink, fontSize = 18.sp)
        Text(
            "${errors.size} error(s) -- fix them in the Creator, then play.",
            color = Cg.ink2, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp),
        )
        errors.forEach { Text("✗  ${it.message}", color = Cg.warn, fontFamily = Cg.mono, fontSize = 11.sp, modifier = Modifier.padding(vertical = 2.dp)) }
        CgButton("back", modifier = Modifier.padding(top = 12.dp), onClick = onExit)
    }
}

/** One beat: what caused it, what it did, and whether it is the opening. */
class Beat(
    val headlines: List<String>,
    val changes: List<Change>,
    val opening: Boolean,
) {
    /** Every board object this beat is about -- what the board should light.
     *  The reason `Change` carries ids at all: the sentence and the thing it is
     *  about belong on screen together. */
    val subjects: Set<ObjectId> = changes.flatMapTo(mutableSetOf()) { it.subjects }
}

/** The board, with no Creator in its signature: the bundle, the session, a way
 *  to advance it, the view toggles and a way out. The Creator and the Player
 *  each pass their own. Its controls sit in a scrollable row at the top. */
@Composable
fun HotseatBody(
    doc: GameDoc,
    session: PlaySession,
    onSession: (PlaySession) -> Unit,
    view: PlayView,
    /** A seat played by the BOT (the Player passes the seat opposite the viewer;
     *  the Playtest tab passes null).
     *
     *  Its answers are recorded in the session like a person's, so a
     *  session replays even after the bot's scoring changes. */
    aiSeat: PlayerId? = null,
    /** WHICH pilot plays `aiSeat` -- a `ccgui.PILOT_PROFILES` name, or
     *  null for the default. Passed in rather than read from the session here
     *  because the board does not own the session; the Player does, and it is
     *  the Player that offers the choice. */
    aiProfile: String? = null,
    /** Which master this board serves. The Player and the Playtest tab diverge
     * ; the visibility policy is extracted once into
     *  `ccgui.Viewpoint`, so the board takes a viewpoint rather than splitting
     *  into two copies. */
    mode: PlayMode = PlayMode.PLAYTEST,
    /** What resolves an art filename to a file on disk. Null renders the
     *  placeholder, so a test or a preview can build a board without one. */
    store: GameStore? = null,
    /** Switch the lens: null when this table has one lens. The
     *  game replays under the new one -- the same game, seen differently.
     *  Second-to-last, before the trailing `onExit`. */
    onLens: ((PlayMode) -> Unit)? = null,
    /** Save the table as a named scenario: null where there is no
     *  game to save it in. Before the trailing `onExit`. */
    onScenario: ((String) -> Unit)? = null,
    onExit: () -> Unit,
) {
    // Only a game without errors starts; otherwise the errors, and a way out.
    val compiled = remember(doc) { doc.compile() }
    val rules = compiled.runnable
    if (rules == null) {
        NotPlayableBody(compiled.errors, onExit)
        return
    }
    // Card key -> its picture and both framings, built ONCE
    // from the doc. The join itself lives in `ccgui.artRefs` where test.sh can
    // watch it.
    val artOf: Map<String, ccgui.CardArtRef> = remember(doc) { ccgui.artRefs(doc) }
    val builtCards: List<Pair<CardDoc, CardDefinition>> = remember(doc) {
        // Through the compiled rules: the cards the game will run.
        doc.cards.map { it to rules.cards.getValue(it.key()) }
    }
    // One attack at a time, as a priority action: the game has an attack program.
    val attacksAreActions = remember(doc) { rules.attackProgram != null }

    var prompt by remember { mutableStateOf<Question?>(null) }
    var boardState by remember { mutableStateOf<GameState?>(null) }
    var finalState by remember { mutableStateOf<GameState?>(null) }

    // The play session lives in `ccgui.PlaySession`; the view model holds one,
    // so a hotseat survives navigating away (re-entry replays the answers).
    val p0Deck = session.p0Deck
    val p1Deck = session.p1Deck
    // Whose side is near. With a bot there is exactly ONE human seat, so the
    // default is the seat the bot does not hold (pointing it at the bot's seat
    // would silently show its hand and hide yours).
    val viewer = view.viewer
        ?: boardState?.turnOrder?.firstOrNull { it != aiSeat }
        ?: boardState?.turnOrder?.firstOrNull()
        ?: "P0"
    // One viewpoint for the board, the seat strips and the zone explorer.
    // `declaredPublic` carries the game's own public zones.
    val vp = remember(mode, viewer, rules) {
        Viewpoint(mode, viewer, rules.publicZoneIds())
    }

    // What the opponent did since this seat was last asked, waiting to be shown
    // ("the response window is a BEAT, not a wait").
    val beats = remember(session.generation) { BeatQueue() }

    // The CURRENT session, for callbacks built once per game: captured
    // directly, every answer was appended to the session as it was when the
    // game began, dropping the ones since.
    val currentSession by rememberUpdatedState(session)
    // Keyed on the LENS as well: switching it replays the game under the new
    // one (the loop below restarts), and the new run needs a fresh input.
    val input = remember(session.generation, mode) {
        HotseatInput(
            beforeAsk = { p ->
                // Not every prompt carries a state -- `PickNumber` asks for a
                // number and nothing else. Such a prompt is always a follow-up
                // to one that DID, so there is nothing new to recap at it.
                p.state?.let { st ->
                    // Advance the board FIRST, so the beat is read over a
                    // board that already shows what it names, then hold it.
                    boardState = st
                    beats.beat(st, p.player)
                }
            },
        ) { p ->
            prompt = p
            p?.state?.let { boardState = it }
        }
    }

    // The bot's seat, if there is one. Only the BOT is observed: a beat
    // announces what someone else did. `aiProfile` is a remember key, so
    // changing the opponent changes who answers from here on; what it already
    // answered is in the session.
    val bot: PlayerInput? = remember(beats, aiSeat, aiProfile, rules) {
        aiSeat?.let { seat -> ObservedPilot(pilotFor(aiProfile, seat, rules)) { _, _, text -> beats.push(text) } }
    }

    // The game these answers must have been recorded against.
    val bundleDigest = remember(doc) { doc.bundleDigest() }
    // Why the last game restarted, for the new game's log.
    var restartNote by remember { mutableStateOf<String?>(null) }

    // Who gave each answer, in order: what a person's Undo needs against a
    // bot (`ccgui.undoPoint`). Rebuilt by every run of the loop below.
    val askedBy = remember(session.generation, mode) { mutableListOf<PlayerId>() }

    LaunchedEffect(session.generation, p0Deck, p1Deck, bundleDigest, session.bundle, mode) {
        if (builtCards.isEmpty()) return@LaunchedEffect
        prompt = null
        finalState = null
        // A session recorded against another version of this game restarts
        // rather than replaying its answers into different rules. Any change
        // (a restart, or stamping a fresh session) returns and runs again once
        // it is the current session: a bot answering in THIS run would append
        // to the unbound one and lose the stamp.
        val bound = session.boundTo(bundleDigest)
        if (bound.session != session) {
            if (bound.dropped > 0) {
                restartNote = "the game was edited since this session was recorded: " +
                    "${bound.dropped} answers dropped, restarted"
            }
            onSession(bound.session)
            return@LaunchedEffect
        }
        // A new game inherits nothing from the old one's response windows.
        // `beats.open` below re-arms it against the fresh setup position.
        beats.reset()
        // The opening position: `ccg.startGame`, through the session's picks.
        val start = session.opening(doc, rules).let { s -> restartNote?.let { s.logged(it) } ?: s }
        restartNote = null
        boardState = start
        // The position the game begins from, and the mark everything after it
        // is measured against.
        beats.open(start, viewer)
        finalState = runCatching {
            // The session's answers, stepped to the first one it lacks,
            // one at a time so the loop knows who gave each.
            askedBy.clear()
            // A person's table takes the binder's edits as answers;
            // pilots never send one. A bench also stops at every priority
            // question, and an empty seat never decks out (`tableRun`).
            var st = step(rules, session.tableRun(start))
            for (a in session.answers) {
                if (st.over) break
                askedBy += st.full!!.player
                st = st.next(a)
            }
            // A replayed move is not news: advance the mark to where the
            // replay ends, or resuming would recap the entire game.
            if (session.answers.isNotEmpty()) {
                beats.clear()
                beats.markSeen(st.question?.state ?: st.state)
            }
            while (!st.over) {
                val q = st.full!!
                val a = when {
                    bot != null && q.player == aiSeat -> bot.ask(st.question!!)
                    // A queued edit goes in at a person's priority question,
                    // before they are asked.
                    q is Question.Priority && view.queued.isNotEmpty() -> Answer.Edit(view.queued.removeAt(0))
                    // The Debug lens sees the whole game.
                    else -> input.ask(if (mode == PlayMode.PLAYTEST) q else st.question!!)
                }
                // Live and replayed alike, only the recorded reference plays:
                // an in-process action with none is a pass both times, so a
                // session replays to the game played. An edit is its own record.
                // `answered` does NOT bump the generation: the same game, one
                // answer further on.
                val ref = a.recorded()
                askedBy += q.player
                onSession(currentSession.answered(ref))
                st = st.next(ref)
            }
            st.state
        }.getOrElse { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            var s = (boardState ?: start).logged("hotseat error: ${e::class.simpleName}: ${e.message ?: e}")
            e.stackTrace.take(6).forEach { s = s.logged("  at $it") }
            s
        }
        boardState = finalState
        prompt = null
    }

    val shown = prompt?.state ?: boardState
    // A card lifted into a focus view by a held tap.
    var focus by remember { mutableStateOf<FocusCard?>(null) }
    // The in-progress answer to a multi-step board prompt (declare attackers /
    // assign blockers) -- shared by the board (which builds it up
    // by tapping) and the PromptBar (whose confirm button submits it). Reset
    // whenever the prompt changes.
    val boardAnswer = remember(prompt) { BoardAnswer() }
    // A play selected but still waiting on WHICH zone. Here because the taps
    // that raise the question come from the hand lanes and the bar's "Play X"
    // chip. Keyed on `prompt`: a new prompt clears it.
    val laneChoice = remember(prompt) { mutableStateOf<PriorityAction.PlayPermanent?>(null) }
    // Armed-and-waiting-for-a-confirm. Lifted out of `BoardView` because in
    // portrait the viewer's hand is DOCKED below the scroll: the board and the
    // dock must arm one thing, not one each.
    val armed = remember(prompt) { mutableStateOf<ObjectId?>(null) }
    // WHOSE hand is docked, or null to leave every hand inline.
    val pinnedHand: PlayerId? = if (vp.godMode) null else viewer

    // FOLD STATE for an always-visible pool: FOLDED UNTIL TAPPED. Auto-opening
    // took the dock row nearest the thumb. Folding costs no information: the
    // chip is lit by `ccgui.anyPlayableIn` whether or not the pool is open. Not
    // keyed on `prompt`: a player who opened it is reading it.
    val poolOpenFor: (String) -> Boolean = { z -> view.poolOverride[z] == true }

    // WHICH SHAPE. Asked of `ccgui.playSurfaceFor`, a rule about SIZE, rather
    // than a `LocalConfiguration.orientation` read scattered through the board
    //: one answer, so the board and the lane picker cannot disagree.
    val cfg = LocalConfiguration.current
    val railed = ccgui.playSurfaceFor(cfg.screenWidthDp, cfg.screenHeightDp) ==
        ccgui.PlaySurface.RAILED

    /** A person's Undo: back past the bot's replies (`ccgui.undoPoint`). */
    fun undo() {
        undoPoint(askedBy.toList(), aiSeat)?.let { onSession(session.undoneTo(it)) }
    }

    /** A table edit: at a person's priority question the engine
     *  takes it now; otherwise it waits for the next one. */
    fun edit(e: TableEdit) {
        val q = prompt
        if (q is Question.Priority && q.player != aiSeat) input.answer(Answer.Edit(e)) else view.queued += e
    }
    // The scenario being named, or null.
    var naming by remember { mutableStateOf<String?>(null) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val reducedMotionBody = rememberReducedMotion()
        // Split once, ranks before hand; a taller prompt takes from the board, never the hand.
        val edgeHeights = remember(shown, rules, vp.viewer) {
            shown?.let { st ->
                boardLayout(rules, st, vp.viewer).rows.map { r -> ccgui.seatEdgeDp(rules, st, r.anchors) }
            } ?: listOf(ccgui.SEAT_EDGE_DP, ccgui.SEAT_EDGE_DP)
        }
        // Measured, not assumed: the stack badge's text scales with font size.
        var stackMeasuredDp by remember { mutableStateOf(0) }
        val stackBlockDp = if ((shown?.stack?.size ?: 0) > 0) stackMeasuredDp.coerceAtLeast(30) + 4 else 0
        val boardRanksPerSeat = remember(rules) { ccgui.boardRanks(rules)?.size ?: 1 }
        val boardFixed = ccgui.boardFixedDp(
            edgeHeights, boardRanksPerSeat, stackBlockDp,
            seatGap = SEAT_GAP.value.toInt(), rankPad = LANE_PAD_V.value.toInt() * 2, rankGap = RANK_GAP.value.toInt(),
        )
        val surfaceSplit = if (railed || pinnedHand == null) null else {
            val seats = shown?.turnOrder?.size ?: 2
            ccgui.splitPlaySurface(
                available = maxHeight.value.toInt() - TOP_BAND_DP - PROMPT_LINE_DP,
                boardFixed = boardFixed,
                ranks = seats * boardRanksPerSeat,
            )
        }
        Column(Modifier.fillMaxSize()) {
            // THE TOP BAND, in both shapes: toolbar plus the turn readout, so
            // the chrome costs one band and nothing floats over the lanes.
            // Leave, where the game stands, the lens, and the tools drawer;
            // everything else lives in the drawer.
            Row(
                Modifier.fillMaxWidth().background(Cg.surface).padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TopAction("‹") { onExit() }
                shown?.let { st ->
                    Text(
                        "turn ${st.turnNumber} · ${st.phase.ifBlank { "—" }} · ${st.activePlayer}",
                        color = Cg.accent, fontFamily = Cg.mono, fontSize = 10.sp, maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                } ?: Spacer(Modifier.weight(1f))
                if (session.debugged) {
                    Text("debugged", color = Cg.warn, fontFamily = Cg.mono, fontSize = 9.sp)
                }
                if (onLens != null) LensToggle(mode, onLens)
                // The binder in reach whenever the Debug lens is on, not only
                // in the drawer.
                if (vp.godMode) TopAction("✦ binder") { view.sandbox = !view.sandbox; view.menu = false }
                TopAction(if (view.menu) "×" else "⋯", fontSize = 16) { view.menu = !view.menu }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Cg.border))
            // The board, identical in both shapes; only its CONTAINER differs.
            // ONE measurement decides both the berth height and
            // whether the board scrolls at all (a rank below the fold cannot be
            // played into).
            //
            // Deliberately NOT a report-back from the board: "board measures,
            // sets a flag, the flag changes the scroll modifier, which changes
            // the height the board measures" is a loop that would settle or
            // oscillate depending on the device. Decided here, once, and the
            // height is PASSED DOWN -- if the two decisions could disagree the
            // board would size itself to fit and then be clipped anyway.
            @Composable
            fun BoardArea(modifier: Modifier) = BoxWithConstraints(modifier) {
            val seats = shown?.turnOrder?.size ?: 2
            val ranksPerSeat = boardRanksPerSeat
            val density = LocalDensity.current
            // Grows only; resets when the board's shape changes.
            var correction by remember(seats, ranksPerSeat, edgeHeights, stackBlockDp) { mutableStateOf(0) }
            val fitH = if (railed) null else ccgui.rankHeightFor(
                available = maxHeight.value.toInt() - correction,
                fixed = boardFixed,
                ranks = seats * ranksPerSeat,
                maxRank = surfaceSplit?.rankH ?: 112,
            )?.dp
            Column(
                // Tighter than the Creator's 12dp: in portrait every dp of
                // width is a dp of lane. Portrait first, with YGO Duel Links as
                // the reference -- a compact board that does not try to show
                // everything at once.
                //
                // Always scrollable, fitH or not. `fixed` above is an ESTIMATE
                // of the anchor strips' height, not a measurement; when it
                // undercounts, the viewer's own anchor strip (last in this
                // Column) would overflow under the prompt band with no way to
                // reach it. A scroll container that fits its content is a no-op.
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    // After verticalScroll, so this is the content height; skipped during transients.
                    .onSizeChanged { sz ->
                        if (railed) return@onSizeChanged
                        val transient = fitH == null || armed.value != null || laneChoice.value != null ||
                            boardAnswer.activeBlocker != null || boardAnswer.blockAssign.isNotEmpty() ||
                            view.zones || view.tuner || view.log
                        correction = ccgui.overflowCorrection(
                            correction, (sz.height / density.density).roundToInt(), maxHeight.value.toInt(), transient,
                        )
                    }
                    .padding(horizontal = 6.dp, vertical = if (railed) 2.dp else 4.dp),
                verticalArrangement = Arrangement.spacedBy(if (railed) 2.dp else 8.dp),
            ) {
                if (builtCards.isEmpty()) {
                    Text("This bundle has no cards to play.", color = Cg.ink2, fontSize = 12.sp)
                } else {
                    shown?.let {
                        BoardView(it, rules, prompt, vp, beats.showing?.subjects.orEmpty(), railed, input, boardAnswer, laneChoice, armed, pinnedHand, onOpenZone = { zf ->
                            view.zoneFocus = zf
                            view.zones = true
                        }, onTogglePool = { z -> view.poolOverride[z] = !poolOpenFor(z) }, juice = view.juice,
                            artOf = artOf, artStore = store, artGameId = doc.id, poolOpen = poolOpenFor, rankH = fitH,
                            onStackHeight = { stackMeasuredDp = it }) { fc ->
                            // The rulebox is attached HERE, where the bundle's
                            // docs are in scope, rather than threaded through
                            // every tile that can raise a focus. Only the DOC
                            // can describe a card -- a built CardDefinition
                            // holds closures, not data.
                            focus = fc.copy(rulebox = ruleboxFor(fc.key, builtCards, rules))
                        }
                    }
                    if (view.zones) {
                        shown?.let {
                            ZonesView(it, rules, vp, view.zoneFocus, onCard = { fc -> focus = fc.copy(rulebox = ruleboxFor(fc.key, builtCards, rules)) }) { zf -> view.zoneFocus = zf }
                        }
                    }
                    // the tuning sheet, under the board rather than
                    // over it -- the whole point is to watch the board react
                    // while a slider moves, so it must not cover it.
                    if (view.tuner) {
                        JuiceTuner(view.juice, onChange = { view.juice = it }) { view.tuner = false }
                    }
                    // The log is reachable (G5 keeps it top level) but opt-in,
                    // so it cannot grow under the board and push it off screen.
                    if (view.log) shown?.log?.takeIf { it.isNotEmpty() }?.let { LogBlock(it) }
                }
            }
            }
            @Composable
            fun Prompt(modifier: Modifier, floating: Boolean = false) =
                PromptBar(
                    prompt, finalState, builtCards, attacksAreActions, rules, input, boardAnswer, laneChoice, armed, modifier, floating,
                    viewer = if (vp.godMode) null else vp.viewer,
                ) { fc ->
                    focus = fc.copy(rulebox = ruleboxFor(fc.key, builtCards, rules))
                }

            // THE ARRANGEMENT: board, then a bounded prompt band, then the hand.
            // Height is what the play surface lacks, so the bands must be
            // CHEAP, not absent -- and nothing is ever drawn OVER the board,
            // where it could hide the lane a zone question is answered with.
            // Railed, the seat numbers flow on one line, which pays for both
            // bands.
            BoardArea(Modifier.fillMaxWidth().weight(1f).starfield(drift = view.juice.enabled && !reducedMotionBody))
            Prompt(
                // The prompt band is height-capped in both shapes and scrolls
                // past the cap, so a longer prompt never shrinks the board
                // under the player. 168dp matches PromptBar's own default cap.
                if (railed) Modifier.fillMaxWidth().heightIn(max = 96.dp)
                else Modifier.fillMaxWidth().heightIn(max = 168.dp)
            )
            // THE BOTTOM EDGE: prompt above, hand below, so the hand never
            // moves. Docked outside the board's scroll, so looking at the board
            // never takes your cards off screen.
            // Player only; the Playtest tab shows every seat's hand in place.
            if (pinnedHand != null) {
                shown?.players?.get(pinnedHand)?.let { me ->
                    HandDock(
                        shown, rules, me,
                        hasPriority = (prompt as? Question.Priority)?.player == pinnedHand,
                        armed = armed, laneChoice = laneChoice, input = input,
                        poolOpen = poolOpenFor,
                        onTogglePool = { z -> view.poolOverride[z] = !poolOpenFor(z) },
                        artOf = artOf, artStore = store, artGameId = doc.id,
                        handH = surfaceSplit?.handH?.dp,
                    ) { fc -> focus = fc.copy(rulebox = ruleboxFor(fc.key, builtCards, rules)) }
                }
            }
        }
        beats.showing?.let { BeatOverlay(it) }
        if (view.menu) {
            TableDrawer(railed = railed, onDismiss = { view.menu = false }) {
                fun tool(label: String, on: Boolean = false, action: () -> Unit) = ToolSpec(label, on, action)
                val debugTools = if (!vp.godMode) emptyList() else buildList {
                    // Undo is a debugger's tool: in reach, it quietly changes
                    // how you play, so the Play lens does not offer it.
                    if (session.inProgress) {
                        add(tool("↶ Undo") { undo() })
                        add(tool("⟲ Restart") { onSession(session.restarted()) })
                    }
                    add(tool("⤮ Shuffle") { onSession(session.reshuffled()) })
                    // The binder: edits to the table, recorded as answers.
                    add(tool("✦ Binder") { view.sandbox = true; view.menu = false })
                    // A position to come back to, and to export as a test.
                    if (onScenario != null && session.inProgress) add(tool("✚ Save scenario") { naming = ""; view.menu = false })
                    // WHOSE side is near, and which decks. Changing a deck
                    // restarts the game, dropping the replayed answers.
                    val order = boardState?.turnOrder.orEmpty()
                    if (order.size > 1) add(tool("⇄ Seat $viewer") { view.viewer = order[(order.indexOf(viewer) + 1) % order.size] })
                    if (doc.decks.size > 1) {
                        add(tool("P0: ${doc.decks.getOrNull(p0Deck)?.name ?: "--"}") { onSession(session.cycleP0(doc.decks.size)) })
                        add(tool("P1: ${doc.decks.getOrNull(p1Deck)?.name ?: "--"}") { onSession(session.cycleP1(doc.decks.size)) })
                    }
                }
                val viewTools = listOf(
                    tool(if (view.zones) "▦ Board" else "▦ Zones", view.zones) { view.zones = !view.zones; view.menu = false },
                    tool("≡ Log", view.log) { view.log = !view.log; view.menu = false },
                    // A toggle, so motion can be compared on the same board.
                    tool(if (view.juice.enabled) "Motion on" else "Motion off", view.juice.enabled) {
                        view.juice = view.juice.copy(enabled = !view.juice.enabled)
                    },
                    tool("Tune motion", view.tuner) { view.tuner = !view.tuner; view.menu = false },
                )
                // Concede only while this seat holds priority: the only
                // moment the engine is listening.
                val gameTools = buildList {
                    (prompt as? Question.Priority)?.takeIf { it.player == viewer }?.let {
                        add(tool("Concede") { view.menu = false; input.answer(PriorityAction.Concede) })
                    }
                    add(tool("Leave the table") { view.menu = false; onExit() })
                }
                if (debugTools.isNotEmpty()) ToolGrid("DEBUG", debugTools)
                ToolGrid("VIEW", viewTools)
                ToolGrid("GAME", gameTools)
            }
        }
        if (view.sandbox && vp.godMode) {
            TableDrawer(railed = railed, onDismiss = { view.sandbox = false }) {
                Binder(doc, shown, viewer, view.queued.size) { e -> edit(e) }
            }
        }
        // In the Debug lens a lifted card can be moved to any zone:
        // the card's owner keeps it; one out of exile goes to the near seat.
        val onMove: ((FocusCard, EditZone) -> Unit)? = if (!vp.godMode) null else { fc, z ->
            fc.id?.let { id -> edit(TableEdit.Move(id, z, owner = if (fc.from == EditZone.EXILE) viewer else null)) }
            focus = null
        }
        focus?.let { CardFocusOverlay(it, artOf, store, doc.id, onMove = onMove) { focus = null } }
        naming?.let { name ->
            AlertDialog(
                onDismissRequest = { naming = null },
                title = { Text("Save this table as a scenario") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("The position as it stands, every move and edit so far. Play › Sandbox reopens it or exports it as a test case.", fontSize = 12.sp)
                        CgField("name", name) { naming = it }
                    }
                },
                confirmButton = {
                    TextButton(enabled = name.isNotBlank(), onClick = { onScenario?.invoke(name.trim()); naming = null }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { naming = null }) { Text("Cancel") } },
            )
        }
    }
}

/** The sandbox binder: any card of the game, into any seat's hand,
 *  play area, graveyard or library top; a draw; a seat's life. Every pick is
 *  a recorded edit, so Undo takes it back. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Binder(doc: GameDoc, state: GameState?, viewer: PlayerId, waiting: Int, onEdit: (TableEdit) -> Unit) {
    val seats = state?.turnOrder.orEmpty().ifEmpty { listOf("P0", "P1") }
    var owner by remember { mutableStateOf(viewer) }
    var zone by remember { mutableStateOf(EditZone.HAND) }
    var filter by remember { mutableStateOf("") }
    // What the last pick did, so a tap on a chip is seen to land.
    var landed by remember { mutableStateOf<String?>(null) }
    Text("SANDBOX", color = Cg.dim, fontFamily = Cg.mono, fontSize = 9.sp, letterSpacing = 1.2.sp)
    if (waiting > 0) {
        Text("$waiting edit(s) wait for the next priority question", color = Cg.warn, fontFamily = Cg.mono, fontSize = 10.sp)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (p in seats) Chip(p, owner == p) { owner = p }
        Text("·", color = Cg.dim)
        for (z in EditZone.entries) Chip(z.name.lowercase().replace('_', ' '), zone == z) { zone = z }
    }
    val life = state?.players?.get(owner)?.counters?.get(LIFE)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip("$owner draws 1", false) { onEdit(TableEdit.Draw(owner, 1)) }
        if (life != null) {
            Chip("life −1", false) { onEdit(TableEdit.SetCounter(LIFE, life - 1, player = owner)) }
            Chip("life +1", false) { onEdit(TableEdit.SetCounter(LIFE, life + 1, player = owner)) }
        }
    }
    CgField("find a card", filter) { filter = it }
    val cards = doc.cards.filter { filter.isBlank() || it.faces[0].name.contains(filter, ignoreCase = true) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (c in cards) Chip(c.faces[0].name, false) {
            onEdit(TableEdit.Conjure(c.key(), owner, zone))
            landed = "→ ${c.faces[0].name} to $owner's ${zone.label()}"
        }
    }
    landed?.let { msg ->
        key(msg) {
            val fade = remember { Animatable(1f) }
            LaunchedEffect(Unit) { fade.animateTo(0f, tween(durationMillis = 1600, delayMillis = 900)); landed = null }
            Text(msg, color = Cg.wire, fontFamily = Cg.mono, fontSize = 10.sp, modifier = Modifier.graphicsLayer { alpha = fade.value })
        }
    }
}

/** The Play / Debug lens switch in the table's top band. */
@Composable
private fun LensToggle(mode: PlayMode, onLens: (PlayMode) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Cg.raised).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for ((m, label) in listOf(PlayMode.PLAYER to "Play", PlayMode.PLAYTEST to "Debug")) {
            val on = m == mode
            Text(
                label,
                color = if (on) Cg.bg else Cg.muted, fontFamily = Cg.mono, fontSize = 10.sp,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.clip(RoundedCornerShape(50))
                    .background(if (on) Cg.wire else Color.Transparent)
                    .clickable(enabled = !on) { onLens(m) }
                    .padding(horizontal = 9.dp, vertical = 4.dp),
            )
        }
    }
}

private class ToolSpec(val label: String, val on: Boolean, val action: () -> Unit)

/** The table's tools: a sheet over the board with a scrim, from the
 *  bottom in portrait and from the right edge when railed, so the board
 *  stays in view beside it. Tapping the scrim or Back closes it. */
@Composable
private fun BoxScope.TableDrawer(railed: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    // Transient view state, like the card focus: Back closes it before it
    // reaches the shell (ArchReviewTest allows this file two BackHandlers).
    BackHandler { onDismiss() }
    // The scrim fades in and the sheet slides in from its edge.
    val reduced = rememberReducedMotion()
    val shown = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 520f)) }
    Box(
        Modifier.matchParentSize().graphicsLayer { alpha = shown.value }.background(Color.Black.copy(alpha = 0.5f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() },
    )
    val shape = if (railed) RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp) else RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
    Column(
        (if (railed) Modifier.align(Alignment.CenterEnd).fillMaxHeight().widthIn(max = 320.dp).fillMaxWidth(0.45f)
        else Modifier.align(Alignment.BottomCenter).fillMaxWidth())
            .graphicsLayer {
                val away = 1f - shown.value
                if (railed) translationX = away * size.width else translationY = away * size.height
            }
            .clip(shape).background(Cg.surface).border(1.dp, Cg.border, shape)
            // Swallow taps, so one on the sheet never reaches the scrim.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!railed) Box(Modifier.align(Alignment.CenterHorizontally).size(width = 32.dp, height = 3.dp).clip(RoundedCornerShape(2.dp)).background(Cg.border))
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolGrid(title: String, tools: List<ToolSpec>) {
    if (tools.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = Cg.dim, fontFamily = Cg.mono, fontSize = 9.sp, letterSpacing = 1.2.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (t in tools) {
                Text(
                    t.label,
                    color = if (t.on) Cg.bg else Cg.ink, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .background(if (t.on) Cg.wire else Cg.raised)
                        .clickable(onClick = t.action)
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                )
            }
        }
    }
}

/** The arcs: one curve from the acting card to each card it could reach, drawn
 *  over the board. WHICH cards is `ccgui.arcsFor`; this knows only where they
 *  landed. Once one target is armed the others fade rather than vanish. */
@Composable
private fun BoxScope.ArcOverlay(
    arcs: ArcSpec,
    bounds: Map<ObjectId, Rect>,
    /** Lane rectangles, for an arc whose target is an empty space rather than
     *  a card -- see `ccgui.ArcEnd.Zone`. */
    zoneBounds: Map<String, Rect>,
    armed: ObjectId?,
    reducedMotion: Boolean,
) {
    // No origin to subtract: `bounds` is in this Box's own coordinates. The
    // dashes crawl so an arc reads as a direction. The transition is created
    // UNCONDITIONALLY (a `remember` inside an `if` changes call-site identity);
    // reduced motion just ignores it.
    val crawl by rememberInfiniteTransition(label = "arc").animateFloat(
        initialValue = 0f,
        targetValue = 28f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "arcPhase",
    )
    val phase = if (reducedMotion) 0f else crawl
    Canvas(Modifier.matchParentSize()) {
        // Where an arc end actually is. Null for an end whose rectangle has not
        // been reported yet -- skipped rather than drawn at the origin, because
        // on the first frame after a new state the maps are empty and an arc to
        // the corner is a lie about where a card is.
        fun at(end: ArcEnd): Offset? = when (end) {
            is ArcEnd.Obj -> bounds[end.id]?.center
            is ArcEnd.Zone -> zoneBounds[end.zone]?.center
            // The hand is DOCKED outside the board's scroll, so it has no
            // rectangle in this space and never will. Drawn from the bottom
            // edge instead: an implied source, in the direction the card is
            // genuinely coming from.
            ArcEnd.Hand -> Offset(size.width / 2f, size.height)
        }
        val from = at(arcs.source) ?: return@Canvas
        for (end in arcs.targets) {
            val to = at(end) ?: continue
            val chosen = end is ArcEnd.Obj && armed == end.id
            val faded = armed != null && !chosen
            val colour = if (chosen) Cg.accent else Cg.wire
            val alpha = if (faded) 0.22f else 0.9f
            // Bowed perpendicular to the run, so several arcs leaving the same
            // card stay distinguishable instead of overlapping into one smear.
            val dx = to.x - from.x
            val dy = to.y - from.y
            val control = Offset(
                x = (from.x + to.x) / 2f - dy * 0.16f,
                y = (from.y + to.y) / 2f + dx * 0.16f,
            )
            val path = Path().apply {
                moveTo(from.x, from.y)
                quadraticTo(control.x, control.y, to.x, to.y)
            }
            drawPath(
                path = path,
                color = colour,
                alpha = alpha,
                style = Stroke(
                    width = if (chosen) 3.dp.toPx() else 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), phase),
                ),
            )
            // A head at the target end -- an arc without one reads as a link
            // rather than an intent.
            drawCircle(
                color = colour, alpha = alpha,
                radius = (if (chosen) 4.dp else 3.dp).toPx(),
                center = to,
            )
        }
        // And a root at the source, so it is obvious which card is acting.
        drawCircle(color = Cg.accent, alpha = 0.9f, radius = 3.5.dp.toPx(), center = from)
    }
}

/** A slow breath, for whatever the beat is pointing at. Scale rather than
 *  alpha: a card that fades looks like it is leaving. */
@Composable
private fun Modifier.beatPulse(): Modifier {
    val t = rememberInfiniteTransition(label = "beat")
    val sc by t.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(tween(620), RepeatMode.Reverse),
        label = "beatScale",
    )
    return this.graphicsLayer { scaleX = sc; scaleY = sc }
}

/** A beat: what just happened, over a board that already shows it. Not a
 *  dialog -- a light scrim, the panel at the top, the cards it is about ringed
 *  underneath. It swallows taps for its duration, so a fast opponent cannot
 *  change the board between two of your taps. Under hotseat it is also the
 *  device-handoff screen. */
@Composable
private fun BeatOverlay(beat: Beat) {
    val anim = remember(beat) { Animatable(0f) }
    LaunchedEffect(beat) { anim.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 400f)) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Cg.bg.copy(alpha = 0.30f * anim.value))
            // Consume every tap without acting on one.
            .pointerInput(beat) { detectTapGestures {} },
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .padding(top = 40.dp, start = 14.dp, end = 14.dp)
                .graphicsLayer {
                    alpha = anim.value
                    translationY = (1f - anim.value) * -16f
                }
                .shadow(14.dp, RoundedCornerShape(10.dp), clip = false)
                .clip(RoundedCornerShape(10.dp))
                .background(Cg.surface)
                .border(1.dp, Cg.accentLight, RoundedCornerShape(10.dp))
                // An opening runs to a dozen lines on a phone: it scrolls rather
                // than growing off the top, the same fix the card focus needed.
                .heightIn(max = 300.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (beat.opening) {
                Text(
                    "THE OPENING", color = Cg.accentLight, fontFamily = Cg.mono,
                    fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                )
            }
            // The CAUSE first -- who chose this -- then what it did.
            beat.headlines.forEach { h ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("\u25b8", color = Cg.accentLight, fontFamily = Cg.mono, fontSize = 13.sp)
                    Text(
                        h, color = Cg.ink, fontFamily = Cg.mono,
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            beat.changes.forEach { c ->
                Text(
                    "\u00b7 ${c.text}",
                    color = if (c.kind == ChangeKind.LOST) Cg.warn else Cg.ink2,
                    fontFamily = Cg.mono, fontSize = 11.sp, lineHeight = 15.sp,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// The board: seat edges, the lane grid of compact CgCardTiles, the stack as a
// badge. Legal hand cards ring green while a seat holds priority and play by
// tap; a held tap lifts any tile into a focus view.
// ---------------------------------------------------------------------------

private val STACK_LAYOUT = ccg.CardLayout(
    art = ccg.ArtSlot.NONE, statCorner = ccg.StatCorner.NONE, showText = false, accent = "#E8B44A",
)

/** A card lifted into the focus overlay. */
private data class FocusCard(
    val name: String,
    val typeLabel: String,
    val cost: String?,
    val pt: String?,
    val text: String,
    val layout: ccg.CardLayout?,
    val counters: Map<String, Int>,
    /** The COMPILED rulebox -- what this card actually does, in play, where
     *  knowing it matters most. */
    val rulebox: List<String> = emptyList(),
    /** `CardDoc.key()` -- which authored card this is. Carried instead of
     *  looking the rulebox up by name, which two cards may share. */
    val key: String = "",
    /** The instance, and the zone it is in, for the Debug lens's move.
     *  Null where it cannot be moved: a card in a prompt, in a
     *  declared zone. */
    val id: ObjectId? = null,
    val from: EditZone? = null,
)

/** The rulebox for a card, from the DOC (the built card drops what only the
 *  Creator reads). */
private fun ruleboxFor(key: String, cards: List<Pair<CardDoc, CardDefinition>>, rules: Rules): List<String> =
    cards.firstOrNull { it.first.key() == key }
        ?.let { runCatching { ruleboxOf(it.first, rules) }.getOrElse { emptyList() } }
        ?: emptyList()

private fun focusForBuilt(
    rules: Rules,
    built: CardDefinition?,
    types: Set<String>,
    cards: List<Pair<CardDoc, CardDefinition>> = emptyList(),
    id: ObjectId? = null,
    from: EditZone? = null,
): FocusCard =
    FocusCard(
        id = id,
        from = from,
        name = built?.name ?: "card",
        typeLabel = types.firstOrNull() ?: "",
        cost = built?.let { costSummary(it.cost) },
        // A card in hand shows its PRINTED stats, read through the type's
        // declared `statFields` exactly as `focusForPerm` does (a permanent
        // shows its current, buffed ones).
        pt = built?.faces?.firstOrNull()?.baseChars?.fields?.let { f ->
            val layout = rules.layoutFor(types)
            layout.statFields.mapNotNull { f[it] }.takeIf { it.isNotEmpty() }?.joinToString("/")
                ?: f["power"]?.let { "$it/${f["toughness"] ?: 0}" }
        },
        text = built?.text ?: "",
        layout = rules.layoutFor(types),
        counters = emptyMap(),
        key = built?.key.orEmpty(),
        rulebox = built?.key?.let { ruleboxFor(it, cards, rules) }.orEmpty(),
    )

private fun focusForPerm(
    s: GameState,
    rules: Rules,
    perm: ccg.Permanent,
    cards: List<Pair<CardDoc, CardDefinition>> = emptyList(),
): FocusCard {
    val c = s.characteristicsOf(perm.id)
    val layout = rules.layoutFor(c.types)
    val pt = layout.statFields.mapNotNull { c.fields[it] }.takeIf { it.isNotEmpty() }?.joinToString("/")
        ?: c.fields["power"]?.let { "$it/${c.fields["toughness"] ?: 0}" }
    return FocusCard(
        name = c.name.ifEmpty { "#${perm.id}" },
        typeLabel = c.types.firstOrNull() ?: "",
        cost = null,
        pt = pt,
        text = rules.cardOf(perm)?.text ?: "",
        layout = layout,
        counters = perm.counters.filterValues { it > 0 },
        // The PERMANENT's own card, not a name lookup: a token has no card at
        // all and correctly gets no rulebox, where matching on the derived
        // name would have found whatever card happened to share it.
        key = rules.cardOf(perm)?.key.orEmpty(),
        rulebox = rules.cardOf(perm)?.key?.let { ruleboxFor(it, cards, rules) }.orEmpty(),
        id = perm.id,
        from = EditZone.BATTLEFIELD,
    )
}

/** The play/cast action for a card in ANY castable zone -- hand, or a
 *  declared custom zone (a Flagship pool). */
private fun cardPlayAction(rules: Rules, built: CardDefinition, ref: CardRef, zone: CastZone): PriorityAction =
    // `ccg.playActionFor` is the one constructor.
    playActionFor(rules, built, CardSource(zone, ref.instanceId))
private fun handPlayAction(rules: Rules, built: CardDefinition, ref: CardRef) =
    cardPlayAction(rules, built, ref, CastZone.Std(HiddenZone.HAND))

/** The viewer's own hand, on the bottom edge outside the board's scroll, so
 *  looking at the board never takes your cards off screen. Reuses
 *  `CastableCardLane` and shares `armed` with the board, so arming here and
 *  confirming there is one gesture, not a second way to play a card. */
@Composable
private fun HandDock(
    s: GameState,
    rules: Rules,
    me: ccg.Player,
    hasPriority: Boolean,
    armed: MutableState<ObjectId?>,
    laneChoice: MutableState<PriorityAction.PlayPermanent?>,
    input: HotseatInput,
    /** Whether each pool is currently unfolded. Ahead of `onFocus` because that
     *  one is a trailing lambda at the call site. */
    poolOpen: (String) -> Boolean,
    /** Folds/unfolds a pool; the chip lives in the dock, not a scrolling list. */
    onTogglePool: (String) -> Unit,
    /** Card art for the hand. Ahead of `onFocus`, which is a trailing lambda
     *  at the call site. */
    artOf: Map<String, ccgui.CardArtRef> = emptyMap(),
    artStore: GameStore? = null,
    artGameId: String = "",
    /** Null = the dense row. Before `onFocus`, a trailing lambda. */
    handH: Dp? = null,
    onFocus: (FocusCard) -> Unit,
) {
    val tileH = handH?.let { (it - 8.dp).coerceIn(64.dp, 132.dp) }
    Column(Modifier.fillMaxWidth().background(Cg.surface).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Cg.border))
        //
        // An open pool replaces the hand; its header comes out of its tiles' height so the dock never resizes.
        val poolIds = rules.hiddenZones.values.filter { it.alwaysVisible }.map { it.id }
        val shownPool = ccgui.dockLane(poolIds, poolOpen) { me.customZones[it].orEmpty().size }
        if (shownPool == null) {
            CastableCardLane(
                s, rules, me.id, me.hand, CastZone.Std(HiddenZone.HAND),
                "your hand (${me.hand.size})",
                hasPriority, armed, laneChoice, input,
                artOf = artOf, artStore = artStore, artGameId = artGameId,
                showTitle = false, tileHeight = tileH, overlayConfirm = true,
                onFocus = onFocus,
            )
        } else {
            val cards = me.customZones[shownPool].orEmpty()
            Row(
                Modifier.fillMaxWidth().height(POOL_HEADER_H).padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("$shownPool (${cards.size})", color = Cg.accentLight, fontFamily = Cg.mono, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
            CastableCardLane(
                s, rules, me.id, cards, CastZone.Declared(shownPool),
                "$shownPool (${cards.size})", hasPriority, armed, laneChoice, input,
                artOf = artOf, artStore = artStore, artGameId = artGameId,
                showTitle = false,
                tileHeight = tileH?.let { (it - POOL_HEADER_H).coerceAtLeast(56.dp) },
                overlayConfirm = true,
                onFocus = onFocus,
            )
        }
    }
}

/** The dock's pool header: its tiles are this much shorter, so opening the
 *  pool never changes the dock's height. */
private val POOL_HEADER_H = 20.dp

/** A face-up, tap-to-play row of cards from ANY castable zone -- the hand, or a
 *  declared custom zone (a Flagship pool). Legal cards ring green; a tap arms, a
 *  second tap or the confirm pill plays it, or opens the lane question when the
 *  type offers more than one zone. Renders nothing when `refs` is empty. */
@Composable
private fun CastableCardLane(
    s: GameState,
    rules: Rules,
    owner: PlayerId,
    refs: List<CardRef>,
    zone: CastZone,
    title: String,
    hasPriority: Boolean,
    armed: MutableState<ObjectId?>,
    laneChoice: MutableState<PriorityAction.PlayPermanent?>,
    input: HotseatInput,
    /** Ahead of `onFocus`, which is a trailing lambda at every call. */
    artOf: Map<String, ccgui.CardArtRef> = emptyMap(),
    artStore: GameStore? = null,
    artGameId: String = "",
    /** The docked hand: no title, fixed tile height, confirm pill overlaid so arming never resizes the dock. */
    showTitle: Boolean = true,
    tileHeight: Dp? = null,
    overlayConfirm: Boolean = false,
    onFocus: (FocusCard) -> Unit,
) {
    if (refs.isEmpty()) return
    Spacer(Modifier.height(if (showTitle) 4.dp else 2.dp))
    if (showTitle) Text(title, color = Cg.dim, fontFamily = Cg.mono, fontSize = 9.sp)
    LaneRow {
        refs.forEach { ref ->
            val built = rules.cards[ref.cardId]
            val types = built?.types ?: emptySet()
            val action = if (hasPriority && built != null) cardPlayAction(rules, built, ref, zone) else null
            val legal = action != null && legality(rules, s, owner, action) is Legality.Legal
            val isArmed = legal && armed.value == ref.instanceId
            val pickingLane = laneChoice.value?.from?.instanceId == ref.instanceId
            // ONE policy for what a tap/confirm does, shared with the confirm
            // pill below and with the prompt bar's own "Play X" chip -- see
            // ccgui.PlayIntent.
            fun act(intent: PlayIntent) {
                when (intent) {
                    is PlayIntent.Ignore -> {}
                    is PlayIntent.Arm -> armed.value = ref.instanceId
                    is PlayIntent.Cancel -> { laneChoice.value = null; armed.value = null }
                    is PlayIntent.Commit -> { laneChoice.value = null; armed.value = null; input.answer(intent.action) }
                    is PlayIntent.ChooseZone -> laneChoice.value = intent.action
                }
            }
            @Composable
            fun Tile() {
                val artRef = built?.key?.let { k -> artOf[k] }
                CgCardTile(
                    art = artRef?.let { e -> artStore?.let { st -> rememberCardArt(st, artGameId, e.file) } },
                    // a hand tile is a thumbnail, so it takes the
                    // thumbnail framing -- which is the full one until the card
                    // is given its own.
                    artRect = artRef?.rect(compact = true),
                    name = built?.name ?: ref.cardId,
                    typeLabel = types.firstOrNull() ?: "",
                    // DENSE: the docked lanes are a single row at the bottom
                    // edge, so their height is the scarcest thing on the
                    // screen -- every dp here is a dp the BOARD does not get.
                    costLabel = null, pt = null, text = "", compact = true,
                    dense = tileHeight == null || tileHeight < 100.dp,
                    height = tileHeight,
                    layout = rules.layoutFor(types),
                    dim = !hasPriority,
                    ring = when {
                        isArmed || pickingLane -> CgTileRing.SELECTED
                        legal -> CgTileRing.PLAYABLE
                        else -> CgTileRing.NONE
                    },
                    lifted = isArmed || pickingLane,
                    onClick = if (!legal) null else ({
                        act(tapIntent(rules, s, owner, action, armed = isArmed, choosingZone = pickingLane))
                    }),
                    onLongPress = { onFocus(focusForBuilt(rules, built, types, id = ref.instanceId, from = editZoneOf(zone))) },
                )
            }
            @Composable
            fun Pills() {
                // The confirm pill asks the SAME zone question the tile does.
                if (isArmed && action != null && !pickingLane) {
                    ConfirmPill("▶ Play?", Cg.go) { act(commitIntent(rules, s, owner, action)) }
                }
                if (pickingLane) {
                    Text("pick a zone ▾", color = Cg.accent, fontFamily = Cg.mono, fontSize = 9.sp)
                }
            }
            if (overlayConfirm) {
                Box(contentAlignment = Alignment.BottomCenter) {
                    Tile()
                    Column(Modifier.padding(bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) { Pills() }
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Tile()
                    Pills()
                }
            }
        }
    }
}

/** A small confirming pill under an armed tile -- tap it (or the tile again)
 *  to submit. Shared by the hand's "Play?" and the board's "Target?". */
@Composable
private fun ConfirmPill(label: String, accent: Color, onConfirm: () -> Unit) {
    Text(
        label,
        color = accent, fontFamily = Cg.mono, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(Cg.accentWash)
            .border(1.dp, accent, RoundedCornerShape(7.dp))
            .clickable(onClick = onConfirm)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** The in-progress answer to a multi-step board prompt -- declare attackers (a
 *  growing set) or assign blockers (a growing map). Shared by the board (which
 *  builds it by tapping) and the PromptBar (which submits it); a new prompt gets
 *  a fresh one. */
private class BoardAnswer {
    var attackSel by mutableStateOf<Set<ObjectId>>(emptySet())

    /** The blocker currently waiting for a tap on the attacker it covers. */
    var activeBlocker by mutableStateOf<ObjectId?>(null)
    var blockAssign by mutableStateOf<Map<ObjectId, ObjectId>>(emptyMap())
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoardView(
    s: GameState,
    rules: Rules,
    prompt: Question?,
    /** WHO is looking, and what they may see. Carries the fixed near-side seat
     *  (so the board never flips under the player) AND the visibility policy,
     *  which is deliberately not a mode flag decided here -- see
     *  `ccgui.Viewpoint`. */
    vp: Viewpoint,
    /** Board objects the live BEAT is talking about. Lit while it shows, so
     *  the sentence and the thing it is about are on screen together. Empty
     *  whenever no beat is up, which is most of the time. */
    beatSubjects: Set<ObjectId>,
    /** RAILED: the anchors sit BESIDE their own lane row rather than above or
     *  below it, which is what stops the board scrolling in landscape. Passed
     *  in rather than re-derived from `LocalConfiguration` here -- one answer
     *  to "which shape", from `ccgui.playSurfaceFor`, where it is tested. */
    railed: Boolean,
    input: HotseatInput,
    answer: BoardAnswer,
    /** A play waiting on a zone choice. Lifted to `HotseatBody` so the board
     *  AND the prompt bar (which owns the picker itself, where every other
     *  choice in this UI is made) share one instance. */
    laneChoice: MutableState<PriorityAction.PlayPermanent?>,
    /** A hand card or board permanent tapped once and waiting for a confirming
     *  second tap. Lifted to `HotseatBody` for the same reason `laneChoice`
     *  was: in portrait the viewer's hand is DOCKED outside this scroll, so
     *  the board and the dock have to arm the same thing. */
    armed: MutableState<ObjectId?>,
    /** This seat's hand is drawn by the dock, not inline here. Null in the
     *  Playtest tab, which shows every hand where it always did. */
    pinnedHandFor: PlayerId?,
    /** How tall a rank of berths may be, measured ONCE by `BoardArea` (null = it
     *  could not fit, so the area scrolls). One measurement decides both, so the
     *  board never sizes itself to fit and is then clipped. */
    rankH: Dp?,
    onOpenZone: (ZoneFocus) -> Unit,
    /** Folds and unfolds the docked seat's pool lane (the chip and the lane are
     *  in different subtrees). Declared BEFORE `onFocus`, which is passed as a
     *  trailing lambda, so "add parameters last" does not apply here. */
    onTogglePool: (String) -> Unit,
    /** Motion settings. Threaded rather than read from a CompositionLocal: a
     *  local read inside a click handler is not a composable call site. */
    juice: JuiceConfig,
    /** Card key -> art filename, plus what resolves it to a file.
     *  Declared BEFORE `onFocus`, which is a trailing lambda. */
    artOf: Map<String, ccgui.CardArtRef>,
    artStore: GameStore?,
    artGameId: String,
    poolOpen: (String) -> Boolean,
    /** Before `onFocus`, a trailing lambda. */
    onStackHeight: (Int) -> Unit = {},
    onFocus: (FocusCard) -> Unit,
) {
    val priorityOf = (prompt as? Question.Priority)?.player
    val density = LocalDensity.current
    val tapIds = remember(prompt, s, rules) { boardTapTargets(rules, prompt, s) }

    // which battlefield permanents / stack items are new since the
    // last state -- these get an entrance slide, everything already on the
    // board when this session (re)opened does not. `null` baseline means "not
    // seen a state yet", so the very first render animates nothing.
    val reducedMotion = rememberReducedMotion()
    val seenPermIds = remember { mutableStateOf<Set<ObjectId>?>(null) }
    val newPermIds = seenPermIds.value?.let { s.battlefield.keys - it } ?: emptySet()
    val seenStackIds = remember { mutableStateOf<Set<ObjectId>?>(null) }
    val newStackIds = seenStackIds.value?.let { seen -> s.stack.map { it.id }.toSet() - seen } ?: emptySet()
    LaunchedEffect(s) {
        seenPermIds.value = s.battlefield.keys
        seenStackIds.value = s.stack.map { it.id }.toSet()
    }

    // What the board should REACT to, from the same `recap` diff the beat
    // speaks. `fxSeq` is the animation key, not the fx values: two consecutive
    // identical hits must both play.
    val prevState = remember { mutableStateOf<GameState?>(null) }
    val fxSeq = remember { mutableStateOf(0) }
    val fx = remember(s) { prevState.value?.let { boardFx(recap(it, s)) } ?: BoardFx.NONE }
    // From the engine's hit record, so an attacker is never inferred.
    val hits = remember(s) { ccgui.newCombatHits(prevState.value, s) }
    val stationOf = remember(s, rules, vp.viewer) {
        boardLayout(rules, s, vp.viewer).rows
            .mapNotNull { r -> ccgui.badgeAnchor(rules, s, r.anchors)?.let { r.owner to it.id } }
            .toMap()
    }
    LaunchedEffect(s) {
        prevState.value = s
        fxSeq.value += 1
    }

    // Arcs: WHICH cards is `ccgui.arcsFor` (tested, built on the taps' own
    // `boardTapTargets`); what is left here is where each tile landed. A
    // pending zone choice takes precedence -- that IS the question on screen.
    val arcs = remember(prompt, s, rules, armed.value, laneChoice.value) {
        arcsFor(rules, prompt, s, armed.value, laneChoice.value)
    }
    // Which lanes a tap may answer with. Taken from the arcs rather than
    // recomputed, so the lit slot, the arc and the commit are one answer.
    val litZones = arcs?.targets.orEmpty().filterIsInstance<ArcEnd.Zone>().map { it.zone }.toSet()

    // What each of the asked seat's permanents could do. Once per state, not
    // once per tile -- see `ccgui.activationsBy`. Empty unless a Priority
    // prompt is live: acting for a seat that was not asked is not a move, it
    // is a bug.
    val moves = remember(prompt, s, rules) {
        val who = (prompt as? Question.Priority)?.player
        if (who == null) emptyMap() else activationsBy(rules, s, who)
    }
    // Which permanent's ability menu is showing. Reset whenever the prompt or
    // the state moves on, so a menu can never outlive the question it answers.
    var abilityMenu by remember(prompt, s) { mutableStateOf<ObjectId?>(null) }
    // Folded by default. NOT keyed on the state: a player who opened the stack
    // to read it should not have it snap shut the moment the next trigger goes
    // on, which is exactly when they are reading it.
    var stackOpen by remember { mutableStateOf(false) }
    // Tile rectangles in the OVERLAY BOX's own coordinate space, never the
    // window's: window-space snapshots of a scrolling subtree taken in
    // different passes disagree by the scroll offset. Box-relative rects move
    // with the Box, and `localBoundingBoxOf` uses live transforms.
    val bounds = remember(prompt, s) { mutableStateMapOf<ObjectId, Rect>() }
    // Not keyed on state: lanes don't move, and a reset would blink the background.
    val laneRects = remember(rules) { mutableStateMapOf<Pair<PlayerId, String>, Rect>() }
    val links = remember(rules) { ccgui.coverLinks(rules) }
    val zoneBounds = remember(prompt, s) { mutableStateMapOf<String, Rect>() }
    var boxCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    // `onPlaced`, not `onGloballyPositioned`: it fires before the children are
    // placed, so a tile's own callback in the same pass sees a non-null Box
    // (otherwise a tile that never moves again never reports, and arcs vanish).
    Box(Modifier.onPlaced { boxCoords = it }) {
    if (!railed) CoverLinks(laneRects, links, s.turnOrder)
    // The board's frame is chrome. Railed it is ~40dp of padding and spacing
    // around a board with 10dp of margin, so it comes off -- the card border
    // is decoration, the lanes are the game.
    CgCard(
        inset = if (railed) 3.dp else 2.dp,
        gap = if (railed) 2.dp else 4.dp,
        border = if (railed) Cg.border else Color.Transparent,
        background = Color.Transparent,
    ) {
        // The turn line lives in the TOP BAND. The stack is a BADGE that
        // expands, not a row that appears -- a row shoved the board down while
        // you were reading it. Expanded, it is the full row of tiles.
        if (s.stack.isNotEmpty()) {
            Column(
                Modifier.onSizeChanged { onStackHeight((it.height / density.density).roundToInt()) },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Spacer(Modifier.height(4.dp))
                val fresh = s.stack.any { it.id in newStackIds }
                Text(
                    "◆ stack ${s.stack.size}${if (stackOpen) " ▾" else " ▸"}",
                    color = if (fresh) Cg.accentLight else Cg.dim,
                    fontFamily = Cg.mono,
                    fontSize = 10.sp,
                    fontWeight = if (fresh) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(Cg.surfaceAlt)
                        .border(1.dp, if (fresh) Cg.accentLight else Cg.border, RoundedCornerShape(7.dp))
                        .clickable { stackOpen = !stackOpen }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                if (stackOpen) {
                    Text("top resolves first", color = Cg.dim, fontFamily = Cg.mono, fontSize = 9.sp)
                    LaneRow {
                        s.stack.asReversed().forEach { so ->
                            val what = (so as? ccg.SpellOnStack)?.label?.ifBlank { "spell" } ?: "trigger"
                            // Drops in from above -- the mirror of a permanent's
                            // slide-up -- for a spell/trigger that just went on.
                            val dropIn = entranceMotion(play = so.id in newStackIds, reducedMotion = reducedMotion, fromY = (-18).dp)
                            CgCardTile(
                                name = what, typeLabel = "#${so.id} · ${so.controller}",
                                costLabel = null, pt = null, text = "", compact = true, layout = STACK_LAYOUT,
                                modifier = dropIn,
                            )
                        }
                    }
                }
            }
        }
        // Seats in VIEWER order -- opponent across the table, you nearest. A
        // fixed grid, one column per contested zone, the same index meaning the
        // same zone on both sides, labels drawn once between them; uncontested
        // permanents on the seat edge; attachments under their host. The
        // categories come from `ccgui.boardLayout`; this only draws them.
        val layout = remember(s, rules, vp.viewer) { boardLayout(rules, s, vp.viewer) }
        val structured = remember(rules) { hasStructuredBoard(rules) }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wiring = TileWiring(
                s, rules, prompt, input, onFocus, armed, tapIds, answer,
                newPermIds, reducedMotion, railed, beatSubjects,
                overlay = { boxCoords },
                litZones = litZones,
                moves = moves,
                menuFor = abilityMenu,
                onMenu = { abilityMenu = it },
                // THE commit, shared with the prompt bar's fallback picker.
                commitZone = { z ->
                    laneChoice.value?.let { pending ->
                        laneChoice.value = null
                        armed.value = null
                        input.answer(pending.copy(zone = z))
                    }
                },
                rankH = rankH,
                onZoneBounds = { z, r -> zoneBounds[z] = r },
                onLaneBounds = { owner, z, r -> laneRects[owner to z] = r },
                artOf = artOf,
                artStore = artStore,
                artGameId = artGameId,
                juice = juice,
                fx = fx,
                fxSeq = fxSeq.value,
        ) { id, r -> bounds[id] = r }
            // Null = no readable grid at this width (too many columns, or an
            // uncapped zone): fall back to the scrolling row with degraded arcs,
            // by design. RAILED, the grid divides what
            // the anchor block leaves. The block width derives from the anchor
            // count, taking the MAXIMUM over rows so lane columns stay aligned
            // across the table.
            val anchorBlockW = if (railed) {
                ccgui.anchorBlockWidthDp(
                    layout.rows.maxOfOrNull { it.anchors.size } ?: 0,
                    maxWidth.value.toInt(),
                ).dp
            } else ANCHOR_BLOCK_W
            val gridW = if (railed) (maxWidth - anchorBlockW).coerceAtLeast(120.dp) else maxWidth
            val tileW = if (structured) gridTileWidth(gridW, layout.columns.size) else null
            Column(Modifier.fillMaxWidth()) {
                layout.rows.forEach { row ->
                    val p = s.players[row.owner] ?: return@forEach
                    val isViewer = row.owner == vp.viewer
                    Spacer(Modifier.height(if (railed) 4.dp else SEAT_GAP))
                    // RAILED puts the anchors BESIDE their own lane row: side by
                    // side a seat costs max(anchor, lane tile) instead of the
                    // sum, which is what stops the board scrolling in landscape.
                    if (railed) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                        ) {
                            // clipToBounds: past the clamp the tiles are cut
                            // off rather than allowed to draw across the lane
                            // grid -- an overflowing Row does not clip itself,
                            // and it would draw over the lanes.
                            Box(Modifier.width(anchorBlockW).clipToBounds()) {
                                SeatEdge(
                                    wiring, p, row.anchors, onOpenZone,
                                    // Same rule as the stacked shape: only the
                                    // DOCKED seat's pool number folds a lane,
                                    // because no other seat has one on screen.
                                    onTogglePool = if (p.id == pinnedHandFor) onTogglePool else null,
                                    poolOpen = poolOpen,
                                    stacked = true,
                                    seatLabel = true,
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                // A header row of column names is meaningless
                                // once the zones are stacked -- each rank
                                // prints its own name in `SeatLanes`.
                                if (tileW != null && isViewer && ccgui.boardRanks(rules) == null) {
                                    GridLabels(layout.columns, tileW)
                                }
                                SeatLanes(wiring, row, layout, tileW, isViewer, litZones)
                            }
                        }
                        return@forEach
                    }
                    // Mirrored, the way a table is: the opponent's strip sits
                    // above their lanes, yours BELOW yours, so the half nearest
                    // your thumb is the half that is yours.
                    if (!isViewer) SeatEdge(wiring, p, row.anchors, onOpenZone, seatLabel = vp.godMode, chipsAbove = true)
                    // Shared labels, once, immediately above the viewer's lanes
                    // -- which puts them between the two sides for any number
                    // of seats, and keeps them adjacent to both grids instead
                    // of separated from one by a hand lane.
                    if (tileW != null && isViewer && ccgui.boardRanks(rules) == null) {
                        GridLabels(layout.columns, tileW)
                    }
                    SeatLanes(wiring, row, layout, tileW, isViewer, litZones)
                    if (isViewer) {
                        SeatEdge(
                            wiring, p, row.anchors, onOpenZone,
                            // Only the DOCKED seat's pool folds a lane; every
                            // other opens the sheet, because there is no lane
                            // of theirs on this screen to fold.
                            onTogglePool = if (p.id == pinnedHandFor) onTogglePool else null,
                            poolOpen = poolOpen,
                            seatLabel = vp.godMode,
                        )
                    }
                    // The hand is face-up only for a seat this viewpoint may
                    // read; the count stays visible either way (public in every
                    // card game).
                    val hasPriority = priorityOf == p.id
                    if (p.id == pinnedHandFor) {
                        // Drawn by the dock at the bottom edge instead -- see
                        // `HandDock`. Nothing here: one hand, one place on screen.
                        Unit
                    } else if (vp.handFaceUp(p.id)) {
                        CastableCardLane(
                            s, rules, p.id, p.hand, CastZone.Std(HiddenZone.HAND),
                            if (hasPriority) "   hand — tap a lit card, then confirm" else "   hand",
                            hasPriority, armed, laneChoice, input,
            artOf = artOf, artStore = artStore, artGameId = artGameId, onFocus = onFocus,
                        )
                    } else {
                        // A hidden hand is a COUNT in the seat's numbers line,
                        // not a band of face-down tiles.
                        Unit
                    }
                    // Declared "always visible" zones (a Flagship pool) are a
                    // PLAY LANE only for a seat this screen plays from -- the
                    // same condition as a face-up hand. Otherwise the pool is a
                    // count chip on the seat edge that opens the zone sheet. The
                    // docked seat's pool is drawn by the dock, never also here.
                    if (p.id == pinnedHandFor || !vp.handFaceUp(p.id)) return@forEach
                    rules.hiddenZones.values.filter { it.alwaysVisible }.forEach { hz ->
                        CastableCardLane(
                            s, rules, p.id, p.customZones[hz.id].orEmpty(), CastZone.Declared(hz.id),
                            if (hasPriority) "   ${hz.id} — tap a lit card, then confirm" else "   ${hz.id}",
                            hasPriority, armed, laneChoice, input,
            artOf = artOf, artStore = artStore, artGameId = artGameId, onFocus = onFocus,
                        )
                    }
                }
            }
        }
    }
        // Drawn OVER the board, so an arc crosses tiles rather than being
        // clipped by whichever lane it starts in. Bounds are collected in this
        // Box's own coordinate space, which is why the Box exists at all.
        if (arcs != null) {
            ArcOverlay(arcs, bounds, zoneBounds, armed.value, reducedMotion)
        }
        // Motion layers live in THIS Box because it is the one whose coordinate space the
        // tile rectangles are already reported in -- a numeral layer with its
        // own bounds mechanism would drift from the arcs the first time a tile
        // moved. All three draw over the board and take no input: a player who
        // wants to look at the position must never be waiting out a flourish.
        NumeralOverlay(fx, bounds, fxSeq.value, juice, reducedMotion)
        // the impact spray, same Box and the same `bounds` as the arcs
        // and the numerals, for the same reason.
        ImpactParticles(fx, bounds, fxSeq.value, juice, reducedMotion)
        AttackPulses(hits, bounds, stationOf, juice, reducedMotion)
        TurnSweep(fxSeq.value, fx.turnChanged, juice, reducedMotion)
        EndFlourish(fxSeq.value, fx.eliminated.isNotEmpty(), juice, reducedMotion)
        // The ambiguity menu: ONLY for a permanent with two or more legal
        // abilities. One ability is a pill on the tile; nothing at all is a
        // plain tile.
        //
        // Anchored to the bottom of the board rather than to the tile, because
        // a lane column is `available / N` wide and an ability reads as a
        // sentence -- "{1}, tap: target Ship gets +1 FD" does not fit in a
        // third of a phone.
        abilityMenu?.let { src ->
            val opts = moves[src].orEmpty()
            if (opts.size > 1) {
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Cg.surface.copy(alpha = 0.96f))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "${s.lbl(src)} — which ability?",
                        color = Cg.dim, fontFamily = Cg.mono, fontSize = 10.sp,
                    )
                    opts.forEach { m ->
                        ConfirmPill(m.label, Cg.go) {
                            abilityMenu = null
                            armed.value = null
                            input.answer(m.action)
                        }
                    }
                    Chip("cancel", false) { abilityMenu = null; armed.value = null }
                }
            }
        }
    }
}

/** Everything a board tile needs in order to answer a tap.
 *
 *  Bundled so the grid, the anchor strip and an attachment stacked under its
 *  host wire tiles the SAME way: threading a dozen parameters through three
 *  call sites is how one of them ends up wired differently.
 *
 *  `isNew` and `inBeat` are derived from the permanent rather than accepted,
 *  so two callers cannot pass inconsistent values. */
private class TileWiring(
    val s: GameState,
    val rules: Rules,
    val prompt: Question?,
    val input: HotseatInput,
    val onFocus: (FocusCard) -> Unit,
    val armed: MutableState<ObjectId?>,
    val tapIds: Set<ObjectId>,
    val answer: BoardAnswer,
    val newPermIds: Set<ObjectId>,
    val reducedMotion: Boolean,
    /** RAILED: board tiles drop their art box, which is 46dp per tile row and
     *  four rows are on screen at once. */
    val railed: Boolean,
    val beatSubjects: Set<ObjectId>,
    /** The arc overlay's Box. Tile rectangles are reported RELATIVE to it, so
     *  they survive a scroll -- see `bounds` in `BoardView`. A lambda rather
     *  than a value because the Box is positioned after its children and the
     *  wiring is built before either. */
    val overlay: () -> LayoutCoordinates?,
    /** Zones a pending play may legally enter, from `openZonesFor` via
     *  `arcsFor` -- so a lit slot can never be one a commit would refuse. */
    val litZones: Set<String>,
    /** What each permanent could DO right now, from `ccgui.activationsBy` --
     *  the engine's own enumeration, grouped. Empty except under a Priority
     *  prompt for the seat being asked. */
    val moves: Map<ObjectId, List<PermMove>>,
    /** Which permanent's ability menu is open, and how to open one. Only ever
     *  needed when a permanent has TWO OR MORE legal abilities: one is a plain
     *  confirm, which is the grammar the board already uses. */
    val menuFor: ObjectId?,
    val onMenu: (ObjectId?) -> Unit,
    /** Commit the pending play into a zone. The SAME handler the prompt bar's
     *  fallback picker uses, so one question has one answer path. */
    val commitZone: (String) -> Unit,
    /** how tall a rank of berths may be so the board FITS. Null means
     *  "not enough height even for a readable berth", and the board scrolls --
     *  which is the honest answer, because a board that fits and cannot be read
     *  is worse than one you scroll. See `ccgui.rankHeightFor`. */
    val rankH: Dp?,
    val onZoneBounds: ((String, Rect) -> Unit)?,
    /** Card art for the board. Keyed by `CardDefinition.key`, the identity a
     *  built card already carries, not by the first face's name. */
    val artOf: Map<String, ccgui.CardArtRef> = emptyMap(),
    val artStore: GameStore? = null,
    val artGameId: String = "",
    val juice: JuiceConfig = JuiceConfig.DEFAULT,
    val fx: BoardFx = BoardFx.NONE,
    val fxSeq: Int = 0,
    /** Every seat's lanes, keyed by (owner, zone) since zone names repeat across seats. Before `onBounds`, a trailing lambda. */
    val onLaneBounds: ((PlayerId, String, Rect) -> Unit)? = null,
    val onBounds: ((ObjectId, Rect) -> Unit)?,
) {
    fun isNew(id: ObjectId) = id in newPermIds
    fun inBeat(id: ObjectId) = id in beatSubjects

    /** Signed damage this transition marked on [id], or 0. */
    fun damageOf(id: ObjectId) = fx.damage[id] ?: 0

    /** Signed net counter movement on [id] this transition, or 0. */
    fun counterDeltaOf(id: ObjectId) = fx.tileCounters[id] ?: 0

    /** Report where a tile landed, in the overlay's space. THE one conversion,
     *  here rather than at each call site, because an arc drawn from a
     *  differently-converted rectangle is the bug this replaces. */
    fun report(id: ObjectId, coords: LayoutCoordinates) {
        val box = overlay() ?: return
        if (!box.isAttached || !coords.isAttached) return
        onBounds?.invoke(id, box.localBoundingBoxOf(coords))
    }

    fun reportLane(owner: PlayerId, zone: String, coords: LayoutCoordinates) {
        val box = overlay() ?: return
        if (!box.isAttached || !coords.isAttached) return
        onLaneBounds?.invoke(owner, zone, box.localBoundingBoxOf(coords))
    }

    /** Same conversion, for a lane column rather than a tile -- an arc to a
     *  LANE needs a rectangle for the empty space, which no permanent owns. */
    fun reportZone(zone: String, coords: LayoutCoordinates) {
        val box = overlay() ?: return
        if (!box.isAttached || !coords.isAttached) return
        onZoneBounds?.invoke(zone, box.localBoundingBoxOf(coords))
    }
}

/** The width the grid gives each column (`available / N`), or null below a
 *  readable floor: then the board falls back to the horizontal scroll, with
 *  degraded arcs. */
private fun gridTileWidth(available: Dp, columns: Int): Dp? {
    if (columns <= 0) return null
    val each = (available - GRID_GAP * (columns - 1) - GRID_EDGE * 2) / columns
    return if (each >= GRID_TILE_MIN) each else null
}

/** A rank divides the SAME width among ITS OWN lanes, so a 3-lane rank on a
 *  6-berth board gets tiles about twice as wide as the whole-board split would
 *  give it. Reconstructs the available width from that split rather than
 *  threading the raw measurement down two more composables. */
private fun rankTileWidth(whole: Dp, allColumns: Int, rankColumns: Int): Dp {
    if (rankColumns <= 0 || allColumns <= 0) return whole
    val available = whole * allColumns + GRID_GAP * (allColumns - 1)
    return (available - GRID_GAP * (rankColumns - 1)) / rankColumns
}

private val GRID_GAP = 6.dp

/** The gap above each seat's block in portrait. Counted in `ccgui.boardFixedDp`. */
private val SEAT_GAP = 6.dp

/** Counted by `ccgui.boardFixedDp`; keep in sync. */
private val LANE_PAD_V = 3.dp
private val RANK_GAP = 2.dp

private val GRID_EDGE = 4.dp
private val GRID_TILE_MIN = 52.dp

/** One permanent and whatever is attached to it, as one cell: the attachment
 *  sits DIRECTLY BENEATH its host ("this Ship is the tough one"). A `Column`,
 *  not an offset overlap -- nothing guesses or measures heights. An attachment
 *  is a full tile through `BattlefieldTile`, so it keeps the ring, tap,
 *  long-press and targeting of any permanent. */
@Composable
private fun BoardStack(
    w: TileWiring,
    host: ccg.Permanent,
    attached: List<ObjectId>,
    width: Dp?,
    fill: ccgui.LaneFill? = null,
    /** See `BattlefieldTile`'s own `flat` -- passed through so a caller
     *  outside the lane grid (namely `AnchorStrip`) can ask for it
     *  independent of `w.railed`. */
    flat: Boolean = w.railed,
    style: TileStyle = TileStyle.CARD,
) {
    // A host and its attachments SHARE one cell, so they divide ITS height
    // rather than each taking a full one -- otherwise bolting an Improvement to
    // a Ship would overflow the lane the moment it landed.
    val stack = fill?.let { ccgui.stackFill(it, 1 + attached.size) }
    if (style != TileStyle.CARD) {
        // The edge's height is fixed, so attachments go beside the anchor, not beneath.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            BattlefieldTile(w, host, null, style = style)
            attached.mapNotNull { w.s.battlefield[it] }.forEach { att ->
                BattlefieldTile(w, att, null, style = TileStyle.CHIP)
            }
        }
        return
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        BattlefieldTile(w, host, width, fill = stack, flat = flat)
        attached.mapNotNull { w.s.battlefield[it] }.forEach { att ->
            BattlefieldTile(w, att, width, fill = stack, flat = flat)
        }
    }
}

/** One seat's lanes: the aligned grid, or the scrolling fallback row. Shared by
 *  STACKED and RAILED so the lit-zone and zone-bounds policy has one copy. */
@Composable
private fun SeatLanes(
    w: TileWiring,
    row: ccgui.BoardRow,
    layout: ccgui.BoardLayout,
    tileW: Dp?,
    isViewer: Boolean,
    litZones: Set<String>,
) {
    val cells = row.grid.flatten()
    // Depth is drawn as depth: when the rules declare ranks (a grid, or a
    // screen) each rank is its own row, and the opponent's are mirrored so the
    // two FRONT lines meet at the centre of the table.
    val ranks = ccgui.boardRanks(w.rules)
        ?.let { order -> if (isViewer) order else order.asReversed() }
    when {
        tileW != null && ranks != null ->
            Column(verticalArrangement = Arrangement.spacedBy(RANK_GAP)) {
                ranks.forEach { rankIds ->
                    // The real column index travels WITH the column (`LaneGrid`
                    // reads `row.grid[ci]` by position; a subset without its
                    // indices would draw zone 0's cells in every rank).
                    val cols = rankIds.mapNotNull { id ->
                        val ci = layout.columns.indexOfFirst { it.zone == id }
                        if (ci < 0) null else ci to layout.columns[ci]
                    }
                    if (cols.isEmpty()) return@forEach
                    if (cols.size == 1) {
                        RankRow(w, row, cols[0].second, cols[0].first, interactive = isViewer)
                    } else {
                        // A rank of lanes divides the width among its own lanes.
                        // No label row: `EmptySlot` already prints the berth name.
                        LaneGrid(w, row, cols, rankTileWidth(tileW, layout.columns.size, cols.size), interactive = isViewer)
                    }
                }
            }
        tileW != null -> LaneGrid(w, row, layout.columns.indices.map { it to layout.columns[it] }, tileW, interactive = isViewer)
        cells.isEmpty() ->
            Text("   no permanents", color = Cg.dim, fontFamily = Cg.mono, fontSize = 10.sp)
        else -> LaneRow {
            cells.forEach { cell ->
                val perm = cell.occupant?.let { w.s.battlefield[it] }
                if (perm != null) {
                    BoardStack(w, perm, cell.attached, null)
                } else {
                    // Lit here too: the fallback row is what a narrow or
                    // many-laned board gets.
                    val lit = isViewer && cell.zone.def in litZones
                    Box(
                        // Zone bounds on the FALLBACK path too, or a narrow
                        // board lights its lanes and then draws no arc to them.
                        if (!isViewer) Modifier
                        else Modifier.onPlaced { w.reportZone(cell.zone.def, it) },
                    ) {
                        EmptySlot(
                            cell.zone.def,
                            height = if (w.railed) FLAT_SLOT_H else 88.dp,
                            lit = lit,
                            dimmed = !lit && litZones.isNotEmpty(),
                            onClick = if (lit) ({ w.commitZone(cell.zone.def) }) else null,
                        )
                    }
                }
            }
        }
    }
}

/** ONE rank, on ONE row: its cells spread ACROSS the width in equal shares at
 *  one fixed height, so the board does not grow as it fills and the two ranks
 *  read as two lines facing each other. */
@Composable
private fun RankRow(
    w: TileWiring,
    row: ccgui.BoardRow,
    col: ccgui.BoardColumn,
    colIndex: Int,
    interactive: Boolean,
) {
    val lit = interactive && col.zone in w.litZones
    val cells = row.grid.getOrNull(colIndex).orEmpty()
    // the measured height when the board is fitting itself.
    val laneH = w.rankH ?: if (w.railed) FLAT_SLOT_H else 78.dp
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = GRID_EDGE)
            .onPlaced {
                if (interactive) w.reportZone(col.zone, it)
                w.reportLane(row.owner, col.zone, it)
            },
    ) {
        Text(
            col.zone, color = Cg.dim, fontFamily = Cg.mono, fontSize = 9.sp,
            modifier = Modifier.padding(start = 2.dp),
        )
        // Equal shares, measured: `EmptySlot` takes a fixed `width` (no
        // modifier), so a `weight(1f)` wrapper would leave it at its default.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val n = cells.size.coerceAtLeast(1)
            val cellW = ((maxWidth - GRID_GAP * (n - 1)) / n).coerceAtLeast(24.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GRID_GAP)) {
                cells.forEach { cell ->
                    val perm = cell.occupant?.let { w.s.battlefield[it] }
                    if (perm != null) {
                        BoardStack(w, perm, cell.attached, cellW)
                    } else {
                        EmptySlot(
                            cell.zone.def,
                            cellW,
                            height = laneH,
                            lit = lit,
                            dimmed = !lit && w.litZones.isNotEmpty(),
                            onClick = if (lit) ({ w.commitZone(col.zone) }) else null,
                        )
                    }
                }
            }
        }
    }
}

/** The lanes as a FIXED grid: one column per contested zone, the same index
 *  meaning the same zone on both sides (`boardLayout` guarantees it; this
 *  refuses to scroll so the alignment survives). A column's cells stack
 *  vertically, so a multi-occupant lane stays one lane. */
@Composable
private fun LaneGrid(
    w: TileWiring,
    row: ccgui.BoardRow,
    /** Each column WITH its index into `row.grid`. The index travels with the
     *  column so a caller cannot hand over a subset and have every cell read
     *  from the wrong lane. */
    columns: List<Pair<Int, ccgui.BoardColumn>>,
    tileW: Dp,
    /** Only the VIEWER's own lanes can be played into, so only that row's
     *  columns light up and report a rectangle for an arc to land on. */
    interactive: Boolean,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = GRID_EDGE, vertical = LANE_PAD_V),
        horizontalArrangement = Arrangement.spacedBy(GRID_GAP),
    ) {
        columns.forEach { (ci, col) ->
            val lit = interactive && col.zone in w.litZones
            // The lane's HEIGHT IS FIXED and its tiles divide it; tiles shed
            // detail (art, then the type line) as their share shrinks. Counts
            // TILES, not cells: attachments must fit too.
            val laneCells = row.grid.getOrNull(ci).orEmpty()
            val laneH = w.rankH ?: if (w.railed) FLAT_SLOT_H else 88.dp
            // CELLS, not tiles: an attachment shares its host's cell
            // rather than claiming a quadrant of its own, so it stays directly
            // beneath the thing it is bolted to.
            val fill = ccgui.laneFill(tileW.value.toInt(), laneH.value.toInt(), laneCells.size)
            val cellW = fill.cellWidthDp.dp
            val cellH = fill.cellHeightDp.dp
            Column(
                Modifier
                    .width(tileW)
                    .onPlaced {
                        if (interactive) w.reportZone(col.zone, it)
                        w.reportLane(row.owner, col.zone, it)
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                laneCells.chunked(fill.cols).forEach { rowCells ->
                  Row(horizontalArrangement = Arrangement.spacedBy(GRID_GAP / 2)) {
                    rowCells.forEach { cell ->
                    val perm = cell.occupant?.let { w.s.battlefield[it] }
                    if (perm != null) {
                        BoardStack(w, perm, cell.attached, cellW, fill = fill)
                    } else {
                        // A REAL empty slot: an open lane and a full one must
                        // not look the same before you tap anything -- and when
                        // a play is waiting on a zone, the legal ones are what
                        // you TAP to answer it.
                        EmptySlot(
                            cell.zone.def,
                            cellW,
                            // The empty slot takes the same share as a tile, so
                            // a capped lane with one Ship and one hole reads as
                            // two equal halves rather than a tile above a
                            // full-height gap.
                            height = cellH,
                            lit = lit,
                            dimmed = !lit && w.litZones.isNotEmpty(),
                            onClick = if (lit) ({ w.commitZone(col.zone) }) else null,
                        )
                    }
                    }
                  }
                }
            }
        }
    }
}

/** The column labels, drawn ONCE for both sides, so the grid reads as opposed
 *  rather than as two unrelated rows. */
@Composable
private fun GridLabels(columns: List<ccgui.BoardColumn>, tileW: Dp) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = GRID_EDGE, vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(GRID_GAP),
    ) {
        columns.forEach { col ->
            Text(
                col.zone.removePrefix("lane-"),
                color = Cg.dim,
                fontFamily = Cg.mono,
                fontSize = 9.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(tileW),
            )
        }
    }
}

/** A seat's edge: the loss-carrying anchor as a badge, one companion as a portrait (more as a chip line),
 *  and a fixed set of numbers either side. Anchors keep full tile wiring through `BoardStack`. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeatEdge(
    w: TileWiring,
    p: ccg.Player,
    anchors: List<ccgui.BoardAnchorItem>,
    onOpenZone: (ZoneFocus) -> Unit,
    /** Null = the pool number opens the zone sheet instead of folding a lane. */
    onTogglePool: ((String) -> Unit)? = null,
    poolOpen: (String) -> Boolean = { false },
    /** RAILED: the edge sits in a narrow block beside the lanes, so it stacks. */
    stacked: Boolean = false,
    /** Show the seat id (Playtest, or a game with no badge). */
    seatLabel: Boolean = false,
    /** The companion line goes above the badge row, so the badge faces the grid. */
    chipsAbove: Boolean = false,
) {
    val s = w.s
    val fields = remember(s, w.rules, p.id) { ccgui.seatFields(w.rules, s, p.id) }
    val badge = remember(s, w.rules, anchors) { ccgui.badgeAnchor(w.rules, s, anchors) }
    val chips = anchors.filter { it != badge }
    val out = p.id in s.losers
    val motion = seatMotion(w.fx.seatCounters[p.id] ?: 0, w.fxSeq, w.juice, w.reducedMotion)
    val showSeat = seatLabel || badge == null

    @Composable
    fun Numbers(list: List<ccgui.SeatField>, lead: Boolean) {
        Row(
            motion,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (lead && showSeat) {
                Text(
                    p.id + if (out) " out" else "",
                    color = if (out) Cg.dim else Cg.ink, fontFamily = Cg.mono,
                    fontSize = 10.sp, fontWeight = FontWeight.SemiBold, softWrap = false,
                )
            }
            list.forEach { f -> SeatNumber(w, p, f, out, onOpenZone, onTogglePool, poolOpen) }
        }
    }

    @Composable
    fun Badge() {
        badge?.let { b -> s.battlefield[b.id]?.let { BoardStack(w, it, b.attached, null, style = TileStyle.BADGE) } }
    }

    @Composable
    fun Chips() {
        chips.forEach { c -> s.battlefield[c.id]?.let { BoardStack(w, it, c.attached, null, style = TileStyle.CHIP) } }
    }

    @Composable
    fun Portraits() {
        chips.forEach { c -> s.battlefield[c.id]?.let { BoardStack(w, it, c.attached, null, style = TileStyle.PORTRAIT) } }
    }

    if (stacked) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = GRID_EDGE, vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(GRID_GAP),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) { Badge(); Chips() }
            Numbers(fields.cards, lead = true)
            Numbers(fields.resources, lead = false)
        }
        return
    }
    // The companion line's height comes from `ccgui.seatEdgeDp`, shared with the height budget.
    val companions = chips.count { s.battlefield[it.id] != null }
    val portraits = badge != null && companions > 0 && ccgui.companionsAsPortraits(companions)
    val chipLine = badge != null && companions > 0 && !portraits

    @Composable
    fun ChipLine() {
        Row(
            Modifier.fillMaxWidth().height(ccgui.SEAT_CHIP_LINE_DP.dp),
            horizontalArrangement = Arrangement.spacedBy(GRID_GAP, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) { Chips() }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = GRID_EDGE)) {
        if (chipLine && chipsAbove) ChipLine()
        Row(
            Modifier.fillMaxWidth().height(ccgui.SEAT_EDGE_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).clipToBounds(), contentAlignment = Alignment.CenterStart) {
                Numbers(fields.cards, lead = true)
            }
            Row(
                Modifier.padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(GRID_GAP),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (badge != null) Badge() else Chips()
            }
            Row(
                Modifier.weight(1f).clipToBounds(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (portraits) Portraits()
                Spacer(Modifier.weight(1f))
                Numbers(fields.resources, lead = false)
            }
        }
        if (chipLine && !chipsAbove) ChipLine()
    }
}

/** A label over its value, always in the same place; zero is dim, never dropped. */
@Composable
private fun SeatNumber(
    w: TileWiring,
    p: ccg.Player,
    f: ccgui.SeatField,
    out: Boolean,
    onOpenZone: (ZoneFocus) -> Unit,
    onTogglePool: ((String) -> Unit)?,
    poolOpen: (String) -> Boolean,
) {
    val zone = f.zone
    val folds = f.pool && zone != null && onTogglePool != null
    val hot = folds && zone != null &&
        ccgui.anyPlayableIn(w.rules, w.s, p.id, p.customZones[zone].orEmpty(), CastZone.Declared(zone))
    val open = folds && zone != null && poolOpen(zone)
    val tap: (() -> Unit)? = when {
        zone == null -> null
        folds -> ({ onTogglePool?.invoke(zone) })
        else -> ({ onOpenZone(ZoneFocus(p.id, zone)) })
    }
    val shape = RoundedCornerShape(6.dp)
    Column(
        Modifier
            .clip(shape)
            .then(if (open) Modifier.background(Cg.accentWash) else Modifier)
            .then(if (hot) Modifier.border(1.dp, Cg.go, shape) else Modifier)
            .then(if (tap != null) Modifier.clickable(onClick = tap) else Modifier)
            .padding(horizontal = 3.dp, vertical = 1.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            f.label + if (folds) (if (open) "▾" else "▸") else "",
            color = Cg.dim, fontFamily = Cg.mono, fontSize = 8.sp, softWrap = false, maxLines = 1,
        )
        Text(
            "${f.value}",
            color = when {
                hot -> Cg.go
                out || f.zero -> Cg.dim
                else -> Cg.ink
            },
            fontFamily = Cg.mono, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            softWrap = false, maxLines = 1,
        )
    }
}

/** Single-line height budgets for the top band and the prompt. */
private const val TOP_BAND_DP = 36
private const val PROMPT_LINE_DP = 48

/** The landscape prompt rail: wide enough for a question over two lines and a
 *  row of chips, narrow enough that the board keeps most of the window. */
private val RAIL_W = 176.dp

/** Anchor-block width beside the lanes: the STACKED fallback only (RAILED uses
 *  `ccgui.anchorBlockWidthDp(n)`). */
private val ANCHOR_BLOCK_W = 300.dp

/** An empty lane, railed: the same height as the flat tile that would occupy
 *  it. Four rows of these are on screen at once. */
private val FLAT_SLOT_H = 36.dp

/** One battlefield permanent's tile, wired to the live prompt: a single-target
 *  prompt (PickTarget / CombatTgt / Redirect) arms on a tap and confirms on the
 *  next; declaring attackers is a multi-select toggle (the bar's "Attack (n)"
 *  confirms); assigning blockers pairs a tapped blocker with the attacker it
 *  covers. Otherwise a plain tile.
 *
 *  THE one place that policy lives; grid, seat edge and attachments all render
 *  through it (see `TileWiring`). */
@Composable
private fun BattlefieldTile(
    w: TileWiring,
    perm: ccg.Permanent,
    width: Dp? = null,
    modifier: Modifier = Modifier,
    fill: ccgui.LaneFill? = null,
    /** Overrides `w.railed` for the "nothing is happening to this tile"
     *  branches. Anchors pass it unrailed too, since an anchor tile with its art
     *  box is taller than the board budget allows. */
    flat: Boolean = w.railed,
    style: TileStyle = TileStyle.CARD,
) {
    val s = w.s
    val rules = w.rules
    val prompt = w.prompt
    val answer = w.answer
    val armed = w.armed
    val onFocus = w.onFocus
    val reducedMotion = w.reducedMotion
    // Arrival (slide up once), attack nudge and impact are folded into the ONE
    // modifier every PermTile branch applies, so no branch can miss one. No-ops
    // under reduced motion or for a tile already on the board.
    val entrance = entranceMotion(
        play = w.isNew(perm.id),
        reducedMotion = reducedMotion,
        fromY = w.juice.arrivalTravelDp.dp,
        cfg = w.juice,
    )
        .then(impactMotion(w.damageOf(perm.id), w.fxSeq, w.juice, reducedMotion))
        .then(counterPop(w.counterDeltaOf(perm.id), w.fxSeq, w.juice, reducedMotion))
    val placed = modifier.then(
        if (w.onBounds == null) {
            Modifier
        } else {
            Modifier.onGloballyPositioned { w.report(perm.id, it) }
        },
    )
    // Beside, not under: the seat edge has a fixed height.
    @Composable
    fun Container(content: @Composable () -> Unit) {
        if (style == TileStyle.CARD) {
            Column(placed, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) { content() }
        } else {
            Row(placed, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) { content() }
        }
    }
    Container {
        when {
            prompt is Question.Attackers && perm.id in prompt.eligible -> {
                val selected = perm.id in answer.attackSel
                val nudge = nudgeMotion(trigger = selected, reducedMotion = reducedMotion)
                PermTile(
                    s, rules, perm, onFocus,
                    flat = flat,
                    style = style,
                    artFilenames = w.artOf, artStore = w.artStore, artGameId = w.artGameId,
                    ring = if (selected) CgTileRing.SELECTED else CgTileRing.TARGET,
                    lifted = selected,
                    onClick = { answer.attackSel = if (selected) answer.attackSel - perm.id else answer.attackSel + perm.id },
                    modifier = entrance.then(nudge),
                    width = width,
                    fill = fill,
                )
            }
            prompt is Question.Blockers && perm.id in prompt.eligibleBlockers -> {
                val isActive = answer.activeBlocker == perm.id
                val assignedTo = answer.blockAssign[perm.id]
                PermTile(
                    s, rules, perm, onFocus,
                    flat = flat,
                    style = style,
                    artFilenames = w.artOf, artStore = w.artStore, artGameId = w.artGameId,
                    ring = if (isActive) CgTileRing.SELECTED else CgTileRing.TARGET,
                    lifted = isActive,
                    onClick = { answer.activeBlocker = if (isActive) null else perm.id },
                    modifier = entrance,
                    width = width,
                    fill = fill,
                )
                // Same rule as the target pill: a shrunken cell gets the mark
                // without the sentence. "→ #12 Bastion Drone" and "tap an
                // attacker" are both wider than a quadrant cell.
                val roomy = style == TileStyle.CARD && (fill == null || fill.detail == ccgui.CellDetail.FULL)
                when {
                    assignedTo != null ->
                        ConfirmPill(
                            if (roomy) "→ ${s.lbl(assignedTo)}" else "→",
                            Cg.wire,
                        ) { answer.blockAssign = answer.blockAssign - perm.id }
                    isActive && roomy ->
                        Text("tap an attacker", color = Cg.dim, fontFamily = Cg.mono, fontSize = 9.sp)
                }
            }
            prompt is Question.Blockers && perm.id in prompt.attackers && answer.activeBlocker != null ->
                PermTile(
                    s, rules, perm, onFocus,
                    flat = flat,
                    style = style,
                    artFilenames = w.artOf, artStore = w.artStore, artGameId = w.artGameId,
                    ring = CgTileRing.TARGET,
                    onClick = {
                        answer.blockAssign = answer.blockAssign + (answer.activeBlocker!! to perm.id)
                        answer.activeBlocker = null
                    },
                    modifier = entrance,
                    width = width,
                    fill = fill,
                )
            perm.id in w.tapIds -> {
                val isArmed = armed.value == perm.id
                PermTile(
                    s, rules, perm, onFocus,
                    flat = flat,
                    style = style,
                    artFilenames = w.artOf, artStore = w.artStore, artGameId = w.artGameId,
                    ring = if (isArmed) CgTileRing.SELECTED else CgTileRing.TARGET,
                    lifted = isArmed,
                    onClick = if (isArmed) {
                        { armed.value = null; prompt?.let { w.input.answer(tapAnswer(rules, it, perm.id)) } }
                    } else {
                        { armed.value = perm.id }
                    },
                    modifier = entrance,
                    width = width,
                    fill = fill,
                )
                if (isArmed) {
                    // Icon only once the card is halved: the text pill is wider
                    // than a quadrant cell.
                    ConfirmPill(
                        if (style == TileStyle.CARD && (fill == null || fill.detail == ccgui.CellDetail.FULL)) "◎ Target?" else "◎",
                        Cg.wire,
                    ) {
                        armed.value = null
                        prompt?.let { w.input.answer(tapAnswer(rules, it, perm.id)) }
                    }
                }
            }
            // Nothing is tappable during a beat, so this ring only says "the
            // beat means THIS one". It breathes so the eye catches it; reduced
            // motion keeps the ring and drops the movement.
            w.inBeat(perm.id) -> PermTile(
                s, rules, perm, onFocus,
                artFilenames = w.artOf, artStore = w.artStore, artGameId = w.artGameId,
                style = style,
                flat = flat,
                ring = CgTileRing.BEAT,
                modifier = entrance.then(if (reducedMotion) Modifier else Modifier.beatPulse()),
                width = width,
                fill = fill,
            )
            // Something this permanent can DO: arm, then confirm. One ability is
            // one pill; two or more open a menu (the only case needing one).
            w.moves[perm.id]?.isNotEmpty() == true -> {
                val moves = w.moves.getValue(perm.id)
                val isArmed = armed.value == perm.id
                PermTile(
                    s, rules, perm, onFocus,
                    style = style,
                    artFilenames = w.artOf, artStore = w.artStore, artGameId = w.artGameId,
                    flat = flat,
                    ring = if (isArmed) CgTileRing.SELECTED else CgTileRing.PLAYABLE,
                    lifted = isArmed,
                    onClick = {
                        if (isArmed) {
                            armed.value = null
                            w.onMenu(null)
                        } else {
                            armed.value = perm.id
                            // Ambiguous only with two or more.
                            w.onMenu(if (moves.size > 1) perm.id else null)
                        }
                    },
                    modifier = entrance,
                    width = width,
                    fill = fill,
                )
                if (isArmed && moves.size == 1) {
                    // Short label on purpose: this pill lives inside a lane
                    // column, and a column is `available / N` wide. What the
                    // ability actually does is a long-press away, where there
                    // is room to read it.
                    ConfirmPill(if (style == TileStyle.CARD) "▶ use" else "▶", Cg.go) {
                        armed.value = null
                        w.onMenu(null)
                        w.input.answer(moves.single().action)
                    }
                }
            }
            else -> PermTile(
                s, rules, perm, onFocus,
                style = style,
                artFilenames = w.artOf, artStore = w.artStore, artGameId = w.artGameId,
                modifier = entrance, width = width, flat = flat, fill = fill,
            )
        }
    }
}

/** The held-tap focus view: the card, larger, lifted to the front with a
 *  spring. Tap anywhere to dismiss. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardFocusOverlay(
    fc: FocusCard,
    /** Card art: the focus view is where the FULL framing is meant to be
     *  seen. Ahead of `onDismiss`, which is a trailing lambda at the call site:
     *  appending a parameter after one silently eats the lambda. */
    artOf: Map<String, ccgui.CardArtRef> = emptyMap(),
    artStore: GameStore? = null,
    artGameId: String = "",
    /** The Debug lens's move: null offers none. */
    onMove: ((FocusCard, EditZone) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(fc) { anim.snapTo(0f); anim.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 240f)) }
    // Composed last, so Back closes the card before any screen-level handler.
    BackHandler { onDismiss() }
    val tilt = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val held = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .fillMaxSize()
            .background(Cg.bg.copy(alpha = 0.86f * anim.value)),
        contentAlignment = Alignment.Center,
    ) {
        // A sibling behind the card, not an ancestor: an ancestor tap detector would consume the hold-to-tilt press.
        Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { onDismiss() } })
        Box(
            Modifier
                .graphicsLayer {
                    val a = anim.value
                    val sc = (0.82f + 0.18f * a) * (1f + 0.02f * held.value)
                    scaleX = sc; scaleY = sc
                    translationY = (1f - a) * 40f
                    alpha = a
                    val maxDeg = 12f
                    val t = tilt.value
                    rotationY = (t.x / (size.width / 2f).coerceAtLeast(1f)).coerceIn(-1f, 1f) * maxDeg
                    rotationX = -(t.y / (size.height / 2f).coerceAtLeast(1f)).coerceIn(-1f, 1f) * maxDeg
                    cameraDistance = 14f * density
                }
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { scope.launch { held.animateTo(1f, spring(stiffness = 400f)) } },
                        onDragEnd = {
                            scope.launch { tilt.animateTo(Offset.Zero, spring(dampingRatio = 0.55f, stiffness = 180f)) }
                            scope.launch { held.animateTo(0f) }
                        },
                        onDragCancel = {
                            scope.launch { tilt.animateTo(Offset.Zero, spring(dampingRatio = 0.55f, stiffness = 180f)) }
                            scope.launch { held.animateTo(0f) }
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            scope.launch { tilt.snapTo(tilt.value + amount) }
                        },
                    )
                }
                .shadow(24.dp, RoundedCornerShape(14.dp), clip = false),
        ) {
            // Sized to the SCREEN: the window's width up to a cap, and the
            // rulebox gets whatever height is left, scrolling beyond it -- so
            // most cards need no scroll and a long one is bounded, not cut.
            BoxWithConstraints {
                val cardW = (maxWidth - 72.dp).coerceAtMost(400.dp)
                // Captured HERE: inside the rulebox's own Column the
                // `BoxWithConstraints` receiver is out of scope.
                val overlayH = maxHeight - 150.dp
                // A real card border and ground, so the focused card reads as an
                // object lifted off the table.
                Column(
                    Modifier
                        .width(cardW)
                        .heightIn(max = overlayH)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Cg.surface)
                        .border(1.dp, Cg.accent.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                val artRef = artOf[fc.key]
                CgCardTile(
                    name = fc.name, typeLabel = fc.typeLabel, costLabel = fc.cost,
                    pt = fc.pt, text = fc.text, layout = fc.layout, counters = fc.counters,
                    art = artRef?.let { e ->
                        artStore?.let { st -> rememberCardArt(st, artGameId, e.file, maxPx = ART_PX_EDIT) }
                    },
                    artRect = artRef?.rect(compact = false),
                    artWhole = true,
                    compact = false, width = cardW,
                    // The focus view exists to be READ: a compiled cost is a
                    // sentence, and ellipsising it hides what the player opened
                    // the card to find out.
                    wrapTypeLine = true,
                )
                if (fc.rulebox.isNotEmpty()) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            // The rulebox gets AT LEAST a third of the overlay
                            // (tall art must not squeeze the rules into a
                            // sliver); a floor, not a cap.
                            .heightIn(min = overlayH / 3)
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .clip(RoundedCornerShape(8.dp))
                            .background(Cg.surface)
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            "RULES", color = Cg.accentLight, fontFamily = Cg.mono,
                            fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                        )
                        fc.rulebox.forEach { line ->
                            Text("· $line", color = Cg.ink2, fontFamily = Cg.mono, fontSize = 10.sp, lineHeight = 14.sp)
                        }
                    }
                }
                }
            }
        }
        val from = fc.from
        if (onMove != null && fc.id != null && from != null) {
            // Where the card can go, under it: a sandbox edit, recorded.
            FlowRow(
                Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = 110.dp, bottom = 24.dp)
                    .graphicsLayer { alpha = anim.value },
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("move to", color = Cg.dim, fontFamily = Cg.mono, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
                for (z in moveTargets(from)) Chip(if (z == EditZone.BATTLEFIELD) "play" else z.label(), false) { onMove(fc, z) }
            }
        }
        Text(
            "‹ back",
            color = Cg.muted, fontFamily = Cg.mono, fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 28.dp)
                .graphicsLayer { alpha = anim.value }
                .clip(RoundedCornerShape(999.dp))
                .border(1.dp, Cg.borderMuted, RoundedCornerShape(999.dp))
                .background(Cg.surface.copy(alpha = 0.7f))
                .clickable(onClick = onDismiss)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LaneRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

/** Which player's zone (or the one shared zone) the zone sheet is browsing.
 *  `player == null` names the shared zone (exile). */
data class ZoneFocus(val player: PlayerId?, val zone: String)

/** How a permanent is drawn; tap, ring, focus and bounds wiring is the same for every style. */
private enum class TileStyle { CARD, BADGE, CHIP, PORTRAIT }

@Composable
private fun AnchorPill(
    s: GameState,
    rules: Rules,
    perm: ccg.Permanent,
    style: TileStyle,
    ring: CgTileRing,
    lifted: Boolean,
    dim: Boolean,
    onClick: (() -> Unit)?,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    /** PORTRAIT only: the card's own picture, framed with its thumbnail crop. */
    art: ImageBitmap? = null,
    artRect: ccg.ArtRect? = null,
) {
    val c = s.characteristicsOf(perm.id)
    val badge = style == TileStyle.BADGE
    val portrait = style == TileStyle.PORTRAIT
    val value = if (badge) ccgui.badgeValue(rules, s, perm.id) else null
    var pressed by remember { mutableStateOf(false) }
    val liftT by animateFloatAsState(if (pressed || lifted) 1f else 0f, label = "anchorLift")
    // pointerInput(Unit) starts once; rememberUpdatedState keeps the handlers current.
    val tap by rememberUpdatedState(onClick)
    val hold by rememberUpdatedState(onLongPress)
    val borderColor = when (ring) {
        CgTileRing.TARGET -> Cg.wire
        CgTileRing.BEAT -> Cg.accentLight
        CgTileRing.PLAYABLE -> Cg.go
        CgTileRing.SELECTED -> Cg.accent
        CgTileRing.NONE -> Cg.borderMuted
    }
    val shape = when {
        badge -> RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 8.dp, bottomEnd = 8.dp)
        portrait -> RoundedCornerShape(8.dp)
        else -> RoundedCornerShape(7.dp)
    }
    Box(
        modifier
            .then(
                when {
                    badge -> Modifier.widthIn(min = 84.dp, max = 124.dp).height(46.dp)
                    portrait -> Modifier.width(64.dp).height(46.dp)
                    else -> Modifier.widthIn(max = 220.dp).height(24.dp)
                },
            )
            .graphicsLayer {
                val sc = 1f + 0.05f * liftT
                scaleX = sc; scaleY = sc
                rotationZ = if (perm.exhausted) (if (badge) 3f else 6f) else 0f
                alpha = if (dim) 0.5f else 1f
            }
            .clip(shape)
            .background(if (pressed || lifted) Cg.floating else Cg.raised)
            .border(if (ring != CgTileRing.NONE) 2.dp else 1.dp, borderColor, shape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { tap?.invoke() },
                    onLongPress = { hold() },
                )
            }
            .padding(horizontal = if (portrait) 0.dp else if (badge) 10.dp else 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (portrait) {
            if (art != null) ArtLayer(art, artRect)
            Box(
                Modifier.fillMaxSize().then(
                    if (art != null) Modifier.background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            0.35f to Color.Transparent, 1f to Cg.bg.copy(alpha = 0.92f),
                        ),
                    ) else Modifier,
                ),
                contentAlignment = if (art != null) Alignment.BottomCenter else Alignment.Center,
            ) {
                Text(
                    c.name.ifEmpty { "#${perm.id}" }, color = Cg.ink, fontSize = 8.sp, lineHeight = 9.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 3.dp, vertical = 2.dp),
                )
            }
        } else if (badge) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    c.name.ifEmpty { "#${perm.id}" }, color = Cg.ink, fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (value != null) {
                    Text(
                        "$value", color = Cg.life, fontFamily = Cg.mono, fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold, lineHeight = 19.sp, maxLines = 1,
                    )
                }
            }
        } else {
            Text(
                c.name.ifEmpty { "#${perm.id}" }, color = Cg.ink2, fontSize = 10.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PermTile(
    s: GameState,
    rules: Rules,
    perm: ccg.Permanent,
    onFocus: (FocusCard) -> Unit,
    /** card key -> art filename, and what resolves it. */
    artFilenames: Map<String, ccgui.CardArtRef> = emptyMap(),
    artStore: GameStore? = null,
    artGameId: String = "",
    ring: CgTileRing = CgTileRing.NONE,
    lifted: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** Fixed tile width, so a lane GRID can size its columns to
     *  `available / N` instead of letting each tile pick its own width and
     *  hoping the two sides of the table happen to line up. Null keeps the
     *  design system's default. */
    width: Dp? = null,
    /** RAILED: drop the art box. See below -- it is the single biggest thing
     *  standing between landscape and a board that fits. */
    flat: Boolean = false,
    /** This tile's SHARE of its lane's fixed height, and how much detail that
     *  share can carry. Null = size to content, which is every caller
     *  outside a lane grid. Decided by `ccgui.laneFill`, not here. */
    fill: ccgui.LaneFill? = null,
    style: TileStyle = TileStyle.CARD,
) {
    if (style != TileStyle.CARD) {
        val ownZones = rules.legalZonesFor(s.characteristicsOf(perm.id).types)
        val portraitArt = if (style == TileStyle.PORTRAIT) perm.cardId?.let { k -> artFilenames[k] } else null
        AnchorPill(
            s, rules, perm, style, ring, lifted,
            dim = ownZones != null && perm.zone.def !in ownZones,
            onClick = onClick,
            onLongPress = { onFocus(focusForPerm(s, rules, perm)) },
            modifier = modifier,
            art = portraitArt?.let { e -> artStore?.let { st -> rememberCardArt(st, artGameId, e.file) } },
            artRect = portraitArt?.rect(compact = true),
        )
        return
    }
    val c = s.characteristicsOf(perm.id)
    // No art box when railed: it costs 46dp per tile row, repeated on every row
    // on screen, and height is what landscape lacks.
    val layout = rules.layoutFor(c.types).let { if (flat) it.copy(art = ArtSlot.NONE) else it }
    // Joined on `CardDefinition.key` -- the identity the built card
    // carries -- rather than on the first face's name.
    val artEntry = perm.cardId?.let { k -> artFilenames[k] }
    val art = artEntry?.let { e -> artStore?.let { st -> rememberCardArt(st, artGameId, e.file) } }
    val pt = layout.statFields.mapNotNull { c.fields[it] }.takeIf { it.isNotEmpty() }?.joinToString("/")
        ?: c.fields["power"]?.let { "$it/${c.fields["toughness"] ?: 0}" }
    // A permanent's OWN natural zone(s), so a Ship in its lane is not dimmed as
    // "off the battlefield". Unrestricted types fall back to `defaultZoneDef`.
    val ownZones = rules.legalZonesFor(c.types) ?: setOf(rules.defaultZoneDef(c.types))
    val offOwnZone = perm.zone.def !in ownZones
    // The same check drives the "@zone" label, so dim and label cannot disagree.
    val extras = buildList {
        if (perm.damageMarked > 0) add("${perm.damageMarked} dmg")
        perm.combatMode?.let { add(it) }
        if (offOwnZone) add("@${perm.zone.def}")
    }
    CgCardTile(
        art = art,
        // the SAME framing the Creator previewed. Art that reframes
        // itself between the two screens would be worse than art that does not
        // move at all. On the board that is the THUMBNAIL framing.
        artRect = artEntry?.rect(compact = true),
        name = c.name.ifEmpty { "#${perm.id}" },
        // No type line when flat: every card on this board is the same type,
        // so it is a word that repeats itself twelve times and costs a text
        // line in each of the four tile rows on screen.
        typeLabel = if (flat) "" else c.types.firstOrNull() ?: "",
        costLabel = null,
        pt = pt,
        text = "",
        modifier = modifier,
        compact = true,
        layout = layout,
        counters = perm.counters.filterValues { it > 0 },
        tapped = perm.exhausted,
        dim = offOwnZone,
        ring = ring,
        lifted = lifted,
        width = width,
        height = fill?.cellHeightDp?.dp,
        hideArt = fill != null && fill.detail != ccgui.CellDetail.FULL,
        hideType = fill?.detail == ccgui.CellDetail.NAME_ONLY,
        onClick = onClick,
        onLongPress = { onFocus(focusForPerm(s, rules, perm)) },
        footer = if (extras.isEmpty()) null else {
            { extras.forEach { Text(it, color = Cg.warn, fontFamily = Cg.mono, fontSize = 8.sp) } }
        },
    )
}

/** The zone sheet: a chip per zone, its cards as a tile grid. The PLAYTEST
 *  viewpoint may browse any zone (a debugger); the PLAYER asks `Viewpoint` the
 *  same question the board asks, or hiding a hand would be defeated by one tap. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ZonesView(
    s: GameState, rules: Rules, vp: Viewpoint, focus: ZoneFocus?,
    /** A held card, lifted into the focus view. Before the trailing `onFocus`. */
    onCard: ((FocusCard) -> Unit)? = null,
    onFocus: (ZoneFocus) -> Unit,
) {
    CgCard {
        Text("zones — tap one to browse it", color = Cg.dim, fontFamily = Cg.mono, fontSize = 9.sp)
        s.players.values.forEach { p ->
            Spacer(Modifier.height(6.dp))
            Text(p.id, color = Cg.ink, fontFamily = Cg.mono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("hand (${p.hand.size})", focus == ZoneFocus(p.id, "hand")) { onFocus(ZoneFocus(p.id, "hand")) }
                // Top of library first -- the next card drawn is the first one
                // listed, which is the only ordering a reader can act on.
                Chip("library (${p.library.size})", focus == ZoneFocus(p.id, "library")) { onFocus(ZoneFocus(p.id, "library")) }
                Chip("graveyard (${p.graveyard.size})", focus == ZoneFocus(p.id, "graveyard")) { onFocus(ZoneFocus(p.id, "graveyard")) }
                // Declared custom zones (a Flagship pool) are browsable too --
                // the fold must leave a way to look.
                rules.hiddenZones.values.forEach { hz ->
                    val n = p.customZones[hz.id].orEmpty().size
                    Chip("${hz.id} ($n)", focus == ZoneFocus(p.id, hz.id)) { onFocus(ZoneFocus(p.id, hz.id)) }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("exile — shared (${s.exile.size})", focus == ZoneFocus(null, "exile")) { onFocus(ZoneFocus(null, "exile")) }
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Cg.border))
        Spacer(Modifier.height(6.dp))
        val cards = zoneCards(s, focus)
        // The explorer respects the SAME visibility policy the board does.
        val readable = focus == null || vp.mayRead(focus.player, focus.zone)
        when {
            focus == null -> Text("tap a zone above to browse it", color = Cg.dim, fontFamily = Cg.mono, fontSize = 11.sp)
            !readable -> FaceDownCards(cards.size)
            cards.isEmpty() -> Text("—  (empty)", color = Cg.dim, fontFamily = Cg.mono, fontSize = 11.sp)
            else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                cards.forEach { ref ->
                    ZoneCardTile(rules, ref, onLongPress = onCard?.let { f -> {
                        val built = rules.cards[ref.cardId]
                        f(focusForBuilt(rules, built, built?.types ?: emptySet(), id = ref.instanceId, from = editZoneNamed(focus!!.zone)))
                    } })
                }
            }
        }
    }
}

private fun zoneCards(s: GameState, focus: ZoneFocus?): List<CardRef> = when {
    focus == null -> emptyList()
    focus.zone == "exile" -> s.exile
    else -> s.players[focus.player]?.let { p ->
        when (focus.zone) {
            "hand" -> p.hand
            "library" -> p.library
            "graveyard" -> p.graveyard
            // A declared custom zone (a Flagship pool).
            else -> p.customZones[focus.zone].orEmpty()
        }
    } ?: emptyList()
}

/** A zone the viewer may not read, drawn as what it actually is: a known
 *  number of unknown cards. Not an empty space and not an error -- the count is
 *  public in every card game, and hiding it would be less honest than hiding
 *  nothing. */
@Composable
private fun FaceDownCards(n: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(n.coerceAtMost(12)) { CardBack() }
        Text(
            if (n == 0) "—  (empty)" else "$n hidden",
            color = Cg.dim, fontFamily = Cg.mono, fontSize = 11.sp,
        )
    }
}

/** One face-down card. */
@Composable
private fun CardBack() {
    Box(
        Modifier
            .width(22.dp)
            .height(30.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Cg.surfaceAlt)
            .border(1.dp, Cg.border, RoundedCornerShape(3.dp)),
    )
}

/** A hand belonging to someone else: its size, drawn, and nothing else. */
@Composable
private fun FaceDownLane(label: String, n: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("   $label — $n", color = Cg.dim, fontFamily = Cg.mono, fontSize = 9.sp)
        LaneRow { repeat(n.coerceAtMost(12)) { CardBack() } }
    }
}

@Composable
private fun ZoneCardTile(rules: Rules, ref: CardRef, onLongPress: (() -> Unit)? = null) {
    val built = rules.cards[ref.cardId]
    val types = built?.types ?: emptySet()
    CgCardTile(
        name = built?.name ?: ref.cardId,
        typeLabel = types.firstOrNull() ?: "",
        costLabel = null, pt = null, text = "", compact = true,
        layout = rules.layoutFor(types),
        onLongPress = onLongPress,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PromptBar(
    prompt: Question?,
    finalState: GameState?,
    cards: List<Pair<CardDoc, CardDefinition>>,
    attacksAreActions: Boolean,
    rules: Rules,
    input: HotseatInput,
    answer: BoardAnswer,
    laneChoice: MutableState<PriorityAction.PlayPermanent?>,
    /** The tile currently ARMED on the board, so the bar can offer a way out of
     *  it. Shared state rather than a copy: the board arms it, the bar
     *  clears it, and there is one answer to "what is armed". */
    armed: MutableState<ObjectId?>,
    /** Band or floating panel. Stacked, this is a bounded strip under the
     *  board; railed, it floats in the board's bottom-right corner and is only
     *  as large as the question being asked. */
    modifier: Modifier = Modifier.fillMaxWidth().heightIn(max = 168.dp),
    /** Floating: shrink to the content instead of filling the corner, and drop
     *  the full-width divider, which is a band's affordance and reads as a
     *  broken edge on a panel. */
    floating: Boolean = false,
    /** Null in Playtest, which keeps seat ids. */
    viewer: PlayerId? = null,
    /** Long-pressing a candidate card opens it, which is the only way to read
     *  a compiled rulebox. Threaded in for the same reason the board has it. */
    onFocus: (FocusCard) -> Unit,
) {
    Column(
        // Capped (the board draws the activations, so the bar is a question and
        // a short row of buttons); it scrolls past the cap rather than
        // truncating.
        modifier.background(Cg.surface).verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .then(if (floating) Modifier.width(IntrinsicSize.Max) else Modifier),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (!floating) Box(Modifier.fillMaxWidth().height(1.dp).background(Cg.border))
        // A pending zone choice pre-empts the normal prompt body: whichever
        // affordance raised it, it is answered in one place.
        val pending = laneChoice.value
        if (pending != null && prompt is Question.Priority) {
            val label = pending.card.faces.getOrElse(pending.face) { pending.card.faces[0] }.name
            // The BOARD answers this: legal lanes light green with arcs. No chip
            // picker here -- two ways to answer one question and the bar's chips
            // win, leaving the highlight unnoticed. Cancel stays, so it can
            // never trap.
            Ask("$label — tap a lit lane on the board")
            Chip("cancel", false) { laneChoice.value = null }
            return@Column
        }
        when (prompt) {
            null -> {
                if (finalState != null) {
                    val who = finalState.losers.joinToString().ifBlank { "nobody" }
                    Text("Game over — loser(s): $who", color = Cg.ink, fontFamily = Cg.mono, fontSize = 13.sp)
                } else {
                    Text("…", color = Cg.dim, fontFamily = Cg.mono, fontSize = 12.sp)
                }
            }
            is Question.Priority -> PriorityControls(prompt, cards, attacksAreActions, rules, input, laneChoice, viewer)
            is Question.PickTarget -> {
                // On-board candidates are answered by tapping their tile
                // (ring = TARGET); only candidates the board can't
                // show a tile for still get a bar chip.
                val offBoard = prompt.candidates.filter { it !in prompt.state.battlefield }
                when {
                    prompt.candidates.isEmpty() ->
                        Text("(no candidates)", color = Cg.dim, fontFamily = Cg.mono, fontSize = 11.sp)
                    offBoard.isEmpty() -> Ask("${prompt.player}: tap a target on the board")
                    else -> {
                        Ask("${prompt.player}: tap a target on the board, or:")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            offBoard.forEach { id -> Chip(prompt.state.lbl(id), false) { input.answer(id) } }
                        }
                    }
                }
            }
            is Question.PickNumber -> NumberControls(prompt, input)
            is Question.Attackers -> {
                // Eligible creatures are tapped on the board -- a plain
                // multi-select toggle (ring = TARGET/SELECTED); this is the
                // one confirm for the whole set.
                Ask("${prompt.player}: declare attackers — tap creatures on the board")
                if (prompt.eligible.isEmpty()) {
                    Text("(nothing can attack)", color = Cg.dim, fontFamily = Cg.mono, fontSize = 11.sp)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CgButton("Attack (${answer.attackSel.size})", enabled = answer.attackSel.isNotEmpty()) {
                            input.answer(answer.attackSel.toList())
                        }
                        CgButton("None") { input.answer(emptyList<ObjectId>()) }
                    }
                }
            }
            is Question.Blockers -> {
                // Tap a blocker to arm it, then tap the attacker it covers;
                // the bar only holds the one confirm.
                Ask("${prompt.player}: declare blockers — tap a blocker, then the attacker it covers")
                CgButton("Confirm blocks (${answer.blockAssign.size})") { input.answer(answer.blockAssign) }
            }
            is Question.CombatTgt -> {
                // Combatants, the defender's Station included, are tapped on
                // the board (ring = TARGET); see ccgui.combatBoardTargets.
                val opp = prompt.state.opponentOf(prompt.player)
                val anchor = ccgui.playerTargetAnchor(rules, prompt.state, opp)
                // The same question the board asks, for this specific attack's
                // range, so the fallback chip never offers a face attack through
                // an opposed lane.
                val faceOpen = prompt.state.canAttackFace(rules, prompt.attacker, opp, prompt.range)
                Ask("${prompt.player}: #${prompt.attacker} attacks whom? — tap a target, or:")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // FALLBACK ONLY: when a permanent stands for the player, its
                    // tile is the way to say this; with none, this chip is the
                    // only way.
                    if (anchor == null && faceOpen) {
                        Chip(ccgui.playerTargetLabel(rules, prompt.state, opp), false) {
                            input.answer(CombatTarget.Player(opp))
                        }
                    }
                    // Holding back is a real choice.
                    Chip("hold back", false) { input.answer(null) }
                }
            }
            is Question.Redirect -> {
                // Eligible blockers are tapped on the board; "let it through"
                // isn't a tile, so it stays a bar chip.
                Ask("${prompt.player}: redirect #${prompt.attacker}? — tap a blocker, or:")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("let it through", false) { input.answer(null) }
                }
            }
            is Question.PickMode -> MultiPickControls(
                title = "${prompt.player}: choose ${prompt.pick} of ${prompt.optionCount}",
                ids = prompt.options.indices.toList(),
                // WHAT each option does, not "option 1".
                label = { i -> modeOptionSummary(prompt.options[i]) },
                need = prompt.pick,
                confirm = "Confirm",
            ) { input.answer(it) }
            // CARDS, drawn as cards: a choice like "draw 2, exile 1 of them" needs
            // stats, cost and rules text.
            is Question.PickCards -> CardPickControls(
                title = "${prompt.player}: pick ${if (prompt.atMost) "up to " else ""}${prompt.count} card(s)",
                candidates = prompt.candidates,
                rules = rules,
                cards = cards,
                need = if (prompt.atMost) null else prompt.count,
                onFocus = onFocus,
            ) { input.answer(it) }
        }
    }
        // Backing out of an armed target: ONE cancel chip for every arming
        // prompt, shown only while something is armed.
        if (armed.value != null) {
            Chip("✕ cancel", false) { armed.value = null }
        }
}

@Composable
private fun Ask(t: String) = Text(t, color = Cg.ink, fontFamily = Cg.mono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PriorityControls(
    p: Question.Priority,
    cards: List<Pair<CardDoc, CardDefinition>>,
    attacksAreActions: Boolean,
    rules: Rules,
    input: HotseatInput,
    laneChoice: MutableState<PriorityAction.PlayPermanent?>,
    viewer: PlayerId? = null,
) {
    val s = p.state
    // The question and its answers share a line. A Row of the question and a
    // weighted FlowRow, so the question stays first and the chips wrap among
    // themselves.
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
    Ask(ccgui.priorityAsk(p.player, viewer, s.phase))
    FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip("Pass", true) { input.answer(PriorityAction.PassPriority) }
        // Build the real action and ask THE legality function -- the same one
        // the engine enforces, so what is offered and what is accepted cannot
        // disagree. `from` makes "do you actually hold this card" part of it.
        @Composable
        fun offer(built: CardDefinition, name: String, from: CardSource?, verbNote: String = "") {
            // `ccg.playActionFor` prices recasts; every path shares it.
            val action = playActionFor(rules, built, from, label = name)
            if (legality(rules, s, p.player, action) is Legality.Legal) {
                val verb = if (action is PriorityAction.CastSpell) "Cast" else "Play"
                // The third affordance that can commit a play (sandbox list,
                // graveyard recast): the same policy as the hand tile, so a Ship
                // played here asks for a lane too.
                Chip("$verb $name$verbNote", false) {
                    when (val i = commitIntent(rules, s, p.player, action)) {
                        is PlayIntent.ChooseZone -> laneChoice.value = i.action
                        is PlayIntent.Commit -> input.answer(i.action)
                        else -> input.answer(action)
                    }
                }
            }
        }

        val me = s.players.getValue(p.player)
        // The hand is played straight off the board (green-ringed tiles);
        // the bar only carries what has no tile: graveyard recast, activated
        // abilities, and INDIVIDUAL-combat attacks. (Cards from no zone at all
        // are the sandbox binder's, as recorded edits.)
        me.graveyard.forEach { ref ->
            val built = rules.cards[ref.cardId] ?: return@forEach
            val r = built.recast ?: return@forEach
            if (r.from != HiddenZone.GRAVEYARD) return@forEach
            offer(built, built.name, CardSource(CastZone.Std(r.from), ref.instanceId, r.afterResolve), " (from graveyard)")
        }
        // Activated abilities -- only those with nowhere else to go:
        // `activationsWithoutTile` subtracts what the board DRAWS, so an ability
        // with no tile still gets a chip (pinned in test/UiTest.kt).
        ccgui.activationsWithoutTile(rules, s, p.player).forEach { (permId, moves) ->
            moves.forEach { m -> Chip("${s.lbl(permId)} · ${m.label}", false) { input.answer(m.action) } }
        }
        if (attacksAreActions && s.phase == "combat") {
            val opp = s.opponentOf(p.player)
            s.battlefield.values
                .filter { it.controller == p.player && !it.exhausted && rules.fightsInCombat(s.characteristicsOf(it.id).types) }
                .forEach { atk ->
                    Chip("Attack ${s.lbl(atk.id)} → $opp", false) {
                        input.answer(PriorityAction.Attack(atk.id, CombatTarget.Player(opp)))
                    }
                }
        }
    }
    }
}

@Composable
private fun NumberControls(p: Question.PickNumber, input: HotseatInput) {
    var v by remember(p) { mutableStateOf(p.min) }
    Ask("${p.player}: ${p.label}  (${p.min}..${p.max})")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Chip("–", false) { if (v > p.min) v-- }
        Text("$v", color = Cg.ink, fontFamily = Cg.mono, fontSize = 15.sp)
        Chip("+", false) { if (v < p.max) v++ }
        Chip("OK", true) { input.answer(v) }
    }
}

/** Pick cards by looking at them: the same `CgCardTile` as the board and hand,
 *  long-press for the rulebox (often the whole basis of the choice). Selection
 *  is a ring, like every other pick in this UI. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardPickControls(
    title: String,
    candidates: List<CardRef>,
    rules: Rules,
    cards: List<Pair<CardDoc, CardDefinition>>,
    need: Int?,
    onFocus: (FocusCard) -> Unit,
    onConfirm: (List<ObjectId>) -> Unit,
) {
    var sel by remember(candidates) { mutableStateOf(setOf<ObjectId>()) }
    Ask(title)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        candidates.forEach { ref ->
            val built = rules.cards[ref.cardId]
            val types = built?.types ?: emptySet()
            val layout = rules.layoutFor(types)
            val chars = built?.faces?.firstOrNull()?.baseChars
            val pt = layout.statFields.mapNotNull { chars?.fields?.get(it) }
                .takeIf { it.isNotEmpty() }?.joinToString("/")
            val on = ref.instanceId in sel
            CgCardTile(
                name = built?.name ?: ref.cardId,
                typeLabel = types.firstOrNull() ?: "",
                costLabel = built?.let { costSummary(it.cost) },
                pt = pt,
                text = built?.text.orEmpty(),
                compact = true,
                layout = layout,
                ring = if (on) CgTileRing.SELECTED else CgTileRing.TARGET,
                lifted = on,
                onClick = {
                    sel = if (on) sel - ref.instanceId else sel + ref.instanceId
                },
                onLongPress = {
                    onFocus(focusForBuilt(rules, built, types, cards))
                },
            )
        }
    }
    val ok = need == null || sel.size == need
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CgButton("Confirm (${sel.size}${need?.let { "/$it" } ?: ""})", enabled = ok) { onConfirm(sel.toList()) }
        if (need == null) CgButton("None", enabled = true) { onConfirm(emptyList()) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> MultiPickControls(
    title: String,
    ids: List<T>,
    label: (T) -> String,
    need: Int?,
    confirm: String,
    onConfirm: (List<T>) -> Unit,
) {
    var sel by remember(ids) { mutableStateOf(setOf<T>()) }
    Ask(title)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ids.forEach { id ->
            Chip(label(id), id in sel) { sel = if (id in sel) sel - id else sel + id }
        }
    }
    val ok = need == null || sel.size == need
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CgButton("$confirm (${sel.size}${need?.let { "/$it" } ?: ""})", enabled = ok) { onConfirm(sel.toList()) }
        if (need == null) CgButton("None", enabled = true) { onConfirm(emptyList()) }
    }
}


/** An empty, nameable place a card could go, so a lane with room reads
 *  differently from a full one before anything is tapped. `lit` makes it the
 *  control: a play is waiting on a zone and this is a legal answer, ringed in
 *  the same green as a playable card. */
@Composable
private fun EmptySlot(
    zoneDef: String,
    width: Dp = 64.dp,
    /** The tile height in force: railed tiles have no art box, and an empty slot
     *  must not be taller than an occupied one. */
    height: Dp = 88.dp,
    lit: Boolean = false,
    /** A zone choice is pending and this is NOT one of the answers. */
    dimmed: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Box(
        Modifier
            .width(width)
            .height(height)
            .then(if (lit) Modifier.background(Cg.go.copy(alpha = 0.20f), RoundedCornerShape(6.dp)) else Modifier)
            .border(
                if (lit) 3.dp else 1.dp,
                // A choice that is pending makes the ILLEGAL lanes recede as
                // well as the legal ones stand out -- contrast is what the eye
                // actually catches, and a green ring on a board of grey rings
                // is a smaller difference than it sounds.
                when {
                    lit -> Cg.go
                    dimmed -> Cg.steel.copy(alpha = 0.10f)
                    else -> Cg.steel.copy(alpha = 0.30f)
                },
                RoundedCornerShape(6.dp),
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (lit) "▾ tap" else zoneDef.removePrefix("lane-"),
            color = when {
                lit -> Cg.go
                dimmed -> Cg.dim.copy(alpha = 0.2f)
                else -> Cg.dim.copy(alpha = 0.5f)
            },
            fontFamily = Cg.mono,
            fontSize = if (lit) 11.sp else 9.sp,
            fontWeight = if (lit) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
