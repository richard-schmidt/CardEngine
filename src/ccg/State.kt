package ccg

// ---------------------------------------------------------------------------
// Game state. Immutable throughout.
//
// `Characteristics` is a `fields` map: `power` / `toughness` are entries a type
// opts into, beside any custom field; a type without "power" has no power.
// Fields are DERIVED: characteristicsOf applies net +1/+1 / -1/-1 counters,
// then folds continuous effects over the base.
// ---------------------------------------------------------------------------

data class Characteristics(
    val name: String = "",
    val types: Set<String> = emptySet(),
    /** "power" -> 3, "toughness" -> 3, "fast" -> 2, ... Absent key = the type
     *  does not have that field. */
    val fields: Map<String, Int> = emptyMap(),
    /** Granted / innate keywords ("flying", "vigilance", "trample", ...),
     *  including layer-6 grants. */
    val keywords: Set<String> = emptySet(),
    /** Humility-style removal: the permanent's TRIGGERED and ACTIVATED
     *  abilities are ignored while this is set. (Static-ability suppression +
     *  layer dependency is a later refinement.) */
    val abilitiesRemoved: Boolean = false,
    /** Activated abilities GRANTED by a static (`CharOp.GrantAbility`).
     *  `Engine.activateAbility` indexes the face's printed
     *  abilities first, then these. */
    val granted: List<ActivatedAbility> = emptyList(),
) {
    val power: Int get() = fields["power"] ?: 0
    val toughness: Int get() = fields["toughness"] ?: 0
    fun hasField(key: String): Boolean = key in fields
    fun has(keyword: String): Boolean = keyword in keywords

    /** Add `d` to `key` -- a no-op if the type does not have that field
     *  (so an anthem never grants "power" to a Land). */
    fun bump(key: String, d: Int): Characteristics =
        if (key in fields) copy(fields = fields + (key to fields.getValue(key) + d)) else this

    /** Set `key`, creating it. Used by "becomes a 1/1", level bands, etc. */
    fun set(key: String, v: Int): Characteristics = copy(fields = fields + (key to v))
}

/** A creature body: declares power/toughness fields. */
fun body(name: String, power: Int, toughness: Int, vararg extraTypes: String): Characteristics =
    Characteristics(name, extraTypes.toSet() + "Creature", mapOf("power" to power, "toughness" to toughness))

data class Permanent(
    val id: ObjectId,
    val controller: PlayerId,
    val base: Characteristics,
    val damageMarked: Int = 0,
    val exhausted: Boolean = false,
    /** Counter kind -> count. "+1/+1" / "-1/-1" also feed characteristicsOf;
     *  "loyalty" / "lore" / "defense" / "level" / "charge" / ... are read by
     *  `diesWhen` predicates and by field-band continuous effects. */
    val counters: Map<String, Int> = emptyMap(),
    /** Which face of `card` is currently up (multi-face cards). */
    val face: Int = 0,
    /** The `Rules.cards` key of the card this instance came from -- how
     *  `Transform`, a copy and the activated abilities reach its faces
     *  (`Rules.cardOf`). A key, not the definition: the state holds what is
     *  running, and the program stays in the rules. Null for a token made from
     *  bare characteristics and for hand-built test states. */
    val cardId: String? = null,
    /** Per-card `diesWhen` override; merged with the type's in the SBA. */
    val diesWhen: BoolExpr? = null,
    /** Which play-zone instance this permanent is in. `BATTLEFIELD` unless
     *  the play action, its type's `zoneOfPlay`, or an `Effect.MovePermanent`
     *  put it in an arena / lane / "Planet". */
    val zone: ZoneRef = BATTLEFIELD,
    /** How many times this permanent has attacked this turn -- gates
     *  windfury / one-attack-per-turn. Reset at untap. */
    val attacksThisTurn: Int = 0,
    /** Optional combat stance -- e.g. "defense" for a Yu-Gi-Oh monster
     *  set in defense position. */
    val combatMode: String? = null,
    /** Which activated-ability indices have been used this turn
     *  (`oncePerTurn`, e.g. Planeswalker loyalty). Reset at turn start. */
    val activatedThisTurn: Set<Int> = emptySet(),
    /** A token -- ceases to exist when it leaves play instead of going
     *  to the graveyard. An emblem is also a token-ish holder (id < 0). */
    val isToken: Boolean = false,
    /** The permanent this one is attached to. Set by `Effect.Attach`, read by
     *  `PermFilter.onlyHost`; when the host leaves the battlefield, SBA sends
     *  this permanent to the graveyard. Null = not an attachment. */
    val hostId: ObjectId? = null,
    /** The `turnNumber` this permanent ENTERED the battlefield on, read only by
     *  `GameParams.attackDelayOnEntry` to answer "has it been here since before
     *  this turn". 0 means "has always been here", which is what a hand-built
     *  test state gets and why adding the field changed no existing test. */
    val enteredOnTurn: Int = 0,
    /** Whose card this is, when someone else controls it (`Effect.GainControl`).
     *  Null = its controller's, which is every permanent nobody has taken. A
     *  card that leaves play goes to its OWNER's zones. */
    val owner: PlayerId? = null,
) {
    fun counter(kind: String): Int = counters[kind] ?: 0

    /** The player whose zones this card goes back to. */
    val ownerId: PlayerId get() = owner ?: controller

    /** What a `CardRef` must carry when the permanent goes back to a hidden
     *  zone: its `cardId`, or the name for a permanent with no card. */
    val cardKey: String get() = cardId ?: base.name
}

