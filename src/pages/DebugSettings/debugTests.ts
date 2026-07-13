/** Descriptor for a single diagnostic test surfaced in the Debug Tests section. Drives the mutually-exclusive Switch rows. */
export interface DebugTestDescriptor {
    /** Settings key on `debug`. */
    key:
        | "debugMode_startTemplateMatchingTest"
        | "debugMode_startSingleTrainingOCRTest"
        | "debugMode_startComprehensiveTrainingOCRTest"
        | "debugMode_startRaceListDetectionTest"
        | "debugMode_startMainScreenUpdateTest"
        | "debugMode_startSkillListBuyTest"
        | "debugMode_startScrollBarDetectionTest"
        | "debugMode_startRainbowDetectionTest"
        | "debugMode_startSpiritGaugeDetectionTest"
        | "debugMode_startTrackblazerRaceSelectionTest"
        | "debugMode_startTrackblazerInventorySyncTest"
        | "debugMode_startTrackblazerBuyItemsTest"
    /** Stable id used for search registration and hero-chip deep-link highlighting. */
    searchId: string
    /** Visible Row title. */
    title: string
    /** Row description. */
    description: string
}

export const DEBUG_TESTS: DebugTestDescriptor[] = [
    {
        key: "debugMode_startTemplateMatchingTest",
        searchId: "debug-template-matching-test",
        title: "Start Basic Template Matching Test",
        description:
            "Disables normal bot operations and starts the template match test. Only on the Home screen and will check if it can find certain essential buttons on the screen. It will also output what scale it had the most success with.",
    },
    {
        key: "debugMode_startSingleTrainingOCRTest",
        searchId: "debug-single-training-ocr-test",
        title: "Start Single Training OCR Test",
        description:
            "Disables normal bot operations and starts the single training OCR test. Only on the Training screen and tests the current training on display for stat gains and failure chances.",
    },
    {
        key: "debugMode_startComprehensiveTrainingOCRTest",
        searchId: "debug-comprehensive-training-ocr-test",
        title: "Start Comprehensive Training OCR Test",
        description: "Disables normal bot operations and starts the comprehensive training OCR test. Only on the Training screen and tests all 5 trainings for their stat gains and failure chances.",
    },
    {
        key: "debugMode_startRaceListDetectionTest",
        searchId: "debug-race-list-detection-test",
        title: "Start Race List Detection Test",
        description:
            "Disables normal bot operations and starts the Race List detection test. Only on the Race List screen and tests detecting the races with double star predictions currently on display.",
    },
    {
        key: "debugMode_startMainScreenUpdateTest",
        searchId: "debug-main-screen-update-test",
        title: "Start Main Screen Update Test",
        description: "Disables normal bot operations and starts the Main Screen update test. This test will go through all Main Screen updates and then print the Trainee information.",
    },
    {
        key: "debugMode_startSkillListBuyTest",
        searchId: "debug-skill-list-buy-test",
        title: "Start Skill List Buy Test",
        description:
            "Processes the list of skills in the Skills screen, reads all skills in the list, logs a summary and then logs another summary of which skills it will buy to bring down the current Skill Points as close to zero as possible and then it will stop there without actually doing the buying.",
    },
    {
        key: "debugMode_startScrollBarDetectionTest",
        searchId: "debug-scrollbar-detection-test",
        title: "Start Scrollbar Detection Test",
        description:
            "Disables normal bot operations and starts the Scrollbar detection test. Detects the list on the current screen, whether it is a full-screen list or one drawn inside a dialog, and reports its scrollbar. It then checks whether that scrollbar can actually be dragged to scroll, since some lists draw one that only reports the position, and finally scrolls the list up and down to verify functionality.",
    },
    {
        key: "debugMode_startRainbowDetectionTest",
        searchId: "debug-rainbow-detection-test",
        title: "Start Rainbow Detection Test",
        description:
            "Disables normal bot operations and starts the Rainbow detection test. Run this on the Training screen: it detects the rainbow glow ring on each support face circle for a few seconds, logs the per-support metrics and the derived rainbow count, and saves an annotated crop to help calibrate the detector.",
    },
    {
        key: "debugMode_startSpiritGaugeDetectionTest",
        searchId: "debug-spirit-gauge-detection-test",
        title: "Start Spirit Gauge Detection Test",
        description:
            "Disables normal bot operations and starts the Unity Cup Spirit Gauge detection test. Run this on the Training screen: it selects each of the five facilities in turn and logs the detected fillable / burst / extreme spirit gauge counts, saving the gauge crops to help verify detection.",
    },
    {
        key: "debugMode_startTrackblazerRaceSelectionTest",
        searchId: "debug-trackblazer-race-selection-test",
        title: "Start Trackblazer Race Selection Test",
        description:
            "Disables normal bot operations and starts the Trackblazer race selection test. Navigates to the Race List if on the Main Screen and identifies the best race to run, including Rivals.",
    },
    {
        key: "debugMode_startTrackblazerInventorySyncTest",
        searchId: "debug-trackblazer-inventory-sync-test",
        title: "Start Trackblazer Inventory Sync Test",
        description:
            "Disables normal bot operations and starts the Trackblazer inventory sync test. Opens the Training Items dialog if on the Main Screen and logs inventory contents and quick-use intentions.",
    },
    {
        key: "debugMode_startTrackblazerBuyItemsTest",
        searchId: "debug-trackblazer-buy-items-test",
        title: "Start Trackblazer Buy Items Test",
        description:
            "Disables normal bot operations and starts the Trackblazer buy items test. Opens the Shop if on the Main Screen and logs shop contents and purchase intentions without actually buying anything.",
    },
]
