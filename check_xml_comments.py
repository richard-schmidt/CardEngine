#!/usr/bin/env python3
"""Fails loudly, with real file:line context, if any XML comment in this
project contains a literal "--" -- aapt2 rejects this with a bare "not well
formed (invalid token)" and gives no indication of which file or why. Run as a
build.sh preflight.

Usage: python3 check_xml_comments.py [project_root]   (default: ".")
Exits 1 and prints every offending file:line if any are found, 0 otherwise.
"""
import re
import sys
from pathlib import Path

COMMENT_RE = re.compile(r"<!--(.*?)-->", re.DOTALL)


def find_offenders(root):
    offenders = []
    paths = list(Path(root).rglob("*.xml"))
    manifest = Path(root) / "AndroidManifest.xml"
    if manifest.exists() and manifest not in paths:
        paths.append(manifest)
    for path in paths:
        # Never walk into resolved external-dependency output -- those are
        # someone else's XML, already built, not this project's source.
        if "deps/raw" in str(path) or "deps/extracted" in str(path) or "/out/" in str(path):
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        for m in COMMENT_RE.finditer(text):
            body = m.group(1)
            if "--" in body:
                line = text[: m.start()].count("\n") + 1
                offenders.append((path, line, body.strip()[:80]))
    return offenders


if __name__ == "__main__":
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    offenders = find_offenders(root)
    if offenders:
        print("XML comment(s) contain a literal '--', which aapt2 rejects as")
        print("\"not well formed (invalid token)\" with no file:line of its own:")
        for path, line, snippet in offenders:
            print(f"  {path}:{line}: {snippet}")
        print("Fix: reword around the '--' (use ':' or a plain sentence break, never '--' as an em-dash).")
        sys.exit(1)
    sys.exit(0)
