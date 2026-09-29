package ccgui

import ccg.BUILTIN_TYPES
import ccg.CardDoc
import ccg.Cost
import ccg.Effect
import ccg.GameDoc
import ccg.RulesDoc

// ---------------------------------------------------------------------------
// THE POOL INSTRUMENT. Pure measurement over a `GameDoc` -- no Compose, no
// `Rules` build (`rules()` can throw on a half-authored game, and authoring is
// when this is wanted). It makes the design register's invariants
// measured instead of asserted.
//
// Two user decisions shape it; do not "tidy" them away:
//
//  1. REPORT, NEVER JUDGE. [RateReport] carries the distribution and each
//     card's distance from the mean, with no verdict vocabulary. Whether the
//     rate is right is a design question this informs.
//  2. BOTH PREDICATES, REPORTED SEPARATELY. The drafted fungibility predicate
//     and its replacement ([crowdGroups]) ship side by side, so where they
//     disagree is visible.
// ---------------------------------------------------------------------------

/** What part a card plays in the decks this game declares: `entries` is the
 *  library you draw from, `slots` names designated cards that start outside it.
 *  The partition that makes a cost curve honest -- measured flat, slot cards and
 *  zero-cost anchors turn an ordinary descending curve into a false plateau.
 *  [UNUSED] (in no deck at all) is a real finding. */
enum class DeckRole { MAIN, SLOT, UNUSED }

/** One card, measured. Everything downstream is an aggregate over these, so a
 *  new measure is usually a new field here and a fold in [poolReport]. */
data class CardStat(
    /** `CardDoc.key()` -- the stable id, falling back to the display name.
     *  Never the bare name, which decks do not reference. */
    val key: String,
    val name: String,
    val role: DeckRole,
    val types: Set<String>,
    /** Generic mana only -- the number a curve is drawn against. */
    val mana: Int,
    /** Typed resource costs, keyed by resource. Separate from [mana] because a
     *  typed cost is a restriction, not an amount. */
    val typedMana: Map<String, Int>,
    /** Which non-mana cost features this card uses, by name: `payFrom`,
     *  `payLife`, `tapSource`, `sacrificeSource`, `additional`, `removeCounters`,
     *  `usesX`, `alternatives`. The doc's founding bug was counting a stat line
     *  as one of these, so they are enumerated explicitly rather than inferred. */
    val costTerms: Set<String>,
    /** Does any of this card's types DECLARE stat fields? Load-bearing: without
     *  it every Manoeuvre and Doctrine reads as zero stats and is filed as the
     *  pool's worst outlier. */
    val declaresFields: Boolean,
    val fields: Map<String, Int>,
    val statTotal: Int,
    /** Rules text, counted as terms: triggers, statics, rule mods, cost mods,
     *  replacements, activated abilities, and a non-`NoOp` cast effect. */
    val textTerms: Int,
    val keywords: Set<String>,
) {
    /** Stats per generic mana, or null where the question is meaningless --
     *  a card with no declared fields, or a free card (division by zero, and
     *  "infinite value" is not a reading anyone wants). */
    val perMana: Double? get() =
        if (!declaresFields || mana <= 0) null else statTotal.toDouble() / mana
}

/** Cards at the same type and cost that are hard to tell apart, reported as a
 *  GROUP (a card resembling nothing is doing its job). A group of two differing
 *  by one keyword is a strictly-worse pair -- the anti-slop invariant's target. */
data class CrowdGroup(
    val type: String,
    val mana: Int,
    /** `CardStat.key` of each member, in the order the set declares them. */
    val members: List<String>,
    /** The largest pairwise distance inside the group. A group held together at
     *  distance 1 is tighter than one held together at the threshold. */
    val worst: Int,
)

/** The stats-per-mana distribution. Carries no verdict -- see decision (1). */
data class RateReport(
    /** Mean stats per generic mana across every card the question applies to. */
    val mean: Double,
    /** Per card (`CardStat.key`), how far its rate sits from [mean].
     *  Signed: above the mean is positive. */
    val distance: Map<String, Double>,
    /** How many cards the mean was taken over. A mean over three cards is not
     *  a rate, and the reader needs to be able to see that. */
    val n: Int,
)

