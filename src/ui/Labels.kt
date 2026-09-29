package ccgui

import ccg.CardFilter
import ccg.CardRef
import ccg.Rules
import ccg.AttackRange
import ccg.compellingGuards
import ccg.laneReaches
import ccg.laneReachesFace
import ccg.BinOp
import ccg.BoolExpr
import ccg.CmpOp
import ccg.Cost
import ccg.GameState
import ccg.IntExpr
import ccg.ObjectId
import ccg.PermFilter
import ccg.PlayerId
import ccg.PlayerRef

// ---------------------------------------------------------------------------
// Rendering an engine value as the short rules text the Creator shows on a
// pill. Pure string functions (see Catalogue.kt). `UiTest.kt` pins that every
// entry a menu can PRODUCE renders as something other than the "⟨expr⟩"
// fallback.
// ---------------------------------------------------------------------------

/** How a game parameter reads in rules text. */
private val PARAM_WORDS = mapOf(
    "startingHandSize" to "the starting hand size",
    "cardsDrawnPerTurn" to "the cards drawn per turn",
    "firstPlayerSkipsFirstDraw" to "whether the first player skips the first draw",
    "maxHandSize" to "the hand limit",
    "playerCount" to "the number of players",
)

/** One `IntExpr` as a pill label. EXHAUSTIVE -- no `else`, so a new node cannot
 *  render as "⟨expr⟩". */
fun intSummary(e: IntExpr): String = when (e) {
    is IntExpr.Lit -> e.value.toString()
    IntExpr.X -> "X"
    IntExpr.Share -> "its share"
    IntExpr.EventAmount -> "that much"
    is IntExpr.Param -> PARAM_WORDS[e.name] ?: e.name
    IntExpr.TurnNumber -> "the turn number"
    is IntExpr.SeatOf -> shortPossessive(e.who) + " seat"
    // Names what the filter counts, never a generic "creatures".
    is IntExpr.CountPerms -> "# ${countedNoun(e.filter)}"
    is IntExpr.CountCounters -> "# ${e.kind?.let { "$it " } ?: ""}counters on ${countedNoun(e.filter)}"
    is IntExpr.HandSize -> shortPossessive(e.who) + " hand size"
    is IntExpr.LifeOf -> shortPossessive(e.who) + " life"
    // SELF is "this card": what a lowered `SelfField` looks like, and
    // what the in-play text of a compiled ability is built from.
    is IntExpr.TargetField -> "${possessive(e.target)} ${e.key}"
    is IntExpr.TargetCounter -> "${possessive(e.target)} ${e.kind} counters"
    is IntExpr.TargetDamage -> if (e.target.name == ccg.SELF) "damage on this card" else "damage on it"
    is IntExpr.SelfField -> "this card's ${e.key}"
    is IntExpr.SelfCounter -> "this card's ${e.kind} counters"
    IntExpr.SelfDamage -> "damage on this card"
    is IntExpr.PlayerCounter -> shortPossessive(e.who) + " " + e.name
    is IntExpr.LaneOf -> "${possessive(e.target)} lane"
    is IntExpr.Cond -> "if ${boolSummary(e.cond)} then ${intSummary(e.then)} else ${intSummary(e.otherwise)}"
    is IntExpr.ZoneSize -> "# cards in " + shortPossessive(e.who) + " " + ccg.zoneName(e.zone).removePrefix("the ")
    is IntExpr.Bin -> if (e.op == BinOp.MIN || e.op == BinOp.MAX) {
        "${e.op.name.lowercase()}(${intSummary(e.a)}, ${intSummary(e.b)})"
    } else {
        val sym = when (e.op) {
            BinOp.ADD -> "+"; BinOp.SUB -> "−"; BinOp.MUL -> "×"; BinOp.DIV -> "÷"
            BinOp.MIN, BinOp.MAX -> error("handled above")
        }
        // Parenthesise a sum inside a product, and a sum on the right of a
        // difference -- the only two places reading left to right misleads.
        fun side(x: IntExpr, right: Boolean): String {
            val inner = intSummary(x)
            val loose = x is IntExpr.Bin && x.op != BinOp.MUL
            val tight = e.op == BinOp.MUL || e.op == BinOp.DIV
            return if (loose && (tight || (right && e.op == BinOp.SUB))) "($inner)" else inner
        }
        "${side(e.a, false)} $sym ${side(e.b, true)}"
    }
}

