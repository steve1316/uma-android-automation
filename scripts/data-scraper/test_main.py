import pytest
import requests

import main


class _Boom:
    def start(self):
        raise ValueError("bad row")


class _Offline:
    def start(self):
        raise requests.exceptions.ConnectionError("no route")


def test_non_retryable_failure_is_recorded():
    main._run_failures.clear()
    main.run_scraper_with_retry(_Boom(), retries=0)
    assert main._run_failures == ["_Boom: ValueError: bad row"]


def test_network_failure_after_retries_is_recorded():
    main._run_failures.clear()
    main.run_scraper_with_retry(_Offline(), retries=0, backoff=0)
    assert main._run_failures == ["_Offline: ConnectionError: no route"]


def test_character_name_prefers_name_en():
    assert main.character_name({"name_en": "A", "en_name": "B"}) == "A"
    assert main.character_name({"en_name": "B"}) == "B"
    assert main.character_name({}) is None


def test_character_presets_use_name_en(monkeypatch):
    datasets = {
        "characters": [{"char_id": 1, "name_en": "Test Girl", "playable_en": True}],
        "character-cards": [{"card_id": 100101, "char_id": 1, "aptitude": ["A", "G", "F", "C", "A", "B", "A", "A", "B", "C"]}],
    }
    monkeypatch.setattr(main, "fetch_gametora_manifest_data", lambda name: datasets[name])
    monkeypatch.setattr(main.CharacterPresetScraper, "save_data", lambda self: None)
    scraper = main.CharacterPresetScraper()
    scraper.start()
    assert scraper.data["Test Girl"]["distanceAptitudes"] == {"Sprint": "F", "Mile": "C", "Medium": "A", "Long": "B"}


def test_trainee_cards_keep_every_global_outfit_with_title_and_grades(monkeypatch):
    datasets = {
        "characters": [{"char_id": 1, "name_en": "Test Girl", "playable_en": True}],
        "character-cards": [
            {"card_id": 100101, "char_id": 1, "title_en_gl": "[Base Outfit]", "release_en": "2025-01-01", "aptitude": list("AGFCABAABC"), "stat_bonus": [10, 0, 20, 0, 0]},
            {"card_id": 100102, "char_id": 1, "title_en_gl": "[Second Outfit]", "release_en": "2025-06-01", "aptitude": list("AGFBAAGABA"), "stat_bonus": [0, 10, 0, 10, 10]},
            {"card_id": 100103, "char_id": 1, "title_en_gl": "[JP Only]", "release_en": None, "aptitude": list("AGFBAAGABA"), "stat_bonus": [0, 0, 0, 0, 0]},
        ],
    }
    monkeypatch.setattr(main, "fetch_gametora_manifest_data", lambda name: datasets[name])
    monkeypatch.setattr(main.TraineeCardScraper, "_read_existing", lambda self: {})
    monkeypatch.setattr(main.TraineeCardScraper, "save_data", lambda self: None)
    scraper = main.TraineeCardScraper()
    scraper.start()
    assert sorted(scraper.data) == ["100101", "100102"]
    card = scraper.data["100102"]
    assert card["name"] == "Test Girl" and card["title"] == "[Second Outfit]"
    assert card["aptitudes"] == {"turf": "A", "dirt": "G", "sprint": "F", "mile": "B", "medium": "A", "long": "A", "front": "G", "pace": "A", "late": "B", "end": "A"}
    assert card["growth"] == {"speed": 0, "stamina": 10, "power": 0, "guts": 10, "wit": 10}


