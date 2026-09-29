package ccgui

import ccg.Depth
import ccg.GameState
import ccg.ObjectId
import ccg.PlayerId
import ccg.PlayZoneDef
import ccg.Rules
import ccg.ZoneRef
import ccg.ZoneScope

// ---------------------------------------------------------------------------
// Where things sit on the board, computed from what the bundle DECLARES.
// Three categories:
//
//   GRID      the contested zones (capped, per-player, or deployable by
//             choice). Index-aligned to `columns`, so the same lane is the same
//             column on both sides of the table and "opposed" is visible.
//   ANCHOR    everything else in play and unattached: a Station, a Leader.
//   ATTACHED  a permanent with a `hostId`, drawn WITH its host wherever the
//             host is. (Improvements share the uncapped zone with the anchors,
//             so "the uncapped zone is the anchor strip" would draw them far
//             from what they are bolted to.)
//
// In `src/ui`, not the Compose board, because it is a decision.
// ---------------------------------------------------------------------------

/** One cell of the grid: a slot in a contested zone plus whatever is attached to
 *  its occupant. `occupant == null` is a VISIBLE empty slot, so a lane with room
 *  looks different from a full one before you commit. */
data class BoardCell(
    val zone: ZoneRef,
    val occupant: ObjectId? = null,
    val attached: List<ObjectId> = emptyList(),
)

/** A permanent in play that nothing is opposed to -- a Station, a Leader --
 *  plus its own attachments, because Fleet Command Uplink attaches to a
 *  **Station** and a Station is an anchor rather than a grid occupant. */
data class BoardAnchorItem(
    val zone: ZoneRef,
    val id: ObjectId,
    val attached: List<ObjectId> = emptyList(),
)

/** One column of the grid. `cap == null` is uncapped: ragged, no alignment
 *  across seats, and at most ONE empty slot, drawn only when some type can
 *  deploy into it (that slot is the answer button for the zone question). A
 *  capped column has exactly `cap` cells on every row, so slot centres are
 *  computed, not measured. */
data class BoardColumn(val zone: String, val cap: Int?)

/** One player's side of the board. */
data class BoardRow(
    val owner: PlayerId,
    /** Index-aligned to `BoardLayout.columns`: `grid[c]` is this seat's cells
     *  in column `c`, and for a capped column its size is exactly that
     *  column's `cap` on every row. That alignment is the point -- it is what
     *  the old flat `slots` list could not promise. */
    val grid: List<List<BoardCell>>,
    /** Uncontested permanents, in declared zone order. */
    val anchors: List<BoardAnchorItem>,
) {
    /** The tallest attachment stack on this row, so a renderer DERIVES its row
     *  height (`tileH + attachRun * step`) instead of measuring it -- measured,
     *  asynchronous tile positions are what break arcs. */
    val attachRun: Int
        get() = maxOf(
            grid.flatten().maxOfOrNull { it.attached.size } ?: 0,
            anchors.maxOfOrNull { it.attached.size } ?: 0,
        )
}

/** The play zones ordered by DEPTH, front-most first -- or null when the zones
 *  are lateral lanes. Read from the zones' declared `depth` (a grid's, or a
 *  flat front and back), never from zone NAMES. */
fun boardRanks(rules: Rules): List<List<String>>? {
    // PER_PLAYER only. The shared `battlefield` holds the anchors -- Stations,
    // Leaders, played Doctrines -- which are drawn in their own strip and are
    // not a rank of anybody's line.
    val per = playableZones(rules).filter { it.scope == ZoneScope.PER_PLAYER }

    // a GRID draws itself. Rows are DEPTHS, front-most first; within a
    // row the lanes run left to right in their own order, and they are NOT
    // mirrored for the opponent -- lane 1 must sit above lane 1, because that
    // is the pair that actually fights.
    val grid = per.filter { it.onGrid }
    if (grid.isNotEmpty()) {
        return Depth.entries
            .map { d -> grid.filter { it.depth == d }.sortedBy { it.lane }.map { it.id } }
            .filter { it.isNotEmpty() }
            .takeIf { it.isNotEmpty() }
    }

    // Otherwise the zones that declare a DEPTH, front-most first, one zone a
    // row; a zone that declares none is drawn behind rather than dropped.
    val deep = per.filter { it.depth != null }
    if (deep.isEmpty()) return null
    val ordered = deep.sortedBy { it.depth!!.ordinal } + per.filter { it.depth == null }
    return ordered.map { listOf(it.id) }
}

/** The board. `rows` is ordered for the VIEWER: opponents first (across the
 *  table), the viewer last (nearest the thumb). */
