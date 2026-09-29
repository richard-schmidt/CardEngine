package ccgui

import ccg.Cost
import ccg.GameDoc
import ccg.TurnMode
import ccg.fieldsForTypes

// ---------------------------------------------------------------------------
// The NAMED variations an agent may apply to a fork. Not a field-path writer:
// a generic setter can express an unknown enum, a cost on a missing card, a
// stat no type declares -- games that LOAD and measure wrong. Each variation
// knows what it varies, refuses what it cannot express, refuses a no-op, and
// REPORTS BEFORE AND AFTER (a silent no-op reads as success).
//
// In `src/ui` because it is decision logic and checks stat fields
// with the Creator's own rule (`fieldsForTypes` + `editableFields`).
// ---------------------------------------------------------------------------

/** The on-disk stem for a fork id, or null if it may not address a file. Every
 *  agent path that turns a caller id into a FILENAME (vary, delete) goes through
 *  here, so `fork:../../cge-test/...` cannot reach outside the fork directory.
 *  An allow-list matching what `fork` mints; here because `test.sh` cannot see
 *  `agent/`. */
fun forkStemOf(gameId: String): String? {
    if (!gameId.startsWith(FORK_PREFIX)) return null
    val stem = gameId.removePrefix(FORK_PREFIX)
    if (stem.isEmpty()) return null
    // Allow-list, not a deny-list: a deny-list for path traversal is a game of
    // whack-a-mole ("..", "%2e%2e", backslashes, absolute paths), and the set
    // of names this tool actually mints is small and known.
    if (!stem.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return null
    return stem
}

/** The prefix every fork id carries in the catalogue. */
const val FORK_PREFIX: String = "fork:"

/** The outcome of a variation. */
sealed interface Varied {
    /** Applied. [before] and [after] describe the ONE thing that changed. */
    data class Ok(val doc: GameDoc, val what: String, val before: String, val after: String) : Varied

    /** Not applied, and why -- phrased for the agent that asked. */
    data class Refused(val why: String) : Varied
}

/** Why this doc may not be varied, or null if it may. **An agent edits a fork,
 *  never a game.** `forkedFrom` is the discriminator (set only by `forkedAs`);
 *  the guard is here, in the tested layer, not in `agent/`. */
fun GameDoc.refuseUnlessFork(): String? =
    if (forkedFrom.isNotEmpty()) null
    else "\"$name\" is not a fork. Fork it first, then vary the fork -- an agent never edits a game directly."

/** Resolve a card by stable id, else display name. Refuses an AMBIGUOUS name
 *  rather than picking one: an arbitrary pick is an unreproducible measurement. */
private fun GameDoc.findCard(key: String): Result<Pair<Int, Int>> {
    val hits = mutableListOf<Pair<Int, Int>>()
    sets.forEachIndexed { si, set ->
        set.cards.forEachIndexed { ci, c ->
            val name = c.faces.firstOrNull()?.name
            if (c.id == key || (c.id.isEmpty() && name == key) || name == key) hits += si to ci
        }
    }
    return when {
        hits.isEmpty() -> Result.failure(IllegalArgumentException("no card \"$key\" -- use its id or its exact name"))
        hits.size > 1 -> Result.failure(
            IllegalArgumentException("\"$key\" names ${hits.size} cards -- use the stable id instead"),
        )
        else -> Result.success(hits.first())
    }
}

private inline fun GameDoc.editCard(
    key: String,
    crossinline edit: (ccg.CardDoc) -> Varied,
): Varied {
    refuseUnlessFork()?.let { return Varied.Refused(it) }
    val at = findCard(key).getOrElse { return Varied.Refused(it.message ?: "no such card") }
    return edit(sets[at.first].cards[at.second])
}

private fun GameDoc.replaceCard(key: String, card: ccg.CardDoc): GameDoc {
    val at = findCard(key).getOrNull() ?: return this
    val set = sets[at.first]
    return copy(
        sets = sets.toMutableList().also { ss ->
            ss[at.first] = set.copy(cards = set.cards.toMutableList().also { it[at.second] = card })
        },
    )
}

// -- the variations --------------------------------------------------------

/** The turn structure's mode: `SHARED` or `PER_PLAYER` -- the seat-advantage
 *  lever (flipping it moves the seat rate ~15-18 points and reverses its sign). */
fun GameDoc.varyTurnMode(mode: String): Varied {
    refuseUnlessFork()?.let { return Varied.Refused(it) }
    val want = TurnMode.entries.firstOrNull { it.name.equals(mode, ignoreCase = true) }
        ?: return Varied.Refused(
            "no turn mode \"$mode\" -- expected one of ${TurnMode.entries.joinToString(", ") { it.name }}",
        )
    val was = rules.turn.mode
    if (was == want) return Varied.Refused("turn mode is already ${was.name} -- nothing to vary")
    return Varied.Ok(
        copy(rules = rules.copy(turn = rules.turn.copy(mode = want))),
        what = "turn mode",
        before = was.name,
        after = want.name,
    )
}

/** `attackDelayOnEntry`: whether a permanent may attack the turn it arrives.
 *  Null for seat balance, still a lever on a card's value. */
fun GameDoc.varyAttackDelay(on: Boolean): Varied {
    refuseUnlessFork()?.let { return Varied.Refused(it) }
    val was = rules.params.attackDelayOnEntry
    if (was == on) return Varied.Refused("attackDelayOnEntry is already $was -- nothing to vary")
    return Varied.Ok(
        copy(rules = rules.copy(params = rules.params.copy(attackDelayOnEntry = on))),
        what = "attackDelayOnEntry",
        before = "$was",
        after = "$on",
    )
}

/** A card's mana cost, as `resource -> amount` (`""` is generic). Replaces the
 *  mana map wholesale (no stray pip survives); the rest of the `Cost` is card
 *  text and untouched. */
fun GameDoc.varyCardCost(key: String, mana: Map<String, Int>): Varied = editCard(key) { card ->
    if (mana.values.any { it < 0 }) {
        return@editCard Varied.Refused("a cost cannot be negative: $mana")
    }
    val was = card.cost.mana
    if (was == mana) return@editCard Varied.Refused("${card.faces.first().name} already costs $mana -- nothing to vary")
    Varied.Ok(
        replaceCard(key, card.copy(cost = card.cost.copy(mana = mana))),
        what = "${card.faces.first().name} cost",
        before = "$was",
        after = "$mana",
    )
}

/** A stat field on a card's first face. Refuses a field the card's types do not
 *  declare and the card does not carry -- the Creator editor's own rule --
 *  because such a write succeeds and changes nothing the engine reads. */
fun GameDoc.varyCardField(key: String, field: String, value: Int): Varied = editCard(key) { card ->
    val face = card.faces.firstOrNull()
        ?: return@editCard Varied.Refused("that card has no faces")
    val allowed = editableFields(rules.fieldsForTypes(face.types), face.fields.keys, emptyList())
    if (field !in allowed) {
        return@editCard Varied.Refused(
            "\"${face.name}\" has no field \"$field\" -- its types (${face.types.joinToString(", ")}) declare " +
                (if (allowed.isEmpty()) "none" else allowed.joinToString(", ")) +
                ". Declare it on the type first, or vary a field it has.",
        )
    }
    val was = face.fields[field]
    if (was == value) {
        return@editCard Varied.Refused("${face.name}'s $field is already $value -- nothing to vary")
    }
    Varied.Ok(
        replaceCard(key, card.copy(faces = card.faces.toMutableList().also { it[0] = face.copy(fields = face.fields + (field to value)) })),
        what = "${face.name} $field",
        before = was?.toString() ?: "(unset)",
        after = "$value",
    )
}

/** The mana cost of one ACTIVATED ability on a card's first face -- a separate
 *  lever from the cast cost, because it is paid every turn. Frequency is priced
 *  by cost: `exhaust` alone is free on a source with nothing else to do, and
 *  adding `{1}` has moved a set more than deleting the ability. */
fun GameDoc.varyAbilityCost(key: String, index: Int, mana: Map<String, Int>): Varied = editCard(key) { card ->
    val face = card.faces.firstOrNull() ?: return@editCard Varied.Refused("that card has no faces")
    val ab = face.activated.getOrNull(index)
        ?: return@editCard Varied.Refused(
            "\"${face.name}\" has ${face.activated.size} activated abilit" +
                (if (face.activated.size == 1) "y" else "ies") + " -- no index $index",
        )
    if (mana.values.any { it < 0 }) return@editCard Varied.Refused("a cost cannot be negative: $mana")
    val was = ab.cost.mana
    if (was == mana) {
        return@editCard Varied.Refused("${face.name}'s ability $index already costs $mana -- nothing to vary")
    }
    Varied.Ok(
        replaceCard(
            key,
            card.copy(
                faces = card.faces.toMutableList().also { fs ->
                    fs[0] = face.copy(
                        activated = face.activated.toMutableList().also {
                            it[index] = ab.copy(cost = ab.cost.copy(mana = mana))
                        },
                    )
                },
            ),
        ),
        what = "${face.name} ability $index cost",
        before = "$was",
        after = "$mana",
    )
}

/** A counter a permanent ENTERS PLAY with (a Station's hull, its store) -- the
 *  starting resource a card brings, often both loss condition and currency. */
fun GameDoc.varyEntersWith(key: String, kind: String, value: Int): Varied = editCard(key) { card ->
    val name = card.faces.firstOrNull()?.name ?: key
    val at = card.entersWith.indexOfFirst { it.kind == kind }
    if (at < 0) {
        return@editCard Varied.Refused(
            "\"$name\" does not enter with any \"$kind\" -- it has " +
                (if (card.entersWith.isEmpty()) "none" else card.entersWith.joinToString(", ") { it.kind }),
        )
    }
    // `CounterDef.initial` is an IntExpr, not an Int -- a Station could enter
    // with a COMPUTED hull. Comparing and writing through `lit` keeps that
    // possible instead of quietly flattening it.
    val was = card.entersWith[at].initial
    val want = ccg.lit(value)
    if (was == want) return@editCard Varied.Refused("$name already enters with $value $kind -- nothing to vary")
    Varied.Ok(
        replaceCard(
            key,
            card.copy(entersWith = card.entersWith.toMutableList().also { it[at] = it[at].copy(initial = want) }),
        ),
        what = "$name enters-with $kind",
        before = "$was",
        after = "$value",
    )
}

/** Every variation an agent may ask for, for a help string and for the MCP
 *  tool description. Kept beside the functions so a new variation that is not
 *  listed here is visible as an omission. */
val VARIATIONS: List<String> = listOf(
    "turn_mode <SHARED|PER_PLAYER>",
    "attack_delay <true|false>",
    "card_cost <card> <resource>=<n>[,<resource>=<n>...]   (\"\" or 'any' = generic)",
    "card_field <card> <field> <n>",
    "ability_cost <card> <index> <resource>=<n>[,...]   (an ACTIVATED ability's price)",
    "enters_with <card> <counter> <n>                   (a Station's hull, store, ...)",
)
