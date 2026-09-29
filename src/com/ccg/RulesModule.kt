package com.ccg

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import ccgui.Focus
import ccgui.Module
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ccg.GameParams
import ccg.HiddenZoneDef
import ccg.permanents
import ccg.CmpOp
import ccg.BoolExpr
import ccg.ResourceModel
import ccg.Orientation
import ccg.PlayerCounterDef
import ccg.BUILTIN_COUNTER_KINDS
import ccg.CounterKindDef
import ccg.usedCounterKinds
import ccg.ArtSlot
import ccg.CardLayout
import ccg.CombatDoc
import ccg.stepNames
import ccg.CounterTrack
import ccg.StatCorner
import ccg.DeckRules
import ccg.DeckSlotDef
import ccg.IdentityRule
import ccg.Effect
import ccg.IntExpr
import ccg.PhaseEffects
import ccg.PhaseSpec
import ccg.PlayZoneDef
import ccg.TurnMode
import ccg.TurnStructure
import ccg.problems
import ccg.TypeDef
import ccg.ZoneScope
import ccg.build
import ccgui.sections
import ccg.Rules
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

// ---------------------------------------------------------------------------
// The Rules module: the section list and every section's editor.
// ---------------------------------------------------------------------------

/** The Rules sub-sections. ORDER IS THE UI: how the game RUNS first (time, then
 *  economy, then what you lose by), then the vocabulary that operates inside
 *  it -- a designer's order, by the user's call, not a dependency order. */
internal enum class RulesSection(val title: String) {
    GAME("The game"),
    TURN("Turn structure"),
    RESOURCES("Resources"),
    COUNTERS("Counters"),
    TYPES("Types & fields"),
    ZONES("Play zones"),
    COMBAT("Combat"),
    DECK("Deck construction"),
}

internal fun rulesSection(f: Focus.Section): RulesSection =
    RulesSection.entries.firstOrNull { it.name == f.name } ?: RulesSection.GAME

private val TYPE_FIELD_OPTS = listOf("power", "toughness", "loyalty", "defense", "lore", "level")

