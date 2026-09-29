package ccg

// ---------------------------------------------------------------------------
// Opening state: `newGame` (players, libraries, counters, seed) and
// `setupGame` (the full, rules-driven setup).
// ---------------------------------------------------------------------------

/** Two (or more) players, a starting life total, and an optional library per
 *  player (as CardRefs). */
fun newGame(
    order: List<PlayerId> = listOf("P0", "P1"),
    startingLife: Int = 20,
    libraries: Map<PlayerId, List<CardRef>> = emptyMap(),
    /** The counters every player starts with. Defaults to `startingLife` life
     *  so every existing caller and test is unaffected. */
    counters: Map<String, Int>? = null,
    /** Seeds the shuffler. Fixed by default so every test is reproducible;
     *  a real game picks one and records it. */
    seed: Int = 0x9E3779B9.toInt(),
    /** `Rules.damageCounter`, baked into the resulting state once -- see
     *  `GameState.damageCounter`'s doc comment for why this can't just be a
     *  live `Rules` reference. */
    damageCounter: String? = LIFE,
): GameState {
    val players = order.associateWith { pid ->
        Player(
            id = pid,
            library = libraries[pid] ?: emptyList(),
            counters = counters ?: mapOf(LIFE to startingLife),
        )
    }
    // Battlefield objects get ids from 1; library CardRefs live in a disjoint
    // high range (see `tokens`) so a permanent's id is deterministic regardless
    // of how big the libraries are.
    return GameState(
        players = players,
        turnOrder = order,
        activePlayer = order.first(),
        nextObjectId = 1,
        rngState = seed,
        damageCounter = damageCounter,
    )
}

/** A throwaway library of N opaque cards for deck-out / draw tests. Ids sit
 *  well above any permanent id a short test allocates. */
fun tokens(n: Int, startId: Int = 100_000): List<CardRef> =
    (0 until n).map { CardRef(startId + it, "token") }

/** Shuffle every player's opening library. Explicit, so that "the deck order
 *  is stable unless shuffled" stays literally true -- nothing else reorders
 *  a library behind your back. */
fun GameState.shuffleAll(): GameState = players.keys.fold(this) { s, pid -> s.shuffleZone(pid) }

/** A seat's deck: an index into the game's `decks`. An index that names no
 *  deck, or a deck with no cards, plays 40 opaque tokens instead, so a game
 *  with no decks yet can still be opened. [NONE] is no deck at all: an empty
 *  library, and nothing begins in play (the sandbox's empty table). */
data class DeckPick(val deck: Int) {
    companion object {
        const val NONE = -1
    }
}

/** The seats dealt no deck, whose empty library is not a loss (`Run.undecked`). */
fun undeckedSeats(seats: List<DeckPick>): Set<PlayerId> =
    seatIds(seats.size).filterIndexed { i, _ -> seats[i].deck == DeckPick.NONE }.toSet()

/** The player ids of [n] seats: `P0`, `P1`, ... */
fun seatIds(n: Int): List<PlayerId> = List(n) { "P$it" }

/** THE start of a game, for every client: the Player, the playtest, the
 *  agent and the corpus. Seat i is player `P<i>` and plays `seats[i]`; its
 *  library ids start at `(i + 1) * 100_000`, the ranges the corpus depends
 *  on. [order] varies who acts first without moving a deck. */
fun startGame(
    doc: GameDoc,
    rules: Rules,
    seats: List<DeckPick>,
    seed: Int,
    order: List<PlayerId> = seatIds(seats.size),
    shuffle: Boolean = true,
): GameState {
    val ids = seatIds(seats.size)
    val decks = seats.map { doc.decks.getOrNull(it.deck) }
    return setupGame(
        rules = rules,
        libraries = ids.withIndex().associate { (i, pid) ->
            val base = (i + 1) * 100_000
            pid to if (seats[i].deck == DeckPick.NONE) emptyList() else (decks[i]?.libraryFor(base) ?: emptyList()).ifEmpty { tokens(40, base) }
        },
        order = order,
        seed = seed,
        shuffle = shuffle,
        startInPlay = ids.withIndex().mapNotNull { (i, pid) -> decks[i]?.let { pid to doc.deckRules.opening(it, 0).second } }.toMap(),
    )
}

/** Set a game up the way its rules say: player counters, the chosen decks as
 *  libraries, a shuffle, the SLOT CARDS placed (a Leader / Base / Hero begins
 *  on the board), then the opening hand. `startId` per seat keeps library ids
 *  disjoint.
 *
 *  `startInPlay` per seat comes from `DeckRules.opening(deck, startId).second`;
 *  each name resolves through `rules.cards`. A card that begins in play fires
 *  no EntersPlay (it started there); it just gets its statics and triggers. */
fun setupGame(
    rules: Rules,
    libraries: Map<PlayerId, List<CardRef>> = emptyMap(),
    order: List<PlayerId> = listOf("P0", "P1"),
    seed: Int = 0x9E3779B9.toInt(),
    shuffle: Boolean = true,
    startInPlay: Map<PlayerId, List<StartCard>> = emptyMap(),
): GameState {
    var s = newGame(
        order = order, libraries = libraries, counters = rules.startingCounters(), seed = seed,
        damageCounter = rules.damageCounter,
    )
    s = s.placed(rules)
    if (shuffle) s = s.shuffleAll()
    if (startInPlay.values.any { it.isNotEmpty() }) {
        s = order.fold(s) { acc, pid ->
            startInPlay[pid].orEmpty().fold(acc) { a, sc ->
                val def = rules.cards[sc.cardName]
                when {
                    def == null -> a.logged("$pid: no card \"${sc.cardName}\" to start in play")
                    // `sc.zone` names a declared HIDDEN zone (a
                    // Flagship pool), not a play zone -- the card sits there
                    // as a CardRef, unplayed, until it is actually cast.
                    sc.zone in rules.hiddenZones -> {
                        val (cardId, a2) = a.allocId()
                        a2.withCustomZone(pid, sc.zone, listOf(CardRef(cardId, sc.cardName)))
                            .logged("$pid starts with ${def.name} available from ${sc.zone}")
                    }
                    else -> {
                        val zone = rules.resolveZone(sc.zone.ifBlank { "battlefield" }, pid)
                        val (ns, id) = enterBattlefield(def, pid, a, 0, zone)
                        var s2 = ns.logged("$pid starts with ${def.name} #$id in play")
                        // A slot card's own `entersWith` counter seeds the
                        // matching declared player counter (starting values
                        // come from the card chosen, not the ruleset).
                        val perm = s2.battlefield[id]
                        if (perm != null) {
                            for ((kind, amount) in perm.counters) {
                                if (rules.playerCounters.any { it.name == kind }) {
                                    val p = s2.players.getValue(pid)
                                    s2 = s2.copy(players = s2.players + (pid to p.withCounter(kind, amount)))
                                        .logged("$pid's $kind set to $amount by ${def.name}")
                                }
                            }
                        }
                        s2
                    }
                }
            }
        }
    }
    val n = rules.params.startingHandSize
    if (n > 0) {
        s = order.fold(s) { acc, pid ->
            val p = acc.players.getValue(pid)
            val drawn = p.library.take(n)
            acc.copy(players = acc.players + (pid to p.copy(library = p.library.drop(n), hand = p.hand + drawn)))
        }.logged("opening hands: $n card(s) each")
    }
    return s
}
