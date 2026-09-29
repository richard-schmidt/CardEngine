package ccgui

import ccg.default
import ccg.publicZoneIds
import ccg.Question
import ccg.AttackRange
import ccg.Answer
import ccg.asAnswer
import ccg.bundleDigest
import ccg.CombatTarget
import ccg.GameDoc
import ccg.GameState
import ccg.ObjectId
import ccg.PlayerId
import ccg.PriorityAction
import ccg.Rules
import ccg.Run
import ccg.step
import ccg.viewFor
import ccg.hasAnyAction
import ccg.legalActionsFor
import ccg.toAction

// ---------------------------------------------------------------------------
// PLAYING A GAME FROM OUTSIDE THE APP (the agent / MCP surface).
//
// The engine PULLS decisions through `PlayerInput.ask`; an agent tool is
// request/response with nothing long-lived. Rather than park a continuation in
// a live process, this uses replay: a game in progress is fully described by
// (bundle, seed, deck picks, answers). So:
//
//   advance(doc, session) -> replay every recorded answer, STOP at the first
//                            unanswered decision, report state and options.
//   session.answered(a)   -> the caller appends one answer and calls again.
//
// Stateless: no process outlives a call and an interrupted game is a string.
// `HotseatInput` in the Compose layer is the interactive twin of `ReplayInput`;
// all real logic (`toAction`, `encodeAnswer`, `legalActionsFor`) is shared.
// ---------------------------------------------------------------------------

/** One option an agent may answer with: a stable id (unambiguous to send back)
 *  and a sentence (readable). */
data class AgentOption(val id: String, val label: String)

/** Where a game is, as an agent sees it. */
data class AgentView(
    val over: Boolean,
    /** Whose decision it is. Null when the game is over. */
    val player: PlayerId?,
    /** What is being asked, as a short phrase ("priority", "declare attackers"). */
    val question: String,
    val turn: Int,
    val phase: String,
    /** The board, per seat, in the order `state.turnOrder` declares. */
    val seats: List<AgentSeat>,
    val options: List<AgentOption>,
    /** The engine log since the previous decision -- what happened while the
     *  agent was not being asked. Without it an agent sees positions and never
     *  events, and cannot say why anything changed. */
    val since: List<String>,
    val losers: List<PlayerId>,
    /** The live question, so a caller decodes an option id against the REAL
     *  question type. Null when the game is over. Kept last. */
    val prompt: Question? = null,
)

data class AgentSeat(
    val id: PlayerId,
    val library: Int,
    val hand: List<String>,
    val graveyard: Int,
    val counters: Map<String, Int>,
    val pool: Map<String, Int>,
    /** "id  Name 2/3  @core" per permanent this seat controls. */
    val board: List<String>,
)

/** Replay a session and report the position (or that the game is finished),
 *  redacted for one seat.
 *
 *  The loop drives BOTH seats from one replay, so `viewer = null` is the open
 *  view an agent playing itself needs; naming a viewer asks `Viewpoint` what
 *  that seat may read, which is what makes a game against a person fair.
 *  `maxTurns` bounds a runaway game so the call ends rather than hangs. */
