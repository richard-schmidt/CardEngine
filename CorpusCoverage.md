# Corpus coverage: the 58 frozen cards vs. what the engine does

A reference set of 58 cards covering the mechanics of the games the engine
targets. Each card is mapped by *capability*, not name (several corpus names are
reused in the test fixtures for different text). **Green** only if its core mechanic is
exercised end to end by a `test/CoreTest.kt` check.

## Tally (needs a re-audit)

| | count |
|---|---|
| **green**: core mechanic tested end to end | **43 / 58** (74%) |
| **partial**: the primitive works; the card's full text has a gap | 13 / 58 |
| **gap**: a load-bearing primitive is missing | 3 / 58 |

The tally was not recounted after later vocabulary landed (stack and board verbs,
reads and `where` filters, `CombatConfig` topology, named variables), so several
items below may now be closed. Re-audit before quoting a number.

## Measured: what the conformance corpus runs

The corpus (`corpus/*.json`) is the 46 recorded games a port must reproduce.
This section is generated from it by a check in `test/ArchReviewTest.kt`, so it is always current; regenerate it with
`CGE_WRITE_CORPUS=1` in the same commit as the corpus. A verb listed as never run
is one a port can get wrong without the corpus noticing.

<!-- measured:begin -->
| game | combat | cases | questions | effect cases run | zones used |
|---|---|---|---|---|---|
| Core | its own program | 18 | 2588 | 15 | exile, flagships, graveyard, hand, in play: 1B, in play: 1F, in play: 2B, in play: 2F, in play: 3B, in play: 3F, in play: battlefield, library, stack |
| Crossroads Arena (Hearthstone-like) | hearthstone | 2 | 52 | 10 | graveyard, hand, in play: battlefield, library |
| Duel Arena (Yu-Gi-Oh-like) | yugioh | 2 | 76 | 10 | graveyard, hand, in play: battlefield, library |
| EPR Skirmish | fastSlowLanes | 18 | 1010 | 18 | exile, flagships, graveyard, hand, in play: battlefield, in play: lane-1, in play: lane-2, in play: lane-3, library, stack |
| Open Seas (One Piece-like) | onePiece | 2 | 62 | 10 | graveyard, hand, in play: battlefield, library |
| Sparkfield (MTG-like) | mtg | 2 | 38 | 13 | graveyard, hand, in play: battlefield, library, stack |
| War Table (SW: Unlimited-like) | mtg | 2 | 38 | 13 | graveyard, hand, in play: battlefield, library |

**26 of 47 core `Effect` cases run in at least one recorded game.**
Never run: `AsPlayer`, `Attack`, `Choose`, `ChooseMany`, `ChooseMode`, `Clash`, `CopyOf`, `CounterSpell`, `CreateEmblem`, `ForEachPlayer`, `GainControl`, `LookAtTop`, `MovePermanent`, `MoveTop`, `Proceed`, `SearchZone`, `SendTo`, `SetCombatMode`, `Shuffle`, `Strike`, `Transform`.
Surface forms, lowered before the engine sees them, so never run as themselves: `DamageOpponent`, `ReturnFromDiscard`.

| effect | runs | games |
|---|---|---|
| `AddCounter` | 459 | core |
| `AddMana` | 793 | core, epr-skirmish |
| `ApplyModifier` | 31 | epr-skirmish |
| `Attach` | 18 | epr-skirmish |
| `ClearDamage` | 5729 | core, crossroads-arena-hearthstone-like, duel-arena-yu-gi-oh-like, epr-skirmish, open-seas-one-piece-like, sparkfield-mtg-like, war-table-sw-unlimited-like |
| `CombatDamage` | 26 | sparkfield-mtg-like, war-table-sw-unlimited-like |
| `CombatWindow` | 122 | crossroads-arena-hearthstone-like, duel-arena-yu-gi-oh-like, open-seas-one-piece-like, sparkfield-mtg-like, war-table-sw-unlimited-like |
| `CreateToken` | 43 | core, epr-skirmish |
| `DealDamage` | 534 | core, crossroads-arena-hearthstone-like, duel-arena-yu-gi-oh-like, epr-skirmish, open-seas-one-piece-like, sparkfield-mtg-like, war-table-sw-unlimited-like |
| `DeclareAttackers` | 13 | sparkfield-mtg-like, war-table-sw-unlimited-like |
| `DeclareBlockers` | 13 | sparkfield-mtg-like, war-table-sw-unlimited-like |
| `Delayed` | 3 | core |
| `Destroy` | 21 | core, epr-skirmish |
| `Discard` | 977 | core, crossroads-arena-hearthstone-like, duel-arena-yu-gi-oh-like, epr-skirmish, open-seas-one-piece-like, sparkfield-mtg-like, war-table-sw-unlimited-like |
| `Draw` | 775 | core, crossroads-arena-hearthstone-like, duel-arena-yu-gi-oh-like, epr-skirmish, open-seas-one-piece-like, sparkfield-mtg-like, war-table-sw-unlimited-like |
| `DrawThenDiscard` | 78 | epr-skirmish |
| `ForEach` | 1642 | core, crossroads-arena-hearthstone-like, duel-arena-yu-gi-oh-like, epr-skirmish, open-seas-one-piece-like, sparkfield-mtg-like, war-table-sw-unlimited-like |
| `FreeAttacks` | 383 | core, epr-skirmish |
| `GainLife` | 32 | crossroads-arena-hearthstone-like, epr-skirmish |
| `If` | 117 | crossroads-arena-hearthstone-like, duel-arena-yu-gi-oh-like, epr-skirmish, open-seas-one-piece-like, sparkfield-mtg-like, war-table-sw-unlimited-like |
| `NoOp` | 8 | duel-arena-yu-gi-oh-like, open-seas-one-piece-like, sparkfield-mtg-like, war-table-sw-unlimited-like |
| `PreventDamage` | 10 | core, epr-skirmish |
| `RemoveCounter` | 99 | core |
| `Sacrifice` | 63 | epr-skirmish |
| `Sequence` | 2111 | core, crossroads-arena-hearthstone-like, duel-arena-yu-gi-oh-like, epr-skirmish, open-seas-one-piece-like, sparkfield-mtg-like, war-table-sw-unlimited-like |
| `Tap` | 2869 | core, crossroads-arena-hearthstone-like, duel-arena-yu-gi-oh-like, epr-skirmish, open-seas-one-piece-like, sparkfield-mtg-like, war-table-sw-unlimited-like |
<!-- measured:end -->

