package ccg

// ---------------------------------------------------------------------------
// JSON for the Effect tree and its direct dependencies.
//
// Follows ExprJson.kt's convention exactly: a bare
// JSON number/string/bool where that's the whole node, an {"op": ...}
// discriminator everywhere else.
// ---------------------------------------------------------------------------

fun effectToJson(e: Effect): String = when (e) {
    is Effect.DealDamage -> """{"op":"dealDamage","amount":${intExprToJson(e.amount)},""" +
        (e.target?.let { """"target":${btJson(it)}}""" } ?: """"player":${playerRefToJson(e.player!!)}}""")
    is Effect.DamageOpponent -> """{"op":"damageOpponent","amount":${intExprToJson(e.amount)}}"""
    is Effect.Draw -> """{"op":"draw","who":${playerRefToJson(e.who)},"count":${intExprToJson(e.count)}}"""
    is Effect.GainLife ->
        """{"op":"gainLife","who":${playerRefToJson(e.who)},"amount":${intExprToJson(e.amount)}""" +
            (if (e.counter != LIFE) ""","counter":${jstr(e.counter)}""" else "") + "}"
    is Effect.AddMana -> """{"op":"addMana","who":${playerRefToJson(e.who)},"mana":${exprMapToJson(e.mana)}}"""
    is Effect.Destroy -> """{"op":"destroy","target":${btJson(e.target)}}"""
    is Effect.AddCounter ->
        """{"op":"addCounter","kind":${jstr(e.kind)},"count":${intExprToJson(e.count)},"target":${btJson(e.target)}}"""
    is Effect.RemoveCounter ->
        """{"op":"removeCounter","kind":${jstr(e.kind)},"count":${intExprToJson(e.count)},"target":${btJson(e.target)}}"""
    is Effect.Transform -> """{"op":"transform","target":${btJson(e.target)}}"""
    is Effect.CreateToken -> buildString {
        append("""{"op":"createToken","chars":${charsToJson(e.chars)},"count":${intExprToJson(e.count)}""")
        e.zone?.let { append(""","zone":${jstr(it)}""") }
        append("}")
    }
    is Effect.CopyOf -> """{"op":"copyOf","target":${btJson(e.target)}}"""
    is Effect.CreateEmblem -> """{"op":"createEmblem","statics":${staticsToJson(e.statics)}}"""
    is Effect.ChooseMode ->
        """{"op":"chooseMode","pick":${intExprToJson(e.pick)},"options":[${e.options.joinToString(",") { effectToJson(it) }}]}"""
    is Effect.Discard -> buildString {
        append("""{"op":"discard","who":${playerRefToJson(e.who)},"count":${intExprToJson(e.count)}""")
        if (e.toZone != HiddenZone.GRAVEYARD) append(""","toZone":${jstr(enumStr(e.toZone))}""")
        append("}")
    }
    is Effect.DrawThenDiscard -> buildString {
        append(
            """{"op":"drawThenDiscard","who":${playerRefToJson(e.who)},""" +
                """"draw":${intExprToJson(e.draw)},"discard":${intExprToJson(e.discard)}""",
        )
        if (e.toZone != HiddenZone.GRAVEYARD) append(""","toZone":${jstr(enumStr(e.toZone))}""")
        append("}")
    }
    is Effect.ReturnFromDiscard ->
        """{"op":"returnFromDiscard","who":${playerRefToJson(e.who)},"count":${intExprToJson(e.count)},"toBattlefield":${e.toBattlefield}}"""
    is Effect.SearchZone -> buildString {
        append("""{"op":"searchZone","who":${playerRefToJson(e.who)},"from":${jstr(enumStr(e.from))},"to":${jstr(enumStr(e.to))},"count":${intExprToJson(e.count)}""")
        append(""","filter":${cardFilterToJson(e.filter)},"thenShuffle":${e.thenShuffle},"upTo":${e.upTo}""")
        if (e.intoPlay) append(""","intoPlay":true""")
        append("}")
    }
    is Effect.Shuffle -> """{"op":"shuffle","who":${playerRefToJson(e.who)},"zone":${jstr(enumStr(e.zone))}}"""
    is Effect.MoveTop -> """{"op":"moveTop","who":${playerRefToJson(e.who)},"count":${intExprToJson(e.count)},"to":${jstr(enumStr(e.to))}}"""
    is Effect.LookAtTop -> """{"op":"lookAtTop","who":${playerRefToJson(e.who)},"count":${intExprToJson(e.count)},"to":${jstr(enumStr(e.to))}}"""
    is Effect.MovePermanent -> """{"op":"movePermanent","target":${btJson(e.target)},"toZone":${zoneRefToJson(e.toZone)}}"""
    is Effect.ApplyModifier -> buildString {
        append("""{"op":"applyModifier","filter":${filterToJson(e.filter)},"ops":[""")
        append(e.ops.joinToString(",") { charOpToJson(it) })
        append("""],"duration":${durationToJson(e.duration)}""")
        if (e.layer != null) append(""","layer":${e.layer}""")
        append("}")
    }
    is Effect.PreventDamage ->
        """{"op":"preventDamage","target":${btJson(e.target)},"amount":${intExprToJson(e.amount)},""" +
            """"all":${e.all},"duration":${durationToJson(e.duration)},"mode":${jstr(shieldModeStr(e.mode))}""" +
            (e.who?.let { ""","who":${playerRefToJson(it)}""" } ?: "") +
            (e.onlyStep?.let { ""","onlyStep":${jstr(it)}""" } ?: "") + "}"
    is Effect.Proceed -> """{"op":"proceed"${e.amount?.let { ""","amount":${intExprToJson(it)}""" } ?: ""}}"""
    is Effect.SetCombatMode -> """{"op":"setCombatMode","target":${btJson(e.target)}${e.mode?.let { ""","mode":${jstr(it)}""" } ?: ""}}"""
    is Effect.Attach -> """{"op":"attach","host":${btJson(e.host)}}"""
    is Effect.ForEach -> """{"op":"forEach","filter":${filterToJson(e.filter)},"body":${effectToJson(e.body)}${bindsJson(e.binds, EACH)}}"""
    is Effect.ForEachPlayer -> """{"op":"forEachPlayer","body":${effectToJson(e.body)}}"""
    is Effect.Sacrifice ->
        """{"op":"sacrifice","who":${playerRefToJson(e.who)},"count":${intExprToJson(e.count)},"filter":${filterToJson(e.filter)}}"""
    is Effect.Delayed -> buildString {
        append("""{"op":"delayed","on":${eventPatternToJson(e.on)},"effect":${effectToJson(e.effect)},""")
        append(""""once":${e.once},"duration":${durationToJson(e.duration)}""")
        if (e.expiresAfter != null) append(""","expiresAfter":${eventPatternToJson(e.expiresAfter)}""")
        append("}")
    }
    is Effect.Sequence -> """{"op":"sequence","steps":[${e.steps.joinToString(",") { effectToJson(it) }}]}"""
    is Effect.Choose -> """{"op":"choose","filter":${filterToJson(e.filter)},"body":${effectToJson(e.body)}${bindsJson(e.binds, CHOSEN)}}"""
    is Effect.ChooseMany -> buildString {
        append("""{"op":"chooseMany","filter":${filterToJson(e.filter)},"count":${intExprToJson(e.count)}""")
        if (e.upTo) append(""","upTo":true""")
        e.divide?.let { append(""","divide":${intExprToJson(it)}""") }
        append(""","body":${effectToJson(e.body)}${bindsJson(e.binds, EACH)}}""")
    }
    is Effect.If ->
        """{"op":"if","cond":${boolExprToJson(e.cond)},"then":${effectToJson(e.then)},"otherwise":${effectToJson(e.otherwise)}}"""
    Effect.NoOp -> """{"op":"noOp"}"""
    is Effect.CounterSpell ->
        """{"op":"counterSpell","types":[${e.types.joinToString(",") { jstr(it) }}]""" +
            (e.whose?.let { ""","whose":${playerRefToJson(it)}""" } ?: ""","whose":null""") +
            (e.target?.let { ""","target":${btJson(it)}""" } ?: "") + "}"
    is Effect.Tap -> """{"op":"tap","target":${btJson(e.target)},"untap":${e.untap}}"""
    is Effect.ClearDamage -> """{"op":"clearDamage","target":${btJson(e.target)}}"""
    is Effect.AsPlayer -> """{"op":"asPlayer","who":${playerRefToJson(e.who)},"body":${effectToJson(e.body)}}"""
    is Effect.SendTo -> """{"op":"sendTo","target":${btJson(e.target)},"to":${jstr(enumStr(e.to))}}"""
    is Effect.GainControl -> """{"op":"gainControl","target":${btJson(e.target)},"duration":${durationToJson(e.duration)}}"""
    is Effect.DeclareAttackers -> """{"op":"declareAttackers","eligible":${filterToJson(e.eligible)}""" +
        (e.staysReady?.let { ""","staysReady":${filterToJson(it)}""" } ?: "") +
        ""","then":${effectToJson(e.then)}}"""
    is Effect.DeclareBlockers -> """{"op":"declareBlockers","eligible":${filterToJson(e.eligible)}""" +
        (if (e.rules.isNotEmpty()) ""","rules":[${e.rules.joinToString(",") { blockRuleToJson(it) }}]""" else "") +
        ""","then":${effectToJson(e.then)}}"""
    Effect.CombatWindow -> """{"op":"combatWindow"}"""
    is Effect.Attack -> """{"op":"attack","attacker":${filterToJson(e.attacker)},"targets":${filterToJson(e.targets)}""" +
        (if (e.attacksPerTurn != lit(1)) ""","attacksPerTurn":${intExprToJson(e.attacksPerTurn)}""" else "") +
        (e.mustTarget?.let { ""","mustTarget":${filterToJson(it)}""" } ?: "") +
        (e.redirect?.let { ""","redirect":${filterToJson(it)}""" } ?: "") +
        (e.staysReady?.let { ""","staysReady":${filterToJson(it)}""" } ?: "") +
        (if (e.reaches != BoolExpr.Const(true)) ""","reaches":${boolExprToJson(e.reaches)}""" else "") +
        (if (e.reachesFace != BoolExpr.Const(true)) ""","reachesFace":${boolExprToJson(e.reachesFace)}""" else "") +
        ""","then":${effectToJson(e.then)}}"""
    is Effect.FreeAttacks -> """{"op":"freeAttacks","step":${jstr(e.step)},"actors":${filterToJson(e.actors)},"body":${gunToJson(e.body)}""" +
        (if (e.guns.isNotEmpty()) ""","guns":[${e.guns.joinToString(",") { gunToJson(it) }}]""" else "") +
        (e.guards?.let { ""","guards":${filterToJson(it)}""" } ?: "") +
        (e.lethal?.let { ""","lethal":${filterToJson(it)}""" } ?: "") +
        (if (e.window) ""","window":true""" else "") +
        (if (e.overflow) ""","overflow":true""" else "") + "}"
    is Effect.Strike -> """{"op":"strike","step":${jstr(e.step)},"amount":${intExprToJson(e.amount)}""" +
        (e.faceAmount?.let { ""","faceAmount":${intExprToJson(it)}""" } ?: "") +
        (if (!e.returnDamage) ""","returnDamage":false""" else "") +
        (e.lethal?.let { ""","lethal":${filterToJson(it)}""" } ?: "") + "}"
    is Effect.Clash -> """{"op":"clash","step":${jstr(e.step)},"attackStat":${intExprToJson(e.attackStat)},"defendStat":${intExprToJson(e.defendStat)}""" +
        (e.faceAmount?.let { ""","faceAmount":${intExprToJson(it)}""" } ?: "") +
        (if (!e.returnDamage) ""","returnDamage":false""" else "") +
        (if (e.excessToController) ""","excessToController":true""" else "") +
        (if (e.onTie != TieResult.BOTH_DESTROYED) ""","onTie":${jstr(tieResultToJson(e.onTie))}""" else "") +
        (if (e.stances.isNotEmpty()) ""","stances":[${e.stances.joinToString(",") { stanceToJson(it) }}]""" else "") +
        (e.pierces?.let { ""","pierces":${filterToJson(it)}""" } ?: "") + "}"
    is Effect.CombatDamage -> """{"op":"combatDamage","step":${jstr(e.step)},"amount":${intExprToJson(e.amount)}""" +
        (e.acts?.let { ""","acts":${filterToJson(it)}""" } ?: "") +
        (e.lethal?.let { ""","lethal":${filterToJson(it)}""" } ?: "") +
        (e.tramples?.let { ""","tramples":${filterToJson(it)}""" } ?: "") +
        (if (e.minBlockers != lit(1)) ""","minBlockers":${intExprToJson(e.minBlockers)}""" else "") +
        (if (e.overflow) ""","overflow":true""" else "") + "}"
}

