package ccg

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType

// ---------------------------------------------------------------------------
// CODEC COMPLETENESS -- every class the save format carries survives JSON with
// EVERY field set.
//
// A per-sample round-trip only proves what its sample exercises, and a sample
// that leaves a field at its default proves nothing about that field: an
// encoder that drops it and a decoder that restores the default agree with each
// other. That is how `Effect.GainLife.counter` was lost on every save while the
// "exhaustive" Effect round-trip stayed green.
//
// So this test enforces three things, none of them hand-maintained:
//   1. REACH. The type graph is walked from the persisted roots (`GameDoc`,
//      `Answer`) through record components and sealed `permittedSubclasses`.
//      Every concrete class reached must have a specimen below.
//   2. NO DEFAULTS. Each specimen is rebuilt through the class's synthetic
//      defaults constructor, one parameter at a time; a field whose specimen
//      value equals its default fails, so a specimen cannot hide a dropped field.
//   3. ROUND TRIP. Each specimen goes through the real codec and back equal.
//
// Plain Java reflection only -- the engine stays off kotlin-reflect.
// ---------------------------------------------------------------------------

private val ROOTS: List<Class<*>> = listOf(GameDoc::class.java, Answer::class.java)

/** Fields that are deliberately NOT persisted, with the reason. A field here is
 *  exempt from the no-defaults rule; everything else must round-trip. */
private val NOT_PERSISTED: Map<String, String> = emptyMap()

/** Interface types reached by the walk that are runtime-only closures. Each is
 *  only allowed behind a NOT_PERSISTED field. */
private val OPAQUE: Set<Class<*>> = emptySet()

/** Cases of a persisted type that are never written as themselves, with the
 *  reason. Not walked, and need no specimen; UiTest pins what they ARE
 *  written as. */
private val IN_PROCESS: Map<Class<*>, String> = mapOf(
    Answer.Act::class.java to "a priority answer carrying the action itself; saved as the reference it names",
)

private fun rt(text: String, parse: (Json) -> Any): Any = parse(Json.parse(text))

/** How each specimen goes through JSON: its own codec where one exists, else the
 *  smallest enclosing class that carries it inline. First match wins. */
