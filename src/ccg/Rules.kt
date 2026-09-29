package ccg

// ---------------------------------------------------------------------------
// The declarative type model. A `TypeDef` says what STRUCTURE a type has: its
// fields, whether it is a spell, whether it fights, and a `diesWhen` predicate
// SBA evaluates per permanent. A card may declare `entersWith` counters
// (loyalty, lore, ...) and its own `diesWhen` (a Saga).
// ---------------------------------------------------------------------------

/** A counter placed on a permanent as it enters. `initial` is evaluated in the
 *  entering permanent's context (usually just a literal). */
data class CounterDef(val kind: String, val initial: IntExpr = lit(0))

data class TypeDef(
    val name: String,
    /** Fields this type carries. A type with no "power" field genuinely has none. */
    val fields: Set<String> = emptySet(),
    /** SBA predicate; `ctx.source` is the permanent under test. Null = never dies by SBA. */
    val diesWhen: BoolExpr? = null,
    val isSpell: Boolean = false,
    /** A card with any `instantSpeed` type may be cast whenever its controller
     *  has priority, not only in a sorcery-speed window. */
    val instantSpeed: Boolean = false,
    /** Does playing a permanent of this type USE THE STACK -- i.e. does it wait
     *  on the stack, giving opponents a window to respond, before it enters
     *  play? True for real permanent spells. A Land is a special action in
     *  MTG and enters immediately, so it declares `false`. */
    val usesStack: Boolean = true,
    /** Takes part in combat (`Rules.fightsInCombat`). */
    val attacks: Boolean = false,
    /** The play-zone def a permanent of this type enters when the play action
     *  names none. null -> "battlefield". Resolved to a concrete instance
     *  via the controller for a per-player def. */
    val zoneOfPlay: String? = null,
    /** Combat damage to a permanent of this type is applied as removal of this
     *  counter kind, not as marked damage. null -> marked
     *  damage. Builtins: Planeswalker -> "loyalty", Battle -> "defense". */
    val damageCounter: String? = null,
    /** How a card of this type is DRAWN. Purely a render hint --
     *  the engine never reads it. Null -> the default card tile. */
    val layout: CardLayout? = null,
    /** A permanent of this type leaving play by ANY path (dies, exiled, bounced,
     *  sacrificed) ends the game for its controller -- the Station / Base / Hero
     *  pattern. */
    val loseOnDeath: Boolean = false,
    /** The SET of play-zone defs a permanent of this type may enter when the
     *  player picks ("3 lanes, pick one"). Empty: `zoneOfPlay` (or
     *  "battlefield") is the only destination. Use `zoneOfPlay` for "always
     *  here", this for "one of these". */
    val zoneChoices: List<String> = emptyList(),
)

/** Where the art sits on a card tile. */
enum class ArtSlot { TOP, LEFT, NONE }

/** Which corner (if any) the stat box occupies. */
enum class StatCorner { BOTTOM_RIGHT, BOTTOM_LEFT, NONE }

/** A counter visualisation running along one edge. */
enum class CounterTrack { NONE, LEFT_EDGE, BOTTOM_STRIP }

/** The graphical structure of a card of a given type. A curated slot
 *  set with preset positions -- not a free canvas. Render-only; JSON on
 *  `TypeDef`, elided when the whole thing equals the default. */
data class CardLayout(
    val art: ArtSlot = ArtSlot.TOP,
    val statCorner: StatCorner = StatCorner.BOTTOM_RIGHT,
    /** Which fields the stat box shows, "/"-joined (derived, not printed). */
    val statFields: List<String> = listOf("power", "toughness"),
    val counterTrack: CounterTrack = CounterTrack.NONE,
    /** Which counter kind the track visualises; null = every counter. */
    val counterKind: String? = null,
    val showText: Boolean = true,
    /** Optional accent for the type line / frame, as `#rrggbb`; null = default. */
    val accent: String? = null,
)

/** The stats a card of these types has, as DECLARED by its types -- the one
 *  definition the card editor, validators and a port all read. */
fun Rules.fieldsForTypes(types: Set<String>): List<String> =
    types.flatMap { typeOf(it).fields }.distinct()

/** Same question, before a full `Rules` build -- the Creator asks it per frame
 *  while editing, where building a ruleset would be both slow and able to
 *  throw. Mirrors `RulesDoc.layoutFor`, for the same reason. */
