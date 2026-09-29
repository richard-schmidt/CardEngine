package ccg

// ---------------------------------------------------------------------------
// The Creator authoring model: an IMMUTABLE tree the UI edits by `copy(...)`.
// Not a parallel AST -- wherever the engine has a declarative type (`Effect`,
// `IntExpr`/`BoolExpr`, `CharOp`, `StaticSpec`, `RuleMod`, `CounterDef`,
// `TypeDef`, `PlayZoneDef`, `CombatConfig`) the doc holds it directly, and
// `TriggerDoc` / `ReplacementDoc` are carried into play as-is.
//
// `CardDoc.build()` -> `CardDefinition`; `GameDoc.compile()` -> `Rules` plus
// its diagnostics (Compile.kt).
// ---------------------------------------------------------------------------

// The variable names (`SELF`, `CHOSEN`, `EACH`, ...) live in Ast.kt; the
// substitution that binds them is Walk.kt.

/**
 * Which RECTANGLE of a picture a tile shows -- x/y/w/h as fractions of the
 * image, top-left origin. A crop, so the whole file is the canvas; fractions so
 * one rect means the same picture at any tile size.
 */
data class ArtRect(
    val x: Float = 0f,
    val y: Float = 0f,
    val w: Float = 1f,
    val h: Float = 1f,
) {
    /** The whole picture. Stored as nothing rather than as a field. */
    val isWhole: Boolean get() = x == 0f && y == 0f && w == 1f && h == 1f
}

/** One authored face. `fields` empty AND `keywords` empty -> no `baseChars`
 *  (a spell / a fieldless permanent). */
data class FaceDoc(
    val name: String,
    val types: Set<String>,
    val fields: Map<String, Int> = emptyMap(),
    val keywords: Set<String> = emptySet(),
    val castEffect: Effect? = null,
    val statics: List<StaticSpec> = emptyList(),
    val ruleMods: List<RuleMod> = emptyList(),
    val costMods: List<CostMod> = emptyList(),
    val triggers: List<TriggerDoc> = emptyList(),
    val activated: List<ActivatedAbility> = emptyList(),
    /** Card art: a relative filename under `games/<id>/art/`. Purely
     *  authoring-side -- `build()` drops it, the engine's `Face` never sees it.
     *  Null = no art (a glyph is drawn instead). */
    val art: String? = null,
    /** which rectangle of [art] the FIELD tile shows. Null = a centred
     *  crop of the tile's own shape, which is what a picture with no crop
     *  chosen for it has always looked like. */
    val artFieldRect: ArtRect? = null,
    /** Declarative replacement effects -- "if X would happen, do Y instead".
     *  See `ReplacementDoc`; added LAST because a mid-list parameter compiles
     *  in the core and breaks only at APK build four minutes later. */
    val replacements: List<ReplacementDoc> = emptyList(),
    /** which rectangle the SHRUNK tile shows (hand, docked lanes, the
     *  card list). Null = follow [artFieldRect], so a card carries a second
     *  crop only when its thumbnail was actually wanted to differ. Added last:
     *  a mid-list parameter compiles in the core and breaks at APK build. */
    val artMiniRect: ArtRect? = null,
) {
    fun setField(key: String, v: Int): FaceDoc = copy(fields = fields + (key to v))
    fun clearField(key: String): FaceDoc = copy(fields = fields - key)
    fun toggleKeyword(kw: String): FaceDoc =
        copy(keywords = if (kw in keywords) keywords - kw else keywords + kw)
}

/** A whole card. `faces` has one entry for the common case, two for an MDFC /
 *  transforming card. */