private val ROUND_TRIPS: List<Pair<Class<*>, (Any) -> Any>> = listOf(
    Effect::class.java to { e -> effectFromJson(effectToJson(e as Effect)) },
    IntExpr::class.java to { e -> intExprFromJson(intExprToJson(e as IntExpr)) },
    BoolExpr::class.java to { e -> boolExprFromJson(boolExprToJson(e as BoolExpr)) },
    PermFilter::class.java to { f -> rt(filterToJson(f as PermFilter), ::filterOf) },
    ZoneScoping::class.java to { z -> (rt(filterToJson(PermFilter(zone = z as ZoneScoping)), ::filterOf) as PermFilter).zone },
    BoundTarget::class.java to { t -> (effectFromJson(effectToJson(Effect.Destroy(t as BoundTarget))) as Effect.Destroy).target },
    Ref::class.java to { r -> (effectFromJson(effectToJson(Effect.Destroy(BoundTarget(r as Ref)))) as Effect.Destroy).target.ref },
    CharOp.Bands.Band::class.java to { b ->
        (rt(charOpToJson(CharOp.Bands("level", listOf(b as CharOp.Bands.Band))), ::charOpOf) as CharOp.Bands).steps.single()
    },
    CharOp::class.java to { o -> rt(charOpToJson(o as CharOp), ::charOpOf) },
    RuleMod::class.java to { r -> rt(ruleModToJson(r as RuleMod), ::ruleModOf) },
    CostMod::class.java to { c -> rt(costModToJson(c as CostMod), ::costModOf) },
    CounterPayment::class.java to { p -> (rt(costToJson(Cost(payFrom = p as CounterPayment)), ::costOf) as Cost).payFrom!! },
    Cost::class.java to { c -> rt(costToJson(c as Cost), ::costOf) },
    ActivatedAbility::class.java to { a -> rt(activatedAbilityToJson(a as ActivatedAbility), ::activatedAbilityOf) },
    StaticSpec::class.java to { s -> rt(staticSpecToJson(s as StaticSpec), ::staticSpecOf) },
    Statics::class.java to { s -> rt(staticsToJson(s as Statics), ::staticsOf) },
    Characteristics::class.java to { c -> rt(charsToJson(c as Characteristics), ::charsOf) },
    CardFilter::class.java to { f -> rt(cardFilterToJson(f as CardFilter), ::cardFilterOf) },
    Duration::class.java to { d -> rt(durationToJson(d as Duration), ::durationOf) },
    EventPattern::class.java to { p -> rt(eventPatternToJson(p as EventPattern), ::eventPatternOf) },
    PlayerRef::class.java to { r -> rt(playerRefToJson(r as PlayerRef), ::playerRefOf) },
    ZoneRef::class.java to { z -> rt(zoneRefToJson(z as ZoneRef), ::zoneRefOf) },
    CounterDef::class.java to { c -> rt(counterDefToJson(c as CounterDef), ::counterDefOf) },
    TriggerDoc::class.java to { t -> rt(triggerDocToJson(t as TriggerDoc), ::triggerDocOf) },
    ReplacementDoc::class.java to { r -> rt(replacementDocToJson(r as ReplacementDoc), ::replacementDocOf) },
    ArtRect::class.java to { a -> rt(artRectToJson(a as ArtRect), ::artRectOf) },
    FaceDoc::class.java to { f -> rt(faceDocToJson(f as FaceDoc), ::faceDocOf) },
    Recast::class.java to { r -> (rt(cardDocToJson(CardDoc(recast = r as Recast)), ::cardDocOf) as CardDoc).recast!! },
    CardDoc::class.java to { c -> rt(cardDocToJson(c as CardDoc), ::cardDocOf) },
    CardLayout::class.java to { l -> rt(cardLayoutToJson(l as CardLayout), ::cardLayoutOf) },
    TypeDef::class.java to { t -> rt(typeDefToJson(t as TypeDef), ::typeDefOf) },
    PlayZoneDef::class.java to { z -> rt(playZoneDefToJson(z as PlayZoneDef), ::playZoneDefOf) },
    HiddenZoneDef::class.java to { z -> rt(hiddenZoneDefToJson(z as HiddenZoneDef), ::hiddenZoneDefOf) },
    CombatStep::class.java to { s -> rt(combatStepToJson(s as CombatStep), ::combatStepOf) },
    ScreenRule::class.java to { s -> rt(combatConfigToJson(COMBAT_BASE.copy(screen = s as ScreenRule)), ::combatConfigOf).let { (it as CombatConfig).screen!! } },
    RangeFields::class.java to { r -> rt(combatConfigToJson(COMBAT_BASE.copy(rangeFields = r as RangeFields)), ::combatConfigOf).let { (it as CombatConfig).rangeFields!! } },
    StanceDef::class.java to { s -> rt(combatConfigToJson(COMBAT_BASE.copy(stances = listOf(s as StanceDef))), ::combatConfigOf).let { (it as CombatConfig).stances.single() } },
    CombatConfig::class.java to { c -> rt(combatConfigToJson(c as CombatConfig), ::combatConfigOf) },
    BlockRule::class.java to { b -> rt(blockRuleToJson(b as BlockRule), ::blockRuleOf) },
    Gun::class.java to { g -> rt(gunToJson(g as Gun), ::gunOf) },
    Combat::class.java to { c -> rt(combatToJson(c as Combat), ::combatOf) },
    CombatDoc::class.java to { c -> rt(combatDocToJson(c as CombatDoc), ::combatDocOf) },
    PhaseSpec::class.java to { p -> (rt(turnStructureToJson(TurnStructure(listOf(p as PhaseSpec))), ::turnStructureOf) as TurnStructure).phases.single() },
    TurnStructure::class.java to { t -> rt(turnStructureToJson(t as TurnStructure), ::turnStructureOf) },
    MulliganRule::class.java to { m -> (rt(gameParamsToJson(GameParams(mulligan = m as MulliganRule)), ::gameParamsOf) as GameParams).mulligan },
    GameParams::class.java to { p -> rt(gameParamsToJson(p as GameParams), ::gameParamsOf) },
    ResourceModel::class.java to { m -> rt(resourceModelToJson(m as ResourceModel), ::resourceModelOf) },
    PlayerCounterDef::class.java to { c -> rt(playerCounterToJson(c as PlayerCounterDef), ::playerCounterOf) },
    CounterKindDef::class.java to { c -> rt(counterKindToJson(c as CounterKindDef), ::counterKindOf) },
    RulesDoc::class.java to { r -> rt(rulesDocToJson(r as RulesDoc), ::rulesDocOf) },
    DeckSlotDef::class.java to { s -> rt(deckSlotDefToJson(s as DeckSlotDef), ::deckSlotDefOf) },
    IdentityRule::class.java to { r -> rt(identityRuleToJson(r as IdentityRule), ::identityRuleOf) },
    DeckRules::class.java to { d -> rt(deckRulesToJson(d as DeckRules), ::deckRulesOf) },
    DeckEntry::class.java to { e -> gameDocFromJson(gameDocToJson(GameDoc(decks = listOf(DeckDoc(entries = listOf(e as DeckEntry)))))).decks.single().entries.single() },
    DeckDoc::class.java to { d -> gameDocFromJson(gameDocToJson(GameDoc(decks = listOf(d as DeckDoc)))).decks.single() },
    SetDoc::class.java to { s -> gameDocFromJson(gameDocToJson(GameDoc(sets = listOf(s as SetDoc)))).sets.single() },
    GameDoc::class.java to { g -> gameDocFromJson(gameDocToJson(g as GameDoc)) },
    CastZone::class.java to { z -> (answerFromJson(answerToJson(Answer.PlayCard(1, z as CastZone))) as Answer.PlayCard).zone },
    CombatTarget::class.java to { t -> (answerFromJson(answerToJson(Answer.CombatTgt(t as CombatTarget))) as Answer.CombatTgt).target!! },
    Answer::class.java to { a -> answerFromJson(answerToJson(a as Answer)) },
    TableEdit::class.java to { e -> tableEditOf(Json.parse(tableEditToJson(e as TableEdit))) },
)

