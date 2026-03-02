from deprecated import deprecated
from selenium import webdriver
from selenium.webdriver.chrome.options import Options
from selenium.webdriver.common.by import By
from selenium.common.exceptions import NoSuchElementException, ElementClickInterceptedException, WebDriverException
from selenium.webdriver.remote.webelement import WebElement
import json
import re
import time
import logging
import os
from typing import List, Dict, Any
from difflib import SequenceMatcher
import bisect
import requests

IS_DELTA = True
DELTA_BACKLOG_COUNT = 5

GAMETORA_DATA_URL = "https://gametora.com/data"
GAMETORA_MANIFESTS_URL = f"{GAMETORA_DATA_URL}/manifests/umamusume.json"
GAMETORA_MANIFEST_DATA_BASE_URL = f"{GAMETORA_DATA_URL}/umamusume"

# Event name patterns that belong to the "After a Race" section.
AFTER_RACE_EVENT_PATTERNS = [
    "Victory! (G1)",
    "Victory! (G2/G3)",
    "Victory! (Pre/OP)",
    "Solid Showing (G1)",
    "Solid Showing (G2/G3)",
    "Solid Showing (Pre/OP)",
    "Defeat (G1)",
    "Defeat (G2/G3)",
    "Defeat (Pre/OP)",
    "Etsuko's Elated Coverage (G1)",
    "Etsuko's Elated Coverage (G2/G3)",
    "Etsuko's Elated Coverage (Pre/OP)",
    "Etsuko's Exhaustive Coverage (G1)",
    "Etsuko's Exhaustive Coverage (G2/G3)",
    "Etsuko's Exhaustive Coverage (Pre/OP)",
]


def load_after_race_events() -> Dict[str, List[str]]:
    """Load "After a Race" events from characters.json.

    These events are identical across all characters, so we only need to read
    them from the first character's data.

    Returns:
        A dictionary of event names to their options.
    """
    after_race_events: Dict[str, List[str]] = {}

    characters_file = os.path.join(os.path.dirname(__file__), "characters.json")
    if not os.path.exists(characters_file):
        logging.warning('characters.json not found. Cannot load "After a Race" events.')
        return after_race_events

    try:
        with open(characters_file, "r", encoding="utf-8") as f:
            characters_data = json.load(f)

        # Get the first character's data only.
        if not characters_data:
            logging.warning('characters.json is empty. Cannot load "After a Race" events.')
            return after_race_events

        first_character_events = next(iter(characters_data.values()))

        # Extract only events that match the "After a Race" patterns.
        for event_name, options in first_character_events.items():
            for pattern in AFTER_RACE_EVENT_PATTERNS:
                if event_name.startswith(pattern):
                    after_race_events[event_name] = options
                    break

        logging.info(f'Loaded {len(after_race_events)} "After a Race" events from characters.json.')

    except (json.JSONDecodeError, KeyError) as e:
        logging.warning(f'Failed to load "After a Race" events from characters.json: {e}')

    return after_race_events


def create_chromedriver():
    """Creates the Chrome driver for scraping.

    Returns:
        The Chrome driver.
    """
    chrome_options = Options()
    chrome_options.add_argument("--headless=new")  # Use the new headless mode
    chrome_options.add_argument("--disable-gpu")  # Disable GPU hardware acceleration (recommended for containers)
    chrome_options.add_argument("--no-sandbox")  # Bypass OS security model (needed for some environments like Docker)
    chrome_options.add_argument("--window-size=1920,1080")  # Set a default window size for consistent rendering
    driver = webdriver.Chrome(options=chrome_options)
    return driver


