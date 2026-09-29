package ccg

// ---------------------------------------------------------------------------
// What a player answered.
//
// A play session is (bundle, seed, deck picks, ANSWERS), so the answer list is
// this project's protocol: typed, with an exhaustive codec.
//
// THE RULE THAT SHAPES EVERY CASE: an answer carries REFERENCES, never objects
// -- "the card with this instance id, in this zone" -- and the receiving side
// rebuilds the action from its own state. Both ends agree because they run the
// same `legalActionsFor`. That is what a wire format, a save file and a
// conformance corpus all need.
// ---------------------------------------------------------------------------

sealed interface Answer {
    /** Do nothing this window. */
    data object Pass : Answer

    /** Give up (`PriorityAction.Concede`). */
    data object Concede : Answer

    /** Play or cast the card with `instanceId`, out of `zone`. Which VERB that
     *  is -- played or cast -- is a rules question the receiver answers with
     *  `Rules.isSpell`, not something the wire has to decide. */
    data class PlayCard(
        val instanceId: ObjectId,
        val zone: CastZone,
        val face: Int = 0,
        /** The play zone chosen, when the game offers a choice (a lane). */
        val zoneDef: String? = null,
    ) : Answer

    /** Activate ability `index` of the permanent `source`. */
    data class ActivateAbility(val source: ObjectId, val index: Int) : Answer

    /** A chosen object (`Question.PickTarget`). */
    data class Target(val id: ObjectId) : Answer

    /** A chosen number (`Question.PickNumber`). */
    data class Number(val n: Int) : Answer

    /** DECLARED combat: who attacks what. */
    data class Attackers(val assignment: Map<ObjectId, CombatTarget>) : Answer

    /** DECLARED combat: blocker -> attacker. */
    data class Blockers(val assignment: Map<ObjectId, ObjectId>) : Answer

    /** FREE combat: what one attacker hits, or null to hold it back. */
    data class CombatTgt(val target: CombatTarget?) : Answer

    /** INDIVIDUAL combat: the redirecting blocker, or null to let it through. */
    data class Blocker(val id: ObjectId?) : Answer

    /** Chosen modes (`Question.PickMode`). */
    data class Modes(val picks: List<Int>) : Answer

    /** Chosen cards (`Question.PickCards`). */
    data class Cards(val ids: List<ObjectId>) : Answer

    /** A priority answer carrying the action ITSELF -- IN-PROCESS ONLY. A bot or
     *  a test script answers with the action it already has. Anything saved or
     *  sent stays a reference: a session records `asAnswer()`, `answerToJson`
     *  writes that reference (refusing an action with none), and nothing
     *  decodes to this case. */
    data class Act(val action: PriorityAction) : Answer

    /** A sandbox edit of the table (Sandbox.kt): accepted only in a
     *  sandbox run and only at a priority question, which is then asked
     *  again over the edited table. */
    data class Edit(val edit: TableEdit) : Answer
}

/** Rebuild the priority action this answer names, in THIS state. Null when it
 *  no longer describes anything legal (the card left the zone): a replay that
 *  hits null has diverged, and says so. */
fun Answer.toAction(rules: Rules, state: GameState, player: PlayerId): PriorityAction? = when (this) {
    Answer.Pass -> PriorityAction.PassPriority
    Answer.Concede -> PriorityAction.Concede
    is Answer.ActivateAbility -> PriorityAction.Activate(source, index)
    is Answer.PlayCard -> {
        val ref = state.cardsInCast(player, zone).firstOrNull { it.instanceId == instanceId }
        val card = ref?.let { rules.cards[it.cardId] }
        val f = card?.faces?.getOrElse(face) { card.faces[0] }
        // Same constructor the enumeration and both UI paths use -- a fourth
        // copy here would have been the very thing `playActionFor` exists to
        // stop, and it would have silently dropped the recast cost again.
        if (card == null || f == null) null
        else playActionFor(rules, card, CardSource(zone, instanceId), face, zoneDef)
    }
    is Answer.Act -> action
    // Not priority answers -- these belong to a specific question the engine
    // asked, and are read by whoever asked it.
    else -> null
}

/** The answer that names this action, for recording what a player just did.
 *  The inverse of `toAction`, and the reason a UI can keep offering rich
 *  `PriorityAction`s while the SESSION stores only references. */
fun PriorityAction.asAnswer(): Answer? = when (this) {
    PriorityAction.PassPriority -> Answer.Pass
    PriorityAction.Concede -> Answer.Concede
    is PriorityAction.Activate -> Answer.ActivateAbility(source, index)
    is PriorityAction.CastSpell -> from?.let { Answer.PlayCard(it.instanceId, it.zone) }
    is PriorityAction.PlayPermanent -> from?.let { Answer.PlayCard(it.instanceId, it.zone, face, zone) }
    else -> null
}

// -- JSON ------------------------------------------------------------------
// Beside the type, with exhaustive `when`s, so a new case cannot ship without
// its encoding.

private fun ctJson(t: CombatTarget): String = when (t) {
    is CombatTarget.Player -> """{"k":"player","id":${jstr(t.id)}}"""
    is CombatTarget.Obj -> """{"k":"obj","id":${t.id}}"""
}

internal fun ctOf(j: Json): CombatTarget = j.obj().let { o ->
    if (o.req("k").str() == "player") CombatTarget.Player(o.req("id").str())
    else CombatTarget.Obj(o.req("id").int())
}

