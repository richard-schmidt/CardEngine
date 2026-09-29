#!/data/data/com.termux/files/usr/bin/bash
# Host-JVM checks for CardEngine v3. Pure Kotlin -- no android.*, no coroutines
# library (only the `suspend` keyword).
#   1. core guard   : src/ccg/ compiles against kotlin-stdlib.jar ALONE.
#   2. ui-logic guard: src/ui/ compiles against stdlib + the core ALONE -- it
#      must stay free of Compose and android.*, which is what lets the Creator's
#      decision logic be tested here at all.
#   3. the suite: test/CoreTest.kt (engine) + test/UiTest.kt (that logic).
set -e
cd "$(dirname "$0")"

STDLIB="$PREFIX/opt/kotlin/lib/kotlin-stdlib.jar"
CORE_SRC=$(find src/ccg -name '*.kt')
UI_SRC=$(find src/ui -name '*.kt')

echo "== Core guard: engine compiles against stdlib only =="
rm -rf out-core; mkdir -p out-core
kotlinc -jvm-target 21 -cp "$STDLIB" -d out-core $CORE_SRC

# 1b. data-only AST guard. The effect language's files hold DATA; what a
#     node MEANS in a running game lives in src/ccg/Eval.kt. A code line in one
#     of these files naming the running state is evaluation creeping back in.
#     Comments are skipped: a doc may say where a node is read.
echo "== AST guard: the language files never name GameState or EvalContext =="
astleak=$(python3 - <<'PYEOF'
import re
for f in ["Ast", "Expr", "Static", "CreatorDoc", "Duration", "Zones"]:
    path = f"src/ccg/{f}.kt"
    inblock = False
    for n, line in enumerate(open(path), 1):
        code = line
        if inblock:
            if "*/" not in code: continue
            code = code.split("*/", 1)[1]; inblock = False
        code = re.sub(r"/\*.*?\*/", "", code)
        if "/*" in code:
            code = code.split("/*", 1)[0]; inblock = True
        code = code.split("//", 1)[0]
        if re.search(r"\b(GameState|EvalContext)\b", code):
            print(f"{path}:{n}: {line.strip()}")
PYEOF
)
if [ -n "$astleak" ]; then
  echo "$astleak"
  echo "FAIL: evaluation belongs in src/ccg/Eval.kt, not in the AST files."
  exit 1
fi

echo "== UI-logic guard: src/ui compiles against stdlib + core only =="
rm -rf out-ui; mkdir -p out-ui
kotlinc -jvm-target 21 -cp "$STDLIB:out-core" -d out-ui $UI_SRC

# 3. accordion guard. `CgGroup` inside a `CgGroup` squashes its rows. A
#    nested section must become a navigable destination or a sheet instead.
echo "== Accordion guard: no CgGroup nested inside a CgGroup =="
nested=$(python3 - <<'PYEOF'
import re, glob, sys
bad = []
for path in glob.glob("src/com/ccg/*.kt"):
    depth = 0            # how many CgGroup bodies we are inside
    stack = []           # brace depth at which each open CgGroup body started
    braces = 0
    for n, line in enumerate(open(path), 1):
        if re.search(r'\bCgGroup\s*\(', line):
            if stack:
                bad.append(f"{path}:{n}: CgGroup nested inside CgGroup")
            stack.append(braces)
        braces += line.count("{") - line.count("}")
        while stack and braces <= stack[-1]:
            stack.pop()
print("\n".join(bad))
PYEOF
)
if [ -n "$nested" ]; then
  echo "$nested"
  echo "FAIL: nested CgGroup -- use a destination or a sheet, not a second accordion."
  exit 1
fi

# 3b. orphaned-KDoc guard. Kotlin attaches only the LAST doc comment to a
#     declaration, so a KDoc directly followed by another KDoc documents
#     nothing: the IDE never shows it and it drifts. It happens whenever a
#     declaration is inserted between a doc and its owner.
echo "== Orphaned-KDoc guard: no doc comment stacked on another =="
stacked=$(python3 - <<'PYEOF'
import re, glob
for path in sorted(glob.glob("src/**/*.kt", recursive=True)):
    text = open(path).read()
    for m in re.finditer(r'\*/[ \t]*\n(?:[ \t]*\n)*[ \t]*/\*\*', text):
        print(f"{path}:{text[:m.start()].count(chr(10)) + 1}: a KDoc stacked on another -- move it to its declaration")
PYEOF
)
if [ -n "$stacked" ]; then
  echo "$stacked"
  echo "FAIL: orphaned KDoc."
  exit 1
fi