fun effectFromJson(text: String): Effect = effectOf(Json.parse(text))

/** A `Gun`. Defaults elided, so a body attack is short. */
internal fun gunToJson(g: Gun): String = buildString {
    append("""{"amount":${intExprToJson(g.amount)}""")
    if (g.faceAmount != g.amount) append(""","faceAmount":${intExprToJson(g.faceAmount)}""")
    if (g.carried != BoolExpr.Const(true)) append(""","carried":${boolExprToJson(g.carried)}""")
    if (g.reaches != BoolExpr.Const(true)) append(""","reaches":${boolExprToJson(g.reaches)}""")
    if (g.reachesFace != BoolExpr.Const(true)) append(""","reachesFace":${boolExprToJson(g.reachesFace)}""")
    g.label?.let { append(""","label":${jstr(enumStr(it))}""") }
    append("}")
}

internal fun gunOf(j: Json): Gun {
    val o = j.obj()
    val amount = intExprOf(o.req("amount"))
    return Gun(
        carried = o["carried"]?.let { boolExprOf(it) } ?: BoolExpr.Const(true),
        reaches = o["reaches"]?.let { boolExprOf(it) } ?: BoolExpr.Const(true),
        reachesFace = o["reachesFace"]?.let { boolExprOf(it) } ?: BoolExpr.Const(true),
        amount = amount,
        faceAmount = o["faceAmount"]?.let { intExprOf(it) } ?: amount,
        label = o["label"]?.str()?.let { enumOf<AttackRange>(it) },
    )
}

