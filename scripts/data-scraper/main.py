import json
import re
import time
import math
import logging
import os
from pathlib import Path
from datetime import date
from typing import List, Dict, Any, Optional, Tuple, Union
import bisect
import requests
from bs4 import BeautifulSoup

# Resolve the JSON output directory relative to this file so the scraper can be invoked from any CWD.
# Layout: <repo>/scripts/data-scraper/main.py -> parents[2] is the repo root, then src/data.
DATA_DIR = Path(__file__).resolve().parents[2] / "src" / "data"

# Resolve the skill-icon output directory the same way so icons land in the app's bundled assets regardless of CWD.
ICONS_DIR = Path(__file__).resolve().parents[2] / "src" / "pages" / "Skills" / "icons"

GAMETORA_DATA_URL = "https://gametora.com/data"
GAMETORA_MANIFESTS_URL = f"{GAMETORA_DATA_URL}/manifests/umamusume.json"
GAMETORA_MANIFEST_DATA_BASE_URL = f"{GAMETORA_DATA_URL}/umamusume"

# Browser-like User-Agent for the plain-HTTP scrapes (some sites reject the default requests UA).
HTTP_HEADERS = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"}

# Module-level run state: the GameTora manifest index and per-dataset manifest data, each fetched once per run and reused across scrapers.
_manifest_index_cache = None
_manifest_data_cache = {}

# GameTora serves each card's training events as pre-rendered JSON under its Next.js page-data endpoint. These cache the
# build id (addresses that endpoint) and the id -> name lookups, fetched once per run.
_gametora_build_id = None
_event_skill_names = None
_event_char_names = None
_event_status_names = None
_event_race_names = None
_event_event_names = None

# Training-event reward rendering, mirroring GameTora's own English templates. A reward is {"t": code, "v": value, "d": id}.
# `di` splits a choice into "Randomly either" outcome groups. Energy scales with a support card's Event Recovery, while
# stats and skill points scale with its Event Effectiveness (see TrainingEventScraper).
EVENT_ARROW = "❯"  # Prefixes a chained event's name, one per unlock level: "(❯)", "(❯❯)", ...
EVENT_TYPE_LABELS = {
    "ft": "After first training",
    "at": "Randomly after training (repeatable)",
    "ny": "Dating before the first New Year's",
    "fs": "After Finals (bond maxed)",
    "ff": "After Finals (bond not maxed)",
    "tf": "Training together failed",
    "pd": "Dating starts",
}
# Reward codes whose label is just "<Name> <value>".
EVENT_SIMPLE_REWARDS = {
    "en": "Energy", "me": "Maximum Energy", "sp": "Speed", "st": "Stamina", "po": "Power", "gu": "Guts", "in": "Wit",
    "mo": "Mood", "pt": "Skill points", "fa": "Fans", "pa": "Passion", "mn": "Mental", "app": "Aptitude Points",
    "ls": "Last trained stat", "ntsr": "Next turn Specialty Rate of all support cards", "track_hint": "Relevant track skill hint",
    "ttl_gauge_all": "All Instruction gauges", "stat_not_disabled": "Stat that didn't have its facility disabled",
}
# Reward codes that render to a fixed phrase with no value.
EVENT_FLAT_REWARDS = {
    "fe": "Full energy recovery", "rf": "Gain a red fragment", "bf": "Gain a blue fragment", "yf": "Gain a yellow fragment",
    "ee": "Event chain ended", "ds": "Can start dating", "ha": "Heal all negative status effects", "no": "Nothing happens",
    "rr": "Standard race rewards", "expensive_races": "Racing consumes more energy",
    "brian_tryhard": "Increased difficulty and rewards of future training goals",
    "yhs_sr": "Guaranteed Super Recovery during the next bath",
}
# Reward codes that render to a fixed conditional-branch header (shown with GameTora's leading condition marker).
EVENT_CONDITION_REWARDS = {
    "motivation_good": "※ Mood Good or better", "motivation_not_good": "※ Mood Normal or worse",
    "result_good": "※ Good result", "result_average": "※ Average result", "result_bad": "※ Bad result",
    "other_cases": "※ In other cases", "most_trained": "※ The outcome depends on the most frequent training type",
    "highest_facility": "※ The outcome depends on which training facility has the highest level (ties decided at random)",
}
EVENT_ENERGY_CODES = {"en"}  # Scaled by Event Recovery.
EVENT_STAT_CODES = {"sp", "st", "po", "gu", "in", "pt", "5s", "rs", "unspecified_stats"}  # Scaled by Event Effectiveness.
EVENT_DIVIDER_CODES = {"di", "di_s"}  # Split a choice into "Randomly either" outcome groups.
# Hiragana, Katakana, and CJK ideographs - any of these in an event marks it as still untranslated (JP-only, not on Global).
EVENT_CJK_PATTERN = re.compile("[\u3040-\u30ff\u4e00-\u9fff]")
EVENT_ACADEMY_CHAR = 9002  # Yayoi Akikawa, the bond target for the `bo_ch` reward.
# Lookups for the `sc` branch-condition reward: running style, race grade, and career class.
EVENT_STRATEGY_NAMES = {1: "Front Runner", 2: "Pace Chaser", 3: "Late Surger", 4: "End Closer"}
EVENT_GRADE_NAMES = {100: "G1", 200: "G2", 300: "G3", 400: "OP", 700: "Pre-OP"}
EVENT_CLASS_NAMES = {1: "Junior Class", 2: "Classic Class", 3: "Senior Class"}
# GameTora sometimes renames a support card; map its current name back to our curated one so the card isn't duplicated.
SUPPORT_CARD_NAME_OVERRIDES = {"The Throne's Assemblage": "Heirs to the Throne"}


def run_scraper_with_retry(scraper, retries: int = 2, backoff: float = 5.0):
    """Runs a scraper's start() with retries so a transient network failure doesn't abort the whole run.

    A scraper that still fails after its retries is skipped rather than fatal, so the scrapers after it continue.

    Args:
        scraper: The scraper instance to run.
        retries (int, optional): How many extra attempts to make after the first.
        backoff (float, optional): Seconds to wait between attempts.
    """
    name = type(scraper).__name__
    for attempt in range(retries + 1):
        try:
            scraper.start()
            return
        except requests.exceptions.RequestException as exc:
            if attempt < retries:
                logging.warning(f"{name} network failure ({exc.__class__.__name__}); retrying in {backoff}s.")
                time.sleep(backoff)
            else:
                logging.error(f"{name} failed after {retries + 1} attempts; skipping. Error: {exc}")
                return
        except Exception as exc:
            # A non-network bug won't be fixed by retrying, but it must not kill the scrapers that follow.
            logging.error(f"{name} raised a non-retryable error; skipping. Error: {exc}")
            return


def download_image(url: str, out_fp: str):
    """Downloads an image and saves it to the given path.

    Args:
        url (str): The image URL.
        out_fp (str): The destination file path.
    """
    try:
        os.makedirs(os.path.dirname(out_fp), exist_ok=True)
        response = requests.get(url, timeout=30)
        response.raise_for_status()
        with open(out_fp, "wb") as f_out:
            f_out.write(response.content)
    except (requests.exceptions.RequestException, OSError) as exc:
        print(f"An error occurred when downloading image: {exc}")


def fetch_gametora_manifest_data(manifest_name: str) -> dict:
    """Fetches a dataset from GameTora's JSON manifest. The index and each dataset are cached per run, so repeat calls don't re-download.

    Args:
        manifest_name (str): The dataset name to fetch.

    Returns:
        The dataset JSON as a dictionary.
    """
    global _manifest_index_cache
    if manifest_name in _manifest_data_cache:
        return _manifest_data_cache[manifest_name]
    if _manifest_index_cache is None:
        response = requests.get(GAMETORA_MANIFESTS_URL, timeout=60)
        response.raise_for_status()
        _manifest_index_cache = response.json()

    manifest_id = _manifest_index_cache[manifest_name]
    manifest_url = f"{GAMETORA_MANIFEST_DATA_BASE_URL}/{manifest_name}.{manifest_id}.json"
    response = requests.get(manifest_url)
    response.raise_for_status()
    _manifest_data_cache[manifest_name] = response.json()
    return _manifest_data_cache[manifest_name]


def fetch_soup(url: str) -> BeautifulSoup:
    """Fetches a page over plain HTTP and returns its parsed HTML tree.

    Args:
        url (str): The page URL to fetch.

    Returns:
        The parsed HTML as a BeautifulSoup tree.
    """
    response = requests.get(url, headers=HTTP_HEADERS, timeout=30)
    response.raise_for_status()
    return BeautifulSoup(response.text, "lxml")


def fetch_gametora_build_id() -> str:
    """Fetches GameTora's current Next.js build id, used to address the per-page data JSON endpoints.

    Returns:
        The build id parsed from a GameTora page's `__NEXT_DATA__`.

    Raises:
        RuntimeError: If the build id cannot be found in the page.
    """
    global _gametora_build_id
    if _gametora_build_id is None:
        html = requests.get("https://gametora.com/umamusume/supports", headers=HTTP_HEADERS, timeout=30).text
        match = re.search(r'"buildId":"([^"]+)"', html)
        if not match:
            raise RuntimeError("Could not find GameTora buildId in page HTML.")
        _gametora_build_id = match.group(1)
    return _gametora_build_id


def fetch_gametora_event_data(section: str, url_name: str) -> Dict[str, Any]:
    """Fetches one card's English training-event data from GameTora's page-data JSON (no browser needed).

    Args:
        section (str): The GameTora section, either "supports" or "characters".
        url_name (str): The card's URL slug.

    Returns:
        The parsed English `eventData` keyed by category, or an empty dict when the page exposes none.
    """
    url = f"https://gametora.com/_next/data/{fetch_gametora_build_id()}/umamusume/{section}/{url_name}.json"
    response = requests.get(url, headers=HTTP_HEADERS, timeout=30)
    response.raise_for_status()
    event_data = response.json().get("pageProps", {}).get("eventData", {})
    raw_en = event_data.get("en") if isinstance(event_data, dict) else None
    return json.loads(raw_en) if raw_en else {}


class BaseScraper:
    """Base class for scraping data from the website.

    Args:
        url (str): The URL to scrape.
        output_filename (str): The filename to save the scraped data to.
    """

    def __init__(self, url: str, output_filename: str):
        self.url = url
        self.output_filename = str(DATA_DIR / output_filename)
        self.data = {}

    def save_data(self):
        """Writes the scraped data to the output file, sorted by key."""
        sorted_data = {key: self.data[key] for key in sorted(self.data.keys())}
        with open(self.output_filename, "w", encoding="utf-8") as f:
            json.dump(sorted_data, f, ensure_ascii=False, indent=4)
        logging.info(f"Saved {len(self.data)} items to {self.output_filename}.")

