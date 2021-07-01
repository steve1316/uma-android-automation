import json

import requests
from bs4 import BeautifulSoup


class SkillScraper:

    # Fetch the page's contents.
    def _get_page_content(self, url: str):
        http_response = requests.get(url)
        soup = None

        if http_response.ok:
            soup = BeautifulSoup(http_response.text, 'html.parser')
        else:
            print(f"HTTP Request came back with {http_response.status_code}")

        return soup

    # Parse out the json containing all the skills.
    def _fetch_skill_list(self, soup: BeautifulSoup):
        script_body = soup.find('script', id = "__NEXT_DATA__")

        json_object = json.loads(script_body.contents[0])
        skill_list = json_object["props"]["pageProps"]["skillData"]

        print(f"\nThere are {len(skill_list)} Skills in the JSON.\n")

        return skill_list

    # Format the skills by deleting unneeded properties and then sort the list.
    def _format_skill_list(self, skill_list: list):
        to_delete = ["jpdesc", "iconid", "condition", "skill_value", "base_time", "activation", "rarity", "cost", "type", "characters", "supports_hint", "supports_event"]

        for i in range(len(skill_list)):
            for prop in to_delete:
                try:
                    del skill_list[i][prop]
                except KeyError:
                    pass

        # Now sort by id ascending.
        skill_list = sorted(skill_list, key=lambda k: k.get('id', 0), reverse = False)

        return skill_list

    def start(self, url: str):
        soup = self._get_page_content(url)
        skill_list = self._fetch_skill_list(soup)
        skill_list = self._format_skill_list(skill_list)

        # Save a JSON version of the newly formatted skills list.
        with open("skills.json", "w", encoding = "utf-8") as file:
            json.dump(skill_list, file, ensure_ascii = False, indent = 4)

        # Now save a txt version of the strings of formatted Kotlin code for SkillData.kt.
        with open("skills.txt", "w", encoding = "utf-8") as file:
            for skill in skill_list:
                file.write(f"\"{skill['jpname']}\" to mapOf(\n\t\"id\" to {int(skill['id'])},\n\t\"englishName\" to \"{skill['enname']}\",\n\t\"englishDescription\" to \"{skill['endesc']}\"),\n")

        return None


if __name__ == '__main__':
    scraper = SkillScraper()

    scraper.start("https://gametora.com/umamusume/skills")
