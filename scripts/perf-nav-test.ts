/**
 * Scripted navigation-latency check. Drives the connected emulator over `adb`, taps a sequence
 * of drawer links, then captures the resulting `[PERF]` and `[BLOCK]` logcat lines and asserts
 * each navigation phase finishes under a configured budget.
 *
 * Run via: `yarn perf:nav` (see package.json) or `tsx scripts/perf-nav-test.ts`.
 *
 * The script is intentionally narrow:
 *   1. Force-stop and relaunch the app so we measure cold mounts of each page.
 *   2. For each scenario, open the drawer (swipe-from-left), tap the named row, wait, capture logs.
 *   3. Parse `[PERF] UI - navigation_to_<route>_<phase>: <ms>ms` lines and the cumulative
 *      `navigation_to_<route>: <ms>ms` line. Print a summary and exit non-zero if any number
 *      exceeds the budget below.
 *
 * The script is *not* a substitute for a manual perception check — but it gives a stable number
 * we can put on regressions.
 */

import { execSync } from "child_process"

const DEVICE = process.env.ANDROID_DEVICE ?? "192.168.0.102:5555"
const PACKAGE = "com.steve1316.uma_android_automation"
const ACTIVITY = "com.steve1316.uma_android_automation/.MainActivity"

// Tap coordinates for drawer-row labels. Calibrated via `adb shell uiautomator dump` on the
// user's emulator (1080x1920 portrait, drawer width 280dp). Each (tapX, tapY) is the *centre of
// the row's text label* — RN's TouchableOpacity rows aren't reported as clickable to
// `uiautomator`, so we can't auto-discover them; recapture with the helper at the bottom of
// this file if the layout shifts.
//
// `expandTapX/Y`, when set, performs a pre-tap (the chevron next to the parent row) before the
// main row tap. Used for nested routes (Smart Race Solver lives under Racing Settings — tapping
// the Racing label navigates instead of expanding, so we tap the chevron at its right edge).
/**
 * One drawer-driven navigation scenario for the harness to exercise.
 */
interface NavScenario {
    /** Human-readable label printed in the harness summary. */
    name: string
    /** Route name as it appears in `[PERF] UI - navigation_to_<route>` log lines. */
    route: string
    /** X-coordinate (px) for the drawer-row tap that triggers navigation. */
    tapX: number
    /** Y-coordinate (px) for the drawer-row tap that triggers navigation. */
    tapY: number
    /** Optional X-coordinate of a chevron tap to expand a parent row before navigating. */
    expandTapX?: number
    /** Optional Y-coordinate of a chevron tap to expand a parent row before navigating. */
    expandTapY?: number
    /**
     * Coordinate of a known checkbox on the destination page. When set, the harness
     * waits for the page to settle, taps the checkbox, and measures `[BLOCK]` events for
     * `TOGGLE_CAPTURE_MS` to surface re-render fan-out cost on already-mounted pages.
     */
    toggleTapX?: number
    /** Companion Y-coordinate to `toggleTapX`. */
    toggleTapY?: number
    /**
     * Sub-nested route name as it appears in `[PERF] UI - navigation_to_<subRoute>` log lines.
     * When set, the harness performs a second navigation hop after the parent page settles:
     * scroll the parent `subScrolls` times, tap `(subTapX, subTapY)` (the in-page link), and
     * measure the sub-route's mount as if it were a fresh cold-nav. The parent's first nav
     * still runs but its metrics are reported as warm-up for the sub-route's budget check.
     */
    subRoute?: string
    /** Number of swipe-up gestures to perform on the parent page before tapping the sub-link. */
    subScrolls?: number
    /** X-coordinate (px) of the in-page link to the sub-nested route. */
    subTapX?: number
    /** Y-coordinate (px) of the in-page link to the sub-nested route. */
    subTapY?: number
}

