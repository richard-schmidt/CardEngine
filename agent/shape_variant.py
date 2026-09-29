#!/usr/bin/env python3
"""Build a combat-shape variant of a forked game, for the FRONT/BACK experiment.

WHY THIS IS A SCRIPT AND NOT A `vary` VERB. The named variations are one field
each, deliberately. This is a ruleset TRANSFORM: it rewrites zones, the combat
preset, a type's zone choices and stat fields, and every zone id referenced
anywhere in the document. Forcing that through `vary` would mean building the
general field-path writer, which is out of scope. It is an experiment tool.

What it does NOT capture, stated so no result is over-read:

  * The design says a long-range ship must SIT in BACK. `zoneChoices` is
    per-TYPE, not per-card, so the approximation lets every ship choose either
    zone and gives `reach` to the back-loaded ones instead. It tests "two zones
    plus a range keyword", not "long range must deploy back".
  * "Only BACK blocks for the defender" is not expressible: opposition is a
    same-named-lane check in the engine. That arm needs engine work.
  * Collapsing two combat waves into one CANNOT be throughput-neutral, so the
    collapse is offered under two rules (max and sum) and the geometry arms are
    compared against the collapse arm sharing their rule -- never against the
    two-wave baseline directly.
"""
import json
import sys

LANES = ["van", "core", "rear"]
# van and core are the forward positions, rear the standoff one.
TO_FRONT_BACK = {"van": "front", "core": "front", "rear": "back"}


def remap_ids(node, mapping):
    """Rewrite every zone id ANYWHERE in the document.

    A blind recursive string rewrite, on purpose: zone ids appear in
    `zoneChoices`, in `PermFilter.inZone` inside card effects, and in any
    ChooseMode built by enumerating lanes. There is no generic Effect walk in
    the engine to reuse, and a partial rewrite is worse than none -- a card
    filtering on a zone that no longer exists still LOADS and simply matches
    nothing, so the arm would quietly measure "Enfilade stopped working".
    """
    if isinstance(node, dict):
        return {k: remap_ids(v, mapping) for k, v in node.items()}
    if isinstance(node, list):
        return [remap_ids(v, mapping) for v in node]
    if isinstance(node, str):
        return mapping.get(node, node)
    return node


def ships(doc):
    for s in doc["sets"]:
        for c in s["cards"]:
            f = c["faces"][0]
            if "Ship" in f.get("types", []):
                yield c, f


def collapse(doc, rule):
    """Give every Ship a single `strike` stat, since one step needs one field."""
    for _c, f in ships(doc):
        fl = f.setdefault("fields", {})
        fast, slow = fl.get("fast", 0), fl.get("slow", 0)
        fl["strike"] = max(fast, slow) if rule == "max" else fast + slow
    for t in doc["rules"].get("extraTypes", []):
        if t.get("name") == "Ship" and "strike" not in t.get("fields", []):
            t["fields"] = list(t["fields"]) + ["strike"]
    doc["rules"]["combat"] = {"kind": "preset", "name": "singleStepLanesCore"}


def screen(doc):
    """Switch on the artillery screen: a back line is unreachable while its
    owner still holds the front.

    Without it the standoff zone STRICTLY DOMINATES, and that is measured
    rather than feared: the deck whose ships came out 100% long-range beat the
    most short-ranged deck 76-17. The screen is what gives the forward zone a
    job, and it is the same shape as `laneLockedPlayerTargets`."""
    doc["rules"]["combat"] = {"kind": "preset", "name": "screenedSingleStep"}


def main():
    if len(sys.argv) < 4:
        sys.exit("usage: shape_variant.py <fork.json> <lanes|front_back> <max|sum>")
    path, shape, rule = sys.argv[1], sys.argv[2], sys.argv[3]
    doc = json.load(open(path))

    collapse(doc, rule)
    if len(sys.argv) > 5 and sys.argv[5] == "screen":
        screen(doc)

    zones = doc["rules"]["extraZones"]
    if shape == "lanes":
        # Same three lanes, same capacity -- ONLY the wave structure changes.
        for z in zones:
            z["combatSteps"] = ["strike"]
    elif shape == "front_back":
        doc = remap_ids(doc, TO_FRONT_BACK)
        # Capacity held CONSTANT at 6 a side (was 3 lanes x 2), so the arm
        # cannot be read as "the board got bigger".
        doc["rules"]["extraZones"] = [
            {"id": "front", "scope": "per_player", "maxOccupants": 4, "combatSteps": ["strike"]},
            {"id": "back", "scope": "per_player", "maxOccupants": 2, "combatSteps": ["strike"]},
        ]
        for t in doc["rules"].get("extraTypes", []):
            if t.get("name") == "Ship":
                t["zoneChoices"] = ["front", "back"]
        # Long range = the back-loaded ships the existing stat line already
        # describes, derived from content rather than hand-picked. The ratio
        # matters: at 1.0, 13 of 19 ships come out long-range, which makes
        # reach the default and erases the front/back tension being tested.
        # NB the ratio is degenerate for fast == 0 (any positive slow beats
        # 0 * ratio), so slow-only ships are always long-range: the knob only
        # filters ships carrying BOTH stats, and the floor is 7 of 19.
        ratio = float(sys.argv[4]) if len(sys.argv) > 4 else 1.0
        for _c, f in ships(doc):
            fl = f.get("fields", {})
            if fl.get("slow", 0) > fl.get("fast", 0) * ratio:
                f["keywords"] = sorted(set(f.get("keywords", [])) | {"reach"})
    else:
        sys.exit(f"unknown shape: {shape}")

    # VERIFY, rather than trust the rewrite. A dangling zone id is the failure
    # mode that would silently invalidate the whole experiment.
    blob = json.dumps(doc)
    if shape == "front_back":
        stale = [l for l in LANES if f'"{l}"' in blob]
        if stale:
            sys.exit(f"FAILED: stale zone ids still referenced: {stale}")
    declared = {z["id"] for z in doc["rules"]["extraZones"]}
    for t in doc["rules"].get("extraTypes", []):
        for zc in t.get("zoneChoices", []):
            if zc not in declared:
                sys.exit(f"FAILED: {t['name']}.zoneChoices names undeclared zone {zc}")

    json.dump(doc, open(path, "w"))
    n = sum(1 for _c, f in ships(doc) if "reach" in f.get("keywords", []))
    print(f"ok: shape={shape} rule={rule} zones={sorted(declared)} long_range_ships={n}")


if __name__ == "__main__":
    main()