## Still missing at the last audit, one capability each

| what is missing | cards |
|---|---|
| a **granted** cast permission (one card letting you cast another) | #21 |
| a counter predicate on `PermFilter` | #18 |
| ETB-modification replacements over OTHER permanents | #39 |
| runtime combat-model reconfiguration | #48 |
| `CombatDamageDealt` (distinct from `DamageDealt`) | #44 |
| downward counter threshold | #16 |
| player-targeted prevention shield | #36 |
| a self-ending static / turn-editing `RuleMod` | #37 |
| a bundle-declared hidden zone + face-down exile | #57 |

## Card-by-card

### Anchors (1 to 6), all green
1 Stoneback Ox ✅ · 2 Emberbolt ✅ · 3 Scout Rider ✅ · 4 Warded Sentinel ✅ (card-authored `SanctuaryField`) · 5 Vinewrap ✅ · 6 Field Marshal ✅

### Expression language (7 to 12)
- 7 Rally the Ranks: ✅
- 8 Voltaic Surge: ✅ (X at cast)
- 9 Overclock: ✅ `ChooseMany(upTo = true, divide = X)` + `IntExpr.Share`
- 10 Tidal Toll: ⚠️ intervening-if tested (Warcry/VanguardScout); this exact upkeep+handsize card not built
- 11 Moonlit Stalker: ⚠️ transform + phase trigger + `HandSize==0` all expressible; transform-via-trigger untested
- 12 Threshold Ward: ✅ conditional layer (RallyingStandard) + `RuleMod.CantAttack(filter, defender = Who.You)` wired into `runDeclaredCombat` / `runFreeCombat` / `resolveIndividualAttack`