/** When an `EventPattern` fires, in words -- "when a Ship you control is
 *  dealt damage". Exhaustive, so a new pattern cannot reach the screen as its
 *  class name. */
fun eventSummary(p: ccg.EventPattern): String {
    fun whose(w: PlayerRef?, owner: String = "") = when (w) {
        null -> ""
        PlayerRef.You -> " you control".takeIf { owner.isEmpty() } ?: " $owner"
        PlayerRef.Opponent -> " an opponent controls".takeIf { owner.isEmpty() } ?: " an opponent's"
        else -> " ${playerNoun(w)} controls".takeIf { owner.isEmpty() } ?: " ${playerPossessive(w)}"
    }
    fun kinds(t: Set<String>, any: String) = if (t.isEmpty()) any else "a " + t.sorted().joinToString("/")
    return when (p) {
        is ccg.EventPattern.OnPhase -> "at the ${p.phase} phase" + when (p.whose) {
            null -> ""; PlayerRef.You -> " of your turn"; PlayerRef.Opponent -> " of an opponent's turn"
            else -> " of ${playerPossessive(p.whose!!)} turn"
        }
        ccg.EventPattern.AnyTurnBegan -> "when a turn begins"
        is ccg.EventPattern.OnCombatStep -> "at the start of " + (p.step?.let { "the $it step" } ?: "each combat step") +
            when (p.whose) {
                null -> ""; PlayerRef.You -> " of your combat"; PlayerRef.Opponent -> " of an opponent's combat"
                else -> " of ${playerPossessive(p.whose!!)} combat"
            }
        ccg.EventPattern.Never -> "never"
        ccg.EventPattern.SelfEnters -> "when this enters"
        ccg.EventPattern.SelfLeaves -> "when this leaves play"
        ccg.EventPattern.SelfAttacks -> "when this attacks"
        is ccg.EventPattern.Enters ->
            "whenever ${if (p.other) "another " else ""}${kinds(p.types, "permanent").removePrefix("a ")}${whose(p.whose)} enters"
        is ccg.EventPattern.Cast -> "whenever " + when (p.whose) {
            null -> "anyone casts"; PlayerRef.You -> "you cast"; PlayerRef.Opponent -> "an opponent casts"
            else -> "${playerNoun(p.whose!!)} casts"
        } + " " + kinds(p.types, "a spell")
        is ccg.EventPattern.CounterCrosses ->
            "when this ${if (p.downward) "drops below" else "reaches"} ${p.k} ${p.kind} counters"
        is ccg.EventPattern.SelfDealsDamage -> "whenever this deals ${if (p.combatOnly) "combat " else ""}damage"
        is ccg.EventPattern.Dies ->
            "whenever ${p.filter?.let { filterSummary(it) } ?: kinds(p.types, "a permanent")}${whose(p.whose)} dies"
        ccg.EventPattern.MovesIntoThisZone -> "whenever another permanent moves into this zone"
        is ccg.EventPattern.Damaged ->
            "whenever ${filterSummary(p.filter)} is dealt ${if (p.combatOnly) "combat " else ""}damage" +
                (p.step?.let { " in the $it step" } ?: "")
        is ccg.EventPattern.PlayerDamaged -> "whenever " + when (p.whose) {
            null -> "a player is"; PlayerRef.You -> "you are"; PlayerRef.Opponent -> "an opponent is"
            else -> "${playerNoun(p.whose!!)} is"
        } + " dealt ${if (p.combatOnly) "combat " else ""}damage"
    }
}

/** "Ships you control" -- what a count counts, as a plural noun phrase. */
private fun countedNoun(f: PermFilter): String {
    val what = if (f.types.isEmpty()) "permanents" else f.types.sorted().joinToString(" ") + "s"
    return when (f.controller) {
        PlayerRef.You -> "$what you control"
        PlayerRef.Opponent -> "$what opp controls"
        null -> "$what in play"
        else -> "$what ${playerNoun(f.controller!!)} controls"
    }
}

/** What an expression with no wording would show. `intSummary` is exhaustive
 *  now, so none does; `UiTest` still pins that every menu entry avoids it. */
const val UNKNOWN_INT = "⟨expr⟩"
const val UNKNOWN_BOOL = "⟨cond⟩"

