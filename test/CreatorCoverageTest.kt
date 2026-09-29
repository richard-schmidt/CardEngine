package ccg

import java.lang.reflect.ParameterizedType

// ---------------------------------------------------------------------------
// CAN THE CREATOR AUTHOR THIS? -- the tripwire.
//
// Walks the doc model BY REFLECTION and requires every field to be classified:
// AUTHORABLE (has a control), NOT_AUTHORABLE (the live gap list) or
// MACHINE_SET. Reflection rather than a fixture,
// which covers only what someone remembered. A new doc field fails here until
// someone decides whether it can be authored.
//
// A REGISTRY check, not a UI test: it verifies that someone asserted a control
// exists, not that it works. The failure it prevents is "nobody ever thought
// about the control".
// ---------------------------------------------------------------------------

/** The doc classes that make up an authored game. Walking stops at anything not
 *  named here, so a new doc TYPE must be added -- and `assertReachable` below
 *  makes forgetting that a failure rather than a silent hole. */
private val DOC_CLASSES: Map<Class<*>, String> = mapOf(
    GameDoc::class.java to "GameDoc",
    RulesDoc::class.java to "RulesDoc",
    SetDoc::class.java to "SetDoc",
    CardDoc::class.java to "CardDoc",
    FaceDoc::class.java to "FaceDoc",
    DeckDoc::class.java to "DeckDoc",
    DeckEntry::class.java to "DeckEntry",
    DeckRules::class.java to "DeckRules",
    DeckSlotDef::class.java to "DeckSlotDef",
    IdentityRule::class.java to "IdentityRule",
    StartCard::class.java to "StartCard",
    GameParams::class.java to "GameParams",
    MulliganRule::class.java to "MulliganRule",
    PlayerCounterDef::class.java to "PlayerCounterDef",
    CounterKindDef::class.java to "CounterKindDef",
    TypeDef::class.java to "TypeDef",
    PlayZoneDef::class.java to "PlayZoneDef",
    HiddenZoneDef::class.java to "HiddenZoneDef",
    TurnStructure::class.java to "TurnStructure",
    PhaseSpec::class.java to "PhaseSpec",
    Cost::class.java to "Cost",
    CounterPayment::class.java to "CounterPayment",
    ActivatedAbility::class.java to "ActivatedAbility",
    CostMod::class.java to "CostMod",
    CounterDef::class.java to "CounterDef",
    CardLayout::class.java to "CardLayout",
    Recast::class.java to "Recast",
)

/** Types that are LEAVES: authored through an editor of their own (the effect
 *  editor, the filter pill, a chip list) rather than field by field. Naming
 *  them is what lets the walk stop somewhere principled. */
private val LEAF_TYPES: Set<Class<*>> = setOf(
    java.lang.String::class.java, String::class.java,
    java.lang.Integer::class.java, Integer.TYPE,
    java.lang.Boolean::class.java, java.lang.Boolean.TYPE,
    // The AST and its friends -- each has a real editor, and their SHAPE is
    // already guarded by the caseTag tripwires and the verb-menu round trips.
    Effect::class.java, BoolExpr::class.java, IntExpr::class.java,
    PermFilter::class.java, CardFilter::class.java,
    // Sealed hierarchies with their own pickers. `CombatDoc` earned its
    // place here late: until blocker 8 its Custom case rendered as one
    // read-only line, so "has an editor of its own" was aspirational.
    ResourceModel::class.java, CombatDoc::class.java, TriggerDoc::class.java,
    // art framing is edited by DRAGGING THE ART, not by four number
    // fields, so its editor is the art box itself rather than a form over its
    // components. Walking into it would demand a control per float that nobody
    // should ever be asked to type.
    ArtRect::class.java,
    ReplacementDoc::class.java, StaticSpec::class.java, RuleMod::class.java,
    CharOp::class.java, BlockRule::class.java,
    // the one player reference, picked by the you/opponent pill (and
    // "as player"'s menu). It was the `Who` enum, a leaf by being an enum.
    PlayerRef::class.java,
)

private fun isLeaf(c: Class<*>): Boolean =
    c in LEAF_TYPES || c.isEnum || c.isPrimitive || !c.name.startsWith("ccg.")

