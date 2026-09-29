package ccg

// Test fixtures. The shipped combat presets are `content/combat/presets.json`;
// these are the `CombatConfig` surface forms they were first generated from,
// kept because tests build rules from them and vary them with `copy()`.
// `ArchReviewTest` checks each one still lowers to its library entry.

/** The bundled games, by their test names. They are the JSON in `content/`. */
val EPR_SKIRMISH: GameDoc by lazy { Bundled.game("epr-skirmish") }
val CORE_BUNDLE: GameDoc by lazy { Bundled.game("core-bundle") }
val SAMPLE_GAMES: List<GameDoc> get() = Bundled.samples

/** Config A. MTG: declared attackers/blockers, a first-strike then regular
 *  sub-phase, both reading `power`; double strike acts in both. */
val MTG_COMBAT = CombatConfig(
    CombatStyle.DECLARED,
    listOf(
        CombatStep("first-strike", "power", requiresKeyword = "first strike", exemptKeyword = "double strike"),
        CombatStep("regular", "power", excludesKeyword = "first strike", exemptKeyword = "double strike"),
    ),
)

/** Config B. Fast then slow, each a power-like field a creature opts into;
 *  free targeting, simultaneous, with a response window. */
val FAST_SLOW_COMBAT = CombatConfig(
    CombatStyle.FREE,
    listOf(
        CombatStep("fast", "fast", responseWindow = true),
        CombatStep("slow", "slow", responseWindow = true),
    ),
)

/** Config B with lanes: the same fast/slow arena, but a
 *  player is only a legal target while the attacker's own lane is unopposed
 *  ("ships in a row without a directly opposing ship can target the
 *  station"). Only meaningful alongside a per-player `PlayZoneDef` the bundle
 *  itself declares (e.g. "lane") -- see `GameState.laneOpposed`. */
val FAST_SLOW_LANES_COMBAT = FAST_SLOW_COMBAT.copy(laneLockedPlayerTargets = true)

/** Config B with lanes that decide OPPONENTS as well as Station access (the
 *  Core rebuild). Ship-vs-ship is locked to the confrontation line, and
 *  "reach" buys the exemption -- which makes cross-lane targeting a costed card
 *  property, reusing the axis measured to matter (an answer suite with reach
 *  beat the shipped single-target suite 73% to 55%). */
val FAST_SLOW_LANES_LOCKED_COMBAT = FAST_SLOW_LANES_COMBAT.copy(
    laneLockedBoardTargets = true,
    crossLaneKeyword = "reach",
)

/** The Core's combat: lanes that decide opponents AND a real cost for tapping,
 *  so "exhaust to move between lanes" trades this round's attack for position. */
val FAST_SLOW_LANES_CORE_COMBAT = FAST_SLOW_LANES_LOCKED_COMBAT.copy(exhaustedCannotAct = true)

/** The lane-locked Core arena with combat collapsed to ONE step reading a single
 *  `strike` field. An experiment preset (no bundle uses it); `strike` rather
 *  than `fast`/`slow` so a single step does not zero cards carrying only one. */
val SINGLE_STEP_LANES_CORE_COMBAT = FAST_SLOW_LANES_CORE_COMBAT.copy(
    steps = listOf(CombatStep("strike", "strike", responseWindow = true)),
)

/** The FRONT/BACK experiment with the artillery screen: one step, lanes decide
 *  whom, `reach` crosses them -- but a back line is unreachable while its owner
 *  still holds the front. */
val SCREENED_SINGLE_STEP_COMBAT = SINGLE_STEP_LANES_CORE_COMBAT.copy(
    screen = ScreenRule(screened = "back", by = "front"),
)

/** FRONT/BACK: one step, lanes decide whom, a back line screened by the front,
 *  plus overflow onto the Station and long range over the line. Both answer one
 *  problem: a single cheap forward body must not buy total immunity. */
val FRONT_BACK_COMBAT = SCREENED_SINGLE_STEP_COMBAT.copy(
    longRangeHitsFace = true,
    overflowToController = true,
)

/** FRONT/BACK in full: one wave, lanes
 *  decide whom, a back line screened by the front, damage overflowing onto the
 *  Station, long range shooting over the line -- and the two stats read by
 *  RANGE, so `sr` is what a body hits its own line with and `lr` what it
 *  reaches across with. */
val FRONT_BACK_CORE_COMBAT = FRONT_BACK_COMBAT.copy(
    rangeFields = RangeFields(close = "sr", far = "lr"),
)

/**
 * The Core's combat: a grid of lanes and berths. Short range fights its own lane,
 * long range every lane, a berth is covered by the front berth of its own lane,
 * and a Station is shot through a completely empty lane.
 *
 * `screen` and `longRangeHitsFace` are unset on purpose: on a grid both rules are
 * structural, and a flag the grid path never reads is a dead flag. `reach` still
 * opens a covered berth, and only that.
 */
val LANE_GRID_CORE_COMBAT = SINGLE_STEP_LANES_CORE_COMBAT.copy(
    rangeFields = RangeFields(close = "sr", far = "lr"),
    attacksPerRange = true,
    overflowToController = true,
)

/** Hearthstone: one attack at a time, attacker & defender exchange `power`
 *  simultaneously, no window; taunt forces the target, stealth hides it,
 *  windfury (attacks-per-turn) handled by the engine. */
val HEARTHSTONE_COMBAT = CombatConfig(
    CombatStyle.INDIVIDUAL,
    listOf(CombatStep("strike", "power")),
    mustTargetKeyword = "taunt",
    cantTargetKeyword = "stealth",
)

/** One Piece: one attack at a time vs a rested character or the Leader/player;
 *  attacker power >= defender power KOs it, no return damage, no excess. */
val ONE_PIECE_COMBAT = CombatConfig(
    CombatStyle.INDIVIDUAL,
    listOf(
        CombatStep(
            "clash", "power", damageModel = DamageModel.COMPARE,
            defenderField = "power", returnDamage = false, onTie = TieResult.ATTACKER_WINS,
        ),
    ),
    onlyExhaustedTargets = true,
    blockerKeyword = "blocker",
    playerHitAmount = 1,
)

/** Yu-Gi-Oh: one attack at a time; compare ATK; loser destroyed, the ATK
 *  difference to the loser's controller; a `defense`-mode monster uses its
 *  `defense` field, can't be destroyed by a weaker attacker, and deals no
 *  excess unless the attacker has piercing. */
val YUGIOH_COMBAT = CombatConfig(
    CombatStyle.INDIVIDUAL,
    listOf(
        CombatStep(
            "battle", "power", damageModel = DamageModel.COMPARE,
            defenderField = "power", returnDamage = true, excessToController = true,
            onTie = TieResult.BOTH_DESTROYED,
        ),
    ),
    stances = listOf(StanceDef("defense", defendsWith = "defense")),
)