/** A shorter form used inside a comparison ("# creatures you control ≥ 3"). */
fun intLabel(a: IntExpr): String = when (a) {
    is IntExpr.CountPerms -> {
        val t = a.filter.types.firstOrNull()?.lowercase() ?: "permanent"
        val who = when (a.filter.controller) {
            PlayerRef.You -> "you control"; PlayerRef.Opponent -> "opp controls"; null -> "in play"
            else -> "${playerNoun(a.filter.controller!!)} controls"
        }
        "# ${t}s $who"
    }
    is IntExpr.LifeOf -> when (a.who) { PlayerRef.You -> "your life"; PlayerRef.Opponent -> "opp life"; else -> "${playerPossessive(a.who)} life" }
    is IntExpr.HandSize -> when (a.who) { PlayerRef.You -> "your hand"; PlayerRef.Opponent -> "opp hand"; else -> "${playerPossessive(a.who)} hand" }
    is IntExpr.Lit -> a.value.toString()
    // Everything else in its own words. This said "value", so a Station's
    // "this card's hull ≤ 0" read "value ≤ 0" in every condition pill.
    else -> intSummary(a)
}

fun boolSummary(b: BoolExpr): String = when (b) {
    is BoolExpr.Const -> if (b.value) "always" else "never"
    is BoolExpr.HasType -> "this is ${b.types.joinToString("+") { it.lowercase() }}"
    is BoolExpr.HasKeyword -> "${targetWord(b.target)} has ${b.keyword}"
    is BoolExpr.IsType -> "${targetWord(b.target)} is ${b.types.sorted().joinToString("+")}"
    is BoolExpr.IsExhausted -> "${targetWord(b.target)} is exhausted"
    is BoolExpr.IsToken -> "${targetWord(b.target)} is a token"
    is BoolExpr.HasField -> "${targetWord(b.target)} has a ${b.key}"
    is BoolExpr.AtDepth -> "${targetWord(b.target)} is in a ${b.depth.name.lowercase()} berth"
    is BoolExpr.ZoneOwner -> "${targetWord(b.target)} stands in " + (b.who?.let { "${playerPossessive(it)} zone" } ?: "a shared zone")
    is BoolExpr.Not -> "not (${boolSummary(b.term)})"
    is BoolExpr.And -> b.terms.joinToString(" and ") { boolSummary(it) }
    is BoolExpr.Or -> b.terms.joinToString(" or ") { boolSummary(it) }
    is BoolExpr.Cmp -> {
        val op = when (b.op) {
            CmpOp.GTE -> "≥"; CmpOp.LTE -> "≤"; CmpOp.GT -> ">"; CmpOp.LT -> "<"; CmpOp.EQ -> "="; CmpOp.NE -> "≠"
        }
        "${intLabel(b.a)} $op ${intLabel(b.b)}"
    }
}

fun filterSummary(f: PermFilter): String {
    // an attach-relative filter names a specific permanent, not a
    // controller/type narrowing -- reads on its own, like `onlyId` would.
    if (f.onlyHost) return "the permanent it's attached to"
    // The type narrowing reads as itself ("a Ship you control"). Text that
    // describes behaviour must match it: dropping the type once made a card
    // claim it could target things it could not.
    val what = if (f.types.isEmpty()) "permanent" else f.types.sorted().joinToString(" ")
    val base = when (f.controller) {
        PlayerRef.You -> if (f.excludesSource) "another $what you control" else "a $what you control"
        PlayerRef.Opponent -> "a $what an opponent controls"
        null -> if (f.excludesSource) "any other $what" else "any $what"
        else -> "a $what ${playerNoun(f.controller!!)} controls"
    }
    // The counter requirement is a second, independent narrowing.
    val counter = f.hasCounter?.let {
        if (f.minCount <= 1) " with a $it counter" else " with $it ${f.minCount} counters"
    } ?: ""
    return base + counter + (f.where?.let { " where ${boolSummary(it)}" } ?: "")
}

/** "it" for a bound target; "this" for the card itself. */
private fun targetWord(t: ccg.BoundTarget): String = when (t.name) {
    ccg.SELF -> "this"; ccg.ATTACKER -> "the attacker"; ccg.TARGET -> "the target"; else -> "it"
}
private fun possessive(t: ccg.BoundTarget): String = when (t.name) {
    ccg.SELF -> "this card's"; ccg.ATTACKER -> "the attacker's"; ccg.TARGET -> "the target's"; else -> "its"
}