// Coordinates calibrated with `enableAskTheDocs=true` (Chat row inserted between
// Home and Settings; pushes everything below it down ~91 px). If that toggle is later disabled,
// re-dump and shift these up.
const SCENARIOS: NavScenario[] = [
    { name: "Settings", route: "Settings", tapX: 213, tapY: 479, toggleTapX: 200, toggleTapY: 1670 },
    // Settings expands by default; nested rows start at y=565 with a vertical stride of ~89 px.
    { name: "Training Settings", route: "TrainingSettings", tapX: 255, tapY: 565 },
    { name: "Training Event Settings", route: "TrainingEventSettings", tapX: 280, tapY: 652 },
    { name: "Racing Settings", route: "RacingSettings", tapX: 229, tapY: 744 },
    { name: "Skill Settings", route: "SkillSettings", tapX: 220, tapY: 826 },
    { name: "Event Log Visualizer", route: "EventLogVisualizer", tapX: 250, tapY: 904 },
    { name: "Discord Settings", route: "DiscordSettings", tapX: 220, tapY: 978 },
    { name: "Scenario Overrides Settings", route: "ScenarioOverridesSettings", tapX: 290, tapY: 1065 },
    { name: "Debug Settings", route: "DebugSettings", tapX: 220, tapY: 1153 },
    { name: "LLM Settings", route: "LLMSettings", tapX: 220, tapY: 1227 },
    // Smart Race Solver lives nested under Racing Settings — tapping the Racing label
    // navigates instead of expanding, so we tap the chevron at its right edge first to expand
    // the group, then tap the SRS row that appears at y=820. The toggle target is the master
    // "Enable Smart Race Solver" checkbox at the top of the page; toggling it off + back on is
    // the most expensive interaction (unmount → remount of calendar grid) and is what the
    // earlier baseline reported as ~1.6 s of blocked time. Auto-expanding Racing here shifts
    // every drawer row below Racing Settings down ~71 px for the rest of the run, which is why
    // the Skill Plan parent below uses tapY=897 instead of the master baseline of 826.
    {
        name: "Smart Race Solver Settings",
        route: "ScheduleScreen",
        expandTapX: 346,
        expandTapY: 744,
        tapX: 220,
        tapY: 820,
        toggleTapX: 220,
        toggleTapY: 185,
    },
    // Sub-nested pages: navigate to the parent first, scroll to the in-page link, then tap. The
    // sub-link y-coords are the on-screen position *after* the configured number of swipe-ups
    // brings the link into view. Calibrated 2026-05-01 on the same emulator as the drawer rows.
    //
    // Order matters: navigating to a sub-route auto-expands the drawer to show that sub-route
    // as a nested row, which shifts every drawer row *below* its parent down by ~89 px. Run
    // Skill Plan first because Skill Settings (y=826) sits below Racing Settings (y=744) — the
    // shift only affects rows below Skill Settings (none of which we touch later in the run).
    // Reversing the order would shift Skill Settings out from under the Racing-Plan-iteration
    // tap.
    // The Skill Plan Settings page has 3 in-page entry links (Skill Point Check, Pre-Finals,
    // Career Complete) that mount the same component under distinct route names — we measure
    // the canonical "Skill Point Check" entry.
    {
        name: "Skill Plan Settings",
        route: "SkillSettings",
        tapX: 220,
        tapY: 897,
        subRoute: "SkillPlanSettingsSkillPointCheck",
        subScrolls: 4,
        subTapX: 540,
        subTapY: 1474,
    },
    {
        name: "Racing Plan Settings",
        route: "RacingSettings",
        tapX: 229,
        tapY: 744,
        subRoute: "RacingPlanSettings",
        subScrolls: 3,
        subTapX: 540,
        subTapY: 1800,
    },
]

// Per-phase budget (ms). Soft thresholds — we report breaches but don't fail the run unless the
// total exceeds [TOTAL_BUDGET_MS]. Tighten as we improve.
const PHASE_BUDGETS_MS: Record<string, number> = {
    drawer_closed: 100,
    dispatch: 250,
    first_commit: 800,
}
const TOTAL_BUDGET_MS = 1500

// How long after a toggle tap to keep listening for `[BLOCK]` / `[SLOW-COMMIT]` events. The
// reported blocked-time is the sum of every `[BLOCK]` line that fires inside this window.
const TOGGLE_CAPTURE_MS = 3500
const TOGGLE_BUDGET_MS = 100

// Cold-start budget: from `am force-stop` + `am start` to the first `Home_mount` log.
const COLD_START_BUDGET_MS = 3000

/**
 * One parsed `[PERF] UI - navigation_to_<route>_<phase>` sample.
 */
interface PhaseSample {
    /** Route name extracted from the log line. */
    route: string
    /** Phase name (e.g. `drawer_closed`, `dispatch`, `first_commit`). */
    phase: string
    /** Phase duration in milliseconds. */
    ms: number
}

