package ccg

// ---------------------------------------------------------------------------
// Declarable turn structure. `Rules.turn` is data, `playGame` folds over it,
// and each phase's automatic work is an `Effect` (`PhaseSpec.onEnter`), so a
// `TurnStructure` round-trips through JSON and is authorable.
// `TurnStructure.MTG` is the classic loop, cleanup step included.
// ---------------------------------------------------------------------------

/** The standard phase work, written in the effect language. A phase's `onEnter`
 *  runs once for each player the phase acts for ("you" is that player); these
 *  are the three a game usually wants, with the numbers read from the game's
 *  params (`IntExpr.Param`). */
object PhaseEffects {
    /** Ready every permanent you control. (Per-turn counts -- attacks, once-a-
     *  turn abilities -- reset when the turn begins, not here.) */
    val UNTAP: Effect = Effect.ForEach(permanents().yours(), Effect.Tap(BoundTarget(EACH), untap = true))

    /** Draw the game's per-turn draw -- except the first player on the first
     *  turn, when the game says so. */
    val DRAW: Effect = Effect.If(
        not(allOf(param("firstPlayerSkipsFirstDraw") eq lit(1), IntExpr.TurnNumber eq lit(1), IntExpr.SeatOf(PlayerRef.You) eq lit(0))),
        Effect.Draw(PlayerRef.You, param("cardsDrawnPerTurn")),
    )

    /** Marked damage clears from every permanent, and you discard down to
     *  the hand limit -- choosing which (it was the last cards drawn). */
    val CLEANUP: Effect = Effect.Sequence(
        listOf(
            Effect.ForEach(permanents(), Effect.ClearDamage(BoundTarget(EACH))),
            Effect.Discard(PlayerRef.You, IntExpr.Bin(BinOp.MAX, lit(0), handSize(PlayerRef.You) - param("maxHandSize"))),
        ),
    )
}

/** One phase of a turn. `interactive` opens a priority window; `combat` runs
 *  the declared combat model (`Rules.combat`) instead of a plain priority
 *  loop. A phase may be both non-interactive and do nothing on entering (a
 *  pure marker). */
data class PhaseSpec(
    val name: String,
    val interactive: Boolean = true,
    val combat: Boolean = false,
    /** Run as the phase begins, once per player it acts for, after the mana
     *  pools empty and before the `PhaseEnter` boundary. */
    val onEnter: Effect = Effect.NoOp,
    /** The active player may take sorcery-speed actions (play a permanent, cast
     *  a non-instant) while they have priority with an empty stack in this
     *  phase. */
    val sorcerySpeed: Boolean = false,
)

/** Whose turn IS it?
 *
 *  `PER_PLAYER` -- MTG / Yu-Gi-Oh / Hearthstone: one player takes a whole turn,
 *  the other may only respond, then they swap.
 *
 *  `SHARED` -- Star Wars: Unlimited / Legends of Runeterra: ONE round both
 *  players are in. The phase list runs once, its work applies to everyone, and
 *  in an interactive phase players ALTERNATE single actions until both pass.
 *  `activePlayer` is the INITIATIVE holder: acts first, passes it at round end. */
enum class TurnMode { PER_PLAYER, SHARED }

/** A whole turn as an ordered list of phases. `playGame` iterates
 *  `Rules.turn.phases`; nothing about the phase set is baked into the engine. */
