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