/** A card in a hidden/private zone. */
data class CardRef(val instanceId: ObjectId, val cardId: String)

/** A hidden (non-battlefield) zone a zone verb can address. `LIBRARY` is the
 *  TOP of the library when used as a destination; `LIBRARY_BOTTOM` the bottom,
 *  which is what makes scry expressible. Exile is game-wide, the rest are
 *  per-player. */
enum class HiddenZone { LIBRARY, LIBRARY_BOTTOM, HAND, GRAVEYARD, EXILE }

/** WHERE a cast comes from: the card instance being spent and the zone it must
 *  be in (hand, or a declared custom zone such as a Flagship pool).
 *  `afterResolve` is where the card goes when the spell finishes (graveyard, or
 *  exile for a recast); a permanent spell enters play instead. Null on a
 *  `PriorityAction` is the sandbox mode (cards conjured, not spent). */
data class CardSource(
    val zone: CastZone,
    val instanceId: ObjectId,
    val afterResolve: HiddenZone = HiddenZone.GRAVEYARD,
)

/** A predicate over CARD DEFINITIONS, for the hidden zones. `PermFilter` reads
 *  `Characteristics`, which only exist on the battlefield -- a card sitting in
 *  a library has nothing but its definition, so a tutor needs its own filter.
 *  Everything set must hold (AND); all-null matches anything. */
data class CardFilter(
    val types: Set<String> = emptySet(),
    val nameIs: String? = null,
    val nameContains: String? = null,
    val maxManaValue: Int? = null,
    val minManaValue: Int? = null,
)

/** The universal player counter. Life is not privileged -- it is simply the
 *  one every game happens to declare. */
const val LIFE = "life"

data class Player(
    val id: PlayerId,
    val library: List<CardRef> = emptyList(),
    val hand: List<CardRef> = emptyList(),
    /** The one graveyard, in one currency: a permanent that dies keeps its id as
     *  `instanceId` and gains a resolvable `cardId`. */
    val graveyard: List<CardRef> = emptyList(),
    /** Typed resource pool. "" = generic. Filled by `Effect.AddMana`,
     *  emptied at every phase boundary. */
    val pool: Map<String, Int> = emptyMap(),
    /** Named player totals -- life and whatever else a game declares -- each able
     *  to end the game at zero. Persist, unlike `pool` (per-turn spendable mana,
     *  emptied at phase boundaries). */
    val counters: Map<String, Int> = mapOf(LIFE to 20),
    /** `ResourceModel.Ramp`: the allowance reached so far (Hearthstone's mana
     *  crystals). Distinct from `pool`, which is what is left to spend now. */
    val ramp: Int = 0,
    /** `ResourceModel.CardDriven`: resource permanents played this turn, so
     *  `legality` can enforce the land drop. Reset at turn start. */
    val resourcePlaysUsed: Int = 0,
    /** cards in a declared `HiddenZoneDef`, keyed by its id -- e.g.
     *  EPR Skirmish's Flagship pool. Seeded once at `setupGame`, spent via
     *  `CastZone.Declared` the same way `hand` is spent via `CastZone.Std
     *  (HiddenZone.HAND)`. */
    val customZones: Map<String, List<CardRef>> = emptyMap(),
) {
    fun mana(key: String): Int = pool[key] ?: 0

    /** Life, read through the counter map. Every existing read site keeps
     *  working, which is what made unifying this cheap. */
    val life: Int get() = counters[LIFE] ?: 0

    fun counter(name: String): Int = counters[name] ?: 0
    fun withCounter(name: String, v: Int): Player = copy(counters = counters + (name to v))
    fun addCounter(name: String, delta: Int): Player = withCounter(name, counter(name) + delta)
}

