# Godot proof of concept

A probe, not a port. It answers one question before any rules code is written in
Godot: can a Godot app, built and exported on the same Android phone as the rest of
CardEngine, get a real game bundle from the Kotlin side and draw from it?

It can. The exported APK boots, reads EPR Skirmish over a loopback socket from
Termux, and draws its first two cards from the real data. Nothing of the engine
is ported, and the app is not a game.

## Why Godot was considered

For the feel of play: animation, particles, card physics, sound. The option
studied was a second implementation of the engine in Godot, checked against the
[conformance corpus](../corpus/): a port conforms when it reproduces every state
digest the Kotlin engine records.

The player stays in Compose for now. This folder is kept as the record of what
was verified, and as the starting point if the port is picked up again.

## What is in it

| Path | What |
|---|---|
| `project/` | The Godot 4.7 project: one scene (`main.tscn`) and its script (`main.gd`). |
| `project/export_presets.cfg` | A working Android export preset: arm64-v8a, the repo's debug key, and a local debug template, so no 1.2 GB template download is needed. |
| `bundle-server.py` | A loopback server in Termux that sends a bundle to any client that connects, then closes the connection. |
| `sync-godot-poc.sh` | Copies the project from this repo to shared storage, where the Godot editor can open it. |
| `toolchain/` | Scripts to fetch only the Android export template out of Godot's release archive. See [toolchain/README.md](toolchain/README.md). |

## What the app checks

`main.gd` runs two probes at start-up and prints `[ok]` or `[FAIL]` for each step:

- **Probe A, shared storage.** Read the bundle from
  `/storage/emulated/0/CardEngine/godot-poc/epr-skirmish.json`. On recent Android
  versions scoped storage refuses a plain `.json` without a broad permission, so
  this is expected to fail.
- **Probe B, loopback socket.** Connect to `127.0.0.1:9944` and read the bundle
  from `bundle-server.py`. This needs no storage permission, and it works.

Whichever probe succeeds, the first two cards of the bundle are drawn from its
data.

## Running it

1. On the phone, install the Godot Android editor.
2. In Termux, copy the project to shared storage:

   ```sh
   bash godot-poc/sync-godot-poc.sh     # to /storage/emulated/0/Godot/cge-poc
   ```

   Termux's home folder is private to Termux, so the editor cannot open this repo
   directly. The script never overwrites an `export_presets.cfg` the editor has
   already rewritten.
3. Start the bundle server (it defaults to this repo's `content/epr-skirmish.json`):

   ```sh
   python3 godot-poc/bundle-server.py [bundle.json] [port]
   ```

4. Open `cge-poc` in the Godot editor and run it, or export the APK and install it.
5. To check an exported APK from Termux: `apksigner verify --print-certs` and
   `aapt2 dump badging` on the file.

## What was learned

- **Exports work by hand only.** The Android editor imports, runs and exports the
  project. Godot's linux-arm64 editor crashes in every Termux runtime tried, so
  there is no scripted build: each APK takes a tap in the editor.
- **The storage wall goes both ways.** A Godot app cannot read a plain `.json` from
  shared storage, and Termux cannot write into another app's private folder. Bundles
  travel over the loopback socket (or the system file picker).
- **The seam fits the engine.** A game is a bundle, a seed, deck picks and answers,
  and answers carry references rather than objects. A future Godot client could
  send answers and receive states over the same kind of socket, with the Kotlin
  engine serving them.
- **The risks of a port are known.** It would lose the Kotlin compiler's checks
  (sealed types, exhaustive `when`s), so the corpus would be its only safety net.
  GDScript integers are 64-bit, so the RNG must mask to 32 bits; its test vector is
  in `test/golden/rng.txt`. GDScript's `await` spreads through every caller with no
  compiler to catch a missing one.
