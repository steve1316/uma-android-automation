package com.steve1316.uma_android_automation.bot

import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.automation_library.utils.SQLiteSettingsManager
import com.steve1316.automation_library.utils.TextUtils
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.types.SkillData
import com.steve1316.uma_android_automation.utils.DoublyLinkedList
import com.steve1316.uma_android_automation.utils.DoublyLinkedListNode

/**
 * Handle communication with the SQLite skills database.
 *
 * @property game A reference to the bot's [Game] instance.
 */
class SkillDatabase(private val game: Game) {
    /** A mapping of skill names to their associated [SkillData]. */
    private val skillData: Map<String, SkillData> = loadSkillData()

    /** A mapping of skill names to their unique skill IDs. */
    private val skillNameToId: Map<String, Int> = skillData.mapValues { it.value.id }

    /** A mapping of unique skill IDs to their associated skill names. */
    private val skillIdToName: Map<Int, String> = skillNameToId.entries.associate { it.value to it.key }

    /** A mapping of skill names to a [DoublyLinkedListNode] representing the upgrade/downgrade paths. */
    private val skillStructure: Map<String, DoublyLinkedListNode<String>> = loadSkillStructure()

    /** A mapping of all skill names in the database to lists of their upgrade chains. */
    val skillUpgradeChains: Map<String, List<String>> = skillStructure.mapValues { it.value.list.getValues() }