def calculate_turn_number(date_string: str) -> int:
    """Calculates the turn number for a race based on its date string.

    This function parses race date strings in the format "Senior Class January, Second Half"
    and converts them to turn numbers using the same logic as the Kotlin GameDate.

    Args:
        date_string: The date string to parse (e.g., "Senior Class January, Second Half").

    Returns:
        The calculated turn number for the race.
    """
    if not date_string or date_string.strip() == "":
        logging.warning("Received empty date string, defaulting to Senior Year Early Jan (turn 49).")
        return 49

    # Handle Pre-Debut dates (though they shouldn't appear in race data).
    if "debut" in date_string.lower():
        logging.warning("Pre-Debut date detected in race data, this shouldn't happen.")
        return 1

    # Define mappings for years and months.
    years = {"Junior Class": 1, "Classic Class": 2, "Senior Class": 3}

    months = {
        "January": 1,
        "Jan": 1,
        "February": 2,
        "Feb": 2,
        "March": 3,
        "Mar": 3,
        "April": 4,
        "Apr": 4,
        "May": 5,
        "June": 6,
        "Jun": 6,
        "July": 7,
        "Jul": 7,
        "August": 8,
        "Aug": 8,
        "September": 9,
        "Sep": 9,
        "October": 10,
        "Oct": 10,
        "November": 11,
        "Nov": 11,
        "December": 12,
        "Dec": 12,
    }

    # Parse the date string.
    # Expected format: "Senior Class January, Second Half"
    parts = date_string.strip().split()
    if len(parts) < 3:
        logging.warning(f"Invalid date string format: {date_string}, defaulting to Senior Year Early Jan (turn 49).")
        return 49

    # Extract year part (first two words).
    year_part = f"{parts[0]} {parts[1]}"
    month_part = parts[2].rstrip(",")  # Remove trailing comma if present.

    # Extract phase part (last two words combined).
    phase_part = f"{parts[-2]} {parts[-1]}"  # "First Half" or "Second Half"

    # Find the best match for year using similarity scoring.
    year = years.get(year_part)
    if year is None:
        best_year_score = 0.0
        best_year = 3  # Default to Senior Year.

        for year_key in years.keys():
            score = SequenceMatcher(None, year_part, year_key).ratio()
            if score > best_year_score:
                best_year_score = score
                best_year = years[year_key]

        logging.info(f"Year not found in mapping, using best match: {year_part} -> {best_year}")
        year = best_year

    # Find the best match for month using similarity scoring.
    month = months.get(month_part)
    if month is None:
        best_month_score = 0.0
        best_month = 1  # Default to January.

        for month_key in months.keys():
            score = SequenceMatcher(None, month_part, month_key).ratio()
            if score > best_month_score:
                best_month_score = score
                best_month = months[month_key]

        logging.info(f"Month not found in mapping, using best match: {month_part} -> {best_month}")
        month = best_month

    # Determine phase (Early = First Half, Late = Second Half).
    phase = "Early" if "First" in phase_part else "Late"

    # Calculate the turn number.
    # Each year has 24 turns (12 months x 2 phases each).
    # Each month has 2 turns (Early and Late).
    turn_number = ((year - 1) * 24) + ((month - 1) * 2) + (1 if phase == "Early" else 2)

    return turn_number


def download_image(url: str, out_fp: str):
    """
    Downloads an image from the given URL and saves it to the specified file path.

    Args:
        url (str): The URL of the image to download.
        out_fp (str): The file path to save the downloaded image to.
    """
    try:
        response = requests.get(url)
        response.raise_for_status()
        with open(out_fp, "wb") as f_out:
            f_out.write(response.content)
    except requests.exceptions.RequestException as exc:
        print(f"An error occurred when downloading image: {e}")


def fetch_gametora_manifest_data(manifest_name: str) -> dict:
    """Fetches data from gametora's JSON manifest.

    Args:
        manifest_name (str): The name of the manifest to fetch data from.

    Returns:
        The fetched manifest data JSON as a dictionary.
    """
    response = requests.get(GAMETORA_MANIFESTS_URL, timeout=60)
    response.raise_for_status()
    manifests = response.json()

    manifest_id = manifests[manifest_name]
    manifest_url = f"{GAMETORA_MANIFEST_DATA_BASE_URL}/{manifest_name}.{manifest_id}.json"
    response = requests.get(manifest_url)
    response.raise_for_status()
    manifest_data = response.json()
    return manifest_data


