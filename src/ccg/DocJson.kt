package ccg

// ---------------------------------------------------------------------------
// JSON for everything above the Effect tree: the authoring doc (GameDoc,
// CardDoc, FaceDoc, ...) and its declarative dependents. The "save a game to a
// file" layer.
// ---------------------------------------------------------------------------

// -- CounterDef --------------------------------------------------------

fun counterDefToJson(c: CounterDef): String = """{"kind":${jstr(c.kind)},"initial":${intExprToJson(c.initial)}}"""
internal fun counterDefOf(j: Json): CounterDef = j.obj().let { CounterDef(it.req("kind").str(), intExprOf(it.req("initial"))) }

// -- Cost / ActivatedAbility / RuleMod / CostMod ------------------------

fun costToJson(c: Cost): String = buildString {
    append("{")
    val parts = mutableListOf<String>()
    if (c.mana.isNotEmpty()) parts += "\"mana\":${manaToJson(c.mana)}"
    if (c.usesX) parts += "\"usesX\":true"
    c.additional?.let { parts += "\"additional\":${effectToJson(it)}" }
    if (c.tapSource) parts += "\"tapSource\":true"
    if (c.sacrificeSource) parts += "\"sacrificeSource\":true"
    if (c.payLife != 0) parts += "\"payLife\":${c.payLife}"
    c.removeCounters?.let { (k, n) -> parts += """"removeCounters":{"kind":${jstr(k)},"n":$n}""" }
    if (c.alternatives.isNotEmpty()) parts += "\"alternatives\":[${c.alternatives.joinToString(",") { costToJson(it) }}]"
    c.payFrom?.let {
        parts += """"payFrom":{"counter":${jstr(it.counter)},"n":${it.amount},"filter":${filterToJson(it.filter)}}"""
    }
    append(parts.joinToString(","))
    append("}")
}
internal fun costOf(j: Json): Cost {
    val o = j.obj()
    return Cost(
        mana = o["mana"]?.let { manaOf(it) } ?: emptyMap(),
        usesX = o.boolOr("usesX", false),
        additional = o["additional"]?.let { effectOf(it) },
        tapSource = o.boolOr("tapSource", false),
        sacrificeSource = o.boolOr("sacrificeSource", false),
        payLife = o.intOr("payLife", 0),
        removeCounters = o["removeCounters"]?.obj()?.let { it.req("kind").str() to it.req("n").int() },
        alternatives = o["alternatives"]?.arr()?.map { costOf(it) } ?: emptyList(),
        payFrom = o["payFrom"]?.obj()?.let {
            CounterPayment(it.req("counter").str(), it.req("n").int(), filterOf(it.req("filter")))
        },
    )
}

fun activatedAbilityToJson(a: ActivatedAbility): String = buildString {
    append("""{"cost":${costToJson(a.cost)},"effect":${effectToJson(a.effect)},"name":${jstr(a.name)}""")
    append(""","oncePerTurn":${a.oncePerTurn}""")
    // Elided at the default, so every bundle written before abilities had a
    // speed is byte-for-byte unchanged.
    if (!a.usesStack) append(""","usesStack":false""")
    append("}")
}
internal fun activatedAbilityOf(j: Json): ActivatedAbility = j.obj().let {
    ActivatedAbility(
        costOf(it.req("cost")), effectOf(it.req("effect")), it.strOr("name", ""),
        it.boolOr("oncePerTurn", false), it.boolOr("usesStack", true),
    )
}

fun ruleModToJson(r: RuleMod): String = when (r) {
    is RuleMod.Cant -> """{"op":"cant","action":${jstr(r.action.key)}${r.who?.let { ""","who":${playerRefToJson(it)}""" } ?: ""}}"""
    is RuleMod.CantAttack ->
        """{"op":"cantAttack","filter":${filterToJson(r.filter)}${r.defender?.let { ""","defender":${playerRefToJson(it)}""" } ?: ""}}"""
    is RuleMod.CantBlock -> """{"op":"cantBlock","filter":${filterToJson(r.filter)}}"""
    is RuleMod.CantActivate -> """{"op":"cantActivate","filter":${filterToJson(r.filter)}}"""
    is RuleMod.ReduceDamage ->
        """{"op":"reduceDamage","filter":${filterToJson(r.filter)},"amount":${intExprToJson(r.amount)}""" +
            (r.condition?.let { ""","condition":${boolExprToJson(it)}""" } ?: "") + "}"
}
internal fun ruleModOf(j: Json): RuleMod {
    val o = j.obj()
    return when (val op = o.req("op").str()) {
        "cant" -> RuleMod.Cant(
            o.req("action").str().let { a ->
                RuleAction.of(a) ?: error("a rule can't forbid '$a' -- the engine can forbid ${RuleAction.entries.joinToString("/") { it.key }}")
            },
            o["who"]?.let { playerRefOf(it) },
        )
        "cantAttack" -> RuleMod.CantAttack(filterOf(o.req("filter")), o["defender"]?.let { playerRefOf(it) })
        "cantBlock" -> RuleMod.CantBlock(filterOf(o.req("filter")))
        "reduceDamage" -> RuleMod.ReduceDamage(
            filterOf(o.req("filter")), intExprOf(o.req("amount")), o["condition"]?.let { boolExprOf(it) },
        )
        "cantActivate" -> RuleMod.CantActivate(filterOf(o.req("filter")))
        else -> error("unknown RuleMod op '$op'")
    }
}

fun costModToJson(c: CostMod): String = buildString {
    append("{")
    val parts = mutableListOf<String>()
    c.who?.let { parts += """"who":${playerRefToJson(it)}""" }
    if (c.types.isNotEmpty()) parts += "\"types\":[${c.types.joinToString(",") { jstr(it) }}]"
    if (c.delta.isNotEmpty()) parts += "\"delta\":${manaToJson(c.delta)}"
    append(parts.joinToString(","))
    append("}")
}
internal fun costModOf(j: Json): CostMod {
    val o = j.obj()
    return CostMod(
        who = o["who"]?.let { playerRefOf(it) },
        types = (o["types"]?.arr()?.map { it.str() } ?: emptyList()).toSet(),
        delta = o["delta"]?.let { manaOf(it) } ?: emptyMap(),
    )
}

private fun manaToJson(m: Map<String, Int>): String = "{" + m.entries.joinToString(",") { "${jstr(it.key)}:${it.value}" } + "}"
private fun manaOf(j: Json): Map<String, Int> = j.obj().mapValues { it.value.int() }

// effectOf(Json) -- the internal Json-node (not string) Effect parser -- comes
// from EffectJson.kt; nested effects here go straight through it, no string
// round-trip.

// -- StaticSpec ----------------------------------------------------------

fun staticSpecToJson(s: StaticSpec): String = buildString {
    append("""{"filter":${filterToJson(s.filter)},"ops":[${s.ops.joinToString(",") { charOpToJson(it) }}]""")
    s.condition?.let { append(""","condition":${boolExprToJson(it)}""") }
    s.layer?.let { append(""","layer":$it""") }
    append("}")
}
internal fun staticSpecOf(j: Json): StaticSpec {
    val o = j.obj()
    return StaticSpec(
        filter = filterOf(o.req("filter")),
        ops = o.req("ops").arr().map { charOpOf(it) },
        condition = o["condition"]?.let { boolExprOf(it) },
        layer = o["layer"]?.int(),
    )
}