// -- specimens: every field set away from its default ------------------------

private val BT = BoundTarget(CHOSEN)
private val FILTER = PermFilter(
    types = setOf("Creature"), controller = PlayerRef.Opponent, excludesSource = true,
    zone = ZoneScoping.Named("lane"), pinned = BoundTarget(CHOSEN), hasCounter = "+1/+1", minCount = 2, onlyHost = true,
    where = BoolExpr.HasKeyword(BoundTarget(SUBJECT), "flying"),
)
private val DRAW = Effect.Draw(PlayerRef.Opponent, lit(2))
private val ABILITY = ActivatedAbility(
    Cost(tapSource = true), Effect.DamageOpponent(lit(1)), name = "Ping", oncePerTurn = true, usesStack = false,
)
private val COST = Cost(
    mana = mapOf("R" to 1), usesX = true, additional = Effect.Discard(PlayerRef.You, lit(1)),
    tapSource = true, sacrificeSource = true, payLife = 2, removeCounters = "charge" to 1,
    alternatives = listOf(Cost(mana = mapOf("" to 2))), payFrom = CounterPayment("charge", 2, FILTER),
)
private val LAYOUT = CardLayout(
    ArtSlot.LEFT, StatCorner.BOTTOM_LEFT, listOf("hull"), CounterTrack.LEFT_EDGE, "charge", showText = false, accent = "#123456",
)
private val STEP = CombatStep(
    "fast", "fast", DamageModel.COMPARE, attackerField = "atk", defenderField = "hull", returnDamage = false,
    excessToController = true, onTie = TieResult.ATTACKER_WINS, responseWindow = true,
    requiresKeyword = "swift", excludesKeyword = "slow", exemptKeyword = "shielded",
)
private val COMBAT_BASE = CombatConfig(CombatStyle.FREE, listOf(STEP))
private val COMBAT = COMBAT_BASE.copy(
    mustTargetKeyword = "taunt", cantTargetKeyword = "stealth", onlyExhaustedTargets = true,
    blockerKeyword = "sentinel", playerHitAmount = 1, laneLockedPlayerTargets = true, laneLockedBoardTargets = true,
    screen = ScreenRule("back", "front"), longRangeHitsFace = true, rangeFields = RangeFields("close", "far"),
    overflowToController = true, crossLaneKeyword = "reach", exhaustedCannotAct = true, attacksPerRange = true,
    blockRules = listOf(BlockRule.cantBlock("wall")),
    stances = listOf(StanceDef("braced", "grit")),
)
private val PHASE = PhaseSpec("main", interactive = false, combat = true, onEnter = PhaseEffects.DRAW, sorcerySpeed = true)
private val PARAMS = GameParams(
    startingHandSize = 3, cardsDrawnPerTurn = 2, firstPlayerSkipsFirstDraw = false,
    mulligan = MulliganRule(1, false), maxHandSize = 9, playerCount = 3, preferredOrientation = Orientation.LANDSCAPE,
    poolPersistsPerTurn = true, poolStoreCounter = "store", attackDelayOnEntry = true,
)
private val TYPE = TypeDef(
    "Ship", fields = setOf("hull"), diesWhen = BoolExpr.Const(false), isSpell = true, instantSpeed = true,
    usesStack = false, attacks = true, zoneOfPlay = "lane", damageCounter = "hull", layout = LAYOUT,
    loseOnDeath = true, zoneChoices = listOf("lane1", "lane2"),
)
private val FACE = FaceDoc(
    "Kestrel", setOf("Ship"), fields = mapOf("hull" to 3), keywords = setOf("swift"),
    castEffect = DRAW, statics = listOf(StaticSpec(FILTER, listOf(CharOp.GrantKeyword("swift")))),
    ruleMods = listOf(RuleMod.CantBlock(FILTER)), costMods = listOf(CostMod(PlayerRef.You, setOf("Ship"), mapOf("" to -1))),
    triggers = listOf(TriggerDoc.SelfEnters(DRAW)), activated = listOf(ABILITY), art = "kestrel.png",
    artFieldRect = ArtRect(0.1f, 0.2f, 0.5f, 0.5f), replacements = listOf(ReplacementDoc.DeathToExile(FILTER), ReplacementDoc.Replace(EventPattern.Dies(setOf("Ship"), filter = FILTER), Effect.Proceed())),
    artMiniRect = ArtRect(0.0f, 0.1f, 0.3f, 0.3f),
)
private val CARD = CardDoc(
    faces = listOf(FACE), entersWith = listOf(CounterDef("lore", lit(1))), diesWhen = BoolExpr.Const(false),
    text = "Draw two.", cost = COST, recast = Recast(HiddenZone.EXILE, Cost(mana = mapOf("" to 3)), HiddenZone.LIBRARY_BOTTOM),
    id = "c1", requires = FILTER,
)
private val RULES = RulesDoc(
    extraTypes = listOf(TYPE), extraZones = listOf(PlayZoneDef("lane", ZoneScope.PER_PLAYER, 3, setOf("fast"), 1, Depth.BACK)),
    combat = CombatDoc.Program(COMBAT.lowered()),
    turn = TurnStructure(listOf(PHASE), TurnMode.SHARED), params = PARAMS,
    resourceModel = ResourceModel.Ramp(2, 8, false, 1, "energy"),
    playerCounters = listOf(PlayerCounterDef("hull", 30, true, 0, 40)),
    extraHiddenZones = listOf(HiddenZoneDef("pool", alwaysVisible = false)), damageCounter = "hull",
    counterKinds = listOf(CounterKindDef("shield")),
)
private val DECK_RULES = DeckRules(
    40, 60, 3, listOf(DeckSlotDef("Leader", setOf("Leader"), 2, "hand")), IdentityRule(listOf("Leader"), setOf("red"), false),
)
private val DECK = DeckDoc("Aggro", listOf(DeckEntry("Grunt", 3)), mapOf("Leader" to listOf("Hero")))

