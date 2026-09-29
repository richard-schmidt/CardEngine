package ccgui

import ccg.CardDoc
import ccg.CharOp
import ccg.ModeOption
import ccg.Effect
import ccg.HiddenZone
import ccg.IntExpr
import ccg.zoneName
import ccg.ReplacementDoc
import ccg.RuleMod
import ccg.Rules
import ccg.StaticSpec
import ccg.TriggerDoc
import ccg.pattern

// ---------------------------------------------------------------------------
// The COMPILED rulebox -- what a card actually does, as opposed to what its
// free-form `text` claims. Rendered from the doc and shown beside the authored
// text; where they disagree, one is a bug.
//
// `effectSummary` and `charOpSummary` are EXHAUSTIVE (no `else`): a verb this
// file cannot describe fails to compile rather than printing its class name.
// ---------------------------------------------------------------------------

fun effectSummary(e: Effect): String = when (e) {
    is Effect.DealDamage -> "deal ${amountOf(e.amount, "damage")}" + (e.player?.let { " to ${playerNoun(it)}" } ?: "")
    is Effect.DamageOpponent -> "deal ${amountOf(e.amount, "damage")} to the opponent"
    is Effect.Draw -> "${does(e.who, "draw")} ${intSummary(e.count)}"
    is Effect.GainLife -> "${does(e.who, "gain")} ${amountOf(e.amount, e.counter)}"
    // The amount is an `IntExpr`; this printed it with `toString`, so a
    // Station's production read "add Bin(op=ADD, a=Lit(value=1), ...) generic".
    is Effect.AddMana ->
        "add " + e.mana.entries.joinToString(", ") { (k, n) -> amountOf(n, if (k.isEmpty()) "mana" else "$k mana") }
    is Effect.Destroy -> "destroy it"
    is Effect.AddCounter -> "put ${amountOf(e.count, "${e.kind} counter(s)")} on it"
    is Effect.RemoveCounter ->
        if (e.count == IntExpr.SelfCounter(e.kind) && e.target.name == ccg.SELF) "remove all ${e.kind} counters from this card"
        else "remove ${amountOf(e.count, "${e.kind} counter(s)")}"
    is Effect.CreateToken -> "create ${intSummary(e.count)} ${e.chars.name} token(s)"
    is Effect.Sacrifice -> "${does(e.who, "sacrifice")} ${intSummary(e.count)} ${filterSummary(e.filter)}"
    // A discard to exile is how a Station stows a card, and "discards" named
    // the graveyard, which is exactly where the card does NOT go.
    is Effect.Discard -> when (e.toZone) {
        HiddenZone.GRAVEYARD -> "${does(e.who, "discard")} ${intSummary(e.count)}"
        HiddenZone.EXILE -> "${does(e.who, "exile")} ${intSummary(e.count)} from hand"
        else -> "${does(e.who, "put")} ${intSummary(e.count)} from hand into ${zoneName(e.toZone)}"
    }
    is Effect.DrawThenDiscard ->
        "${does(e.who, "draw")} ${intSummary(e.draw)}, then ${verb(e.who, "put")} ${intSummary(e.discard)} into ${zoneName(e.toZone)}"
    is Effect.ApplyModifier -> "${filterSummary(e.filter)} gets ${e.ops.joinToString(", ") { charOpSummary(it) }}"
    is Effect.PreventDamage ->
        "prevent " + (if (e.all) "all damage" else "${intSummary(e.amount)} damage") +
            (e.onlyStep?.let { " in the $it step" } ?: "")
    is Effect.Attach -> "attach this to it"
    is Effect.CounterSpell -> "counter " + (if (e.types.isEmpty()) "a spell" else "a ${e.types.sorted().joinToString("/")} spell") +
        when (e.whose) { null -> ""; ccg.PlayerRef.You -> " you control"; ccg.PlayerRef.Opponent -> " an opponent controls"; else -> " ${playerNoun(e.whose!!)} controls" }
    is Effect.Tap -> if (e.untap) "ready it" else "exhaust it"
    is Effect.ClearDamage -> "remove all damage from it"
    is Effect.AsPlayer -> (if (e.who == ccg.PlayerRef.Chosen) "a chosen player" else playerNoun(e.who)) +
        " (as \"you\"): " + effectSummary(e.body)
    is Effect.SendTo -> when (e.to) {
        HiddenZone.HAND -> "return it to its owner's hand"
        HiddenZone.LIBRARY -> "put it on top of its owner's library"
        HiddenZone.LIBRARY_BOTTOM -> "put it on the bottom of its owner's library"
        HiddenZone.GRAVEYARD -> "put it into its owner's graveyard"
        HiddenZone.EXILE -> "exile it"
    }
    is Effect.GainControl -> "gain control of it" + when (val d = e.duration) {
        ccg.Duration.Permanent -> ""
        ccg.Duration.EndOfTurn -> " until end of turn"
        ccg.Duration.EndOfNextTurn -> " until your next turn ends"
        is ccg.Duration.While -> " while ${boolSummary(d.cond)}"
    }
    is Effect.Choose -> "choose ${filterSummary(e.filter)}: ${effectSummary(e.body)}"
    is Effect.ChooseMany -> "choose ${intSummary(e.count)} ${filterSummary(e.filter)}: ${effectSummary(e.body)}"
    is Effect.ForEach -> "for each ${filterSummary(e.filter)}: ${effectSummary(e.body)}"
    is Effect.ForEachPlayer -> "for each player: ${effectSummary(e.body)}"
    is Effect.Sequence -> e.steps.joinToString("; ") { effectSummary(it) }
    is Effect.If -> "if ${boolSummary(e.cond)}, ${effectSummary(e.then)}" +
        (if (e.otherwise != Effect.NoOp) " otherwise ${effectSummary(e.otherwise)}" else "")
    Effect.NoOp -> "nothing"
    // Exhaustive on purpose: a new verb must be described here to compile.
    is Effect.ChooseMode ->
        "choose ${intSummary(e.pick)}: " + e.options.joinToString(" · ") { effectSummary(it) }
    is Effect.Transform -> "transform it"
    is Effect.CopyOf -> "create a copy of it"
    is Effect.CreateEmblem -> "create an emblem"
    is Effect.ReturnFromDiscard ->
        "${does(e.who, "return")} ${intSummary(e.count)} from the discard pile" +
            (if (e.toBattlefield) " to the battlefield" else " to hand")
    is Effect.SearchZone ->
        "${does(e.who, "search")} ${zoneName(e.from)} for ${intSummary(e.count)} " +
            "${cardFilterSummary(e.filter)} and puts it " + (if (e.intoPlay) "onto the battlefield" else "in ${zoneName(e.to)}")
    is Effect.Shuffle -> "${does(e.who, "shuffle")} ${zoneName(e.zone)}"
    is Effect.MoveTop ->
        "${does(e.who, "move")} ${intSummary(e.count)} from the top of the library to ${zoneName(e.to)}"
    is Effect.LookAtTop ->
        "${does(e.who, "look")} at ${intSummary(e.count)} from the top of the library " +
            "and may move any of them to ${zoneName(e.to)}"
    is Effect.MovePermanent -> "move it to ${e.toZone.def}"
    is Effect.Proceed -> e.amount?.let { "let it happen, as ${intSummary(it)}" } ?: "let it happen after all"
    is Effect.SetCombatMode ->
        e.mode?.let { "it fights as \"$it\"" } ?: "it stops fighting in any special mode"
    is Effect.Delayed ->
        "later, ${eventSummary(e.on)}: ${effectSummary(e.effect)}" +
            (if (e.once) "" else " (each time)")
    is Effect.DeclareAttackers -> "declare attackers among ${filterSummary(e.eligible)}, then ${effectSummary(e.then)}"
    is Effect.DeclareBlockers -> "declare blockers among ${filterSummary(e.eligible)}, then ${effectSummary(e.then)}"
    Effect.CombatWindow -> "players may respond"
    is Effect.CombatDamage -> "${e.step} damage: each fighter deals ${intSummary(e.amount)}"
    is Effect.Attack -> "attack, if the attack is legal, then ${effectSummary(e.then)}"
    is Effect.FreeAttacks -> "${e.step}: every fighter picks a target, then they all strike" + (if (e.window) ", after a window" else "")
    is Effect.Strike -> "${e.step}: the attacker deals ${intSummary(e.amount)}" + (if (e.returnDamage) ", and takes as much back" else "")
    is Effect.Clash -> "${e.step}: compare ${intSummary(e.attackStat)} with ${intSummary(e.defendStat)}; the lower is destroyed"
}

