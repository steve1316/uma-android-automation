package com.steve1316.uma_android_automation.bot

import android.util.Log
import java.util.Collections
import kotlinx.coroutines.*

import com.steve1316.automation_library.utils.SettingsHelper
import com.steve1316.automation_library.utils.SQLiteSettingsManager
import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.automation_library.utils.TextUtils
import com.steve1316.automation_library.data.SharedData

import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.types.BoundingBox
import com.steve1316.uma_android_automation.types.SkillData
import com.steve1316.uma_android_automation.types.SkillType
import com.steve1316.uma_android_automation.types.TrackDistance
import com.steve1316.uma_android_automation.types.RunningStyle
import com.steve1316.uma_android_automation.types.Aptitude
import com.steve1316.uma_android_automation.utils.DoublyLinkedList
import com.steve1316.uma_android_automation.utils.DoublyLinkedListNode

import com.steve1316.uma_android_automation.components.*

/** Handles communication with the skills database.
 *
 * @param game Reference to the bot's Game instance.
 *
 * @property skillUpgradeChains A mapping of all skill names in the database to
 * lists of their upgrade chains. An upgrade chain is just all versions of the skill
 * in order from lowest to highest. See [SkillList.kt::generateSkillListEntries]
 * for an example implementation.
 */
class SkillDatabase (private val game: Game) {
    private val TAG: String = "[${MainActivity.loggerTag}]SkillDatabase"

    // Mapping of skill names to their associated datum.
    private val skillData: Map<String, SkillData> = loadSkillData()
    // A mapping of skill name to skill ID.
    private val skillNameToId: Map<String, Int> = skillData.mapValues { it.value.id }
    // Invert the mapping for reverse lookups.
    private val skillIdToName: Map<Int, String> = skillNameToId.entries.associate { it.value to it.key }
    // A structure used to map skill names to a doubly linked list representing
    // the upgrade/downgrade paths (versions) for each skill.
    private val skillStructure: Map<String, DoublyLinkedListNode<String>> = loadSkillStructure()
    val skillUpgradeChains: Map<String, List<String>> = skillStructure.mapValues { it.value.list.getValues() }

    companion object {
        private val TABLE_SKILLS = "skills"
        private val SKILLS_COLUMN_SKILL_ID = "skill_id"
        private val SKILLS_COLUMN_GENE_ID = "gene_id"
        private val SKILLS_COLUMN_NAME_EN = "name_en"
        private val SKILLS_COLUMN_DESC_EN = "desc_en"
        private val SKILLS_COLUMN_ICON_ID = "icon_id"
        private val SKILLS_COLUMN_COST = "cost"
        private val SKILLS_COLUMN_EVAL_PT = "eval_pt"
        private val SKILLS_COLUMN_PT_RATIO = "pt_ratio"
        private val SKILLS_COLUMN_RARITY = "rarity"
        private val SKILLS_COLUMN_CONDITION = "condition"
        private val SKILLS_COLUMN_PRECONDITION = "precondition"
        private val SKILLS_COLUMN_INHERITED = "inherited"
        private val SKILLS_COLUMN_COMMUNITY_TIER = "community_tier"
        private val SKILLS_COLUMN_VERSIONS = "versions"
        private val SKILLS_COLUMN_UPGRADE = "upgrade"
        private val SKILLS_COLUMN_DOWNGRADE = "downgrade"
        private val SIMILARITY_THRESHOLD = 0.7
    }

