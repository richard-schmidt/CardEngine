package ccgui

import ccg.HiddenZone
import ccg.zoneName
import ccg.ActivatedAbility
import ccg.CharOp
import ccg.Cost
import ccg.Effect
import ccg.EventPattern
import ccg.IntExpr
import ccg.RuleMod
import ccg.Rules
import ccg.TriggerDoc
import ccg.PlayerRef
import ccg.creatures
import ccg.lit
import ccg.refersTo
import ccg.renameVar
import ccg.substituteTarget
import ccg.permanents

// ---------------------------------------------------------------------------
// `ccgui` -- the Creator's and Player's DECISION LOGIC, outside Compose so the
// suite can reach it: plain Kotlin over engine types, no Compose, no android.*
// (a test.sh guard).
//
// This file holds the KIND CATALOGUES: for each sealed engine hierarchy the
// editor exposes, the menu labels and the label <-> case mappings. A new engine
// case compiles and is simply never offered unless checked, so `UiTest.kt`
// round-trips every one against an exhaustive `when`.
// ---------------------------------------------------------------------------

/** A card is a SPELL iff it has at least one type and EVERY type is a spell type
 *  (delegates to `Rules.isSpell`). A card with any permanent type is played,
 *  even with a cast effect. */
fun isSpellCard(types: Set<String>, rules: Rules): Boolean = rules.isSpell(types)

fun litOf(e: IntExpr): Int = (e as? IntExpr.Lit)?.value ?: 0

/** Phase names offered by the trigger / event-pattern pickers. */
const val PHASES = "untap,upkeep,draw,main,combat,main2,end"

// -- CharOp ----------------------------------------------------------------

val CHAR_OPS = listOf(
    "get +P/+T", "become P/T", "have a keyword", "be an added type", "become these types",
    "lose all abilities", "P/T by counter (bands)", "have an activated ability",
    // a non-P/T game (ships use hull/fast/slow) needs
    // to boost a field by NAME, not the hard-coded "power"/"toughness" pair.
    "boost a named field",
    // the general form of "become P/T", as the one above is of "+P/+T".
    "set a named field",
)

fun charOpOfKind(k: String): CharOp = when (k) {
    CHAR_OPS[0] -> CharOp.PlusPT(lit(1), lit(1))
    CHAR_OPS[1] -> CharOp.SetPT(lit(1), lit(1))
    CHAR_OPS[2] -> CharOp.GrantKeyword("flying")
    CHAR_OPS[3] -> CharOp.AddType("Artifact")
    CHAR_OPS[4] -> CharOp.SetTypes(setOf("Creature"))
    CHAR_OPS[6] -> CharOp.Bands("level", listOf(CharOp.Bands.Band(2, lit(3), lit(3)), CharOp.Bands.Band(5, lit(6), lit(6))))
    CHAR_OPS[7] -> CharOp.GrantAbility(ActivatedAbility(Cost(tapSource = true), Effect.Draw(PlayerRef.You, lit(1))))
    CHAR_OPS[8] -> CharOp.PlusField("hull", lit(1))
    CHAR_OPS[9] -> CharOp.SetField("hull", lit(1))
    else -> CharOp.RemoveAbilities
}

fun charOpKind(o: CharOp): String = when (o) {
    is CharOp.PlusPT -> CHAR_OPS[0]
    is CharOp.SetPT -> CHAR_OPS[1]
    is CharOp.GrantKeyword -> CHAR_OPS[2]
    is CharOp.AddType -> CHAR_OPS[3]
    is CharOp.SetTypes -> CHAR_OPS[4]
    CharOp.RemoveAbilities -> CHAR_OPS[5]
    is CharOp.Bands -> CHAR_OPS[6]
    is CharOp.GrantAbility -> CHAR_OPS[7]
    is CharOp.PlusField -> CHAR_OPS[8]
    is CharOp.SetField -> CHAR_OPS[9]
}