/** Everything [poolReport] measures. */
data class PoolReport(
    val cards: List<CardStat>,
    /** Deck role -> generic mana -> how many cards. */
    val curve: Map<DeckRole, Map<Int, Int>>,
    /** Number of text terms -> how many cards carry exactly that many. */
    val density: Map<Int, Int>,
    /** Declared field name -> every value seen for it, ascending. The shape of
     *  a field across the pool, which is what "is hull spread or clustered"
     *  actually asks. */
    val fieldSpread: Map<String, List<Int>>,
    /** Cost-term name -> how many cards use it. */
    val costTermUse: Map<String, Int>,
    /** Keyword -> how many cards carry it. A pool with one keyword on two cards
     *  cannot be crediting keywords as differentiation, which is worth being
     *  able to see rather than assume. */
    val keywordUse: Map<String, Int>,
    /** The design doc's drafted predicate, kept verbatim. See [fungible]. */
    val fungible: List<String>,
    /** The proposed replacement. See [CrowdGroup]. */
    val crowded: List<CrowdGroup>,
    val rate: RateReport,
) {
    fun card(key: String): CardStat? = cards.firstOrNull { it.key == key }
}

/** Distance at or under which two cards count as hard to tell apart. Calibrated
 *  on real near-duplicates in the Core (same stat line, one keyword or one
 *  ability apart = 2); higher starts merging cards that genuinely differ. */
const val CROWD_THRESHOLD: Int = 2

/** How far apart two cards are: a crude, EXPLAINABLE count, readable back as a
 *  sentence ("same line, one keyword apart"). A stat point weighs 1; any
 *  structural difference (keyword, ability, cost term, typed resource) weighs 2,
 *  encoding the register's position that a TERM differentiates more than a
 *  stat tweak. The main number to argue with. */
fun distanceBetween(a: CardStat, b: CardStat): Int {
    val keys = a.fields.keys + b.fields.keys
    val statDiff = keys.sumOf { k ->
        kotlin.math.abs((a.fields[k] ?: 0) - (b.fields[k] ?: 0))
    }
    val costDiff = (a.costTerms - b.costTerms).size + (b.costTerms - a.costTerms).size
    val kwDiff = (a.keywords - b.keywords).size + (b.keywords - a.keywords).size
    val textDiff = kotlin.math.abs(a.textTerms - b.textTerms)
    val manaDiff = a.typedMana.keys.union(b.typedMana.keys).sumOf { k ->
        kotlin.math.abs((a.typedMana[k] ?: 0) - (b.typedMana[k] ?: 0))
    }
    return statDiff + 2 * (costDiff + kwDiff + textDiff + manaDiff)
}

/** The drafted fungibility predicate, encoded verbatim:
 *
 *  > A card is fungible when all of: its cost is pure generic mana (no typed
 *  > resource, no `payFrom`, no `payLife`, no additional cost); its fields are
 *  > exactly its types' declared defaults; and it carries no trigger, static,
 *  > rule mod, replacement, activated ability or cast effect beyond `NoOp`.
 *
 *  One deviation: `TypeDef.fields` has no defaults, so "fields are the types'
 *  defaults" reads as "declares no field its types do not carry". Kept beside
 *  [crowdGroups] because it flags only vanilla bodies, which is why the
 *  replacement exists. */
fun isFungible(c: CardStat, declaredFieldsFor: (Set<String>) -> Set<String>): Boolean {
    if (c.typedMana.isNotEmpty()) return false
    if (c.costTerms.isNotEmpty()) return false
    if (c.textTerms > 0) return false
    if (c.keywords.isNotEmpty()) return false
    val declared = declaredFieldsFor(c.types)
    return c.fields.keys.all { it in declared }
}

/** Group cards that are hard to tell apart, within a type and a cost. CONNECTED
 *  COMPONENTS at [threshold], not cliques (the slot is the unit judged); a chain
 *  can merge, so [CrowdGroup.worst] carries the widest pair in the group. */
