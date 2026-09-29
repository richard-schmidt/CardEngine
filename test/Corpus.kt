package ccg

// ---------------------------------------------------------------------------
// The P0 test corpus: hand-built `CardDefinition`s the engine suites play.
//
// Test-only: nothing outside the tests uses it. Triggers are `TriggerDoc` /
// `EventPattern` data, as in any running game.
// ---------------------------------------------------------------------------

/** An anthem-shaped static: +dp/+dt to a filtered set. */
private fun anthemStatics(dp: Int, dt: Int, filter: PermFilter): Statics =
    Statics(chars = listOf(StaticSpec(filter, listOf(CharOp.PlusPT(lit(dp), lit(dt))))))

private val otherCreaturesYouControl: PermFilter = creatures().yours().otherThanThis()

// -- the six core anchors (P1 / P2) ---------------------------------------

val StonebackOx = card("Stoneback Ox", setOf("Creature"), body("Stoneback Ox", 3, 3))

val Emberbolt = card(
    "Emberbolt", setOf("Instant"),
    castEffect = Effect.Choose(creatures(), Effect.DealDamage(lit(3), BoundTarget(CHOSEN))),
)

val ScoutRider = card(
    "Scout Rider", setOf("Creature"), body("Scout Rider", 2, 1),
    triggers = listOf(TriggerDoc.SelfEnters(Effect.Draw(PlayerRef.You, lit(1)))),
)

val WardedSentinel = card("Warded Sentinel", setOf("Creature"), body("Warded Sentinel", 2, 3))

val Vinewrap = card(
    "Vinewrap", setOf("Sorcery"),
    castEffect = Effect.Choose(creatures(), Effect.Destroy(BoundTarget(CHOSEN))),
)

val FieldMarshal = card(
    "Field Marshal", setOf("Creature"), body("Field Marshal", 2, 2),
    statics = anthemStatics(1, 1, otherCreaturesYouControl),
)

val Grunt = card("Grunt", setOf("Creature"), body("Grunt", 2, 2))

// -- P2 green cards ------------------------------------------------------

val RallyTheRanks = card("Rally the Ranks", setOf("Sorcery"), castEffect = Effect.Draw(PlayerRef.You, countOf(creatures().yours())))

val VoltaicSurge = card(
    "Voltaic Surge", setOf("Instant"),
    castEffect = Effect.Choose(creatures(), Effect.DealDamage(x, BoundTarget(CHOSEN))),
)

val VoltFace = card("Volt to the Face", setOf("Instant"), castEffect = Effect.DamageOpponent(x))

val Warcry = card(
    "Warcry", setOf("Sorcery"),
    castEffect = Effect.Sequence(
        listOf(
            Effect.Draw(PlayerRef.You, lit(1)),
            Effect.If(countOf(creatures().yours()) gte 3, Effect.Draw(PlayerRef.You, lit(1))),
        ),
    ),
)

val VanguardScout = card(
    "Vanguard Scout", setOf("Creature"), body("Vanguard Scout", 2, 2),
    triggers = listOf(TriggerDoc.SelfEnters(
        Effect.If(countOf(creatures().yours().otherThanThis()) gte 1, Effect.Draw(PlayerRef.You, lit(1))),
    )),
)

// -- P3 green cards: type model, fields, counters ------------------------

/** #14 Wither Priest -- ETB: two -1/-1 counters on target creature. */
val WitherPriest = card(
    "Wither Priest", setOf("Creature"), body("Wither Priest", 2, 2),
    triggers = listOf(TriggerDoc.SelfEnters(Effect.Choose(creatures(), Effect.AddCounter("-1/-1", lit(2), BoundTarget(CHOSEN))))),
)

/** #13 Thornling Brood -- "whenever Thornling Brood attacks, put a +1/+1
 *  counter on it." Its P8 combat trigger (was an ETB stand-in through P7). */
