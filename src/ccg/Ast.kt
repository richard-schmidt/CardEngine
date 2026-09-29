package ccg

// ---------------------------------------------------------------------------
// The typed effect AST -- the free algebra of primitive card actions.
//
// ONE closed sealed hierarchy, interpreted by a recursive fold in Engine.kt;
// adding a card never edits the engine. Every value slot is an `IntExpr` and
// `If` takes a `BoolExpr` (Expr.kt): authorable, serialisable, no lambdas.
// This file is data only; evaluation lives in Eval.kt.
// ---------------------------------------------------------------------------

typealias ObjectId = Int

/** Readable player handles ("P0" / "P1") so assertion failures name a player. */
typealias PlayerId = String

/** What a target slot refers to: an object, or a named VARIABLE that a
 *  binder fills. Each binder declares the name it binds, so a `Choose`
 *  inside a `Choose` can name the outer one's pick. */
sealed interface Ref {
    data class Obj(val id: ObjectId) : Ref
    data class Var(val name: String) : Ref
}

/** A target slot. Legality is re-checked when the effect resolves -- a bound
 *  id is not trusted, because the target can leave the battlefield in
 *  between. `BoundTarget(id)` names an object, `BoundTarget(name)` a
 *  variable. */
data class BoundTarget(val ref: Ref) {
    constructor(id: ObjectId) : this(Ref.Obj(id))
    constructor(name: String) : this(Ref.Var(name))

    /** The object, or `NO_OBJECT` while this is still a variable. */
    val id: ObjectId get() = (ref as? Ref.Obj)?.id ?: NO_OBJECT

    /** The variable's name, or null once bound. */
    val name: String? get() = (ref as? Ref.Var)?.name
}

/** No object: real ids start at 1. What an unbound variable points at. */
const val NO_OBJECT: ObjectId = 0

// The DEFAULT variable names. A binder may declare another; these are
// what it binds when it does not, and what the fixed binders always bind.

/** The permanent the effect comes from -- bound where there is one (a
 *  trigger's or ability's own permanent), rebound by `GrantAbility`. */
const val SELF = "self"

/** A `Choose`'s pick. */
const val CHOSEN = "chosen"

/** The current permanent of a `ForEach` or a `ChooseMany`. */
const val EACH = "each"

/** The permanent the TRIGGERING EVENT is about ("it", "the creature that died"),
 *  bound from `GameEvent.subject` when a trigger fires; unbound (affects
 *  nothing) for an event with no permanent. Companion read: `IntExpr.EventAmount`. */
const val TRIGGER = "trigger"

/** The permanent a `PermFilter.where` is testing -- "a creature WITH flying",
 *  "a non-Ship", "a creature with power 3 or greater". Bound by the filter
 *  itself, per candidate (it evaluates with `EvalContext.subject`); nowhere
 *  else does it mean anything. */
const val SUBJECT = "subject"
/** The attacking permanent, and the one it attacks: bound by the attack verbs
 *  while they read reach and damage -- "a target in the attacker's lane". */
const val ATTACKER = "attacker"
const val TARGET = "target"

/** The names the old format spelled as negative ids; the JSON decoder still
 *  reads them. */
internal val LEGACY_SENTINELS: Map<Int, String> = mapOf(-1 to SELF, -2 to CHOSEN, -3 to EACH, -4 to TRIGGER, -5 to SUBJECT)

sealed interface Effect {

    /** Deal damage to a permanent -- or, with `player` set, to a player.
     *  Exactly one of the two: `target` is null for player damage. A player's
     *  damage goes through `GameEvent.PlayerDamaged`, so shields,
     *  replacements and triggers see it as they see any other. */
    data class DealDamage(val amount: IntExpr, val target: BoundTarget?, val player: PlayerRef? = null) : Effect {
        init { require((target == null) != (player == null)) { "DealDamage names a permanent or a player, not both or neither" } }
    }

    /** Deal damage to the resolving controller's opponent (2-player), no
     *  targeting. A SURFACE form: it lowers to `DealDamage(player = Opponent)`. */
    data class DamageOpponent(val amount: IntExpr) : Effect