fun crowdGroups(cards: List<CardStat>, threshold: Int = CROWD_THRESHOLD): List<CrowdGroup> {
    val out = ArrayList<CrowdGroup>()
    // Only cards whose types declare stat fields: a fieldless card's identity
    // lives inside an `Effect` this metric cannot read, and every pair of them
    // would score distance 0.
    val comparable = cards.filter { it.declaresFields }
    // Bucket by primary type + generic mana: comparing a 2-cost Ship against a
    // 2-cost Doctrine is not a question anyone asked.
    val buckets = comparable.groupBy { (it.types.firstOrNull() ?: "") to it.mana }
    for ((bucket, members) in buckets) {
        if (members.size < 2) continue
        val seen = HashSet<String>()
        for (seed in members) {
            if (seed.key in seen) continue
            // Walk the component from this seed.
            val comp = ArrayList<CardStat>()
            val queue = ArrayDeque<CardStat>()
            queue.add(seed)
            seen.add(seed.key)
            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                comp.add(cur)
                for (other in members) {
                    if (other.key in seen) continue
                    if (distanceBetween(cur, other) <= threshold) {
                        seen.add(other.key)
                        queue.add(other)
                    }
                }
            }
            if (comp.size < 2) continue
            var worst = 0
            for (i in comp.indices) for (j in i + 1 until comp.size) {
                val d = distanceBetween(comp[i], comp[j])
                if (d > worst) worst = d
            }
            out.add(CrowdGroup(bucket.first, bucket.second, comp.map { it.key }, worst))
        }
    }
    // Biggest crowd first: that is the slot most worth looking at.
    return out.sortedWith(compareByDescending<CrowdGroup> { it.members.size }.thenBy { it.mana })
}

/** Which cost features a cost uses, by name. Enumerated rather than inferred:
 *  the miscount this whole feature exists because of was a script deciding what
 *  counted as a cost term on its own. */
private fun Cost.termNames(): Set<String> {
    val s = LinkedHashSet<String>()
    if (usesX) s.add("usesX")
    if (additional != null) s.add("additional")
    if (tapSource) s.add("tapSource")
    if (sacrificeSource) s.add("sacrificeSource")
    if (payLife > 0) s.add("payLife")
    if (removeCounters != null) s.add("removeCounters")
    if (alternatives.isNotEmpty()) s.add("alternatives")
    if (payFrom != null) s.add("payFrom")
    // An alternative's own cost terms count (a `payFrom` nested in an
    // alternative is the Core's identity mechanic). One level only, as the
    // engine honours.
    for (a in alternatives) {
        if (a.usesX) s.add("usesX")
        if (a.additional != null) s.add("additional")
        if (a.tapSource) s.add("tapSource")
        if (a.sacrificeSource) s.add("sacrificeSource")
        if (a.payLife > 0) s.add("payLife")
        if (a.removeCounters != null) s.add("removeCounters")
        if (a.payFrom != null) s.add("payFrom")
    }
    return s
}

/** Every declared field name for a set of type names, under these rules. */
private fun RulesDoc.declaredFields(types: Set<String>): Set<String> {
    val all = BUILTIN_TYPES + extraTypes.associateBy { it.name }
    return types.flatMap { all[it]?.fields.orEmpty() }.toSet()
}

/** Measure one card. Public so a caller can measure a card mid-edit without
 *  folding the whole pool. */
