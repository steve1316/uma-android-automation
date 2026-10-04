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

# Keys every entry of a dict-of-records file must carry, with their types. The two event files are checked separately.
REQUIRED_KEYS = {
    "skills.json": {"id": int, "name_en": str, "icon_id": int},
    "epithets.json": {"name": str, "bullet_points": list},
    "characterPresets.json": {"name": str, "distanceAptitudes": dict, "surfaceAptitudes": dict},
    "character_objectives.json": {"name": str, "mandatoryRaces": list},
}

# Fields a broken source page silently empties, with the value that means empty and a name for the problem message. The merge is
# additive, so the number of entries with the field filled must never drop.
FILLED_FIELDS = [
    ("epithets.json", "characters", [], "character-restricted epithets"),
    ("skills.json", "community_tier", None, "skills with a community tier"),
    ("skills.json", "eval_pt", 0, "skills with evaluation points"),
]

# GameTora's id field for each card kind.
ID_KEYS = {"character": "card_id", "support": "support_id"}

# The files a new trainee and a new support card must appear in.
TRAINEE_FILES = ["characters.json", "characterPresets.json", "character_objectives.json"]
SUPPORT_FILES = ["supports.json"]


def released_cards(char_cards: List[dict], support_cards: List[dict], today: str) -> Dict[str, List[dict]]:
    """Keeps the cards that have released on Global by `today`.

    Args:
        char_cards (List[dict]): GameTora `character-cards` entries.
        support_cards (List[dict]): GameTora `support-cards` entries.
        today (str): ISO date to compare `release_en` against.

    Returns:
        A dict with `character` and `support` lists of released cards.
    """
    cards = {"character": char_cards, "support": support_cards}
    return {kind: [c for c in cards[kind] if scraper.is_global_release(c, today)] for kind in ID_KEYS}


def new_cards(released: Dict[str, List[dict]], included: Dict[str, List[int]]) -> Dict[str, List[dict]]:
    """Finds the released cards the committed data does not include yet.

    Args:
        released (Dict[str, List[dict]]): Output of `released_cards`.
        included (Dict[str, List[int]]): The `included_cards.json` contents, card ids per kind.

    Returns:
        A dict with `character` and `support` lists of cards new since the last data commit.
    """
    have = {kind: set(included.get(kind, [])) for kind in ID_KEYS}
    return {kind: [c for c in released[kind] if c[key] not in have[kind]] for kind, key in ID_KEYS.items()}


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
    for kind, label in (("character", character_label), ("support", support_label)):
        if fresh[kind]:
            newest = max(fresh[kind], key=lambda c: (c["release_en"], c[ID_KEYS[kind]]))
            return f"{label(newest)} {newest['release_en']}"
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


def _shape_problems(name: str, data: Dict[str, Any]) -> List[str]:
    """Lists entries in one data file that do not have the expected shape.

    Args:
        name (str): The file name, which picks the rules.
        data (Dict[str, Any]): The parsed file.

    Returns:
        One message per bad entry.
    """
    problems = []
    if name in ("characters.json", "supports.json"):
        for key, events in data.items():
            if not isinstance(events, dict) or not events:
                problems.append(f"{name}: {key} has no events")
            elif not all(isinstance(opts, list) and all(isinstance(o, str) for o in opts) for opts in events.values()):
                problems.append(f"{name}: {key} has an event whose options are not a list of strings")
        return problems
    for key, entry in data.items():
        for field, kind in REQUIRED_KEYS[name].items():
            if not isinstance(entry, dict) or field not in entry:
                problems.append(f"{name}: {key} is missing {field}")
            elif not isinstance(entry[field], kind):
                problems.append(f"{name}: {key}.{field} is not a {kind.__name__}")
    return problems


def _filled_count(data: Any, field: str, empty: Any) -> int:
    """Counts the entries of a data file whose field holds a real value.

    Args:
        data (Any): A parsed data file, or None when it is missing.
        field (str): The field to look at.
        empty (Any): The value that means the field was never filled in.

    Returns:
        How many entries have the field set to something other than `empty`.
    """
    if not isinstance(data, dict):
        return 0
    return sum(1 for e in data.values() if isinstance(e, dict) and e.get(field, empty) not in (empty, None))