data class BoardLayout(val columns: List<BoardColumn>, val rows: List<BoardRow>) {
    val columnIds: List<String> get() = columns.map { it.zone }

    /** Every permanent this layout actually DRAWS (grid occupants, anchors, and
     *  their attachments), so an affordance can ask "does this have a tile?".
     *  The prompt bar carries exactly the activations whose source has none. */
    fun renderedIds(): Set<ObjectId> = buildSet {
        for (row in rows) {
            for (cell in row.grid.flatten()) {
                cell.occupant?.let { add(it) }
                addAll(cell.attached)
            }
            for (anchor in row.anchors) {
                add(anchor.id)
                addAll(anchor.attached)
            }
        }
    }
}

/** The zones a permanent can sit in, sorted by id so columns line up the same
 *  way on both sides every frame. */
private fun playableZones(rules: Rules): List<PlayZoneDef> =
    rules.zones.values.sortedBy { it.id }

/** The zones some type declares as a deployment CHOICE (`TypeDef.zoneChoices`).
 *  `zoneOfPlay` does not count: it is never a question. */
private fun choosableZones(rules: Rules): Set<String> =
    rules.types.values.flatMapTo(mutableSetOf()) { it.zoneChoices }

/** A contested zone: capped, per-player, or deployable INTO BY CHOICE. The
 *  third clause matters because the zone question is answered only by tapping a
 *  lit slot, and the Creator mints zones shared and uncapped by default -- such
 *  a zone must still get a column to point at. */
private fun isGridZone(def: PlayZoneDef, choosable: Set<String>): Boolean =
    def.maxOccupants != null || def.scope == ZoneScope.PER_PLAYER || def.id in choosable

fun boardLayout(rules: Rules, state: GameState, viewer: PlayerId): BoardLayout {
    val zones = playableZones(rules)
    val choosable = choosableZones(rules)
    // A game with nothing contested still needs its permanents drawn, so the
    // fallback is "everything is a ragged column" rather than an empty grid and
    // an anchor strip holding the entire board. This is also exactly what an
    // MTG-shaped game gets: one uncapped column, no anchors, no special case.
    val contested = zones.filter { isGridZone(it, choosable) }
    val gridZones = if (contested.isEmpty()) zones else contested
    val gridIds = gridZones.map { it.id }.toSet()
    val anchorZones = zones.filterNot { it.id in gridIds }

    // Opponents across the table, the viewer nearest -- the arrangement every
    // card game on a table has, and one the old code did not have because it
    // walked `players` in map order.
    val order = state.turnOrder.filter { it != viewer } + listOf(viewer)

    // -- attachments ------------------------------------------------------
    // Drawn with the host, so they come out of the occupant lists first. SBA
    // guarantees a real state's hosts resolve; an unresolvable host (a
    // hand-built state) demotes its attachment to an ordinary occupant rather
    // than dropping it.
    val live = state.battlefield
    val bound = live.values.filter { it.hostId != null && it.hostId in live.keys }
    val attachedTo: Map<ObjectId, List<ObjectId>> =
        bound.groupBy { it.hostId!! }.mapValues { (_, ps) -> ps.map { it.id }.sorted() }
    val isAttached = bound.map { it.id }.toSet()

    val byZone: Map<ZoneRef, List<ObjectId>> = live.values
        .filterNot { it.id in isAttached }
        .groupBy { it.zone }
        .mapValues { (_, ps) -> ps.map { it.id } }

    fun refFor(def: PlayZoneDef, owner: PlayerId): ZoneRef =
        if (def.scope == ZoneScope.PER_PLAYER) ZoneRef(def.id, owner) else ZoneRef(def.id)

    fun occupantsOf(def: PlayZoneDef, owner: PlayerId, ref: ZoneRef): List<ObjectId> =
        byZone[ref].orEmpty().filter { id ->
            // A shared zone holds everyone's permanents, so a row still shows
            // only the ones this seat controls.
            def.scope == ZoneScope.PER_PLAYER || live[id]?.controller == owner
        }

    val rows = order.map { owner ->
        val grid = gridZones.map { def ->
            val ref = refFor(def, owner)
            val cells = occupantsOf(def, owner, ref)
                .map { BoardCell(ref, it, attachedTo[it].orEmpty()) }
                .toMutableList()
            // Room left over is drawn as empty slots. A capped lane draws its
            // remaining room (the room is the information). An uncapped zone
            // someone can choose draws ONE slot (the answer button). An
            // uncapped zone nobody chooses -- an MTG battlefield -- draws none.
            val cap = def.maxOccupants
            if (cap != null) {
                repeat((cap - cells.size).coerceAtLeast(0)) { cells += BoardCell(ref, null) }
            } else if (def.id in choosable) {
                cells += BoardCell(ref, null)
            }
            cells.toList()
        }

        val anchors = anchorZones.flatMap { def ->
            val ref = refFor(def, owner)
            occupantsOf(def, owner, ref).map { BoardAnchorItem(ref, it, attachedTo[it].orEmpty()) }
        }

        BoardRow(
            owner = owner,
            grid = grid,
            anchors = anchors,
        )
    }

    return BoardLayout(columns = gridZones.map { BoardColumn(it.id, it.maxOccupants) }, rows = rows)
}

