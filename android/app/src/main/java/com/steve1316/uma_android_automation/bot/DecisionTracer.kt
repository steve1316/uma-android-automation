package com.steve1316.uma_android_automation.bot

import com.steve1316.automation_library.utils.BotStatus
import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.uma_android_automation.types.GameDate
import com.steve1316.uma_android_automation.types.Mood
import com.steve1316.uma_android_automation.types.StatName
import com.steve1316.uma_android_automation.types.Trainee

/**
 * Per-turn structured decision logger.
 *
 * Captures every notable decision the bot makes during a main-screen turn (action choice, item use, training selection, race eligibility, etc.) and
 * emits a single consolidated block at turn end via `MessageLog.i`. The block is modeled on the existing `Item Usage Summary` and `Training Analysis
 * Results` blocks so users have a single greppable section that answers "why did the bot do X this turn?".
 *
 * `startTurn(...)` snapshots state at the beginning of a main-screen decision. The various `record...` methods append events as the decision tree is
 * walked. `emit()` flushes the formatted block once and clears the buffer. Existing `MessageLog.i/v/w/e` lines are left untouched so chronological tracing is unaffected.
 */
class DecisionTracer {
    /** Header for the current turn's Decision Report block (e.g. "Turn 25 (CLASSIC EARLY JANUARY)"). */
    private var turnLabel: String = ""

    /** Current game date captured by `startTurn`, exposed via `currentTurnDate()` for run analytics. Null until the first turn opens. */
    private var turnDate: GameDate? = null

    /** Snapshot of trainee and campaign state captured by `startTurn`. */
    private var stateSnapshot: StateSnapshot? = null

    /** Snapshot of decision-relevant settings captured by `startTurn`. */
    private var settingsSnapshot: SettingsSnapshot? = null

    /** Ordered list of decision events recorded this turn. */
    private val events: MutableList<DecisionEvent> = mutableListOf()

    /** True once `emit()` has flushed this turn's block, so subsequent calls become no-ops. */
    private var hasEmitted: Boolean = false

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Data types

    /** Snapshot of trainee and campaign state at the start of a turn. */
    data class StateSnapshot(
        /** Trainee's current energy percentage (0-100). */
        val energy: Int,
        /** Trainee's current mood at decision time. */
        val mood: Mood,
        /** Active negative status names. Empty when none. */
        val negativeStatuses: List<String>,
        /** Decision-relevant inventory grouped by category label (e.g. `Megaphones`) then mapped to item-name -> count. Outer and inner maps preserve insertion order (use `LinkedHashMap`). Empty for campaigns without inventory tracking. */
        val inventory: Map<String, Map<String, Int>>,
        /** Campaign-specific extra state (e.g. `consecutiveRaceCount` for Trackblazer) as displayable key/value pairs. */
        val extra: Map<String, String>,
    )

    /** Settings that influence this turn's decision tree. Subclasses populate via the builder. */
    class SettingsSnapshot {
        /** Ordered map of setting display name to formatted value. */
        val entries: LinkedHashMap<String, String> = LinkedHashMap()

        /**
         * Append a setting to the snapshot in insertion order.
         *
         * @param key Display name of the setting (e.g. "Max Failure Chance").
         * @param value Setting value. Coerced to string via `toString()`. Null renders as "(unset)".
         * @return This snapshot for chained `.add(...)` calls.
         */
        fun add(key: String, value: Any?): SettingsSnapshot {
            entries[key] = value?.toString() ?: "(unset)"
            return this
        }
    }

    /** An action that was considered but not chosen, plus the short reason it was rejected. */
    data class RejectedAlternative(
        /** Display name of the rejected action (e.g. "REST", "RACE", "RECOVER_MOOD"). */
        val action: String,
        /** One-line reason this action was not chosen. */
        val reason: String,
    )

    /** A training that lost the scoring contest, with its score and rejection reason for the Decision Report's runner-ups list. */
    data class TrainingRunnerUp(
        /** The stat whose training was scored. */
        val stat: StatName,
        /** True if this training was filtered out (blacklist, failure-chance gate, etc.). False if simply outscored. */
        val rejected: Boolean,
        /** Short reason explaining the verdict. */
        val reason: String,
        /** Optional numeric score from the scoring function. Null when scoring data was not captured at the call site. */
        val score: Double? = null,
        /** Optional failure chance percentage for this training, when available. */
        val failureChance: Int? = null,
        /** Optional full stat-gain map for this training (gain per `StatName`). Renders as `gains=[SPD:N STA:N PWR:N GUTS:N WIT:N]` when present. */
        val statGains: Map<StatName, Int>? = null,
    )

