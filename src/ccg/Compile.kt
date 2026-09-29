package ccg

// ---------------------------------------------------------------------------
// The compiler. `GameDoc.compile()` is the one path from authored content to
// what the engine runs: it ASSEMBLES the `Rules` and DIAGNOSES the doc. A
// `Diagnostic` says WHERE, so the Creator can take the author there.
// ---------------------------------------------------------------------------

/** One thing wrong with a game, as data.
 *
 *  [path] names the part of the doc it is about, from the card's face when
 *  [cardKey] is set, from the game when it is not:
 *
 *      castEffect · triggers[i] · activated[i] · statics[i] · keywords · ruleMods[i]
 *      decks[i] · rules.turn · rules.params · rules.zones · rules.types[<name>]
 *
 *  "" is the whole thing. Indices are 0-based; [message] numbers from 1, as
 *  the author reads them. */
data class Diagnostic(
    /** The kind of problem, which carries its severity. */
    val kind: DiagCode,
    /** The card it is about (`CardDoc.key()`), or null for a game-level one. */
    val cardKey: String? = null,
    /** Which face of that card. Null for a game-level diagnostic. */
    val face: Int? = null,
    val path: String = "",
    val message: String,
) {
    /** A stable name for the kind of problem -- what a test or a screen keys on. */
    val code: String get() = kind.id
    val severity: Severity get() = kind.severity
}

/** An ERROR makes the game play differently from what was authored -- a name
 *  that resolves to nothing, a read of a variable nothing binds -- so a game
 *  with one is not started. A WARNING is reported and never enforced:
 *  deck legality, a keyword nothing reads, an empty deck. */
enum class Severity { ERROR, WARNING }

/** Every kind of problem the compiler reports, each with its severity --
 *  decided here once, so no call site can report one problem at two. */
enum class DiagCode(val severity: Severity) {
    // A name that resolves to nothing.
    UNKNOWN_TYPE(Severity.ERROR), UNKNOWN_FIELD(Severity.ERROR), UNKNOWN_COUNTER(Severity.ERROR),
    UNKNOWN_PLAYER_COUNTER(Severity.ERROR), UNKNOWN_PHASE(Severity.ERROR), UNKNOWN_ZONE(Severity.ERROR),
    UNKNOWN_STEP(Severity.ERROR), UNKNOWN_STANCE(Severity.ERROR), UNKNOWN_PARAM(Severity.ERROR),
    UNKNOWN_CARD(Severity.ERROR), UNKNOWN_COMBAT_PRESET(Severity.ERROR),
    // A variable nothing binds here.
    FREE_X(Severity.ERROR), FREE_SHARE(Severity.ERROR), FREE_CHOSEN_TARGET(Severity.ERROR),
    FREE_CHOSEN_PLAYER(Severity.ERROR), FREE_EACH_TARGET(Severity.ERROR), FREE_SELF_READ(Severity.ERROR),
    FREE_EVENT(Severity.ERROR), FREE_SUBJECT(Severity.ERROR), FREE_NAMED(Severity.ERROR),
    // Replacements, and what only one can do.
    REPLACEMENT_PATTERN(Severity.ERROR), REPLACEMENT_ASKS(Severity.ERROR),
    PROCEED_OUTSIDE_REPLACEMENT(Severity.ERROR), DIES_FILTER_ON_TRIGGER(Severity.ERROR),
    // Players: damage to nobody who takes it, an opponent among several.
    NO_PLAYER_DAMAGE(Severity.ERROR), AMBIGUOUS_OPPONENT(Severity.ERROR),
    // A card that starts there goes to the hidden zone, not the declared one.
    ZONE_COLLISION(Severity.ERROR),
    // Reported, never enforced. A counter declared to cancel
    // itself is one: the declaration is ignored, so nothing plays differently.
    COUNTER_CANCELS_ITSELF(Severity.WARNING), DUPLICATE_NAME(Severity.WARNING), TURN(Severity.WARNING), PARAMS(Severity.WARNING),
    NO_LOSS(Severity.WARNING), EMPTY_DECK(Severity.WARNING), DEAD_KEYWORD(Severity.WARNING),
    DECK_RULES(Severity.WARNING),
    ;