    companion object {
        private val TAG: String = "[${MainActivity.loggerTag}]SkillDatabase"

        /** The name of the skills table in the database. */
        private const val TABLE_SKILLS = "skills"

        /** The name of the skill ID column. */
        private const val SKILLS_COLUMN_SKILL_ID = "skill_id"

        /** The name of the English name column. */
        private const val SKILLS_COLUMN_NAME_EN = "name_en"

        /** The name of the English description column. */
        private const val SKILLS_COLUMN_DESC_EN = "desc_en"

        /** The name of the icon ID column. */
        private const val SKILLS_COLUMN_ICON_ID = "icon_id"

        /** The name of the base cost column. */
        private const val SKILLS_COLUMN_COST = "cost"

        /** The name of the evaluation points column. */
        private const val SKILLS_COLUMN_EVAL_PT = "eval_pt"

        /** The name of the condition column. */
        private const val SKILLS_COLUMN_CONDITION = "condition"

        /** The name of the precondition column. */
        private const val SKILLS_COLUMN_PRECONDITION = "precondition"

        /** The name of the inherited column. */
        private const val SKILLS_COLUMN_INHERITED = "inherited"

        /** The name of the community tier column. */
        private const val SKILLS_COLUMN_COMMUNITY_TIER = "community_tier"

        /** The name of the upgrade ID column. */
        private const val SKILLS_COLUMN_UPGRADE = "upgrade"

        /** The name of the downgrade ID column. */
        private const val SKILLS_COLUMN_DOWNGRADE = "downgrade"

        /** The threshold for fuzzy string matching (0.0 to 1.0). */
        private const val SIMILARITY_THRESHOLD = 0.7
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Load all skill data from the SQLite database.
     *
     * @return A mapping of skill names to [SkillData] objects.
     */
    private fun loadSkillData(): Map<String, SkillData> {
        val settingsManager = SQLiteSettingsManager(game.myContext)
        if (!settingsManager.isAvailable()) {
            MessageLog.e(TAG, "[ERROR] loadSkillData:: Database not available.")
            settingsManager.close()
            return emptyMap()
        }

        try {
            val database = settingsManager.readableDatabase
            val result: MutableMap<String, SkillData> = mutableMapOf()

            val query = "SELECT * FROM $TABLE_SKILLS"
            val cursor = database.rawQuery(query, null)
            cursor.use {
                if (cursor.moveToFirst()) {
                    do {
                        val idIndex: Int = cursor.getColumnIndexOrThrow(SKILLS_COLUMN_SKILL_ID)
                        val nameIndex: Int = cursor.getColumnIndexOrThrow(SKILLS_COLUMN_NAME_EN)
                        val descriptionIndex: Int = cursor.getColumnIndexOrThrow(SKILLS_COLUMN_DESC_EN)
                        val iconIdIndex: Int = cursor.getColumnIndexOrThrow(SKILLS_COLUMN_ICON_ID)
                        val costIndex: Int = cursor.getColumnIndexOrThrow(SKILLS_COLUMN_COST)
                        val evalPtIndex: Int = cursor.getColumnIndexOrThrow(SKILLS_COLUMN_EVAL_PT)
                        val conditionIndex: Int = cursor.getColumnIndexOrThrow(SKILLS_COLUMN_CONDITION)
                        val preconditionIndex: Int = cursor.getColumnIndexOrThrow(SKILLS_COLUMN_PRECONDITION)
                        val inheritedIndex: Int = cursor.getColumnIndexOrThrow(SKILLS_COLUMN_INHERITED)
                        val communityTierIndex: Int = cursor.getColumnIndexOrThrow(SKILLS_COLUMN_COMMUNITY_TIER)
                        val upgradeIndex: Int = cursor.getColumnIndexOrThrow(SKILLS_COLUMN_UPGRADE)
                        val downgradeIndex: Int = cursor.getColumnIndexOrThrow(SKILLS_COLUMN_DOWNGRADE)

                        val skillDataItem =
                            SkillData(
                                id = it.getInt(idIndex),
                                name = it.getString(nameIndex),
                                description = it.getString(descriptionIndex),
                                iconId = it.getInt(iconIdIndex),
                                cost = it.getInt(costIndex),
                                evalPt = it.getInt(evalPtIndex),
                                condition = it.getString(conditionIndex),
                                precondition = it.getString(preconditionIndex),
                                bIsInheritedUnique = it.getInt(inheritedIndex) == 1,
                                communityTier = if (it.isNull(communityTierIndex)) null else it.getInt(communityTierIndex),
                                upgrade = if (it.isNull(upgradeIndex)) null else it.getInt(upgradeIndex),
                                downgrade = if (it.isNull(downgradeIndex)) null else it.getInt(downgradeIndex),
                            )
                        result[skillDataItem.name] = skillDataItem
                    } while (cursor.moveToNext())
                }
            }

            return result.toMap()
        } catch (e: Exception) {
            MessageLog.e(TAG, "[ERROR] loadSkillData:: ${e.message}")
            return emptyMap()
        } finally {
            settingsManager.close()
        }
    }

    /**
     * Generate a mapping of skill names to a linked list representing their respective upgrade chains.
     *
     * @return The generated mapping of skill names to linked list nodes.
     */
    private fun loadSkillStructure(): Map<String, DoublyLinkedListNode<String>> {
        fun getVersionNames(id: Int?, bIsUpgrade: Boolean = false): List<String> {
            var currentId: Int? = id ?: return emptyList()

            val versionResults: MutableList<String> = mutableListOf()

            while (currentId != null) {
                // Look up directly so an unreleased upgrade/downgrade target ends the chain silently instead of warning.
                val name: String = skillIdToName[currentId] ?: break
                val tmpData: SkillData? = getSkillData(name)
                if (tmpData == null) {
                    MessageLog.e(TAG, "[ERROR] loadSkillStructure::getVersionNames:: \"$name\" not in skillData.")
                    break
                }

                versionResults.add(name)

                currentId = if (bIsUpgrade) tmpData.upgrade else tmpData.downgrade
            }

            return if (bIsUpgrade) versionResults.toList() else versionResults.reversed().toList()
        }

        val structureResult: MutableMap<String, DoublyLinkedListNode<String>> = mutableMapOf()

        for ((name, data) in skillData) {
            // Get all downgrade and upgrade versions for this skill.
            val downgradeNames: List<String> = getVersionNames(data.downgrade)
            val upgradeNames: List<String> = getVersionNames(data.upgrade, bIsUpgrade = true)

            // Combine the version names, including the current one, in order.
            val orderedNames: List<String> = downgradeNames + name + upgradeNames

            // Skip if this chain has already been added to the structure.
            // The first occurrence of any skill in a chain will populate all entries for that chain.
            if (orderedNames.any { it in structureResult }) {
                continue
            }

            // Generate the linked list of versions.
            val list = DoublyLinkedList<String>()
            for (orderedName in orderedNames) {
                if (orderedName in structureResult) {
                    MessageLog.e(TAG, "[ERROR] loadSkillStructure:: \"$orderedName\" already in skillStructure!")
                    continue
                }
                val node: DoublyLinkedListNode<String> = list.append(orderedName)

                // Add this node's reference to the skill structure.
                structureResult[orderedName] = node
            }
        }

        return structureResult.toMap()
    }

    /**
     * Search for a skill name in the loaded database using fuzzy string matching.
     *
     * @param name The skill name to search for.
     * @return The corrected skill name on success, null otherwise.
     */
    fun fuzzySearchSkillName(name: String): String? {
        return TextUtils.matchStringInList(
            query = name,
            choices = skillData.keys.toList(),
            threshold = SIMILARITY_THRESHOLD,
        )
    }

    /**
     * Verify if a skill name exists in the database.
     *
     * @param name The skill name to search for.
     * @param fuzzySearch Whether to use fuzzy matching if an exact match is not found.
     * @return The exact or corrected skill name on success, null otherwise.
     */
    fun checkSkillName(name: String, fuzzySearch: Boolean = false): String? {
        // Check for an exact match first.
        if (name in skillData) {
            return name
        }

        // Fallback to a fuzzy search if requested.
        return if (fuzzySearch) fuzzySearchSkillName(name) else null
    }

    /**
     * Retrieve [SkillData] for the specified skill name.
     *
     * @param name The name of the skill to retrieve.
     * @return The [SkillData] object if found, null otherwise.
     */
    fun getSkillData(name: String): SkillData? {
        var result: SkillData? = skillData[name]
        if (result == null) {
            MessageLog.w(TAG, "[WARN] getSkillData:: Skill name (\"$name\") not found. Attempting fuzzy search...")
            val tmpName: String? = checkSkillName(name, fuzzySearch = true)
            if (tmpName == null) {
                MessageLog.w(TAG, "[WARN] getSkillData:: No fuzzy match found for \"$name\".")
                return null
            }
            result = skillData[tmpName]
        }

        return result
    }

    /**
     * Retrieve a list of [SkillData] objects for the specified skill names.
     *
     * @param names The list of skill names to retrieve.
     * @return A list of [SkillData] objects if all names are found, null otherwise.
     */
    fun getSkillData(names: List<String>): List<SkillData>? {
        val results: MutableList<SkillData> = mutableListOf()
        for (name in names) {
            val data: SkillData = getSkillData(name) ?: return null
            results.add(data)
        }

        return results.toList()
    }

    /**
     * Retrieve the skill name associated with the specified skill ID.
     *
     * @param id The ID of the skill to look up.
     * @return The name of the skill if found, null otherwise.
     */
    fun getSkillName(id: Int): String? {
        if (id !in skillIdToName) {
            MessageLog.w(TAG, "[WARN] getSkillName:: Skill ID ($id) not found.")
        }

        return skillIdToName[id]
    }

    /**
     * Retrieve the unique skill ID associated with the specified skill name.
     *
     * @param name The name of the skill to look up.
     * @return The ID of the skill if found, null otherwise.
     */
    fun getSkillId(name: String): Int? {
        var result: Int? = skillNameToId[name]
        if (result == null) {
            MessageLog.w(TAG, "[WARN] getSkillId:: Skill name (\"$name\") not found. Attempting fuzzy search...")
            val tmpName: String? = checkSkillName(name, fuzzySearch = true)
            if (tmpName == null) {
                MessageLog.w(TAG, "[WARN] getSkillId:: No fuzzy match found for \"$name\".")
                return null
            }
            result = skillNameToId[tmpName]
            if (result == null) {
                // This indicates a potential logic error where the ID map is out of sync with the data map.
                MessageLog.e(TAG, "[ERROR] getSkillId:: skillData and skillNameToId keys are not the same.")
            }
        }

        return result
    }

    /**
     * Retrieve a list of all skill versions that must be purchased before the specified skill becomes available.
     *
     * @param name The name of the target skill.
     * @return A list of skill names in order from the lowest version to the specified version.
     */
    fun getRequiredUpgrades(name: String): List<String> {
        if (checkSkillName(name) == null) {
            MessageLog.e(TAG, "[ERROR] getRequiredUpgrades:: Failed to find skill in database: $name")
            return emptyList()
        }

        var currentNode: DoublyLinkedListNode<String>? = skillStructure[name]
        if (currentNode == null) {
            MessageLog.e(TAG, "[ERROR] getRequiredUpgrades:: \"$name\" not in skillStructure.")
            return emptyList()
        }

        val upgradeResults: MutableList<String> = mutableListOf()

        // Walk backward through the list until the beginning of the chain is reached.
        while (currentNode != null) {
            upgradeResults.add(currentNode.value)
            currentNode = currentNode.prev
        }

        // Reverse the results to ensure they are in order from the first version to the target.
        return upgradeResults.reversed().toList()
    }

    /**
     * Retrieve the immediate upgrade version of the specified skill.
     *
     * @param name The name of the skill whose upgrade version is requested.
     * @return The upgraded skill name if one exists, null otherwise.
     */
    fun getUpgrade(name: String): String? {
        if (checkSkillName(name) == null) {
            MessageLog.e(TAG, "[ERROR] getUpgrade:: Failed to find skill in database: $name")
            return null
        }

        val currentNode: DoublyLinkedListNode<String>? = skillStructure[name]
        if (currentNode == null) {
            MessageLog.e(TAG, "[ERROR] getUpgrade:: \"$name\" not in skillStructure.")
            return null
        }

        return currentNode.next?.value
    }

    /**
     * Retrieve a list of all upgraded versions for the specified skill.
     *
     * @param name The name of the skill whose upgrade versions are requested.
     * @return A list of upgraded skill names, including the specified one, in order.
     */
    fun getUpgrades(name: String): List<String> {
        if (checkSkillName(name) == null) {
            MessageLog.e(TAG, "[ERROR] getUpgrades:: Failed to find skill in database: $name")
            return emptyList()
        }

        var currentNode: DoublyLinkedListNode<String>? = skillStructure[name]
        if (currentNode == null) {
            MessageLog.e(TAG, "[ERROR] getUpgrades:: \"$name\" not in skillStructure.")
            return emptyList()
        }

        val upgradeResults: MutableList<String> = mutableListOf()

        // Walk forward through the list until the end of the chain is reached.
        while (currentNode != null) {
            upgradeResults.add(currentNode.value)
            currentNode = currentNode.next
        }

        return upgradeResults.toList()
    }

    /**
     * Retrieve the immediate downgrade version of the specified skill.
     *
     * @param name The name of the skill whose downgrade version is requested.
     * @return The downgraded skill name if one exists, null otherwise.
     */
    fun getDowngrade(name: String): String? {
        if (checkSkillName(name) == null) {
            MessageLog.e(TAG, "[ERROR] getDowngrade:: Failed to find skill in database: $name")
            return null
        }

        val currentNode: DoublyLinkedListNode<String>? = skillStructure[name]
        if (currentNode == null) {
            MessageLog.e(TAG, "[ERROR] getDowngrade:: \"$name\" not in skillStructure.")
            return null
        }

        return currentNode.prev?.value
    }

    /**
     * Retrieve a list of all downgrade versions for the specified skill.
     *
     * @param name The name of the skill whose downgrade versions are requested.
     * @return A list of downgrade skill names, including the specified one, in descending order.
     */
    fun getDowngrades(name: String): List<String> {
        if (checkSkillName(name) == null) {
            MessageLog.e(TAG, "[ERROR] getDowngrades:: Failed to find skill in database: $name")
            return emptyList()
        }

        var currentNode: DoublyLinkedListNode<String>? = skillStructure[name]
        if (currentNode == null) {
            MessageLog.e(TAG, "[ERROR] getDowngrades:: \"$name\" not in skillStructure.")
            return emptyList()
        }

        val downgradeResults: MutableList<String> = mutableListOf()

        // Walk backward through the list until the beginning of the chain is reached.
        while (currentNode != null) {
            downgradeResults.add(currentNode.value)
            currentNode = currentNode.prev
        }

        return downgradeResults.toList()
    }

    /**
     * Compare two skill versions and identify the higher version.
     *
     * @param a The name of the first skill to compare.
     * @param b The name of the second skill to compare against.
     * @return The name of the higher versioned skill on success, null otherwise.
     */
    fun compareVersions(a: String, b: String): String? {
        if (checkSkillName(a) == null) {
            MessageLog.e(TAG, "[ERROR] compareVersions:: Failed to find skill in database: $a")
            return null
        }

        if (checkSkillName(b) == null) {
            MessageLog.e(TAG, "[ERROR] compareVersions:: Failed to find skill in database: $b")
            return null
        }

        val nodeA: DoublyLinkedListNode<String>? = skillStructure[a]
        if (nodeA == null) {
            MessageLog.e(TAG, "[ERROR] compareVersions:: \"$a\" not in skillStructure.")
            return null
        }

        val nodeB: DoublyLinkedListNode<String>? = skillStructure[b]
        if (nodeB == null) {
            MessageLog.e(TAG, "[ERROR] compareVersions:: \"$b\" not in skillStructure.")
            return null
        }

        // Retrieve the index of the first skill in its own chain list.
        val indexA: Int? = nodeA.list.findIndex(nodeA.value)
        if (indexA == null) {
            MessageLog.e(TAG, "[ERROR] compareVersions:: Error getting node index for \"$a\".")
            return null
        }

        // Retrieve the index of the second skill in the same chain list.
        val indexB: Int? = nodeA.list.findIndex(nodeB.value)
        if (indexB == null) {
            MessageLog.e(TAG, "[ERROR] compareVersions:: \"$b\" not found in \"$a\"'s list: ${nodeA.list}")
            return null
        }

        return if (indexA > indexB) a else b
    }

    /**
     * Calculate the version difference between two skills in the same upgrade chain.
     *
     * For example, if A is version 1 and B is version 3, the difference is -2 (1 - 3).
     *
     * @param a The name of the first skill to compare.
     * @param b The name of the second skill to compare against.
     * @return The version difference between the two skills on success, null otherwise.
     */
    fun getVersionDelta(a: String, b: String): Int? {
        if (checkSkillName(a) == null) {
            MessageLog.e(TAG, "[ERROR] getVersionDelta:: Failed to find skill in database: $a")
            return null
        }

        if (checkSkillName(b) == null) {
            MessageLog.e(TAG, "[ERROR] getVersionDelta:: Failed to find skill in database: $b")
            return null
        }

        val nodeA: DoublyLinkedListNode<String>? = skillStructure[a]
        if (nodeA == null) {
            MessageLog.e(TAG, "[ERROR] getVersionDelta:: \"$a\" not in skillStructure.")
            return null
        }

        val nodeB: DoublyLinkedListNode<String>? = skillStructure[b]
        if (nodeB == null) {
            MessageLog.e(TAG, "[ERROR] getVersionDelta:: \"$b\" not in skillStructure.")
            return null
        }

        // Retrieve the index of the first skill in its own chain list.
        val indexA: Int? = nodeA.list.findIndex(nodeA.value)
        if (indexA == null) {
            MessageLog.e(TAG, "[ERROR] getVersionDelta:: Error getting node index for \"$a\".")
            return null
        }

        // Retrieve the index of the second skill in the same chain list.
        val indexB: Int? = nodeA.list.findIndex(nodeB.value)
        if (indexB == null) {
            MessageLog.e(TAG, "[ERROR] getVersionDelta:: \"$b\" not found in \"$a\"'s list: ${nodeA.list}")
            return null
        }

        return indexA - indexB
    }

    /**
     * Retrieve a range of skill versions between two specified points in an upgrade chain.
     *
     * @param a The start version of the range.
     * @param b The end version of the range.
     * @return An ordered list of skill names from lowest to highest version, or an empty list on failure.
     */
    fun getVersionRange(a: String, b: String): List<String> {
        val higherVersionName: String? = compareVersions(a, b)
        if (higherVersionName == null) {
            MessageLog.w(TAG, "[WARN] getVersionRange:: compareVersions returned null.")
            return emptyList()
        }

        var startVersion: String = a
        var endVersion: String = b

        // Swap the start and end points if they were passed out of chronological order.
        if (higherVersionName == a) {
            startVersion = b
            endVersion = higherVersionName
        }

        var currentNode: DoublyLinkedListNode<String>? = skillStructure[startVersion]
        if (currentNode == null) {
            MessageLog.e(TAG, "[ERROR] getVersionRange:: \"$startVersion\" not in skillStructure.")
            return emptyList()
        }

        val rangeResults: MutableList<String> = mutableListOf()

        // Walk forward through the list from the start point to the end point.
        while (currentNode != null && currentNode.value != endVersion) {
            rangeResults.add(currentNode.value)
            currentNode = currentNode.next
        }

        // Manually include the end point version in the results.
        rangeResults.add(endVersion)

        return rangeResults.toList()
    }
}