internal val SPECIMENS: List<Any> = listOf(
    // Effect
    Effect.DealDamage(lit(2), BT), Effect.DealDamage(lit(2), null, PlayerRef.Active), Effect.DamageOpponent(lit(2)), DRAW,
    Effect.GainLife(PlayerRef.Opponent, lit(3), counter = "store"),
    Effect.AddMana(PlayerRef.Opponent, mapOf("G" to lit(1), "" to x)),
    Effect.Destroy(BT), Effect.AddCounter("lore", lit(1), BT), Effect.RemoveCounter("loyalty", lit(2), BT),
    Effect.Transform(BT),
    Effect.CreateToken(Characteristics("Soldier", setOf("Creature"), mapOf("power" to 1), setOf("swift"), true, listOf(ABILITY)), lit(2), "lane"),
    Effect.CopyOf(BT),
    Effect.CreateEmblem(Statics(
        chars = listOf(StaticSpec(FILTER, listOf(CharOp.PlusPT(lit(1), lit(1))))),
        rules = listOf(RuleMod.Cant(RuleAction.GAIN_LIFE, PlayerRef.Opponent)), costs = listOf(CostMod(PlayerRef.You, setOf("Ship"), mapOf("" to -1))),
    )),
    Effect.ChooseMode(listOf(DRAW, Effect.NoOp), lit(2)),
    Effect.Discard(PlayerRef.Opponent, lit(1), HiddenZone.EXILE),
    Effect.DrawThenDiscard(PlayerRef.Opponent, lit(2), lit(1), HiddenZone.EXILE),
    Effect.ReturnFromDiscard(PlayerRef.Opponent, lit(1), toBattlefield = true),
    Effect.SearchZone(PlayerRef.Opponent, HiddenZone.LIBRARY, HiddenZone.HAND, lit(2), CardFilter(types = setOf("Creature")), thenShuffle = false, upTo = false),
    Effect.SearchZone(PlayerRef.You, HiddenZone.GRAVEYARD, HiddenZone.GRAVEYARD, lit(1), intoPlay = true),
    Effect.Shuffle(PlayerRef.Opponent, HiddenZone.GRAVEYARD),
    Effect.MoveTop(PlayerRef.Opponent, lit(3), HiddenZone.GRAVEYARD),
    Effect.LookAtTop(PlayerRef.Opponent, lit(2), HiddenZone.LIBRARY_BOTTOM),
    Effect.MovePermanent(BT, ZoneRef("planet", "P0")),
    Effect.ApplyModifier(FILTER, listOf(CharOp.PlusPT(lit(1), lit(1))), layer = 7, duration = Duration.EndOfNextTurn),
    Effect.PreventDamage(BT, lit(2), all = true, duration = Duration.EndOfNextTurn, mode = ShieldMode.INSTANCES, who = PlayerRef.You, onlyStep = "fast"),
    Effect.SetCombatMode(BT, "defense"), Effect.Attach(BT),
    Effect.ForEach(FILTER, Effect.DealDamage(lit(1), BoundTarget("ship")), binds = "ship"),
    Effect.ForEachPlayer(DRAW), Effect.Sacrifice(PlayerRef.Opponent, lit(1), FILTER),
    Effect.Delayed(EventPattern.OnPhase("end"), DRAW, once = false, expiresAfter = EventPattern.AnyTurnBegan, duration = Duration.EndOfTurn),
    Effect.Sequence(listOf(Effect.NoOp, DRAW)), Effect.Choose(FILTER, Effect.Destroy(BT)),
    Effect.Choose(FILTER, Effect.Choose(FILTER, Effect.Destroy(BT), binds = "second"), binds = "first"),
    Effect.ChooseMany(FILTER, lit(2), upTo = true, divide = lit(4), body = Effect.DealDamage(IntExpr.Share, BoundTarget("t")), binds = "t"),
    // a target slot holds an object or a named variable.
    Ref.Obj(7), Ref.Var("chosen"), BoundTarget(7), BoundTarget(CHOSEN),
    Effect.If(BoolExpr.Const(true), DRAW, otherwise = Effect.DamageOpponent(lit(1))), Effect.NoOp,
 Effect.CounterSpell(setOf("Instant"), PlayerRef.You, BoundTarget(7)), Effect.Tap(BT, untap = true),
    Effect.SendTo(BT, HiddenZone.EXILE), Effect.GainControl(BT, Duration.EndOfTurn), Effect.ClearDamage(BT), Effect.Proceed(IntExpr.EventAmount - 1), Effect.Proceed(),
    // The combat verbs, every field away from its default.
    Effect.DeclareAttackers(FILTER, FILTER, Effect.CombatWindow),
    Effect.DeclareBlockers(FILTER, listOf(BlockRule(FILTER, FILTER)), Effect.CombatWindow),
    Effect.CombatWindow,
    Effect.CombatDamage("first-strike", lit(2), acts = FILTER, lethal = FILTER, tramples = FILTER, minBlockers = lit(2), overflow = true),
    Effect.Attack(FILTER, FILTER, lit(2), FILTER, FILTER, FILTER, Effect.CombatWindow,
        reaches = BoolExpr.AtDepth(BoundTarget(TARGET), Depth.BACK), reachesFace = BoolExpr.ZoneOwner(BoundTarget(ATTACKER), PlayerRef.Defending)),
    // FREE combat and the geometry it is written in.
    Effect.FreeAttacks(
        "fast", FILTER,
        body = Gun(
            carried = BoolExpr.HasField(BoundTarget(ATTACKER), "sr"),
            reaches = BoolExpr.ZoneOwner(BoundTarget(TARGET), null),
            reachesFace = BoolExpr.AtDepth(BoundTarget(ATTACKER), Depth.FRONT),
            amount = IntExpr.Cond(BoolExpr.Const(true), IntExpr.LaneOf(BoundTarget(ATTACKER)), lit(1)),
            faceAmount = lit(2),
            label = AttackRange.CLOSE,
        ),
        guns = listOf(Gun(amount = lit(1))), guards = FILTER, lethal = FILTER, window = true, overflow = true,
    ),
    Effect.Strike("strike", lit(2), lit(1), returnDamage = false, lethal = FILTER),
    Effect.Clash("clash", lit(2), lit(3), lit(1), returnDamage = false, excessToController = true,
        onTie = TieResult.NOTHING, stances = listOf(StanceDef("defense", "defense")), pierces = FILTER),
    Effect.AsPlayer(PlayerRef.ControllerOf(BT), DRAW),
    PlayerRef.You, PlayerRef.Opponent, PlayerRef.EachOpponent, PlayerRef.Active, PlayerRef.Chosen, PlayerRef.ControllerOf(BT), PlayerRef.OwnerOf(BT),
    PlayerRef.Defending, PlayerRef.Seat("P1"),
    // the geometry reach is written in
    IntExpr.LaneOf(BT), IntExpr.Cond(BoolExpr.Const(false), lit(1), lit(2)),
    BoolExpr.HasField(BT, "sr"), BoolExpr.AtDepth(BT, Depth.BACK), BoolExpr.ZoneOwner(BT, PlayerRef.Defending), BoolExpr.ZoneOwner(BT, null),
    Gun(BoolExpr.Const(false), BoolExpr.Const(false), BoolExpr.Const(false), lit(2), lit(3), AttackRange.FAR),
    Combat(Effect.CombatWindow, HEARTHSTONE_COMBAT.lowered().attack),
    // IntExpr / BoolExpr
    IntExpr.Lit(3), IntExpr.X, IntExpr.CountPerms(FILTER), IntExpr.CountCounters(FILTER, "+1/+1"),
    IntExpr.HandSize(PlayerRef.Opponent), IntExpr.LifeOf(PlayerRef.Opponent), IntExpr.Bin(BinOp.MUL, lit(2), x),
    IntExpr.SelfField("power"), IntExpr.SelfDamage, IntExpr.SelfCounter("lore"),
    IntExpr.TargetField(BT, "power"), IntExpr.TargetCounter(BT, "lore"), IntExpr.TargetDamage(BT), IntExpr.Share, IntExpr.EventAmount,
    IntExpr.Param("maxHandSize"), IntExpr.TurnNumber, IntExpr.SeatOf(PlayerRef.Opponent),
    IntExpr.PlayerCounter(PlayerRef.Opponent, "store"), IntExpr.ZoneSize(PlayerRef.Opponent, HiddenZone.EXILE),
    BoolExpr.HasKeyword(BT, "flying"), BoolExpr.IsType(BT, setOf("Ship")), BoolExpr.IsExhausted(BT), BoolExpr.IsToken(BT),
    BoolExpr.Const(true), BoolExpr.Cmp(lit(1), CmpOp.GTE, x), BoolExpr.And(listOf(BoolExpr.Const(true))),
    BoolExpr.Or(listOf(BoolExpr.Const(false))), BoolExpr.Not(BoolExpr.Const(true)), BoolExpr.HasType(setOf("Ship")),
    // filters / zones / targets
    FILTER, FILTER.copy(zone = ZoneScoping.SameAs(BoundTarget(ATTACKER), eitherSide = true)), ZoneScoping.Any, ZoneScoping.SameAs(), ZoneScoping.SameAs(eitherSide = true), ZoneScoping.SameAs(BoundTarget(CHOSEN), eitherSide = true), ZoneScoping.Named("lane"),
    ZoneScoping.Exact(ZoneRef("lane", "P1")), ZoneRef("lane", "P1"), BT,
    CardFilter(setOf("Creature"), "Grunt", "run", 4, 1),
    // CharOp
    CharOp.PlusPT(lit(1), lit(2)), CharOp.SetPT(lit(3), lit(3)), CharOp.GrantKeyword("flying"),
    CharOp.AddType("Artifact"), CharOp.SetTypes(setOf("Elemental")), CharOp.RemoveAbilities, CharOp.GrantAbility(ABILITY),
    CharOp.Bands("level", listOf(CharOp.Bands.Band(2, lit(3), lit(3))), fieldA = "hull", fieldB = "fast"),
    CharOp.Bands.Band(2, lit(3), lit(4)), CharOp.PlusField("fast", lit(1)), CharOp.SetField("hull", lit(4)),
    // statics
    RuleMod.Cant(RuleAction.GAIN_LIFE, PlayerRef.Opponent), RuleMod.CantAttack(FILTER, PlayerRef.You), RuleMod.CantBlock(FILTER),
    RuleMod.CantActivate(FILTER), RuleMod.ReduceDamage(FILTER, lit(1), BoolExpr.Const(true)),
    CostMod(PlayerRef.You, setOf("Ship"), mapOf("" to -1)),
    StaticSpec(FILTER, listOf(CharOp.GrantKeyword("swift")), BoolExpr.Const(true), layer = 6),
    Statics(
        chars = listOf(StaticSpec(FILTER, listOf(CharOp.RemoveAbilities))), rules = listOf(RuleMod.CantBlock(FILTER)),
        costs = listOf(CostMod(PlayerRef.You, setOf("Ship"), mapOf("" to -1))),
        replacements = listOf(ReplacementDoc.DeathToExile(FILTER)),
    ),
    Duration.Permanent, Duration.EndOfTurn, Duration.EndOfNextTurn, Duration.While(BoolExpr.Const(true)),
    EventPattern.OnPhase("end", PlayerRef.Opponent), EventPattern.AnyTurnBegan, EventPattern.Never,
    EventPattern.SelfEnters, EventPattern.SelfLeaves, EventPattern.SelfAttacks,
    EventPattern.Enters(setOf("Ship"), PlayerRef.You, other = true), EventPattern.Cast(setOf("Instant"), null),
    EventPattern.CounterCrosses("lore", 2, downward = true), EventPattern.SelfDealsDamage(true),
    EventPattern.Dies(emptySet(), PlayerRef.Opponent), EventPattern.Dies(setOf("Ship"), filter = FILTER), EventPattern.MovesIntoThisZone,
    EventPattern.Damaged(FILTER, combatOnly = true), EventPattern.Damaged(FILTER, step = "fast"), EventPattern.PlayerDamaged(PlayerRef.You, combatOnly = true), EventPattern.OnCombatStep("fast", PlayerRef.Opponent),
    // costs and abilities
    COST, CounterPayment("charge", 2, FILTER), ABILITY,
    Characteristics("Soldier", setOf("Creature"), mapOf("power" to 1), setOf("swift"), true, listOf(ABILITY)),
    // triggers / replacements
    TriggerDoc.SelfEnters(DRAW, 1), TriggerDoc.SelfLeaves(DRAW, 1), TriggerDoc.SelfAttacks(DRAW, 1),
    TriggerDoc.YouCastType(setOf("Instant"), DRAW, 1), TriggerDoc.OnYourPhase("upkeep", DRAW, 1),
    TriggerDoc.CounterThreshold("lore", 3, DRAW, 1, downward = true),
    TriggerDoc.SelfDealsDamage(true, DRAW, 1), TriggerDoc.CreatureDies(PlayerRef.Opponent, DRAW, 1, types = setOf("Ship")),
    TriggerDoc.On(EventPattern.Damaged(FILTER), DRAW, 1),
    ReplacementDoc.DamageToSacrificeSelf(FILTER, "fast"), ReplacementDoc.PreventDamageTo(FILTER, "slow"),
    ReplacementDoc.DamageToRemoveCounter(FILTER, "shield", "fast"), ReplacementDoc.DeathToExile(FILTER),
    ReplacementDoc.Replace(EventPattern.Damaged(FILTER, step = "fast"), Effect.Proceed(IntExpr.EventAmount - 1)),
    // cards
    ArtRect(0.1f, 0.2f, 0.5f, 0.5f), CounterDef("lore", lit(1)), FACE, CARD,
    Recast(HiddenZone.EXILE, Cost(mana = mapOf("" to 3)), HiddenZone.LIBRARY_BOTTOM),
    // rules
    LAYOUT, TYPE, PlayZoneDef("lane", ZoneScope.PER_PLAYER, 3, setOf("fast"), 1, Depth.BACK),
    HiddenZoneDef("pool", alwaysVisible = false), STEP, COMBAT, ScreenRule("back", "front"), RangeFields("close", "far"), StanceDef("braced", "grit"),
    BlockRule.needsKeyword("flying", "reach"), BlockRule.needsStat("big", "power", 3), BlockRule.cantBlock("wall"),
    CombatDoc.Preset("swu"), CombatDoc.Program(COMBAT.lowered()),
    // Every shape a combat program takes: an attack program, and free
    // combat's reach written out over the grid.
    CombatDoc.Program(HEARTHSTONE_COMBAT.lowered()), CombatDoc.Program(ONE_PIECE_COMBAT.lowered()),
    CombatDoc.Program(YUGIOH_COMBAT.lowered()), CombatDoc.Program(LANE_GRID_CORE_COMBAT.lowered()),
    CombatDoc.Program(FRONT_BACK_COMBAT.lowered()),
    PHASE, TurnStructure(listOf(PHASE), TurnMode.SHARED), MulliganRule(1, false), PARAMS,
    ResourceModel.None, ResourceModel.Ramp(2, 8, false, 1, "energy"), ResourceModel.CardDriven(2, setOf("Ship")),
    PlayerCounterDef("hull", 30, true, 0, 40), CounterKindDef("shield", cancels = "charge"), RULES,
    // decks and the game
    DeckSlotDef("Leader", setOf("Leader"), 2, "hand"), IdentityRule(listOf("Leader"), setOf("red"), false),
    DECK_RULES, DeckEntry("Grunt", 3), DECK, SetDoc("Alpha", listOf(CARD)),
    GameDoc("Skirmish", RULES, listOf(SetDoc("Alpha", listOf(CARD))), listOf(DECK), DECK_RULES, "g1", 7, "g0", "a fork", "#5B9CF5", "c1"),
    // the session format
    Answer.Pass, Answer.Concede, Answer.PlayCard(4, CastZone.Declared("pool"), face = 1, zoneDef = "lane"),
    Answer.ActivateAbility(3, 1), Answer.Target(2), Answer.Number(5),
    Answer.Attackers(mapOf(3 to CombatTarget.Player("P1"))), Answer.Blockers(mapOf(4 to 3)),
    Answer.CombatTgt(CombatTarget.Obj(3)), Answer.Blocker(4), Answer.Modes(listOf(1, 0)), Answer.Cards(listOf(7, 8)),
    // the sandbox's table edits
    Answer.Edit(TableEdit.Conjure("bolt", "P1", EditZone.LIBRARY_TOP)), TableEdit.Conjure("ox", "P0", EditZone.GRAVEYARD), TableEdit.Move(5, EditZone.EXILE),
    TableEdit.Move(9, EditZone.BATTLEFIELD, owner = "P1"),
    TableEdit.SetCounter("hull", 2, player = "P0", id = 6), TableEdit.Draw("P1", 2),
    CastZone.Std(HiddenZone.GRAVEYARD), CastZone.Declared("pool"), CombatTarget.Player("P1"), CombatTarget.Obj(3),
)