/** Counter kinds the editors offer. The engine takes any string; these are
 *  the ones with meaning elsewhere in the model (Saga lore, damage counters,
 *  loyalty) plus the universal one. */
val COUNTER_KINDS = listOf("+1/+1", "-1/-1", "charge", "lore", "loyalty", "defense")

// -- TriggerDoc ------------------------------------------------------------

val TRIGGER_KINDS = listOf(
    "when this enters play", "when this leaves play", "when this attacks",
    "whenever you cast a type", "at the start of your phase",
    "when a counter reaches K (chapter)", "whenever a creature dies",
    "whenever this deals damage", "whenever an event happens…",
)

/** `defaultPhase` is the phase an "on your phase" trigger is SEEDED with: the
 *  game's own phase, since a seed naming a phase the game lacks never fires. */
fun triggerOfKind(kind: String, eff: Effect, order: Int, defaultPhase: String = "upkeep"): TriggerDoc = when (kind) {
    TRIGGER_KINDS[0] -> TriggerDoc.SelfEnters(eff, order)
    TRIGGER_KINDS[1] -> TriggerDoc.SelfLeaves(eff, order)
    TRIGGER_KINDS[2] -> TriggerDoc.SelfAttacks(eff, order)
    TRIGGER_KINDS[3] -> TriggerDoc.YouCastType(setOf("Instant"), eff, order)
    TRIGGER_KINDS[4] -> TriggerDoc.OnYourPhase(defaultPhase, eff, order)
    TRIGGER_KINDS[5] -> TriggerDoc.CounterThreshold("lore", 1, eff, order)
    TRIGGER_KINDS[7] -> TriggerDoc.SelfDealsDamage(combatOnly = true, effect = eff, order = order)
    TRIGGER_KINDS[8] -> TriggerDoc.On(EventPattern.Damaged(creatures().yours()), eff, order)
    else -> TriggerDoc.CreatureDies(null, eff, order)
}

fun triggerKind(t: TriggerDoc): String = when (t) {
    is TriggerDoc.SelfEnters -> TRIGGER_KINDS[0]
    is TriggerDoc.SelfLeaves -> TRIGGER_KINDS[1]
    is TriggerDoc.SelfAttacks -> TRIGGER_KINDS[2]
    is TriggerDoc.SelfDealsDamage -> TRIGGER_KINDS[7]
    is TriggerDoc.YouCastType -> TRIGGER_KINDS[3]
    is TriggerDoc.OnYourPhase -> TRIGGER_KINDS[4]
    is TriggerDoc.CounterThreshold -> TRIGGER_KINDS[5]
    is TriggerDoc.CreatureDies -> TRIGGER_KINDS[6]
    is TriggerDoc.On -> TRIGGER_KINDS[8]
}

/** This trigger with a new effect, everything else KEPT (rebuilding from the
 *  kind reset what its own pills had set). */
fun TriggerDoc.withEffect(e: Effect): TriggerDoc = when (this) {
    is TriggerDoc.SelfEnters -> copy(effect = e)
    is TriggerDoc.SelfLeaves -> copy(effect = e)
    is TriggerDoc.SelfAttacks -> copy(effect = e)
    is TriggerDoc.YouCastType -> copy(effect = e)
    is TriggerDoc.OnYourPhase -> copy(effect = e)
    is TriggerDoc.CounterThreshold -> copy(effect = e)
    is TriggerDoc.SelfDealsDamage -> copy(effect = e)
    is TriggerDoc.CreatureDies -> copy(effect = e)
    is TriggerDoc.On -> copy(effect = e)
}

/** This trigger with a new `order`, everything else kept (see `withEffect`). */
fun TriggerDoc.withOrder(n: Int): TriggerDoc = when (this) {
    is TriggerDoc.SelfEnters -> copy(order = n)
    is TriggerDoc.SelfLeaves -> copy(order = n)
    is TriggerDoc.SelfAttacks -> copy(order = n)
    is TriggerDoc.YouCastType -> copy(order = n)
    is TriggerDoc.OnYourPhase -> copy(order = n)
    is TriggerDoc.CounterThreshold -> copy(order = n)
    is TriggerDoc.SelfDealsDamage -> copy(order = n)
    is TriggerDoc.CreatureDies -> copy(order = n)
    is TriggerDoc.On -> copy(order = n)
}