fun CardDoc.cardStat(rules: RulesDoc, role: DeckRole): CardStat {
    val f = faces.firstOrNull()
    val types = f?.types.orEmpty()
    val fields = f?.fields.orEmpty()
    val declared = rules.declaredFields(types)
    val text = (f?.triggers?.size ?: 0) +
        (f?.statics?.size ?: 0) +
        (f?.ruleMods?.size ?: 0) +
        (f?.costMods?.size ?: 0) +
        (f?.replacements?.size ?: 0) +
        (f?.activated?.size ?: 0) +
        (if (f?.castEffect != null && f.castEffect != Effect.NoOp) 1 else 0)
    return CardStat(
        key = key(),
        name = f?.name ?: "",
        role = role,
        types = types,
        mana = cost.mana[""] ?: 0,
        typedMana = cost.mana.filterKeys { it.isNotEmpty() },
        costTerms = cost.termNames(),
        declaresFields = declared.isNotEmpty(),
        fields = fields,
        statTotal = fields.values.sum(),
        textTerms = text,
        keywords = f?.keywords.orEmpty(),
    )
}

/** Which role each card plays across every deck. A card in both an `entries`
 *  list and a `slots` map is [DeckRole.MAIN]: being drawable is what the curve
 *  cares about. */
fun GameDoc.deckRoles(): Map<String, DeckRole> {
    val main = HashSet<String>()
    val slot = HashSet<String>()
    for (d in decks) {
        for (e in d.entries) main.add(e.cardName)
        for (names in d.slots.values) slot.addAll(names)
    }
    val out = HashMap<String, DeckRole>()
    for (s in sets) for (c in s.cards) {
        val k = c.key()
        out[k] = when {
            k in main -> DeckRole.MAIN
            k in slot -> DeckRole.SLOT
            else -> DeckRole.UNUSED
        }
    }
    return out
}

/** Measure the whole pool. The one entry point every reader uses. */
fun GameDoc.poolReport(threshold: Int = CROWD_THRESHOLD): PoolReport {
    val roles = deckRoles()
    val cards = sets.flatMap { s ->
        s.cards.map { c -> c.cardStat(rules, roles[c.key()] ?: DeckRole.UNUSED) }
    }

    val curve = DeckRole.entries.associateWith { role ->
        cards.filter { it.role == role }
            .groupingBy { it.mana }.eachCount()
            .toSortedMap().toMap()
    }

    val density = cards.groupingBy { it.textTerms }.eachCount().toSortedMap().toMap()

    val fieldSpread = HashMap<String, MutableList<Int>>()
    for (c in cards) for ((k, v) in c.fields) fieldSpread.getOrPut(k) { ArrayList() }.add(v)

    val costTermUse = HashMap<String, Int>()
    for (c in cards) for (t in c.costTerms) costTermUse[t] = (costTermUse[t] ?: 0) + 1

    val keywordUse = HashMap<String, Int>()
    for (c in cards) for (k in c.keywords) keywordUse[k] = (keywordUse[k] ?: 0) + 1

    val rated = cards.mapNotNull { c -> c.perMana?.let { c.key to it } }
    val mean = if (rated.isEmpty()) 0.0 else rated.sumOf { it.second } / rated.size
    val rate = RateReport(
        mean = mean,
        distance = rated.associate { (k, v) -> k to (v - mean) },
        n = rated.size,
    )

    return PoolReport(
        cards = cards,
        curve = curve,
        density = density,
        fieldSpread = fieldSpread.mapValues { (_, v) -> v.sorted() },
        costTermUse = costTermUse.toSortedMap().toMap(),
        keywordUse = keywordUse.toSortedMap().toMap(),
        fungible = cards.filter { isFungible(it) { t -> rules.declaredFields(t) } }.map { it.key },
        crowded = crowdGroups(cards, threshold),
        rate = rate,
    )
}

// ---------------------------------------------------------------------------
// THE DISPLAY MODEL -- one definition, several readers. `agent/pool` and the
// Creator's Pool socket both render this, so they cannot disagree, and the
// ORDER and WORDING are testable here (the Compose screen is not).
// ---------------------------------------------------------------------------

/** One rendered line. [cardKey] non-null means the line is ABOUT a card, which
 *  is what makes it tappable in the Creator and quotable in the CLI. */
data class PoolLine(val text: String, val cardKey: String? = null)

/** A titled block of lines. [note] says what the reader is looking at; it is
 *  not decoration -- several of these measures are easy to misread, and the
 *  note is where the caveat lives. */
