package ccg

// ---------------------------------------------------------------------------
// THE WRITTEN CONTRACT -- a JSON Schema for the language, INFERRED from the
// codec's own specimens (the material `CodecTest` guarantees complete) rather
// than written by hand. A nested object that a family below decodes and
// re-encodes unchanged is a `$ref` to it; anything else is described in place.
//
// Two checks keep it honest:
//   1. the committed file equals what this generates (regenerate with
//      CGE_WRITE_SCHEMA=1 and commit the diff);
//   2. every bundled game validates against it.
// ---------------------------------------------------------------------------

private const val SCHEMA_PATH = "docs/schema/cardengine.schema.json"

/** A family with its own codec. Order breaks a tie only between families that
 *  decode the same JSON identically -- in practice, an empty object. */
private class Family(val name: String, val cls: Class<*>, val enc: (Any) -> String, val dec: (Json) -> Any)

private val FAMILIES: List<Family> = listOf(
    Family("Effect", Effect::class.java, { effectToJson(it as Effect) }, ::effectOf),
    Family("IntExpr", IntExpr::class.java, { intExprToJson(it as IntExpr) }, ::intExprOf),
    Family("BoolExpr", BoolExpr::class.java, { boolExprToJson(it as BoolExpr) }, ::boolExprOf),
    Family("PermFilter", PermFilter::class.java, { filterToJson(it as PermFilter) }, ::filterOf),
    Family("CharOp", CharOp::class.java, { charOpToJson(it as CharOp) }, ::charOpOf),
    Family("RuleMod", RuleMod::class.java, { ruleModToJson(it as RuleMod) }, ::ruleModOf),
    Family("CostMod", CostMod::class.java, { costModToJson(it as CostMod) }, ::costModOf),
    Family("Cost", Cost::class.java, { costToJson(it as Cost) }, ::costOf),
    Family("ActivatedAbility", ActivatedAbility::class.java, { activatedAbilityToJson(it as ActivatedAbility) }, ::activatedAbilityOf),
    Family("StaticSpec", StaticSpec::class.java, { staticSpecToJson(it as StaticSpec) }, ::staticSpecOf),
    Family("Statics", Statics::class.java, { staticsToJson(it as Statics) }, ::staticsOf),
    Family("Characteristics", Characteristics::class.java, { charsToJson(it as Characteristics) }, ::charsOf),
    Family("CardFilter", CardFilter::class.java, { cardFilterToJson(it as CardFilter) }, ::cardFilterOf),
    Family("Duration", Duration::class.java, { durationToJson(it as Duration) }, ::durationOf),
    Family("EventPattern", EventPattern::class.java, { eventPatternToJson(it as EventPattern) }, ::eventPatternOf),
    Family("PlayerRef", PlayerRef::class.java, { playerRefToJson(it as PlayerRef) }, ::playerRefOf),
    // an object id, or a variable's name (the decoder also still reads
    // the old negative ids, which are integers too).
    Family("Target", BoundTarget::class.java, { btJson(it as BoundTarget) }, ::btOf),
    Family("ZoneRef", ZoneRef::class.java, { zoneRefToJson(it as ZoneRef) }, ::zoneRefOf),
    Family("CounterDef", CounterDef::class.java, { counterDefToJson(it as CounterDef) }, ::counterDefOf),
    Family("TriggerDoc", TriggerDoc::class.java, { triggerDocToJson(it as TriggerDoc) }, ::triggerDocOf),
    Family("ReplacementDoc", ReplacementDoc::class.java, { replacementDocToJson(it as ReplacementDoc) }, ::replacementDocOf),
    Family("ArtRect", ArtRect::class.java, { artRectToJson(it as ArtRect) }, ::artRectOf),
    Family("FaceDoc", FaceDoc::class.java, { faceDocToJson(it as FaceDoc) }, ::faceDocOf),
    Family("CardDoc", CardDoc::class.java, { cardDocToJson(it as CardDoc) }, ::cardDocOf),
    Family("CardLayout", CardLayout::class.java, { cardLayoutToJson(it as CardLayout) }, ::cardLayoutOf),
    Family("TypeDef", TypeDef::class.java, { typeDefToJson(it as TypeDef) }, ::typeDefOf),
    Family("PlayZoneDef", PlayZoneDef::class.java, { playZoneDefToJson(it as PlayZoneDef) }, ::playZoneDefOf),
    Family("HiddenZoneDef", HiddenZoneDef::class.java, { hiddenZoneDefToJson(it as HiddenZoneDef) }, ::hiddenZoneDefOf),
    Family("CombatStep", CombatStep::class.java, { combatStepToJson(it as CombatStep) }, ::combatStepOf),
    Family("CombatConfig", CombatConfig::class.java, { combatConfigToJson(it as CombatConfig) }, ::combatConfigOf),
    Family("BlockRule", BlockRule::class.java, { blockRuleToJson(it as BlockRule) }, ::blockRuleOf),
    Family("Gun", Gun::class.java, { gunToJson(it as Gun) }, ::gunOf),
    Family("Combat", Combat::class.java, { combatToJson(it as Combat) }, ::combatOf),
    Family("CombatDoc", CombatDoc::class.java, { combatDocToJson(it as CombatDoc) }, ::combatDocOf),
    Family("TurnStructure", TurnStructure::class.java, { turnStructureToJson(it as TurnStructure) }, ::turnStructureOf),
    Family("GameParams", GameParams::class.java, { gameParamsToJson(it as GameParams) }, ::gameParamsOf),
    Family("ResourceModel", ResourceModel::class.java, { resourceModelToJson(it as ResourceModel) }, ::resourceModelOf),
    Family("PlayerCounterDef", PlayerCounterDef::class.java, { playerCounterToJson(it as PlayerCounterDef) }, ::playerCounterOf),
    Family("CounterKindDef", CounterKindDef::class.java, { counterKindToJson(it as CounterKindDef) }, ::counterKindOf),
    Family("RulesDoc", RulesDoc::class.java, { rulesDocToJson(it as RulesDoc) }, ::rulesDocOf),
    Family("DeckSlotDef", DeckSlotDef::class.java, { deckSlotDefToJson(it as DeckSlotDef) }, ::deckSlotDefOf),
    Family("IdentityRule", IdentityRule::class.java, { identityRuleToJson(it as IdentityRule) }, ::identityRuleOf),
    Family("DeckRules", DeckRules::class.java, { deckRulesToJson(it as DeckRules) }, ::deckRulesOf),
    Family("GameDoc", GameDoc::class.java, { gameDocToJson(it as GameDoc) }, ::gameDocOf),
    Family("Answer", Answer::class.java, { answerToJson(it as Answer) }, ::answerOf),
    Family("TableEdit", TableEdit::class.java, { tableEditToJson(it as TableEdit) }, ::tableEditOf),
)

