package com.ccg

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import ccg.ActivatedAbility
import ccg.CharOp
import ccg.Cost
import ccg.Effect
import ccg.IntExpr
import ccg.EventPattern
import ccg.minus
import ccg.PermFilter
import ccg.ReplacementDoc
import ccg.CostMod
import ccg.RuleAction
import ccg.RuleMod
import ccg.SELF
import ccg.TRIGGER
import ccg.StaticSpec
import ccg.TriggerDoc
import ccg.PlayerRef
import ccg.creatures
import ccg.lit
import ccg.permanents
import ccgui.playerWord
import ccgui.CANT_ACTIONS
import ccgui.CHAR_OPS
import ccgui.PHASES
import ccgui.RULE_MODS
import ccgui.charOpKind
import ccgui.charOpOfKind
import ccgui.costSummary
import ccgui.litOf
import ccgui.ruleModKind
import ccgui.ruleModOfKind
import ccgui.triggerKind
import ccgui.triggerOfKind
import ccgui.triggerSubjectOf
import ccgui.retargetTrigger
import ccgui.withEffect
import ccgui.withOrder
import ccgui.TRIGGER_KINDS

// ---------------------------------------------------------------------------
// Trigger and static-ability editors: the same sentence-with-pills idiom as the
// Effect editor; a trigger's effect reuses EffectSection with implicitTarget =
// SELF ("... to this").
// ---------------------------------------------------------------------------

// -- Triggers ----------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TriggerEditor(triggers: List<TriggerDoc>, onChange: (List<TriggerDoc>) -> Unit) {
    // The phase a NEW "on your phase" trigger names: "upkeep" if the game has
    // one, else its first declared phase (a seed naming a missing phase never
    // fires). Read here, in composable context, for the click handlers below.
    val phases = LocalVocab.current.phases
    val seedPhase = phases.firstOrNull { it == "upkeep" } ?: phases.firstOrNull() ?: "upkeep"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        triggers.forEachIndexed { i, t ->
            fun put(nt: TriggerDoc) = onChange(triggers.mapIndexed { j, x -> if (j == i) nt else x })
            CgCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MenuPill(triggerKind(t), TRIGGER_KINDS) { k -> put(triggerOfKind(k, t.effect, t.order, seedPhase)) }
                    Spacer(Modifier.weight(1f))
                    // ORDER: lower resolves first, which is how a Saga's
                    // chapters are sequenced. It was always 0 and unreachable
                    // after, so two triggers on one card could not be ordered.
                    // Shown only when there IS something to order against.
                    if (triggers.size > 1) {
                        word("order")
                        StepPill(t.order) { n -> put(t.withOrder(n)) }
                    }
                    CgDeleteX { onChange(triggers.filterIndexed { j, _ -> j != i }) }
                }
                when (t) {
                    is TriggerDoc.YouCastType -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        // The game's own types. This offered five of Magic's,
                        // so "whenever you cast a Manoeuvre" was unsayable.
                        (LocalVocab.current.types + t.types.filter { it !in LocalVocab.current.types }).forEach { ty ->
                            SmallChip(ty, ty in t.types) {
                                put(t.copy(types = if (ty in t.types) t.types - ty else t.types + ty))
                            }
                        }
                    }
                    is TriggerDoc.OnYourPhase -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        word("phase:")
                        MenuPill(t.phase, LocalVocab.current.phases) { put(t.copy(phase = it)) }
                    }
                    is TriggerDoc.CounterThreshold -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CgMiniField(t.kind, Modifier.width(64.dp)) { put(t.copy(kind = it)) }
                        MenuPill(if (t.downward) "drops to" else "reaches", listOf("reaches", "drops to")) {
                            put(t.copy(downward = it == "drops to"))
                        }
                        StepPill(t.k) { put(t.copy(k = it.coerceAtLeast(1))) }
                    }
                    is TriggerDoc.SelfDealsDamage -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        word("damage:")
                        MenuPill(if (t.combatOnly) "combat only" else "any damage", listOf("combat only", "any damage")) {
                            put(t.copy(combatOnly = it == "combat only"))
                        }
                    }
                    is TriggerDoc.CreatureDies -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            word("whose:")
                            MenuPill(playerWord(t.whose, "anyone's", "yours", "an opponent's"), listOf("anyone's", "yours", "an opponent's")) { s ->
                                put(t.copy(whose = when (s) { "yours" -> PlayerRef.You; "an opponent's" -> PlayerRef.Opponent; else -> null }))
                            }
                        }
                        // `types` was the LAST hard-coded "Creature" left in the
                        // authoring layer -- the engine's copy was fixed rounds
                        // ago and this one kept the trigger pinned to a type a
                        // Ships game does not have. Empty = any type dying.
                        Text("which types (none = any)", color = Cg.dim, fontSize = 9.sp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            (LocalVocab.current.types + t.types.filter { it !in LocalVocab.current.types }).forEach { ty ->
                                SmallChip(ty, ty in t.types) {
                                    put(t.copy(types = if (ty in t.types) t.types - ty else t.types + ty))
                                }
                            }
                        }
                    }
                    is TriggerDoc.On -> EventPatternEditor(t.on) { put(t.copy(on = it)) }
                    else -> {}
                }
                // The effect acts on this permanent, or on the one the
                // event is about ("it" / "that creature").
                val subject = triggerSubjectOf(t.effect)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    word("then, acting on")
                    MenuPill(if (subject == TRIGGER) "the permanent that triggered it" else "this", listOf("this", "the permanent that triggered it")) {
                        put(t.withEffect(retargetTrigger(t.effect, if (it == "this") SELF else TRIGGER)))
                    }
                }
                EffectSection(t.effect, "＋ effect", subject) { ne -> put(t.withEffect(ne ?: Effect.NoOp)) }
            }
        }
        Box {
            var open by remember { mutableStateOf(false) }
            CgAddButton("trigger") { open = true }
            DropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.background(Cg.surface)) {
                TRIGGER_KINDS.forEach { k ->
                    DropdownMenuItem(
                        text = { Text(k, color = Cg.ink, fontSize = 12.sp, fontFamily = Cg.mono) },
                        onClick = { onChange(triggers + triggerOfKind(k, Effect.Draw(PlayerRef.You, lit(1)), 0, seedPhase)); open = false },
                    )
                }
            }
        }
    }
}