/** Does this game have a board worth laying out in columns? A single uncapped
 *  shared zone is a pile, drawn as a row. A question about the game, not the
 *  layout (which always produces a grid); it decides how the grid is DRAWN. */
fun hasStructuredBoard(rules: Rules): Boolean {
    val zones = playableZones(rules)
    val choosable = choosableZones(rules)
    return zones.size > 1 || zones.any { isGridZone(it, choosable) }
}

// ---------------------------------------------------------------------------
// WHICH SHAPE THE PLAY SURFACE TAKES
// ---------------------------------------------------------------------------

/** How the play surface arranges its three bands.
 *
 *  * `STACKED`  -- board, then prompt, then hand, down the screen. Portrait.
 *  * `RAILED`   -- the same bands with the board laid out for a short wide
 *                  window: anchors BESIDE their lane row, flat tiles, the seat's
 *                  numbers on one line.
 *
 *  What it decides is "is this window short and wide enough for the other
 *  layout". Controls never float over the board, where they would hide the
 *  lane column under them. */
enum class PlaySurface { STACKED, RAILED }

/** Minimum width before a side rail is worth taking. Below this the rail eats
 *  more from the board than the prompt was costing it. */
const val RAIL_MIN_WIDTH_DP = 560

// ---------------------------------------------------------------------------
// HOW WIDE A SEAT'S ANCHOR BLOCK HAS TO BE
// ---------------------------------------------------------------------------

/** One anchor tile, in dp. */
const val ANCHOR_TILE_W_DP = 104

/** The gap between two tiles, and the block's own left/right inset. */
const val BOARD_GAP_DP = 6
const val BOARD_EDGE_DP = 4

/** The width the seat's numbers are GUARANTEED -- a floor, not a share. A
 *  `weight(1f)` child after fixed-width tiles in a Row gets zero width once the
 *  tiles fill the block, and every glyph wraps. */
const val SEAT_NUMBERS_W_DP = 78

/** How wide the anchor block must be for `anchorCount` tiles plus the numbers'
 *  floor. Derived from the count, not a constant: nothing says a seat has two
 *  anchors. */
fun anchorBlockWidthDp(anchorCount: Int): Int {
    val n = anchorCount.coerceAtLeast(0)
    val tiles = if (n == 0) 0 else ANCHOR_TILE_W_DP * n + BOARD_GAP_DP * (n - 1)
    return BOARD_EDGE_DP * 2 + tiles + SEAT_NUMBERS_W_DP
}

/** The anchor block, clamped so it never starves the lane grid; beyond the clamp
 *  tiles are clipped rather than drawn across the lanes. `gridTileWidth`'s floor
 *  is the next defence. */
fun anchorBlockWidthDp(anchorCount: Int, availableDp: Int): Int =
    minOf(anchorBlockWidthDp(anchorCount), (availableDp * 55) / 100)

/** Which arrangement a viewport of this size should use -- a decision with a
 *  rule, so it lives here and is tested. Size-based, not
 *  orientation-based: a foldable's tall narrow window is portrait-shaped
 *  whatever the sensor says. */
fun playSurfaceFor(widthDp: Int, heightDp: Int): PlaySurface =
    if (widthDp >= RAIL_MIN_WIDTH_DP && widthDp > heightDp) PlaySurface.RAILED
    else PlaySurface.STACKED

// ---------------------------------------------------------------------------
// HOW A LANE'S CARDS SHARE ITS FIXED HEIGHT
// ---------------------------------------------------------------------------

/** How much of a card a cell has room to draw. A card never scales (scaled text
 *  is unreadable); it sheds DETAIL, worst-first, so the name always survives. */
enum class CellDetail {
    /** Art box, name, type line, stat corner -- the tile as designed. */
    FULL,

