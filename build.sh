#!/data/data/com.termux/files/usr/bin/bash
set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

# Every path and secret below can be overridden from the environment -- that is
# how the same script runs in CI (.github/workflows/apk.yml) as on the phone.
ANDROID_JAR="${ANDROID_JAR:-$HOME/android-sdk/platforms/android-34/android.jar}"
OUT="$PROJECT_DIR/out"
APP_NAME="app"
KEYSTORE="${KEYSTORE:-$PROJECT_DIR/debug.keystore}"
KEYSTORE_PASS="${KEYSTORE_PASS:-android}"
KEY_PASS="${KEY_PASS:-$KEYSTORE_PASS}"
export KEYSTORE_PASS KEY_PASS   # apksigner reads them via env:, off the command line

# Keystore lives at the project root, NOT inside $OUT -- $OUT gets wiped on every
# build, and a build script that regenerates its signing key each run silently
# breaks in-place app updates (Android refuses to install over a different-signed
# APK).
# In CI a missing keystore is an error, never a fresh key: an APK signed with a
# throwaway key cannot be installed over the one already on the phone.
if [ ! -f "$KEYSTORE" ] && [ -n "$CI" ]; then
  echo "FAIL: no keystore at $KEYSTORE (CI never generates one)."
  exit 1
fi
if [ ! -f "$KEYSTORE" ]; then
  echo "== Generating persistent debug keystore =="
  keytool -genkeypair -v -keystore "$KEYSTORE" -storepass android -alias androiddebugkey \
    -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US"
fi

# A literal "--" inside an XML <!-- --> comment fails aapt2 with a bare "not
# well formed (invalid token)" and no file:line -- check for it before any
# resolve/compile step runs.
python3 "$PROJECT_DIR/check_xml_comments.py" "$PROJECT_DIR"

# ---- External dependencies (Gradle-as-resolver-only) ----
# Gradle's ONLY job here is resolving Maven/JitPack coordinates (incl. transitive
# deps) to real .aar/.jar files -- no Android Gradle Plugin, no android {} block.
# Packaging is the hand-rolled aapt2/d8/ecj pipeline below.
DEPS_RAW="$PROJECT_DIR/deps/raw"
DEPS_EXTRACTED="$PROJECT_DIR/deps/extracted"

