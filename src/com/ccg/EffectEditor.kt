package com.ccg

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ccg.BoolExpr
import ccg.BoundTarget
import ccg.CHOSEN
import ccg.Characteristics
import ccg.CharOp
import ccg.CmpOp
import ccg.Duration
import ccg.EACH
import ccg.TRIGGER
import ccg.Effect
import ccg.EventPattern
import ccg.IntExpr
import ccg.PermFilter
import ccg.Gun
import ccg.SELF
import ccg.ShieldMode
import ccg.Statics
import ccg.PlayerRef
import ccg.ZoneRef
import ccg.ZoneScoping
import ccg.creatures
import ccg.lit
import ccgui.COUNTER_KINDS
import ccgui.PHASES
import ccgui.allVerbs
import ccgui.playerWord
import ccgui.boolSummary
import ccgui.filterSummary
import ccgui.intKinds
import ccgui.WHERE_KINDS
import ccgui.cmpOpLabel
import ccgui.cmpOpOf
import ccgui.CMP_OPS
import ccgui.ROLES
import ccgui.roleOf
import ccgui.roleLabel
import ccgui.condAbout
import ccgui.condSubject
import ccgui.condOfKind
import ccgui.COND_KINDS
import ccgui.whereKind
import ccgui.whereOfKind
import ccgui.PLAYER_REFS
import ccgui.WHO_REFS
import ccgui.whoRefOf
import ccgui.playerRefKind
import ccgui.playerRefOfKind
import ccgui.BIN_OPS
import ccgui.binOpLabel
import ccgui.binOpOf
import ccgui.EVENT_KINDS
import ccgui.eventKind
import ccgui.eventOfKind
import ccgui.intSummary
import ccg.ofType
import ccg.permanents

// ---------------------------------------------------------------------------
// The sentence-template Effect editor: every Effect case renders as its rules
// sentence with the editable parts as tappable pills.
// ---------------------------------------------------------------------------

/** `implicitTarget` -- the id a bare targeted verb / "the … target" wording
 *  refers to when NOT inside a `Choose`: `CHOSEN` for a cast effect, `SELF`
 *  for a trigger's effect ("put a counter on THIS"). A `Choose` node overrides
 *  it to `CHOSEN` for its body. */
@Composable
fun EffectSection(effect: Effect?, addLabel: String = "＋ add effect", implicitTarget: String = CHOSEN, onChange: (Effect?) -> Unit) {
    if (effect == null) {
        AddEffectRow(addLabel, implicitTarget) { onChange(it) }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        EffectNode(effect, 0, implicitTarget, onChange = { onChange(it) }, onRemove = { onChange(null) })
    }
}

@Composable
fun CastEffectEditor(effect: Effect?, onChange: (Effect?) -> Unit) =
    EffectSection(effect, "＋ add cast effect", CHOSEN, onChange)

@Composable
private fun AddEffectRow(label: String, implicitTarget: String, onPick: (Effect) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        CgAddButton(label) { open = true }
        VerbMenu(open, includeTargeted = true, implicitTarget = implicitTarget, onDismiss = { open = false }) { onPick(it); open = false }
    }
}

@Composable
private fun VerbMenu(open: Boolean, includeTargeted: Boolean, implicitTarget: String = CHOSEN, onDismiss: () -> Unit, onPick: (Effect) -> Unit) {
    val stances = LocalVocab.current.stances
    DropdownMenu(open, onDismissRequest = onDismiss, modifier = Modifier.background(Cg.surface)) {
        allVerbs(implicitTarget, includeTargeted, stances).forEach { v ->
            DropdownMenuItem(
                text = { Text(v.label, color = Cg.ink, fontSize = 12.sp, fontFamily = Cg.mono) },
                onClick = { onPick(v.make()) },
            )
        }
    }
}

/** What a tutor may fetch. Four independent narrowings, each optional and each
 *  off by default -- an empty filter reads "any card", which is what the old
 *  `cardId: String?` could only express as "no name given". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CardFilterPills(f: ccg.CardFilter, onChange: (ccg.CardFilter) -> Unit) {
    word(ccgui.cardFilterSummary(f) + " —")
    // types: the same type vocabulary the rest of the editor offers.
    CgMiniField(f.types.joinToString(" "), Modifier.width(96.dp), mono = false) { txt ->
        onChange(f.copy(types = txt.split(" ").map { it.trim() }.filter { it.isNotEmpty() }.toSet()))
    }
    word("named")
    CgMiniField(f.nameIs ?: "", Modifier.width(80.dp)) { onChange(f.copy(nameIs = it.ifBlank { null })) }
    word("~")
    CgMiniField(f.nameContains ?: "", Modifier.width(70.dp)) { onChange(f.copy(nameContains = it.ifBlank { null })) }
    word("cost ≤")
    NullableStepPill(f.maxManaValue) { onChange(f.copy(maxManaValue = it)) }
    word("≥")
    NullableStepPill(f.minManaValue) { onChange(f.copy(minManaValue = it)) }
}

/** A stepper whose lowest position is "no bound at all", not zero. */
@Composable
internal fun NullableStepPill(v: Int?, onChange: (Int?) -> Unit) {
    MenuPill(v?.toString() ?: "—", listOf("—") + (0..9).map { it.toString() }) {
        onChange(if (it == "—") null else it.toInt())
    }
}

/** Which hidden zone a zone verb reads or writes. */
@Composable
private fun ZonePill(z: ccg.HiddenZone, onPick: (ccg.HiddenZone) -> Unit) {
    MenuPill(ccg.zoneName(z), ccgui.HIDDEN_ZONES) { onPick(ccgui.zoneOfName(it)) }
}

/** Nesting indent, CAPPED -- past ~3 levels the left pad stopped leaving the
 *  content a usable width and every pill wrapped to its own line. A tinted
 *  border on nested rows keeps the "this is inside something" cue. */
private fun nodeIndent(depth: Int) = (minOf(depth, 3) * 10).dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NodeRow(depth: Int, onRemove: (() -> Unit)?, swap: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = nodeIndent(depth))
            .clip(RoundedCornerShape(7.dp))
            .background(Cg.surfaceAlt)
            .border(1.dp, if (depth > 0) Cg.borderMuted else Cg.border, RoundedCornerShape(7.dp))
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (swap != null) swap()
        // FlowRow so a long sentence (filter + duration + several pills) wraps
        // onto the next line instead of squashing when the node is nested.
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            content()
        }
        if (onRemove != null) CgDeleteX(onRemove)
    }
}

/** The "⇄" pill on every effect node -- opens the verb menu and REPLACES the
 *  node with the picked verb. This is the in-place "change what this effect
 *  does" affordance (before, you had to delete then re-add). */
@Composable
private fun SwapPill(implicitTarget: String, onSwap: (Effect) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Pill("⇄") { open = true }
        VerbMenu(open, includeTargeted = true, implicitTarget = implicitTarget, onDismiss = { open = false }) {
            onSwap(it); open = false
        }
    }
}

@Composable
internal fun word(t: String) = Text(t, color = Cg.ink2, fontSize = 12.sp, fontFamily = Cg.sans)