class BaseScraper:
    """Base class for scraping data from the website.

    Args:
        url (str): The URL to scrape.
        output_filename (str): The filename to save the scraped data to.
    """

    def __init__(self, url: str, output_filename: str):
        self.url = url
        self.output_filename = output_filename
        self.data = self.load_existing_data()
        self.initial_data_count = len(self.data) if IS_DELTA else 0
        self.cookie_accepted = False

    def safe_click(self, driver: webdriver.Chrome, element: WebElement, retries: int = 3, delay: float = 0.5):
        """Try clicking an element normally and falls back to JS click if blocked by ads/overlays.

        Args:
            driver (webdriver.Chrome): The Chrome driver.
            element (WebElement): The web element to interact with.
            retries (int, optional): How many times to retry if intercepted.
            delay (float, optional): Seconds to wait between retries
        """
        for _ in range(retries):
            try:
                element.click()
                return True
            except ElementClickInterceptedException:
                # Fallback to scrolling + JS click.
                try:
                    driver.execute_script("arguments[0].scrollIntoView(true);", element)
                    driver.execute_script("arguments[0].click();", element)
                    return True
                except WebDriverException as _:
                    # If JS click fails, wait a bit and retry.
                    time.sleep(delay)
        return False

    def load_existing_data(self):
        """Loads existing JSON data from the output file if delta scraping is enabled.

        Returns:
            The loaded data dictionary, or an empty dictionary if the file doesn't exist or delta scraping is disabled.
        """
        if not IS_DELTA:
            return {}

        if not os.path.exists(self.output_filename):
            logging.info(f"Output file {self.output_filename} does not exist. Starting with empty data.")
            return {}

        try:
            with open(self.output_filename, "r", encoding="utf-8") as f:
                existing_data = json.load(f)
                logging.info(f"Loaded {len(existing_data)} existing items from {self.output_filename} for delta merge.")
                return existing_data
        except json.JSONDecodeError as e:
            logging.warning(f"Failed to parse existing JSON file {self.output_filename}: {e}. Starting with empty data.")
            return {}
        except Exception as e:
            logging.warning(f"Failed to load existing data from {self.output_filename}: {e}. Starting with empty data.")
            return {}

    def save_data(self):
        """Saves the scraped data to a file."""
        # Sort keys alphabetically to maintain consistent ordering.
        sorted_data = {key: self.data[key] for key in sorted(self.data.keys())}

        with open(self.output_filename, "w", encoding="utf-8") as f:
            json.dump(sorted_data, f, ensure_ascii=False, indent=4)

        if IS_DELTA and self.initial_data_count > 0:
            new_or_updated = len(self.data) - self.initial_data_count
            logging.info(
                f"Saved {len(self.data)} items to {self.output_filename} (delta merge: {self.initial_data_count} existing + {new_or_updated} new/updated)."
            )
        else:
            logging.info(f"Saved {len(self.data)} items to {self.output_filename}.")

    def handle_cookie_consent(self, driver: webdriver.Chrome):
        """Handles the cookie consent.

        Args:
            driver (webdriver.Chrome): The Chrome driver.
        """
        if not self.cookie_accepted:
            try:
                cookie_consent_button = driver.find_element(By.XPATH, "//button[contains(@class, 'legal_cookie_banner_button')]")
                if cookie_consent_button:
                    cookie_consent_button.click()
                    time.sleep(0.5)
                    self.cookie_accepted = True
                    logging.info("Cookie consent accepted.")
            except NoSuchElementException:
                logging.info("No cookie consent button found.")
                self.cookie_accepted = True

    def handle_ad_banner(self, driver: webdriver.Chrome, skip: bool = False):
        """Handles the ad banner.

        Args:
            driver (webdriver.Chrome): The Chrome driver.
            skip (bool, optional): Whether to skip the ad banner. Defaults to False.

        Returns:
            Whether the ad banner was dismissed.
        """
        if not skip:
            try:
                ad_banner_button = driver.find_element(By.XPATH, "//div[contains(@class, 'publift-widget-sticky_footer-button')]")
                if ad_banner_button and ad_banner_button.is_displayed():
                    ad_banner_button.click()
                    time.sleep(0.5)
                    logging.info("Ad banner dismissed.")
                    return True
            except NoSuchElementException:
                logging.info("No ad banner found.")
            return False
        else:
            return True

    def extract_training_event_options(self, tooltip_rows: List[WebElement]):
        """Extracts the training event options from the tooltip rows.

        Args:
            tooltip_rows (List[WebElement]): The tooltip rows.

        Returns:
            The training event options.
        """
        options = []
        for tooltip_row in tooltip_rows:
            event_option_div = tooltip_row.find_element(By.XPATH, ".//div[contains(@class, 'sc-') and contains(@class, '-2 ')]")
            event_result_divs = event_option_div.find_elements(By.XPATH, ".//div")
            text_fragments = [div.text.strip() for div in event_result_divs]

            # Handle events where it offers random outcomes.
            if text_fragments and "Randomly either" in text_fragments[0]:
                option_text = "Randomly either\n----------\n"

                # Group the outcomes by dividers.
                current_group = []
                for fragment in text_fragments[1:]:
                    if fragment == "or":
                        option_text += "\n".join(current_group) + "\n----------\n"
                        current_group = []
                    else:
                        current_group.append(fragment)
                # Add the last group to the option text.
                if current_group:
                    option_text += "\n".join(current_group)
            else:
                # Otherwise, just join the text fragments for regular event outcomes.
                option_text = "\n".join(text_fragments)

            # Replace all instances of "Wisdom" with "Wit" to match the in-game terminology.
            option_text = option_text.replace("Wisdom", "Wit")
            options.append(option_text)
        return options

    def process_training_events(
        self, driver: webdriver.Chrome, item_name: str, data_dict: Dict[str, List[str]], include_after_race_events: bool = False
    ):
        """Processes the training events for the given item.

        Args:
            driver (webdriver.Chrome): The Chrome driver.
            item_name (str): The name of the item.
            data_dict (Dict[str, List[str]]): The data dictionary to modify.
            include_after_race_events (bool): Whether to include 'After a Race' events (only for characters).
        """
        # Find all training events first.
        all_training_events_unfiltered = driver.find_elements(
            By.XPATH, "//button[contains(@class, 'sc-') and contains(@class, '-0 ')]"
        )
        logging.info(f"Found {len(all_training_events_unfiltered)} unfiltered training events for {item_name}.")

        # Find the "Events Without Choices" section header and exclude events from its following grid.
        # The section header is a div with class 'sc-*-0' containing the text "Events Without Choices".
        # The grid following it (sc-*-2) contains training events we want to exclude.
        events_to_exclude = set()
        try:
            # Find the div containing "Events Without Choices" text.
            no_choices_header = driver.find_element(
                By.XPATH, "//div[contains(@class, 'sc-') and contains(@class, '-0 ') and contains(text(), 'Events Without Choices')]"
            )
            # Find the next sibling div which should be the grid containing events without choices.
            no_choices_grid = no_choices_header.find_element(
                By.XPATH, "./following-sibling::div[contains(@class, 'sc-') and contains(@class, '-2 ')][1]"
            )
            # Get all training event buttons within this grid.
            events_without_choices = no_choices_grid.find_elements(
                By.XPATH, ".//button[contains(@class, 'sc-') and contains(@class, '-0 ')]"
            )
            events_to_exclude = set(events_without_choices)
            logging.info(f"Found {len(events_to_exclude)} events without choices to exclude for {item_name}.")
        except NoSuchElementException:
            logging.info(f'No "Events Without Choices" section found for {item_name}. Including all events.')

        # Filter out the events without choices.
        all_training_events = [event for event in all_training_events_unfiltered if event not in events_to_exclude]
        logging.info(f"Found {len(all_training_events)} training events (after filtering) for {item_name}.")

        # Find the "After a Race" section and exclude its events from scraping.
        # These events are identical across all characters, so we copy them from characters.json.
        if include_after_race_events:
            after_race_events = set()
            try:
                after_race_header = driver.find_element(
                    By.XPATH, "//div[contains(@class, 'sc-') and contains(@class, '-0 ') and contains(text(), 'After a Race')]"
                )
                after_race_grid = after_race_header.find_element(
                    By.XPATH, "./following-sibling::div[contains(@class, 'sc-') and contains(@class, '-2 ')][1]"
                )
                after_race_buttons = after_race_grid.find_elements(
                    By.XPATH, ".//button[contains(@class, 'sc-') and contains(@class, '-0 ')]"
                )
                after_race_events = set(after_race_buttons)
                logging.info(f'Found {len(after_race_events)} "After a Race" events to copy for {item_name}.')
            except NoSuchElementException:
                logging.info(f'No "After a Race" section found for {item_name}.')

            # Filter out the "After a Race" events from the list to scrape.
            all_training_events = [event for event in all_training_events if event not in after_race_events]
            logging.info(f'Found {len(all_training_events)} training events (after excluding "After a Race") for {item_name}.')

            # Copy the "After a Race" events from the preloaded cache.
            data_dict.update(self.after_race_events)
            logging.info(f'Copied {len(self.after_race_events)} "After a Race" events for {item_name}.')

        ad_banner_closed = False

        for j, training_event in enumerate(all_training_events):
            self.safe_click(driver, training_event)
            time.sleep(1.0)

            tooltip = driver.find_element(By.XPATH, "//div[@data-tippy-root]")
            try:
                tooltip_title = tooltip.find_element(By.XPATH, ".//div[contains(@class, 'sc-') and contains(@class, '-2 ')]").text
                if tooltip_title in data_dict:
                    logging.info(
                        f"Training event {tooltip_title} ({j + 1}/{len(all_training_events)}) already exists. Overwriting with new data..."
                    )
            except NoSuchElementException:
                logging.warning(f"No tooltip title found for training event ({j + 1}/{len(all_training_events)}).")
                continue

            tooltip_rows = tooltip.find_elements(By.XPATH, ".//div[contains(@class, 'sc-') and contains(@class, '-0 ')]")
            if len(tooltip_rows) == 0:
                logging.warning(f"No options found for training event {tooltip_title} ({j + 1}/{len(all_training_events)}).")
                continue

            logging.info(f"Found {len(tooltip_rows)} options for training event {tooltip_title} ({j + 1}/{len(all_training_events)}).")
            options = self.extract_training_event_options(tooltip_rows)
            data_dict[tooltip_title] = options

            ad_banner_closed = self.handle_ad_banner(driver, ad_banner_closed)

    def _sort_by_value(self, driver: webdriver.Chrome, value_key: str):
        """Sorts the list elements by the given value key.

        Args:
            driver (webdriver.Chrome): The Chrome driver.
            value_key (str): The key to sort by.
        """
        # Click on the "Sort by" dropdown and select the value key.
        sort_by_dropdown = driver.find_element(By.XPATH, "//select[contains(@id, ':r')]")
        sort_by_dropdown.click()
        time.sleep(0.5)
        value_option = sort_by_dropdown.find_element(By.XPATH, f".//option[@value='{value_key}']")
        value_option.click()
        time.sleep(0.5)