/** Every authorable field in the doc model, as `Owner.field`. */
private fun docFields(): Set<String> {
    val out = sortedSetOf<String>()
    for ((cls, label) in DOC_CLASSES) {
        for (f in cls.declaredFields) {
            // Kotlin synthesises these; they are not authored content.
            if (f.isSynthetic || f.name.startsWith("\$") || f.name == "Companion") continue
            if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
            out += "$label.${f.name}"
        }
    }
    return out
}

/** Every ccg type REACHED by walking the doc model, so that forgetting to add a
 *  new doc class to `DOC_CLASSES` is itself a failure rather than a hole. */
private fun reachedTypes(): Set<Class<*>> {
    val out = mutableSetOf<Class<*>>()
    fun consider(t: java.lang.reflect.Type) {
        when (t) {
            is ParameterizedType -> t.actualTypeArguments.forEach { consider(it) }
            is Class<*> -> if (!isLeaf(t)) out += t
            else -> Unit
        }
    }
    for (cls in DOC_CLASSES.keys) {
        for (f in cls.declaredFields) {
            if (f.isSynthetic || java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
            consider(f.genericType)
        }
    }
    return out
}

// -- the registry ----------------------------------------------------------
// Every field above must appear in exactly one of these two sets. Adding an
// engine field without classifying it fails the check below, by name.

/** Has a real control in the Creator UI. */
private val AUTHORABLE = setOf(
    "GameDoc.name", "GameDoc.id", "GameDoc.rules", "GameDoc.sets", "GameDoc.decks",
    "GameDoc.deckRules", "GameDoc.contentVersion",
    // Its control is the art modal, opened by tapping the art box on
    // the card detail page: the solid rectangle over the picture.
    "FaceDoc.artFieldRect",
    // The dashed rectangle in the same modal -- the shrunk tile's own
    // crop, which exists only once it is switched on.
    "FaceDoc.artMiniRect",
    "RulesDoc.extraTypes", "RulesDoc.extraZones", "RulesDoc.combat", "RulesDoc.turn",
    "RulesDoc.resourceModel", "RulesDoc.playerCounters",
    // Rules > The game (blocker 1, closed). Every one of these was Kotlin-only,
    // so every UI-authored game was silently MTG-shaped.
    "RulesDoc.params",
    "GameParams.startingHandSize", "GameParams.cardsDrawnPerTurn", "GameParams.maxHandSize",
    "GameParams.playerCount", "GameParams.mulligan", "GameParams.firstPlayerSkipsFirstDraw",
    "GameParams.poolPersistsPerTurn", "GameParams.poolStoreCounter",
    // Rules > The game > deployment. An analytical parameter: whether a body
    // can fight the turn it lands changes the shape of a game, not its numbers.
    "GameParams.attackDelayOnEntry",
    "GameParams.preferredOrientation",
    "MulliganRule.redraws", "MulliganRule.bottomOnePerMulligan",
    "SetDoc.name", "SetDoc.cards",
    "CardDoc.faces", "CardDoc.entersWith", "CardDoc.text", "CardDoc.cost",
    "CardDoc.recast", "CardDoc.id",
    "FaceDoc.name", "FaceDoc.types", "FaceDoc.fields", "FaceDoc.keywords",
    "FaceDoc.castEffect", "FaceDoc.triggers", "FaceDoc.statics", "FaceDoc.ruleMods",
    "FaceDoc.activated", "FaceDoc.replacements", "FaceDoc.art",
    "DeckDoc.name", "DeckDoc.entries", "DeckDoc.slots",
    "DeckEntry.cardName", "DeckEntry.count",
    // Deck construction: sizes,
    // copy limits, slots and the identity rule all have controls.
    "DeckRules.minSize", "DeckRules.maxSize", "DeckRules.maxCopies",
    "DeckRules.slots", "DeckRules.identity",
    "DeckSlotDef.name", "DeckSlotDef.types", "DeckSlotDef.count", "DeckSlotDef.startsIn",
    "IdentityRule.from", "IdentityRule.vocabulary", "IdentityRule.allowNeutral",
    "PlayerCounterDef.name", "PlayerCounterDef.starting", "PlayerCounterDef.loseAtZero",
    "TypeDef.name", "TypeDef.isSpell", "TypeDef.attacks", "TypeDef.zoneOfPlay",
    "TypeDef.zoneChoices", "TypeDef.layout",
    // Blocker 3, closed: a type declares its own stat names by free text, and
    // a card's steppers are DERIVED from what its types declare.
    "TypeDef.fields",
    // Blocker 5, closed: what makes a permanent a LOSS CONDITION -- an SWU
    // Base, a Hearthstone Hero, an EPR Skirmish Station.
    "TypeDef.diesWhen", "TypeDef.loseOnDeath", "TypeDef.damageCounter",
    "RulesDoc.damageCounter",
    // Rules > Counters, "counters on cards".
    "RulesDoc.counterKinds", "CounterKindDef.name",
    // the "cancels" picker on each counter kind's row.
    "CounterKindDef.cancels",
    "PlayZoneDef.id", "PlayZoneDef.scope",
    "TurnStructure.phases", "TurnStructure.mode",
    "PhaseSpec.name", "PhaseSpec.onEnter", "PhaseSpec.interactive", "PhaseSpec.combat",
    "PhaseSpec.sorcerySpeed",
    "Cost.mana", "Cost.usesX", "Cost.tapSource", "Cost.sacrificeSource",
    "Cost.payLife", "Cost.removeCounters", "Cost.additional", "Cost.alternatives",
    "ActivatedAbility.name", "ActivatedAbility.cost", "ActivatedAbility.effect",
    "ActivatedAbility.oncePerTurn",
    // the ability's own speed -- does activating it open a response
    // window. Controlled beside "once per turn" in the ability editor.
    "ActivatedAbility.usesStack",
    "CounterDef.kind", "CounterDef.initial",
    "CardLayout.art", "CardLayout.statCorner", "CardLayout.statFields",
    "CardLayout.counterTrack", "CardLayout.counterKind", "CardLayout.showText",
    "CardLayout.accent",
    "Recast.from", "Recast.cost", "Recast.afterResolve",
    // The last of the ranked authoring gaps.
    "Cost.payFrom", "CounterPayment.counter", "CounterPayment.amount", "CounterPayment.filter",
    "FaceDoc.costMods", "CostMod.who", "CostMod.types", "CostMod.delta",
    "CardDoc.requires", "CardDoc.diesWhen",
    "PlayZoneDef.maxOccupants",
    // Rules > zones. The lane-step field: which combat waves a permanent
    // standing in this zone may act in. Lanes differentiated by
    // rule: position changes what a card DOES, not what a number is.
    "PlayZoneDef.combatSteps",
    "PlayerCounterDef.min", "PlayerCounterDef.max",
    "RulesDoc.extraHiddenZones", "HiddenZoneDef.id", "HiddenZoneDef.alwaysVisible",
    "TypeDef.instantSpeed", "TypeDef.usesStack",
    // The Overview's identity row (colour swatches, cover card).
    "GameDoc.accent", "GameDoc.cover",
)

/** No control exists. THE LIVE GAP LIST. Moving a line from here to AUTHORABLE is what
 *  "closing a gap" means, and this check is what stops the list regrowing. */
private val NOT_AUTHORABLE = setOf(
    // A start card is named per DECK, on the Decks screen, not declared here --
    // this is the shape of the rule, and `DeckDoc.slots` is the authorable half.
    // The only entries left, and both are correct rather than gaps.
    "StartCard.cardName", "StartCard.zone",
    // A zone's place in the grid: a real gap -- a positional game cannot be
    // authored in-app until these get controls.
    "PlayZoneDef.lane", "PlayZoneDef.depth",
)

/** Set by the machine and deliberately NOT offered to an author (fork
 *  provenance): neither "has a control" nor a gap. `GameDoc.id` would also fit
 *  here but stays in AUTHORABLE for now. */
private val MACHINE_SET = setOf(
    "GameDoc.forkedFrom", "GameDoc.forkNote",
)

internal fun creatorCoverageChecks() {

    check("creator every authorable field is classified -- a new one cannot ship unnoticed") {
        val fields = docFields()
        assertTrue(fields.size > 60, "the walk found the doc model at all (${fields.size} fields)")

        val classified = AUTHORABLE + NOT_AUTHORABLE + MACHINE_SET
        val unclassified = fields - classified
        assertTrue(
            unclassified.isEmpty(),
            "these doc fields are in none of AUTHORABLE / NOT_AUTHORABLE / MACHINE_SET -- decide " +
                "whether the Creator can author them, and classify them: $unclassified",
        )

        // And the registry may not name fields that no longer exist, or it
        // would quietly rot into fiction the way CorpusCoverage's "every
        // capability has a control" did.
        val phantom = classified - fields
        assertTrue(phantom.isEmpty(), "registry names fields that do not exist: $phantom")

        // A field cannot be in two categories -- checked pairwise, so a field
        // added to MACHINE_SET without being removed from the others fails
        // here rather than being silently counted as classified twice.
        assertTrue((AUTHORABLE intersect NOT_AUTHORABLE).isEmpty())
        assertTrue((AUTHORABLE intersect MACHINE_SET).isEmpty(), "authorable AND machine-set: ${AUTHORABLE intersect MACHINE_SET}")
        assertTrue((NOT_AUTHORABLE intersect MACHINE_SET).isEmpty(), "a gap AND machine-set: ${NOT_AUTHORABLE intersect MACHINE_SET}")

        // Bounded, so a field JOINING the gap list is a decision: two correct
        // entries (start cards, named per deck) plus the grid's lane/depth.
        assertTrue(
            NOT_AUTHORABLE.size <= 4,
            "the authoring gap list grew: ${NOT_AUTHORABLE.sorted()} -- if that is deliberate, " +
                "say so and raise this bound",
        )
    }

    check("creator the walk cannot silently stop -- every doc type it reaches is walked") {
        // Without this, adding a doc class and forgetting to list it in
        // DOC_CLASSES would hide all of its fields from the check above --
        // the hole the fixture approach would have had everywhere.
        val missing = reachedTypes() - DOC_CLASSES.keys
        assertTrue(
            missing.isEmpty(),
            "reached from the doc model but never walked: ${missing.map { it.simpleName }} " +
                "-- add to DOC_CLASSES, or to LEAF_TYPES if it has an editor of its own",
        )
    }

    check("creator a card's stat fields come from what its TYPES declare") {
        // The card's stat rows derive from `TypeDef.fields` (one definition in
        // src/ccg), so EPR Skirmish's "hull" is declarable and settable.
        val doc = RulesDoc(
            extraTypes = listOf(
                TypeDef("Ship", fields = setOf("hull", "fast", "slow")),
                TypeDef("Relic", fields = setOf("charge")),
            ),
        )
        assertEq(listOf("hull", "fast", "slow"), doc.fieldsForTypes(setOf("Ship")).sortedBy {
            listOf("hull", "fast", "slow").indexOf(it)
        })
        // A builtin type still answers, so nothing that worked before stops.
        assertEq(setOf("power", "toughness"), doc.fieldsForTypes(setOf("Creature")).toSet())
        // Multi-type cards get the union, once each.
        assertEq(
            setOf("hull", "fast", "slow", "charge"),
            doc.fieldsForTypes(setOf("Ship", "Relic")).toSet(),
        )
        assertEq(doc.fieldsForTypes(setOf("Ship", "Ship")).size, doc.fieldsForTypes(setOf("Ship")).size)
        // An undeclared type contributes nothing rather than throwing -- the
        // Creator asks this every frame while a type name is half-typed.
        assertTrue(doc.fieldsForTypes(setOf("Zeppelin")).isEmpty())

        // And the built ruleset agrees with the doc, or the editor would be
        // teaching a vocabulary the game does not have.
        val built = GameDoc(rules = doc).rules()
        assertEq(doc.fieldsForTypes(setOf("Ship")).toSet(), built.fieldsForTypes(setOf("Ship")).toSet())

        // The real measure: EPR Skirmish's own Ship, through the same call the
        // card editor makes.
        val eprs = EPR_SKIRMISH.rules
        assertTrue("hull" in eprs.fieldsForTypes(setOf("Ship")), "the shipped game's own stat is reachable")
    }

    check("creator the gap list is real -- EPR Skirmish is still unauthorable, and says why") {
        // Pinned: EPR Skirmish is
        // authorable in its own Creator.
        assertTrue(
            "TypeDef.fields" in AUTHORABLE,
            "a type can declare its own stat names, so EPRS's \"hull\" is reachable",
        )
        // Blocker 1 is closed, so this now asserts the OPPOSITE of what it did
        // when written -- which is the point: the registry is the live record.
        assertTrue("RulesDoc.params" in AUTHORABLE, "GameParams got its editor")
        // The shipped game really does depend on both.
        val shipT = EPR_SKIRMISH.rules.extraTypes.first { it.name == "Ship" }
        assertTrue("hull" in shipT.fields, "the Ship type really does declare a custom field")
    }
}