/** you / opponent / each opponent, for a verb's own `PlayerRef` slot. */
@Composable
internal fun WhoPill(who: PlayerRef, onChange: (PlayerRef) -> Unit) {
    MenuPill(playerWord(who, "", "you", "opponent"), WHO_REFS) { onChange(whoRefOf(it)) }
}

/** Duration slot: permanent / end of turn / your next turn / while <cond>. */
@Composable
internal fun DurationPill(d: Duration, onChange: (Duration) -> Unit) {
    val labels = listOf("permanent", "until end of turn", "until your next turn", "while <cond>")
    val cur = when (d) {
        Duration.Permanent -> labels[0]
        Duration.EndOfTurn -> labels[1]
        Duration.EndOfNextTurn -> labels[2]
        is Duration.While -> labels[3]
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        MenuPill(cur, labels) { s ->
            onChange(
                when (s) {
                    labels[0] -> Duration.Permanent
                    labels[1] -> Duration.EndOfTurn
                    labels[2] -> Duration.EndOfNextTurn
                    else -> Duration.While(BoolExpr.Const(true))
                },
            )
        }
        if (d is Duration.While) BoolPill(d.cond) { onChange(Duration.While(it)) }
    }
}

/** `EventPattern` slot for `Effect.Delayed`: which event, as one pill. Its
 *  parameters are edited by `EventPatternEditor` below it. */
@Composable
internal fun EventPatternPill(p: EventPattern, onChange: (EventPattern) -> Unit) {
    // Read in composable context: `LocalVocab.current` inside a click handler
    // is not a composable call site, and the compiler only says so at APK
    // build -- eight minutes away.
    val phases = LocalVocab.current.phases
    // The LAST declared phase, not the literal "end" -- a game whose turn ends
    // in "regroup" has no phase called "end", and a delayed trigger pointed at
    // one would never fire.
    MenuPill(eventKind(p), EVENT_KINDS) { k -> onChange(eventOfKind(k, phases.lastOrNull() ?: "end")) }
}

