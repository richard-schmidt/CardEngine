package ccg

// ---------------------------------------------------------------------------
// What a player MAY do, and whether a given thing is allowed -- the
// client-facing contract. A bot, an RPC layer or a port needs `PriorityAction`,
// `Legality`, `legality`, `legalActionsFor` and `playActionFor`, not the
// interpreter. `legality` and `legalActionsFor` stay together and one calls the
// other (extended to its inverse).
// ---------------------------------------------------------------------------

sealed interface PriorityAction {
    data object PassPriority : PriorityAction

    /** Give up. Always legal, for whoever is being asked. Leaves a clean final
     *  state with a loser and a log line -- what a bot or remote client needs to
     *  stop, and what a play app puts in its menu. */
    data object Concede : PriorityAction
    /** `spellTypes` is the spell card's types -- lets a "whenever you cast an
     *  instant" trigger match. `cost` is paid before the spell hits the
     *  stack; an unpayable cost makes the cast a no-op. */
    data class CastSpell(
        val effect: Effect,
        val spellTypes: Set<String> = emptySet(),
        val cost: Cost = Cost(),
        /** Where the card is being cast FROM. Non-null makes the cast legal
         *  only while that card is really in that zone, spends it, and puts it
         *  in `afterResolve` when the spell finishes. */
        val from: CardSource? = null,
        /** The card's name, for the log ("$caster casts <label> #id"). */
        val label: String = "",
    ) : PriorityAction

    /** Activate ability #`index` of a battlefield permanent. */
    data class Activate(val source: ObjectId, val index: Int) : PriorityAction
    /** `zone` is a play-zone DEF id; null -> the type's `zoneOfPlay`, else
     *  "battlefield". The engine resolves it to a concrete instance. */
    data class PlayPermanent(
        val card: CardDefinition,
        val face: Int = 0,
        val zone: String? = null,
        /** Same as `CastSpell.from` -- the card is spent out of its zone. */
        val from: CardSource? = null,
    ) : PriorityAction

    /** INDIVIDUAL combat: resolve one attack now. Only meaningful when
     *  `rules.combat.style == INDIVIDUAL`. */
    data class Attack(val attacker: ObjectId, val target: CombatTarget) : PriorityAction
}

/** How a hidden zone reads in the log. */
fun zoneName(z: HiddenZone): String = when (z) {
    HiddenZone.LIBRARY -> "the library"
    HiddenZone.LIBRARY_BOTTOM -> "the bottom of the library"
    HiddenZone.HAND -> "hand"
    HiddenZone.GRAVEYARD -> "the graveyard"
    HiddenZone.EXILE -> "exile"
}

/** `zoneName`, widened to a `CastZone`. */
fun zoneName(z: CastZone): String = when (z) {
    is CastZone.Std -> zoneName(z.zone)
    is CastZone.Declared -> z.id
}

/** The verdict on a `PriorityAction`. */
sealed interface Legality {
    data object Legal : Legality
    data class Denied(val reason: String) : Legality
}

/** THE action for playing or casting a card from a zone, recast cost included --
 *  one function for the engine, the board and the prompt bar. */
fun playActionFor(
    rules: Rules,
    card: CardDefinition,
    from: CardSource?,
    face: Int = 0,
    zoneDef: String? = null,
    label: String? = null,
): PriorityAction {
    val f = card.faces.getOrElse(face) { card.faces[0] }
    return if (rules.isSpell(f.types)) {
        // A card cast out of the zone its `recast` names is paid for at the
        // recast price -- flashback, and EPR Skirmish's graveyard plays.
        val cost = from?.let { src -> card.recast?.takeIf { CastZone.Std(it.from) == src.zone }?.cost } ?: card.cost
        PriorityAction.CastSpell(f.castEffect ?: Effect.NoOp, f.types, cost, from = from, label = label ?: f.name)
    } else {
        PriorityAction.PlayPermanent(card, face, zoneDef, from)
    }
}

