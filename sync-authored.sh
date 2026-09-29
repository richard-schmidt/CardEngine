#!/data/data/com.termux/files/usr/bin/bash
# Pull Creator-exported games from shared storage into ~/cge-test/.
#
# WHY THIS EXISTS: Termux's home is drwx------ and uid-isolated, so Android's
# file picker cannot write into ~/cge-test at all. The app must export to shared
# storage; this carries it the rest of the way.
#
# Safe to run any time. It copies, never moves -- the export stays where you put
# it, so a mistake here cannot lose your only copy.
set -u

DEST="$HOME/cge-test"
mkdir -p "$DEST"

# Where the SAF picker plausibly drops a file. A dedicated folder first, then
# the usual suspects.
SRCS="
/storage/emulated/0/CardEngine
/storage/emulated/0/Download
/storage/emulated/0/Documents
"

found=0
for dir in $SRCS; do
  [ -d "$dir" ] || continue
  # -maxdepth 1: a game is exported as one file, and recursing into Download is
  # how you accidentally scan a few thousand unrelated JSONs.
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    # Only take files that actually look like a GameDoc. Download is full of
    # JSON that is not a game, and a parse failure in the suite should mean
    # "your game is broken", never "you once downloaded a config file".
    # A scenario exported as a conformance case carries a bundle, so
    # it would pass for a game: it goes to cases/, which AuthoredTest replays.
    if head -c 200 "$f" | grep -q '"corpusVersion"'; then
      mkdir -p "$DEST/cases"
      base=$(basename "$f")
      if [ -e "$DEST/cases/$base" ] && [ ! "$f" -nt "$DEST/cases/$base" ]; then
        continue
      fi
      cp -f "$f" "$DEST/cases/$base" && echo "  synced case: $base  (from $dir)" && found=$((found+1))
      continue
    fi
    if head -c 4000 "$f" | grep -q '"cards"' && head -c 4000 "$f" | grep -q '"rules"'; then
      base=$(basename "$f")
      if [ -e "$DEST/$base" ] && [ ! "$f" -nt "$DEST/$base" ]; then
        continue                      # already have it, and it is not newer
      fi
      cp -f "$f" "$DEST/$base" && echo "  synced: $base  (from $dir)" && found=$((found+1))
    fi
  done <<EOT
$(find "$dir" -maxdepth 1 -type f -name '*.json' 2>/dev/null)
EOT
done

if [ "$found" -eq 0 ]; then
  echo "  no new exported games found."
  echo "  Creator -> export -> JSON, save under internal storage, then re-run."
fi