    /** "unknown-type", "free-self-read": the name tests and screens key on. */
    val id: String get() = name.lowercase().replace('_', '-')
}

/** The code for a name of this kind that resolves to nothing. */
fun NameKind.unknown(): DiagCode = when (this) {
    NameKind.TYPE -> DiagCode.UNKNOWN_TYPE
    NameKind.FIELD -> DiagCode.UNKNOWN_FIELD
    NameKind.COUNTER -> DiagCode.UNKNOWN_COUNTER
    NameKind.PLAYER_COUNTER -> DiagCode.UNKNOWN_PLAYER_COUNTER
    NameKind.PHASE -> DiagCode.UNKNOWN_PHASE
    NameKind.ZONE -> DiagCode.UNKNOWN_ZONE
    NameKind.STEP -> DiagCode.UNKNOWN_STEP
    NameKind.STANCE -> DiagCode.UNKNOWN_STANCE
    NameKind.PARAM -> DiagCode.UNKNOWN_PARAM
}

/** The code for a read of this variable where nothing binds it. */
fun FreeVar.code(): DiagCode = when (this) {
    FreeVar.X -> DiagCode.FREE_X
    FreeVar.SHARE -> DiagCode.FREE_SHARE
    FreeVar.CHOSEN_TARGET -> DiagCode.FREE_CHOSEN_TARGET
    FreeVar.CHOSEN_PLAYER -> DiagCode.FREE_CHOSEN_PLAYER
    FreeVar.EACH_TARGET -> DiagCode.FREE_EACH_TARGET
    FreeVar.SELF_READ -> DiagCode.FREE_SELF_READ
    FreeVar.EVENT -> DiagCode.FREE_EVENT
    FreeVar.SUBJECT -> DiagCode.FREE_SUBJECT
    FreeVar.NAMED -> DiagCode.FREE_NAMED
}

/** A compiled game: what the engine runs, and what is wrong with it. */
data class Compiled(val rules: Rules, val diagnostics: List<Diagnostic>) {
    val errors: List<Diagnostic> get() = diagnostics.filter { it.severity == Severity.ERROR }

    /** The rules, when the game may be played: null while it has errors. */
    val runnable: Rules? get() = rules.takeIf { errors.isEmpty() }

    /** [runnable], or [NotPlayable] naming the errors -- for callers with no
     *  screen to show them on (the agent CLI). */
    fun playable(): Rules = runnable ?: throw NotPlayable(errors)
}

/** A game with error diagnostics was asked to start. */
class NotPlayable(val errors: List<Diagnostic>) :
    IllegalStateException("the game has errors and cannot start:\n" + errors.joinToString("\n") { "  " + it.message })

/** The `Rules` a game doc describes. The only place one is built. */
internal fun assemble(g: GameDoc): Rules = Rules(
    // Lowered -- a type's `diesWhen` is authored in the same language.
    types = (BUILTIN_TYPES + g.rules.extraTypes.associateBy { it.name }).mapValues { it.value.lowered(g.rules.damageCounter, g.rules.params) },
    zones = BUILTIN_ZONES + g.rules.extraZones.associateBy { it.id },
    // The combat's programs, lowered like every other authored effect.
    combat = g.rules.combat.compile().lowered(g.rules.damageCounter, g.rules.params),
    // A phase's work is authored in the same language, so it is lowered too
    // -- with the game's params filled, which the engine would otherwise
    // do on every phase.
    turn = g.rules.turn.copy(phases = g.rules.turn.phases.map { it.copy(onEnter = it.onEnter.lowered(g.rules.damageCounter, g.rules.params)) }),
    // Keyed on `CardDoc.key()` (minted id, else display name), the identifier
    // deck entries and slots hold.
    cards = g.cards.associate { it.key() to it.build(g.rules.damageCounter, g.rules.params) },
    params = g.rules.params,
    resourceModel = g.rules.resourceModel,
    playerCounters = g.rules.playerCounters,
    hiddenZones = g.rules.extraHiddenZones.associateBy { it.id },
    damageCounter = g.rules.damageCounter,
    counterKinds = BUILTIN_COUNTER_DEFS + g.rules.counterKinds.orEmpty(),
)

