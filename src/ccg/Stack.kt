package ccg

// ---------------------------------------------------------------------------
// What sits on the stack: DATA the engine moves around, not the engine.
// ---------------------------------------------------------------------------

sealed interface StackObject {
    val id: ObjectId
    val controller: PlayerId
    val effect: Effect
    val source: ObjectId?
}

data class SpellOnStack(
    override val id: ObjectId,
    override val controller: PlayerId,
    override val effect: Effect,
    override val source: ObjectId? = null,
    /** The card's name, for the log. "" for an ad-hoc test effect. */
    val label: String = "",
    /** The physical card this spell was cast from, if any -- removed from its
     *  zone at cast time and put into `goesTo` once the spell resolves. Null
     *  for an effect conjured with no card behind it. */
    val card: CardRef? = null,
    val goesTo: HiddenZone = HiddenZone.GRAVEYARD,
    /** The spell's types, so "counter target Instant" can tell. */
    val types: Set<String> = emptySet(),
) : StackObject

/** A permanent SPELL waiting on the stack (Ox, Sunspire, …). Previously
 *  `PlayPermanent` put a permanent straight onto the battlefield, so an
 *  opponent never got a window to respond TO it -- the stack was always empty
 *  for permanents. Its `id` is the id the permanent will have once it
 *  resolves, allocated up front, so nothing downstream sees a different one.
 *  `Land`-like types (`TypeDef.usesStack = false`) skip this and enter at
 *  once, as a special action. */
data class PermanentOnStack(
    override val id: ObjectId,
    override val controller: PlayerId,
    val card: CardDefinition,
    val face: Int,
    val zone: ZoneRef,
    val label: String = "",
) : StackObject {
    override val effect: Effect get() = Effect.NoOp
    override val source: ObjectId? get() = null
}

data class TriggeredAbilityOnStack(
    override val id: ObjectId,
    override val controller: PlayerId,
    override val effect: Effect,
    val sourceEvent: GameEvent,
    override val source: ObjectId? = null,
) : StackObject
