package ccg

// ---------------------------------------------------------------------------
// The serialisable form of the expression language: IntExpr / BoolExpr <->
// JSON. A number literal is a bare JSON number; every other node is an object
// with an "op" discriminator.
// ---------------------------------------------------------------------------

// -- IntExpr -> JSON --------------------------------------------------------

fun intExprToJson(e: IntExpr): String = when (e) {
    is IntExpr.Lit -> e.value.toString()
    IntExpr.X -> """{"op":"x"}"""
    is IntExpr.CountPerms -> """{"op":"count","filter":${filterToJson(e.filter)}}"""
    is IntExpr.CountCounters ->
        """{"op":"countCounters","filter":${filterToJson(e.filter)}${e.kind?.let { ""","kind":${jstr(it)}""" } ?: ""}}"""
    is IntExpr.HandSize -> """{"op":"handSize","who":${playerRefToJson(e.who)}}"""
    is IntExpr.LifeOf -> """{"op":"lifeOf","who":${playerRefToJson(e.who)}}"""
    is IntExpr.Bin -> """{"op":${jstr(binStr(e.op))},"a":${intExprToJson(e.a)},"b":${intExprToJson(e.b)}}"""
    is IntExpr.SelfField -> """{"op":"selfField","key":${jstr(e.key)}}"""
    IntExpr.SelfDamage -> """{"op":"selfDamage"}"""
    is IntExpr.SelfCounter -> """{"op":"selfCounter","kind":${jstr(e.kind)}}"""
    is IntExpr.TargetField -> """{"op":"targetField","target":${btJson(e.target)},"key":${jstr(e.key)}}"""
    is IntExpr.TargetCounter -> """{"op":"targetCounter","target":${btJson(e.target)},"kind":${jstr(e.kind)}}"""
    is IntExpr.TargetDamage -> """{"op":"targetDamage","target":${btJson(e.target)}}"""
    IntExpr.Share -> """{"op":"share"}"""
    IntExpr.EventAmount -> """{"op":"eventAmount"}"""
    is IntExpr.Param -> """{"op":"param","name":${jstr(e.name)}}"""
    IntExpr.TurnNumber -> """{"op":"turnNumber"}"""
    is IntExpr.SeatOf -> """{"op":"seatOf","who":${playerRefToJson(e.who)}}"""
    is IntExpr.LaneOf -> """{"op":"laneOf","target":${btJson(e.target)}}"""
    is IntExpr.Cond -> """{"op":"cond","if":${boolExprToJson(e.cond)},"then":${intExprToJson(e.then)},"else":${intExprToJson(e.otherwise)}}"""
    is IntExpr.PlayerCounter -> """{"op":"playerCounter","who":${playerRefToJson(e.who)},"name":${jstr(e.name)}}"""
    is IntExpr.ZoneSize -> """{"op":"zoneSize","who":${playerRefToJson(e.who)},"zone":${jstr(enumStr(e.zone))}}"""
}

fun boolExprToJson(e: BoolExpr): String = when (e) {
    is BoolExpr.Const -> e.value.toString()
    is BoolExpr.Cmp -> """{"op":${jstr(cmpStr(e.op))},"a":${intExprToJson(e.a)},"b":${intExprToJson(e.b)}}"""
    is BoolExpr.And -> """{"op":"and","terms":[${e.terms.joinToString(",") { boolExprToJson(it) }}]}"""
    is BoolExpr.Or -> """{"op":"or","terms":[${e.terms.joinToString(",") { boolExprToJson(it) }}]}"""
    is BoolExpr.Not -> """{"op":"not","term":${boolExprToJson(e.term)}}"""
    is BoolExpr.HasType -> """{"op":"hasType","types":[${e.types.joinToString(",") { jstr(it) }}]}"""
    is BoolExpr.HasKeyword -> """{"op":"hasKeyword","target":${btJson(e.target)},"keyword":${jstr(e.keyword)}}"""
    is BoolExpr.IsType -> """{"op":"isType","target":${btJson(e.target)},"types":[${e.types.joinToString(",") { jstr(it) }}]}"""
    is BoolExpr.IsExhausted -> """{"op":"isExhausted","target":${btJson(e.target)}}"""
    is BoolExpr.IsToken -> """{"op":"isToken","target":${btJson(e.target)}}"""
    is BoolExpr.HasField -> """{"op":"hasField","target":${btJson(e.target)},"key":${jstr(e.key)}}"""
    is BoolExpr.AtDepth -> """{"op":"atDepth","target":${btJson(e.target)},"depth":${jstr(enumStr(e.depth))}}"""
    is BoolExpr.ZoneOwner -> """{"op":"zoneOwner","target":${btJson(e.target)}""" + (e.who?.let { ""","who":${playerRefToJson(it)}""" } ?: "") + "}"
}