fun advance(
    doc: GameDoc,
    session: PlaySession,
    maxTurns: Int = 60,
    viewer: PlayerId? = null,
): AgentView {
    val rules = doc.compile().playable()
    // Answers recorded against another game are never replayed into this one.
    require(session.bundle == null || session.bundle == doc.bundleDigest()) {
        "this session was recorded against a different version of the game; start a new one"
    }
    val start = session.opening(doc, rules)
    val logBefore = start.log.size
    // The run, replayed to its first unanswered question: the same
    // `step` every client plays through.
    val stepped = step(rules, Run(start, session.answers, maxTurns))
    // The referee's question: an agent answering for both seats reads it
    // whole. A named viewer reads that seat's view.
    val p = stepped.full
    val state = viewer?.let { stepped.state.viewFor(it, rules) } ?: stepped.state
    val vp = viewer?.let {
        Viewpoint(PlayMode.PLAYER, it, declaredPublic = rules.publicZoneIds())
    }
    val seats = state.turnOrder.mapNotNull { id ->
        val pl = state.players[id] ?: return@mapNotNull null
        // A hidden hand is a COUNT, not an absence -- the count is public in
        // every card game, and hiding it would be less honest than hiding
        // nothing. Same wording the board's own face-down lane uses.
        val handOpen = vp == null || vp.handFaceUp(id)
        AgentSeat(
            id = id,
            library = pl.library.size,
            hand = if (handOpen) pl.hand.map { cardLabel(rules, it) }
                   else listOf("${pl.hand.size} hidden"),
            graveyard = pl.graveyard.size,
            counters = pl.counters.filterValues { it != 0 },
            pool = pl.pool.filterValues { it != 0 },
            board = state.battlefield.values.filter { it.controller == id }.map { perm ->
                val c = state.characteristicsOf(perm.id)
                val stats = rules.layoutFor(c.types).statFields.mapNotNull { c.fields[it] }
                    .takeIf { it.isNotEmpty() }?.joinToString("/")?.let { " $it" } ?: ""
                val marks = buildList {
                    if (perm.exhausted) add("exhausted")
                    if (perm.damageMarked > 0) add("${perm.damageMarked} dmg")
                    perm.hostId?.let { add("on #$it") }
                }.joinToString(", ").let { if (it.isEmpty()) "" else "  ($it)" }
                "#${perm.id} ${c.name}$stats @${perm.zone.def}$marks"
            },
        )
    }

    return AgentView(
        over = p == null,
        player = p?.player,
        question = p?.let { questionOf(it) } ?: "the game is over",
        turn = state.turnNumber,
        phase = state.phase,
        seats = seats,
        options = p?.let { optionsFor(rules, it) }.orEmpty(),
        since = state.log.drop(logBefore.coerceAtMost(state.log.size)),
        losers = state.losers.toList(),
        prompt = p,
    )
}

/** The two actions `beatText` has no sentence for. */
private fun passLabel(a: PriorityAction): String = when (a) {
    PriorityAction.PassPriority -> "pass"
    PriorityAction.Concede -> "concede"
    else -> a::class.simpleName ?: "act"
}

/** What a prompt is asking, in a phrase. */
fun questionOf(p: Question): String = when (p) {
    is Question.Priority -> "priority -- play something, or pass"
    is Question.PickTarget -> "choose a target"
    is Question.PickNumber -> "${p.label} (${p.min}..${p.max})"
    is Question.Attackers -> "declare attackers"
    is Question.Blockers -> "declare blockers"
    is Question.CombatTgt -> "what does #${p.attacker} attack?"
    is Question.Redirect -> "block #${p.attacker}?"
    is Question.PickMode -> "choose ${p.pick} mode(s)"
    is Question.PickCards -> "choose ${if (p.atMost) "up to " else ""}${p.count} card(s)"
}

/** Every answer the agent may give, as (id, sentence), built from
 *  `legalActionsFor` and the engine's own candidate lists -- the same
 *  enumeration the pilots use, so an offered option is one the engine accepts. */
fun optionsFor(rules: Rules, p: Question): List<AgentOption> = when (p) {
    is Question.Priority ->
        // `beatText` is the sentence the board narrates a move with, so agents
        // and humans read one vocabulary; a pass (null there) is worded here.
        // PASS AND CONCEDE ARE APPENDED: `legalActionsFor` omits both (right for
        // pilots, a dead end for an option list), and its enumeration order is
        // a contract pilots break ties on, so it is not widened here.
        // EXPANDED PER LANE: an agent has no second "where?" question, so each
        // destination from `openZonesFor` (the board's own answer) is its own
        // option. A card with one destination stays one option.
        legalActionsFor(rules, p.state, p.player).flatMapIndexed { i, a ->
            val label = beatText(p.state, p.player, a) ?: passLabel(a)
            val zones = openZonesFor(rules, p.state, p.player, a).orEmpty()
            if (zones.size <= 1) listOf(AgentOption("a$i", label))
            else zones.map { z -> AgentOption("a$i@$z", "$label into $z") }
        } + AgentOption("pass", "pass -- do nothing this window") +
            AgentOption("concede", "concede the game")
    is Question.PickTarget -> p.candidates.map { AgentOption("t$it", p.state.lbl(it)) }
    is Question.PickNumber -> (p.min..p.max).map { AgentOption("n$it", "$it") }
    is Question.Attackers ->
        p.eligible.map { AgentOption("x$it", "attack with ${p.state.lbl(it)}") } +
            AgentOption("x-none", "attack with nothing")
    is Question.Blockers ->
        p.eligibleBlockers.flatMap { b ->
            p.attackers.map { a -> AgentOption("b$b:$a", "${p.state.lbl(b)} blocks ${p.state.lbl(a)}") }
        } + AgentOption("b-none", "block with nothing")
    is Question.CombatTgt ->
        boardTapTargets(rules, p, p.state).map { AgentOption("c$it", "attack ${p.state.lbl(it)}") } +
            AgentOption("c-hold", "hold back -- deal no damage this step")
    is Question.Redirect ->
        p.candidates.map { AgentOption("r$it", "block with ${p.state.lbl(it)}") } +
            AgentOption("r-none", "let it through")
    is Question.PickMode ->
        p.options.mapIndexed { i, o -> AgentOption("m$i", modeOptionSummary(o)) }
    is Question.PickCards ->
        p.candidates.map { AgentOption("k${it.instanceId}", cardLabel(rules, it)) }
}