    /** Draw for a player relative to the resolving controller. */
    data class Draw(val who: PlayerRef, val count: IntExpr) : Effect

    /** Gain (or, negative, lose) a player counter. `counter` defaults to life,
     *  so "gain 3 life" and "gain 2 gold" are the same verb. */
    data class GainLife(val who: PlayerRef, val amount: IntExpr, val counter: String = LIFE) : Effect

    /** Add resources to a player's pool; "" key = generic. Amounts are
     *  expressions ("add {1} per card under this Station", "add X"). */
    data class AddMana(val who: PlayerRef, val mana: Map<String, IntExpr>) : Effect

    data class Destroy(val target: BoundTarget) : Effect

    /** Put / remove `count` counters of `kind` on a target permanent. */
    data class AddCounter(val kind: String, val count: IntExpr, val target: BoundTarget) : Effect
    data class RemoveCounter(val kind: String, val count: IntExpr, val target: BoundTarget) : Effect

    /** Flip a multi-face permanent to its next face. */
    data class Transform(val target: BoundTarget) : Effect

    // -- creation / choices / zone ops ------------------------------------

    /** Create `count` token permanents with `chars` (name / types / fields /
     *  keywords). A token that leaves play ceases to exist. */
    data class CreateToken(
        val chars: Characteristics,
        val count: IntExpr = lit(1),
        val zone: String? = null,
    ) : Effect

    /** Create a token that is a copy of `target`'s printed characteristics
     *  (its `base` + `card` wiring), under the resolving controller. */
    data class CopyOf(val target: BoundTarget) : Effect

    /** Create an emblem for the resolving controller: a sourceless, permanent
     *  static holder that nothing can remove. */
    data class CreateEmblem(val statics: Statics) : Effect

    /** Pick `pick` of `options` (1 normally; more for entwine / escalate) and run
     *  each in order. Chosen at announcement (modes, then X, then targets); only
     *  options whose targets can bind are offered. */
    data class ChooseMode(val options: List<Effect>, val pick: IntExpr = lit(1)) : Effect

    /** `who` discards `count` cards from their whole hand (their choice) to
     *  `toZone` (graveyard by default; EXILE is gone for good). For "draw N,
     *  then cull M of THOSE" use `DrawThenDiscard`: this one can cull a card
     *  that was in hand before. */
    data class Discard(val who: PlayerRef, val count: IntExpr, val toZone: HiddenZone = HiddenZone.GRAVEYARD) : Effect

    /** Draw `draw` cards, then `who` sends `discard` of THOSE cards (never one
     *  already in hand) to `toZone` -- "draw 2, exile 1 of them". Fused because
     *  after a plain `Draw` nothing distinguishes the drawn cards. */
    data class DrawThenDiscard(
        val who: PlayerRef,
        val draw: IntExpr,
        val discard: IntExpr,
        val toZone: HiddenZone = HiddenZone.GRAVEYARD,
    ) : Effect

    /** `who` chooses `count` cards from their discard pile and returns them to
     *  hand, or to the battlefield when `toBattlefield` (needs the def in
     *  `Rules.cards`). */
    data class ReturnFromDiscard(
        val who: PlayerRef,
        val count: IntExpr,
        val toBattlefield: Boolean = false,
    ) : Effect

    // -- hidden-zone operations -----------------------------------------
    // Tutor, mill, scry/surveil: the library family, asking through the same
    // card-choice question as hand and graveyard.

    /** Tutor: `who` searches `from` for up to `count` cards (their choice,
     *  optionally only those named `cardId`) and moves them to `to`. */
    data class SearchZone(
        val who: PlayerRef,
        val from: HiddenZone,
        val to: HiddenZone,
        val count: IntExpr = lit(1),
        /** WHAT may be fetched. An empty filter is "any card" -- a real tutor
         *  says "a Creature card", "a card costing 3 or less", not one exact
         *  name, which is all `cardId: String?` could ever express. */
        val filter: CardFilter = CardFilter(),
        /** Searching a library reveals its order, so a tutor shuffles after.
         *  Off for a search of a zone whose order nobody hides. */
        val thenShuffle: Boolean = true,
        /** "Up to" `count` (a tutor may find nothing it wants) or EXACTLY
         *  `count` where that many match -- what a return-from-discard is,
         *  and what it lowers to. */
        val upTo: Boolean = true,
        /** Put what is found onto the battlefield -- each card into its own
         *  type's zone of play, staying in `from` when that zone is full --
         *  instead of into `to`. "Return a creature from your graveyard to
         *  play" lowers to this. */
        val intoPlay: Boolean = false,
    ) : Effect

