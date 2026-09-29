#!/usr/bin/env python3
"""Minimal manifest merger for the Gradle-as-resolver build.

A real Android Gradle Plugin manifest merge does much more (tools:node
semantics beyond plain match-by-name, permission upgrades, placeholder
resolution from multiple sources). This does the subset that's actually
needed here: for each dependency AAR that declares
<application> children (providers, services, etc.) or top-level
<uses-permission>/<uses-feature> elements, merge them into the app's own
manifest, substituting ${applicationId} with the app's real package name.

<application> children ARE match-by-name merged (see merge_application_child
below) -- multiple dependency AARs commonly declare the *same* logical
<provider>/<service>/<receiver>/<activity> (most often
androidx.startup.InitializationProvider, one per AAR that registers an App
Startup initializer, all sharing one authority) and need their <meta-data>
children unioned into ONE element, not left as separate same-authority
duplicates (emoji2, lifecycle-process and profileinstaller each declare
their own InitializationProvider). Still doesn't
implement tools:node semantics beyond plain match-by-name (no remove,
replace, merge-only-if, etc.) and doesn't dedupe anything outside
<application>'s direct children.
"""
import sys
import xml.etree.ElementTree as ET

ANDROID_NS = "http://schemas.android.com/apk/res/android"
ANDROID_ATTR = "{%s}" % ANDROID_NS


def strip_tools_attribs(elem):
    """Drop tools:* attributes -- they're manifest-merger-time instructions
    (e.g. tools:node="merge") that mean nothing to aapt2 and aren't
    implemented by this simplified merge anyway."""
    tools_ns = "{http://schemas.android.com/tools}"
    for key in [k for k in elem.attrib if k.startswith(tools_ns)]:
        del elem.attrib[key]
    for child in elem:
        strip_tools_attribs(child)


def substitute_application_id(elem, application_id):
    for key, value in list(elem.attrib.items()):
        if "${applicationId}" in value:
            elem.attrib[key] = value.replace("${applicationId}", application_id)
    for child in elem:
        substitute_application_id(child, application_id)


def identity_key(elem):
    """(tag, android:name) for the element types that commonly get declared
    identically by more than one dependency AAR -- provider/service/receiver/
    activity are matched by name the same way AGP's own merger does. Anything
    else (meta-data, intent-filter, ...) has no stable identity worth
    deduping on here, so it's never treated as "the same" as another element
    -- always appended as its own child instead."""
    if elem.tag not in ("provider", "service", "receiver", "activity", "activity-alias"):
        return None
    name = elem.get(ANDROID_ATTR + "name")
    return (elem.tag, name) if name else None


def merge_application_child(app_application, existing_by_key, child):
    """Append `child` under app_application, unless an element with the same
    identity_key() is already present -- in that case, move child's own
    children (e.g. <meta-data>) into the existing element instead of adding a
    second sibling with the same android:name/authorities. Only appends
    child's grandchildren the existing element doesn't already have one
    matching (by full string equality) -- keeps this idempotent if the same
    dependency manifest is ever processed twice."""
    key = identity_key(child)
    if key is not None and key in existing_by_key:
        existing = existing_by_key[key]
        existing_signatures = {ET.tostring(c) for c in existing}
        for grandchild in list(child):
            if ET.tostring(grandchild) not in existing_signatures:
                existing.append(grandchild)
        return False
    app_application.append(child)
    if key is not None:
        existing_by_key[key] = child
    return True


def main():
    app_manifest_path, deps_extracted_list_path, output_path = sys.argv[1:4]

    ET.register_namespace("android", ANDROID_NS)
    app_tree = ET.parse(app_manifest_path)
    app_root = app_tree.getroot()
    application_id = app_root.get("package")
    if not application_id:
        print("merge_manifest.py: app manifest has no package attribute", file=sys.stderr)
        sys.exit(1)

    app_application = app_root.find("application")
    if app_application is None:
        print("merge_manifest.py: app manifest has no <application> element", file=sys.stderr)
        sys.exit(1)

    existing_permissions = {
        el.get(ANDROID_ATTR + "name")
        for el in app_root.findall("uses-permission")
    }

    existing_by_key = {}
    for child in app_application:
        key = identity_key(child)
        if key is not None:
            existing_by_key[key] = child

    with open(deps_extracted_list_path) as f:
        dep_manifest_paths = [line.strip() for line in f if line.strip()]

    merged_count = 0
    for dep_manifest_path in dep_manifest_paths:
        try:
            dep_root = ET.parse(dep_manifest_path).getroot()
        except ET.ParseError as e:
            print(f"merge_manifest.py: skipping unparseable {dep_manifest_path}: {e}", file=sys.stderr)
            continue

        dep_application = dep_root.find("application")
        if dep_application is not None:
            for child in list(dep_application):
                strip_tools_attribs(child)
                substitute_application_id(child, application_id)
                merge_application_child(app_application, existing_by_key, child)
                merged_count += 1

        for perm in dep_root.findall("uses-permission"):
            name = perm.get(ANDROID_ATTR + "name")
            if name and name not in existing_permissions:
                strip_tools_attribs(perm)
                substitute_application_id(perm, application_id)
                app_root.insert(0, perm)
                existing_permissions.add(name)
                merged_count += 1

    app_tree.write(output_path, encoding="utf-8", xml_declaration=True)
    print(f"merge_manifest.py: merged {merged_count} element(s) from {len(dep_manifest_paths)} dependency manifest(s)")


if __name__ == "__main__":
    main()