data class CardDoc(
    val faces: List<FaceDoc> = listOf(FaceDoc("New Card", setOf("Creature"), mapOf("power" to 1, "toughness" to 1))),
    val entersWith: List<CounterDef> = emptyList(),
    val diesWhen: BoolExpr? = null,
    val text: String = "",
    val cost: Cost = Cost(),
    /** Flashback and friends -- a second castable zone. */
    val recast: Recast? = null,
    /** Stable identity: `Rules.cards` keys on it once minted, so a rename never
     *  dangles a deck entry. `""` = not minted yet (`GameStore` mints and
     *  rewrites references on first load/save via `withCardIds`). Not a random
     *  default, so two `CardDoc()` values stay equal. */
    val id: String = "",
    /** "You may only play this while a permanent matching this exists" (an
     *  Improvement with no legal host would just be wasted). Data rather than
     *  inferred from triggers, so `legality()` can read it. Null = none. */
    val requires: PermFilter? = null,
) {
    fun face(i: Int): FaceDoc = faces.getOrElse(i) { faces[0] }
    fun updateFace(i: Int, f: (FaceDoc) -> FaceDoc): CardDoc =
        copy(faces = faces.mapIndexed { j, x -> if (j == i) f(x) else x })
    fun addFace(fd: FaceDoc): CardDoc = copy(faces = faces + fd)
    fun removeFace(i: Int): CardDoc = if (faces.size <= 1) this else copy(faces = faces.filterIndexed { j, _ -> j != i })

    /** The identifier `Rules.cards`, `DeckEntry.cardName` and `DeckDoc.slots`
     *  all agree on: the minted id, else the display name. Every writer and
     *  reader of a card reference goes through this. */
    fun key(): String = id.ifEmpty { faces.firstOrNull()?.name ?: "" }
}

/** A replacement effect -- "if X would happen, do Y instead".
 *
 *  [Replace] is the one form the engine runs: a pattern, and an effect
 *  run INSTEAD of the event it matches. The other kinds are SURFACE shapes of
 *  it, lowered by `compile()` (Lower.kt). `instead` runs inside `applyEvent`,
 *  which is synchronous, so it may not ask anyone anything; the compiler
 *  reports a verb that would. */
sealed interface ReplacementDoc {
    /** "If an event matching [pattern] would happen, do [instead] instead."
     *  `instead` reads the event as a trigger does -- `TRIGGER` the permanent
     *  it is about, `EventAmount` its amount -- and `SELF` is this
     *  replacement's source. `Effect.Proceed` lets the event happen after all,
     *  changed. The patterns a replacement can match: `Damaged`,
     *  `PlayerDamaged`, `Dies`. */
    data class Replace(val pattern: EventPattern, val instead: Effect) : ReplacementDoc

    /** "If damage would be dealt to a permanent matching `filter`, prevent it
     *  and SACRIFICE the permanent this is on instead" -- the shield totem.
     *  `onlyStep` scopes it to one combat wave ("fast" / "slow"). */
    data class DamageToSacrificeSelf(val filter: PermFilter, val onlyStep: String? = null) : ReplacementDoc

    /** "If damage would be dealt to a permanent matching `filter`, prevent it."
     *  Unlike a `PreventDamage` shield this is not a pool and is not spent -- it
     *  lasts as long as the permanent carrying it. */
    data class PreventDamageTo(val filter: PermFilter, val onlyStep: String? = null) : ReplacementDoc

    /** "If damage would be dealt to a permanent matching `filter` that holds at
     *  least one `counter`, prevent it and SPEND ONE" -- a shield that eats one
     *  hit of any size. Per instance and counter-backed, so the same counter can
     *  be a defensive charge and a `Cost.payFrom` currency. Fires `CounterSpent`
     *  so "whenever I lose a shield" triggers still see it. */
    data class DamageToRemoveCounter(
        val filter: PermFilter,
        val counter: String,
        val onlyStep: String? = null,
    ) : ReplacementDoc

    /** "If a permanent matching `filter` would die, exile it instead." It still
     *  leaves play (leave-triggers fire) but never reaches the graveyard, so no
     *  death trigger fires. */
    data class DeathToExile(val filter: PermFilter) : ReplacementDoc
}

/** A declarative trigger. `effect` may use `BoundTarget(SELF)`; `build()`
 *  rebinds it to the real permanent id. `order` follows the engine's rule:
 *  lower resolves first (Saga chapter = K). */
sealed interface TriggerDoc {
    val effect: Effect
    val order: Int