class SkillScraper(BaseScraper):
    """Scrapes the skills from the website."""

    def __init__(self):
        super().__init__("https://gametora.com/umamusume/skills", "skills.json")

    def scrape_skill_evaluation_points(self):
        """Scrapes skill Evaluation Points from the umamusume wiki.

        Evaluation Points affect the result rank of a trainee.
        Unsure whether the evaluation points are 1:1 with the rank gained or if they
        are just a factor of the rank calculation.

        We also scrape the evaluation point ratio which is just the ratio
        of evaluation points to the base cost of the skill.

        Returns:
            The skill evaluation points as a dictionary mapping skill ID to evaluation points.
        """
        driver = create_chromedriver()
        driver.get("https://umamusu.wiki/Game:List_of_Skills")
        data = {}

        tables = driver.find_elements(By.TAG_NAME, "table")
        for table in tables:
            tbody = table.find_element(By.TAG_NAME, "tbody")
            rows = tbody.find_elements(By.TAG_NAME, "tr")
            for row in rows:
                cells = row.find_elements(By.TAG_NAME, "td")
                skill_name_anchor = cells[1].find_element(By.TAG_NAME, "a")
                skill_id = skill_name_anchor.get_attribute("title")
                skill_id = int("".join(filter(str.isdigit, skill_id)))
                skill_points = int(cells[3].text.strip())
                if skill_points == 0:
                    continue
                skill_evaluation_points = int(cells[4].text.strip())
                skill_point_ratio = float(cells[5].text.strip())
                data[skill_id] = {
                    "evaluation_points": skill_evaluation_points,
                    "point_ratio": skill_point_ratio,
                }

        driver.quit()
        return data

    def scrape_skill_tier_list(self):
        """Scrapes Game8's skill tier list.

        Game8's tier list is split across four different tables (SS, S, A, and B).
        Since they don't use any unique IDs for the tables, we instead use the headers
        before each table to determine which rank is associated with each table.
        See `h4_tier_map` for the mapping of header to tier.

        There are other tier lists out there but this site seems like it isn't
        going anywhere so it makes it relatively stable for scraping.

        Returns:
            The tier list of skills as a dictionary mapping skill name to tier.
        """
        driver = create_chromedriver()
        driver.get("https://game8.co/games/Umamusume-Pretty-Derby/archives/536805")

        h4_tier_map = {
            "hs_1": 0,  # SS
            "hs_2": 1,  # S
            "hs_3": 2,  # A
            "hs_4": 3,  # B
        }

        res = {}

        for h4_id, tier_name in h4_tier_map.items():
            table = driver.find_element(By.XPATH, f"//h4[@id='{h4_id}']/following-sibling::table[2]")
            tds = table.find_elements(By.TAG_NAME, "td")

            for td in tds:
                divs = td.find_elements(By.TAG_NAME, "div")
                for div in divs:
                    anchor = div.find_elements(By.TAG_NAME, "a")[-1]
                    skill_name = anchor.text.strip()
                    # Make sure we use the same special characters as gametora.
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

        driver.quit()

        # They misspelled some skill names so we need to fix them.
        # Pretty much if you run the scraper and it throws an error for a skill,
        # just make sure that the skill isn't misspelled and then
        # update this map.
        rename_map = {
            "Let's Pump Some Iron": "Let's Pump Some Iron!",
            "Fast and Furious": "Fast & Furious",
            "Mile Straightaway ○": "Mile Straightaways ○",
            "Mile Straightaway ◎": "Mile Straightaways ◎",
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
        """Gets the activation condition/precondition string for a skill.

        `skill_data` is a very complex and deeply nested JSON object.
        For each skill entry in this JSON, we need to extract the conditions
        and preconditions string values. However, these can be in one of a few places.

        The following is one of the more complex examples from the data:

        {
            "name_en": "Arrows Whistle, Shadows Disperse",
            "condition_groups": [
                {
                    "condition": "is_finalcorner==1",
                    "precondition": "phase>=2&order_rate<=50&overtake_target_time>=2",
                }
            ],
            "gene_version": {
                "condition_groups": [
                    {
                        "condition": "is_finalcorner==1",
                        "precondition": "phase>=2&order_rate<=50&overtake_target_time>=2",
                    }
                ],
            },
            "loc": {
                "en": {     // Global version
                    "condition_groups": [
                        {
                            "condition": "is_finalcorner==1&order_rate<=40&overtake_target_time>=2",
                        }
                    ],
                    "gene_version": {
                        "condition_groups": [
                            {
                                "condition": "is_finalcorner==1&order_rate<=40&overtake_target_time>=2",
                            }
                        ]
                    }
                }
            }
        }

        In this example, we have a unique skill. Since this is a unique skill,
        it has properties called "gene_version". The "gene_version" is the inherited
        version that can be purchased when inherited from legacy umamusume.

        If a skill has a gene_version, then we always want to use that data since
        the non-inherited version can't be purchased.

        However, in these entries we also have the "loc" property. This is the
        localization (i.e. JP, KO, Global). These localizations may be on different
        patches and thus may have different values. So we want to make sure to use
        the Global (en) localization.

        Then within the localization, we can extract our "condition" string.
        Take note that the "precondition" field is not in the localization.
        Not every entry contains all of these structures so we have to combine
        data across the existing fields to get everything we need.

        To do this, we try to get data using the following priority order:
        1) loc -> en -> gene_version -> condition_groups -> condition/precondition
        2) loc -> en -> condition_groups -> condition/precondition
        3) gene_version -> condition_groups -> condition/precondition
        4) condition_groups -> condition/precondition

        To sum up, we just need to get the most accurate data possible for the
        global release by combining the best data we can extract from the entry.
        Not every entry has all these fields so we just take what we can get.

        Args:
            skill_object (Dict[str, Any]) A single entry from skill_data. This is a complex nested dict.
            get_preconditions (bool, optional) Whether to get the "preconditions" entry
                instead of the "conditions" entry. Defaults to False.

        Returns:
            The condition string.
        """
        # Prioritize getting the english version of the condition group since it
        # should be the current global patch data.
        # Always try the gene_version first.
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

        # Just return now if we still havent found anything.
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
        # Capitalization on the website we use for the tier list may differ.
        # We need to make everything lowercase for proper lookups between sources.
        skill_to_tier_map_lowercase = {k.lower(): k for k in skill_to_tier_map.keys()}

        try:
            skill_data = fetch_gametora_manifest_data("skills")

            skill_id_to_name = {}
            for skill in skill_data:
                try:
                    # If name_en doesnt exist, then the skill isn't in global yet.
                    if "name_en" not in skill:
                        continue

                    skill_id = skill["id"]
                    skill_gene_id = skill_id
                    skill_name_en = skill["name_en"].strip().replace("  ", " ")
                    skill_desc_en = skill["desc_en"]
                    skill_iconid = skill["iconid"]
                    skill_rarity = skill["rarity"]
                    skill_inherited = False
                    skill_cost = skill.get("cost", None)
                    # For inherited unique skills, we actually want the
                    # gene version's ID since the primary ID isn't the one that
                    # we can purchase through inheritance.
                    if "gene_version" in skill:
                        skill_gene_id = skill["gene_version"]["id"]
                        skill_desc_en = skill["gene_version"]["desc_en"]
                        skill_iconid = skill["gene_version"]["iconid"]
                        skill_rarity = skill["gene_version"]["rarity"]
                        skill_inherited = skill["gene_version"].get("inherited", False)
                        skill_cost = skill["gene_version"].get("cost", None)

                    if skill_cost is None:
                        logging.warning(f"Dropping skill with invalid COST: {skill_name_en}")
                        continue

                    # Get the skill activation conditions.
                    skill_condition = self.get_skill_activation_conditions(skill)
                    skill_precondition = self.get_skill_activation_conditions(skill, get_preconditions=True)

                    extra_data = skill_evaluation_points.get(
                        skill_gene_id,
                        {"evaluation_points": 0, "point_ratio": 0.0},
                    )

                    # The tier list doesn't include any of the JP skills so we don't treat
                    # missing skills as errors. These warnings should be reviewed by maintainer
                    # in case any skill names are misspelled.
                    # We can ignore any negative skills since they won't appear in the tier list.
                    tmp_skill_name = skill_to_tier_map_lowercase.get(skill_name_en.lower(), None)
                    bIsNegative = skill_iconid % 10 == 4
                    if tmp_skill_name is None and not bIsNegative:
                        logging.warning(f"Skill Tier Unknown: {skill_name_en}")

                    community_tier = skill_to_tier_map.get(tmp_skill_name, None)

                    # Corrections to invalid GameTora skill data.
                    if skill_name_en.lower() == "indomitable" and skill_id != 200471:
                        # There are multiple entries with the name "Indomitable".
                        # Only the one with id 200471 is valid. Skip others.
                        continue
                    elif skill_id in [1000011, 1000012, 1000013, 1000014, 1000015, 1000016, 1000017]:
                        # These are carnival bonus skill IDs. These aren't currently valid.
                        # Unsure if they ever will be. So we skip them.
                        continue

                    for old_name, old_entry in self.data.items():
                        old_id = old_entry.get("id")
                        if old_id == skill_id:
                            logging.warning(
                                f"Duplicate ID when adding skill: {skill_name_en} ({skill_id}), Previous entry: {old_name} ({old_id})"
                            )

                    tmp = {
                        "id": skill_id,
                        "gene_id": skill_gene_id,
                        "name_en": skill_name_en,
                        "desc_en": skill_desc_en,
                        "icon_id": skill_iconid,
                        "cost": skill_cost,
                        "eval_pt": extra_data["evaluation_points"],
                        "pt_ratio": extra_data["point_ratio"],
                        "rarity": skill_rarity,
                        "condition": skill_condition,
                        "precondition": skill_precondition,
                        "inherited": skill_inherited,
                        "community_tier": community_tier,
                        "versions": sorted(skill.get("versions", [])),
                        "upgrade": None,
                        "downgrade": None,
                    }
                    skill_id_to_name[skill["id"]] = skill_name_en

                    self.data[skill_name_en] = tmp
                except KeyError as exc:
                    if "name_en" in skill:
                        logging.error(f"KeyError when parsing skill ({skill['name_en']}): {exc}")
                    else:
                        logging.error(f"KeyError when parsing skill: {exc}")
                    continue

            # Populate the upgrade/downgrade versions for every skill.
            for skill_name, skill in self.data.items():
                # If skill has no other versions, skip.
                if skill["versions"] == []:
                    continue

                # Now determine the upgrades/downgrades of this skill.
                index = bisect.bisect_left(skill["versions"], skill["id"])
                if index == 0:
                    # This is the highest level of this skill.
                    downgrade_version = skill["versions"][0]
                    if downgrade_version in skill_id_to_name:
                        self.data[skill_name]["downgrade"] = downgrade_version
                elif index == len(skill["versions"]):
                    # This is the lowest level of this skill.
                    upgrade_version = skill["versions"][-1]
                    if upgrade_version in skill_id_to_name:
                        self.data[skill_name]["upgrade"] = upgrade_version
                else:
                    # Skill has both an upgraded and downgraded variant.
                    upgrade_version = skill["versions"][index - 1]
                    if upgrade_version in skill_id_to_name:
                        self.data[skill_name]["upgrade"] = upgrade_version

                    downgrade_version = skill["versions"][index]
                    if downgrade_version in skill_id_to_name:
                        self.data[skill_name]["downgrade"] = downgrade_version

            # Save the skill icons
            icon_ids = set(x["icon_id"] for x in self.data.values())
            for icon_id in icon_ids:
                url = f"https://gametora.com/images/umamusume/skill_icons/utx_ico_skill_{icon_id}.png"
                out_fp = f"../pages/SkillSettings/icons/utx_ico_skill_{icon_id}.png"
                download_image(url, out_fp)

            self.save_data()

        except Exception as exc:
            print("Error:", exc)


class CharacterScraper(BaseScraper):
    """Scrapes the characters from the website.

    Args:
        after_race_events (Dict[str, List[str]]): Preloaded "After a Race" events to copy to each character.
    """

    def __init__(self, after_race_events: Dict[str, List[str]]):
        super().__init__("https://gametora.com/umamusume/characters", "characters.json")
        self.after_race_events = after_race_events

    def start(self):
        """Starts the scraping process."""
        driver = create_chromedriver()
        driver.get(self.url)
        time.sleep(5)

        self.handle_cookie_consent(driver)

        # Sort the characters by release date descending order.
        self._sort_by_value(driver, "implemented")

        # Get all character links.
        try:
            character_grid = driver.find_element(By.XPATH, "//div[contains(@class, 'characters_page_character_list')]")
        except NoSuchElementException:
            # Fallback to general lookup just in case.
            character_grid = driver.find_element(By.XPATH, "//div[contains(@class, 'sc-dc9ce0a6-0')]")
        
        all_character_items = character_grid.find_elements(By.CSS_SELECTOR, "a[href^='/umamusume/characters/']")
        # Filter out hidden elements using Selenium's is_displayed() method.
        character_items = [item for item in all_character_items if item.is_displayed()]

        logging.info(f"Found {len(character_items)} characters.")
        character_links = [item.get_attribute("href") for item in character_items]

        # If this is a delta scrape, scrape the first 10 characters as the list is now sorted by descending release date.
        if IS_DELTA:
            character_links = character_links[:DELTA_BACKLOG_COUNT]
            logging.info(
                f"Scraping the first {DELTA_BACKLOG_COUNT} characters for the delta scrape as the list is now sorted by descending release date."
            )

        # Iterate through each character.
        for i, link in enumerate(character_links):
            logging.info(f"Navigating to {link} ({i + 1}/{len(character_links)})")
            driver.get(link)
            time.sleep(3)

            character_name = driver.find_element(By.XPATH, "//main//h1").text
            character_name = character_name.replace("(Original)", "").strip()
            # Remove any other parentheses that denote different forms of the character like "Wedding" or "Swimsuit".
            character_name = re.sub(r"\s*\(.*?\)", "", character_name).strip()

            # Initialize an empty object to store the following character data if it doesn't exist yet.
            if character_name not in self.data:
                self.data[character_name] = {}

            # Scrape all the Training Events (including "After a Race" events for characters).
            self.process_training_events(driver, character_name, self.data[character_name], include_after_race_events=True)

        self.save_data()
        driver.quit()


class SupportCardScraper(BaseScraper):
    """Scrapes the support cards from the website."""

    def __init__(self):
        super().__init__("https://gametora.com/umamusume/supports", "supports.json")

    def start(self):
        """Starts the scraping process."""
        driver = create_chromedriver()
        driver.get(self.url)
        time.sleep(5)

        self.handle_cookie_consent(driver)

        # Sort the support cards by release date descending order.
        self._sort_by_value(driver, "implemented")

        # Get all support card links.
        support_card_grid = driver.find_element(By.XPATH, "//div[contains(@class, 'sc-dc9ce0a6-0')]")
        all_support_card_items = support_card_grid.find_elements(By.CSS_SELECTOR, "a[href^='/umamusume/supports/']")
        # Filter out hidden elements using Selenium's is_displayed() method.
        filtered_support_card_items = [item for item in all_support_card_items if item.is_displayed()]

        logging.info(f"Found {len(filtered_support_card_items)} support cards.")
        support_card_links = [item.get_attribute("href") for item in filtered_support_card_items]

        # If this is a delta scrape, scrape the first 10 support cards as the list is now sorted by descending release date.
        if IS_DELTA:
            support_card_links = support_card_links[:DELTA_BACKLOG_COUNT]
            logging.info(
                f"Scraping the first {DELTA_BACKLOG_COUNT} support cards for the delta scrape as the list is now sorted by descending release date."
            )

        # Iterate through each support card.
        for i, link in enumerate(support_card_links):
            logging.info(f"Navigating to {link} ({i + 1}/{len(support_card_links)})")
            driver.get(link)
            time.sleep(3)

            support_card_name = driver.find_element(By.XPATH, "//main//h1").text
            support_card_name = support_card_name.replace("Support Card", "").strip()
            # Remove any other parentheses that denote different forms of the support card.
            support_card_name = re.sub(r"\s*\(.*?\)", "", support_card_name).strip()

            # Initialize an empty object to store the following support card data if it doesn't exist yet.
            if support_card_name not in self.data:
                self.data[support_card_name] = {}

            # Extract the rarity from the parentheses.
            rarity_match = re.search(r"\((SSR|SR|R)\)", support_card_name)
            if rarity_match:
                support_card_rarity = rarity_match.group(1)
                support_card_name = support_card_name.replace(f" ({support_card_rarity})", "").strip()
            else:
                # Fallback to a more basic method.
                support_card_rarity = support_card_name.split(" ")[-1].replace(")", "").replace("(", "").strip()

            # Scrape all the Training Events.
            self.process_training_events(driver, support_card_name, self.data[support_card_name])

        self.save_data()
        driver.quit()


class RaceScraper(BaseScraper):
    """Scrapes the races from the website."""

    def __init__(self):
        super().__init__("https://gametora.com/umamusume/races", "races.json")

    def start(self):
        """Starts the scraping process."""
        driver = create_chromedriver()
        driver.get(self.url)
        time.sleep(5)

        self.handle_cookie_consent(driver)

        # Get references to all the races in the list by locating their race banner images.
        race_images = driver.find_elements(By.CSS_SELECTOR, "img[src*='/race_banners/']")

        # Pop the first 2 races (Junior Make Debut and Junior Maiden Race).
        race_images = race_images[2:]

        # Pop the last 7 races (URA Finals, Grand Masters, Twinkle Star Climax).
        race_images = race_images[:-7]

        logging.info(f"Found {len(race_images)} races.")

        ad_banner_closed = False

        # Iterate through each race.
        for i, link in enumerate(race_images):
            ad_banner_closed = self.handle_ad_banner(driver, ad_banner_closed)

            logging.info(f"Opening race ({i + 1}/{len(race_images)})")
            self.safe_click(driver, link)
            time.sleep(0.5)

            # Acquire the elements needed to scrape the race information.
            dialog = driver.find_element(By.XPATH, "//div[@role='dialog']").find_element(
                By.XPATH, ".//div[contains(@class, 'races_det_wrapper')]"
            )
            dialog_infobox = dialog.find_element(By.XPATH, ".//div[contains(@class, 'races_det_infobox')]")
            dialog_schedules = dialog.find_elements(By.XPATH, ".//div[contains(@class, 'races_det_schedule')]")
            for dialog_schedule in dialog_schedules:
                dialog_schedule_items = dialog_schedule.find_elements(By.XPATH, ".//div[contains(@class, 'races_schedule_item')]")

                # Extract all caption-value pairs for the elements.
                captions = dialog_infobox.find_elements(By.XPATH, ".//div[contains(@class, 'races_det_item_caption')]")
                values = dialog_infobox.find_elements(By.XPATH, ".//div[contains(@class, 'races_det_item__')]")
                info_map = {}
                for cap, val in zip(captions, values):
                    info_map[cap.text.strip()] = val.text.strip()

                race_data = {
                    "name": dialog.find_element(By.XPATH, ".//div[contains(@class, 'races_det_header')]").text,
                    "date": dialog_schedule.find_element(By.XPATH, ".//div[contains(@class, 'races_schedule_header')]").text.replace(
                        "\n", " "
                    ),
                    "raceTrack": info_map.get("Racetrack"),
                    "course": info_map.get("Course"),
                    "direction": "Right" if info_map.get("Direction") in ["Clockwise", "Right"] else "Left",
                    "grade": info_map.get("Grade"),
                    "terrain": info_map.get("Terrain"),
                    "distanceType": info_map.get("Distance (type)"),
                    "distanceMeters": int(info_map.get("Distance (meters)")),
                    "fans": int(
                        dialog_schedule_items[-1]
                        .text.replace("Fans gained", "")
                        .replace("for 1st place", "")
                        .replace("See all", "")
                        .strip()
                    ),
                }

                # Calculate turn number based on the race date.
                race_data["turnNumber"] = calculate_turn_number(race_data["date"])

                # Construct the in-game formatted name of the race.
                distance_type_formatted = "Med" if info_map.get("Distance (type)") == "Medium" else info_map.get("Distance (type)")
                race_data["nameFormatted"] = (
                    f"{race_data['raceTrack']} {race_data['terrain']} {race_data['distanceMeters']}m ({distance_type_formatted}) {race_data['direction']}"
                )
                if race_data["course"]:
                    race_data["nameFormatted"] += f" / {race_data['course']}"

                logging.info(f"Race data: {race_data}")

                # Create a unique key that combines race name and date to handle duplicate race names.
                unique_key = f"{race_data['name']} ({race_data['date']})"
                self.data[unique_key] = race_data

            # Close the dialog.
            dialog_close_button = driver.find_element(By.XPATH, "//div[@role='dialog']").find_element(By.CSS_SELECTOR, "img[src='/images/ui/close.png']")
            self.safe_click(driver, dialog_close_button)
            time.sleep(0.5)

        self.save_data()
        driver.quit()


if __name__ == "__main__":
    logging.basicConfig(format="%(asctime)s - %(levelname)s - %(message)s", level=logging.INFO)
    start_time = time.time()

    skill_scraper = SkillScraper()
    skill_scraper.start()

    after_race_events = load_after_race_events()
    character_scraper = CharacterScraper(after_race_events)
    character_scraper.start()

    support_card_scraper = SupportCardScraper()
    support_card_scraper.start()

    race_scraper = RaceScraper()
    race_scraper.start()

    end_time = round(time.time() - start_time, 2)
    logging.info(f"Total time for processing all applications: {end_time} seconds or {round(end_time / 60, 2)} minutes.")
