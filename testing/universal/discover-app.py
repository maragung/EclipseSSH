#!/usr/bin/env python3
"""Build the application model (application-model.json) from an APK.

Two passes, both black-box - no source, no app-specific knowledge:

  Static pass (aapt2): `aapt2 dump badging` gives package, versions, SDK
  levels, permissions, launchable activity, ABIs; `aapt2 dump xmltree
  AndroidManifest.xml` gives the component inventory (activities, services,
  receivers, providers), exported flags, and intent filters - from which deep
  links (VIEW actions with data schemes) are derived.

  Runtime pass (optional, --merge-runtime): with the app installed and
  running on a connected device, the first screen is dumped via uiautomator
  and its classified elements are merged into the model, so the explorer
  starts from observed reality rather than a manifest guess.

Nothing about the app is hardcoded: if aapt2 cannot read the manifest, the
model records that and the pipeline fails at the launch step with evidence.

Usage:
  discover-app.py --apk app.apk --aapt2 $ANDROID_HOME/build-tools/36.0.0/aapt2 \
      --out application-model.json
  discover-app.py ... --merge-runtime --package com.example.app [--serial X]
"""
import argparse
import json
import re
import subprocess
import sys

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from adbutil import (Adb, DANGEROUS_PERMISSIONS, classify_element,  # noqa: E402
                     element_label, parse_ui_xml, screen_signature,
                     screen_summary, visible_elements)


# ---------------------------------------------------------------------------
# aapt2 plumbing
# ---------------------------------------------------------------------------

def aapt2(aapt2_path, args, apk):
    proc = subprocess.run([aapt2_path] + args + [apk],
                          capture_output=True, text=True, errors="replace",
                          timeout=120)
    return proc.returncode, proc.stdout, proc.stderr


def parse_badging(text):
    """Extract the facts badging states on one line each."""
    model = {}
    m = re.search(
        r"^package: name='([^']*)' versionCode='(\d*)' versionName='([^']*)'",
        text, re.M)
    if m:
        model["package"] = m.group(1)
        model["versionCode"] = m.group(2)
        model["versionName"] = m.group(3)
    m = re.search(r"^sdkVersion:'(\d+)'", text, re.M)
    if m:
        model["minSdk"] = m.group(1)
    m = re.search(r"^targetSdkVersion:'(\d+)'", text, re.M)
    if m:
        model["targetSdk"] = m.group(1)
    m = re.search(r"^application-label:'([^']*)'", text, re.M)
    if m:
        model["applicationLabel"] = m.group(1)
    m = re.search(r"^launchable-activity: name='([^']*)'", text, re.M)
    if m:
        model["launchableActivity"] = m.group(1)
    # permissions: uses-permission: name='...' (may repeat, may carry
    # maxSdkVersion= on the same line - the name is what matters here)
    model["permissions"] = sorted(set(
        re.findall(r"^uses-permission: name='([^']*)'", text, re.M)))
    # native-code may appear multiple times (one line per group of ABIs)
    model["nativeAbis"] = sorted(set(
        re.findall(r"'([^']+)'", "\n".join(
            line for line in text.splitlines()
            if line.startswith("native-code:")))))
    return model


def parse_xmltree(text):
    """Parse `aapt2 dump xmltree` output into a nested dict tree.

    Format: two spaces of indent per depth; lines are
      E: <element> (line=N)          - opens an element
      A: <attr>="value" (Raw: ...)   - attribute of the innermost element
    """
    root = {"tag": "root", "attrs": {}, "children": []}
    stack = [root]
    for line in text.splitlines():
        stripped = line.lstrip()
        if not stripped.startswith(("E:", "A:")):
            continue
        depth = (len(line) - len(stripped)) // 2
        # Pop back to the parent of this line's element/attribute.
        while len(stack) > depth + 1:
            stack.pop()
        if stripped.startswith("E:"):
            tag = stripped[2:].split("(")[0].strip()
            node = {"tag": tag, "attrs": {}, "children": []}
            stack[-1]["children"].append(node)
            stack.append(node)
        else:
            head = re.match(r'A: ([^=]+)="', stripped)
            if not head:
                continue
            name = head.group(1)
            # aapt2 appends " (Raw: "...")" whenever the manifest literal
            # differs from the resource-resolved value; the raw literal is
            # what a component name or permission must be read from, and a
            # greedy value regex would otherwise swallow the Raw marker.
            raw = re.search(r'\(Raw: "(.*)"\)\s*$', stripped)
            if raw:
                value = raw.group(1)
            else:
                value = stripped[head.end():].rstrip()
                if value.endswith('"'):
                    value = value[:-1]
            stack[-1]["attrs"][name] = value
    return root


def resolve_name(raw, package):
    """Manifest component names are relative ('.Main'), absolute
    ('com.foo.Main'), or fully-qualified-with-package; normalize all three."""
    if not raw:
        return raw
    if raw.startswith("."):
        return package + raw
    if "." not in raw:
        return package + "." + raw
    return raw