/**
 * Run a shell command synchronously and return its stdout.
 * @param cmd - The full command line to execute.
 * @param opts - Options bag. `check` defaults to `true`; pass `false` to swallow non-zero exits and return an empty string. `timeoutMs` defaults to 15000.
 * @returns The captured stdout as a UTF-8 string.
 */
const sh = (cmd: string, opts: { check?: boolean; timeoutMs?: number } = {}): string => {
    try {
        return execSync(cmd, { encoding: "utf8", timeout: opts.timeoutMs ?? 15000, stdio: ["ignore", "pipe", "pipe"] })
    } catch (e) {
        if (opts.check === false) return ""
        throw e
    }
}

/**
 * Run an `adb -s <DEVICE> ...` command against the configured emulator/device.
 * @param args - Arguments to pass to `adb` (everything after `-s <DEVICE>`).
 * @param timeoutMs - Per-command timeout in milliseconds. Defaults to 15000.
 * @returns The combined stdout of the command.
 */
const adb = (args: string, timeoutMs = 15000): string => sh(`adb -s ${DEVICE} ${args}`, { timeoutMs })

/**
 * Promise-based sleep helper.
 * @param ms - Duration to sleep in milliseconds.
 * @returns A promise that resolves once the duration has elapsed.
 */
const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms))

/**
 * Capture `adb logcat` for `windowMs` milliseconds and return the collected lines. Uses
 * `adb logcat -c` then sleeps then `adb logcat -d` (one-shot) — simpler and less prone to
 * hung child processes than streaming `spawn`.
 *
 * @param windowMs - Capture duration in milliseconds.
 * @returns The combined stdout of `adb logcat -d` over the window.
 */
const captureLogcat = async (windowMs: number): Promise<string> => {
    adb("logcat -c", 10000)
    await sleep(windowMs)
    return adb('logcat -d -v brief ReactNativeJS:V "*:S"', 15000)
}

const PHASE_RE = /\[PERF\] UI - navigation_to_([A-Za-z]+)_([a-z_]+): ([\d.]+)ms/
const TOTAL_RE = /\[PERF\] UI - navigation_to_([A-Za-z]+): ([\d.]+)ms/
const BLOCK_RE = /\[BLOCK\] JS thread blocked for (\d+)ms/
const SLOW_COMMIT_RE = /\[SLOW-COMMIT\] (\S+) commit took (\d+)ms/
// Sub-routes are reached via in-page nav links rather than drawer rows, so they bypass
// `markNavigationStart` and never get the `navigation_to_<route>` total. The destination's
// `usePerformanceLogging` hook still fires `[PERF] UI - <component>_commit` on mount, and the
// `Details` payload carries the real first-commit duration under `duration_ms` — we use that
// as the sub-route's first-commit metric.
const COMMIT_DURATION_RE = /\[PERF\] UI - ([A-Za-z]+)_commit: [\d.]+ms \| Details: \{[^}]*"duration_ms":([\d.]+)/
// ScheduleScreen emits its preview-solver wall-clock via `console.log` rather than the
// `[PERF]` channel — the harness picks it up so we can track the bridge round-trip cost over
// time. Captured during the Schedule scenario's nav window because `previewSchedule` auto-fires on
// first mount when no cached preview exists.
const PREVIEW_RE = /\[SmartRaceSolver\] previewSchedule:end (\d+)ms/

/**
 * Aggregated parse result of one logcat capture window.
 */
interface Sample {
    /** Per-phase navigation samples extracted from `[PERF] UI - navigation_to_<route>_<phase>` lines. */
    phases: PhaseSample[]
    /** Cumulative `navigation_to_<route>` totals keyed by route. */
    totals: Map<string, number>
    /** Every `[BLOCK] JS thread blocked for <n>ms` event observed in the window. */
    blocks: number[]
    /** Every `[SLOW-COMMIT] <component> commit took <n>ms` event observed in the window. */
    slowCommits: Array<{ component: string; ms: number }>
}

/**
 * Parse a logcat dump into the typed event buckets the harness reports on.
 * @param logs - The raw stdout from `adb logcat -d`.
 * @returns A `Sample` containing every phase, total, block, and slow-commit event.
 */