class SkillScraper(BaseScraper):
    """Scrapes the skills from the website."""

    def __init__(self):
        super().__init__("https://gametora.com/umamusume/skills", "skills.json")

    def scrape_skill_evaluation_points(self):
        """Scrapes skill Evaluation Points (which affect result rank) from the umamusu wiki.

        Returns:
            A dict mapping skill ID to its `evaluation_points`.
        """
        soup = fetch_soup("https://umamusu.wiki/Game:List_of_Skills")
        data = {}

        for table in soup.find_all("table"):
            tbody = table.find("tbody")
            if not tbody:
                continue
            for row in tbody.find_all("tr"):
                cells = row.find_all("td")
                if len(cells) < 6:
                    continue
                skill_name_anchor = cells[1].find("a")
                if not skill_name_anchor or not skill_name_anchor.get("title"):
                    continue
                skill_id = int("".join(filter(str.isdigit, skill_name_anchor["title"])))
                skill_points = int(cells[3].get_text(strip=True))
                if skill_points == 0:
                    continue
                data[skill_id] = {"evaluation_points": int(cells[4].get_text(strip=True))}

        return data

    def scrape_skill_tier_list(self):
        """Scrapes Game8's skill tier list, reading rank from each table's preceding header since the tables lack IDs.

        Returns:
            A dict mapping skill name to tier (see `tier_letter_map`).
        """
        soup = fetch_soup("https://game8.co/games/Umamusume-Pretty-Derby/archives/536805")

        tier_letter_map = {
            "SS": 0,
            "S": 1,
            "A": 2,
            "B": 3,
        }

        res = {}

        # Game8 now lists skills under per-category "<tier> Tier <category> Skills" h3 headers (e.g. "SS Tier Acceleration Skills"), with the skills in the first table after each header.
        # Read the tier from the header text since the tables have no ids.
        for h3 in soup.find_all("h3", class_="a-header--3"):
            match = re.match(r"^(SS|S|A|B) Tier\b", h3.get_text(strip=True))
            if not match:
                continue
            tier_name = tier_letter_map[match.group(1)]
            table = h3.find_next("table")
            if table is None:
                continue

            for td in table.find_all("td"):
                for div in td.find_all("div"):
                    anchors = div.find_all("a")
                    if not anchors:
                        continue
                    anchor_text = anchors[-1].get_text(strip=True)
                    # Game8 sometimes packs an aptitude's two grades into one cell as "X ○ / X ◎", so register each grade separately to match GameTora.
                    for skill_name in anchor_text.split(" / "):
                        # Make sure we use the same special characters as GameTora.
                        skill_name = skill_name.replace("◯", "○")
                        skill_name = skill_name.replace("◎", "◎")
                        # Get rid of any double spaces.
                        skill_name = skill_name.replace("  ", "")
                        if skill_name in res and res[skill_name] != tier_name:
                            logging.warning(
                                f"Skill is already in tier map with conflicting value: {skill_name} ({tier_name} != {res[skill_name]})"
                            )
                            continue
                        res[skill_name] = tier_name

        # Fix tier-list misspellings so names match GameTora. Add an entry if a skill warns as unknown.
        rename_map = {
            "Let's Pump Some Iron": "Let's Pump Some Iron!",
            "Fast and Furious": "Fast & Furious",
            "Mile Straightaway ○": "Mile Straightaways ○",
            "Flowery ☆ Maneuver": "Flowery☆Maneuver",
            "OMG! ☆ The Final Sprint (ﾟ∀ﾟ)": "OMG! (ﾟ∀ﾟ) The Final Sprint! ☆",
        }

        for old_name, new_name in rename_map.items():
            if old_name in res:
                res[new_name] = res.pop(old_name)
            else:
                logging.warning(f"Old name not in rename_map: {old_name}")

        return res

    def get_skill_activation_conditions(self, skill_object: Dict[str, Any], get_preconditions: bool = False) -> str:
        """Extracts a skill's condition (or precondition) string from its deeply-nested entry.

        Combines fields by priority, preferring the inherited gene_version (the only purchasable form for unique
        skills) and the Global `en` localization (other locales may be on different patches). Preconditions live
        only outside `loc`. Priority: loc.en.gene_version -> loc.en -> gene_version -> top-level condition_groups.

        Args:
            skill_object (Dict[str, Any]): A single skill entry (complex nested dict).
            get_preconditions (bool, optional): Return preconditions instead of conditions. Defaults to False.

        Returns:
            The matching conditions joined by "@", or "" when none are found.
        """
        # Prefer the Global en data (gene_version first), the current Global patch.
        groups = skill_object.get("loc", {}).get("en", {}).get("gene_version", None)
        if groups is not None:
            groups = skill_object.get("loc", {}).get("en", {}).get("gene_version", {}).get("condition_groups", None)
        else:
            groups = skill_object.get("loc", {}).get("en", {}).get("condition_groups", None)

        # Fall back to main condition_groups field.
        if groups is None:
            if "gene_version" in skill_object:
                groups = skill_object["gene_version"].get("condition_groups", None)
            else:
                groups = skill_object.get("condition_groups", None)

        # Nothing found.
        if groups is None:
            return ""

        res = []
        for group in groups:
            condition = group.get("precondition" if get_preconditions else "condition", None)
            if condition is not None:
                res.append(condition)

        return "@".join(res)

    def start(self):
        self.data = {}

        # Get supplementary data for later use.
        skill_evaluation_points = self.scrape_skill_evaluation_points()
        skill_to_tier_map = self.scrape_skill_tier_list()
        # Lowercase the tier-list keys for case-insensitive lookups across sources.
        skill_to_tier_map_lowercase = {k.lower(): k for k in skill_to_tier_map.keys()}

        try:
            skill_data = fetch_gametora_manifest_data("skills")

            skill_id_to_name = {}
            versions_by_name = {}
            for skill in skill_data:
                try:
                    # No name_en means the skill isn't on Global yet.
                    if "name_en" not in skill:
                        continue

                    skill_id = skill["id"]
                    skill_gene_id = skill_id
                    skill_name_en = skill["name_en"].strip().replace("  ", " ")
                    skill_desc_en = skill["desc_en"]
                    skill_iconid = skill["iconid"]
                    skill_inherited = False
                    skill_cost = skill.get("cost", None)
                    # Unique inherited skills: use the gene_version, since the primary ID isn't purchasable via inheritance.
                    if "gene_version" in skill:
                        skill_gene_id = skill["gene_version"]["id"]
                        skill_desc_en = skill["gene_version"]["desc_en"]
                        skill_iconid = skill["gene_version"]["iconid"]
                        skill_inherited = skill["gene_version"].get("inherited", False)
                        skill_cost = skill["gene_version"].get("cost", None)

                    if skill_cost is None:
                        logging.warning(f"Dropping skill with invalid COST: {skill_name_en}")
                        continue

                    # Get the skill activation conditions.
                    skill_condition = self.get_skill_activation_conditions(skill)
                    skill_precondition = self.get_skill_activation_conditions(skill, get_preconditions=True)

                    extra_data = skill_evaluation_points.get(skill_gene_id, {"evaluation_points": 0})

                    # JP-only skills aren't on the tier list, so a miss isn't an error (review for misspellings). Negative skills never appear there.
                    tmp_skill_name = skill_to_tier_map_lowercase.get(skill_name_en.lower(), None)
                    bIsNegative = skill_iconid % 10 == 4
                    if tmp_skill_name is None and not bIsNegative:
                        logging.warning(f"Skill Tier Unknown: {skill_name_en}")

                    community_tier = skill_to_tier_map.get(tmp_skill_name, None)

                    # Corrections to invalid GameTora skill data.
                    if skill_name_en.lower() == "indomitable" and skill_id != 200471:
                        # Multiple "Indomitable" entries exist; only 200471 is valid.
                        continue
                    elif skill_id in [1000011, 1000012, 1000013, 1000014, 1000015, 1000016, 1000017]:
                        # Carnival bonus skill IDs, not currently valid.
                        continue

                    for old_name, old_entry in self.data.items():
                        old_id = old_entry.get("id")
                        if old_id == skill_id:
                            logging.warning(
                                f"Duplicate ID when adding skill: {skill_name_en} ({skill_id}), Previous entry: {old_name} ({old_id})"
                            )

                    tmp = {
                        "id": skill_id,
                        "name_en": skill_name_en,
                        "desc_en": skill_desc_en,
                        "icon_id": skill_iconid,
                        "cost": skill_cost,
                        "eval_pt": extra_data["evaluation_points"],
                        "condition": skill_condition,
                        "precondition": skill_precondition,
                        "inherited": skill_inherited,
                        "community_tier": community_tier,
                        "upgrade": None,
                        "downgrade": None,
                    }
                    skill_id_to_name[skill["id"]] = skill_name_en
                    versions_by_name[skill_name_en] = sorted(skill.get("versions", []))

                    self.data[skill_name_en] = tmp
                except KeyError as exc:
                    if "name_en" in skill:
                        logging.error(f"KeyError when parsing skill ({skill['name_en']}): {exc}")
                    else:
                        logging.error(f"KeyError when parsing skill: {exc}")
                    continue

            # Populate the upgrade/downgrade IDs for every skill from its version chain.
            for skill_name, skill in self.data.items():
                versions = versions_by_name.get(skill_name, [])
                if not versions:
                    continue

                index = bisect.bisect_left(versions, skill["id"])
                if index == 0:
                    # This is the highest level of this skill.
                    downgrade_version = versions[0]
                    if downgrade_version in skill_id_to_name:
                        self.data[skill_name]["downgrade"] = downgrade_version
                elif index == len(versions):
                    # This is the lowest level of this skill.
                    upgrade_version = versions[-1]
                    if upgrade_version in skill_id_to_name:
                        self.data[skill_name]["upgrade"] = upgrade_version
                else:
                    # Skill has both an upgraded and downgraded variant.
                    upgrade_version = versions[index - 1]
                    if upgrade_version in skill_id_to_name:
                        self.data[skill_name]["upgrade"] = upgrade_version

                    downgrade_version = versions[index]
                    if downgrade_version in skill_id_to_name:
                        self.data[skill_name]["downgrade"] = downgrade_version

            self.save_data()

            # Save the skill icons after the JSON is written so an icon/network failure can never skip the save.
            try:
                icon_ids = set(x["icon_id"] for x in self.data.values())
                for icon_id in icon_ids:
                    url = f"https://gametora.com/images/umamusume/skill_icons/utx_ico_skill_{icon_id}.png"
                    out_fp = str(ICONS_DIR / f"utx_ico_skill_{icon_id}.png")
                    download_image(url, out_fp)
            except Exception as exc:
                print("Error downloading skill icons:", exc)

        except Exception as exc:
            print("Error:", exc)