// -- the reflective machinery ----------------------------------------------

private fun isModelClass(c: Class<*>) = c.name.startsWith("ccg.") && !c.isEnum && !c.isPrimitive

private fun componentsOf(c: Class<*>): List<java.lang.reflect.Method> =
    c.methods.filter { it.parameterCount == 0 && Regex("component\\d+").matches(it.name) }
        .sortedBy { it.name.removePrefix("component").toInt() }

private fun fieldNames(c: Class<*>): List<String> =
    c.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.map { it.name }

private fun classesIn(t: Type): List<Class<*>> = when (t) {
    is Class<*> -> listOf(t)
    is ParameterizedType -> classesIn(t.rawType) + t.actualTypeArguments.flatMap { classesIn(it) }
    is WildcardType -> t.upperBounds.flatMap { classesIn(it) }
    else -> emptyList()
}

/** Every concrete model class reachable from the persisted roots, with the
 *  field path that first reached it (for the failure message). */
private fun reachable(): Map<Class<*>, String> {
    val seen = linkedMapOf<Class<*>, String>()
    fun visit(c: Class<*>, via: String) {
        if (!isModelClass(c) || c in seen || c in OPAQUE || c in IN_PROCESS) return
        if (c.isInterface || Modifier.isAbstract(c.modifiers)) {
            val subs = c.permittedSubclasses ?: error("$via reaches ${c.name}, an open interface -- " +
                "a persisted type must be sealed or listed in OPAQUE behind a NOT_PERSISTED field")
            for (s in subs) visit(s, via)
            return
        }
        seen[c] = via
        val names = fieldNames(c)
        componentsOf(c).forEachIndexed { i, m ->
            val field = "${c.simpleName}.${names.getOrElse(i) { m.name }}"
            for (t in classesIn(m.genericReturnType)) {
                if (t in OPAQUE && field !in NOT_PERSISTED) error("$field holds ${t.simpleName}, a closure, and is not in NOT_PERSISTED")
                visit(t, field)
            }
        }
    }
    for (r in ROOTS) visit(r, r.simpleName)
    return seen
}

