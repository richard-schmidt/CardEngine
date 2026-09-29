package ccg

// ---------------------------------------------------------------------------
// The words the engine itself knows. No keyword has an engine-coded rule any
// more: a keyword means something in a game when its combat program or
// one of its cards tests for it (`keywordsWithRules`). `Keyword` survives as
// the vocabulary the lowering of an old `CombatConfig` writes its programs in
// (Lower.kt), and as the Creator's suggestions.
// ---------------------------------------------------------------------------

/** The keywords the lowered legacy combat programs test for. `names` are the
 *  spellings that carry one; the first is canonical ("piercing" is the
 *  Yu-Gi-Oh spelling of trample). */
enum class Keyword(vararg val names: String) {
    /** Any damage it deals in combat is lethal. */
    DEATHTOUCH("deathtouch"),
    /** Excess combat damage carries through to the defender. "piercing" is the
     *  Yu-Gi-Oh spelling of the same rule. */
    TRAMPLE("trample", "piercing"),
    /** Can't be blocked except by two or more blockers. */
    MENACE("menace"),
    /** Attacking doesn't exhaust it. */
    VIGILANCE("vigilance"),
    /** May attack twice a turn (INDIVIDUAL combat). */
    WINDFURY("windfury"),
    /** Can't attack. */
    DEFENDER("defender"),
    ;

    companion object {
        val allNames: Set<String> = entries.flatMap { it.names.toList() }.toSet()
    }
}

fun Characteristics.has(k: Keyword): Boolean = k.names.any { it in keywords }

/** Under `CombatConfig.onlyExhaustedTargets`, a permanent of this type may be
 *  attacked even while ready (Star Wars Unlimited's Leader). */
const val LEADER_TYPE = "Leader"

/** What a `RuleMod.Cant` can forbid. The doc stores the `key` string -- it is
 *  the saved format -- and `GameDoc.problems()` reports one the engine does not
 *  know, instead of it silently forbidding nothing. */
enum class RuleAction(val key: String) {
    GAIN_LIFE("gainLife"),
    DRAW("draw"),
    CAST("cast"),
    ;

    companion object {
        fun of(key: String): RuleAction? = entries.firstOrNull { it.key == key }
    }
}

/** The keywords a combat's programs test for: every `HasKeyword` in
 *  them -- first strike, reach, taunt, a block rule's flying. */
fun Combat.keywordsNamed(): Set<String> {
    val found = mutableSetOf<String>()
    val s = object : Subst() {
        override fun rewrite(e: BoolExpr): BoolExpr = e.also { if (it is BoolExpr.HasKeyword) found += it.keyword }
    }
    program?.subst(s); attack?.subst(s)
    return found
}

/** Every keyword something in this game reads: the ones its combat tests
 *  for, the ones its cards test for, and its deck-identity vocabulary. A
 *  keyword on a card outside this set does nothing. */
fun GameDoc.keywordsWithRules(): Set<String> =
    rules.combat.compile().keywordsNamed() + keywordsCardsRead() + deckRules.identity?.vocabulary.orEmpty()