/** Shapes the no-defaults specimens cannot show, because the codec writes a
 *  DIFFERENT shape for the default: a per-player turn is a bare phase array. */
private val EXTRA_SHAPES: List<Any> = listOf(
    TurnStructure(listOf(PhaseSpec("main"))),
    // "damageCounter": null -- players take no damage (EPR Skirmish, Core).
    RulesDoc(damageCounter = null),
)

/** Keys whose value is a MAP (its keys are data -- a mana colour, a field
 *  name), not a record. Structure cannot tell the two apart; the bundled-game
 *  validation below fails if this list is short. */
private val MAP_KEYS = setOf("mana", "fields", "delta", "slots", "counters", "copies", "maxCopies")

// -- inference ---------------------------------------------------------------

private typealias S = Map<String, Any?>

private val TAG_KEYS = listOf("op", "kind", "k")

private fun familyOf(j: Json): String? = FAMILIES.firstOrNull { f ->
    runCatching { Json.parse(f.enc(f.dec(j))) == j }.getOrDefault(false)
}?.name

private fun prim(j: Json): S = when (j) {
    is Json.Str -> mapOf("type" to "string")
    is Json.Bool -> mapOf("type" to "boolean")
    is Json.Num -> mapOf("type" to if (j.value == Math.floor(j.value)) "integer" else "number")
    Json.Null -> mapOf("type" to "null")
    else -> error("not a primitive: $j")
}