val ThornlingBrood = card(
    "Thornling Brood", setOf("Creature"), body("Thornling Brood", 1, 1),
    triggers = listOf(TriggerDoc.SelfAttacks(Effect.AddCounter("+1/+1", lit(1), BoundTarget(SELF)))),
)

/** #17 Runegate Pillar -- a Land. No power/toughness fields at all. */
val RunegatePillar = card("Runegate Pillar", setOf("Land"))

/** #20 Sunspire -- a Planeswalker. Enters with 4 loyalty; dies at 0 (the type's
 *  diesWhen). Loyalty-ability costs (+1 / -3) are P8 -- P3 just proves the
 *  field + death predicate. */
val Sunspire = card(
    "Sunspire, the Ascendant", setOf("Planeswalker"),
    entersWith = listOf(CounterDef("loyalty", lit(4))),
)

/** #21 Siege of the Ninth Gate -- a Battle. Enters with 5 defense; dies at 0. */
val SiegeOfTheNinthGate = card(
    "Siege of the Ninth Gate", setOf("Enchantment", "Battle"),
    entersWith = listOf(CounterDef("defense", lit(5))),
)

/** #22 The Bloomcycle -- a Saga. Enters with 1 lore; a lore counter is added
 *  at the start of the controller's main phase; chapter K fires when lore
 *  passes K (order = K so they resolve I -> II -> III); sacrificed once lore
 *  reaches chapter III. */
val TheBloomcycle = card(
    "The Bloomcycle", setOf("Enchantment", "Saga"),
    entersWith = listOf(CounterDef("lore", lit(1))),
    diesWhen = selfCounter("lore") gte 3,
    triggers = listOf(
        TriggerDoc.OnYourPhase("main", Effect.AddCounter("lore", lit(1), BoundTarget(SELF))),
        TriggerDoc.CounterThreshold("lore", 1, Effect.Draw(PlayerRef.You, lit(1)), order = 1),  // I: draw
        TriggerDoc.CounterThreshold("lore", 2, Effect.Draw(PlayerRef.You, lit(1)), order = 2),  // II: draw
        TriggerDoc.CounterThreshold("lore", 3, Effect.GainLife(PlayerRef.You, lit(3)), order = 3), // III: gain 3
    ),
)

// -- P4 green cards: event model + trigger scoping ---------------------

/** #26 Archive Keeper -- "whenever you cast an instant or sorcery, draw a card".
 *  (The "then discard" -- chooseFromZone -- is P8.) */
val ArchiveKeeper = card(
    "Archive Keeper", setOf("Creature"), body("Archive Keeper", 2, 3),
    triggers = listOf(TriggerDoc.YouCastType(setOf("Instant", "Sorcery"), Effect.Draw(PlayerRef.You, lit(1)))),
)

/** #28 Deathbloom Herald -- "when this leaves the battlefield, its controller
 *  gains 2 life." (Tokens -- P8; a life gain stands in.) */
val DeathbloomHerald = card(
    "Deathbloom Herald", setOf("Creature"), body("Deathbloom Herald", 3, 2),
    triggers = listOf(TriggerDoc.SelfLeaves(Effect.GainLife(PlayerRef.You, lit(2)))),
)

/** #27 Chime of Endings -- "whenever a creature is put into a graveyard from
 *  anywhere, you gain 1 life." (The real card scries.) */
val ChimeOfEndings = card(
    "Chime of Endings", setOf("Enchantment"),
    triggers = listOf(TriggerDoc.CreatureDies(null, Effect.GainLife(PlayerRef.You, lit(1)))),
)

/** #29 Toll Collector -- "whenever a creature an opponent controls enters,
 *  that player loses 1 life." A non-self, opponent-scoped ETB trigger. */
val TollCollector = card(
    "Toll Collector", setOf("Creature"), body("Toll Collector", 1, 3),
    // the entering creature's controller is the opponent; hit their life.
    triggers = listOf(TriggerDoc.On(EventPattern.Enters(setOf("Creature"), PlayerRef.Opponent), Effect.DamageOpponent(lit(1)))),
)