def find_problems(old: Dict[str, Any], new: Dict[str, Any], fresh: Dict[str, List[dict]]) -> List[str]:
    """Gates a fresh scrape. The merge is additive, so any shrink or gap means the scrape broke.

    Args:
        old (Dict[str, Any]): File name to parsed contents at HEAD. A missing file maps to None.
        new (Dict[str, Any]): File name to parsed contents on disk after the scrape. Unparseable maps to None.
        fresh (Dict[str, List[dict]]): Output of `new_cards`. Each new card must show up in its files.

    Returns:
        Human-readable problems. Empty means the data can ship.
    """
    problems = []
    for name in UPDATABLE_FILES:
        before = old.get(name) or {}
        after = new.get(name)
        if not isinstance(after, dict):
            problems.append(f"{name}: missing or not valid JSON")
            continue
        if len(after) < len(before):
            problems.append(f"{name}: entry count dropped from {len(before)} to {len(after)}")
        problems.extend(_shape_problems(name, after))
    for name, field, empty, what in FILLED_FIELDS:
        before = _filled_count(old.get(name), field, empty)
        after = _filled_count(new.get(name), field, empty)
        if after < before:
            problems.append(f"{name}: {what} dropped from {before} to {after}")
    for card in fresh["character"]:
        for name in TRAINEE_FILES:
            if card["name_en"] not in (new.get(name) or {}):
                problems.append(f"new trainee {card['name_en']} has no entry in {name}")
    for card in fresh["support"]:
        for name in SUPPORT_FILES:
            if support_label(card) not in (new.get(name) or {}):
                problems.append(f"new support card {support_label(card)} has no entry in {name}")
    return problems


def plan_work(index: Dict[str, str], saved_ids: Dict[str, str], fresh: Dict[str, List[dict]]) -> List[str]:
    """Explains why a full scrape is needed. An empty list means nothing changed.

    Args:
        index (Dict[str, str]): GameTora's manifest index, dataset name to content id.
        saved_ids (Dict[str, str]): The ids the last successful refresh scraped.
        fresh (Dict[str, List[dict]]): Output of `new_cards`.

    Returns:
        One reason per moved dataset or new card.
    """
    reasons = [f"{name} moved" for name in WATCHED_DATASETS if index.get(name) != saved_ids.get(name)]
    reasons += [f"new trainee {character_label(c)}" for c in fresh["character"]]
    reasons += [f"new support card {support_label(c)}" for c in fresh["support"]]
    return reasons


def merge_included(included: Dict[str, List[int]], fresh: Dict[str, List[dict]]) -> Dict[str, List[int]]:
    """Adds the fresh card ids to the included set.

    Args:
        included (Dict[str, List[int]]): Current `included_cards.json` contents.
        fresh (Dict[str, List[dict]]): Output of `new_cards`.

    Returns:
        The merged, sorted id lists.
    """
    return {kind: sorted(set(included.get(kind, [])) | {c[key] for c in fresh[kind]}) for kind, key in ID_KEYS.items()}


def write_data_version(label: str, now: datetime) -> dict:
    """Writes `src/data/data_version.json`, which the app compares against its own copy.

    Args:
        label (str): Output of `build_label`.
        now (datetime): The UTC time of this refresh. Later stamps sort later as plain strings.

    Returns:
        The written stamp.
    """
    stamp = {
        "version": now.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "label": label,
        "minAppVersion": MIN_APP_VERSION,
        "files": {name: file_md5(DATA_DIR / name) for name in UPDATABLE_FILES},
    }
    _write_json(DATA_VERSION_PATH, stamp)
    return stamp


