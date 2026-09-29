package com.ccg

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import ccgui.CardArea
import ccgui.diagnosticsByArea
import ccgui.locate
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ccg.Keyword
import ccg.ActivatedAbility
import ccg.permanents
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import ccg.CmpOp
import ccg.BoolExpr
import ccg.fieldsForTypes
import ccgui.editableFields
import ccg.CardDoc
import ccg.Diagnostic
import ccg.CardLayout
import ccg.stepNames
import ccg.layoutFor
import ccg.Cost
import ccg.CounterDef
import ccg.HiddenZone
import ccg.Recast
import ccg.zoneName
import ccgui.HIDDEN_ZONES
import ccgui.zoneOfName
import ccg.Effect
import ccg.FaceDoc
import ccg.IntExpr
import ccg.SELF
import ccg.PlayerRef
import ccg.build
import ccg.lit
import ccgui.costSummary
import ccgui.ruleboxOf
import ccgui.poolReport
import ccgui.sections
import ccg.Rules
import androidx.compose.runtime.setValue
import ccgui.Module
import ccgui.Focus

// ---------------------------------------------------------------------------
// The Cards module: the set list, the card editor, and the Pool view.
// ---------------------------------------------------------------------------

private val CARDS_VIEWS = listOf("Cards", "Pool")

/** The sets of a game, and the cards of the selected set. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetsScreen(vm: CreatorViewModel, store: GameStore, onOpenCard: () -> Unit) {
    val g = vm.game
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("sets", color = Cg.dim, fontFamily = Cg.mono, fontSize = 9.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            g.sets.forEachIndexed { i, st ->
                Chip("${st.name} (${st.cards.size})", i == vm.setIndex) { vm.selectSet(i) }
            }
            CgAddButton("set") { vm.addSet() }
        }
        CgField("set name", vm.set.name) { n -> vm.updateGame { it.updateSet(vm.setIndex) { s -> s.copy(name = n) } } }
        if (g.sets.size > 1) CgButton("remove this set") { vm.removeSet(vm.setIndex) }

        Text("cards in ${vm.set.name}", color = Cg.dim, fontFamily = Cg.mono, fontSize = 9.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            vm.set.cards.forEachIndexed { i, c ->
                val f = c.faces[0]
                val layout = vm.game.rules.layoutFor(f.types)
                Box {
                    CgCardTile(
                        name = f.name,
                        typeLabel = f.types.joinToString(" ").ifEmpty { "—" },
                        costLabel = costSummary(c.cost).takeIf { !c.cost.isFree },
                        pt = statLabel(f, layout),
                        text = c.text,
                        onClick = { vm.select(i); onOpenCard() },
                        layout = layout,
                        art = rememberCardArt(store, vm.game.id, f.art),
                        // Thumbnails take the thumbnail framing.
                        artRect = ccgui.rectFor(f, compact = true),
                        counters = c.entersWith.associate { it.kind to ((it.initial as? IntExpr.Lit)?.value ?: 0) },
                        compact = true,
                    )
                    if (vm.set.cards.size > 1) {
                        Box(Modifier.align(Alignment.TopEnd)) { CgDeleteX { vm.deleteCard(i) } }
                    }
                }
            }
        }
        CgAddButton("card") { vm.addCard(); onOpenCard() }
        Spacer(Modifier.height(24.dp))
    }
}

/** The behaviour blocks a card can OPT INTO -- shown only once added or once
 *  the loaded card already has content for them. */
private enum class CardBlock(val label: String, val area: CardArea) {
    CAST("Cast effect", CardArea.CAST),
    TRIGGERS("Triggers", CardArea.TRIGGERS),
    STATICS("Static abilities", CardArea.STATICS),
    ACTIVATED("Activated abilities", CardArea.ACTIVATED),
    ENTERS("Enters-with counters", CardArea.ENTERS),
    RECAST("Cast from another zone", CardArea.RECAST),
    REPLACEMENTS("Replacement effects", CardArea.REPLACEMENTS),
}

