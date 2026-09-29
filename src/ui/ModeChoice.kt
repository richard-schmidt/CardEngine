package ccgui

import ccg.Effect
import ccg.EvalContext
import ccg.GameState
import ccg.IntExpr
import ccg.ModeOption
import ccg.PlayerId
import ccg.eval
import ccg.matches
import ccg.usesX

// ---------------------------------------------------------------------------
// WHICH MODE A PILOT TAKES -- one definition for every pilot.
//
// The default answer to a mode question is the first option(s). Pilots once
// all took it, so no modal card's other modes had ever been measured (every
// lane sweep only ever swept lane 1). A change is only measurable if some
// policy can perceive it.
//
// Here, not inside a pilot: `PolicyPilot` and `HeuristicPilot` are different
// classes, and a term written into one leaves the other blind.
// ---------------------------------------------------------------------------

/** A rough magnitude for an effect: damage dealt, cards drawn, counters moved,
 *  in roughly "points of damage". Shared by the mode term and the ability
 *  tie-breaker. Only separates bigger from smaller; WHICH KIND to prefer stays
 *  in `Policy.value`.
 *
 *  Amounts are EVALUATED, not pattern-matched for literals (a charge burst
 *  reads its counter). BOARD-AWARE and SIGNED: `ForEach` enumerates what its
 *  filter really matches, +body for an opponent's permanent and -body for mine,
 *  so "sweep the lane holding their three" beats "the lane holding my two" and a
 *  symmetric sweep scores net. `Choose` follows `PolicyPilot`'s real targeting
 *  rule (prefer a permanent that is not mine). */
fun effectImpact(e: Effect, ctx: EvalContext): Int {
    // An unbound X would read 0, so it falls back. Evaluating over a view is
    // total -- a view keeps every size `eval` reads (ccg/Visibility.kt).
    fun size(x: IntExpr, fallback: Int) = if (x.usesX()) fallback else x.eval(ctx)
    fun hits(f: ccg.PermFilter): List<ccg.ObjectId> = ctx.state.battlefield.keys.filter { f.matches(ctx, it) }
    fun mine(id: ccg.ObjectId) = ctx.state.battlefield[id]?.controller == ctx.controller

    fun walk(x: Effect): Int = when (x) {
        is Effect.DealDamage -> size(x.amount, 2)
        is Effect.DamageOpponent -> size(x.amount, 2)
        is Effect.Draw -> 2
        is Effect.CreateToken -> 2
        is Effect.AddCounter -> size(x.count, 1)
        is Effect.RemoveCounter -> size(x.count, 1)
        // A constant, in the same spirit as Draw's 2, and the number matters:
        // removal that scored ZERO is what made this whole function worth
        // extracting. A destroy must beat a small burn and lose to a genuine
        // multi-target sweep, which 5 does at the Core's magnitudes.
        is Effect.Destroy -> 5
        // Evaluated, not summed as literals: mana amounts became expressions
        // so a Station could add "1 per card stowed under it", and reading only
        // the literal would score a growing economy as a constant -- the same
        // mistake `impactOf`'s doc comment records about the Union's charge.
        is Effect.AddMana -> x.mana.values.sumOf { size(it, 1) }
        // Signed per permanent -- the term that makes a lane a choice.
        is Effect.ForEach -> walk(x.body).let { body ->
            if (body == 0) 0 else hits(x.filter).sumOf { if (mine(it)) -body else body }
        }
        // The pilot will aim this at something that is not mine if it can; if
        // nothing matches at all the mode does nothing, which is worth saying
        // out loud rather than scoring as though it fired.
        is Effect.Choose -> walk(x.body).let { body ->
            val h = hits(x.filter)
            when {
                h.isEmpty() -> 0
                h.any { !mine(it) } -> body
                else -> -body
            }
        }
        // Up to `count` of them.
        is Effect.ChooseMany -> walk(x.body).let { body ->
            val enemies = hits(x.filter).count { !mine(it) }
            body * minOf(size(x.count, 1).coerceAtLeast(1), enemies)
        }
        is Effect.ForEachPlayer -> walk(x.body)
        is Effect.ChooseMode -> x.options.maxOfOrNull { walk(it) } ?: 0
        is Effect.Sequence -> x.steps.sumOf { walk(it) }
        is Effect.Delayed -> walk(x.effect)
        else -> 0
    }
    return walk(e)
}

/** The indices a pilot takes from a modal question: `pick` of them, highest
 *  `effectImpact` first, ties by enumeration order (deterministic replay).
 *
 *  COST ALTERNATIVES ARE LEFT ALONE: the engine offers only payable ones and
 *  first-wins means "spend mana, keep the alternative resource" -- the Core's
 *  designed default (`manaOrShield`). Reorder effects, never costs. Anything not
 *  wholly `OfEffect` keeps the default. */
fun bestModes(
    options: List<ModeOption>,
    pick: Int,
    state: GameState,
    seat: PlayerId,
): List<Int> {
    val n = pick.coerceIn(0, options.size)
    if (n == 0 || options.isEmpty()) return emptyList()
    val effects = options.map { it as? ModeOption.OfEffect }
    // A cost alternative, or a shape this does not understand: unchanged.
    if (effects.any { it == null }) return (0 until n).toList()
    if (n == options.size) return options.indices.toList()

    val ctx = EvalContext(state, seat)
    val scored = effects.mapIndexed { i, o -> i to effectImpact(o!!.effect, ctx) }
    return scored
        .sortedWith(compareByDescending<Pair<Int, Int>> { it.second }.thenBy { it.first })
        .take(n)
        .map { it.first }
        // Applied in the prompt's own order: `pick` > 1 means "these modes
        // happen", not "these modes happen best-first", and the engine folds
        // them in the order handed back.
        .sorted()
}