// -- EventPattern ----------------------------------------------------------

/** The events a delayed effect or a general trigger can wait for. Every
 *  `EventPattern` case is here (pinned by UiTest), so the matcher and its
 *  editor cannot drift the way the two event languages once did. */
val EVENT_KINDS = listOf(
    "a phase begins", "any turn begins", "never",
    "this enters play", "this leaves play", "this attacks",
    "a permanent enters", "a spell is cast", "a counter on this reaches K",
    "this deals damage", "a permanent dies", "a permanent moves into this zone",
    "a permanent is dealt damage", "a player is dealt damage", "a combat step begins",
)

/** A seed for each kind. `defaultPhase` as for `triggerOfKind`: the game's own
 *  last phase, never a literal it may not have. */
fun eventOfKind(kind: String, defaultPhase: String = "end"): EventPattern = when (kind) {
    EVENT_KINDS[0] -> EventPattern.OnPhase(defaultPhase)
    EVENT_KINDS[1] -> EventPattern.AnyTurnBegan
    EVENT_KINDS[3] -> EventPattern.SelfEnters
    EVENT_KINDS[4] -> EventPattern.SelfLeaves
    EVENT_KINDS[5] -> EventPattern.SelfAttacks
    EVENT_KINDS[6] -> EventPattern.Enters(other = true)
    EVENT_KINDS[7] -> EventPattern.Cast()
    EVENT_KINDS[8] -> EventPattern.CounterCrosses("lore", 1)
    EVENT_KINDS[9] -> EventPattern.SelfDealsDamage()
    EVENT_KINDS[10] -> EventPattern.Dies()
    EVENT_KINDS[11] -> EventPattern.MovesIntoThisZone
    EVENT_KINDS[12] -> EventPattern.Damaged(creatures().yours())
    EVENT_KINDS[13] -> EventPattern.PlayerDamaged(PlayerRef.Opponent)
    EVENT_KINDS[14] -> EventPattern.OnCombatStep(ccg.DECLARE_BLOCKERS_STEP, PlayerRef.You)
    else -> EventPattern.Never
}

fun eventKind(p: EventPattern): String = when (p) {
    is EventPattern.OnPhase -> EVENT_KINDS[0]
    EventPattern.AnyTurnBegan -> EVENT_KINDS[1]
    EventPattern.Never -> EVENT_KINDS[2]
    EventPattern.SelfEnters -> EVENT_KINDS[3]
    EventPattern.SelfLeaves -> EVENT_KINDS[4]
    EventPattern.SelfAttacks -> EVENT_KINDS[5]
    is EventPattern.Enters -> EVENT_KINDS[6]
    is EventPattern.Cast -> EVENT_KINDS[7]
    is EventPattern.CounterCrosses -> EVENT_KINDS[8]
    is EventPattern.SelfDealsDamage -> EVENT_KINDS[9]
    is EventPattern.Dies -> EVENT_KINDS[10]
    EventPattern.MovesIntoThisZone -> EVENT_KINDS[11]
    is EventPattern.Damaged -> EVENT_KINDS[12]
    is EventPattern.PlayerDamaged -> EVENT_KINDS[13]
    is EventPattern.OnCombatStep -> EVENT_KINDS[14]
}

/** What a trigger's effect acts on when it names no chosen target: this
 *  permanent, or the one the event is about. The editor offers the switch
 *  and renders "this" / "the permanent that triggered it" from it. */
fun triggerSubjectOf(e: Effect): String = if (e.refersTo(ccg.TRIGGER)) ccg.TRIGGER else ccg.SELF

/** Flip a trigger's effect between acting on this permanent and on the
 *  triggering one -- every in-scope reference moves together. */
