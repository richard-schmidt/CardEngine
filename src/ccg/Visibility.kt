package ccg

// ---------------------------------------------------------------------------
// Hidden information. The engine is the referee and sees everything; a
// PLAYER is asked with `viewFor` -- the state as that seat may see it -- so a
// question cannot carry more than its player is entitled to. One rule, here:
// the board's `ccgui.Viewpoint` and a pilot's redaction defer to it.
//
// A view keeps every COUNT, and the instance id of every card the viewer may
// read (a chosen target still resolves). A card it may not read loses its
// identity AND its id: instance ids are dealt in decklist order, so a real id
// would name the card to anyone who knows the list. What `eval` reads on
// a view is what it reads on the game: expressions see hidden zones only
// through their sizes (`HandSize`, `ZoneSize`), and a view keeps sizes. The
// RNG goes too, since it predicts the next shuffle. Not hidden: the log,
// which counts draws and never names a hidden card.
// ---------------------------------------------------------------------------

/** The `cardId` a hidden card carries in a view. Not a real id, so
 *  `rules.cards[HIDDEN_CARD]` is null and nothing can act on a masked card. */
const val HIDDEN_CARD: String = "?hidden"

/** Zones everyone may read, whoever owns them. */
val PUBLIC_ZONES: Set<String> = setOf("graveyard", "exile", "battlefield")

/** The zones this game declares public (`HiddenZoneDef.alwaysVisible`). */
fun Rules.publicZoneIds(): Set<String> = hiddenZones.values.filter { it.alwaysVisible }.map { it.id }.toSet()

/** May `viewer` read the faces in `owner`'s `zone` (`owner` null: a shared
 *  zone)? A library is face-down to EVERYONE, its owner included. */
fun zoneOpenTo(viewer: PlayerId, owner: PlayerId?, zone: String, declaredPublic: Set<String>): Boolean = when {
    zone in PUBLIC_ZONES || zone in declaredPublic -> true
    zone == "library" || zone == "library_bottom" -> false
    else -> owner == viewer
}

/** This state as `viewer` may see it: every hidden zone keeps its size, and
 *  each card the viewer may not read becomes `HIDDEN_CARD` under a fresh id
 *  (-1, -2, ... in the order met, so it collides with no real one and means
 *  nothing outside this view). The RNG state is blanked. */
fun GameState.viewFor(viewer: PlayerId, declaredPublic: Set<String>): GameState {
    fun open(owner: PlayerId, zone: String) = zoneOpenTo(viewer, owner, zone, declaredPublic)
    var masked = 0
    fun mask(refs: List<CardRef>) = refs.map { CardRef(-(++masked), HIDDEN_CARD) }
    return copy(
        rngState = 0,
        // The pending frames hold whole states: never shown.
        pending = emptyList(),
        players = players.mapValues { (owner, p) ->
            p.copy(
                library = if (open(owner, "library")) p.library else mask(p.library),
                hand = if (open(owner, "hand")) p.hand else mask(p.hand),
                graveyard = if (open(owner, "graveyard")) p.graveyard else mask(p.graveyard),
                customZones = p.customZones.mapValues { (zone, refs) -> if (open(owner, zone)) refs else mask(refs) },
            )
        },
    )
}

fun GameState.viewFor(viewer: PlayerId, rules: Rules): GameState = viewFor(viewer, rules.publicZoneIds())