private fun presetBlurb(name: String): String = when (name) {
    "mtg" -> "Declared attackers + declared blockers; first-strike then regular; sequenced damage."
    "fastSlow" -> "Two simultaneous sub-phases (fast, slow); free targeting; no blockers."
    "hearthstone" -> "One attack at a time; taunt; divine-shield-style prevention."
    "onePiece" -> "One attack at a time; only rested targets; a blocker keyword."
    "yugioh" -> "One attack at a time; stat COMPARE decides destroy; attack / defense position."
    "fastSlowLanes" -> "fastSlow, plus: a ship may only hit the Station while its own lane is unopposed."
    "fastSlowLanesLocked" -> "…and lanes decide OPPONENTS too; \"reach\" buys the exemption."
    "fastSlowLanesCore" -> "The shipped Core: lanes decide opponents AND exhausting costs a wave."
    "singleStepLanesCore" -> "One combat wave instead of two, reading a single `strike` field."
    "screenedSingleStep" -> "One wave, and a back line is unreachable while its owner holds the front."
    "frontBack" -> "FRONT/BACK: one wave, a screened back line, damage overflows onto the Station, " +
        "and long range shoots over the line."
    "frontBackCore" -> "The shipped Core: FRONT/BACK, plus the two stats read by RANGE — `sr` on your " +
        "own line, `lr` when you reach across or aim at the Station."
    else -> ""
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RulesScreen(vm: CreatorViewModel, onOpen: (RulesSection) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "how the game runs, then what plays inside it — time and economy first, " +
                "vocabulary next, deck construction last",
            color = Cg.dim, fontFamily = Cg.mono, fontSize = 10.sp,
        )
        RulesSection.entries.forEach { s ->
            CgModule(
                title = s.title,
                summary = rulesSummary(vm, s),
                dock = if (s == RulesSection.DECK) Cg.accent else Cg.accentDim,
                dot = rulesDot(vm, s),
                icon = when (s) {
                    RulesSection.GAME -> CgIconKind.RULES
                    RulesSection.TYPES -> CgIconKind.TYPES
                    RulesSection.ZONES -> CgIconKind.ZONES
                    RulesSection.COUNTERS -> CgIconKind.COUNTERS
                    RulesSection.TURN -> CgIconKind.TURN
                    RulesSection.COMBAT -> CgIconKind.COMBAT
                    RulesSection.RESOURCES -> CgIconKind.RESOURCES
                    RulesSection.DECK -> CgIconKind.DECKRULES
                },
            ) { onOpen(s) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun rulesSummary(vm: CreatorViewModel, s: RulesSection): String {
    val r = vm.game.rules
    return when (s) {
        RulesSection.GAME -> {
            val p = r.params
            "${p.playerCount}p · ${p.startingHandSize} cards · draw ${p.cardsDrawnPerTurn}" +
                (p.poolStoreCounter?.let { " · store $it" } ?: "")
        }
        RulesSection.TYPES ->
            "${BUILTIN_TYPE_NAMES.size} built-in" + r.extraTypes.size.let { if (it > 0) " · $it custom" else "" }
        RulesSection.ZONES ->
            "battlefield" + r.extraZones.size.let { if (it > 0) " · +$it" else "" }
        RulesSection.COUNTERS ->
            r.playerCounters.joinToString(", ") { "${it.name} ${it.starting}" } +
                (r.counterKinds?.takeIf { it.isNotEmpty() }?.let { ks -> " · on cards: " + ks.joinToString(", ") { it.name } } ?: "")
        RulesSection.TURN ->
            "${r.turn.phases.size} phases" + if (r.turn != TurnStructure.MTG) " · custom" else ""
        RulesSection.COMBAT -> when (val c = r.combat) {
            is CombatDoc.Preset -> c.name
            is CombatDoc.Program -> "this game's own"
        }
        RulesSection.RESOURCES -> when (val m = r.resourceModel) {
            ResourceModel.None -> "from cards only"
            is ResourceModel.Ramp -> "+${m.perTurn}/turn, cap ${m.cap}"
            is ResourceModel.CardDriven -> "${m.playsPerTurn} play/turn"
        }
        RulesSection.DECK ->
            deckRulesPresetName(vm.game.deckRules) +
                vm.game.deckRules.slots.joinToString("") { " · ${it.count} ${it.name}" }
    }
}

private fun rulesDot(vm: CreatorViewModel, s: RulesSection): CgDot = when (s) {
    RulesSection.TURN ->
        if (vm.game.rules.turn.problems(vm.game.rules.params).isNotEmpty()) CgDot.WARN else CgDot.NONE
    RulesSection.DECK -> {
        // A slot rule that no deck has filled is the visible gap (5.3 adds the
        // pickers). Cheap and deterministic -- no Rules build per row.
        val dr = vm.game.deckRules
        val unfilled = dr.slots.isNotEmpty() && (
            vm.game.decks.isEmpty() ||
                vm.game.decks.any { d -> dr.slots.any { slot -> (d.slots[slot.name]?.size ?: 0) < slot.count } }
            )
        if (unfilled) CgDot.WARN else CgDot.NONE
    }
    else -> CgDot.NONE
}

/** One Rules section, opened from the list. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RulesSectionScreen(
    vm: CreatorViewModel,
    section: RulesSection,
    onNavigate: (Module) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (section) {
            RulesSection.TYPES -> RulesTypesEditor(vm)
            RulesSection.ZONES -> RulesZonesEditor(vm)
            RulesSection.GAME -> RulesGameEditor(vm)
            RulesSection.COUNTERS -> RulesCountersEditor(vm)
            RulesSection.TURN -> RulesTurnEditor(vm)
            RulesSection.COMBAT -> RulesCombatEditor(vm)
            RulesSection.RESOURCES -> RulesResourcesEditor(vm)
            RulesSection.DECK -> RulesDeckEditor(vm, onNavigate)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RulesTypesEditor(vm: CreatorViewModel) {
    val r = vm.game.rules
    val zoneIds = listOf("battlefield") + r.extraZones.map { it.id }
    Text(
        "Built-ins (Creature, Instant, …) are always available; these are extra. " +
            "Card types reference these names.",
        color = Cg.dim, fontSize = 10.sp,
    )
    r.extraTypes.forEachIndexed { i, t ->
        fun put(nt: TypeDef) = vm.updateRules { d ->
            d.copy(extraTypes = d.extraTypes.mapIndexed { j, x -> if (j == i) nt else x })
        }
        CgCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CgMiniField(t.name, Modifier.weight(1f)) { put(t.copy(name = it)) }
                CgDeleteX {
                    vm.updateRules { d -> d.copy(extraTypes = d.extraTypes.filterIndexed { j, _ -> j != i }) }
                }
            }
            Text(
                "fields — the stats this type has. Cards of this type get a stepper for each.",
                color = Cg.dim, fontSize = 9.sp, fontFamily = Cg.mono,
            )
            // The builtin names are SUGGESTIONS, not the vocabulary: a type
            // declares its own stats.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (TYPE_FIELD_OPTS + t.fields.filter { it !in TYPE_FIELD_OPTS }.sorted()).forEach { f ->
                    Chip(f, f in t.fields) {
                        put(t.copy(fields = if (f in t.fields) t.fields - f else t.fields + f))
                    }
                }
            }
            NameAdder("field") { name -> put(t.copy(fields = t.fields + name)) }
            CgCheck("is a spell (goes to discard on resolve)", t.isSpell) { put(t.copy(isSpell = it)) }
            CgCheck("can be declared an attacker", t.attacks) { put(t.copy(attacks = it)) }
            // WHEN it may be played, and whether it uses the stack. The second
            // is what makes an unanswerable card expressible -- EPR Skirmish's
            // sorcery-speed Directive resolves the moment it is played, with no
            // response window, and that is a type flag rather than a verb.
            CgCheck("may be played at any time (instant speed)", t.instantSpeed) { put(t.copy(instantSpeed = it)) }
            CgCheck("uses the stack (opponents get a window)", t.usesStack) { put(t.copy(usesStack = it)) }
            CgInlineField("enters zone") {
                CgPicker(t.zoneOfPlay ?: "battlefield", zoneIds) { z ->
                    put(t.copy(zoneOfPlay = z.takeIf { it != "battlefield" }))
                }
            }
            // `zoneChoices` ("3 lanes, pick one" at cast time). An empty
            // selection means just the one zone above.
            if (r.extraZones.isNotEmpty()) {
                Text("may ALSO be played into any of (optional, overrides the above into a choice)", color = Cg.dim, fontSize = 9.sp, fontFamily = Cg.mono)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    r.extraZones.map { it.id }.forEach { z ->
                        Chip(z, z in t.zoneChoices) {
                            put(t.copy(zoneChoices = if (z in t.zoneChoices) t.zoneChoices - z else t.zoneChoices + z))
                        }
                    }
                }
            }
            // -- what makes a permanent a LOSS CONDITION -------------------
            // `loseOnDeath`, `diesWhen` and `damageCounter`: what SWU's Base,
            // Hearthstone's Hero and EPR Skirmish's Station are made of.
            Text(
                "dying and damage \u2014 how a permanent of this type is lost",
                color = Cg.dim, fontSize = 9.sp, fontFamily = Cg.mono,
            )
            CgCheck("losing this loses the game", t.loseOnDeath) { put(t.copy(loseOnDeath = it)) }
            // Combat damage REMOVES this counter instead of marking damage (so
            // damage is permanent). `LocalVocab.current` is read HERE: inside a
            // click handler it is not a composable call site, which only the APK
            // build reports.
            val counterVocab = LocalVocab.current.counters
            val noDmg = "(marks damage normally)"
            CgInlineField("damage removes") {
                CgPicker(t.damageCounter ?: noDmg, listOf(noDmg) + counterVocab) { pick ->
                    put(t.copy(damageCounter = pick.takeIf { it != noDmg }))
                }
            }
            CgInlineField("dies when") {
                if (t.diesWhen == null) {
                    CgAddButton("condition") {
                        // Seeded with the predicate this feature exists for --
                        // "this card's <counter> reaches 0" -- rather than
                        // `Const(true)`, which would delete the permanent the
                        // instant it entered play.
                        val kind = counterVocab.firstOrNull() ?: "hull"
                        put(t.copy(diesWhen = BoolExpr.Cmp(IntExpr.SelfCounter(kind), CmpOp.LTE, IntExpr.Lit(0))))
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        BoolPill(t.diesWhen!!) { b -> put(t.copy(diesWhen = b)) }
                        CgDeleteX { put(t.copy(diesWhen = null)) }
                    }
                }
            }
            TypeLayoutBlock(t) { nl -> put(t.copy(layout = nl)) }
        }
    }
    CgAddButton("type") { vm.updateRules { it.copy(extraTypes = it.extraTypes + TypeDef("MyType")) } }
}

/** a type's card graphical structure -- curated slots + presets, with
 *  a live tile preview. Builtin types aren't editable here so they stay
 *  read-only by construction. */
@Composable
private fun TypeLayoutBlock(t: TypeDef, onLayout: (CardLayout?) -> Unit) {
    CgGroup("graphical structure", initiallyOpen = t.layout != null, summary = if (t.layout != null) "custom" else null) {
        val l = t.layout
        CgCheck("give this type its own card layout", l != null) { on ->
            onLayout(if (on) CardLayout() else null)
        }
        if (l != null) {
            CgInlineField("art") {
                CgSegmented(listOf("top", "left", "none"), l.art.name.lowercase()) { s ->
                    onLayout(l.copy(art = ArtSlot.valueOf(s.uppercase())))
                }
            }
            CgInlineField("stat box") {
                val cur = when (l.statCorner) {
                    StatCorner.BOTTOM_RIGHT -> "BR"; StatCorner.BOTTOM_LEFT -> "BL"; StatCorner.NONE -> "none"
                }
                CgSegmented(listOf("BR", "BL", "none"), cur) { s ->
                    onLayout(l.copy(statCorner = when (s) {
                        "BR" -> StatCorner.BOTTOM_RIGHT; "BL" -> StatCorner.BOTTOM_LEFT; else -> StatCorner.NONE
                    }))
                }
            }
            CgField("stat fields (space separated)", l.statFields.joinToString(" ")) { txt ->
                onLayout(l.copy(statFields = txt.split(" ").map { it.trim() }.filter { it.isNotEmpty() }))
            }
            CgInlineField("counter track") {
                val cur = when (l.counterTrack) {
                    CounterTrack.NONE -> "none"; CounterTrack.LEFT_EDGE -> "left"; CounterTrack.BOTTOM_STRIP -> "bottom"
                }
                CgSegmented(listOf("none", "left", "bottom"), cur) { s ->
                    onLayout(l.copy(counterTrack = when (s) {
                        "left" -> CounterTrack.LEFT_EDGE; "bottom" -> CounterTrack.BOTTOM_STRIP; else -> CounterTrack.NONE
                    }))
                }
            }
            CgField("counter kind (blank = all)", l.counterKind ?: "") { onLayout(l.copy(counterKind = it.ifBlank { null })) }
            CgCheck("show rules text", l.showText) { onLayout(l.copy(showText = it)) }
            CgField("accent #rrggbb (blank = default)", l.accent ?: "") { onLayout(l.copy(accent = it.ifBlank { null })) }

            Text("preview", color = Cg.dim, fontFamily = Cg.mono, fontSize = 9.sp)
            CgCardTile(
                name = "Sample ${t.name}",
                typeLabel = t.name,
                costLabel = "◆◆",
                pt = t.fields.toList().take(2).takeIf { it.isNotEmpty() }?.joinToString("/") { "3" },
                text = "A sample card of this type.",
                layout = l,
                counters = mapOf((l.counterKind ?: "charge") to 3),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RulesZonesEditor(vm: CreatorViewModel) {
    val r = vm.game.rules
    Text("'battlefield' always exists; add arenas / lanes / planets here.", color = Cg.dim, fontSize = 10.sp)
    r.extraZones.forEachIndexed { i, z ->
        fun put(nz: PlayZoneDef) = vm.updateRules { d ->
            d.copy(extraZones = d.extraZones.mapIndexed { j, x -> if (j == i) nz else x })
        }
        CgCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CgMiniField(z.id, Modifier.weight(1f)) { put(z.copy(id = it)) }
                CgDeleteX {
                    vm.updateRules { d -> d.copy(extraZones = d.extraZones.filterIndexed { j, _ -> j != i }) }
                }
            }
            CgSegmented(
                listOf("shared", "perPlayer"),
                if (z.scope == ZoneScope.SHARED) "shared" else "perPlayer",
            ) { s -> put(z.copy(scope = if (s == "perPlayer") ZoneScope.PER_PLAYER else ZoneScope.SHARED)) }
            // A CAP is what makes a lane a lane rather than a pile. Null means
            // uncapped, which is a real answer, so it is a toggle.
            CgCheck("limits how many fit", z.maxOccupants != null) {
                put(z.copy(maxOccupants = if (it) 1 else null))
            }
            z.maxOccupants?.let { cap ->
                LabeledStep("at most", cap) { put(z.copy(maxOccupants = it.coerceAtLeast(1))) }
            }
            // WHICH COMBAT WAVES a permanent here may act in, offered from the
            // steps THIS GAME declares. Null = all of them (a real answer, so a
            // toggle rather than an empty set).
            val stepNames = r.combat.compile().stepNames().toList()
            if (stepNames.isNotEmpty()) {
                CgCheck("acts only in some combat steps", z.combatSteps != null) { on ->
                    put(z.copy(combatSteps = if (on) stepNames.toSet() else null))
                }
                z.combatSteps?.let { sel ->
                    for (sn in stepNames) {
                        CgCheck("acts in \"$sn\"", sn in sel) { on ->
                            put(z.copy(combatSteps = if (on) sel + sn else sel - sn))
                        }
                    }
                }
            }
        }
    }
    CgAddButton("zone") { vm.updateRules { it.copy(extraZones = it.extraZones + PlayZoneDef("newzone")) } }

    // -- declared HIDDEN zones ------------------------------------------------
    // A pool a deck draws from that is not the library and not the hand -- EPR
    // Skirmish's Flagship pool. `alwaysVisible` is what makes it a POOL rather
    // than a second hand: everyone can see what is in it.
    Spacer(Modifier.height(6.dp))
    Text(
        "off-board pools \u2014 a named zone cards are played FROM, beside the hand",
        color = Cg.dim, fontSize = 9.sp, fontFamily = Cg.mono,
    )
    r.extraHiddenZones.forEachIndexed { i, hz ->
        fun putH(nz: HiddenZoneDef) = vm.updateRules { d ->
            d.copy(extraHiddenZones = d.extraHiddenZones.mapIndexed { j, x -> if (j == i) nz else x })
        }
        CgCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CgMiniField(hz.id, Modifier.weight(1f)) { putH(hz.copy(id = it)) }
                CgDeleteX {
                    vm.updateRules { d -> d.copy(extraHiddenZones = d.extraHiddenZones.filterIndexed { j, _ -> j != i }) }
                }
            }
            CgCheck("both players can see it", hz.alwaysVisible) { putH(hz.copy(alwaysVisible = it)) }
        }
    }
    CgAddButton("pool") {
        vm.updateRules { it.copy(extraHiddenZones = it.extraHiddenZones + HiddenZoneDef("flagships")) }
    }
}

@Composable
private fun RulesGameEditor(vm: CreatorViewModel) {
    val r = vm.game.rules
    val p = r.params
    fun put(np: GameParams) = vm.updateRules { it.copy(params = np) }

    Text(
        "The shape of a game, as opposed to what is in it: hand size, draws, " +
            "hand limit, mulligans. The defaults are Magic's: seven cards, draw one, discard to seven.",
        color = Cg.dim, fontSize = 10.sp,
    )

    CgCard {
        LabeledStep("players", p.playerCount) { put(p.copy(playerCount = it.coerceAtLeast(1))) }
        LabeledStep("opening hand", p.startingHandSize) { put(p.copy(startingHandSize = it)) }
        LabeledStep("cards drawn per turn", p.cardsDrawnPerTurn) { put(p.copy(cardsDrawnPerTurn = it)) }
        // Nullable: "no limit" is a real and common answer, so it needs to be
        // expressible rather than approximated by a big number.
        CgCheck("discard down to a hand limit", p.maxHandSize != null) {
            put(p.copy(maxHandSize = if (it) 7 else null))
        }
        p.maxHandSize?.let { limit -> LabeledStep("hand limit", limit) { put(p.copy(maxHandSize = it)) } }
        CgCheck("the player going first skips their first draw", p.firstPlayerSkipsFirstDraw) {
            put(p.copy(firstPlayerSkipsFirstDraw = it))
        }
    }

    CgCard {
        Text("resources that survive", color = Cg.accentLight, fontFamily = Cg.mono, fontSize = 10.sp)
        Text(
            "A pool empties at every phase boundary. Both of these change that: a resource " +
                "gained early in a turn can then be spent later in it.",
            color = Cg.dim, fontSize = 10.sp,
        )
        CgCheck("the pool lasts the whole turn", p.poolPersistsPerTurn) {
            put(p.copy(poolPersistsPerTurn = it))
        }
        // A counter NAME rather than a number, deliberately: the cap
        // then belongs to whichever card the player brought, not to the ruleset.
        // Offered as the declared counters, so it cannot name one that does not
        // exist -- the same reasoning as the deck slot pickers.
        val none = "(no store — the pool empties)"
        val opts = listOf(none) + r.playerCounters.map { it.name }
        Text("banked between turns, capped by", color = Cg.dim, fontSize = 10.sp)
        CgPicker(p.poolStoreCounter ?: none, opts) { pick ->
            put(p.copy(poolStoreCounter = pick.takeIf { it != none }))
        }
    }

    CgCard {
        Text("deployment", color = Cg.accentLight, fontFamily = Cg.mono, fontSize = 10.sp)
        Text(
            "By default a body can fight the moment it lands, so a lane is bought and spent " +
                "in one turn. Turning this on is Magic's summoning sickness, generalised: it " +
                "makes deployment a commitment the opponent gets one turn to answer.",
            color = Cg.dim, fontSize = 10.sp,
        )
        CgCheck("a permanent cannot attack the turn it arrives", p.attackDelayOnEntry) {
            put(p.copy(attackDelayOnEntry = it))
        }
    }

    CgCard {
        Text("mulligan", color = Cg.accentLight, fontFamily = Cg.mono, fontSize = 10.sp)
        LabeledStep("redraws allowed", p.mulligan.redraws) {
            put(p.copy(mulligan = p.mulligan.copy(redraws = it)))
        }
        CgCheck("put one card back per mulligan (London)", p.mulligan.bottomOnePerMulligan) {
            put(p.copy(mulligan = p.mulligan.copy(bottomOnePerMulligan = it)))
        }
    }

    CgCard {
        Text("how it wants to be held", color = Cg.dim, fontSize = 10.sp)
        val names = Orientation.entries.map { it.name.lowercase() }
        CgPicker(p.preferredOrientation.name.lowercase(), names) { pick ->
            put(p.copy(preferredOrientation = Orientation.entries.first { it.name.lowercase() == pick }))
        }
    }

    // Reported, never enforced -- the same stance as every other problems()
    // surface in this app.
    p.problems().forEach { Text("· $it", color = Cg.warn, fontFamily = Cg.mono, fontSize = 10.sp) }
}

/** Type a name, add it -- the general escape from a fixed vocabulary; each site
 *  keeps its curated list alongside as suggestions. Blank input is refused (an
 *  empty name is silent corruption). */
@Composable
internal fun NameAdder(what: String, onAdd: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CgMiniField(draft, Modifier.weight(1f)) { draft = it }
        CgAddButton(what) {
            val name = draft.trim()
            if (name.isNotEmpty()) {
                onAdd(name)
                draft = ""
            }
        }
    }
}

/** A named stepper row -- the params screen is a column of them, and a bare
 *  `StepPill` gives no clue which number it is. */
@Composable
private fun LabeledStep(label: String, v: Int, onChange: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Cg.ink2, fontFamily = Cg.mono, fontSize = 11.sp)
        StepPill(v, onChange)
    }
}

