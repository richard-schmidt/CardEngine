package ccg

// ---------------------------------------------------------------------------
// Deck-construction rules, built for LEADER games. SWU (Leader + Base) and
// Hearthstone (Hero) are one shape: DESIGNATED cards that live outside the
// main deck, start the game in play, and constrain what the deck may contain
// (EDH colour identity is the same rule).
//
//   DeckSlotDef   -- a slot (Leader / Base / Hero): which types fill it, how
//                    many, and where its cards begin.
//   IdentityRule  -- an identity vocabulary, the slots whose cards fix the
//                    legal set, and whether neutral cards are always allowed.
//   DeckRules     -- size / copies / slots / identity; `problems(deck, rules)`
//                    REPORTS, never enforces.
//
// The rules say "one Leader"; the deck (`DeckDoc.slots`) names which card.
// `setupGame` places slot cards before the opening hand.
// ---------------------------------------------------------------------------

/** One designated slot outside the main deck: `count` cards of each type in
 *  `types`, each beginning in `startsIn` -- a play zone (enters play), a
 *  declared hidden zone (sits uncast, e.g. a Flagship pool), or blank (kept
 *  outside the game). */
data class DeckSlotDef(
    val name: String,
    val types: Set<String> = emptySet(),
    val count: Int = 1,
    val startsIn: String = "battlefield",
)

/** How a deck's identity is constrained. A card's identity keys are its
 *  keywords that appear in `vocabulary`; a deck's legal identity is the union
 *  over the cards in the `from` slots. An EMPTY vocabulary means no identity
 *  system, and the check is skipped. */
data class IdentityRule(
    val from: List<String> = emptyList(),
    val vocabulary: Set<String> = emptySet(),
    val allowNeutral: Boolean = true,
)

/** A card that begins the game already in play -- a Leader / Base / Hero on the
 *  board. Named by the deck, resolved through `Rules.cards` at setup. */
data class StartCard(val cardName: String, val zone: String = "battlefield")

data class DeckRules(
    val minSize: Int = 0,
    val maxSize: Int? = null,
    /** Copies of one card name allowed in the main deck. Null = no limit. */
    val maxCopies: Int? = null,
    val slots: List<DeckSlotDef> = emptyList(),
    val identity: IdentityRule? = null,
) {
    /** Everything wrong with `deck` under these rules, as sentences. REPORTED,
     *  never enforced -- the same stance as `GameDoc.problems()`.
     *  Total: an unknown card name is itself one of the sentences, not a crash. */
    fun problems(deck: DeckDoc, rules: Rules): List<String> = buildList {
        val where = "deck \"${deck.name}\""
        val n = deck.size
        if (n < minSize) add("$where has $n cards, under the minimum of $minSize")
        maxSize?.let { if (n > it) add("$where has $n cards, over the maximum of $it") }
        maxCopies?.let { mc ->
            deck.entries.filter { it.count > mc }.forEach {
                add("$where runs ${it.count} copies of \"${it.cardName}\" -- the limit is $mc")
            }
        }

        fun typesOf(name: String): Set<String>? = rules.cards[name]?.faces?.firstOrNull()?.types
        fun keywordsOf(name: String): Set<String> =
            rules.cards[name]?.faces?.firstOrNull()?.baseChars?.keywords ?: emptySet()

        for (slot in slots) {
            val named = deck.slots[slot.name] ?: emptyList()
            if (named.size != slot.count) {
                add("$where names ${named.size} card(s) in the ${slot.name} slot -- it takes ${slot.count}")
            }
            for (cn in named) {
                val t = typesOf(cn)
                when {
                    t == null -> add("$where names \"$cn\" in the ${slot.name} slot, which no set defines")
                    slot.types.isNotEmpty() && !t.containsAll(slot.types) ->
                        add("\"$cn\" in $where's ${slot.name} slot is not ${slot.types.joinToString("/")}")
                }
            }
        }

        val id = identity
        if (id != null && id.vocabulary.isNotEmpty()) {
            val allowed: Set<String> = id.from
                .flatMap { s -> deck.slots[s] ?: emptyList() }
                .flatMap { keywordsOf(it) }
                .filter { it in id.vocabulary }
                .toSet()
            for (e in deck.entries) {
                val keys = keywordsOf(e.cardName).filter { it in id.vocabulary }.toSet()
                when {
                    keys.isEmpty() && !id.allowNeutral ->
                        add("$where runs \"${e.cardName}\", which has no identity -- neutral cards are not allowed here")
                    keys.isNotEmpty() && !allowed.containsAll(keys) ->
                        add("$where runs \"${e.cardName}\" (${keys.joinToString("/")}), outside its identity of " +
                            (allowed.joinToString("/").ifEmpty { "none" }))
                }
            }
        }
    }

    /** Split `deck` into its shuffleable library and the cards that start in
     *  play, per these slots. Feed the second half to `setupGame(startInPlay =
     *  ...)`. */
    fun opening(deck: DeckDoc, startId: Int): Pair<List<CardRef>, List<StartCard>> {
        val inPlay = slots.flatMap { slot ->
            if (slot.startsIn.isBlank()) emptyList()
            else (deck.slots[slot.name] ?: emptyList()).map { StartCard(it, slot.startsIn) }
        }
        return deck.libraryFor(startId) to inPlay
    }

    companion object {
        /** 60+, four of a card, no slots. */
        val MTG = DeckRules(minSize = 60, maxCopies = 4)

        /** Exactly 30, two of a card, one Hero in play. The class vocabulary is
         *  left to the caller (see `IdentityRule.vocabulary`). */
        val HEARTHSTONE = DeckRules(
            minSize = 30, maxSize = 30, maxCopies = 2,
            slots = listOf(DeckSlotDef("Hero", setOf("Hero"), 1)),
            identity = IdentityRule(from = listOf("Hero"), allowNeutral = true),
        )

        /** 50, three of a card, a Leader and a Base in play; identity is the
         *  union of the Leader's and Base's aspects, over the six fixed ones. */
        val SWU = DeckRules(
            minSize = 50, maxSize = 50, maxCopies = 3,
            slots = listOf(
                DeckSlotDef("Leader", setOf("Leader"), 1),
                DeckSlotDef("Base", setOf("Base"), 1),
            ),
            identity = IdentityRule(
                from = listOf("Leader", "Base"),
                vocabulary = setOf("Vigilance", "Command", "Aggression", "Cunning", "Heroism", "Villainy"),
                allowNeutral = true,
            ),
        )
    }
}