// -- Statics -----------------------------------------------------------

/** The +P/+T · become P/T · keyword · type · … op list, reused by the static
 *  editor and by `Effect.ApplyModifier` in the Effect editor. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CharOpListEditor(ops: List<CharOp>, addLabel: String = "op", onChange: (List<CharOp>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ops.forEachIndexed { oi, op ->
            fun putOp(no: CharOp) = onChange(ops.mapIndexed { j, x -> if (j == oi) no else x })
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MenuPill(charOpKind(op), CHAR_OPS) { k -> putOp(charOpOfKind(k)) }
                    when (op) {
                        is CharOp.PlusPT -> {
                            StepPill(litOf(op.power)) { putOp(op.copy(power = lit(it))) }; word("/")
                            StepPill(litOf(op.toughness)) { putOp(op.copy(toughness = lit(it))) }
                        }
                        is CharOp.SetPT -> {
                            StepPill(litOf(op.power)) { putOp(op.copy(power = lit(it))) }; word("/")
                            StepPill(litOf(op.toughness)) { putOp(op.copy(toughness = lit(it))) }
                        }
                        is CharOp.GrantKeyword -> CgMiniField(op.keyword, Modifier.width(90.dp)) { putOp(op.copy(keyword = it)) }
                        is CharOp.AddType -> CgMiniField(op.type, Modifier.width(90.dp)) { putOp(op.copy(type = it)) }
                        is CharOp.SetTypes -> CgMiniField(op.types.joinToString(" "), Modifier.width(120.dp)) {
                            putOp(op.copy(types = it.split(" ").filter { s2 -> s2.isNotBlank() }.toSet()))
                        }
                        is CharOp.Bands -> {
                            CgMiniField(op.counter, Modifier.width(76.dp)) { putOp(op.copy(counter = it)) }
                            word("→")
                            CgMiniField(op.fieldA, Modifier.width(64.dp)) { putOp(op.copy(fieldA = it)) }
                            word("/")
                            CgMiniField(op.fieldB, Modifier.width(64.dp)) { putOp(op.copy(fieldB = it)) }
                        }
                        is CharOp.PlusField -> {
                            CgMiniField(op.field, Modifier.width(76.dp)) { putOp(op.copy(field = it)) }
                            StepPill(litOf(op.amount)) { putOp(op.copy(amount = lit(it))) }
                        }
                        is CharOp.SetField -> {
                            CgMiniField(op.field, Modifier.width(76.dp)) { putOp(op.copy(field = it)) }
                            word("=")
                            StepPill(litOf(op.value)) { putOp(op.copy(value = lit(it))) }
                        }
                        is CharOp.GrantAbility -> {}
                        CharOp.RemoveAbilities -> {}
                    }
                    Spacer(Modifier.weight(1f))
                    CgDeleteX { onChange(ops.filterIndexed { j, _ -> j != oi }) }
                }
                if (op is CharOp.GrantAbility) {
                    // the granted ability's own cost + effect.
                    // Its effect targets the permanent that HAS it, so SELF.
                    Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        word("cost: ${costSummary(op.ability.cost)}")
                        CostEditor(op.ability.cost, allowAlternatives = false) { nc ->
                            putOp(op.copy(ability = op.ability.copy(cost = nc)))
                        }
                        word("→")
                        EffectSection(op.ability.effect, "＋ ability effect", SELF) { ne ->
                            putOp(op.copy(ability = op.ability.copy(effect = ne ?: Effect.NoOp)))
                        }
                    }
                }
                if (op is CharOp.Bands) {
                    op.steps.forEachIndexed { bi, b ->
                        fun putB(nb: CharOp.Bands.Band) =
                            putOp(op.copy(steps = op.steps.mapIndexed { j, x -> if (j == bi) nb else x }))
                        Row(
                            Modifier.padding(start = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            word("≥"); StepPill(b.at) { putB(b.copy(at = it)) }; word("→")
                            StepPill(litOf(b.power)) { putB(b.copy(power = lit(it))) }; word("/")
                            StepPill(litOf(b.toughness)) { putB(b.copy(toughness = lit(it))) }
                            CgDeleteX { putOp(op.copy(steps = op.steps.filterIndexed { j, _ -> j != bi })) }
                        }
                    }
                    Box(Modifier.padding(start = 16.dp)) {
                        CgAddButton("band") { putOp(op.copy(steps = op.steps + CharOp.Bands.Band(1, lit(1), lit(1)))) }
                    }
                }
            }
        }
        Box {
            var open by remember { mutableStateOf(false) }
            CgAddButton(addLabel) { open = true }
            DropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.background(Cg.surface)) {
                CHAR_OPS.forEach { k ->
                    DropdownMenuItem(
                        text = { Text(k, color = Cg.ink, fontSize = 12.sp, fontFamily = Cg.mono) },
                        onClick = { onChange(ops + charOpOfKind(k)); open = false },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StaticEditor(
    statics: List<StaticSpec>,
    ruleMods: List<RuleMod>,
    /** Cost-modifying statics ("your spells cost 1 less"). They had NO control
     *  and were also missing from `CardBlock.STATICS.hasContent`, so a
     *  JSON-imported card carrying one showed an EMPTY Statics block -- the
     *  data was there and the UI said there was nothing. Two bugs in one gap. */
    costMods: List<CostMod> = emptyList(),
    onStatics: (List<StaticSpec>) -> Unit,
    onRuleMods: (List<RuleMod>) -> Unit,
    onCostMods: (List<CostMod>) -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        costMods.forEachIndexed { i, cm ->
            fun putCm(n: CostMod) = onCostMods(costMods.mapIndexed { j, x -> if (j == i) n else x })
            CgCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    word("costs")
                    // A negative delta is a DISCOUNT, which is the usual case
                    // and the reason the stepper is allowed below zero.
                    StepPill(cm.delta[""] ?: 0) { v -> putCm(cm.copy(delta = mapOf("" to v))) }
                    word("more")
                    Spacer(Modifier.weight(1f))
                    CgDeleteX { onCostMods(costMods.filterIndexed { j, _ -> j != i }) }
                }
                CgInlineField("whose") { WhoOrNobodyPill(cm.who) { w -> putCm(cm.copy(who = w)) } }
                Text("only these types (none = any)", color = Cg.dim, fontSize = 9.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LocalVocab.current.types.forEach { ty ->
                        SmallChip(ty, ty in cm.types) {
                            putCm(cm.copy(types = if (ty in cm.types) cm.types - ty else cm.types + ty))
                        }
                    }
                }
            }
        }
        statics.forEachIndexed { i, s ->
            fun put(ns: StaticSpec) = onStatics(statics.mapIndexed { j, x -> if (j == i) ns else x })
            CgCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterPill(s.filter) { put(s.copy(filter = it)) }
                    Spacer(Modifier.weight(1f))
                    CgDeleteX { onStatics(statics.filterIndexed { j, _ -> j != i }) }
                }
                CharOpListEditor(s.ops) { put(s.copy(ops = it)) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (s.condition == null) {
                        CgAddButton("condition") { put(s.copy(condition = BoolExpr.Const(true))) }
                    } else {
                        word("as long as")
                        BoolPill(s.condition!!) { put(s.copy(condition = it)) }
                        CgDeleteX { put(s.copy(condition = null)) }
                    }
                }
            }
        }
        CgAddButton("static ability") {
            onStatics(statics + StaticSpec(creatures().yours(), listOf(CharOp.PlusPT(lit(1), lit(1)))))
        }

        ruleMods.forEachIndexed { i, r ->
            fun put(nr: RuleMod) = onRuleMods(ruleMods.mapIndexed { j, x -> if (j == i) nr else x })
            CgCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MenuPill(ruleModKind(r), RULE_MODS) { k -> put(ruleModOfKind(k)) }
                    Spacer(Modifier.weight(1f))
                    CgDeleteX { onRuleMods(ruleMods.filterIndexed { j, _ -> j != i }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    when (r) {
                        is RuleMod.Cant -> {
                            WhoOrNobodyPill(r.who) { put(r.copy(who = it)) }
                            word("can't")
                            MenuPill(r.action.key, CANT_ACTIONS) { k -> RuleAction.of(k)?.let { put(r.copy(action = it)) } }
                        }
                        // Cluster 5. The filter is read in THIS card's
                        // controller's context, so "theirs" = your opponents'.
                        is RuleMod.CantAttack -> {
                            FilterPill(r.filter) { put(r.copy(filter = it)) }
                            word("can't attack")
                            MenuPill(
                                playerWord(r.defender, "anyone", "you", "your opponents"),
                                listOf("anyone", "you", "your opponents"),
                            ) { s ->
                                put(r.copy(defender = when (s) { "you" -> PlayerRef.You; "your opponents" -> PlayerRef.Opponent; else -> null }))
                            }
                        }
                        is RuleMod.CantBlock -> {
                            FilterPill(r.filter) { put(r.copy(filter = it)) }
                            word("can't block")
                        }
                        is RuleMod.CantActivate -> {
                            FilterPill(r.filter) { put(r.copy(filter = it)) }
                            word("can't activate abilities")
                        }
                        is RuleMod.ReduceDamage -> {
                            FilterPill(r.filter) { put(r.copy(filter = it)) }
                            word("takes")
                            IntPill(r.amount) { put(r.copy(amount = it)) }
                            word("less damage")
                        }
                    }
                }
            }
        }
        CgAddButton("cost mod") { onCostMods(costMods + CostMod(delta = mapOf("" to -1))) }
        Box {
            var open by remember { mutableStateOf(false) }
            CgAddButton("rule") { open = true }
            DropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.background(Cg.surface)) {
                RULE_MODS.forEach { k ->
                    DropdownMenuItem(
                        text = { Text(k, color = Cg.ink, fontSize = 12.sp, fontFamily = Cg.mono) },
                        onClick = { onRuleMods(ruleMods + ruleModOfKind(k)); open = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun WhoOrNobodyPill(who: PlayerRef?, onChange: (PlayerRef?) -> Unit) {
    MenuPill(
        playerWord(who, "nobody", "you", "your opponents"),
        listOf("nobody", "you", "your opponents"),
    ) { s -> onChange(when (s) { "you" -> PlayerRef.You; "your opponents" -> PlayerRef.Opponent; else -> null }) }
}

// -- shared bits -----------------------------------------------------

@Composable
internal fun MenuPill(value: String, options: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Pill(value) { open = true }
        DropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.background(Cg.surface)) {
            options.forEach { o ->
                DropdownMenuItem(
                    text = { Text(o, color = Cg.ink, fontSize = 12.sp, fontFamily = Cg.mono) },
                    onClick = { onPick(o); open = false },
                )
            }
        }
    }
}

/** "3m ago" / "5h ago" / "2d ago", coarse on purpose -- a version list, not a
 *  clock. */
internal fun relativeTime(epochMillis: Long): String {
    val m = ((System.currentTimeMillis() - epochMillis).coerceAtLeast(0)) / 60_000
    return when {
        m < 1 -> "just now"
        m < 60 -> "${m}m ago"
        m < 24 * 60 -> "${m / 60}h ago"
        else -> "${m / (24 * 60)}d ago"
    }
}

/** The game's saved-version list, read lazily when opened. Restoring is the
 *  caller's job (`onPick`). */
@Composable
internal fun HistoryPill(store: GameStore, gameId: String, onPick: (GameStore.GameVersion) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var versions by remember { mutableStateOf<List<GameStore.GameVersion>>(emptyList()) }
    Box {
        Pill("history") { versions = store.versions(gameId); open = true }
        DropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.background(Cg.surface)) {
            if (versions.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("no earlier versions yet", color = Cg.dim, fontSize = 11.sp, fontFamily = Cg.mono) },
                    onClick = { open = false },
                )
            }
            versions.forEach { v ->
                DropdownMenuItem(
                    text = { Text("${relativeTime(v.savedAt)} — ${v.name}", color = Cg.ink, fontSize = 12.sp, fontFamily = Cg.mono) },
                    onClick = { onPick(v); open = false },
                )
            }
        }
    }
}