    /** Loads all skill data from the database.
     *
     * @return A mapping of skill names to SkillData objects.
     */
    private fun loadSkillData(): Map<String, SkillData> {
        val settingsManager = SQLiteSettingsManager(game.myContext)
        if (!settingsManager.initialize()) {
            MessageLog.e(TAG, "[ERROR] Database not available.")
            return emptyMap()
        }
        val database = settingsManager.getDatabase() ?: return emptyMap()

        try {
            val result: MutableMap<String, SkillData> = mutableMapOf()

            val query = "SELECT * FROM ${TABLE_SKILLS}"
            val cursor = database.rawQuery(query, null)
            cursor.use {
                if (cursor.moveToFirst()) {
                    do {
                        val idIndex: Int = cursor.getColumnIndexOrThrow(SKILLS_COLUMN_SKILL_ID)
                        val geneIdIndex: Int = cursor.getColumnIndexOrThrow(SKILLS_COLUMN_GENE_ID)
                        val nameIndex: Int = cursor.getColumnIndexOrThrow(SKILLS_COLUMN_NAME_EN)
                        val descriptionIndex: Int = cursor.getColumnIndexOrThrow(SKILLS_COLUMN_DESC_EN)
                        val iconIdIndex: Int = cursor.getColumnIndexOrThrow(SKILLS_COLUMN_ICON_ID)
                        val costIndex: Int = cursor.getColumnIndexOrThrow(SKILLS_COLUMN_COST)
                        val evalPtIndex: Int = cursor.getColumnIndexOrThrow(SKILLS_COLUMN_EVAL_PT)
                        val ptRatioIndex: Int = cursor.getColumnIndexOrThrow(SKILLS_COLUMN_PT_RATIO)
                        val rarityIndex: Int = cursor.getColumnIndexOrThrow(SKILLS_COLUMN_RARITY)
                        val conditionIndex: Int = cursor.getColumnIndexOrThrow(SKILLS_COLUMN_CONDITION)
                        val preconditionIndex: Int = cursor.getColumnIndexOrThrow(SKILLS_COLUMN_PRECONDITION)
                        val inheritedIndex: Int = cursor.getColumnIndexOrThrow(SKILLS_COLUMN_INHERITED)
                        val communityTierIndex: Int = cursor.getColumnIndexOrThrow(SKILLS_COLUMN_COMMUNITY_TIER)
                        val versionsIndex: Int = cursor.getColumnIndexOrThrow(SKILLS_COLUMN_VERSIONS)
                        val upgradeIndex: Int = cursor.getColumnIndexOrThrow(SKILLS_COLUMN_UPGRADE)
                        val downgradeIndex: Int = cursor.getColumnIndexOrThrow(SKILLS_COLUMN_DOWNGRADE)

                        val skillData = SkillData(
                            id = it.getInt(idIndex),
                            geneId = it.getInt(geneIdIndex),
                            name = it.getString(nameIndex),
                            description = it.getString(descriptionIndex),
                            iconId = it.getInt(iconIdIndex),
                            cost = it.getInt(costIndex),
                            evalPt = it.getInt(evalPtIndex),
                            ptRatio = it.getDouble(ptRatioIndex),
                            rarity = it.getInt(rarityIndex),
                            condition = it.getString(conditionIndex),
                            precondition = it.getString(preconditionIndex),
                            bIsInheritedUnique = it.getInt(inheritedIndex) == 1,
                            communityTier = if (it.isNull(communityTierIndex)) null else it.getInt(communityTierIndex),
                            versions = it.getString(versionsIndex),
                            upgrade = if (it.isNull(upgradeIndex)) null else it.getInt(upgradeIndex),
                            downgrade = if (it.isNull(downgradeIndex)) null else it.getInt(downgradeIndex),
                        )
                        result[skillData.name] = skillData
                    } while (cursor.moveToNext())
                }
            }

            return result.toMap()
        } catch (e: Exception) {
            MessageLog.e(TAG, "[ERROR] loadSkillData: ${e.message}")
            return emptyMap<String, SkillData>()
        } finally {
            settingsManager.close()
        }
    }

    /** Generates a mapping of skill names to a linked list of other versions of the skill.
     *
     * @return The generated mapping.
     */
    private fun loadSkillStructure(): Map<String, DoublyLinkedListNode<String>> {
        fun getVersionNames(id: Int?, bIsUpgrade: Boolean = false): List<String> {
            var id: Int? = id ?: return emptyList()

            val result: MutableList<String> = mutableListOf()

            while (id != null) {
                val name: String? = getSkillName(id)
                if (name == null) {
                    break
                }
                val tmpData: SkillData? = getSkillData(name)
                if (tmpData == null) {
                    MessageLog.e(TAG, "loadSkillStructure::getVersionNames: \"$name\" not in skillData.")
                    break
                }

                result.add(name)

                id = if (bIsUpgrade) tmpData.upgrade else tmpData.downgrade
            }

            return if (bIsUpgrade) result.toList() else result.reversed().toList()
        }

        val result: MutableMap<String, DoublyLinkedListNode<String>> = mutableMapOf()

        for ((name, skillData) in skillData) {
            // Get all downgrade/upgrade versions for this skill.
            val downgradeNames: List<String> = getVersionNames(skillData.downgrade)
            val upgradeNames: List<String> = getVersionNames(skillData.upgrade, bIsUpgrade = true)

            // Combine the version names, including this one, in order.
            val orderedNames: List<String> = downgradeNames + name + upgradeNames
            // We don't want to add anything if it is already in the structure.
            // The first occurrence of any skill in a skill's upgrade chain
            // will populate all entries for that chain in the structure.
            if (orderedNames.any { it in result }) {
                continue
            }

            // Now generate the linked list of versions.
            val list = DoublyLinkedList<String>()
            for (orderedName in orderedNames) {
                if (orderedName in result) {
                    MessageLog.e(TAG, "loadSkillStructure: \"$orderedName\" already in skillStructure!")
                    continue
                }
                val node: DoublyLinkedListNode<String> = list.append(orderedName)
                // Add this node's reference to the skill structure.
                result[orderedName] = node
            }
        }

        return result.toMap()
    }