class TrainingEventScraper(BaseScraper):
    """Shared base for the JSON-backed character and support training-event scrapers.

    GameTora exposes each card's events as structured JSON (event name, choices, and reward objects) under its
    Next.js page-data endpoint, so these scrapers reconstruct the exact displayed text with no browser. Events are
    grouped under the card's character name to match the existing schema.
    """

    @staticmethod
    def _load_lookups():
        """Loads and caches the id -> name lookups (skills, characters incl. NPCs, status effects, races, chained events) used to render rewards."""
        global _event_skill_names, _event_char_names, _event_status_names, _event_race_names, _event_event_names
        if _event_skill_names is None:
            _event_skill_names = {s["id"]: (s.get("name_en") or s.get("enname")) for s in fetch_gametora_manifest_data("skills")}
            char_names = {c["char_id"]: c.get("en_name") for c in fetch_gametora_manifest_data("characters")}
            for card in fetch_gametora_manifest_data("support-cards"):
                char_names.setdefault(card["char_id"], card["char_name"])  # Support-only characters (NPCs) aren't in `characters`.
            _event_char_names = char_names
            _event_status_names = {s["id"]: s.get("name_en") for s in fetch_gametora_manifest_data("status-effects")}
            _event_race_names = {r["id"]: r["name_en"] for r in fetch_gametora_manifest_data("races")}
            _event_event_names = fetch_gametora_manifest_data("dict/te_names_by_id_en")

    @staticmethod
    def _ordinal(n: int) -> str:
        """Formats an integer as an English ordinal.

        Args:
            n (int): The number to format.

        Returns:
            The ordinal string, e.g. 1 -> "1st", 2 -> "2nd", 3 -> "3rd". Non-numeric input is returned unchanged.
        """
        try:
            n = int(n)
        except (TypeError, ValueError):
            return str(n)
        suffix = "th" if 11 <= n % 100 <= 13 else {1: "st", 2: "nd", 3: "rd"}.get(n % 10, "th")
        return f"{n}{suffix}"

    @staticmethod
    def _scale(value: Optional[str], mult: float) -> Optional[str]:
        """Scales a signed reward value by a multiplier, flooring each part.

        Args:
            value (Optional[str]): The reward value, possibly a "/"-separated range like "+5/+10".
            mult (float): The multiplier to apply.

        Returns:
            The scaled value string, or the original when it is not numeric.
        """
        if value is None or mult == 1.0:
            return value
        parts = []
        for part in value.split("/"):
            match = re.match(r"^([+-]?)(\d+)$", part.strip())
            if not match:
                return value
            number = int(match.group(2)) * (-1 if match.group(1) == "-" else 1)
            parts.append(f"{math.floor(number * mult):+d}")
        return "/".join(parts)

    @staticmethod
    def _is_global_release(card: Dict[str, Any]) -> bool:
        """Returns whether a card has released on the Global (EN) server, so JP-only cards are skipped.

        Args:
            card (Dict[str, Any]): A character-cards or support-cards entry, which carries per-server release dates.

        Returns:
            True when the card's `release_en` date is set and not in the future.
        """
        release_en = card.get("release_en")
        return bool(release_en) and release_en <= date.today().isoformat()

    @staticmethod
    def _is_unlocalized(name: str, options: List[str]) -> bool:
        """Returns whether an event still contains Japanese text, marking it as not yet localized for the Global server.

        A Global-released card can still carry individual events GameTora hasn't translated; those come through with
        Japanese names or option text and must be skipped.

        Args:
            name (str): The event's display name.
            options (List[str]): The event's rendered option strings.

        Returns:
            True when the name or any option contains Japanese characters.
        """
        return bool(EVENT_CJK_PATTERN.search(name)) or any(EVENT_CJK_PATTERN.search(o) for o in options)

    def _read_existing(self) -> Dict[str, Dict[str, List[str]]]:
        """Reads this scraper's current output file from disk.

        Returns:
            The existing file contents, or an empty dict when the file is missing or unreadable.
        """
        if not os.path.exists(self.output_filename):
            return {}
        try:
            with open(self.output_filename, "r", encoding="utf-8") as f:
                return json.load(f)
        except (json.JSONDecodeError, OSError) as exc:
            logging.warning(f"Could not read existing {os.path.basename(self.output_filename)} ({exc.__class__.__name__}).")
            return {}

    def _render_reward(self, reward: Dict[str, Any], card_char: str, energy_mult: float, stat_mult: float) -> str:
        """Renders one reward object into its displayed line, mirroring GameTora's English templates.

        Args:
            reward (Dict[str, Any]): The reward, shaped like {"t": code, "v": value, "d": id, "r": is_random}.
            card_char (str): The card's character name, used for an untargeted bond.
            energy_mult (float): The card's Event Recovery multiplier (applied to energy).
            stat_mult (float): The card's Event Effectiveness multiplier (applied to stats and skill points).

        Returns:
            The rendered reward line.
        """
        code, value, target = reward.get("t"), reward.get("v"), reward.get("d")
        prefix = "(random) " if reward.get("r") else ""
        if isinstance(value, int):
            value = f"{value:+d}"  # Some values arrive as bare integers (e.g. 65); display them signed like the string ones.
        if code in EVENT_ENERGY_CODES:
            value = self._scale(value, energy_mult)
        elif code in EVENT_STAT_CODES:
            value = self._scale(value, stat_mult)
        # Stat and energy ranges are space-padded ("+15 / +20"); Mood keeps its raw "+2/+1".
        spaced = value if code == "mo" else (value.replace("/", " / ") if isinstance(value, str) else value)
        if code == "nl":
            return ""  # A blank-line separator between conditional branches; renders to nothing on its own line.
        if code in EVENT_SIMPLE_REWARDS:
            return f"{prefix}{EVENT_SIMPLE_REWARDS[code]} {spaced}"
        if code in EVENT_FLAT_REWARDS:
            return prefix + EVENT_FLAT_REWARDS[code]
        if code in EVENT_CONDITION_REWARDS:
            return prefix + EVENT_CONDITION_REWARDS[code]
        if code == "5s":
            return f"{prefix}All stats {spaced}"
        if code == "rs":
            return f"{prefix}1 random stat {spaced}" if target == 1 else f"{prefix}{target} random stats {spaced}"
        if code == "unspecified_stats":
            return f"{prefix}1 stat {spaced}" if target == 1 else f"{prefix}{target} stats {spaced}"
        if code == "sk":
            return f"{prefix}{_event_skill_names.get(target, target)} hint {value}"
        if code == "sg":
            return f"{prefix}Obtain {_event_skill_names.get(target, target)} skill"
        if code == "sre":
            return f"{prefix}Lose the {_event_skill_names.get(target, target)} skill"
        if code == "sr":
            return prefix + " or ".join(f"{_event_skill_names.get(x['d'], x['d'])} hint {x['v']}" for x in target)
        if code == "se":
            return f"{prefix}Get {_event_status_names.get(target, target)} status"
        if code == "he":
            return f"{prefix}Heal {_event_status_names.get(target, target)}" if target is not None else f"{prefix}Heal a negative status effect"
        if code == "hp":
            return f"{prefix}Heal {_event_skill_names.get(target, target)}"
        if code == "ps_h":
            return f"{prefix}※ {_event_skill_names.get(target, target)} was healed"
        if code == "ps_nh":
            return f"{prefix}※ {_event_skill_names.get(target, target)} was not healed"
        if code == "se_h":
            return f"{prefix}※ {_event_status_names.get(target, target)} was healed"
        if code == "se_nh":
            return f"{prefix}※ {_event_status_names.get(target, target)} was not healed"
        if code == "bo":
            who = _event_char_names.get(target, card_char) if target is not None else card_char
            return f"{prefix}{who} bond {value}"
        if code == "bo_ch":
            return f"{prefix}{_event_char_names.get(EVENT_ACADEMY_CHAR, EVENT_ACADEMY_CHAR)} bond {value}"
        if code == "bo_l":
            return f"{prefix}Bond of the support with the lowest bond (apart from this card) {value}"
        if code == "bo_l_c":
            return f"{prefix}Bond of the support with the lowest bond {value}" if target == 1 else f"{prefix}Bond of {target} supports with the lowest bond {value}"
        if code == "bo_r":
            return f"{prefix}Bond of {target} random support cards {value}"
        if code == "rc":
            return f"{prefix}Objective race changed to {_event_race_names.get(target, target)}"
        if code == "rh":
            return f"{prefix}Increased difficulty of {_event_race_names.get(target, target)}"
        if code == "ra":
            return f"{prefix}Objective race {_event_race_names.get(target, target)} cancelled"
        if code == "rl":
            return f"{prefix}Cannot race for one turn" if target == 1 else f"{prefix}Cannot race for {target} turns"
        if code == "fd":
            return f"{prefix}{target} random types of training will be disabled for one turn"
        if code == "et":
            return f"{prefix}Event 「{_event_event_names.get(str(target), target)}」 will occur next turn"
        if code == "ttl_gauge":
            return f"{prefix}{_event_char_names.get(target, target)}'s Instruction gauge {value}"
        if code == "sga":
            return f"{prefix}Star Gauge of {target} random characters {value}"
        if code == "fans_minimum":
            return f"{prefix}※ At least {target} fans"
        if code == "fans_maximum":
            return f"{prefix}※ Less than {target} fans"
        if code == "sl":
            return f"{prefix}※ If {_event_char_names.get(target, target)} is scenario-linked:"
        if code == "nsl":
            return f"{prefix}※ If not scenario-linked:"
        if code == "w_e":
            return f"{prefix}※ {value} wins"
        if code == "brf":
            return f"{prefix}※ Will affect the outcome of the {self._ordinal(target)} event"
        if code == "brp":
            return f"{prefix}※ Can only happen if you chose the {self._ordinal(value)} option during the {self._ordinal(target)} event"
        if code == "bp2":
            return f"{prefix}※ If you chose the {self._ordinal(value)} option during both previous two chain events"
        if code == "ct":
            return f"{prefix}{target}"
        if code == "sc":
            return f"{prefix}{self._render_condition(target)}"
        if code == "mt":
            return f"{prefix}Performance token you have the least of {value}"
        if code == "yhs_tix":
            return f"{prefix}Onsen tickets {value}"
        if code == "all_disc":
            return f"{prefix}All discipline levels {value}"
        if code == "veggies":
            return f"{prefix}All vegetables {value}"
        # Fallback for rare scenario-only codes so nothing silently vanishes from the output.
        return f"{prefix}{code} {value}".strip()

    @staticmethod
    def _render_condition(detail: Any) -> str:
        """Renders an `sc` reward's branch condition (a [type, args...] list) with GameTora's leading condition marker.

        Args:
            detail (Any): The condition payload, e.g. ["s_gn_race_wn_c", strategy, grade, count] or ["class", class_id].

        Returns:
            The rendered condition line.
        """
        if not isinstance(detail, list) or not detail:
            return f"※ {detail}"
        kind = detail[0]
        if kind == "s_gn_race_wn_c" and len(detail) >= 4:
            return f"※ Get a win streak of {detail[3]}+ {EVENT_GRADE_NAMES.get(detail[2], detail[2])} races as {EVENT_STRATEGY_NAMES.get(detail[1], detail[1])}"
        if kind == "class" and len(detail) >= 2:
            return f"※ {EVENT_CLASS_NAMES.get(detail[1], detail[1])}"
        return f"※ {kind}"

    def _render_choice(self, rewards: List[Dict[str, Any]], card_char: str, energy_mult: float, stat_mult: float) -> str:
        """Renders one choice's reward list, splitting "di" markers into "Randomly either" outcome groups.

        A plain "di" separates groups with dashed dividers. A "di" carrying a percentage (e.g. "~90") fences each later
        branch with its "or (~X%)" line, shown twice as GameTora does. "nl" rewards render to blank lines that separate
        the conditional branches built from "ct" / "sc" / motivation / result headers.

        Args:
            rewards (List[Dict[str, Any]]): The choice's reward objects.
            card_char (str): The card's character name.
            energy_mult (float): The card's Event Recovery multiplier.
            stat_mult (float): The card's Event Effectiveness multiplier.

        Returns:
            The rendered, newline-joined option text.
        """
        groups = [[]]
        percents = [None]
        for reward in rewards:
            if reward.get("t") in EVENT_DIVIDER_CODES:
                groups.append([])
                percents.append(reward.get("d"))
            else:
                groups[-1].append(self._render_reward(reward, card_char, energy_mult, stat_mult))
        if len(groups) == 1:
            return "\n".join(groups[0])
        if any(percents):
            shown = [(pct, g) for pct, g in zip(percents, groups) if g]
            parts = ["\n".join(g) if i == 0 else f"or ({pct}%)\nor ({pct}%)\n" + "\n".join(g) for i, (pct, g) in enumerate(shown)]
            return "Randomly either\n----------\n" + "\n".join(parts)
        return "Randomly either\n----------\n" + "\n----------\n\n----------\n".join("\n".join(g) for g in groups)

    def _event_display_name(self, category: str, index: int, event: Dict[str, Any]) -> str:
        """Builds an event's display-name key, adding the chain-level arrow prefix and timing suffix GameTora shows.

        Args:
            category (str): The event category (e.g. "random", "arrows", "dates").
            index (int): The event's position within its category, used for the arrow count.
            event (Dict[str, Any]): The event object, with "n" (name) and optional "type".

        Returns:
            The display name used as the event's key.
        """
        name = event["n"]
        relevant_char = event.get("relevant_char")
        if relevant_char is not None:  # Group/scenario cards tag each event with the featured member.
            name = name + "\n" + str(_event_char_names.get(relevant_char, relevant_char))
        elif category in ("arrows", "dates"):  # Chained events show one unlock arrow per level.
            name = "(" + EVENT_ARROW * (index + 1) + ")\n" + name
        if event.get("type") in EVENT_TYPE_LABELS:
            name = name + "\n" + EVENT_TYPE_LABELS[event["type"]]
        return name

    def _ingest_events(self, card_events: Dict[str, List[str]], categories, char_name: str, energy_mult: float, stat_mult: float):
        """Renders a card's events and adds the localized, not-yet-present ones to the character's event dict.

        Existing events are kept (setdefault), so curated values are never overwritten, and events still carrying
        untranslated Japanese text are skipped as not yet on the Global server.

        Args:
            card_events (Dict[str, List[str]]): The character's accumulated events, mutated in place.
            categories: An iterable of (category, events) pairs from the card's event data.
            char_name (str): The card's character name, used to render targeted rewards.
            energy_mult (float): The card's Event Recovery multiplier.
            stat_mult (float): The card's Event Effectiveness multiplier.
        """
        for category, entries in categories:
            for entry_index, event in enumerate(entries):
                name = self._event_display_name(category, entry_index, event)
                options = [self._render_choice(c["r"], char_name, energy_mult, stat_mult) for c in event["c"]]
                if not self._is_unlocalized(name, options):
                    card_events.setdefault(name, options)