/** Everything wrong with `g`, whose assembled rules are [built]. */
internal fun diagnose(g: GameDoc, built: Rules): List<Diagnostic> = buildList {
    fun game(code: DiagCode, path: String, message: String) = add(Diagnostic(code, path = path, message = message))

    g.rules.combat.unknownPreset()?.let {
        game(DiagCode.UNKNOWN_COMBAT_PRESET, "rules.combat", "combat preset \"$it\" doesn't exist -- the game has no combat until one is chosen")
    }

    g.duplicateNames().forEach {
        add(Diagnostic(DiagCode.DUPLICATE_NAME, cardKey = it, message = "two cards are both named \"$it\" -- one will shadow the other"))
    }
    g.rules.turn.problems(g.rules.params).forEach { game(DiagCode.TURN, "rules.turn", it) }
    g.rules.params.problems().forEach { game(DiagCode.PARAMS, "rules.params", it) }
    if (g.rules.playerCounters.none { it.loseAtZero } && g.rules.extraTypes.none { it.loseOnDeath }) {
        game(DiagCode.NO_LOSS, "rules", "no player counter ends the game at zero, and no type ends it on death -- nobody can lose except by decking")
    }
    g.rules.counterKinds?.filter { it.cancels == it.name }?.forEach {
        game(DiagCode.COUNTER_CANCELS_ITSELF, "rules.counterKinds[${it.name}]", "counter \"${it.name}\" is declared to cancel itself -- that would remove them all, so the declaration is ignored")
    }
    g.decks.forEachIndexed { i, d -> if (d.entries.isEmpty()) game(DiagCode.EMPTY_DECK, "decks[$i]", "deck \"${d.name}\" is empty") }
    // A keyword nothing reads is a card that silently does less than its
    // text says. (A forbidden action nothing honours cannot be written:
    // `RuleMod.Cant.action` is a `RuleAction`.)
    val meaningful = g.keywordsWithRules()
    for (card in g.cards) {
        val key = card.key()
        card.faces.forEachIndexed { fi, f ->
            addAll(f.scopeDiagnostics(key, fi))
            val granted = f.statics.flatMap { it.ops }.filterIsInstance<CharOp.GrantKeyword>().map { it.keyword }
            (f.keywords + granted).filterNot { it in meaningful }.distinct().sorted().forEach {
                add(Diagnostic(DiagCode.DEAD_KEYWORD, key, fi, "keywords", "\"${f.name}\" has keyword \"$it\", which no rule in this game reads -- it does nothing"))
            }
        }
    }
    // `known` uses CardDoc.key(), the same identifier `assemble` keys on.
    val known = g.cards.map { it.key() }.toSet()
    g.decks.forEachIndexed { i, d ->
        d.entries.map { it.cardName }.filterNot { it in known }.distinct().forEach {
            game(DiagCode.UNKNOWN_CARD, "decks[$i]", "deck \"${d.name}\" lists \"$it\", which no set defines")
        }
    }
    // A zone id declared as BOTH a play zone and a hidden zone is
    // ambiguous -- setupGame must pick one deterministically (it checks
    // hidden zones first), so report the collision rather than let a
    // start-in-play card silently land in the wrong place.
    val zoneCollisions = g.rules.extraZones.map { it.id }.toSet()
        .intersect(g.rules.extraHiddenZones.map { it.id }.toSet())
    zoneCollisions.sorted().forEach {
        game(DiagCode.ZONE_COLLISION, "rules.zones", "\"$it\" is declared as both a play zone and a hidden zone -- a card that starts there goes to the hidden zone")
    }
    // A type's zoneOfPlay / zoneChoices naming an undeclared zone is a typo
    // that would otherwise resolve to an invisible, uncapped shared zone.
    val declaredZoneIds = setOf("battlefield") + g.rules.extraZones.map { it.id }
    g.rules.extraTypes.forEach { t ->
        (listOfNotNull(t.zoneOfPlay) + t.zoneChoices).filterNot { it in declaredZoneIds }.distinct().sorted().forEach {
            game(DiagCode.UNKNOWN_ZONE, "rules.types[${t.name}]", "type \"${t.name}\" names zone \"$it\", which isn't declared anywhere -- check for a typo")
        }
    }
    addAll(unknownNames(g, built))
    addAll(replacementDiagnostics(g))
    addAll(opponentDiagnostics(g))
    // deck-construction legality, reported the same way.
    g.decks.forEachIndexed { i, d -> g.deckRules.problems(d, built).forEach { game(DiagCode.DECK_RULES, "decks[$i]", it) } }
}