@Composable
private fun RulesCountersEditor(vm: CreatorViewModel) {
    val r = vm.game.rules
    Text(
        "Life is just one of these. Declare \"gold\" or \"devotion\" and tick lose-at-zero " +
            "to end the game on it.",
        color = Cg.dim, fontSize = 10.sp,
    )
    r.playerCounters.forEachIndexed { i, c ->
        fun put(nc: PlayerCounterDef) = vm.updateRules { d ->
            d.copy(playerCounters = d.playerCounters.mapIndexed { j, x -> if (j == i) nc else x })
        }
        CgCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CgMiniField(c.name, Modifier.weight(1f)) { put(c.copy(name = it)) }
                StepPill(c.starting) { put(c.copy(starting = it)) }
                if (r.playerCounters.size > 1) {
                    CgDeleteX {
                        vm.updateRules { d -> d.copy(playerCounters = d.playerCounters.filterIndexed { j, _ -> j != i }) }
                    }
                }
            }
            CgCheck("reaching 0 loses the game", c.loseAtZero) { put(c.copy(loseAtZero = it)) }
            // Clamps. Null means unbounded, which is the usual answer.
            CgCheck("has a floor", c.min != null) { put(c.copy(min = if (it) 0 else null)) }
            c.min?.let { m -> LabeledStep("never below", m) { put(c.copy(min = it)) } }
            CgCheck("has a ceiling", c.max != null) { put(c.copy(max = if (it) 20 else null)) }
            c.max?.let { m -> LabeledStep("never above", m) { put(c.copy(max = it)) } }
        }
    }
    CgAddButton("counter") {
        vm.updateRules { it.copy(playerCounters = it.playerCounters + PlayerCounterDef("gold", 0)) }
    }
    Spacer(Modifier.height(6.dp))
    // WHICH counter damage to a player hits ("nothing" = players take no
    // damage).
    Text("damage to a player removes", color = Cg.dim, fontSize = 9.sp, fontFamily = Cg.mono)
    // "nothing": players take no damage -- the loss condition lives on the
    // board (a Station); any card that damages a player is then reported.
    val noDamage = "nothing (players take no damage)"
    CgPicker(r.damageCounter ?: noDamage, r.playerCounters.map { it.name } + noDamage) { pick ->
        vm.updateRules { it.copy(damageCounter = pick.takeIf { it != noDamage }) }
    }
    Spacer(Modifier.height(10.dp))
    RulesCounterKindsEditor(vm)
}