private fun neutral(t: Class<*>): Any? = when (t) {
    Integer.TYPE -> 0
    java.lang.Boolean.TYPE -> false
    java.lang.Long.TYPE -> 0L
    java.lang.Float.TYPE -> 0f
    java.lang.Double.TYPE -> 0.0
    else -> null
}

/** The fields of `x` whose value equals the class's declared default.
 *
 *  Calls the synthetic `(params..., masks, DefaultConstructorMarker)`
 *  constructor with ONE parameter's mask bit set and a neutral argument in its
 *  place. A parameter with a default comes back as that default; one without
 *  comes back as the neutral argument, or throws its null check. Either way the
 *  field is "at its default" exactly when it comes back equal to the specimen. */
private fun fieldsAtDefault(x: Any): List<String> {
    val c = x.javaClass
    val comps = componentsOf(c)
    if (comps.isEmpty()) return emptyList()
    val n = comps.size
    val masks = (n + 31) / 32
    val ctor = c.declaredConstructors.firstOrNull {
        it.parameterCount == n + masks + 1 && it.parameterTypes.last().name == "kotlin.jvm.internal.DefaultConstructorMarker"
    } ?: return emptyList()
    ctor.isAccessible = true
    val values = comps.map { it.invoke(x) }
    val names = fieldNames(c)
    val out = mutableListOf<String>()
    for (i in 0 until n) {
        val args = arrayOfNulls<Any>(n + masks + 1)
        for (j in 0 until n) args[j] = values[j]
        args[i] = neutral(ctor.parameterTypes[i])
        for (m in 0 until masks) args[n + m] = if (i / 32 == m) (1 shl (i % 32)) else 0
        val built = try { ctor.newInstance(*args) } catch (_: InvocationTargetException) { continue }
        if (comps[i].invoke(built) == values[i]) out += "${c.simpleName}.${names.getOrElse(i) { "component${i + 1}" }}"
    }
    return out
}

