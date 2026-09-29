package ccg

// ---------------------------------------------------------------------------
// Card definitions: terms in the free algebra of Ast.kt + Expr.kt. A
// CardDefinition is a list of FACES (two for an MDFC / transforming card); a
// Face bundles characteristics, cast effect, statics and triggers. The card
// declares `entersWith` counters and an optional per-card `diesWhen` (a Saga).
// ---------------------------------------------------------------------------

/** A triggered ability in play, as data. `on` is matched with `source` as "this"
 *  and `controller` as "you", read when the event happens (a change of control
 *  is one field). `effect` has `SELF` bound; `TRIGGER` and `EventAmount` bind
 *  from the event as it fires (`bindEvent`). */
data class TriggeredAbility(
    val source: ObjectId,
    val controller: PlayerId,
    val on: EventPattern,
    val effect: Effect,
    /** When several abilities trigger off the SAME event, they go on the stack
     *  so that the LOWEST `order` resolves first (Saga chapters: order = K). */
    val order: Int = 0,
) {
    /** Does `event` fire this ability, read against `state` as it is now? */
    fun matches(event: GameEvent, state: GameState): Boolean =
        on.matches(event, EvalContext(state, controller, source = source))
}

/** One face of a card. Everything it does in play is data: `triggers` and
 *  `statics` are wired onto a permanent by `wireFace`, which binds `SELF` and
 *  records the permanent and controller beside them. */
data class Face(
    val name: String,
    val types: Set<String>,
    val baseChars: Characteristics? = null,
    val castEffect: Effect? = null,
    val triggers: List<TriggerDoc> = emptyList(),
    val statics: Statics = Statics(),
    /** Activated abilities of this face: pay a `Cost`, get an `Effect`.
     *  Their effects use `BoundTarget(SELF)` for this permanent. */
    val activated: List<ActivatedAbility> = emptyList(),
)

/** "You may also cast this from `from` for `cost`, and it goes to
 *  `afterResolve` instead of the graveyard when it finishes." That last field
 *  is what stops a flashback card being cast over and over. */
data class Recast(
    val from: HiddenZone = HiddenZone.GRAVEYARD,
    val cost: Cost = Cost(),
    val afterResolve: HiddenZone = HiddenZone.EXILE,
)

data class CardDefinition(
    val faces: List<Face>,
    val entersWith: List<CounterDef> = emptyList(),
    val diesWhen: BoolExpr? = null,
    val text: String = "",
    /** What it costs to play / cast this card. `Cost()` = free. */
    val cost: Cost = Cost(),
    /** A SECOND way to cast this card, out of a zone that is not the hand
     *  (Flashback, jump-start, aftermath). Null = hand only. */
    val recast: Recast? = null,
    /** A precondition `legality()` checks before this card may be played --
     *  see `CardDoc.requires`. Null = playable whenever affordable+timed,
     *  which is every card that predates it. */
    val requires: PermFilter? = null,
    /** WHICH `CardDoc` this was built from (`CardDoc.key()`), so anything
     *  downstream (the rulebox, recasting) joins on identity rather than the
     *  first face's name. Empty for hand-built test cards. Kept last. */
    val key: String = "",
) {
    val name: String get() = faces[0].name
    val types: Set<String> get() = faces[0].types
    val castEffect: Effect? get() = faces[0].castEffect
    /** The `Rules.cards` key: `key`, or the name for a hand-built card. */
    val cardKey: String get() = key.ifEmpty { name }
}

/** Single-face card sugar. */
fun card(
    name: String,
    types: Set<String>,
    baseChars: Characteristics? = null,
    castEffect: Effect? = null,
    triggers: List<TriggerDoc> = emptyList(),
    statics: Statics = Statics(),
    activated: List<ActivatedAbility> = emptyList(),
    entersWith: List<CounterDef> = emptyList(),
    diesWhen: BoolExpr? = null,
    text: String = "",
    cost: Cost = Cost(),
): CardDefinition = CardDefinition(
    listOf(Face(name, types, baseChars, castEffect, triggers, statics, activated)),
    entersWith, diesWhen, text, cost,
)

/** Put a card onto the battlefield: place the chosen face, seed its counters,
 *  wire that face's continuous / triggered abilities. */
fun enterBattlefield(
    card: CardDefinition,
    controller: PlayerId,
    state: GameState,
    faceIndex: Int = 0,
    zone: ZoneRef = BATTLEFIELD,
    /** Reuse an id already allocated for the permanent SPELL on the stack, so
     *  a permanent keeps one identity from cast to battlefield. */
    reuseId: ObjectId? = null,
): Pair<GameState, ObjectId> {
    val (id, s0) = if (reuseId != null) reuseId to state else state.allocId()
    val face = card.faces.getOrElse(faceIndex) { card.faces[0] }
    val base = face.baseChars ?: Characteristics(name = face.name, types = face.types)
    val ctx0 = EvalContext(s0, controller, id)
    var counters = emptyMap<String, Int>()
    for (cd in card.entersWith) {
        counters = counters + (cd.kind to (counters[cd.kind] ?: 0) + cd.initial.eval(ctx0))
    }
    val perm = Permanent(
        id = id, controller = controller, base = base,
        counters = counters, face = faceIndex, cardId = card.cardKey, diesWhen = card.diesWhen, zone = zone,
        // Stamped HERE, at the one place a permanent becomes a permanent, so a
        // reanimated or moved body cannot acquire a different arrival turn by
        // taking a different route onto the battlefield.
        enteredOnTurn = s0.turnNumber,
    )
    val s = s0.copy(battlefield = s0.battlefield + (id to perm)).wireFace(face, id, controller)
    return s to id
}

/** Put `face`'s triggered abilities and statics in play for permanent `self`
 *  -- the one place enter-play, transform and a token copy all go through. */
fun GameState.wireFace(face: Face, self: ObjectId, controller: PlayerId): GameState {
    var s = this
    val triggers = face.triggers.map { it.compile(self, controller) }
    if (triggers.isNotEmpty()) s = s.copy(triggeredAbilities = s.triggeredAbilities + (self to triggers))
    return wireStatics(s, face.statics, self, controller)
}
