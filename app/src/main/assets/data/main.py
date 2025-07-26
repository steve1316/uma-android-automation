import undetected_chromedriver as uc
from selenium.webdriver.common.by import By
from selenium.common.exceptions import NoSuchElementException
from selenium.webdriver.remote.webelement import WebElement
import json
import re
import time
import logging
from typing import List, Dict


def create_chromedriver():
    """Creates the Chrome driver for scraping.

    Returns:
        The Chrome driver.
    """
    options = uc.ChromeOptions()
    options.add_argument('--headless')
    driver = uc.Chrome(options=options, use_subprocess=True)
    return driver

class BaseScraper:
    """Base class for scraping data from the website.

    Args:
        url (str): The URL to scrape.
        output_filename (str): The filename to save the scraped data to.
    """
    def __init__(self, url: str, output_filename: str):
        self.url = url
        self.output_filename = output_filename
        self.data = {}
        self.cookie_accepted = False
        
    def save_data(self):
        """Saves the scraped data to a file."""
        with open(self.output_filename, "w", encoding="utf-8") as f:
            json.dump(self.data, f, ensure_ascii=False, indent=4)
        logging.info(f"Saved {len(self.data)} items to {self.output_filename}")
        
    def handle_cookie_consent(self, driver: uc.Chrome):
        """Handles the cookie consent.

        Args:
            driver (uc.Chrome): The Chrome driver.
        """
        if not self.cookie_accepted:
            try:
                cookie_consent_button = driver.find_element(By.XPATH, "//button[contains(@class, 'legal_cookie_banner_button__Oy_Qr')]")
                if cookie_consent_button:
                    cookie_consent_button.click()
                    time.sleep(0.1)
                    self.cookie_accepted = True
                    logging.info("Cookie consent accepted.")
            except NoSuchElementException:
                logging.info("No cookie consent button found.")
                self.cookie_accepted = True
                
    def extract_training_event_options(self, tooltip_rows: List[WebElement]):
        """Extracts the training event options from the tooltip rows.

        Args:
            tooltip_rows (List[WebElement]): The tooltip rows.

        Returns:
            The training event options.
        """
        options = []
        for tooltip_row in tooltip_rows:
            td = tooltip_row.find_elements(By.XPATH, ".//td[contains(@class, 'tooltips_ttable_cell___3NMF')]")[1]
            td_divs = td.find_elements(By.XPATH, ".//div")
            text_fragments = [div.text.strip() for div in td_divs]

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
    
    def process_training_events(self, driver: uc.Chrome, item_name: str, data_dict: Dict[str, List[str]]):
        """Processes the training events for the given item.

        Args:
            driver (uc.Chrome): The Chrome driver.
            item_name (str): The name of the item.
            data_dict (Dict[str, List[str]]): The data dictionary to modify.
        """
        all_training_events = driver.find_elements(By.XPATH, "//div[contains(@class, 'compatibility_viewer_item__SWULM')]")
        logging.info(f"Found {len(all_training_events)} training events for {item_name}.")

        for j, training_event in enumerate(all_training_events):
            training_event.click()
            time.sleep(0.3)

            tooltip = driver.find_element(By.XPATH, "//div[@data-tippy-root]")
            try:
                tooltip_title = tooltip.find_element(By.XPATH, ".//div[contains(@class, 'tooltips_ttable_heading__jlJcE')]").text
            except NoSuchElementException:
                logging.info(f"No tooltip title found for training event ({j + 1}/{len(all_training_events)}).")
                continue

            tooltip_rows = tooltip.find_elements(By.XPATH, ".//tr")
            if len(tooltip_rows) == 0:
                logging.info(f"No options found for training event {tooltip_title} ({j + 1}/{len(all_training_events)}).")
                continue
            elif tooltip_title in data_dict:
                logging.info(f"Training event {tooltip_title} ({j + 1}/{len(all_training_events)}) already exists.")
                continue

            logging.info(f"Found {len(tooltip_rows)} options for training event {tooltip_title} ({j + 1}/{len(all_training_events)}).")
            data_dict[tooltip_title] = self.extract_training_event_options(tooltip_rows)

class SkillScraper(BaseScraper):
    """Scrapes the skills from the website."""
    def __init__(self):
        super().__init__("https://gametora.com/umamusume/skills", "skills.json")

    def start(self):
        """Starts the scraping process."""
        driver = create_chromedriver()
        driver.get(self.url)
        time.sleep(3)

        # Show the Settings dropdown and toggle "Show skill IDs" and "For character-specific skills..."
        show_settings_button = driver.find_element(By.XPATH, "//div[contains(@class, 'utils_padbottom_half__VUTlR')]//button[contains(@class, 'filters_button_moreless__u5k7N')]")
        show_settings_button.click()
        time.sleep(0.1)
        show_skill_ids_checkbox = driver.find_element(By.XPATH, "//input[contains(@id, 'showIdCheckbox')]")
        show_skill_ids_checkbox.click()
        time.sleep(0.1)
        show_character_specific_checkbox = driver.find_element(By.XPATH, "//input[contains(@id, 'showUniqueCharCheckbox')]")
        show_character_specific_checkbox.click()
        time.sleep(0.1)

        all_skill_rows = driver.find_elements(By.CSS_SELECTOR, "div.skills_table_row_ja__pAfOT")
        logging.info(f"Found {len(all_skill_rows)} non-hidden and hidden skill rows.")

        # Scrape all skill rows.
        for i, skill_row in enumerate(all_skill_rows):
            skill_name = skill_row.find_element(By.XPATH, ".//div[contains(@class, 'skills_table_jpname__ga5DL')]").text
            skill_description = skill_row.find_element(By.XPATH, ".//div[contains(@class, 'skills_table_desc__i63a8')]").text

            # Strip the skill ID from the description.
            skill_id_match = re.search(r'\((\d+)\)$', skill_description)
            skill_id = skill_id_match.group(1) if skill_id_match else None
            clean_description = re.sub(r'\s*\(\d+\)$', '', skill_description) if skill_id else skill_description

            if skill_name and skill_name not in self.data:
                logging.info(f"Scraped skill ({i + 1}/{len(all_skill_rows)}): {skill_name}")
                self.data[skill_name] = {
                    "id": int(skill_id),
                    "englishName": skill_name,
                    "englishDescription": clean_description
                }

        self.save_data()
        driver.quit()