/** Every action `player` could legally take right now, EXCLUDING pass and
 *  concede. The inverse of `legality()`, which it calls, so the two agree. It
 *  serves the UI, the pilots, the agent surface and a remote client; in the
 *  engine because the engine cannot reach upward. */
fun legalActionsFor(rules: Rules, state: GameState, player: PlayerId): List<PriorityAction> {
    if (player in state.losers) return emptyList()
    val out = mutableListOf<PriorityAction>()

    // ENUMERATION ORDER IS PART OF THE CONTRACT: pilots break ties by it, and a
    // port must reproduce it. Declared, never inherited from map insertion
    // order: hand first (a list), then declared zones by id, then permanents
    // by ascending object id.
    //
    // Cards castable from any zone this game casts from: the hand, plus every
    // declared custom zone (a Flagship pool).
    val zones = listOf(CastZone.Std(HiddenZone.HAND)) +
        (state.players[player]?.customZones?.keys.orEmpty().sorted().map { CastZone.Declared(it) })
    for (z in zones) {
        for (ref in state.cardsInCast(player, z)) {
            val card = rules.cards[ref.cardId] ?: continue
            val face = card.faces.firstOrNull() ?: continue
            val action = playActionFor(rules, card, CardSource(z, ref.instanceId))
            if (legality(rules, state, player, action) is Legality.Legal) out += action
        }
    }

    // Activated abilities of permanents this player controls, in object-id
    // order -- see the note above.
    for (perm in state.battlefield.values.sortedBy { it.id }) {
        if (perm.controller != player) continue
        state.abilitiesOf(perm.id, rules).forEachIndexed { i, _ ->
            val action = PriorityAction.Activate(perm.id, i)
            if (legality(rules, state, player, action) is Legality.Legal) out += action
        }
    }
    return out
}

/** Has this player anything to do beyond passing? For CLIENTS (a UI deciding
 *  whether to bother a human, a pilot whether to think), never the engine --
 *  a client may offer actions the state does not contain. Excludes `Concede`,
 *  which is always legal and would make this true forever. */
fun hasAnyAction(rules: Rules, state: GameState, player: PlayerId): Boolean =
    legalActionsFor(rules, state, player).isNotEmpty()

/** THE legality check: the engine enforces it, the autopilot uses it to decide
 *  what to queue, the UI to decide what to OFFER (separate partial copies once
 *  disagreed and silently dropped actions).
 *
 *  `Attack` / `Activate` / `PassPriority` are always legal here; their own
 *  preconditions are checked where they resolve. */