// -- replacements ---------------------------------------------------------
// A replacement's `instead` runs inside the synchronous event machinery, so it
// may not ask anyone anything; `Proceed` means something only there; and a
// death is known "as it was in play" only before it happens.

/** The patterns a replacement can match: events that have not happened yet. */
private fun EventPattern.replaceable(): Boolean =
    this is EventPattern.Damaged || this is EventPattern.PlayerDamaged || this is EventPattern.Dies

/** Every effect node `visit` reaches that runs NOW -- not a `Delayed` body or
 *  a granted ability, which run later, on their own. */
private fun runsNow(visit: (Subst) -> Unit): List<Effect> {
    val out = mutableListOf<Effect>()
    visit(object : Subst() {
        override fun shadowedBy(binder: Binder, name: String?) = binder == Binder.EVENT || binder == Binder.GRANT
        override fun rewrite(e: Effect): Effect = e.also { out += it }
    })
    return out
}

/** Would running this ask a player something? */
private fun Effect.asks(): Boolean = when (this) {
    is Effect.Choose, is Effect.ChooseMany, is Effect.ChooseMode, is Effect.Discard, is Effect.DrawThenDiscard,
    is Effect.LookAtTop, is Effect.Sacrifice, is Effect.SearchZone,
    // Combat asks who attacks and blocks, and opens windows.
    is Effect.DeclareAttackers, is Effect.DeclareBlockers, Effect.CombatWindow, is Effect.Attack, is Effect.FreeAttacks,
    -> true
    is Effect.AsPlayer -> who == PlayerRef.Chosen
    else -> false
}

private fun replacementDiagnostics(g: GameDoc): List<Diagnostic> = buildList {
    fun proceedIn(key: String?, fi: Int?, path: String, where: String, visit: (Subst) -> Unit) {
        if (runsNow(visit).any { it is Effect.Proceed }) {
            add(Diagnostic(DiagCode.PROCEED_OUTSIDE_REPLACEMENT, key, fi, path, "$where lets an event happen, but only a replacement has one to let happen"))
        }
    }
    for (card in g.cards) {
        val key = card.key()
        card.faces.forEachIndexed { fi, f ->
            val n = "\"${f.name}\""
            f.castEffect?.let { e -> proceedIn(key, fi, "castEffect", "$n cast effect") { e.subst(it) } }
            f.triggers.forEachIndexed { i, t ->
                proceedIn(key, fi, "triggers[$i]", "$n trigger ${i + 1}") { t.effect.subst(it) }
                if ((t.pattern as? EventPattern.Dies)?.filter != null) {
                    add(Diagnostic(DiagCode.DIES_FILTER_ON_TRIGGER, key, fi, "triggers[$i]", "$n trigger ${i + 1} tests the dying permanent as it was in play, which only a replacement can see -- it would never fire"))
                }
            }
            f.activated.forEachIndexed { i, a -> proceedIn(key, fi, "activated[$i]", "$n ability ${i + 1}") { a.effect.subst(it) } }
            f.replacements.forEachIndexed { i, r ->
                val rep = r as? ReplacementDoc.Replace ?: return@forEachIndexed
                val path = "replacements[$i]"
                if (!rep.pattern.replaceable()) {
                    add(Diagnostic(DiagCode.REPLACEMENT_PATTERN, key, fi, path, "$n replacement ${i + 1} can't replace that event -- only damage to a permanent, damage to a player or a death"))
                }
                runsNow { rep.instead.subst(it) }.filter { it.asks() }.map { it::class.simpleName }.distinct().forEach {
                    add(Diagnostic(DiagCode.REPLACEMENT_ASKS, key, fi, path, "$n replacement ${i + 1} would ask a player to choose ($it), and a replacement can't wait for an answer"))
                }
            }
        }
    }
    g.rules.turn.phases.forEachIndexed { i, ph -> proceedIn(null, null, "rules.turn.phases[$i]", "phase \"${ph.name}\"") { ph.onEnter.subst(it) } }
}