/**
 * A continuous effect in play: one `CharOp` applied at its (layer, timestamp)
 * place. Data, never a closure, so a running game can be digested.
 *
 * It covers either a LIVE set -- `filter` (and `condition`), re-read on every
 * derivation (a static ability) -- or a SNAPSHOT, `affected`, fixed when a
 * one-shot `ApplyModifier` resolved. `controller` is "you" and `source` "this",
 * read at evaluation time; `source` also prunes it when that permanent leaves.
 * `duration` + `startTurn` bound its lifetime.
 */
data class ContinuousEffect(
    val source: ObjectId,
    val controller: PlayerId,
    val layer: Int,
    val timestamp: Long,
    val op: CharOp,
    /** The live set: every permanent this matches. Ignored when `affected` is set. */
    val filter: PermFilter = PermFilter(),
    /** Applied only while this holds (a conditional static). */
    val condition: BoolExpr? = null,
    /** The snapshot: exactly these permanents. */
    val affected: Set<ObjectId>? = null,
    val duration: Duration = Duration.Permanent,
    val startTurn: Int = 0,
) {
    /** `ctx` carries the state and the `visiting` re-entrancy set (finding D);
     *  this effect's own controller and source are swapped in. */
    private fun own(ctx: EvalContext) = ctx.copy(controller = controller, source = source)

    fun appliesTo(ctx: EvalContext, id: ObjectId): Boolean {
        affected?.let { return id in it }
        val c = own(ctx)
        return filter.matches(c, id) && (condition?.eval(c) ?: true)
    }

    /** The op's VALUES read this effect's own source and controller, exactly as
     *  its filter does -- not the permanent being derived (that is `subject`). */
    fun apply(ctx: EvalContext, c: Characteristics): Characteristics = op.apply(own(ctx), c)
}

/** A one-shot / floating trigger created by a resolving effect. `duration`
 *  + `startTurn` let a floating trigger that never fires be swept up. */
data class DelayedTrigger(
    val id: ObjectId,
    val controller: PlayerId,
    val on: EventPattern,
    val effect: Effect,
    val once: Boolean,
    val expiresAfter: EventPattern? = null,
    val duration: Duration = Duration.Permanent,
    val startTurn: Int = 0,
    /** The permanent whose effect armed it, so "this card's ..." still means
     *  that card when it fires. It was dropped, and read 0. */
    val source: ObjectId? = null,
)

/** A temporary change of control (`Effect.GainControl` with a duration):
 *  when `duration` ends, `permanent` goes back to `previous` -- if `controller`
 *  still has it. */
data class ControlChange(
    val permanent: ObjectId,
    val controller: PlayerId,
    val previous: PlayerId,
    val duration: Duration,
    val startTurn: Int,
    val source: ObjectId? = null,
)

/** The turn loop's place: which turn of this run, which phase,
 *  whose turn, who acts in it, and where in the phase -- the `PhaseEnter`
 *  boundary of actor [stage], or the phase's own window ([MAIN]). [phase]
 *  [TURN_BEGAN] is the turn-begin boundary. With it a priority window is a
 *  point the game can be picked up from. */
data class TurnPoint(val turns: Int, val phase: Int, val ap: PlayerId, val actors: List<PlayerId>, val stage: Int = MAIN) {
    companion object {
        const val MAIN = -1
        const val TURN_BEGAN = -1
    }
}

/** A priority window in progress: who holds priority and the
 *  loop's counters, so the window is data, not a coroutine's locals. Carried
 *  by the state of every `Question.Priority`, never by a state between
 *  questions. [at] is set for a window the turn itself opens (a phase's
 *  own, or its turn-start / phase-start boundary's): the game frame's
 *  checkpoint moves past it when it closes. A window opened inside something
 *  else (combat, an action) has none. */
data class PriorityWindow(val holder: Int, val passes: Int, val stalled: Int, val steps: Int, val at: TurnPoint? = null)

/** An engine run's record. `events`: every event that took effect, in
 *  order, as `settle` drained it. `replaced`: every event a replacement
 *  intercepted, as it did. */
data class EventTrace(
    val events: List<GameEvent> = emptyList(),
    val replaced: List<GameEvent> = emptyList(),
    /** How many times each `Effect` case ran, by class name. */
    val effects: Map<String, Int> = emptyMap(),
)