private fun CardBlock.hasContent(card: CardDoc, face: FaceDoc): Boolean = when (this) {
    CardBlock.CAST -> face.castEffect != null
    CardBlock.TRIGGERS -> face.triggers.isNotEmpty()
    // Cost modifiers live in Statics too.
    CardBlock.STATICS -> face.statics.isNotEmpty() || face.ruleMods.isNotEmpty() || face.costMods.isNotEmpty()
    CardBlock.ACTIVATED -> face.activated.isNotEmpty()
    CardBlock.ENTERS -> card.entersWith.isNotEmpty()
    CardBlock.RECAST -> card.recast != null
    CardBlock.REPLACEMENTS -> face.replacements.isNotEmpty()
}

@Composable
private fun SectionCard(title: String, summary: String? = null, content: @Composable ColumnScope.() -> Unit) {
    CgCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                title.uppercase(), color = Cg.accentLight, fontFamily = Cg.mono,
                fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
            )
            if (summary != null) Text(summary, color = Cg.dim, fontFamily = Cg.mono, fontSize = 10.sp)
        }
        content()
    }
}

/** The stat-box string for a face under a layout: its `statFields`, "/"-joined,
 *  else the classic power/toughness. */
internal fun statLabel(face: FaceDoc, layout: CardLayout): String? =
    layout.statFields.mapNotNull { face.fields[it] }.takeIf { it.isNotEmpty() }?.joinToString("/")
        ?: face.fields["power"]?.let { "$it/${face.fields["toughness"] ?: 0}" }

/** The always-pinned preview: the card as it will read, in its type's layout. */
@Composable
private fun CardPreview(vm: CreatorViewModel, store: GameStore) {
    val card = vm.card
    val face = vm.face
    val img = rememberCardArt(store, vm.game.id, face.art, maxPx = ART_PX_EDIT)
    var studio by remember { mutableStateOf(false) }
    // The art box is one AFFORDANCE: tap it and the art modal opens, where
    // everything about the picture (choose, crop, replace, remove) happens. A
    // tile is too small to author in.
    val artTap = Modifier.pointerInput(Unit) { detectTapGestures { studio = true } }
    val layout = remember(vm.game.rules.extraTypes, face.types) { vm.game.rules.layoutFor(face.types) }
    CgCardTile(
        name = face.name,
        typeLabel = face.types.joinToString(" ").ifEmpty { "—" },
        costLabel = costSummary(card.cost).takeIf { !card.cost.isFree },
        pt = statLabel(face, layout),
        text = card.text,
        layout = layout,
        art = img,
        // The preview is the FIELD tile -- the card as it sits in play.
        artRect = face.artFieldRect,
        artModifier = artTap,
        counters = card.entersWith.associate { it.kind to ((it.initial as? IntExpr.Lit)?.value ?: 0) },
    )
    Text(
        if (face.art == null) {
            "tap the art box to choose an image · it is copied into this game's folder, so a bare " +
                ".json shared without it shows the ◈ glyph"
        } else {
            "tap the art box to crop or replace"
        },
        color = Cg.dim, fontSize = 10.sp,
    )
    CompiledRulebox(card, vm.game.compile().rules)
    if (studio) {
        CgArtStudio(store, vm.game.id, face, onFace = { f -> vm.updateFace { f } }) { studio = false }
    }
}

/** What the card ACTUALLY does (the compiled rulebox), beside what its text
 *  claims. Where they disagree one is a bug; an EMPTY rulebox under authored
 *  text is the loudest signal of all. */
