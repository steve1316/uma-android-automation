import json
from datetime import datetime, timezone
from pathlib import Path

import refresh

CHAR_CARDS = [
    {"card_id": 102101, "name_en": "Tamamo Cross", "version": None, "release_en": "2025-06-26"},
    {"card_id": 102143, "name_en": "Tamamo Cross", "version": "fall_festival", "release_en": "2026-09-07"},
    {"card_id": 100846, "name_en": "Vodka", "version": "christmas", "release_en": "2026-09-28"},
    {"card_id": 104901, "name_en": "Nakayama Festa", "version": None, "release_en": "2026-09-15"},
    {"card_id": 199901, "name_en": "Future Girl", "version": None, "release_en": "2027-01-01"},
    {"card_id": 199902, "name_en": "JP Only", "version": None, "release_en": None},
]
SUPPORT_CARDS = [
    {"support_id": 30120, "char_name": "Yaeno Muteki", "release_en": "2026-09-07"},
    {"support_id": 30125, "char_name": "Sakura Laurel", "release_en": "2026-09-28"},
    {"support_id": 30999, "char_name": "The Throne's Assemblage", "release_en": "2026-09-29"},
]


def test_released_cards_drops_future_and_undated():
    released = refresh.released_cards(CHAR_CARDS, SUPPORT_CARDS, "2026-10-04")
    assert [c["card_id"] for c in released["character"]] == [102101, 102143, 100846, 104901]
    assert [c["support_id"] for c in released["support"]] == [30120, 30125, 30999]


def test_new_cards_excludes_included_ids():
    released = refresh.released_cards(CHAR_CARDS, SUPPORT_CARDS, "2026-10-04")
    fresh = refresh.new_cards(released, {"character": [102101, 102143], "support": [30120]})
    assert [c["card_id"] for c in fresh["character"]] == [100846, 104901]
    assert [c["support_id"] for c in fresh["support"]] == [30125, 30999]


def test_character_label_maps_outfit_codes():
    assert refresh.character_label(CHAR_CARDS[0]) == "Tamamo Cross"
    assert refresh.character_label(CHAR_CARDS[1]) == "Tamamo Cross (Festival)"
    assert refresh.character_label(CHAR_CARDS[2]) == "Vodka (Christmas)"
    assert refresh.character_label({"name_en": "X", "version": "summer_camp"}) == "X (Summer Camp)"


def test_support_label_applies_name_overrides():
    assert refresh.support_label(SUPPORT_CARDS[2]) == "Heirs to the Throne"


def test_build_label_prefers_newest_trainee_then_support_then_date():
    trainee = {"character": [CHAR_CARDS[3], CHAR_CARDS[2]], "support": [SUPPORT_CARDS[1]]}
    assert refresh.build_label(trainee, "2026-10-04") == "Vodka (Christmas) 2026-09-28"
    support_only = {"character": [], "support": [SUPPORT_CARDS[0], SUPPORT_CARDS[1]]}
    assert refresh.build_label(support_only, "2026-10-04") == "Sakura Laurel 2026-09-28"
    assert refresh.build_label({"character": [], "support": []}, "2026-10-04") == "2026-10-04"
    assert refresh.commit_subject("Vodka (Christmas) 2026-09-28") == "Update game data up to Vodka (Christmas) 2026-09-28"


def test_file_md5_ignores_crlf(tmp_path: Path):
    lf = tmp_path / "lf.json"
    crlf = tmp_path / "crlf.json"
    lf.write_bytes(b'{\n    "a": 1\n}\n')
    crlf.write_bytes(b'{\r\n    "a": 1\r\n}\r\n')
    assert refresh.file_md5(lf) == refresh.file_md5(crlf) == "e7b14d5dfc9c22649d62e565e83b1256"


