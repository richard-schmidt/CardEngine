#!/data/data/com.termux/files/usr/bin/bash
# Push the PoC's Godot sources from this repo onto shared storage.
#
# WHY THIS EXISTS: same wall as sync-authored.sh, in the other direction.
# Termux's home is drwx------, so the Godot editor can NEVER open this repo.
# The Godot project has to live under /storage/emulated/0/, but the source of
# truth stays here, in git, alongside the engine it is a probe for.
#
# Copies, never moves. Does NOT touch .godot/ (the editor's own import cache)
# and does NOT overwrite export_presets.cfg if it already exists on the far
# side -- the editor rewrites that file with resolved defaults the first time
# it opens the project, and clobbering it would undo that.
set -u

SRC="$(cd "$(dirname "$0")" && pwd)/project"
DEST="/storage/emulated/0/Godot/cge-poc"

mkdir -p "$DEST"
for f in project.godot main.gd main.tscn; do
  cp -v "$SRC/$f" "$DEST/$f"
done

if [ -f "$DEST/export_presets.cfg" ]; then
  echo "keeping existing $DEST/export_presets.cfg (editor may have rewritten it)"
else
  cp -v "$SRC/export_presets.cfg" "$DEST/export_presets.cfg"
fi

echo
echo "synced to $DEST"