    /** when this permanent enters play. */
    data class SelfEnters(override val effect: Effect, override val order: Int = 0) : TriggerDoc
    /** when this permanent leaves play (any destination). */
    data class SelfLeaves(override val effect: Effect, override val order: Int = 0) : TriggerDoc
    /** when this permanent is declared an attacker. */
    data class SelfAttacks(override val effect: Effect, override val order: Int = 0) : TriggerDoc
    /** whenever you cast a spell of one of `types`. */
    data class YouCastType(val types: Set<String>, override val effect: Effect, override val order: Int = 0) : TriggerDoc
    /** at the start of your `phase`. */
    data class OnYourPhase(val phase: String, override val effect: Effect, override val order: Int = 0) : TriggerDoc
    /** when a counter of `kind` on this permanent crosses `k`. Upward is the
     *  Saga chapter ("when the 3rd lore counter is added"); DOWNWARD is
     *  "when the last counter leaves", which no direction flag meant
     *  could not be said at all. */
    data class CounterThreshold(
        val kind: String, val k: Int, override val effect: Effect, override val order: Int = 0,
        val downward: Boolean = false,
    ) : TriggerDoc

    /** whenever THIS permanent deals damage. `combatOnly` distinguishes combat
     *  damage from a spell's -- the events carry the flag now. */
    data class SelfDealsDamage(
        val combatOnly: Boolean = false, override val effect: Effect, override val order: Int = 0,
    ) : TriggerDoc
    /** whenever a creature dies anywhere (optionally only an opponent's / yours). */
    data class CreatureDies(
        val whose: PlayerRef? = null, override val effect: Effect, override val order: Int = 0,
        /** WHICH types dying count (default Creature). Trailing, so positional
         *  constructions still compile. */
        val types: Set<String> = setOf("Creature"),
    ) : TriggerDoc

    /** Any `EventPattern` -- the general form, for the events the named kinds
     *  above do not cover ("whenever a creature you control is dealt damage",
     *  "whenever another permanent enters"). The named kinds stay for their
     *  JSON and their editors; each is exactly one pattern (`pattern`). */
    data class On(val on: EventPattern, override val effect: Effect, override val order: Int = 0) : TriggerDoc
}

/** The one event matcher this trigger uses. */
val TriggerDoc.pattern: EventPattern
    get() = when (this) {
        is TriggerDoc.SelfEnters -> EventPattern.SelfEnters
        is TriggerDoc.SelfLeaves -> EventPattern.SelfLeaves
        is TriggerDoc.SelfAttacks -> EventPattern.SelfAttacks
        is TriggerDoc.YouCastType -> EventPattern.Cast(types, PlayerRef.You)
        is TriggerDoc.OnYourPhase -> EventPattern.OnPhase(phase, PlayerRef.You)
        is TriggerDoc.CounterThreshold -> EventPattern.CounterCrosses(kind, k, downward)
        is TriggerDoc.SelfDealsDamage -> EventPattern.SelfDealsDamage(combatOnly)
        is TriggerDoc.CreatureDies -> EventPattern.Dies(types, whose)
        is TriggerDoc.On -> on
    }

// -- compile: doc -> engine --------------------------------------------------

/** The engine's card, in CORE forms only (see Lower.kt). `damageCounter` is
 *  the game's, for `LifeOf`: null means its players take no damage. */
internal fun CardDoc.build(damageCounter: String? = null, params: GameParams? = null): CardDefinition = CardDefinition(
    faces = faces.map { it.build(damageCounter, params) },
    entersWith = entersWith.map { it.copy(initial = it.initial.lowered(damageCounter, params)) },
    diesWhen = diesWhen?.lowered(damageCounter, params),
    text = text,
    cost = cost.lowered(damageCounter, params),
    recast = recast?.let { it.copy(cost = it.cost.lowered(damageCounter, params)) },
    requires = requires?.lowered(damageCounter, params),
    // THE identity, from the one function that derives it. A built card knows
    // which authored card it came from, so nothing downstream has to guess by
    // name -- see `CardDefinition.key`.
    key = key(),
)

internal fun FaceDoc.build(damageCounter: String? = null, params: GameParams? = null): Face {
    val hasBody = fields.isNotEmpty() || keywords.isNotEmpty()
    val dc = damageCounter
    return Face(
        name = name,
        types = types,
        baseChars = if (hasBody) Characteristics(name, types, fields, keywords) else null,
        castEffect = castEffect?.lowered(dc, params),
        triggers = triggers.map { it.lowered(dc, params) },
        statics = Statics(
            chars = statics.map { it.lowered(dc, params) },
            rules = ruleMods.map { it.lowered(dc, params) },
            costs = costMods,
            replacements = replacements.map { it.lowered(dc, params) },
        ),
        activated = activated.map { it.lowered(dc, params) },
    )
}