### Counters (13 to 19)
- 13 Thornling Brood: ✅
- 14 Wither Priest: ✅
- 15 Corrosive Lash: ✅ −1/−1 + annihilation ✅ and "if it already has a +1/+1 counter" via `IntExpr.TargetCounter`
- 16 Charge Coil: ⚠️ typed counter ✅, remove-as-cost ✅, AddMana ✅; **downward threshold** ("last counter leaves → sac") missing
- 17 Adept: ✅ level bands are now `CharOp.Bands` (declarative, round-trips, in the Creator's CharOp editor). The `makeContinuousEffect` hatch that made this card non-authorable is **deleted**.
- 18 Proliferating Font: ⚠️ choose-any-number ✅ (`ChooseMany(upTo = true)`); still needs a **counter predicate on `PermFilter`** ("each permanent with a counter on it")
- 19 Tallywarden: ⚠️ `CountCounters` IntExpr ✅ & round-trips; self-buff-from-count static untested

### Type model (20 to 25)
- 20 Sunspire: ✅ loyalty field ✅, diesWhen ✅, attackable ✅ (via `TypeDef.damageCounter`), field-cost abilities ✅, `createToken` (+1) ✅, `createEmblem` (−8) ✅
- 21 Siege of the Ninth Gate: ⚠️ defense field ✅, combat-damage-to-defense ✅, cast-from-exile now expressible (`CardSource(EXILE, id)`); what is missing is *granting* that permission to a card this one exiled; a per-instance permission, not a zone
- 22 The Bloomcycle: ✅ Saga mechanic; "create Beast" closed, "search library" closed by the hidden-zones slice (`Effect.SearchZone`)
- 23 Runegate Pillar: ✅ + tap-for-mana ✅
- 24 Pilgrim // Shrine: ✅
- 25 Grizzled Outrider: ✅

### Events (26 to 33)
- 26 Archive Keeper: ✅; "then discard" (loot) closed by `Effect.Discard`
- 27 Chime of Endings: ✅ (dies-anywhere); "scry" payload missing
- 28 Deathbloom Herald: ✅ LTB≠Dies + token payload (`CreateToken`)
- 29 Toll Collector: ✅ (opponent-scoped ETB)
- 30 Hive Overseer: ✅ (end-step + ForEach)
- 31 Delayed Blast: ✅
- 32 Echo of the Vow: ❌ spell copy. `Effect.CopyOf` copies a PERMANENT; copying a spell needs a stack-object verb; now *expressible* for the first time, since the stack is in `GameState`, but the verb does not exist
- 33 Wrath of the Commons: ✅ (APNAP)

### Durations (34 to 37)
- 34 Last Stand: ⚠️ (name reused) +X/+X EOT + conditional 2nd clause; expressible, untested as this
- 35 Battle Fury: ⚠️ (name reused) team EOT pump ✅; "haste" keyword not wired
- 36 Bulwark Chant: ❌ (name reused) **player-targeted** prevention shield
- 37 Slow the Sands: ⚠️ (name reused) "your opponent skips their draw step" ✅ via `RuleMod.Cant("draw", Who.Opponent)`, honoured in `drawCards`. Remaining: a **self-ending** static (a static that removes itself)

### Static vocabulary (38 to 43)
- 38 Skyward Doctrine: ✅; conditional 2nd clause expressible
- 39 Leyline Tithe: ⚠️ (name reused) cost reduction ✅; "opp creatures enter tapped" (ETB-mod replacement) missing
- 40 Null Field: ✅ (name reused) "can't gain life" ✅ and "creatures can't attack you" ✅; `Cant("cast")` also honoured now
- 41 Primal Mimicry: ✅ type-replace + SetPT + RemoveAbilities layered; mass version is filter-only
- 42 Sculptor's Whim: ✅ (name reused) add-type ✅ + activated-ability injection via `CharOp.GrantAbility`
- 43 Grantcaller's Creed: ✅ (name reused) `StaticSpec(creatures().yours(), [CharOp.GrantAbility(...)])`

### Combat (44 to 50)
- 44 Ironhide Vanguard: ⚠️ first strike ✅, trample ✅; **`CombatDamageDealt` event** missing
- 45 Duelist's Instinct: ✅
- 46 Counterstroke: ✅ cast-timing ✅ (`legality()`), attack restrictions ✅, and the fight-like dynamic value via `IntExpr.TargetField`
- 47 Twin Fangs: ✅ + deathtouch
- 48 Shift the Tempo: ❌ runtime combat-model reconfiguration
- 49 Fogbank Warden: ⚠️ defender ✅; multi-block by one blocker missing
- 50 Venomspine Lurker: ✅ deathtouch + menace

### Creation · choices · costs (51 to 56)
- 51 Twinflame Ritual: ✅ `CopyOf` + `CreateToken` (both tested)
- 52 Sunspire's Verdict: ✅ `ChooseMode` with `pick` > 1 = entwine (tested)
- 53 Grave Bargain: ✅ additional cost = sacrifice + `ReturnFromDiscard`
- 54 Embercaller's Rite: ✅ base spell + *alternative cost* (`Cost.alternatives`) + **cast-from-the-graveyard** (`CardDefinition.recast`, which also says where the card goes afterwards; EXILE, so it is not recastable forever)
- 55 Rite of Passage: ✅ `ReturnFromDiscard(toBattlefield = true)` via the `Rules.cards` registry (tested)
- 56 Bloodpact Sentry: ✅ `Cost(mana, alternatives = [Cost(payLife = 3)])`; the engine tries every option and asks the controller when more than one is payable

### Stretch: zones and positional (57 to 58)
- 57 Cartographer's Table: ⚠️ move-between-hidden-zones ✅ (`HiddenZone` + `SearchZone`/`MoveTop`/`LookAtTop` over library/hand/graveyard/exile); still needs a **named per-player zone** ("the map") and face-down exile
- 58 The Iron Line: ✅ **GREEN.** The laned board and the opposing-lane query both exist and are tested: N per-player `PlayZoneDef` lanes with `maxOccupants`, `GameState.laneOpposed`, and `CombatConfig.laneLockedPlayerTargets` (a ship may only hit the station while its OWN lane is unopposed). Test: "laneOpposed treats each of N independently-named lanes on its own".