const parseSamples = (logs: string): Sample => {
    const phases: PhaseSample[] = []
    const totals = new Map<string, number>()
    const blocks: number[] = []
    const slowCommits: Array<{ component: string; ms: number }> = []
    for (const line of logs.split("\n")) {
        const p = PHASE_RE.exec(line)
        if (p) {
            phases.push({ route: p[1], phase: p[2], ms: parseFloat(p[3]) })
            continue
        }
        const t = TOTAL_RE.exec(line)
        if (t) {
            totals.set(t[1], parseFloat(t[2]))
            continue
        }
        const b = BLOCK_RE.exec(line)
        if (b) blocks.push(parseInt(b[1], 10))
        const s = SLOW_COMMIT_RE.exec(line)
        if (s) slowCommits.push({ component: s[1], ms: parseInt(s[2], 10) })
    }
    return { phases, totals, blocks, slowCommits }
}

/**
 * Open the navigation drawer via an edge-swipe from `x=5` to `x=600`.
 * @returns Nothing; the swipe event is fire-and-forget.
 */
const openDrawer = () => {
    // Edge-swipe right from x=5 to x=600.
    adb("shell input swipe 5 600 600 600 80")
}

/**
 * Send a single `input tap` event to the device.
 * @param x - X-coordinate (px) of the tap.
 * @param y - Y-coordinate (px) of the tap.
 * @returns Nothing; the tap event is fire-and-forget.
 */
const tapAt = (x: number, y: number) => {
    adb(`shell input tap ${x} ${y}`)
}

/**
 * Aggregated metrics for one scenario run, used in the final summary table.
 */
interface ScenarioResult {
    /** Route name (matches `NavScenario.route`). */
    route: string
    /** Total `navigation_to_<route>` time in milliseconds, or `-1` if the log line never fired. */
    total: number
    /** Per-phase samples for this scenario. */
    phases: PhaseSample[]
    /** Every `[BLOCK]` event observed in the navigation window. */
    navBlocks: number[]
    /** Sum of every `[BLOCK]` event in the toggle window, or `null` if no toggle was scripted. */
    toggleBlockedMs: number | null
    /** Individual block events observed in the toggle window. */
    toggleBlocks: number[]
    /** Every `[SLOW-COMMIT]` event observed in the toggle window. */
    toggleSlowCommits: Array<{ component: string; ms: number }>
    /** Sub-route name (matches `NavScenario.subRoute`), or `null` if this scenario has no sub-hop. */
    subRoute: string | null
    /** Total `navigation_to_<subRoute>` time in milliseconds, or `-1` if the log line never fired. */
    subTotal: number
    /** Per-phase samples for the sub-route's mount. */
    subPhases: PhaseSample[]
    /**
     * Wall-clock duration of the most recent `previewSchedule` call observed in the nav window,
     * or `null` if the scenario doesn't trigger one. Currently only set for the Smart Race
     * Solver Settings scenario, where preview auto-fires on first mount.
     */
    previewMs: number | null
}

/**
 * Cold-start phase: force-stop the app, relaunch via `am start -W` (which blocks until the
 * launcher activity is displayed and reports `TotalTime`), then watch for the `Home_mount`
 * log so we measure JS-side readiness instead of just the activity-display point.
 *
 * @returns Cold-start time in ms, or `-1` if the `Home_mount` log never landed in the window.
 */
const measureColdStart = async (): Promise<number> => {
    console.log("\n=== Cold start ===")
    adb(`shell am force-stop ${PACKAGE}`)
    await sleep(500)
    adb("logcat -c", 10000)
    const t0 = Date.now()
    // `am start -W` waits for the activity to be displayed and prints `TotalTime` in ms.
    const startOut = adb(`shell am start -W -n ${ACTIVITY}`, 30000)
    const totalTimeMatch = /TotalTime: (\d+)/.exec(startOut)
    const activityDisplayMs = totalTimeMatch ? parseInt(totalTimeMatch[1], 10) : -1
    // Wait until either Home_mount lands or we hit a 10 s ceiling.
    let homeMountMs = -1
    for (let i = 0; i < 20; i++) {
        await sleep(500)
        const logs = adb('logcat -d -v brief ReactNativeJS:V "*:S"', 10000)
        if (/Home_mount/.test(logs)) {
            homeMountMs = Date.now() - t0
            break
        }
    }
    console.log(`  activity_display: ${activityDisplayMs}ms`)
    console.log(`  ${homeMountMs <= COLD_START_BUDGET_MS ? "✓" : "✗"} home_mount:      ${homeMountMs}ms (budget ${COLD_START_BUDGET_MS}ms)`)
    return homeMountMs
}