/** Two observations of one position, as one schema. */
private fun merge(a: S, b: S): S {
    if (a == b) return a
    fun alts(x: S): List<S> = @Suppress("UNCHECKED_CAST") (x["anyOf"] as? List<S>) ?: listOf(x)
    if ("enum" in a && "enum" in b) {
        @Suppress("UNCHECKED_CAST")
        return mapOf("enum" to ((a["enum"] as List<String>) + (b["enum"] as List<String>)).distinct().sorted())
    }
    if (a["type"] == "object" && b["type"] == "object" && "properties" in a && "properties" in b) {
        @Suppress("UNCHECKED_CAST") val pa = a["properties"] as Map<String, S>
        @Suppress("UNCHECKED_CAST") val pb = b["properties"] as Map<String, S>
        val keys = (pa.keys + pb.keys).sorted()
        return a + ("properties" to keys.associateWith { k -> if (k in pa && k in pb) merge(pa.getValue(k), pb.getValue(k)) else pa[k] ?: pb.getValue(k) })
    }
    if (a["type"] == "object" && b["type"] == "object" && "additionalProperties" in a && "additionalProperties" in b &&
        a["additionalProperties"] is Map<*, *> && b["additionalProperties"] is Map<*, *>
    ) {
        @Suppress("UNCHECKED_CAST")
        return a + ("additionalProperties" to merge(a["additionalProperties"] as S, b["additionalProperties"] as S))
    }
    if (a["type"] == "array" && b["type"] == "array") {
        @Suppress("UNCHECKED_CAST") val ia = a["items"] as S?
        @Suppress("UNCHECKED_CAST") val ib = b["items"] as S?
        return mapOf("type" to "array", "items" to (if (ia == null) ib else if (ib == null) ia else merge(ia, ib)))
            .filterValues { it != null }
    }
    if (setOf(a["type"], b["type"]) == setOf("integer", "number") && a.size == 1 && b.size == 1) return mapOf("type" to "number")
    val all = (alts(a) + alts(b)).distinct()
    return if (all.size == 1) all.single() else mapOf("anyOf" to all)
}

/** The schema of one JSON value observed at a position. */
private fun describe(j: Json, key: String?): S = when (j) {
    is Json.Obj -> familyOf(j)?.let { mapOf("\$ref" to "#/\$defs/$it") } ?: if (key in MAP_KEYS) {
        mapOf("type" to "object", "additionalProperties" to j.members.values.map { describe(it, null) }.reduceOrNull(::merge).orEmpty())
    } else {
        record(j)
    }
    is Json.Arr -> j.items.map { describe(it, null) }.reduceOrNull(::merge)
        ?.let { mapOf("type" to "array", "items" to it) } ?: mapOf("type" to "array")
    else -> prim(j)
}

/** A record. `enums` are the keys whose value is an enum's spelling (or the
 *  family's tag): their observed values are listed, so a typo is an error. */
private fun record(j: Json.Obj, tag: String? = null, enums: Set<String> = emptySet(), typed: Map<String, S> = emptyMap()): S = mapOf(
    "type" to "object",
    "properties" to j.members.keys.sorted().associateWith { k ->
        val v = j.members.getValue(k)
        typed[k] ?: if ((k == tag || k in enums) && v is Json.Str) mapOf("enum" to listOf(v.value)) else describe(v, k)
    },
    "additionalProperties" to false,
) + (tag?.let { mapOf("required" to listOf(it)) } ?: emptyMap())

private fun componentsOf(c: Class<*>): List<java.lang.reflect.Method> =
    c.methods.filter { it.parameterCount == 0 && Regex("component\\d+").matches(it.name) }
        .sortedBy { it.name.removePrefix("component").toInt() }

