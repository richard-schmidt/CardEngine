package ccg

// ---------------------------------------------------------------------------
// Play zones. "In play" is a DECLARED SET of zones, each shared (one instance)
// or per-player (one per player: arenas, lanes). A `ZoneRef` names a concrete
// instance -- `(def, owner)`, owner null exactly when the def is shared.
//
// The engine keeps ONE flat `battlefield` map with `Permanent.zone` as the
// discriminator: zone is a FILTER concern, so derivation, SBA and every fold
// work unchanged.
// ---------------------------------------------------------------------------

enum class ZoneScope { SHARED, PER_PLAYER }

/** A declared play zone. `id` is referenced by `TypeDef.zoneOfPlay`, the play
 *  action, `Effect.MovePermanent`, and zone-scoped filters. */
data class PlayZoneDef(
    val id: String,
    val scope: ZoneScope = ZoneScope.SHARED,
    /** A cap on how many permanents ONE instance of this zone may hold ("one
     *  Ship per lane"). null = unlimited. Enforced by `legality()` and every
     *  placement path; each player's instance of a PER_PLAYER zone counts
     *  separately. */
    val maxOccupants: Int? = null,
    /** Which COMBAT STEPS a permanent in this zone may act in; null = all.
     *  Lanes differentiated by rule rather than stat bonus, so position changes
     *  what a card DOES:
     *
     *      VAN   setOf("fast")           strikes early, cannot follow up
     *      CORE  setOf("fast", "slow")   acts in both waves
     *      REAR  emptySet()              never fights; pure support
     *
     *  Step NAMES, not an enum: a game's `CombatConfig` declares its steps. An
     *  unknown name never matches, which fails closed. */
    val combatSteps: Set<String>? = null,
    /** Which LANE of the grid this zone is, counting from 1. A zone declaring
     *  both `lane` and `depth` is on a two-dimensional board and combat
     *  geometry reads the numbers instead of comparing zone ids ("same lane,
     *  either berth"); a zone declaring neither is flat and unchanged. */
    val lane: Int? = null,
    /** how deep this zone sits in its lane. Front protects back, and
     *  only within the SAME lane. */
    val depth: Depth? = null,
) {
    /** Part of a grid, rather than a flat zone. Both halves or neither -- a
     *  half-declared zone would make every rule below ask "which is it". */
    val onGrid: Boolean get() = lane != null && depth != null
}

/** Where a zone sits in its lane. FRONT is the exposed berth that
 *  protects BACK; BACK is the protected one. */
enum class Depth { FRONT, BACK }

/** A concrete zone instance. `owner` is null iff the backing def is shared. */
data class ZoneRef(val def: String, val owner: PlayerId? = null) {
    override fun toString(): String = if (owner == null) def else "$def@$owner"
}

/** The single zone every pre-P5 permanent lived in; still the default. */
val BATTLEFIELD = ZoneRef("battlefield")

val BUILTIN_ZONES: Map<String, PlayZoneDef> = mapOf(
    "battlefield" to PlayZoneDef("battlefield", ZoneScope.SHARED),
)

/** A declared HIDDEN zone: a per-player pool of cards beside hand / library /
 *  graveyard / exile (e.g. Flagships, always visible to their owner, castable
 *  directly). Always per-player -- a shared hidden zone is not coherent. No
 *  builtins. */
data class HiddenZoneDef(val id: String, val alwaysVisible: Boolean = true)

/** Where a cast can be spent FROM: a built-in hidden zone or one the bundle
 *  declared. Library-relative verbs (`MoveTop`, `LookAtTop`, `Shuffle`,
 *  `SearchZone`) stay `HiddenZone`-only until a card needs more. */
sealed interface CastZone {
    data class Std(val zone: HiddenZone) : CastZone
    data class Declared(val id: String) : CastZone
}

/** How a `PermFilter` is scoped by zone. `Any` is the pre-P5 behaviour. */
sealed interface ZoneScoping {
    /** Every zone. */
    data object Any : ZoneScoping

    /** The zone [of] is in: the same INSTANCE ("units on this Planet" --
     *  your half of a lane), or with [eitherSide] the same DEF, either
     *  owner's ("every Ship in this lane", the whole line). [of] is any bound
     *  target; an unbound `SELF` is the source. Nothing there -- no
     *  source, or it left play -- matches nothing. */
    data class SameAs(val of: BoundTarget = BoundTarget(SELF), val eitherSide: Boolean = false) : ZoneScoping

    /** Any instance of a named def -- either player's copy of "planet-a". */
    data class Named(val def: String) : ZoneScoping

    /** One specific instance. */
    data class Exact(val ref: ZoneRef) : ZoneScoping
}