def collect_components(tree, package):
    """Walk the manifest tree for the four component kinds and their intent
    filters; derive exported flags and deep links along the way."""
    kinds = ("activity", "activity-alias", "service", "receiver", "provider")
    inventory = {k: [] for k in kinds}
    deep_links = []

    def walk(node):
        if node["tag"] in kinds:
            entry = {
                "name": resolve_name(node["attrs"].get("android:name", ""),
                                     package),
                "exported": node["attrs"].get("android:exported", ""),
                "permission": node["attrs"].get("android:permission", ""),
            }
            filters = []
            for child in node["children"]:
                if child["tag"] != "intent-filter":
                    continue
                filt = {"actions": [], "categories": [], "data": []}
                for sub in child["children"]:
                    name = sub["attrs"].get("android:name", "")
                    if sub["tag"] == "action":
                        filt["actions"].append(name)
                    elif sub["tag"] == "category":
                        filt["categories"].append(name)
                    elif sub["tag"] == "data":
                        data = {k.replace("android:", ""): v
                                for k, v in sub["attrs"].items()}
                        filt["data"].append(data)
                        # A VIEW filter with a scheme is a deep link entry.
                        if "android.intent.action.VIEW" in filt["actions"] \
                                and data.get("scheme"):
                            deep_links.append({
                                "scheme": data.get("scheme"),
                                "host": data.get("host", ""),
                                "pathPrefix": data.get("pathPrefix", ""),
                            })
                if filt["actions"] or filt["categories"] or filt["data"]:
                    filters.append(filt)
            entry["intentFilters"] = filters
            # Exported is explicit on modern manifests; implicit truth is
            # "has an intent filter" for activities/receivers/services.
            if entry["exported"] == "":
                entry["exported"] = "implicit:" + (
                    "true" if filters else "false")
            inventory[node["tag"]].append(entry)
        for child in node["children"]:
            walk(child)

    walk(tree)
    return inventory, deep_links


# ---------------------------------------------------------------------------
# runtime pass
# ---------------------------------------------------------------------------

def merge_runtime_screen(model, serial):
    """Launch the app, dump the first screen, and fold it into the model.

    The launchable activity from badging is authoritative; if the app is
    already in the foreground we dump what is there instead of relaunching,
    so a first-run tutorial or permission prompt is captured as-is.
    """
    adb = Adb(serial=serial)
    package = model.get("package")
    activity = model.get("launchableActivity")
    if not package:
        model["runtimeScreen"] = {"error": "no package - cannot merge runtime"}
        return model

    launched = adb.is_foreground(package)
    launch_output = ""
    if not launched and activity:
        launched, launch_output = adb.am_start(package, activity)
    if not launched:
        model["runtimeScreen"] = {
            "error": "app did not reach the foreground",
            "launchOutput": launch_output[:2000],
        }
        return model

    # Give the first frame a bounded moment before dumping.
    import time
    time.sleep(3)
    elements = adb.ui_dump()
    if elements is None:
        model["runtimeScreen"] = {"error": "uiautomator dump failed"}
        return model
    elements = visible_elements(elements, package)
    model["runtimeScreen"] = {
        "signature": screen_signature(elements),
        "summary": screen_summary(elements),
        "elements": [
            {
                "label": element_label(e),
                "kind": classify_element(e),
                "resourceId": e.attrs.get("resource-id", ""),
                "text": e.attrs.get("text", "")[:120],
                "contentDesc": e.attrs.get("content-desc", "")[:120],
                "clickable": e.attrs.get("clickable") == "true",
                "bounds": e.attrs.get("bounds", ""),
            }
            for e in elements if classify_element(e) != "static"
        ][:120],  # cap: a first screen beyond 120 interactive elements is a
        # dump anomaly, not something a report needs verbatim
        "allElementCount": len(elements),
    }
    return model


# ---------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--apk", required=True)
    ap.add_argument("--aapt2", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--merge-runtime", action="store_true",
                    help="attach the observed first screen (needs a device)")
    ap.add_argument("--package", default="",
                    help="override/refine the package (used with "
                         "--merge-runtime when badging is unavailable)")
    ap.add_argument("--serial", default="")
    args = ap.parse_args()

    rc, badging, err = aapt2(args.aapt2, ["dump", "badging"], args.apk)
    if rc != 0:
        print("aapt2 dump badging failed: %s" % err.strip(), file=sys.stderr)
        sys.exit(1)
    model = parse_badging(badging)

    rc, xmltree, err = aapt2(
        args.aapt2, ["dump", "xmltree", args.apk, "AndroidManifest.xml"],
    )
    if rc == 0:
        tree = parse_xmltree(xmltree)
        inventory, deep_links = collect_components(tree, model["package"])
        model["components"] = inventory
        model["deepLinks"] = deep_links
    else:
        # badging worked but xmltree did not: the model still carries the
        # launchable activity, so the engine can run - components are simply
        # unknown rather than wrong.
        model["components"] = {}
        model["deepLinks"] = []
        model["componentDiscoveryError"] = err.strip()[:500]

    # Permission classification: static best-effort now; the permission
    # journey re-probes at runtime with pm grant, which is authoritative.
    model["runtimePermissions"] = sorted(
        p for p in model.get("permissions", [])
        if p in DANGEROUS_PERMISSIONS)
    model["declaresInternet"] = \
        "android.permission.INTERNET" in model.get("permissions", [])

    if args.merge_runtime:
        model = merge_runtime_screen(model, args.serial)

    with open(args.out, "w") as fh:
        json.dump(model, fh, indent=2, sort_keys=True)
        fh.write("\n")
    print("application model written to %s "
          "(package=%s %s activities=%s runtime-perms=%s)" % (
              args.out, model.get("package"), model.get("versionName", ""),
              len(model.get("components", {}).get("activity", [])),
              len(model.get("runtimePermissions", []))))


if __name__ == "__main__":
    main()