    /** Reorder a hidden zone. A real verb, not a side effect of drawing:
     *  library order is stable and only this disturbs it. */
    data class Shuffle(val who: PlayerRef, val zone: HiddenZone = HiddenZone.LIBRARY) : Effect

    /** Mill: move `count` cards off the TOP of `who`'s library into `to`. */
    data class MoveTop(val who: PlayerRef, val count: IntExpr, val to: HiddenZone) : Effect

    /** Scry / surveil: look at the top `count` of `who`'s library and choose
     *  any number of them to move to `to`; the rest stay on top in order.
     *  `to = LIBRARY_BOTTOM` is scry, `to = GRAVEYARD` is surveil. */
    data class LookAtTop(val who: PlayerRef, val count: IntExpr, val to: HiddenZone) : Effect

    /** Move a permanent to another play zone. Fires `MovesZone`; a no-op if
     *  it is already there or has left play. */
    data class MovePermanent(val target: BoundTarget, val toZone: ZoneRef) : Effect

    // -- bounded continuous effects + prevention ---------------------

    /** Put a bounded continuous modifier onto the board. The affected set is a
     *  SNAPSHOT of `filter` at resolution (unlike a static's live filter), and
     *  its values lock then too. `ops` apply in order; `layer` overrides their
     *  default layers; `duration` bounds its life. */
    data class ApplyModifier(
        val filter: PermFilter,
        val ops: List<CharOp>,
        val layer: Int? = null,
        val duration: Duration = Duration.EndOfTurn,
    ) : Effect

    /** Create a prevention shield on a permanent: absorb `amount` damage, or all
     *  of it when `all`, until `duration` expires. `mode = INSTANCES` makes
     *  `amount` a count of whole hits absorbed regardless of size (Divine
     *  Shield / Barrier). */
    data class PreventDamage(
        val target: BoundTarget,
        val amount: IntExpr = lit(0),
        val all: Boolean = false,
        val duration: Duration = Duration.EndOfTurn,
        val mode: ShieldMode = ShieldMode.POINTS,
        /** Shield a PLAYER rather than the permanent `target`. */
        val who: PlayerRef? = null,
        /** Guard only damage dealt in this combat step ("fast" / "slow" in EPR
         *  Skirmish). Null = any damage. */
        val onlyStep: String? = null,
    ) : Effect

    /** Inside a replacement's `instead` only: the replaced event happens
     *  after all -- with `amount` in place of its own when there is one (the
     *  rest of a hit a shield could not stop, a hit made smaller), unchanged
     *  when null. An amount of 0 or less is no event at all. Anywhere else it
     *  does nothing, and the compiler reports it. */
    data class Proceed(val amount: IntExpr? = null) : Effect

    /** Set (or clear) a permanent's combat stance (a Yu-Gi-Oh position). */
    data class SetCombatMode(val target: BoundTarget, val mode: String?) : Effect

    /** Attach the resolving effect's SOURCE permanent to `host` -- typically
     *  "when this enters, choose a Ship or Station, attach to it". No-op without
     *  a source or once the host has left. SBA sends an unattached permanent to
     *  the graveyard; `PermFilter.onlyHost()` reads the host back. */
    data class Attach(val host: BoundTarget) : Effect

    // -- iteration + delayed effects ----------------------------------

    /** Run `body` once per battlefield permanent matching `filter` (resolved in
     *  the controller's context), in id order. `body` reads the current
     *  iteration's permanent as `BoundTarget(binds)`. */
    data class ForEach(val filter: PermFilter, val body: Effect, val binds: String = EACH) : Effect