/** "you draw" / "the opponent draws" -- the subject and its verb, agreeing.
 *  It was `whoWord(who) + " draws"`, so every "you" line read "you draws". */
private fun does(w: ccg.PlayerRef, verb: String): String = "${playerNoun(w)} " + verb(w, verb)

private fun verb(w: ccg.PlayerRef, base: String): String = when {
    w == ccg.PlayerRef.You -> base
    base.endsWith("sh") || base.endsWith("ch") -> base + "es"
    else -> base + "s"
}

/** "3 damage", or "damage equal to this card's charge counters": a number
 *  reads in front of its noun, an expression does not. */
private fun amountOf(n: IntExpr, noun: String): String =
    if (n is IntExpr.Lit || n == IntExpr.X) "${intSummary(n)} $noun" else "$noun equal to ${intSummary(n)}"

/** A characteristic-changing op, in words. EXHAUSTIVE -- a new `CharOp` case
 *  fails to compile here instead of printing its class name. */
fun charOpSummary(op: CharOp): String = when (op) {
    is CharOp.PlusPT -> "+${intSummary(op.power)}/+${intSummary(op.toughness)}"
    is CharOp.SetPT -> "base ${intSummary(op.power)}/${intSummary(op.toughness)}"
    is CharOp.GrantKeyword -> "\"${op.keyword}\""
    is CharOp.AddType -> "type ${op.type}"
    is CharOp.SetTypes -> "types ${op.types.joinToString(" ")}"
    CharOp.RemoveAbilities -> "no abilities"
    // The reported one: name the FIELD, which is the whole content of the op.
    // "+2 fast" is a card's text; "PlusField" is a class name.
    is CharOp.PlusField -> "+${intSummary(op.amount)} ${op.field}"
    is CharOp.SetField -> "base ${intSummary(op.value)} ${op.field}"
    // The ability's own name if it has one -- an author writes "exhaust, pay 2
    // hull: draw a card" and that is exactly what should appear.
    is CharOp.GrantAbility ->
        "gains \"${op.ability.name.ifBlank { "an activated ability" }}\""
    // A band is a table, so it reads as one: the counter it keys on and what
    // the two fields become at each step.
    is CharOp.Bands ->
        "${op.fieldA}/${op.fieldB} by ${op.counter}: " +
            op.steps.joinToString(", ") { "${it.at}+ \u2192 ${intSummary(it.power)}/${intSummary(it.toughness)}" }
}

