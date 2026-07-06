/**
 * Static search registry containing all searchable settings items.
 * This is used to pre-populate the search index at app initialization
 * without rendering any components, avoiding the UI freeze caused by
 * the HeadlessRenderer approach.
 *
 * To add a new searchable item, add an entry here with the same `id`
 * as the `searchId` prop on the component. The `page` must match the
 * Stack.Screen name / SearchPageProvider `page` value.
 */

import { SearchOption } from "../context/SearchRegistryContext"

/** All searchable items across all settings pages. */
const searchConfig: SearchOption[] = [
    // ============================================================
    // Schedule (ScheduleScreen)
    // ============================================================
    {
        id: "schedule-screen",
        title: "Schedule",
        description: "Unified calendar of every date-based scheduler: Smart Race Solver races, recreation dates, Pure Passion, and stop dates. Resolves cross-feature conflicts on one turn grid.",
        page: "ScheduleScreen",
        tab: "raceSolver",
    },
    {
        id: "schedule-dating-source",
        title: "Dating Schedule source",
        description: "Recreation outings and Pure Passion, shown and edited on the unified Schedule calendar.",
        page: "ScheduleScreen",
        tab: "recreation",
    },
    {
        id: "schedule-srs-source",
        title: "Smart Race Solver source",
        description: "Solver-planned races and manual per-turn locks, shown on the Schedule calendar.",
        page: "ScheduleScreen",
        tab: "raceSolver",
    },
    {
        id: "settings-dating-schedule",
        title: "Support Card Dating Schedule",
        description:
            "On a pinned turn the bot does a support-card recreation outing over every other action, including scheduled races (in-game racing agenda or Smart Race Solver). Only mandatory career-goal races take priority.",
        page: "ScheduleScreen",
        tab: "recreation",
    },
    {
        id: "settings-recreation-catch-up",
        title: "Catch Up On Missed Dates",
        description: "If a scheduled outing gets skipped (e.g. a mandatory race lands on it), make it up on the next available turn instead of losing it.",
        page: "ScheduleScreen",
        tab: "recreation",
        parentId: "settings-dating-schedule",
    },
    {
        id: "settings-dating-preset",
        title: "Schedule Preset",
        description: "Pick an optimized preset (Pure Passion timed for a summer camp) or Custom to hand-pick turns on the Schedule screen.",
        page: "ScheduleScreen",
        tab: "recreation",
        parentId: "settings-dating-schedule",
    },
    // ============================================================
    // Settings (SettingsMain)
    // ============================================================
    {
        id: "settings-stop-before-finals",
        title: "Stop before Finals",
        description: "Pause to buy skills before the final races",
        page: "SettingsMain",
    },
    {
        id: "settings-claw-machine-attempt",
        title: "Enable Claw Machine Attempt",
        description: "Attempt to complete the claw machine instead of stopping",
        page: "SettingsMain",
    },
    {
        id: "settings-enable-swipe-based-scrolling",
        title: "Enable Swipe-Based Scrolling",
        description: "Scroll lists by swiping instead of detecting the in-game scrollbar. Enable this if the bot cannot scroll lists normally. This may or may not work depending on the device.",
        page: "SettingsMain",
    },
    {
        id: "settings-enable-settings-display",
        title: "Enable Settings Display in Message Log",
        description: "Show current bot configuration in the message log",
        page: "SettingsMain",
    },
    {
        id: "settings-enable-message-id-display",
        title: "Enable Message ID Display",
        description: "Shows message IDs in the message log to help with debugging.",
        page: "DebugSettings",
    },
    {
        id: "settings-wait-delay",
        title: "Wait Delay",
        description:
            "Sets the delay between actions and imaging operations. Lowering this will make the bot run much faster at the risk of the bot losing track of its location after loading/connecting screens.",
        page: "SettingsMain",
    },
    {
        id: "settings-overlay-button-size",
        title: "Overlay Button Size",
        description: "Sets the size of the floating overlay button in density-independent pixels (dp). Higher values make the button easier to tap.",
        page: "DebugSettings",
    },
    {
        id: "settings-management-title",
        title: "Settings Management",
        description: "Import and export settings from JSON file or access the app's data directory.",
        page: "SettingsMain",
    },

    // ============================================================
    // Training Settings
    // ============================================================
    {
        id: "training-settings-profile-selector",
        title: "Profile Selector",
        description: "Profiles constitute only the Training settings and stat targets.",
        page: "TrainingSettings",
    },
    {
        id: "training-blacklist",
        title: "Blacklist",
        description: "Select which stats to exclude from training. These stats will be skipped during training sessions.",
        page: "TrainingSettings",
    },
    {
        id: "training-prioritization",
        title: "Prioritization",
        description: "Select the priority order of the stats. The stats will be trained in the order they are selected. If none are selected, then the default order will be used.",
        page: "TrainingSettings",
    },
    {
        id: "event-choice-stat-priority",
        title: "Event Choice Prioritization",
        description:
            "Select the priority order of stats used when scoring in-game event choices. Events typically grant flat stat gains, so a different ordering than regular training may be optimal.",
        page: "TrainingSettings",
    },
    {
        id: "summer-training-stat-priority",
        title: "Summer Training Prioritization",
        description: "Select the priority order of stats used during Summer Training. Facility levels are maxed during summer, so a different ordering than regular training may be optimal.",
        page: "TrainingSettings",
    },
    {
        id: "disable-training-on-maxed-stats",
        title: "Disable Training on Maxed Stats",
        description: "When enabled, training will be skipped for stats that have reached their maximum value.",
        page: "TrainingSettings",
    },
    {
        id: "maximum-failure-chance",
        title: "Set Maximum Failure Chance",
        description: "Set the maximum acceptable failure chance for training sessions. Training with higher failure rates will be avoided.",
        page: "TrainingSettings",
    },
    {
        id: "enable-riskier-training",
        title: "Enable Riskier Training",
        description: "When enabled, trainings with high main stat gains will use a separate, higher maximum failure chance threshold.",
        page: "TrainingSettings",
    },
    {
        id: "risky-training-min-stat-gain",
        title: "Minimum Main Stat Gain Threshold",
        description: "When a training's main stat gain meets or exceeds this value, it will be considered for risky training.",
        page: "TrainingSettings",
        parentId: "enable-riskier-training",
    },
    {
        id: "risky-training-max-failure-chance",
        title: "Risky Training Maximum Failure Chance",
        description: "Set the maximum acceptable failure chance for risky training sessions with high main stat gains.",
        page: "TrainingSettings",
        parentId: "enable-riskier-training",
    },
    {
        id: "enable-prioritize-skill-hints",
        title: "Prioritize Skill Hints",
        description: "When enabled, the bot will prioritize acquiring skill hints, bypassing stat prioritization and blacklist, while still being constrained by the failure chance thresholds.",
        page: "TrainingSettings",
    },
    {
        id: "enable-training-level-weighting",
        title: "Weight Score by Training Level",
        description:
            "When enabled (Year 2+), the bot reads each training's level (1-5) via OCR and boosts the score for trainings whose stat sits in the top 3 of your Stat Prioritization list. Helps the bot stick with stats you've invested in. OCR is skipped during Pre-Debut, Junior, and Summer.",
        page: "TrainingSettings",
    },
    {
        id: "must-rest-before-summer",
        title: "Must Rest before Summer",
        description:
            "Optimizes June Late Phase in Classic and Senior Years for Summer Training. If Energy < 70%, it will Rest. If Energy >= 70% and Mood < Great, it will recover Mood. If Energy >= 70% and Mood is Great, it will train Wit.",
        page: "TrainingSettings",
    },
    {
        id: "train-wit-during-finale",
        title: "Train Wit During Finale",
        description: "When enabled, the bot will train Wit during URA finale turns (73, 74, 75) instead of recovering energy or mood, even if the failure chance is high.",
        page: "TrainingSettings",
    },
    {
        id: "enable-rainbow-training-bonus",
        title: "Enable Rainbow Training Bonus",
        description:
            "When enabled (Year 2+), rainbow trainings receive a significant bonus to their score, making them more likely to be selected. This is highly dependent on device configuration and may result in false positives.",
        page: "TrainingSettings",
    },
    {
        id: "enable-prioritize-near-max-friendship",
        title: "Prioritize Near-Max Friendship Bars",
        description:
            "When enabled (Year 2+), trainings with multiple green/blue friendship bars close to maxing receive an anticipatory rainbow multiplier, helping the bot favor them so the bars cross into orange and unlock rainbow training on later turns. Does not stack with the actual rainbow bonus.",
        page: "TrainingSettings",
    },
    {
        id: "enable-training-analysis-validation",
        title: "Enable Training Analysis Validation",
        description:
            "When enabled, the bot will validate the current selected stat during training analysis. This helps prevent the bot from accidentally training a stat during analysis at the cost of a significant increase in scenario completion time.",
        page: "TrainingSettings",
    },
    {
        id: "preferred-distance-override",
        title: "Preferred Distance Override",
        description: "Set the preferred race distance for training targets. Auto picks based on character aptitudes.",
        page: "TrainingSettings",
    },
    {
        id: "disable-stat-targets",
        title: "Disable Stat Targets",
        description:
            "When enabled, all per-distance stat targets below are ignored. Every stat is treated as having a target equal to the in-game stat cap (1200), so the bot will keep pushing your top priority stats even after they would normally be considered 'done.' Useful when you want strict adherence to your Stat Prioritization list.",
        page: "TrainingSettings",
    },
    {
        id: "stat-targets-by-distance",
        title: "Stat Targets by Distance",
        description: "Set target values for each stat based on race distance.",
        page: "TrainingSettings",
    },
    {
        id: "training-year-milestone-targets",
        title: "Training Year Milestone Targets",
        description: "Controls how aggressively the bot paces stat training during the Pre-Debut, Junior and Classic Years.",
        page: "TrainingSettings",
    },
    {
        id: "junior-milestone-percent",
        title: "End of Junior Year Milestone",
        description: "Percentage of the primary stat targets to aim for by the end of Junior Year.",
        page: "TrainingSettings",
        parentId: "training-year-milestone-targets",
    },
    {
        id: "classic-milestone-percent",
        title: "End of Classic Year Milestone",
        description: "Percentage of the primary stat targets to aim for by the end of Classic Year.",
        page: "TrainingSettings",
        parentId: "training-year-milestone-targets",
    },
    {
        id: "training-scoring-sandbox",
        title: "Scoring Sandbox",
        description: "Preview which training the bot would pick for a hypothetical scenario.",
        page: "TrainingSettings",
    },
    {
        id: "training-scoring-priority-multiplier",
        title: "Priority Multiplier",
        description: "Tune how much priority order influences scoring.",
        page: "TrainingSettings",
    },
    {
        id: "training-scoring-ratio-curve",
        title: "Ratio Curve",
        description: "Tune the per-bucket ratio multipliers that drive stat-efficiency scoring. Buckets at 15/30/45/60/75/90% of target are fixed.",
        page: "TrainingSettings",
    },
    {
        id: "training-scoring-weights",
        title: "Score Weights",
        description: "Adjust how stat efficiency, relationships, and misc contribute to the final score.",
        page: "TrainingSettings",
    },
    {
        id: "training-scoring-main-stat-bonuses",
        title: "Main Stat Bonuses",
        description: "Adjust the per-stat gain thresholds that trigger the main-stat bonus.",
        page: "TrainingSettings",
    },
    {
        id: "training-scoring-level-boost",
        title: "Level Boost",
        description: "Adjust how training facility level amplifies the primary stat contribution.",
        page: "TrainingSettings",
    },
    {
        id: "training-scoring-misc",
        title: "Misc Scoring",
        description: "Tune rainbow, anticipatory, energy, and other misc scoring multipliers.",
        page: "TrainingSettings",
    },
    // ============================================================
    // Training Event Settings
    // ============================================================
    {
        id: "prioritize-energy-options",
        title: "Prioritize Energy Options",
        description:
            "When enabled, the bot will prioritize training event choices that provide energy recovery or avoid energy consumption, helping to maintain optimal energy levels for training sessions.",
        page: "TrainingEventSettings",
    },
    {
        id: "training-event-option-overrides",
        title: "Training Event Option Overrides",
        description:
            "Force the bot to select a specific option for character or support training events. Search through all available events and select which option to use. This overrides the normal stat prioritization logic.",
        page: "TrainingEventSettings",
    },
    {
        id: "special-event-overrides",
        title: "Special Event Overrides",
        description: "Override the bot's normal stat prioritization for specific training events. These settings bypass the standard weight calculation system.",
        page: "TrainingEventSettings",
    },
    {
        id: "ocr-recognition-settings",
        title: "OCR Recognition Settings",
        description: "Configure settings for detecting and recognizing Training Event titles using OCR.",
        page: "TrainingEventSettings",
    },
    {
        id: "automatic-ocr-retry-training",
        title: "Enable Automatic OCR Retry for Training Events",
        description: "When enabled, the bot will automatically retry OCR detection with adjusted settings if the initial attempt for a training event title fails or has low confidence.",
        page: "TrainingEventSettings",
        parentId: "ocr-recognition-settings",
    },
    {
        id: "ocr-confidence-training",
        title: "OCR Confidence for Training Events",
        description: "The minimum confidence level required for a Training Event title to be considered a match. Higher values ensure more accurate recognition but may lead to more missed events.",
        page: "TrainingEventSettings",
        parentId: "ocr-recognition-settings",
    },
    {
        id: "hide-ocr-comparison-results-training",
        title: "Hide OCR String Comparison Results",
        description: "If enabled, the bot will suppress detailed logging of individual string similarity scores during training event detection to keep the logs cleaner.",
        page: "TrainingEventSettings",
        parentId: "ocr-recognition-settings",
    },

    // ============================================================
    // OCR Settings
    // ============================================================
    {
        id: "ocr-threshold",
        title: "OCR Threshold",
        description:
            "The brightness threshold used to distinguish text from the background during OCR. Note: This setting does not affect high-precision features like Stat Detection or Training Failure Chance detection, as they use specialized processing.",
        page: "DebugSettings",
    },

    // ============================================================
    // Racing Settings
    // ============================================================
    {
        id: "enable-farming-fans",
        title: "Enable Farming Fans",
        description: "When enabled, the bot will start running extra races to gain fans.",
        page: "RacingSettings",
    },
    {
        id: "days-to-run-extra-races",
        title: "Days to Run Extra Races",
        description: "Extra races are eligible only on days where current day % value == 0. For example, 5 means days 5, 10, 15, etc. Has no effect when Smart Race Solver is enabled.",
        page: "RacingSettings",
    },
    {
        id: "min-energy-for-extra-racing",
        title: "Minimum Energy for Extra Races",
        description:
            "Skip the fan-farming extra race when energy is below this percentage. 0 disables the floor. Only gates the standard fan-farming cadence, never mandatory, scheduled, or solver races.",
        page: "RacingSettings",
    },
    {
        id: "enable-g1-day-preference",
        title: "Prefer Training on G1 Days",
        description: "On a G1 race day (Classic/Senior years), peek at the trainings first and stay to train when a strong rainbow training is available instead of taking the race.",
        page: "RacingSettings",
    },
    {
        id: "g1-day-min-rainbow-count",
        title: "Minimum Rainbows to Train Over G1",
        description: "The best training must have at least this many rainbow supports to train instead of racing the G1.",
        page: "RacingSettings",
        parentId: "enable-g1-day-preference",
    },
    {
        id: "ignore-consecutive-race-warning",
        title: "Ignore Consecutive Race Warning",
        description: "When enabled, the bot will ignore the warning popup about consecutive races and continue racing.",
        page: "RacingSettings",
    },
    {
        id: "disable-race-retries",
        title: "Disable Race Retries",
        description: "When enabled, the bot will not retry mandatory races if they fail and will stop.",
        page: "RacingSettings",
    },
    {
        id: "enable-free-race-retry",
        title: "Allow Daily Free Race Retry",
        description: "When enabled, the bot will attempt to retry a failed mandatory race only if the daily free race retry is available.",
        page: "RacingSettings",
        parentId: "disable-race-retries",
    },
    {
        id: "enable-complete-career-on-failure",
        title: "Complete Career on Failure",
        description: "When enabled, the bot will proceed to the career completion screen when a mandatory race fails and retries are exhausted.",
        page: "RacingSettings",
    },
    {
        id: "enable-stop-on-mandatory-races",
        title: "Stop on Mandatory Races",
        description: "When enabled, the bot will automatically stop when it encounters a mandatory race, allowing you to manually handle them.",
        page: "RacingSettings",
    },
    {
        id: "junior-year-race-strategy",
        title: "Junior Year Strategy",
        description: "The race strategy to use for all races during Junior Year.",
        page: "RacingSettings",
    },
    {
        id: "original-race-strategy",
        title: "Original Strategy",
        description: "The race strategy to reset to after Junior Year. The bot will use this strategy for races in Year 2 and beyond.",
        page: "RacingSettings",
    },
    {
        id: "enable-per-distance-strategy",
        title: "Per-Distance Strategy",
        description: "When enabled, allows setting different race strategies for each track distance.",
        page: "RacingSettings",
    },
    {
        id: "junior-strategy-short",
        title: "Junior Year Short Distance Strategy",
        description: "The race strategy to use for short distance races during Junior Year.",
        page: "RacingSettings",
    },
    {
        id: "junior-strategy-mile",
        title: "Junior Year Mile Distance Strategy",
        description: "The race strategy to use for mile distance races during Junior Year.",
        page: "RacingSettings",
    },
    {
        id: "junior-strategy-medium",
        title: "Junior Year Medium Distance Strategy",
        description: "The race strategy to use for medium distance races during Junior Year.",
        page: "RacingSettings",
    },
    {
        id: "junior-strategy-long",
        title: "Junior Year Long Distance Strategy",
        description: "The race strategy to use for long distance races during Junior Year.",
        page: "RacingSettings",
    },
    {
        id: "original-strategy-short",
        title: "Original Short Distance Strategy",
        description: "The race strategy to use for short distance races in Year 2 and beyond.",
        page: "RacingSettings",
    },
    {
        id: "original-strategy-mile",
        title: "Original Mile Distance Strategy",
        description: "The race strategy to use for mile distance races in Year 2 and beyond.",
        page: "RacingSettings",
    },
    {
        id: "original-strategy-medium",
        title: "Original Medium Distance Strategy",
        description: "The race strategy to use for medium distance races in Year 2 and beyond.",
        page: "RacingSettings",
    },
    {
        id: "original-strategy-long",
        title: "Original Long Distance Strategy",
        description: "The race strategy to use for long distance races in Year 2 and beyond.",
        page: "RacingSettings",
    },
    {
        id: "enable-force-racing",
        title: "Force Racing",
        description: "When enabled, the bot will skip all training, rest, and mood recovery activities and focus exclusively on racing every day.",
        page: "RacingSettings",
    },
    {
        id: "enable-user-in-game-race-agenda",
        title: "Enable User In-Game Race Agenda",
        description:
            "When enabled, the bot will load your selected in-game race agenda instead of using the racing plan settings. Note that this will disable the farming fans and racing plan settings.",
        page: "RacingSettings",
    },
    {
        id: "user-in-game-race-agenda",
        title: "Select Agenda",
        description: "The in-game race agenda the bot loads when the toggle above is enabled.",
        page: "RacingSettings",
        parentId: "enable-user-in-game-race-agenda",
    },
    {
        id: "custom-agenda-title",
        title: "Custom Agenda Title",
        description: "If you renamed your agenda in-game, enter the custom title here. Leave blank to use the selected agenda name above.",
        page: "RacingSettings",
        parentId: "enable-user-in-game-race-agenda",
    },
    {
        id: "limit-races-to-in-game-agenda",
        title: "Limit Extra Races to Agenda",
        description:
            "When enabled, the bot will override the racing behavior of any scenario such that it will not run any extra races except for the ones scheduled by the selected user's in-game racing agenda.",
        page: "RacingSettings",
        parentId: "enable-user-in-game-race-agenda",
    },
    {
        id: "skip-summer-training-for-agenda",
        title: "Skip Summer Training for Agenda",
        description:
            "When enabled, the bot will perform scheduled races from the in-game racing agenda during Summer instead of prioritizing Summer training. Note that this requires 'Enable User In-Game Race Agenda' to be enabled.",
        page: "RacingSettings",
        parentId: "enable-user-in-game-race-agenda",
    },

    // ============================================================
    // Smart Race Solver Settings
    // ============================================================
    {
        id: "enable-smart-race-solver",
        title: "Enable Smart Race Solver",
        description: "Plans every turn of the career to maximize score by targeting epithet rewards. The bot only races when the solver picks a race; other turns become training or rest.",
        page: "ScheduleScreen",
        tab: "raceSolver",
    },
    {
        id: "disable-schedule-replan-on-race-loss",
        title: "Disable Schedule Re-Plan Upon Race Loss",
        description:
            "When a race is lost, keep the original schedule instead of re-planning the remaining turns. The loss is still recorded; epithets that depended on the lost race won't be re-routed.",
        page: "ScheduleScreen",
        tab: "raceSolver",
        parentId: "enable-smart-race-solver",
    },
    {
        id: "smart-solver-max-races",
        title: "Maximum Extra Races",
        description: "Caps how many optional races the solver schedules across the whole career. Mandatory career races always run and don't count toward this. 0 = no limit.",
        page: "ScheduleScreen",
        tab: "raceSolver",
        parentId: "enable-smart-race-solver",
    },
    {
        id: "smart-solver-max-consecutive-races",
        title: "Maximum Consecutive Races",
        description: "Caps how many races the solver schedules in back-to-back turns. Late-December turns are exempt so a chain may run into year-end. 0 = no limit.",
        page: "ScheduleScreen",
        tab: "raceSolver",
        parentId: "enable-smart-race-solver",
    },
    {
        id: "smart-solver-include-op",
        title: "Include OP / Pre-OP Races",
        description: "Lets the solver also consider OP and Pre-OP races, not just G1/G2/G3. Useful for weaker characters whose only eligible races are OP/Pre-OP.",
        page: "ScheduleScreen",
        tab: "raceSolver",
        parentId: "enable-smart-race-solver",
    },
    {
        id: "smart-solver-allow-summer",
        title: "Allow Racing During Summer",
        description: "Lets the solver schedule races during the Classic/Senior summer training-camp turns, which are blocked by default.",
        page: "ScheduleScreen",
        tab: "raceSolver",
        parentId: "enable-smart-race-solver",
    },
    {
        id: "smart-solver-how-it-works",
        title: "How it works",
        description: "Smart Race Solver overview, loss handling, race-history scrape, and notes on epithets without matchers.",
        page: "ScheduleScreen",
        tab: "raceSolver",
        parentId: "enable-smart-race-solver",
    },
    {
        id: "smart-solver-character-preset",
        title: "Character Preset",
        description: "Pick the trainee. Sets the calendar's mandatory races and seeds the Race Solver aptitudes.",
        page: "ScheduleScreen",
    },
    {
        id: "smart-solver-aptitudes",
        title: "Aptitudes",
        description: "Distance and surface aptitude grades. Races below the threshold are skipped by the solver.",
        page: "ScheduleScreen",
        tab: "raceSolver",
        parentId: "enable-smart-race-solver",
    },
    {
        id: "smart-solver-aptitude-threshold",
        title: "Aptitude Threshold",
        description: "Minimum aptitude (distance AND surface) required for a race to be eligible.",
        page: "ScheduleScreen",
        tab: "raceSolver",
        parentId: "enable-smart-race-solver",
    },
    {
        id: "smart-solver-target-epithets",
        title: "Target Epithets",
        description: "Epithets the solver actively pursues. Selecting one biases the schedule toward completing it.",
        page: "ScheduleScreen",
        tab: "raceSolver",
        parentId: "enable-smart-race-solver",
    },
    {
        id: "smart-solver-forced-epithets",
        title: "Forced Epithets",
        description:
            "Epithets the solver MUST complete. If completion becomes impossible (for example a needed race was already lost), the solver stops planning. Use sparingly - each forced epithet narrows what the solver can pick.",
        page: "ScheduleScreen",
        tab: "raceSolver",
        parentId: "enable-smart-race-solver",
    },
    {
        id: "smart-solver-optimize-mode",
        title: "Optimization Mode",
        description: "Pick whether the solver chases stat epitaphs or also emphasizes fan-heavy races.",
        page: "ScheduleScreen",
        tab: "raceSolver",
        parentId: "enable-smart-race-solver",
    },
    {
        id: "smart-solver-weights",
        title: "Scoring Weights",
        description: "Tune how the solver balances race value, epithet completion, fan rewards, and penalties.",
        page: "ScheduleScreen",
        tab: "raceSolver",
        parentId: "enable-smart-race-solver",
    },
    {
        id: "smart-solver-epithet-rewards",
        title: "Epithet Rewards",
        description: "Rewards for each selected and projected epithet.",
        page: "ScheduleScreen",
        tab: "raceSolver",
        parentId: "enable-smart-race-solver",
    },
    {
        id: "smart-solver-diagnostic",
        title: "Configuration Summary",
        description: "Read-only summary of the current solver configuration.",
        page: "ScheduleScreen",
        tab: "raceSolver",
        parentId: "enable-smart-race-solver",
    },

    // ============================================================
    // Skill Settings
    // ============================================================
    {
        id: "enable-skill-point-check",
        title: "Enable Skill Point Check",
        description: "Stop the bot when the skill point threshold is reached",
        page: "Skills",
    },
    {
        id: "skill-point-check",
        title: "Skill Point Threshold",
        description: "The number of skill points to accumulate before stopping the bot.",
        page: "Skills",
        parentId: "enable-skill-point-check",
    },
    {
        id: "skill-point-check-plan",
        title: "Enable Skill Point Check Plan",
        description: "Purchase skills based on this plan's configuration",
        page: "Skills",
        parentId: "enable-skill-point-check",
    },
    {
        id: "skill-plan-running-style",
        title: "Running Style for Skills",
        description: "Dictates which skills are considered for purchase based on the preferred running style.",
        page: "Skills",
    },
    {
        id: "preferred-track-surface",
        title: "Track Surface for Skills",
        description: "Dictates which skills are considered for purchase based on the terrain.",
        page: "Skills",
    },
    {
        id: "prioritize-recovery-for-stamina",
        title: "Prioritize Recovery Skills for Stamina",
        description: "On Medium/Long builds, nudge recovery skills up the auto-purchase ranking so the trainee holds pace longer. Has no effect on Sprint/Mile builds.",
        page: "Skills",
    },

    // ============================================================
    // Skill Plan Settings — Skill Point Check
    // ============================================================
    {
        id: "enable-buy-negative-skills-SkillPlanSettingsSkillPointCheck",
        title: "Purchase All Negative Skills",
        description: "Attempt to buy all negative skills (e.g. Firm Conditions x)",
        page: "Skills",
        parentId: "skill-point-check-plan",
    },
    {
        id: "exclude-green-skills-SkillPlanSettingsSkillPointCheck",
        title: "Skip All Green Skills",
        description: "Exclude green stat-trigger skills",
        page: "Skills",
        parentId: "skill-point-check-plan",
    },
    {
        id: "exclude-red-skills-SkillPlanSettingsSkillPointCheck",
        title: "Skip All Red Skills (Debuffs)",
        description: "Exclude red debuff skills",
        page: "Skills",
        parentId: "skill-point-check-plan",
    },
    {
        id: "exclude-unique-skills-SkillPlanSettingsSkillPointCheck",
        title: "Skip All Unique Skills",
        description: "Exclude inherited unique (legacy) skills",
        page: "Skills",
        parentId: "skill-point-check-plan",
    },
    {
        id: "exclude-double-circle-skills-SkillPlanSettingsSkillPointCheck",
        title: "Skip All Double-O (Circle) Skills",
        description: "Only buy the single-circle version; skip the double-circle upgrade",
        page: "Skills",
        parentId: "skill-point-check-plan",
    },

    // ============================================================
    // Skill Plan Settings — Pre-Finals
    // ============================================================
    {
        id: "enable-skill-plan-preFinals",
        title: "Enable Pre-Finals Plan",
        description: "Purchase skills based on this plan's configuration",
        page: "Skills",
    },
    {
        id: "enable-buy-negative-skills-SkillPlanSettingsPreFinals",
        title: "Purchase All Negative Skills",
        description: "Attempt to buy all negative skills (e.g. Firm Conditions x)",
        page: "Skills",
        parentId: "enable-skill-plan-preFinals",
    },
    {
        id: "exclude-green-skills-SkillPlanSettingsPreFinals",
        title: "Skip All Green Skills",
        description: "Exclude green stat-trigger skills",
        page: "Skills",
        parentId: "enable-skill-plan-preFinals",
    },
    {
        id: "exclude-red-skills-SkillPlanSettingsPreFinals",
        title: "Skip All Red Skills (Debuffs)",
        description: "Exclude red debuff skills",
        page: "Skills",
        parentId: "enable-skill-plan-preFinals",
    },
    {
        id: "exclude-unique-skills-SkillPlanSettingsPreFinals",
        title: "Skip All Unique Skills",
        description: "Exclude inherited unique (legacy) skills",
        page: "Skills",
        parentId: "enable-skill-plan-preFinals",
    },
    {
        id: "exclude-double-circle-skills-SkillPlanSettingsPreFinals",
        title: "Skip All Double-O (Circle) Skills",
        description: "Only buy the single-circle version; skip the double-circle upgrade",
        page: "Skills",
        parentId: "enable-skill-plan-preFinals",
    },

    // ============================================================
    // Skill Plan Settings — Career Complete
    // ============================================================
    {
        id: "enable-skill-plan-careerComplete",
        title: "Enable Career Complete Plan",
        description: "Purchase skills based on this plan's configuration",
        page: "Skills",
    },
    {
        id: "enable-buy-negative-skills-SkillPlanSettingsCareerComplete",
        title: "Purchase All Negative Skills",
        description: "Attempt to buy all negative skills (e.g. Firm Conditions x)",
        page: "Skills",
        parentId: "enable-skill-plan-careerComplete",
    },
    {
        id: "exclude-green-skills-SkillPlanSettingsCareerComplete",
        title: "Skip All Green Skills",
        description: "Exclude green stat-trigger skills",
        page: "Skills",
        parentId: "enable-skill-plan-careerComplete",
    },
    {
        id: "exclude-red-skills-SkillPlanSettingsCareerComplete",
        title: "Skip All Red Skills (Debuffs)",
        description: "Exclude red debuff skills",
        page: "Skills",
        parentId: "enable-skill-plan-careerComplete",
    },
    {
        id: "exclude-unique-skills-SkillPlanSettingsCareerComplete",
        title: "Skip All Unique Skills",
        description: "Exclude inherited unique (legacy) skills",
        page: "Skills",
        parentId: "enable-skill-plan-careerComplete",
    },
    {
        id: "exclude-double-circle-skills-SkillPlanSettingsCareerComplete",
        title: "Skip All Double-O (Circle) Skills",
        description: "Only buy the single-circle version; skip the double-circle upgrade",
        page: "Skills",
        parentId: "enable-skill-plan-careerComplete",
    },

    // ============================================================
    // Scenario Overrides Settings
    // ============================================================
    {
        id: "trackblazer-consecutive-races-limit",
        title: "Trackblazer Consecutive Races Limit",
        description:
            "Sets the maximum number of consecutive races the bot is allowed to run in the Trackblazer scenario before stopping. Note that a -30 stat penalty can apply starting from 3 consecutive races.",
        page: "ScenarioOverridesSettings",
    },
    {
        id: "trackblazer-ignore-low-energy-racing-block",
        title: "Trackblazer Ignore Low Energy Racing Block",
        description: "When enabled, the Trackblazer bot will not block racing when energy is critically low (<=1%) with 3+ consecutive races.",
        page: "ScenarioOverridesSettings",
    },
    {
        id: "unity-cup-burst-max-failure-chance",
        title: "Unity Cup Burst Failure-Chance Exemption",
        description: "Allow a training with a Spirit Explosion gauge ready to burst up to this failure chance before it is skipped. 0 disables the exemption and uses the normal failure limit.",
        page: "ScenarioOverridesSettings",
    },
    {
        id: "unity-cup-pre-race-stat-focus",
        title: "Unity Cup Pre-Race Stat Focus",
        description: "In the two turns before a Unity Cup team race (Late June / Late December), stop rewarding slow gauge-filling so stat gains decide the turn. Bursts are still prioritized.",
        page: "ScenarioOverridesSettings",
    },
    {
        id: "trackblazer-energy-threshold",
        title: "Trackblazer Energy Threshold",
        description: "The energy level below which the bot will attempt to use energy-restoring items in the Trackblazer scenario.",
        page: "ScenarioOverridesSettings",
    },
    {
        id: "trackblazer-force-train-energy-floor",
        title: "Trackblazer Force-Train Energy Floor (Summer/Finale)",
        description:
            "During Summer and the Finale, the Trackblazer scenario normally always picks training. If energy is at or below this floor, the bot skips that override and falls through to the standard decision flow so items are not queued for a training with little hope for success.",
        page: "ScenarioOverridesSettings",
    },
    {
        id: "trackblazer-shop-check-grades",
        title: "Trackblazer Shop Check Grades",
        description: "Select which race grades should trigger a shop check after the race in the Trackblazer scenario.",
        page: "ScenarioOverridesSettings",
    },
    {
        id: "trackblazer-skip-risky-charm-training-below-gain",
        title: "Trackblazer Skip Risky Charm Training Below Main Stat Gain",
        description:
            "When a Good-Luck Charm is available to override a risky training's failure chance, skip that training anyway if its main stat gain is below this value. Prevents committing the Charm to low-value risky picks.",
        page: "ScenarioOverridesSettings",
    },
    {
        id: "trackblazer-skip-bad-mood-items-below-gain",
        title: "Trackblazer Skip Items During Bad Mood Below Main Stat Gain",
        description:
            "When mood is BAD or AWFUL, refuse to use Reset Whistle / Good-Luck Charm / Megaphone if the selected training's main stat gain is below this floor. Prevents wasting items on structurally low-return turns where the mood multiplier caps the stat gains.",
        page: "ScenarioOverridesSettings",
    },
    {
        id: "trackblazer-skip-empowering-megaphone-below-gain",
        title: "Trackblazer Skip Empowering Megaphone Below Main Stat Gain",
        description:
            "Skip the Empowering Megaphone (+60% for 2 turns) when the selected training's main stat gain is below this value, falling through to a lower tier whose threshold is met. 0 = always allowed.",
        page: "ScenarioOverridesSettings",
    },
    {
        id: "trackblazer-skip-motivating-megaphone-below-gain",
        title: "Trackblazer Skip Motivating Megaphone Below Main Stat Gain",
        description:
            "Skip the Motivating Megaphone (+40% for 3 turns) when the selected training's main stat gain is below this value, falling through to a lower tier whose threshold is met. 0 = always allowed.",
        page: "ScenarioOverridesSettings",
    },
    {
        id: "trackblazer-skip-coaching-megaphone-below-gain",
        title: "Trackblazer Skip Coaching Megaphone Below Main Stat Gain",
        description: "Skip the Coaching Megaphone (+20% for 4 turns) when the selected training's main stat gain is below this value. 0 = always allowed.",
        page: "ScenarioOverridesSettings",
    },
    {
        id: "trackblazer-max-retries-per-race",
        title: "Trackblazer Max Retries per Race",
        description: "The maximum number of times the bot will attempt to retry a failed race in the Trackblazer scenario.",
        page: "ScenarioOverridesSettings",
    },
    {
        id: "trackblazer-whistle-forces-training",
        title: "Trackblazer Reset Whistle Forces Training",
        description:
            "Whether or not using a Reset Whistle means it can ignore the failure chance thresholds in the Training Settings page. If enabled, the bot will pick the best available training after usage even if it's risky.",
        page: "ScenarioOverridesSettings",
    },
    {
        id: "trackblazer-retry-races-before-final-grades",
        title: "Trackblazer Race Grades to use Race Retries on",
        description: "Select which race grades should allow using a Race Retry in the Trackblazer scenario.",
        page: "ScenarioOverridesSettings",
    },
    {
        id: "trackblazer-enable-irregular-training",
        title: "Trackblazer Enable Irregular Training",
        description: "When enabled, the bot will occasionally check for highly profitable training sessions before opting for extra races.",
        page: "ScenarioOverridesSettings",
    },
    {
        id: "trackblazer-irregular-training-min-stat-gain",
        title: "Trackblazer Irregular Training Minimum Stat Gain",
        description: "Sets the minimum main stat gain required to skip racing and perform Irregular Training instead.",
        page: "ScenarioOverridesSettings",
    },
    {
        id: "trackblazer-excluded-items",
        title: "Trackblazer Items to Exclude from Shop",
        description: "Select items that the bot will never purchase from the shop in the Trackblazer scenario.",
        page: "ScenarioOverridesSettings",
    },
    {
        id: "trackblazer-shop-check-frequency",
        title: "Trackblazer Shop Check Frequency",
        description: "Sets the frequency of shop checks after races in the Trackblazer scenario. 1 = every race, 2 = 1 day after, 3 = 2 days after, etc.",
        page: "ScenarioOverridesSettings",
    },
    {
        id: "trackblazer-preferred-distances",
        title: "Trackblazer Preferred Track Distances",
        description: "Select preferred track distances for extra race selection. Matching races will be prioritized. Leave empty for no preference.",
        page: "ScenarioOverridesSettings",
    },
    {
        id: "trackblazer-preferred-surfaces",
        title: "Trackblazer Preferred Track Surfaces",
        description: "Select preferred track surfaces for extra race selection. Matching races will be prioritized. Leave empty for no preference.",
        page: "ScenarioOverridesSettings",
    },
    {
        id: "trackblazer-energy-item-reserve",
        title: "Trackblazer Energy Item Emergency Reserve",
        description: "Number of energy items (lowest-tier first) to keep reserved for emergency race recovery when energy hits 1% or below with 3+ consecutive races.",
        page: "ScenarioOverridesSettings",
    },
    {
        id: "trackblazer-cupcake-reserve",
        title: "Trackblazer Cupcake Reserve for Kale Juice Synergy",
        description: "Number of cupcakes (Plain preferred) to keep so the mood penalty from Royal Kale Juice can be offset.",
        page: "ScenarioOverridesSettings",
    },
    {
        id: "trackblazer-master-hammer-finale-reserve",
        title: "Trackblazer Master Cleat Hammer Finale Reserve",
        description: "Master Cleat Hammers held back for the Finale days (73-75). Pre-finale days only spend the surplus above this reserve, and only on G1/G2 races.",
        page: "ScenarioOverridesSettings",
    },
    {
        id: "trackblazer-artisan-hammer-min-stock-for-g3",
        title: "Trackblazer Artisan Hammer Min Stock for G3",
        description: "Minimum Artisan Cleat Hammer inventory before the bot is allowed to spend one on a G3 race.",
        page: "ScenarioOverridesSettings",
    },
    {
        id: "trackblazer-artisan-hammer-min-stock-for-g2",
        title: "Trackblazer Artisan Hammer Min Stock for G2",
        description: "Minimum Artisan Cleat Hammer inventory before the bot is allowed to spend one on a G2 race. G1 is always allowed.",
        page: "ScenarioOverridesSettings",
    },
    {
        id: "trackblazer-glow-stick-final-reserve",
        title: "Trackblazer Glow Stick Final-Day Reserve",
        description: "Glow Sticks held back for Day 75 (the Final). Pre-final-day races only spend sticks above this reserve.",
        page: "ScenarioOverridesSettings",
    },
    {
        id: "trackblazer-glow-stick-min-fans",
        title: "Trackblazer Glow Stick Minimum Fans",
        description: "Minimum projected fan gain on a race before the bot uses a Glow Stick on it. Applies on standard and finale days.",
        page: "ScenarioOverridesSettings",
    },

    // ============================================================
    // Discord Settings
    // ============================================================
    {
        id: "enableDiscordNotifications",
        title: "Enable Discord Notifications",
        description: "DM run results when the bot stops",
        page: "DiscordSettings",
    },
    {
        id: "discordBotToken",
        title: "Discord Bot Token",
        description: "The token generated from the Discord Developer Portal. Your Discord bot must share a server with you.",
        page: "DiscordSettings",
        parentId: "enableDiscordNotifications",
    },
    {
        id: "discordUserID",
        title: "Discord User ID",
        description: "Your Discord user ID. Enable Developer Mode in Discord settings, then click your name and select 'Copy User ID'.",
        page: "DiscordSettings",
        parentId: "enableDiscordNotifications",
    },

    // ============================================================
    // Debug Settings
    // ============================================================
    {
        id: "enable-debug-mode",
        title: "Enable Debug Mode",
        description: "Allows debugging messages in the log and test images to be created in the /temp/ folder.",
        page: "DebugSettings",
    },
    {
        id: "template-match-confidence",
        title: "Adjust Confidence for Template Matching",
        description:
            "Sets the minimum confidence level for template matching with 1080p as the baseline. Consider lowering this to something like 0.7 or 70% at lower resolutions. Making it too low will cause the bot to match on too many things as false positives.",
        page: "DebugSettings",
    },
    {
        id: "template-match-custom-scale",
        title: "Set the Custom Image Scale for Template Matching",
        description:
            "Manually set the scale to do template matching. The Basic Template Matching Test can help find your recommended scale. Making it too low or too high will cause the bot to match on too little or too many things as false positives.",
        page: "DebugSettings",
    },
    {
        id: "enable-screen-recording",
        title: "Enable Screen Recording",
        description:
            "Records the screen while the bot is running. The mp4 file will be saved to the /recordings folder of the app's data directory. Note that performance and battery life may be impacted while recording.",
        page: "DebugSettings",
    },
    {
        id: "recording-bit-rate",
        title: "Recording Quality (Bit Rate)",
        description: "Sets the video bit rate for screen recording. Higher values produce better quality but larger file sizes.",
        page: "DebugSettings",
        parentId: "enable-screen-recording",
    },
    {
        id: "recording-frame-rate",
        title: "Recording Frame Rate",
        description: "Sets the frame rate for screen recording.",
        page: "DebugSettings",
        parentId: "enable-screen-recording",
    },
    {
        id: "recording-resolution-scale",
        title: "Recording Resolution Scale",
        description: "Scales the recording resolution. Lower values produce smaller file sizes but lower quality. 1.0 = full resolution, 0.5 = half resolution.",
        page: "DebugSettings",
        parentId: "enable-screen-recording",
    },
    {
        id: "debug-accessibility-service-check",
        title: "Accessibility Service Check",
        description: "The Accessibility Service allows the bot to perform clicks and gestures on your behalf. Check the current registration and initialization status here.",
        page: "DebugSettings",
    },
    {
        id: "debug-overlay-permission-check",
        title: "Overlay Permission Check",
        description: "The Overlay (Display over other apps) permission is required for the bot to render its on-screen control overlay. Check the current grant status here.",
        page: "DebugSettings",
    },
    {
        id: "debug-battery-optimization-check",
        title: "Battery Optimization Check",
        description: "Disabling battery optimization for this app prevents Android from killing the bot during long-running automation runs. Check the current status here.",
        page: "DebugSettings",
    },
    {
        id: "debug-template-matching-test",
        title: "Start Basic Template Matching Test",
        description:
            "Disables normal bot operations and starts the template match test. Only on the Home screen and will check if it can find certain essential buttons on the screen. It will also output what scale it had the most success with.",
        page: "DebugSettings",
    },
    {
        id: "debug-single-training-ocr-test",
        title: "Start Single Training OCR Test",
        description:
            "Disables normal bot operations and starts the single training OCR test. Only on the Training screen and tests the current training on display for stat gains and failure chances.",
        page: "DebugSettings",
    },
    {
        id: "debug-comprehensive-training-ocr-test",
        title: "Start Comprehensive Training OCR Test",
        description: "Disables normal bot operations and starts the comprehensive training OCR test. Only on the Training screen and tests all 5 trainings for their stat gains and failure chances.",
        page: "DebugSettings",
    },
    {
        id: "debug-race-list-detection-test",
        title: "Start Race List Detection Test",
        description:
            "Disables normal bot operations and starts the Race List detection test. Only on the Race List screen and tests detecting the races with double star predictions currently on display.",
        page: "DebugSettings",
    },
    {
        id: "debug-main-screen-update-test",
        title: "Start Main Screen Update Test",
        description: "Disables normal bot operations and starts the Main Screen update test. This test will go through all Main Screen updates and then print the Trainee information.",
        page: "DebugSettings",
    },
    {
        id: "debug-skill-list-buy-test",
        title: "Start Skill List Buy Test",
        description:
            "Processes the list of skills in the Skills screen, reads all skills in the list, logs a summary and then logs another summary of which skills it will buy to bring down the current Skill Points as close to zero as possible and then it will stop there without actually doing the buying.",
        page: "DebugSettings",
    },
    {
        id: "debug-scrollbar-detection-test",
        title: "Start Scrollbar Detection Test",
        description:
            "Disables normal bot operations and starts the Scrollbar detection test. Detects the scrollbar on the current screen and attempts to scroll it up and down to verify functionality.",
        page: "DebugSettings",
    },
    {
        id: "debug-trackblazer-race-selection-test",
        title: "Start Trackblazer Race Selection Test",
        description:
            "Disables normal bot operations and starts the Trackblazer race selection test. Navigates to the Race List if on the Main Screen and identifies the best race to run, including Rivals.",
        page: "DebugSettings",
    },
    {
        id: "debug-trackblazer-inventory-sync-test",
        title: "Start Trackblazer Inventory Sync Test",
        description:
            "Disables normal bot operations and starts the Trackblazer inventory sync test. Opens the Training Items dialog if on the Main Screen and logs inventory contents and quick-use intentions.",
        page: "DebugSettings",
    },
    {
        id: "debug-trackblazer-buy-items-test",
        title: "Start Trackblazer Buy Items Test",
        description:
            "Disables normal bot operations and starts the Trackblazer buy items test. Opens the Shop if on the Main Screen and logs shop contents and purchase intentions without actually buying anything.",
        page: "DebugSettings",
    },
    {
        id: "dump-logcat",
        title: "Dump logcat (last 6h)",
        description: "Saves this app's recent logcat output to a timestamped .txt file at the root of your storage folder.",
        page: "DebugSettings",
    },
    {
        id: "llm-ask-the-docs",
        title: "Ask the Docs",
        description:
            "On-device documentation chatbot. Answers are grounded in README.md, HOW_IT_WORKS.md, in-app option descriptions, and Kotlin source code, retrieved via MiniLM embeddings and cosine similarity. Fully offline.",
        page: "Chat",
    },
    {
        id: "llm-enable-ask-the-docs",
        title: "Enable Ask the Docs feature",
        description: "Show the Ask the Docs page in the navigation drawer and reveal the rest of these LLM options. Off by default.",
        page: "LLMSettings",
    },
    {
        id: "llm-device-fitness",
        title: "Device Fitness",
        description:
            "Diagnostic row showing total/available RAM, the CPU-feature acceleration tier llama.rn picked (v8 baseline vs v8.2+dotprod), and the recommended chat-model preset based on free RAM. Driven by `LLMChatModule.getDeviceCapabilities()`.",
        page: "LLMSettings",
    },
    {
        id: "llm-embedder-engine",
        title: "Ask the Docs Engine",
        description:
            "On-demand MiniLM embedder (~22 MB) downloaded from Hugging Face. Required for both retrieve-only search and the chat model. Kept out of the APK so users who never enable Ask the Docs don't pay the bytes; downloaded once and cached locally.",
        page: "LLMSettings",
    },
    {
        id: "llm-embedder-delete",
        title: "Delete Ask the Docs Engine",
        description: "Removes the downloaded MiniLM embedder ONNX from disk to reclaim ~22 MB. The engine can be re-downloaded any time.",
        page: "LLMSettings",
    },
    {
        id: "llm-model-url",
        title: "Chat Model URL",
        description: "Hugging Face URL of the GGUF chat model to download. Pick a preset (Qwen 2.5, etc.) or choose Custom and paste any GGUF model URL.",
        page: "LLMSettings",
    },
    {
        id: "llm-hf-token",
        title: "Hugging Face Access Token",
        description: "Optional bearer token sent with the model download request. Required for gated repos. Persisted under the chat category so it is never included in settings exports.",
        page: "LLMSettings",
    },
    {
        id: "llm-active-model",
        title: "Active Chat Model",
        description:
            "Which downloaded GGUF model the chatbot uses for generation. Tap a downloaded model row to mark it active; falls back to the most recently downloaded one when no explicit selection is set.",
        page: "LLMSettings",
    },
    {
        id: "llm-download-chat-model",
        title: "Download Chat Model",
        description: "Downloads the selected GGUF chat model file (Qwen 2.5 1.5B Instruct ~1.1 GB by default) for llama.rn generation. Stored in app-private storage; can be deleted later.",
        page: "LLMSettings",
    },
    {
        id: "llm-delete-chat-model",
        title: "Delete Chat Model",
        description: "Removes a downloaded chat model from disk to free space. Retrieve-only search continues to work without any model present.",
        page: "LLMSettings",
    },
    {
        id: "llm-max-output-tokens",
        title: "Max output tokens",
        description: "Upper bound on chatbot answer length. Higher values produce longer, more thorough answers but slow generation noticeably on phones above ~1024.",
        page: "LLMSettings",
    },
    {
        id: "llm-citation-char-cap",
        title: "Context per citation (chars)",
        description:
            "How much of each retrieved doc section is fed to the chatbot LLM. Larger caps give the model more material to summarize from but consume more of the model's context window budget.",
        page: "LLMSettings",
    },
    {
        id: "llm-model-context-window",
        title: "Model context window (tokens)",
        description: "Engine KV-cache size for the chatbot LLM. 4096 tokens is the default; raising it requires the loaded model to support it. Changing this reloads the model on the next chat call.",
        page: "LLMSettings",
    },
]

export default searchConfig