fun legality(rules: Rules, state: GameState, player: PlayerId, action: PriorityAction): Legality {
    fun where() = state.phase.ifBlank { "-" }
    /** You may only spend a card you actually hold. This is the whole of "hand
     *  management" as a rule -- everything else is which cards get offered. */
    fun held(from: CardSource?, what: String): Legality? = when {
        from == null -> null
        state.cardsInCast(player, from.zone).none { it.instanceId == from.instanceId } ->
            Legality.Denied("$what is not in ${zoneName(from.zone)}")
        else -> null
    }
    return when (action) {
        // You may always give up -- no phase, cost or timing gate. A rule that
        // could refuse a concession would leave a client with no way out.
        PriorityAction.Concede -> Legality.Legal
        is PriorityAction.CastSpell -> {
            val cost = effectiveCost(state, player, action.cost, action.spellTypes)
            val what = action.label.ifEmpty { "that" }
            held(action.from, what) ?: when {
                !state.canAfford(player, cost) -> Legality.Denied("can't afford $what")
                // A spell with nothing to target is refused before the card
                // leaves hand, so it would stay legal and be chosen forever.
                !canBindChoices(action.effect, player, state, rules = rules) ->
                    Legality.Denied("no legal target for $what")
                rules.instantSpeed(action.spellTypes) || sorcerySpeedWindow(rules, state, player) -> Legality.Legal
                else -> Legality.Denied("can't cast $what now (${where()})")
            }
        }
        is PriorityAction.PlayPermanent -> {
            val f = action.card.faces.getOrElse(action.face) { action.card.faces[0] }
            val cost = effectiveCost(state, player, action.card.cost, f.types)
            val model = rules.resourceModel
            val spent = state.players[player]?.resourcePlaysUsed ?: 0
            val limit = (model as? ResourceModel.CardDriven)?.playsPerTurn ?: Int.MAX_VALUE
            held(action.from, f.name) ?: when {
                // A type declaring its zone set has it enforced; other types stay
                // unrestricted. Checked before cost, so a wrong zone reads as a
                // zone problem.
                action.zone != null && rules.legalZonesFor(f.types)?.let { action.zone !in it } == true ->
                    Legality.Denied("${action.zone} is not a legal zone for ${f.name}")
                // A declared precondition ("only while you control a Ship").
                action.card.requires?.let { req ->
                    state.battlefield.keys.none { req.matches(EvalContext(state, player, null), it) }
                } == true ->
                    Legality.Denied("${f.name} needs something in play to attach to / target")
                // Capacity: a chosen zone must have room; an unspecified one
                // fails only if EVERY legal destination is full.
                !(action.zone?.let { rules.zoneHasRoom(state, player, it) }
                    ?: (rules.legalZonesFor(f.types) ?: setOf(rules.defaultZoneDef(f.types)))
                        .any { rules.zoneHasRoom(state, player, it) }) ->
                    Legality.Denied("no room to play ${f.name}")
                !state.canAfford(player, cost) -> Legality.Denied("can't afford ${f.name}")
                // The land drop: one clause, in the single legality check the
                // engine and the UI both go through.
                model.limitsPlayOf(f.types) && spent >= limit ->
                    Legality.Denied("already played $limit resource(s) this turn")
                sorcerySpeedWindow(rules, state, player) -> Legality.Legal
                else -> Legality.Denied("can't play ${f.name} now (${where()})")
            }
        }
        is PriorityAction.Activate -> {
            val perm = state.battlefield[action.source]
            val ability = state.abilitiesOf(action.source, rules).getOrNull(action.index)
            when {
                perm == null -> Legality.Denied("#${action.source} is gone")
                // Report the real reason -- `abilitiesOf` returns nothing at
                // all when a static has removed them, which would otherwise
                // read as "no such ability".
                state.characteristicsOf(action.source).abilitiesRemoved ->
                    Legality.Denied("#${action.source} has lost its abilities")
                ability == null -> Legality.Denied("#${action.source} has no ability #${action.index}")
                perm.controller != player -> Legality.Denied("#${action.source} isn't yours")
                state.activateBarred(action.source) -> Legality.Denied("#${action.source} can't activate abilities")
                ability.oncePerTurn && action.index in perm.activatedThisTurn ->
                    Legality.Denied("#${action.source} already used that this turn")
                !state.canAfford(player, effectiveCost(state, player, ability.cost, perm.base.types), action.source) ->
                    Legality.Denied("can't pay for #${action.source} ability #${action.index}")
                // The engine will decline this WITHOUT paying for it, leaving
                // the ability untapped and still legal -- which is an infinite
                // priority loop, not a fizzle. Refuse it here instead, using
                // the one definition the engine itself binds with.
                !canBindChoices(ability.effect.bindSelf(action.source), player, state, action.source, rules) ->
                    Legality.Denied("no legal target for #${action.source} ability #${action.index}")
                else -> Legality.Legal
            }
        }
        PriorityAction.PassPriority, is PriorityAction.Attack -> Legality.Legal
    }
}

// ---------------------------------------------------------------------------
// Can this effect's choices be bound at all?
//
// `Engine.bindChoices` returns null when a `Choose` has no candidates and the
// action does not happen, without paying its cost -- so if `legality` approved
// it, the action stayed legal and the priority loop could spin. The emptiness
// question therefore lives HERE, once, and both consult it.
//
// CONSERVATIVE: false only when no binding can exist regardless of choices. A
// `Choose` nested in another's body may filter on the outer pick, so it reports
// true; the priority loop's no-progress rule covers that residue.
// ---------------------------------------------------------------------------

/** The range an X is asked in -- by the engine, and by `canBindChoices`
 *  when it looks for an X that lets an effect bind. */