    /** Run `body` once per player, active player first (APNAP), each time with
     *  `controller` rebound to that player. */
    data class ForEachPlayer(val body: Effect) : Effect

    /** `who` sacrifices `count` permanents matching `filter` (each a choice). */
    data class Sacrifice(val who: PlayerRef, val count: IntExpr, val filter: PermFilter) : Effect

    /** Register `effect` to fire once when `on` matches a later event, then drop
     *  itself. `expiresAfter` removes it early on a matching event; `duration`
     *  on a turn/condition boundary. `effect`'s targets are already bound when
     *  this node resolves. */
    data class Delayed(
        val on: EventPattern,
        val effect: Effect,
        val once: Boolean = true,
        val expiresAfter: EventPattern? = null,
        val duration: Duration = Duration.Permanent,
    ) : Effect

    /** Categorical composition: run each step's resulting state into the next. */
    data class Sequence(val steps: List<Effect>) : Effect

    /** Pick one permanent matching `filter`, then run `body` with
     *  `BoundTarget(binds)` resolved to it; nested binders of other names can
     *  read both picks. A LEADING Choose binds at announcement, so the stack
     *  holds a choice-free tree; one met mid-resolution binds then. */
    data class Choose(val filter: PermFilter, val body: Effect, val binds: String = CHOSEN) : Effect

    /** Choose several permanents matching `filter` and run `body` once per pick,
     *  with `BoundTarget(binds)` bound to it. `count` is how many; `upTo` makes
     *  it a maximum.
     *
     *  `divide` splits a total across the picks: the controller assigns each
     *  share, substituted as `IntExpr.Share` ("divide X damage among any number
     *  of targets"). Bound at announcement; an unmeetable exact `count` makes
     *  it uncastable. */
    data class ChooseMany(
        val filter: PermFilter,
        val count: IntExpr = lit(1),
        val upTo: Boolean = false,
        val divide: IntExpr? = null,
        val body: Effect,
        val binds: String = EACH,
    ) : Effect

    data class If(val cond: BoolExpr, val then: Effect, val otherwise: Effect = NoOp) : Effect

    /** Run `body` as `who` -- "you" inside it is that player:
     *  "target player draws two" is `AsPlayer(PlayerRef.Chosen, Draw(You, 2))`,
     *  "its controller loses 2" is `AsPlayer(ControllerOf(it), GainLife(You, -2))`.
     *  Other fields can name any player directly; this re-seats "you". */
    data class AsPlayer(val who: PlayerRef, val body: Effect) : Effect

    // -- the stack and the board -----------

    /** Counter a spell on the stack matching `types` (empty = any) and `whose`
     *  (null = anyone's); it leaves without resolving and its card goes to the
     *  graveyard. `target` is the spell picked at announcement (nothing to pick:
     *  uncastable; gone at resolution: fizzles). Unbound: the most recent match.
     *  `counterCandidates` says what may be picked. */
    data class CounterSpell(
        val types: Set<String> = emptySet(),
        val whose: PlayerRef? = PlayerRef.Opponent,
        val target: BoundTarget? = null,
    ) : Effect

    /** Exhaust (tap) a permanent, or ready (untap) it with `untap`. */
    data class Tap(val target: BoundTarget, val untap: Boolean = false) : Effect

    /** Remove all marked damage from a permanent -- what a cleanup phase does
     *  to every one of them (`PhaseEffects.CLEANUP`). */
    data class ClearDamage(val target: BoundTarget) : Effect

    /** Take a permanent out of play to a hidden zone of its OWNER's: hand by
     *  default ("return it to its owner's hand"), the top or bottom of the
     *  library, or exile. A token ceases to exist, as always. */
    data class SendTo(val target: BoundTarget, val to: HiddenZone = HiddenZone.HAND) : Effect

    /** The resolving controller gains control of a permanent, for `duration`
     *  -- then it goes back. Its abilities, statics and triggers go with it:
     *  they read their controller from a field, so this is one field
     *  per record. It cannot attack this turn, as if it had just entered. */
    data class GainControl(val target: BoundTarget, val duration: Duration = Duration.Permanent) : Effect