/** #30 Hive Overseer -- "at the beginning of your end step, put a +1/+1
 *  counter on each OTHER creature you control." A boundary trigger whose
 *  effect scopes over a set. */
val HiveOverseer = card(
    "Hive Overseer", setOf("Creature"), body("Hive Overseer", 2, 2),
    triggers = listOf(TriggerDoc.OnYourPhase(
        "end",
        Effect.ForEach(creatures().yours().otherThanThis(), Effect.AddCounter("+1/+1", lit(1), BoundTarget(EACH))),
    )),
)

/** #31 Delayed Blast -- "at the beginning of the next end step, deal 4 damage
 *  to target creature." A delayed one-shot. */
val DelayedBlast = card(
    "Delayed Blast", setOf("Instant"),
    castEffect = Effect.Choose(
        creatures(),
        Effect.Delayed(on = EventPattern.OnPhase("end"), effect = Effect.DealDamage(lit(4), BoundTarget(CHOSEN))),
    ),
)

/** #33 Wrath of the Commons -- "each player sacrifices two creatures." */
val WrathOfTheCommons = card(
    "Wrath of the Commons", setOf("Sorcery"),
    castEffect = Effect.ForEachPlayer(Effect.Sacrifice(PlayerRef.You, lit(2), creatures())),
)

/** #17 Adept of the Ninefold Path -- level bands. A `CharOp.Bands` static that
 *  SETS power/toughness from the `level` counter count (review finding E --
 *  was the one card that forced a raw `makeContinuousEffect`). Level-up as an
 *  activated ability is P8; P3 tests the band mechanic via a direct AddCounter. */
val Adept = card(
    "Adept of the Ninefold Path", setOf("Creature"), body("Adept of the Ninefold Path", 1, 1),
    statics = Statics(
            chars = listOf(
                StaticSpec(
                    PermFilter(pinned = BoundTarget(SELF)),
                    listOf(
                        CharOp.Bands(
                            "level",
                            listOf(
                                CharOp.Bands.Band(5, lit(6), lit(6)),
                                CharOp.Bands.Band(2, lit(3), lit(3)),
                            ),
                        ),
                    ),
                ),
            ),
        ),
)

// -- P3 multi-face cards ------------------------------------------------

/** #24 Pilgrim // Shrine -- MDFC. Cast either face from hand. */
val PilgrimShrine = CardDefinition(
    faces = listOf(
        Face("Pilgrim", setOf("Creature"), body("Pilgrim", 1, 1)),
        Face("Shrine", setOf("Land")),
    ),
)

/** #25 Grizzled Outrider <-> Pack Alpha -- a transforming DFC. The back face
 *  carries an anthem. Transform is toggled here by the `Metamorphosis` test
 *  spell; the `{3}: transform` activated ability is P8. */
val GrizzledOutrider = CardDefinition(
    faces = listOf(
        Face("Grizzled Outrider", setOf("Creature"), body("Grizzled Outrider", 2, 2)),
        Face(
            "Pack Alpha", setOf("Creature"), body("Pack Alpha", 4, 4),
            statics = anthemStatics(1, 1, otherCreaturesYouControl),
        ),
    ),
)

/** A test spell: transform target permanent. */
val Metamorphosis = card(
    "Metamorphosis", setOf("Sorcery"),
    castEffect = Effect.Choose(permanents(), Effect.Transform(BoundTarget(CHOSEN))),
)

// -- P5 green cards: play zones / arenas ------------------------------
// A demo bundle: the shared "skies" arena + a per-player "planet". The cards
// below are authored against P5_RULES.

val P5_RULES = Rules(
    types = BUILTIN_TYPES_CORE + listOf(
        // a Skyship enters the shared "skies" arena when the play action names
        // no zone -- exercises TypeDef.zoneOfPlay.
        TypeDef(
            "Skyship",
            fields = setOf("power", "toughness"),
            attacks = true,
            diesWhen = (selfField("toughness") lte selfDamage).lowered(),
            zoneOfPlay = "skies",
        ),
    ).associateBy { it.name },
    zones = BUILTIN_ZONES + listOf(
        PlayZoneDef("skies", ZoneScope.SHARED),
        PlayZoneDef("planet", ZoneScope.PER_PLAYER),
    ).associateBy { it.id },
)