fun retargetTrigger(e: Effect, to: String): Effect =
    if (to == ccg.TRIGGER) e.renameVar(ccg.SELF, ccg.TRIGGER) else e.renameVar(ccg.TRIGGER, ccg.SELF)


// -- RuleMod ---------------------------------------------------------------

val RULE_MODS = listOf("a player can't …", "can't attack", "can't block", "can't activate abilities", "takes less damage")

/** The actions `RuleMod.Cant` is actually honoured for -- keep in step with
 *  the engine (`GameState.forbids` callers). */
val CANT_ACTIONS = ccg.RuleAction.entries.map { it.key }

fun ruleModOfKind(k: String): RuleMod = when (k) {
    RULE_MODS[1] -> RuleMod.CantAttack(creatures(), defender = PlayerRef.You)
    RULE_MODS[2] -> RuleMod.CantBlock(creatures().theirs())
    RULE_MODS[3] -> RuleMod.CantActivate(permanents().theirs())
    RULE_MODS[4] -> RuleMod.ReduceDamage(creatures().yours(), lit(1))
    else -> RuleMod.Cant(ccg.RuleAction.GAIN_LIFE, PlayerRef.Opponent)
}

fun ruleModKind(r: RuleMod): String = when (r) {
    is RuleMod.Cant -> RULE_MODS[0]
    is RuleMod.CantAttack -> RULE_MODS[1]
    is RuleMod.CantBlock -> RULE_MODS[2]
    is RuleMod.CantActivate -> RULE_MODS[3]
    is RuleMod.ReduceDamage -> RULE_MODS[4]
}

// -- hidden zones ----------------------------------------------------------
// Derived from the enum and the engine's own wording, so a new zone shows up
// in the menus without a second list to keep in step.

val HIDDEN_ZONES: List<String> = HiddenZone.entries.map { zoneName(it) }

fun zoneOfName(n: String): HiddenZone =
    HiddenZone.entries.firstOrNull { zoneName(it) == n } ?: HiddenZone.LIBRARY

// ---------------------------------------------------------------------------
// WHICH STAT FIELDS A CARD'S EDITOR SHOWS.
// ---------------------------------------------------------------------------

/** The stat-field rows the card editor draws, in order: the fields the card's
 *  TYPES declare, plus any the card carries (kept editable if its type stops
 *  declaring them), each once. `order` is the preferred leading order; the rest
 *  sort alphabetically after, declared before ad-hoc. Here, not in the
 *  composable, so the union is tested. */
fun editableFields(declared: List<String>, present: Set<String>, order: List<String>): List<String> =
    (declared + present)
        .distinct()
        .sortedWith(
            compareBy(
                { it !in declared },
                { order.indexOf(it).takeIf { i -> i >= 0 } ?: order.size },
                { it },
            ),
        )

// -- PermFilter.where --------------------------------------------------

/** The "and also…" tests a filter can add about each candidate. Each is a
 *  `BoolExpr` about `SUBJECT`; "custom" is one this menu cannot produce (a
 *  file can say more than the menu does). */
val WHERE_KINDS = listOf(
    "nothing more", "has a keyword", "is not of a type", "is exhausted", "is ready",
    "is a token", "is not a token", "a stat is at least N", "a stat is at most N",
    "lacks a keyword",
)

private val SUBJ = ccg.BoundTarget(ccg.SUBJECT)