/** The kind pill plus the chosen pattern's own parameters. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EventPatternEditor(p: EventPattern, onChange: (EventPattern) -> Unit) {
    val phases = LocalVocab.current.phases
    val types = LocalVocab.current.types
    fun whoLabel(w: PlayerRef?) = playerWord(w, "anyone's", "yours", "an opponent's")
    fun whoOfLabel(s: String) = when (s) { "yours" -> PlayerRef.You; "an opponent's" -> PlayerRef.Opponent; else -> null }
    val whoLabels = listOf("anyone's", "yours", "an opponent's")
    @Composable
    fun typeChips(sel: Set<String>, put: (Set<String>) -> Unit) {
        Text("which types (none = any)", color = Cg.dim, fontSize = 9.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            (types + sel.filter { it !in types }).forEach { ty ->
                SmallChip(ty, ty in sel) { put(if (ty in sel) sel - ty else sel + ty) }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            EventPatternPill(p, onChange)
            when (p) {
                is EventPattern.OnPhase -> {
                    MenuPill(p.phase, phases) { onChange(p.copy(phase = it)) }
                    MenuPill(whoLabel(p.whose), whoLabels) { onChange(p.copy(whose = whoOfLabel(it))) }
                }
                is EventPattern.Enters -> {
                    MenuPill(whoLabel(p.whose), whoLabels) { onChange(p.copy(whose = whoOfLabel(it))) }
                    MenuPill(if (p.other) "another" else "any", listOf("any", "another")) { onChange(p.copy(other = it == "another")) }
                }
                is EventPattern.Cast -> MenuPill(whoLabel(p.whose), whoLabels) { onChange(p.copy(whose = whoOfLabel(it))) }
                is EventPattern.Dies -> MenuPill(whoLabel(p.whose), whoLabels) { onChange(p.copy(whose = whoOfLabel(it))) }
                is EventPattern.CounterCrosses -> {
                    CgMiniField(p.kind, Modifier.width(64.dp)) { onChange(p.copy(kind = it)) }
                    MenuPill(if (p.downward) "drops to" else "reaches", listOf("reaches", "drops to")) { onChange(p.copy(downward = it == "drops to")) }
                    StepPill(p.k) { onChange(p.copy(k = it.coerceAtLeast(1))) }
                }
                is EventPattern.SelfDealsDamage ->
                    MenuPill(if (p.combatOnly) "combat only" else "any damage", listOf("combat only", "any damage")) { onChange(p.copy(combatOnly = it == "combat only")) }
                is EventPattern.Damaged -> {
                    FilterPill(p.filter) { onChange(p.copy(filter = it)) }
                    MenuPill(if (p.combatOnly) "combat only" else "any damage", listOf("combat only", "any damage")) { onChange(p.copy(combatOnly = it == "combat only")) }
                }
                is EventPattern.PlayerDamaged -> {
                    MenuPill(playerWord(p.whose, "any player", "you", "an opponent"), listOf("any player", "you", "an opponent")) {
                        onChange(p.copy(whose = when (it) { "you" -> PlayerRef.You; "an opponent" -> PlayerRef.Opponent; else -> null }))
                    }
                    MenuPill(if (p.combatOnly) "combat only" else "any damage", listOf("combat only", "any damage")) { onChange(p.copy(combatOnly = it == "combat only")) }
                }
                is EventPattern.OnCombatStep -> {
                    // A step name: the two declare steps, or a damage step by the name its CombatDamage gives it.
                    CgMiniField(p.step ?: "", Modifier.width(96.dp)) { onChange(p.copy(step = it.ifBlank { null })) }
                    MenuPill(playerWord(p.whose, "anyone's combat", "your combat", "an opponent's combat"), listOf("anyone's combat", "your combat", "an opponent's combat")) {
                        onChange(p.copy(whose = when (it) { "your combat" -> PlayerRef.You; "an opponent's combat" -> PlayerRef.Opponent; else -> null }))
                    }
                }
                EventPattern.AnyTurnBegan, EventPattern.Never, EventPattern.SelfEnters, EventPattern.SelfLeaves,
                EventPattern.SelfAttacks, EventPattern.MovesIntoThisZone -> {}
            }
        }
        when (p) {
            is EventPattern.Enters -> typeChips(p.types) { onChange(p.copy(types = it)) }
            is EventPattern.Cast -> typeChips(p.types) { onChange(p.copy(types = it)) }
            is EventPattern.Dies -> typeChips(p.types) { onChange(p.copy(types = it)) }
            else -> {}
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EffectNode(e: Effect, depth: Int, implicitTarget: String, onChange: (Effect) -> Unit, onRemove: (() -> Unit)?) {
    val tw = when (implicitTarget) {
        SELF -> "this"
        EACH -> "it"
        TRIGGER -> "the permanent that triggered it"
        else -> "the chosen target"
    }
    val swp: @Composable () -> Unit = { SwapPill(implicitTarget) { onChange(it) } }
    when (e) {
        is Effect.DamageOpponent -> NodeRow(depth, onRemove, swap = swp) {
            word("Deal"); IntPill(e.amount) { onChange(e.copy(amount = it)) }; word("damage to your opponent")
        }
        is Effect.DealDamage -> NodeRow(depth, onRemove, swap = swp) {
            word("Deal"); IntPill(e.amount) { onChange(e.copy(amount = it)) }; word("damage to")
            // A player or the implicit target -- never both.
            MenuPill(playerWord(e.player, tw, "you", "the opponent"), listOf(tw, "you", "the opponent")) { pick ->
                onChange(when (pick) {
                    "you" -> e.copy(target = null, player = PlayerRef.You)
                    "the opponent" -> e.copy(target = null, player = PlayerRef.Opponent)
                    else -> e.copy(target = BoundTarget(implicitTarget), player = null)
                })
            }
        }
        is Effect.Draw -> NodeRow(depth, onRemove, swap = swp) {
            word("You draw"); IntPill(e.count) { onChange(e.copy(count = it)) }
        }
        is Effect.GainLife -> NodeRow(depth, onRemove, swap = swp) {
            word("You gain"); IntPill(e.amount) { onChange(e.copy(amount = it)) }; word("life")
        }
        is Effect.AddMana -> NodeRow(depth, onRemove, swap = swp) {
            // The amount is an IntExpr now, so a card can add "1 per card under
            // this Station". A stepper can only author a LITERAL, so a non
            // -literal is shown in words instead of being silently rounded to
            // one -- editing it through this row would destroy it.
            word("You add")
            when (val amt = e.mana[""]) {
                null -> StepPill(0) { onChange(e.copy(mana = mapOf("" to ccg.lit(it)))) }
                is ccg.IntExpr.Lit -> StepPill(amt.value) { onChange(e.copy(mana = mapOf("" to ccg.lit(it)))) }
                else -> word(ccgui.intSummary(amt))
            }
            word("mana")
        }
        is Effect.Discard -> NodeRow(depth, onRemove, swap = swp) {
            WhoPill(e.who) { onChange(e.copy(who = it)) }
            word("discards"); IntPill(e.count) { onChange(e.copy(count = it)) }
            word("into"); ZonePill(e.toZone) { onChange(e.copy(toZone = it)) }
        }
        is Effect.DrawThenDiscard -> NodeRow(depth, onRemove, swap = swp) {
            WhoPill(e.who) { onChange(e.copy(who = it)) }
            word("draws"); IntPill(e.draw) { onChange(e.copy(draw = it)) }
            word("then sends"); IntPill(e.discard) { onChange(e.copy(discard = it)) }
            word("of those into"); ZonePill(e.toZone) { onChange(e.copy(toZone = it)) }
        }
        is Effect.Sacrifice -> NodeRow(depth, onRemove, swap = swp) {
            word("You sacrifice"); IntPill(e.count) { onChange(e.copy(count = it)) }
            FilterPill(e.filter) { onChange(e.copy(filter = it)) }
        }
        is Effect.CreateToken -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            NodeRow(depth, onRemove, swap = swp) {
                word("Create"); IntPill(e.count) { onChange(e.copy(count = it)) }
                CgMiniField(e.chars.name, Modifier.width(90.dp)) { onChange(e.copy(chars = e.chars.copy(name = it))) }
                word("tokens")
            }
            // A token's types, fields and zone come from the game's own
            // vocabulary (a Ship token, in a lane).
            val v = LocalVocab.current
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                word("of type")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    (v.types + e.chars.types.filter { it !in v.types }).forEach { ty ->
                        Chip(ty, ty in e.chars.types) {
                            val next = if (ty in e.chars.types) e.chars.types - ty else e.chars.types + ty
                            onChange(e.copy(chars = e.chars.copy(types = next)))
                        }
                    }
                }
            }
            // Every stat field the GAME declares, plus whatever the token
            // carries (one field too many beats hiding one it needs).
            (v.fields + e.chars.fields.keys.filter { it !in v.fields }).forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    word(f)
                    StepPill(e.chars.fields[f] ?: 0) {
                        onChange(e.copy(chars = e.chars.copy(fields = e.chars.fields + (f to it))))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                word("in")
                val dflt = "(its type's own zone)"
                CgPicker(e.zone ?: dflt, listOf(dflt) + v.zones) { z ->
                    onChange(e.copy(zone = z.takeIf { it != dflt }))
                }
            }
        }
        is Effect.Destroy -> NodeRow(depth, onRemove, swap = swp) { word("Destroy $tw") }
        is Effect.Transform -> NodeRow(depth, onRemove, swap = swp) { word("Transform $tw") }
        is Effect.AddCounter -> NodeRow(depth, onRemove, swap = swp) {
            word("Put"); IntPill(e.count) { onChange(e.copy(count = it)) }
            CgMiniField(e.kind, Modifier.width(56.dp)) { onChange(e.copy(kind = it)) }
            word("counters on $tw")
        }
        is Effect.RemoveCounter -> NodeRow(depth, onRemove, swap = swp) {
            word("Remove"); IntPill(e.count) { onChange(e.copy(count = it)) }
            CgMiniField(e.kind, Modifier.width(56.dp)) { onChange(e.copy(kind = it)) }
            word("from $tw")
        }
        Effect.NoOp -> NodeRow(depth, onRemove, swap = swp) { word("Do nothing") }
        // -- combat. On a card, declaring attackers is an extra combat.
        is Effect.DeclareAttackers -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NodeRow(depth, onRemove, swap = swp) {
                word("Declare attackers among"); FilterPill(e.eligible) { onChange(e.copy(eligible = it)) }
                val ready = e.staysReady
                if (ready == null) {
                    CgAddButton("some stay ready") { onChange(e.copy(staysReady = PermFilter())) }
                } else {
                    word("; these stay ready:"); FilterPill(ready) { onChange(e.copy(staysReady = it)) }
                    CgDeleteX { onChange(e.copy(staysReady = null)) }
                }
                word(", then:")
            }
            EffectNode(e.then, depth + 1, implicitTarget, onChange = { onChange(e.copy(then = it)) }, onRemove = null)
        }
        is Effect.DeclareBlockers -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NodeRow(depth, onRemove, swap = swp) {
                word("Declare blockers among"); FilterPill(e.eligible) { onChange(e.copy(eligible = it)) }
                if (e.rules.isNotEmpty()) word("(${e.rules.size} block rule(s))")
                word(", then:")
            }
            EffectNode(e.then, depth + 1, implicitTarget, onChange = { onChange(e.copy(then = it)) }, onRemove = null)
        }
        Effect.CombatWindow -> NodeRow(depth, onRemove, swap = swp) { word("Players may respond (combat window)") }
        is Effect.Attack -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NodeRow(depth, onRemove, swap = swp) {
                word("An attack by"); FilterPill(e.attacker) { onChange(e.copy(attacker = it)) }
                word("at"); FilterPill(e.targets) { onChange(e.copy(targets = it)) }
                word(", attacks a turn"); IntPill(e.attacksPerTurn, ccg.SUBJECT) { onChange(e.copy(attacksPerTurn = it)) }
            }
            NodeRow(depth + 1, null) {
                @Composable fun opt(label: String, f: PermFilter?, set: (PermFilter?) -> Effect) {
                    if (f == null) CgAddButton(label) { onChange(set(PermFilter())) }
                    else { word("$label:"); FilterPill(f) { onChange(set(it)) }; CgDeleteX { onChange(set(null)) } }
                }
                opt("guards", e.mustTarget) { e.copy(mustTarget = it) }
                opt("may be redirected to", e.redirect) { e.copy(redirect = it) }
                opt("stays ready", e.staysReady) { e.copy(staysReady = it) }
            }
            NodeRow(depth + 1, null) {
                word("reaches a permanent when"); BoolPill(e.reaches, ccg.TARGET) { onChange(e.copy(reaches = it)) }
                word("the player when"); BoolPill(e.reachesFace, ccg.ATTACKER) { onChange(e.copy(reachesFace = it)) }
                word(", then:")
            }
            EffectNode(e.then, depth + 1, implicitTarget, onChange = { onChange(e.copy(then = it)) }, onRemove = null)
        }
        is Effect.FreeAttacks -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NodeRow(depth, onRemove, swap = swp) {
                word("Step"); CgMiniField(e.step, Modifier.width(72.dp)) { onChange(e.copy(step = it)) }
                word(": every"); FilterPill(e.actors) { onChange(e.copy(actors = it)) }; word("picks a target")
                MenuPill(if (e.window) "then a window" else "no window", listOf("then a window", "no window")) {
                    onChange(e.copy(window = it == "then a window"))
                }
                MenuPill(if (e.overflow) "excess carries on" else "excess is lost", listOf("excess carries on", "excess is lost")) {
                    onChange(e.copy(overflow = it == "excess carries on"))
                }
            }
            GunRow(depth + 1, "its attack", e.body, onRemove = null) { onChange(e.copy(body = it)) }
            e.guns.forEachIndexed { i, g ->
                GunRow(depth + 1, "gun ${i + 1}", g, onRemove = { onChange(e.copy(guns = e.guns.filterIndexed { j, _ -> j != i })) }) { ng ->
                    onChange(e.copy(guns = e.guns.mapIndexed { j, x -> if (j == i) ng else x }))
                }
            }
            NodeRow(depth + 1, null) { CgAddButton("a gun (one more attack)") { onChange(e.copy(guns = e.guns + e.body)) } }
        }
        is Effect.Strike -> NodeRow(depth, onRemove, swap = swp) {
            word("Step"); CgMiniField(e.step, Modifier.width(72.dp)) { onChange(e.copy(step = it)) }
            word(": the attacker deals"); IntPill(e.amount, ccg.SUBJECT) { onChange(e.copy(amount = it)) }
            MenuPill(if (e.returnDamage) "and takes it back" else "one way", listOf("and takes it back", "one way")) {
                onChange(e.copy(returnDamage = it == "and takes it back"))
            }
        }
        is Effect.Clash -> NodeRow(depth, onRemove, swap = swp) {
            word("Step"); CgMiniField(e.step, Modifier.width(72.dp)) { onChange(e.copy(step = it)) }
            word(": compare"); IntPill(e.attackStat, ccg.SUBJECT) { onChange(e.copy(attackStat = it)) }
            word("with"); IntPill(e.defendStat, ccg.SUBJECT) { onChange(e.copy(defendStat = it)) }
            MenuPill(if (e.excessToController) "difference to the loser" else "no difference", listOf("difference to the loser", "no difference")) {
                onChange(e.copy(excessToController = it == "difference to the loser"))
            }
        }
        is Effect.CombatDamage -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NodeRow(depth, onRemove, swap = swp) {
                word("Combat damage step"); CgMiniField(e.step, Modifier.width(72.dp)) { onChange(e.copy(step = it)) }
                word(": each fighter deals"); IntPill(e.amount, ccg.SUBJECT) { onChange(e.copy(amount = it)) }
            }
            NodeRow(depth + 1, null) {
                @Composable fun opt(label: String, f: PermFilter?, set: (PermFilter?) -> Effect) {
                    if (f == null) CgAddButton(label) { onChange(set(PermFilter())) }
                    else { word("$label:"); FilterPill(f) { onChange(set(it)) }; CgDeleteX { onChange(set(null)) } }
                }
                opt("only these fight", e.acts) { e.copy(acts = it) }
                opt("lethal", e.lethal) { e.copy(lethal = it) }
                opt("trample", e.tramples) { e.copy(tramples = it) }
                word("blockers needed"); IntPill(e.minBlockers, ccg.SUBJECT) { onChange(e.copy(minBlockers = it)) }
            }
        }

        is Effect.ChooseMany -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NodeRow(depth, onRemove, swap = swp) {
                word("Choose")
                MenuPill(if (e.upTo) "up to" else "exactly", listOf("exactly", "up to")) { onChange(e.copy(upTo = it == "up to")) }
                IntPill(e.count, implicitTarget) { onChange(e.copy(count = it)) }
                FilterPill(e.filter) { onChange(e.copy(filter = it)) }
                if (e.divide == null) {
                    CgAddButton("divide a pool") { onChange(e.copy(divide = lit(3))) }
                } else {
                    word(", dividing")
                    IntPill(e.divide!!, implicitTarget) { onChange(e.copy(divide = it)) }
                    CgDeleteX { onChange(e.copy(divide = null)) }
                }
                word(", then for each:")
            }
            EffectNode(e.body, depth + 1, EACH, onChange = { onChange(e.copy(body = it)) }, onRemove = null)
        }
        is Effect.Choose -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NodeRow(depth, onRemove, swap = swp) { word("Choose"); FilterPill(e.filter) { onChange(e.copy(filter = it)) }; word(", then:") }
            EffectNode(e.body, depth + 1, CHOSEN, onChange = { onChange(e.copy(body = it)) }, onRemove = null)
        }
        is Effect.Sequence -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NodeRow(depth, onRemove, swap = swp) { word("In order:") }
            e.steps.forEachIndexed { i, step ->
                EffectNode(
                    step, depth + 1, implicitTarget,
                    onChange = { s -> onChange(e.copy(steps = e.steps.mapIndexed { j, x -> if (j == i) s else x })) },
                    onRemove = { onChange(e.copy(steps = e.steps.filterIndexed { j, _ -> j != i })) },
                )
            }
            Box(Modifier.padding(start = nodeIndent(depth + 1))) {
                var open by remember { mutableStateOf(false) }
                CgAddButton("step") { open = true }
                VerbMenu(open, includeTargeted = false, implicitTarget = implicitTarget, onDismiss = { open = false }) {
                    onChange(e.copy(steps = e.steps + it)); open = false
                }
            }
        }
        is Effect.If -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NodeRow(depth, onRemove, swap = swp) { word("If"); BoolPill(e.cond) { onChange(e.copy(cond = it)) }; word(":") }
            EffectNode(e.then, depth + 1, implicitTarget, onChange = { onChange(e.copy(then = it)) }, onRemove = null)
            NodeRow(depth, null) { word("Otherwise:") }
            EffectNode(e.otherwise, depth + 1, implicitTarget, onChange = { onChange(e.copy(otherwise = it)) }, onRemove = null)
        }
        is Effect.ChooseMode -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NodeRow(depth, onRemove, swap = swp) {
                word("Choose"); IntPill(e.pick) { onChange(e.copy(pick = it)) }; word("of:")
            }
            e.options.forEachIndexed { i, opt ->
                EffectNode(
                    opt, depth + 1, implicitTarget,
                    onChange = { o -> onChange(e.copy(options = e.options.mapIndexed { j, x -> if (j == i) o else x })) },
                    onRemove = { onChange(e.copy(options = e.options.filterIndexed { j, _ -> j != i })) },
                )
            }
            Box(Modifier.padding(start = nodeIndent(depth + 1))) {
                var open by remember { mutableStateOf(false) }
                CgAddButton("option") { open = true }
                VerbMenu(open, includeTargeted = true, implicitTarget = implicitTarget, onDismiss = { open = false }) {
                    onChange(e.copy(options = e.options + it)); open = false
                }
            }
        }
        // -- 5b-follow: the remaining verbs -------------------------------

        is Effect.PreventDamage -> NodeRow(depth, onRemove, swap = swp) {
            word("Prevent")
            MenuPill(if (e.all) "all" else "the next N", listOf("the next N", "all")) {
                onChange(e.copy(all = it == "all"))
            }
            if (!e.all) IntPill(e.amount) { onChange(e.copy(amount = it)) }
            word("damage to")
            MenuPill(
                playerWord(e.who, "that permanent", "you", "the opponent"),
                listOf("that permanent", "you", "the opponent"),
            ) { pick ->
                onChange(e.copy(who = when (pick) { "you" -> PlayerRef.You; "the opponent" -> PlayerRef.Opponent; else -> null }))
            }
            if (e.who == null) word(tw)
            MenuPill(if (e.mode == ShieldMode.INSTANCES) "hits" else "points", listOf("points", "hits")) {
                onChange(e.copy(mode = if (it == "hits") ShieldMode.INSTANCES else ShieldMode.POINTS))
            }
            DurationPill(e.duration) { onChange(e.copy(duration = it)) }
        }
        is Effect.CopyOf -> NodeRow(depth, onRemove, swap = swp) {
            word("Create a token that's a copy of $tw")
        }
        is Effect.Attach -> NodeRow(depth, onRemove, swap = swp) {
            word("Attach to $tw")
        }
        is Effect.CounterSpell -> NodeRow(depth, onRemove, swap = swp) {
            word("Counter the latest")
            CgMiniField(e.types.sorted().joinToString(" "), Modifier.width(96.dp), mono = false) { txt ->
                onChange(e.copy(types = txt.split(" ").filter { it.isNotBlank() }.toSet()))
            }
            word("spell (blank = any) of")
            MenuPill(playerWord(e.whose, "anyone", "yours", "an opponent"), listOf("an opponent", "yours", "anyone")) {
                onChange(e.copy(whose = when (it) { "yours" -> PlayerRef.You; "anyone" -> null; else -> PlayerRef.Opponent }))
            }
        }
        is Effect.Tap -> NodeRow(depth, onRemove, swap = swp) {
            MenuPill(if (e.untap) "Ready" else "Exhaust", listOf("Exhaust", "Ready")) { onChange(e.copy(untap = it == "Ready")) }
            word(tw)
        }
        is Effect.ClearDamage -> NodeRow(depth, onRemove, swap = swp) { word("Remove all damage from $tw") }
        is Effect.SendTo -> NodeRow(depth, onRemove, swap = swp) {
            word("Put $tw into its owner's"); ZonePill(e.to) { onChange(e.copy(to = it)) }
        }
        is Effect.GainControl -> NodeRow(depth, onRemove, swap = swp) {
            word("Gain control of $tw"); DurationPill(e.duration) { onChange(e.copy(duration = it)) }
        }
        is Effect.MovePermanent -> NodeRow(depth, onRemove, swap = swp) {
            word("Move $tw to zone")
            CgMiniField(e.toZone.def, Modifier.width(96.dp)) { onChange(e.copy(toZone = ZoneRef(it))) }
        }
        is Effect.Proceed -> NodeRow(depth, onRemove, swap = swp) {
            word("Let it happen")
            MenuPill(if (e.amount == null) "unchanged" else "as", listOf("unchanged", "as")) {
                onChange(e.copy(amount = if (it == "unchanged") null else e.amount ?: IntExpr.EventAmount))
            }
            e.amount?.let { a -> IntPill(a) { onChange(e.copy(amount = it)) } }
        }
        is Effect.SetCombatMode -> NodeRow(depth, onRemove, swap = swp) {
            word("Set $tw's combat stance to")
            MenuPill(e.mode ?: "(clear)", LocalVocab.current.stances + "(clear)") {
                onChange(e.copy(mode = if (it == "(clear)") null else it))
            }
        }
        is Effect.ReturnFromDiscard -> NodeRow(depth, onRemove, swap = swp) {
            WhoPill(e.who) { onChange(e.copy(who = it)) }
            word("return"); IntPill(e.count) { onChange(e.copy(count = it)) }
            word("cards from discard to")
            MenuPill(if (e.toBattlefield) "the battlefield" else "hand", listOf("hand", "the battlefield")) {
                onChange(e.copy(toBattlefield = it == "the battlefield"))
            }
        }
        is Effect.SearchZone -> NodeRow(depth, onRemove, swap = swp) {
            WhoPill(e.who) { onChange(e.copy(who = it)) }
            word("search"); ZonePill(e.from) { onChange(e.copy(from = it)) }
            word("for"); IntPill(e.count) { onChange(e.copy(count = it)) }
            CardFilterPills(e.filter) { onChange(e.copy(filter = it)) }
            word("→")
            MenuPill(if (e.intoPlay) "the battlefield" else "a zone", listOf("a zone", "the battlefield")) {
                onChange(e.copy(intoPlay = it == "the battlefield"))
            }
            if (!e.intoPlay) ZonePill(e.to) { onChange(e.copy(to = it)) }
            MenuPill(if (e.thenShuffle) "then shuffle" else "keep order", listOf("then shuffle", "keep order")) {
                onChange(e.copy(thenShuffle = it == "then shuffle"))
            }
        }
        is Effect.Shuffle -> NodeRow(depth, onRemove, swap = swp) {
            WhoPill(e.who) { onChange(e.copy(who = it)) }
            word("shuffle"); ZonePill(e.zone) { onChange(e.copy(zone = it)) }
        }
        is Effect.MoveTop -> NodeRow(depth, onRemove, swap = swp) {
            WhoPill(e.who) { onChange(e.copy(who = it)) }
            word("put"); IntPill(e.count) { onChange(e.copy(count = it)) }
            word("cards from the top of the library into")
            ZonePill(e.to) { onChange(e.copy(to = it)) }
        }
        is Effect.LookAtTop -> NodeRow(depth, onRemove, swap = swp) {
            WhoPill(e.who) { onChange(e.copy(who = it)) }
            word("look at"); IntPill(e.count) { onChange(e.copy(count = it)) }
            word("cards, keep any on top, rest to")
            ZonePill(e.to) { onChange(e.copy(to = it)) }
        }
        is Effect.ApplyModifier -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NodeRow(depth, onRemove, swap = swp) {
                word("Affected:"); FilterPill(e.filter) { onChange(e.copy(filter = it)) }
                DurationPill(e.duration) { onChange(e.copy(duration = it)) }
                word("— they:")
            }
            Box(Modifier.padding(start = nodeIndent(depth + 1))) {
                CharOpListEditor(e.ops) { onChange(e.copy(ops = it)) }
            }
        }
        is Effect.CreateEmblem -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NodeRow(depth, onRemove, swap = swp) { word("Create an emblem with:") }
            Box(Modifier.padding(start = nodeIndent(depth + 1))) {
                StaticEditor(
                    e.statics.chars, e.statics.rules,
                    onStatics = { onChange(e.copy(statics = e.statics.copy(chars = it))) },
                    onRuleMods = { onChange(e.copy(statics = e.statics.copy(rules = it))) },
                )
            }
        }
        is Effect.Delayed -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NodeRow(depth, onRemove, swap = swp) {
                word("Later, when"); EventPatternEditor(e.on) { onChange(e.copy(on = it)) }; word(", do:")
            }
            EffectNode(e.effect, depth + 1, implicitTarget, onChange = { onChange(e.copy(effect = it)) }, onRemove = null)
        }
        is Effect.ForEach -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NodeRow(depth, onRemove, swap = swp) {
                word("For each"); FilterPill(e.filter) { onChange(e.copy(filter = it)) }; word(", do:")
            }
            EffectNode(e.body, depth + 1, EACH, onChange = { onChange(e.copy(body = it)) }, onRemove = null)
        }
        is Effect.ForEachPlayer -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NodeRow(depth, onRemove, swap = swp) { word("For each player, do:") }
            EffectNode(e.body, depth + 1, implicitTarget, onChange = { onChange(e.copy(body = it)) }, onRemove = null)
        }
        is Effect.AsPlayer -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NodeRow(depth, onRemove, swap = swp) {
                word("As")
                MenuPill(playerRefKind(e.who), PLAYER_REFS) { onChange(e.copy(who = playerRefOfKind(it, implicitTarget))) }
                word("(\"you\" below), do:")
            }
            EffectNode(e.body, depth + 1, implicitTarget, onChange = { onChange(e.copy(body = it)) }, onRemove = null)
        }
    }
}

/** One attack a FREE fighter makes: when it is carried, what it
 *  reaches (board and face), and how much it deals -- all read with the
 *  attacker and the target bound. */