    /** Uses fuzzy matching to find a skill name in the loaded skill data.
     *
     * @param name The skill name to search.
     *
     * @return On success, the corrected skill name. On fail, NULL.
     */
    fun fuzzySearchSkillName(name: String): String? {
        return TextUtils.matchStringInList(
            query = name,
            choices = skillData.keys.toList(),
            threshold = SIMILARITY_THRESHOLD,
        )
    }

    /** Checks if a skill name exists in our skill data.
     *
     * @param name The skill name to search.
     * @param fuzzySearch Whether to use fuzzy matching to broaden our search results.
     * If not specified, then only exact matches will be returned.
     *
     * @return On success, if [fuzzySearch] is enabled then the corrected skill
     * name is returned. If [fuzzySearch] is not enabled then the exact match is
     * returned. On fail, NULL is returned.
     */
    fun checkSkillName(name: String, fuzzySearch: Boolean = false): String? {
        // Check for an exact match first.
        if (name in skillData) {
            return name
        }
        // Fallback to a fuzzy search.
        return if (fuzzySearch) fuzzySearchSkillName(name) else null
    }

    /** Gets SkillData for a skill name.
     *
     * @param name The name to get.
     *
     * @return If [name] was found, the SkillData object. Otherwise, NULL.
     */
    fun getSkillData(name: String): SkillData? {
        var res: SkillData? = skillData[name]
        if (res == null) {
            MessageLog.w(TAG, "getSkillData: Skill name (\"$name\") not found. Attempting fuzzy search...")
            val tmpName: String? = checkSkillName(name, fuzzySearch = true)
            if (tmpName == null) {
                MessageLog.w(TAG, "getSkillData: No fuzzy match found for \"$name\".")
                return null
            }
            res = skillData[tmpName]
        }
        return res
    }

    /** Gets SkillData for a list of skill names.
     *
     * @param names The list of names to get.
     *
     * @return If all names were found, the list of SkillData objects. Otherwise, NULL.
     */
    fun getSkillData(names: List<String>): List<SkillData>? {
        val res: MutableList<SkillData> = mutableListOf()
        for (name in names) {
            val skillData: SkillData = getSkillData(name) ?: return null
            res.add(skillData)
        }
        return res.toList()
    }

    /** Returns the skill name for a skill ID.
     *
     * @param id The ID of the skill to lookup.
     *
     * @return On success, the name of the skill. On failure, NULL.
     */
    fun getSkillName(id: Int): String? {
        if (id !in skillIdToName) {
            MessageLog.w(TAG, "getSkillName: Skill ID ($id) not found.")
        }
        return skillIdToName[id]
    }

    /** Returns the skill ID for a skill name.
     *
     * @param name The name of the skill to lookup.
     *
     * @return On success, the ID of the skill. On failure, NULL.
     */
    fun getSkillId(name: String): Int? {
        var res: Int? = skillNameToId[name]
        if (res == null) {
            MessageLog.w(TAG, "getSkillId: Skill name (\"$name\") not found. Attempting fuzzy search...")
            val tmpName: String? = checkSkillName(name, fuzzySearch = true)
            if (tmpName == null) {
                MessageLog.w(TAG, "getSkillId: No fuzzy match found for \"$name\".")
                return null
            }
            res = skillNameToId[tmpName]
            if (res == null) {
                // This indicates some unknown error where skillNameToId somehow
                // doesn't contain the same keys as skillData.
                MessageLog.e(TAG, "getSkillId: skillData and skillNameToId keys are not the same.")
            }
        }
        return res
    }