/** internal, not private: DocJson.kt embeds Effect nodes inside Cost /
 *  ActivatedAbility / TriggerDoc / FaceDoc without a string round-trip. */
internal fun effectOf(j: Json): Effect {
    val o = j.obj()
    return when (val op = o.req("op").str()) {
        "dealDamage" -> Effect.DealDamage(intExprOf(o.req("amount")), o["target"]?.let { btOf(it) }, o["player"]?.let { playerRefOf(it) })
        "damageOpponent" -> Effect.DamageOpponent(intExprOf(o.req("amount")))
        "draw" -> Effect.Draw(playerRefOf(o.req("who")), intExprOf(o.req("count")))
        "gainLife" -> Effect.GainLife(playerRefOf(o.req("who")), intExprOf(o.req("amount")), o["counter"]?.str() ?: LIFE)
        "addMana" -> Effect.AddMana(playerRefOf(o.req("who")), exprMapOf(o.req("mana")))
        "destroy" -> Effect.Destroy(btOf(o.req("target")))
        "addCounter" -> Effect.AddCounter(o.req("kind").str(), intExprOf(o.req("count")), btOf(o.req("target")))
        "removeCounter" -> Effect.RemoveCounter(o.req("kind").str(), intExprOf(o.req("count")), btOf(o.req("target")))
        "transform" -> Effect.Transform(btOf(o.req("target")))
        "createToken" -> Effect.CreateToken(charsOf(o.req("chars")), intExprOf(o.req("count")), o["zone"]?.str())
        "copyOf" -> Effect.CopyOf(btOf(o.req("target")))
        "createEmblem" -> Effect.CreateEmblem(staticsOf(o.req("statics")))
        "chooseMode" -> Effect.ChooseMode(o.req("options").arr().map { effectOf(it) }, intExprOf(o.req("pick")))
        "discard" -> Effect.Discard(
            playerRefOf(o.req("who")), intExprOf(o.req("count")),
            o["toZone"]?.str()?.let { enumOf<HiddenZone>(it) } ?: HiddenZone.GRAVEYARD,
        )
        "drawThenDiscard" -> Effect.DrawThenDiscard(
            playerRefOf(o.req("who")), intExprOf(o.req("draw")), intExprOf(o.req("discard")),
            o["toZone"]?.str()?.let { enumOf<HiddenZone>(it) } ?: HiddenZone.GRAVEYARD,
        )
        "returnFromDiscard" -> Effect.ReturnFromDiscard(
            playerRefOf(o.req("who")), intExprOf(o.req("count")), o.boolOr("toBattlefield", false),
        )
        "searchZone" -> Effect.SearchZone(
            playerRefOf(o.req("who")), enumOf<HiddenZone>(o.req("from").str()), enumOf<HiddenZone>(o.req("to").str()),
            intExprOf(o.req("count")),
            // legacy: an exact-name tutor was all `cardId` could say.
            o["filter"]?.let { cardFilterOf(it) } ?: CardFilter(nameIs = o["cardId"]?.str()),
            o.boolOr("thenShuffle", true),
            o.boolOr("upTo", true),
            o.boolOr("intoPlay", false),
        )
        "shuffle" -> Effect.Shuffle(playerRefOf(o.req("who")), enumOf<HiddenZone>(o.req("zone").str()))
        "moveTop" -> Effect.MoveTop(playerRefOf(o.req("who")), intExprOf(o.req("count")), enumOf<HiddenZone>(o.req("to").str()))
        "lookAtTop" -> Effect.LookAtTop(playerRefOf(o.req("who")), intExprOf(o.req("count")), enumOf<HiddenZone>(o.req("to").str()))
        "movePermanent" -> Effect.MovePermanent(btOf(o.req("target")), zoneRefOf(o.req("toZone")))
        "applyModifier" -> Effect.ApplyModifier(
            filter = filterOf(o.req("filter")),
            ops = o.req("ops").arr().map { charOpOf(it) },
            layer = o["layer"]?.int(),
            duration = durationOf(o.req("duration")),
        )
        "preventDamage" -> Effect.PreventDamage(
            target = btOf(o.req("target")),
            amount = intExprOf(o.req("amount")),
            all = o.boolOr("all", false),
            duration = durationOf(o.req("duration")),
            mode = shieldModeOf(o.req("mode").str()),
            who = o["who"]?.let { playerRefOf(it) },
            onlyStep = o["onlyStep"]?.str(),
        )
        "proceed" -> Effect.Proceed(o["amount"]?.let { intExprOf(it) })
        "setCombatMode" -> Effect.SetCombatMode(btOf(o.req("target")), o["mode"]?.str())
        "attach" -> Effect.Attach(btOf(o.req("host")))
        "forEach" -> Effect.ForEach(filterOf(o.req("filter")), effectOf(o.req("body")), o.strOr("binds", EACH))
        "forEachPlayer" -> Effect.ForEachPlayer(effectOf(o.req("body")))
        "sacrifice" -> Effect.Sacrifice(playerRefOf(o.req("who")), intExprOf(o.req("count")), filterOf(o.req("filter")))
        "delayed" -> Effect.Delayed(
            on = eventPatternOf(o.req("on")),
            effect = effectOf(o.req("effect")),
            once = o.boolOr("once", true),
            expiresAfter = o["expiresAfter"]?.let { eventPatternOf(it) },
            duration = durationOf(o.req("duration")),
        )
        "sequence" -> Effect.Sequence(o.req("steps").arr().map { effectOf(it) })
        "choose" -> Effect.Choose(filterOf(o.req("filter")), effectOf(o.req("body")), o.strOr("binds", CHOSEN))
        "chooseMany" -> Effect.ChooseMany(
            filter = filterOf(o.req("filter")),
            count = intExprOf(o.req("count")),
            upTo = o.boolOr("upTo", false),
            divide = o["divide"]?.let { intExprOf(it) },
            body = effectOf(o.req("body")),
            binds = o.strOr("binds", EACH),
        )
        "if" -> Effect.If(boolExprOf(o.req("cond")), effectOf(o.req("then")), effectOf(o.req("otherwise")))
        "noOp" -> Effect.NoOp
        "counterSpell" -> Effect.CounterSpell(
            o["types"]?.arr()?.map { it.str() }?.toSet() ?: emptySet(),
            // Absent means the default (an opponent's); null means anyone's.
            if ("whose" !in o) PlayerRef.Opponent else o["whose"]?.takeIf { it != Json.Null }?.let { playerRefOf(it) },
            o["target"]?.let { btOf(it) },
        )
        "tap" -> Effect.Tap(btOf(o.req("target")), o.boolOr("untap", false))
        "clearDamage" -> Effect.ClearDamage(btOf(o.req("target")))
        "asPlayer" -> Effect.AsPlayer(playerRefOf(o.req("who")), effectOf(o.req("body")))
        "sendTo" -> Effect.SendTo(btOf(o.req("target")), o["to"]?.str()?.let { enumOf<HiddenZone>(it) } ?: HiddenZone.HAND)
        "gainControl" -> Effect.GainControl(btOf(o.req("target")), o["duration"]?.let { durationOf(it) } ?: Duration.Permanent)
        "declareAttackers" -> Effect.DeclareAttackers(
            filterOf(o.req("eligible")), o["staysReady"]?.let { filterOf(it) }, effectOf(o.req("then")),
        )
        "declareBlockers" -> Effect.DeclareBlockers(
            filterOf(o.req("eligible")), o["rules"]?.arr()?.map { blockRuleOf(it) }.orEmpty(), effectOf(o.req("then")),
        )
        "combatWindow" -> Effect.CombatWindow
        "attack" -> Effect.Attack(
            attacker = filterOf(o.req("attacker")),
            targets = filterOf(o.req("targets")),
            attacksPerTurn = o["attacksPerTurn"]?.let { intExprOf(it) } ?: lit(1),
            mustTarget = o["mustTarget"]?.let { filterOf(it) },
            redirect = o["redirect"]?.let { filterOf(it) },
            staysReady = o["staysReady"]?.let { filterOf(it) },
            then = effectOf(o.req("then")),
            reaches = o["reaches"]?.let { boolExprOf(it) } ?: BoolExpr.Const(true),
            reachesFace = o["reachesFace"]?.let { boolExprOf(it) } ?: BoolExpr.Const(true),
        )
        "strike" -> Effect.Strike(
            o.req("step").str(), intExprOf(o.req("amount")), o["faceAmount"]?.let { intExprOf(it) },
            o.boolOr("returnDamage", true), o["lethal"]?.let { filterOf(it) },
        )
        "clash" -> Effect.Clash(
            step = o.req("step").str(),
            attackStat = intExprOf(o.req("attackStat")),
            defendStat = intExprOf(o.req("defendStat")),
            faceAmount = o["faceAmount"]?.let { intExprOf(it) },
            returnDamage = o.boolOr("returnDamage", true),
            excessToController = o.boolOr("excessToController", false),
            onTie = o["onTie"]?.str()?.let { tieResultFromJson(it) } ?: TieResult.BOTH_DESTROYED,
            stances = o["stances"]?.arr()?.map { stanceOf(it) }.orEmpty(),
            pierces = o["pierces"]?.let { filterOf(it) },
        )
        "combatDamage" -> Effect.CombatDamage(
            step = o.req("step").str(),
            amount = intExprOf(o.req("amount")),
            acts = o["acts"]?.let { filterOf(it) },
            lethal = o["lethal"]?.let { filterOf(it) },
            tramples = o["tramples"]?.let { filterOf(it) },
            minBlockers = o["minBlockers"]?.let { intExprOf(it) } ?: lit(1),
            overflow = o.boolOr("overflow", false),
        )
        "freeAttacks" -> Effect.FreeAttacks(
            step = o.req("step").str(),
            actors = filterOf(o.req("actors")),
            body = gunOf(o.req("body")),
            guns = o["guns"]?.arr()?.map { gunOf(it) }.orEmpty(),
            guards = o["guards"]?.let { filterOf(it) },
            lethal = o["lethal"]?.let { filterOf(it) },
            window = o.boolOr("window", false),
            overflow = o.boolOr("overflow", false),
        )
        else -> error("unknown Effect op '$op'")
    }
}

