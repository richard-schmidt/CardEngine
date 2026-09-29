package cge

import ccg.GameDoc
import ccg.PlayerId
import ccg.PriorityAction
import ccgui.ALL_POLICIES
import ccgui.PassingPilot
import ccgui.PlaySession
import ccgui.PolicyPilot
import ccgui.SeatedPilots
import ccgui.opening

// ---------------------------------------------------------------------------
// WHAT ACTUALLY GETS PLAYED, by card type.
//
// "Are the spells being used, or is this a Ship game with a spell-shaped
// decoration?" is not answerable from a win-rate matrix, and it is not
// answerable from deck composition either -- a deck can hold twelve Manoeuvres
// and cast two. So this counts the actions a pilot really takes.
//
// Counted per DECK and per TYPE, and reported three ways, because each alone
// misleads:
//
//   plays/game     absolute volume -- but a type with more copies plays more
//   share          what a turn is MADE of -- but says nothing about waste
//   utilisation    plays/game per COPY IN DECK, which is the honest "does a
//                  card of this type, sitting in this deck, ever get used"
//
// A card is attributed to ONE type: its first non-generic type name, with
// Flagship winning over Ship (a Flagship is always available from a pool, so
// counting it as a Ship would hide the thing that makes it different).
// ---------------------------------------------------------------------------

private val TYPE_ORDER = listOf("Flagship", "Ship", "Manoeuvre", "Directive", "Doctrine", "Station", "Leader")

private fun bucketOf(types: Set<String>): String =
    TYPE_ORDER.firstOrNull { it in types } ?: types.firstOrNull() ?: "?"

private class Tally {
    val plays = mutableMapOf<String, Int>()
    /** How often a card of this type was a LEGAL option at a decision point.
     *  "Never offered" and "offered and never chosen" are completely different
     *  diagnoses -- the first is a rules or cost problem, the second is the
     *  pilot's valuation -- and a play count alone cannot tell them apart. */
    val offered = mutableMapOf<String, Int>()
    var games = 0
    var decisions = 0
    /** Why a card of this type was refused, when it was in hand and NOT on the
     *  menu. "Never offered" is a symptom; this is the cause. */
    val refusals = mutableMapOf<String, MutableMap<String, Int>>()
    fun refuse(type: String, why: String) {
        refusals.getOrPut(type) { mutableMapOf() }.merge(why, 1, Int::plus)
    }
    fun add(t: String) { plays[t] = (plays[t] ?: 0) + 1 }
    fun offer(t: String) { offered[t] = (offered[t] ?: 0) + 1 }
}

/** Sees the STATE as well as the action, which `ObservedPilot` does not -- and
 *  the state is what makes "was it even on the menu" answerable. */
private class Watcher(
    private val inner: ccg.PlayerInput,
    private val onAsk: (PlayerId, ccg.GameState, PriorityAction) -> Unit,
) : ccg.PlayerInput {
    override suspend fun ask(q: ccg.Question): ccg.Answer {
        val a = inner.ask(q)
        // These pilots answer priority with the action itself; a bare
        // reference (Pass) reads as the pass it is.
        if (q is ccg.Question.Priority) onAsk(q.player, q.state, (a as? ccg.Answer.Act)?.action ?: PriorityAction.PassPriority)
        return a
    }
}

/** `Face.types`, NOT `Face.baseChars?.types`: `baseChars` is null for a card
 *  with no stat line (every Doctrine), which would count as never played. */
private fun typesOf(a: PriorityAction): Set<String>? = when (a) {
    is PriorityAction.PlayPermanent -> a.card.faces.getOrNull(a.face)?.types
    is PriorityAction.CastSpell -> a.spellTypes
    else -> null
}