class CharacterScraper(TrainingEventScraper):
    """Builds character training events from GameTora's page-data JSON (no browser needed).

    Each character's choice events (story, outings, secret, and version variants) plus the templated "Dance Lesson"
    and "New Year's Resolutions" are rendered from JSON, with the oldest card's version winning on shared events. The
    events that are identical for every character (After a Race, the seasonal bonuses, Etsuko's coverage, ...) live
    only in GameTora's opaque shared dataset, so they are carried over from the existing characters.json, matching the
    previous scraper's "After a Race" caching behaviour.
    """

    # Character event categories holding a normal list of choice events. "nochoice" single-outcome story beats are
    # intentionally excluded (the previous scraper never captured them); "dance"/"nyear" are templated separately.
    CHOICE_CATEGORIES = ("wchoice", "version", "outings", "secret")

    def __init__(self):
        super().__init__("https://gametora.com/umamusume/characters", "characters.json")

    @staticmethod
    def _common_events(existing: Dict[str, Dict[str, List[str]]]) -> Dict[str, List[str]]:
        """Finds the events shared identically across at least half of the existing characters.

        Args:
            existing (Dict[str, Dict[str, List[str]]]): The current characters.json contents.

        Returns:
            A mapping of shared event name to its options.
        """
        counts: Dict[Tuple[str, str], int] = {}
        for events in existing.values():
            for name, options in events.items():
                key = (name, json.dumps(options, ensure_ascii=False))
                counts[key] = counts.get(key, 0) + 1
        threshold = max(1, len(existing) // 2)
        return {name: json.loads(options) for (name, options), count in counts.items() if count >= threshold}

    @staticmethod
    def _templated_events(events: Dict[str, Any]) -> Dict[str, List[str]]:
        """Builds the per-character templated events ("Dance Lesson", "New Year's Resolutions") from their stat codes.

        Args:
            events (Dict[str, Any]): The character's event data, whose "dance"/"nyear" entries are bare stat codes.

        Returns:
            A mapping of the templated event names to their options (empty when the codes are absent).
        """
        built: Dict[str, List[str]] = {}
        dance = events.get("dance")
        if isinstance(dance, list):
            # The lesson grants +10 to each listed stat, but +20 when the listed reward is skill points.
            built["Dance Lesson"] = ["Skill points +20" if c == "pt" else f"{EVENT_SIMPLE_REWARDS.get(c, c)} +10" for c in dance]
        nyear = events.get("nyear")
        if isinstance(nyear, str):
            built["New Year's Resolutions"] = [f"{EVENT_SIMPLE_REWARDS.get(nyear, nyear)} +10", "Energy +20", "Skill points +20"]
        return built

    def start(self):
        """Adds newly-released Global character events to characters.json, preserving the existing curated data.

        Existing events are kept as-is (additive merge), so the user's curated Global values are never overwritten by
        GameTora's JP-current values. Only Global-released cards are read, and only event names not already present are
        added. The shared events (After a Race, ...) are carried onto any brand-new characters.
        """
        self._load_lookups()
        self.data = self._read_existing()
        common = self._common_events(self.data)
        # Oldest card first so its version wins on shared events.
        cards = sorted(fetch_gametora_manifest_data("character-cards"), key=lambda c: c["card_id"])
        for index, card in enumerate(cards):
            if not self._is_global_release(card):
                continue
            char_name = _event_char_names.get(card["char_id"])
            if char_name is None:
                continue
            try:
                events = fetch_gametora_event_data("characters", card["url_name"])
            except (requests.exceptions.RequestException, ValueError) as exc:
                logging.warning(f"Skipping character card {card['url_name']} ({exc.__class__.__name__}).")
                continue
            char_events = self.data.setdefault(char_name, {})
            self._ingest_events(char_events, ((cat, events.get(cat) or []) for cat in self.CHOICE_CATEGORIES), char_name, 1.0, 1.0)
            for name, options in self._templated_events(events).items():
                char_events.setdefault(name, options)
            if (index + 1) % 100 == 0:
                logging.info(f"Processed {index + 1}/{len(cards)} character cards.")
        # Carry the identical shared events (After a Race, seasonal bonuses, ...) onto any newly-added characters.
        for char_events in self.data.values():
            for name, options in common.items():
                char_events.setdefault(name, options)
        self.data = {name: events for name, events in self.data.items() if events}
        logging.info(f"characters.json now has {len(self.data)} characters from GameTora JSON.")
        self.save_data()


class SupportCardScraper(TrainingEventScraper):
    """Builds support-card training events from GameTora's page-data JSON (no browser needed).

    Each support card's events are grouped under its character name. When several cards of one character share an
    event, the oldest card's version wins (lower support_id == earlier release), matching the previous scraper's
    release-ordered merge. Energy and stat rewards are scaled by the card's Event Recovery / Event Effectiveness so
    the values match the in-game maximum the page displays.
    """

    def __init__(self):
        super().__init__("https://gametora.com/umamusume/supports", "supports.json")

    @staticmethod
    def _event_multipliers(card: Dict[str, Any]) -> Tuple[float, float]:
        """Reads a support card's max-level Event Recovery and Event Effectiveness as reward multipliers.

        Args:
            card (Dict[str, Any]): The support-cards entry, whose `effects` rows are [type, value-per-level...].

        Returns:
            An (energy_mult, stat_mult) tuple. Event Recovery (type 25) scales energy, Event Effectiveness (type 26) scales stats.
        """
        maxed = {}
        for row in card["effects"]:
            values = [v for v in row[1:] if v != -1]
            if values:
                maxed[row[0]] = max(values)
        return 1 + maxed.get(25, 0) / 100, 1 + maxed.get(26, 0) / 100

    def start(self):
        """Adds newly-released Global support-card events to supports.json, preserving the existing curated data.

        Existing events are kept as-is (additive merge) so curated Global values are never overwritten by GameTora's
        JP-current values. Only Global-released cards are read, and only event names not already present are added.
        """
        self._load_lookups()
        self.data = self._read_existing()
        # Oldest card first so its version wins on shared events.
        cards = sorted(fetch_gametora_manifest_data("support-cards"), key=lambda c: c["support_id"])
        for index, card in enumerate(cards):
            if not self._is_global_release(card):
                continue
            try:
                events = fetch_gametora_event_data("supports", card["url_name"])
            except (requests.exceptions.RequestException, ValueError) as exc:
                logging.warning(f"Skipping support card {card['url_name']} ({exc.__class__.__name__}).")
                continue
            char_name = SUPPORT_CARD_NAME_OVERRIDES.get(card["char_name"], card["char_name"])
            energy_mult, stat_mult = self._event_multipliers(card)
            card_events = self.data.setdefault(char_name, {})
            self._ingest_events(card_events, events.items(), char_name, energy_mult, stat_mult)
            if (index + 1) % 100 == 0:
                logging.info(f"Processed {index + 1}/{len(cards)} support cards.")
        self.data = {name: events for name, events in self.data.items() if events}
        logging.info(f"supports.json now has {len(self.data)} characters from GameTora JSON.")
        self.save_data()


class RaceScraper(BaseScraper):
    """Builds the race list from GameTora's race_instances JSON dataset (no browser needed).

    Each entry in `race_instances` is one scheduled occurrence of a race on the career calendar: it carries the
    year/month/half placement plus the full race `details` and a `fans_gain` id into the `race-fans` reward tables.
    We keep only EN-released, non-special races - the same set the old page scrape produced. Full rebuild so races
    that leave the EN calendar do not linger.
    """

    # GameTora numeric codes -> the labels the Smart Race Solver expects, derived from the live race datasets.
    COURSE_CODES = {1: None, 2: "Inner", 3: "Outer"}
    TERRAIN_CODES = {1: "Turf", 2: "Dirt"}
    TRACK_CODES = {
        10001: "Sapporo", 10002: "Hakodate", 10003: "Niigata", 10004: "Fukushima", 10005: "Nakayama",
        10006: "Tokyo", 10007: "Chukyo", 10008: "Kyoto", 10009: "Hanshin", 10010: "Kokura", 10101: "Ooi",
    }
    # Career-calendar pieces used to build the date string and turn number.
    MONTH_NAMES = {1: "January", 2: "February", 3: "March", 4: "April", 5: "May", 6: "June",
                   7: "July", 8: "August", 9: "September", 10: "October", 11: "November", 12: "December"}
    HALF_NAMES = {1: "First Half", 2: "Second Half"}

    def __init__(self):
        super().__init__("https://gametora.com/umamusume/races", "races.json")

    @staticmethod
    def _distance_type(meters: int) -> str:
        """Buckets a race distance in meters into the in-game distance category.

        Args:
            meters (int): The race distance in meters.

        Returns:
            One of "Sprint", "Mile", "Medium", or "Long".
        """
        if meters <= 1400:
            return "Sprint"
        if meters <= 1800:
            return "Mile"
        if meters <= 2400:
            return "Medium"
        return "Long"

    def start(self):
        """Builds the race list from GameTora's race_instances + race-fans JSON datasets (no browser needed)."""
        self.data = {}
        instances = fetch_gametora_manifest_data("race_instances")
        race_fans = fetch_gametora_manifest_data("race-fans")
        first_place_fans = {entry["id"]: next((f["fans"] for f in entry["fans"] if f["order"] == 1), 0) for entry in race_fans}

        for instance in instances:
            # Skip the fixed special races (Make Debut, Maiden, URA Finals, etc.) and any race not yet on the EN server.
            if instance.get("special_race"):
                continue
            details = instance["details"]
            if details.get("did_not_exist") is not None:
                continue

            date = f"{EVENT_CLASS_NAMES[instance['year']]} {self.MONTH_NAMES[instance['month']]}, {self.HALF_NAMES[instance['half']]}"
            track = self.TRACK_CODES[details["track"]]
            course = self.COURSE_CODES[details["course"]]
            direction = "Right" if details["direction"] == 1 else "Left"  # GameTora: 1 = clockwise/right, everything else is left.
            terrain = self.TERRAIN_CODES[details["terrain"]]
            distance_meters = details["distance"]
            distance_type = self._distance_type(distance_meters)

            distance_type_formatted = "Med" if distance_type == "Medium" else distance_type
            name_formatted = f"{track} {terrain} {distance_meters}m ({distance_type_formatted}) {direction}"
            if course:
                name_formatted += f" / {course}"

            self.data[f"{details['name_en']} ({date})"] = {
                "name": details["name_en"],
                "date": date,
                "raceTrack": track,
                "course": course,
                "direction": direction,
                "grade": EVENT_GRADE_NAMES[details["grade"]],
                "terrain": terrain,
                "distanceType": distance_type,
                "distanceMeters": distance_meters,
                "fans": first_place_fans[instance["fans_gain"]],
                "turnNumber": (instance["year"] - 1) * 24 + (instance["month"] - 1) * 2 + (1 if instance["half"] == 1 else 2),
                "nameFormatted": name_formatted,
            }

        logging.info(f"Built {len(self.data)} races from GameTora JSON.")
        self.save_data()


class EpithetScraper(BaseScraper):
    """Scrapes the epithets/nicknames from GameTora.

    Each epithet's row on GameTora is a free-text bullet list - scenario restriction (when
    present), conditions, qualifiers, then the reward. The Smart Race Solver stores these
    bullets verbatim into `bullet_points` and derives every structured property it needs
    from them at runtime: reward kind/amount, scenario gate, and the AND-list of race-win
    matchers the solver evaluates. `matchers` are derived here in the scraper via
    `derive_matchers` so a re-scrape always rebuilds them from current bullet text - no
    hand-curation step is required.
    """

    # Fields owned by the scraper.
    SCRAPED_FIELDS = (
        "name",
        "bullet_points",
        "scenarios",
        "characters",
        "matchers",
    )

    # Regex matching GameTora's `<X> scenario only` bullet. Group 1 captures the scenario.
    _SCENARIO_RESTRICTION_RE = re.compile(r"([A-Za-z][A-Za-z0-9 \-]*?) scenario only", re.IGNORECASE)

    # Regex matching GameTora's character-restriction bullet, e.g. `Yaeno Muteki only`.
    # Anchored so bullets with extra words (e.g. "Win 5 races as a Late Surger only")
    # never qualify. Bullets containing `scenario only` are filtered out by the caller.
    _CHARACTER_RESTRICTION_RE = re.compile(r"^(.+?)\s+only$")

    # A race template's optional 3rd param is the career year the win must happen in, rendered as a suffix.
    _RACE_YEAR_SUFFIX = {"1": " (Junior)", "2": " (Classic)", "3": " (Senior)"}

    # //////////////////////////////////////////////////////////////////////////////////////////////////
    # //////////////////////////////////////////////////////////////////////////////////////////////////
    # Bullet -> matcher derivation

    # Number-word lookup so "Win three races..." is treated identically to "Win 3 races...".
    _NUMBER_WORDS = {
        "one": 1, "two": 2, "three": 3, "four": 4, "five": 5,
        "six": 6, "seven": 7, "eight": 8, "nine": 9, "ten": 10,
    }
    # "twice" / "three times" / etc. -> numeric times count for `winRaceTimes`.
    _TIMES_WORDS = {
        "twice": 2, "three times": 3, "four times": 4,
        "five times": 5, "six times": 6, "seven times": 7,
    }

    # Whitelisted descriptor tokens for the `winCount` filter. Anything outside this set
    # disqualifies the bullet so the parser never produces a partially-correct matcher that
    # would over-fire (e.g. "Win 5 G1 races with a Mood level of Bad" - the Mood clause is
    # not representable, so we skip the whole bullet rather than emit a too-broad winCount).
    _TERRAIN_WORDS = {"dirt": "Dirt", "turf": "Turf"}
    _GRADE_WORDS = {"g1": "G1", "g2": "G2", "g3": "G3", "op": "OP"}
    # Includes GameTora's hyphenated forms ("short-distance", "medium-distance",
    # "long-distance"). "Sprint" / "Mile" / "Medium" / "Long" mirror the Kotlin
    # `TrackDistance` enum.
    _DISTANCE_WORDS = {
        "sprint": "Sprint",
        "short-distance": "Sprint",
        "mile": "Mile",
        "mile-length": "Mile",
        "medium": "Medium",
        "medium-distance": "Medium",
        "long": "Long",
        "long-distance": "Long",
    }
    # Distance shorthand: "core" = Mile + Medium, "non-core" = Sprint + Long. Mirrors
    # the in-game grouping used by Standard / Non-Standard Distance Leader.
    _DISTANCE_GROUP_WORDS = {
        "core": ["Mile", "Medium"],
        "non-core": ["Sprint", "Long"],
    }

    # Substrings that, when present in a bullet, mark it as carrying a sub-clause the
    # parser can't represent. Bullets matching any of these are dropped silently.
    # `with` / `that have` are intentionally not blanket-blocked - GameTora uses both
    # for representable filters (e.g. `with 'Junior Stakes' in their name`,
    # `that are held in either Sapporo or Hakodate`). The dedicated sub-parsers handle
    # those shapes before the generic block runs.
    _UNREPRESENTABLE_MARKERS = (
        " with a difference ", " with a length ", " with a mood ", " with at least ",
        " while ", " as a ", " as the ", " as most ",
        " without ", " having ", " before ", " inbetween ", " between ",
        " inherit ", " place higher ", " trigger ", " buy ",
        " activate ", " reach ", " earn ", " have ", " be at ", " complete the career",
        " raise the level", " mood level", " most popular", " single race",
        " parent ", " parents ", " from a parent", " from parents",
    )

    # Bullets that begin with these prefixes never describe a race-win condition.
    _NON_WIN_PREFIXES = (
        "reach ", "earn ", "have ", "be at ", "inherit ", "complete ", "raise ",
        "trigger ", "buy ", "activate ", "place ", "finish ", "without ",
    )

    def __init__(self):
        super().__init__("https://gametora.com/umamusume/nicknames", "epithets.json")

    def start(self):
        """Builds epithets from GameTora's nicknames JSON dataset (no browser needed).

        Each epithet's EN bullet list is reconstructed from `nicknames` with all [[...]] templates resolved, then
        `scenarios` / `characters` / `matchers` are derived from those bullets exactly as before. Full rebuild so
        epithets that leave the EN/global list do not linger.
        """
        self.data = {}
        scraped = self._build_epithets_from_json()
        logging.info(f"Built {len(scraped)} epithets from GameTora JSON.")

        # Regenerate the structured fields from the bullet text on every run so GameTora copy changes flow straight
        # into solver matchers with no hand-curation step.
        for name, fresh in scraped.items():
            bullets = fresh["bullet_points"]
            self.data[name] = {
                "name": fresh["name"],
                "bullet_points": bullets,
                "scenarios": self.derive_scenarios(bullets),
                "characters": self.derive_characters(bullets),
                "matchers": self.derive_matchers(bullets),
            }

        self.save_data()

    @classmethod
    def derive_scenarios(cls, bullets: List[str]) -> List[str]:
        """Pulls scenario-restriction names out of the bullet list.

        Each `<X> scenario only` bullet contributes the captured scenario name. An empty
        list means the epithet is universally obtainable across every scenario. Mirrors
        `EpithetFilters.scenariosFromBullets` in Kotlin and `scenariosForEpithet` in TS.

        Args:
            bullets: The epithet's `bullet_points` array as scraped from GameTora.

        Returns:
            Distinct scenario names referenced by any restriction bullet, in order.
        """
        out: List[str] = []
        seen: set = set()
        for raw in bullets:
            for m in cls._SCENARIO_RESTRICTION_RE.finditer(raw):
                name = m.group(1).strip()
                if name and name not in seen:
                    seen.add(name)
                    out.append(name)
        return out

    @classmethod
    def derive_characters(cls, bullets: List[str]) -> List[str]:
        """Pulls character-restriction names out of the bullet list.

        Each standalone `<character name> only` bullet contributes the captured name.
        Bullets containing `scenario only` are skipped so the two restriction kinds never
        collide. An empty list means the epithet has no character gate. Mirrors
        `EpithetFilters.charactersFromBullets` in Kotlin and `charactersForEpithet` in TS.

        Args:
            bullets: The epithet's `bullet_points` array as scraped from GameTora.

        Returns:
            Distinct character names referenced by any standalone restriction bullet.
        """
        out: List[str] = []
        seen: set = set()
        for raw in bullets:
            trimmed = raw.strip().rstrip(".")
            if "scenario only" in trimmed.lower():
                continue
            m = cls._CHARACTER_RESTRICTION_RE.fullmatch(trimmed)
            if m is None:
                continue
            name = m.group(1).strip()
            if name and name not in seen:
                seen.add(name)
                out.append(name)
        return out

    @classmethod
    def derive_matchers(cls, bullets: List[str]) -> List[Dict[str, Any]]:
        """Builds the AND-combined `matchers` list for an epithet from its bullet text.

        Each bullet is run through a strict pattern cascade. Bullets that match a known
        race-win shape contribute one (or more) structured matcher entries. Everything
        else is dropped. The conservative approach prevents partially-recognised bullets
        from over-firing - the solver would rather miss a matcher than mis-complete an
        epithet that still has unfulfilled conditions.

        Recognised bullet shapes (case-insensitive):

        - `Get [either] the X[, Y, ... [and|or] Z] epithet[s]` -> `epithetAll` /
          `epithetAnyOf` (the `either` keyword switches to the disjunctive form).
        - `Win any (N|<word>) of [the] A, B, ... [and|or] Z` -> `winAnyOf` with `count=N`.
        - `Win [either] the X or [the] Y` -> `winAnyOf` with `count=1`.
        - `Win [at least|exactly] N <descriptor> races?` where `<descriptor>` is composed
          only of whitelisted terrain / grade / distance / "graded" tokens -> `winCount`
          with the corresponding `filter`. The "country's name" idiom maps to
          `nameContainsCountry: true` for the Globe-Trotter epithet.
        - `Win the X[, Y, ... [and|or] Z]` -> one `winRace` per name, with `atClass` lifted
          from any `(Junior|Classic|Senior)` qualifier.
        - `Win the X (twice|N times)` -> `winRaceTimes`.

        Args:
            bullets: The epithet's `bullet_points` array as scraped from GameTora.

        Returns:
            Ordered list of structured matchers. Empty when no bullet matched any
            recognised shape.
        """
        out: List[Dict[str, Any]] = []
        for raw in bullets:
            b = raw.strip().rstrip(".")
            if not b:
                continue
            lower = b.lower()
            # Skip the reward bullet and the scenario-restriction bullet outright.
            if lower.startswith("reward:"):
                continue
            if "scenario only" in lower:
                continue
            # Skip bullets carrying sub-clauses we can't represent. Emitting a
            # partial matcher here would mark the epithet completable on conditions
            # the user hasn't actually met.
            if any(marker in (" " + lower + " ") for marker in cls._UNREPRESENTABLE_MARKERS):
                continue
            if any(lower.startswith(p) for p in cls._NON_WIN_PREFIXES):
                continue

            matcher = (
                cls._parse_get_epithet(b)
                or cls._parse_win_any_of(b)
                or cls._parse_win_count_at_tracks(b)
                or cls._parse_win_count_name_contains(b)
                or cls._parse_win_count_country_idiom(b)
                or cls._parse_win_count_grade_open(b)
                or cls._parse_win_one_per_distance(b)
                or cls._parse_win_count(b)
                or cls._parse_win_either_or(b)
                or cls._parse_win_races(b)
            )
            if matcher is None:
                continue
            if isinstance(matcher, list):
                for entry in matcher:
                    cls._attach_display_label(entry)
                    out.append(entry)
            else:
                cls._attach_display_label(matcher)
                out.append(matcher)
        return out

    @classmethod
    def _attach_display_label(cls, matcher: Dict[str, Any]) -> None:
        """Stamps `displayLabel` / `displayLabelTemplate` onto `matcher` in place.

        These fields are the canonical condition strings consumed by the React popover, the
        Race History tooltip in `log_viewer.html`, and the Kotlin win log. Synthesizing them
        once at scrape time means no runtime layer needs its own filter -> phrase translation,
        so the three surfaces can no longer drift apart on wording.

        Args:
            matcher: A matcher dict freshly produced by one of the `_parse_*` helpers. The
                relevant key is mutated in place. Dependency matchers
                (`epithetAll`, `epithetAnyOf`) gain neither field.
        """
        t = matcher.get("type")
        if t == "winRace":
            name = matcher.get("name")
            if name:
                matcher["displayLabel"] = f"Win the {name}"
        elif t == "winRaceTimes":
            name = matcher.get("name")
            times = matcher.get("times")
            if name and times is not None:
                matcher["displayLabel"] = f"Win the {name} ({times} times)"
            elif name:
                matcher["displayLabel"] = f"Win the {name}"
        elif t in ("winAnyOf", "winAtLeast"):
            matcher["displayLabelTemplate"] = "Win the {race}"
        elif t == "winCount":
            count = matcher.get("count", 1)
            phrase = cls._describe_filter(matcher.get("filter") or {})
            if count != 1:
                phrase = re.sub(r"race$", "races", phrase)
            matcher["displayLabel"] = f"Win {count} {phrase}"

    @classmethod
    def _describe_filter(cls, f: Dict[str, Any]) -> str:
        """Synthesizes the noun phrase describing a `winCount` matcher's filter clause.

        Field order mirrors the Kotlin / TypeScript convention: grade -> OP+ -> graded -> distanceTypes -> terrain -> nameContainsCountry -> nameContains -> raceTracks -> "race".
        This is the only place in the codebase that turns filter shapes into English; both
        runtimes consume the result via `displayLabel`.

        Args:
            f: The filter dict from a `winCount` matcher.

        Returns:
            A noun phrase like `"G1 Sprint/Mile Turf race"` suitable for prefixing with `"Win N "`.
        """
        parts: List[str] = []
        grade = f.get("grade")
        if grade:
            parts.append(grade)
        if f.get("gradeAtLeastOpen"):
            parts.append("OP+")
        if f.get("gradedOnly"):
            parts.append("graded")
        dts = f.get("distanceTypes") or []
        if dts:
            parts.append("/".join(d[0].upper() + d[1:].lower() for d in dts))
        terrain = f.get("terrain")
        if terrain:
            parts.append(terrain[0].upper() + terrain[1:].lower())
        if f.get("nameContainsCountry"):
            parts.append("country-named")
        nc = f.get("nameContains")
        if nc:
            parts.append(f'"{nc}"-named')
        tracks = f.get("raceTracks") or []
        if tracks:
            parts.append("at " + "/".join(tracks))
        parts.append("race")
        return " ".join(parts)

    @classmethod
    def _parse_get_epithet(cls, b: str) -> Optional[Dict[str, Any]]:
        """Parses `Get [either] the X[, Y[ and|or] Z] epithet[s]` into `epithetAll` / `epithetAnyOf`.

        Args:
            b: The bullet text to match (already stripped of leading/trailing whitespace and trailing period).

        Returns:
            The matcher dict, or None when `b` doesn't match the prefix.
        """
        m = re.match(r"^Get\s+(either\s+)?the\s+(.+?)\s+epithets?$", b, re.IGNORECASE)
        if not m:
            return None
        is_either = bool(m.group(1))
        names = cls._split_name_list(m.group(2))
        if not names:
            return None
        kind = "epithetAnyOf" if is_either else "epithetAll"
        return {"type": kind, "names": [n for n, _ in names]}

    @classmethod
    def _parse_win_any_of(cls, b: str) -> Optional[Dict[str, Any]]:
        """Parses `Win any (N|<word>) of [the] A, B[, ... [and|or] Z]` into `winAtLeast`.

        GameTora's "any N of" phrasing maps to the distinct-race variant - racing the same horse twice doesn't count for two - so
        we emit `EpithetMatcher.WinAtLeast` rather than the looser `EpithetMatcher.WinAnyOf` which counts repeats.

        Args:
            b: The bullet text to match.

        Returns:
            The matcher dict, or None when `b` doesn't match the shape.
        """
        m = re.match(r"^Win\s+any\s+(\d+|[A-Za-z]+)\s+of\s+(?:the\s+)?(.+)$", b, re.IGNORECASE)
        if not m:
            return None
        count = cls._parse_count_word(m.group(1))
        if count is None:
            return None
        names = cls._split_name_list(m.group(2))
        if not names:
            return None
        return {"type": "winAtLeast", "names": [n for n, _ in names], "count": count}

    @classmethod
    def _parse_win_either_or(cls, b: str) -> Optional[Dict[str, Any]]:
        """Parses `Win [either] the X or [the] Y` into `winAnyOf` with `count=1`.

        Args:
            b: The bullet text to match.

        Returns:
            The matcher dict, or None when `b` doesn't match the shape.
        """
        m = re.match(r"^Win\s+(?:either\s+)?the\s+(.+?)\s+or\s+(?:the\s+)?(.+)$", b, re.IGNORECASE)
        if not m:
            return None
        # Reject if the right side itself contains another " or " - that's a list and
        # `_split_name_list` would handle it, but only via `_parse_win_any_of` which
        # has already run. Falling through avoids ambiguity.
        if " or " in m.group(2):
            return None
        a, ac_a = cls._strip_class(m.group(1).strip())
        b_name, ac_b = cls._strip_class(m.group(2).strip())
        if not a or not b_name:
            return None
        entry: Dict[str, Any] = {"type": "winAnyOf", "names": [a, b_name], "count": 1}
        if ac_a and ac_a == ac_b:
            entry["atClass"] = ac_a
        return entry

    @classmethod
    def _parse_win_count_name_contains(cls, b: str) -> Optional[Dict[str, Any]]:
        """Recognises `Win N races with 'X' in their name` (Junior Jewel, Umatastic) and produces a `winCount` with `nameContains: "X"`.

        The single-quoted substring may use either ASCII or curly quotes.

        Args:
            b: The bullet text to match.

        Returns:
            The matcher dict, or None when `b` doesn't match the shape.
        """
        m = re.match(
            r"^Win\s+(\d+|[A-Za-z]+)\s+races?\s+with\s+['‘’\"“”](.+?)['‘’\"“”]\s+in\s+their\s+name$",
            b,
            re.IGNORECASE,
        )
        if not m:
            return None
        count = cls._parse_count_word(m.group(1))
        if count is None:
            return None
        return {"type": "winCount", "count": count, "filter": {"nameContains": m.group(2)}}

    @classmethod
    def _parse_win_count_grade_open(cls, b: str) -> Optional[Dict[str, Any]]:
        """Recognises `Win N races of grade Open or higher` (Pro Racer) and produces a `winCount` with `gradeAtLeastOpen: true`.

        Args:
            b: The bullet text to match.

        Returns:
            The matcher dict, or None when `b` doesn't match the shape.
        """
        m = re.match(
            r"^Win\s+(\d+|[A-Za-z]+)\s+races?\s+of\s+grade\s+open\s+or\s+higher$",
            b,
            re.IGNORECASE,
        )
        if not m:
            return None
        count = cls._parse_count_word(m.group(1))
        if count is None:
            return None
        return {"type": "winCount", "count": count, "filter": {"gradeAtLeastOpen": True}}

    @classmethod
    def _parse_win_one_per_distance(cls, b: str) -> Optional[List[Dict[str, Any]]]:
        """Recognises `Win one [terrain] D1, D2[, ... and Dn] race` (Dirt Dancer, Turf Tussler).

        Emits a separate `winCount` per distance with `count=1`, each carrying the shared terrain filter when present. Returning a list
        lets `derive_matchers` flatten them into the AND list.

        Args:
            b: The bullet text to match.

        Returns:
            A list of matcher dicts (one per distance), or None when `b` doesn't match the shape.
        """
        m = re.match(r"^Win\s+one\s+(.+?)\s+races?$", b, re.IGNORECASE)
        if not m:
            return None
        descriptor = m.group(1)
        # Normalise " and " / " or " into commas to make tokenisation order-free.
        normalised = re.sub(r"\s+(?:and|or)\s+", ", ", descriptor, flags=re.IGNORECASE)
        tokens = [t.strip() for t in normalised.split(",") if t.strip()]
        # First word may be a terrain ("dirt"/"turf"), shared across the per-distance
        # matchers. The remaining tokens must each map to a single distance type.
        terrain: Optional[str] = None
        if tokens and tokens[0].lower().split()[0] in cls._TERRAIN_WORDS:
            head = tokens[0].lower().split()
            terrain = cls._TERRAIN_WORDS[head[0]]
            # Strip the terrain word out of the first token so the rest of it (e.g.
            # "short-distance" in "dirt short-distance") survives as a distance.
            rest = " ".join(head[1:]).strip()
            if rest:
                tokens[0] = rest
            else:
                tokens.pop(0)
        # Every remaining token must resolve to one distance. If even one fails, we
        # skip the bullet rather than emit a partial set.
        distances: List[str] = []
        for t in tokens:
            d = cls._DISTANCE_WORDS.get(t.lower())
            if d is None:
                return None
            distances.append(d)
        if not distances:
            return None
        out: List[Dict[str, Any]] = []
        for d in distances:
            f: Dict[str, Any] = {"distanceTypes": [d]}
            if terrain:
                f["terrain"] = terrain
            out.append({"type": "winCount", "count": 1, "filter": f})
        return out

    @classmethod
    def _parse_win_count_country_idiom(cls, b: str) -> Optional[Dict[str, Any]]:
        """Recognises GameTora's Globe-Trotter wording, `Win N races which include a country's name in their name`.

        Produces a `winCount` with the `nameContainsCountry` filter - the only filter shape that doesn't fit the token-based
        descriptor parser.

        Args:
            b: The bullet text to match.

        Returns:
            The matcher dict, or None when `b` doesn't match the shape.
        """
        m = re.match(
            r"^Win\s+(\d+|[A-Za-z]+)\s+races?\s+which\s+include\s+a\s+country['’]?s?\s+name",
            b,
            re.IGNORECASE,
        )
        if not m:
            return None
        count = cls._parse_count_word(m.group(1))
        if count is None:
            return None
        return {"type": "winCount", "count": count, "filter": {"nameContainsCountry": True}}

    @classmethod
    def _parse_win_count_at_tracks(cls, b: str) -> Optional[Dict[str, Any]]:
        """Recognises `Win N <descriptor> races (that are )?held in/at <track list>` and produces a `winCount` with `raceTracks`.

        Any `gradedOnly` flag picked up from the descriptor is preserved. Used by the Hokkaido Hotshot / Kanto Conqueror /
        Tohoku Top Dog / Kokura Constable / West Japan Whiz / Kyushu / Pro Racer epithets, which all describe their
        location filter this way.

        Args:
            b: The bullet text to match.

        Returns:
            The matcher dict, or None when `b` doesn't match the shape.
        """
        m = re.match(
            r"^Win\s+(?:at\s+least\s+|exactly\s+)?(\d+|[A-Za-z]+)\s+(.+?)\s+races?\s+(?:that\s+are\s+)?held\s+(?:in|at|on)\s+(?:either\s+)?(.+)$",
            b,
            re.IGNORECASE,
        )
        if not m:
            return None
        count = cls._parse_count_word(m.group(1))
        if count is None:
            return None
        descriptor = m.group(2).strip()
        filt = cls._parse_filter(descriptor) or {}
        if filt is None:
            return None
        track_list = cls._split_name_list(m.group(3))
        if not track_list:
            return None
        filt["raceTracks"] = [name for name, _ in track_list]
        return {"type": "winCount", "count": count, "filter": filt}

    @classmethod
    def _parse_win_count(cls, b: str) -> Optional[Dict[str, Any]]:
        """Parses `Win [at least|exactly] N <descriptor> races?` into `winCount`.

        Args:
            b: The bullet text to match.

        Returns:
            The matcher dict, or None when `b` doesn't match the shape.
        """
        m = re.match(
            r"^Win\s+(?:at\s+least\s+|exactly\s+)?(\d+|[A-Za-z]+)\s+(.+?)\s+races?$",
            b,
            re.IGNORECASE,
        )
        if not m:
            return None
        count = cls._parse_count_word(m.group(1))
        if count is None:
            return None
        descriptor = m.group(2).strip()
        # "races which include a country's name in their name" - the only special-case
        # idiom on GameTora that maps to the structured `nameContainsCountry` filter.
        if "country" in descriptor.lower() and "name" in descriptor.lower():
            return {"type": "winCount", "count": count, "filter": {"nameContainsCountry": True}}
        filt = cls._parse_filter(descriptor)
        if filt is None:
            return None
        return {"type": "winCount", "count": count, "filter": filt}

    @classmethod
    def _parse_win_races(cls, b: str) -> Optional[Union[Dict[str, Any], List[Dict[str, Any]]]]:
        """Parses `Win the X[, Y, ... [and] Z] [twice|N times]` into one or more `winRace` entries.

        Returns a single `winRaceTimes` entry when the bullet ends in a repeat qualifier. The leading `the` is required
        so race-count bullets (`Win 3 graded races that are held in either Sapporo or Hakodate`) don't accidentally
        split on their internal `or`.

        Args:
            b: The bullet text to match.

        Returns:
            A single `winRaceTimes` dict, a list of `winRace` dicts, or None when `b` doesn't match the shape.
        """
        # Detect a trailing repeat qualifier: " twice" / " three times" / etc.
        repeat: Optional[int] = None
        body = b
        for phrase, n in cls._TIMES_WORDS.items():
            suffix = f" {phrase}"
            if body.lower().endswith(suffix):
                repeat = n
                body = body[: -len(suffix)].rstrip()
                break

        m = re.match(r"^Win\s+the\s+(.+)$", body, re.IGNORECASE)
        if not m:
            return None
        names = cls._split_name_list(m.group(1))
        if not names:
            return None

        if repeat is not None:
            if len(names) != 1:
                # Ambiguous: "Win the A, B and C twice" - skip rather than guess.
                return None
            name, atclass = names[0]
            entry: Dict[str, Any] = {"type": "winRaceTimes", "name": name, "times": repeat}
            if atclass:
                entry["atClass"] = atclass
            return entry

        out: List[Dict[str, Any]] = []
        for name, atclass in names:
            entry = {"type": "winRace", "name": name}
            if atclass:
                entry["atClass"] = atclass
            out.append(entry)
        return out

    @classmethod
    def _parse_filter(cls, descriptor: str) -> Optional[Dict[str, Any]]:
        """Translates a `winCount` descriptor like `dirt G1` or `non-core distance` into a filter dict.

        Returns None when the descriptor contains a token that doesn't map to a whitelisted filter key - that's the
        safety guard that prevents partial matchers.

        Args:
            descriptor: The descriptor portion of the bullet (between count and `races?`).

        Returns:
            A filter dict, or None when any token in `descriptor` is unrecognised.
        """
        f: Dict[str, Any] = {}
        # The "distance" suffix in "core distance" / "non-core distance" is grammatical
        # filler - drop it so the group token resolves cleanly.
        cleaned = re.sub(r"\bdistance\b", " ", descriptor, flags=re.IGNORECASE)
        for token in cleaned.split():
            tl = token.lower().rstrip(",")
            if not tl:
                continue
            if tl in cls._TERRAIN_WORDS:
                f["terrain"] = cls._TERRAIN_WORDS[tl]
            elif tl in cls._GRADE_WORDS:
                f["grade"] = cls._GRADE_WORDS[tl]
            elif tl in cls._DISTANCE_WORDS:
                f.setdefault("distanceTypes", []).append(cls._DISTANCE_WORDS[tl])
            elif tl in cls._DISTANCE_GROUP_WORDS:
                # "core" / "non-core" expand to a fixed multi-distance set.
                f.setdefault("distanceTypes", []).extend(cls._DISTANCE_GROUP_WORDS[tl])
            elif tl == "graded":
                f["gradedOnly"] = True
            else:
                # Any unknown token disqualifies the entire bullet - bail rather than
                # produce a partially-correct filter that would over-fire.
                return None
        return f

    @classmethod
    def _parse_count_word(cls, raw: str) -> Optional[int]:
        """Returns the integer for `raw` (digit string or English number word).

        Args:
            raw: A digit string (e.g. "3") or English number word (e.g. "three").

        Returns:
            The integer value, or None when `raw` is neither a digit string nor a known number word.
        """
        s = raw.lower().strip()
        if s.isdigit():
            return int(s)
        return cls._NUMBER_WORDS.get(s)

    @classmethod
    def _split_name_list(cls, s: str) -> List[Tuple[str, Optional[str]]]:
        """Splits a comma/`and`/`or`-separated race or epithet list.

        Any `(Junior|Classic|Senior)` class qualifier is stripped into the second tuple element.

        Args:
            s: The list string (e.g. `"Tokyo Yushun (Classic), Arima Kinen and Japan Cup"`).

        Returns:
            A list of `(name, atClass)` tuples in input order; `atClass` is None when no class qualifier was present.
        """
        s = s.strip().rstrip(".")
        # Replace " and " / " or " with commas before splitting so the list reads
        # uniformly. Avoid splitting inside parens (e.g. "Tokyo Yushun (Japanese
        # Derby)") by temporarily masking them.
        masked = re.sub(r"\(([^)]*)\)", lambda m: "(" + m.group(1).replace(",", "\x00").replace(" and ", "\x01").replace(" or ", "\x02") + ")", s)
        # Replace top-level " and "/" or " with commas.
        masked = re.sub(r"\s+(?:and|or)\s+", ", ", masked, flags=re.IGNORECASE)
        parts = [p.strip() for p in masked.split(",") if p.strip()]
        out: List[Tuple[str, Optional[str]]] = []
        for p in parts:
            # Restore masked separators inside parens.
            p = p.replace("\x00", ",").replace("\x01", " and ").replace("\x02", " or ")
            # Drop a leading "the " from items like "the Hanshin Juvenile Fillies".
            p = re.sub(r"^the\s+", "", p, flags=re.IGNORECASE)
            name, atclass = cls._strip_class(p)
            if not name:
                continue
            out.append((name, atclass))
        return out

    @classmethod
    def _strip_class(cls, name: str) -> Tuple[str, Optional[str]]:
        """Splits a trailing `(Junior|Classic|Senior)` qualifier off `name`.

        Other parenthesised suffixes (e.g. `Tokyo Yushun (Japanese Derby)`) are left intact.

        Args:
            name: A race-name candidate that may carry a trailing class qualifier.

        Returns:
            A `(name, atClass)` tuple where `atClass` is the capitalised class name when present, otherwise None.
        """
        m = re.match(r"^(.+?)\s+\((Junior|Classic|Senior)\)$", name, re.IGNORECASE)
        if m:
            return m.group(1).strip(), m.group(2).capitalize()
        return name.strip(), None

    def _build_epithets_from_json(self) -> Dict[str, Dict[str, Any]]:
        """Reconstructs each EN epithet's display bullets from GameTora's nicknames dataset.

        The nicknames JSON stores conditions with `[[race|id]]` / `[[character|id]]` / `[[nickname|id]]` / `[[medal|...]]` /
        `[[skill|id]]` templates plus separate `char` / `scenario` / `rewards` fields. We resolve those into the same
        plain-text bullets the page used to render, in display order: character restriction, scenario restriction,
        conditions, then reward. Only entries with a `name_en_gl` (the global/EN name) are kept.

        Returns:
            Dict keyed by epithet name with `name` and `bullet_points` populated.
        """
        nicknames = fetch_gametora_manifest_data("nicknames")
        id_to_race = {r["id"]: r.get("name_en") for r in fetch_gametora_manifest_data("races")}
        id_to_char = {c["char_id"]: c.get("en_name") for c in fetch_gametora_manifest_data("characters")}
        id_to_scenario = {s["id"]: s.get("name_en") for s in fetch_gametora_manifest_data("scenarios")}
        id_to_skill = {s["id"]: (s.get("name_en") or s.get("enname")) for s in fetch_gametora_manifest_data("skills")}
        id_to_nickname = {e["id"]: (e.get("name_en_gl") or e.get("name_en")) for e in nicknames}

        def resolve_templates(text: str) -> str:
            def repl(match):
                parts = match.group(1).split("|")
                kind = parts[0]
                if kind == "race":
                    name = id_to_race.get(int(parts[1]), parts[1])
                    return name + (self._RACE_YEAR_SUFFIX.get(parts[2], "") if len(parts) > 2 else "")
                if kind == "character":
                    return id_to_char.get(int(parts[1]), parts[1])
                if kind == "nickname":
                    return id_to_nickname.get(int(parts[1]), parts[1])
                if kind == "skill":
                    return id_to_skill.get(int(parts[1]), parts[1])
                if kind == "medal":
                    return f"{parts[1]} medal"
                return match.group(0)

            return re.sub(r"\[\[([^\]]+)\]\]", repl, text)

        def render_reward(reward: Dict[str, Any]) -> str:
            if reward.get("t") == "sk":
                return f"Reward: {id_to_skill.get(reward.get('d'), reward.get('d'))} hint {reward.get('v')}"
            return f"Reward: {reward.get('d')} random stats {reward.get('v')}"

        results: Dict[str, Dict[str, Any]] = {}
        for entry in nicknames:
            name = entry.get("name_en_gl")
            if not name:  # No global/EN name means the epithet is not on the EN server yet.
                continue
            bullets: List[str] = []
            if entry.get("char") is not None and id_to_char.get(entry["char"]):
                bullets.append(f"{id_to_char[entry['char']]} only")
            if entry.get("scenario") is not None and id_to_scenario.get(entry["scenario"]):
                bullets.append(f"{id_to_scenario[entry['scenario']]} scenario only")
            bullets.extend(resolve_templates(b) for b in (entry.get("desc_en") or []))
            bullets.extend(render_reward(r) for r in (entry.get("rewards") or []))
            results[name] = {"name": name, "bullet_points": bullets}
        return results


class CharacterPresetScraper(BaseScraper):
    """Scrapes per-character distance and surface aptitudes for the Smart Race Solver.

    GameTora's `character-cards` JSON dataset carries each card's aptitude grade letters
    (Turf, Dirt, Sprint, Mile, Medium, Long, then running styles). The Smart Race Solver feeds
    the distance and surface grades into its aptitude eligibility filter, so they need to stay in sync with the game.

    Output schema (one entry per character) matches `src/data/characterPresets.json`:

        {
            "<character name>": {
                "name": "<character name>",
                "distanceAptitudes": { "Sprint": "F", "Mile": "C", "Medium": "A", "Long": "C" },
                "surfaceAptitudes": { "Turf": "A", "Dirt": "G" }
            }
        }
    """

    DISTANCE_KEYS = ("Sprint", "Mile", "Medium", "Long")
    SURFACE_KEYS = ("Turf", "Dirt")
    # Index of each grade in GameTora's character-cards `aptitude` array: surface (Turf, Dirt), distance (Sprint, Mile,
    # Medium, Long), then running styles (Front, Pace, Late, End) which the Smart Race Solver does not use.
    APTITUDE_INDEX = {"Turf": 0, "Dirt": 1, "Sprint": 2, "Mile": 3, "Medium": 4, "Long": 5}

    def __init__(self):
        super().__init__("https://gametora.com/umamusume/characters", "characterPresets.json")

    def start(self):
        """Builds per-character aptitudes from GameTora's JSON datasets (no browser needed).

        `characters` gives each character's EN name and `playable_en` flag. `character-cards` gives the per-card
        `aptitude` grade array. Aptitudes are character-level, so the base card (lowest `card_id`) of each EN-playable
        character supplies the canonical grades. This is a full rebuild so characters that leave the EN server do not linger.
        """
        self.data = {}
        characters = fetch_gametora_manifest_data("characters")
        cards = fetch_gametora_manifest_data("character-cards")

        # Keep the base card (lowest card_id) per character; aptitudes are identical across a character's costumes.
        base_card_by_char = {}
        for card in sorted(cards, key=lambda c: c["card_id"]):
            base_card_by_char.setdefault(card["char_id"], card)

        for char in characters:
            name = char.get("en_name")
            if not char.get("playable_en") or not name:
                continue
            card = base_card_by_char.get(char["char_id"])
            aptitude = card.get("aptitude") if card else None
            if not aptitude or len(aptitude) < 6:
                logging.warning(f"No aptitude data for {name}; skipping.")
                continue
            self.data[name] = {
                "name": name,
                "distanceAptitudes": {k: aptitude[self.APTITUDE_INDEX[k]] for k in self.DISTANCE_KEYS},
                "surfaceAptitudes": {k: aptitude[self.APTITUDE_INDEX[k]] for k in self.SURFACE_KEYS},
            }

        logging.info(f"Built aptitudes for {len(self.data)} EN-playable characters from GameTora JSON.")
        self.save_data()


class CharacterObjectivesScraper(BaseScraper):
    """Scrapes each character's mandatory career-objective races (URA scenario) from GameTora.

    GameTora exposes the URA objectives as a structured manifest data file (`ura-objectives`),
    joined to character names by `char_id` via the `characters` manifest. The Smart Race Solver
    uses these to lock the turns the game forces a mandatory race so it never double-books them.

    Output schema (one entry per EN-playable character) -> `src/data/character_objectives.json`:

        {
            "<character name>": {
                "name": "<character name>",
                "mandatoryRaces": [
                    {
                        "turn": 25,
                        "isChoice": false,
                        "options": [
                            { "raceName": "Shinzan Kinen", "grade": "G3", "surface": "Turf",
                              "distanceType": "Mile", "fans": 3800 }
                        ]
                    }
                ]
            }
        }
    """

    # GameTora numeric codes. Debut (900) is intentionally dropped: the Junior Make Debut turn is
    # pre-debut and already shown via the solver's synthetic display row.
    GRADE_CODES = {100: "G1", 200: "G2", 300: "G3", 400: "OP", 900: "Debut"}
    TERRAIN_CODES = {1: "Turf", 2: "Dirt"}

    def __init__(self):
        super().__init__("", "character_objectives.json")

    @staticmethod
    def _distance_type(meters: int) -> str:
        """Buckets a race distance in meters to the app's distance category.

        Mirrors the Kotlin `TrackDistance` thresholds: Sprint <= 1400, Mile <= 1800,
        Medium <= 2400, otherwise Long.

        Args:
            meters: Race distance in meters.

        Returns:
            One of 'Sprint', 'Mile', 'Medium', 'Long'.
        """
        if meters <= 1400:
            return "Sprint"
        if meters <= 1800:
            return "Mile"
        if meters <= 2400:
            return "Medium"
        return "Long"

    def start(self):
        """Fetches the URA objectives + characters manifests and writes the mandatory-race file."""
        # Full rebuild every run so characters that left the EN server do not linger.
        self.data = {}

        objectives = fetch_gametora_manifest_data("ura-objectives")
        characters = fetch_gametora_manifest_data("characters")

        id_to_name = {c["char_id"]: c.get("en_name") for c in characters if c.get("char_id") and c.get("en_name")}
        en_playable = {c["char_id"] for c in characters if c.get("char_id") and c.get("playable_en") and c.get("en_name")}

        for entry in objectives:
            char_id = entry.get("char_id")
            name = id_to_name.get(char_id)
            if not name or char_id not in en_playable:
                continue

            by_turn: Dict[int, List[Dict[str, Any]]] = {}
            for obj in entry.get("objectives", []):
                # target_type 1 is a race-placement objective. target_type 3 is the URA Finals.
                if obj.get("target_type") != 1:
                    continue
                turn = obj.get("turn")
                if not isinstance(turn, int) or turn > 72:  # 72 is the final turn of a URA career.
                    continue
                for r in obj.get("races", []):
                    grade = self.GRADE_CODES.get(r.get("grade"))
                    if grade is None or grade == "Debut":
                        continue
                    meters = r.get("distance", 0)
                    by_turn.setdefault(turn, []).append(
                        {
                            "raceName": r.get("name_en", ""),
                            "grade": grade,
                            "surface": self.TERRAIN_CODES.get(r.get("terrain"), "Turf"),
                            "distanceType": self._distance_type(meters),
                            "fans": r.get("fans_gained", 0),
                        }
                    )

            mandatory_races: List[Dict[str, Any]] = []
            for turn in sorted(by_turn.keys()):
                seen = set()
                deduped: List[Dict[str, Any]] = []
                for o in by_turn[turn]:
                    k = (o["raceName"], o["distanceType"], o["surface"])
                    if k in seen:
                        continue
                    seen.add(k)
                    deduped.append(o)
                mandatory_races.append({"turn": turn, "isChoice": len(deduped) > 1, "options": deduped})

            if mandatory_races:
                self.data[name] = {"name": name, "mandatoryRaces": mandatory_races}

        logging.info(f"Scraped mandatory objectives for {len(self.data)} EN-playable characters.")
        self.save_data()


if __name__ == "__main__":
    logging.basicConfig(format="%(asctime)s - %(levelname)s - %(message)s", level=logging.INFO)
    start_time = time.time()

    run_scraper_with_retry(SkillScraper())

    run_scraper_with_retry(CharacterScraper())

    run_scraper_with_retry(SupportCardScraper())

    # Races are static so no need to re-scrape every time.
    # run_scraper_with_retry(RaceScraper())

    run_scraper_with_retry(EpithetScraper())

    run_scraper_with_retry(CharacterPresetScraper())

    run_scraper_with_retry(CharacterObjectivesScraper())

    end_time = round(time.time() - start_time, 2)
    logging.info(f"Total time for processing all applications: {end_time} seconds or {round(end_time / 60, 2)} minutes.")