fun TriggerDoc.compile(self: ObjectId, controller: PlayerId): TriggeredAbility {
    val eff = effect.bindSelf(self)
    val on = pattern
    return TriggeredAbility(source = self, controller = controller, on = on, effect = eff, order = order)
}


// -- combat as a doc --------------------------------------------------
// A game's combat is a program in the language. A doc either names a preset
// from the library (saved as its name, and following the library) or holds
// its own program. The presets are the library file
// `content/combat/presets.json`, loaded by `Bundled`.

/** The presets, by name: each one's programs. */
val COMBAT_PRESETS: Map<String, Combat> get() = Bundled.combatPresets

/** The preset called [name], or null when there is none. */
fun presetByName(name: String): Combat? = COMBAT_PRESETS[name]

sealed interface CombatDoc {
    data class Preset(val name: String) : CombatDoc
    data class Program(val combat: Combat) : CombatDoc

    /** The combat this names. An unknown preset compiles to no combat, and
     *  the game reports it as an error (`unknown-combat-preset`): shown and
     *  edited, never played. */
    fun compile(): Combat = when (this) {
        is Preset -> presetByName(name) ?: NO_COMBAT_PROGRAM
        is Program -> combat
    }

    /** The preset name that resolves to nothing, if this is one. */
    fun unknownPreset(): String? = (this as? Preset)?.name?.takeIf { presetByName(it) == null }

    companion object {
        /** A preset's programs are saved as its name; anything else as itself. */
        fun of(c: Combat): CombatDoc = COMBAT_PRESETS.entries.firstOrNull { it.value == c }?.let { Preset(it.key) } ?: Program(c)
    }
}

// -- the bundle ------------------------------------------------------------

/** One line of a bundle's deck list: how many copies of a card start in the
 *  library. */
data class DeckEntry(val cardName: String, val count: Int = 1)

/** The rules half of a game: everything that shapes play, with no cards in it.
 *  The game-structure parameters hang here. */
data class RulesDoc(
    val extraTypes: List<TypeDef> = emptyList(),
    val extraZones: List<PlayZoneDef> = emptyList(),
    val combat: CombatDoc = CombatDoc.Preset("mtg"),
    /** Held directly -- like `extraTypes` it is already an engine type. */
    val turn: TurnStructure = TurnStructure.MTG,
    /** the game-structure parameters. */
    val params: GameParams = GameParams(),
    val resourceModel: ResourceModel = ResourceModel.None,
    val playerCounters: List<PlayerCounterDef> = DEFAULT_PLAYER_COUNTERS,
    /** Declared custom hidden zones -- e.g. a Flagship pool. New
     *  params go LAST (a mid-list positional caller would silently shift). */
    val extraHiddenZones: List<HiddenZoneDef> = emptyList(),
    /** Which player counter "damage to a player" hits.
     *  Null: players take no damage (see `Rules.damageCounter`). */
    val damageCounter: String? = LIFE,
    /** The counter kinds this game's cards may use, beyond the built-ins. NULL =
     *  never declared (an old save): `withDeclaredCounters` fills it once from
     *  what the cards use, and from then on an undeclared kind is reported.
     *  Empty = declares none. Kept last. */
    val counterKinds: List<CounterKindDef>? = null,
)

/** The card-tile layout for `types` under this rules doc -- the first
 *  type that declares one, else the default. Cheap: no full `Rules` build. */
fun RulesDoc.layoutFor(types: Set<String>): CardLayout {
    val all = BUILTIN_TYPES + extraTypes.associateBy { it.name }
    // The declared order, as `Rules.declared`.
    return all.keys.filter { it in types }.firstNotNullOfOrNull { all[it]?.layout } ?: CardLayout()
}

/** A named group of cards inside a game -- a real container with its own
 *  identity, not a label, so a set can be added and worked on separately the
 *  way a real CCG ships expansions. */
