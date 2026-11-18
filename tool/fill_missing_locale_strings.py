#!/usr/bin/env python3
"""Fill locale resource files with any base strings they are missing.

Android silently falls back to the default language when a translated resource
is absent. This makes that fallback explicit in each locale file and keeps
LocaleStringsTest useful without weakening its assertion.
"""

from __future__ import annotations

import argparse
import xml.etree.ElementTree as ET
from pathlib import Path


RESOURCE_ROOTS = (
    Path("app/src/main/res"),
    Path("sharedUi/src/commonMain/composeResources"),
)


def entries(path: Path) -> dict[str, str]:
    """Return translatable resource names and their serialized XML."""
    root = ET.fromstring(path.read_text(encoding="utf-8"))
    result: dict[str, str] = {}
    for child in root:
        name = child.get("name")
        if not name or child.get("translatable") == "false":
            continue
        result[name] = ET.tostring(child, encoding="unicode")
    return result


def names(path: Path) -> set[str]:
    """Return every resource name present in a resource file."""
    root = ET.fromstring(path.read_text(encoding="utf-8"))
    return {
        child.get("name")
        for child in root
        if child.get("name")
    }


def fill(base_file: Path, locale_file: Path, check: bool) -> int:
    base = entries(base_file)
    missing = [name for name in base if name not in names(locale_file)]
    if not missing:
        return 0

    if check:
        print(f"{locale_file}: missing {len(missing)} keys")
        for name in missing:
            print(f"  {name}")
        return len(missing)

    text = locale_file.read_text(encoding="utf-8")
    addition = "\n".join(f"    {base[name].strip()}" for name in missing)
    text = text.replace("</resources>", f"{addition}\n</resources>")
    locale_file.write_text(text, encoding="utf-8")
    print(f"{locale_file}: added {len(missing)} keys")
    return len(missing)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--check",
        action="store_true",
        help="report missing resources without changing files",
    )
    args = parser.parse_args()

    missing_total = 0
    for resource_root in RESOURCE_ROOTS:
        base_file = resource_root / "values" / "strings.xml"
        if not base_file.is_file():
            continue
        for locale_dir in sorted(resource_root.glob("values-*")):
            locale_file = locale_dir / "strings.xml"
            if locale_file.is_file():
                missing_total += fill(base_file, locale_file, args.check)

    return 1 if args.check and missing_total else 0


if __name__ == "__main__":
    raise SystemExit(main())
