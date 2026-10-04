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