/** The family a declared Kotlin type IS, if any. */
private fun familyFor(t: java.lang.reflect.Type): Family? =
    (t as? Class<*>)?.let { c -> FAMILIES.firstOrNull { it.cls.isAssignableFrom(c) } }

/** The schema a field's DECLARED type gives its JSON key: a family (or a
 *  list, set or map of one) is a `$ref`, whatever the specimen happened to
 *  hold. A slot typed `IntExpr` holding a literal is a bare number, and only
 *  its type says it could hold `{"op":"selfCounter",...}`. Null = not a family
 *  type; infer from the value. */
private fun declaredSchema(t: java.lang.reflect.Type): S? {
    familyFor(t)?.let { return mapOf("\$ref" to "#/\$defs/${it.name}") }
    if (t is java.lang.reflect.ParameterizedType) {
        val raw = t.rawType as Class<*>
        val args = t.actualTypeArguments.map { if (it is java.lang.reflect.WildcardType) it.upperBounds[0] else it }
        if (Collection::class.java.isAssignableFrom(raw)) declaredSchema(args[0])?.let { return mapOf("type" to "array", "items" to it) }
        if (Map::class.java.isAssignableFrom(raw) && args[0] == String::class.java) {
            declaredSchema(args[1])?.let { return mapOf("type" to "object", "additionalProperties" to it) }
        }
    }
    return null
}

/** A different value of the same declared type -- enough to see which JSON
 *  key a field is written under. */
private fun perturb(v: Any?, t: Class<*>): Any? = when {
    v is Int -> v + 1
    v is Boolean -> !v
    v is String -> v + "_"
    v is Float -> v + 0.5f
    v is Enum<*> -> t.enumConstants.firstOrNull { it != v }
    v is List<*> && v.isNotEmpty() -> v + v.first()
    v is Set<*> && v.isNotEmpty() -> v.drop(1).toSet()
    v is Map<*, *> && v.isNotEmpty() -> v.entries.drop(1).associate { it.key to it.value }
    v != null && familyFor(v.javaClass) != null -> SPECIMENS.firstOrNull { t.isInstance(it) && it != v }
    else -> null
}

/** Which JSON key each field of `x` is written under, mapped to the schema its
 *  declared type gives (for the fields whose type is a family). */
private fun typedKeys(x: Any, f: Family): Map<String, S> {
    val base = Json.parse(f.enc(x)) as? Json.Obj ?: return emptyMap()
    val comps = componentsOf(x.javaClass)
    val ctor = x.javaClass.declaredConstructors.firstOrNull { c ->
        c.parameterCount == comps.size && c.parameterTypes.toList() == comps.map { it.returnType }
    } ?: return emptyMap()
    ctor.isAccessible = true
    val values = comps.map { it.invoke(x) }
    val out = mutableMapOf<String, S>()
    comps.forEachIndexed { i, m ->
        val schema = declaredSchema(m.genericReturnType) ?: return@forEachIndexed
        val other = perturb(values[i], m.returnType) ?: return@forEachIndexed
        val v = runCatching { Json.parse(f.enc(ctor.newInstance(*values.toMutableList().also { it[i] = other }.toTypedArray()))) }
            .getOrNull() as? Json.Obj ?: return@forEachIndexed
        val changed = (base.members.keys + v.members.keys).filter { base.members[it] != v.members[it] }
        if (changed.size == 1) out[changed.single()] = schema
    }
    return out
}

/** `x` once per other constant of each of its enum-typed fields -- how a
 *  specimen shows every spelling an enum can take ("lte" as well as "gte"). */
private fun enumVariants(x: Any): List<Pair<Int, Any>> {
    val comps = componentsOf(x.javaClass)
    val ctor = x.javaClass.declaredConstructors.firstOrNull { c ->
        c.parameterCount == comps.size && c.parameterTypes.toList() == comps.map { it.returnType }
    } ?: return emptyList()
    ctor.isAccessible = true
    val values = comps.map { it.invoke(x) }
    return comps.indices.flatMap { i ->
        val t = comps[i].returnType
        if (!t.isEnum) emptyList() else t.enumConstants.filter { it != values[i] }.mapNotNull { e ->
            runCatching { i to ctor.newInstance(*values.toMutableList().also { it[i] = e }.toTypedArray()) }.getOrNull()
        }
    }
}

