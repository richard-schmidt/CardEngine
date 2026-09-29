package com.ccg

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ccg.DeckDoc
import ccg.layoutFor
import ccg.DeckEntry
import ccg.DeckRules
import ccg.IntExpr
import ccg.problems
import ccgui.costSummary
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import ccgui.Module
import ccgui.Focus

// ---------------------------------------------------------------------------
// The Decks module: the game's decks, their slots and legality.
// ---------------------------------------------------------------------------

/** The game's decks. One per seat is the point -- two decks facing each other.
 *  5.3: the SLOT block (Leader / Base / Hero, outside the main deck) and a live
 *  legality line -- reported, never enforced. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DecksScreen(vm: CreatorViewModel, store: GameStore, onOpenDeckRules: () -> Unit) {
    val g = vm.game
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            g.decks.forEachIndexed { i, d ->
                Chip("${d.name} (${d.size})", i == vm.deckIndex) { vm.selectDeck(i) }
            }
            CgAddButton("deck") { vm.addDeck() }
        }
        val deck = vm.deck
        if (deck == null) {
            Text("No decks yet. A seat with no deck falls back to opaque filler, so drawing means nothing.",
                color = Cg.dim, fontSize = 11.sp)
        } else {
            CgField("deck name", deck.name) { n -> vm.updateDeck { it.copy(name = n) } }
            Text("${deck.size} cards in the deck", color = Cg.ink2, fontFamily = Cg.mono, fontSize = 12.sp)

            // -- slot cards (5.3): designated cards outside the main deck --
            val rule = g.deckRules
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "rule: ${deckRulesPresetName(rule)}",
                    color = Cg.dim, fontFamily = Cg.mono, fontSize = 9.sp,
                )
                TopAction("edit →") { onOpenDeckRules() }
            }
            if (rule.slots.isNotEmpty()) {
                // The picker shows/picks by NAME (nicer to read), but
                // DeckDoc.slots must hold c.key(), as the stepper above does.
                val candidatesFor = { types: Set<String> ->
                    listOf("—") + g.cards
                        .filter { c -> types.isEmpty() || c.faces[0].types.containsAll(types) }
                        .map { it.faces[0].name }
                }
                fun keyOfName(name: String): String = g.cards.firstOrNull { it.faces[0].name == name }?.key() ?: name
                fun nameOfKey(key: String): String = g.cards.firstOrNull { it.key() == key }?.faces?.get(0)?.name ?: key
                rule.slots.forEach { slot ->
                    CgCard {
                        Text(
                            "${slot.name} · needs ${slot.count} · " +
                                "${slot.types.joinToString("/").ifEmpty { "any type" }} · " +
                                "starts in ${slot.startsIn.ifBlank { "—" }}",
                            color = Cg.ink2, fontFamily = Cg.mono, fontSize = 10.sp,
                        )
                        val assigned = deck.slots[slot.name] ?: emptyList()
                        val opts = candidatesFor(slot.types)
                        repeat(slot.count) { k ->
                            CgInlineField(if (slot.count > 1) "${slot.name} ${k + 1}" else slot.name) {
                                CgPicker(assigned.getOrNull(k)?.let(::nameOfKey) ?: "—", opts) { picked ->
                                    val next = MutableList(slot.count) { assigned.getOrNull(it) ?: "" }
                                    next[k] = if (picked == "—") "" else keyOfName(picked)
                                    vm.updateDeck { it.setSlot(slot.name, next.filter { s -> s.isNotBlank() }) }
                                }
                            }
                        }
                    }
                }
            }

            // -- legality, reported not enforced --
            if (rule != DeckRules()) {
                val problems = rule.problems(deck, g.compile().rules)
                if (problems.isEmpty()) {
                    Text("✓ legal under ${deckRulesPresetName(rule)}", color = Cg.go, fontFamily = Cg.mono, fontSize = 11.sp)
                } else {
                    Text("${problems.size} problem(s) — reported, not blocked", color = Cg.warn, fontFamily = Cg.mono, fontSize = 11.sp)
                    problems.forEach { Text("⚠  $it", color = Cg.ink2, fontFamily = Cg.mono, fontSize = 10.sp) }
                }
            }

            Text("cards", color = Cg.dim, fontFamily = Cg.mono, fontSize = 9.sp)
            g.sets.forEach { st ->
                Text(st.name, color = Cg.dim, fontFamily = Cg.mono, fontSize = 9.sp)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    st.cards.forEach { c ->
                        val f = c.faces[0]
                        val layout = g.rules.layoutFor(f.types)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            CgCardTile(
                                name = f.name,
                                typeLabel = f.types.joinToString(" ").ifEmpty { "—" },
                                costLabel = costSummary(c.cost).takeIf { !c.cost.isFree },
                                pt = statLabel(f, layout),
                                text = c.text,
                                layout = layout,
                                art = rememberCardArt(store, g.id, f.art),
                                artRect = ccgui.rectFor(f, compact = true),
                                counters = c.entersWith.associate { it.kind to ((it.initial as? IntExpr.Lit)?.value ?: 0) },
                                compact = true,
                            )
                            // Keyed on c.key(), not f.name:
                            // once a card has a minted id, DeckEntry must hold
                            // that id, not the display name, or the deck
                            // silently stops resolving this card at all.
                            CgStepper(deck.countOf(c.key()), min = 0) { v -> vm.updateDeck { it.setCount(c.key(), v) } }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CgButton("4 of each") {
                    vm.updateDeck { d -> d.copy(entries = g.cards.map { DeckEntry(it.key(), 4) }) }
                }
                CgButton("clear") { vm.updateDeck { it.copy(entries = emptyList()) } }
                CgButton("delete deck") { vm.removeDeck(vm.deckIndex) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

internal val DecksModule = ModuleSpec(Module.DECKS, CgIconKind.DECKS) {
    DecksScreen(vm, store) { push(Module.RULES, Focus.Section(RulesSection.DECK.name)) }
}
