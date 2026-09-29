package ccgui

import ccg.BoolExpr
import ccg.BoundTarget
import ccg.CHOSEN
import ccg.CardFilter
import ccg.CharOp
import ccg.Characteristics
import ccg.EACH
import ccg.Effect
import ccg.EventPattern
import ccg.HiddenZone
import ccg.IntExpr
import ccg.minus
import ccg.program
import ccg.attackProgram
import ccg.Statics
import ccg.PlayerRef
import ccg.ZoneRef
import ccg.creatures
import ccg.lit

// ---------------------------------------------------------------------------
// The verb catalogue -- what the Effect editor's "+ add effect" menu offers,
// and the seed value each entry produces. Extracted from the Compose file so
// `UiTest.kt` can assert the invariant that matters: EVERY `Effect` case the
// editor can render must be reachable from this menu. A new AST case that
// nobody can add through the UI is invisible.
// ---------------------------------------------------------------------------

data class Verb(val label: String, val make: () -> Effect)

val LEAF_VERBS = listOf(
    Verb("deal damage to opponent") { Effect.DamageOpponent(lit(3)) },
    Verb("you draw") { Effect.Draw(PlayerRef.You, lit(1)) },
    Verb("you gain life") { Effect.GainLife(PlayerRef.You, lit(3)) },
    Verb("you add mana") { Effect.AddMana(PlayerRef.You, mapOf("" to lit(1))) },
    Verb("you discard") { Effect.Discard(PlayerRef.You, lit(1)) },
    Verb("you draw, then discard some of THOSE") { Effect.DrawThenDiscard(PlayerRef.You, lit(2), lit(1)) },
    Verb("you sacrifice") { Effect.Sacrifice(PlayerRef.You, lit(1), creatures()) },
    Verb("create a token") { Effect.CreateToken(Characteristics("Soldier", setOf("Creature"), mapOf("power" to 1, "toughness" to 1)), lit(1)) },
    Verb("counter a spell") { Effect.CounterSpell() },
    Verb("do nothing") { Effect.NoOp },
)

/** The verbs that act on the bound target [tid]. "Set its combat stance" is
 *  offered only when the game's combat declares a stance ([stances]), and
 *  names the first: an undeclared stance is a compile error, so a verb
 *  seeded with a fixed name would stop every other game from starting. */
fun targetedVerbs(tid: String, stances: List<String> = emptyList()) = listOfNotNull(
    Verb("deal damage to it") { Effect.DealDamage(lit(3), BoundTarget(tid)) },
    Verb("destroy it") { Effect.Destroy(BoundTarget(tid)) },
    Verb("put counters on it") { Effect.AddCounter("+1/+1", lit(1), BoundTarget(tid)) },
    Verb("remove counters from it") { Effect.RemoveCounter("+1/+1", lit(1), BoundTarget(tid)) },
    Verb("transform it") { Effect.Transform(BoundTarget(tid)) },
    Verb("prevent damage to it") { Effect.PreventDamage(BoundTarget(tid), lit(3)) },
    Verb("make a token copy of it") { Effect.CopyOf(BoundTarget(tid)) },
    stances.firstOrNull()?.let { st -> Verb("set its combat stance") { Effect.SetCombatMode(BoundTarget(tid), st) } },
    Verb("move it to a zone") { Effect.MovePermanent(BoundTarget(tid), ZoneRef("battlefield")) },
    Verb("attach to it") { Effect.Attach(BoundTarget(tid)) },
    Verb("exhaust or ready it") { Effect.Tap(BoundTarget(tid)) },
    Verb("remove all damage from it") { Effect.ClearDamage(BoundTarget(tid)) },
    // Only inside a replacement ("... instead"); the compiler reports it anywhere else.
    Verb("let it happen, changed") { Effect.Proceed(IntExpr.EventAmount - 1) },
    Verb("return it to its owner's hand") { Effect.SendTo(BoundTarget(tid), HiddenZone.HAND) },
    Verb("gain control of it") { Effect.GainControl(BoundTarget(tid), ccg.Duration.EndOfTurn) },
)

