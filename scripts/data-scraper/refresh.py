"""Helpers for the scheduled game-data refresh workflow (`.github/workflows/refresh-data.yml`).

Subcommands:
    plan       Decide whether GameTora has anything new, without a full scrape. Prints and outputs `work=true|false`.
    check      Exit 1 when the freshly scraped `src/data` looks broken compared to HEAD.
    finalize   Stamp `src/data/data_version.json` and `included_cards.json`, and output the commit subject.
    save-ids   Record the GameTora dataset ids this run scraped, for the next `plan`.
    seed       Write `included_cards.json` for every card released on or before `--through`.
"""

import argparse
import hashlib
import json
import os
import subprocess
import sys
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional

import requests

sys.path.insert(0, str(Path(__file__).resolve().parent))
import main as scraper  # noqa: E402

SCRIPT_DIR = Path(__file__).resolve().parent
DATA_DIR = scraper.DATA_DIR
DATA_VERSION_PATH = DATA_DIR / "data_version.json"
INCLUDED_CARDS_PATH = SCRIPT_DIR / "included_cards.json"
MANIFEST_IDS_PATH = SCRIPT_DIR / ".cache" / "manifest_ids.json"

# The files the app can download. races.json and scenarios.json are not scraped, so they only change with an APK.
UPDATABLE_FILES = ["characters.json", "supports.json", "skills.json", "epithets.json", "characterPresets.json", "character_objectives.json"]

# Every GameTora manifest dataset main.py reads. A changed id means GameTora republished that dataset.
WATCHED_DATASETS = [
    "character-cards",
    "characters",
    "dict/te_names_by_id_en",
    "nicknames",
    "race-fans",
    "race_instances",
    "races",
    "scenarios",
    "skills",
    "status-effects",
    "support-cards",
    "ura-objectives",
]

# Lowest app version that can read this data format. Bump it when a data change would break older apps.
MIN_APP_VERSION = "5.8.8"

# GameTora outfit codes spelled the way the commit history names them. Unknown codes fall back to title case.
VERSION_LABELS = {"fall_festival": "Festival"}


def released_cards(char_cards: List[dict], support_cards: List[dict], today: str) -> Dict[str, List[dict]]:
    """Keeps the cards that have released on Global by `today`.

    Args:
        char_cards (List[dict]): GameTora `character-cards` entries.
        support_cards (List[dict]): GameTora `support-cards` entries.
        today (str): ISO date to compare `release_en` against.

    Returns:
        A dict with `character` and `support` lists of released cards.
    """

    def is_out(card: dict) -> bool:
        return bool(card.get("release_en")) and card["release_en"] <= today

    return {"character": [c for c in char_cards if is_out(c)], "support": [c for c in support_cards if is_out(c)]}


def new_cards(released: Dict[str, List[dict]], included: Dict[str, List[int]]) -> Dict[str, List[dict]]:
    """Finds the released cards the committed data does not include yet.

    Args:
        released (Dict[str, List[dict]]): Output of `released_cards`.
        included (Dict[str, List[int]]): The `included_cards.json` contents, card ids per kind.

    Returns:
        A dict with `character` and `support` lists of cards new since the last data commit.
    """
    have_chars = set(included.get("character", []))
    have_supports = set(included.get("support", []))
    return {
        "character": [c for c in released["character"] if c["card_id"] not in have_chars],
        "support": [c for c in released["support"] if c["support_id"] not in have_supports],
    }


def character_label(card: dict) -> str:
    """Names a trainee card the way the commit history does, e.g. `Tamamo Cross (Festival)`.

    Args:
        card (dict): A GameTora `character-cards` entry.

    Returns:
        The character name, with the outfit in parentheses for alternate versions.
    """
    version = card.get("version")
    if not version:
        return card["name_en"]
    return f"{card['name_en']} ({VERSION_LABELS.get(version, version.replace('_', ' ').title())})"


def support_label(card: dict) -> str:
    """Names a support card by its character, using the scraper's curated name overrides.

    Args:
        card (dict): A GameTora `support-cards` entry.

    Returns:
        The character name the data files key the card under.
    """
    return scraper.SUPPORT_CARD_NAME_OVERRIDES.get(card["char_name"], card["char_name"])


def build_label(fresh: Dict[str, List[dict]], today: str) -> str:
    """Builds the update label from the newest new trainee, else the newest new support card, else the date.

    Args:
        fresh (Dict[str, List[dict]]): Output of `new_cards`.
        today (str): ISO date used when no card is new.

    Returns:
        A label such as `Tamamo Cross (Christmas) 2026-12-25` or `2026-12-25`.
    """
    if fresh["character"]:
        newest = max(fresh["character"], key=lambda c: (c["release_en"], c["card_id"]))
        return f"{character_label(newest)} {newest['release_en']}"
    if fresh["support"]:
        newest = max(fresh["support"], key=lambda c: (c["release_en"], c["support_id"]))
        return f"{support_label(newest)} {newest['release_en']}"
    return today


def commit_subject(label: str) -> str:
    """Builds the commit and PR subject for a label.

    Args:
        label (str): Output of `build_label`.

    Returns:
        The subject line, matching the repo's existing data commits.
    """
    return f"Update game data up to {label}"


def file_md5(path: Path) -> str:
    """Hashes a data file the way the app verifies it after download.

    GitHub serves the committed blob with LF endings, but a Windows working copy may hold CRLF. JSON never contains a raw CR,
    so normalizing is safe.

    Args:
        path (Path): The file to hash.

    Returns:
        The hex md5 of the file bytes with CRLF normalized to LF.
    """
    return hashlib.md5(path.read_bytes().replace(b"\r\n", b"\n")).hexdigest()