    /** Returns a list of skill names which must be purchased before the passed skill name becomes available.
     *
     * @param name The skill that we wish to purchase.
     *
     * @return A list of skill names which will need to be purchased for the passed
     * skill to become available.
     */
    fun getRequiredUpgrades(name: String): List<String> {
        if (checkSkillName(name) == null) {
            MessageLog.e(TAG, "getRequiredUpgrades: Failed to find skill in database: $name")
            return emptyList()
        }

        var node: DoublyLinkedListNode<String>? = skillStructure[name]
        if (node == null) {
            MessageLog.e(TAG, "getRequiredUpgrades: \"$name\" not in skillStructure.")
            return emptyList()
        }

        val result: MutableList<String> = mutableListOf()
        // Walk backward until there are no previous versions.
        while(node != null) {
            result.add(node.value)
            node = node.prev
        }
        // Make sure to reverse to put it in order from first to last.
        return result.reversed().toList()
    }

    /** Returns the direct upgrade skill name for the passed skill.
     *
     * @param name The skill whose upgrade we want to find.
     *
     * @return The upgraded skill name if one exists, otherwise NULL.
     */
    fun getUpgrade(name: String): String? {
        if (checkSkillName(name) == null) {
            MessageLog.e(TAG, "getUpgrade: Failed to find skill in database: $name")
            return null
        }

        var node: DoublyLinkedListNode<String>? = skillStructure[name]
        if (node == null) {
            MessageLog.e(TAG, "getUpgrade: \"$name\" not in skillStructure.")
            return null
        }
        return node.next?.value
    }

    /** Returns a list of skill names which are upgrades to the passed skill.
     *
     * @param name The skill whose upgrades we want to find.
     *
     * @return A list of the upgraded skill names.
     */
    fun getUpgrades(name: String): List<String> {
        if (checkSkillName(name) == null) {
            MessageLog.e(TAG, "getUpgrades: Failed to find skill in database: $name")
            return emptyList()
        }

        var node: DoublyLinkedListNode<String>? = skillStructure[name]
        if (node == null) {
            MessageLog.e(TAG, "getUpgrades: \"$name\" not in skillStructure.")
            return emptyList()
        }

        val result: MutableList<String> = mutableListOf()
        // Walk forward until there are no more versions.
        while(node != null) {
            result.add(node.value)
            node = node.next
        }
        // This list is already in the correct order.
        return result.toList()
    }

    /** Returns the direct downgrade skill name for the passed skill.
     *
     * @param name The skill whose downgrade we want to find.
     *
     * @return The downgraded skill name if one exists, otherwise NULL.
     */
    fun getDowngrade(name: String): String? {
        if (checkSkillName(name) == null) {
            MessageLog.e(TAG, "getDowngrade: Failed to find skill in database: $name")
            return null
        }

        var node: DoublyLinkedListNode<String>? = skillStructure[name]
        if (node == null) {
            MessageLog.e(TAG, "getDowngrade: \"$name\" not in skillStructure.")
            return null
        }
        return node.prev?.value
    }

    /** Returns a list of skill names which are downgrades to the passed skill.
     *
     * @param name The skill whose downgrades we want to find.
     *
     * @return A list of the upgraded skill names.
     */
    fun getDowngrades(name: String): List<String> {
        if (checkSkillName(name) == null) {
            MessageLog.e(TAG, "getDowngrades: Failed to find skill in database: $name")
            return emptyList()
        }

        var node: DoublyLinkedListNode<String>? = skillStructure[name]
        if (node == null) {
            MessageLog.e(TAG, "getDowngrades: \"$name\" not in skillStructure.")
            return emptyList()
        }

        val result: MutableList<String> = mutableListOf()
        // Walk forward until there are no more versions.
        while(node != null) {
            result.add(node.value)
            node = node.prev
        }
        // This list is already in the correct order.
        return result.toList()
    }