/** The counter kinds cards may put on permanents. Every counter a card
 *  names must be one of these or a builtin, or the game reports it -- a
 *  misspelt kind would otherwise read 0 forever, silently. */
@Composable
private fun RulesCounterKindsEditor(vm: CreatorViewModel) {
    val kinds = vm.game.rules.counterKinds
    Text("COUNTERS ON CARDS", color = Cg.dim, fontSize = 9.sp, fontFamily = Cg.mono)
    Text(
        "Built in: ${BUILTIN_COUNTER_KINDS.joinToString(", ")} (+1/+1 cancels -1/-1). Declare any other kind your cards use " +
            "(shield, charge …); a card naming one not listed here is reported. A kind that cancels another " +
            "removes one of each while a card carries both.",
        color = Cg.dim, fontSize = 10.sp,
    )
    if (kinds == null) {
        // Only a game nobody has saved since declarations existed; Save
        // declares what the cards already use. Offered here too, so the
        // author can see the list before saving.
        Text("Not declared yet — Save declares the kinds your cards already use.", color = Cg.warn, fontSize = 10.sp)
        CgAddButton("declare them now") {
            vm.updateRules { it.copy(counterKinds = vm.game.usedCounterKinds().map { k -> CounterKindDef(k) }) }
        }
        return
    }
    kinds.forEachIndexed { i, k ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CgMiniField(k.name, Modifier.weight(1f)) { name ->
                vm.updateRules { d -> d.copy(counterKinds = d.counterKinds?.mapIndexed { j, x -> if (j == i) x.copy(name = name) else x }) }
            }
            // the kind this one annihilates with, as +1/+1 does -1/-1.
            val noCancel = "cancels nothing"
            CgPicker(
                k.cancels?.let { "cancels $it" } ?: noCancel,
                listOf(noCancel) + (BUILTIN_COUNTER_KINDS + kinds.map { it.name }).distinct().filter { it != k.name }.map { "cancels $it" },
            ) { pick ->
                val c = pick.takeIf { it != noCancel }?.removePrefix("cancels ")
                vm.updateRules { d -> d.copy(counterKinds = d.counterKinds?.mapIndexed { j, x -> if (j == i) x.copy(cancels = c) else x }) }
            }
            CgDeleteX {
                vm.updateRules { d -> d.copy(counterKinds = d.counterKinds?.filterIndexed { j, _ -> j != i }) }
            }
        }
    }
    CgAddButton("counter kind") {
        vm.updateRules { d -> d.copy(counterKinds = (d.counterKinds ?: emptyList()) + CounterKindDef("charge")) }
    }
}