/** "your" / "opp" / "its controller's" -- the terse form expressions use. */
private fun shortPossessive(r: PlayerRef): String = when (r) {
    PlayerRef.You -> "your"
    PlayerRef.Opponent -> "opp"
    else -> playerPossessive(r)
}

/** A player, as the subject of a sentence -- any `PlayerRef`. */
fun playerNoun(r: PlayerRef): String = when (r) {
    PlayerRef.You -> "you"
    PlayerRef.Opponent -> "the opponent"
    PlayerRef.EachOpponent -> "each opponent"
    PlayerRef.Active -> "the active player"
    PlayerRef.Chosen -> "the chosen player"
    PlayerRef.Defending -> "the defending player"
    is PlayerRef.Seat -> r.id
    is PlayerRef.ControllerOf -> "${possessive(r.target)} controller"
    is PlayerRef.OwnerOf -> "${possessive(r.target)} owner"
}

/** A player pill's label: its own words for none / you / the opponent, and
 *  `playerNoun` for any other reference -- a two-way pill must not show
 *  "the active player" as "opponent". */
fun playerWord(r: PlayerRef?, none: String, you: String, opponent: String): String = when (r) {
    null -> none
    PlayerRef.You -> you
    PlayerRef.Opponent -> opponent
    else -> playerNoun(r)
}

/** "your", "the opponent's", "its controller's". */
fun playerPossessive(r: PlayerRef): String = if (r == PlayerRef.You) "your" else playerNoun(r) + "'s"

/** A one-line summary of a cost, for a collapsed group header. Covers every
 *  field of `Cost`; `UiTest`'s "costSummary never calls a real cost free" walks
 *  each field and fails if any summarises as "free" (a hull-only cost once
 *  did). `additional` renders what it does, not "+ an effect". */
fun costSummary(c: Cost): String {
    if (c.isFree) return "free"
    val parts = buildList {
        c.mana[""]?.takeIf { it > 0 }?.let { add("{$it}") }
        c.mana.filterKeys { it.isNotEmpty() }.forEach { (k, n) -> add("{${k.repeat(n.coerceAtLeast(1))}}") }
        if (c.usesX) add("{X}")
        if (c.tapSource) add("{T}")
        if (c.sacrificeSource) add("sacrifice it")
        if (c.payLife > 0) add("${c.payLife} life")
        c.removeCounters?.let { (k, n) -> add("−$n $k") }
        // The missing one. Named with its source, because "5 hull" and "5 hull
        // off a Station you control" are different prices -- the second can be
        // unpayable with the first still true.
        c.payFrom?.let { add("${it.amount} ${it.counter} from ${filterSummary(it.filter)}") }
        c.additional?.let { add(effectSummary(it)) }
    }
    val base = parts.joinToString(", ").ifBlank { "free" }
    return if (c.alternatives.isEmpty()) base else "$base  or  " + c.alternatives.joinToString(" or ") { costSummary(it) }
}

/** "#3 Spring" / "#2 Grunt 2/2" -- how every chip names a permanent. Total:
 *  an id that has left play renders as "#id ?" rather than throwing. */
fun GameState.lbl(id: ObjectId): String {
    // A spell on the stack is a target too (counter target spell).
    stack.firstOrNull { it.id == id }?.let { so ->
        val name = when (so) {
            is ccg.SpellOnStack -> so.label.ifEmpty { "a spell" }
            is ccg.PermanentOnStack -> so.label.ifEmpty { so.card.faces.getOrElse(so.face) { so.card.faces[0] }.name }
            else -> "an ability"
        }
        return "#$id $name (on the stack)"
    }
    val c = characteristicsOf(id)
    if (c.name.isEmpty()) return "#$id ?"
    val pt = if ("power" in c.fields) " ${c.fields["power"]}/${c.fields["toughness"]}" else ""
    return "#$id ${c.name}$pt"
}

/** The opponent's combatants an attack may be aimed or redirected at -- the
 *  board's tappable set. The opponent's FACE is a separate target, not in this
 *  set. Reads `Rules.fightsInCombat`, so a non-combat permanent (a Station, a
 *  Leader) is excluded; a Station is reached by attacking its player. */
