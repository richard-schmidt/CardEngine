#!/data/data/com.termux/files/usr/bin/bash
# Compile the headless agent surface. Separate from build.sh on purpose: none
# of this belongs in the APK, and build.sh compiles `find src -name "*.kt"`.
set -eu
cd "$(dirname "$0")/.."
S="$PREFIX/opt/kotlin/lib/kotlin-stdlib.jar"
OUT="agent/out"
mkdir -p "$OUT"

# The core and the decision layer, exactly as test.sh builds them -- same
# sources, same guards, so the agent cannot be playing a different engine than
# the suite tests.
rm -rf "$OUT"/{core,ui,content,cli}
kotlinc -jvm-target 21 -cp "$S" -d "$OUT/core" $(ls src/ccg/*.kt) 2>&1 | grep -E "error:" && exit 1
kotlinc -jvm-target 21 -cp "$S:$OUT/core" -d "$OUT/ui" $(ls src/ui/*.kt) 2>&1 | grep -E "error:" && exit 1
# The bundled content is JSON, read as a classpath resource: `content/`
# under a classpath root, so every wrapper below keeps "$OUT/content" on it.
mkdir -p "$OUT/content" && cp -r content "$OUT/content/"
# What games exist -- shared by every tool below, so `cge` and `balance` can
# never disagree about what a game name (or a fork id) resolves to.
rm -rf "$OUT/catalogue"
kotlinc -jvm-target 21 -cp "$S:$OUT/core:$OUT/content" -d "$OUT/catalogue" \
  agent/Catalogue.kt 2>&1 | grep -E "error:" && exit 1

kotlinc -jvm-target 21 -cp "$S:$OUT/core:$OUT/ui:$OUT/content:$OUT/catalogue" -d "$OUT/cli" \
  agent/CgeAgent.kt 2>&1 | grep -E "error:" && exit 1

# The measurement tools. `Balance.kt` is the matchup matrix; `SeatProbe.kt`
# is a one-variable-at-a-time seat-advantage harness.
rm -rf "$OUT"/{balance,probe,playmix,dumpdeadlock,pool,dump,economy}
kotlinc -jvm-target 21 -cp "$S:$OUT/core:$OUT/ui:$OUT/content:$OUT/catalogue" -d "$OUT/balance" \
  agent/Balance.kt 2>&1 | grep -E "error:" && exit 1
kotlinc -jvm-target 21 -cp "$S:$OUT/core:$OUT/ui:$OUT/content:$OUT/catalogue" -d "$OUT/probe" \
  agent/SeatProbe.kt 2>&1 | grep -E "error:" && exit 1
# `PlayMix.kt`: what a pilot actually PLAYS, by card type -- the question a
# win-rate matrix cannot answer and deck composition answers wrongly.
kotlinc -jvm-target 21 -cp "$S:$OUT/core:$OUT/ui:$OUT/content:$OUT/catalogue" -d "$OUT/playmix" \
  agent/PlayMix.kt 2>&1 | grep -E "error:" && exit 1
# Finds one deadlocked (fixed-point) game and prints the position,
# rather than another aggregate draw-rate number.
kotlinc -jvm-target 21 -cp "$S:$OUT/core:$OUT/ui:$OUT/content:$OUT/catalogue" -d "$OUT/dumpdeadlock" \
  agent/DumpDeadlock.kt 2>&1 | grep -E "error:" && exit 1
# The pool instrument at the shell. Renders `ccgui.sections()`
# and formats nothing itself, so it cannot drift from the Creator's Pool socket.
kotlinc -jvm-target 21 -cp "$S:$OUT/core:$OUT/ui:$OUT/content:$OUT/catalogue" -d "$OUT/pool" \
  agent/Pool.kt 2>&1 | grep -E "error:" && exit 1
# The Kotlin bundle as JSON, so `dump core` vs a Creator export is a
# plain `diff` rather than a hand comparison of Kotlin against JSON.
kotlinc -jvm-target 21 -cp "$S:$OUT/core:$OUT/ui:$OUT/content:$OUT/catalogue" -d "$OUT/dump" \
  agent/Dump.kt 2>&1 | grep -E "error:" && exit 1
# Is the economy starving anyone, and where. `balance` cannot answer it
# -- doubling the Core's ramp moves nothing, because no policy values mana.
kotlinc -jvm-target 21 -cp "$S:$OUT/core:$OUT/ui:$OUT/content:$OUT/catalogue" -d "$OUT/economy" \
  agent/Economy.kt 2>&1 | grep -E "error:" && exit 1

cat > agent/cge <<SH
#!/data/data/com.termux/files/usr/bin/bash
S="\$PREFIX/opt/kotlin/lib/kotlin-stdlib.jar"
D="$(pwd)/agent/out"
exec java -cp "\$S:\$D/core:\$D/ui:\$D/content:\$D/catalogue:\$D/cli" cge.CgeAgentKt "\$@"
SH
chmod +x agent/cge

# Wrappers, so a measurement is a command rather than a remembered classpath.
for tool in balance:Balance probe:SeatProbe playmix:PlayMix dumpdeadlock:DumpDeadlock pool:Pool dump:Dump economy:Economy; do
  name="${tool%%:*}"; cls="${tool##*:}"
  cat > "agent/$name" <<SH
#!/data/data/com.termux/files/usr/bin/bash
S="\$PREFIX/opt/kotlin/lib/kotlin-stdlib.jar"
D="$(pwd)/agent/out"
exec java -cp "\$S:\$D/core:\$D/ui:\$D/content:\$D/catalogue:\$D/$name" cge.${cls}Kt "\$@"
SH
  chmod +x "agent/$name"
done

echo "built: agent/cge  agent/balance  agent/probe  agent/playmix  agent/dumpdeadlock  agent/pool  agent/dump  agent/economy"