/** #34 Orbital Cutter -- a Skyship. Its type routes it into "skies" with no
 *  zone named on the play action. */
val OrbitalCutter = card(
    "Orbital Cutter", setOf("Skyship"),
    baseChars = Characteristics("Orbital Cutter", setOf("Skyship"), mapOf("power" to 3, "toughness" to 2)),
)

/** #35 Ward of the Expanse -- "creatures in the same zone as this get +1/+0."
 *  A zone-scoped layer-7 static (filter `.inThisZone()`). */
val WardOfTheExpanse = card(
    "Ward of the Expanse", setOf("Enchantment"),
    statics = anthemStatics(1, 0, creatures().inThisZone()),
)

/** #36 Warp Anchor -- "whenever another permanent moves into this permanent's
 *  zone, you draw a card." A MovesZone-scoped trigger. */
val WarpAnchor = card(
    "Warp Anchor", setOf("Enchantment"),
    triggers = listOf(TriggerDoc.On(EventPattern.MovesIntoThisZone, Effect.Draw(PlayerRef.You, lit(1)))),
)

// -- P6 green cards: effect durations + prevention -------------------

/** #37 Battle Fury -- "target creature gets +2/+0 until end of turn." */
val BattleFury = card(
    "Battle Fury", setOf("Instant"),
    castEffect = Effect.Choose(creatures(), Effect.ApplyModifier(creatures().only(CHOSEN), listOf(CharOp.PlusPT(lit(2), lit(0))))),
)

/** #38 Bulwark Chant -- "creatures you control get +0/+2 until end of turn."
 *  The affected set is snapshot at resolution: a creature that enters later
 *  this turn is not buffed. */
val BulwarkChant = card(
    "Bulwark Chant", setOf("Instant"),
    castEffect = Effect.ApplyModifier(creatures().yours(), listOf(CharOp.PlusPT(lit(0), lit(2)))),
)

/** #39 Sculptor's Whim -- "target creature becomes 0/1 until end of turn." */
val SculptorsWhim = card(
    "Sculptor's Whim", setOf("Instant"),
    castEffect = Effect.Choose(creatures(), Effect.ApplyModifier(creatures().only(CHOSEN), listOf(CharOp.SetPT(lit(0), lit(1))))),
)

/** #40 Slow the Sands -- "target creature gets -3/-0 for as long as you control
 *  an Enchantment." A While-duration modifier. */
val SlowTheSands = card(
    "Slow the Sands", setOf("Sorcery"),
    castEffect = Effect.Choose(
        creatures(),
        Effect.ApplyModifier(
            creatures().only(CHOSEN),
            listOf(CharOp.PlusPT(lit(-3), lit(0))),
            duration = Duration.While(controls(ofType("Enchantment"))),
        ),
    ),
)

/** #41 Aegis Ward -- "prevent the next 3 damage that would be dealt to target
 *  creature this turn." */
val AegisWard = card(
    "Aegis Ward", setOf("Instant"),
    castEffect = Effect.Choose(creatures(), Effect.PreventDamage(BoundTarget(CHOSEN), lit(3))),
)

/** #42 Last Stand -- "prevent all damage that would be dealt to target creature
 *  this turn." */
val LastStand = card(
    "Last Stand", setOf("Sorcery"),
    castEffect = Effect.Choose(creatures(), Effect.PreventDamage(BoundTarget(CHOSEN), all = true)),
)

// -- P7 green cards: static-effect vocabulary ------------------------

/** #43 Skyward Doctrine -- "creatures you control have flying." A layer-6
 *  keyword grant over a filter. */
