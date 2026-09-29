# CardEngine user guide

CardEngine is an Android app for designing trading card games and playing them.
A game is one file: its rules, its card sets and its decks. You build it in the
app's editors, play it at a table against the bot or with two seats in your
hands, and change it between games until it plays the way you want.

This guide follows the app from the outside: where things are, how to build a
game from nothing, and how to test it at the table.

- [1. The app at a glance](#1-the-app-at-a-glance)
- [2. Getting around](#2-getting-around)
- [3. Creating a game, step by step](#3-creating-a-game-step-by-step)
- [4. Playtesting and playing](#4-playtesting-and-playing)
- [5. Glossary](#5-glossary)

---

## 1. The app at a glance

The app has one home, the **Shelf**, and one workspace per game. Inside a game,
a rail of five modules holds everything you can edit, and **Play** leads to the
**table**, where the game runs.

```
Shelf  (home; Back here closes the app)
│   a carousel of game boxes, ending on "new game"
│   IN PROGRESS: games left on the table, one tap back to them
│
└── a game's workspace            ⌕ jump search in the top bar
    │   rail: Overview · Rules · Cards · Decks · Play
    │   (at the bottom in portrait, at the left in landscape)
    │
    ├── Overview     name, colour, cover, readiness, issues, export, history
    ├── Rules        section list › one section's editor
    │                  The game · Turn structure · Resources · Counters ·
    │                  Types & fields · Play zones · Combat · Deck construction
    ├── Cards        Cards | Pool
    │                  sets and their cards › the card editor
    ├── Decks        deck list › one deck: entries, slots, legality
    └── Play         Game | Sandbox  (setup)
                       › the table
                           top band: ‹ leave · turn readout · Play/Debug lens ·
                                     ✦ binder (Debug) · ⋯ tools
                           tools drawer: DEBUG · VIEW · GAME
```

**Back** always goes one step towards the Shelf:

| You are on | Back goes to |
|---|---|
| The table, with a game running | "Leave the table?", then the module you came from. The game stays under IN PROGRESS. |
| Play's setup | The module you came from |
| A card, a deck or a Rules section | Its list |
| A module's first screen | Overview |
| Overview | The Shelf (asks first if you have unsaved changes) |
| The Shelf | Out of the app |

The rail hides while a game is on the table, so the board gets the whole screen.

Tapping another module in the rail moves **sideways**: it replaces where you are
and keeps the game open. The jump search and links from issues move **forward**,
so Back returns to where you came from.

## 2. Getting around

### 2.1 The Shelf

The Shelf is a carousel of game boxes: swipe to move between them, or tap a dot
under the carousel. Each box shows the game's cover card (or its name, set large
and faint) on its colour, with its counts (cards, sets, decks, issues) and a tag:
**built-in**, **sample** or **yours**.

Tap a box to bring it to the centre. On the centred box:

- **edit** opens the game's workspace at Overview.
- **▶ play** opens the game at Play's setup.
- **delete** removes the game, its art and its saved versions, after asking. You
  cannot delete the last game.
- **● unsaved edits** means the open game has changes you have not saved yet.
- **⚠ this build has newer content: update** appears on a built-in game when the
  app ships a newer version of it.

The last box is **new game**:

- Tap **+ new game** for an empty game. It opens straight at Rules.
- **import bundle** reads a `.ceg.zip` (rules, cards and card art).
- **import JSON** reads a `.json` (rules and cards, no art).

An import always becomes a new game with its own identity; it never overwrites
one you have.

The **built-in** menu at the top right adds the sample games you do not have yet,
and adds, updates or refreshes each built-in game. Refreshing a built-in game you
have edited asks first; your edited copy is kept as a version, so you can get it
back from its history.

**IN PROGRESS**, under the carousel, lists games left on the table (up to
three), with who sits at the table, how many moves were made, and whether the
game was **debugged**. Tap **resume ›** to go straight back to the table.

### 2.2 A game's workspace

Opening a game gives the app the game's colour and shows the **rail** of
modules: at the bottom of the screen in portrait, down the left side in
landscape. In landscape, Cards and Rules show their list and the open item side
by side.

The top bar shows where you are, a dot when there are unsaved changes, **Save**,
and **⌕** (the jump search). **‹** and the system Back gesture go back one step.

| Module | What it is for |
|---|---|
| **Overview** | The game's front page: name, colour, cover, export, version history, what is wrong with it, and a way to play it. |
| **Rules** | Everything that shapes play, in eight sections. |
| **Cards** | Sets and their cards, each card's editor, and the **Pool** view of the whole card pool. |
| **Decks** | Each deck's cards and slot cards, and whether it is legal. |
| **Play** | Start a game or a sandbox, resume the game on the table, open saved scenarios. |

**Overview** in detail:

- The **name** is editable in place.
- **colour**: **auto** (picked from the game's identity, so it never changes on
  its own) or one of six swatches. The game wears it on the Shelf and inside its
  workspace.
- **cover**: **auto** (the first card with a picture, else the first card) or a
  card you pick. Its picture goes on the game's box on the Shelf.
- The **readiness row** counts cards, sets and decks, and shows **ready** or the
  number of issues.
- The **issues** list says what is wrong. An issue that names a card is a link
  (**→**): tap it to open that card.
- **▶ Play** goes to Play.

### 2.3 Finding things: the jump search

**⌕** searches the open game for a card (by any face's name; types and keywords
rank lower), a Rules section (by title, or by what the game puts in it: a phase
name finds Turn structure, a counter finds Counters), a deck, or an issue. Pick
a result to jump to it. An issue about a card opens that card. Back returns to
where you searched from; Back with the search open just closes it.

### 2.4 Saving, history and recovery

Edits change a **working copy** of the game. Nothing is written until you tap
**Save**, and leaving a game with unsaved changes asks whether to save or
discard them. You can play the working copy without saving: the table always
runs the game as it currently stands in the editors.

- **Version history.** Every Save keeps the previous content as a version (the
  last 20). Overview › **history** lists them by date and name; picking one loads
  it into the working copy, as an ordinary edit you can save or discard.
- **Recovery.** If the app closes with unsaved changes, it offers to restore
  them the next time you open that game.
- **Games on the table** are kept on every move, so they survive the app
  closing too.

### 2.5 Sharing a game

Overview › **export** writes the game to a file you choose:

- **bundle .ceg.zip**: rules, cards and card art. Use this to move a game to
  another device.
- **JSON**: rules and cards only. Cards with art show a placeholder glyph where
  the picture would be.

To load a game, use **import** on the Shelf's last box.

## 3. Creating a game, step by step

A game has three parts, and the modules follow them:

- **Rules**: the shape of play (turns, resources, counters, card types, zones,
  combat, deck construction).
- **Cards**: grouped into **sets**. A card has one or more **faces**; each face
  has a name, types, stats and behaviour.
- **Decks**: which cards each seat starts with.

Nothing here is enforced while you edit: mistakes are **reported** as issues,
and you fix them in any order. A game with **errors** will not start at the
table; **warnings** never block anything.

The walkthrough below builds a small two-player game from nothing. Every step
names the screen and the control to use.

### Step 1: Create the game

1. On the Shelf, swipe to the last box and tap **+ new game**. The game opens
   at Rules, named "New Game".
2. Go to Overview and tap the name to rename it. Pick a **colour** if you like.
3. Tap **Save**. From now on, save whenever you want a checkpoint: each Save is
   kept in the game's history.

### Step 2: The shape of the game (Rules › The game)

The summary line under **The game** reads like `2p · 7 cards · draw 1`.

- **players**, **opening hand** and **cards drawn per turn**.
- **discard down to a hand limit**: tick it and set the **hand limit**.
- **the player going first skips their first draw**.
- **resources that survive**: by default a resource pool empties at every phase
  boundary. **the pool lasts the whole turn** keeps it until the turn ends; a
  **store** banks some of it between turns, up to a cap.
- **deployment**: **a permanent cannot attack the turn it arrives** (summoning
  sickness).
- **mulligan**: how many redraws are allowed, and whether each one puts a card
  back (London mulligan).

Anything questionable here is listed under the section as a warning.

### Step 3: Time and economy (Rules › Turn structure, Resources)

**Turn structure** is the list of phases a turn goes through. Reorder with ▲▼.
Each phase can:

- run an **on enter** effect for each player it acts for (untap, draw, clean up,
  or anything you write),
- open a **priority window** where players may act,
- be a **sorcery-speed window**, where slow cards may be played,
- **run combat**.

At the top, choose how turns work:

- **one turn each**: the active player goes through every phase; the other may
  only respond at instant speed. Magic works this way.
- **shared round**: both players are in one round. The phases run once for
  everyone, and in a sorcery-speed phase the two sides alternate single actions
  until both pass. The **initiative** passes at the end of the round.

**↺ reset to MTG** puts back Magic's phases.

**Resources** says how players get what pays costs:

- **gain per turn (ramp)**: a fixed amount every turn, growing to a cap, or
- **play a resource card per turn**: cards of the resource types you name make
  resources, a limited number per turn.

### Step 4: What players and cards track (Rules › Counters)

**Player counters** are numbers every player has. Life is the first of
them. For each one, set:

- its starting value,
- **reaching 0 loses the game**, if it should,
- a floor (**never below**) or a ceiling (**never above**).

**damage to a player removes** picks which counter damage to a player takes
away. Choose **nothing** for a game where players take no damage.

**Counters on cards** are kinds of counter that permanents can carry. `+1/+1`
and `-1/-1` are built in and cancel each other. Declare any other kind (shield,
charge…) here; a card that names an undeclared kind is reported. A kind can
**cancel** another: a card carrying both loses one of each. If your cards
already use counter kinds, **declare them now** fills the list for you.

### Step 5: Card types (Rules › Types & fields)

The built-in types (Creature, Instant, Sorcery, Enchantment, Artifact, Land,
Planeswalker, Saga, Battle) are always available. Add your own here, for example
"Ship" or "Station". For each type:

- **fields**: the stats cards of this type carry (power, toughness, hull,
  range…). Every card of the type gets a stepper for each.
- what kind of card it is: **is a spell** (goes to discard when it resolves),
  **can be declared an attacker**, **may be played at any time** (instant
  speed), **uses the stack** (opponents get a window to respond).
- **enters zone**: where it goes when played. Add more zones under **may ALSO
  be played into any of** to let the player choose (for example, one of three
  lanes).
- **dying and damage**: how a permanent of this type is lost. Damage is marked
  normally, or **damage removes** a counter (a hull, loyalty…); **dies when** a
  condition holds; **losing this loses the game**.
- **graphical structure**: **give this type its own card layout** to choose
  which stats the stat box shows, a counter track, whether rules text shows, an accent
  colour. The sample card shows the result.

### Step 6: The board (Rules › Play zones)

`battlefield` always exists. Add named zones for arenas, lanes or planets:

- **limits how many fit**: the most cards the zone holds (a lane usually holds one or two).
- **acts only in some combat steps**: for games where position decides when a
  card fights.
- **off-board pools**: a named zone that cards are played *from*, beside the
  hand (a pool of flagships, a command zone). **both players can see it** makes
  it public.

### Step 7: Combat (Rules › Combat)

Start from a preset; each one says in a line how it plays:

| Preset | Plays like |
|---|---|
| **mtg** | Declared attackers and blockers, first strike then regular damage. |
| **fastSlow** | Two simultaneous damage steps (fast, slow), free targeting, no blockers. |
| **hearthstone** | One attack at a time; taunt; divine-shield-style prevention. |
| **onePiece** | One attack at a time; only rested targets; a blocker keyword. |
| **yugioh** | One attack at a time; comparing stats decides; attack and defense positions. |
| lane and front/back presets | Variants where lanes, lines and range decide who can hit whom. |

To go further, **make it this game's own** turns the preset into its combat
**program**, which you edit with the same sentence editor as card effects: what
a combat phase runs, and, for one-attack-at-a-time games, what one attack does.
**↺ back to a preset** undoes that.

### Step 8: Cards (Cards)

Cards lives in **sets**. The first set exists already; **＋ set** adds another,
and **set name** renames it. The set's cards show as tiles; **＋ card** adds one
and opens it. Tap any tile to edit it.

The **card editor**, top to bottom:

1. **The preview**: the card as it will look, in its type's layout. Tap the art
   box to choose a picture (see *Card art* below).
2. **compiled rulebox**: what the card does as the engine will run it, one line
   per behaviour. Compare it with your rules text: if they disagree, one of them
   is wrong. **This card compiles to no behaviour at all** means the card does
   nothing yet.
3. **✦ Try it on the table**: puts this card, as it is now, into your hand on a
   table (see [4.5](#45-the-sandbox)).
4. **Identity**: **name**, **types** (tap to toggle), **rules text**, and
   **＋ face** for double-faced cards. Two card-level conditions:
   - **only playable if**: the card cannot be played unless something is true
     (for example, an attachment that needs a host);
   - **dies when**: an extra loss condition for this card only.
5. **Body: fields & keywords**: a stepper for each stat the card's types
   declare, **＋ field** for a stat just this card has, and its keywords.
6. **Cost**: generic and typed resources, **{X}**, **tap it ({T})**, sacrifice,
   removing counters, paying from a permanent's counters, an **additional cost**
   (an effect, such as "discard a card"), and **alternative ways to pay** (the
   engine asks which one when more than one is payable).
7. **behaviour › ＋ add**: the blocks a card may have. Each appears once added:

   | Block | What it is |
   |---|---|
   | **Cast effect** | What the card does when it resolves (spells). |
   | **Triggers** | "When this enters…", "at the start of your phase…", "whenever a creature dies…", "whenever this deals damage…", and more. |
   | **Static abilities** | Continuous effects while the card is in play: modify stats, grant keywords or abilities, "your opponents can't…", cost changes. |
   | **Activated abilities** | Pay a cost, get an effect: "{T}: add 1", "once per turn", whether it uses the stack. This is how a card becomes a resource source. |
   | **Enters-with counters** | Counters the permanent arrives with, a fixed number or computed. |
   | **Cast from another zone** | A second way to cast the card, from a zone other than the hand (flashback), and where it goes afterwards. |
   | **Replacement effects** | "If X would happen, do Y instead": destroy this instead, prevent it, exile it instead, spend a counter instead. |

   Delete a whole block with its **×**. Issues found in a block are shown at its
   top, and the block's title gets a ⚠.

At the bottom, **compiles to** shows the card's built name, types, stats and
entering counters.

#### Writing effects: sentences with pills

Every effect reads as its rules sentence. The parts you can change are
**pills**: tap one to change it.

- **＋ add effect** offers the verbs: deal damage, draw, gain life, add
  resources, discard, draw then discard some of those, sacrifice, create a
  token, counter a spell, and the verbs that act on a target (destroy it, put
  counters on it, transform it, prevent damage to it, attach to it, move it…).
- Numbers are pills too. A number can be fixed or computed: "1 per card under
  this Station", "its power", "the turn number", "if … then … else".
- **Choose** picks a target first, then the verbs inside act on "it".
  Choose several, **up to** a number, or **divide a pool** among the targets.
- **In order**, **Otherwise**, **For each**, **For each player**, **Later,
  when…** (a delayed trigger) and **Create an emblem** build larger effects out
  of smaller ones.
- Filters ("which permanents?") combine type, controller, zone, counters and a
  free condition, with presets such as "any creature" or "a permanent you
  control".

To check an effect, read the compiled rulebox under the preview: it shows the
effect as the engine will run it.

#### Card art

Tap the art box in the preview to open **Card art**:

- **choose an image**: the picture is copied into the game's folder, so it
  travels with a `.ceg.zip` export.
- A card is drawn at three sizes, each a different shape. Drag a rectangle to
  move a crop and a corner to resize it: the **FIELD** crop is the card in play,
  the **MINI** crop is the small tile (hand, lists). **shrunk: follows field**
  derives the small one from the field crop; **shrunk: own crop** frames it on
  its own. The focus view (a held card) always shows the whole picture.

#### The Pool view

**Cards › Pool** measures the whole card pool: stats per cost, crowded slots
(cards at the same type and cost that are hard to tell apart), and more. It
reports measurements only. Every card it names is a link to that card.

### Step 9: Decks (Decks, Rules › Deck construction)

**Rules › Deck construction** sets the constraints decks are checked against.
**start from** a preset (**mtg**, **hearthstone**, **swu**) or set them
yourself:

- **minimum cards**, **has a maximum size**, **limits copies of one card**;
- **slots**: cards a deck names but does not shuffle in (a Leader, a Base, a
  Hero), which types may fill each, and where it **starts in**;
- **identity**: which keywords on slot cards decide what else the deck may
  play.

In **Decks**, **＋ deck** adds a deck. For each deck:

- a stepper under every card sets its number of copies; **4 of each** and
  **clear** fill or empty the deck;
- each slot is a picker of the cards allowed in it;
- **✓ legal under …**, or the list of problems. An illegal deck still plays; the
  problems are only reported.

A game with no decks can still be played: each seat then draws plain filler.

### Step 10: Read the issues, then play

Go back to **Overview**. The issues list is the game's to-do list:

- **Errors** stop the game from starting: a name that refers to nothing (a type,
  field, counter, phase, zone, combat step or card that is not declared), an
  effect that reads a target or a number nothing provides, a replacement that
  cannot work as written, damage to players in a game where they take none.
- **Warnings** do not block: a duplicate name, a questionable turn structure or
  parameter, no way to lose, an empty deck, a keyword nothing gives meaning to,
  a deck that breaks the construction rules.

An issue that names a card is a link to it. When the readiness row says
**ready**, tap **▶ Play**.

## 4. Playtesting and playing

The table runs the game as it is in the editors, unsaved changes included. Change a card, go back to Play, and the next game uses the new card.

### 4.1 Starting a game (Play › Game)

**ON THE TABLE › ▶ Resume** returns to a game already in progress.

Under **NEW GAME**:

1. Choose who sits at the table:
   - **vs AI**: you play P0, the bot plays P1.
   - **both seats**: you play both seats by hand, every hand open, with the
     debug tools.
2. Choose each seat's deck: tap **P0 …** / **P1 …** to cycle through the game's
   decks, or tick **… deck: random each game**.
3. Against the bot, choose the **Opponent**. Each opponent plays its own style
   (there are no difficulty levels): **Proactive** races, **Reactive** answers, **Attrition**
   grinds, and **Heuristic** is the default all-rounder. Or tick **Opponent:
   random each game**.
4. Tap **▶ Start**. If a game is already on the table, the button says so and
   replaces it.

The same seed, decks and answers always play out the same way, so a game can be
stopped, resumed and replayed exactly.

### 4.2 Reading the table

```
┌──────────────────────────────────────────────┐
│ ‹  turn 3 · main · P0      Play|Debug  ✦  ⋯  │  top band
├──────────────────────────────────────────────┤
│  opponent's seat edge: badge, numbers        │
│  opponent's lanes                            │
│  ── lane names ──                            │
│  your lanes              ◆ stack 1 ▸         │  the board
│  your seat edge: badge, numbers              │
├──────────────────────────────────────────────┤
│  prompt: what you are being asked            │  prompt band
├──────────────────────────────────────────────┤
│  your hand                                   │  hand dock
└──────────────────────────────────────────────┘
```

- **The top band**: **‹** leaves the table, then the turn number, the phase and
  whose turn it is; **debugged** if the Debug lens has been used in this game;
  the **Play | Debug** lens switch; **✦ binder** (Debug lens only); **⋯** the
  tools drawer.
- **The board** is laid out from the game's own rules: your side nearest you,
  the opponent's across the table, the lane names between the two sides.
  Contested zones (lanes) form a grid, one column per lane, so the same column
  is the same lane on both sides. A seat's **edge** carries what it cannot lose
  without losing the game (a Station, a Leader) and its numbers: hand, library
  and graveyard sizes, pools, resources, and player counters such as life. Tap a
  number to browse that zone. Cards attached to another card sit directly
  beneath it.
- **The stack** is a badge, **◆ stack n**. Tap it to unfold the spells and
  abilities waiting to resolve; the top one resolves first.
- **The prompt band** says what you are being asked and holds the answers that
  have no place on the board: **Pass**, **Confirm**, **hold back**, **let it
  through**, choices between options.
- **The hand dock** shows your hand at a fixed place at the bottom. A game
  with an always-visible pool shows a chip for it; tap it to open the pool in
  place of the hand.

**Beats.** When the opponent acts, the board first shows the result, then a
short panel says what happened and who chose it, with the cards it is about
ringed on the board. Taps are held during a beat, so the board cannot change
between two of your taps. At the start of a game, **THE OPENING** summarises
the setup (draws, starting cards, anything that triggered).

**Arcs.** When you arm a card, curves run from it to everything it could reach.
They show what the tap would accept.

**Hold any card** to lift it into the focus view: the card larger, with its
**RULES**, the behaviour the engine actually runs. Tap anywhere to put it back.

### 4.3 Acting

Everything is done by tapping the board. A card or permanent you may use now is
**lit** (ringed in green).

- **Play a card**: tap it in your hand to arm it (**▶ Play?**), then tap it again
  or the pill to play it. If its type can go into more than one zone, the legal
  lanes light up: tap one.
- **Targets**: when an effect asks for a target, the legal ones are lit. Tap one
  to arm it (**◎ Target?**), tap again to confirm. **✕ cancel** backs out of an
  armed choice.
- **Abilities**: a permanent with one ability you can use now shows a **▶ use**
  pill. With several, tap it for a menu ("which ability?").
- **Declare attackers**: tap the attackers (each toggles), then **Attack (n)** in
  the prompt band. **None** attacks with nothing.
- **Declare blockers**: tap a blocker, then the attacker it blocks; **Confirm
  blocks**.
- **One attack at a time** (in games that fight that way): the attack is a
  priority action; tap the target, or pick **hold back**. A defender may be
  asked to redirect it, or **let it through**.
- **Pass** gives priority away. When nothing is on the stack and both players
  pass, the game moves on.
- **Choices** ("choose 1 of 3", "pick up to 2 cards") show the options as
  sentences or as cards; long-press a card to read it before picking.

### 4.4 Lenses and tools

The **Play** lens shows the game from your seat: the opponent's hand is hidden
(its size is always shown), and only the play tools are offered. The **Debug**
lens shows every hand and adds the debugging tools. Against the bot you can
switch lens at any time; the game is marked **debugged** from then on (on the
table, under Resume and under IN PROGRESS), and its result should not be
counted. A both-seats table is always in Debug.

The **⋯** drawer (from the bottom in portrait, from the right in landscape):

| Group | Tool | What it does |
|---|---|---|
| DEBUG | **↶ Undo** | Back to just before your last move. Against the bot, the bot's replies since go too. |
| | **⟲ Restart** | The same game from the start: same seed, same decks. |
| | **⤮ Shuffle** | Restart with a new shuffle. |
| | **✦ Binder** | The sandbox binder (see below). |
| | **✚ Save scenario** | Keep this position under a name (see below). |
| | **⇄ Seat …** | Which seat is nearest you. |
| | **P0: … / P1: …** | Change a seat's deck; the game restarts. |
| VIEW | **▦ Zones / ▦ Board** | Browse every zone (hand, library top first, graveyard, pools, exile), within what the lens lets you see. |
| | **≡ Log** | The game log, under the board. |
| | **Motion on / off**, **Tune motion** | Board animations, and sliders to adjust them. |
| GAME | **Concede** | Only when it is your turn to act. |
| | **Leave the table** | Back to the workspace. The game stays under IN PROGRESS. |

All animation stands down when the system's "remove animations" setting is on.

### 4.5 The sandbox

The sandbox is a test bench: any card of the game, no deck rules, and direct
edits to the table. Every edit is recorded like a move, so **Undo**, resuming
and exporting all include it. Three ways in:

- **Play › Sandbox**: under **TABLE**, choose an **Empty table** or **The
  game's decks**, and **Both seats by hand** or **Bot at P1**. Add the cards to
  put **IN P0'S HAND AT THE START** (find a card by name). Then **▶ Open the
  sandbox**. A sandbox always opens in the Debug lens.
- **✦ Try it on the table**, from the card editor: that card into your hand, on
  the table in progress or on a new both-seats table.
- **✦ binder**, on any table in the Debug lens.

The **binder** puts any card of the game into any seat's hand, play area,
graveyard, library top or exile; draws a card for a seat; and moves a seat's
life up or down. It says what the last pick did ("→ card to P0's hand"). An edit
made while nobody is being asked waits for the next moment a player has
priority.

In the Debug lens, **hold a card** and choose **move to** to send it to any
other zone.

An empty table never runs out of cards: a seat with no deck does not lose for
drawing from an empty library, and every priority question is asked, so you
always get the chance to edit.

**Scenarios.** **✚ Save scenario** keeps the table as it stands (every move and
edit) under a name. **Play › Sandbox › SCENARIOS** lists them: open one to
replay it under the game as it is now (so an edited card may change where it
leads), export it, or delete it. An exported scenario is a test case: it
records the state at every question, so a later version of the engine can
check it still reaches the same positions.

### 4.6 When a game won't start

If the game has errors, the table says **This game can't start** and lists them.
Fix them in the editors (Overview's issues list links to each card) and come
back. If the game was edited since a game on the table was recorded, the table
says so, restarts that game and drops the answers that no longer fit.

## 5. Glossary

| Term | Meaning |
|---|---|
| **Bundle** | A game exported with its card art, as a `.ceg.zip`. |
| **Compiled rulebox** | What a card does as the engine runs it, shown beside the rules text you wrote. |
| **Counter** | A number. **Player counters** belong to players (life is one); **counters on cards** sit on permanents (+1/+1, shield, charge…). |
| **Debugged** | A game in which the Debug lens was used; its result does not count. |
| **Face** | One side of a card. Most cards have one; double-faced cards have two. |
| **Field** | A stat a card type declares (power, toughness, hull…). |
| **Initiative** | In a shared round, the player who acts first and declares attacks; it passes at the end of the round. |
| **Issue** | Something wrong with the game. **Errors** stop it from starting; **warnings** do not. |
| **Lens** | How you look at a table: **Play** (your seat only) or **Debug** (everything, with tools). |
| **Pilot** | The bot that plays a seat, with its style (Proactive, Reactive, Attrition, Heuristic). |
| **Pool** | An off-board zone cards are played from, beside the hand; also the resources a player holds to pay costs. |
| **Priority** | The moment a player may act. Everyone passing in turn moves the game on. |
| **Rail** | The row (or column, in landscape) of module buttons in a game's workspace. |
| **Sandbox** | A table where you may put any card anywhere, recorded as moves. |
| **Scenario** | A saved sandbox position, which can be reopened or exported as a test case. |
| **Slot** | A card a deck names outside its main list: a Leader, a Base, a Hero. |
| **Stack** | Spells and abilities waiting to resolve; the last one added resolves first. |
| **Working copy** | The game as it is in the editors, saved or not. The table always runs it. |