/** The first path at which two model values differ -- "GameDoc.sets[0].cards[3]
 *  .faces[0].castEffect.counter: store != life" -- so a failure names the field
 *  instead of printing two whole games. */
internal fun firstDiff(a: Any?, b: Any?, path: String = ""): String? {
    if (a == b) return null
    if (a == null || b == null || a.javaClass != b.javaClass) return "$path: $a != $b"
    if (a is List<*> && b is List<*>) {
        if (a.size != b.size) return "$path: size ${a.size} != ${b.size}"
        return a.indices.firstNotNullOfOrNull { firstDiff(a[it], b[it], "$path[$it]") }
    }
    if (a is Map<*, *> && b is Map<*, *>) {
        if (a.keys != b.keys) return "$path: keys ${a.keys} != ${b.keys}"
        return a.keys.firstNotNullOfOrNull { firstDiff(a[it], b[it], "$path[$it]") }
    }
    val comps = componentsOf(a.javaClass)
    if (comps.isEmpty()) return "$path: $a != $b"
    val names = fieldNames(a.javaClass)
    return comps.indices.firstNotNullOfOrNull { i ->
        firstDiff(comps[i].invoke(a), comps[i].invoke(b), "$path.${names.getOrElse(i) { comps[i].name }}")
    } ?: "$path: $a != $b"
}