@Composable
private fun GunRow(depth: Int, label: String, g: Gun, onRemove: (() -> Unit)?, onChange: (Gun) -> Unit) {
    NodeRow(depth, onRemove) {
        word("$label, carried when"); BoolPill(g.carried, ccg.ATTACKER) { onChange(g.copy(carried = it)) }
        word("reaches a permanent when"); BoolPill(g.reaches, ccg.TARGET) { onChange(g.copy(reaches = it)) }
        word("the player when"); BoolPill(g.reachesFace, ccg.ATTACKER) { onChange(g.copy(reachesFace = it)) }
    }
    NodeRow(depth, null) {
        word("deals"); IntPill(g.amount, ccg.ATTACKER) { onChange(g.copy(amount = it)) }
        word("to the player"); IntPill(g.faceAmount, ccg.ATTACKER) { onChange(g.copy(faceAmount = it)) }
    }
}

// -- slot editors --------------------------------------------------------

@Composable
internal fun Pill(text: String, onClick: () -> Unit) {
    Text(
        text,
        color = Cg.ink, fontFamily = Cg.mono, fontSize = 12.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Cg.accentWash)
            .border(1.dp, Cg.accent, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

@Composable
internal fun StepPill(v: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Pill("–") { if (v > 0) onChange(v - 1) }
        Text("$v", color = Cg.ink, fontFamily = Cg.mono, fontSize = 12.sp)
        Pill("+") { onChange(v + 1) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun IntPill(e: IntExpr, target: String = CHOSEN, onChange: (IntExpr) -> Unit) {
    var open by remember { mutableStateOf(false) }
    // Wraps: a nested read or sum in a Row would squeeze whatever follows
    // it to a sliver.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box {
            Pill(intSummary(e)) { open = true }
            DropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.background(Cg.surface)) {
                intKinds(target).forEach { (label, ex) ->
                    DropdownMenuItem(
                        text = { Text(label, color = Cg.ink, fontSize = 12.sp, fontFamily = Cg.mono) },
                        onClick = { onChange(ex); open = false },
                    )
                }
            }
        }
        when (e) {
            is IntExpr.Lit -> StepPill(e.value) { onChange(IntExpr.Lit(it)) }
            // the new reads and arithmetic carry their own parameters.
            is IntExpr.PlayerCounter -> {
                WhoPill(e.who) { onChange(e.copy(who = it)) }
                MenuPill(e.name, (LocalVocab.current.playerCounters + e.name).distinct()) { onChange(e.copy(name = it)) }
            }
            is IntExpr.ZoneSize -> {
                WhoPill(e.who) { onChange(e.copy(who = it)) }
                ZonePill(e.zone) { onChange(e.copy(zone = it)) }
            }
            is IntExpr.Param -> MenuPill(e.name, (ccg.PARAM_NAMES + e.name).distinct()) { onChange(e.copy(name = it)) }
            is IntExpr.SeatOf -> WhoPill(e.who) { onChange(e.copy(who = it)) }
            is IntExpr.Bin -> {
                word("("); IntPill(e.a, target) { onChange(e.copy(a = it)) }
                MenuPill(binOpLabel(e.op), BIN_OPS) { onChange(e.copy(op = binOpOf(it))) }
                IntPill(e.b, target) { onChange(e.copy(b = it)) }; word(")")
            }
            // Which permanent a read is about, and what it reads.
            is IntExpr.TargetField -> {
                MenuPill(roleLabel(e.target), ROLES) { onChange(e.copy(target = roleOf(it))) }
                MenuPill(e.key, (LocalVocab.current.fields + e.key).distinct()) { onChange(e.copy(key = it)) }
            }
            is IntExpr.LaneOf -> MenuPill(roleLabel(e.target), ROLES) { onChange(e.copy(target = roleOf(it))) }
            is IntExpr.Cond -> {
                word("(if"); BoolPill(e.cond, target) { onChange(e.copy(cond = it)) }
                word("then"); IntPill(e.then, target) { onChange(e.copy(then = it)) }
                word("else"); IntPill(e.otherwise, target) { onChange(e.copy(otherwise = it)) }; word(")")
            }
            else -> {}
        }
    }
}

/** The parameters of a `where` preset: its keyword, type, stat and number. */
@Composable
private fun WhereParams(b: BoolExpr?, onChange: (BoolExpr) -> Unit) {
    val vocab = LocalVocab.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            b is BoolExpr.HasKeyword -> CgMiniField(b.keyword, Modifier.width(96.dp)) { onChange(b.copy(keyword = it)) }
            b is BoolExpr.Not && b.term is BoolExpr.HasKeyword -> {
                val k = b.term as BoolExpr.HasKeyword
                CgMiniField(k.keyword, Modifier.width(96.dp)) { onChange(BoolExpr.Not(k.copy(keyword = it))) }
            }
            b is BoolExpr.Not && b.term is BoolExpr.IsType -> {
                val t = b.term as BoolExpr.IsType
                CgPicker(t.types.firstOrNull() ?: "", (vocab.types + t.types).distinct()) {
                    onChange(BoolExpr.Not(t.copy(types = setOf(it))))
                }
            }
            b is BoolExpr.Cmp && b.a is IntExpr.TargetField && b.b is IntExpr.Lit -> {
                val f = b.a as IntExpr.TargetField
                CgPicker(f.key, (vocab.fields + f.key).distinct()) { onChange(b.copy(a = f.copy(key = it))) }
                StepPill((b.b as IntExpr.Lit).value) { onChange(b.copy(b = IntExpr.Lit(it))) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
/** The vocabulary an authoring control may offer -- this game's declared type
 *  names, zone ids, counter kinds, phases and keywords. Ambient rather than
 *  threaded through every filter call site; the default is the builtins, so a
 *  control outside a provider still works. */
data class AuthoringVocab(
    val types: List<String> = emptyList(),
    val zones: List<String> = listOf("battlefield"),
    val counters: List<String> = COUNTER_KINDS,
    /** Every stat field any of this game's types declares. A combat step that
     *  names a field no card has deals nothing at all, silently. */
    val fields: List<String> = emptyList(),
    /** The phase names THIS game declares (its `TurnStructure`), so a trigger
     *  can name a phase the author created. */
    val phases: List<String> = PHASES.split(","),
    /** Keywords have no declaration site (free-form strings to the engine), so
     *  this list is HARVESTED from the game's cards: used once, suggested
     *  everywhere; a typo shows as its own chip. */
    val keywords: List<String> = emptyList(),
    /** The player counters this game declares (life, store, gold ...) -- what
     *  `IntExpr.PlayerCounter` can read. Added last: a mid-list parameter
     *  compiles in the core and breaks only at APK build. */
    val playerCounters: List<String> = listOf(ccg.LIFE),
    /** The combat stances this game's combat declares -- all `SetCombatMode`
     *  may name. */
    val stances: List<String> = emptyList(),
)

val LocalVocab = androidx.compose.runtime.compositionLocalOf { AuthoringVocab() }

/** A permanent filter, as a pill that opens a real editor. Every filter slot in
 *  the Creator goes through it; the canned presets ("a creature you control")
 *  are shortcuts into the editor, not the whole of it. */
@Composable
internal fun FilterPill(f: PermFilter, onChange: (PermFilter) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Pill(filterSummary(f)) { open = true }
    if (open) FilterDialog(f, onDismiss = { open = false }, onChange = onChange)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterDialog(f: PermFilter, onDismiss: () -> Unit, onChange: (PermFilter) -> Unit) {
    val vocab = LocalVocab.current
    // Edited locally and committed on close, so a half-built filter -- types
    // picked but controller not yet -- never reaches the document.
    var draft by remember(f) { mutableStateOf(f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which permanents?", color = Cg.ink, fontFamily = Cg.mono, fontSize = 14.sp) },
        confirmButton = {
            TextButton(onClick = { onChange(draft); onDismiss() }) {
                Text("Apply", color = Cg.accentLight)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Cg.ink2) } },
        containerColor = Cg.surface,
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(filterSummary(draft), color = Cg.accentLight, fontFamily = Cg.mono, fontSize = 11.sp)

                FilterLabel("quick")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Presets: each clears the other axes, which is what a
                    // preset should do.
                    listOf(
                        "any creature" to creatures(),
                        "a creature you control" to creatures().yours(),
                        "an opponent's creature" to creatures().theirs(),
                        "any permanent" to permanents(),
                        "a permanent you control" to permanents().yours(),
                    ).forEach { (label, pf) -> Chip(label, false) { draft = pf } }
                }

                FilterLabel("types — empty means any type")
                if (vocab.types.isEmpty()) {
                    Text("This game declares no types yet.", color = Cg.dim, fontSize = 10.sp)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // The game's OWN types, so a filter cannot name one that
                    // does not exist -- which would match nothing, silently.
                    (vocab.types + draft.types.filter { it !in vocab.types }).forEach { ty ->
                        Chip(ty, ty in draft.types) {
                            draft = draft.copy(types = if (ty in draft.types) draft.types - ty else draft.types + ty)
                        }
                    }
                }

                FilterLabel("controlled by")
                val who = listOf("anyone", "you", "an opponent")
                CgSegmented(who, when (draft.controller) { PlayerRef.You -> who[1]; PlayerRef.Opponent -> who[2]; else -> who[0] }) { pick ->
                    draft = draft.copy(
                        controller = when (pick) { who[1] -> PlayerRef.You; who[2] -> PlayerRef.Opponent; else -> null },
                    )
                }

                FilterLabel("in zone")
                val anyZone = "any zone"
                val sameZone = "this card's own zone"
                // The distinction that makes area effects authorable at all: a
                // PER_PLAYER zone gives each side its own instance, so "own
                // zone" is HALF a lane and "either side" is the whole line.
                val eitherSide = "this card's zone, either side"
                // Or the zone of what the effect chose: "each unit in
                // the chosen unit's lane".
                val chosenZone = "the chosen card's zone"
                val chosenEither = "the chosen card's zone, either side"
                val zoneOpts = listOf(anyZone, sameZone, eitherSide, chosenZone, chosenEither) + vocab.zones
                val zoneCur = when (val z = draft.zone) {
                    ZoneScoping.Any -> anyZone
                    is ZoneScoping.SameAs -> when (z.of) {
                        BoundTarget(SELF) -> if (z.eitherSide) eitherSide else sameZone
                        BoundTarget(CHOSEN) -> if (z.eitherSide) chosenEither else chosenZone
                        else -> ((z.of.ref as? ccg.Ref.Var)?.name ?: "a card") + "'s zone" + if (z.eitherSide) ", either side" else ""
                    }
                    is ZoneScoping.Named -> z.def
                    is ZoneScoping.Exact -> z.ref.def
                }
                CgPicker(zoneCur, zoneOpts) { pick ->
                    draft = draft.copy(
                        zone = when (pick) {
                            anyZone -> ZoneScoping.Any
                            sameZone -> ZoneScoping.SameAs()
                            eitherSide -> ZoneScoping.SameAs(eitherSide = true)
                            chosenZone -> ZoneScoping.SameAs(BoundTarget(CHOSEN))
                            chosenEither -> ZoneScoping.SameAs(BoundTarget(CHOSEN), eitherSide = true)
                            else -> ZoneScoping.Named(pick)
                        },
                    )
                }

                FilterLabel("counters")
                val noCounter = "(any)"
                CgPicker(draft.hasCounter ?: noCounter, listOf(noCounter) + vocab.counters) { pick ->
                    draft = draft.copy(hasCounter = pick.takeIf { it != noCounter })
                }
                if (draft.hasCounter != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("at least", color = Cg.ink2, fontFamily = Cg.mono, fontSize = 11.sp)
                        StepPill(draft.minCount) { draft = draft.copy(minCount = it.coerceAtLeast(1)) }
                    }
                }

                CgCheck("not this card itself", draft.excludesSource) { draft = draft.copy(excludesSource = it) }
                CgCheck("only what this is attached to", draft.onlyHost) { draft = draft.copy(onlyHost = it) }

                // anything else about each candidate, as a condition on it.
                FilterLabel("and also")
                val kind = whereKind(draft.where)
                CgPicker(kind, WHERE_KINDS + (if (kind == "custom") listOf("custom") else emptyList())) { pick ->
                    if (pick != "custom") draft = draft.copy(where = whereOfKind(pick))
                }
                WhereParams(draft.where) { draft = draft.copy(where = it) }
            }
        },
    )
}

@Composable
private fun FilterLabel(s: String) =
    Text(s, color = Cg.dim, fontFamily = Cg.mono, fontSize = 9.sp)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BoolPill(b: BoolExpr, target: String = CHOSEN, onChange: (BoolExpr) -> Unit) {
    // A compound (and/or/not) is a short head pill over its terms, one per
    // line and indented; anything else wraps. Drawn inline in one Row, a deep
    // tree squeezes its neighbours to one character a line.
    when (b) {
        is BoolExpr.And, is BoolExpr.Or, is BoolExpr.Not -> CompoundCond(b, target, onChange)
        else -> FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CondMenuPill(boolSummary(b), target, onChange)
            CondParams(b, target, onChange)
        }
    }
}