@Composable
private fun RulesTurnEditor(vm: CreatorViewModel) {
    val r = vm.game.rules
    Text(
        "The turn `playGame` folds over. Reorder with ▲▼; 'on enter' is an effect run for each player the phase acts for -- untap, draw and cleanup are presets.",
        color = Cg.dim, fontSize = 10.sp,
    )
    // Shared-turn step 3: the mode is authorable, so a game can declare a
    // shared round without touching Kotlin -- which is what Cycle B needs,
    // since that game gets built in the app rather than in code.
    CgInlineField("mode") {
        CgPicker(
            if (r.turn.mode == TurnMode.SHARED) "shared round" else "one turn each",
            listOf("one turn each", "shared round"),
        ) { pick ->
            vm.updateRules { d ->
                d.copy(turn = d.turn.copy(mode = if (pick == "shared round") TurnMode.SHARED else TurnMode.PER_PLAYER))
            }
        }
    }
    Text(
        if (r.turn.mode == TurnMode.SHARED) {
            "SHARED: ONE round both players are in. The phase list runs once, its auto-work " +
                "(untap/draw/cleanup) applies to EVERYONE, each player gets their own phase " +
                "trigger, and in a sorcery-speed phase the two sides ALTERNATE single actions " +
                "until both pass. `activePlayer` means whoever holds the INITIATIVE, which " +
                "passes at the end of the round — and, in declared/individual combat, is who " +
                "declares attackers."
        } else {
            "ONE TURN EACH: the active player takes the whole phase list privately; the other " +
                "may only respond at instant speed. Then they swap."
        },
        color = Cg.dim, fontSize = 9.sp, fontFamily = Cg.mono,
    )
    r.turn.problems(r.params).forEach { p ->
        Text("⚠  $p", color = Cg.warn, fontFamily = Cg.mono, fontSize = 10.sp)
    }
    r.turn.phases.forEachIndexed { i, ph ->
        fun put(np: PhaseSpec) = vm.updateRules { d ->
            d.copy(turn = d.turn.copy(phases = d.turn.phases.mapIndexed { j, x -> if (j == i) np else x }))
        }
        fun move(delta: Int) = vm.updateRules { d ->
            val list = d.turn.phases.toMutableList()
            val to = i + delta
            if (to in list.indices) list.add(to, list.removeAt(i))
            d.copy(turn = d.turn.copy(phases = list))
        }
        CgCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CgMiniField(ph.name, Modifier.weight(1f)) { put(ph.copy(name = it)) }
                Text("▲", color = Cg.accentLight, fontSize = 12.sp, modifier = Modifier.clickable { move(-1) }.padding(4.dp))
                Text("▼", color = Cg.accentLight, fontSize = 12.sp, modifier = Modifier.clickable { move(1) }.padding(4.dp))
                CgDeleteX {
                    vm.updateRules { d -> d.copy(turn = d.turn.copy(phases = d.turn.phases.filterIndexed { j, _ -> j != i })) }
                }
            }
            // a phase's work is an effect, run for each player the phase
            // acts for ("you" = that player). The standard three are presets;
            // anything else is written in the same editor as a card's effect.
            val presets = listOf("none" to Effect.NoOp, "untap" to PhaseEffects.UNTAP, "draw" to PhaseEffects.DRAW, "cleanup" to PhaseEffects.CLEANUP)
            val current = presets.firstOrNull { it.second == ph.onEnter }?.first ?: "custom"
            CgInlineField("on enter") {
                CgPicker(current, presets.map { it.first } + "custom") { s ->
                    presets.firstOrNull { it.first == s }?.let { put(ph.copy(onEnter = it.second)) }
                }
            }
            EffectSection(ph.onEnter.takeIf { it != Effect.NoOp }, addLabel = "＋ phase effect") { e ->
                put(ph.copy(onEnter = e ?: Effect.NoOp))
            }
            CgCheck("priority window", ph.interactive) { put(ph.copy(interactive = it)) }
            CgCheck("runs combat", ph.combat) { put(ph.copy(combat = it)) }
            CgCheck("sorcery-speed window", ph.sorcerySpeed) { put(ph.copy(sorcerySpeed = it)) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CgAddButton("phase") {
            vm.updateRules { d ->
                val taken = d.turn.phases.map { it.name }.toSet()
                val name = generateSequence(1) { it + 1 }.map { "phase$it" }.first { it !in taken }
                d.copy(turn = d.turn.copy(phases = d.turn.phases + PhaseSpec(name)))
            }
        }
        if (r.turn != TurnStructure.MTG) {
            CgButton("↺ reset to MTG") { vm.updateRules { it.copy(turn = TurnStructure.MTG) } }
        }
    }
}