/**
 * Harness entrypoint. Runs the cold-start probe, then loops over every `NavScenario`,
 * captures cold-nav and (where wired) toggle metrics, prints a summary table, and exits
 * non-zero if any phase or toggle exceeds its configured budget.
 * @returns A promise that resolves once the harness has finished printing its summary.
 */
const main = async () => {
    console.log(`Targeting ${DEVICE} (${PACKAGE})`)
    const coldStartMs = await measureColdStart()
    // Give the post-mount cascade time to finish before we start probing nav.
    await sleep(3500)

    let anyBreach = coldStartMs < 0 || coldStartMs > COLD_START_BUDGET_MS
    const summary: ScenarioResult[] = []

    for (const sc of SCENARIOS) {
        console.log(`\n=== ${sc.name} ===`)
        // Re-open drawer fresh each time so the prior nav doesn't bleed into the next.
        openDrawer()
        await sleep(700)

        // Some scenarios live behind a chevron expansion. Tap the chevron first and let the
        // disclosure animation settle before the actual nav tap.
        if (sc.expandTapX != null && sc.expandTapY != null) {
            tapAt(sc.expandTapX, sc.expandTapY)
            await sleep(700)
        }

        // SRS auto-fires `previewSchedule` on first mount, which can take up to ~1.5 s after
        // the first commit; widen the nav window for that scenario only so the `previewSchedule:end`
        // log lands inside the capture.
        const navWindowMs = sc.route === "ScheduleScreen" ? 6000 : 4000
        const logsPromise = captureLogcat(navWindowMs)
        tapAt(sc.tapX, sc.tapY)
        const navLogs = await logsPromise

        const { phases, totals, blocks: navBlocks } = parseSamples(navLogs)
        let previewMs: number | null = null
        for (const line of navLogs.split("\n")) {
            const m = PREVIEW_RE.exec(line)
            if (m) previewMs = parseInt(m[1], 10)
        }
        if (previewMs != null) {
            console.log(`  preview_ms: ${previewMs}ms (previewSchedule wall-clock)`)
        }
        const total = totals.get(sc.route) ?? -1
        const routePhases = phases.filter((p) => p.route === sc.route)

        console.log(`  total: ${total}ms (budget ${TOTAL_BUDGET_MS}ms)`)
        for (const p of routePhases) {
            const budget = PHASE_BUDGETS_MS[p.phase]
            const tag = budget != null && p.ms > budget ? "  ✗" : "  ✓"
            console.log(`${tag} ${p.phase.padEnd(16)} ${p.ms.toFixed(0)}ms${budget != null ? ` (budget ${budget}ms)` : ""}`)
            if (budget != null && p.ms > budget) anyBreach = true
        }
        if (navBlocks.length > 0) {
            console.log(`  nav blocks: ${navBlocks.join("ms, ")}ms`)
        }
        if (total > TOTAL_BUDGET_MS) anyBreach = true

        // Toggle phase. Wait for the deferred-render cascade to settle, then tap the known
        // checkbox and re-capture. Sum every `[BLOCK]` event that lands in the window.
        let toggleBlockedMs: number | null = null
        let toggleBlocks: number[] = []
        let toggleSlowCommits: Array<{ component: string; ms: number }> = []
        if (sc.toggleTapX != null && sc.toggleTapY != null) {
            // Let any deferred-render cascade settle so its blocks don't get attributed to the
            // toggle.
            await sleep(2500)
            const tLogsPromise = captureLogcat(TOGGLE_CAPTURE_MS)
            tapAt(sc.toggleTapX, sc.toggleTapY)
            const tLogs = await tLogsPromise
            const tParsed = parseSamples(tLogs)
            toggleBlocks = tParsed.blocks
            toggleSlowCommits = tParsed.slowCommits
            toggleBlockedMs = toggleBlocks.reduce((a, b) => a + b, 0)
            const tag = toggleBlockedMs <= TOGGLE_BUDGET_MS ? "  ✓" : "  ✗"
            console.log(`${tag} toggle blocked   ${toggleBlockedMs}ms (budget ${TOGGLE_BUDGET_MS}ms)`)
            if (toggleBlocks.length > 0) console.log(`    block events: ${toggleBlocks.join("ms, ")}ms`)
            if (toggleSlowCommits.length > 0) {
                const top = toggleSlowCommits.sort((a, b) => b.ms - a.ms).slice(0, 5)
                console.log(`    slow commits: ${top.map((c) => `${c.component}=${c.ms}ms`).join(", ")}`)
            }
            if (toggleBlockedMs > TOGGLE_BUDGET_MS) anyBreach = true
            // Toggle it back so subsequent runs don't drift the user's settings forever.
            tapAt(sc.toggleTapX, sc.toggleTapY)
            await sleep(800)
        }

        // Sub-route phase. Treat the parent page as already-warm: scroll its content the
        // configured number of times to bring the in-page link into view, then tap it and
        // measure the sub-route's commit-phase duration. In-page nav links bypass
        // `markNavigationStart`, so we use the destination's `<Route>_commit` `duration_ms`
        // as the first-commit metric (see `COMMIT_DURATION_RE`).
        let subTotal = -1
        let subPhases: PhaseSample[] = []
        if (sc.subRoute && sc.subTapX != null && sc.subTapY != null) {
            // Settle any deferred-render cascade on the parent page first.
            await sleep(2500)
            const scrolls = sc.subScrolls ?? 0
            for (let i = 0; i < scrolls; i++) {
                adb("shell input swipe 540 1500 540 200 100")
                await sleep(400)
            }
            const subLogsPromise = captureLogcat(4000)
            tapAt(sc.subTapX, sc.subTapY)
            const subLogs = await subLogsPromise
            for (const line of subLogs.split("\n")) {
                const m = COMMIT_DURATION_RE.exec(line)
                if (m && m[1] === sc.subRoute) {
                    const ms = parseFloat(m[2])
                    subTotal = ms
                    subPhases = [{ route: sc.subRoute, phase: "first_commit", ms }]
                    break
                }
            }
            const firstCommitBudget = PHASE_BUDGETS_MS["first_commit"] ?? TOTAL_BUDGET_MS
            const tag = subTotal > 0 && subTotal <= firstCommitBudget ? "  ✓" : "  ✗"
            console.log(`${tag} sub_first_commit ${subTotal === -1 ? "no log" : `${subTotal.toFixed(0)}ms`} (${sc.subRoute}, budget ${firstCommitBudget}ms)`)
            if (subTotal > firstCommitBudget || subTotal === -1) anyBreach = true
            // The sub-route lives under a stack-pushed parent (Settings hub → RacingSettings →
            // RacingPlanSettings). Inside a stack-pushed screen, edge-swipe is intercepted as
            // stack-pop rather than drawer-open, so the next iteration's `openDrawer()` would
            // silently fail. Pop twice to land back on the drawer-level Settings hub before
            // the post-iteration `am start LAUNCHER` reset.
            adb("shell input keyevent KEYCODE_BACK")
            await sleep(500)
            adb("shell input keyevent KEYCODE_BACK")
            await sleep(500)
        }

        summary.push({
            route: sc.route,
            total,
            phases: routePhases,
            navBlocks,
            toggleBlockedMs,
            toggleBlocks,
            toggleSlowCommits,
            subRoute: sc.subRoute ?? null,
            subTotal,
            subPhases,
            previewMs,
        })

        // Reset to a known state by re-launching the activity rather than relying on BACK
        // semantics (which differ for drawer-level vs stack-level screens). singleTask + the
        // launcher intent brings the existing task to front at its root (Home).
        adb(`shell am start -W -n ${ACTIVITY} -a android.intent.action.MAIN -c android.intent.category.LAUNCHER`, 15000)
        await sleep(1500)
    }

    console.log("\n=== Summary ===")
    console.log(`  cold_start ${coldStartMs}ms`)
    for (const s of summary) {
        const phasePart = s.phases.map((p) => `${p.phase}=${p.ms.toFixed(0)}`).join(" ")
        const togglePart = s.toggleBlockedMs != null ? ` toggle_blocked=${s.toggleBlockedMs}ms` : ""
        const previewPart = s.previewMs != null ? ` preview=${s.previewMs}ms` : ""
        console.log(`  ${s.route.padEnd(28)} total=${s.total}ms ${phasePart}${togglePart}${previewPart}`)
        if (s.subRoute) {
            const subPhasePart = s.subPhases.map((p) => `${p.phase}=${p.ms.toFixed(0)}`).join(" ")
            console.log(`    └─ ${s.subRoute.padEnd(24)} total=${s.subTotal}ms ${subPhasePart}`)
        }
    }

    if (anyBreach) {
        console.log("\nFAIL: at least one phase exceeded its budget.")
        process.exit(1)
    }
    console.log("\nPASS")
}

void main().catch((e) => {
    console.error(e)
    process.exit(1)
})