data class SetDoc(val name: String = "Core", val cards: List<CardDoc> = emptyList()) {
    fun updateCard(i: Int, f: (CardDoc) -> CardDoc): SetDoc =
        copy(cards = cards.mapIndexed { j, c -> if (j == i) f(c) else c })
    fun addCard(c: CardDoc = CardDoc()): SetDoc = copy(cards = cards + c)
    fun removeCard(i: Int): SetDoc = copy(cards = cards.filterIndexed { j, _ -> j != i })
}

/** A named deck; a game owns several and each seat picks one.
 *
 *  `slots` names the designated cards that live OUTSIDE `entries` (slot name
 *  "Leader" / "Base" / "Hero" -> card names). `entries` is the main deck only,
 *  and `size` counts it alone (SWU's 50, Hearthstone's 30). */
data class DeckDoc(
    val name: String = "Starter",
    val entries: List<DeckEntry> = emptyList(),
    val slots: Map<String, List<String>> = emptyMap(),
) {
    val size: Int get() = entries.sumOf { it.count }

    fun setSlot(slot: String, cards: List<String>): DeckDoc =
        copy(slots = if (cards.isEmpty()) slots - slot else slots + (slot to cards))

    /** Expand into a library. Instance ids start at `startId`, which must stay
     *  clear of battlefield ObjectIds (those start at 1). */
    fun libraryFor(startId: Int): List<CardRef> {
        var id = startId
        return entries.flatMap { e -> List(e.count.coerceAtLeast(0)) { CardRef(id++, e.cardName) } }
    }

    fun countOf(cardName: String): Int = entries.firstOrNull { it.cardName == cardName }?.count ?: 0
    fun setCount(cardName: String, n: Int): DeckDoc {
        val rest = entries.filterNot { it.cardName == cardName }
        return copy(entries = if (n <= 0) rest else rest + DeckEntry(cardName, n))
    }
}

/** A whole game: its rules, its sets of cards, its decks, and the construction
 *  rules those decks must follow. `deckRules` lives here, not on
 *  `RulesDoc`, because deck legality is an authoring-time report over the decks
 *  -- not a runtime rule the engine folds. */