/** internal, not private: EffectJson.kt embeds a PermFilter inside several
 *  Effect nodes (ApplyModifier / ForEach / Choose / Sacrifice) and StaticSpec. */
internal fun filterToJson(f: PermFilter): String = buildString {
    append("{")
    val parts = mutableListOf<String>()
    if (f.types.isNotEmpty()) parts += "\"types\":[${f.types.joinToString(",") { jstr(it) }}]"
    if (f.controller != null) parts += "\"controller\":${playerRefToJson(f.controller)}"
    if (f.excludesSource) parts += "\"excludesSource\":true"
    f.pinned?.let { parts += "\"only\":${btJson(it)}" }
    if (f.hasCounter != null) parts += """"hasCounter":${jstr(f.hasCounter)},"minCount":${f.minCount}"""
    if (f.onlyHost) parts += "\"onlyHost\":true"
    f.where?.let { parts += "\"where\":${boolExprToJson(it)}" }
    when (val z = f.zone) {
        ZoneScoping.Any -> {}
        is ZoneScoping.SameAs -> {
            val of = if (z.of == BoundTarget(SELF)) "" else ",\"of\":${btJson(z.of)}"
            parts += "\"zone\":{\"scope\":\"${if (z.eitherSide) "sameDef" else "thisZone"}\"$of}"
        }
        is ZoneScoping.Named -> parts += "\"zone\":{\"scope\":\"named\",\"def\":${jstr(z.def)}}"
        is ZoneScoping.Exact ->
            parts += "\"zone\":{\"scope\":\"exact\",\"def\":${jstr(z.ref.def)}${z.ref.owner?.let { ""","owner":${jstr(it)}""" } ?: ""}}"
    }
    append(parts.joinToString(","))
    append("}")
}

private fun zoneScopingOf(j: Json): ZoneScoping {
    val o = j.obj()
    return when (val scope = o.req("scope").str()) {
        // "of" is absent for the source (older files never name one).
        "thisZone", "sameDef" -> ZoneScoping.SameAs(o["of"]?.let { btOf(it) } ?: BoundTarget(SELF), eitherSide = scope == "sameDef")
        "named" -> ZoneScoping.Named(o.req("def").str())
        "exact" -> ZoneScoping.Exact(ZoneRef(o.req("def").str(), o["owner"]?.str()))
        else -> error("unknown zone scope '$scope'")
    }
}

private fun binStr(op: BinOp) = enumStr(op)
private fun cmpStr(op: CmpOp) = when (op) {
    CmpOp.EQ -> "eq"; CmpOp.NE -> "ne"; CmpOp.LT -> "lt"; CmpOp.LTE -> "lte"; CmpOp.GT -> "gt"; CmpOp.GTE -> "gte"
}

// -- JSON -> IntExpr / BoolExpr -------------------------------------------

fun intExprFromJson(text: String): IntExpr = intExprOf(Json.parse(text))
fun boolExprFromJson(text: String): BoolExpr = boolExprOf(Json.parse(text))


internal fun filterOf(j: Json): PermFilter {
    val o = j.obj()
    return PermFilter(
        types = (o["types"]?.arr()?.map { it.str() } ?: emptyList()).toSet(),
        controller = o["controller"]?.let { playerRefOf(it) },
        excludesSource = o.boolOr("excludesSource", false),
        zone = o["zone"]?.let { zoneScopingOf(it) } ?: ZoneScoping.Any,
        pinned = o["only"]?.let { btOf(it) },
        hasCounter = o["hasCounter"]?.str(),
        minCount = o.intOr("minCount", 1),
        onlyHost = o.boolOr("onlyHost", false),
        where = o["where"]?.let { boolExprOf(it) },
    )
}

internal fun intExprOf(j: Json): IntExpr = when (j) {
    is Json.Num -> IntExpr.Lit(j.value.toInt())
    is Json.Obj -> {
        val o = j.members
        when (val op = o.req("op").str()) {
            "x" -> IntExpr.X
            "count" -> IntExpr.CountPerms(filterOf(o.req("filter")))
            "countCounters" -> IntExpr.CountCounters(filterOf(o.req("filter")), o["kind"]?.str())
            "handSize" -> IntExpr.HandSize(playerRefOf(o.req("who")))
            "lifeOf" -> IntExpr.LifeOf(playerRefOf(o.req("who")))
            "add", "sub", "mul", "div", "min", "max" -> IntExpr.Bin(enumOf<BinOp>(op), intExprOf(o.req("a")), intExprOf(o.req("b")))
            "selfField" -> IntExpr.SelfField(o.req("key").str())
            "selfDamage" -> IntExpr.SelfDamage
            "selfCounter" -> IntExpr.SelfCounter(o.req("kind").str())
        "targetField" -> IntExpr.TargetField(btOf(o.req("target")), o.req("key").str())
        "targetCounter" -> IntExpr.TargetCounter(btOf(o.req("target")), o.req("kind").str())
        "targetDamage" -> IntExpr.TargetDamage(btOf(o.req("target")))
        "share" -> IntExpr.Share
        "eventAmount" -> IntExpr.EventAmount
        "param" -> IntExpr.Param(o.req("name").str())
        "turnNumber" -> IntExpr.TurnNumber
        "seatOf" -> IntExpr.SeatOf(playerRefOf(o.req("who")))
        "laneOf" -> IntExpr.LaneOf(btOf(o.req("target")))
        "cond" -> IntExpr.Cond(boolExprOf(o.req("if")), intExprOf(o.req("then")), intExprOf(o.req("else")))
        "playerCounter" -> IntExpr.PlayerCounter(playerRefOf(o.req("who")), o.req("name").str())
        "zoneSize" -> IntExpr.ZoneSize(playerRefOf(o.req("who")), enumOf<HiddenZone>(o.req("zone").str()))
            else -> error("unknown IntExpr op '$op'")
        }
    }
    else -> error("expected a number or object for an IntExpr, got $j")
}

internal fun boolExprOf(j: Json): BoolExpr = when (j) {
    is Json.Bool -> BoolExpr.Const(j.value)
    is Json.Obj -> {
        val o = j.members
        when (val op = o.req("op").str()) {
            "eq" -> cmp(o, CmpOp.EQ); "ne" -> cmp(o, CmpOp.NE)
            "lt" -> cmp(o, CmpOp.LT); "lte" -> cmp(o, CmpOp.LTE)
            "gt" -> cmp(o, CmpOp.GT); "gte" -> cmp(o, CmpOp.GTE)
            "and" -> BoolExpr.And(o.req("terms").arr().map { boolExprOf(it) })
            "or" -> BoolExpr.Or(o.req("terms").arr().map { boolExprOf(it) })
            "not" -> BoolExpr.Not(boolExprOf(o.req("term")))
            "hasType" -> BoolExpr.HasType(o.req("types").arr().map { it.str() }.toSet())
            "hasKeyword" -> BoolExpr.HasKeyword(btOf(o.req("target")), o.req("keyword").str())
            "isType" -> BoolExpr.IsType(btOf(o.req("target")), o.req("types").arr().map { it.str() }.toSet())
            "isExhausted" -> BoolExpr.IsExhausted(btOf(o.req("target")))
            "isToken" -> BoolExpr.IsToken(btOf(o.req("target")))
            "hasField" -> BoolExpr.HasField(btOf(o.req("target")), o.req("key").str())
            "atDepth" -> BoolExpr.AtDepth(btOf(o.req("target")), enumOf<Depth>(o.req("depth").str()))
            "zoneOwner" -> BoolExpr.ZoneOwner(btOf(o.req("target")), o["who"]?.let { playerRefOf(it) })
            else -> error("unknown BoolExpr op '$op'")
        }
    }
    else -> error("expected a boolean or object for a BoolExpr, got $j")
}

private fun cmp(o: Map<String, Json>, op: CmpOp) = BoolExpr.Cmp(intExprOf(o.req("a")), op, intExprOf(o.req("b")))