private fun familySchema(f: Family): S {
    val tagged = f.cls.isInterface
    val base = (SPECIMENS + EXTRA_SHAPES).filter { f.cls.isInstance(it) }
    check(base.isNotEmpty()) { "no specimen of ${f.name}" }
    val parts = base.groupBy { it.javaClass }.toSortedMap(compareBy { it.name }).map { (_, xs) ->
        // Keys an enum drives: they changed when only an enum field did.
        val enums = mutableSetOf<String>()
        val typed = xs.map { typedKeys(it, f) }.fold(emptyMap<String, S>()) { acc, m -> acc + m }
        val encoded = xs.flatMap { x ->
            val b = Json.parse(f.enc(x))
            val vs = enumVariants(x).map { Json.parse(f.enc(it.second)) }
            if (b is Json.Obj) vs.filterIsInstance<Json.Obj>().forEach { v ->
                v.members.forEach { (k, jv) -> if (jv is Json.Str && jv != b.members[k]) enums += k }
            }
            listOf(b) + vs
        }
        val tag = if (tagged) TAG_KEYS.firstOrNull { k -> encoded.all { (it as? Json.Obj)?.members?.get(k) is Json.Str } } else null
        encoded.map {
            when {
                it is Json.Obj -> record(it, tag, enums - typed.keys, typed)
                // A member spelled as a bare word ("you") is its own tag.
                tagged && it is Json.Str -> mapOf("enum" to listOf(it.value))
                else -> describe(it, null)
            }
        }.reduce(::merge)
    }
    val flat = parts.flatMap { p -> @Suppress("UNCHECKED_CAST") (p["anyOf"] as? List<S>) ?: listOf(p) }.distinct()
    // The bare words, as one list: "you" | "opponent" | {...}.
    val (words, rest) = flat.partition { it.keys == setOf("enum") }
    val all = (listOfNotNull(words.reduceOrNull(::merge)) + rest)
    return if (all.size == 1) all.single() else mapOf("anyOf" to all)
}

internal fun generateSchema(): String {
    val root: S = mapOf(
        "\$schema" to "https://json-schema.org/draft/2020-12/schema",
        "\$id" to "cardengine.schema.json",
        "title" to "CardEngine v3 game file",
        "description" to "Generated by test/SchemaTest.kt from the codec's specimens. Do not edit: " +
            "regenerate with CGE_WRITE_SCHEMA=1. A game file is a GameDoc; a play session's answers are Answers.",
        "\$ref" to "#/\$defs/GameDoc",
        "\$defs" to FAMILIES.sortedBy { it.name }.associate { it.name to familySchema(it) },
    )
    return pretty(root) + "\n"
}

private fun pretty(v: Any?, indent: String = ""): String {
    val next = "$indent  "
    return when (v) {
        null -> "null"
        is String -> jstr(v)
        is Boolean, is Int -> v.toString()
        is Map<*, *> -> if (v.isEmpty()) "{}" else
            v.entries.joinToString(",\n", "{\n", "\n$indent}") { (k, x) -> "$next${jstr(k as String)}: ${pretty(x, next)}" }
        is List<*> -> if (v.isEmpty()) "[]" else v.joinToString(",\n", "[\n", "\n$indent]") { "$next${pretty(it, next)}" }
        else -> error("unprintable $v")
    }
}

// -- a small validator -------------------------------------------------------
// Only the keywords the generator writes: $ref, anyOf, const, enum, type,
// properties, additionalProperties, required, items.