    // -- combat: a game's combat is a program of these verbs. A card may
    // use them too: `DeclareAttackers` starts a combat of its own (an extra
    // combat), and the others act on the combat under way -- outside one they
    // find nothing to do. Each does nothing once a player has lost.

    /** Declare attackers. The controller picks which of its permanents matching
     *  [eligible] attack, and what each attacks; each one not matching
     *  [staysReady] is exhausted. Then [then] runs with the attacks held in
     *  `GameState.combat` -- only if something attacked. A permanent fights
     *  only if its type says so (`TypeDef.attacks`), and never while it
     *  arrived this turn under `attackDelayOnEntry` or a rule bars it. */
    data class DeclareAttackers(
        val eligible: PermFilter,
        val staysReady: PermFilter? = null,
        val then: Effect = NoOp,
    ) : Effect

    /** Declare blockers: each attacked player picks which of its permanents
     *  matching [eligible] (read from its own side) block which attacker. A
     *  pairing every one of [rules] allows is kept. Then [then] runs with the
     *  blocks added to `GameState.combat`. */
    data class DeclareBlockers(
        val eligible: PermFilter,
        val rules: List<BlockRule> = emptyList(),
        val then: Effect = NoOp,
    ) : Effect

    /** A priority window inside combat: players may respond before it goes on. */
    data object CombatWindow : Effect

    /** One combat damage step, simultaneous. Every attacker and blocker
     *  matching [acts] strikes for [amount] (read with `SUBJECT` = it). An
     *  unblocked attacker hits what it attacks; a blocked one splits its damage
     *  among its blockers in order, lethal each before the next -- one matching
     *  [lethal] needs 1 per blocker, and its damage is lethal -- and one
     *  matching [tramples] sends what is left on. An attacker blocked by fewer
     *  than [minBlockers] (read with `SUBJECT` = it) counts as unblocked.
     *  [step] names the step, for zones that fight in some steps only and for
     *  the damage events. */
    data class CombatDamage(
        val step: String,
        val amount: IntExpr,
        val acts: PermFilter? = null,
        val lethal: PermFilter? = null,
        val tramples: PermFilter? = null,
        val minBlockers: IntExpr = lit(1),
        /** Damage past what a permanent can absorb carries on to its
         *  controller -- to the permanent standing in for them, if any. */
        val overflow: Boolean = false,
    ) : Effect

    /** One attack (INDIVIDUAL combat): the priority action `Attack` names the
     *  attacker and what it attacks, and they are the combat under way. The
     *  attack is checked -- the attacker matches [attacker] and has attacks
     *  left ([attacksPerTurn]); a permanent target matches [targets] (read
     *  from the attacker's side); while the defender has a permanent matching
     *  [mustTarget], only such a one may be attacked -- then committed: the
     *  defender may move it onto one of its permanents matching [redirect]
     *  (read from its side), the attacker is exhausted unless it matches
     *  [staysReady], and [then] runs. `SUBJECT` is the permanent tested. */
    data class Attack(
        val attacker: PermFilter = PermFilter(),
        val targets: PermFilter = PermFilter(controller = PlayerRef.Opponent),
        val attacksPerTurn: IntExpr = lit(1),
        val mustTarget: PermFilter? = null,
        val redirect: PermFilter? = null,
        val staysReady: PermFilter? = null,
        val then: Effect = NoOp,
        /** May the attacker reach a permanent target / the defending player?
         *  Read with `ATTACKER`, `TARGET` and `PlayerRef.Defending` bound. */
        val reaches: BoolExpr = BoolExpr.Const(true),
        val reachesFace: BoolExpr = BoolExpr.Const(true),
    ) : Effect