// -- shared small encoders --------------------------------------------------

/** An object is its id; a variable is its NAME. Older formats
 *  spelled the default variables as negative ids -- still read. */
internal fun btJson(t: BoundTarget): String = when (val r = t.ref) {
    is Ref.Obj -> r.id.toString()
    is Ref.Var -> jstr(r.name)
}
internal fun btOf(j: Json): BoundTarget =
    if (j is Json.Str) BoundTarget(j.value)
    else j.int().let { id -> LEGACY_SENTINELS[id]?.let { BoundTarget(it) } ?: BoundTarget(id) }

/** `,"binds":name` unless it is the binder's default. */
private fun bindsJson(name: String, default: String) = if (name == default) "" else ",\"binds\":" + jstr(name)

private fun exprMapToJson(m: Map<String, IntExpr>): String =
    "{" + m.entries.joinToString(",") { """${jstr(it.key)}:${intExprToJson(it.value)}""" } + "}"

private fun exprMapOf(j: Json): Map<String, IntExpr> = j.obj().mapValues { intExprOf(it.value) }

private fun intMapToJson(m: Map<String, Int>): String =
    "{" + m.entries.joinToString(",") { "${jstr(it.key)}:${it.value}" } + "}"
private fun intMapOf(j: Json): Map<String, Int> = j.obj().mapValues { it.value.int() }