/** `Statics.replacements` is a predicate-lambda list -- not serialised (an
 *  emblem authored from a card never uses one). */
fun staticsToJson(s: Statics): String = buildString {
    append("{")
    val parts = mutableListOf<String>()
    if (s.chars.isNotEmpty()) parts += "\"chars\":[${s.chars.joinToString(",") { staticSpecToJson(it) }}]"
    if (s.rules.isNotEmpty()) parts += "\"rules\":[${s.rules.joinToString(",") { ruleModToJson(it) }}]"
    if (s.costs.isNotEmpty()) parts += "\"costs\":[${s.costs.joinToString(",") { costModToJson(it) }}]"
    if (s.replacements.isNotEmpty()) {
        parts += "\"replacements\":[${s.replacements.joinToString(",") { replacementDocToJson(it) }}]"
    }
    append(parts.joinToString(","))
    append("}")
}
internal fun staticsOf(j: Json): Statics {
    val o = j.obj()
    return Statics(
        chars = o["chars"]?.arr()?.map { staticSpecOf(it) } ?: emptyList(),
        rules = o["rules"]?.arr()?.map { ruleModOf(it) } ?: emptyList(),
        costs = o["costs"]?.arr()?.map { costModOf(it) } ?: emptyList(),
        replacements = o["replacements"]?.arr()?.map { replacementDocOf(it) } ?: emptyList(),
    )
}

// -- TypeDef / PlayZoneDef ------------------------------------------------

fun typeDefToJson(t: TypeDef): String = buildString {
    append("""{"name":${jstr(t.name)}""")
    if (t.fields.isNotEmpty()) append(""","fields":[${t.fields.joinToString(",") { jstr(it) }}]""")
    t.diesWhen?.let { append(""","diesWhen":${boolExprToJson(it)}""") }
    if (t.isSpell) append(""","isSpell":true""")
    if (t.instantSpeed) append(""","instantSpeed":true""")
    if (!t.usesStack) append(""","usesStack":false""")
    if (t.attacks) append(""","attacks":true""")
    if (t.loseOnDeath) append(""","loseOnDeath":true""")
    t.zoneOfPlay?.let { append(""","zoneOfPlay":${jstr(it)}""") }
    if (t.zoneChoices.isNotEmpty()) append(""","zoneChoices":[${t.zoneChoices.joinToString(",") { jstr(it) }}]""")
    t.damageCounter?.let { append(""","damageCounter":${jstr(it)}""") }
    t.layout?.takeIf { it != CardLayout() }?.let { append(""","layout":${cardLayoutToJson(it)}""") }
    append("}")
}
internal fun typeDefOf(j: Json): TypeDef {
    val o = j.obj()
    return TypeDef(
        name = o.req("name").str(),
        fields = (o["fields"]?.arr()?.map { it.str() } ?: emptyList()).toSet(),
        diesWhen = o["diesWhen"]?.let { boolExprOf(it) },
        isSpell = o.boolOr("isSpell", false),
        instantSpeed = o.boolOr("instantSpeed", false),
        usesStack = o.boolOr("usesStack", true),
        attacks = o.boolOr("attacks", false),
        loseOnDeath = o.boolOr("loseOnDeath", false),
        zoneOfPlay = o["zoneOfPlay"]?.str(),
        zoneChoices = o["zoneChoices"]?.arr()?.map { it.str() } ?: emptyList(),
        damageCounter = o["damageCounter"]?.str(),
        layout = o["layout"]?.let { cardLayoutOf(it) },
    )
}

fun cardLayoutToJson(l: CardLayout): String = buildString {
    append("{")
    val parts = mutableListOf<String>()
    if (l.art != ArtSlot.TOP) parts += """"art":${jstr(enumStr(l.art))}"""
    if (l.statCorner != StatCorner.BOTTOM_RIGHT) parts += """"statCorner":${jstr(enumStr(l.statCorner))}"""
    if (l.statFields != listOf("power", "toughness")) {
        parts += """"statFields":[${l.statFields.joinToString(",") { jstr(it) }}]"""
    }
    if (l.counterTrack != CounterTrack.NONE) parts += """"counterTrack":${jstr(enumStr(l.counterTrack))}"""
    l.counterKind?.let { parts += """"counterKind":${jstr(it)}""" }
    if (!l.showText) parts += """"showText":false"""
    l.accent?.let { parts += """"accent":${jstr(it)}""" }
    append(parts.joinToString(","))
    append("}")
}
internal fun cardLayoutOf(j: Json): CardLayout = j.obj().let { o ->
    CardLayout(
        art = o["art"]?.str()?.let { enumOf<ArtSlot>(it) } ?: ArtSlot.TOP,
        statCorner = o["statCorner"]?.str()?.let { enumOf<StatCorner>(it) } ?: StatCorner.BOTTOM_RIGHT,
        statFields = o["statFields"]?.arr()?.map { it.str() } ?: listOf("power", "toughness"),
        counterTrack = o["counterTrack"]?.str()?.let { enumOf<CounterTrack>(it) } ?: CounterTrack.NONE,
        counterKind = o["counterKind"]?.str(),
        showText = o.boolOr("showText", true),
        accent = o["accent"]?.str(),
    )
}

// -- TurnStructure -----------------------------------

/** A PER_PLAYER structure still writes the BARE ARRAY it always has, so every
 *  game already on disk round-trips byte-for-byte and no save needs migrating.
 *  A SHARED one needs somewhere to put the mode, so it writes an object
 *  instead; `turnStructureOf` accepts either shape. */
fun turnStructureToJson(t: TurnStructure): String =
    if (t.mode != TurnMode.PER_PLAYER) {
        """{"mode":${jstr(enumStr(t.mode))},"phases":${phasesToJson(t.phases)}}"""
    } else {
        phasesToJson(t.phases)
    }

