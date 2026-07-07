# How It Works

*Last updated: 2026-06-27*

A comprehensive guide to the inner workings of the app. This document explains what the bot does at each step of a campaign, how it makes decisions, and how each scenario differs.

## Table of Contents

- [1. Architecture Overview](#1-architecture-overview)
- [2. The Turn System](#2-the-turn-system)
- [3. The Main Loop](#3-the-main-loop)
- [4. A Turn in Detail](#4-a-turn-in-detail)
- [5. Decision Engine](#5-decision-engine)
- [6. Training System](#6-training-system)
- [7. Racing System](#7-racing-system)
- [8. Training Events](#8-training-events)
- [9. Scenario: URA Finale](#9-scenario-ura-finale)
- [10. Scenario: Unity Cup](#10-scenario-unity-cup)
- [11. Scenario: Trackblazer](#11-scenario-trackblazer)
- [12. Support-Card Dating Schedule](#12-support-card-dating-schedule)
- [13. Smart Race Solver](#13-smart-race-solver)
- [14. Ask the Docs Chatbot](#14-ask-the-docs-chatbot)
- [15. Decision Tracer](#15-decision-tracer)
- [16. Remote Log Viewer & Run Analytics](#16-remote-log-viewer--run-analytics)

---

## 1. Architecture Overview

The bot is an Android app built with a **React Native** frontend (settings UI, message log) and a **Kotlin** backend (automation engine). It uses the [Android CV Automation Library](https://github.com/steve1316/android-cv-automation-library) framework to interact with the game.

**How it sees the screen:** A `MediaProjectionService` captures the device screen. The bot then uses **OpenCV template matching** (TM_CCOEFF_NORMED with multi-scale search) to detect buttons, icons, and dialogs, **OCR** (Google ML Kit + Tesseract) to read text like stat values, event names, and race names, and optionally **YOLOv8 object detection** (via ONNX Runtime) to detect training stat gain digits with higher accuracy than template matching.

**How it interacts:** An `AccessibilityService` performs tap and swipe gestures on the device.

**How it bootstraps:** On first launch the entire app is gated behind a one-time **First-Run Wizard** — a single scrollable page of setup cards — instead of the old Home-screen permission dialog. The wizard is mounted by `useFirstRunGate()`, which reads and writes a `firstRun.completed` flag in SQLite so it only appears once. Card 1 asks where the bot should save its files: the user can pick any folder through the Android **Storage Access Framework (SAF)** picker, or fall back to **App default (internal storage)** (also used automatically on devices that have no document picker). Card 2 appears only when a scan finds files in the old `logs/` and `recordings/` locations and offers to **Move**, **Leave**, or **Delete** them. Card 3 is the **System Checks** list, which walks the user through the Accessibility, Display-over-other-apps (overlay), and Battery-optimization permissions; **Finish** stays disabled until all three are granted (and, for a SAF folder, a write-access probe succeeds).

File storage is brokered by the native `StorageBridgeModule`: logs and screen recordings are written under the chosen SAF tree (in `logs/` and `recordings/` subfolders) or under internal app storage when the fallback is selected. The older `PermissionSetupDialog` still exists on the Home page as an optional on-demand permission re-check, but it no longer drives first-run setup.

**How it decides:** The bot runs a `process()` loop that is called repeatedly by the `Game` class. Each call handles one "tick" — detecting which screen the game is on and taking the appropriate action.

```mermaid
classDiagram
    class Campaign {
        +process() TaskResult?
        +handleMainScreen() Boolean
        +decideNextAction() MainScreenAction
        +executeAction() Boolean
    }
    Campaign <|-- UraFinale
    Campaign <|-- UnityCup
    Campaign <|-- Trackblazer
    Campaign *-- Racing
    Campaign *-- Training
    Campaign *-- TrainingEvent
    Campaign *-- SkillPlan
    Campaign *-- Trainee
```

The `Game` class instantiates the correct scenario subclass (`UraFinale`, `UnityCup`, or `Trackblazer`) based on the user's selection, then calls `process()` in a loop until the campaign ends or the bot is stopped.

---

## 2. The Turn System

A full campaign spans **75 turns** across 3 years plus a finale season:

| Year | Turns | Months |
|------|-------|--------|
| **Junior** | 1–24 | Pre-Debut (1–11), Debut Race (12), Post-Debut (13–24) |
| **Classic** | 25–48 | Regular (25–36), Summer (37–40), Regular (41–48) |
| **Senior** | 49–72 | Regular (49–60), Summer (61–64), Regular (65–72) |
| **Finale** | 73–75 | Qualifier (73), Semi-Final (74), Finals (75) |

Each year has 12 months with 2 phases each (Early and Late), totaling 24 turns per year. Months run from January through December.

```mermaid
gantt
    title Campaign Timeline (75 Turns)
    dateFormat X
    axisFormat %s
    section Junior Year
    Pre-Debut (1-11)     :a1, 1, 11
    Debut Race (12)      :milestone, m1, 12, 12
    Post-Debut (13-24)   :a2, 13, 24
    section Classic Year
    Regular (25-36)      :a3, 25, 36
    Summer (37-40)       :crit, a4, 37, 40
    Regular (41-48)      :a5, 41, 48
    section Senior Year
    Regular (49-60)      :a6, 49, 60
    Summer (61-64)       :crit, a7, 61, 64
    Regular (65-72)      :a8, 65, 72
    section Finale
    Qualifier (73)       :crit, a9, 73, 73
    Semi-Final (74)      :crit, a10, 74, 74
    Finals (75)          :crit, a11, 75, 75
```

> [!NOTE]
> Sections highlighted in red are **special periods** where normal gameplay rules change — Summer blocks racing entirely, and Finale forces mandatory back-to-back races.

**Key periods:**
- **Pre-Debut (turns 1–11):** No races are available yet. The bot focuses on training and building relationships.
- **Summer (turns 37–40 and 61–64):** Training-only period. No races can be entered (unless using the in-game race agenda override).
- **Finale (turns 73–75):** Three mandatory back-to-back races. Injury and consecutive race checks are skipped.

**Date detection:** The bot reads the date string from the screen via OCR (e.g., "Classic Year Early Feb") and converts it to an internal turn number. During Pre-Debut, it reads a "turns remaining" countdown instead. During Finale, it detects the goal text or Trackblazer's "X/3" indicator.

---

## 3. The Main Loop

Every tick of the bot calls `process()`, which checks the current screen and dispatches to the appropriate handler:

```mermaid
flowchart TD
    Start["process() called"] --> Dialogs{"Any dialogs\ndetected?"}
    Dialogs -->|Yes| HandleDialog["Handle dialog\n(close, confirm, etc.)"]
    HandleDialog --> Return["Return null\n(continue loop)"]
    Dialogs -->|No| MainScreen{"On the\nMain Screen?"}
    MainScreen -->|Yes| HandleMain["handleMainScreen()\n(full turn logic)"]
    HandleMain --> Return
    MainScreen -->|No| TrainingEvent{"Training Event\nscreen?"}
    TrainingEvent -->|Yes| HandleEvent["handleTrainingEvent()\n(select reward option)"]
    HandleEvent --> Return
    TrainingEvent -->|No| MandatoryRace{"Mandatory Race\nPrep screen?"}
    MandatoryRace -->|Yes| HandleMandatory["handleRaceEvents()\n(enter mandatory race)"]
    HandleMandatory --> Return
    MandatoryRace -->|No| RacingScreen{"Already on\nRacing screen?"}
    RacingScreen -->|Yes| HandleRace["handleStandaloneRace()\n(complete the race)"]
    HandleRace --> Return
    RacingScreen -->|No| EndScreen{"Career End\nscreen?"}
    EndScreen -->|Yes| FinalUpdate["Purchase career-end skills\nRead final fan count\nLog final stats"]
    FinalUpdate --> Complete["Return Success\n(bot stops)"]
    EndScreen -->|No| CampaignSpecific{"Campaign-specific\ncondition?"}
    CampaignSpecific -->|Yes| HandleCampaign["Handle scenario logic\n(e.g. Unity Cup race)"]
    HandleCampaign --> Return
    CampaignSpecific -->|No| Inheritance{"Inheritance\nevent?"}
    Inheritance -->|Yes| HandleInherit["Accept inheritance"]
    HandleInherit --> Return
    Inheritance -->|No| Misc["Perform misc checks\nor tap to progress"]
    Misc --> Return
```

**Key points:**
- **Dialogs are always checked first.** Any popup (confirmation, warning, tutorial) is handled before any other logic runs.
- **Main Screen handling** is where the core turn logic lives — stat updates, decision-making, and action execution all happen here.
- **Training Events** appear after a training or race completes and offer reward choices.
- **Campaign-specific conditions** allow each scenario to inject custom screen detection (e.g., Unity Cup's opponent selection screen).
- If no known screen is detected, the bot taps the screen to try to progress past any intermediate animation or transition.

> [!TIP]
> The main loop is designed to be **idempotent** — each call to `process()` handles exactly one screen transition. If the game is between screens or in an animation, the bot simply taps and waits for the next tick.

> [!NOTE]
> **Warning-popup exit threshold.** When `performMiscChecks()` matches a `ButtonCancel` template, the bot does not exit immediately. It increments a `consecutiveButtonCancelMatches` counter and only exits once the counter reaches **5 consecutive iterations**, throwing a `CampaignBreakpointException` with a "Bot may have encountered a warning popup" notification. A single-frame template miss against an unrelated UI element no longer trips a false-positive exit. This replaced the old "Stop on Unexpected Popups" setting.

---

## 4. A Turn in Detail

When the bot detects it is on the Main Screen, `handleMainScreen()` orchestrates the full turn:

```mermaid
sequenceDiagram
    participant Bot as Campaign
    participant OCR as OCR Engine
    participant State as Game State

    Bot->>Bot: onBeforeMainScreenUpdate()
    Note over Bot: Scenario hook (e.g. Trackblazer shop check)

    Bot->>OCR: Read date string from screen
    OCR-->>Bot: "Classic Year Early Feb"
    Bot->>State: updateDate() → turn 27

    Note over Bot: Date changed → reset daily flags

    par 9 parallel OCR threads (10s timeout)
        Bot->>OCR: Read Speed stat
        Bot->>OCR: Read Stamina stat
        Bot->>OCR: Read Power stat
        Bot->>OCR: Read Guts stat
        Bot->>OCR: Read Wit stat
        Bot->>OCR: Read Skill Points
        Bot->>OCR: Read Mood
        Bot->>OCR: Read Energy
        Bot->>OCR: Check racing requirements
    end

    Bot->>Bot: Update aptitudes (first time only)
    Bot->>Bot: Update fan count (if needed)
    Bot->>Bot: performGlobalChecks()
    Bot->>Bot: onMainScreenEntry()
    Note over Bot: Scenario hook (e.g. Trackblazer item usage)
    Bot->>Bot: decideNextAction()
    Bot->>Bot: executeAction()
```

### 4.1 Parallel Turn-Start Updates

Every time the date changes, the bot reads the trainee's current state using **9 parallel OCR threads** coordinated by a `CountDownLatch` with a **10-second timeout**:

| Thread | Reads | Method |
|--------|-------|--------|
| 1–5 | Speed, Stamina, Power, Guts, Wit | `trainee.updateStats()` |
| 6 | Skill Points | `trainee.updateSkillPoints()` |
| 7 | Mood (icon-based detection) | `trainee.updateMood()` |
| 8 | Racing requirements (fans/trophies) | `racing.checkRacingRequirements()` |
| 9 | Energy (bar position) | `trainee.updateEnergy()` |

Thread 8 (racing requirements) is **skipped during summer** since no races are available. Logging output is temporarily disabled during parallel reads to avoid garbled messages, then re-enabled after all threads complete.

> [!WARNING]
> If any thread fails to complete within the 10-second timeout, the bot logs an error and continues with whatever data it managed to read. Stat values that timed out retain their previous values.

### 4.2 Global Checks

After stat updates, the bot performs several global checks that can stop or pause the campaign:

1. **Pre-Finals Skill Shopping (turn 72):** If the `preFinals` skill plan is enabled, the bot opens the skill shop and purchases skills before entering the finale.
2. **Skill Point Threshold:** If skill points reach the configured threshold, the bot either runs the `skillPointCheck` skill plan or stops entirely.
3. **Stop Before Finals:** If `enableStopBeforeFinals` is on and the bot reaches turn 72, it stops so the user can take over for the finals.
4. **Stop at Date:** If the current date matches any user-configured stop dates, the bot stops.

> [!NOTE]
> **Skill-plan auto-purchase.** The skill plans referenced above (the skill-point-threshold plan, the pre-Finals plan, and the career-complete plan) auto-buy skills filtered two ways. The **Style** preferences — Running Style, Track Distance, and Track Surface "for Skills" — now strictly restrict which skills the auto-strategy considers, applied consistently across every spending strategy. Separate category-exclusion toggles drop whole groups from the plan: negative, green, red, unique, and the new **Skip Double-O (Circle) Skills** (`excludeDoubleCircleSkills`, default off), which buys only the single-circle version of an upgrade and skips the double-circle one. Skills the user adds to the plan by hand are always bought regardless of these exclusions.

### 4.3 Scenario Hooks

Each scenario can override these hooks to inject custom logic at specific points in the turn:

| Hook | When Called | Example Usage |
|------|-----------|---------------|
| `onBeforeMainScreenUpdate()` | Before date detection | Trackblazer: check if shop visit is needed |
| `onAfterTurnStartUpdates()` | After parallel OCR reads | Additional post-update logic |
| `onMainScreenEntry()` | Before decision-making | Trackblazer: use training items |
| `onScheduledRacePrepScreen()` | On the Race Prep screen before a scheduled or mandatory race | Trackblazer: use race items (hammers, glow sticks) |
| `handleRaceEventFallback()` | When a race attempt fails (e.g. consecutive race limit) | Trackblazer: back out and train instead (non-mandatory races only) |
| `resetDailyFlags()` | When date changes | Reset scenario-specific per-turn flags |

---

## 5. Decision Engine

The `decideNextAction()` method determines what the bot should do this turn. It follows a strict **priority waterfall** — the first matching condition wins:

```mermaid
flowchart TD
    Start["decideNextAction()"] --> A{"Mandatory Race?"}
    A -->|Yes| RACE["→ RACE"]
    A -->|No| B{"Racing popup\nencountered?"}
    B -->|Yes| RACE
    B -->|No| DT{"Pinned dating /\nrecreation turn?"}
    DT -->|Yes| DATE["→ DATE"]
    DT -->|No| SR{"Scheduled\nRace?"}
    SR -->|Yes| RACE
    SR -->|No| C{"Force Racing\nenabled?"}
    C -->|Yes| RACE
    C -->|No| D{"Maiden race\nnot completed?"}
    D -->|Yes| RACE
    D -->|No| F{"Fan or Trophy\nrequirement active?"}
    F -->|Yes| RACE
    F -->|No| E{"Pre-Summer prep?\n(June Late, Classic/Senior)"}
    E -->|Yes| PreSummer{"Energy < 70%?"}
    PreSummer -->|Yes| REST["→ REST"]
    PreSummer -->|No| MoodCheck{"Mood < Great?"}
    MoodCheck -->|Yes| RECOVER["→ RECOVER_MOOD"]
    MoodCheck -->|No| WIT["→ TRAIN (forced Wit)"]
    E -->|No| G{"Injury detected?\n(skipped in Finale)"}
    G -->|Yes| NONE["→ NONE\n(injury handled internally)"]
    G -->|No| H{"Mood recovery\nneeded?"}
    H -->|Yes| RECOVER
    H -->|No| I{"Eligible for\nextra racing?"}
    I -->|Yes| RACE
    I -->|No| TRAIN["→ TRAIN"]
```

**Priority explanations:**

1. **Mandatory Race:** If the game shows a mandatory race ribbon (career-goal or race-day), the bot must race. No choice here.
2. **Racing popup:** If a previous race selection triggered a popup that wasn't fully resolved, continue with racing.
3. **Dating/Recreation outing (`DATE`):** If the dating schedule is active and today is a pinned (or catch-up) recreation turn with no mandatory race, the bot spends the turn on the outing. This **outranks** scheduled agenda races and Smart Race Solver races. See [Section 12](#12-support-card-dating-schedule).
4. **Scheduled Race:** If the game shows a scheduled (in-game agenda) race label, the bot races.
5. **Force Racing:** User setting that bypasses all other logic and forces racing every turn.
6. **Maiden Race:** The first race of the campaign must be completed before regular training resumes.
7. **Fan/Trophy Requirements:** If the game requires a minimum fan count, trophy count, or goal race points, the bot prioritizes racing to meet it. This **outranks** pre-summer prep so a mandatory career goal (e.g. a "win a G1" trophy) is never skipped in favor of a summer-prep training.
8. **Pre-Summer Prep (June Late):** On the last turn before Summer training, the bot ensures energy is high (≥70%) and mood is Great. If energy is low, it rests. If mood is low, it recovers mood. If both are fine, it trains Wit (which recovers some energy in preparation for Summer Training).[^1]

[^1]: Wit is chosen as the "throwaway" training because it recovers some energy, helping the trainee enter Summer Training in better condition.
9. **Injury Check:** If an injury is detected, the bot handles it (usually by resting). This check is **skipped during Finale turns** since those races are mandatory.
10. **Mood Recovery:** If mood has dropped to Normal or below, the bot recovers before training (bad mood penalizes training gains).
11. **Extra Racing:** If the bot is eligible for extra races (based on farming fans, racing plan, or smart racing logic), it races.
12. **Default: Train.** If nothing else applies, the bot trains.

> [!NOTE]
> **Trackblazer override:** Before calling the base decision logic, Trackblazer's `decideNextAction()` first checks for **Irregular Training** — evaluating whether a high-value training opportunity exists that's worth skipping a race for. See [Section 11.6](#116-irregular-training) for details.

---

## 6. Training System

The training system analyzes all 5 training options (Speed, Stamina, Power, Guts, Wit), scores them, and selects the best one.

### 6.1 Training Analysis Pipeline

When `analyzeTrainings()` is called:

1. **Iterate all 5 stats:** For each stat, the bot clicks the corresponding training tab button.
2. **Stat gain detection per training:**
   - Main stat gain and sub-stat gains (detected via template matching or optionally **YOLO** — see below)
   - Failure chance percentage
   - Relationship bar colors (blue, green, orange) for support card characters present
   - Rainbow count (number of rainbow indicators)
   - Skill hints available
3. **Results are cached** for the current turn in `cachedAnalysisResults` to avoid re-reading if the training screen is visited multiple times.
4. **Filtering:** Trainings exceeding the maximum failure chance threshold (default 20%) are excluded, unless risky training mode or Good-Luck Charm overrides are active.

> [!NOTE]
> **First-failure-chance OCR retry.** If the very first failure-chance read on a Training screen entry comes back unparseable, `recoverAndRetryFailureChance()` clicks back out and re-enters the Training screen once before giving up. This catches the common case where the failure-chance number hasn't fully rendered yet on the first frame the bot captures.

**YOLO Stat Detection:** When `enableYoloStatDetection` is enabled, stat gain digits are detected using a **YOLOv8 nano** model (`best.onnx`) instead of template matching. The model is trained to detect 11 classes (digits 0–9 and the '+' symbol) in small 130x50 pixel crop regions for each stat. It runs via ONNX Runtime with a confidence threshold of 0.8 and IoU threshold of 0.45 for NMS. The `YoloDetector` is loaded once as a singleton and kept in memory. Both detection methods coexist — the setting controls which one is used at runtime. The YOLO training pipeline and model export tools live in the [yolo/](yolo/) directory.

### 6.2 Scoring Algorithm

Each training option receives a weighted score from `calculateRawTrainingScore()`:

$$\text{Score} = \bigl(\text{StatEfficiency} \times w_{\text{stat}} + \text{Relationship} \times w_{\text{rel}} + \text{Misc} \times w_{\text{misc}}\bigr) \times \text{RainbowMultiplier}$$

| Component | Weight (with relationships) | Weight (without) | What It Measures |
|-----------|---------------------------|-------------------|-----------------|
| Stat Efficiency | 60% | 70% | How much the stat gain moves toward the target for the trainee's distance |
| Relationship | 10% | 0% | Support card relationship bar progress (blue = 2.5, green = 1.0, orange = 0.0) |
| Misc | 30% | 30% | Mood gain, bond progress, skill hints, and other bonuses |

**Rainbow Multiplier:**
- If rainbow training bonus is enabled: **2.0x**
- If rainbow training bonus is disabled but rainbows are present: **1.5x**
- No rainbows: **1.0x**

Rainbow training is heavily favored because it improves overall stat ratio balance. Applied only from Classic Year onward.

**Anticipatory Rainbow Multiplier (`enablePrioritizeNearMaxFriendship`, default on):** From Classic Year onward, when a training has **no real rainbows** but does have green/blue friendship bars sitting near the rainbow threshold, the bot applies a smaller anticipatory bonus to reward the "almost-rainbow" turn. Each qualifying bar contributes `fillPercent / 100` to a sum, then the multiplier is `1.0 + min(0.6, 0.2 * sum)` — capped at **1.6x** so anticipation can never out-rank an actual rainbow training (which sits at 1.5x / 2.0x). Only green/blue bars above 10% fill count. The intent is to lean toward trainings that are one bond tick away from becoming real rainbows without overruling a stat that already is one.

**Training Level Weighting (`enableTrainingLevelWeighting`, default on):** When enabled, each option's main-stat score is amplified by a multiplier derived from the **OCR-detected training facility level** (1–5). Only the user's **top three priority stats** receive any boost, and only training levels ≥ 2 matter. At Lvl 5 the multipliers are roughly rank 1 = 1.75x, rank 2 = 1.25x, rank 3 = 1.10x. The fade keeps the boost concentrated on the trainee's top priority while still rewarding investment in their secondary. Training levels are OCR'd from each facility tab, anchored against the Energy label location which is cached on first use.

> [!IMPORTANT]
> **Stat Cap Awareness:** If a stat is at or above the effective cap (absolute cap - 100 buffer), training for that stat scores **0** and is skipped. The one exception is a **one-time rainbow allowance** — a stat can be trained past the buffer if it's a rainbow training and that stat hasn't used this allowance yet.

### 6.3 Special Training Modes

<details>
<summary><strong>Risky Training</strong></summary>

When enabled, the bot will accept trainings with higher failure chances if the stat gain is large enough:
- **Minimum stat gain:** Configurable (default 20)
- **Maximum failure chance:** Configurable (default 30%)

This overrides the normal failure chance filter for trainings that meet both thresholds.
</details>

<details>
<summary><strong>Rainbow Training Bonus</strong></summary>

When enabled, rainbow trainings receive a 2.0x score multiplier instead of 1.5x. This makes the bot more aggressively pursue rainbow training opportunities, which provide balanced stat gains across multiple categories.
</details>

<details>
<summary><strong>Train Wit During Finale</strong></summary>

During Finale turns (73–75), if the trainee's energy is too low for optimal training, the bot normally rests. With this setting enabled, it **trains Wit instead of resting**, since:
- Energy recovery is less valuable when only 1–3 turns remain
- Wit training typically has low failure chance
- On turn 75 (the final turn), resting is completely pointless, so Wit is always forced
</details>

<details>
<summary><strong>Skill Hint Prioritization</strong></summary>

When enabled, the bot adds bonus weight to trainings that offer skill hints, making it more likely to choose trainings where support cards are offering learnable skills.

Before committing to a hint, the bot checks that the hinted training is actually safe to take. When it spots a skill-hint icon it maps the icon to the nearest training button to learn which stat it belongs to, navigates to that facility, and reads its failure chance. The hint is only tapped if that failure chance is within the active threshold — or if the check is bypassed because it is a Finals turn or a Good-Luck Charm is active. Otherwise the bot falls back to normal training analysis and picks a safer training instead of tapping the hint blindly.
</details>

<details>
<summary><strong>End-of-Year Milestone Stat Targets</strong></summary>

The bot supports configurable per-year stat targets (End of Junior / End of Classic Year Milestones). When enabled, training selection biases toward stats that are still short of their milestone target for the current year, helping the trainee hit each year's stat goals before the next phase.
</details>

<details>
<summary><strong>Disable Per-Distance Stat Targets</strong></summary>

When `disableStatTargets` is enabled, the per-distance stat targets that normally drive the Stat Efficiency component are ignored — every stat is treated as if its target equals the scenario stat cap. This keeps every stat's ratio multiplier in the "encourage training" band rather than letting it taper off once the per-distance target is reached, which is useful for runs where the user wants to push every stat as high as possible regardless of the trainee's distance preference.
</details>

<details>
<summary><strong>Training Failure Fallbacks</strong></summary>

When every training option is filtered out by the failure-chance or stat-cap rules, the bot follows a configurable fallback chain (e.g. rest, recover mood, or force Wit) instead of stalling on the turn. The fallback decision also respects negative statuses — for instance, with active negative conditions the forced fallback is Wit rather than Speed to avoid stat reductions from conditions like Slow Metabolism.
</details>

<details>
<summary><strong>Per-Context Stat Priorities</strong></summary>

The Training Settings page exposes three stat-priority lists instead of one:

- **Training stat priority** — used by the per-turn training scorer (the existing behavior).
- **Event choice stat priority** — used by [Section 8.3](#83-default-scoring) when scoring training-event option text. Falls back to the training list if left empty.
- **Summer training stat priority** — used during Summer turns (37–40 and 61–64), when the trainee is barred from racing and the optimal stat to push is often different from the rest-of-year priority. Falls back to the training list if left empty.

This lets the user, for example, push Speed during the year but lean into Stamina or Wit during Summer Training without having to flip the global priority.
</details>

### 6.4 Training Configuration Summary

| Setting | Default | Effect |
|---------|---------|--------|
| Stat Prioritization | Speed, Stamina, Power, Guts, Wit | Order determines scoring weight for stat gains |
| Training Blacklist | (empty) | Stats in this list are never selected |
| Max Failure Chance | 20% | Trainings above this are filtered out |
| Disable on Maxed Stat | true | Skip training for stats at/above buffer |
| Rainbow Training Bonus | false | 2.0x multiplier for rainbow trainings |
| Prioritize Near-Max Friendship | true | Anticipatory rainbow multiplier (up to 1.6x) in Year 2+ for green/blue bars near the rainbow threshold |
| Train Wit During Finale | false | Wit training instead of resting during finale |
| Risky Training | false | Accept higher failure for larger gains |
| Training Level Weighting | true | Amplify top-3 priority stats by OCR-detected facility level (1-5) |
| Disable Per-Distance Stat Targets | false | Treat every stat's target as the scenario stat cap |

---

## 7. Racing System

The racing system handles race detection, selection, execution, and result processing.

### 7.1 Race Types

| Type | Detection | When |
|------|-----------|------|
| **Mandatory** | `IconRaceDayRibbon` or `IconGoalRibbon` | Game-forced races (Debut, Finale, goal races) |
| **Scheduled** | `LabelScheduledRace` | Races from the user's in-game agenda |
| **Extra** | Eligibility check | Fan farming, racing plan, or smart racing |
| **Maiden** | First race flag | Must be completed once before regular training |

### 7.2 Extra Race Eligibility

The bot determines if extra races should be run via `checkEligibilityToStartExtraRacingProcess()`:

- **Force Racing:** Always race if the setting is enabled.
- **Fan/Trophy Requirements:** Race to meet minimum thresholds shown on screen.
- **Racing Plan:** User-defined schedule of specific races to enter on specific turns.
- **In-Game Race Agenda:** Follows the agenda set within the game itself.
- **Fan Farming:** Enters races based on a configurable interval (`daysToRunExtraRaces`).
- **Smart Racing / Look-Ahead:** Checks upcoming turns for higher-quality races and may defer racing to a better opportunity.
- **Smart Race Solver:** From Classic year onward, the optional [Smart Race Solver](#13-smart-race-solver) can take over extra-race scheduling — see Section 13. When it's enabled with `enableFarmingFans` on and `enableForceRacing` off, the solver decides which turns are race turns and which races are picked, and the legacy fan-farming and look-ahead heuristics step aside. When the solver picks `Train` for a turn, the **legacy extra-race fallback is suppressed entirely** — the bot will not enter a race that the solver did not plan, even if the older fan-farming heuristic would have triggered.

> [!IMPORTANT]
> **Trackblazer** bypasses smart racing logic entirely and races as aggressively as possible, only stopping for summer, finals, or when the consecutive race limit is reached.

### 7.3 Race Selection

When the bot decides to race:

1. **Open the race list** and scan available races.
2. **Database lookup:** Each detected race name is matched against an internal race database keyed by turn number. The database contains grade, fan reward, surface, and distance information.
3. **Grade priority:** G1 > G2 > G3 > OP > Pre-OP. Higher-grade races are always preferred.
4. **Filtering:** Races can be filtered by minimum fan threshold, preferred terrain, preferred grades, and preferred distances.
5. **Selection:** The highest-priority race that passes all filters is selected.

### 7.4 Race Execution

Once a race is selected:

1. **Strategy Selection:** The bot selects a running strategy (Front Runner, Stalker, Betweener, or Chaser) based on the trainee's aptitudes. If **per-distance strategies** are enabled in Racing Settings, the bot resolves the strategy separately per distance bucket (Sprint, Mile, Medium, Long) against the currently detected `lastRaceDistance`, overriding the global strategy for that race. For **mandatory races** (which skip the normal race-list selection flow), the bot reads the race distance directly off the Race Prep screen so per-distance strategies still apply on Debut, goal, and Finale turns.
2. **Skip or Manual:** If the "skip" button is available, the bot skips the race animation. Otherwise, it watches and fast-forwards.
3. **Retries:** If a race is lost and retries are enabled, the bot can retry the race (free retry available once per campaign if enabled). **Mandatory races** (Debut, goal, and Finale races) additionally retry until the trainee places 1st whenever the in-game "Try Again" button is available, so a forced race is re-run for the win rather than accepted at a lower placement.
4. **Complete Career on Failure:** If a mandatory race is lost and this setting is enabled, the bot continues the campaign anyway rather than stopping.

> [!CAUTION]
> Losing a mandatory race without `enableCompleteCareerOnFailure` will **stop the bot entirely**. If you want fully unattended runs, make sure this setting is enabled.

---

## 8. Training Events

Training events are popup screens that appear after training or racing, offering the player a choice between 2+ reward options.

### 8.1 Event Detection

1. The bot detects the training event screen via template matching (`IconTrainingEventHorseshoe`).
2. **OCR reads** the event title and the character or support card name.
3. **Fuzzy string matching** (Jaro-Winkler algorithm) compares the detected text against the event database to identify which event this is and what each option rewards.

### 8.2 Override System

The bot checks for overrides in this priority order:

| Priority | Override Type | Description |
|----------|--------------|-------------|
| 1 | **Special Event** | Hardcoded overrides for game-critical events (New Year's, Shrine Visit, etc.) |
| 2 | **Character Event** | User-configured choice for a specific character's events |
| 3 | **Support Event** | User-configured choice for a specific support card's events |
| 4 | **Scenario Event** | User-configured choice for scenario-specific events |
| 5 | **Default Scoring** | Weighted algorithm (see below) |

If any override matches, its configured option is selected immediately without scoring.

### 8.3 Default Scoring

When no override applies, each option receives a weight score based on its rewards:

| Reward Type | Weight | Notes |
|-------------|--------|-------|
| "Can start dating" | +1000 | Extremely high priority — unlocks dating events |
| "Event chain ended" | -300 | Penalty — ending an event chain loses future rewards |
| "(Random)" | -10 | Small penalty for uncertain outcomes |
| "Randomly" | +50 | Mild bonus for partially random outcomes |
| Energy gain | value × multiplier | Multiplier scales with current energy[^2] (4x at <30%, 3x at <50%, 2x at <70%, 0x at ≥90%). If "Prioritize Energy" is enabled, multiplier is 100x |
| Mood gain | 80–150 | Higher weight when mood is lower (150 at Awful, 0 at Great). Mood loss: -150 |
| Bond gain | +20 | Bond loss: -20 |
| Skill hint | +25 | Learning a new skill |
| Positive status | +25 | Gaining a beneficial condition |
| Negative status | -25 | Gaining a harmful condition |
| Stat gain (priority stat) | value + 10–50 bonus | Bonus based on stat priority rank (1st: +50, 2nd: +40, 3rd: +30, 4th: +20, 5th: +10) |
| Stat gain (other) | raw value | No priority bonus |
| Skill points | raw value | Direct skill point gains |

[^2]: The energy multiplier is intentionally aggressive — at low energy, even small energy gains receive high scores because training at low energy carries significant failure risk.

The option with the **highest total weight** is selected.

> [!TIP]
> You can override the bot's event choices for specific characters, support cards, or scenario events in the **Training Event Settings** page. Overrides take priority over the scoring algorithm, letting you force a specific option for events you know are better than what the bot would calculate.

---

## 9. Scenario: URA Finale

URA Finale is the **simplest scenario** — it uses almost entirely the base `Campaign` logic with only one minor override:

- **`openFansDialog()`:** Uses a different button location (`ButtonHomeFansInfo` in the top half of the screen) to open the fans information panel. This is the only code difference from the base campaign.

Everything else — decision logic, training, racing, events, and finale handling — uses the standard base implementation described in sections 3–8.

> [!TIP]
> If you're new to the bot, URA Finale is the best scenario to start with since its behavior is entirely described by the shared systems in sections 3–8 with no scenario-specific complexity.

**Finale behavior (turns 73–75):**
- All 3 finale races (Qualifier, Semi-Final, Finals) are **mandatory**.
- **Injury checks are skipped** during the finale since the races must be run regardless.
- **Consecutive race warnings** are automatically confirmed.
- If `trainWitDuringFinale` is enabled, the bot trains Wit instead of resting between finale races.
- If `enableStopBeforeFinals` is enabled, the bot stops at turn 72 so the user can manually handle skill purchases or other preparations.
- If the `preFinals` skill plan is enabled, the bot automatically purchases skills on turn 72 before entering the finale.

---

## 10. Scenario: Unity Cup

Unity Cup adds a unique opponent selection and race system on top of the base campaign.

### 10.1 Tutorial Handling

The first time a Training Event screen appears, the bot checks for the Unity Cup tutorial header (`IconUnityCupTutorialHeader`). If detected, it selects the second option to close it and sets a flag to skip this check on subsequent turns.

### 10.2 Opponent Selection

When a Unity Cup race is triggered, the bot enters an opponent selection screen with 3 opponents to choose from:

```mermaid
stateDiagram-v2
    [*] --> TapOpponent1
    TapOpponent1 --> AnalyzePredictions1: Confirmation dialog opens
    AnalyzePredictions1 --> RaceConfirmed: ≥3 double circles ✓
    AnalyzePredictions1 --> TapOpponent2: < 3 double circles ✗

    TapOpponent2 --> AnalyzePredictions2: Confirmation dialog opens
    AnalyzePredictions2 --> RaceConfirmed: ≥3 double circles ✓
    AnalyzePredictions2 --> TapOpponent3: < 3 double circles ✗

    TapOpponent3 --> AnalyzePredictions3: Confirmation dialog opens
    AnalyzePredictions3 --> RaceConfirmed: ≥3 double circles ✓
    AnalyzePredictions3 --> ForceFallback: < 3 double circles ✗

    ForceFallback --> RaceConfirmed: Force select Opponent 2
    RaceConfirmed --> [*]
```

**How it works:**

1. The bot detects 3 opponent positions via `LabelUnityCupOpponentSelectionLaurel`.
2. Starting with Opponent 1, it taps the opponent and then the "Select Opponent" button.
3. A confirmation dialog opens showing race predictions. The bot counts **double circle icons** (`IconDoubleCircle`) in the middle region of the screen.
4. If **3 or more double circles** are found → the matchup is favorable. The bot confirms the selection.
5. If fewer than 3 → the bot closes the dialog and tries the next opponent.
6. **Fallback:** If all 3 opponents fail the threshold, the bot **forces selection of Opponent 2** as a compromise.

> [!CAUTION]
> The fallback always picks Opponent 2 regardless of prediction quality. If all opponents are unfavorable, the race may be lost.

### 10.3 Race Execution

After selecting an opponent:

- The bot checks if the "See All Race Results" button is **locked** (via `checkDisabled()`).
  - **Locked:** The bot clicks "Watch Main Race" and runs the race manually with retries.
  - **Unlocked:** The bot clicks the skip button to instantly see results.
- The race sequence ends when `IconUnityCupRaceEndLogo` is detected, at which point the bot clicks "Next" to return to the main screen.
- **Finals race** (`ButtonUnityCupRaceFinal`): When racing Team Zenith in the finals, the bot sets `bIsFinals = true` which auto-confirms the opponent dialog without prediction analysis.

### 10.4 Training Scoring

Unity Cup uses a modified training scoring mode during Junior and Classic years that factors in the **Spirit Gauge** mechanic:

- **Spirit Burst Bonus:** +800 base + 400 per additional gauge ready to burst
- **Facility Preference:** +200 for Speed/Wit facilities; conditional for Stamina/Power/Guts
- **Gauge Fill Bonus:** +300 base + 100 per fillable gauge, with +200 bonus in the early game
- **Relationship:** 1.5x scaled relationship score

From Senior Year onward, Unity Cup switches to the standard stat efficiency scoring described in [Section 6.2](#62-scoring-algorithm).

---

## 11. Scenario: Trackblazer

Trackblazer is the **most complex scenario**, adding a shop system, item management, consecutive race tracking, irregular training evaluation, and custom race selection.

### 11.1 Overview and Flow Differences

Trackblazer overrides the decision engine to add several scenario-specific checks before falling through to the base logic:

```mermaid
flowchart TD
    Start["Trackblazer\ndecideNextAction()"] --> Summer{"Is it\nSummer?"}
    Summer -->|Yes| TRAIN["→ TRAIN\n(Summer training)"]
    Summer -->|No| Finale{"Finale turns\n73-75?"}
    Finale -->|Yes| TRAIN2["→ TRAIN\n(Finale training)"]
    Finale -->|No| EnergyGuard{"Energy ≤ 10% AND\n3+ consecutive races?"}
    EnergyGuard -->|Yes| REST["→ REST\n(avoid -30 stat penalty)"]
    EnergyGuard -->|No| Irregular{"Irregular Training\nenabled + not checked?"}
    Irregular -->|Yes| EvalTraining["Open training screen\nAnalyze all 5 trainings"]
    EvalTraining --> ValidFound{"High-value training\nfound?"}
    ValidFound -->|Yes| TRAIN3["→ TRAIN\n(irregular training)"]
    ValidFound -->|No| BackOut["Close training screen\nMark as checked"]
    BackOut --> BaseDecision["super.decideNextAction()\n(base priority waterfall)"]
    Irregular -->|No| BaseDecision
```

> [!IMPORTANT]
> **Key difference from base Campaign:** During Finale, Trackblazer **trains** instead of racing. The 3 finale races are still mandatory, but between them the bot prioritizes training over rest (unlike URA Finale which follows the standard logic).

**Race fallback behavior:** If a non-mandatory race attempt fails (e.g. the consecutive race limit is reached after selecting a race), Trackblazer backs out of the race dialogs and falls back to training for the turn instead of erroring out. Mandatory races are not affected — those always proceed normally.

### 11.2 Shop System

The Trackblazer shop allows purchasing items with coins earned from races. The bot visits the shop periodically and buys items according to a priority list.

#### Shop Visit Triggers

- **After qualifying races:** When a race of the configured grade (default: G1, G2, G3) is completed and the shop check frequency counter is reached.
- **Shop check frequency:** Configurable (default 3). The bot visits the shop every N turns after the first qualifying race, not after every single race.
- **First-time check:** The bot performs an initial shop check the first time it has the opportunity.

#### Buying Priority List

Items are purchased in strict priority order. The bot buys the highest-priority affordable item first, then moves down the list:

| Tier | Items | Purpose |
|------|-------|---------|
| **1. Critical** | Good-Luck Charm, Master Cleat Hammer, Artisan Cleat Hammer, Glow Sticks, Royal Kale Juice, Grilled Carrots, Rich Hand Cream, Miracle Cure | Core race/training items + emergency heals |
| **2. Stats** | Speed/Stamina/Power/Guts/Wit Scrolls (+15), then Manuals (+7) | Direct stat boosts |
| **3. Energy + Mood** | Vita 65, Vita 40, Vita 20, Berry Sweet Cupcake, Plain Cupcake | Energy restoration + mood recovery |
| **4. Training Effects** | Empowering/Motivating Megaphone, Ankle Weights (top 3 stats), Coaching Megaphone, Reset Whistle | Training bonuses |
| **5. Bad Condition Heals** | Fluffy Pillow, Pocket Planner, Smart Scale, Aroma Diffuser, Practice Drills DVD | Heal negative statuses |
| **6. Training Facilities** | Training Applications (top 3 stats) | Facility level boosts |
| **7. Other Energy** | Energy Drink MAX, Energy Drink MAX EX | Additional energy items |
| **8. Good Conditions** | Pretty Mirror, Reporter's Binoculars, Master Practice Guide, Scholar's Hat | Positive status effects |

**Inventory limits:** Most items are capped at 5 copies. Condition-related items (good/bad) are typically capped at 1 (except Rich Hand Cream and Miracle Cure at 5).

> [!WARNING]
> **OCR coin reading:** The bot reads the shop coin count via OCR. If OCR reads 0 coins (likely an OCR error), the bot enters a "force purchase" mode where it attempts purchases anyway. This prevents a misread from blocking all shop activity for the rest of the run.

### 11.3 Item Usage System

**Items are only available from turn 13 onward** (after Pre-Debut). The item dialog is not accessible before that point. All item usage described below is gated on `date.day >= 13`.

The bot opens the Training Items dialog when **any** of these conditions are met:

| Trigger Condition | Why |
|-------------------|-----|
| First inventory sync not yet performed | Need to scan the full item list to populate the internal inventory cache |
| Energy ≤ threshold (default 40%) and energy items exist | Low energy hurts training and race performance |
| Mood ≤ Normal and energy < 70% and cupcakes exist | Low mood penalizes training gains |
| Bad condition active and heal items exist | Bad conditions block certain actions |
| Stat items (Scrolls/Manuals/Notepads) exist | Direct stat gains — always used when available |
| Megaphone exists, none currently active, and a training is selected | Training bonus multiplier |
| Ankle Weights exist for the selected training stat | Training stat bonus |
| Good-Luck Charm exists, not used today, failure chance ≥ 20%, and a training is selected | Prevent training failure |

If **none** of these conditions are met and the inventory has already been synced, the bot skips opening the dialog entirely to save time. Several extra short-circuits skip the open even when one of the bullets above looks satisfied:

- **Conserved energy item only:** If the only energy items in inventory are the lowest-tier copies reserved for emergency race recovery, the dialog is skipped — opening it would just use them and defeat the conservation rule.
- **Conserved megaphone or Charm only:** If the only training-effect items in inventory would be filtered out by the megaphone-priority logic or by the Charm low-failure / low-gain rules, the dialog is skipped.
- **No matching condition heal:** If no negative status is active, condition heals don't trigger the dialog even when they're in inventory.
- **Low main stat gain floor (Trackblazer):** Trackblazer also tracks a `trackblazerSkipBadMoodItemsBelowGain` (default 15) — when mood is low, the bot refuses to spend a Charm or run a Reset Whistle reshuffle if the selected training's main stat gain falls below this floor. The mood penalty would cap the gain enough that the item is conserved for a higher-gain turn instead.

Once the dialog is open, the bot scrolls through the full item list, performing **inventory sync** and **inline item usage** in a single pass. Each item encountered is evaluated against the rules below. If the cached inventory already accounts for every item of interest, the scan exits early.

> [!TIP]
> The single-pass design means the bot opens the Training Items dialog **at most once per turn** (plus once for race items if racing). After the first full scan, subsequent turns use the cached inventory to skip items that aren't needed, enabling early exit from the scroll loop.

#### Complete Item Reference

Below is every item in the Trackblazer shop, organized by category. For each item: what it does, when the bot uses it, and when it does not.

---

<details>
<summary><strong>Stats — Notepads, Manuals, and Scrolls</strong></summary>

| Item | Price | Effect |
|------|-------|--------|
| Speed/Stamina/Power/Guts/Wit **Notepad** | 10 coins | +3 to the respective stat |
| Speed/Stamina/Power/Guts/Wit **Manual** | 15 coins | +7 to the respective stat |
| Speed/Stamina/Power/Guts/Wit **Scroll** | 30 coins | +15 to the respective stat |

**When used:** Immediately on sight during the inventory scan pass, every turn. The bot clicks the "+" button up to **5 times per item** (consuming up to 5 copies in one pass). These are "quick-use" items — no conditional logic is needed.

**When NOT used:**
- The stat is already at its cap.
- Turn is before 13 (Pre-Debut).

**Shop priority:** Scrolls are purchased before Manuals. Notepads are **not** included in the default buy priority list — they are only purchased if the bot happens to have leftover coins after everything else. However, if the user already has Notepads in inventory, they will still be used.

</details>

<details>
<summary><strong>Energy — Vita 20, Vita 40, Vita 65</strong></summary>

| Item | Price | Effect |
|------|-------|--------|
| **Vita 20** | 35 coins | Energy +20 |
| **Vita 40** | 55 coins | Energy +40 |
| **Vita 65** | 75 coins | Energy +65 |

**When used:** Only when **all** of these conditions are true:
1. Energy is at or below the energy threshold (default 40%)
2. A **Good-Luck Charm is NOT being used this turn** (see [Charm interaction](#good-luck-charm--energy-item-interaction) below)
3. The item is part of the **optimal combination** chosen by the greedy energy algorithm
4. Using the item would not burn the **last copy of the lowest-tier energy item** reserved for emergency race recovery (unless force-override is active)

**The greedy energy algorithm (`isBestEnergyItemToUse()`):**
1. Collect all available energy items (from inventory + items not yet scanned in this pass).
2. **Reserve** `trackblazerEnergyItemReserve` copies (default 1) of the lowest-tier energy item in `energyItemConservationOrder` (Vita 20 → Vita 40 → Vita 65) so an emergency race recovery always has something to draw on.
3. Sort the remaining gains descending (65 → 40 → 20).
4. Greedily pick items whose cumulative gain stays within a **soft overshoot cap of 110%** — a small overshoot is allowed so that a larger combined gain (e.g. Vita 65 + Vita 40 = 105) is preferred over a strictly-under-100 combination (e.g. 65 + 20 = 85).
5. **Multiple items can be used in a single turn** — every item in the picked set will be consumed when encountered during the scan. If the current item is in the picked set → use it. Otherwise → skip it.

**Example:** Trainee has 35% energy with Vita 65, Vita 40, and Vita 20 available (plus extra copies).
- Vita 20 is reserved for emergency recovery.
- 35 + 65 = 100 → pick Vita 65.
- 100 + 40 = 140 → exceeds 110, skip.
- Result: Use Vita 65 only.

**Example:** Trainee has 30% energy with Vita 65, Vita 40, and Vita 20 available (plus extra copies).
- Vita 20 is reserved.
- 30 + 65 = 95 → pick Vita 65. Remaining headroom: 15 (up to 110).
- 95 + 40 = 135 → exceeds 110, skip Vita 40.
- Result: Use Vita 65 only. (Compare to the older single-item algorithm which would have picked the same item but with different reasoning.)

**Example:** Trainee has 20% energy with Vita 65, Vita 40 available (multiple copies of each).
- 20 + 65 = 85 → pick Vita 65.
- 85 + 40 = 125 → exceeds 110, skip.
- But a second Vita 40 could be evaluated next time: 20 + 40 + 40 = 100 fits. Depending on inventory order, the bot stacks items toward 100% rather than stopping after the first pick.

**When NOT used:**
- Energy is above the threshold (default 40%).
- A Good-Luck Charm is being used this turn (Charm sets failure to 0%, making energy irrelevant for training — using energy items would waste them since the energy cost is deducted after training).
- The item is the last copy of the reserved lowest-tier energy item (conserved for emergency race recovery).
- Using this item would exceed the soft overshoot cap (110%) given the already-picked items.

**Special Royal Kale Juice priority:** When energy ≤ 20%, the bot checks if Royal Kale Juice is available. If it is, all Vita items are skipped in favor of Kale Juice, since any Vita used first would be partially wasted by the Kale Juice's full restore.

</details>

<details>
<summary><strong>Energy — Royal Kale Juice</strong></summary>

| Item | Price | Effect |
|------|-------|--------|
| **Royal Kale Juice** | 70 coins | Energy set to 100%, Motivation -1 |

Royal Kale Juice is handled separately from Vita items because of its mood penalty.

**When used:** Only when **all** of these conditions are true:
1. A **Good-Luck Charm is NOT being used this turn**
2. The greedy energy algorithm selects it as the best choice
3. **AND** at least one of these "mood safety" conditions is met:
   - Energy is critically low (≤ 20%) — used as a **last resort** regardless of mood
   - Mood recovery items (Cupcakes) are available in inventory to offset the -1 mood
   - Mood is already Awful (can't get worse)

**When NOT used:**
- Energy is above 20% and no cupcakes are available and mood is not Awful (the -1 mood penalty has no safety net).
- A Good-Luck Charm is being used this turn.
- A Vita item is more efficient (e.g., at 60% energy, Vita 40 gives exactly what's needed without a mood penalty).

**Side effects:** After use, the trainee's mood is decremented by 1 level (e.g., Great → Good). The bot tracks this internally.

**Cupcake auto-pairing:** When Royal Kale Juice is queued during the inventory pass, the bot sets a `bKaleJuiceQueuedThisPass` flag and **re-runs the cupcake gate later in the same pass** with a fresh bitmap capture (`recheck = true`). The cupcake's normal "disabled in dialog" short-circuit and the "mood already high enough" guard are bypassed when this flag is set, so a Berry Sweet Cupcake or Plain Cupcake will be used in the same item dialog open to immediately offset the -1 mood penalty from Kale Juice. The flag is cleared once the cupcake fires (or once the pass ends), so it doesn't leak to subsequent turns.

</details>

<details>
<summary><strong>Energy — Energy Drink MAX and Energy Drink MAX EX</strong></summary>

| Item | Price | Effect |
|------|-------|--------|
| **Energy Drink MAX** | 30 coins | Maximum energy +4, Energy +5 |
| **Energy Drink MAX EX** | 50 coins | Maximum energy +8 |

**When used:** These are marked as **quick-use** items. They are used immediately on sight during the inventory scan, every turn they are available. Energy Drink MAX also adds +5 to current energy as a side effect.

**When NOT used:**
- Turn is before 13.

**Shop priority:** These are in Tier 7 (low priority) — purchased only after most other items. The max energy increase is a long-term investment that pays off over many turns.

</details>

<details>
<summary><strong>Mood — Berry Sweet Cupcake and Plain Cupcake</strong></summary>

| Item | Price | Effect |
|------|-------|--------|
| **Berry Sweet Cupcake** | 55 coins | Motivation +2 |
| **Plain Cupcake** | 30 coins | Motivation +1 |

**When used:** Only when **all** of these conditions are true:
1. Mood is Normal or below (≤ Normal), OR a Royal Kale Juice was queued earlier in the same pass (`bKaleJuiceQueuedThisPass`)
2. Energy is below 70% (if energy is high enough, the bot prefers to train without mood recovery), OR the Kale-Juice offset path is active
3. Cupcake stock exceeds `trackblazerCupcakeReserve` (default 1) — the bot will not burn the last reserved copy unless it is the cupcake that is being paired with a same-pass Kale Juice to offset its mood penalty

The first cupcake encountered during the scan is used. Berry Sweet Cupcake raises mood to Good; Plain Cupcake raises it to Normal (from the decremented state).

**When NOT used:**
- Mood is Good or Great.
- Energy is ≥ 70% (high energy means training will succeed well enough despite mood).

**Note — Interaction with Royal Kale Juice:** Cupcakes serve as a "safety net" for Kale Juice usage. The bot checks for cupcake availability before using Kale Juice at moderate energy levels (21–40%) because the Kale Juice would drop mood by 1. If cupcakes are available to compensate, Kale Juice is considered safe to use.

</details>

<details>
<summary><strong>Bond — Yummy Cat Food and Grilled Carrots</strong></summary>

| Item | Price | Effect |
|------|-------|--------|
| **Yummy Cat Food** | 10 coins | Yayoi Akikawa's bond +5 |
| **Grilled Carrots** | 40 coins | All support card bonds +5 |

**When used:** These are marked as **quick-use** items. Used immediately on sight during the inventory scan, every turn.

**When NOT used:**
- Bond is already maxed for all relevant characters.

**Shop priority:** Grilled Carrots is in Tier 1 (critical) because +5 bond to all support cards is extremely valuable early. Yummy Cat Food is not in the default priority list.

</details>

<details>
<summary><strong>Good Conditions — Pretty Mirror, Reporter's Binoculars, Master Practice Guide, Scholar's Hat</strong></summary>

| Item | Price | Effect |
|------|-------|--------|
| **Pretty Mirror** | 150 coins | Gain "Charming ○" status |
| **Reporter's Binoculars** | 150 coins | Gain "Hot Topic" status |
| **Master Practice Guide** | 150 coins | Gain "Practice Perfect ○" status |
| **Scholar's Hat** | 280 coins | Gain "Fast Learner" status |

**When used:** These are marked as **quick-use** items. Used immediately on sight during the inventory scan.

**When NOT used:**
- The status effect is already active.

**Shop priority:** Tier 8 (lowest priority). These are expensive and only purchased after all other categories are covered. The bot caps inventory at 1 copy each since each status effect can only be active once.

</details>

<details>
<summary><strong>Heal Bad Conditions — Fluffy Pillow, Pocket Planner, Rich Hand Cream, Smart Scale, Aroma Diffuser, Practice Drills DVD</strong></summary>

| Item | Price | Heals |
|------|-------|-------|
| **Fluffy Pillow** | 15 coins | Night Owl |
| **Pocket Planner** | 15 coins | Slacker |
| **Rich Hand Cream** | 15 coins | Skin Outbreak |
| **Smart Scale** | 15 coins | Slow Metabolism |
| **Aroma Diffuser** | 15 coins | Migraine |
| **Practice Drills DVD** | 15 coins | Practice Poor |

**When used:** During the inventory scan, if the trainee currently has **any negative status** and the corresponding heal item is encountered, it is used.

**When NOT used:**
- The trainee has no negative statuses.
- The specific negative status that this item heals is not currently active.

**Shop priority:** Rich Hand Cream is in Tier 1 (critical) because Skin Outbreak prevents the trainee from entering races, which is devastating in Trackblazer's race-heavy strategy. All other condition heals are in Tier 5. Inventory limit is 1 copy each (except Rich Hand Cream at 5 copies due to its critical nature).

</details>

<details>
<summary><strong>Heal Bad Conditions — Miracle Cure</strong></summary>

| Item | Price | Effect |
|------|-------|--------|
| **Miracle Cure** | 40 coins | Heal all negative status effects |

**When used:** Same conditions as individual heal items — used when the trainee has any negative status. This is a quick-use item so it's used on sight if any negative status is active.

**When NOT used:**
- The trainee has no negative statuses.

**Shop priority:** Tier 1 (critical). Inventory limit is 5 copies. The bot buys Miracle Cures as general-purpose insurance against bad conditions.

</details>

<details>
<summary><strong>Training Effects — Megaphones (Empowering, Motivating, Coaching)</strong></summary>

| Item | Price | Effect | Duration |
|------|-------|--------|----------|
| **Empowering Megaphone** | 70 coins | Training bonus +60% | 2 turns |
| **Motivating Megaphone** | 55 coins | Training bonus +40% | 3 turns |
| **Coaching Megaphone** | 40 coins | Training bonus +20% | 4 turns |

**When used:** Only when **all** of these conditions are true:
1. No megaphone is currently active (`megaphoneTurnCounter == 0`)
2. A training has been selected for this turn (`trainingSelected != null`)
3. No **better** megaphone is available in inventory

**Megaphone priority logic:** The bot always uses the **best available** megaphone, not just the first one encountered during scanning. When it encounters a megaphone:
- It checks if a higher-tier megaphone exists in inventory that hasn't been scanned yet or is known to be enabled.
- For Motivating Megaphone: skips if Empowering exists.
- For Coaching Megaphone: skips if Empowering or Motivating exists.
- Empowering is always used immediately since nothing is better.

**When NOT used:**
- A megaphone effect is already active (turns remaining > 0). The bot decrements the counter each turn after an action is taken.
- No training is selected this turn (e.g., the bot is racing or resting).
- A better eligible megaphone is available in inventory.
- The selected training's main stat gain is below the tier's per-tier stat threshold (`trackblazerSkipEmpoweringMegaphoneBelowGain` / `trackblazerSkipMotivatingMegaphoneBelowGain` / `trackblazerSkipCoachingMegaphoneBelowGain`, all default 0). When a tier is blocked by its threshold, the bot falls through to the next lower tier whose threshold is met. If no tier qualifies, no megaphone is used this turn.

**Duration tracking:** After use, the bot sets `megaphoneTurnCounter` to 2/3/4 depending on the megaphone type. This counter is decremented by 1 at the end of each turn where an action was taken — training, voluntary races, **and mandatory races** (the latter is handled explicitly in `Trackblazer.handleRaceEvents()` because the mandatory-race path returns before reaching `executeAction()`, which is where the per-turn decrement normally fires).

</details>

<details>
<summary><strong>Training Effects — Ankle Weights (Speed, Stamina, Power, Guts, Wit)</strong></summary>

| Item | Price | Effect |
|------|-------|--------|
| **[Stat] Ankle Weights** | 50 coins each | Training bonus +50% for that stat, Energy consumption +20% (one turn) |

**When used:** Only when **all** of these conditions are true:
1. A training has been selected for this turn
2. The Ankle Weights match the **selected training stat** (e.g., Speed Ankle Weights are only used when Speed training is selected)

**When NOT used:**
- No training is selected this turn.
- The Ankle Weights are for a different stat than the selected training.
- Wit Ankle Weights: technically exist in the shop but are **never purchased** by the default priority list (only Speed/Stamina/Power/Guts weights for the top 3 prioritized stats are bought).

> **Warning:** Ankle Weights increase energy consumption by 20% for that turn. The bot does not factor this into the energy threshold check — if the trainee is at low energy and Ankle Weights are used, the training may consume more energy than expected.

**Shop priority:** Tier 4. Only purchased for the top 3 stats in the user's stat prioritization order. For example, if stat priority is Speed > Power > Stamina > Guts > Wit, the bot buys Speed, Power, and Stamina Ankle Weights but not Guts or Wit.

</details>

<details>
<summary><strong>Training Effects — Good-Luck Charm</strong></summary>

| Item | Price | Effect |
|------|-------|--------|
| **Good-Luck Charm** | 40 coins | Training failure rate set to 0% (one turn) |

**When used:** Only when **all** of these conditions are true:
1. A training has been selected for this turn
2. The selected training's failure chance is **≥ 20%**
3. A Charm has **not already been used** this turn (`bUsedCharmToday == false`)

**When NOT used:**
- No training is selected this turn.
- The training's failure chance is < 20% (not risky enough to warrant a Charm).
- A Charm was already used this turn (only 1 per turn).

<h4 id="good-luck-charm--energy-item-interaction">Good-Luck Charm / Energy Item Interaction</h4>

> **Caution:** This is a critical interaction: **when a Good-Luck Charm is being used (or will be used) this turn, all energy items (Vita 20/40/65 and Royal Kale Juice) are skipped.**

**Why:** The Charm sets training failure to 0%, making the trainee's energy level irrelevant for training success. Energy is deducted *after* training completes, so restoring it beforehand provides no benefit. Using energy items would waste them.

The bot checks for this interaction before evaluating any energy item. It considers a Charm "being used" if:
- A Charm has already been queued this turn, OR
- A Charm is available in inventory AND the current training's failure chance is ≥ 20% (meaning a Charm *will* be queued when the scan reaches it)

**Shop priority:** Tier 1 (critical). This is the **highest priority** purchase in the shop because it enables the bot to safely train high-risk options that would otherwise be filtered out.

**Irregular Training interaction:** When evaluating irregular training, the bot checks if a Charm is available. If so, it passes `ignoreFailureChance = true` to the training analysis, allowing high-failure trainings to be considered as candidates.

</details>

<details>
<summary><strong>Training Effects — Reset Whistle</strong></summary>

| Item | Price | Effect |
|------|-------|--------|
| **Reset Whistle** | 20 coins | Shuffle support card distribution across training facilities |

**When used:** Only when **all** of these conditions are true:
1. Turn is ≥ 13
2. A Whistle has **not already been used** this turn (`bUsedWhistleToday == false`)
3. The training analysis found **no suitable training** (`trainingSelected == null`)
4. This is **not** an irregular training evaluation (whistles are blocked during irregular checks to prevent wasting them on opportunistic training)

**What happens after use:**
1. The bot confirms usage and closes the item dialog.
2. Support cards are reshuffled across the 5 training facilities.
3. The bot re-runs the full training analysis.
4. If `whistleForcesTraining` is enabled (default: true) and the re-analysis still finds no suitable training, the bot **forces the best available training** even if it doesn't meet normal thresholds — **unless** the forced stat was already explicitly rejected by analysis (e.g. high failure chance, low gain while a Charm is active) or is blacklisted. In that case, the bot refuses to force-train and falls back to mood or energy recovery instead.
    - **Charm-safety guard:** When the forced pick comes from the rejected pool, it is by definition either below the Charm minimum-gain floor or has a failure chance high enough that it would only have been viable with a Good-Luck Charm. If a Charm cannot fire on it (none in inventory, already used today, or the analyzer's charm gates would suppress it) **and** the failure chance is **≥ 50%**, the bot abandons the force-pick and falls through to the recovery branch instead of running a near-certain failure with no defensive item.
5. After the whistle, a second item usage pass runs in case the new training recommendation changes which items should be used (e.g., different Ankle Weights).

**When NOT used:**
- A suitable training was already found (the whistle is only for "rescuing" bad turns).
- A Whistle was already used this turn.
- This is an irregular training evaluation (the whistle is too valuable to use on a speculative check).
- Energy recovery is needed (`needsEnergyRecovery` is true) — the problem is low energy, not bad training options, so reshuffling won't help.

**Shop priority:** Tier 4 (training effects). Relatively cheap at 20 coins and very useful as a safety net.

</details>

<details>
<summary><strong>Training Facilities — Training Applications</strong></summary>

| Item | Price | Effect |
|------|-------|--------|
| **Speed Training Application** | 150 coins | Speed Training Level +1 |
| **Stamina Training Application** | 150 coins | Stamina Training Level +1 |
| **Power Training Application** | 150 coins | Power Training Level +1 |
| **Guts Training Application** | 150 coins | Guts Training Level +1 |
| **Wit Training Application** | 150 coins | Wit Training Level +1 |

**When used:** These are marked as **quick-use** items. Used immediately on sight during the inventory scan. Training level increases are permanent and improve all future training gains for that stat.

**When NOT used:**
- The facility is already at max level.

**Shop priority:** Tier 6. Only purchased for the top 3 stats in the user's stat prioritization order. At 150 coins each, they are expensive but provide a lasting benefit.

</details>

<details>
<summary><strong>Races — Master Cleat Hammer, Artisan Cleat Hammer, Glow Sticks</strong></summary>

| Item | Price | Effect |
|------|-------|--------|
| **Master Cleat Hammer** | 40 coins | Race bonus +35% (one turn) |
| **Artisan Cleat Hammer** | 25 coins | Race bonus +20% (one turn) |
| **Glow Sticks** | 15 coins | Race fan gain +50% (one turn) |

> **Important:** These items are **not** used during the normal training item pass. They have their own dedicated usage flow that triggers on the **Race Prep screen** before a race begins. This includes mandatory races (Finale turns 73–75) and scheduled races via the `onScheduledRacePrepScreen()` hook.

> [!NOTE]
> **Turn 65 conservation gate.** All Hammer and Glow Stick conservation thresholds described below **only kick in from turn 65 onward** — the turn right after Senior Year Summer training, which is the last window the in-game shop can refresh before the Finales. Before turn 65 the bot uses these items freely on every qualifying race, since hoarding them through the Classic year offers no benefit if the trainee can simply restock during Senior summer. The cutoff turn is hard-coded as `raceItemConservationStartDay = 65` and is surfaced in the Scenario Overrides banner alongside the threshold values themselves.

The conservation rules below also use a set of **user-tunable thresholds** exposed under Scenario Overrides → *Trackblazer Item Conservation*:

| Setting | Default | Meaning |
|---------|---------|---------|
| `trackblazerEnergyItemReserve` | 1 | Copies of the lowest-tier Vita item to keep in reserve for emergency race recovery |
| `trackblazerCupcakeReserve` | 1 | Copies of the cheapest cupcake to keep in reserve for the Kale-Juice mood offset |
| `trackblazerMasterHammerFinaleReserve` | 2 | Copies of Master Cleat Hammer required during Finale turns 73-74 before using one; the leftover is saved for turn 75 |
| `trackblazerArtisanHammerMinStockForG3` | 3 | Minimum Artisan Hammer stock required (from turn 65 on) before spending one on a G3 race |
| `trackblazerArtisanHammerMinStockForG2` | 2 | Minimum Artisan Hammer stock required (from turn 65 on) before spending one on a G2 race |
| `trackblazerGlowStickFinalReserve` | 1 | Copies of Glow Sticks to reserve heading into the Finales (consumed on turn 75 only) |
| `trackblazerGlowStickMinFans` | 20000 | Minimum fan reward of a G1 race for a Glow Stick to be spent on it |

**Master Cleat Hammer — when used:**
- The upcoming race is **G1 grade**.
- The item is available in inventory.
- **Finale conservation (from turn 65 on):** During turns 73 and 74 (Qualifier and Semi-Final), the bot only uses this item if it has at least `trackblazerMasterHammerFinaleReserve` (default 2) copies, saving the last one for turn 75 (Finals). On turn 75, all remaining copies are used freely.

**Artisan Cleat Hammer — when used:**
- The upcoming race is **G2 or G3 grade**.
- OR the race is G1 but no Master Cleat Hammer is available (fallback).
- The item is available in inventory.
- **Conservation (from turn 65 on):** For G3 races, requires stock ≥ `trackblazerArtisanHammerMinStockForG3` (default 3); for G2 races, requires stock ≥ `trackblazerArtisanHammerMinStockForG2` (default 2). The "2-copy" Finale rule for turns 73-74 still applies on top of these.

**Glow Sticks — when used:**
- The upcoming race is **G1 grade**.
- The race awards at least `trackblazerGlowStickMinFans` fans (default 20,000).
- The item is available in inventory.
- **Top-tier G1 exception (pre-Finale):** For G1 races awarding **≥ 30,000 fans** before turn 73, the bot will spend the **last** Glow Stick even when only 1 copy remains in inventory. The shop refreshes when the Finales begin, so there is another chance to buy more before turn 75.
- **Finale conservation (from turn 65 on):** During turns 73 and 74, the bot only uses Glow Sticks when stock exceeds `trackblazerGlowStickFinalReserve` (default 1), reserving the last one(s) for turn 75 (Finals). On turn 75, all remaining copies are used freely.

**When NONE of these are used:**
- The race is OP or Pre-OP grade (no items for low-grade races).
- A race item (`bUsedHammerToday`) has already been used this turn.
- Turn is before 13.
- No matching items are available in inventory.

**Shop priority:** Master Cleat Hammer is Tier 1 (critical). Artisan Cleat Hammer is also Tier 1. Glow Sticks is also Tier 1. All three are among the first items the bot purchases.

</details>

### 11.5 Consecutive Race System

Trackblazer tracks how many races the trainee has performed consecutively:

- **Counter:** Incremented after each race. Reset to 0 when the bot rests or recovers mood.
- **Warning at 3+:** After 3 consecutive races, the game shows a warning about potential stat penalties.
- **Energy guard at 3+:** When the counter is ≥ 3 and energy is critically low (0–1%), racing is **blocked** regardless of the configured limit to avoid compounding the -30 stat penalty at zero energy. This guard can be disabled with the `ignoreLowEnergyRacingBlock` setting for users who want the bot to keep racing even in that danger zone.
- **Grade filtering at 3+:** When the counter is ≥ 3, the bot only accepts **G1, G2, or G3** races. Lower-grade races (OP, Pre-OP) are skipped to avoid wasting the consecutive race penalty on low-value races.
- **Hard limit (default 5):** The bot stops racing entirely when the consecutive count reaches the configured limit (plus 1), unless it's the final turn.
- **OCR tracking:** The bot reads the consecutive race count from the warning dialog via OCR to stay synchronized with the game.

> [!IMPORTANT]
> The counter resets to 0 when the bot **rests** or **recovers mood**, not after training. If the bot trains between races, the consecutive count continues to climb.

### 11.6 Irregular Training

Irregular Training is an optional feature that evaluates whether a high-value training opportunity is worth skipping a race for:

1. **When checked:** On non-mandatory, non-scheduled race days during Classic and Senior years. **Skipped entirely** when energy is ≤ 10% with 3+ consecutive races — the bot rests instead to avoid the -30 stat penalty (see [11.1 flowchart](#111-overview-and-flow-differences)). A Good-Luck Charm in inventory does **not** override this guard, because the Charm can only fire after `analyzeTrainings()` produces a selection with measured failure chance ≥ 20% — it cannot protect a turn whose analysis is the thing at risk. The irregular evaluation itself is also skipped at `energy <= 0` for the same reason.
2. **Process:**
   - The bot opens the training screen and runs a full analysis of all 5 training options.
   - If a Good-Luck Charm is available, failure chance is ignored during evaluation.
   - The analysis uses an `isIrregularEvaluation = true` flag which applies a higher minimum stat gain threshold (configurable, default 30).
3. **If a valid training is found:** The bot closes the training screen, sets `bIsIrregularTraining = true`, and returns `TRAIN` — effectively "hijacking" a race turn for training.
4. **If no valid training is found:** The bot closes the training screen and falls through to the normal decision logic (which will likely result in racing).
5. **Once per turn:** The check is performed at most once per turn to prevent infinite loops.

> [!TIP]
> Irregular Training pairs well with the **Good-Luck Charm** — with a Charm in inventory, the bot can consider high-failure trainings during irregular evaluation that it would normally skip, unlocking more opportunities to "hijack" race turns.

### 11.7 Race Selection

Trackblazer uses a specialized race selection algorithm (`findSuitableRace()`, formerly `findSuitableTrackblazerRace()` — renamed and exposed via a generic `Campaign` hook so future scenarios can plug into the same flow) that scans the entire race list:

1. **Scan the full list:** Uses `ScrollList` to paginate through all available races across multiple pages.
2. **Identify candidates:** For each race, the bot looks for **double-star prediction icons** (`IconRaceListPredictionDoubleStar`) indicating favorable matchups.
3. **For each double-star race:**
   - Extract the race name via OCR
   - Look up the race in the database by turn number
   - Check for **Rival status** via template matching (`LabelRivalRacer`)
   - Filter by grade based on the current consecutive race count (see [11.5](#115-consecutive-race-system))
4. **Selection priority:**
   - **Smart Race Solver match first** — when the [Smart Race Solver](#13-smart-race-solver) has a planned race for this turn and the scan encounters it, the scan **short-circuits** and commits to that race without finishing the rest of the list. See [Section 13.6](#136-race-day-lifecycle--peek-mark-pending-commit).
   - **Rival races** (these offer bonus rewards)
   - Among non-rival candidates, races matching the configured **preferred distance** and/or **preferred surface** (Scenario Overrides UI) are preferred over ones that don't
   - Then by **grade:** G1 > G2 > G3 > OP > Pre-OP
5. **Second pass:** After selecting the winner, the bot scrolls back through the list to find the winner's current screen position and taps it.
6. **Fallback:** If `ScrollList` creation fails, the bot falls back to single-page detection.

> [!NOTE]
> The bot also tracks `lastRaceDistance` so that per-distance running strategies (see [Section 7.4](#74-race-execution)) can be resolved against the race that was actually selected.

---

## 12. Support-Card Dating Schedule

Some support cards (Team Sirius, Heirs to the Throne) unlock a chain of **recreation "dates"** that ends in a **Pure Passion** buff. The dating schedule lets the bot run those outings on user-pinned turns and, when a card times Pure Passion for a specific turn, **hold the final outing** until that turn so the buff lands on the summer / Senior training block. The logic lives in the base [Campaign](android/app/src/main/java/com/steve1316/uma_android_automation/bot/Campaign.kt) class, so it applies to every scenario, and the side-effect-free scheduling math is factored into [DatingSchedule.kt](android/app/src/main/java/com/steve1316/uma_android_automation/bot/DatingSchedule.kt) so it can be unit tested without the OCR / settings machinery.

### 12.1 Settings

The schedule is configured on the General Settings page. Regular outing turns are pinned on a `SeasonCalendar` picker — the same 72-cell calendar component the Smart Race Solver uses ([Section 13.5](#135-settings-ui--calendar-preview)).

| Setting | Meaning |
|---------|---------|
| `enableDatingSchedule` | Master toggle. When off, recreation stays opportunistic — done during rest / mood recovery as before. |
| `recreationTurns` | The set of 1-indexed career turns (1–72) pinned for regular outings. |
| `purePassionTurn` | The single turn that holds the **final** outing so Pure Passion activates then (default 60). A non-positive value means "no timed final" — every outing proceeds on its pinned turn (e.g. Team Sirius). |
| `recreationTotalOutings` | The chain length for the active card (Team Sirius 7, Heirs to the Throne 5). Used as a fallback until the in-game "X/Y" progress is read. |
| `enableRecreationCatchUp` | On by default. A pinned outing that got pre-empted (a race landed on its turn, or recreation was unavailable) is made up on the next available turn. |

### 12.2 Decision priority

`shouldDoRecreationToday()` inserts a `DATE` action into the [Section 5](#5-decision-engine) waterfall, just below the mandatory-race and racing-popup checks:

- A pinned (or catch-up) outing **outranks** scheduled in-game agenda races **and** Smart Race Solver races — the outing wins the turn.
- It does **not** outrank **mandatory career-goal races** (`IconRaceDayRibbon` / `IconGoalRibbon`), which can never be skipped.

### 12.3 Holding the final outing

Pure Passion must land on the right turn, so the bot must not finish the chain early. Before starting an outing it reads the in-game **"Group Event Progress X/Y"**, and if only the final outing remains and today is not the Pure Passion turn, `shouldHoldFinalOuting()` **backs out** of the Choose Recreation Partner dialog and the turn is spent on a normal action instead. On the Pure Passion turn the final outing is allowed and the buff triggers.

### 12.4 Catch-up and abandon

Two follow-up behaviors keep the schedule robust when reality drifts from the plan:

- **Catch-up** (`isBehindSchedule()`, gated by `enableRecreationCatchUp`) — if fewer outings have started than the number of pinned turns already due, the bot does an outing on the next available turn to get back on track.
- **Abandon** (`isScheduleAbandoned()`) — once the current turn passes `purePassionTurn` with the chain still unfinished, the schedule is dropped and recreation falls back to the opportunistic path. `isScheduleActive()` is the single gate that couples "enabled" with "not abandoned", and every scheduling call site checks it.

### 12.5 Group Event Progress OCR

The per-run counter (`recreationOutingsStarted`) drifts if the user plays a few turns manually or restarts the bot mid-run, so it is not trusted on its own. `getGroupEventProgress()` OCRs the "X/Y" text to the right of the **Group Event Progress** pill on the open partner dialog and re-syncs both the completed count and the chain total — the authoritative position that survives a restart or manual play. The crop is anchored to the pill's right edge rather than a fixed pixel offset, so it holds up across scrolling and resolution changes, but it may still need on-device calibration — the `GroupEventProgress` debug crop dumps the region for tuning.

### 12.6 Partner-dialog safety

Every back-out path in `handleRecreationDate()` — a held final outing, an unreadable dialog, or no selectable date label — routes through `cancelPartnerDialog()`, which cancels the dialog, waits for loading, and returns `false`. This closes a hang where a failed opportunistic recovery used to leave the Choose Recreation Partner dialog open and desync the bot for ~30 minutes. The `choose_recreation_partner` unhandled-dialog handler is the backstop that closes the dialog if it is ever found stranded.

---

## 13. Smart Race Solver

An optimization-based race scheduler that replaces the older Smart Racing Plan. Instead of asking the user to hand-pick races on a calendar, the solver takes the trainee's aptitudes, the bundled race database, and a set of **epithet** goals, and searches the entire 72-turn space for the highest-scoring race-vs-train schedule. The bot then drives the in-game race picker against that plan turn by turn.

### 13.1 When the solver runs

The solver is **opt-in** via the `enableSmartRaceSolver` setting on the Racing Settings page. It only takes over extra-race selection when:

- `enableFarmingFans` is on (the bot is allowed to enter extra races at all),
- `enableForceRacing` is off (the user hasn't asked the bot to race every turn unconditionally),
- and the campaign is past Junior year (the solver plans from Classic onward; Junior racing follows the existing maiden-race / mandatory-race path).

> [!NOTE]
> **Junior-year Pre-OP override.** Trackblazer normally rejects Pre-OP races outright during Junior year (their fan rewards are too low to justify the consecutive-race penalty). When the Smart Race Solver explicitly plans a Pre-OP race for a specific Junior turn, the Junior-year grade filter is **overridden** for that turn so the solver's plan can execute. Pre-OP races picked outside of the solver are still filtered out.

> [!NOTE]
> **Manual race locks are honored at runtime.** The Settings UI lets the user pin a specific race or `TRAIN_LOCK_SENTINEL` to a calendar cell. Earlier builds applied these locks to the preview render but not to the live solve loop — the bot would happily race a different race on the locked turn. The peek path now consults the locked schedule directly, so any cell the user pinned is respected during the actual run.

When all three hold, [Racing.kt](android/app/src/main/java/com/steve1316/uma_android_automation/bot/Racing.kt) calls `SmartRaceSolverIntegration.peekRaceKeyForTurn()` to ask "is there a race planned for the current turn, and if so, which one?" The answer steers both extra-racing eligibility and the race-list scan inside Trackblazer's `findSuitableRace()`.

> [!IMPORTANT]
> **Trackblazer integration.** Trackblazer's `decideNextAction()` consults `peekDecisionForTurn()` *before* the existing flowchart in [Section 11.1](#111-overview-and-flow-differences). When the solver has picked `Race`, the turn defers to the racing flow; when it picks `Train`, the legacy fan-farming heuristic is bypassed so the turn really is a training turn.

> [!NOTE]
> **Mandatory career races are auto-locked.** For every scenario other than Trackblazer, the solver loads each character's forced career races from [character_objectives.json](src/data/character_objectives.json) and locks them onto their turns via [MandatoryRaces.kt](android/app/src/main/java/com/steve1316/uma_android_automation/bot/solver/MandatoryRaces.kt). For a choice turn (e.g. Oaks vs. Derby) it picks the option that best fits the run's aptitudes. These locks override any manual lock on the same turn and cannot be edited or removed in the Settings UI — the solver must schedule them, and the rest of the plan is optimized around them.

### 13.2 Architecture

```mermaid
flowchart LR
    Settings["SmartRaceSolverSettings (TS)"] -->|SolverConfigSnapshot| Bridge["SmartRaceSolverModule (RN bridge)"]
    Bridge --> Integration["SmartRaceSolverIntegration"]
    Integration --> Solver["SmartRaceSolver.solve()"]
    Solver -->|exact| MILP["MilpSolver (ojAlgo)"]
    Solver -.->|fallback| Heuristic["Heuristic (beam search)"]
    Integration --> History["RaceHistory + EpithetTracker"]
    Bot["Racing.kt / Trackblazer.kt"] -->|peek / mark / commit| Integration
    Integration --> LogStream["LogStreamServer (Race History calendar)"]
```

The solver itself is a **pure function** — `solve(state) -> Schedule` — defined in [SmartRaceSolver.kt](android/app/src/main/java/com/steve1316/uma_android_automation/bot/solver/SmartRaceSolver.kt). State management (race history, parsed JSON caches, pending-race bookkeeping) lives in the integration object. The React Native side never re-implements the algorithm; it ships a `SolverConfigSnapshot` over the bridge, gets back a `SchedulePreview`, and renders the calendar.

| Layer | Files | Responsibility |
|-------|-------|----------------|
| Solver core | [SmartRaceSolver.kt](android/app/src/main/java/com/steve1316/uma_android_automation/bot/solver/SmartRaceSolver.kt), [MilpSolver.kt](android/app/src/main/java/com/steve1316/uma_android_automation/bot/solver/MilpSolver.kt), [Heuristic.kt](android/app/src/main/java/com/steve1316/uma_android_automation/bot/solver/Heuristic.kt), [ScoringFunctions.kt](android/app/src/main/java/com/steve1316/uma_android_automation/bot/solver/ScoringFunctions.kt) | Two interchangeable backends + the shared scoring formula. |
| Domain types | [Schedule.kt](android/app/src/main/java/com/steve1316/uma_android_automation/bot/solver/Schedule.kt), [SolverState.kt](android/app/src/main/java/com/steve1316/uma_android_automation/bot/solver/SolverState.kt), [Epithet.kt](android/app/src/main/java/com/steve1316/uma_android_automation/bot/solver/Epithet.kt), [RaceHistory.kt](android/app/src/main/java/com/steve1316/uma_android_automation/bot/solver/RaceHistory.kt) | `Decision`, `Schedule`, `Aptitudes`, `EpithetMatcher`, `RaceWin`. |
| Bot integration | [SmartRaceSolverIntegration.kt](android/app/src/main/java/com/steve1316/uma_android_automation/bot/solver/SmartRaceSolverIntegration.kt) | Race-history accumulation, lazy JSON parsing, peek / mark / commit lifecycle, calendar broadcasts. |
| RN bridge | [SmartRaceSolverModule.kt](android/app/src/main/java/com/steve1316/uma_android_automation/bot/solver/SmartRaceSolverModule.kt), [src/lib/solver/preview.ts](src/lib/solver/preview.ts) | `previewSchedule()` JSON-in / JSON-out call surface for the settings UI. |
| Settings UI | [src/pages/SmartRaceSolverSettings/](src/pages/SmartRaceSolverSettings/), [src/lib/solver/scoring.ts](src/lib/solver/scoring.ts), [src/lib/solver/constants.ts](src/lib/solver/constants.ts) | Calendar preview, character preset, target / forced epithet picker, weight sliders. |

### 13.3 Backends — MILP first, beam search as fallback

`SmartRaceSolver.solve(state)` tries the exact backend first and falls back to the heuristic only if the model is infeasible:

1. **MILP (default).** [MilpSolver.kt](android/app/src/main/java/com/steve1316/uma_android_automation/bot/solver/MilpSolver.kt) builds an `ExpressionsBasedModel` via **ojAlgo** that mirrors the reference Trackblazer site's GLPK formulation. Decision variables: `x[turn]` (race vs train), `r[turn][raceKey]` (specific race choice), `y[epithet]` (completion indicator), `z[turn]` (3rd-or-later consecutive-race indicator). Each [`EpithetMatcher`](android/app/src/main/java/com/steve1316/uma_android_automation/bot/solver/Epithet.kt) becomes one or two linear inequalities tying `y[e]` to the corresponding sum of `r`-variables and the history-derived constant.
2. **Beam-search heuristic (fallback).** When MILP returns an empty schedule (typically because forced epithets are mutually contradictory), [Heuristic.kt](android/app/src/main/java/com/steve1316/uma_android_automation/bot/solver/Heuristic.kt) takes over. It expands each beam into one child per legal decision (locked decision, available race, `Train`, `Rest`), scores each child, and prunes back to `DEFAULT_BEAM_WIDTH = 32`.

Both backends share the scoring function in [ScoringFunctions.kt](android/app/src/main/java/com/steve1316/uma_android_automation/bot/solver/ScoringFunctions.kt). The objective being maximized is:

$$\text{Score} = \sum_{\text{race}} v_\text{race} - \sum_{\text{race}} c_\text{race} + \sum_{\text{epithet}} r_\text{epithet} - \text{penalties}$$

where `v_race` is the per-race stat + skill-point reward (uplifted by `raceBonusPct`), `c_race` is the per-race cost expressed as a percentage of a G2 baseline (`raceCostPct`), `r_epithet` is the epithet's reward magnitude scaled by `epithetValue`, and penalties cover the 3rd-consecutive-race penalty (waived on Late-Dec turns 24 / 48 / 72 to match the reference solver) and a summer-racing penalty (turns 37–40 and 61–64).

> [!NOTE]
> With the default weights — a 50% race-bonus uplift on top of the base reward table and a per-race cost equal to the weighted G2 baseline — G2 / G3 races score zero and only get picked when an epithet, fan tiebreaker, or Late-Dec window pushes them positive. The default schedule is therefore train-heavy and races only when a goal pulls it to.

> [!NOTE]
> **Hard scheduling caps.** Two optional limits are enforced as hard constraints in both backends (the MILP adds linear constraints; the heuristic prunes the beam). **Maximum Extra Races** (`smartRaceSolverMaxRaces`, default 0 = no limit) caps how many optional races the whole schedule may contain — mandatory career races always run and do not count toward it. **Maximum Consecutive Races** (`smartRaceSolverMaxConsecutiveRaces`, default 3) forbids more than N races in a row across the 72-turn schedule, with the Late-Dec turn windows (24 / 48 / 72) exempt so a year-end chain is not penalized twice.

### 13.4 Epithets — the goal language

Epithets are the goals the solver is trying to satisfy. Each is a flat list of [`EpithetMatcher`](android/app/src/main/java/com/steve1316/uma_android_automation/bot/solver/Epithet.kt) entries combined with logical AND. Subtypes cover:

- **`WinRace(name, atClass?)`** — win the named race (optionally only in Junior / Classic / Senior).
- **`WinRaceTimes(name, times)`** — win the named race at least `times` times.
- **`WinAnyOf(names, count, atClass?)`** — win at least `count` distinct races from `names`.
- **`WinAtLeast(filter, count, atClass?)`** — win at least `count` races that satisfy a structured filter (terrain, grade, distance, country tokens, etc.).
- **`AnyOf` / `AllOf`** — boolean combinations over other matchers.

[`EpithetTracker`](android/app/src/main/java/com/steve1316/uma_android_automation/bot/solver/EpithetTracker.kt) classifies each epithet against the live state as one of `COMPLETED`, `IN_PROGRESS`, `DEAD`, or `UNTOUCHED`. **Dead** is the recovery hook: when a race is lost, the bot adds the epithet whose prerequisite was missed to `SolverState.deadEpithets`, calls `solve` again, and the heuristic re-plans around the dead branch.

The epithet corpus itself is generated by [scripts/scrapers/epithet_scraper.py](scripts/scrapers/epithet_scraper.py) into [src/data/epithets.json](src/data/epithets.json). Display labels for matcher conditions are pre-computed at build time by [scripts/precompute-epithet-labels.ts](scripts/precompute-epithet-labels.ts) so the runtime renderer never has to re-derive them.

### 13.5 Settings UI — calendar preview

The [Smart Race Solver Settings page](src/pages/SmartRaceSolverSettings/) lets the user:

- Pick a **character preset** (sourced from [src/data/characterPresets.json](src/data/characterPresets.json)). Selecting one seeds the six aptitude rows (Sprint / Mile / Medium / Long / Turf / Dirt) — the user can still hand-edit individual cells.
- Pick **target epithets** (the solver gets a bonus for completing them) and **forced epithets** (hard-locked — schedules that don't complete them are discarded). Empty-matcher epithets are flagged with a red dot and skipped by the solver.
- Set **per-turn manual locks** — pin a specific race or `TRAIN_LOCK_SENTINEL` onto a calendar cell to override the solver for that turn.
- Tune the **weights bundle** (`raceValue`, `epithetValue`, `statWeight`, `spWeight`, `hintWeight`, `consecutiveRacePenalty`, `summerPenalty`, `raceBonusPct`, `raceCostPct`, `aptitudeThreshold`, `includeOpAndPreOp`, `allowSummerRacing`).

After every meaningful change the page debounces a `SmartRaceSolverModule.previewSchedule()` call into Kotlin (see [src/lib/solver/preview.ts](src/lib/solver/preview.ts)) and renders the returned `SchedulePreview` onto a 72-cell calendar. Each cell shows the picked race name, grade badge, and epithet progression for that turn; a popover gives the full per-matcher condition labels and pending prerequisites. A floating Recalculate FAB and a stale-preview warning surface when the inputs have changed but the calendar hasn't refreshed yet.

> [!NOTE]
> **General vs. Aptitudes layout.** The settings page is split into a **General** section (scheduling constraints and toggles — Disable Schedule Re-Plan Upon Race Loss, Maximum Extra Races, Maximum Consecutive Races, Include OP / Pre-OP Races, and Allow Racing During Summer) and an **Aptitudes** section (the six aptitude rows plus the aptitude-threshold selector). The new caps from [Section 13.3](#133-backends--milp-first-beam-search-as-fallback) live in the General section.

### 13.6 Race-day lifecycle — peek, mark pending, commit

The solver is consulted at three moments per race-day turn:

1. **Peek (pre-decision).** Before deciding to race or train, the bot calls `peekRaceKeyForTurn()` (or `peekDecisionForTurn()` from Trackblazer). The integration object returns the `Decision` from the cached schedule without mutating any state.
2. **Mark pending (at tap).** Once the bot finds the planned race in the in-game list and taps it, [`SmartRaceSolverIntegration.markPendingRace()`](android/app/src/main/java/com/steve1316/uma_android_automation/bot/solver/SmartRaceSolverIntegration.kt) stores a `pendingRace` snapshot. This race is considered "speculatively won" for downstream peek calls so the rest of the turn can plan against the assumed result.
3. **Commit (at result).** [Racing.kt](android/app/src/main/java/com/steve1316/uma_android_automation/bot/Racing.kt) detects the 1st-place screen via [`LabelCongratulations`](android/app/src/main/java/com/steve1316/uma_android_automation/components/LabelCongratulations.kt) and calls `commitPendingRace(won = firstPlace)`. The plan is locked in once at run start (by the post-seed broadcast) and otherwise only changes on a loss. On a win the race is appended to the permanent history and the cached schedule is reused for the broadcast - the future plan does not change, only the past results panel gains the new win. The rest of the schedule was already optimized assuming the trainee would win this race, so re-running the solver would only produce noise. On a loss the race is recorded in `raceLosses`, the matcher's epithet is marked dead, and the next snapshot re-runs the solver so the schedule replans around the dead epithet. When `disableScheduleReplanOnRaceLoss` (default off) is enabled, the loss is still recorded but the original schedule is kept intact — the remaining turns are not re-planned around the dead epithet. Turn-advance broadcasts only refresh the badge - they reuse the cached schedule. Peek calls from `Trackblazer` also read the cached schedule rather than re-solving every main-screen iteration.

> [!IMPORTANT]
> The speculative-pending model is what makes the race-list scan in [Trackblazer.findSuitableRace()](#117-race-selection) able to **short-circuit**: once the scan finds a race whose key matches `peekRaceKeyForTurn()`, it stops scrolling and commits to that race instead of finishing the full multi-page sweep.

> [!NOTE]
> **Same-track disambiguation by fan count.** Two races on the same turn can share an identical on-screen track string (e.g. both read "Tokyo Turf 2400m"). To make sure it taps the race the solver actually planned, the bot also OCRs each row's fan reward and matches it against the planned race's fan count; a row whose fans cannot be read (returns -1) is accepted for backward compatibility.

### 13.7 Race history — seed, broadcast, calendar

`SmartRaceSolverIntegration` keeps two in-memory lists for the current run:

- `raceHistory` — confirmed wins (or speculatively-pending wins). The solver reads this on every solve so already-won races aren't picked again. Scheduled in-game agenda races (not only solver-picked ones) are now also marked pending so their win or loss is recorded, and each race's running style is captured during the Career → Race History scrape.
- `raceLosses` — confirmed losses. Not consumed by the solver but surfaced in the Remote Log Viewer so the user can see what was attempted.

Both lists are cleared by `reset()` when a new bot run starts. On startup the bot calls `seedHistoryFromCareerScrape()` to OCR the in-game **Career → Race History** screen so a mid-run restart picks up where the previous session left off (skipped at or before turn 13 since pre-debut has no real history). When that scrape isn't usable, `seedHistoryFromPreview()` falls back to seeding from the Preview schedule's already-completed turns.

After every commit, [LogStreamServer](android/app/src/main/java/com/steve1316/uma_android_automation/utils/LogStreamServer.kt) broadcasts a fresh JSON calendar snapshot to the **Remote Log Viewer**. The viewer's Race History tab renders a 72-cell calendar with grade badges, race names, and per-cell tooltips that include the epithet progression, the per-matcher condition labels, and a synthetic Junior Make Debut entry. The whole panel hides itself when `enableSmartRaceSolver` is off.

---

## 14. Ask the Docs Chatbot

An optional, fully offline documentation assistant that answers questions about the app. The pipeline is **retrieval-augmented**: a small embedding model finds the most relevant excerpts from the app's own docs and source code, and a downloaded GGUF chat model paraphrases them. Every chat call runs locally — the only network use is the one-time download of the embedder ONNX and the user-selected GGUF.

### 14.1 Overview & guarantees

- **Opt-in.** Hidden until the user enables `Enable Ask the Docs feature` on the LLM Settings page. The toggle lives at `chat.enableAskTheDocs` in `BotStateContext` and gates both the drawer entry and the rest of the LLM Settings page.
- **Retrieve-only fallback.** Even with no chat model downloaded — or when generation is rejected by the verifier — the user still gets a verbatim excerpt from the most-similar doc chunk. The feature degrades to "search" rather than failing.
- **Three answer modes** surfaced as a label under each answer:
    - `generated` — LLM paraphrase that passed the grounding check.
    - `verifierFallback` — LLM produced an answer with too little overlap with the excerpts; the verbatim top citation is shown instead.
    - `retrieveOnly` — no model loaded, or the model returned the `NOT_IN_DOCS` sentinel.

```
question
   │
   ▼
(JS) Chat → bridge → searchDocs(q, 4)
   │
   ▼
DocIndex top-k by cosine similarity
   │
   ▼
ChatOrchestrator.expandSection() reassembles full sections
   │
   ├── no active model ─────────────────────────► retrieveOnly (verbatim)
   │
   ▼
llama.rn generates an answer
   │
   ├── output == "NOT_IN_DOCS" ─────────────────► retrieveOnly (verbatim)
   │
   ▼
groundingVerifier.overlap()
   │
   ├── overlap ≥ SUMMARY_THRESHOLD (0.3) ───────► generated (cite)
   └── overlap <  SUMMARY_THRESHOLD ────────────► verifierFallback (verbatim)
```

### 14.2 Corpus & indexing

The corpus is built **at compile time** by [scripts/build-doc-index.ts](scripts/build-doc-index.ts) and shipped as a binary asset that the app loads on first chat call. Sources covered:

- `README.md` and `HOW_IT_WORKS.md` (this file).
- The static option descriptions from [src/context/searchConfig.ts](src/context/searchConfig.ts) — same strings used by the in-app settings search.
- The Kotlin source under [android/app/src/main/java/com/steve1316/uma_android_automation/](android/app/src/main/java/com/steve1316/uma_android_automation/), so questions about implementation are grounded in the actual code rather than docs only. Code chunking is done with a **tree-sitter Kotlin** parser so chunks land on declaration boundaries (functions, classes, top-level properties) instead of arbitrary line ranges.

The script splits each source into roughly section-sized **chunks** (each chunk keeps its `source` and hierarchical `heading` so citations stay readable), embeds them, and writes the binary index consumed by [DocIndex.kt](android/app/src/main/java/com/steve1316/uma_android_automation/llm/DocIndex.kt). The index format is **v2**: chunk metadata (including a single `kind` byte distinguishing `"doc"` from `"code"`) followed by a contiguous block of L2-normalized 384-dim float vectors — small enough to load fully into memory.

### 14.3 Embedding pipeline

The embedder is `sentence-transformers/all-MiniLM-L6-v2` running through **ONNX Runtime for Android**. The corpus build script and [EmbeddingService.kt](android/app/src/main/java/com/steve1316/uma_android_automation/llm/EmbeddingService.kt) use the same model so query and document vectors live in the same space.

> [!NOTE]
> **Embedder is downloaded, not bundled.** The ONNX file is too large to ship inside the APK, so the app fetches it on first use from the public mirror at [src/lib/chat/embedder.ts](src/lib/chat/embedder.ts) (`Xenova/all-MiniLM-L6-v2/onnx/model_quantized.onnx`). The download is SHA-256-verified against the value baked into the build script, so a runtime mirror swap can't desynchronize the embeddings.

- **Tokenization** is BERT-style WordPiece, implemented in pure Kotlin in [WordPieceTokenizer.kt](android/app/src/main/java/com/steve1316/uma_android_automation/llm/WordPieceTokenizer.kt) (no JNI dependency on a native tokenizer). It does basic Unicode normalization + accent stripping, then greedy longest-match against the WordPiece vocab, with `[CLS]`/`[SEP]` framing and zero-padding to a fixed length.
- **Pooling.** The model returns one vector per token; `EmbeddingService.meanPoolAndNormalize()` masks padding, mean-pools across real tokens, then L2-normalizes. After normalization, **dot product equals cosine similarity**, which lets retrieval skip the divide step entirely.
- **Lazy init.** Both `EmbeddingService` and `DocIndex` are loaded once and cached using double-checked locking, so the first chat call pays the load cost and every subsequent call is cheap.

### 14.4 Retrieval

[ChatOrchestrator.kt](android/app/src/main/java/com/steve1316/uma_android_automation/llm/ChatOrchestrator.kt) is the single entry point used by the React Native bridge:

1. Embed the query with `EmbeddingService`.
2. Call `DocIndex.search(vector, k)`. With `TOP_K = 4` (see [src/pages/Chat/index.tsx](src/pages/Chat/index.tsx)) the chat page asks for four chunks; the search itself is a linear scan of normalized vectors — fast enough on-device given the small corpus.
3. For each hit, `ChatOrchestrator.expandSection()` walks neighboring chunks of the same section heading and reassembles a larger excerpt (capped at `EXPANSION_CHAR_CAP`) so the LLM sees coherent prose instead of the truncated chunk window the indexer produced.

Each result carries `source`, `heading`, `text` (raw chunk), `expandedText` (reassembled section), `score`, and a `kind` of `"doc"` or `"code"` — code citations render with Kotlin syntax highlighting and a `File.kt::member` heading; doc citations render as Markdown.

### 14.5 Generation (optional)

When a downloaded GGUF is present, [llamaRunner.ts](src/lib/chat/llamaRunner.ts) loads it through `llama.rn` and the [LLMChatModule.kt](android/app/src/main/java/com/steve1316/uma_android_automation/llm/LLMChatModule.kt) bridge. The system prompt:

- Forbids verbatim copying — the model must paraphrase.
- Caps target length at 4–10 sentences and forbids any `Answer:` prefix.
- Tells the model to emit the literal sentinel `NOT_IN_DOCS` when the excerpts don't contain the answer. The Chat page treats that sentinel as a signal to drop into retrieve-only mode rather than show a hallucinated reply.

The supported presets are **Qwen 2.5 Instruct** GGUFs (Q4_K_M quants verified against the official Hugging Face repos) at 0.5B / 1.5B / 3B sizes, plus a **Custom** card for pasting any other `.gguf` URL. The 0.5B preset is the default — fast on the slowest devices, weak summaries; the 3B preset gives the highest quality but needs ~4 GB free RAM.

Three knobs from [chatSettings.ts](src/lib/chat/chatSettings.ts) tune the generation step:

- `maxOutputTokens` — hard cap on the answer length (default 768).
- `llmCitationCharCap` — how much of each expanded citation is fed in (default 2200). Larger cap → more material to summarize from; smaller cap → faster, fits more citations into the model's context window.
- `modelContextWindow` — the engine KV-cache size (`n_ctx`, default 4096). Changing it reloads the loaded model on the next chat call.

Sampling defaults in [llamaRunner.ts](src/lib/chat/llamaRunner.ts) are tuned to keep small models from looping: `temperature = 0.35`, `topK = 40`, `topP = 0.95`, `minP = 0.05`, and a **repetition penalty** of `penaltyRepeat = 1.1` over `penaltyLastN = 128` recent tokens. Without `penaltyRepeat` and `minP`, the 0.5B model in particular tends to cycle through paragraph-sized fragments verbatim. The default `stop` list covers Gemma (`<end_of_turn>`), Qwen (`<|im_end|>` / `<|end|>`), and Llama (`<|eot_id|>` / `</s>`) end-of-turn markers so the model halts cleanly regardless of which preset is loaded.

> [!TIP]
> **Stop generation.** The Ask button on the Chat page acts as a stop button while a generation is in flight — tapping it cancels the in-progress `llama.rn` call so a runaway response can be aborted without waiting for `maxOutputTokens` to roll over.

### 14.6 Grounding verifier & failure modes

Generated answers are not trusted blindly. [src/lib/chat/groundingVerifier.ts](src/lib/chat/groundingVerifier.ts) computes a token-overlap score between the generated answer and the (trimmed) citation excerpts:

- `overlap >= SUMMARY_THRESHOLD` (0.3) → answer is accepted as `generated`. The Chat page also surfaces `grounding NN%` in the mode label so the user can judge confidence.
- `overlap <  SUMMARY_THRESHOLD` → the generated text is discarded; the verbatim top citation is shown as `verifierFallback`. The rejected answer is kept on the result object for diagnostics but not displayed.

This is deliberately conservative: if the model wandered off the docs, the user gets the source text instead of a confident-sounding fabrication.

### 14.7 Model lifecycle

Chat models are GGUF files downloaded at runtime by [ModelDownloader.kt](android/app/src/main/java/com/steve1316/uma_android_automation/llm/ModelDownloader.kt) using Android's system `DownloadManager`. The downloader exposes `pending → running → paused → complete | failed | error` state subtypes that the LLM Settings page subscribes to via a `NativeEventEmitter` for live progress.

- **Storage.** Files land in app-private storage and are listed by `LLMChatModule.listModels()`. Multiple models can be kept on disk at the same time and switched between freely.
- **Active model.** The user's choice persists under `ACTIVE_MODEL_SETTING` (chat category, key `activeModelFilename`). It can be switched from the LLM Settings page **and** from the selector at the top of the Ask the Docs page — both go through the same write path so the change survives app restart.
- **Hugging Face token.** Public Qwen presets need no auth, but the Custom card accepts a Hugging Face read-access token and persists it in SQLite outside `BotStateContext` so it never leaks into settings exports. The token field is shown unmasked so the user can verify it before saving.
- **Race protection.** Because `EmbeddingService` and `DocIndex` are lazily initialized, downloading a chat model while a query is in flight can't corrupt embedding state — generation simply falls back to `retrieveOnly` for that one call and the next call picks up the newly active model.
- **Deletion.** Per-file or bulk delete is offered from the Downloaded Models list; deleting the active file clears `ACTIVE_MODEL_SETTING` so the next chat call cleanly drops to retrieve-only.

### 14.8 Device fitness panel

The LLM Settings page surfaces a small **Device Fitness** row driven by [src/lib/chat/deviceCapabilities.ts](src/lib/chat/deviceCapabilities.ts):

- **RAM (total / available)** read from Android's `ActivityManager.MemoryInfo`. Used both for the diagnostic display and for a pre-download fit check that warns when the selected preset's hand-tuned RAM requirement (see `PRESET_RAM_REQUIREMENTS_BYTES`) exceeds available memory.
- **Acceleration tier** derived from `Build.SUPPORTED_ABIS[0]` plus the `Features:` line in `/proc/cpuinfo`:
    - `v8.2 + dotprod (fast)` — arm64 device with `asimddp` support; runs the dotprod-optimized llama.rn variant.
    - `v8 baseline (slow)` — arm64 device without `asimddp`; runs the baseline arm64 variant.
    - `x86_64 native` — Android emulator on a desktop x86_64 host. The APK ships both `arm64-v8a` and `x86_64` ABIs so the emulator gets native llama.rn binaries instead of QEMU-translated arm64 ones, which is roughly an order of magnitude faster on `qwen2.5-0.5b-instruct-q4_k_m.gguf`.
    - `unknown` — defensive fallback when neither check returns useful data.
- **Recommended preset** picked by walking `PRESET_RAM_REQUIREMENTS_BYTES` against available RAM and surfacing the largest preset that fits.

The `i8mm` and Hexagon / OpenCL llama.rn variants are intentionally trimmed from the APK to keep the install size down, so the tier label is informational only — the runtime always picks one of the three shipped variants.

---

## 15. Decision Tracer

The Decision Tracer is a structured **per-turn log block** that answers "why did the bot do X this turn?" without forcing the user to grep across dozens of interleaved `MessageLog` lines. It sits alongside the existing chronological log — the original `MessageLog.i/v/w/e` lines are untouched — and emits a single consolidated **Decision Report** block at the end of every main-screen turn.

### 15.1 Architecture

A single [DecisionTracer.kt](android/app/src/main/java/com/steve1316/uma_android_automation/bot/DecisionTracer.kt) instance lives on `Campaign` (`Campaign.decisionTracer`). Each main-screen turn:

1. **`startTurn(...)`** snapshots trainee state (energy, mood, negative statuses, inventory by category, scenario extras like `consecutiveRaceCount`) and a `SettingsSnapshot` of decision-relevant settings.
2. The decision tree calls `record...` methods as it walks — `recordActionChoice`, `recordRejectedAlternative`, `recordItemUsage`, `recordTrainingSelection`, `recordNote`, etc. — appending events in order.
3. **`emit()`** flushes the formatted block once via `MessageLog.i` and clears the buffer for the next turn.

`Campaign` exposes an overrideable `gatherDecisionSettings()` hook so each scenario contributes its own settings snapshot. Trackblazer, URA Finale, and Unity Cup all override this; the base campaign also instruments its `decideNextAction()` priority waterfall and `Racing.kt` instruments race eligibility and result handling.

### 15.2 What ends up in a Decision Report

Each block is bracketed by a header like `============== Turn 25 (CLASSIC EARLY JANUARY) Decision Report ==============` and contains:

- **State snapshot** — energy, mood, active negative statuses, decision-relevant inventory grouped by category (Megaphones, Hammers, Energy, Cupcakes, Stat items, Race items, etc.), and any scenario extras (e.g. Trackblazer's `consecutiveRaceCount`).
- **Settings snapshot** — the live values of every setting that gated a decision this turn, including the new Trackblazer item conservation thresholds.
- **Action choice** — which `MainScreenAction` (RACE / TRAIN / REST / RECOVER_MOOD / NONE) the bot picked, with a one-line reason.
- **Rejected alternatives** — actions that were considered but lost, each with a short rejection reason (e.g. `REST: pre-summer prep already satisfied`).
- **Training selection** — the picked training, its score, and a runner-up list with scores and rejection reasons.
- **Item usage** — which items were used or deliberately skipped, with the gate that fired.

### 15.3 Coverage

| Layer | Instrumented decisions |
|-------|------------------------|
| `Campaign.decideNextAction()` | The full priority waterfall (mandatory race, force racing, maiden, pre-summer prep, fan/trophy requirement, injury, mood recovery, extra-race eligibility, default train) |
| `Racing.kt` | Race eligibility checks, race-list scan results, result detection |
| Trackblazer | Irregular Training evaluation, item usage pass, consecutive-race guard, race fallback |
| URA Finale & Unity Cup | Scenario-specific override branches |

> [!TIP]
> The Decision Report block is greppable — looking for `Decision Report` finds every turn at once, and looking for a specific action like `→ TRAIN` or a specific rejection like `RACE: ` narrows it to the turns where that decision was on the table.

---

## 16. Remote Log Viewer & Run Analytics

The **Remote Log Viewer** is an on-device web dashboard served by [LogStreamServer.kt](android/app/src/main/java/com/steve1316/uma_android_automation/utils/LogStreamServer.kt) over a WebSocket and rendered by [log_viewer.html](android/app/src/main/assets/log_viewer.html). Pointing a browser on the same network at the device opens it. Besides the live log stream it has two data tabs: **Race History** (driven by the Smart Race Solver) and **Run Analytics** (a per-run statistics dashboard).

### 16.1 Race History tab

This tab renders the Smart Race Solver's 72-cell calendar — see [Section 13.7](#137-race-history--seed-broadcast-calendar) for how the data is seeded and broadcast. Two display details were added since:

- **Mandatory career races** ([Section 13.1](#131-when-the-solver-runs)) render in amber with a pin marker so they stand out from solver-chosen races, and their tooltip labels them as forced career races.
- Each race's **running style** (captured during the Career → Race History scrape) is appended to the per-cell tooltip alongside the track, distance, and fan reward.

The whole panel hides itself when the Smart Race Solver is disabled.

### 16.2 Run Analytics dashboard

The analytics tab is a live, per-run statistics dashboard. A [RunAnalytics.kt](android/app/src/main/java/com/steve1316/uma_android_automation/bot/RunAnalytics.kt) singleton accumulates the run as it happens:

- **Per-turn records** — turn number and date label, the five stats, energy, mood, fan count, skill points, the action taken that turn, and (on training turns) the trained stat, its stat gains, and the failure chance.
- **Per-race records** — turn, race name, grade, surface, distance, fans awarded, whether the trainee won, and whether the race was mandatory.

The bot feeds these in from the turn loop ([Campaign.kt](android/app/src/main/java/com/steve1316/uma_android_automation/bot/Campaign.kt) / [Trackblazer.kt](android/app/src/main/java/com/steve1316/uma_android_automation/bot/campaigns/Trackblazer.kt)) and from the race solver's commit step. After each turn boundary `RunAnalytics` serializes the entire run to a JSON snapshot that `LogStreamServer` broadcasts; the latest snapshot is cached and replayed to any browser that connects mid-run, so a refresh paints the current state immediately.

The dashboard groups roughly twenty charts into four areas:

| Area | Charts |
|------|--------|
| **Trainee** | Hero card (name, scenario, date, turn progress, current stats, a "Start fresh" button to discard the saved run), stats radar, stat-growth line, stat-composition area |
| **Training** | Training-focus distribution, total and cumulative stat gains, skill points over time, failure chance over time and its distribution, energy-vs-failure scatter, gains per turn |
| **Racing** | Win-rate gauge, races by grade, wins/losses by grade, race-results timeline, races by surface and by distance, fans over time and fans per race |
| **Per-year & cadence** | Action mix by year, overall action distribution, mood distribution, energy & mood over time |

Every chart has an info button explaining what it shows and a button to export its data as CSV. On a restart the dashboard resumes a matching saved run (same scenario and trainee, same-or-later turn) and keeps the elapsed-runtime timer running rather than starting over.