@Composable
internal fun SmallChip(text: String, on: Boolean, onClick: () -> Unit) {
    Text(
        text, color = if (on) Cg.ink else Cg.ink2, fontFamily = Cg.mono, fontSize = 10.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (on) Cg.accentWash else Cg.surfaceAlt)
            .border(1.dp, if (on) Cg.accent else Cg.border, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    )
}

// ---------------------------------------------------------------------------
// Replacement effects -- "if X would happen, do Y instead" -- so a card
// carrying only a replacement does not look empty.
// ---------------------------------------------------------------------------

private val REPLACEMENT_KINDS = listOf(
    "damage to it -> destroy this instead",
    "damage to it -> prevent it",
    "it would die -> exile it instead",
    "damage to it -> spend a counter instead",
    // The general form: any pattern a replacement can match, any effect.
    "if … would happen -> do … instead",
)

private fun replacementKind(r: ReplacementDoc): String = when (r) {
    is ReplacementDoc.DamageToSacrificeSelf -> REPLACEMENT_KINDS[0]
    is ReplacementDoc.PreventDamageTo -> REPLACEMENT_KINDS[1]
    is ReplacementDoc.DeathToExile -> REPLACEMENT_KINDS[2]
    is ReplacementDoc.DamageToRemoveCounter -> REPLACEMENT_KINDS[3]
    is ReplacementDoc.Replace -> REPLACEMENT_KINDS[4]
}