data class PoolSection(val title: String, val note: String, val lines: List<PoolLine>)

private fun Double.r2(): String {
    val v = kotlin.math.round(this * 100.0) / 100.0
    return if (v == v.toLong().toDouble()) "${v.toLong()}.00" else v.toString()
}

/** The report in reading order, ACTIONABLE FIRST: the parts that name cards to
 *  edit come before the parts that describe the pool. */
fun PoolReport.sections(): List<PoolSection> {
    val out = ArrayList<PoolSection>()
    fun nameOf(k: String) = card(k)?.name ?: k

    out.add(
        PoolSection(
            "Crowded slots",
            if (crowded.isEmpty()) "Nothing is hard to tell apart." else
                "Cards of the same type and cost that are hard to tell apart. " +
                    "A group of one is a card doing its job, so only groups are listed. " +
                    "Cards with no stat line are not compared -- their identity is inside an effect.",
            crowded.flatMap { g ->
                listOf(PoolLine("${g.type} at ${g.mana} -- ${g.members.size} cards, widest gap ${g.worst}")) +
                    g.members.map { k ->
                        val c = card(k)
                        val line = c?.fields?.entries?.joinToString(" ") { (f, v) -> "$f$v" }.orEmpty()
                        val kw = c?.keywords?.joinToString(" ")?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""
                        val tx = c?.textTerms?.takeIf { it > 0 }?.let { " · $it text" } ?: ""
                        PoolLine("    ${nameOf(k)}  $line$kw$tx", k)
                    }
            },
        ),
    )

    out.add(
        PoolSection(
            "Carries no distinguishing term",
            "The design doc's drafted predicate, kept so it can be compared against the above. " +
                "It can only ever flag a card with no text, so a deliberately vanilla body lands " +
                "here by construction.",
            fungible.map { PoolLine(nameOf(it), it) },
        ),
    )

    val unused = cards.filter { it.role == DeckRole.UNUSED }
    out.add(
        PoolSection(
            "In no deck",
            "Authored but referenced by no deck's entries or slots.",
            unused.map { PoolLine(it.name, it.key) },
        ),
    )

    out.add(
        PoolSection(
            "The curve",
            "By deck role. Cards you DRAW are the curve; slot cards start outside the library " +
                "and counting them together hides the shape of both.",
            DeckRole.entries.mapNotNull { role ->
                val row = curve[role].orEmpty()
                if (row.isEmpty()) null
                else PoolLine("${role.name.lowercase().padEnd(7)} " + row.entries.joinToString("  ") { "${it.key}:${it.value}" })
            },
        ),
    )

    out.add(
        PoolSection(
            "Rules text",
            "How many cards carry how many terms -- triggers, statics, rule mods, cost mods, " +
                "replacements, activated abilities, and a cast effect.",
            density.map { (terms, n) -> PoolLine("$terms term${if (terms == 1) "" else "s"}: $n cards") },
        ),
    )

    val rated = cards.filter { it.perMana != null }.sortedBy { it.perMana }
    out.add(
        PoolSection(
            "Stats per mana",
            "Reported, not judged: whether a tight rate is costing discipline or unwanted " +
                "sameness is a design question this cannot answer. Cards with no stat line, and " +
                "free cards, are excluded rather than counted as zero.",
            if (rated.isEmpty()) emptyList() else
                listOf(PoolLine("mean ${rate.mean.r2()} over ${rate.n} cards")) +
                    rated.take(3).map { PoolLine("    lowest  ${it.name}  ${it.perMana!!.r2()}", it.key) } +
                    rated.takeLast(3).reversed().map { PoolLine("    highest ${it.name}  ${it.perMana!!.r2()}", it.key) },
        ),
    )

    out.add(
        PoolSection(
            "Costs and keywords",
            "What the pool actually uses. A term nothing uses is a term the game does not have.",
            costTermUse.map { (t, n) -> PoolLine("cost: $t -- $n cards") } +
                keywordUse.map { (k, n) -> PoolLine("keyword: $k -- $n cards") },
        ),
    )

    return out
}