/** Turn an option id from `optionsFor` back into the `Answer` it names -- the
 *  other half of one encoding, kept beside it. Dispatched on the QUESTION, not
 *  the id's prefix, so an id is only read in the context that offered it.
 *
 *  Null = not an id this question offered; report it rather than substitute a
 *  legal move. */
fun answerFor(rules: Rules, p: Question, id: String): Answer? {
    fun idOf(prefix: String): ObjectId? =
        if (id.startsWith(prefix)) id.removePrefix(prefix).toIntOrNull() else null
    return when (p) {
        is Question.Priority -> when {
            id == "pass" -> Answer.Pass
            id == "concede" -> Answer.Concede
            id.startsWith("a") -> {
                // "a3" or "a3@core" -- the index picks the action, the suffix
                // picks the destination.
                val body = id.removePrefix("a")
                val at = body.indexOf('@')
                val i = (if (at < 0) body else body.take(at)).toIntOrNull()
                val zone = if (at < 0) null else body.drop(at + 1)
                val action = i?.let { legalActionsFor(rules, p.state, p.player).getOrNull(it) }
                val answer = action?.asAnswer()
                when {
                    answer == null -> null
                    zone == null -> answer
                    // A zone is only accepted if it is one this play could
                    // actually take -- an id naming a full or undeclared lane
                    // is refused, not silently redirected.
                    answer is Answer.PlayCard &&
                        zone in openZonesFor(rules, p.state, p.player, action).orEmpty() ->
                        answer.copy(zoneDef = zone)
                    else -> null
                }
            }
            else -> null
        }
        is Question.PickTarget -> idOf("t")?.takeIf { it in p.candidates }?.let { Answer.Target(it) }
        is Question.PickNumber -> idOf("n")?.takeIf { it in p.min..p.max }?.let { Answer.Number(it) }
        is Question.Attackers -> when {
            id == "x-none" -> Answer.Attackers(emptyMap())
            else -> idOf("x")?.takeIf { it in p.eligible }?.let {
                // One attacker at a time, aimed at the defending player. The
                // engine takes a whole assignment, but an agent answering one
                // option at a time is the shape every other prompt has, and a
                // partial declaration is a legal one.
                Answer.Attackers(mapOf(it to CombatTarget.Player(p.state.opponentOf(p.player))))
            }
        }
        is Question.Blockers -> when {
            id == "b-none" -> Answer.Blockers(emptyMap())
            id.startsWith("b") -> {
                val parts = id.removePrefix("b").split(":")
                val b = parts.getOrNull(0)?.toIntOrNull()
                val a = parts.getOrNull(1)?.toIntOrNull()
                if (b == null || a == null || b !in p.eligibleBlockers || a !in p.attackers) null
                else Answer.Blockers(mapOf(b to a))
            }
            else -> null
        }
        is Question.CombatTgt -> when {
            id == "c-hold" -> Answer.CombatTgt(null)
            else -> idOf("c")?.takeIf { it in boardTapTargets(rules, p, p.state) }?.let {
                // The SAME mapping the board's own tap uses, so a tile and an
                // agent option mean the same thing.
                Answer.CombatTgt(tapAnswer(rules, p, it) as? CombatTarget)
            }
        }
        is Question.Redirect -> when {
            id == "r-none" -> Answer.Blocker(null)
            else -> idOf("r")?.takeIf { it in p.candidates }?.let { Answer.Blocker(it) }
        }
        is Question.PickMode -> idOf("m")?.takeIf { it in p.options.indices }?.let { Answer.Modes(listOf(it)) }
        is Question.PickCards ->
            idOf("k")?.takeIf { k -> p.candidates.any { it.instanceId == k } }?.let { Answer.Cards(listOf(it)) }
    }
}