val STRUCT_VERBS = listOf(
    Verb("choose a target, then…") { Effect.Choose(creatures(), Effect.DealDamage(lit(3), BoundTarget(CHOSEN))) },
    Verb("in order… (sequence)") { Effect.Sequence(listOf(Effect.Draw(PlayerRef.You, lit(1)))) },
    Verb("if… then… (conditional)") { Effect.If(BoolExpr.Const(true), Effect.Draw(PlayerRef.You, lit(1))) },
    Verb("choose one of… (modal)") { Effect.ChooseMode(listOf(Effect.DamageOpponent(lit(3)), Effect.GainLife(PlayerRef.You, lit(3)))) },
    Verb("choose several targets…") {
        Effect.ChooseMany(creatures(), lit(2), upTo = true, body = Effect.DealDamage(lit(1), BoundTarget(EACH)))
    },
    Verb("for each permanent…") { Effect.ForEach(creatures(), Effect.DealDamage(lit(1), BoundTarget(EACH))) },
    Verb("for each player…") { Effect.ForEachPlayer(Effect.Draw(PlayerRef.You, lit(1))) },
    Verb("as another player…") { Effect.AsPlayer(ccg.PlayerRef.Chosen, Effect.Draw(PlayerRef.You, lit(1))) },
    Verb("later, do… (delayed)") { Effect.Delayed(EventPattern.OnPhase("end"), Effect.Draw(PlayerRef.You, lit(1))) },
    Verb("pump affected creatures (modifier)") { Effect.ApplyModifier(creatures().yours(), listOf(CharOp.PlusPT(lit(1), lit(1)))) },
    Verb("create an emblem") { Effect.CreateEmblem(Statics()) },
    Verb("return cards from the graveyard") { Effect.ReturnFromDiscard(PlayerRef.You, lit(1)) },
    Verb("search a zone (tutor)") {
        Effect.SearchZone(PlayerRef.You, HiddenZone.LIBRARY, HiddenZone.HAND, lit(1), CardFilter(types = setOf("Creature")))
    },
    Verb("shuffle a zone") { Effect.Shuffle(PlayerRef.You, HiddenZone.LIBRARY) },
    Verb("mill from the top") { Effect.MoveTop(PlayerRef.You, lit(2), HiddenZone.GRAVEYARD) },
    Verb("look at the top (scry)") { Effect.LookAtTop(PlayerRef.You, lit(2), HiddenZone.LIBRARY_BOTTOM) },
)

/** The combat verbs. On a card, "declare attackers" is an extra combat;
 *  the others act on the combat under way. */
val COMBAT_VERBS = listOf(
    Verb("an extra combat (declare attackers…)") { ccg.COMBAT_PRESETS.getValue("mtg").program!! },
    Verb("declare blockers…") { Effect.DeclareBlockers(creatures().yours(), then = Effect.NoOp) },
    Verb("a combat response window") { Effect.CombatWindow },
    Verb("a combat damage step") { Effect.CombatDamage("extra", IntExpr.TargetField(BoundTarget(ccg.SUBJECT), "power")) },
    Verb("one attack (checked, then resolved)…") { ccg.COMBAT_PRESETS.getValue("hearthstone").attack!! },
    Verb("an attack exchanges damage") { Effect.Strike("strike", IntExpr.TargetField(BoundTarget(ccg.SUBJECT), "power")) },
    Verb("every fighter picks a target (free combat step)…") { (ccg.COMBAT_PRESETS.getValue("fastSlowLanes").program as Effect.Sequence).steps.first() },
    Verb("an attack compares stats") {
        Effect.Clash("clash", IntExpr.TargetField(BoundTarget(ccg.SUBJECT), "power"), IntExpr.TargetField(BoundTarget(ccg.SUBJECT), "power"))
    },
)

/** Everything the verb menu offers. `includeTargeted` is false inside a
 *  container that has no bound target to talk about (a Sequence step).
 *  [stances] are the ones the game's combat declares. */
fun allVerbs(implicitTarget: String, includeTargeted: Boolean = true, stances: List<String> = emptyList()): List<Verb> =
    LEAF_VERBS + (if (includeTargeted) targetedVerbs(implicitTarget, stances) else emptyList()) + STRUCT_VERBS + COMBAT_VERBS

/** What the `IntPill` menu offers. `target` is the bound target the
 *  target-relative reads should refer to. */
fun intKinds(target: String): List<Pair<String, IntExpr>> = listOf(
    "a number" to IntExpr.Lit(3),
    "X" to IntExpr.X,
    "# creatures you control" to IntExpr.CountPerms(creatures().yours()),
    "your hand size" to IntExpr.HandSize(PlayerRef.You),
    "opponent's life" to IntExpr.LifeOf(PlayerRef.Opponent),
    // read the bound target rather than the source
    "its power" to IntExpr.TargetField(BoundTarget(target), "power"),
    "its toughness" to IntExpr.TargetField(BoundTarget(target), "toughness"),
    "its +1/+1 counters" to IntExpr.TargetCounter(BoundTarget(target), "+1/+1"),
    "damage on it" to IntExpr.TargetDamage(BoundTarget(target)),
    // this target's share inside a dividing ChooseMany
    "its share (divided)" to IntExpr.Share,
    // inside a trigger, the event's amount ("that much")
    "that much (the event's amount)" to IntExpr.EventAmount,
    // any player counter, a hidden zone's size, and arithmetic
    "a player counter" to IntExpr.PlayerCounter(PlayerRef.You, ccg.LIFE),
    "cards in a zone" to IntExpr.ZoneSize(PlayerRef.You, HiddenZone.GRAVEYARD),
    "a calculation (a + b)" to IntExpr.Bin(ccg.BinOp.ADD, lit(1), lit(1)),
    // the game's own numbers, the turn, and turn order
    "a game parameter" to IntExpr.Param("cardsDrawnPerTurn"),
    "the turn number" to IntExpr.TurnNumber,
    "a player's seat (first = 0)" to IntExpr.SeatOf(PlayerRef.You),
    // where it stands, and a choice between two numbers
    "its lane" to IntExpr.LaneOf(BoundTarget(target)),
    "if … then … else" to IntExpr.Cond(BoolExpr.Const(true), lit(1), lit(0)),
)