private fun czJson(z: CastZone): String = when (z) {
    is CastZone.Std -> """{"k":"std","zone":${jstr(enumStr(z.zone))}}"""
    is CastZone.Declared -> """{"k":"declared","id":${jstr(z.id)}}"""
}

internal fun czOf(j: Json): CastZone = j.obj().let { o ->
    if (o.req("k").str() == "declared") CastZone.Declared(o.req("id").str())
    else CastZone.Std(enumOf<HiddenZone>(o.req("zone").str()))
}

fun answerToJson(a: Answer): String = when (a) {
    Answer.Pass -> """{"a":"pass"}"""
    Answer.Concede -> """{"a":"concede"}"""
    is Answer.PlayCard ->
        """{"a":"play","id":${a.instanceId},"zone":${czJson(a.zone)},"face":${a.face}""" +
            (a.zoneDef?.let { ""","def":${jstr(it)}""" } ?: "") + "}"
    is Answer.ActivateAbility -> """{"a":"activate","src":${a.source},"i":${a.index}}"""
    is Answer.Target -> """{"a":"target","id":${a.id}}"""
    is Answer.Number -> """{"a":"number","n":${a.n}}"""
    is Answer.Attackers ->
        """{"a":"attackers","m":[${a.assignment.entries.joinToString(",") { """{"k":${it.key},"v":${ctJson(it.value)}}""" }}]}"""
    is Answer.Blockers ->
        """{"a":"blockers","m":[${a.assignment.entries.joinToString(",") { """{"k":${it.key},"v":${it.value}}""" }}]}"""
    is Answer.CombatTgt -> """{"a":"combatTgt"${a.target?.let { ""","t":${ctJson(it)}""" } ?: ""}}"""
    is Answer.Blocker -> """{"a":"blocker"${a.id?.let { ""","id":$it""" } ?: ""}}"""
    is Answer.Modes -> """{"a":"modes","p":[${a.picks.joinToString(",")}]}"""
    is Answer.Cards -> """{"a":"cards","ids":[${a.ids.joinToString(",")}]}"""
    is Answer.Act -> answerToJson(a.action.asAnswer() ?: error("an in-process action has no reference to save: ${a.action}"))
    is Answer.Edit -> """{"a":"edit","e":${tableEditToJson(a.edit)}}"""
}

internal fun answerOf(j: Json): Answer = j.obj().let { o ->
    when (val k = o.req("a").str()) {
        "edit" -> Answer.Edit(tableEditOf(o.req("e")))
        "pass" -> Answer.Pass
        "concede" -> Answer.Concede
        "play" -> Answer.PlayCard(
            o.req("id").int(), czOf(o.req("zone")), o.intOr("face", 0), o["def"]?.str(),
        )
        "activate" -> Answer.ActivateAbility(o.req("src").int(), o.req("i").int())
        "target" -> Answer.Target(o.req("id").int())
        "number" -> Answer.Number(o.req("n").int())
        "attackers" -> Answer.Attackers(
            o.req("m").arr().associate { e -> e.obj().let { it.req("k").int() to ctOf(it.req("v")) } },
        )
        "blockers" -> Answer.Blockers(
            o.req("m").arr().associate { e -> e.obj().let { it.req("k").int() to it.req("v").int() } },
        )
        "combatTgt" -> Answer.CombatTgt(o["t"]?.let { ctOf(it) })
        "blocker" -> Answer.Blocker(o["id"]?.int())
        "modes" -> Answer.Modes(o.req("p").arr().map { it.int() })
        "cards" -> Answer.Cards(o.req("ids").arr().map { it.int() })
        else -> error("unknown answer kind: $k")
    }
}

/** Parse one answer from text -- the public door, matching `intExprFromJson`.
 *  `Json` itself is internal, so the wire form is a string at the boundary. */
fun answerFromJson(text: String): Answer = answerOf(Json.parse(text))

/** A whole answer LIST, which is what a session actually stores and what a
 *  lockstep peer actually sends. */
fun answersToJson(list: List<Answer>): String = "[${list.joinToString(",") { answerToJson(it) }}]"

fun answersFromJson(text: String): List<Answer> = Json.parse(text).arr().map { answerOf(it) }

/** The raw value an `Answer` carries, for callers that want the plain value.
 *  Needs no context -- an `Answer` says which shape it is; ENCODING needs the
 *  question, which is why that half lives with the questions. */
fun Answer.raw(): Any? = when (this) {
    Answer.Pass -> PriorityAction.PassPriority
    Answer.Concede -> PriorityAction.Concede
    is Answer.Target -> id
    is Answer.Number -> n
    is Answer.Attackers -> assignment
    is Answer.Blockers -> assignment
    is Answer.CombatTgt -> target
    is Answer.Blocker -> id
    is Answer.Modes -> picks
    is Answer.Cards -> ids
    is Answer.Act -> action
    is Answer.Edit -> edit
    // A play or an ability needs the STATE to become an action again, so these
    // are rebuilt by `toAction` rather than decoded blind.
    is Answer.PlayCard, is Answer.ActivateAbility -> null
}

/** How many cases `Answer` has, so a test can assert it exercised all of them.
 *  Hand-maintained (`sealedSubclasses` needs kotlin-reflect); safe
 *  because it sits beside the declaration and its consumer fails on drift. */
fun answerCaseCount(): Int = 14