fun ruleModSummary(r: RuleMod): String = when (r) {
    is RuleMod.Cant -> "${playerWord(r.who, "players", "you", "the opponent")} can't ${r.action.key}"
    is RuleMod.CantAttack -> "${filterSummary(r.filter)} can't attack"
    is RuleMod.CantBlock -> "${filterSummary(r.filter)} can't block"
    is RuleMod.CantActivate -> "${filterSummary(r.filter)} can't activate abilities"
    is RuleMod.ReduceDamage ->
        "${filterSummary(r.filter)} takes ${intSummary(r.amount)} less damage" +
            (r.condition?.let { " while ${boolSummary(it)}" } ?: "")
}

fun replacementSummary(r: ReplacementDoc): String = when (r) {
    is ReplacementDoc.DamageToSacrificeSelf ->
        "if ${filterSummary(r.filter)} would be dealt damage" +
            (r.onlyStep?.let { " in the $it step" } ?: "") + ", prevent it and destroy this instead"
    is ReplacementDoc.PreventDamageTo ->
        "prevent damage to ${filterSummary(r.filter)}" + (r.onlyStep?.let { " in the $it step" } ?: "")
    is ReplacementDoc.DamageToRemoveCounter ->
        "if ${filterSummary(r.filter)} would be dealt damage" +
            (r.onlyStep?.let { " in the $it step" } ?: "") +
            ", prevent it and remove a ${r.counter} counter instead"
    is ReplacementDoc.DeathToExile -> "if ${filterSummary(r.filter)} would die, exile it instead"
    is ReplacementDoc.Replace -> "if ${eventSummary(r.pattern).removePrefix("whenever ")} would happen, instead: ${effectSummary(r.instead)}"
}

private fun staticSummary(s: StaticSpec): String =
    "${filterSummary(s.filter)} gets ${s.ops.joinToString(", ") { charOpSummary(it) }}" +
        (s.condition?.let { " while ${boolSummary(it)}" } ?: "")

private fun triggerSummary(t: TriggerDoc): String {
    val when_ = when (t) {
        is TriggerDoc.SelfEnters -> "when this enters"
        is TriggerDoc.SelfLeaves -> "when this leaves play"
        is TriggerDoc.SelfAttacks -> "when this attacks"
        is TriggerDoc.OnYourPhase -> "at your ${t.phase}"
        is TriggerDoc.CreatureDies -> "when a ${t.types.joinToString(" ")} dies"
        else -> eventSummary(t.pattern)
    }
    return "$when_ — ${effectSummary(t.effect)}"
}

/** The compiled rulebox, one line per behaviour, in the order the card reads.
 *  Empty means the card genuinely does nothing -- which is itself worth seeing
 *  on screen: a card that LOOKS authored and compiles to nothing is a bug. */
fun ruleboxOf(doc: CardDoc, rules: Rules): List<String> = buildList {
    doc.requires?.let { add("requires: ${filterSummary(it)}") }
    if (doc.entersWith.isNotEmpty()) {
        add("enters with " + doc.entersWith.joinToString(", ") { "${intSummary(it.initial)} ${it.kind}" })
    }
    for (f in doc.faces) {
        f.castEffect?.let { add(effectSummary(it)) }
        f.triggers.forEach { add(triggerSummary(it)) }
        f.statics.forEach { add(staticSummary(it)) }
        f.ruleMods.forEach { add(ruleModSummary(it)) }
        f.replacements.forEach { add(replacementSummary(it)) }
        f.activated.forEach {
            add("${costSummary(it.cost)}: ${effectSummary(it.effect)}" + if (it.oncePerTurn) " (once per turn)" else "")
        }
    }
    doc.recast?.let { add("castable from ${it.from.name.lowercase()}") }
}

/** A modal option, in words. A mode question carries data rather than
 *  prose: both renderings already exist here, and which words to use is a UI
 *  decision. */
fun modeOptionSummary(o: ModeOption): String = when (o) {
    is ModeOption.OfEffect -> effectSummary(o.effect)
    is ModeOption.OfCost -> costSummary(o.cost)
    is ModeOption.OfPlayer -> o.player
}