@Composable
private fun CompiledRulebox(card: CardDoc, rules: Rules) {
    val lines = remember(card, rules) { runCatching { ruleboxOf(card, rules) }.getOrElse { emptyList() } }
    Spacer(Modifier.height(6.dp))
    SectionCard("compiled rulebox", if (lines.isEmpty()) "does nothing" else "${lines.size} line(s)") {
        if (lines.isEmpty()) {
            Text(
                "This card compiles to no behaviour at all.",
                color = Cg.warn, fontFamily = Cg.mono, fontSize = 10.sp,
            )
        } else {
            lines.forEach { l ->
                Text("· $l", color = Cg.ink2, fontFamily = Cg.mono, fontSize = 10.sp)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardsTab(
    vm: CreatorViewModel,
    store: GameStore,
    /** Put this card into the game on the table ("Try it"). */
    onTry: (String) -> Unit,
) {
    val face = vm.face
    val card = vm.card
    // Read here, not in a click handler -- a CompositionLocal read inside an
    // onClick is not a composable call site and fails only at APK build.
    val counterVocab = LocalVocab.current.counters
    // Blocks the user explicitly added this session -- reset when the card changes.
    var added by remember(vm.selected, vm.setIndex) { mutableStateOf(emptySet<CardBlock>()) }
    val shown = CardBlock.entries.filter { it in added || it.hasContent(card, face) }
    val addable = CardBlock.entries.filter { it !in shown }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // -- TYPE FILTER over the card list ---------------------------------
        // By the GAME's own types ("the Ships", "the Manoeuvres"). A view
        // preference in a `remember` (never makes the game dirty), KEYED ON THE
        // SET: a filter surviving into a set without that type would show an
        // empty list with no chip to clear it.
        var typeFilter by remember(vm.setIndex) { mutableStateOf<String?>(null) }
        val setTypes = remember(vm.set.cards) {
            vm.set.cards.flatMap { it.faces.flatMap { f -> f.types } }.distinct().sorted()
        }
        // And belt-and-braces for the case keying cannot reach: the last card
        // of a type being deleted or retyped WHILE that filter is active.
        if (typeFilter != null && typeFilter !in setTypes) typeFilter = null
        if (setTypes.size > 1) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Chip("all ${vm.set.cards.size}", typeFilter == null) { typeFilter = null }
                setTypes.forEach { ty ->
                    val n = vm.set.cards.count { c -> c.faces.any { ty in it.types } }
                    Chip("$ty $n", typeFilter == ty) { typeFilter = if (typeFilter == ty) null else ty }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // withIndex(), NOT forEachIndexed on a filtered list: `vm.select`
            // takes the index into the REAL card list, so filtering first and
            // indexing after would select the wrong card -- silently, and only
            // once a filter was active.
            vm.set.cards.withIndex()
                .filter { (_, c) -> typeFilter == null || c.faces.any { typeFilter in it.types } }
                .forEach { (i, c) ->
                    Chip(c.faces[0].name, i == vm.selected) { vm.select(i) }
                }
            CgAddButton("card") { vm.addCard() }
        }

        CardPreview(vm, store)
        // Straight onto the table, in your hand, as a sandbox edit: the card
        // as it is now, unsaved edits included (Play runs the working copy).
        CgButton("✦  Try it on the table") { onTry(card.key()) }

        // -- HEAD: always visible --
        SectionCard("Identity") {
            // WHICH FACE -- a row that appears only once a card has two.
            if (card.faces.size > 1) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    card.faces.forEachIndexed { i, f ->
                        Chip(f.name.ifBlank { "face ${i + 1}" }, i == vm.faceIndex) { vm.selectFace(i) }
                    }
                    CgDeleteX { vm.removeFace(vm.faceIndex) }
                }
            }
            CgField("name", face.name) { n -> vm.updateFace { it.copy(name = n) } }
            Text("types", color = Cg.dim, fontSize = 9.sp, fontFamily = Cg.mono)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (BUILTIN_TYPE_NAMES + vm.game.rules.extraTypes.map { it.name }).forEach { t ->
                    Chip(t, t in face.types) {
                        vm.updateFace { it.copy(types = if (t in it.types) it.types - t else it.types + t) }
                    }
                }
            }
            CgField("rules text", card.text, mono = true) { t -> vm.updateCardDoc { it.copy(text = t) } }
            CgAddButton("face") { vm.addFace() }

            // -- the two card-level gates -------------------------------------
            // `requires` is a PLAY-TIME precondition legality() enforces, which
            // is what stops an attaching Improvement being played with no host.
            CgInlineField("only playable if") {
                if (card.requires == null) {
                    CgAddButton("condition") { vm.updateCardDoc { it.copy(requires = permanents().yours()) } }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterPill(card.requires!!) { f -> vm.updateCardDoc { it.copy(requires = f) } }
                        CgDeleteX { vm.updateCardDoc { it.copy(requires = null) } }
                    }
                }
            }
            // A per-CARD dies-when, merged with its type's in the SBA -- a Saga
            // that ends at its last chapter, a unit that dies at zero fuel.
            CgInlineField("dies when") {
                if (card.diesWhen == null) {
                    CgAddButton("condition") {
                        val kind = counterVocab.firstOrNull() ?: "hull"
                        vm.updateCardDoc {
                            it.copy(diesWhen = BoolExpr.Cmp(IntExpr.SelfCounter(kind), CmpOp.LTE, IntExpr.Lit(0)))
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        BoolPill(card.diesWhen!!) { b -> vm.updateCardDoc { it.copy(diesWhen = b) } }
                        CgDeleteX { vm.updateCardDoc { it.copy(diesWhen = null) } }
                    }
                }
            }
        }

        SectionCard("Body: fields & keywords") {
            // The stat rows: what the card's TYPES declare, plus any field the
            // card carries (kept editable if its type stops declaring it).
            val declared = vm.game.rules.fieldsForTypes(face.types)
            val shown = editableFields(declared, face.fields.keys, BODY_FIELDS)
            if (shown.isEmpty()) {
                Text(
                    "This card's types declare no fields. Add some in Rules ▸ Types & fields, " +
                        "or add one here just for this card.",
                    color = Cg.dim, fontSize = 10.sp,
                )
            }
            shown.forEach { k ->
                CgInlineField(k) {
                    if (k in face.fields) {
                        CgStepper(face.fields.getValue(k)) { v -> vm.updateFace { it.setField(k, v) } }
                        CgDeleteX { vm.updateFace { it.clearField(k) } }
                    } else {
                        CgAddButton(k) {
                            vm.updateFace { it.setField(k, if (k == "power" || k == "toughness") 1 else 2) }
                        }
                    }
                }
            }
            NameAdder("field") { name -> vm.updateFace { it.setField(name, 1) } }
            Spacer(Modifier.height(2.dp))
            Text("keywords", color = Cg.dim, fontSize = 9.sp, fontFamily = Cg.mono)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // The vocabulary is the keywords this game gives a rule to plus
                // those harvested from its cards and anything typed below --
                // unlike types and fields, keywords have no declaration site,
                // so they are gathered rather than declared.
                val kws = LocalVocab.current.keywords.ifEmpty { Keyword.allNames.toList() } +
                    face.keywords.filter { it !in LocalVocab.current.keywords }
                kws.forEach { kw -> Chip(kw, kw in face.keywords) { vm.updateFace { it.toggleKeyword(kw) } } }
            }
            NameAdder("keyword") { name -> vm.updateFace { it.toggleKeyword(name) } }
        }

        SectionCard("Cost", summary = costSummary(card.cost)) {
            CostEditor(card.cost) { c -> vm.updateCardDoc { it.copy(cost = c) } }
        }

        // Art is set, replaced and framed from the art box in the preview; the
        // controls beneath it clear or reset.

        // -- progressive: add a behaviour block --
        if (addable.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("behaviour", color = Cg.dim, fontFamily = Cg.mono, fontSize = 9.sp)
                MenuPill("＋ add", addable.map { it.label }) { picked ->
                    CardBlock.entries.firstOrNull { it.label == picked }?.let { added = added + it }
                }
            }
        }

        // this face's diagnostics, each shown in the block its path
        // names. The face's own fields have no block, so theirs go here.
        val issues = diagnosticsByArea(vm.game.diagnostics(), card, vm.faceIndex)
        issues[CardArea.FACE]?.let { IssueLines(it) }
        shown.forEach { block ->
            CardBlockEditor(vm, block, issues[block.area].orEmpty()) { added = added - block }
        }

        // -- compiles-to footer --
        // Through the game's compile, so it shows what the engine runs.
        vm.game.compile().rules.cards[card.key()]?.let { built ->
            Text(
                "compiles to  ·  ${built.name}  ·  ${built.types.joinToString()}" +
                    (built.faces[0].baseChars?.let { bc -> "  ·  " + bc.fields.entries.joinToString(" ") { "${it.key} ${it.value}" } } ?: "") +
                    (if (built.entersWith.isNotEmpty()) "  ·  enters " + built.entersWith.joinToString { "${it.kind} ${(it.initial as? IntExpr.Lit)?.value ?: "×"}" } else ""),
                color = Cg.dim, fontFamily = Cg.mono, fontSize = 10.sp,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardBlockEditor(vm: CreatorViewModel, block: CardBlock, issues: List<Diagnostic>, onRemove: () -> Unit) {
    val face = vm.face
    fun removeBlock() {
        when (block) {
            CardBlock.CAST -> vm.updateFace { it.copy(castEffect = null) }
            CardBlock.TRIGGERS -> vm.updateFace { it.copy(triggers = emptyList()) }
            CardBlock.STATICS -> vm.updateFace { it.copy(statics = emptyList(), ruleMods = emptyList(), costMods = emptyList()) }
            CardBlock.ACTIVATED -> vm.updateFace { it.copy(activated = emptyList()) }
            CardBlock.ENTERS -> vm.updateCardDoc { it.copy(entersWith = emptyList()) }
            CardBlock.RECAST -> vm.updateCardDoc { it.copy(recast = null) }
            CardBlock.REPLACEMENTS -> vm.updateFace { it.copy(replacements = emptyList()) }
        }
        onRemove()
    }
    CgGroup(if (issues.isEmpty()) block.label else "⚠ ${block.label}", initiallyOpen = true, onDelete = { removeBlock() }) {
        if (issues.isNotEmpty()) IssueLines(issues)
        when (block) {
            CardBlock.CAST ->
                CastEffectEditor(face.castEffect) { e -> vm.updateFace { it.copy(castEffect = e) } }
            CardBlock.TRIGGERS ->
                TriggerEditor(face.triggers) { ts -> vm.updateFace { it.copy(triggers = ts) } }
            CardBlock.STATICS ->
                StaticEditor(
                    statics = face.statics,
                    ruleMods = face.ruleMods,
                    costMods = face.costMods,
                    onStatics = { s -> vm.updateFace { it.copy(statics = s) } },
                    onRuleMods = { r -> vm.updateFace { it.copy(ruleMods = r) } },
                    onCostMods = { c -> vm.updateFace { it.copy(costMods = c) } },
                )
            CardBlock.ACTIVATED -> CardActivatedEditor(vm)
            CardBlock.ENTERS -> CardEntersEditor(vm)
            CardBlock.RECAST -> CardRecastEditor(vm)
            CardBlock.REPLACEMENTS -> ReplacementEditor(
                replacements = face.replacements,
                // compile() is a pure, cheap fold over the doc -- no ruleset build,
                // so this cannot throw mid-frame the way a rules() call could.
                steps = vm.game.rules.combat.compile().stepNames().toList(),
            ) { rs -> vm.updateFace { it.copy(replacements = rs) } }
        }
    }
}

/** Diagnostics shown where they apply. */
@Composable
private fun IssueLines(issues: List<Diagnostic>) {
    issues.forEach { Text("⚠  ${it.message}", color = Cg.warn, fontFamily = Cg.mono, fontSize = 10.sp) }
}

@Composable
private fun CardActivatedEditor(vm: CreatorViewModel) {
    val face = vm.face
    Text(
        "Pay a cost, get an effect. This is how a card becomes a resource source — " +
            "e.g. \"{T}: you add 1 generic\" — which is what makes other cards' costs payable.",
        color = Cg.dim, fontSize = 10.sp,
    )
    face.activated.forEachIndexed { i, ab ->
        fun put(na: ActivatedAbility) = vm.updateFace { f ->
            f.copy(activated = f.activated.mapIndexed { j, x -> if (j == i) na else x })
        }
        CgCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CgMiniField(ab.name, Modifier.weight(1f)) { put(ab.copy(name = it)) }
                CgDeleteX {
                    vm.updateFace { f -> f.copy(activated = f.activated.filterIndexed { j, _ -> j != i }) }
                }
            }
            CgCheck("once per turn (loyalty-style)", ab.oncePerTurn) { put(ab.copy(oncePerTurn = it)) }
            // Same wording as the type-level control in the Rules tab,
            // because it is the same axis -- a reader who has met one should
            // not have to work out whether the other means something else.
            CgCheck("uses the stack (opponents get a window)", ab.usesStack) { put(ab.copy(usesStack = it)) }
            Text("cost: ${costSummary(ab.cost)}", color = Cg.dim, fontSize = 9.sp, fontFamily = Cg.mono)
            CostEditor(ab.cost, allowAlternatives = false) { c -> put(ab.copy(cost = c)) }
            Text("effect", color = Cg.dim, fontSize = 9.sp, fontFamily = Cg.mono)
            EffectSection(ab.effect, "＋ ability effect", SELF) { e -> put(ab.copy(effect = e ?: Effect.NoOp)) }
        }
    }
    CgAddButton("ability") {
        vm.updateFace { f ->
            f.copy(
                activated = f.activated + ActivatedAbility(
                    Cost(tapSource = true),
                    Effect.AddMana(PlayerRef.You, mapOf("" to lit(1))),
                    "{T}: add 1",
                ),
            )
        }
    }
}