fun RulesDoc.fieldsForTypes(types: Set<String>): List<String> {
    val all = BUILTIN_TYPES + extraTypes.associateBy { it.name }
    return types.flatMap { all[it]?.fields.orEmpty() }.distinct()
}

val BUILTIN_TYPES: Map<String, TypeDef> = listOf(
    TypeDef(
        "Creature",
        fields = setOf("power", "toughness"),
        attacks = true,
        diesWhen = selfField("toughness") lte selfDamage,
    ),
    TypeDef("Instant", isSpell = true, instantSpeed = true),
    TypeDef("Sorcery", isSpell = true),
    TypeDef("Land", usesStack = false, layout = CardLayout(art = ArtSlot.NONE, statCorner = StatCorner.NONE)),
    TypeDef("Artifact"),
    TypeDef("Enchantment"),
    // loyalty / defense are counters; the card supplies the starting amount.
    TypeDef(
        "Planeswalker", diesWhen = selfCounter("loyalty") lte lit(0), damageCounter = "loyalty",
        layout = CardLayout(
            art = ArtSlot.LEFT, statCorner = StatCorner.NONE,
            counterTrack = CounterTrack.LEFT_EDGE, counterKind = "loyalty", accent = "#D9A441",
        ),
    ),
    TypeDef(
        "Battle", diesWhen = selfCounter("defense") lte lit(0), damageCounter = "defense",
        layout = CardLayout(
            statFields = listOf("defense"),
            counterTrack = CounterTrack.BOTTOM_STRIP, counterKind = "defense", accent = "#F2555A",
        ),
    ),
    // Saga's diesWhen is per-card (lore >= final chapter) -- see The Bloomcycle.
    TypeDef(
        "Saga",
        layout = CardLayout(
            art = ArtSlot.LEFT, statCorner = StatCorner.NONE,
            counterTrack = CounterTrack.LEFT_EDGE, counterKind = "lore", accent = "#9B7BF0",
        ),
    ),
).associateBy { it.name }

/** [BUILTIN_TYPES] as the interpreter runs them: authored above in the
 *  surface language, lowered once here (see Lower.kt). */
val BUILTIN_TYPES_CORE: Map<String, TypeDef> = BUILTIN_TYPES.mapValues { it.value.lowered(null) }