private fun replacementOfKind(kind: String, keep: PermFilter): ReplacementDoc = when (kind) {
    REPLACEMENT_KINDS[1] -> ReplacementDoc.PreventDamageTo(keep)
    REPLACEMENT_KINDS[2] -> ReplacementDoc.DeathToExile(keep)
    // A shield by default because that is what the counter-backed form is for;
    // the counter is editable on the row.
    REPLACEMENT_KINDS[3] -> ReplacementDoc.DamageToRemoveCounter(keep, "shield")
    REPLACEMENT_KINDS[4] -> ReplacementDoc.Replace(EventPattern.Damaged(keep), Effect.Proceed(IntExpr.EventAmount - 1))
    else -> ReplacementDoc.DamageToSacrificeSelf(keep)
}

/** The filter a kind is about; the general form's, when its pattern has one. */
private fun replacementFilter(r: ReplacementDoc): PermFilter = when (r) {
    is ReplacementDoc.DamageToSacrificeSelf -> r.filter
    is ReplacementDoc.PreventDamageTo -> r.filter
    is ReplacementDoc.DeathToExile -> r.filter
    is ReplacementDoc.DamageToRemoveCounter -> r.filter
    is ReplacementDoc.Replace -> when (val p = r.pattern) {
        is EventPattern.Damaged -> p.filter
        is EventPattern.Dies -> p.filter ?: PermFilter()
        else -> PermFilter()
    }
}