// -- opponents --------------------------------------------------------------
// Where "opponent" MATCHES it is any opponent, at any player count. Where a
// verb acts on it, or an expression reads it, it has to be ONE player -- and
// with three or more there is no one to pick.

private fun opponentDiagnostics(g: GameDoc): List<Diagnostic> = buildList {
    val n = g.rules.params.playerCount
    if (n <= 2) return@buildList
    fun check(key: String?, fi: Int?, path: String, where: String, visit: (Subst) -> Unit) {
        var hit = false
        visit(object : Subst() {
            override val intoTemplates get() = true
            override fun actsOn(r: PlayerRef) { if (r == PlayerRef.Opponent) hit = true }
            override fun reads(r: PlayerRef) { if (r == PlayerRef.Opponent) hit = true }
        })
        if (hit) add(Diagnostic(DiagCode.AMBIGUOUS_OPPONENT, key, fi, path,
            "$where names \"the opponent\", and with $n players there is more than one -- say each opponent, or a chosen player"))
    }
    for (card in g.cards) {
        val key = card.key()
        card.faces.forEachIndexed { fi, f ->
            val w = "\"${f.name}\""
            f.castEffect?.let { e -> check(key, fi, "castEffect", "$w cast effect") { e.subst(it) } }
            f.triggers.forEachIndexed { i, t -> check(key, fi, "triggers[$i]", "$w trigger ${i + 1}") { t.effect.subst(it) } }
            f.activated.forEachIndexed { i, a -> check(key, fi, "activated[$i]", "$w ability ${i + 1}") { a.effect.subst(it) } }
            f.statics.forEachIndexed { i, st ->
                check(key, fi, "statics[$i]", "$w static ${i + 1}") { s -> st.ops.forEach { it.subst(s) }; st.condition?.subst(s) }
            }
            f.replacements.forEachIndexed { i, r -> check(key, fi, "replacements[$i]", "$w replacement ${i + 1}") { r.names(it) } }
        }
    }
    g.rules.turn.phases.forEachIndexed { i, ph -> check(null, null, "rules.turn.phases[$i]", "phase \"${ph.name}\"") { ph.onEnter.subst(it) } }
}

// -- names -------------------------------------------------------------------
// Every name a game uses, resolved against what it declares. An undeclared
// type, field, zone, phase or counter is almost always a typo, and each fails
// SILENTLY in play (matches nothing, reads 0, never fires).

/** One name, where it was used. `face` null = the card as a whole. */
internal data class NameUse(
    val kind: NameKind, val value: String,
    val cardKey: String?, val face: Int?, val path: String,
    /** How the message names the place: "\"Bolt\" trigger 2", "type \"Ship\"". */
    val where: String,
)

/** The player counter a use of the damage counter names, in a game whose
 *  players take no damage (`RulesDoc.damageCounter` null). No real counter
 *  has an empty name, so it can never resolve. */
private const val NO_DAMAGE_COUNTER = ""

/** [damageCounter] is the game's: a use of "the damage counter" is a use of
 *  that player counter's name. */
private class Collect(private val damageCounter: String?, private val sink: (NameKind, String) -> Unit) : Subst() {
    override val intoTemplates: Boolean get() = true
    override fun name(kind: NameKind, value: String) = sink(kind, value)
    override fun usesDamageCounter() = sink(NameKind.PLAYER_COUNTER, damageCounter ?: NO_DAMAGE_COUNTER)
}