    /** The art box goes first. It is the largest single consumer of a tile's
     *  height (46dp of an 88dp compact tile) and the least informative -- on a
     *  board where every card is a Ship, the glyph placeholder says nothing the
     *  name does not. */
    NO_ART,

    /** Name only. The type line goes second, for the reason the landscape tile
     *  already drops it: "Ship" under every card on a board of Ships. */
    NAME_ONLY,
}

/** A lane's cells, given its fixed box.
 *
 *  `cols`/`rows` is the packing; `cellWidthDp`/`cellHeightDp` is one cell of
 *  it. A caller chunks its cells by `cols` and lays each chunk out as a row. */
data class LaneFill(
    val cols: Int,
    val rows: Int,
    val cellWidthDp: Int,
    val cellHeightDp: Int,
    val detail: CellDetail,
)

/** Below this a cell cannot show a legible name at all, so the lane is allowed
 *  to exceed its nominal box rather than render mush. */
const val LANE_CELL_MIN_DP = 18

/** Thresholds, each "what this tier's content actually needs". FULL wants the
 *  art box (46dp) plus the two text lines and padding, and wants enough WIDTH
 *  to be worth drawing a picture in at all -- a 57dp-wide art box is a smear,
 *  which is why width gates it too. */
const val LANE_CELL_FULL_DP = 72
const val LANE_CELL_NO_ART_DP = 38
const val LANE_CELL_FULL_W_DP = 70

/** The most columns a lane splits into. TWO, hence "quadrants": a lane is a
 *  narrow box, and a third column takes every cell below the width at which a
 *  card name is worth printing. */
const val LANE_MAX_COLS = 2

/** Divide a lane's fixed box between `cellCount` cells, so the board's height
 *  does not depend on how the game develops. Three or more cells split into two
 *  columns (a lane is wider than tall: 2x2 at ~42dp beats 1x3 at 27dp); one or
 *  two stay stacked at full width. Counts CELLS: an attachment subdivides its
 *  host's cell. */
fun laneFill(
    laneWidthDp: Int,
    laneHeightDp: Int,
    cellCount: Int,
    gapDp: Int = 3,
): LaneFill {
    val n = cellCount.coerceAtLeast(1)
    val cols = if (n <= 2) 1 else LANE_MAX_COLS
    val rows = (n + cols - 1) / cols
    val w = ((laneWidthDp - gapDp * (cols - 1)) / cols).coerceAtLeast(1)
    val h = ((laneHeightDp - gapDp * (rows - 1)) / rows).coerceAtLeast(LANE_CELL_MIN_DP)
    return LaneFill(cols, rows, w, h, cellDetail(w, h))
}

/** How much of a card fits in a box this size. One definition, so a cell and an
 *  attachment stack inside a cell cannot disagree about what "too small for the
 *  art box" means. */
fun cellDetail(widthDp: Int, heightDp: Int): CellDetail = when {
    heightDp >= LANE_CELL_FULL_DP && widthDp >= LANE_CELL_FULL_W_DP -> CellDetail.FULL
    heightDp >= LANE_CELL_NO_ART_DP -> CellDetail.NO_ART
    else -> CellDetail.NAME_ONLY
}

/** A host and its attachments SHARE one cell, stacked in a single column: the
 *  adjacency is the reading ("this Ship is the tough one"). */
fun stackFill(cell: LaneFill, tiles: Int, gapDp: Int = 2): LaneFill {
    val n = tiles.coerceAtLeast(1)
    if (n == 1) return cell
    val h = ((cell.cellHeightDp - gapDp * (n - 1)) / n).coerceAtLeast(LANE_CELL_MIN_DP)
    return LaneFill(1, n, cell.cellWidthDp, h, cellDetail(cell.cellWidthDp, h))
}

/**
 * How tall one rank of berths may be so the whole board FITS without scrolling
 * (a rank below the fold cannot be played into). The berth height is the one
 * freely adjustable term. Null when even [minRank] will not fit: below a
 * readable berth, scrolling is right.
 *
 * @param available   the height the board area actually got
 * @param fixed       everything in it that is not a berth
 * @param ranks       how many rank rows the whole board draws (both seats)
 */
fun rankHeightFor(
    available: Int,
    fixed: Int,
    ranks: Int,
    minRank: Int = 52,
    maxRank: Int = 88,
): Int? {
    if (ranks <= 0) return maxRank
    val forRanks = available - fixed
    if (forRanks <= 0) return null
    val each = forRanks / ranks
    return when {
        each >= maxRank -> maxRank
        each >= minRank -> each
        else -> null
    }
}
