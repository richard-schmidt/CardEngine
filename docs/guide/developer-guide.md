# CardEngine developer guide

How CardEngine is built, why it is shaped the way it is, and how to extend it.
For using the app, see the [user guide](user-guide.md).

- [1. Principles](#1-principles)
- [2. Architecture](#2-architecture)
  - [2.1 Functional architecture](#21-functional-architecture)
  - [2.2 Non-functional architecture](#22-non-functional-architecture)
  - [2.3 Applicative architecture](#23-applicative-architecture)
- [3. Toolchain, build and test](#3-toolchain-build-and-test)
- [4. How-tos: extending the vocabulary](#4-how-tos-extending-the-vocabulary)
- [5. Reference](#5-reference)

---

## 1. Principles

Each of these decisions is enforced by the build or the test suite.

1. **A game is data.** Rules, cards and decks are one serialisable document.
   Everything a game can vary (card types, zones, turn structure, resources,
   player counters, combat) is a declarative data type with named presets;
   no game needs Kotlin code of its own.
2. **Card behaviour is a closed language.** Effects form one sealed AST,
   interpreted by one exhaustive fold. Values and conditions inside effects are
   expression trees rather than lambdas, so every card can be saved, edited,
   compared and read back.
3. **The engine core depends on the Kotlin standard library alone.** No
   Android, no Compose, no coroutines library. It runs on a phone, a desktop JVM
   or a CI box unchanged.
4. **State is immutable, and randomness lives in it.** Every engine step returns
   a new `GameState`; the shuffler's state is a field of it. The same seed and
   the same answers always produce the same game.
5. **A game in progress is its seed, deck picks and answers.** The board, the
   log and the whole state are derived by replay. Undo, resume, parked games,
   scenarios and bot games are all built on this.
6. **One legality function.** The engine, the board's taps, the bots and the
   agent all ask the same `legality()`, so what is offered is always what is
   accepted.
7. **UI decisions live outside Compose.** What a tap means, what the board
   shows, how a value is labelled: plain Kotlin in `ccgui`, where the suite can
   test it. Compose files only draw.
8. **Authoring mistakes are reported.** Validation is a total
   function returning located diagnostics. Only errors that would make the game
   play differently from what was written stop it from starting; nothing throws
   mid-game.
9. **Invariants are checked by guards.** Dependency rules, "every effect is
   reachable from the editor", "every doc field has a control": each is a check
   that fails the build.
10. **Scope is games playable on paper.** Mechanics that only a computer could
    adjudicate are out of scope.

## 2. Architecture

### 2.1 Functional architecture

#### The pipeline

```
 GameDoc (authored, JSON)                      src/ccg/CreatorDoc.kt
   │  rules: RulesDoc · sets: [SetDoc] · decks: [DeckDoc] · deckRules
   │
   ▼  GameDoc.compile()                         src/ccg/Compile.kt, Lower.kt
 Compiled(rules: Rules, diagnostics)
   │  resolves every name to a declaration, reports located diagnostics,
   │  lowers surface forms to the core language
   │  .runnable = rules, or null while there are errors
   ▼
 startGame(doc, rules, seats, seed) → GameState   src/ccg/Setup.kt
   │
   ▼  step(rules, Run) → Stepped                src/ccg/Step.kt
 Stepped: state, question?  ──answer──►  stepped.next(answer)
   │                                        ▲
   ▼                                        │
 a person (the table), a pilot (the bot), an agent (CLI/MCP), a corpus file
```

**Authoring model.** `GameDoc` is an immutable tree that the Creator edits by
`copy(...)`: `RulesDoc` (extra types, zones, counters, turn, resources, combat,
parameters), `SetDoc`s of `CardDoc`s (each with one or more `FaceDoc`s: name,
types, fields, keywords, cast effect, triggers, statics, activated abilities,
replacements), and `DeckDoc`s checked against `DeckRules`. A card's identity is
`CardDoc.key()`: a minted id, else its display name.

**Compilation.** `GameDoc.compile()` is the only path from authored content to
what the engine runs. It assembles `Rules`, checks that every name (type, field,
counter, phase, zone, combat step, stance, parameter, card) resolves to a
declaration and every variable (X, a chosen target, "each", the triggering
event…) is bound where it is read, and returns `Diagnostic`s that carry a path
into the document, so the app can take the author to the problem. Errors make
`runnable` null; warnings never do.

**Lowering.** The authoring language has *surface forms* an author reaches for
("this card's power", "+1/+1", "when this enters", a combat preset) and a *core*
the interpreter runs. `Lower.kt` defines each surface form once, as the core it
means. The interpreter refuses a surface form, so it only handles the core.

**The effect language.** `Effect` (`Ast.kt`) is a sealed hierarchy: damage,
draw, counters, tokens, choices (`Choose`, `ChooseMany`, `ChooseMode`), zone
moves, continuous modifiers, prevention, delayed triggers, iteration, control,
and the combat verbs. Every value slot is an `IntExpr` and every condition a
`BoolExpr` (`Expr.kt`). Targets are `BoundTarget`s referring to an object or to
a named variable a binder fills. Static abilities are `CharOp`s and rule
modifiers (`Static.kt`); triggers and replacements match `EventPattern`s.
`Walk.kt` is the one traversal of the tree; evaluation lives in `Eval.kt`, and
the AST files hold data only.

**The engine.** `Engine.kt` runs a LIFO stack; a priority rotation in which any
action restarts the round; an event queue that feeds triggered abilities back
onto the stack; replacement effects, each applied at most once per event chain;
and a state-based-action fixpoint after each resolution. Announcing a spell or
ability follows rules order: modes, then X, then targets, then the cost. Turns
fold over the declared `TurnStructure`; combat is a program of combat verbs
(`CombatResolver.kt`), with the presets as library content in
`content/combat/presets.json`.

**The runtime seam.** Nothing in the engine suspends. A client calls
`step(rules, run)` and gets a `Stepped`: the state and the question the asked
player must answer (or none, at the end). It answers with `next(answer)`. The
engine's pending work is data in `GameState.pending` (`Machine.kt`): frames with
a checkpoint, so a question's state alone is enough to go on, and resuming
re-runs one frame rather than the whole game. A question with nothing to choose
is answered inside the engine and is not returned.

**Questions and answers.** `Question` (priority, targets, modes, numbers, cards,
combat declarations) is what the engine asks; `Answer` is what comes back.
Answers carry references rather than objects ("the card with this instance id,
in this zone"), so both ends rebuild the action from their own state. An invalid
answer becomes a deterministic default plus a log line, and the question is not
asked again.

**Sessions.** `ccgui.PlaySession` is a game in progress: the bundle digest, the
seed, the deck picks and the answers. It encodes to a few hundred bytes of text
(`SessionCodec.kt`). The table, parked games, scenarios, undo (drop answers),
restart (drop all answers) and the bot's games are all this one value. A
session recorded against a different version of the game is detected by the
bundle digest and restarted instead of replayed into different rules.

**Deciders.** Anything that answers questions is a pilot behind one seam
(`PlayerInput` in the core; `ccgui.Pilot` and the policy pilots in `Policy.kt`).
Pilots see the game through `GameState.asSeenBy(seat, rules)` (`Redaction.kt`),
the same visibility rule as the board, so a bot cannot look at a hand it should
not see. The same pilots drive the balance tools.

**Visibility.** The engine is the referee and sees everything. `Visibility.kt`
says what each player may see; `ccgui.Viewpoint` applies the Play and Debug
lenses on the board.

**Conformance.** The corpus (`corpus/*.json`) records the bundled games' matches:
the answers, and a canonical digest of the state after each one
(`Digest.kt`). A second implementation of the rules conforms when it
reproduces every digest. Scenarios exported from the app use the same format.

### 2.2 Non-functional architecture

| Quality | How it is achieved |
|---|---|
| **Determinism** | Immutable state, RNG inside the state (Mulberry32, `Rng.kt`), a declared order wherever order matters (permanents by id, triggers by active player then declared order). A replay golden (`test/golden/replay.txt`) pins how every bundled match ends; an independent Python implementation pins the RNG's test vector. |
| **Portability** | The core compiles against the standard library alone and reads and writes JSON with its own small parser. Content is JSON with a JSON Schema (`docs/schema/cardengine.schema.json`). The conformance corpus lets a port in another language prove it plays the same games. |
| **Robustness** | Evaluation is total: an unbound value reads 0, a reference to something gone does nothing and says so in the log. An invalid answer becomes a default. An action that changes nothing but the log counts as a pass after one free retry, so no client can spin the priority loop; an endless loop ends the game as a draw. |
| **Authoring safety** | Problems are located diagnostics rather than exceptions. The authoring surface is guarded like the engine: every field of the document must have an editor control or be explicitly classified, or a test fails. |
| **Testability** | The core and the UI decision logic compile without Android, so the whole rules engine and most of the app's behaviour run in a host-JVM suite in a few minutes. |
| **Maintainability** | Dependency rules and data/evaluation separation are build guards. Exhaustive `when`s in the tests fail to compile when a sealed case is added, so a new effect cannot land unhandled or unreachable from the editor. |
| **Durability** | Saves are explicit; each keeps the previous version (20 per game). A debounced crash scratch protects unsaved edits; a game on the table is written on every move. |
| **Privacy** | Everything is local: games live in the app's own storage, and moving one is an explicit export. The app has no account and no network features. |
| **Performance** | The engine re-runs at most one frame per answer. Expensive views (the pool report, compilation for the editors) are recomputed only when the game changes. |
| **Accessibility** | All motion honours the system's "remove animations" setting; top-bar actions are at least 44 × 40 dp; text colours meet WCAG AA contrast. |

Current scope limits: two players today (seats
are a list and new code adds no two-seat assumption), a both-seats table without
a hand-off screen, and mechanics that need hidden computer adjudication left out.

### 2.3 Applicative architecture

#### Packages and their dependency rules

| Path | Package | Holds | May depend on |
|---|---|---|---|
| `src/ccg/` | `ccg` | The engine: authoring model, compiler, effect language, interpreter, state, combat, JSON codecs, conformance | `kotlin-stdlib` only |
| `src/ui/` | `ccgui` | Decision logic for the app: navigation, sessions, pilots, board layout, labels, verb catalogue, prompts, pool statistics, sandbox, agent play | stdlib + `ccg` |
| `src/com/ccg/` | `com.ccg` | The Compose app: the shell, the Creator modules, the table, the design system, storage | anything |
| `agent/` | | The `cge` CLI, the MCP server, balance and measurement tools | stdlib + `ccg` + `ccgui` |
| `test/` | | The suites, on a hand-rolled harness | stdlib + `ccg` + `ccgui` |
| `godot-poc/` | | A Godot proof of concept, outside every build ([README](../../godot-poc/README.md)) | |

The first two rules are checked by compiling each layer against only what it may
see. Further guards keep evaluation out of the AST files, keep every client on
`step`, and keep game construction on `compile()` (see
[3.3](#33-the-test-suite-and-its-guards)).

```
   ┌──────────────────────── com.ccg (Compose) ────────────────────────┐
   │ MainActivity → shell (Creator.kt)  → modules: Overview · Rules ·  │
   │                                      Cards · Decks · Play         │
   │ Hotseat.kt (the table) · CgDesignSystem · GameStore (storage)      │
   └───────────────┬───────────────────────────────────────────────────┘
                   │ calls
   ┌───────────────▼──────────── ccgui (decisions) ────────────────────┐   agent/
   │ Nav · PlaySession · Pilot/Policy · BoardLayout · PlayIntent ·     │◄── cge CLI,
   │ Labels · Verbs · Rulebox · Recap · Viewpoint · PoolStats · …      │    MCP server,
   └───────────────┬───────────────────────────────────────────────────┘    tools
                   │ calls
   ┌───────────────▼───────────── ccg (engine core) ───────────────────┐
   │ CreatorDoc → Compile/Lower → Rules → Engine (step, Machine) → State│
   │ Ast · Expr · Static · Events · Combat · Json codecs · Digest      │
   └───────────────────────────────────────────────────────────────────┘
```

#### The app layer

- **Shell.** `MainActivity` hosts one root, the Shelf. Navigation is data:
  `ccgui.Nav`, a stack of `Route`s with every Back rule in one pure reducer, so
  the suite tests it. The Compose shell draws the top route and asks `Nav` what
  Back does.
- **Modules.** Each rail module is a `ModuleSpec` in its own file
  (`OverviewModule.kt`, `RulesModule.kt`, `CardsModule.kt`, `DecksModule.kt`,
  `PlayModule.kt`), registered in `MODULES` (`Modules.kt`). A module navigates
  only through its `ModuleScope`.
- **State.** `CreatorViewModel` (`Creator.kt`) holds the working copy of the open
  game, the play session and the view state. Edits are `copy(...)` on the
  immutable document.
- **The table.** `Hotseat.kt` runs a `step` loop in a coroutine: each question
  goes to the bot's pilot or to the person's seat, which raises a prompt and
  waits for the board to answer. The decisions it draws (what a tap means,
  where things sit, what to recap) come from `ccgui`.
- **Storage** (`GameStore.kt`), under the app's files directory:

  ```
  games/<id>.json                 a game (saved only by Save)
  games/<id>/versions/<ts>.json   previous saves, 20 kept
  games/<id>/art/                 card pictures
  games/<id>/scenarios/*.txt      saved sandbox tables
  games/.parked/<id>.txt          the game left on the table
  games/.autosave/<id>.json       crash scratch of unsaved edits
  ```

  A `.ceg.zip` export carries the game file and its art.
- **Bundled content.** `content/*.json` (listed by `content/index.json`) and the
  combat preset library ship in the APK and are read as classpath resources by
  `ccg.Bundled`.

#### The agent layer

`agent/` builds command-line tools on the same engine and decision logic, outside
the APK. `cge` plays a game headlessly, one JSON request in and one JSON response
out; the session token is the whole state, so the tool remembers nothing between
calls. `agent/mcp_server.py` exposes the same verbs as an MCP server (list games,
new game, state, act, fork, vary, pool, list cards). An agent does not edit a game in
place: it **forks** it and applies named **variations** (`ccgui.Variation`). The
measurement tools (`balance`, `probe`, `playmix`, `economy`, `pool`, `dump`,
`dumpdeadlock`) play or read games at scale for balance work.

## 3. Toolchain, build and test

### 3.1 The toolchain

CardEngine is developed on an Android phone in **Termux**, without Android
Studio and without the Android Gradle Plugin. The same scripts run in CI on a
Linux runner.

| Tool | Role |
|---|---|
| `kotlinc` (Termux's `kotlin` package) | Compiles all Kotlin. Its bundled `compose-compiler-plugin.jar` performs the Compose transform. |
| `aapt2` | Compiles and links Android resources, produces `R.java` and the base APK. |
| `ecj` (on the phone) / `javac` (in CI) | Compiles `R.java`. |
| `d8` | Dexes the app's classes and every dependency jar. |
| `apksigner` | Signs the APK. |
| Gradle (`build.gradle.kts`) | **Only resolves dependencies**: Maven coordinates to `.aar`/`.jar` files under `deps/`. It builds nothing. |
| Python 3 | Build and test guards, the manifest merge, the MCP server. |

Requirements: an Android SDK platform jar (`android-34` by default, path in
`ANDROID_JAR`), Kotlin, Gradle, a JDK, `aapt2`, `d8`, `apksigner`, `zip`. Every
path and secret in `build.sh` can be overridden from the environment.

### 3.2 Building the APK: `build.sh`

```sh
bash build.sh        # → out/apk/app-signed.apk
```

The steps, in order:

1. **Keystore**: a persistent `debug.keystore` at the project root, created once.
   It must not change between builds, or Android refuses to install the new APK
   over the old one. In CI a missing keystore is an error.
2. **XML comment check**: `check_xml_comments.py` fails early on a `--` inside
   an XML comment, which `aapt2` would reject without saying where.
3. **Resolve dependencies**: `gradle resolveDeps`, then each `.aar` is unpacked:
   its `classes.jar`, its resources, its native libraries and its manifest.
   Some AndroidX and Kotlin libraries publish only multiplatform variants, so
   `build.gradle.kts` resolves them through a separate JVM configuration.
4. **Compile resources** and **merge manifests** (`merge_manifest.py`: each
   dependency's providers, services and permissions, with `${applicationId}`
   substituted and App Startup providers merged into one).
5. **Link resources**: `aapt2 link`, with one `--extra-packages` per library so
   each gets its own `R` class.
6. **Compile `R.java`**.
7. **Import guard**: a cross-package symbol used without its import fails here
   in seconds rather than after the Kotlin compile.
8. **Compile Kotlin**: every `src/**/*.kt`, with the Compose plugin,
   `-jvm-target 11`, against `android.jar`, the `R` classes and the dependency
   jars.
9. **Dex**: `d8 --min-api 23` over the app's classes and every dependency jar.
   Keep `--min-api` in step with the manifest's `minSdkVersion` by hand.
10. **Package**: all `classes*.dex` (a large dependency tree multidexes), the
    `content/` directory (the bundled games, read as classpath resources; the
    build fails if they are missing), and native libraries.
11. **Sign**: `apksigner`.

The CI workflow runs `test.sh`, then `build.sh` with the keystore from
repository secrets, verifies the signature and publishes the APK as a release.

### 3.3 The test suite and its guards

```sh
bash test.sh         # guards, then every suite, on the host JVM
```

`test.sh` needs no Android at all. It runs, in order:

| Step | What it checks |
|---|---|
| **Core guard** | `src/ccg` compiles against `kotlin-stdlib.jar` alone. |
| **AST guard** | The language files (`Ast`, `Expr`, `Static`, `CreatorDoc`, `Duration`, `Zones`) never name `GameState` or `EvalContext` in code: evaluation belongs in `Eval.kt`. |
| **UI-logic guard** | `src/ui` compiles against stdlib and the core alone. |
| **Accordion guard** | No `CgGroup` nested in a `CgGroup`: a nested section becomes a destination or a sheet. |
| **Orphaned-KDoc guard** | No doc comment stacked on another (Kotlin attaches only the last). |
| **Unreachable-state guard** | Every `mutableStateOf` field on the ViewModel is written by something a user can do, not only seeded by a reset. |
| **Unreachable-content guard** | Every list in `content/index.json` is read by the app, so no bundled game ships without a way to open it. |
| **One-build-path guard** | Outside the core, games are built only by `compile()`, never by the unchecked `rules()` / `build()`. |
| **One-way-to-run guard** | Outside the core, games run only through `step`; nothing constructs an `Engine`. |
| **The suites** | Every `test/*.kt`, compiled against the guarded classes and run on the JVM. |

The harness is hand-rolled (no JUnit): `check(name) { … }` with `assertEq` and
`assertTrue`, defined in `test/CoreTest.kt`. Its `main()` runs the core checks
and then each suite's entry function (`uiChecks()`, `contentChecks()`,
`archReviewChecks()`…), and exits non-zero if anything failed. **A new test file
must add its entry function to that list**; the build compiles every file in
`test/`, but only calls what `main()` calls.

| Suite | Covers |
|---|---|
| `CoreTest.kt` | The engine, on minimal fixtures. |
| `UiTest.kt` | `ccgui`: navigation, sessions, board layout, prompts, labels, pilots. |
| `ContentTest.kt` | The bundled games: claims about the games actually shipped. |
| `CodecTest.kt`, `SchemaTest.kt` | Every persisted field round-trips; the JSON Schema matches the codec. |
| `CreatorCoverageTest.kt` | Every document field is classified as authorable (has a control), not authorable (the gap list) or machine-set. A new field fails here until classified. |
| `AuthoredTest.kt` | Games exported from the app and dropped in `~/cge-test/`, and exported scenarios in `~/cge-test/cases/`, played headless. |
| `PoolTest.kt` | The pool instrument. |
| `ReviewTest.kt`, `DslReviewTest.kt`, `ArchReviewTest.kt` | Regressions pinned by earlier reviews, plus the golden and generated-file checks. |

`sync-authored.sh`, run by `test.sh`, copies games exported from the app out of
shared storage into `~/cge-test/` (Termux's home is private to Termux, so the
app cannot write there directly).

#### Generated files

These files are written by the code and committed. The suite fails if a
committed copy differs from what the code produces now. Regenerate one only for
a change that is meant to alter it, and say why in the commit.

| File | What | Regenerate with |
|---|---|---|
| `docs/schema/cardengine.schema.json` | JSON Schema for game files and answers | `CGE_WRITE_SCHEMA=1 bash test.sh` |
| `content/*.json`, `content/combat/presets.json` | The bundled games and the combat preset library. These are the source: edit them directly, or export from the app. | `CGE_WRITE_CONTENT=1 bash test.sh` normalises them |
| `corpus/*.json` | The conformance corpus: each bundled game's matches, with answers and a digest after each | `CGE_WRITE_CORPUS=1 bash test.sh` |
| `CorpusCoverage.md` (measured section) | Which effect cases the corpus exercises | `CGE_WRITE_CORPUS=1 bash test.sh` |
| `test/golden/replay.txt` | How every bundled match ends: "this change alters no game" | `CGE_WRITE_REPLAY=1 bash test.sh` |
| `test/golden/rng.txt` | The RNG's test vector, from an independent Python implementation | `python3 test/golden/mulberry32.py > test/golden/rng.txt` |

### 3.4 Agent tools

```sh
bash agent/build-agent.sh          # builds the tools and their wrapper scripts
./agent/cge games                  # headless play: one JSON request, one response
./agent/balance <game> <seeds>     # deck × deck × pilot win-rate matrix
python3 agent/mcp_server.py        # the same play loop as a stdio MCP server
```

`build-agent.sh` compiles the core and `ccgui` exactly as `test.sh` does, then
each tool. The wrappers embed an absolute path, so they are generated rather than
committed. Tools:

| Tool | Answers |
|---|---|
| `cge` | Play a game: `games`, `forks`, `new`, `state`, `act`, `cards`, `fork`, `vary`, `delete-fork`. |
| `balance` | Every ordered deck pairing under every pilot pairing: win rates, skew, seat advantage, mirror spreads, best vs best. |
| `probe` | The seat-advantage probe: vary one thing and see whether the seat rate moves. |
| `playmix` | What pilots actually play, by card type, and why a card in hand was not on the menu. |
| `economy` | Whether a deck can pay for its own cards, and when. |
| `pool` | The pool report, as the app's Pool view shows it. |
| `dump` | A game as JSON, to `diff` a bundled game against an exported one. |
| `dumpdeadlock` | The final position of the first game that does not finish. |

Games are resolved through one catalogue (`agent/Catalogue.kt`): the bundled
games, games exported from the app, and forks, which live in `~/cge-forks/` and
are never written anywhere else. `CGE_TEST_DIR` and `CGE_FORK_DIR` override
the two directories.

## 4. How-tos: extending the vocabulary

The language is made of sealed hierarchies matched by exhaustive `when`s, so
**adding a case and following the compile errors** finds most of the places it
must be handled: the interpreter, the traversal, the codec, the labels, the
editor and the tests' `caseTag` tripwires. The lists below also name the places
the compiler cannot find for you. In every case, finish with `bash test.sh`.

### 4.1 Add an effect verb

Example to follow: `Effect.ClearDamage`.

1. **The case** in `src/ccg/Ast.kt`: a `data class` in `sealed interface Effect`,
   data only. Value slots are `IntExpr`, conditions `BoolExpr`, targets
   `BoundTarget`. Document what it does and what it does when its target is gone.
2. **What it does**, in the `interpret` fold of `src/ccg/Engine.kt`. Evaluate slots
   through `Eval.kt`, raise `GameEvent`s for anything other cards may react to,
   and log refusals (`state.logged(...)`) rather than failing silently. A verb's
   amount is never negative.
3. **The traversal** in `src/ccg/Walk.kt`: how substitution and binding pass
   through the new node, and which names it refers to (so the compiler can check
   them).
4. **JSON** in `src/ccg/EffectJson.kt`: one line in `effectToJson` and one in the
   decoder, under a new `"op"` name. Elide fields that are at their default.
5. **Is it a surface form?** If it is a convenience for something the core can
   already say, do not interpret it: lower it in `src/ccg/Lower.kt` (a clause of
   `lowerNode`) and add it to `Effect.isSurface()`. The interpreter refuses a
   surface form that reaches it.
6. **Words**: `src/ui/Rulebox.kt` (the compiled rulebox line) and, where the
   verb has a sentence form in labels, `src/ui/Labels.kt`.
7. **The editor**. In `src/ui/Verbs.kt`, add a `Verb` to the right list with a
   sensible seed value; in `src/com/ccg/EffectEditor.kt`, its sentence, with pills
   for the editable parts.
8. **Tests**:
   - the `caseTag` tripwires in `test/UiTest.kt` and `test/CoreTest.kt` (they
     stop compiling until the case is numbered), and bump "all N Effect cases
     are on the menu";
   - a specimen in `test/CodecTest.kt` with every field away from its default,
     so it round-trips;
   - a behaviour check in `test/CoreTest.kt`.
9. **Regenerate the schema**: `CGE_WRITE_SCHEMA=1 bash test.sh`, and commit it.
   The corpus and the replay golden must not change; if they do, the new verb
   altered existing games.

### 4.2 Add a value or a condition (`IntExpr`, `BoolExpr`)

Example: `IntExpr.TurnNumber`.

1. `src/ccg/Expr.kt`: the case.
2. `src/ccg/Eval.kt`: its value. Evaluation is total: return 0 (or false) for
   anything unbound or gone.
3. `src/ccg/Walk.kt`: substitution, and any names or variables it reads.
4. `src/ccg/ExprJson.kt`: encode and decode.
5. `src/ui/Labels.kt`: how it reads ("the turn number").
6. `src/ui/Verbs.kt`: the number pill's menu (`intKinds`) or the condition
   menu, so an author can pick it.
7. `test/CodecTest.kt` specimen, the `BoolExpr` tripwire in `test/UiTest.kt` for a
   condition, and a check that it evaluates.

If it reads a variable (a chosen target, the triggering event), the compiler
must report a read where nothing binds it: add the `FreeVar` case in
`src/ccg/Walk.kt` and its `DiagCode` in `src/ccg/Compile.kt`.

### 4.3 Add a static effect (`CharOp` or `RuleMod`)

Example: `CharOp.AddType`, `RuleMod.Cant`.

1. `src/ccg/Static.kt`: the case. A `CharOp` declares its default **layer**
   (the order continuous effects apply in).
2. `src/ccg/Eval.kt`: how it changes a permanent's derived characteristics. A
   `RuleMod` is read where the rule applies (`GameState` helpers such as
   `activateBarred`, `attackBarred`, or the engine for draws and casts).
3. `src/ccg/Walk.kt`, `src/ccg/EffectJson.kt` (CharOp) or `src/ccg/DocJson.kt`
   (RuleMod).
4. `src/ui/Catalogue.kt`: the menu labels (`CHAR_OPS`, `RULE_MODS`) and their
   mapping both ways; `src/ui/Rulebox.kt`: its line;
   `src/com/ccg/TriggerStaticEditor.kt`: its editor row.
5. Tripwires in `test/UiTest.kt` and `test/CoreTest.kt`, a codec specimen, a
   behaviour check.

### 4.4 Add an event or a trigger

Example: `EventPattern.SelfAttacks` and `TriggerDoc.SelfAttacks`.

1. If nothing raises the event yet, add a `GameEvent` (`src/ccg/Events.kt`) and
   raise it in the engine where it happens.
2. `EventPattern` (`src/ccg/Ast.kt`): the matcher, and how it matches in
   `src/ccg/Eval.kt`. Say whether it needs a source ("this") and what happens
   without one.
3. A named trigger kind is a **surface form**: add the `TriggerDoc` case in
   `src/ccg/CreatorDoc.kt` with the pattern it means. Otherwise authors reach it
   through the generic "whenever an event happens…" trigger.
4. `src/ccg/Walk.kt`; JSON in `src/ccg/EffectJson.kt` (pattern) and
   `src/ccg/DocJson.kt` (trigger kind).
5. `src/ui/Catalogue.kt`: `TRIGGER_KINDS` / `EVENT_KINDS` and their mappings;
   `src/ui/Labels.kt`: the "when…" wording.
6. Tripwires (`test/UiTest.kt`, `test/DslReviewTest.kt`), a codec specimen, a
   check that it fires, and one that it does not fire when it should not.

### 4.5 Add a combat verb or a combat preset

Combat is a program in the effect language, so a new combat **mechanism** is an
effect verb (4.1) that acts on the combat under way (`GameState.combat`), plus
its handling in `src/ccg/CombatResolver.kt`.

A new **preset** needs no code: add it to `content/combat/presets.json` as a
program, normalise the file with `CGE_WRITE_CONTENT=1 bash test.sh`, and give it
a one-line description in `presetBlurb` (`src/com/ccg/RulesModule.kt`).

### 4.6 Add a game parameter

Example: `GameParams.attackDelayOnEntry`.

1. `src/ccg/Params.kt`: the field, with a default that keeps every existing game
   playing as before. If effects should be able to read it, add its name to
   `PARAM_NAMES` and to the `when` beside it.
2. Where the engine honours it.
3. `src/ccg/DocJson.kt`: write it only when it differs from the default, and
   read it with that default, so no file needs migrating.
4. Its control in `src/com/ccg/RulesModule.kt` (The game section).
5. `test/CreatorCoverageTest.kt`: classify the new field as authorable. **Every
   field of the document must be classified**, or this test fails: that is what
   keeps the app able to author everything the engine can run.
6. A codec specimen and a behaviour check. If agents should be able to vary it,
   add a named variation in `src/ui/Variation.kt`.

The same steps apply to any new field of the document (`TypeDef`, `PlayZoneDef`,
`CardDoc`, …): model, engine, codec, control, coverage classification, tests.

### 4.7 Add a Creator control

1. Put any decision (what the control offers, how a value reads, what a choice
   produces) in `src/ui` as plain Kotlin, and test it in `test/UiTest.kt`.
2. The composable in the module's file only draws it and calls
   `vm.updateGame { … }` (or `updateFace`, `updateCardDoc`, `updateRules`,
   `updateDeck`) with a `copy(...)`.
3. Respect the build guards: no `CgGroup` inside a `CgGroup` (use a
   destination or a sheet), no `BackHandler` outside the shell and the table's
   overlays, and no ViewModel state field that only a reset writes.
4. Mark the field authorable in `test/CreatorCoverageTest.kt`.

### 4.8 Add a pilot style

A pilot style is a `Policy` in `src/ui/Policy.kt`: a name, the value of each
action, which lane it prefers, whether it presses the opponent's face, and its
trading and blocking stance. `PolicyPilot` does everything else, so styles
differ only in what they want. Add it to `ALL_POLICIES`; it then appears in
Play's opponent list and in the balance tools. Check it with
`./agent/balance <game> <seeds>` before and after.

### 4.9 Change the file format

Adding an optional key with a harmless default needs nothing more. Anything that
makes an existing file decode **differently** needs a format bump:

1. Increase `FORMAT_VERSION` in `src/ccg/DocJson.kt`.
2. Add a step to `MIGRATIONS` that rewrites the previous version's JSON into the
   new meaning. The decoder only ever knows the current shape.
3. `CGE_WRITE_SCHEMA=1 bash test.sh`; normalise content with
   `CGE_WRITE_CONTENT=1` if the bundled games change form.
4. Update the format assertion in `test/ArchReviewTest.kt`.

### 4.10 Change a bundled game

The bundled games are `content/*.json`, listed in `content/index.json`. Edit the
JSON directly, or edit the game in the app, export it as JSON and copy it over.
Then:

- `CGE_WRITE_CONTENT=1 bash test.sh` normalises it to the codec's own form;
- if its matches change, `CGE_WRITE_CORPUS=1` and `CGE_WRITE_REPLAY=1` record
  the new ones, and say in the commit why the games changed;
- raise its `contentVersion`, so installed copies show **update** on the Shelf.

## 5. Reference

### Source map

**`src/ccg`: the engine core**

| File | Holds |
|---|---|
| `CreatorDoc.kt` | The authoring model: `GameDoc`, `RulesDoc`, `SetDoc`, `CardDoc`, `FaceDoc`, `DeckDoc`, `TriggerDoc`, `ReplacementDoc`. |
| `Compile.kt`, `Lower.kt` | `GameDoc.compile()`, diagnostics, and lowering surface forms to core. |
| `Ast.kt`, `Expr.kt`, `Static.kt`, `Duration.kt` | The language: `Effect`, `IntExpr`/`BoolExpr`, `CharOp`/`RuleMod`, durations. Data only. |
| `Eval.kt`, `Walk.kt` | Evaluation, and the one traversal of the language. |
| `Rules.kt`, `Zones.kt`, `Turn.kt`, `Params.kt`, `Vocabulary.kt`, `Deck.kt` | The declarative game model: types, zones, turn structure, parameters, known words, deck construction. |
| `Cards.kt`, `Cost.kt` | Built card definitions and costs. |
| `State.kt`, `Stack.kt`, `Events.kt`, `Visibility.kt` | The immutable game state, stack objects, the event stream, hidden information. |
| `Engine.kt`, `Machine.kt`, `Step.kt` | The interpreter, pending work as frames, and the `step` seam. |
| `Actions.kt`, `Answer.kt`, `PlayerInput.kt` | What a player may do (`legality()`), answers, the decider seam. |
| `Combat.kt`, `CombatResolver.kt` | The combat model and the combat verbs. |
| `Setup.kt`, `Rng.kt`, `Sandbox.kt` | Opening state, the seeded RNG, sandbox table edits. |
| `Json.kt`, `DocJson.kt`, `EffectJson.kt`, `ExprJson.kt` | The JSON reader and codecs, format versions and migrations. |
| `Bundled.kt`, `Conformance.kt`, `Digest.kt` | Bundled content, the conformance corpus, the canonical state digest. |

**`src/ui`: `ccgui`, decisions outside Compose**

| File | Holds |
|---|---|
| `Shell.kt`, `Landing.kt`, `Identity.kt`, `Diagnostics.kt` | Navigation and Back, the Shelf's decisions, colours and covers, the jump search, where an issue leads. |
| `PlaySession.kt`, `SessionCodec.kt`, `Sandbox.kt`, `Conformance.kt` | Sessions, parked games, sandbox setups and scenarios, exporting a session as a test case. |
| `Pilot.kt`, `Policy.kt`, `ModeChoice.kt`, `Redaction.kt` | Pilots and play styles, and what a pilot may see. |
| `Viewpoint.kt`, `BoardLayout.kt`, `SeatEdge.kt`, `Prompt.kt`, `PlayIntent.kt` | The lenses, where things sit on the board, what a tap means. |
| `Recap.kt`, `Juice.kt`, `Particles.kt`, `Atmosphere.kt`, `ArtFraming.kt` | What just happened, board motion, card-art framing. |
| `Labels.kt`, `Rulebox.kt`, `Verbs.kt`, `Catalogue.kt` | Rules text, the compiled rulebox, the editor's menus. |
| `PoolStats.kt` | The pool report. |
| `AgentPlay.kt`, `Variation.kt` | Playing from outside the app, forks and named variations. |

**`src/com/ccg`: the Compose app**

| File | Holds |
|---|---|
| `MainActivity.kt`, `Creator.kt`, `Modules.kt` | The activity, the shell and the ViewModel, the module contract. |
| `ShelfScreen.kt`, `OverviewModule.kt`, `RulesModule.kt`, `CardsModule.kt`, `DecksModule.kt`, `PlayModule.kt`, `JumpSearch.kt` | The Shelf and the five modules. |
| `EffectEditor.kt`, `TriggerStaticEditor.kt`, `CostEditor.kt`, `CardArt.kt` | The sentence editors and the art studio. |
| `Hotseat.kt`, `JuiceFx.kt`, `Motion.kt`, `AtmosphereFx.kt` | The table and its motion. |
| `CgDesignSystem.kt` | The design tokens (`Cg`) and shared components. |
| `GameStore.kt` | Storage, versions, art, scenarios, parked games, import and export. |

### Where to start reading

1. `src/ccg/CreatorDoc.kt`: what a game is.
2. `src/ccg/Ast.kt` and `src/ccg/Expr.kt`: what cards can say.
3. `src/ccg/Step.kt`: how a game is run.
4. `src/ui/PlaySession.kt`: how a game in progress is kept.
5. `test/CoreTest.kt`: the engine's behaviour, one check at a time.