    /** Verdict for an inventory item considered during a turn. */
    enum class ItemVerdict { USED, SKIPPED, CONSERVED, NOT_PRESENT }

    /** Verdict for the Reset Whistle path. */
    enum class WhistleVerdict { USED, BLOCKED, NOT_ELIGIBLE, NOT_IN_INVENTORY }

    /** Internal event record produced by the various `record...` methods. */
    sealed class DecisionEvent {
        /** Event recording the main-screen action chosen for this turn. */
        data class ActionChoice(
            /** The action the bot will execute. */
            val chosen: MainScreenAction,
            /** Short human-readable explanation of why this action won. */
            val reason: String,
            /** Alternatives that were considered and ruled out, in evaluation order. */
            val rejected: List<RejectedAlternative>,
        ) : DecisionEvent()

        /** Event recording a decision about a specific inventory item. */
        data class ItemDecision(
            /** Display name of the item considered (e.g. "Empowering Megaphone"). */
            val item: String,
            /** What the bot decided to do with the item. */
            val verdict: ItemVerdict,
            /** Short explanation supporting the verdict. */
            val reason: String,
        ) : DecisionEvent()

        /** Event recording the outcome of the Good-Luck Charm gating decision. */
        data class CharmGate(
            /** True if the charm was queued for use this turn. */
            val queued: Boolean,
            /** When `queued=false`, the short name of the gate that blocked it (e.g. "min stat gain not met"). */
            val blockingGate: String?,
        ) : DecisionEvent()

        /** Event recording the outcome of the Reset Whistle decision. */
        data class WhistleOutcome(
            /** The Whistle's final disposition. */
            val verdict: WhistleVerdict,
            /** Short explanation of the verdict. */
            val reason: String,
            /** When the Whistle was used, the new training selected by the re-analysis pass. */
            val postRollSelection: StatName?,
        ) : DecisionEvent()

        /** Event recording the final training selection plus runner-up breakdown. */
        data class TrainingSelection(
            /** The picked stat, or null when no training was chosen this turn. */
            val selected: StatName?,
            /** Code path that produced the pick (analysis, forced-default, forced-from-skipped, etc.). */
            val source: SelectionSource?,
            /** Short explanation of why this pick won. */
            val reason: String,
            /** Other trainings the analyzer evaluated this turn, with their scores and rejection reasons. */
            val runnerUps: List<TrainingRunnerUp>,
            /** Failure chance percentage for the picked training, when available. Rendered as a `Pick:` line in the report. */
            val pickedFailureChance: Int? = null,
            /** Full stat-gain map for the picked training, when available. Rendered alongside `pickedFailureChance` on the `Pick:` line. */
            val pickedStatGains: Map<StatName, Int>? = null,
        ) : DecisionEvent()

        /** Event recording whether the bot is eligible to race this turn. */
        data class RaceEligibility(
            /** True if the bot may race. False if a gate blocked it. */
            val eligible: Boolean,
            /** Short explanation of the eligibility decision. */
            val reason: String,
        ) : DecisionEvent()

        /** Free-form note for context that doesn't fit any other event type. */
        data class Note(
            /** Note text to display in the Decision Report. */
            val message: String,
        ) : DecisionEvent()

        /** Event recording that the bot abandoned its original action and executed a recovery instead. */
        data class RecoveryExecuted(
            /** The recovery action that executed (e.g. "RECOVER_ENERGY", "RECOVER_MOOD"). */
            val action: String,
            /** Short explanation of why recovery happened. */
            val reason: String,
        ) : DecisionEvent()
    }

