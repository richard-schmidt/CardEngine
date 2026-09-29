package com.ccg

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ccg.Cost
import ccg.permanents
import ccg.CounterPayment
import ccg.Effect
import ccg.SELF
import ccgui.costSummary

// ---------------------------------------------------------------------------
// The Cost editor, reused for the card's own cost and (non-recursively) each
// alternative -- `Cost.alternatives` is one level deep, so the nested editor
// hides "add an alternative".
// ---------------------------------------------------------------------------

@Composable
internal fun CostEditor(cost: Cost, allowAlternatives: Boolean = true, onChange: (Cost) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            word("generic")
            StepPill(cost.mana[""] ?: 0) { n ->
                onChange(cost.copy(mana = if (n == 0) cost.mana - "" else cost.mana + ("" to n)))
            }
        }
        // Typed resources ("R", "G", …) -- an open set, so a free-text key.
        cost.mana.filterKeys { it.isNotEmpty() }.forEach { (k, n) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CgMiniField(k, Modifier.width(56.dp)) { nk ->
                    onChange(cost.copy(mana = cost.mana - k + (nk to n)))
                }
                StepPill(n) { v -> onChange(cost.copy(mana = cost.mana + (k to v))) }
                Spacer(Modifier.weight(1f))
                CgDeleteX { onChange(cost.copy(mana = cost.mana - k)) }
            }
        }
        CgAddButton("typed resource") {
            val key = generateSequence('A') { it + 1 }.map { it.toString() }.first { it !in cost.mana }
            onChange(cost.copy(mana = cost.mana + (key to 1)))
        }

        CgCheck("has an {X}", cost.usesX) { onChange(cost.copy(usesX = it)) }
        CgCheck("tap it ({T})", cost.tapSource) { onChange(cost.copy(tapSource = it)) }
        CgCheck("sacrifice it", cost.sacrificeSource) { onChange(cost.copy(sacrificeSource = it)) }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            word("pay"); StepPill(cost.payLife) { onChange(cost.copy(payLife = it)) }; word("life")
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val rc = cost.removeCounters
            if (rc == null) {
                CgAddButton("remove counters") { onChange(cost.copy(removeCounters = "loyalty" to 1)) }
            } else {
                word("remove"); StepPill(rc.second) { onChange(cost.copy(removeCounters = rc.first to it)) }
                CgMiniField(rc.first, Modifier.width(72.dp)) { onChange(cost.copy(removeCounters = it to rc.second)) }
                word("counters")
                CgDeleteX { onChange(cost.copy(removeCounters = null)) }
            }
        }

        // -- pay from a PERMANENT's counters ------------------------------
        // A counter NAME plus a FILTER saying which permanents may pay: the
        // payer is chosen at cast time (`payersFor`), not fixed.
        val payVocab = LocalVocab.current.counters
        Text("pay from a permanent's counters", color = Cg.dim, fontSize = 9.sp, fontFamily = Cg.mono)
        val pf = cost.payFrom
        if (pf == null) {
            CgAddButton("counter payment") {
                onChange(
                    cost.copy(
                        payFrom = CounterPayment(
                            counter = payVocab.firstOrNull() ?: "hull",
                            amount = 1,
                            filter = permanents().yours(),
                        ),
                    ),
                )
            }
        } else {
            CgCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CgPicker(pf.counter, payVocab.ifEmpty { listOf(pf.counter) }) {
                        onChange(cost.copy(payFrom = pf.copy(counter = it)))
                    }
                    StepPill(pf.amount) { onChange(cost.copy(payFrom = pf.copy(amount = it.coerceAtLeast(1)))) }
                    CgDeleteX { onChange(cost.copy(payFrom = null)) }
                }
                CgInlineField("paid by") {
                    FilterPill(pf.filter) { f -> onChange(cost.copy(payFrom = pf.copy(filter = f))) }
                }
            }
        }

        // An additional cost is an Effect ("sacrifice a creature", "discard").
        // It targets the paying permanent, so SELF.
        Text("additional cost (an effect)", color = Cg.dim, fontSize = 9.sp, fontFamily = Cg.mono)
        EffectSection(cost.additional, "＋ additional cost", SELF) { e -> onChange(cost.copy(additional = e)) }

        if (allowAlternatives) {
            Text(
                "alternative ways to pay — the engine tries each and asks you when more than one is payable",
                color = Cg.dim, fontSize = 9.sp,
            )
            cost.alternatives.forEachIndexed { i, alt ->
                CgCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("or", color = Cg.accent, fontFamily = Cg.mono, fontSize = 11.sp)
                        Text(costSummary(alt), color = Cg.ink2, fontFamily = Cg.mono, fontSize = 11.sp)
                        Spacer(Modifier.weight(1f))
                        CgDeleteX {
                            onChange(cost.copy(alternatives = cost.alternatives.filterIndexed { j, _ -> j != i }))
                        }
                    }
                    Box(Modifier.padding(start = 8.dp)) {
                        // One level only -- an alternative has no alternatives.
                        CostEditor(alt, allowAlternatives = false) { na ->
                            onChange(cost.copy(alternatives = cost.alternatives.mapIndexed { j, x -> if (j == i) na else x }))
                        }
                    }
                }
            }
            CgAddButton("alternative cost") {
                onChange(cost.copy(alternatives = cost.alternatives + Cost(payLife = 3)))
            }
        }
    }
}