fun zoneRefToJson(z: ZoneRef): String = """{"def":${jstr(z.def)}${z.owner?.let { ""","owner":${jstr(it)}""" } ?: ""}}"""
internal fun zoneRefOf(j: Json): ZoneRef = j.obj().let { ZoneRef(it.req("def").str(), it["owner"]?.str()) }

fun durationToJson(d: Duration): String = when (d) {
    Duration.Permanent -> """{"kind":"permanent"}"""
    Duration.EndOfTurn -> """{"kind":"endOfTurn"}"""
    Duration.EndOfNextTurn -> """{"kind":"endOfNextTurn"}"""
    is Duration.While -> """{"kind":"while","cond":${boolExprToJson(d.cond)}}"""
}
internal fun durationOf(j: Json): Duration {
    val o = j.obj()
    return when (val k = o.req("kind").str()) {
        "permanent" -> Duration.Permanent
        "endOfTurn" -> Duration.EndOfTurn
        "endOfNextTurn" -> Duration.EndOfNextTurn
        "while" -> Duration.While(boolExprOf(o.req("cond")))
        else -> error("unknown Duration kind '$k'")
    }
}

fun charsToJson(c: Characteristics): String = buildString {
    append("""{"name":${jstr(c.name)},"types":[${c.types.joinToString(",") { jstr(it) }}]""")
    if (c.fields.isNotEmpty()) append(""","fields":${c.fields.entries.joinToString(",", "{", "}") { "${jstr(it.key)}:${it.value}" }}""")
    if (c.keywords.isNotEmpty()) append(""","keywords":[${c.keywords.joinToString(",") { jstr(it) }}]""")
    if (c.abilitiesRemoved) append(""","abilitiesRemoved":true""")
    if (c.granted.isNotEmpty()) append(""","granted":[${c.granted.joinToString(",") { activatedAbilityToJson(it) }}]""")
    append("}")
}
internal fun charsOf(j: Json): Characteristics {
    val o = j.obj()
    return Characteristics(
        name = o.req("name").str(),
        types = o.req("types").arr().map { it.str() }.toSet(),
        fields = o["fields"]?.obj()?.mapValues { it.value.int() } ?: emptyMap(),
        keywords = (o["keywords"]?.arr()?.map { it.str() } ?: emptyList()).toSet(),
        abilitiesRemoved = o.boolOr("abilitiesRemoved", false),
        granted = o["granted"]?.arr()?.map { activatedAbilityOf(it) } ?: emptyList(),
    )
}