    companion object {
        /** Log tag prepended to every Decision Report block emitted via `MessageLog.i`. */
        private const val TAG: String = "[DECISION]"

        /** Main-career turns before the Finale. The overlay's progress ring is full at this turn. */
        private const val CAREER_TURNS: Int = 72

        /** Finale turns after `CAREER_TURNS`. */
        private const val FINALE_TURNS: Int = 3

        /**
         * Builds the overlay's progress label for a turn.
         *
         * @param day The turn number, 1 to 72, or 73 to 75 in the Finale.
         * @return A label such as "Turn 34/72" or "Finale 2/3".
         */
        fun statusLabelFor(day: Int): String = if (day > CAREER_TURNS) "Finale ${(day - CAREER_TURNS).coerceAtMost(FINALE_TURNS)}/$FINALE_TURNS" else "Turn $day/$CAREER_TURNS"

        /**
         * Shows a turn on the overlay and in the notification.
         *
         * @param day The turn number, 1 to 72, or 73 to 75 in the Finale.
         */
        fun pushProgress(day: Int) {
            BotStatus.setProgress(day, CAREER_TURNS, statusLabelFor(day))
        }

        /**
         * Builds the overlay's short description of the action taken this turn.
         *
         * @param chosen The main-screen action the bot chose, or null when none was recorded.
         * @param trainedStat The stat trained this turn, or null when unknown.
         * @return A short phrase such as "Trained Speed", or an empty string when there is nothing to show.
         */
        fun statusDetailFor(chosen: MainScreenAction?, trainedStat: StatName?): String =
            when (chosen) {
                MainScreenAction.TRAIN -> if (trainedStat != null) "Trained ${trainedStat.name.lowercase().replaceFirstChar { it.uppercase() }}" else "Trained"
                MainScreenAction.RACE -> "Raced"
                MainScreenAction.REST -> "Rested"
                MainScreenAction.RECOVER_MOOD -> "Recovered mood"
                MainScreenAction.DATE -> "Went on a date"
                MainScreenAction.NONE, null -> ""
            }
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Lifecycle

    /**
     * Open a new decision-tracing window for the current turn. Resets all per-turn buffers.
     *
     * @param date Current game date used for the turn label.
     * @param trainee Live trainee whose state is snapshotted (shallow copy of relevant fields).
     * @param inventorySnapshot Decision-relevant inventory grouped by category label -> item-name -> count. Both maps preserve insertion order (use `LinkedHashMap`). Pass an empty map when the campaign has no inventory concept.
     * @param settings Settings values that drive decisions this turn.
     * @param extraState Optional map of campaign-specific state values (e.g. Trackblazer's `consecutiveRaceCount`) that the base trainee snapshot does not carry.
     */
    fun startTurn(
        date: GameDate,
        trainee: Trainee,
        inventorySnapshot: Map<String, Map<String, Int>> = emptyMap(),
        settings: SettingsSnapshot = SettingsSnapshot(),
        extraState: Map<String, String> = emptyMap(),
    ) {
        turnLabel = formatTurnLabel(date)
        turnDate = date
        stateSnapshot =
            StateSnapshot(
                energy = trainee.energy,
                mood = trainee.mood,
                negativeStatuses = trainee.currentNegativeStatuses.toList(),
                inventory = inventorySnapshot.mapValuesTo(LinkedHashMap()) { (_, items) -> LinkedHashMap(items) },
                extra = extraState.toMap(),
            )
        settingsSnapshot = settings
        events.clear()
        hasEmitted = false

        // Show the new turn on the overlay and in the notification.
        pushProgress(date.day)
    }

    /**
     * Emit the consolidated Decision Report block for the current turn and clear the buffer. No-op if `startTurn` was never called or if the block has
     * already been emitted for this turn (subsequent re-emits during the same turn are silently dropped).
     */
    fun emit() {
        if (hasEmitted || stateSnapshot == null) return
        MessageLog.i(TAG, formatReport())
        hasEmitted = true

        // Show what the bot did this turn on the overlay and in the notification.
        val chosen = events.filterIsInstance<DecisionEvent.ActionChoice>().lastOrNull()?.chosen
        val detail = statusDetailFor(chosen, lastTrainingSelection()?.selected)
        if (detail.isNotEmpty()) BotStatus.setDetail(detail)
    }

    /**
     * The game date captured for the current turn by `startTurn`, or null before the first turn opens.
     *
     * @return The current turn's date, or null.
     */
    fun currentTurnDate(): GameDate? = turnDate

    /**
     * The most recent training selection recorded this turn, or null when none was recorded.
     *
     * @return The last `TrainingSelection` event, or null.
     */
    fun lastTrainingSelection(): DecisionEvent.TrainingSelection? = events.filterIsInstance<DecisionEvent.TrainingSelection>().lastOrNull()

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Recording methods

    /**
     * Record the main-screen action chosen this turn and any alternatives that were considered but rejected.
     *
     * @param chosen The action the bot will execute.
     * @param reason Short human-readable explanation of why this action won.
     * @param rejected Alternatives that were considered and ruled out, in evaluation order.
     */
    fun recordActionChoice(chosen: MainScreenAction, reason: String, rejected: List<RejectedAlternative> = emptyList()) {
        events.add(DecisionEvent.ActionChoice(chosen, reason, rejected))
    }

    /**
     * Append a free-form note for context that doesn't fit any other event type.
     *
     * @param message Note text to display in the Decision Report.
     */
    fun recordNote(message: String) {
        events.add(DecisionEvent.Note(message))
    }

    /**
     * Record that the bot abandoned its original action and executed a recovery instead (REST or RECOVER_MOOD). Used by training-backout branches that
     * fall through to `recoverEnergy()` / `recoverMood()` after the analyzer fails to pick a training, so the report shows the real final action.
     *
     * @param action The recovery action that executed (e.g. "RECOVER_ENERGY", "RECOVER_MOOD").
     * @param reason Short explanation of why recovery happened.
     */
    fun recordRecoveryExecuted(action: String, reason: String) {
        events.add(DecisionEvent.RecoveryExecuted(action, reason))
    }

    /**
     * Record a decision about a specific inventory item (used, skipped, conserved for later, or not present).
     *
     * @param item Display name of the item considered (e.g. "Empowering Megaphone", "Plain Cupcake").
     * @param verdict What the bot decided to do with the item.
     * @param reason Short explanation supporting the verdict.
     */
    fun recordItemDecision(item: String, verdict: ItemVerdict, reason: String) {
        events.add(DecisionEvent.ItemDecision(item, verdict, reason))
    }

    /**
     * Record the outcome of the Good-Luck Charm gating decision.
     *
     * @param queued True if the charm was queued for use this turn.
     * @param blockingGate When `queued=false`, the short name of the gate that blocked it (e.g. "min stat gain not met").
     */
    fun recordCharmGate(queued: Boolean, blockingGate: String? = null) {
        events.add(DecisionEvent.CharmGate(queued, blockingGate))
    }

    /**
     * Record the outcome of the Reset Whistle decision (used, blocked, not eligible, or absent from inventory).
     *
     * @param verdict The Whistle's final disposition.
     * @param reason Short explanation of the verdict.
     * @param postRollSelection When the Whistle was used, the new training selected by the re-analysis pass.
     */
    fun recordWhistleOutcome(verdict: WhistleVerdict, reason: String, postRollSelection: StatName? = null) {
        events.add(DecisionEvent.WhistleOutcome(verdict, reason, postRollSelection))
    }

    /**
     * Record the final training selection plus runner-up data for the Decision Report's training breakdown.
     *
     * @param selected The picked stat, or null when no training was chosen and the bot backed out for recovery.
     * @param source Code path that produced the pick (analysis, forced-default, forced-from-skipped, etc.).
     * @param reason Short explanation of why this pick won.
     * @param runnerUps Other trainings the analyzer evaluated this turn, with their scores and rejection reasons.
     * @param pickedFailureChance Failure chance percentage for the picked training, when available.
     * @param pickedStatGains Full stat-gain map for the picked training, when available.
     */
    fun recordTrainingSelection(
        selected: StatName?,
        source: SelectionSource?,
        reason: String,
        runnerUps: List<TrainingRunnerUp> = emptyList(),
        pickedFailureChance: Int? = null,
        pickedStatGains: Map<StatName, Int>? = null,
    ) {
        events.add(DecisionEvent.TrainingSelection(selected, source, reason, runnerUps, pickedFailureChance, pickedStatGains))
    }

    /**
     * Record whether the bot is eligible to race this turn based on consecutive-race rules.
     *
     * @param eligible True if the bot may race. False if a gate blocked it.
     * @param reason Short explanation of the eligibility decision.
     */
    fun recordRaceEligibility(eligible: Boolean, reason: String) {
        events.add(DecisionEvent.RaceEligibility(eligible, reason))
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Formatting

    /**
     * Build the consolidated Decision Report string for the current turn.
     *
     * @return Multi-line report block ready to be passed to `MessageLog.i`. Empty when no state was snapshotted.
     */
    private fun formatReport(): String {
        val state = stateSnapshot ?: return ""
        val settings = settingsSnapshot ?: SettingsSnapshot()
        val sb = StringBuilder()
        sb.append("\n============== $turnLabel Decision Report ==============\n")
        sb.append(formatStateSection(state))
        sb.append(formatSettingsSection(settings))
        sb.append(formatItemsUsedSection())
        sb.append(formatEventsSection())
        sb.append("==============================================================")
        return sb.toString()
    }

    /**
     * Render the State and Inventory sections of the report.
     *
     * @param state The state snapshot to render.
     * @return Formatted State and Inventory lines.
     */
    private fun formatStateSection(state: StateSnapshot): String {
        val sb = StringBuilder()
        sb.append("State:\n")
        sb.append("  Energy = ${state.energy}%\n")
        sb.append("  Mood = ${state.mood}\n")
        sb.append("  Negative Statuses = ${if (state.negativeStatuses.isEmpty()) "[]" else state.negativeStatuses.joinToString(", ", "[", "]")}\n")
        state.extra.forEach { (key, value) -> sb.append("  $key = $value\n") }
        if (state.inventory.isNotEmpty()) {
            sb.append("Inventory (decision-relevant):\n")
            state.inventory.forEach { (group, items) ->
                sb.append("  [$group]\n")
                items.toSortedMap().forEach { (name, count) -> sb.append("  $name = $count\n") }
            }
        }
        return sb.toString()
    }

    /**
     * Render the Settings section of the report, or an empty string when no settings were captured.
     *
     * @param settings The settings snapshot to render.
     * @return Formatted Settings lines, or empty string when there is nothing to show.
     */
    private fun formatSettingsSection(settings: SettingsSnapshot): String {
        if (settings.entries.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("Settings (decision-relevant):\n")
        settings.entries.forEach { (key, value) -> sb.append("  $key = $value\n") }
        return sb.toString()
    }

    /**
     * Render the consolidated "Items Used" summary that mirrors the existing `Item Usage Summary` block. Lists every inventory item the bot actually
     * consumed this turn (Megaphones, energy/mood items, Good-Luck Charm, Reset Whistle). The per-event lines below still carry the full reasoning. This
     * section is the quick "what got used?" reference at the top of the report.
     *
     * @return Formatted Items Used section, or "Items Used: None" when nothing was consumed.
     */
    private fun formatItemsUsedSection(): String {
        data class UsedItem(val name: String, val reason: String?)
        val used = mutableListOf<UsedItem>()
        events.forEach { event ->
            when (event) {
                is DecisionEvent.ItemDecision ->
                    if (event.verdict == ItemVerdict.USED) used.add(UsedItem(event.item, event.reason))
                is DecisionEvent.CharmGate ->
                    if (event.queued) used.add(UsedItem("Good-Luck Charm", "Charm gate passed; setting failure chance to 0%"))
                is DecisionEvent.WhistleOutcome ->
                    if (event.verdict == WhistleVerdict.USED) used.add(UsedItem("Reset Whistle", event.reason))
                else -> Unit
            }
        }
        if (used.isEmpty()) return "Items Used: None\n"
        val sb = StringBuilder()
        sb.append("Items Used:\n")
        used.forEach { item ->
            if (item.reason.isNullOrBlank()) {
                sb.append("  - ${item.name}\n")
            } else {
                sb.append("  - ${item.name}: ${item.reason}\n")
            }
        }
        return sb.toString()
    }

    /**
     * Render the per-event section that walks through each decision event in recording order.
     *
     * @return Formatted event lines, or "No decision events recorded." when the event list is empty.
     */
    private fun formatEventsSection(): String {
        if (events.isEmpty()) {
            return "No decision events recorded.\n"
        }
        val sb = StringBuilder()
        events.forEach { event ->
            when (event) {
                is DecisionEvent.ActionChoice -> {
                    sb.append("\nAction: ${event.chosen}\n")
                    sb.append("  Reason: ${event.reason}\n")
                    if (event.rejected.isNotEmpty()) {
                        sb.append("  Rejected alternatives:\n")
                        event.rejected.forEach { sb.append("    - ${it.action}: ${it.reason}\n") }
                    }
                }
                is DecisionEvent.ItemDecision -> {
                    // USED items are already listed in the top-level Items Used section, so suppress the per-event render to avoid duplication.
                    if (event.verdict != ItemVerdict.USED) {
                        sb.append("\nItem: ${event.item} -> ${event.verdict}\n")
                        sb.append("  Reason: ${event.reason}\n")
                    }
                }
                is DecisionEvent.CharmGate -> {
                    // Queued charm uses are already in the Items Used section. Render only when the charm was blocked, so the gate explanation surfaces.
                    if (!event.queued) {
                        sb.append("\nCharm: NOT QUEUED\n")
                        event.blockingGate?.let { sb.append("  Gate: $it\n") }
                    }
                }
                is DecisionEvent.WhistleOutcome -> {
                    sb.append("\nWhistle: ${event.verdict}\n")
                    sb.append("  Reason: ${event.reason}\n")
                    event.postRollSelection?.let { sb.append("  Post-roll selection: $it\n") }
                }
                is DecisionEvent.TrainingSelection -> {
                    val pick = event.selected?.name ?: "NONE"
                    sb.append("\nTraining selected: $pick (source=${event.source ?: "unset"})\n")
                    sb.append("  Reason: ${event.reason}\n")
                    if (event.pickedFailureChance != null || event.pickedStatGains != null) {
                        val pieces =
                            listOfNotNull(
                                event.pickedFailureChance?.let { "fail=$it%" },
                                event.pickedStatGains?.let { "gains=${formatStatGains(it)}" },
                            )
                        if (pieces.isNotEmpty()) sb.append("  Pick: ${pieces.joinToString(", ")}\n")
                    }
                    if (event.runnerUps.isNotEmpty()) {
                        sb.append("  Runner-ups:\n")
                        event.runnerUps.forEach { ru ->
                            val verdict = if (ru.rejected) "REJECTED" else "considered"
                            val scoreFragment = ru.score?.let { "score=${"%.2f".format(it)}, " } ?: ""
                            val failFragment = ru.failureChance?.let { "fail=$it%, " } ?: ""
                            val gainFragment = ru.statGains?.let { gains -> "gains=${formatStatGains(gains)}, " } ?: ""
                            sb.append("    - ${ru.stat} ($verdict): $scoreFragment$failFragment$gainFragment${ru.reason}\n")
                        }
                    }
                }
                is DecisionEvent.RaceEligibility -> {
                    sb.append("\nRace eligibility: ${if (event.eligible) "ELIGIBLE" else "NOT ELIGIBLE"}\n")
                    sb.append("  Reason: ${event.reason}\n")
                }
                is DecisionEvent.Note -> {
                    sb.append("\nNote: ${event.message}\n")
                }
                is DecisionEvent.RecoveryExecuted -> {
                    sb.append("\nRecovery executed: ${event.action}\n")
                    sb.append("  Reason: ${event.reason}\n")
                }
            }
        }
        return sb.toString()
    }

    /**
     * Render a stat-gain map as a fixed-order bracketed list (`[SPD:2 STA:11 PWR:1 GUTS:0 WIT:0]`). Missing keys render as 0 so every line has the same width.
     *
     * @param gains Stat-gain values keyed by stat.
     * @return Bracketed string showing each stat in the canonical SPD/STA/PWR/GUTS/WIT order.
     */
    private fun formatStatGains(gains: Map<StatName, Int>): String {
        val order = listOf(StatName.SPEED to "SPD", StatName.STAMINA to "STA", StatName.POWER to "PWR", StatName.GUTS to "GUTS", StatName.WIT to "WIT")
        return order.joinToString(separator = " ", prefix = "[", postfix = "]") { (stat, label) -> "$label:${gains[stat] ?: 0}" }
    }

    /**
     * Build the turn label header (e.g. "Turn 25 (CLASSIC EARLY JANUARY)") used at the top of every Decision Report block.
     *
     * @param date The current game date.
     * @return Formatted turn label string.
     */
    private fun formatTurnLabel(date: GameDate): String {
        val year = date.year.toString().replace('_', ' ')
        val month = date.month.toString().replace('_', ' ')
        val phase = date.phase.toString().replace('_', ' ')
        return "Turn ${date.day} ($year $phase $month)"
    }
}
