# Game Data Update Instructions

This directory contains the Python scraper that produces the game-data JSON files in [`src/data/`](../../src/data/) used by the Uma Musume Android Automation bot.

## Prerequisites

- **Python 3.10+**: Ensure you have Python installed and added to your PATH.
- **Google Chrome**: Required for scraping data via Selenium.
- **Chrome Driver**: Selenium will attempt to manage this automatically, but ensure your Chrome is up to date.

## Installation

Install the required Python dependencies using `pip`:

```bash
pip install -r scripts/data-scraper/requirements.txt
```

## Updating Game Data

To update all game data files (`skills.json`, `characters.json`, `supports.json`, `races.json`, `epithets.json`, and `characterPresets.json`), run the following command from the repo root:

```bash
python scripts/data-scraper/main.py
```

The script writes its output into [`src/data/`](../../src/data/) regardless of the current working directory (paths are resolved via `Path(__file__).resolve().parents[2] / "src" / "data"`).

### What this script does:

Each pass is a scraper class invoked from the `__main__` block at the bottom of `main.py`, in this order:

1.  **Skills** (`SkillScraper`): Scrapes skill data, evaluation points (from Umamusume Wiki), and tier lists (from Game8).
2.  **Characters** (`CharacterScraper`): Scrapes character-specific training events and "After a Race" events.
3.  **Support Cards** (`SupportCardScraper`): Scrapes support card training events and effects.
4.  **Races** (`RaceScraper`): Scrapes race information and calculates turn numbers for the in-game calendar. **Commented out by default** - races only change when Global takes a content update, so uncomment the call deliberately when one lands.
5.  **Epithets** (`EpithetScraper`): Scrapes nickname rewards and conditions; preserves the curated `dependsOn` and `matchers` fields used by the Smart Race Solver.
6.  **Character Presets** (`CharacterPresetScraper`): Scrapes per-character distance and surface aptitudes used by the Smart Race Solver as starting aptitude defaults (`characterPresets.json`). Selectors are best-effort and may need updating if gametora reshuffles its CSS modules.
7.  **Character Objectives** (`CharacterObjectivesScraper`): Scrapes the per-character mandatory career-objective races for the URA scenario (`character_objectives.json`). The Smart Race Solver uses these to lock the turns the game forces a race on.

### Global-only and additive

The scraper is written to be safe to re-run at any time:

- **Global-only.** Entries are gated on `release_en` being set and not in the future, so unreleased JP content never leaks into the app's data.
- **Additive.** Merges into the existing JSON use `setdefault`, so a re-scrape adds new entries but never overwrites values already on disk. This is what protects hand-curated fields such as the `dependsOn` and `matchers` on each epithet. Correcting an existing value therefore means editing the JSON by hand or deleting the stale entry first.

> [!TIP]
> The scrape is chatty. Redirect it to a log file so you can follow along, and pass `-u` so Python does not buffer the output:
>
> ```bash
> python -u update.py > scraper_run.log 2>&1
> ```