data class GameState(
    val players: Map<PlayerId, Player>,
    val battlefield: Map<ObjectId, Permanent> = emptyMap(),
    /** The LIFO stack, bottom-first. In the state so legality, the UI and a save
     *  can all see it. */
    val stack: List<StackObject> = emptyList(),
    val exile: List<CardRef> = emptyList(),
    /** The shuffler's state. In the state because undo replays the game from its
     *  answers: same seed + same answers = same game. Nothing reorders a library
     *  except an explicit shuffle. */
    val rngState: Int = 0x9E3779B9.toInt(),
    val continuousEffects: List<ContinuousEffect> = emptyList(),
    val triggeredAbilities: Map<ObjectId, List<TriggeredAbility>> = emptyMap(),
    val delayedTriggers: List<DelayedTrigger> = emptyList(),
    /** Replacements the STATE holds rather than a card in play -- what a
     *  `PreventDamage` makes. Consulted before `replacements`. */
    val shields: List<ActiveReplacement> = emptyList(),
    /** Game-rule statics ("opponents can't gain life") and card-authored
     *  replacement effects. Pruned on leave-play and by duration, like a
     *  continuous effect. */
    val ruleMods: List<ActiveRuleMod> = emptyList(),
    val replacements: List<ActiveReplacement> = emptyList(),
    /** Cost-modifying statics ("your spells cost {1} less"). */
    val costMods: List<ActiveCostMod> = emptyList(),
    val turnOrder: List<PlayerId>,
    val activePlayer: PlayerId,
    val losers: Set<PlayerId> = emptySet(),
    /** The current phase's NAME -- for the log, `GameEvent.PhaseEnter` and
     *  `ScriptedInput`'s "P0@main" keying. Display only: names are author
     *  supplied and may repeat, so rules lookups use `phaseIndex`. */
    val phase: String = "",
    /** The authoritative phase handle: an index into `Rules.turn.phases`, or
     *  `NO_PHASE` at a turn/phase boundary, or `FREE_PRIORITY` in the
     *  no-turn-structure `Engine.run()` harness. */
    val phaseIndex: Int = FREE_PRIORITY,
    val turnNumber: Int = 1,
    val nextObjectId: ObjectId = 1,
    val log: List<String> = emptyList(),
    /** `Rules.damageCounter`, baked in at setup so evaluation and `canAfford` can
     *  read the declared counter without `GameState` carrying `Rules`.
     *  Null = players take no damage. */
    val damageCounter: String? = LIFE,
    /** Recent combat hits, bounded and sequenced -- a state diff can't tell who dealt damage. */
    val combatHits: List<CombatHit> = emptyList(),
    /** The combat under way: what the combat verbs have declared so far.
     *  Null outside combat. */
    val combat: CombatState? = null,
    /** Where each zone the game places on its board sits (lane, depth), baked
     *  in from `Rules.zones` as `damageCounter` is, so evaluation reads
     *  geometry without `Rules`. Derived from the bundle, so not
     *  digested. The engine fills it when it starts a run. */
    val zoneGeometry: Map<String, ZoneGeometry> = emptyMap(),
    /** Events raised but not yet matched against triggers, oldest first;
     *  `Engine.settle` drains them. In the state so a state the engine discards
     *  takes its events with it. Empty between actions. */
    val pendingEvents: List<GameEvent> = emptyList(),
    /** Control changes that end. A permanent one is not recorded. */
    val controlChanges: List<ControlChange> = emptyList(),
    /** What the engine did, when asked to record it (`traced()`); null records
     *  nothing. Not part of the game: a digest leaves it out, as it does `log`. */
    val trace: EventTrace? = null,
    /** The priority window this state was asked in; null
     *  between questions. See `PriorityWindow`. */
    val priority: PriorityWindow? = null,
    /** The work waiting on this state's question (Machine.kt):
     *  empty between questions. Not the game: a digest leaves it out, and a
     *  player's view drops it (it holds whole states). */
    val pending: List<Frame> = emptyList(),
) {
    /** `pid`'s first opponent in turn order still in the game -- any other
     *  seat once the game is over. */
    fun opponentOf(pid: PlayerId): PlayerId = turnOrder.firstOrNull { it != pid && it !in losers } ?: turnOrder.first { it != pid }

    /** `pid`'s opponents still in the game, in turn order starting after
     *  `pid` -- the order an `EachOpponent` verb runs in. */
    fun opponentsOf(pid: PlayerId): List<PlayerId> {
        val i = turnOrder.indexOf(pid)
        return (1 until turnOrder.size).map { turnOrder[(i + it) % turnOrder.size] }.filter { it !in losers }
    }

    /** Whose turn follows `pid`'s: the next seat in `turnOrder` that has not
     *  lost, wrapping. Two players or twenty. */
    fun nextInTurn(pid: PlayerId): PlayerId {
        val i = turnOrder.indexOf(pid)
        return (1..turnOrder.size).map { turnOrder[(i + it) % turnOrder.size] }.first { it !in losers || it == pid }
    }

    /** This state, recording what the engine does from here on. */
    fun traced(): GameState = if (trace != null) this else copy(trace = EventTrace())

    internal fun recordEvent(e: GameEvent): GameState = trace?.let { copy(trace = it.copy(events = it.events + e)) } ?: this
    internal fun recordEffect(e: Effect): GameState = trace?.let { t ->
        val k = e::class.simpleName ?: "?"
        copy(trace = t.copy(effects = t.effects + (k to (t.effects[k] ?: 0) + 1)))
    } ?: this
    internal fun recordReplaced(e: GameEvent): GameState = trace?.let { copy(trace = it.copy(replaced = it.replaced + e)) } ?: this

    /** The permanents in play in THE declared order: by id, ascending. Every
     *  list the engine builds from the battlefield goes through this, never the
     *  map's insertion order (a port reproduces "by id", not an insertion order). */
    val inPlay: List<Permanent> get() = battlefield.values.sortedBy { it.id }
    val inPlayIds: List<ObjectId> get() = battlefield.keys.sorted()

    /** Is `player` currently barred from `action` by a rule-modifying static?
     *  `who` on the mod is relative to its controller. */
    fun forbids(action: RuleAction, player: PlayerId): Boolean = ruleMods.any {
        val m = it.mod
        m is RuleMod.Cant && m.action == action &&
            (m.who == null || EvalContext(this, it.controller, source = it.source).names(m.who, player))
    }

    /** Each permanent-scoped `RuleMod` matched against `id`, in ITS
     *  controller's evaluation context. */
    private inline fun <reified M : RuleMod> barredBy(id: ObjectId, test: (M, EvalContext) -> Boolean): Boolean =
        ruleMods.any { am ->
            val m = am.mod
            m is M && test(m, EvalContext(this, am.controller, source = am.source))
        }

    /** Cluster 5: may `attacker` be declared as an attacker at all? When
     *  `defender` is given, may it attack THAT player? A mod that only bars
     *  attacking a specific player does not bar attacking generally. */
    fun attackBarred(attacker: ObjectId, defender: PlayerId? = null): Boolean =
        barredBy<RuleMod.CantAttack>(attacker) { m, ctx ->
            m.filter.matches(ctx, attacker) && when {
                m.defender == null -> true
                defender == null -> false
                else -> ctx.names(m.defender, defender)
            }
        }

    /** Total continuous damage reduction protecting `target` (RuleMod.
     *  ReduceDamage). Stacks, because two anthems should both count. */
    fun damageReduction(target: ObjectId): Int =
        ruleMods.sumOf { am ->
            val m = am.mod
            if (m is RuleMod.ReduceDamage) {
                val ctx = EvalContext(this, am.controller, source = am.source)
                if (m.filter.matches(ctx, target) && (m.condition?.eval(ctx) ?: true)) maxOf(0, m.amount.eval(ctx)) else 0
            } else {
                0
            }
        }

    /** Cluster 5: the defending player a `CombatTarget` belongs to. */
    fun defenderOf(target: CombatTarget): PlayerId? = when (target) {
        is CombatTarget.Player -> target.id
        is CombatTarget.Obj -> battlefield[target.id]?.controller
    }

    /** Is `attacker`'s own lane still opposed on `defender`'s side (does the same
     *  zone def owned by `defender` hold anything)? An attacker outside a
     *  per-player lane is unopposed, so games without lanes are unaffected. */
    fun laneOpposed(attacker: ObjectId, defender: PlayerId): Boolean {
        val lane = battlefield[attacker]?.zone ?: return false
        if (lane.owner == null) return false
        val oppositeLane = ZoneRef(lane.def, defender)
        return battlefield.values.any { it.zone == oppositeLane }
    }

    fun blockBarred(blocker: ObjectId): Boolean =
        barredBy<RuleMod.CantBlock>(blocker) { m, ctx -> m.filter.matches(ctx, blocker) }

    /** Can `player` afford `cost` right now, without asking anything?
     *  CONSERVATIVE: X and an `additional` effect cannot be judged without input,
     *  so they count as payable. `legality` uses this, so the UI may offer an
     *  action that then fails, never the reverse. */
    fun canAfford(player: PlayerId, cost: Cost, source: ObjectId? = null): Boolean {
        if (cost.isFree) return true
        if (cost.alternatives.isNotEmpty()) {
            return (listOf(cost.basic()) + cost.alternatives.map { it.basic() }).any { canAfford(player, it, source) }
        }
        val p = players[player] ?: return false
        var pool = p.pool
        for ((k, amt) in cost.mana) {
            if (k.isEmpty()) continue
            if ((pool[k] ?: 0) < amt) return false
            pool = pool + (k to (pool.getValue(k) - amt))
        }
        if ((cost.mana[""] ?: 0) > pool.values.sum()) return false
        if (cost.payLife > 0 && (damageCounter == null || p.counter(damageCounter) < cost.payLife)) return false
        // A permanent-sourced counter cost is payable if ANY permanent the
        // player controls matches the filter and holds enough. Refusing here is
        // the whole point of it being a cost -- see Cost.payFrom.
        cost.payFrom?.let { pay ->
            if (payersFor(player, pay, source).isEmpty()) return false
        }
        if (source != null) {
            val perm = battlefield[source] ?: return false
            if (cost.tapSource && perm.exhausted) return false
            cost.removeCounters?.let { (k, n) -> if (perm.counter(k) < n) return false }
        }
        // An additional effect is a COST, so it refuses when it cannot be
        // paid. See `additionalPayable`.
        cost.additional?.let { add ->
            if (!additionalPayable(player, if (source != null) add.bindSelf(source) else add, source)) return false
        }
        return true
    }

    /** Can a `Cost.additional` effect actually be paid? A cost is refused when
     *  short; an unchecked `additional` let a sacrifice with nothing to sacrifice
     *  be skipped for free.
     *
     *  Partial by necessity: `additional` is an arbitrary `Effect`, so this
     *  answers the shapes used as costs (sacrifice, discard, a sequence of them)
     *  and returns `true` for anything else. It never makes a cost harder. */
    fun additionalPayable(player: PlayerId, e: Effect, source: ObjectId? = null): Boolean {
        val ctx = EvalContext(this, player, source = source)
        return when (e) {
            // Mirrors the interpreter exactly (Engine's Effect.Sacrifice arm):
            // candidates are permanents the SACRIFICING player controls that
            // match the filter. Asking a different question here than the
            // interpreter asks is how "may I?" and "which one?" drift apart.
            is Effect.Sacrifice -> {
                val who = ctx.playerId(e.who) ?: return false
                val c2 = EvalContext(this, who, source = source)
                inPlayIds.count { e.filter.matches(c2, it) && battlefield[it]?.controller == who } >=
                    e.count.eval(ctx)
            }
            is Effect.Discard -> (ctx.playerId(e.who)?.let { players[it] }?.hand?.size ?: 0) >= e.count.eval(ctx)
            is Effect.Sequence -> e.steps.all { additionalPayable(player, it, source) }
            else -> true
        }
    }

    /** Every permanent `player` controls that could pay `pay` (matches the filter,
     *  holds enough counters). One function for `canAfford` (may I?) and
     *  `payCost` (which one?), so they cannot disagree. */
    fun payersFor(player: PlayerId, pay: CounterPayment, source: ObjectId? = null): List<ObjectId> {
        val ctx = EvalContext(this, player, source = source)
        return inPlay
            .filter { it.controller == player && it.counter(pay.counter) >= pay.amount }
            .filter { pay.filter.matches(ctx, it.id) }
            .map { it.id }
    }

    /** The activated abilities usable from `source` -- printed then granted
     *, in the order `PriorityAction.Activate` indexes. */
    fun abilitiesOf(source: ObjectId, rules: Rules): List<ActivatedAbility> {
        val perm = battlefield[source] ?: return emptyList()
        val derived = characteristicsOf(source)
        if (derived.abilitiesRemoved) return emptyList()
        val face = rules.faceOf(perm)
        return (face?.activated ?: emptyList()) + derived.granted
    }

    fun activateBarred(source: ObjectId): Boolean =
        barredBy<RuleMod.CantActivate>(source) { m, ctx -> m.filter.matches(ctx, source) }

    /** Memo for the TOP-LEVEL derivation only (empty `visiting`); a GameState is
     *  immutable, so the result is fixed for its lifetime. In the class body, so
     *  `equals` / `hashCode` / `copy` ignore it. Re-entrant calls return the base
     *  and must not be cached under the same key. */
    private val derivedCache = HashMap<ObjectId, Characteristics>()

    /** Derived characteristics: the base, then net +1/+1 / -1/-1 counters, then
     *  every applicable continuous effect in (layer, timestamp) order.
     *
     *  A static's `appliesTo` / `condition` may read derived characteristics;
     *  `visiting` guards re-entry for the same id (the nested call yields the
     *  base). Coarser than MTG's layer dependencies, enough for the corpus. */
    fun characteristicsOf(id: ObjectId, visiting: Set<ObjectId> = emptySet()): Characteristics {
        // Total by design: a query for a permanent that is not (or no longer)
        // in play answers "no characteristics", it does not crash. Every
        // in-engine caller already guards `id in battlefield`; this protects a
        // UI/read-side caller that raced a permanent leaving play.
        val perm = battlefield[id] ?: return Characteristics()
        var c = perm.base
        if (id in visiting) return c
        val top = visiting.isEmpty()
        if (top) derivedCache[id]?.let { return it }
        val net = perm.counter("+1/+1") - perm.counter("-1/-1")
        if (net != 0) c = c.bump("power", net).bump("toughness", net)
        // Each effect swaps in its OWN controller and source (see
        // `ContinuousEffect.apply`); `subject` is the permanent being derived.
        val ctx = EvalContext(this, perm.controller, source = id, visiting = visiting + id, subject = id)
        val out = continuousEffects
            .filter { it.appliesTo(ctx, id) }
            .sortedWith(compareBy({ it.layer }, { it.timestamp }))
            .fold(c) { chars, eff -> eff.apply(ctx, chars) }
        if (top) derivedCache[id] = out
        return out
    }

    /** The cards in one hidden zone. `LIBRARY_BOTTOM` reads the library too --
     *  it only differs as a DESTINATION. */
    fun cardsIn(player: PlayerId, zone: HiddenZone): List<CardRef> = when (zone) {
        HiddenZone.LIBRARY, HiddenZone.LIBRARY_BOTTOM -> players[player]?.library ?: emptyList()
        HiddenZone.HAND -> players[player]?.hand ?: emptyList()
        HiddenZone.GRAVEYARD -> players[player]?.graveyard ?: emptyList()
        HiddenZone.EXILE -> exile
    }

    /** Take `refs` out of `zone`. */
    fun removeFrom(player: PlayerId, zone: HiddenZone, refs: Set<ObjectId>): GameState {
        if (refs.isEmpty()) return this
        fun keep(l: List<CardRef>) = l.filterNot { it.instanceId in refs }
        val p = players[player] ?: return this
        return when (zone) {
            HiddenZone.LIBRARY, HiddenZone.LIBRARY_BOTTOM ->
                copy(players = players + (player to p.copy(library = keep(p.library))))
            HiddenZone.HAND -> copy(players = players + (player to p.copy(hand = keep(p.hand))))
            HiddenZone.GRAVEYARD -> copy(players = players + (player to p.copy(graveyard = keep(p.graveyard))))
            HiddenZone.EXILE -> copy(exile = keep(exile))
        }
    }

    /** `cardsIn`, widened to a `CastZone` -- a `Std` zone reads the
     *  usual way, a `Declared` zone reads the player's `customZones`. */
    fun cardsInCast(player: PlayerId, zone: CastZone): List<CardRef> = when (zone) {
        is CastZone.Std -> cardsIn(player, zone.zone)
        is CastZone.Declared -> players[player]?.customZones?.get(zone.id) ?: emptyList()
    }

    /** `removeFrom`, widened to a `CastZone`. */
    fun removeFromCast(player: PlayerId, zone: CastZone, refs: Set<ObjectId>): GameState = when (zone) {
        is CastZone.Std -> removeFrom(player, zone.zone, refs)
        is CastZone.Declared -> {
            val p = players[player] ?: return this
            val kept = (p.customZones[zone.id] ?: emptyList()).filterNot { it.instanceId in refs }
            copy(players = players + (player to p.copy(customZones = p.customZones + (zone.id to kept))))
        }
    }

    /** seed a declared custom zone -- setup-time only (there is no
     *  runtime verb that moves cards into one yet). */
    fun withCustomZone(player: PlayerId, zone: String, refs: List<CardRef>): GameState {
        val p = players[player] ?: return this
        return copy(players = players + (player to p.copy(customZones = p.customZones + (zone to (p.customZones[zone].orEmpty() + refs)))))
    }

    /** Put `refs` into `zone` -- on TOP for `LIBRARY`, at the bottom for
     *  `LIBRARY_BOTTOM`, appended for the rest. */
    fun addTo(player: PlayerId, zone: HiddenZone, refs: List<CardRef>): GameState {
        if (refs.isEmpty()) return this
        val p = players[player] ?: return this
        return when (zone) {
            HiddenZone.LIBRARY -> copy(players = players + (player to p.copy(library = refs + p.library)))
            HiddenZone.LIBRARY_BOTTOM -> copy(players = players + (player to p.copy(library = p.library + refs)))
            HiddenZone.HAND -> copy(players = players + (player to p.copy(hand = p.hand + refs)))
            HiddenZone.GRAVEYARD -> copy(players = players + (player to p.copy(graveyard = p.graveyard + refs)))
            HiddenZone.EXILE -> copy(exile = exile + refs)
        }
    }

    fun allocId(): Pair<ObjectId, GameState> = nextObjectId to copy(nextObjectId = nextObjectId + 1)

    /** `id` changes hands to `to`, and everything it has in play follows: its
     *  triggers, statics, rule mods, replacements and cost mods each carry a
     *  controller field, read when evaluated. It counts as having just
     *  entered for "can it attack yet". */
    fun withController(id: ObjectId, to: PlayerId): GameState {
        val perm = battlefield[id] ?: return this
        if (perm.controller == to) return this
        return copy(
            battlefield = battlefield + (id to perm.copy(controller = to, owner = perm.ownerId, enteredOnTurn = turnNumber)),
            triggeredAbilities = triggeredAbilities[id]?.let { t -> triggeredAbilities + (id to t.map { it.copy(controller = to) }) }
                ?: triggeredAbilities,
            continuousEffects = continuousEffects.map { if (it.source == id) it.copy(controller = to) else it },
            ruleMods = ruleMods.map { if (it.source == id) it.copy(controller = to) else it },
            replacements = replacements.map { if (it.source == id) it.copy(controller = to) else it },
            costMods = costMods.map { if (it.source == id) it.copy(controller = to) else it },
        ).logged("#$id: ${perm.controller} -> $to takes control")
    }

    fun logged(line: String): GameState = copy(log = log + line)

    /** Take a permanent off the battlefield. Its triggered abilities are NOT
     *  pruned here -- a "when this leaves" trigger must still be visible for the
     *  event that just fired; `drainTriggers` prunes gone permanents' abilities
     *  once the current batch is processed. */
    fun leaveBattlefield(id: ObjectId): GameState = copy(
        battlefield = battlefield - id,
        continuousEffects = continuousEffects.filterNot { it.source == id },
        ruleMods = ruleMods.filterNot { it.source == id },
        replacements = replacements.filterNot { it.source == id },
        costMods = costMods.filterNot { it.source == id },
        controlChanges = controlChanges.filterNot { it.permanent == id },
    )
}