/** Every name the game uses: its cards, its types and its combat steps. */
internal fun nameUses(g: GameDoc): List<NameUse> = buildList {
    /** Walk `visit` and report every name it meets as used at (path, where). */
    fun at(cardKey: String?, face: Int?, path: String, where: String, visit: (Subst) -> Unit) =
        visit(Collect(g.rules.damageCounter) { k, v -> add(NameUse(k, v, cardKey, face, path, where)) })

    g.rules.damageCounter?.let { at(null, null, "rules.damageCounter", "player damage") { s -> s.name(NameKind.PLAYER_COUNTER, it) } }
    for (card in g.cards) {
        val key = card.key()
        val cardName = "\"${card.faces.firstOrNull()?.name ?: key}\""
        at(key, null, "entersWith", "$cardName enters-with") { s -> card.entersWith.forEach { s.name(NameKind.COUNTER, it.kind); it.initial.subst(s) } }
        at(key, null, "cost", "$cardName cost") { s -> card.cost.names(s) }
        card.recast?.let { r -> at(key, null, "recast", "$cardName recast cost") { s -> r.cost.names(s) } }
        card.requires?.let { f -> at(key, null, "requires", "$cardName requirement") { s -> f.subst(s) } }
        card.diesWhen?.let { c -> at(key, null, "diesWhen", "$cardName dies-when") { s -> c.subst(s) } }
        card.faces.forEachIndexed { fi, f ->
            val n = "\"${f.name}\""
            f.castEffect?.let { e -> at(key, fi, "castEffect", "$n cast effect") { s -> e.subst(s) } }
            f.triggers.forEachIndexed { i, tr -> at(key, fi, "triggers[$i]", "$n trigger ${i + 1}") { s -> tr.pattern.names(s); tr.effect.subst(s) } }
            f.activated.forEachIndexed { i, a -> at(key, fi, "activated[$i]", "$n ability ${i + 1}") { s -> a.cost.names(s); a.effect.subst(s) } }
            f.statics.forEachIndexed { i, st ->
                at(key, fi, "statics[$i]", "$n static ${i + 1}") { s -> st.filter.subst(s); st.ops.forEach { it.subst(s) }; st.condition?.subst(s) }
            }
            f.ruleMods.forEachIndexed { i, m -> at(key, fi, "ruleMods[$i]", "$n rule ${i + 1}") { s -> m.names(s) } }
            f.costMods.forEachIndexed { i, m -> at(key, fi, "costMods[$i]", "$n cost change ${i + 1}") { s -> m.types.forEach { s.name(NameKind.TYPE, it) } } }
            f.replacements.forEachIndexed { i, r -> at(key, fi, "replacements[$i]", "$n replacement ${i + 1}") { s -> r.names(s) } }
        }
    }
    g.rules.counterKinds?.forEach { k ->
        k.cancels?.let { c -> at(null, null, "rules.counterKinds[${k.name}]", "counter \"${k.name}\"") { s -> s.name(NameKind.COUNTER, c) } }
    }
    g.rules.turn.phases.forEachIndexed { i, ph ->
        if (ph.onEnter != Effect.NoOp) at(null, null, "rules.turn.phases[$i]", "phase \"${ph.name}\"") { s -> ph.onEnter.subst(s) }
    }
    for (td in g.rules.extraTypes) {
        at(null, null, "rules.types[${td.name}]", "type \"${td.name}\"") { s ->
            td.fields.forEach { s.name(NameKind.FIELD, it) }
            td.damageCounter?.let { s.name(NameKind.COUNTER, it) }
            td.layout?.counterKind?.let { s.name(NameKind.COUNTER, it) }
            td.diesWhen?.subst(s)
        }
    }
    // The combat's programs name what they read -- the stats a fighter
    // strikes with, the types and keywords its filters test -- exactly as a
    // card's effect does.
    g.rules.combat.compile().let { c ->
        c.program?.let { p -> at(null, null, "rules.combat", "the combat") { s -> p.subst(s) } }
        c.attack?.let { p -> at(null, null, "rules.combat.attack", "an attack") { s -> p.subst(s) } }
    }
    g.deckRules.slots.forEach { slot ->
        at(null, null, "deckRules", "deck slot \"${slot.name}\"") { s -> slot.types.forEach { s.name(NameKind.TYPE, it) } }
    }
}

/** The steps a combat's programs declare, by name. */
fun Combat.stepNames(): Set<String> {
    val found = mutableSetOf<String>()
    val collect = stepCollector(found)
    program?.subst(collect); attack?.subst(collect)
    return found
}

/** The stances a combat's clashes know. */
fun Combat.stanceNames(): Set<String> {
    val found = mutableSetOf<String>()
    val collect = object : Subst() {
        override fun rewrite(e: Effect): Effect = e.also { if (it is Effect.Clash) found += it.stances.map { st -> st.name } }
    }
    program?.subst(collect); attack?.subst(collect)
    return found
}

private fun stepCollector(found: MutableSet<String>) = object : Subst() {
    override val intoTemplates get() = true
    override fun rewrite(e: Effect): Effect = e.also {
        when (it) {
            is Effect.CombatDamage -> found += it.step
            is Effect.Strike -> found += it.step
            is Effect.Clash -> found += it.step
            is Effect.FreeAttacks -> found += it.step
            else -> {}
        }
    }
}

