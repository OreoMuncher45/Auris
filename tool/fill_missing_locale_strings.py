#!/usr/bin/env python3
"""Fill locale resource files with any base strings they are missing.

Android silently falls back to the default language when a translated resource
is absent. This makes that fallback explicit in each locale file and keeps
LocaleStringsTest useful without weakening its assertion.
"""

from __future__ import annotations

import argparse
import re
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Dict, List, Set, Tuple


REPO_ROOT = Path(__file__).resolve().parent.parent
RESOURCE_ROOTS = (
    REPO_ROOT / "app/src/main/res",
    REPO_ROOT / "sharedUi/src/commonMain/composeResources",
)
LANGUAGE_SOURCE = (
    REPO_ROOT
    / "app/src/main/java/com/music/bitchord/ui/components/AppLanguageDialog.kt"
)
RESOURCE_TYPES = ("string", "plurals", "string-array")
ResourceKey = Tuple[str, str]


def supported_languages() -> Set[str]:
    """Read the app's supported language tags from its single source."""
    source = LANGUAGE_SOURCE.read_text(encoding="utf-8")
    tags = set(re.findall(r'AppLanguage\("([^"]+)"', source))
    if not tags:
        raise RuntimeError(f"No supported languages found in {LANGUAGE_SOURCE}")
    return tags


def entries(path: Path) -> Dict[ResourceKey, str]:
    """Return translatable resource type/name pairs and their serialized XML."""
    root = ET.fromstring(path.read_text(encoding="utf-8"))
    result: Dict[ResourceKey, str] = {}
    for child in root:
        name = child.get("name")
        if not name or child.get("translatable") == "false":
            continue
        key = (child.tag, name)
        result[key] = ET.tostring(child, encoding="unicode")
    return result


def names(path: Path) -> Set[ResourceKey]:
    """Return every translatable resource type/name pair in a resource file."""
    root = ET.fromstring(path.read_text(encoding="utf-8"))
    return {
        (child.tag, child.get("name"))
        for child in root
        if child.get("name") and child.get("translatable") != "false"
    }


def locale_directories(resource_root: Path, languages: Set[str]) -> List[Path]:
    """Return locale directories, including region variants, but not configs."""
    directories = []
    for directory in resource_root.glob("values-*"):
        qualifier = directory.name[len("values-"):]
        language = qualifier.split("-", 1)[0].lower()
        if language in languages:
            directories.append(directory)
    return sorted(directories)


def insert_entries(text: str, missing: Dict[ResourceKey, str]) -> str:
    """Insert resources immediately before the real closing resources tag."""
    closing = re.search(r"</resources\s*>\s*\Z", text)
    if closing is None:
        raise ValueError("Resource file has no closing </resources> tag")

    addition = "\n".join(f"    {missing[key].strip()}" for key in missing)
    prefix = text[: closing.start()].rstrip()
    return f"{prefix}\n{addition}\n</resources>\n"


def fill(base_file: Path, locale_file: Path, check: bool) -> int:
    base = entries(base_file)
    if not locale_file.exists():
        if check:
            print(f"{locale_file}: missing resource file")
            return len(base)
        locale_file.parent.mkdir(parents=True, exist_ok=True)
        locale_file.write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n',
            encoding="utf-8",
        )

    locale_names = names(locale_file)
    missing = {
        key: xml
        for key, xml in base.items()
        if key not in locale_names
    }
    if not missing:
        return 0

    if check:
        print(f"{locale_file}: missing {len(missing)} keys")
        for resource_type, name in missing:
            print(f"  {resource_type}:{name}")
        return len(missing)

    text = locale_file.read_text(encoding="utf-8")
    locale_file.write_text(insert_entries(text, missing), encoding="utf-8")
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

    languages = supported_languages() - {"en"}
    missing_total = 0
    for resource_root in RESOURCE_ROOTS:
        base_file = resource_root / "values" / "strings.xml"
        if not base_file.is_file():
            continue

        directories = locale_directories(resource_root, languages)
        exact_directories = {f"values-{tag}" for tag in languages}
        for tag in sorted(languages):
            locale_file = resource_root / f"values-{tag}" / "strings.xml"
            missing_total += fill(base_file, locale_file, args.check)

        for directory in directories:
            if directory.name in exact_directories:
                continue
            locale_file = directory / "strings.xml"
            if locale_file.is_file():
                missing_total += fill(base_file, locale_file, args.check)

    return 1 if args.check and missing_total else 0


if __name__ == "__main__":
    raise SystemExit(main())