private fun phasesToJson(phases: List<PhaseSpec>): String =
    """[${phases.joinToString(",") { p ->
        buildString {
            append("""{"name":${jstr(p.name)}""")
            if (!p.interactive) append(""","interactive":false""")
            if (p.combat) append(""","combat":true""")
            if (p.sorcerySpeed) append(""","sorcerySpeed":true""")
            if (p.onEnter != Effect.NoOp) append(""","onEnter":${effectToJson(p.onEnter)}""")
            append("}")
        }
    }}]"""

internal fun turnStructureOf(j: Json): TurnStructure =
    if (j is Json.Obj) {
        val o = j.obj()
        TurnStructure(
            phasesOf(o.req("phases")),
            mode = o["mode"]?.str()?.let { enumOf<TurnMode>(it) } ?: TurnMode.PER_PLAYER,
        )
    } else {
        TurnStructure(phasesOf(j)) // the legacy bare array -- PER_PLAYER
    }

private fun phasesOf(j: Json): List<PhaseSpec> =
    j.arr().map { pj ->
        val o = pj.obj()
        PhaseSpec(
            name = o.req("name").str(),
            interactive = o.boolOr("interactive", true),
            combat = o.boolOr("combat", false),
            sorcerySpeed = o.boolOr("sorcerySpeed", false),
            // An effect since format 5; absent means nothing to do.
            onEnter = o["onEnter"]?.let { effectOf(it) } ?: Effect.NoOp,
        )
    }

fun zoneScopeStr(s: ZoneScope): String = enumStr(s)
fun zoneScopeOf(s: String): ZoneScope = enumOf(s, "perPlayer" to ZoneScope.PER_PLAYER)

fun playZoneDefToJson(z: PlayZoneDef): String = buildString {
    append("""{"id":${jstr(z.id)},"scope":${jstr(zoneScopeStr(z.scope))}""")
    z.maxOccupants?.let { append(""","maxOccupants":$it""") }
    z.combatSteps?.let { s -> append(""","combatSteps":[${s.joinToString(",") { jstr(it) }}]""") }
    // a zone that lost its lane/depth on the way through JSON would
    // load as a FLAT zone -- same ids, different game, and nothing would say so.
    z.lane?.let { append(""","lane":$it""") }
    z.depth?.let { append(""","depth":${jstr(enumStr(it))}""") }
    append("}")
}
internal fun playZoneDefOf(j: Json): PlayZoneDef = j.obj().let {
    PlayZoneDef(
        it.req("id").str(), zoneScopeOf(it.req("scope").str()),
        maxOccupants = it["maxOccupants"]?.int(),
        combatSteps = it["combatSteps"]?.arr()?.map { s -> s.str() }?.toSet(),
        lane = it["lane"]?.int(),
        depth = it["depth"]?.str()?.let { d -> enumOf<Depth>(d) },
    )
}

/** a declared HIDDEN zone. */
fun hiddenZoneDefToJson(z: HiddenZoneDef): String = buildString {
    append("""{"id":${jstr(z.id)}""")
    if (!z.alwaysVisible) append(""","alwaysVisible":false""")
    append("}")
}
internal fun hiddenZoneDefOf(j: Json): HiddenZoneDef = j.obj().let { HiddenZoneDef(it.req("id").str(), it.boolOr("alwaysVisible", true)) }

// -- Combat: CombatStep / the base CombatConfig / CombatConfigDoc --------

private fun damageModelStr(m: DamageModel) = enumStr(m)
private fun damageModelOf(s: String) = enumOf<DamageModel>(s)
private fun tieResultStr(t: TieResult) = enumStr(t)
private fun tieResultOf(s: String) =
    enumOf(s, "bothDestroyed" to TieResult.BOTH_DESTROYED, "attackerWins" to TieResult.ATTACKER_WINS)
internal fun tieResultToJson(t: TieResult) = tieResultStr(t)
internal fun tieResultFromJson(s: String) = tieResultOf(s)
internal fun stanceToJson(s: StanceDef) = """{"name":${jstr(s.name)},"defendsWith":${jstr(s.defendsWith)}}"""
internal fun stanceOf(j: Json): StanceDef = j.obj().let { StanceDef(it.req("name").str(), it.req("defendsWith").str()) }
private fun combatStyleStr(s: CombatStyle) = enumStr(s)
private fun combatStyleOf(s: String) = enumOf<CombatStyle>(s)

fun combatStepToJson(s: CombatStep): String = buildString {
    append("""{"name":${jstr(s.name)},"damageField":${jstr(s.damageField)},"damageModel":${jstr(damageModelStr(s.damageModel))}""")
    s.attackerField?.let { append(""","attackerField":${jstr(it)}""") }
    append(""","defenderField":${jstr(s.defenderField)}""")
    if (!s.returnDamage) append(""","returnDamage":false""")
    if (s.excessToController) append(""","excessToController":true""")
    append(""","onTie":${jstr(tieResultStr(s.onTie))}""")
    if (s.responseWindow) append(""","responseWindow":true""")
    s.requiresKeyword?.let { append(""","requiresKeyword":${jstr(it)}""") }
    s.excludesKeyword?.let { append(""","excludesKeyword":${jstr(it)}""") }
    s.exemptKeyword?.let { append(""","exemptKeyword":${jstr(it)}""") }
    append("}")
}
internal fun combatStepOf(j: Json): CombatStep {
    val o = j.obj()
    return CombatStep(
        name = o.req("name").str(),
        damageField = o.req("damageField").str(),
        damageModel = damageModelOf(o.req("damageModel").str()),
        attackerField = o["attackerField"]?.str(),
        defenderField = o.strOr("defenderField", "toughness"),
        returnDamage = o.boolOr("returnDamage", true),
        excessToController = o.boolOr("excessToController", false),
        onTie = o["onTie"]?.str()?.let { tieResultOf(it) } ?: TieResult.BOTH_DESTROYED,
        responseWindow = o.boolOr("responseWindow", false),
        requiresKeyword = o["requiresKeyword"]?.str(),
        excludesKeyword = o["excludesKeyword"]?.str(),
        exemptKeyword = o["exemptKeyword"]?.str(),
    )
}

/** A CombatConfig, block rules included (format 4). Format 3 wrote them
 *  beside it, as the closures they were could not be saved: `v3to4`. */
fun combatConfigToJson(c: CombatConfig): String = buildString {
    append("""{"style":${jstr(combatStyleStr(c.style))},"steps":[${c.steps.joinToString(",") { combatStepToJson(it) }}]""")
    c.mustTargetKeyword?.let { append(""","mustTargetKeyword":${jstr(it)}""") }
    c.cantTargetKeyword?.let { append(""","cantTargetKeyword":${jstr(it)}""") }
    if (c.onlyExhaustedTargets) append(""","onlyExhaustedTargets":true""")
    c.blockerKeyword?.let { append(""","blockerKeyword":${jstr(it)}""") }
    c.playerHitAmount?.let { append(""","playerHitAmount":$it""") }
    if (c.laneLockedPlayerTargets) append(""","laneLockedPlayerTargets":true""")
    if (c.laneLockedBoardTargets) append(""","laneLockedBoardTargets":true""")
    c.crossLaneKeyword?.let { append(""","crossLaneKeyword":${jstr(it)}""") }
    if (c.exhaustedCannotAct) append(""","exhaustedCannotAct":true""")
    if (c.attacksPerRange) append(""","attacksPerRange":true""")
    // The FRONT/BACK rules. Omitted here they would round-trip to their
    // defaults and a saved game would silently lose its combat topology --
    // the shape of silent loss this codec has been bitten by before.
    c.screen?.let { append(""","screen":{"screened":${jstr(it.screened)},"by":${jstr(it.by)}}""") }
    if (c.longRangeHitsFace) append(""","longRangeHitsFace":true""")
    if (c.overflowToController) append(""","overflowToController":true""")
    c.rangeFields?.let { append(""","rangeFields":{"close":${jstr(it.close)},"far":${jstr(it.far)}}""") }
    if (c.blockRules.isNotEmpty()) append(""","blockRules":[${c.blockRules.joinToString(",") { blockRuleToJson(it) }}]""")
    if (c.stances.isNotEmpty()) append(""","stances":[${c.stances.joinToString(",") { stanceToJson(it) }}]""")
    append("}")
}
internal fun combatConfigOf(j: Json): CombatConfig {
    val o = j.obj()
    return CombatConfig(
        style = combatStyleOf(o.req("style").str()),
        steps = o.req("steps").arr().map { combatStepOf(it) },
        mustTargetKeyword = o["mustTargetKeyword"]?.str(),
        cantTargetKeyword = o["cantTargetKeyword"]?.str(),
        onlyExhaustedTargets = o.boolOr("onlyExhaustedTargets", false),
        blockerKeyword = o["blockerKeyword"]?.str(),
        playerHitAmount = o["playerHitAmount"]?.int(),
        laneLockedPlayerTargets = o.boolOr("laneLockedPlayerTargets", false),
        laneLockedBoardTargets = o.boolOr("laneLockedBoardTargets", false),
        crossLaneKeyword = o["crossLaneKeyword"]?.str(),
        exhaustedCannotAct = o.boolOr("exhaustedCannotAct", false),
        attacksPerRange = o.boolOr("attacksPerRange", false),
        screen = o["screen"]?.let { s -> s.obj().let { ScreenRule(it.req("screened").str(), it.req("by").str()) } },
        longRangeHitsFace = o.boolOr("longRangeHitsFace", false),
        overflowToController = o.boolOr("overflowToController", false),
        rangeFields = o["rangeFields"]?.let { r -> r.obj().let { RangeFields(it.req("close").str(), it.req("far").str()) } },
        blockRules = o["blockRules"]?.arr()?.map { blockRuleOf(it) }.orEmpty(),
        stances = o["stances"]?.arr()?.map { stanceOf(it) }.orEmpty(),
    )
}

fun blockRuleToJson(b: BlockRule): String =
    """{"attacker":${filterToJson(b.attacker)},"blocker":${filterToJson(b.blocker)}}"""
internal fun blockRuleOf(j: Json): BlockRule {
    val o = j.obj()
    return BlockRule(filterOf(o.req("attacker")), filterOf(o.req("blocker")))
}

/** A game's combat as programs: each one an effect, or null. */
fun combatToJson(c: Combat): String =
    """{"program":${c.program?.let { effectToJson(it) } ?: "null"}""" +
        (c.attack?.let { ""","attack":${effectToJson(it)}""" } ?: "") + "}"

internal fun combatOf(j: Json): Combat {
    val o = j.obj()
    return Combat(o["program"]?.takeIf { it != Json.Null }?.let { effectOf(it) }, o["attack"]?.takeIf { it != Json.Null }?.let { effectOf(it) })
}

/** The preset library as a file: each preset's programs, by name.
 *  Written to `content/combat/presets.json`, and read back by the same codec. */
fun combatPresetsToJson(presets: Map<String, Combat> = COMBAT_PRESETS): String =
    """{"formatVersion":$FORMAT_VERSION,"presets":{""" +
        presets.entries.joinToString(",") { (name, c) -> "${jstr(name)}:${combatToJson(c)}" } + "}}"

fun combatPresetsOf(text: String): Map<String, Combat> {
    val o = Json.parse(text).obj()
    require(o.req("formatVersion").int() == FORMAT_VERSION) { "a preset library of another format" }
    return o.req("presets").obj().mapValues { combatOf(it.value) }
}

fun combatDocToJson(c: CombatDoc): String = when (c) {
    is CombatDoc.Preset -> """{"kind":"preset","name":${jstr(c.name)}}"""
    is CombatDoc.Program -> """{"kind":"program","combat":${combatToJson(c.combat)}}"""
}
internal fun combatDocOf(j: Json): CombatDoc {
    val o = j.obj()
    return when (val k = o.req("kind").str()) {
        "preset" -> CombatDoc.Preset(o.req("name").str())
        "program" -> CombatDoc.Program(combatOf(o.req("combat")))
        else -> error("unknown combat kind '$k'")
    }
}

// -- TriggerDoc ------------------------------------------------------------

fun triggerDocToJson(t: TriggerDoc): String {
    val (op, extra) = when (t) {
        is TriggerDoc.SelfEnters -> "selfEnters" to ""
        is TriggerDoc.SelfLeaves -> "selfLeaves" to ""
        is TriggerDoc.SelfAttacks -> "selfAttacks" to ""
        is TriggerDoc.YouCastType -> "youCastType" to ""","types":[${t.types.joinToString(",") { jstr(it) }}]"""
        is TriggerDoc.OnYourPhase -> "onYourPhase" to ""","phase":${jstr(t.phase)}"""
        is TriggerDoc.CounterThreshold -> "counterThreshold" to ""","kind":${jstr(t.kind)},"k":${t.k},"down":${t.downward}"""
        is TriggerDoc.SelfDealsDamage -> "selfDealsDamage" to ""","combatOnly":${t.combatOnly}"""
        is TriggerDoc.CreatureDies -> "creatureDies" to
            (t.whose?.let { ""","whose":${playerRefToJson(it)}""" } ?: "") +
            (if (t.types != setOf("Creature")) ""","dieTypes":[${t.types.joinToString(",") { jstr(it) }}]""" else "")
        is TriggerDoc.On -> "on" to ""","event":${eventPatternToJson(t.on)}"""
    }
    return """{"op":${jstr(op)},"effect":${effectToJson(t.effect)},"order":${t.order}$extra}"""
}
internal fun triggerDocOf(j: Json): TriggerDoc {
    val o = j.obj()
    val effect = effectOf(o.req("effect"))
    val order = o.intOr("order", 0)
    return when (val op = o.req("op").str()) {
        "selfEnters" -> TriggerDoc.SelfEnters(effect, order)
        "selfLeaves" -> TriggerDoc.SelfLeaves(effect, order)
        "selfAttacks" -> TriggerDoc.SelfAttacks(effect, order)
        "youCastType" -> TriggerDoc.YouCastType(o.req("types").arr().map { it.str() }.toSet(), effect, order)
        "onYourPhase" -> TriggerDoc.OnYourPhase(o.req("phase").str(), effect, order)
        "counterThreshold" ->
            TriggerDoc.CounterThreshold(o.req("kind").str(), o.req("k").int(), effect, order, o.boolOr("down", false))
        "selfDealsDamage" -> TriggerDoc.SelfDealsDamage(o.boolOr("combatOnly", false), effect, order)
        "creatureDies" -> TriggerDoc.CreatureDies(
            o["whose"]?.let { playerRefOf(it) }, effect, order,
            types = o["dieTypes"]?.arr()?.map { it.str() }?.toSet() ?: setOf("Creature"),
        )
        "on" -> TriggerDoc.On(eventPatternOf(o.req("event")), effect, order)
        else -> error("unknown TriggerDoc op '$op'")
    }
}

// -- FaceDoc / CardDoc / BundleDoc -----------------------------------------

fun faceDocToJson(f: FaceDoc): String = buildString {
    append("""{"name":${jstr(f.name)},"types":[${f.types.joinToString(",") { jstr(it) }}]""")
    if (f.fields.isNotEmpty()) append(""","fields":${f.fields.entries.joinToString(",", "{", "}") { "${jstr(it.key)}:${it.value}" }}""")
    if (f.keywords.isNotEmpty()) append(""","keywords":[${f.keywords.joinToString(",") { jstr(it) }}]""")
    f.castEffect?.let { append(""","castEffect":${effectToJson(it)}""") }
    if (f.statics.isNotEmpty()) append(""","statics":[${f.statics.joinToString(",") { staticSpecToJson(it) }}]""")
    if (f.ruleMods.isNotEmpty()) append(""","ruleMods":[${f.ruleMods.joinToString(",") { ruleModToJson(it) }}]""")
    if (f.costMods.isNotEmpty()) append(""","costMods":[${f.costMods.joinToString(",") { costModToJson(it) }}]""")
    if (f.triggers.isNotEmpty()) append(""","triggers":[${f.triggers.joinToString(",") { triggerDocToJson(it) }}]""")
    if (f.activated.isNotEmpty()) append(""","activated":[${f.activated.joinToString(",") { activatedAbilityToJson(it) }}]""")
    f.art?.let { append(""","art":${jstr(it)}""") }
    // Art rects: elided when absent AND when the rect is the whole
    // picture, so an uncropped card serialises exactly as it did before these
    // fields existed. Both rects go through ONE writer and ONE reader -- two
    // copies of this shape is how the second one ends up missing a field.
    f.artFieldRect?.takeIf { !it.isWhole }?.let { append(""","artFieldRect":${artRectToJson(it)}""") }
    f.artMiniRect?.takeIf { !it.isWhole }?.let { append(""","artMiniRect":${artRectToJson(it)}""") }
    if (f.replacements.isNotEmpty()) {
        append(""","replacements":[${f.replacements.joinToString(",") { replacementDocToJson(it) }}]""")
    }
    append("}")
}

/** Replacement effects, as DATA -- the whole point of `ReplacementDoc`: a card
 *  carrying one survives the round-trip the app plays from. */
fun replacementDocToJson(r: ReplacementDoc): String = when (r) {
    is ReplacementDoc.DamageToSacrificeSelf ->
        """{"kind":"damageToSacrificeSelf","filter":${filterToJson(r.filter)}""" +
            (r.onlyStep?.let { ""","onlyStep":${jstr(it)}""" } ?: "") + "}"
    is ReplacementDoc.PreventDamageTo ->
        """{"kind":"preventDamageTo","filter":${filterToJson(r.filter)}""" +
            (r.onlyStep?.let { ""","onlyStep":${jstr(it)}""" } ?: "") + "}"
    is ReplacementDoc.DamageToRemoveCounter ->
        """{"kind":"damageToRemoveCounter","filter":${filterToJson(r.filter)},"counter":${jstr(r.counter)}""" +
            (r.onlyStep?.let { ""","onlyStep":${jstr(it)}""" } ?: "") + "}"
    is ReplacementDoc.DeathToExile ->
        """{"kind":"deathToExile","filter":${filterToJson(r.filter)}}"""
    is ReplacementDoc.Replace ->
        """{"kind":"replace","pattern":${eventPatternToJson(r.pattern)},"instead":${effectToJson(r.instead)}}"""
}
internal fun replacementDocOf(j: Json): ReplacementDoc = j.obj().let { o ->
    when (val k = o.req("kind").str()) {
        "damageToSacrificeSelf" -> ReplacementDoc.DamageToSacrificeSelf(filterOf(o.req("filter")), o["onlyStep"]?.str())
        "preventDamageTo" -> ReplacementDoc.PreventDamageTo(filterOf(o.req("filter")), o["onlyStep"]?.str())
        "damageToRemoveCounter" -> ReplacementDoc.DamageToRemoveCounter(
            filterOf(o.req("filter")), o.req("counter").str(), o["onlyStep"]?.str(),
        )
        "deathToExile" -> ReplacementDoc.DeathToExile(filterOf(o.req("filter")))
        "replace" -> ReplacementDoc.Replace(eventPatternOf(o.req("pattern")), effectOf(o.req("instead")))
        else -> error("unknown replacement kind: $k")
    }
}
internal fun faceDocOf(j: Json): FaceDoc {
    val o = j.obj()
    return FaceDoc(
        name = o.req("name").str(),
        types = o.req("types").arr().map { it.str() }.toSet(),
        fields = o["fields"]?.obj()?.mapValues { it.value.int() } ?: emptyMap(),
        keywords = (o["keywords"]?.arr()?.map { it.str() } ?: emptyList()).toSet(),
        castEffect = o["castEffect"]?.let { effectOf(it) },
        statics = o["statics"]?.arr()?.map { staticSpecOf(it) } ?: emptyList(),
        ruleMods = o["ruleMods"]?.arr()?.map { ruleModOf(it) } ?: emptyList(),
        costMods = o["costMods"]?.arr()?.map { costModOf(it) } ?: emptyList(),
        triggers = o["triggers"]?.arr()?.map { triggerDocOf(it) } ?: emptyList(),
        replacements = o["replacements"]?.arr()?.map { replacementDocOf(it) } ?: emptyList(),
        activated = o["activated"]?.arr()?.map { activatedAbilityOf(it) } ?: emptyList(),
        art = o["art"]?.str(),
        // Legacy `artFrame` / `artFrameMini` keys (a transform, not a rect) are
        // ignored: such a card loads uncropped.
        artFieldRect = o["artFieldRect"]?.let { artRectOf(it) },
        artMiniRect = o["artMiniRect"]?.let { artRectOf(it) },
    )
}

fun artRectToJson(r: ArtRect): String =
    """{"x":${r.x},"y":${r.y},"w":${r.w},"h":${r.h}}"""

internal fun artRectOf(j: Json): ArtRect = j.obj().let {
    ArtRect(
        x = it["x"]?.flt() ?: 0f,
        y = it["y"]?.flt() ?: 0f,
        w = it["w"]?.flt() ?: 1f,
        h = it["h"]?.flt() ?: 1f,
    )
}

fun cardDocToJson(c: CardDoc): String = buildString {
    append("""{"faces":[${c.faces.joinToString(",") { faceDocToJson(it) }}]""")
    if (c.entersWith.isNotEmpty()) append(""","entersWith":[${c.entersWith.joinToString(",") { counterDefToJson(it) }}]""")
    c.diesWhen?.let { append(""","diesWhen":${boolExprToJson(it)}""") }
    if (c.text.isNotEmpty()) append(""","text":${jstr(c.text)}""")
    if (!c.cost.isFree) append(""","cost":${costToJson(c.cost)}""")
    c.recast?.let {
        append(""","recast":{"from":${jstr(enumStr(it.from))},"cost":${costToJson(it.cost)},"after":${jstr(enumStr(it.afterResolve))}}""")
    }
    if (c.id.isNotEmpty()) append(""","id":${jstr(c.id)}""")
    c.requires?.let { append(""","requires":${filterToJson(it)}""") }
    append("}")
}
internal fun cardDocOf(j: Json): CardDoc {
    val o = j.obj()
    return CardDoc(
        faces = o.req("faces").arr().map { faceDocOf(it) },
        entersWith = o["entersWith"]?.arr()?.map { counterDefOf(it) } ?: emptyList(),
        diesWhen = o["diesWhen"]?.let { boolExprOf(it) },
        text = o.strOr("text", ""),
        cost = o["cost"]?.let { costOf(it) } ?: Cost(),
        recast = o["recast"]?.obj()?.let { r ->
            Recast(enumOf<HiddenZone>(r.req("from").str()), costOf(r.req("cost")), enumOf<HiddenZone>(r.req("after").str()))
        },
        id = o.strOr("id", ""),
        requires = o["requires"]?.let { filterOf(it) },
    )
}

fun rulesDocToJson(r: RulesDoc): String = buildString {
    append("{")
    val parts = mutableListOf<String>()
    if (r.extraTypes.isNotEmpty()) parts += """"extraTypes":[${r.extraTypes.joinToString(",") { typeDefToJson(it) }}]"""
    if (r.extraZones.isNotEmpty()) parts += """"extraZones":[${r.extraZones.joinToString(",") { playZoneDefToJson(it) }}]"""
    if (r.extraHiddenZones.isNotEmpty()) {
        parts += """"extraHiddenZones":[${r.extraHiddenZones.joinToString(",") { hiddenZoneDefToJson(it) }}]"""
    }
    parts += """"combat":${combatDocToJson(r.combat)}"""
    if (r.turn != TurnStructure.MTG) parts += """"turn":${turnStructureToJson(r.turn)}"""
    if (r.params != GameParams()) parts += """"params":${gameParamsToJson(r.params)}"""
    if (r.resourceModel != ResourceModel.None) parts += """"resources":${resourceModelToJson(r.resourceModel)}"""
    if (r.playerCounters != DEFAULT_PLAYER_COUNTERS) {
        parts += """"counters":[${r.playerCounters.joinToString(",") { playerCounterToJson(it) }}]"""
    }
    if (r.damageCounter != LIFE) parts += """"damageCounter":${r.damageCounter?.let { jstr(it) } ?: "null"}"""
    r.counterKinds?.let { ks -> parts += """"counterKinds":[${ks.joinToString(",") { counterKindToJson(it) }}]""" }
    append(parts.joinToString(","))
    append("}")
}

internal fun rulesDocOf(j: Json): RulesDoc = j.obj().let { o ->
    RulesDoc(
        extraTypes = o["extraTypes"]?.arr()?.map { typeDefOf(it) } ?: emptyList(),
        extraZones = o["extraZones"]?.arr()?.map { playZoneDefOf(it) } ?: emptyList(),
        extraHiddenZones = o["extraHiddenZones"]?.arr()?.map { hiddenZoneDefOf(it) } ?: emptyList(),
        combat = o["combat"]?.let { combatDocOf(it) } ?: CombatDoc.Preset("mtg"),
        turn = o["turn"]?.let { turnStructureOf(it) } ?: TurnStructure.MTG,
        params = o["params"]?.let { gameParamsOf(it) } ?: GameParams(),
        resourceModel = o["resources"]?.let { resourceModelOf(it) } ?: ResourceModel.None,
        playerCounters = o["counters"]?.arr()?.map { playerCounterOf(it) } ?: DEFAULT_PLAYER_COUNTERS,
        // Absent = the default, "life"; an explicit null = players take no
        // damage. (A format-2 file meant something else by absent: v2to3.)
        damageCounter = if ("damageCounter" in o) o["damageCounter"]?.takeIf { it != Json.Null }?.str() else LIFE,
        counterKinds = o["counterKinds"]?.arr()?.map { counterKindOf(it) },
    )
}

fun counterKindToJson(c: CounterKindDef): String =
    """{"name":${jstr(c.name)}""" + (c.cancels?.let { ""","cancels":${jstr(it)}""" } ?: "") + "}"

internal fun counterKindOf(j: Json): CounterKindDef = j.obj().let { o ->
    CounterKindDef(o.req("name").str(), o["cancels"]?.takeIf { it != Json.Null }?.str())
}

// -- game parameters --------------------------------------------------------

fun gameParamsToJson(p: GameParams): String =
    """{"hand":${p.startingHandSize},"draw":${p.cardsDrawnPerTurn},""" +
        """"skipFirstDraw":${p.firstPlayerSkipsFirstDraw},""" +
        """"mulligan":{"redraws":${p.mulligan.redraws},"bottomOne":${p.mulligan.bottomOnePerMulligan}},""" +
        (p.maxHandSize?.let { """"maxHand":$it,""" } ?: "") +
        """"players":${p.playerCount},"orientation":${jstr(enumStr(p.preferredOrientation))},""" +
        """"poolPersistsPerTurn":${p.poolPersistsPerTurn}""" +
        (if (p.attackDelayOnEntry) ""","attackDelayOnEntry":true""" else "") +
        (p.poolStoreCounter?.let { ""","poolStore":${jstr(it)}""" } ?: "") + "}"

internal fun gameParamsOf(j: Json): GameParams = j.obj().let { o ->
    GameParams(
        startingHandSize = o.intOr("hand", 7),
        cardsDrawnPerTurn = o.intOr("draw", 1),
        firstPlayerSkipsFirstDraw = o.boolOr("skipFirstDraw", true),
        mulligan = o["mulligan"]?.obj()?.let { m ->
            MulliganRule(m.intOr("redraws", 0), m.boolOr("bottomOne", true))
        } ?: MulliganRule(),
        maxHandSize = o["maxHand"]?.int(),
        playerCount = o.intOr("players", 2),
        preferredOrientation = o["orientation"]?.str()?.let { enumOf<Orientation>(it) } ?: Orientation.EITHER,
        poolPersistsPerTurn = o.boolOr("poolPersistsPerTurn", false),
        attackDelayOnEntry = o.boolOr("attackDelayOnEntry", false),
        poolStoreCounter = o["poolStore"]?.str(),
    )
}

fun resourceModelToJson(m: ResourceModel): String = when (m) {
    ResourceModel.None -> """{"kind":"none"}"""
    is ResourceModel.Ramp ->
        """{"kind":"ramp","perTurn":${m.perTurn},"cap":${m.cap},"refill":${m.refillEachTurn},""" +
            """"start":${m.startingAmount},"key":${jstr(m.key)}}"""
    is ResourceModel.CardDriven ->
        """{"kind":"cardDriven","playsPerTurn":${m.playsPerTurn},"types":[${m.types.joinToString(",") { jstr(it) }}]}"""
}

internal fun resourceModelOf(j: Json): ResourceModel = j.obj().let { o ->
    when (val k = o.req("kind").str()) {
        "none" -> ResourceModel.None
        "ramp" -> ResourceModel.Ramp(
            o.intOr("perTurn", 1), o.intOr("cap", 10), o.boolOr("refill", true),
            o.intOr("start", 0), o.strOr("key", ""),
        )
        "cardDriven" -> ResourceModel.CardDriven(
            o.intOr("playsPerTurn", 1),
            o["types"]?.arr()?.map { it.str() }?.toSet() ?: setOf("Land"),
        )
        else -> error("unknown resource model '$k'")
    }
}

fun playerCounterToJson(c: PlayerCounterDef): String = buildString {
    append("""{"name":${jstr(c.name)},"starting":${c.starting},"loseAtZero":${c.loseAtZero}""")
    c.min?.let { append(""","min":$it""") }
    c.max?.let { append(""","max":$it""") }
    append("}")
}

internal fun playerCounterOf(j: Json): PlayerCounterDef = j.obj().let { o ->
    PlayerCounterDef(
        o.req("name").str(), o.intOr("starting", 0), o.boolOr("loseAtZero", false),
        o["min"]?.int(), o["max"]?.int(),
    )
}

private fun deckEntriesToJson(es: List<DeckEntry>): String =
    "[" + es.joinToString(",") { """{"card":${jstr(it.cardName)},"n":${it.count}}""" } + "]"

private fun deckEntriesOf(j: Json): List<DeckEntry> =
    j.arr().map { it.obj().let { d -> DeckEntry(d.req("card").str(), d.req("n").int()) } }

private fun slotMapToJson(m: Map<String, List<String>>): String =
    "{" + m.entries.joinToString(",") { (k, v) -> "${jstr(k)}:[${v.joinToString(",") { jstr(it) }}]" } + "}"

private fun slotMapOf(j: Json): Map<String, List<String>> =
    j.obj().mapValues { (_, v) -> v.arr().map { it.str() } }

// -- DeckRules / DeckSlotDef / IdentityRule ----------------------------

fun deckSlotDefToJson(s: DeckSlotDef): String = buildString {
    append("""{"name":${jstr(s.name)}""")
    if (s.types.isNotEmpty()) append(""","types":[${s.types.joinToString(",") { jstr(it) }}]""")
    if (s.count != 1) append(""","count":${s.count}""")
    if (s.startsIn != "battlefield") append(""","startsIn":${jstr(s.startsIn)}""")
    append("}")
}
internal fun deckSlotDefOf(j: Json): DeckSlotDef = j.obj().let { o ->
    DeckSlotDef(
        name = o.req("name").str(),
        types = (o["types"]?.arr()?.map { it.str() } ?: emptyList()).toSet(),
        count = o.intOr("count", 1),
        startsIn = o.strOr("startsIn", "battlefield"),
    )
}

fun identityRuleToJson(r: IdentityRule): String = buildString {
    append("{")
    val parts = mutableListOf<String>()
    if (r.from.isNotEmpty()) parts += """"from":[${r.from.joinToString(",") { jstr(it) }}]"""
    if (r.vocabulary.isNotEmpty()) parts += """"vocabulary":[${r.vocabulary.joinToString(",") { jstr(it) }}]"""
    if (!r.allowNeutral) parts += """"allowNeutral":false"""
    append(parts.joinToString(","))
    append("}")
}
internal fun identityRuleOf(j: Json): IdentityRule = j.obj().let { o ->
    IdentityRule(
        from = o["from"]?.arr()?.map { it.str() } ?: emptyList(),
        vocabulary = (o["vocabulary"]?.arr()?.map { it.str() } ?: emptyList()).toSet(),
        allowNeutral = o.boolOr("allowNeutral", true),
    )
}

fun deckRulesToJson(d: DeckRules): String = buildString {
    append("{")
    val parts = mutableListOf<String>()
    if (d.minSize != 0) parts += """"minSize":${d.minSize}"""
    d.maxSize?.let { parts += """"maxSize":$it""" }
    d.maxCopies?.let { parts += """"maxCopies":$it""" }
    if (d.slots.isNotEmpty()) parts += """"slots":[${d.slots.joinToString(",") { deckSlotDefToJson(it) }}]"""
    d.identity?.let { parts += """"identity":${identityRuleToJson(it)}""" }
    append(parts.joinToString(","))
    append("}")
}
internal fun deckRulesOf(j: Json): DeckRules = j.obj().let { o ->
    DeckRules(
        minSize = o.intOr("minSize", 0),
        maxSize = o["maxSize"]?.int(),
        maxCopies = o["maxCopies"]?.int(),
        slots = o["slots"]?.arr()?.map { deckSlotDefOf(it) } ?: emptyList(),
        identity = o["identity"]?.let { identityRuleOf(it) },
    )
}

fun gameDocToJson(g: GameDoc): String = buildString {
    append("""{"formatVersion":$FORMAT_VERSION""")
    if (g.contentVersion != 0) append(""","contentVersion":${g.contentVersion}""")
    if (g.id.isNotEmpty()) append(""","id":${jstr(g.id)}""")
    // Provenance. Elided when absent, like every other authoring-only
    // field, so a game nobody forked round-trips byte-identically to before.
    if (g.forkedFrom.isNotEmpty()) append(""","forkedFrom":${jstr(g.forkedFrom)}""")
    if (g.forkNote.isNotEmpty()) append(""","forkNote":${jstr(g.forkNote)}""")
    if (g.accent.isNotEmpty()) append(""","accent":${jstr(g.accent)}""")
    if (g.cover.isNotEmpty()) append(""","cover":${jstr(g.cover)}""")
    append(""","name":${jstr(g.name)}""")
    append(""","rules":${rulesDocToJson(g.rules)}""")
    append(""","sets":[""")
    append(g.sets.joinToString(",") { st ->
        """{"name":${jstr(st.name)},"cards":[${st.cards.joinToString(",") { cardDocToJson(it) }}]}"""
    })
    append("]")
    if (g.decks.isNotEmpty()) {
        append(""","decks":[""")
        append(g.decks.joinToString(",") { d ->
            buildString {
                append("""{"name":${jstr(d.name)},"entries":${deckEntriesToJson(d.entries)}""")
                if (d.slots.isNotEmpty()) append(""","slots":${slotMapToJson(d.slots)}""")
                append("}")
            }
        })
        append("]")
    }
    if (g.deckRules != DeckRules()) append(""","deckRules":${deckRulesToJson(g.deckRules)}""")
    append("}")
}

// -- the format version ---------------------------------------------------
//
// A game file says which format wrote it. Reading an older one runs it up
// the MIGRATIONS chain -- one step per version, JSON to JSON -- and then
// decodes it as current, so the decoder itself only ever knows one shape.
// Bump FORMAT_VERSION for any change that makes an existing file decode
// DIFFERENTLY, and add the step that turns the old meaning into the new. A
// new optional key with a harmless default needs no bump.

/** The format this build writes. */
const val FORMAT_VERSION = 8

private typealias JObj = Map<String, Json>

/** Step `n` lifts a format-`n` file to `n + 1`. */
private val MIGRATIONS: Map<Int, (JObj) -> JObj> = mapOf(1 to ::v1to2, 2 to ::v2to3, 3 to ::v3to4, 4 to ::v4to5, 5 to ::v5to6, 6 to ::v6to7, 7 to ::v7to8)

/** Which format wrote `o`. It was `"v"` before format 3, and absent before that --
 *  the pre-split bundle (1) had no "sets"; anything later without a number
 *  is 2, the only format ever written as `"v"`. */
internal fun formatVersionOf(o: JObj): Int =
    o["formatVersion"]?.int() ?: o["v"]?.int() ?: if (o["sets"] == null) 1 else 2

/** Format 1, the pre-split bundle: a flat "cards" list, one "deck", and the
 *  rules knobs at the top level. */
private fun v1to2(o: JObj): JObj = buildMap {
    o["id"]?.let { put("id", it) }
    o["name"]?.let { put("name", it) }
    put("rules", Json.Obj(o)) // rulesDocOf reads only the keys it knows
    put("sets", Json.Arr(listOf(Json.Obj(mapOf("name" to Json.Str("Core"), "cards" to (o["cards"] ?: Json.Arr(emptyList())))))))
    val deck = o["deck"]?.arr().orEmpty()
    if (deck.isNotEmpty()) put("decks", Json.Arr(listOf(Json.Obj(mapOf("name" to Json.Str("Starter"), "entries" to Json.Arr(deck))))))
}

/** Format 2 left `damageCounter` out when it was "life" -- even in a game
 *  with no "life" counter (EPR Skirmish, Core), whose players then took
 *  damage into a counter nothing showed or read. Format 3 can say "no player
 *  damage" (null); that is what such a game meant. */
private fun v2to3(o: JObj): JObj {
    val r = o["rules"]?.obj() ?: return o
    if ("damageCounter" in r) return o
    val counters = r["counters"]?.arr()?.map { it.obj()["name"]?.str() } ?: listOf(LIFE)
    return if (LIFE in counters) o else o + ("rules" to Json.Obj(r + ("damageCounter" to Json.Null)))
}

/** Format 3 wrote a custom combat config's block rules beside its base, as
 *  "blockRuleSpecs"; format 4 writes them inside it, as "blockRules". */
private fun v3to4(o: JObj): JObj {
    val r = o["rules"]?.obj() ?: return o
    val c = r["combat"]?.obj() ?: return o
    val specs = c["blockRuleSpecs"] ?: return o
    val base = c["base"]?.obj() ?: return o
    val moved = if ((specs as? Json.Arr)?.items.isNullOrEmpty()) base else base + ("blockRules" to specs)
    val combat = c - "blockRuleSpecs" + ("base" to Json.Obj(moved))
    return o + ("rules" to Json.Obj(r + ("combat" to Json.Obj(combat))))
}

/** Format 4 named a phase's work -- "untap", "draw", "cleanup" -- from a
 *  closed enum; format 5 writes it as an effect. Each name becomes the
 *  `PhaseEffects` effect that does what the name did. An unknown name is left
 *  as it is, so the decoder refuses it rather than guessing. */
private fun v4to5(o: JObj): JObj {
    val r = o["rules"]?.obj() ?: return o
    val turn = r["turn"] ?: return o
    fun phase(p: Json): Json {
        val po = p.obj()
        val name = (po["onEnter"] as? Json.Str)?.value ?: return p
        val effect = when (name) {
            "none" -> null
            "untap" -> PhaseEffects.UNTAP
            "draw" -> PhaseEffects.DRAW
            "cleanup" -> PhaseEffects.CLEANUP
            else -> return p
        }
        return Json.Obj(if (effect == null) po - "onEnter" else po + ("onEnter" to Json.parse(effectToJson(effect))))
    }
    fun phases(a: Json): Json = Json.Arr(a.arr().map { phase(it) })
    val migrated = if (turn is Json.Obj) {
        val t = turn.obj()
        t["phases"]?.let { Json.Obj(t + ("phases" to phases(it))) } ?: turn
    } else {
        phases(turn)
    }
    return o + ("rules" to Json.Obj(r + ("turn" to migrated)))
}

/** Before format 6 every combat knew the stance "defense" (defending with the
 *  "defense" field); format 6 knows only the stances a config declares. A
 *  custom config is given the one it had; a preset declares its own. */
private fun v5to6(o: JObj): JObj {
    val r = o["rules"]?.obj() ?: return o
    val c = r["combat"]?.obj() ?: return o
    val base = c["base"]?.obj() ?: return o
    if ("stances" in base) return o
    val defense = Json.Obj(mapOf("name" to Json.Str("defense"), "defendsWith" to Json.Str("defense")))
    val combat = c + ("base" to Json.Obj(base + ("stances" to Json.Arr(listOf(defense)))))
    return o + ("rules" to Json.Obj(r + ("combat" to Json.Obj(combat))))
}

/** Before format 7 a block rule was one of three closed kinds ("op"); format
 *  7 writes every rule as an attacker filter and a blocker filter, and the
 *  kinds are the shapes `BlockRule`'s helpers build. An unknown op is left
 *  as it is, so the decoder refuses it rather than guessing. */
private fun v6to7(o: JObj): JObj {
    val r = o["rules"]?.obj() ?: return o
    val c = r["combat"]?.obj() ?: return o
    val base = c["base"]?.obj() ?: return o
    val rules = base["blockRules"]?.arr() ?: return o
    fun rule(j: Json): Json {
        val ro = j.obj()
        val b = when (ro["op"]?.str()) {
            "needsKeyword" -> BlockRule.needsKeyword(ro.req("attackerKw").str(), ro.req("blockerKw").str())
            "needsPower" -> BlockRule.needsStat(ro.req("attackerKw").str(), "power", ro.req("minPower").int())
            "cantBlock" -> BlockRule.cantBlock(ro.req("keyword").str())
            else -> return j
        }
        return Json.parse(blockRuleToJson(b))
    }
    val combat = c + ("base" to Json.Obj(base + ("blockRules" to Json.Arr(rules.map { rule(it) }))))
    return o + ("rules" to Json.Obj(r + ("combat" to Json.Obj(combat))))
}

/** Before format 8 a game's own combat was a config of flags ("custom");
 *  format 8 holds its programs. The config means exactly the programs
 *  it lowers to, so that is what it becomes. A preset stays a preset. */
private fun v7to8(o: JObj): JObj {
    val r = o["rules"]?.obj() ?: return o
    val c = r["combat"]?.obj() ?: return o
    if (c["kind"]?.str() != "custom") return o
    val combat = combatConfigOf(c.req("base")).lowered()
    return o + ("rules" to Json.Obj(r + ("combat" to Json.parse(combatDocToJson(CombatDoc.Program(combat))))))
}

internal fun gameDocOf(j: Json): GameDoc {
    var o = j.obj()
    val from = formatVersionOf(o)
    require(from <= FORMAT_VERSION) { "this game was saved by a newer build (format $from; this one reads up to $FORMAT_VERSION)" }
    for (v in from until FORMAT_VERSION) o = MIGRATIONS.getValue(v)(o)
    return GameDoc(
        id = o.strOr("id", ""),
        name = o.strOr("name", "Untitled Game"),
        contentVersion = o.intOr("contentVersion", 0),
        forkedFrom = o.strOr("forkedFrom", ""),
        forkNote = o.strOr("forkNote", ""),
        accent = o.strOr("accent", ""),
        cover = o.strOr("cover", ""),
        rules = o["rules"]?.let { rulesDocOf(it) } ?: RulesDoc(),
        sets = o.req("sets").arr().map {
            it.obj().let { st -> SetDoc(st.strOr("name", "Core"), st["cards"]?.arr()?.map { c -> cardDocOf(c) } ?: emptyList()) }
        },
        decks = o["decks"]?.arr()?.map {
            it.obj().let { d ->
                DeckDoc(
                    d.strOr("name", "Deck"),
                    d["entries"]?.let { e -> deckEntriesOf(e) } ?: emptyList(),
                    d["slots"]?.let { s -> slotMapOf(s) } ?: emptyMap(),
                )
            }
        } ?: emptyList(),
        deckRules = o["deckRules"]?.let { deckRulesOf(it) } ?: DeckRules(),
    )
}

fun gameDocFromJson(text: String): GameDoc = gameDocOf(Json.parse(text))
