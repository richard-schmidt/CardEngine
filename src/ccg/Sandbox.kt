package ccg

// ---------------------------------------------------------------------------
// The sandbox's table edits.
//
// An edit is an ANSWER (`Answer.Edit`), so a session carries it like any other
// move: undo drops it, resume and replay re-apply it, and it saves as data --
// references only, by the Answer.kt rule. The engine accepts one only in a
// sandbox run (`Run.sandbox`) and only at a priority question, where the
// window's checkpoint IS the question's state (Machine.kt): it edits that
// checkpoint and asks the same question again. Anywhere else an edit would be
// lost the next time the frame replays from its checkpoint.
//
// No deck legality, no costs, no timing: a card goes where it is put. Putting
// a card onto the table is not "entering play" by the game's rules, so it
// triggers nothing; removing one sends it to exile the way the game would, so
// what watches for leaving play still sees it.
// ---------------------------------------------------------------------------

/** Where an edit puts a card. */
enum class EditZone { HAND, BATTLEFIELD, GRAVEYARD, LIBRARY_TOP, EXILE }

/** The zone a permanent leaving play goes to, for a move off the table. */
internal fun EditZone.leavingTo(): HiddenZone? = when (this) {
    EditZone.HAND -> HiddenZone.HAND
    EditZone.GRAVEYARD -> HiddenZone.GRAVEYARD
    EditZone.LIBRARY_TOP -> HiddenZone.LIBRARY
    EditZone.EXILE -> HiddenZone.EXILE
    EditZone.BATTLEFIELD -> null
}

sealed interface TableEdit {
    /** A new copy of the card keyed [card], for [owner], into [to]. */
    data class Conjure(val card: String, val owner: PlayerId, val to: EditZone) : TableEdit

    /** The object [id] -- in play, or a card in a hand, graveyard, library
     *  or exile -- goes to [to]. It stays [owner]'s: the owner of the zone it
     *  is in, or of the permanent. A card in exile names no owner, so moving
     *  one out takes [owner]. */
    data class Move(val id: ObjectId, val to: EditZone, val owner: PlayerId? = null) : TableEdit

    /** A counter set to [value]: on the permanent [id], or else on [player]
     *  (life and the other player totals). */
    data class SetCounter(val kind: String, val value: Int, val player: PlayerId? = null, val id: ObjectId? = null) : TableEdit

    /** [player] draws [n], by the game's own draw. */
    data class Draw(val player: PlayerId, val n: Int) : TableEdit
}

/** One line for the log and the UI. */
fun TableEdit.describe(): String = when (this) {
    is TableEdit.Conjure -> "sandbox: $card into $owner's ${to.label()}"
    is TableEdit.Move -> "sandbox: #$id to ${to.label()}"
    is TableEdit.SetCounter -> "sandbox: ${id?.let { "#$it" } ?: player}'s $kind set to $value"
    is TableEdit.Draw -> "sandbox: $player draws $n"
}

/** "library top", "exile", ... */
fun EditZone.label(): String = name.lowercase().replace('_', ' ')

/** The part of an edit that is only list and map surgery. Moving a
 *  permanent off the table and `Draw` go through the engine (`Engine.edited`),
 *  which knows leaving play and drawing. Null when the edit names nothing. */
internal fun GameState.editZones(edit: TableEdit, rules: Rules): GameState? = when (edit) {
    is TableEdit.Conjure -> {
        val card = rules.cards[edit.card] ?: return null
        if (edit.owner !in players) return null
        if (edit.to == EditZone.BATTLEFIELD) {
            enterBattlefield(card, edit.owner, this).first
        } else {
            val (id, s) = allocId()
            s.putInto(CardRef(id, edit.card), edit.owner, edit.to)
        }
    }
    is TableEdit.Move -> {
        // A card in a zone; a permanent is the engine's.
        val holder = players.entries.firstOrNull { (_, p) ->
            (p.hand + p.graveyard + p.library).any { it.instanceId == edit.id }
        }?.key
        val ref = holder?.let { h -> players.getValue(h).let { p -> (p.hand + p.graveyard + p.library).first { it.instanceId == edit.id } } }
            ?: exile.firstOrNull { it.instanceId == edit.id }
            ?: return null
        val owner = edit.owner ?: holder ?: return null
        if (owner !in players) return null
        fun drop(l: List<CardRef>) = l.filterNot { it.instanceId == edit.id }
        val lifted = copy(
            players = players.mapValues { (_, p) -> p.copy(hand = drop(p.hand), graveyard = drop(p.graveyard), library = drop(p.library)) },
            exile = drop(exile),
        )
        if (edit.to == EditZone.BATTLEFIELD) {
            // A new permanent, by the game's own entering; the card keeps no id.
            val card = rules.cards[ref.cardId] ?: return null
            enterBattlefield(card, owner, lifted).first
        } else {
            lifted.putInto(ref, owner, edit.to)
        }
    }
    is TableEdit.SetCounter -> when {
        edit.id != null -> battlefield[edit.id]?.let { perm ->
            copy(battlefield = battlefield + (edit.id to perm.copy(counters = perm.counters + (edit.kind to edit.value))))
        }
        edit.player != null -> players[edit.player]?.let { p ->
            copy(players = players + (edit.player to p.copy(counters = p.counters + (edit.kind to edit.value))))
        }
        else -> null
    }
    is TableEdit.Draw -> null
}

/** [ref] into [owner]'s [to] zone (not the table). The library's top is the
 *  front of its list; exile is shared. */
private fun GameState.putInto(ref: CardRef, owner: PlayerId, to: EditZone): GameState {
    val p = players.getValue(owner)
    val q = when (to) {
        EditZone.HAND -> p.copy(hand = p.hand + ref)
        EditZone.GRAVEYARD -> p.copy(graveyard = p.graveyard + ref)
        EditZone.LIBRARY_TOP -> p.copy(library = listOf(ref) + p.library)
        EditZone.EXILE, EditZone.BATTLEFIELD -> p
    }
    return copy(players = players + (owner to q), exile = if (to == EditZone.EXILE) exile + ref else exile)
}

// -- JSON ------------------------------------------------------------------

internal fun tableEditToJson(e: TableEdit): String = when (e) {
    is TableEdit.Conjure -> """{"k":"conjure","card":${jstr(e.card)},"owner":${jstr(e.owner)},"to":${jstr(e.to.name)}}"""
    is TableEdit.Move -> """{"k":"move","id":${e.id},"to":${jstr(e.to.name)}""" + (e.owner?.let { ""","owner":${jstr(it)}""" } ?: "") + "}"
    is TableEdit.SetCounter -> """{"k":"counter","kind":${jstr(e.kind)},"v":${e.value}""" +
        (e.player?.let { ""","p":${jstr(it)}""" } ?: "") + (e.id?.let { ""","id":$it""" } ?: "") + "}"
    is TableEdit.Draw -> """{"k":"draw","p":${jstr(e.player)},"n":${e.n}}"""
}

internal fun tableEditOf(j: Json): TableEdit = j.obj().let { o ->
    when (val k = o.req("k").str()) {
        "conjure" -> TableEdit.Conjure(o.req("card").str(), o.req("owner").str(), EditZone.valueOf(o.req("to").str()))
        "move" -> TableEdit.Move(o.req("id").int(), EditZone.valueOf(o.req("to").str()), o["owner"]?.str())
        "counter" -> TableEdit.SetCounter(o.req("kind").str(), o.req("v").int(), o["p"]?.str(), o["id"]?.int())
        "draw" -> TableEdit.Draw(o.req("p").str(), o.req("n").int())
        else -> error("unknown table edit '$k'")
    }
}
