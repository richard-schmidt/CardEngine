# Godot toolchain scripts

The reproducible way to get a Godot export template for the port probe.

They are **not** part of any build. Nothing in `build.sh` or `test.sh` runs
them.

| script | what it does |
|---|---|
| `fetch_templates.py` | Lists what is inside the 1.2 GB export-templates tpz **without downloading it**, by range-fetching the zip's central directory over HTTP. Use it to check sizes and entry names before pulling anything |
| `pull_android_templates.py` | Pulls only the entries listed in its `WANT` constant out of that tpz (currently `android_debug.apk` and `version.txt`, ~120 MB instead of 1220 MB) and verifies each against the CRC32 and uncompressed size recorded in the archive's own central directory. It exits non-zero on a mismatch instead of leaving a corrupt template behind |

    python3 godot-poc/toolchain/fetch_templates.py
    python3 godot-poc/toolchain/pull_android_templates.py ~/.local/share/godot/export_templates/4.7.stable

Both hard-code the 4.7-stable URL. Bump it in the `URL` constant for another
version; the release asset naming has been stable across 4.x.

The Godot binaries themselves (~135 MB each) are not in git. Godot's **editor
mode** currently crashes in every Termux runtime tried, so these scripts stage
a route that does not yet run to completion on-device.