/** An and / or / not: a head pill (which still replaces the whole condition),
 *  then its terms, each on its own line under an indent rule. Full width, so
 *  inside a sentence's FlowRow it takes a line of its own. */
@Composable
private fun CompoundCond(b: BoolExpr, target: String, onChange: (BoolExpr) -> Unit) {
    val terms = when (b) {
        is BoolExpr.And -> b.terms
        is BoolExpr.Or -> b.terms
        is BoolExpr.Not -> listOf(b.term)
        else -> return
    }
    fun rebuild(ts: List<BoolExpr>): BoolExpr = when (b) {
        is BoolExpr.And -> BoolExpr.And(ts)
        is BoolExpr.Or -> BoolExpr.Or(ts)
        else -> BoolExpr.Not(ts.single())
    }
    val head = when (b) {
        is BoolExpr.And -> "all of"
        is BoolExpr.Or -> "any of"
        else -> "not"
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CondMenuPill(head, target, onChange)
        Column(
            Modifier.fillMaxWidth().padding(start = 6.dp)
                .border(1.dp, Cg.borderMuted, RoundedCornerShape(6.dp))
                .padding(start = 8.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            terms.forEachIndexed { i, t ->
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.weight(1f)) {
                        BoolPill(t, target) { nt -> onChange(rebuild(terms.mapIndexed { j, x -> if (j == i) nt else x })) }
                    }
                    if (b !is BoolExpr.Not && terms.size > 1) CgDeleteX { onChange(rebuild(terms.filterIndexed { j, _ -> j != i })) }
                }
            }
            if (b !is BoolExpr.Not) CgAddButton("term") { onChange(rebuild(terms + BoolExpr.Const(true))) }
        }
    }
}