fun whereOfKind(kind: String): ccg.BoolExpr? = when (kind) {
    WHERE_KINDS[1] -> ccg.BoolExpr.HasKeyword(SUBJ, "flying")
    WHERE_KINDS[2] -> ccg.BoolExpr.Not(ccg.BoolExpr.IsType(SUBJ, setOf("Creature")))
    WHERE_KINDS[3] -> ccg.BoolExpr.IsExhausted(SUBJ)
    WHERE_KINDS[4] -> ccg.BoolExpr.Not(ccg.BoolExpr.IsExhausted(SUBJ))
    WHERE_KINDS[5] -> ccg.BoolExpr.IsToken(SUBJ)
    WHERE_KINDS[6] -> ccg.BoolExpr.Not(ccg.BoolExpr.IsToken(SUBJ))
    WHERE_KINDS[7] -> ccg.BoolExpr.Cmp(IntExpr.TargetField(SUBJ, "power"), ccg.CmpOp.GTE, lit(3))
    WHERE_KINDS[8] -> ccg.BoolExpr.Cmp(IntExpr.TargetField(SUBJ, "power"), ccg.CmpOp.LTE, lit(2))
    WHERE_KINDS[9] -> ccg.BoolExpr.Not(ccg.BoolExpr.HasKeyword(SUBJ, "flying"))
    else -> null
}

fun whereKind(b: ccg.BoolExpr?): String = when {
    b == null -> WHERE_KINDS[0]
    b is ccg.BoolExpr.HasKeyword && b.target == SUBJ -> WHERE_KINDS[1]
    b is ccg.BoolExpr.Not && (b.term as? ccg.BoolExpr.IsType)?.target == SUBJ -> WHERE_KINDS[2]
    b is ccg.BoolExpr.IsExhausted && b.target == SUBJ -> WHERE_KINDS[3]
    b is ccg.BoolExpr.Not && (b.term as? ccg.BoolExpr.IsExhausted)?.target == SUBJ -> WHERE_KINDS[4]
    b is ccg.BoolExpr.IsToken && b.target == SUBJ -> WHERE_KINDS[5]
    b is ccg.BoolExpr.Not && (b.term as? ccg.BoolExpr.IsToken)?.target == SUBJ -> WHERE_KINDS[6]
    b is ccg.BoolExpr.Cmp && (b.a as? IntExpr.TargetField)?.target == SUBJ && b.op == ccg.CmpOp.GTE && b.b is IntExpr.Lit -> WHERE_KINDS[7]
    b is ccg.BoolExpr.Cmp && (b.a as? IntExpr.TargetField)?.target == SUBJ && b.op == ccg.CmpOp.LTE && b.b is IntExpr.Lit -> WHERE_KINDS[8]
    b is ccg.BoolExpr.Not && (b.term as? ccg.BoolExpr.HasKeyword)?.target == SUBJ -> WHERE_KINDS[9]
    else -> "custom"
}

// -- conditions: every BoolExpr, built as a tree ----------------------
// The condition editor's own menu. Where `WHERE_KINDS` offers ready-made
// tests about the permanent a filter checks, this builds ANY condition --
// what authored reach is written in ("the target stands in the attacker's
// lane, or the attacker has reach").

val COND_KINDS = listOf(
    "always", "never", "compare two numbers", "all of…", "any of…", "not…",
    "has a keyword", "is of a type", "is exhausted", "is a token", "has a stat",
    "stands in a front berth", "stands in a back berth", "stands in a player's zone", "stands in a shared zone",
)

/** A seed for each kind, about `who`. */
fun condOfKind(kind: String, who: ccg.BoundTarget = ccg.BoundTarget(ccg.CHOSEN)): ccg.BoolExpr = when (kind) {
    COND_KINDS[1] -> ccg.BoolExpr.Const(false)
    COND_KINDS[2] -> ccg.BoolExpr.Cmp(IntExpr.TargetField(who, "power"), ccg.CmpOp.GTE, lit(1))
    COND_KINDS[3] -> ccg.BoolExpr.And(listOf(ccg.BoolExpr.Const(true)))
    COND_KINDS[4] -> ccg.BoolExpr.Or(listOf(ccg.BoolExpr.Const(true)))
    COND_KINDS[5] -> ccg.BoolExpr.Not(ccg.BoolExpr.Const(true))
    COND_KINDS[6] -> ccg.BoolExpr.HasKeyword(who, "reach")
    COND_KINDS[7] -> ccg.BoolExpr.IsType(who, setOf("Creature"))
    COND_KINDS[8] -> ccg.BoolExpr.IsExhausted(who)
    COND_KINDS[9] -> ccg.BoolExpr.IsToken(who)
    COND_KINDS[10] -> ccg.BoolExpr.HasField(who, "power")
    COND_KINDS[11] -> ccg.BoolExpr.AtDepth(who, ccg.Depth.FRONT)
    COND_KINDS[12] -> ccg.BoolExpr.AtDepth(who, ccg.Depth.BACK)
    COND_KINDS[13] -> ccg.BoolExpr.ZoneOwner(who, ccg.PlayerRef.Defending)
    COND_KINDS[14] -> ccg.BoolExpr.ZoneOwner(who, null)
    else -> ccg.BoolExpr.Const(true)
}

