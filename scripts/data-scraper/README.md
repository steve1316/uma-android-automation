# Game Data Update Instructions

This directory contains the Python scraper that produces the game-data JSON files in [`src/data/`](../../src/data/) used by the Uma Musume Android Automation bot.

## Prerequisites

- **Python 3.10+**: Ensure you have Python installed and added to your PATH.

## Installation and Running

Plain HTTP only. Install the required Python dependencies using `pip`:

```bash
pip install -r requirements.txt
```

Then update all game data files (`skills.json`, `characters.json`, `supports.json`, `races.json`, `epithets.json`, and `characterPresets.json`) by running from the repo root:

```bash
python update.py
```

To exit with code 1 if any scraper or card fetch failed (used by CI):

```bash
python update.py --strict
```

Strict mode also fails when the Game8 skill tier list or the umamusu.wiki evaluation points page parses to nothing, which usually means a bot check page was served. Set `FLARESOLVERR_URL` (e.g. `http://localhost:8191`) to load those pages through a [FlareSolverr](https://github.com/FlareSolverr/FlareSolverr) browser instead, as CI does.

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