const val MAX_X = 99

/** The candidates an `Effect.Choose` would offer, with no player asked. */
fun chooseCandidates(
    filter: PermFilter,
    chooser: PlayerId,
    state: GameState,
    source: ObjectId? = null,
): List<ObjectId> =
    state.inPlayIds.filter { filter.matches(EvalContext(state, chooser, source), it) }

/** The stack items `e` may counter, most recent LAST -- THE definition,
 *  shared by `legality`, the announcement and resolution. */
fun counterCandidates(e: Effect.CounterSpell, controller: PlayerId, state: GameState, source: ObjectId? = null): List<ObjectId> {
    val ctx = EvalContext(state, controller, source)
    return state.stack.filter { so ->
        val types = when (so) {
            is SpellOnStack -> so.types
            is PermanentOnStack -> so.card.faces.getOrElse(so.face) { so.card.faces[0] }.types
            else -> return@filter false
        }
        (e.whose == null || ctx.names(e.whose, so.controller)) &&
            (e.types.isEmpty() || types.any { it in e.types })
    }.map { it.id }
}

/** Could this effect bind every choice it needs, in this state? */
fun canBindChoices(
    effect: Effect,
    chooser: PlayerId,
    state: GameState,
    source: ObjectId? = null,
    /** When supplied, also refuses an effect the engine would decline for lack of
     *  room in a destination zone (a repeatable move into a full lane). Null
     *  keeps `Engine.bindChoices` unchanged; `legality` is merely stricter,
     *  which is the safe direction. */
    rules: Rules? = null,
): Boolean = when {
    // X is chosen BEFORE targets, so an effect whose choices read X can
    // bind if SOME X lets it. Checked at X = 0 alone, "destroy a Ship costing
    // X or less" was never offered while only costlier Ships were in play.
    effect.usesX() -> (0..MAX_X).any { canBindChoices(effect.substituteX(it), chooser, state, source, rules) }
    else -> canBindFixed(effect, chooser, state, source, rules)
}

private fun canBindFixed(
    effect: Effect,
    chooser: PlayerId,
    state: GameState,
    source: ObjectId?,
    rules: Rules?,
): Boolean = when (effect) {
    is Effect.Choose -> chooseCandidates(effect.filter, chooser, state, source).isNotEmpty()
    // Counter TARGET spell: something must be there to pick.
    is Effect.CounterSpell -> effect.target != null || counterCandidates(effect, chooser, state, source).isNotEmpty()
    // Mirrors `Engine.bindModes`: only bindable options are offered, and it
    // must be able to pick as many as it asks for.
    is Effect.ChooseMode -> {
        val want = effect.pick.eval(EvalContext(state, chooser, source)).coerceIn(0, effect.options.size)
        effect.options.count { canBindChoices(it, chooser, state, source, rules) } >= want
    }
    // Mirrors `Engine.bindChoices`: exact counts need that many candidates.
    is Effect.ChooseMany -> effect.upTo ||
        chooseCandidates(effect.filter, chooser, state, source).size >=
        effect.count.eval(EvalContext(state, chooser, source))
    // Every step must bind -- `bindChoices` fails the whole sequence if one
    // does, so this has to as well.
    is Effect.Sequence -> effect.steps.all { canBindChoices(it, chooser, state, source, rules) }
    is Effect.MovePermanent -> rules == null || run {
        val id = if (effect.target.name == SELF) source else effect.target.id
        val perm = id?.let { state.battlefield[it] }
        if (perm == null) {
            true
        } else {
            val dest = if (effect.toZone.owner == null) {
                rules.resolveZone(effect.toZone.def, perm.controller)
            } else {
                effect.toZone
            }
            // Already there is a harmless no-op the priority loop's
            // no-progress rule handles; NO ROOM is the one that spins.
            perm.zone == dest || rules.zoneHasRoom(state, perm.controller, dest)
        }
    }
    // `bindChoices` keeps an unbindable branch as-is rather than failing, so an
    // `If` never makes an action impossible.
    else -> true
}