data class TurnStructure(val phases: List<PhaseSpec>, val mode: TurnMode = TurnMode.PER_PLAYER) {
    companion object {
        /** The pre-P-review hardcoded loop, verbatim + a cleanup step. */
        val MTG = TurnStructure(
            listOf(
                PhaseSpec("untap", interactive = false, onEnter = PhaseEffects.UNTAP),
                PhaseSpec("upkeep"),
                PhaseSpec("draw", onEnter = PhaseEffects.DRAW),
                PhaseSpec("main", sorcerySpeed = true),
                PhaseSpec("combat", combat = true),
                PhaseSpec("main2", sorcerySpeed = true),
                PhaseSpec("end", onEnter = PhaseEffects.CLEANUP),
            ),
        )

        /** A stripped turn -- one draw + one action window -- showing the
         *  structure is not fixed. */
        val SIMPLE = TurnStructure(
            listOf(
                PhaseSpec("start", interactive = false, onEnter = PhaseEffects.UNTAP),
                PhaseSpec("draw", onEnter = PhaseEffects.DRAW),
                PhaseSpec("play", sorcerySpeed = true),
                PhaseSpec("battle", combat = true),
                PhaseSpec("end", onEnter = PhaseEffects.CLEANUP),
            ),
        )
    }
}

// -- phase identity ---------------------------------------------------------
// `GameState.phaseIndex` -- not the phase NAME -- is the authoritative handle
// into `Rules.turn.phases`; names are author-supplied and may repeat. The two
// "no current phase" situations are DISTINCT sentinels, because they mean
// opposite things for timing:

/** Between turns, or inside a turn/phase boundary window. Timing rules APPLY:
 *  there is no sorcery-speed window here. */
const val NO_PHASE: Int = -1

/** `Engine.run()` -- the free-priority harness with no turn structure at all
 *  (engine tests). Timing rules do NOT apply; anything may be cast any time. */
const val FREE_PRIORITY: Int = -2

/** The current phase, or null at a boundary / in free-priority mode. */
fun TurnStructure.phaseAt(index: Int): PhaseSpec? = phases.getOrNull(index)

/** Does `player` have a sorcery-speed window right now? Fails CLOSED: a
 *  boundary or out-of-range `phaseIndex` is not a window, nor is a non-empty
 *  stack. Free-priority mode is the one open case (its own sentinel). The
 *  whole answer, for the engine and the UI alike. */
fun sorcerySpeedWindow(rules: Rules, state: GameState, player: PlayerId): Boolean {
    if (state.phaseIndex == FREE_PRIORITY) return true
    if (state.stack.isNotEmpty()) return false
    val ph = rules.turn.phaseAt(state.phaseIndex) ?: return false
    if (!ph.sorcerySpeed) return false
    // THE line that makes a non-active player a responder only. Under SHARED
    // whoever holds the action may take a sorcery-speed action.
    return rules.turn.mode == TurnMode.SHARED || state.activePlayer == player
}

/** Authoring problems in a turn structure -- surfaced in the Creator's Bundle
 *  tab. Empty list = well-formed. */
fun TurnStructure.problems(params: GameParams = GameParams()): List<String> = buildList {
    if (phases.isEmpty()) {
        add("no phases at all -- every turn will do nothing")
        return@buildList
    }
    if (phases.any { it.name.isBlank() }) add("a phase has a blank name")
    phases.groupBy { it.name }.filterValues { it.size > 1 }.keys.takeIf { it.isNotEmpty() }
        ?.let { add("duplicate phase name(s): ${it.joinToString()} -- triggers on that name fire in every copy") }
    if (phases.none { it.interactive || it.combat }) add("no phase opens a priority window -- nobody can ever act")
    if (phases.none { it.sorcerySpeed }) add("no sorcery-speed phase -- permanents can never be played")
    if (phases.none { it.combat }) add("no combat phase -- creatures can never attack")
    // Only a problem if the game relies on the automatic draw: a game declaring
    // `cardsDrawnPerTurn = 0` draws some other way on purpose.
    if (params.cardsDrawnPerTurn > 0 && phases.none { ph -> ph.onEnter.anyNode { it is Effect.Draw } }) {
        add("no draw phase, but the game draws " + params.cardsDrawnPerTurn + " card(s) per turn automatically")
    }
    if (phases.count { ph -> ph.onEnter.anyNode { it is Effect.ClearDamage } } > 1) add("more than one cleanup phase")
    if (phases.count { ph -> ph.onEnter.anyNode { it is Effect.Tap && it.untap } } > 1) add("more than one untap phase")
}