internal fun validate(schemaText: String, rootRef: String, doc: Json): List<String> {
    val root = Json.parse(schemaText).obj()
    val defs = root.getValue("\$defs").obj()
    val errors = mutableListOf<String>()
    fun ok(s: Json, v: Json, path: String, out: MutableList<String>) {
        val o = s.obj()
        o["\$ref"]?.let { ref -> return ok(defs.getValue(ref.str().removePrefix("#/\$defs/")), v, path, out) }
        o["anyOf"]?.let { alts ->
            val tries = alts.arr().map { a -> mutableListOf<String>().also { ok(a, v, path, it) } }
            if (tries.none { it.isEmpty() }) out += tries.minByOrNull { it.size }!!.ifEmpty { listOf("$path: matches no alternative") }
            return
        }
        o["const"]?.let { c -> if (c != v) out += "$path: expected $c, got $v"; return }
        o["enum"]?.let { e -> if (v !in e.arr()) out += "$path: expected one of ${e.arr().map { it.str() }}, got $v"; return }
        val type = o["type"]?.str()
        val fits = when (type) {
            null -> true
            "object" -> v is Json.Obj
            "array" -> v is Json.Arr
            "string" -> v is Json.Str
            "boolean" -> v is Json.Bool
            "integer" -> v is Json.Num && v.value == Math.floor(v.value)
            "number" -> v is Json.Num
            "null" -> v == Json.Null
            else -> false
        }
        if (!fits) { out += "$path: expected $type, got $v".take(200); return }
        if (v is Json.Obj) {
            val props = o["properties"]?.obj().orEmpty()
            o["required"]?.arr()?.forEach { r -> if (r.str() !in v.members) out += "$path: missing ${r.str()}" }
            for ((k, x) in v.members) {
                val ps = props[k]
                when {
                    ps != null -> ok(ps, x, "$path.$k", out)
                    o["additionalProperties"] == Json.Bool(false) -> out += "$path: unexpected key '$k'"
                    o["additionalProperties"] is Json.Obj -> ok(o.getValue("additionalProperties"), x, "$path.$k", out)
                }
            }
        }
        if (v is Json.Arr) o["items"]?.let { it2 -> v.items.forEachIndexed { i, x -> ok(it2, x, "$path[$i]", out) } }
    }
    ok(Json.Obj(mapOf("\$ref" to Json.Str("#/\$defs/$rootRef"))), doc, rootRef, errors)
    return errors
}

internal fun schemaChecks() {
    println()
    println("The written contract -- a JSON Schema generated from the codec")
    val schema = generateSchema()
    val file = java.io.File(SCHEMA_PATH)
    if (System.getenv("CGE_WRITE_SCHEMA") == "1") {
        file.parentFile.mkdirs(); file.writeText(schema)
        println("  wrote $SCHEMA_PATH")
    }

    check("the committed schema is what the codec generates") {
        assertTrue(file.exists() && file.readText() == schema,
            "$SCHEMA_PATH is stale -- rerun with CGE_WRITE_SCHEMA=1 and commit it (the diff IS the grammar change)")
    }

    check("every bundled game validates against the schema") {
        var n = 0
        val games = listOf(EPR_SKIRMISH, CORE_BUNDLE) + SAMPLE_GAMES
        val errors = games.flatMap { g ->
            listOf(g, g.withCardIds { "id${n++}" }).flatMap { v -> validate(schema, "GameDoc", Json.parse(gameDocToJson(v))).map { "${g.name}: $it" } }
        }
        assertTrue(errors.isEmpty(), "\n    " + errors.distinct().take(20).joinToString("\n    "))
    }

    check("the schema has teeth: an unknown op and an unknown key are both rejected") {
        val bad1 = Json.parse(gameDocToJson(CORE_BUNDLE).replaceFirst("\"op\":\"draw\"", "\"op\":\"drwa\""))
        val bad2 = Json.parse(gameDocToJson(CORE_BUNDLE).replaceFirst("\"name\":", "\"nmae\":"))
        assertTrue(validate(schema, "GameDoc", bad1).isNotEmpty(), "a misspelt op")
        assertTrue(validate(schema, "GameDoc", bad2).isNotEmpty(), "a misspelt key")
    }
}