def _write_json(path: Path, data: Any):
    """Writes JSON with the scraper's formatting.

    Args:
        path (Path): Destination file.
        data (Any): The value to write.
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, ensure_ascii=False, indent=4)
        f.write("\n")


def _read_json(path: Path, default: Any) -> Any:
    """Reads a JSON file, falling back when it is missing or broken.

    Args:
        path (Path): The file to read.
        default (Any): Returned when the file cannot be read.

    Returns:
        The parsed contents or `default`.
    """
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except (OSError, json.JSONDecodeError):
        return default


def _load_data(at_head: bool) -> Dict[str, Any]:
    """Loads every updatable file from disk or from the HEAD commit.

    Args:
        at_head (bool): Read `git show HEAD:src/data/<name>` instead of the working copy.

    Returns:
        File name to parsed contents, with None for missing or unparseable files.
    """
    loaded = {}
    for name in UPDATABLE_FILES:
        try:
            if at_head:
                raw = subprocess.run(["git", "show", f"HEAD:src/data/{name}"], capture_output=True, check=True, cwd=DATA_DIR).stdout
                loaded[name] = json.loads(raw)
            else:
                loaded[name] = json.loads((DATA_DIR / name).read_bytes())
        except (subprocess.CalledProcessError, OSError, json.JSONDecodeError):
            loaded[name] = None
    return loaded


def _released(today: str) -> Dict[str, List[dict]]:
    """Fetches GameTora's card lists and keeps the cards released on Global by `today`.

    Args:
        today (str): ISO date for the Global release gate.

    Returns:
        Output of `released_cards`.
    """
    return released_cards(scraper.fetch_gametora_manifest_data("character-cards"), scraper.fetch_gametora_manifest_data("support-cards"), today)


def _fresh_cards(today: str, included: Optional[Dict[str, List[int]]] = None) -> Dict[str, List[dict]]:
    """Fetches GameTora's card lists and returns the cards new since the last data commit.

    Args:
        today (str): ISO date for the Global release gate.
        included (Optional[Dict[str, List[int]]]): The `included_cards.json` contents. Read from disk when omitted.

    Returns:
        Output of `new_cards`.
    """
    return new_cards(_released(today), included if included is not None else _read_json(INCLUDED_CARDS_PATH, {}))


def _output(**values: str):
    """Prints values and appends them to `$GITHUB_OUTPUT` when running in Actions.

    Args:
        **values (str): Output names and values.
    """
    for key, value in values.items():
        print(f"{key}={value}")
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as f:
            for key, value in values.items():
                f.write(f"{key}={value}\n")


def main(argv: Optional[List[str]] = None) -> int:
    """Runs one subcommand.

    Args:
        argv (Optional[List[str]]): Arguments, defaulting to `sys.argv[1:]`.

    Returns:
        The process exit code.
    """
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("plan")
    sub.add_parser("check")
    finalize = sub.add_parser("finalize")
    finalize.add_argument("--data-changed", choices=["true", "false"], required=True)
    sub.add_parser("save-ids")
    seed = sub.add_parser("seed")
    seed.add_argument("--through", required=True, help="Treat every card released on or before this ISO date as already included.")
    seed.add_argument("--label", help="Label for the seed data_version.json. Defaults to the newest included trainee.")
    args = parser.parse_args(argv)
    today = date.today().isoformat()

    if args.command == "plan":
        index = scraper.fetch_gametora_manifest_index()
        reasons = plan_work(index, _read_json(MANIFEST_IDS_PATH, {}), _fresh_cards(today))
        for reason in reasons:
            print(f"  {reason}")
        _output(work="true" if reasons else "false")
        return 0

    if args.command == "check":
        old = _load_data(at_head=True)
        problems = find_problems(old, _load_data(at_head=False), _fresh_cards(today))
        # An unreadable HEAD copy would turn the shrink gate off for that file, so fail instead.
        problems += [f"{name}: could not read the HEAD copy" for name in UPDATABLE_FILES if old.get(name) is None]
        for problem in problems:
            print(f"::error::{problem}")
        print(f"check: {len(problems)} problem(s)")
        return 1 if problems else 0

    if args.command == "finalize":
        current = _read_json(INCLUDED_CARDS_PATH, None)
        fresh = _fresh_cards(today, current or {})
        label = build_label(fresh, today)
        included = merge_included(current or {}, fresh)
        cards_changed = included != current
        if cards_changed:
            _write_json(INCLUDED_CARDS_PATH, included)
        if args.data_changed == "true":
            write_data_version(label, datetime.now(timezone.utc))
            _output(commit="true", label=label, subject=commit_subject(label))
        elif cards_changed:
            # New outfits whose events were already present add nothing to src/data. Record them so `plan` stops re-running.
            _output(commit="true", label=label, subject=f"Record cards released up to {today}")
        else:
            _output(commit="false", label=label, subject="")
        return 0

    if args.command == "save-ids":
        index = scraper.fetch_gametora_manifest_index()
        _write_json(MANIFEST_IDS_PATH, {name: index.get(name) for name in WATCHED_DATASETS})
        return 0

    if args.command == "seed":
        released = _released(args.through)
        _write_json(INCLUDED_CARDS_PATH, merge_included({}, released))
        label = args.label or build_label({"character": released["character"], "support": []}, args.through)
        write_data_version(label, datetime.fromisoformat(f"{args.through}T00:00:00+00:00"))
        print(f"seeded {len(released['character'])} trainee and {len(released['support'])} support cards, label {label!r}")
        return 0
    return 2


if __name__ == "__main__":
    sys.exit(main())