/** Reorder one hidden zone, advancing the shuffler stored on the state. */
fun GameState.shuffleZone(pid: PlayerId, zone: HiddenZone = HiddenZone.LIBRARY): GameState {
    val cards = cardsIn(pid, zone)
    if (cards.size < 2) return this
    val rng = Rng(rngState)
    val reordered = shuffle(cards, rng)
    val cleared = removeFrom(pid, zone, cards.map { it.instanceId }.toSet())
    return cleared.addTo(pid, zone, reordered).copy(rngState = rng.seedState)
}


/** A zone's place on the board: its lane and its depth, where it declares them. */
data class ZoneGeometry(val lane: Int?, val depth: Depth?)

/** The places `Rules.zones` declares, for `GameState.zoneGeometry`. */
fun Rules.zoneGeometry(): Map<String, ZoneGeometry> =
    zones.filterValues { it.lane != null || it.depth != null }.mapValues { ZoneGeometry(it.value.lane, it.value.depth) }

/** This state, with the zone geometry of `rules`. */
fun GameState.placed(rules: Rules): GameState =
    rules.zoneGeometry().let { g -> if (g == zoneGeometry) this else copy(zoneGeometry = g) }

/** A combat in progress: the attacks declared, in declaration order, and the
 *  blocks (blocker -> the attacker it blocks), as the combat verbs left them. */
data class CombatState(
    val attacks: List<Pair<ObjectId, CombatTarget>> = emptyList(),
    val blocks: List<Pair<ObjectId, ObjectId>> = emptyList(),
)

/** One combat hit that landed (after shields). [target] is set for a
 *  permanent, [player] for damage dealt to a player directly. */
data class CombatHit(
    val seq: Int,
    val source: ObjectId?,
    val target: ObjectId?,
    val player: PlayerId?,
    val amount: Int,
)

/** Bounded so the state doesn't grow with the length of the game. */
const val COMBAT_HITS_KEPT = 16

/** This state with one more combat hit recorded, the oldest dropped past the cap. */
fun GameState.withCombatHit(source: ObjectId?, target: ObjectId?, player: PlayerId?, amount: Int): GameState {
    val seq = (combatHits.lastOrNull()?.seq ?: 0) + 1
    return copy(combatHits = (combatHits + CombatHit(seq, source, target, player, amount)).takeLast(COMBAT_HITS_KEPT))
}

/** The game is over: someone has lost and at most one seat is left. With
 *  three or more players the rest play on past a loss; with two, the
 *  first loss ends it, as always. */
val GameState.isOver: Boolean get() = losers.isNotEmpty() && turnOrder.count { it !in losers } <= 1
