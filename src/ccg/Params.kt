package ccg

// ---------------------------------------------------------------------------
// The frame AROUND the rules: the parameters every card game sets (opening
// hand, draws, hand limit, resources per turn). Declarative data with named
// presets, like `CombatConfig` / `TurnStructure`, so a bundle holds it
// directly and it round-trips.
// ---------------------------------------------------------------------------

/** A kind of counter a permanent can carry ("shield", "hull", "charge"). A game
 *  declares its kinds so the compiler can report a name nothing declares (a
 *  misspelt read would otherwise read 0 forever).
 *
 *  `cancels` names the kind this one annihilates with: while a permanent
 *  carries both, SBA removes one of each. Declaring it on either side is
 *  enough. */
data class CounterKindDef(val name: String, val cancels: String? = null)

/** Counter kinds every game has without declaring them: the engine or a
 *  builtin type gives each a meaning. +1/+1 and -1/-1 cancel, as data --
 *  `stateBasedActions` knows no counter by name. */
val BUILTIN_COUNTER_DEFS: List<CounterKindDef> = listOf(
    CounterKindDef("+1/+1", cancels = "-1/-1"), CounterKindDef("-1/-1"),
    CounterKindDef("loyalty"), CounterKindDef("defense"), CounterKindDef("lore"),
)
val BUILTIN_COUNTER_KINDS: List<String> = BUILTIN_COUNTER_DEFS.map { it.name }

/** What an unset limit reads as through `IntExpr.Param`: large enough that
 *  "hand size minus the limit" is never positive, small enough not to
 *  overflow arithmetic on it. */
const val NO_LIMIT: Int = 1_000_000

/** The game parameters an expression may read, by name. */
val PARAM_NAMES: List<String> = listOf("startingHandSize", "cardsDrawnPerTurn", "firstPlayerSkipsFirstDraw", "maxHandSize", "playerCount")

/** `name`'s value as a number, or null when no param has that name. */
fun GameParams.param(name: String): Int? = when (name) {
    "startingHandSize" -> startingHandSize
    "cardsDrawnPerTurn" -> cardsDrawnPerTurn
    "firstPlayerSkipsFirstDraw" -> if (firstPlayerSkipsFirstDraw) 1 else 0
    "maxHandSize" -> maxHandSize ?: NO_LIMIT
    "playerCount" -> playerCount
    else -> null
}

/** A named player total. Life is one of these, not a privileged field --
 *  a game may declare "gold" and "devotion" and end at zero on either. */
data class PlayerCounterDef(
    val name: String,
    val starting: Int = 0,
    /** Reaching zero (or below) loses the game. The state-based action folds
     *  over every counter with this set. */
    val loseAtZero: Boolean = false,
    /** Clamps applied whenever the counter changes. Null = unbounded. */
    val min: Int? = null,
    val max: Int? = null,
) {
    fun clamp(v: Int): Int {
        var out = v
        if (min != null && out < min) out = min
        if (max != null && out > max) out = max
        return out
    }
}

val DEFAULT_PLAYER_COUNTERS = listOf(PlayerCounterDef(LIFE, starting = 20, loseAtZero = true))

/** How a player comes by the resources that pay costs. `Player.pool` empties
 *  at every phase boundary; this is what refills it. */
sealed interface ResourceModel {
    /** Nothing automatic -- resources come only from cards' mana abilities.
     *  This is what the engine did before, and it is still a real choice. */
    data object None : ResourceModel

    /** Hearthstone / Marvel Snap: gain `perTurn` at the start of your turn, up
     *  to `cap`, and the pool refills to that each turn. */
    data class Ramp(
        val perTurn: Int = 1,
        val cap: Int = 10,
        val refillEachTurn: Boolean = true,
        val startingAmount: Int = 0,
        /** Which resource key fills. "" is generic. */
        val key: String = "",
    ) : ResourceModel

    /** Magic: resources come from permanents, and the parameter is how many
     *  such permanents you may PLAY per turn -- the land drop. */
    data class CardDriven(
        val playsPerTurn: Int = 1,
        val types: Set<String> = setOf("Land"),
    ) : ResourceModel

    companion object {
        val MTG = CardDriven()
        val HEARTHSTONE = Ramp()
    }
}

/** Is playing a permanent of these types limited by the resource model? */
fun ResourceModel.limitsPlayOf(types: Set<String>): Boolean =
    this is ResourceModel.CardDriven && types.any { it in this.types }

/** How many redraws a mulligan allows, and whether it costs a card. */
data class MulliganRule(
    val redraws: Int = 0,
    /** London-style: after each mulligan you put `redraws taken` cards back. */
    val bottomOnePerMulligan: Boolean = true,
)

/** Which way a game wants to be held. A presentation hint, read by the board:
 *  "phone-first with leeway per game" is a parameter, not a layout guess. */
enum class Orientation { EITHER, PORTRAIT, LANDSCAPE }

/** The game-structure parameters proper. */
data class GameParams(
    val startingHandSize: Int = 7,
    val cardsDrawnPerTurn: Int = 1,
    /** The player who goes first skips their first draw step (MTG). */
    val firstPlayerSkipsFirstDraw: Boolean = true,
    val mulligan: MulliganRule = MulliganRule(),
    /** Discard down to this at cleanup. Null = no limit. */
    val maxHandSize: Int? = 7,
    val playerCount: Int = 2,
    val preferredOrientation: Orientation = Orientation.EITHER,
    /** Does `Player.pool` survive the rest of the ACTIVE player's turn once
     *  granted, instead of clearing at every phase boundary? Independent of how
     *  the pool fills: an effect-driven resource ("add {N} at the start of your
     *  turn, spend any time") needs it under `ResourceModel.None`. */
    val poolPersistsPerTurn: Boolean = false,
    /** Names the PLAYER COUNTER that caps how much of each resource key survives
     *  the round boundary -- a mana STORE. Null: the pool empties.
     *
     *  A counter name, not an Int: the cap belongs to the card the
     *  player brought (a Station seeds it via `entersWith`), so banking is a
     *  Station identity dial. Production stays flat and the top end is reached
     *  by declining to act, not by a clock. */
    val poolStoreCounter: String? = null,
    /** May a permanent act in combat on the turn it ENTERED play? False: no
     *  deployment delay. True: generalised summoning sickness -- a permanent
     *  whose `enteredOnTurn` is the current turn is skipped by every combat
     *  style's participant filter.
     *
     *  A parameter because it changes a game's shape: with no delay, board
     *  presence is bought and spent in one round. ENTERED, not cast: a permanent
     *  returned or moved keeps the turn it arrived. */
    val attackDelayOnEntry: Boolean = false,
) {
    /** Everything questionable, as sentences -- reported, not enforced, the
     *  same stance as `TurnStructure.problems()`. */
    fun problems(): List<String> = buildList {
        if (startingHandSize < 0) add("starting hand size is negative")
        if (cardsDrawnPerTurn < 0) add("cards drawn per turn is negative")
        if (playerCount < 2) add("a game needs at least two players")
        if (playerCount > 2) add("the engine plays two-player games only -- $playerCount players is not honoured")
        if (maxHandSize != null && maxHandSize < startingHandSize) {
            add("the opening hand of $startingHandSize is above the hand limit of $maxHandSize -- " +
                "the first cleanup will discard down to it")
        }
    }

    companion object {
        val MTG = GameParams()
        val HEARTHSTONE = GameParams(
            startingHandSize = 3,
            firstPlayerSkipsFirstDraw = false,
            maxHandSize = 10,
        )
    }
}
