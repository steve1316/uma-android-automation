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