/** The damage steps a game's cards declare: a `CombatDamage` NAMES its step
 *  (an extra combat's "extra"), so a trigger may listen for it. */
private fun declaredDamageSteps(g: GameDoc): Set<String> {
    val found = mutableSetOf<String>()
    val collect = stepCollector(found)
    for (card in g.cards) for (f in card.faces) {
        f.castEffect?.subst(collect)
        f.triggers.forEach { it.effect.subst(collect) }
        f.activated.forEach { it.effect.subst(collect) }
        f.replacements.forEach { it.names(collect) }
    }
    g.rules.turn.phases.forEach { it.onEnter.subst(collect) }
    return found
}

/** What the game declares for each kind of name, or null where it cannot
 *  be known -- counters never declared (an older game), combat steps under
 *  an unknown preset (reported once, as that). A null kind is not checked. */
internal fun declaredNames(g: GameDoc, built: Rules): Map<NameKind, Set<String>> {
    val types = BUILTIN_TYPES.values + g.rules.extraTypes
    return buildMap {
        // A card's own types and fields DECLARE them, as tags ("Flagship",
        // "Scout") that no TypeDef needs to know about. What gets checked is
        // every REFERENCE -- a filter, a pattern, a cost, a stat read -- to a
        // name nothing declares and no card carries.
        val faces = g.cards.flatMap { it.faces }
        put(NameKind.TYPE, types.map { it.name }.toSet() + faces.flatMap { it.types })
        put(NameKind.FIELD, types.flatMap { it.fields }.toSet() + faces.flatMap { it.fields.keys })
        g.rules.counterKinds?.let { put(NameKind.COUNTER, BUILTIN_COUNTER_KINDS.toSet() + it.map { k -> k.name }) }
        put(NameKind.PLAYER_COUNTER, g.rules.playerCounters.map { it.name }.toSet())
        put(NameKind.PHASE, g.rules.turn.phases.map { it.name }.toSet())
        put(NameKind.ZONE, setOf("battlefield") + g.rules.extraZones.map { it.id })
        if (g.rules.combat.unknownPreset() == null) {
            put(NameKind.STEP, built.combat.stepNames() + DECLARE_ATTACKERS_STEP + DECLARE_BLOCKERS_STEP + declaredDamageSteps(g))
            put(NameKind.STANCE, built.combat.stanceNames())
        }
        put(NameKind.PARAM, PARAM_NAMES.toSet())
    }
}

private fun unknownNames(g: GameDoc, built: Rules): List<Diagnostic> {
    val known = declaredNames(g, built)
    // A type's own declared fields are declarations, not uses.
    return nameUses(g).filterNot { it.kind == NameKind.FIELD && it.path.startsWith("rules.types[") }
        .filter { u -> known[u.kind]?.let { u.value !in it } ?: false }
        .distinct()
        .map { u ->
            if (u.kind == NameKind.PLAYER_COUNTER && u.value == NO_DAMAGE_COUNTER) Diagnostic(DiagCode.NO_PLAYER_DAMAGE, u.cardKey, u.face, u.path,
                "${u.where} damages a player or uses their damage counter, but players in this game take no damage",
            ) else Diagnostic(
                u.kind.unknown(), u.cardKey, u.face, u.path,
                "${u.where} names ${u.kind.noun} \"${u.value}\", which this game doesn't declare",
            )
        }
}

/** The counter kinds this game's cards and types use that are not builtin. */
fun GameDoc.usedCounterKinds(): List<String> =
    nameUses(this).filter { it.kind == NameKind.COUNTER }.map { it.value }
        .filterNot { it in BUILTIN_COUNTER_KINDS }.distinct().sorted()

/** A game that never declared its counter kinds declares exactly the ones it
 *  uses, once. Idempotent (a declaration, even empty, is kept), so afterwards a
 *  misspelt kind is reported. Run by `GameStore` on load and save. */
fun GameDoc.withDeclaredCounters(): GameDoc =
    if (rules.counterKinds != null) this
    else copy(rules = rules.copy(counterKinds = usedCounterKinds().map { CounterKindDef(it) }))
