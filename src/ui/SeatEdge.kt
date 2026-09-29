package ccgui

import ccg.GameState
import ccg.PlayerId
import ccg.ResourceModel
import ccg.Rules

// Seat edge and board height decisions.

/** One number on a seat's edge; [zone] is set when a tap opens that zone. */
data class SeatField(
    val key: String,
    val label: String,
    val value: Int,
    val zone: String? = null,
    /** An always-visible pool: the docked seat's tap folds its lane instead. */
    val pool: Boolean = false,
) {
    val zero: Boolean get() = value == 0
}

data class SeatFields(val cards: List<SeatField>, val resources: List<SeatField>) {
    val all: List<SeatField> get() = cards + resources
}

/** The same fields in the same order for every seat, zeros included, so nothing reflows between turns. */
fun seatFields(rules: Rules, s: GameState, player: PlayerId): SeatFields {
    val p = s.players[player] ?: return SeatFields(emptyList(), emptyList())
    val cards = buildList {
        add(SeatField("hand", "hand", p.hand.size, zone = "hand"))
        add(SeatField("library", "lib", p.library.size, zone = "library"))
        add(SeatField("graveyard", "grave", p.graveyard.size, zone = "graveyard"))
        rules.hiddenZones.values.filter { it.alwaysVisible }.sortedBy { it.id }.forEach { hz ->
            add(SeatField("zone:${hz.id}", hz.id.take(5), p.customZones[hz.id].orEmpty().size, zone = hz.id, pool = true))
        }
    }
    val poolKeys = buildSet {
        add("")
        (rules.resourceModel as? ResourceModel.Ramp)?.let { add(it.key) }
        s.players.values.forEach { addAll(it.pool.keys) }
    }.sorted()
    val declared = rules.playerCounters.map { it.name }
    val extra = s.players.values.flatMap { it.counters.keys }.filter { it !in declared }.distinct().sorted()
    val resources = buildList {
        poolKeys.forEach { k -> add(SeatField("pool:$k", k.ifBlank { "mana" }, p.pool[k] ?: 0)) }
        (declared + extra).forEach { c -> add(SeatField("counter:$c", c, p.counters[c] ?: 0)) }
    }
    return SeatFields(cards, resources)
}

/** The first anchor whose type loses the game when it dies -- by type, never by name. */
fun badgeAnchor(rules: Rules, s: GameState, anchors: List<BoardAnchorItem>): BoardAnchorItem? =
    anchors.firstOrNull { a ->
        s.battlefield[a.id] != null &&
            s.characteristicsOf(a.id).types.any { t -> rules.types[t]?.loseOnDeath == true }
    }

/** The badge's damage counter, or null when its type declares none. */
fun badgeValue(rules: Rules, s: GameState, id: ccg.ObjectId): Int? {
    val perm = s.battlefield[id] ?: return null
    val counter = rules.damageCounterFor(s.characteristicsOf(id).types) ?: return null
    return perm.counters[counter] ?: 0
}

/** Fixed, so the board's height budget is exact. */
const val SEAT_EDGE_DP = 50

const val SEAT_CHIP_LINE_DP = 28

const val PORTRAIT_COMPANIONS_MAX = 1

fun companionsAsPortraits(companions: Int): Boolean = companions <= PORTRAIT_COMPANIONS_MAX

/** Main row, plus a chip line only when a badge has more companions than fit as portraits. */
fun seatEdgeDp(rules: Rules, s: GameState, anchors: List<BoardAnchorItem>): Int {
    val badge = badgeAnchor(rules, s, anchors) ?: return SEAT_EDGE_DP
    val companions = anchors.count { it != badge && s.battlefield[it.id] != null }
    return SEAT_EDGE_DP + if (companions > 0 && !companionsAsPortraits(companions)) SEAT_CHIP_LINE_DP else 0
}

/** An open, non-empty pool replaces the hand in the dock; null = the hand. */
fun dockLane(poolIds: List<String>, isOpen: (String) -> Boolean, sizeOf: (String) -> Int): String? =
    poolIds.firstOrNull { isOpen(it) && sizeOf(it) > 0 }

/** Non-rank board height, from the constants the board is drawn with plus the measured stack block. */
fun boardFixedDp(
    edges: List<Int>,
    ranksPerSeat: Int,
    stackBlockDp: Int,
    seatGap: Int = 6,
    rankPad: Int = 6,
    rankGap: Int = 2,
    frame: Int = 12,
): Int {
    val perSeatRanks = ranksPerSeat * rankPad + (ranksPerSeat - 1).coerceAtLeast(0) * rankGap
    return edges.sumOf { it + seatGap + perSeatRanks } + stackBlockDp + frame
}

/** Height the budget missed, taken from what was drawn. Grows only, skips transients, settles in two looks. */
fun overflowCorrection(current: Int, contentDp: Int, viewportDp: Int, transient: Boolean): Int =
    if (transient) current else current + (contentDp - viewportDp).coerceAtLeast(0)

/** Null [rankH] = ranks can't be readable; the board scrolls. */
data class SurfaceSplit(val rankH: Int?, val handH: Int)

/** Ranks up to [rankCap], then the hand up to [handMax], then the rest back to the ranks, so nothing is left as a gap. */
fun splitPlaySurface(
    available: Int,
    boardFixed: Int,
    ranks: Int,
    handMin: Int = 72,
    // A berth tile uses extra height; a hand tile past ~100dp does not.
    handMax: Int = 100,
    rankMin: Int = 52,
    rankCap: Int = 72,
    rankMax: Int = 112,
): SurfaceSplit {
    if (ranks <= 0) return SurfaceSplit(rankMax, (available - boardFixed).coerceIn(handMin, handMax))
    val forRanksAtMinHand = available - boardFixed - handMin
    if (forRanksAtMinHand < ranks * rankCap) {
        val each = forRanksAtMinHand / ranks
        return SurfaceSplit(if (each >= rankMin) each else null, handMin)
    }
    val hand = (available - boardFixed - ranks * rankCap).coerceIn(handMin, handMax)
    val leftover = available - boardFixed - ranks * rankCap - hand
    return SurfaceSplit((rankCap + leftover / ranks).coerceAtMost(rankMax), hand)
}

/** Words in the Player; seat ids in Playtest. */
fun priorityAsk(player: PlayerId, viewer: PlayerId?, phase: String): String = when (viewer) {
    null -> "$player: priority  (${phase.ifBlank { "—" }})"
    player -> "Your move"
    else -> "$player's move"
}