class Rules(
    val types: Map<String, TypeDef> = BUILTIN_TYPES_CORE,
    /** The play zones this bundle declares. Always includes "battlefield". */
    val zones: Map<String, PlayZoneDef> = BUILTIN_ZONES,
    /** The game's combat, as programs. Defaults to the library's `mtg`. */
    val combat: Combat = COMBAT_PRESETS.getValue("mtg"),
    /** The turn structure `playGame` folds over. */
    val turn: TurnStructure = TurnStructure.MTG,
    /** cardId -> definition -- lets `ReturnFromDiscard(toBattlefield)`
     *  and other zone->battlefield effects materialise a `CardRef`. A
     *  `BundleDoc`'s cards populate this. */
    val cards: Map<String, CardDefinition> = emptyMap(),
    /** The game-structure parameters: opening hand, draw, hand
     *  limit, player count, orientation hint. */
    val params: GameParams = GameParams(),
    /** How resources are acquired. `None` is what the engine did before. */
    val resourceModel: ResourceModel = ResourceModel.None,
    /** The player totals this game declares. Life is just the usual one. */
    val playerCounters: List<PlayerCounterDef> = DEFAULT_PLAYER_COUNTERS,
    /** Declared custom hidden zones -- e.g. EPR Skirmish's Flagship
     *  pool. No builtins, unlike `zones` -- most games need none at all. */
    val hiddenZones: Map<String, HiddenZoneDef> = emptyMap(),
    /** Which player counter "damage to a player" hits (life is not
     *  privileged).
     *
     *  NULL: players take no damage -- the loss condition lives on the board.
     *  Player damage is then a no-op, `payLife` cannot be paid, `LifeOf` reads
     *  0, and the compiler reports any card relying on them (`no-player-damage`). */
    val damageCounter: String? = LIFE,
    /** The counter kinds permanents may carry: the builtins, then the game's
     *  own. Read for `cancels`. */
    val counterKinds: List<CounterKindDef> = BUILTIN_COUNTER_DEFS,
) {
    /** Each pair of counter kinds that annihilate, once, in declaration order.
     *  A kind cancelling itself is dropped (the compiler reports it). */
    val cancellations: List<Pair<String, String>> =
        counterKinds.mapNotNull { d -> d.cancels?.takeIf { it != d.name }?.let { d.name to it } }
            .distinctBy { setOf(it.first, it.second) }

    /** The game's combat, as a program in the effect language -- what
     *  a combat phase runs. Null while its style is not yet in the language
     *  (`CombatResolver.runCombat` then). */
    val combatProgram: Effect? get() = combat.program

    /** What one `PriorityAction.Attack` runs (INDIVIDUAL combat), or
     *  null in a game where attacks are declared some other way. */
    val attackProgram: Effect? get() = combat.attack

    /** How attacks reach here, read from those programs. */
    val reach: Reach? = reachOf(combat.program, combat.attack)

    /** Starting values for a new player, from the declared counters. */
    fun startingCounters(): Map<String, Int> = playerCounters.associate { it.name to it.starting }

    /** Apply a counter's declared min/max. */
    fun clampCounter(name: String, v: Int): Int =
        playerCounters.firstOrNull { it.name == name }?.clamp(v) ?: v

    fun typeOf(name: String): TypeDef = types[name] ?: TypeDef(name)

    /** The card-tile layout for these types: the first type that declares one,
     *  else the default (render-only). */
    fun layoutFor(types: Set<String>): CardLayout =
        declared(types).firstNotNullOfOrNull { this.types[it]?.layout } ?: CardLayout()

    /** A card castable at instant speed -- any of its types is `instantSpeed`
     *. */
    fun instantSpeed(types: Set<String>): Boolean = types.any { typeOf(it).instantSpeed }

    /** Does playing this permanent go through the stack (so opponents get a
     *  window before it enters play)? False if ANY of its types opts out --
     *  a Land is played, not cast. */
    fun usesStack(types: Set<String>): Boolean = types.none { !typeOf(it).usesStack }

    /** Every `diesWhen` in force for a permanent: its card override, then each
     *  declared type's. The permanent dies if ANY holds. */
    fun diesPredicates(perm: Permanent): List<BoolExpr> =
        listOfNotNull(perm.diesWhen) + perm.base.types.mapNotNull { types[it]?.diesWhen }

    /** Does a permanent with these types take part in combat? THE definition,
     *  public because the UI needs the same answer (a hard-coded "Creature" copy
     *  there once hid every Ship as a combat target). */
    fun fightsInCombat(types: Set<String>): Boolean = types.any { typeOf(it).attacks }

    /** WHO declares attackers this combat, stated as a logged, tested rule.
     *  `PER_PLAYER`: the active player. `SHARED`: the initiative holder. Not an
     *  enum with an unimplemented `EACH_PLAYER` case; when a game wants both
     *  sides declaring, this is where it changes. FREE combat never asks. */
    fun declaresAttackers(state: GameState): PlayerId = state.activePlayer

    /** How that rule reads in the log, so the choice is visible in play. */
    fun declarerNote(): String =
        if (turn.mode == TurnMode.SHARED) "initiative holder" else "active player"

    /** Resolve a zone def id to a concrete instance for `controller`: a
     *  per-player def gets that player's copy; anything else (including an
     *  unknown def) is shared. Player actions are validated by `legality()`
     *  first; content paths are checked by `GameDoc.problems()`. */
    fun resolveZone(def: String, controller: PlayerId): ZoneRef =
        if (zones[def]?.scope == ZoneScope.PER_PLAYER) ZoneRef(def, controller) else ZoneRef(def)

    /** Does `controller` have room for one more of THEIR OWN permanents in zone
     *  `def` (`PlayZoneDef.maxOccupants`)? No cap = always true. Counted per
     *  controller, not by total headcount, which is how board caps work in real
     *  games ("7 of my minions") and keeps a foreign permanent in your zone off
     *  your cap. */
    fun zoneHasRoom(state: GameState, controller: PlayerId, def: String): Boolean =
        zoneHasRoom(state, controller, resolveZone(def, controller))

    /** The same check against a concrete instance (`MovePermanent` names a
     *  `ZoneRef` directly, possibly another player's). */
    fun zoneHasRoom(state: GameState, controller: PlayerId, zone: ZoneRef): Boolean {
        val cap = zones[zone.def]?.maxOccupants ?: return true
        return state.battlefield.values.count { it.zone == zone && it.controller == controller } < cap
    }

    /** WHERE a permanent of these types goes: `requested` if given (legality
     *  validated it), else the default, unless that instance is full -- then the
     *  first declared choice with room. The one place this decision lives, for
     *  both `playPermanent` and `CreateToken`. */
    fun openZoneFor(state: GameState, controller: PlayerId, types: Set<String>, requested: String? = null): String {
        if (requested != null) return requested
        val default = defaultZoneDef(types)
        if (zoneHasRoom(state, controller, default)) return default
        return (legalZonesFor(types) ?: setOf(default))
            .firstOrNull { zoneHasRoom(state, controller, it) } ?: default
    }

    /** The SET of play-zone defs a permanent of these types may legally enter,
     *  or null for UNRESTRICTED. Declaring `zoneOfPlay` or `zoneChoices` opts a
     *  type into restriction (`zoneOfPlay` acts as a choice of one); a type
     *  declaring neither may be played into any zone explicitly named. */
    fun legalZonesFor(types: Set<String>): Set<String>? {
        val restricting = types.mapNotNull { this.types[it] }.filter { it.zoneChoices.isNotEmpty() || it.zoneOfPlay != null }
        if (restricting.isEmpty()) return null
        return restricting.flatMap { it.zoneChoices + listOfNotNull(it.zoneOfPlay) }.toSet()
    }

    /** The zone def a card enters when the play names none: the first type's
     *  `zoneOfPlay`, else the first type's first `zoneChoices`, else
     *  "battlefield". */
    fun defaultZoneDef(types: Set<String>): String =
        declared(types).firstNotNullOfOrNull { this.types[it]?.zoneOfPlay }
            ?: declared(types).firstNotNullOfOrNull { this.types[it]?.zoneChoices?.firstOrNull() }
            ?: "battlefield"

    /** Is this card CAST (a spell) rather than PLAYED (a permanent)? True only
     *  when EVERY type is a spell type -- a Creature+Artifact card is played.
     *  The single answer; `ccgui.isSpellCard` delegates here. */
    fun isSpell(types: Set<String>): Boolean =
        types.isNotEmpty() && types.all { this.types[it]?.isSpell == true }

    /** The counter kind combat damage to a permanent of these types removes
     *, or null for marked damage. */
    fun damageCounterFor(types: Set<String>): String? =
        declared(types).firstNotNullOfOrNull { this.types[it]?.damageCounter }

    /** A card's types in the order THE GAME declares them, then any it does
     *  not declare by name. Where two of a card's types disagree (a zone, a
     *  layout, a damage counter), the first declared wins. It was the card's
     *  own set order, which is whatever order the set was built in. */
    fun declared(types: Set<String>): List<String> =
        this.types.keys.filter { it in types } + types.filter { it !in this.types }.sorted()

    companion object {
        val DEFAULT = Rules()
    }
}