/** A pill reading [label] that opens the condition menu: build any kind, or a
 *  quick pick. Picking replaces the condition the pill stands for. */
@Composable
private fun CondMenuPill(label: String, target: String, onChange: (BoolExpr) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Pill(label) { open = true }
        DropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.background(Cg.surface)) {
            val quick = listOf<Pair<String, BoolExpr>>(
                "you control ≥ N creatures" to BoolExpr.Cmp(IntExpr.CountPerms(creatures().yours()), CmpOp.GTE, IntExpr.Lit(3)),
                "you control ≤ N creatures" to BoolExpr.Cmp(IntExpr.CountPerms(creatures().yours()), CmpOp.LTE, IntExpr.Lit(1)),
                "opponent controls ≥ N creatures" to BoolExpr.Cmp(IntExpr.CountPerms(creatures().theirs()), CmpOp.GTE, IntExpr.Lit(3)),
                "your life ≥ N" to BoolExpr.Cmp(IntExpr.LifeOf(PlayerRef.You), CmpOp.GTE, IntExpr.Lit(10)),
                "your life ≤ N" to BoolExpr.Cmp(IntExpr.LifeOf(PlayerRef.You), CmpOp.LTE, IntExpr.Lit(5)),
                "opponent's life ≤ N" to BoolExpr.Cmp(IntExpr.LifeOf(PlayerRef.Opponent), CmpOp.LTE, IntExpr.Lit(10)),
                "your hand ≥ N cards" to BoolExpr.Cmp(IntExpr.HandSize(PlayerRef.You), CmpOp.GTE, IntExpr.Lit(1)),
                "your hand ≤ N cards" to BoolExpr.Cmp(IntExpr.HandSize(PlayerRef.You), CmpOp.LTE, IntExpr.Lit(0)),
                "you control an Artifact" to BoolExpr.Cmp(IntExpr.CountPerms(ofType("Artifact").yours()), CmpOp.GTE, IntExpr.Lit(1)),
                "you control an Enchantment" to BoolExpr.Cmp(IntExpr.CountPerms(ofType("Enchantment").yours()), CmpOp.GTE, IntExpr.Lit(1)),
            ) + run {
                // THIS CARD's own counter -- what a board-based loss
                // condition is made of (a Station dies at 0 hull).
                val kinds = LocalVocab.current.counters.ifEmpty { COUNTER_KINDS }
                kinds.map { k ->
                    "this card's $k \u2264 N" to BoolExpr.Cmp(IntExpr.SelfCounter(k), CmpOp.LTE, IntExpr.Lit(0))
                } + kinds.map { k ->
                    "this card's $k \u2265 N" to BoolExpr.Cmp(IntExpr.SelfCounter(k), CmpOp.GTE, IntExpr.Lit(1))
                }
            }
            // Build any condition, kind by kind -- what reach is written in.
            val built = COND_KINDS.map { k -> "build: $k" to condOfKind(k, ccg.BoundTarget(target)) }
            (built + quick).forEach { (item, ex) ->
                DropdownMenuItem(
                    text = { Text(item, color = Cg.ink, fontSize = 12.sp, fontFamily = Cg.mono) },
                    onClick = { onChange(ex); open = false },
                )
            }
        }
    }
}