    /** Compare the version of two skills and return which ever is higher.
     *
     * @param a The first skill name to compare.
     * @param b The skill name to compare against.
     *
     * @return On success, the skill name which is a higher version. On failure, NULL.
     */
    fun compareVersions(a: String, b: String): String? {
        if (checkSkillName(a) == null) {
            MessageLog.e(TAG, "compareVersions: Failed to find skill in database: $a")
            return null
        }

        if (checkSkillName(b) == null) {
            MessageLog.e(TAG, "compareVersions: Failed to find skill in database: $b")
            return null
        }

        val nodeA: DoublyLinkedListNode<String>? = skillStructure[a]
        if (nodeA == null) {
            MessageLog.e(TAG, "compareVersions: \"$a\" not in skillStructure.")
            return null
        }

        val nodeB: DoublyLinkedListNode<String>? = skillStructure[b]
        if (nodeB == null) {
            MessageLog.e(TAG, "compareVersions: \"$b\" not in skillStructure.")
            return null
        }

        // Get index of A in its own list.
        val indexA: Int? = nodeA.list.findIndex(nodeA.value)
        if (indexA == null) {
            MessageLog.e(TAG, "compareVersions: Error getting node index for \"$a\".")
            return null
        }
        
        // Now get the index of B in A's list.
        val indexB: Int? = nodeA.list.findIndex(nodeB.value)
        if (indexB == null) {
            MessageLog.e(TAG, "compareVersions: \"$b\" not found in \"$a\"'s list: ${nodeA.list}")
            return null
        }
        
        return if (indexA > indexB) a else b
    }

    /** Compare the version of two skills the difference.
     *
     * For example, if A is version 1 and B is version 3, then we would return -2
     * since A - B = 1 - 3 = -2.
     *
     * This is useful if we want to determine how many upgrades it would take
     * to reach a certain version of a skill.
     *
     * @param a The first skill name to compare.
     * @param b The skill name to compare against.
     *
     * @return On success, the difference between versions a and b. On failure, NULL.
     */
    fun getVersionDelta(a: String, b: String): Int? {
        if (checkSkillName(a) == null) {
            MessageLog.e(TAG, "compareVersions: Failed to find skill in database: $a")
            return null
        }

        if (checkSkillName(b) == null) {
            MessageLog.e(TAG, "compareVersions: Failed to find skill in database: $b")
            return null
        }

        val nodeA: DoublyLinkedListNode<String>? = skillStructure[a]
        if (nodeA == null) {
            MessageLog.e(TAG, "compareVersions: \"$a\" not in skillStructure.")
            return null
        }

        val nodeB: DoublyLinkedListNode<String>? = skillStructure[b]
        if (nodeB == null) {
            MessageLog.e(TAG, "compareVersions: \"$b\" not in skillStructure.")
            return null
        }

        // Get index of A in its own list.
        val indexA: Int? = nodeA.list.findIndex(nodeA.value)
        if (indexA == null) {
            MessageLog.e(TAG, "compareVersions: Error getting node index for \"$a\".")
            return null
        }
        
        // Now get the index of B in A's list.
        val indexB: Int? = nodeA.list.findIndex(nodeB.value)
        if (indexB == null) {
            MessageLog.e(TAG, "compareVersions: \"$b\" not found in \"$a\"'s list: ${nodeA.list}")
            return null
        }
        
        return indexA - indexB
    }

    /** Returns a slice of the versions of a skill.
     *
     * @param a The start version of the slice.
     * @param b The end version of the slice.
     *
     * @return The list of versions, ordered from lowest to highest.
     * If no full slice could be generated, then an empty list is returned.
     */
    fun getVersionRange(a: String, b: String): List<String> {
        val higherVersionName: String? = compareVersions(a, b)
        if (higherVersionName == null) {
            MessageLog.w(TAG, "getVersionRange: compareVersions returned NULL.")
            return emptyList()
        }

        var a: String = a
        var b: String = b

        // If A is higher, we want to swap A and B for future operations.
        if (higherVersionName == a) {
            a = b
            b = higherVersionName
        }

        var node: DoublyLinkedListNode<String>? = skillStructure[a]
        if (node == null) {
            MessageLog.e(TAG, "getVersionRange: \"$a\" not in skillStructure.")
            return emptyList()
        }

        val result: MutableList<String> = mutableListOf()
        while (node != null && node.value != b) {
            result.add(node.value)
            node = node.next
        }
        // Need to manually add our B value here.
        result.add(b)
        return result.toList()
    }
}