@Composable
private fun RulesCombatEditor(vm: CreatorViewModel) {
    val doc = vm.game.rules.combat
    fun put(c: CombatDoc) = vm.updateRules { it.copy(combat = c) }

    // The preset row: the fast path. A preset is saved by NAME and follows the
    // library; "make it this game's own" copies its program in, to edit.
    CgInlineField("preset") {
        CgPicker(
            (doc as? CombatDoc.Preset)?.name ?: "this game's own",
            ccg.COMBAT_PRESETS.keys.toList() + listOf("this game's own"),
        ) { p -> if (p in ccg.COMBAT_PRESETS) put(CombatDoc.Preset(p)) }
    }

    when (doc) {
        is CombatDoc.Preset -> {
            Text(presetBlurb(doc.name), color = Cg.ink2, fontSize = 11.sp)
            // Copying keeps the preset's program, so the author starts from
            // something that already works -- and sees what it is made of.
            CgButton("make it this game's own (edit its program)") { put(CombatDoc.Program(doc.compile())) }
        }

        is CombatDoc.Program -> {
            // Combat is a program in the effect language: the same verbs,
            // filters and conditions a card uses, plus the combat verbs.
            val c = doc.combat
            fun putCombat(nc: ccg.Combat) = put(CombatDoc.Program(nc))
            CgCard {
                Text("a combat phase runs", color = Cg.dim, fontSize = 9.sp, fontFamily = Cg.mono)
                EffectSection(c.program, addLabel = "＋ what combat does") { putCombat(c.copy(program = it)) }
            }
            CgCard {
                Text("each attack, made with priority, runs", color = Cg.dim, fontSize = 9.sp, fontFamily = Cg.mono)
                Text(
                    "Only for games where one attack resolves at a time. Leave it empty where attacks are declared in combat.",
                    color = Cg.ink2, fontSize = 10.sp,
                )
                EffectSection(c.attack, addLabel = "＋ what one attack does") { putCombat(c.copy(attack = it)) }
            }
            CgButton("↺  back to a preset (mtg)") { put(CombatDoc.Preset("mtg")) }
        }
    }
}