def test_trainee_cards_merge_additively(monkeypatch):
    datasets = {
        "characters": [{"char_id": 1, "name_en": "Test Girl", "playable_en": True}],
        "character-cards": [{"card_id": 100101, "char_id": 1, "title_en_gl": "[New Title]", "release_en": "2025-01-01", "aptitude": list("AGFCABAABC"), "stat_bonus": [0, 0, 0, 0, 0]}],
    }
    monkeypatch.setattr(main, "fetch_gametora_manifest_data", lambda name: datasets[name])
    monkeypatch.setattr(main.TraineeCardScraper, "_read_existing", lambda self: {"100101": {"name": "Test Girl", "title": "[Curated Title]"}})
    monkeypatch.setattr(main.TraineeCardScraper, "save_data", lambda self: None)
    scraper = main.TraineeCardScraper()
    scraper.start()
    assert scraper.data["100101"]["title"] == "[Curated Title]"


def test_support_cards_keep_title_type_and_rarity(monkeypatch):
    datasets = {
        "support-cards": [
            {"support_id": 30107, "char_name": "Maruzensky", "title_en": "[Sentimental Flare ♪]", "type": "speed", "rarity": 3, "release_en": "2025-01-01"},
            {"support_id": 30999, "char_name": "The Throne's Assemblage", "title_en": "[Group Card]", "type": "group", "rarity": 3, "release_en": "2025-01-01"},
            {"support_id": 31000, "char_name": "Future Girl", "title_en": "[Not Yet]", "type": "wit", "rarity": 3, "release_en": None},
        ],
    }
    monkeypatch.setattr(main, "fetch_gametora_manifest_data", lambda name: datasets[name])
    monkeypatch.setattr(main.SupportCardCatalogScraper, "_read_existing", lambda self: {})
    monkeypatch.setattr(main.SupportCardCatalogScraper, "save_data", lambda self: None)
    scraper = main.SupportCardCatalogScraper()
    scraper.start()
    assert sorted(scraper.data) == ["30107", "30999"]
    assert scraper.data["30107"] == {"name": "Maruzensky", "title": "[Sentimental Flare ♪]", "type": "Speed", "rarity": "SSR"}
    assert scraper.data["30999"]["name"] == "Heirs to the Throne" and scraper.data["30999"]["type"] == "Group"


class _FakeResponse:
    def __init__(self, body):
        self._body = body

    def raise_for_status(self):
        pass

    def json(self):
        return self._body


def test_fetch_soup_goes_through_flaresolverr_when_configured(monkeypatch):
    calls = []

    def fake_post(url, json, timeout):
        calls.append((url, json))
        return _FakeResponse({"status": "ok", "solution": {"status": 200, "response": "<html><h3>SS Tier</h3></html>"}})

    monkeypatch.setenv("FLARESOLVERR_URL", "http://localhost:8191/")
    monkeypatch.setattr(main.requests, "post", fake_post)
    soup = main.fetch_soup("https://game8.co/page")
    assert soup.find("h3").get_text() == "SS Tier"
    assert calls[0][0] == "http://localhost:8191/v1"
    assert calls[0][1]["cmd"] == "request.get" and calls[0][1]["url"] == "https://game8.co/page"
    assert calls[0][1]["waitInSeconds"] == main.FLARESOLVERR_WAIT_SECONDS


def test_fetch_soup_raises_when_flaresolverr_page_fails(monkeypatch):
    monkeypatch.setenv("FLARESOLVERR_URL", "http://localhost:8191")
    monkeypatch.setattr(main.requests, "post", lambda url, json, timeout: _FakeResponse({"status": "ok", "solution": {"status": 403, "response": ""}}))
    monkeypatch.setattr(main.requests, "get", lambda *args, **kwargs: pytest.fail("fetched over plain HTTP"))
    with pytest.raises(requests.exceptions.HTTPError):
        main.fetch_soup("https://game8.co/page")


def test_empty_source_page_is_recorded_as_a_failure():
    main._run_failures.clear()
    assert main.record_if_empty("Game8 skill tier list", {}) == {}
    assert main._run_failures == ["Game8 skill tier list: no rows parsed"]
    main._run_failures.clear()
    main.record_if_empty("Game8 skill tier list", {"Skill": 0})
    assert main._run_failures == []