fun combatBoardTargets(
    rules: Rules,
    state: GameState,
    attackerController: PlayerId,
    /** WHICH attacker is asking: narrows to what it can reach, via the
     *  `laneReaches` the engine enforces. */
    attacker: ObjectId?,
    /** WHICH of that attacker's attacks is asking (a close gun and a battery
     *  reach different sets). Neither this nor `attacker` is defaulted: naming
     *  an attacker means saying which gun. The whole-board question has its own
     *  overload below. */
    range: AttackRange?,
): Set<ObjectId> {
    val opp = state.opponentOf(attackerController)
    val reachable = state.battlefield.values
        .filter { it.controller == opp && rules.fightsInCombat(state.characteristicsOf(it.id).types) }
        .filter { attacker == null || state.laneReaches(rules, attacker, it.id, range) }
        .map { it.id }
        .toSet()
    // Compulsion narrows it further: with a guard in reach, nothing else is a
    // legal target. Asked here for the same reason `laneReaches` is -- an
    // option the engine will refuse is a wasted attack, and wasted attacks
    // contaminate every number taken under the config that produces them.
    if (attacker == null) return reachable
    val guards = state.compellingGuards(rules, attacker, opp)
    return if (guards.isEmpty()) reachable else reachable.intersect(guards.toSet())
}

/** The whole board: every enemy permanent that fights, with no attacker in
 *  mind. What a UI wants when it is DESCRIBING the board rather than aiming
 *  something at it. */
fun combatBoardTargets(rules: Rules, state: GameState, attackerController: PlayerId): Set<ObjectId> =
    combatBoardTargets(rules, state, attackerController, attacker = null, range = null)

/** May `attacker` aim past the board at the defending PLAYER (and so at the
 *  Station standing in for them)? Both refusals the engine makes, in one place
 *  a pilot can ask: the lane must be unopposed, and no guard may be waiting. */
fun GameState.canAttackFace(
    rules: Rules,
    attacker: ObjectId,
    defender: PlayerId,
    range: AttackRange?,
): Boolean =
    // Position through `laneReachesFace`, the engine's OWN definition, rather
    // than a second copy of the rule here -- that copy is what would have gone
    // stale the moment rule E (long range shoots over the line) was added.
    laneReachesFace(rules, attacker, defender, range) &&
        compellingGuards(rules, attacker, defender).isEmpty()

// -- hidden-zone cards ------------------------------------------------------

/** Name a card sitting in a hidden zone. Nothing on the battlefield backs it,
 *  so `GameState.lbl` cannot help -- only the bundle's own definitions can. */
fun cardLabel(rules: Rules, ref: CardRef): String {
    val name = rules.cards[ref.cardId]?.faces?.firstOrNull()?.name ?: ref.cardId
    return "#${ref.instanceId} $name"
}

/** How a `CardFilter` reads in the editor and in a prompt. */
fun cardFilterSummary(f: CardFilter): String {
    val parts = mutableListOf<String>()
    f.nameIs?.let { parts += "named \"$it\"" }
    f.nameContains?.let { parts += "whose name has \"$it\"" }
    if (f.types.isNotEmpty()) parts += f.types.sorted().joinToString(" ") + " card"
    f.maxManaValue?.let { parts += "costing $it or less" }
    f.minManaValue?.let { parts += "costing $it or more" }
    return if (parts.isEmpty()) "any card" else parts.joinToString(", ")
}

// -- attacking "the player" -------------------------------------------------

/** What attacking the PLAYER is CALLED at this table: the permanent standing
 *  for them (a Station) where one exists, else the plain player label. */
fun playerTargetLabel(rules: Rules, state: GameState, defender: PlayerId): String =
    playerTargetAnchor(rules, state, defender)
        ?.let { state.characteristicsOf(it).name } ?: "$defender (player)"

/** The permanent that STANDS FOR a player as a combat target (`loseOnDeath`; a
 *  Station). One definition for the label and the tap: `boardTapTargets`
 *  includes it and `tapAnswer` maps it back to `CombatTarget.Player`, so a
 *  Station is targeted exactly like a Ship, arcs included. Null when the game
 *  has none; then the prompt bar's chip is the only way to say it. */
fun playerTargetAnchor(rules: Rules, state: GameState, defender: PlayerId): ObjectId? =
    state.battlefield.values.firstOrNull { p ->
        p.controller == defender &&
            state.characteristicsOf(p.id).types.any { rules.typeOf(it).loseOnDeath }
    }?.id