/** The parameters of one condition node, recursively: its operands, its
 *  terms, the permanent it is about and what it asks of it. */
@Composable
private fun CondParams(b: BoolExpr, target: String, onChange: (BoolExpr) -> Unit) {
    val vocab = LocalVocab.current
    condSubject(b)?.let { who -> MenuPill(roleLabel(who), ROLES) { onChange(condAbout(b, roleOf(it))) } }
    when (b) {
        is BoolExpr.Cmp -> {
            IntPill(b.a, target) { onChange(b.copy(a = it)) }
            MenuPill(cmpOpLabel(b.op), CMP_OPS) { onChange(b.copy(op = cmpOpOf(it))) }
            IntPill(b.b, target) { onChange(b.copy(b = it)) }
        }
        // Compounds are drawn by `CompoundCond`, never inline.
        is BoolExpr.And, is BoolExpr.Or, is BoolExpr.Not -> {}
        is BoolExpr.HasKeyword -> CgMiniField(b.keyword, Modifier.width(96.dp)) { onChange(b.copy(keyword = it)) }
        is BoolExpr.IsType -> CgPicker(b.types.firstOrNull() ?: "", (vocab.types + b.types).distinct()) { onChange(b.copy(types = setOf(it))) }
        is BoolExpr.HasField -> MenuPill(b.key, (vocab.fields + b.key).distinct()) { onChange(b.copy(key = it)) }
        is BoolExpr.ZoneOwner -> b.who?.let { w ->
            MenuPill(playerRefKind(w), PLAYER_REFS) { onChange(b.copy(who = playerRefOfKind(it, target))) }
        }
        is BoolExpr.Const, is BoolExpr.IsExhausted, is BoolExpr.IsToken, is BoolExpr.AtDepth, is BoolExpr.HasType -> {}
    }
}