// An exhaustive `when`, NOT an if/else pair: the previous two-way form would
// have silently serialised a third mode as "instances" and read it back wrong.
// A closed enum needs a total mapping or adding a case is a silent data bug.
fun shieldModeStr(m: ShieldMode): String = when (m) {
    ShieldMode.POINTS -> "points"
    ShieldMode.INSTANCES -> "instances"
    ShieldMode.REDUCTION -> "reduction"
}
// ...and a STRICT reverse mapping: a typo or a newer file's mode must not load
// silently as some other rule. An unknown name is a load error, like an
// unknown op.
internal fun shieldModeOf(s: String): ShieldMode = when (s) {
    "points" -> ShieldMode.POINTS
    "instances" -> ShieldMode.INSTANCES
    "reduction" -> ShieldMode.REDUCTION
    else -> error("unknown shield mode '$s'")
}

fun eventPatternToJson(p: EventPattern): String {
    fun types(t: Set<String>) = "[" + t.joinToString(",") { jstr(it) } + "]"
    fun who(w: PlayerRef?) = w?.let { ""","whose":${playerRefToJson(it)}""" } ?: ""
    return when (p) {
        is EventPattern.OnPhase -> """{"kind":"onPhase","phase":${jstr(p.phase)}${who(p.whose)}}"""
        EventPattern.AnyTurnBegan -> """{"kind":"anyTurnBegan"}"""
        is EventPattern.OnCombatStep -> """{"kind":"onCombatStep"""" + (p.step?.let { ""","step":${jstr(it)}""" } ?: "") + "${who(p.whose)}}"
        EventPattern.Never -> """{"kind":"never"}"""
        EventPattern.SelfEnters -> """{"kind":"selfEnters"}"""
        EventPattern.SelfLeaves -> """{"kind":"selfLeaves"}"""
        EventPattern.SelfAttacks -> """{"kind":"selfAttacks"}"""
        is EventPattern.Enters -> """{"kind":"enters","types":${types(p.types)}${who(p.whose)},"other":${p.other}}"""
        is EventPattern.Cast -> """{"kind":"cast","types":${types(p.types)}${who(p.whose)}}"""
        is EventPattern.CounterCrosses ->
            """{"kind":"counterCrosses","counter":${jstr(p.kind)},"k":${p.k},"down":${p.downward}}"""
        is EventPattern.SelfDealsDamage -> """{"kind":"selfDealsDamage","combatOnly":${p.combatOnly}}"""
        is EventPattern.Dies -> """{"kind":"dies","types":${types(p.types)}${who(p.whose)}""" +
            (p.filter?.let { ""","filter":${filterToJson(it)}""" } ?: "") + "}"
        EventPattern.MovesIntoThisZone -> """{"kind":"movesIntoThisZone"}"""
        is EventPattern.Damaged -> """{"kind":"damaged","filter":${filterToJson(p.filter)},"combatOnly":${p.combatOnly}""" +
            (p.step?.let { ""","step":${jstr(it)}""" } ?: "") + "}"
        is EventPattern.PlayerDamaged -> """{"kind":"playerDamaged"${who(p.whose)},"combatOnly":${p.combatOnly}}"""
    }
}
internal fun eventPatternOf(j: Json): EventPattern {
    val o = j.obj()
    fun types() = o.req("types").arr().map { it.str() }.toSet()
    fun who() = o["whose"]?.let { playerRefOf(it) }
    return when (val k = o.req("kind").str()) {
        "onPhase" -> EventPattern.OnPhase(o.req("phase").str(), who())
        "anyTurnBegan" -> EventPattern.AnyTurnBegan
        "onCombatStep" -> EventPattern.OnCombatStep(o["step"]?.str(), who())
        "never" -> EventPattern.Never
        "selfEnters" -> EventPattern.SelfEnters
        "selfLeaves" -> EventPattern.SelfLeaves
        "selfAttacks" -> EventPattern.SelfAttacks
        "enters" -> EventPattern.Enters(types(), who(), o.boolOr("other", false))
        // `whose` absent means ANYONE's here, not the constructor's "you".
        "cast" -> EventPattern.Cast(types(), who())
        "counterCrosses" -> EventPattern.CounterCrosses(o.req("counter").str(), o.req("k").int(), o.boolOr("down", false))
        "selfDealsDamage" -> EventPattern.SelfDealsDamage(o.boolOr("combatOnly", false))
        "dies" -> EventPattern.Dies(types(), who(), o["filter"]?.let { filterOf(it) })
        "movesIntoThisZone" -> EventPattern.MovesIntoThisZone
        "damaged" -> EventPattern.Damaged(filterOf(o.req("filter")), o.boolOr("combatOnly", false), o["step"]?.str())
        "playerDamaged" -> EventPattern.PlayerDamaged(who(), o.boolOr("combatOnly", false))
        else -> error("unknown EventPattern kind '$k'")
    }
}

fun charOpToJson(c: CharOp): String = when (c) {
    is CharOp.PlusPT -> """{"op":"plusPT","power":${intExprToJson(c.power)},"toughness":${intExprToJson(c.toughness)}}"""
    is CharOp.PlusField -> """{"op":"plusField","field":${jstr(c.field)},"amount":${intExprToJson(c.amount)}}"""
    is CharOp.SetField -> """{"op":"setField","field":${jstr(c.field)},"value":${intExprToJson(c.value)}}"""
    is CharOp.SetPT -> """{"op":"setPT","power":${intExprToJson(c.power)},"toughness":${intExprToJson(c.toughness)}}"""
    is CharOp.GrantKeyword -> """{"op":"grantKeyword","keyword":${jstr(c.keyword)}}"""
    is CharOp.AddType -> """{"op":"addType","type":${jstr(c.type)}}"""
    is CharOp.SetTypes -> """{"op":"setTypes","types":[${c.types.joinToString(",") { jstr(it) }}]}"""
    CharOp.RemoveAbilities -> """{"op":"removeAbilities"}"""
    is CharOp.GrantAbility -> """{"op":"grantAbility","ability":${activatedAbilityToJson(c.ability)}}"""
    is CharOp.Bands -> """{"op":"bands","counter":${jstr(c.counter)},"fieldA":${jstr(c.fieldA)},"fieldB":${jstr(c.fieldB)},"steps":[${
        c.steps.joinToString(",") { """{"at":${it.at},"power":${intExprToJson(it.power)},"toughness":${intExprToJson(it.toughness)}}""" }
    }]}"""
}
internal fun charOpOf(j: Json): CharOp {
    val o = j.obj()
    return when (val op = o.req("op").str()) {
        "plusPT" -> CharOp.PlusPT(intExprOf(o.req("power")), intExprOf(o.req("toughness")))
        "plusField" -> CharOp.PlusField(o.req("field").str(), intExprOf(o.req("amount")))
        "setField" -> CharOp.SetField(o.req("field").str(), intExprOf(o.req("value")))
        "setPT" -> CharOp.SetPT(intExprOf(o.req("power")), intExprOf(o.req("toughness")))
        "grantKeyword" -> CharOp.GrantKeyword(o.req("keyword").str())
        "addType" -> CharOp.AddType(o.req("type").str())
        "setTypes" -> CharOp.SetTypes(o.req("types").arr().map { it.str() }.toSet())
        "removeAbilities" -> CharOp.RemoveAbilities
        "grantAbility" -> CharOp.GrantAbility(activatedAbilityOf(o.req("ability")))
        "bands" -> CharOp.Bands(
            o.req("counter").str(),
            o.req("steps").arr().map {
                val s = it.obj()
                CharOp.Bands.Band(s.req("at").int(), intExprOf(s.req("power")), intExprOf(s.req("toughness")))
            },
            o.strOr("fieldA", "power"),
            o.strOr("fieldB", "toughness"),
        )
        else -> error("unknown CharOp op '$op'")
    }
}

// -- CardFilter -------------------------------------------------------------

fun cardFilterToJson(f: CardFilter): String = buildString {
    append("{")
    val parts = mutableListOf<String>()
    if (f.types.isNotEmpty()) parts += """"types":[${f.types.joinToString(",") { jstr(it) }}]"""
    f.nameIs?.let { parts += """"nameIs":${jstr(it)}""" }
    f.nameContains?.let { parts += """"nameContains":${jstr(it)}""" }
    f.maxManaValue?.let { parts += """"maxMv":$it""" }
    f.minManaValue?.let { parts += """"minMv":$it""" }
    append(parts.joinToString(","))
    append("}")
}

internal fun cardFilterOf(j: Json): CardFilter = j.obj().let { o ->
    CardFilter(
        types = o["types"]?.arr()?.map { it.str() }?.toSet() ?: emptySet(),
        nameIs = o["nameIs"]?.str(),
        nameContains = o["nameContains"]?.str(),
        maxManaValue = o["maxMv"]?.int(),
        minManaValue = o["minMv"]?.int(),
    )
}

/** "you" / "opponent" as bare strings -- the shorthand every save already
 *  holds (they were `Who`) -- and every other reference as an object. */
fun playerRefToJson(r: PlayerRef): String = when (r) {
    PlayerRef.You -> "\"you\""
    PlayerRef.Opponent -> "\"opponent\""
    PlayerRef.EachOpponent -> """{"k":"eachOpponent"}"""
    PlayerRef.Active -> """{"k":"active"}"""
    PlayerRef.Chosen -> """{"k":"chosen"}"""
    PlayerRef.Defending -> """{"k":"defending"}"""
    is PlayerRef.Seat -> """{"k":"seat","id":${jstr(r.id)}}"""
    is PlayerRef.ControllerOf -> """{"k":"controllerOf","target":${btJson(r.target)}}"""
    is PlayerRef.OwnerOf -> """{"k":"ownerOf","target":${btJson(r.target)}}"""
}
internal fun playerRefOf(j: Json): PlayerRef {
    if (j is Json.Str) return when (j.value) {
        "you" -> PlayerRef.You
        "opponent" -> PlayerRef.Opponent
        else -> error("unknown player '${j.value}'")
    }
    val o = j.obj()
    return when (val k = o.req("k").str()) {
        "eachOpponent" -> PlayerRef.EachOpponent
        "active" -> PlayerRef.Active
        "chosen" -> PlayerRef.Chosen
        "defending" -> PlayerRef.Defending
        "seat" -> PlayerRef.Seat(o.req("id").str())
        "controllerOf" -> PlayerRef.ControllerOf(btOf(o.req("target")))
        "ownerOf" -> PlayerRef.OwnerOf(btOf(o.req("target")))
        else -> error("unknown PlayerRef '$k'")
    }
}