    /** Every fighter picks what it attacks, and they all strike at once (FREE
     *  combat, one step of it). Each permanent matching [actors] makes one
     *  attack per gun of [guns] it carries -- or one [body] attack when it
     *  carries none -- asking what it attacks. An attack is refused if it
     *  cannot reach its target (the gun's `reaches` / `reachesFace`), or
     *  ignores a guard: a defender's permanent matching [guards] that the
     *  attacker's body reaches. With [window] players may respond before the
     *  damage; then every attack deals its gun's amount, together. A fighter
     *  matching [lethal] deals lethal damage; with [overflow] damage past
     *  what a permanent absorbs carries on to its controller. */
    data class FreeAttacks(
        val step: String,
        val actors: PermFilter,
        val body: Gun,
        val guns: List<Gun> = emptyList(),
        val guards: PermFilter? = null,
        val lethal: PermFilter? = null,
        val window: Boolean = false,
        val overflow: Boolean = false,
    ) : Effect

    /** One attack's damage, exchanged: the attacker deals [amount] (read with
     *  `SUBJECT` = it) to what it attacks -- [faceAmount] instead when that is
     *  a player -- and a permanent it attacks deals its own [amount] back when
     *  [returnDamage]. A fighter matching [lethal] deals lethal damage. */
    data class Strike(
        val step: String,
        val amount: IntExpr,
        val faceAmount: IntExpr? = null,
        val returnDamage: Boolean = true,
        val lethal: PermFilter? = null,
    ) : Effect

    /** One attack decided by comparing stats: the attacker's [attackStat]
     *  against the defender's [defendStat] (each read with `SUBJECT` = that
     *  one). The higher destroys the lower -- the attacker only when
     *  [returnDamage] -- and with [excessToController] the difference goes to
     *  the loser's controller. [onTie] says what a tie does. A defender in one
     *  of [stances] defends with that stance's field and is not traded; an
     *  attacker matching [pierces] still sends the difference on. A player
     *  attacked takes [faceAmount], or the attacker's [attackStat]. */
    data class Clash(
        val step: String,
        val attackStat: IntExpr,
        val defendStat: IntExpr,
        val faceAmount: IntExpr? = null,
        val returnDamage: Boolean = true,
        val excessToController: Boolean = false,
        val onTie: TieResult = TieResult.BOTH_DESTROYED,
        val stances: List<StanceDef> = emptyList(),
        val pierces: PermFilter? = null,
    ) : Effect

    data object NoOp : Effect
}

/** One kind of attack a fighter makes in FREE combat: a close gun, a
 *  long-range battery, or a body's only attack. Everything is read with
 *  `ATTACKER` bound; [reaches] and [amount] with `TARGET` too, and the face
 *  ones with `PlayerRef.Defending`. [label] is what the question shows. */
data class Gun(
    val carried: BoolExpr = BoolExpr.Const(true),
    val reaches: BoolExpr = BoolExpr.Const(true),
    val reachesFace: BoolExpr = BoolExpr.Const(true),
    val amount: IntExpr,
    val faceAmount: IntExpr = amount,
    val label: AttackRange? = null,
)

/** A player -- THE player reference, used by every field that names one. `You`
 *  and `Opponent` are relative to the resolving controller (JSON: the bare
 *  strings "you" / "opponent"). `EvalContext.playerId` resolves one. */
sealed interface PlayerRef {
    /** the resolving controller. */
    data object You : PlayerRef
    /** the resolving controller's opponent. Where it MATCHES (a filter's
     *  controller, an event's or a rule's "whose") it is any opponent; where it
     *  ACTS or is READ it names one player, so with 3+ players it is ambiguous
     *  there, and the compiler says so (`ambiguous-opponent`). */
    data object Opponent : PlayerRef
    /** each of the controller's opponents. A verb acting on it runs once
     *  per opponent, in turn order from the controller -- "you" still meaning
     *  the controller -- and a read sums over them; where it matches it is any
     *  opponent, as `Opponent` is. */
    data object EachOpponent : PlayerRef
    /** whose turn it is. */
    data object Active : PlayerRef
    /** a player the resolving controller chooses ("target player"). Only
     *  `AsPlayer` asks the question; anywhere else it names nobody, and the
     *  compiler reports it (`unbound-player`). */
    data object Chosen : PlayerRef
    /** whoever controls `target` now -- "its controller". */
    data class ControllerOf(val target: BoundTarget) : PlayerRef
    /** whoever owns `target` -- "its owner". */
    data class OwnerOf(val target: BoundTarget) : PlayerRef
    /** the player being attacked: bound by the attack verbs while they
     *  test whether an attack reaches; names nobody anywhere else. */
    data object Defending : PlayerRef
    /** one player, by seat: what the engine writes when it pins a player it
     *  has already resolved (a shield on another player). Never authored;
     *  names nobody once that seat is not in the game. */
    data class Seat(val id: PlayerId) : PlayerRef
}