/** Which kind a condition is. EXHAUSTIVE: a new `BoolExpr` must be placed. */
fun condKind(b: ccg.BoolExpr): String = when (b) {
    is ccg.BoolExpr.Const -> if (b.value) COND_KINDS[0] else COND_KINDS[1]
    is ccg.BoolExpr.Cmp -> COND_KINDS[2]
    is ccg.BoolExpr.And -> COND_KINDS[3]
    is ccg.BoolExpr.Or -> COND_KINDS[4]
    is ccg.BoolExpr.Not -> COND_KINDS[5]
    is ccg.BoolExpr.HasKeyword -> COND_KINDS[6]
    is ccg.BoolExpr.IsType, is ccg.BoolExpr.HasType -> COND_KINDS[7]
    is ccg.BoolExpr.IsExhausted -> COND_KINDS[8]
    is ccg.BoolExpr.IsToken -> COND_KINDS[9]
    is ccg.BoolExpr.HasField -> COND_KINDS[10]
    is ccg.BoolExpr.AtDepth -> if (b.depth == ccg.Depth.FRONT) COND_KINDS[11] else COND_KINDS[12]
    is ccg.BoolExpr.ZoneOwner -> if (b.who != null) COND_KINDS[13] else COND_KINDS[14]
}

/** Which permanent a condition or a read is about: the roles a variable can
 *  play. `ROLE_NAMES[i]` is the variable `ROLES[i]` names. */
val ROLES = listOf("this card", "the chosen one", "each one", "the triggering one", "the attacker", "the target", "the permanent tested")
private val ROLE_NAMES = listOf(ccg.SELF, ccg.CHOSEN, ccg.EACH, ccg.TRIGGER, ccg.ATTACKER, ccg.TARGET, ccg.SUBJECT)

fun roleOf(label: String): ccg.BoundTarget = ccg.BoundTarget(ROLE_NAMES[ROLES.indexOf(label).coerceAtLeast(0)])

/** A role's label; a variable of another name, or an object, reads as itself. */
fun roleLabel(t: ccg.BoundTarget): String =
    t.name?.let { n -> ROLE_NAMES.indexOf(n).takeIf { it >= 0 }?.let { ROLES[it] } ?: n } ?: "#${t.id}"

/** The permanent a one-permanent condition is about, or null for a
 *  combinator or a comparison. */
fun condSubject(b: ccg.BoolExpr): ccg.BoundTarget? = when (b) {
    is ccg.BoolExpr.HasKeyword -> b.target
    is ccg.BoolExpr.IsType -> b.target
    is ccg.BoolExpr.IsExhausted -> b.target
    is ccg.BoolExpr.IsToken -> b.target
    is ccg.BoolExpr.HasField -> b.target
    is ccg.BoolExpr.AtDepth -> b.target
    is ccg.BoolExpr.ZoneOwner -> b.target
    is ccg.BoolExpr.Const, is ccg.BoolExpr.Cmp, is ccg.BoolExpr.And, is ccg.BoolExpr.Or, is ccg.BoolExpr.Not,
    is ccg.BoolExpr.HasType -> null
}