# 4. unreachable-state guard. Plumbing with no affordance -- state the game
#    honours but no control can change -- is invisible to the suites: src/ccg
#    and src/ui are covered, and the missing inch is always the last one, in
#    Compose. Its mechanical signature: a `by mutableStateOf` field on the
#    ViewModel that nothing ever ASSIGNS outside its own reset.
echo "== Unreachable-state guard: every ViewModel state field has a writer =="
orphans=$(python3 - <<'PYEOF'
import re, glob
decl = re.compile(r'^\s*(?:var|val)\s+(\w+)\s+by\s+mutableStateOf', re.M)
bad = []
for path in glob.glob("src/com/ccg/*.kt"):
    text = open(path).read()
    if "class CreatorViewModel" not in text:
        continue
    for name in decl.findall(text):
        # THE distinction that matters: the ViewModel's own resets assign the
        # BARE name (`hotP0Deck = 0`), while an affordance -- a button, a chip,
        # a screen -- always writes through the instance (`vm.hotP0Deck = ...`).
        # So "is there a way for a user to change this?" is exactly "does any
        # `vm.<name> =` exist?".
        writers = 0
        for other in glob.glob("src/com/ccg/*.kt"):
            body = open(other).read()
            writers += len(re.findall(rf'\bvm\.{re.escape(name)}\s*(?:=[^=]|\+\+|--)', body))
        # A field may also be reachable through a METHOD (`vm.loadGame(...)`,
        # `vm.updateFace {}`) rather than a direct write. So the orphan test is
        # narrower: every assignment sits inside a reset/load, i.e. the field is only ever
        # SEEDED, never changed by anything a user does.
        for m in re.finditer(rf'^\s*{re.escape(name)}\s*(?:=[^=]|\+\+|--)', text, re.M):
            fn = None
            for f in re.finditer(r'^\s*(?:private\s+)?fun\s+(\w+)', text[:m.start()], re.M):
                fn = f.group(1)
            if fn is None or not re.match(r'^(reset|load|restore|revert)', fn):
                writers += 1
        if writers == 0:
            bad.append(f"  {name}: only ever seeded in a reset -- nothing a user does can change it")
print("\n".join(bad))
PYEOF
)
if [ -n "$orphans" ]; then
  echo "$orphans"
  echo "FAIL: ViewModel state with no way to change it -- plumbing without an affordance."
  echo "      Either wire a control, or delete the field."
  exit 1
fi

# 7. unreachable-CONTENT guard. Guard 4's failure for content: a game that
#    ships in the APK with no screen that opens it. Content in the APK proves
#    it COMPILED, not that it is REACHABLE. The bundles are JSON listed in
#    `content/index.json`, so each list in it must be read by the Compose
#    layer.
echo "== Unreachable-content guard: every list in content/index.json has an install path =="
unreachable=$(python3 - <<'PYEOF_INNER'
import glob, json, os, re
index = json.load(open("content/index.json"))
ui = "".join(open(p).read() for p in glob.glob("src/com/ccg/*.kt"))
bad = [f"content/index.json:{key}" for key in index if not re.search(r'\bBundled\.' + key + r'\b', ui)]
bad += [f"content/index.json:{key}: {name}.json is missing" for key, names in index.items() for name in names if not os.path.exists(f"content/{name}.json")]
print("\n".join(bad))
PYEOF_INNER
)
if [ -n "$unreachable" ]; then
  echo "FAIL: bundled content shipped in the APK but reachable from no screen:"
  echo "$unreachable" | sed 's/^/  /'
  echo "  Read the list as Bundled.<key> in Creator.kt, or delete it."
  exit 1
fi
echo "  ok -- every bundled list is read by the Creator"

# 8. one-build-path guard. `GameDoc.rules()` and `CardDoc.build()`
#    skip the checking, so a game built through them plays with an unknown
#    field or zone. They are `internal` to the core, but the APK compiles
#    src/com/ccg into the SAME module as src/ccg, where `internal` hides
#    nothing -- so the rule is enforced here: outside the core, games are
#    built only by `compile()`, and played only through `runnable`/`playable()`.
echo "== One-build-path guard: only the core calls rules() / build() =="
bypass=$(command grep -rnE '\.(rules|build)\(' src/com src/ui agent --include=*.kt || true)
if [ -n "$bypass" ]; then
  echo "FAIL: a game or card built outside GameDoc.compile():"
  echo "$bypass" | sed 's/^/  /'
  echo "  Use compile().runnable to play, compile().rules to display."
  exit 1
fi
echo "  ok -- nothing outside src/ccg builds around the compiler"

# 9. one-way-to-run guard. A game runs through `ccg.step` (or
#    `playOut`/`playToEnd`, which are built on it): a client that constructs
#    an Engine itself skips the forced-question rule and the view, and is a
#    second driver to keep in step. Same-module `internal` hides nothing, so
#    the rule is enforced here.
echo "== One-way-to-run guard: only the core constructs an Engine =="
drivers=$(command grep -rnE '\bEngine\(|\.playGame\(' src/com src/ui agent --include=*.kt | command grep -vE '^[^:]+:[0-9]+:\s*(//|\*|/\*)' || true)
if [ -n "$drivers" ]; then
  echo "FAIL: a game driven around ccg.step:"
  echo "$drivers" | sed 's/^/  /'
  echo "  Use step(rules, Run(...)) / next(answer), or playToEnd for a pilot that never suspends."
  exit 1
fi
echo "  ok -- every client runs games through step"

# The bundled content is JSON, read by `ccg.Bundled` as a classpath
# resource. out-content is the classpath root that carries it.
echo "== Content: content/ onto the test classpath =="
rm -rf out-content; mkdir -p out-content; cp -r content out-content/

# Carry any Creator-exported game from shared storage into ~/cge-test. Termux's
# home is drwx------ and uid-isolated, so the app's file picker cannot write
# there and this is the only way authored content reaches the suite. Never
# fatal: a missing shared-storage folder must not fail the build.
echo "== Syncing authored games (Creator exports) =="
bash "$(dirname "$0")/sync-authored.sh" || true

echo "== Compiling tests against the guarded classes (host JVM) =="
rm -rf out-test; mkdir -p out-test
# Only the tests, against the classes the guards above just produced: the
# tests use `internal` members, and `-Xfriend-paths` grants that across the
# module boundary. So the guards must stay ABOVE this step.
# GLOBBED, not listed: a new test file that silently does not run would leave
# the suite reporting a confident green.
kotlinc -jvm-target 21 -cp "$STDLIB:out-core:out-ui:out-content" -Xfriend-paths=out-core,out-ui,out-content \
  -d out-test $(ls test/*.kt)

echo "== Running =="
java -cp "$STDLIB:out-core:out-ui:out-content:out-test" ccg.CoreTestKt