/** A declarative event predicate -- THE event matcher. `Effect.Delayed` uses it
 *  and every `TriggerDoc` compiles to one, so the two cannot diverge.
 *
 *  Matched with `source` = the permanent it belongs to ("this") and
 *  `controller` = "you"; with no source a `Self*` case never matches. */
sealed interface EventPattern {
    /** a phase begins. `whose` null = anyone's turn, else relative to "you". */
    data class OnPhase(val phase: String, val whose: PlayerRef? = null) : EventPattern
    /** any player's turn beginning. */
    data object AnyTurnBegan : EventPattern
    /** a combat step begins: "declare-attackers", "declare-blockers", or
     *  a damage step by its name; null = any. `whose` null = anyone's combat,
     *  else relative to "you". */
    data class OnCombatStep(val step: String? = null, val whose: PlayerRef? = null) : EventPattern
    /** never matches -- an inert placeholder. */
    data object Never : EventPattern

    /** this permanent entered play. */
    data object SelfEnters : EventPattern
    /** this permanent left play, to anywhere. */
    data object SelfLeaves : EventPattern
    /** this permanent was declared as an attacker. */
    data object SelfAttacks : EventPattern

    /** a permanent entered play. `types` empty = any; `whose` null = anyone's;
     *  `other` excludes this permanent itself. The types are the ENTERING
     *  permanent's, read as it is matched. */
    data class Enters(
        val types: Set<String> = emptySet(),
        val whose: PlayerRef? = null,
        val other: Boolean = false,
    ) : EventPattern

    /** a spell was cast. `types` empty = any spell; `whose` null = anyone's. */
    data class Cast(val types: Set<String> = emptySet(), val whose: PlayerRef? = PlayerRef.You) : EventPattern

    /** a counter of `kind` on THIS permanent crossed `k` -- upward is a Saga
     *  chapter, downward "when the last one leaves". */
    data class CounterCrosses(val kind: String, val k: Int, val downward: Boolean = false) : EventPattern

    /** this permanent dealt damage (to a permanent or a player). */
    data class SelfDealsDamage(val combatOnly: Boolean = false) : EventPattern

    /** a permanent died. `types` empty = any type; `whose` null = anyone's. */
    data class Dies(
        val types: Set<String> = setOf("Creature"),
        val whose: PlayerRef? = null,
        /** The permanent as it is in play -- known only BEFORE it leaves, so
         *  only a replacement (which sees the event before it happens) can read
         *  it. The compiler reports one on a trigger. Null = no further test. */
        val filter: PermFilter? = null,
    ) : EventPattern

    /** another permanent moved into this permanent's play zone. */
    data object MovesIntoThisZone : EventPattern

    /** a permanent matching `filter` was dealt damage -- "whenever a creature
     *  you control is dealt damage, ...". Matched while the damaged permanent
     *  is still in play: one that died of it is no longer a match. */
    data class Damaged(
        val filter: PermFilter = PermFilter(),
        val combatOnly: Boolean = false,
        /** Only damage from this combat step ("fast" / "slow"). Null = any. */
        val step: String? = null,
    ) : EventPattern

    /** a player was dealt damage. `whose` null = any player. */
    data class PlayerDamaged(val whose: PlayerRef? = null, val combatOnly: Boolean = false) : EventPattern
}

// The characteristic-transform vocabulary carried by ApplyModifier / StaticSpec
// (CharOp) lives in Static.kt.

// The walks over this tree -- usesX / substituteX / substituteShare /
// substituteTarget / bindSelf -- are one traversal, in Walk.kt.