data class GameDoc(
    val name: String = "Untitled Game",
    val rules: RulesDoc = RulesDoc(),
    val sets: List<SetDoc> = listOf(SetDoc()),
    val decks: List<DeckDoc> = emptyList(),
    /** Deck-construction rules for `decks`. Defaults to `DeckRules()`
     *  -- no size / copy / slot / identity constraint at all, which is the
     *  right default for a sandbox. `DeckRules.MTG` / `.HEARTHSTONE` / `.SWU`
     *  are opt-in presets. */
    val deckRules: DeckRules = DeckRules(),
    /** Stable identity; the on-disk file is keyed by it, so a rename is a plain
     *  field edit. `""` = not minted yet (`GameStore` assigns a UUID). Not a
     *  random default, so two `GameDoc()` values stay equal. */
    val id: String = "",
    /** Bundled-content stamp, for a game shipped inside the app and saved to
     *  disk: the Games screen compares it against the bundled definition so a
     *  stale copy is VISIBLE. Bump it when the bundled game changes; authored
     *  games leave it at 0. */
    val contentVersion: Int = 0,
    /** The `GameDoc.id` this game was forked from, or "". Authoring-only (like
     *  [id] and `FaceDoc.art`): never seen by the engine, elided in JSON when
     *  absent. Makes a directory of forks comparable. */
    val forkedFrom: String = "",
    /** What was varied, in the forker's own words -- "TurnMode.PER_PLAYER",
     *  "Harrow costs -1". Pairs with [forkedFrom]; neither is meaningful
     *  alone. */
    val forkNote: String = "",
    /** The game's own colour on the Shelf and in its workspace, as `#rrggbb`.
     *  "" = one picked from its id (`ccgui.accentOf`). Authoring-only, elided
     *  in JSON when absent. */
    val accent: String = "",
    /** The card on the game's box, by `CardDoc.key()`. "" = the first card
     *  with a picture (`ccgui.coverCard`). Authoring-only, elided when absent. */
    val cover: String = "",
) {
    /** Derive a variant of this game to iterate on without touching it (an agent
     *  never edits a game, it forks it). The new [id] gives it its own file,
     *  version ring and art directory.
     *
     *  **Card ids are preserved**, so a fork, its parent and its siblings join
     *  on "the same card". [contentVersion] is cleared: a fork is not a stale
     *  copy of the bundle. Pure -- the caller supplies the id. */
    fun forkedAs(newId: String, note: String, name: String = this.name): GameDoc = copy(
        id = newId,
        name = name,
        contentVersion = 0,
        forkedFrom = id,
        forkNote = note,
    )

    /** Every card in the game, across all its sets. */
    val cards: List<CardDoc> get() = sets.flatMap { it.cards }

    /** Names shared by more than one card without a minted id. `key()` falls
     *  back to the name for those, so a duplicate would shadow silently;
     *  `withCardIds` leaves them unminted, so this keeps reporting until the
     *  author renames one. */
    fun duplicateNames(): List<String> =
        cards.filter { it.id.isEmpty() }.mapNotNull { it.faces.firstOrNull()?.name }
            .groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()

    /** Build the game and check it: the one path from authored content to
     *  what the engine runs. Never throws: a game too broken to play still
     *  assembles, so the Creator can show it, and its errors say why it will
     *  not start (`Compiled.runnable`). */
    fun compile(): Compiled = assemble(this).let { r -> Compiled(r, diagnose(this, r)) }

    /** The assembled rules without the checking, for the suites. Anything
     *  that PLAYS goes through [compile], which refuses a game with errors. */
    internal fun rules(): Rules = assemble(this)

    /** Everything wrong with the game, as data. */
    fun diagnostics(): List<Diagnostic> = compile().diagnostics

    /** [diagnostics], as sentences. */
    fun problems(): List<String> = diagnostics().map { it.message }

    /** Mint an id (via [mintId]) for every card without one, then rewrite every
     *  `DeckEntry.cardName` / `DeckDoc.slots` value that named that card to the
     *  new id. Idempotent; pure (`GameStore` supplies the UUID source).
     *
     *  A name shared by two id-less cards is left alone -- a name->id map cannot
     *  tell which card a deck meant. `duplicateNames()` reports it until the
     *  author renames one. */
    fun withCardIds(mintId: () -> String): GameDoc {
        val idLessNameCounts = cards.filter { it.id.isEmpty() }
            .mapNotNull { it.faces.firstOrNull()?.name }
            .groupingBy { it }.eachCount()
        val renamed = HashMap<String, String>()   // old display name -> new id
        val migratedSets = sets.map { set ->
            set.copy(cards = set.cards.map { card ->
                val name = card.faces.firstOrNull()?.name
                if (card.id.isNotEmpty() || name == null || idLessNameCounts[name] != 1) card
                else {
                    val newId = mintId()
                    renamed[name] = newId
                    card.copy(id = newId)
                }
            })
        }
        if (renamed.isEmpty()) return this
        val migratedDecks = decks.map { d ->
            d.copy(
                entries = d.entries.map { e -> renamed[e.cardName]?.let { e.copy(cardName = it) } ?: e },
                slots = d.slots.mapValues { (_, names) -> names.map { renamed[it] ?: it } },
            )
        }
        return copy(sets = migratedSets, decks = migratedDecks)
    }

    fun updateSet(i: Int, f: (SetDoc) -> SetDoc): GameDoc =
        copy(sets = sets.mapIndexed { j, x -> if (j == i) f(x) else x })
    fun addSet(name: String = "New Set"): GameDoc = copy(sets = sets + SetDoc(name))
    fun removeSet(i: Int): GameDoc = if (sets.size <= 1) this else copy(sets = sets.filterIndexed { j, _ -> j != i })

    fun updateDeck(i: Int, f: (DeckDoc) -> DeckDoc): GameDoc =
        copy(decks = decks.mapIndexed { j, x -> if (j == i) f(x) else x })
    fun addDeck(name: String = "New Deck"): GameDoc = copy(decks = decks + DeckDoc(name))
    fun removeDeck(i: Int): GameDoc = copy(decks = decks.filterIndexed { j, _ -> j != i })
}