@Composable
private fun CardEntersEditor(vm: CreatorViewModel) {
    vm.card.entersWith.forEachIndexed { i, cd ->
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CgMiniField(cd.kind, Modifier.weight(1f)) { k ->
                vm.updateCardDoc { d ->
                    d.copy(entersWith = d.entersWith.mapIndexed { j, x -> if (j == i) x.copy(kind = k) else x })
                }
            }
            // Was literal-only; an IntPill makes a COMPUTED starting count
            // expressible ("enters with a counter for each Ship you control"),
            // which is what `CounterDef.initial` being an IntExpr is for.
            IntPill(cd.initial) { ex ->
                vm.updateCardDoc { d ->
                    d.copy(entersWith = d.entersWith.mapIndexed { j, x -> if (j == i) x.copy(initial = ex) else x })
                }
            }
            CgDeleteX {
                vm.updateCardDoc { d -> d.copy(entersWith = d.entersWith.filterIndexed { j, _ -> j != i }) }
            }
        }
    }
    CgAddButton("counter") {
        vm.updateCardDoc { it.copy(entersWith = it.entersWith + CounterDef("charge", lit(1))) }
    }
}

@Composable
private fun CardRecastEditor(vm: CreatorViewModel) {
    Text(
        "Flashback and friends. A second way to cast this card, out of a zone that is not the " +
            "hand — and where the card goes afterwards, which is what stops it being cast over and over.",
        color = Cg.dim, fontSize = 10.sp,
    )
    val r = vm.card.recast
    if (r == null) {
        CgButton("add") {
            vm.updateCardDoc { it.copy(recast = Recast(HiddenZone.GRAVEYARD, it.cost, HiddenZone.EXILE)) }
        }
    } else {
        CgInlineField("from") {
            MenuPill(zoneName(r.from), HIDDEN_ZONES) { z ->
                vm.updateCardDoc { it.copy(recast = r.copy(from = zoneOfName(z))) }
            }
        }
        CgInlineField("then goes to") {
            MenuPill(zoneName(r.afterResolve), HIDDEN_ZONES) { z ->
                vm.updateCardDoc { it.copy(recast = r.copy(afterResolve = zoneOfName(z))) }
            }
        }
        Text("for:", color = Cg.ink2, fontSize = 11.sp)
        CostEditor(r.cost, allowAlternatives = false) { c ->
            vm.updateCardDoc { it.copy(recast = r.copy(cost = c)) }
        }
    }
}