GOOD = {
    "characters.json": {"Vodka": {"Event A": ["Speed +10"]}, "Nakayama Festa": {"Event B": ["Guts +10"]}},
    "supports.json": {"Sakura Laurel": {"Event C": ["Stamina +5"]}},
    "skills.json": {"Skill A": {"id": 1, "name_en": "Skill A", "icon_id": 10011}},
    "epithets.json": {"Ep": {"name": "Ep", "bullet_points": ["Win"]}},
    "characterPresets.json": {
        "Vodka": {"name": "Vodka", "distanceAptitudes": {}, "surfaceAptitudes": {}},
        "Nakayama Festa": {"name": "Nakayama Festa", "distanceAptitudes": {}, "surfaceAptitudes": {}},
    },
    "character_objectives.json": {
        "Vodka": {"name": "Vodka", "mandatoryRaces": []},
        "Nakayama Festa": {"name": "Nakayama Festa", "mandatoryRaces": []},
    },
}
FRESH = {"character": [CHAR_CARDS[2], CHAR_CARDS[3]], "support": [SUPPORT_CARDS[1]]}


def test_find_problems_passes_good_data():
    assert refresh.find_problems(GOOD, GOOD, FRESH) == []


def test_find_problems_flags_shrink_bad_json_and_bad_shape():
    new = dict(GOOD)
    new["supports.json"] = {}
    new["skills.json"] = None
    new["epithets.json"] = {"Ep": {"name": "Ep"}}
    problems = refresh.find_problems(GOOD, new, {"character": [], "support": []})
    assert "supports.json: entry count dropped from 1 to 0" in problems
    assert "skills.json: missing or not valid JSON" in problems
    assert "epithets.json: Ep is missing bullet_points" in problems


def test_find_problems_flags_incomplete_new_trainee_and_support():
    new = json.loads(json.dumps(GOOD))
    del new["characters.json"]["Nakayama Festa"]
    del new["character_objectives.json"]["Nakayama Festa"]
    new["supports.json"] = {"Other": {"E": ["x"]}}
    problems = refresh.find_problems({}, new, FRESH)
    assert "new trainee Nakayama Festa has no entry in characters.json" in problems
    assert "new trainee Nakayama Festa has no entry in character_objectives.json" in problems
    assert "new support card Sakura Laurel has no entry in supports.json" in problems


def test_find_problems_flags_empty_event_lists():
    new = json.loads(json.dumps(GOOD))
    new["characters.json"]["Vodka"] = {}
    assert "characters.json: Vodka has no events" in refresh.find_problems(GOOD, new, {"character": [], "support": []})


def test_plan_work_reports_moved_ids_and_new_cards():
    index = {name: "a" for name in refresh.WATCHED_DATASETS}
    saved = dict(index)
    none = {"character": [], "support": []}
    assert refresh.plan_work(index, saved, none) == []
    saved["support-cards"] = "old"
    assert refresh.plan_work(index, saved, none) == ["support-cards moved"]
    assert refresh.plan_work(index, index, FRESH) == ["new trainee Vodka (Christmas)", "new trainee Nakayama Festa", "new support card Sakura Laurel"]


def test_merge_included_adds_fresh_ids_sorted():
    merged = refresh.merge_included({"character": [5], "support": [9]}, FRESH)
    assert merged == {"character": [5, 100846, 104901], "support": [9, 30125]}


def test_write_data_version_hashes_every_updatable_file(tmp_path, monkeypatch):
    for name in refresh.UPDATABLE_FILES:
        (tmp_path / name).write_text("{}\n", encoding="utf-8")
    monkeypatch.setattr(refresh, "DATA_DIR", tmp_path)
    monkeypatch.setattr(refresh, "DATA_VERSION_PATH", tmp_path / "data_version.json")
    stamp = refresh.write_data_version("Vodka (Christmas) 2026-09-28", datetime(2026, 10, 4, 12, 0, 5, tzinfo=timezone.utc))
    on_disk = json.loads((tmp_path / "data_version.json").read_text(encoding="utf-8"))
    assert on_disk == stamp
    assert stamp["version"] == "2026-10-04T12:00:05Z"
    assert stamp["minAppVersion"] == refresh.MIN_APP_VERSION
    assert sorted(stamp["files"]) == sorted(refresh.UPDATABLE_FILES)