private fun replacementStep(r: ReplacementDoc): String? = when (r) {
    is ReplacementDoc.DamageToSacrificeSelf -> r.onlyStep
    is ReplacementDoc.PreventDamageTo -> r.onlyStep
    is ReplacementDoc.DeathToExile, is ReplacementDoc.Replace -> null
    is ReplacementDoc.DamageToRemoveCounter -> r.onlyStep
}

private fun replacementWithStep(r: ReplacementDoc, step: String?): ReplacementDoc = when (r) {
    is ReplacementDoc.DamageToSacrificeSelf -> r.copy(onlyStep = step)
    is ReplacementDoc.PreventDamageTo -> r.copy(onlyStep = step)
    is ReplacementDoc.DeathToExile, is ReplacementDoc.Replace -> r
    is ReplacementDoc.DamageToRemoveCounter -> r.copy(onlyStep = step)
}

@Composable
internal fun ReplacementEditor(
    replacements: List<ReplacementDoc>,
    steps: List<String>,
    onChange: (List<ReplacementDoc>) -> Unit,
) {
    replacements.forEachIndexed { i, r ->
        fun put(nr: ReplacementDoc) = onChange(replacements.mapIndexed { j, x -> if (j == i) nr else x })
        CgCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MenuPill(replacementKind(r), REPLACEMENT_KINDS) { k -> put(replacementOfKind(k, replacementFilter(r))) }
                Spacer(Modifier.weight(1f))
                CgDeleteX { onChange(replacements.filterIndexed { j, _ -> j != i }) }
            }
            if (r is ReplacementDoc.Replace) {
                // The pattern and the effect, with the same editors a trigger uses.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    word("if"); EventPatternPill(r.pattern) { put(r.copy(pattern = it)) }; word("would happen, instead")
                }
                EffectSection(r.instead, implicitTarget = TRIGGER) { put(r.copy(instead = it ?: Effect.NoOp)) }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    word("affecting")
                    FilterPill(replacementFilter(r)) { f ->
                        put(
                            when (val cur = r) {
                                is ReplacementDoc.DamageToSacrificeSelf -> cur.copy(filter = f)
                                is ReplacementDoc.PreventDamageTo -> cur.copy(filter = f)
                                is ReplacementDoc.DeathToExile -> cur.copy(filter = f)
                                is ReplacementDoc.DamageToRemoveCounter -> cur.copy(filter = f)
                                is ReplacementDoc.Replace -> cur
                            },
                        )
                    }
                }
            }
            if (r is ReplacementDoc.DamageToRemoveCounter) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    word("spending a")
                    CgMiniField(r.counter, Modifier.width(96.dp)) { put(r.copy(counter = it)) }
                    word("counter")
                }
            }
            // Only the damage-shaped kinds can be scoped to a combat wave, and
            // only when the game HAS more than one step to scope to.
            if (r !is ReplacementDoc.DeathToExile && r !is ReplacementDoc.Replace && steps.size > 1) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val cur = replacementStep(r)
                    word("in step")
                    MenuPill(cur ?: "any", listOf("any") + steps) { k ->
                        put(replacementWithStep(r, k.takeIf { it != "any" }))
                    }
                }
            }
        }
    }
    CgAddButton("replacement effect") {
        onChange(replacements + ReplacementDoc.DamageToSacrificeSelf(PermFilter().onlyHost()))
    }
}