/** The same condition about `t` instead. */
fun condAbout(b: ccg.BoolExpr, t: ccg.BoundTarget): ccg.BoolExpr = when (b) {
    is ccg.BoolExpr.HasKeyword -> b.copy(target = t)
    is ccg.BoolExpr.IsType -> b.copy(target = t)
    is ccg.BoolExpr.IsExhausted -> b.copy(target = t)
    is ccg.BoolExpr.IsToken -> b.copy(target = t)
    is ccg.BoolExpr.HasField -> b.copy(target = t)
    is ccg.BoolExpr.AtDepth -> b.copy(target = t)
    is ccg.BoolExpr.ZoneOwner -> b.copy(target = t)
    else -> b
}

val CMP_OPS = listOf("≥", "≤", ">", "<", "=", "≠")
private val CMP_OP_VALUES = listOf(ccg.CmpOp.GTE, ccg.CmpOp.LTE, ccg.CmpOp.GT, ccg.CmpOp.LT, ccg.CmpOp.EQ, ccg.CmpOp.NE)
fun cmpOpOf(label: String): ccg.CmpOp = CMP_OP_VALUES[CMP_OPS.indexOf(label).coerceAtLeast(0)]
fun cmpOpLabel(op: ccg.CmpOp): String = CMP_OPS[CMP_OP_VALUES.indexOf(op)]

// -- PlayerRef -----------------------------------------------------------

val PLAYER_REFS = listOf("the active player", "a chosen player", "its controller", "its owner", "the opponent", "each opponent", "the defending player")

/** `target` is what "its" means here -- the editor's implicit target. */
fun playerRefOfKind(kind: String, target: String): ccg.PlayerRef = when (kind) {
    PLAYER_REFS[0] -> ccg.PlayerRef.Active
    PLAYER_REFS[2] -> ccg.PlayerRef.ControllerOf(ccg.BoundTarget(target))
    PLAYER_REFS[3] -> ccg.PlayerRef.OwnerOf(ccg.BoundTarget(target))
    PLAYER_REFS[4] -> ccg.PlayerRef.Opponent
    PLAYER_REFS[5] -> ccg.PlayerRef.EachOpponent
    PLAYER_REFS[6] -> ccg.PlayerRef.Defending
    else -> ccg.PlayerRef.Chosen
}

fun playerRefKind(r: ccg.PlayerRef): String = when (r) {
    ccg.PlayerRef.Active -> PLAYER_REFS[0]
    ccg.PlayerRef.Chosen -> PLAYER_REFS[1]
    is ccg.PlayerRef.ControllerOf -> PLAYER_REFS[2]
    is ccg.PlayerRef.OwnerOf -> PLAYER_REFS[3]
    // "you, as you" re-seats nobody; the menu never offers it.
    ccg.PlayerRef.Opponent, ccg.PlayerRef.You -> PLAYER_REFS[4]
    ccg.PlayerRef.EachOpponent -> PLAYER_REFS[5]
    ccg.PlayerRef.Defending -> PLAYER_REFS[6]
    // Written only by the engine, never offered; shown as the nearest kind.
    is ccg.PlayerRef.Seat -> PLAYER_REFS[1]
}

/** A verb's own player slot ("who draws"): you, the opponent, or each
 *  opponent -- the one a game of three or more players needs. */
val WHO_REFS = listOf("you", "opponent", "each opponent")
fun whoRefOf(label: String): ccg.PlayerRef = when (label) {
    WHO_REFS[1] -> ccg.PlayerRef.Opponent
    WHO_REFS[2] -> ccg.PlayerRef.EachOpponent
    else -> ccg.PlayerRef.You
}

/** The arithmetic ops, as the number editor names them. */
val BIN_OPS = listOf("+", "−", "×", "÷", "min", "max")
fun binOpOf(label: String): ccg.BinOp = ccg.BinOp.entries[BIN_OPS.indexOf(label).coerceAtLeast(0)]
fun binOpLabel(op: ccg.BinOp): String = BIN_OPS[op.ordinal]