@Composable
private fun RulesResourcesEditor(vm: CreatorViewModel) {
    val r = vm.game.rules
    Text(
        "How a player comes by what pays costs. The pool empties each phase; this is what refills it.",
        color = Cg.dim, fontSize = 10.sp,
    )
    val kinds = listOf("from cards only", "gain per turn (ramp)", "play a resource card per turn")
    val current = when (r.resourceModel) {
        ResourceModel.None -> kinds[0]
        is ResourceModel.Ramp -> kinds[1]
        is ResourceModel.CardDriven -> kinds[2]
    }
    CgPicker(current, kinds) { k ->
        vm.updateRules {
            it.copy(
                resourceModel = when (k) {
                    kinds[1] -> ResourceModel.Ramp()
                    kinds[2] -> ResourceModel.CardDriven()
                    else -> ResourceModel.None
                },
            )
        }
    }
    when (val m = r.resourceModel) {
        ResourceModel.None -> {}
        is ResourceModel.Ramp -> {
            fun put(nm: ResourceModel) = vm.updateRules { it.copy(resourceModel = nm) }
            CgInlineField("gained per turn") { CgStepper(m.perTurn, min = 0) { put(m.copy(perTurn = it)) } }
            CgInlineField("cap") { CgStepper(m.cap, min = 0) { put(m.copy(cap = it)) } }
            CgCheck("refill the pool each turn", m.refillEachTurn) { put(m.copy(refillEachTurn = it)) }
            CgInlineField("start with") { CgStepper(m.startingAmount, min = 0) { put(m.copy(startingAmount = it)) } }
            // A TYPED resource key ("energy") rather than generic mana. Blank
            // is generic, which is what every existing game means.
            CgInlineField("resource key") {
                CgMiniField(m.key, Modifier.width(90.dp)) { put(m.copy(key = it)) }
            }
        }
        is ResourceModel.CardDriven -> {
            fun put(nm: ResourceModel) = vm.updateRules { it.copy(resourceModel = nm) }
            CgInlineField("plays per turn") { CgStepper(m.playsPerTurn, min = 0) { put(m.copy(playsPerTurn = it)) } }
            CgField("resource types (space separated)", m.types.joinToString(" ")) { t ->
                put(m.copy(types = t.split(" ").map { it.trim() }.filter { it.isNotEmpty() }.toSet()))
            }
        }
    }
}