fun runPlayMix(doc: GameDoc, seeds: Int, maxTurns: Int) {
    val rules = doc.compile().playable()
    val deckNames = doc.decks.map { it.name }

    // Deck composition, by the same buckets, so utilisation has a denominator.
    val byKey = doc.cards.associateBy { it.key() }
    val composition = doc.decks.map { d ->
        val m = mutableMapOf<String, Int>()
        for (e in d.entries) {
            val c = byKey[e.cardName] ?: doc.cards.firstOrNull { it.faces.first().name == e.cardName }
            val b = bucketOf(c?.faces?.first()?.types ?: emptySet())
            m[b] = (m[b] ?: 0) + e.count
        }
        // Slots (Station / Leader / Flagship) are not drawn -- they start in
        // play or in an always-available pool. Counted so the report can say
        // so, never folded into the deck's card count.
        for ((_, ids) in d.slots) for (id in ids) {
            val c = byKey[id] ?: doc.cards.firstOrNull { it.faces.first().name == id }
            val b = bucketOf(c?.faces?.first()?.types ?: emptySet())
            m[b] = (m[b] ?: 0) + 1
        }
        m.toMap()
    }

    val tally = deckNames.indices.map { Tally() }

    for (a in doc.decks.indices) for (b in doc.decks.indices) {
        for (pa in ALL_POLICIES) for (pb in ALL_POLICIES) {
            for (seed in 0 until seeds) {
                val seen = mutableListOf<Pair<PlayerId, String>>()
                val offers = mutableListOf<Pair<PlayerId, String>>()
                val refused = mutableListOf<Pair<PlayerId, Pair<String, String>>>()
                val decided = mutableMapOf<PlayerId, Int>()
                fun watch(seat: PlayerId, inner: ccg.PlayerInput) = Watcher(inner) { p, state, action ->
                    decided[p] = (decided[p] ?: 0) + 1
                    // What was ON THE MENU, deduplicated by type: a hand with
                    // four copies offers one Doctrine decision, not four.
                    ccg.legalActionsFor(rules, state, p)
                        .mapNotNull { typesOf(it)?.takeIf { ty -> ty.isNotEmpty() }?.let(::bucketOf) }
                        .distinct()
                        .forEach { offers += p to it }
                    typesOf(action)?.takeIf { it.isNotEmpty() }?.let { seen += p to bucketOf(it) }
                    // WHY a card in hand is not on the menu. Asked here because
                    // "never offered" is where every silent-skip bug hides.
                    for (ref in state.cardsInCast(p, ccg.CastZone.Std(ccg.HiddenZone.HAND))) {
                        val card = rules.cards[ref.cardId]
                        if (card == null) { refused += p to ("?" to "card id does not resolve"); continue }
                        val ty = card.faces.firstOrNull()?.types.orEmpty()
                        if (ty.isEmpty()) continue
                        val act = ccg.playActionFor(rules, card, ccg.CardSource(ccg.CastZone.Std(ccg.HiddenZone.HAND), ref.instanceId))
                        val leg = ccg.legality(rules, state, p, act)
                        if (leg !is ccg.Legality.Legal) {
                            refused += p to (bucketOf(ty) to (leg.toString().take(70)))
                        }
                    }
                }
                val p0 = watch("P0", PolicyPilot("P0", rules, pa))
                val p1 = watch("P1", PolicyPilot("P1", rules, pb))
                val start = PlaySession(p0Deck = a, p1Deck = b, seed = seed).opening(doc, rules)
                val pilots = SeatedPilots(mapOf("P0" to p0, "P1" to p1), fallback = PassingPilot())
                if (runCatching { ccg.playToEnd(rules, start, pilots, maxTurns) }.isFailure) continue
                tally[a].games++
                tally[b].games++
                for ((seat, bucket) in seen) {
                    if (seat == "P0") tally[a].add(bucket) else tally[b].add(bucket)
                }
                for ((seat, bucket) in offers) {
                    if (seat == "P0") tally[a].offer(bucket) else tally[b].offer(bucket)
                }
                for ((seat, r) in refused) {
                    if (seat == "P0") tally[a].refuse(r.first, r.second) else tally[b].refuse(r.first, r.second)
                }
                tally[a].decisions += decided["P0"] ?: 0
                tally[b].decisions += decided["P1"] ?: 0
            }
        }
    }

    println()
    println("== WHAT GETS PLAYED, by card type ==")
    println("(a Flagship is counted apart from a Ship: it comes from an always-available pool)")
    for ((i, name) in deckNames.withIndex()) {
        val t = tally[i]
        val total = t.plays.values.sum().toDouble().coerceAtLeast(1.0)
        println()
        println("$name   (${t.games} games)")
        println("  %-11s %9s %8s %9s %9s %9s %8s".format("type", "in deck", "plays/g", "share", "per copy", "offered/g", "taken"))
        for (bucket in TYPE_ORDER) {
            val n = t.plays[bucket] ?: 0
            val copies = composition[i][bucket] ?: 0
            if (n == 0 && copies == 0) continue
            val perGame = n.toDouble() / t.games.coerceAtLeast(1)
            val share = 100.0 * n / total
            val perCopy = if (copies == 0) Double.NaN else perGame / copies
            val off = (t.offered[bucket] ?: 0).toDouble() / t.games.coerceAtLeast(1)
            val taken = if (off <= 0.0) Double.NaN else n.toDouble() / (t.offered[bucket] ?: 1)
            println(
                "  %-11s %9d %8.2f %8.1f%% %9s %9.2f %7s".format(
                    bucket, copies, perGame, share,
                    if (perCopy.isNaN()) "-" else "%.2f".format(perCopy),
                    off,
                    if (taken.isNaN()) "never offered" else "%.1f%%".format(100.0 * taken),
                ),
            )
        }
        for ((type, why) in t.refusals) {
            if ((t.offered[type] ?: 0) > 0) continue
            val top = why.entries.sortedByDescending { it.value }.take(2)
            println("     $type never legal: " + top.joinToString("; ") { "${it.key} (x${it.value})" })
        }
        val ships = (t.plays["Ship"] ?: 0) + (t.plays["Flagship"] ?: 0)
        println("  -> NON-SHIP share of plays: %.1f%%".format(100.0 * (total - ships) / total))
    }
    println()
    println("CSV")
    println("deck,type,in_deck,plays_per_game,share_pct,per_copy")
    for ((i, name) in deckNames.withIndex()) {
        val t = tally[i]
        val total = t.plays.values.sum().toDouble().coerceAtLeast(1.0)
        for (bucket in TYPE_ORDER) {
            val n = t.plays[bucket] ?: 0
            val copies = composition[i][bucket] ?: 0
            if (n == 0 && copies == 0) continue
            val perGame = n.toDouble() / t.games.coerceAtLeast(1)
            val off = (t.offered[bucket] ?: 0).toDouble() / t.games.coerceAtLeast(1)
            println(
                "%s,%s,%d,%.4f,%.2f,%s,%.4f,%s".format(
                    name, bucket, copies, perGame, 100.0 * n / total,
                    if (copies == 0) "" else "%.4f".format(perGame / copies),
                    off,
                    if (off <= 0.0) "" else "%.2f".format(100.0 * n / (t.offered[bucket] ?: 1)),
                ),
            )
        }
    }
}

fun main(args: Array<String>) {
    val name = args.getOrNull(0) ?: "core"
    val seeds = args.getOrNull(1)?.toIntOrNull() ?: 4
    val maxTurns = args.getOrNull(2)?.toIntOrNull() ?: 30
    val doc = catalogue()[name]
    if (doc == null) {
        println("no such game: $name   (known: ${catalogue().keys.joinToString(", ")})")
        return
    }
    runPlayMix(doc, seeds, maxTurns)
}