val SkywardDoctrine = card(
    "Skyward Doctrine", setOf("Enchantment"),
    statics = Statics(chars = listOf(StaticSpec(creatures().yours(), listOf(CharOp.GrantKeyword("flying"))))),
)

/** #44 Grantcaller's Creed -- "creatures you control get +1/+1 and have
 *  vigilance." Two ops in one static, at their own layers. */
val GrantcallersCreed = card(
    "Grantcaller's Creed", setOf("Enchantment"),
    statics = Statics(chars = listOf(StaticSpec(creatures().yours(), listOf(CharOp.PlusPT(lit(1), lit(1)), CharOp.GrantKeyword("vigilance"))))),
)

/** #45 Dead Weight Doctrine -- "creatures your opponents control get -2/-2."
 *  A debuff static scoped by the controller-relative `.theirs()` filter. */
val DeadWeightDoctrine = card(
    "Dead Weight Doctrine", setOf("Enchantment"),
    statics = Statics(chars = listOf(StaticSpec(creatures().theirs(), listOf(CharOp.PlusPT(lit(-2), lit(-2)))))),
)

/** #46 Null Field -- "creatures lose all abilities and are 1/1." Type/ability
 *  removal + set P/T, layered (RemoveAbilities at 6, SetPT at 7). */
val NullField = card(
    "Null Field", setOf("Enchantment"),
    statics = Statics(chars = listOf(StaticSpec(creatures(), listOf(CharOp.RemoveAbilities, CharOp.SetPT(lit(1), lit(1)))))),
)

/** #47 Leyline Tithe -- "your opponents can't gain life." A rule-modifying
 *  static, queried by the engine before a life gain. */
val LeylineTithe = card(
    "Leyline Tithe", setOf("Enchantment"),
    statics = Statics(rules = listOf(RuleMod.Cant(RuleAction.GAIN_LIFE, PlayerRef.Opponent))),
)

/** #48 Rallying Standard -- "as long as you control three or more creatures,
 *  creatures you control get +1/+0." A conditional layer. */
val RallyingStandard = card(
    "Rallying Standard", setOf("Enchantment"),
    statics = Statics(
            chars = listOf(
                StaticSpec(
                    creatures().yours(),
                    listOf(CharOp.PlusPT(lit(1), lit(0))),
                    condition = countOf(creatures().yours()) gte 3,
                ),
            ),
        ),
)

/** #49 Sanctuary Field -- "if a creature you control would be destroyed, exile
 *  it instead." A card-authored replacement carried in play (vs. the
 *  source-less `warding`). */
val SanctuaryField = card(
    "Sanctuary Field", setOf("Enchantment"),
    statics = Statics(replacements = listOf(ReplacementDoc.DeathToExile(permanents().yours()))),
)

/** #50 Primal Mimicry -- "until end of turn, target creature becomes a 4/4
 *  Elemental with trample." The full P7 vocabulary through a P6 duration:
 *  type replace + set P/T + keyword grant. */
val PrimalMimicry = card(
    "Primal Mimicry", setOf("Sorcery"),
    castEffect = Effect.Choose(
        creatures(),
        Effect.ApplyModifier(
            creatures().only(CHOSEN),
            listOf(
                CharOp.SetTypes(setOf("Creature", "Elemental")),
                CharOp.SetPT(lit(4), lit(4)),
                CharOp.GrantKeyword("trample"),
            ),
        ),
    ),
)

// -- a replacement effect held by the state, with no card behind it --------

/** "If a creature `controller` controls would die, exile it instead." The
 *  permanent still LEAVES the battlefield (LTB triggers see it) -- just to
 *  exile, not the graveyard, so no "dies" trigger fires. */
fun warding(controller: PlayerId): ActiveReplacement =
    held(controller, ReplacementDoc.DeathToExile(creatures().yours()))

/** A replacement in play with no source: what a test states as data where it
 *  once handed the engine a closure. */
fun held(controller: PlayerId, doc: ReplacementDoc): ActiveReplacement =
    ActiveReplacement(source = null, controller = controller, doc = doc.lowered(null))