/** The card `perm` came from, through the card table. Null for a token
 *  made from bare characteristics, and for a key these rules don't define. */
fun Rules.cardOf(perm: Permanent): CardDefinition? = perm.cardId?.let { cards[it] }

/** The face `perm` shows, from its card. */
fun Rules.faceOf(perm: Permanent): Face? = cardOf(perm)?.let { it.faces.getOrElse(perm.face) { _ -> it.faces[0] } }

/** Does the card `ref` points at match `filter`? An unknown cardId (an opaque
 *  filler token, or a card the bundle no longer defines) matches only the
 *  empty filter -- a tutor cannot fetch what it cannot describe. */
fun Rules.cardMatches(filter: CardFilter, ref: CardRef): Boolean {
    val def = cards[ref.cardId]
    val face = def?.faces?.firstOrNull()
    if (filter.nameIs != null && !filter.nameIs.equals(face?.name ?: ref.cardId, ignoreCase = true)) return false
    if (filter.nameContains != null &&
        !(face?.name ?: ref.cardId).contains(filter.nameContains, ignoreCase = true)
    ) return false
    if (filter.types.isNotEmpty() && (face == null || !face.types.containsAll(filter.types))) return false
    if (filter.maxManaValue != null || filter.minManaValue != null) {
        val mv = def?.cost?.manaValue() ?: return false
        if (filter.maxManaValue != null && mv > filter.maxManaValue) return false
        if (filter.minManaValue != null && mv < filter.minManaValue) return false
    }
    return true
}