@Composable
private fun RulesDeckEditor(vm: CreatorViewModel, onNavigate: (Module) -> Unit) {
    val b = vm.game
    val dr = b.deckRules
    val vocab = LocalVocab.current
    fun put(nd: DeckRules) = vm.updateGame { it.copy(deckRules = nd) }

    Text(
        "Legality is REPORTED (on the game header and the Decks screen), never enforced mid-game. " +
            "Slot cards — Leader / Base / Hero — are assigned per deck on the Decks screen.",
        color = Cg.dim, fontSize = 10.sp,
    )

    // Presets are a STARTING POINT; sizes, slots and identity are editable below.
    val presets = listOf("none", "mtg", "hearthstone", "swu")
    CgInlineField("start from") {
        CgPicker(deckRulesPresetName(dr), presets + listOf("custom")) { p ->
            put(
                when (p) {
                    "none" -> DeckRules()
                    "mtg" -> DeckRules.MTG
                    "hearthstone" -> DeckRules.HEARTHSTONE
                    "swu" -> DeckRules.SWU
                    else -> return@CgPicker
                },
            )
        }
    }

    CgCard {
        LabeledStep("minimum cards", dr.minSize) { put(dr.copy(minSize = it)) }
        // Both nullable, and "no limit" is the right answer for a game that
        // does not care -- so a toggle, not a sentinel number.
        CgCheck("has a maximum size", dr.maxSize != null) {
            put(dr.copy(maxSize = if (it) dr.minSize.coerceAtLeast(40) else null))
        }
        dr.maxSize?.let { m -> LabeledStep("maximum cards", m) { put(dr.copy(maxSize = it)) } }
        CgCheck("limits copies of one card", dr.maxCopies != null) {
            put(dr.copy(maxCopies = if (it) 4 else null))
        }
        dr.maxCopies?.let { m -> LabeledStep("copies allowed", m) { put(dr.copy(maxCopies = it)) } }
    }

    // -- slots: designated cards outside the deck ---------------------------
    // SWU's Leader + Base, Hearthstone's Hero, EPR Skirmish's Station / Leader /
    // Flagship pool: one shape.
    Text(
        "slots — cards a deck names but does not shuffle in",
        color = Cg.dim, fontSize = 9.sp, fontFamily = Cg.mono,
    )
    dr.slots.forEachIndexed { i, s ->
        fun putSlot(ns: DeckSlotDef) =
            put(dr.copy(slots = dr.slots.mapIndexed { j, x -> if (j == i) ns else x }))
        CgCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CgMiniField(s.name, Modifier.weight(1f)) { putSlot(s.copy(name = it)) }
                StepPill(s.count) { putSlot(s.copy(count = it.coerceAtLeast(1))) }
                CgDeleteX { put(dr.copy(slots = dr.slots.filterIndexed { j, _ -> j != i })) }
            }
            Text("which types may fill it (none = any)", color = Cg.dim, fontSize = 9.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (vocab.types + s.types.filter { it !in vocab.types }).forEach { ty ->
                    Chip(ty, ty in s.types) {
                        putSlot(s.copy(types = if (ty in s.types) s.types - ty else s.types + ty))
                    }
                }
            }
            // WHERE the card starts. A declared hidden zone (EPRS's Flagship
            // pool) is as valid an answer as the battlefield, so this offers
            // the game's own zones rather than assuming a board.
            val nowhere = "(stays out of play)"
            CgInlineField("starts in") {
                CgPicker(s.startsIn.ifBlank { nowhere }, listOf(nowhere) + vocab.zones) { z ->
                    putSlot(s.copy(startsIn = if (z == nowhere) "" else z))
                }
            }
        }
    }
    CgAddButton("slot") { put(dr.copy(slots = dr.slots + DeckSlotDef("Leader", count = 1))) }

    // -- identity ------------------------------------------------------------
    Spacer(Modifier.height(4.dp))
    Text(
        "identity — what a deck's slot cards let it play",
        color = Cg.dim, fontSize = 9.sp, fontFamily = Cg.mono,
    )
    val id = dr.identity
    if (id == null) {
        CgAddButton("identity rule") { put(dr.copy(identity = IdentityRule())) }
    } else {
        CgCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("read off these slots", color = Cg.ink2, fontFamily = Cg.mono, fontSize = 11.sp)
                CgDeleteX { put(dr.copy(identity = null)) }
            }
            if (dr.slots.isEmpty()) {
                Text("Add a slot above first — identity is read off slot cards.", color = Cg.dim, fontSize = 10.sp)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                dr.slots.forEach { s ->
                    Chip(s.name, s.name in id.from) {
                        put(
                            dr.copy(
                                identity = id.copy(
                                    from = if (s.name in id.from) id.from - s.name else id.from + s.name,
                                ),
                            ),
                        )
                    }
                }
            }
            // An EMPTY vocabulary means "no identity system" and the check is
            // skipped -- said out loud so it does not read as a bug.
            Text(
                "vocabulary — which keywords count as identity. Empty means this game has " +
                    "no identity system and the check is skipped.",
                color = Cg.dim, fontSize = 9.sp,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (vocab.keywords + id.vocabulary.filter { it !in vocab.keywords }).forEach { kw ->
                    Chip(kw, kw in id.vocabulary) {
                        put(
                            dr.copy(
                                identity = id.copy(
                                    vocabulary = if (kw in id.vocabulary) id.vocabulary - kw else id.vocabulary + kw,
                                ),
                            ),
                        )
                    }
                }
            }
            NameAdder("term") { name -> put(dr.copy(identity = id.copy(vocabulary = id.vocabulary + name))) }
            CgCheck("cards with no identity are always legal", id.allowNeutral) {
                put(dr.copy(identity = id.copy(allowNeutral = it)))
            }
        }
    }

    if (dr.slots.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        CgButton("assign slot cards in Decks  →") { onNavigate(Module.DECKS) }
    }
}

internal fun deckRulesPresetName(d: DeckRules): String = when (d) {
    DeckRules() -> "none"
    DeckRules.MTG -> "mtg"
    DeckRules.HEARTHSTONE -> "hearthstone"
    DeckRules.SWU -> "swu"
    else -> "custom"
}

internal val RulesModule = ModuleSpec(
    Module.RULES, CgIconKind.RULES,
    badge = { vm -> if (vm.game.problems().isNotEmpty()) CgDot.WARN else CgDot.NONE },
) {
    val f = route.focus as? Focus.Section
    val list: @Composable () -> Unit = { RulesScreen(vm) { section -> openDetail(Module.RULES, Focus.Section(section.name)) } }
    val section: (@Composable () -> Unit)? = f?.let { { RulesSectionScreen(vm, rulesSection(it)) { m -> push(m) } } }
    when {
        // Landscape: the sections stay beside the one open.
        twoPane -> TwoPanes(list = list, detail = section, empty = "Pick a section to edit it.")
        section != null -> section()
        else -> list()
    }
}
