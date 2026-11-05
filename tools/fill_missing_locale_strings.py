#!/usr/bin/env python3
"""Fills a locale's strings.xml with the keys it is missing.

Android falls back to the default language for a key a translation does not have, so a missing
key is invisible at runtime — until a test asks whether every language translates every key.
This writes the default language's text into the locales that are missing it, so the file says
what the app was showing anyway.
"""

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def entries(path):
    """name -> raw XML of the element, for every translatable entry in the file."""
    text = path.read_text(encoding="utf-8")
    root = ET.fromstring(text)
    out = {}
    for child in root:
        name = child.get("name")
        if not name or child.get("translatable") == "false":
            continue
        out[name] = ET.tostring(child, encoding="unicode")
    return out


def names(path):
    root = ET.fromstring(path.read_text(encoding="utf-8"))
    return {child.get("name") for child in root if child.get("name")}


def fill(base_file, locale_file):
    base = entries(base_file)
    have = names(locale_file)
    missing = [name for name in base if name not in have]
    if not missing:
        return 0

    text = locale_file.read_text(encoding="utf-8")
    addition = "\n".join("    " + base[name].strip() for name in missing)
    text = text.replace("</resources>", addition + "\n</resources>")
    locale_file.write_text(text, encoding="utf-8")
    return len(missing)


def main():
    trees = [
        ("app/src/main/res", "phone"),
        ("sharedUi/src/commonMain/composeResources", "shared"),
    ]
    for root_name, label in trees:
        root = Path(root_name)
        base_file = root / "values" / "strings.xml"
        if not base_file.is_file():
            print(f"{label}: no {base_file}, skipped")
            continue
        for locale_dir in sorted(root.glob("values-*")):
            locale_file = locale_dir / "strings.xml"
            if not locale_file.is_file():
                continue
            added = fill(base_file, locale_file)
            if added:
                print(f"{label}: {locale_dir.name} +{added}")


if __name__ == "__main__":
    main()