class CharacterScraper(BaseScraper):
    """Scrapes the characters from the website."""
    def __init__(self):
        super().__init__("https://gametora.com/umamusume/characters", "characters.json")

    def start(self):
        """Starts the scraping process."""
        driver = create_chromedriver()
        driver.get(self.url)
        time.sleep(3)

        # Sort the characters by ascending order.
        self._sort_by_name(driver)

        # Get all character links.
        character_grid = driver.find_element(By.XPATH, "//div[contains(@class, 'sc-70f2d7f-0 dSgCQa')]")
        character_items = character_grid.find_elements(By.XPATH, ".//a[contains(@class, 'sc-73e3e686-1 iAslZY')]")
        logging.info(f"Found {len(character_items)} characters.")
        character_links = [item.get_attribute("href") for item in character_items]

        # Iterate through each character.
        for i, link in enumerate(character_links):
            logging.info(f"Navigating to {link} ({i + 1}/{len(character_links)})")
            driver.get(link)
            time.sleep(3)

            self.handle_cookie_consent(driver)

            character_name = driver.find_element(By.XPATH, "//h1[contains(@class, 'utils_headingXl__vl546')]").text
            character_name = character_name.replace("(Original)", "").strip()
            # Remove any other parentheses that denote different forms of the character like "Wedding" or "Swimsuit".
            character_name = re.sub(r'\s*\(.*?\)', '', character_name).strip()

            # Initialize an empty object to store the following character data if it doesn't exist yet.
            if character_name not in self.data:
                self.data[character_name] = {}

            # Scrape all the Training Events.
            self.process_training_events(driver, character_name, self.data[character_name])

        self.save_data()
        driver.quit()

    def _sort_by_name(self, driver: uc.Chrome):
        """Sorts the characters by name in ascending order.

        Args:
            driver (uc.Chrome): The Chrome driver.
        """
        # Click on the "Sort by" dropdown and select "Name".
        sort_by_dropdown = driver.find_element(By.XPATH, "//div[contains(@class, 'filters_sort_row__2mli0')]")
        first_select = sort_by_dropdown.find_element(By.XPATH, ".//select[1]")
        first_select.click()
        time.sleep(0.1)
        name_option = first_select.find_element(By.XPATH, ".//option[@value='name']")
        name_option.click()
        time.sleep(0.1)

        # Then sort by ascending order.
        second_select = sort_by_dropdown.find_element(By.XPATH, ".//select[2]")
        second_select.click()
        time.sleep(0.1)
        ascending_option = second_select.find_element(By.XPATH, ".//option[@value='asc']")
        ascending_option.click()
        time.sleep(0.1)

class SupportCardScraper(BaseScraper):
    """Scrapes the support cards from the website."""
    def __init__(self):
        super().__init__("https://gametora.com/umamusume/supports", "supports.json")

    def start(self):
        """Starts the scraping process."""
        driver = create_chromedriver()
        driver.get(self.url)
        time.sleep(3)

        # Get all support card links.
        support_card_grid = driver.find_element(By.XPATH, "//div[contains(@class, 'sc-70f2d7f-0 dSgCQa')]")
        support_card_items = support_card_grid.find_elements(By.XPATH, ".//a[contains(@class, 'sc-73e3e686-1 iAslZY')]")
        logging.info(f"Found {len(support_card_items)} support cards.")
        support_card_links = [item.get_attribute("href") for item in support_card_items]

        # Iterate through each support card.
        for i, link in enumerate(support_card_links):
            logging.info(f"Navigating to {link} ({i + 1}/{len(support_card_links)})")
            driver.get(link)
            time.sleep(3)

            self.handle_cookie_consent(driver)

            support_card_name = driver.find_element(By.XPATH, "//h1[contains(@class, 'utils_headingXl__vl546')]").text
            support_card_name = support_card_name.replace("Support Card", "").strip()
            # Remove any other parentheses that denote different forms of the support card.
            support_card_name = re.sub(r'\s*\(.*?\)', '', support_card_name).strip()

            # Initialize an empty object to store the following support card data if it doesn't exist yet.
            if support_card_name not in self.data:
                self.data[support_card_name] = {}

            # Extract the rarity from the parentheses.
            rarity_match = re.search(r'\((SSR|SR|R)\)', support_card_name)
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

if __name__ == '__main__':
    logging.basicConfig(format='%(asctime)s - %(levelname)s - %(message)s', level=logging.INFO)
    start_time = time.time()

    skill_scraper = SkillScraper()
    skill_scraper.start()

    character_scraper = CharacterScraper()
    character_scraper.start()

    support_card_scraper = SupportCardScraper()
    support_card_scraper.start()

    end_time = round(time.time() - start_time, 2)
    logging.info(f"Total time for processing all applications: {end_time} seconds or {round(end_time / 60, 2)} minutes.")