private fun roundTripOf(x: Any): (Any) -> Any =
    ROUND_TRIPS.firstOrNull { it.first.isInstance(x) }?.second ?: error("no round trip registered for ${x.javaClass.name}")

internal fun codecChecks() {
    println()
    println("Codec completeness -- every persisted field survives JSON")

    check("every class the save format can reach has a specimen") {
        val reached = reachable()
        val have = SPECIMENS.map { it.javaClass }.toSet()
        val missing = reached.filterKeys { it !in have }.map { (c, via) -> "${c.name} (via $via)" }
        assertTrue(missing.isEmpty(), "no specimen for: ${missing.joinToString("; ")}")
    }

    check("no specimen leaves a field at its default -- a dropped field could hide there") {
        // Per CLASS: a field is covered when ANY specimen of it sets the field
        // -- a dropped field fails that specimen's round trip. Per specimen
        // was stricter than the risk, and unsatisfiable for fields that
        // exclude each other (DealDamage's target / player).
        val lazy = SPECIMENS.groupBy { it.javaClass }.values
            .flatMap { xs -> xs.map { fieldsAtDefault(it).toSet() }.reduce { a, b -> a intersect b } }
            .filter { it !in NOT_PERSISTED }.distinct()
        assertTrue(lazy.isEmpty(), "set these away from their defaults: ${lazy.joinToString(", ")}")
    }

    check("every specimen round-trips through its codec unchanged") {
        val broken = SPECIMENS.mapNotNull { s ->
            val back = try { roundTripOf(s)(s) } catch (e: Throwable) { return@mapNotNull "${s.javaClass.simpleName}: threw ${e.message}" }
            firstDiff(s, back, s.javaClass.simpleName)
        }
        assertTrue(broken.isEmpty(), "\n    " + broken.joinToString("\n    "))
    }

    check("every bundled game round-trips whole, before and after ids are minted") {
        var n = 0
        val games = listOf(EPR_SKIRMISH, CORE_BUNDLE) + SAMPLE_GAMES
        val broken = games.flatMap { g ->
            val minted = g.withCardIds { "id${n++}" }
            listOfNotNull(
                firstDiff(g, gameDocFromJson(gameDocToJson(g)), g.name),
                firstDiff(minted, gameDocFromJson(gameDocToJson(minted)), "${g.name} (minted)"),
            )
        }
        assertTrue(broken.isEmpty(), "\n    " + broken.joinToString("\n    "))
    }
}