if [ -f "$PROJECT_DIR/build.gradle.kts" ]; then
  echo "== Resolving external dependencies (Gradle) =="
  gradle resolveDeps --console=plain -q

  rm -rf "$DEPS_EXTRACTED"
  mkdir -p "$DEPS_EXTRACTED"
  for artifact in "$DEPS_RAW"/*; do
    [ -e "$artifact" ] || continue
    name="$(basename "$artifact")"
    case "$name" in
      *.aar)
        # An AAR is just a zip: classes.jar + (maybe) res/ + jni/ + its own
        # manifest. The manifest is merged later (see "Merging manifests"
        # below) -- a deliberately partial merge, not full AGP parity.
        dest="$DEPS_EXTRACTED/${name%.aar}"
        mkdir -p "$dest"
        unzip -q -o "$artifact" -d "$dest"
        ;;
      *.jar)
        dest="$DEPS_EXTRACTED/${name%.jar}"
        mkdir -p "$dest"
        cp "$artifact" "$dest/classes.jar"
        ;;
    esac
  done
fi

EXTRA_JARS=()
EXTRA_RES_DIRS=()
EXTRA_PACKAGES=()
EXTRA_JNI_DIRS=()
EXTRA_MANIFESTS=()
if [ -d "$DEPS_EXTRACTED" ]; then
  for dir in "$DEPS_EXTRACTED"/*/; do
    [ -f "${dir}classes.jar" ] && EXTRA_JARS+=("${dir}classes.jar")
    if [ -d "${dir}res" ] && [ -n "$(find "${dir}res" -type f 2>/dev/null)" ]; then
      EXTRA_RES_DIRS+=("${dir}res")
    fi
    # An AAR's jni/<abi>/*.so maps 1:1 onto the APK's own lib/<abi>/*.so --
    # same directory shape, just renamed at the top level. Collected here,
    # staged and zipped into place in the Packaging step below.
    if [ -d "${dir}jni" ] && [ -n "$(find "${dir}jni" -name '*.so' 2>/dev/null)" ]; then
      EXTRA_JNI_DIRS+=("${dir}jni")
    fi
    # A library's own precompiled classes.jar was built expecting its own
    # per-package R class (e.g. a custom View reading its own
    # com.example.lib.R.styleable.* directly, baked in at compile time) --
    # without generating one, that reference is a real NoClassDefFoundError
    # at runtime even though everything links and dexes cleanly.
    # --extra-packages (below) is what AGP itself uses to solve this for
    # library modules.
    if [ -f "${dir}AndroidManifest.xml" ]; then
      pkg="$(grep -o 'package="[^"]*"' "${dir}AndroidManifest.xml" | head -1 | sed 's/package="//; s/"$//')"
      [ -n "$pkg" ] && EXTRA_PACKAGES+=("$pkg")
      EXTRA_MANIFESTS+=("${dir}AndroidManifest.xml")
    fi
  done
fi

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/apk" "$OUT/res"

echo "== Compiling resources =="
aapt2 compile --dir res -o "$OUT/res/compiled.zip"
RES_ZIPS=("$OUT/res/compiled.zip")
i=0
for resdir in "${EXTRA_RES_DIRS[@]}"; do
  i=$((i + 1))
  # Merged flat into the app's own package (same approach AAPT1 used) rather
  # than linked as a separate static library with its own package id -- fine
  # as long as a dependency's resource names never collide with the app's
  # own or each other's. A genuine collision fails aapt2 link hard and
  # clearly (exit 1, no APK produced) -- confirmed behavior, not a guess.
  aapt2 compile --dir "$resdir" -o "$OUT/res/dep-$i.zip"
  RES_ZIPS+=("$OUT/res/dep-$i.zip")
done

echo "== Merging manifests =="
# Simplified manifest merge (see merge_manifest.py's own docstring for what
# this does and doesn't cover): each dependency AAR's <application> children
# (providers/services/etc.) and top-level <uses-permission> elements get
# appended into a copy of this app's own manifest, substituting
# ${applicationId} placeholders -- e.g. androidx.startup's own <provider
# android:authorities="${applicationId}.androidx-startup"> needs this to
# resolve to a real, valid authority string, not the literal placeholder.
MERGED_MANIFEST="$OUT/AndroidManifest.merged.xml"
if [ ${#EXTRA_MANIFESTS[@]} -gt 0 ]; then
  manifest_list_file="$OUT/dep-manifests.txt"
  printf '%s\n' "${EXTRA_MANIFESTS[@]}" > "$manifest_list_file"
  python3 merge_manifest.py AndroidManifest.xml "$manifest_list_file" "$MERGED_MANIFEST"
else
  cp AndroidManifest.xml "$MERGED_MANIFEST"
fi

echo "== Linking resources =="
EXTRA_PACKAGES_ARG=()
for pkg in "${EXTRA_PACKAGES[@]}"; do
  # Repeated per-package, NOT comma-joined -- aapt2 takes a single package
  # name per --extra-packages occurrence; a joined "a,b" string is parsed as
  # one literal (invalid) package name instead of two.
  EXTRA_PACKAGES_ARG+=(--extra-packages "$pkg")
done
aapt2 link -o "$OUT/apk/base.apk" \
  --manifest "$MERGED_MANIFEST" \
  -I "$ANDROID_JAR" \
  --java "$OUT/gen" \
  --auto-add-overlay \
  "${EXTRA_PACKAGES_ARG[@]}" \
  "${RES_ZIPS[@]}"

echo "== Compiling Java (R.java only) =="
# Bypassing the `ecj` wrapper script: it hardcodes Termux's own bundled (older,
# pre-API-30) android.jar on the classpath ahead of ours, which shadows newer
# platform APIs (anything added after that stub's API level fails to compile
# with a confusing "method undefined" even though it's really there). Invoking
# the underlying compiler directly instead, with only our own classpath.
unset JAVA_HOME
EXTRA_CP=""
for jar in "${EXTRA_JARS[@]}"; do
  EXTRA_CP="$EXTRA_CP:$jar"
done
# ecj under dalvikvm on the phone; the host JDK's javac anywhere else (CI).
compile_java() {  # compile_java <classpath> <sources...>
  local cp="$1"; shift
  if command -v dalvikvm >/dev/null 2>&1; then
    dalvikvm -Xmx256m \
      -Xcompiler-option --compiler-filter=speed \
      -cp /data/data/com.termux/files/usr/share/dex/ecj.jar \
      org.eclipse.jdt.internal.compiler.batch.Main \
      -proc:none -7 \
      -classpath "$cp" \
      -d "$OUT/classes" \
      "$@"
  else
    javac -nowarn -proc:none -source 8 -target 8 -Xlint:-options \
      -classpath "$cp" -d "$OUT/classes" "$@"
  fi
}
compile_java "$ANDROID_JAR$EXTRA_CP" $(find "$OUT/gen" src -name "*.java")

# Import guard: a cross-package symbol used without its import costs a full
# build to discover, and there is no IDE here to say so sooner. This runs in
# seconds, before the expensive step.
#
# Coverage: every top-level declaration ccg/ccgui export -- types, functions,
# vals -- in BOTH consuming source trees. Package-aware: a file in `ccgui`
# needs no `ccgui` import.
#
# Containment, because most functions are LOWERCASE (`tokens`, `newGame`,
# `countOf`) and collide with local vals and parameters constantly: the local
# exclusion list is deliberately GENEROUS -- any val/var/fun/parameter name in
# the file suppresses that symbol. That over-excludes, so the guard MISSES some
# real cases. That is the correct direction: a guard with false positives gets
# deleted, and a deleted guard catches nothing at all.
echo "== Import guard: cross-package symbols have imports =="
missing=$(python3 - <<'PYEOF'
import re, glob

def top_level_decls(paths):
    """Everything a package exports at the top level: types, funs, vals."""
    names = set()
    for f in glob.glob(paths):
        text = open(f).read()
        names |= set(re.findall(r'^(?:public )?(?:data |sealed |value |enum )?(?:class|interface|object)\s+(\w+)', text, re.M))
        names |= set(re.findall(r'^(?:public )?(?:suspend )?fun\s+(?:<[^>]+>\s*)?(\w+)\s*\(', text, re.M))
        names |= set(re.findall(r'^(?:public )?(?:const )?va[lr]\s+(\w+)', text, re.M))
    # `internal`/`private` are never importable from another module anyway, and
    # the regexes above skip them by not matching those prefixes.
    return names

def ext_decls(paths):
    r"""EXTENSION functions -- `fun GameState.canAttackFace(...)`.

    They need their own pass because the guard was blind to them TWICE, in one
    round, at a cost of a four-minute build each time:

      1. `top_level_decls`' function regex is `fun\s+(\w+)\s*\(`, which cannot
         match a receiver -- `GameState` matches `\w+` and then the next char is
         `.`, not `(`. So the name never entered the exported set at all.
      2. Even if it had, the call site `state.canAttackFace(...)` is DOTTED, and
         the generous local-exclusion below drops every `.name` as member
         access. Correct for `.copy`/`.map`; wrong for exactly this case.

    So extensions are matched on declaration here, and checked below against
    dotted CALLS rather than bare identifiers -- the one place where a leading
    dot is evidence FOR a cross-package symbol rather than against it."""
    names = set()
    for f in glob.glob(paths):
        text = open(f).read()
        names |= set(re.findall(
            r'^(?:public )?(?:suspend )?fun\s+(?:<[^>]+>\s*)?\w+(?:<[^>]+>)?\.(\w+)\s*\(',
            text, re.M))
    return names

exported = {
    "ccg": top_level_decls("src/ccg/*.kt"),
    "ccgui": top_level_decls("src/ui/*.kt"),
}
def member_names(*globs):
    """Every INDENTED `fun name(` -- i.e. a method on some class, anywhere.

    THE CONTAINMENT for the extension check, and it is not hypothetical: adding
    that check immediately flagged `.layoutFor()` in Hotseat.kt, which compiles
    fine. There are two of them -- a MEMBER `Rules.layoutFor` (Rules.kt) and an
    unrelated top-level extension `RulesDoc.layoutFor` (CreatorDoc.kt) -- and
    the call resolves to the member, which needs no import.

    A name that is BOTH a member somewhere and an exported extension cannot be
    told apart by grep, so it is dropped. Same direction as every other
    containment here: miss a real case rather than cry wolf, because a guard
    that cries wolf gets deleted and then catches nothing at all."""
    names = set()
    for g in globs:
        for f in glob.glob(g):
            names |= set(re.findall(r'^\s+(?:public |private |internal |protected )?(?:suspend |inline |open |override )*fun\s+(?:<[^>]+>\s*)?(\w+)\s*\(', open(f).read(), re.M))
    return names

_members = member_names("src/ccg/*.kt", "src/ui/*.kt", "src/com/ccg/*.kt")
exported_ext = {
    "ccg": ext_decls("src/ccg/*.kt") - _members,
    "ccgui": ext_decls("src/ui/*.kt") - _members,
}

# Which package each consuming tree is IN -- a file does not import its own.
trees = {"src/com/ccg/*.kt": None, "src/ui/*.kt": "ccgui"}

bad = []
for pattern, own in trees.items():
    for f in glob.glob(pattern):
        text = open(f).read()
        if re.search(r'^import (?:ccg|ccgui)\.\*', text, re.M):
            continue                       # wildcard: cannot reason, stay quiet
        imported = set(re.findall(r'^import (?:ccg|ccgui)\.(\w+)', text, re.M))
        body = re.sub(r'^import .*$', '', text, flags=re.M)
        body = re.sub(r'/\*.*?\*/', '', body, flags=re.S)
        body = re.sub(r'//[^\n]*', '', body)
        body = re.sub(r'"""(?:.|\n)*?"""', '""', body)
        body = re.sub(r'"(?:\\.|[^"\\\n])*"', '""', body)
        # GENEROUS local exclusion: any name declared or bound in this file.
        local = set(re.findall(r'(?:class|interface|object|fun|va[lr])\s+(\w+)', body))
        local |= set(re.findall(r'(\w+)\s*:\s*[A-Z]', body))      # parameters
        local |= set(re.findall(r'\.(\w+)', body))                 # member access
        used = set(re.findall(r'(?<![\w.])(\w+)(?![\w])', body))
        for pkg, names in exported.items():
            if pkg == own:
                continue                   # same package: no import needed
            for sym in sorted(used & names):
                if sym in imported or sym in local:
                    continue
                # The coverage line, a real limit: single-word lowercase
                # exports (ccg does export `val x`) collide with ordinary local
                # names that no regex can reliably see inside lambda parameters.
                #
                # So: every CAPITALISED export is covered (all types, and any
                # capitalised fun/val), plus every multi-word camelCase one --
                # `ruleboxOf`, `legalActionsFor`, `combatBoardTargets`. Those do
                # not collide with locals in practice.
                #
                # NOT covered, deliberately: single-word lowercase exports
                # (`lit`, `x`, `tokens`, `shuffle`). Grep cannot tell those
                # from a variable, and a guard that cries wolf gets deleted.
                distinctive = sym[0].isupper() or any(c.isupper() for c in sym[1:])
                if not distinctive:
                    continue
                bad.append(f"  {f}: {sym} (from {pkg}) used with no import")
        # EXTENSION FUNCTIONS, checked on dotted calls. Same containment as
        # above -- multi-word or capitalised only -- and still suppressed by a
        # local declaration of the same name, so a member that happens to share
        # an extension's name stays quiet.
        called = set(re.findall(r'\.(\w+)\s*\(', body))
        declared_here = set(re.findall(r'fun\s+(?:<[^>]+>\s*)?(?:\w+(?:<[^>]+>)?\.)?(\w+)\s*\(', body))
        for pkg, names in exported_ext.items():
            if pkg == own:
                continue
            for sym in sorted(called & names):
                if sym in imported or sym in declared_here:
                    continue
                if not (sym[0].isupper() or any(c.isupper() for c in sym[1:])):
                    continue
                bad.append(f"  {f}: .{sym}() (extension from {pkg}) used with no import")
print("\n".join(bad))
PYEOF
)
if [ -n "$missing" ]; then
  echo "$missing"
  echo "FAIL: a cross-package symbol is used without importing it."
  exit 1
fi

echo "== Compiling Kotlin (Compose) =="
# kotlinc, with the Compose compiler plugin Termux's own kotlin package
# bundles directly, compiling against R.java's already-compiled output
# ($OUT/classes, from the step above) plus every resolved AndroidX/Compose
# jar. Output goes to a separate directory -- ecj and kotlinc are two
# independent compiler invocations with no joint-compilation step (unlike
# Gradle's kotlin-android plugin).
KOTLIN_PLUGIN="${KOTLIN_PLUGIN:-$PREFIX/opt/kotlin/lib/compose-compiler-plugin.jar}"
mkdir -p "$OUT/classes-kotlin"
KOTLIN_SOURCES=$(find src -name "*.kt")
if [ -n "$KOTLIN_SOURCES" ]; then
  kotlinc -Xplugin="$KOTLIN_PLUGIN" \
    -jvm-target 11 \
    -cp "$ANDROID_JAR:$OUT/classes$EXTRA_CP" \
    -d "$OUT/classes-kotlin" \
    $KOTLIN_SOURCES
fi

echo "== Dexing =="
# The dependency's own bytecode has to be dexed alongside ours too. --min-api
# must track AndroidManifest.xml's own minSdkVersion -- keep them in sync by
# hand, this doesn't read the manifest for you.
d8 --output "$OUT/apk" --min-api 23 $(find "$OUT/classes" "$OUT/classes-kotlin" -name "*.class") "${EXTRA_JARS[@]}"

echo "== Packaging =="
cd "$OUT/apk"
cp base.apk "$APP_NAME-unsigned.apk"
# A large enough dependency tree pushes total method count past the
# single-dex 65536 limit -- d8 then multidexes automatically (classes.dex,
# classes2.dex, ...), and a packaging step that only zips classes.dex will
# SILENTLY drop the rest: the build succeeds, apksigner verify passes, the
# app installs and launches, and it only fails later as a
# ClassNotFoundException/NoClassDefFoundError for whichever class happened to
# land in a dropped file. Hence the glob, not a literal "classes.dex". ART on
# minSdk 21+ picks up every classesN.dex at the APK root natively.
zip -j "$APP_NAME-unsigned.apk" classes*.dex
# The bundled games and the combat presets: JSON at the APK root, where
# `ccg.Bundled` reads them as classpath resources. Without them the app starts
# and every bundled game and preset throws on first use, so check they landed.
(cd "$PROJECT_DIR" && zip -r "$OUT/apk/$APP_NAME-unsigned.apk" content)
unzip -l "$APP_NAME-unsigned.apk" | command grep -q 'content/combat/presets.json' || { echo "FAIL: content/ is not in the APK"; exit 1; }

if [ ${#EXTRA_JNI_DIRS[@]} -gt 0 ]; then
  # Stage into a real lib/<abi>/ tree first -- zip needs the directory
  # structure to exist on disk to preserve it (unlike classes.dex above,
  # which is zipped in flat with -j since it always lives at the APK root).
  LIB_STAGE="$OUT/lib-stage"
  rm -rf "$LIB_STAGE"
  mkdir -p "$LIB_STAGE"
  for jnidir in "${EXTRA_JNI_DIRS[@]}"; do
    cp -r "$jnidir"/. "$LIB_STAGE/"
  done
  mv "$LIB_STAGE" "$OUT/apk/lib"
  cd "$OUT/apk"
  zip -r "$APP_NAME-unsigned.apk" lib
fi

echo "== Signing =="
apksigner sign --ks "$KEYSTORE" --ks-pass env:KEYSTORE_PASS --key-pass env:KEY_PASS \
  ${KEY_ALIAS:+--ks-key-alias "$KEY_ALIAS"} \
  --out "$APP_NAME-signed.apk" "$APP_NAME-unsigned.apk"

echo ""
echo "Built: $OUT/apk/$APP_NAME-signed.apk"