/** Find a card by `CardDoc.key()` (never the display name), as (set index,
 *  card index). */
private fun CreatorViewModel.locate(key: String): Pair<Int, Int>? {
    game.sets.forEachIndexed { si, s ->
        val ci = s.cards.indexOfFirst { it.key() == key }
        if (ci >= 0) return si to ci
    }
    return null
}

@Composable
private fun PoolScreen(vm: CreatorViewModel, onOpenCard: (Int, Int) -> Unit) {
    // Recomputed when the game changes, not on every recomposition: this walks
    // every card and every pair within a cost bucket, and a per-frame version
    // of that is how a readiness check becomes "the feature doesn't work".
    val sections = remember(vm.game) { vm.game.poolReport().sections() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "${vm.game.sets.sumOf { it.cards.size }} cards · ${vm.game.decks.size} decks",
            color = Cg.dim, fontFamily = Cg.mono, fontSize = 10.sp,
        )
        for (s in sections) {
            // One CgGroup per section, never nested -- accordion-inside-
            // accordion is banned by a build guard.
            CgGroup(s.title, summary = "${s.lines.count { it.cardKey != null }} cards") {
                Column(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(s.note, color = Cg.dim, fontSize = 10.sp, lineHeight = 14.sp)
                    if (s.lines.isEmpty()) {
                        Text("(none)", color = Cg.muted, fontFamily = Cg.mono, fontSize = 11.sp)
                    }
                    for (line in s.lines) {
                        val key = line.cardKey
                        val at = key?.let { vm.locate(it) }
                        if (at == null) {
                            Text(
                                line.text, color = Cg.ink2, fontFamily = Cg.mono,
                                fontSize = 11.sp, lineHeight = 15.sp,
                            )
                        } else {
                            // Tappable, and it must LOOK tappable -- a row that
                            // acts and reads like a label is the same defect as
                            // an affordance nobody can find.
                            Row(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable { onOpenCard(at.first, at.second) }
                                    .padding(vertical = 3.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    line.text, color = Cg.ink, fontFamily = Cg.mono,
                                    fontSize = 11.sp, lineHeight = 15.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                Text("›", color = Cg.accentLight, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

internal val CardsModule = ModuleSpec(Module.CARDS, CgIconKind.CARDS) {
    val detail = route.focus is Focus.Card
    when {
        // Landscape: the list stays beside the card it opened.
        twoPane -> TwoPanes(
            list = { CardsList(this) },
            detail = if (detail) ({ CardsTab(vm, store, onTry = { key -> vm.tryCard(key); push(Module.PLAY, Focus.Table) }) }) else null,
            empty = "Pick a card to edit it.",
        )
        detail -> CardsTab(vm, store, onTry = { key -> vm.tryCard(key); push(Module.PLAY, Focus.Table) })
        else -> CardsList(this)
    }
}

/** The Cards list: the sets, or the Pool report -- Pool is a VIEW of the
 *  cards (a place to think about the whole set), not a destination. */
@Composable
private fun CardsList(scope: ModuleScope) = with(scope) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp)) {
            CgSegmented(CARDS_VIEWS, CARDS_VIEWS[vm.cardsView]) { s ->
                vm.cardsView = CARDS_VIEWS.indexOf(s).coerceAtLeast(0)
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (vm.cardsView == 0) {
                SetsScreen(vm, store) { openDetail(Module.CARDS, Focus.Card(vm.selected)) }
            } else {
                PoolScreen(vm) { setIdx, cardIdx -> openCard(setIdx, cardIdx) }
            }
        }
    }
}
