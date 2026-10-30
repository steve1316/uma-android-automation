package com.steve1316.uma_android_automation.types

import android.graphics.Bitmap
import com.steve1316.automation_library.data.SharedData
import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.automation_library.utils.SettingsHelper
import com.steve1316.automation_library.utils.TextUtils
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.uma_android_automation.components.ButtonClose
import com.steve1316.uma_android_automation.components.ButtonConfirm
import com.steve1316.uma_android_automation.components.ButtonConfirmUse
import com.steve1316.uma_android_automation.components.ButtonExchange
import com.steve1316.uma_android_automation.components.ButtonSkillUp
import com.steve1316.uma_android_automation.components.ButtonTrainingItems
import com.steve1316.uma_android_automation.components.CheckboxDoNotShowAgain
import com.steve1316.uma_android_automation.components.CheckboxShopItem
import com.steve1316.uma_android_automation.components.IconDialogScrollListBottomRight
import com.steve1316.uma_android_automation.components.IconDialogScrollListTopLeft
import com.steve1316.uma_android_automation.components.LabelOnSale
import com.steve1316.uma_android_automation.components.LabelPurchased
import com.steve1316.uma_android_automation.types.Mood
import com.steve1316.uma_android_automation.types.StatName
import com.steve1316.uma_android_automation.types.Trainee
import com.steve1316.uma_android_automation.utils.CropMemory
import com.steve1316.uma_android_automation.utils.ScrollList
import com.steve1316.uma_android_automation.utils.ScrollListEntry
import com.steve1316.uma_android_automation.utils.ScrollListEntryDetectionConfig
import com.steve1316.uma_android_automation.utils.toGrayMat
import org.json.JSONArray
import org.opencv.core.Mat
import org.opencv.core.Point

/** Wait after each Shop list swipe, on top of the wait inside the swipe. The list stops dead after the swipe's edge tap, as the skill list does. */
private const val SHOP_LIST_SETTLE_SECONDS = 0.2

/**
 * Blur applied before the Shop list's background flood fill. At the default 7 the glow of a NEW badge turns into a ramp gentle enough for the fill
 * to climb into the card, mostly on the last card where the list's background is darkest, and that card is then missed.
 */
private const val SHOP_LIST_BLUR_SIZE = 5

/**
 * Store a single scanned item's entry, name, and disabled state.
 *
 * @property entry The [ScrollListEntry] of the item.
 * @property itemName The name of the item.
 * @property isDisabled Whether the item is disabled in the UI.
 */
data class ScannedItem(val entry: ScrollListEntry, val itemName: String, val isDisabled: Boolean)

/**
 * Store information about a single item in the Trackblazer scenario.
 *
 * @property price The cost of the item in the Shop.
 * @property effect A brief description of the item's effect.
 * @property isQuickUsage Whether the item can be used directly from the Training Items dialog.
 * @property category The category the item belongs to for UI and organization.
 */
data class TrackblazerItemInfo(val price: Int, val effect: String, val isQuickUsage: Boolean, val category: String)

/**
 * Handle interaction with the item shop list in the Trackblazer scenario.
 *
 * @property game Reference to the bot's [Game] instance.
 */
class TrackblazerShopList(private val game: Game) {
    companion object {
        private val TAG: String = "[${MainActivity.loggerTag}]TrackblazerShopList"

        /**
         * Pure calculation function that determines which items to purchase given a budget.
         *
         * This implements a greedy algorithm that iterates through a priority-ordered list,
         * purchasing items (up to inventory limits) in order of priority while respecting
         * the coin budget.
         *
         * @param priorityList Ordered list of item names to buy (highest priority first).
         * @param availableItems Items available in the shop as (name, price) pairs.
         * @param inventoryLimits Maximum quantity to buy for each item.
         * @param coins Available coins to spend.
         * @param excludedItems Items to skip even if in the priority list.
         * @return List of (name, price) pairs representing items that should be purchased.
         */
        fun calculatePurchases(
            priorityList: List<String>,
            availableItems: List<Pair<String, Int>>,
            inventoryLimits: Map<String, Int>,
            coins: Int,
            excludedItems: List<String> = emptyList(),
        ): List<Pair<String, Int>> {
            if (priorityList.isEmpty() || availableItems.isEmpty()) return emptyList()

            val filteredAvailable = availableItems.filter { (name, _) -> name !in excludedItems }.toMutableList()
            val result = mutableListOf<Pair<String, Int>>()
            var remainingCoins = coins

            for (item in priorityList) {
                val limit = inventoryLimits[item] ?: 0
                if (limit <= 0) continue

                var boughtCount = 0
                while (boughtCount < limit) {
                    val availableIndex = filteredAvailable.indexOfFirst { it.first == item }
                    if (availableIndex == -1) break

                    val price = filteredAvailable[availableIndex].second
                    if (remainingCoins >= price) {
                        result.add(Pair(item, price))
                        remainingCoins -= price
                        filteredAvailable.removeAt(availableIndex)
                        boughtCount++
                    } else {
                        break
                    }
                }
            }

            return result
        }
    }

    /** Callback provider to retrieve the current inventory summary. */
    var getInventorySummaryCallback: (() -> String)? = null

    /** List of names for items that grant stats. */
    val statItemNames get() = shopItems.filter { it.value.category == "Stats" }.keys.toList()

    /** List of names for items that restore energy. */
    val energyItemNames get() = listOf("Vita 65", "Vita 40", "Vita 20", "Energy Drink MAX")

    /** List of names for items that heal bad status conditions. */
    val badConditionHealItemNames get() = shopItems.filter { it.value.category == "Heal Bad Conditions" }.keys.toList()

    /** Mapping of shop items to their price, effect, and whether they are allowed for quick usage. */
    val shopItems: Map<String, TrackblazerItemInfo> =
        mapOf(
            // Stats
            "Speed Notepad" to TrackblazerItemInfo(10, "Speed +3", true, "Stats"),
            "Stamina Notepad" to TrackblazerItemInfo(10, "Stamina +3", true, "Stats"),
            "Power Notepad" to TrackblazerItemInfo(10, "Power +3", true, "Stats"),
            "Guts Notepad" to TrackblazerItemInfo(10, "Guts +3", true, "Stats"),
            "Wit Notepad" to TrackblazerItemInfo(10, "Wit +3", true, "Stats"),
            "Speed Manual" to TrackblazerItemInfo(15, "Speed +7", true, "Stats"),
            "Stamina Manual" to TrackblazerItemInfo(15, "Stamina +7", true, "Stats"),
            "Power Manual" to TrackblazerItemInfo(15, "Power +7", true, "Stats"),
            "Guts Manual" to TrackblazerItemInfo(15, "Guts +7", true, "Stats"),
            "Wit Manual" to TrackblazerItemInfo(15, "Wit +7", true, "Stats"),
            "Speed Scroll" to TrackblazerItemInfo(30, "Speed +15", true, "Stats"),
            "Stamina Scroll" to TrackblazerItemInfo(30, "Stamina +15", true, "Stats"),
            "Power Scroll" to TrackblazerItemInfo(30, "Power +15", true, "Stats"),
            "Guts Scroll" to TrackblazerItemInfo(30, "Guts +15", true, "Stats"),
            "Wit Scroll" to TrackblazerItemInfo(30, "Wit +15", true, "Stats"),
            // Energy and Motivation
            "Vita 20" to TrackblazerItemInfo(35, "Energy +20", false, "Energy and Motivation"),
            "Vita 40" to TrackblazerItemInfo(55, "Energy +40", false, "Energy and Motivation"),
            "Vita 65" to TrackblazerItemInfo(75, "Energy +65", false, "Energy and Motivation"),
            "Royal Kale Juice" to TrackblazerItemInfo(70, "Energy +100, Motivation -1", false, "Energy and Motivation"),
            "Energy Drink MAX" to TrackblazerItemInfo(30, "Maximum energy +4, Energy +5", false, "Energy and Motivation"),
            "Energy Drink MAX EX" to TrackblazerItemInfo(50, "Maximum energy +8", false, "Energy and Motivation"),
            "Plain Cupcake" to TrackblazerItemInfo(30, "Motivation +1", true, "Energy and Motivation"),
            "Berry Sweet Cupcake" to TrackblazerItemInfo(55, "Motivation +2", true, "Energy and Motivation"),
            // Bond
            "Yummy Cat Food" to TrackblazerItemInfo(10, "Yayoi Akikawa's bond +5", true, "Bond"),
            "Grilled Carrots" to TrackblazerItemInfo(40, "All Support card bonds +5", true, "Bond"),
            // Get Good Conditions
            "Pretty Mirror" to TrackblazerItemInfo(150, "Get Charming ○ status effect", true, "Get Good Conditions"),
            "Reporter's Binoculars" to TrackblazerItemInfo(150, "Get Hot Topic status effect", true, "Get Good Conditions"),
            "Master Practice Guide" to TrackblazerItemInfo(150, "Get Practice Perfect ○ status effect", true, "Get Good Conditions"),
            "Scholar's Hat" to TrackblazerItemInfo(280, "Get Fast Learner status effect", true, "Get Good Conditions"),
            // Heal Bad Conditions
            "Fluffy Pillow" to TrackblazerItemInfo(15, "Heal Night Owl", true, "Heal Bad Conditions"),
            "Pocket Planner" to TrackblazerItemInfo(15, "Heal Slacker", true, "Heal Bad Conditions"),
            "Rich Hand Cream" to TrackblazerItemInfo(15, "Heal Skin Outbreak", true, "Heal Bad Conditions"),
            "Smart Scale" to TrackblazerItemInfo(15, "Heal Slow Metabolism", true, "Heal Bad Conditions"),
            "Aroma Diffuser" to TrackblazerItemInfo(15, "Heal Migraine", true, "Heal Bad Conditions"),
            "Practice Drills DVD" to TrackblazerItemInfo(15, "Heal Practice Poor", true, "Heal Bad Conditions"),
            "Miracle Cure" to TrackblazerItemInfo(40, "Heal all negative status effects", true, "Heal Bad Conditions"),
            // Training Facilities
            "Speed Training Application" to TrackblazerItemInfo(150, "Speed Training Level +1", true, "Training Facilities"),
            "Stamina Training Application" to TrackblazerItemInfo(150, "Stamina Training Level +1", true, "Training Facilities"),
            "Power Training Application" to TrackblazerItemInfo(150, "Power Training Level +1", true, "Training Facilities"),
            "Guts Training Application" to TrackblazerItemInfo(150, "Guts Training Level +1", true, "Training Facilities"),
            "Wit Training Application" to TrackblazerItemInfo(150, "Wit Training Level +1", true, "Training Facilities"),
            // Training Effects
            "Coaching Megaphone" to TrackblazerItemInfo(40, "Training bonus +20% for 4 turns", false, "Training Effects"),
            "Motivating Megaphone" to TrackblazerItemInfo(55, "Training bonus +40% for 3 turns", false, "Training Effects"),
            "Empowering Megaphone" to TrackblazerItemInfo(70, "Training bonus +60% for 2 turns", false, "Training Effects"),
            "Speed Ankle Weights" to TrackblazerItemInfo(50, "Speed training bonus +50%, Energy consumption +20% (One turn)", false, "Training Effects"),
            "Stamina Ankle Weights" to TrackblazerItemInfo(50, "Stamina training bonus +50%, Energy consumption +20% (One turn)", false, "Training Effects"),
            "Power Ankle Weights" to TrackblazerItemInfo(50, "Power training bonus +50%, Energy consumption +20% (One turn)", false, "Training Effects"),
            "Guts Ankle Weights" to TrackblazerItemInfo(50, "Guts training bonus +50%, Energy consumption +20% (One turn)", false, "Training Effects"),
            "Wit Ankle Weights" to TrackblazerItemInfo(50, "Wit training bonus +50%, Energy consumption +20% (One turn)", false, "Training Effects"),
            "Good-Luck Charm" to TrackblazerItemInfo(40, "Training failure rate set to 0% (One turn)", false, "Training Effects"),
            "Reset Whistle" to TrackblazerItemInfo(20, "Shuffle support card distribution", false, "Training Effects"),
            // Races
            "Artisan Cleat Hammer" to TrackblazerItemInfo(25, "Race bonus +20% (One turn)", false, "Races"),
            "Master Cleat Hammer" to TrackblazerItemInfo(40, "Race bonus +35% (One turn)", false, "Races"),
            "Glow Sticks" to TrackblazerItemInfo(15, "Race fan gain +50% (One turn)", false, "Races"),
        )

    /** Items to not purchase from the Shop. */
    private val excludedItemsString = SettingsHelper.getStringSetting("scenarioOverrides", "trackblazerExcludedItems", "[]")

    /** Whether the shop currently has a sale active. */
    private var isShopOnSale: Boolean = false

    /** Whether the "Do not show again" checkbox has been clicked during the purchase flow. */
    private var hasClickedDoNotShowAgain: Boolean = false

    /** The item names read for the current frame with one batched OCR call, by entry index. Only names that matched a known item are kept. */
    private var frameNames: Map<Int, String> = emptyMap()

    /** The held amounts read for the current frame in the same OCR call as [frameNames], by entry index. Only rows with a plus button and a numeric read are kept. */
    private var frameAmounts: Map<Int, Int> = emptyMap()

    /** The name crops read during [buyItems], with their names, so its purchase pass recognizes each row by its pixels instead of reading it again. Null outside [buyItems]. */
    private var nameCrops: CropMemory<String>? = null

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Scroll through the Shop list to identify available items.
     *
     * @return True if the scrolling process finished normally, false otherwise.
     */
    fun scrollShop(): Boolean {
        val itemNameMap = mutableMapOf<Int, String>()
        return processItemsWithFallback(
            keyExtractor = { entry ->
                // Detect the item name for each entry.
                val name = getShopItemName(entry, isEntryDisabled(entry.bitmap))
                if (name != null) itemNameMap[entry.index] = name
                name
            },
        ) { entry ->
            // Check if the item is buyable or disabled.
            val isDisabled = isEntryDisabled(entry.bitmap)
            val itemName = itemNameMap[entry.index] ?: getShopItemName(entry, isDisabled)
            if (itemName != null) {
                val itemPrice = getShopItemPrice(itemName, entry.bitmap)
                MessageLog.i(TAG, "[INFO] Detected Shop Item: \"$itemName\" with price $itemPrice at index ${entry.index}.")
            }
            false
        }
    }

    /**
     * Extract the item name from a cropped Shop list entry bitmap.
     *
     * @param entry The [ScrollListEntry] of the item.
     * @param isDisabled Whether the item's checkbox or plus button is disabled.
     * @return The detected item name if found, or null otherwise.
     */
    fun getShopItemName(entry: ScrollListEntry, isDisabled: Boolean = false): String? {
        frameNames[entry.index]?.let { return it }
        val bitmap = entry.bitmap
        val (refPoint, isPurchased) = findNameReference(entry) ?: return null
        val nameBBox = nameBox(refPoint)

        // Create a cropped bitmap of the name region.
        val croppedName = game.imageUtils.createSafeBitmap(bitmap, nameBBox, "ShopItemName")
        if (croppedName == null) {
            if (game.debugMode) MessageLog.e(TAG, "[ERROR] getShopItemName:: Failed to crop name region for reference point at $refPoint.")
            return null
        }

        var detectedText = ""

        if (!isDisabled && !isPurchased) {
            // Perform OCR with thresholding first for enabled items. Skipped for disabled/purchased rows whose greyed text thresholding washes out.
            detectedText =
                game.imageUtils.performOCROnRegion(
                    croppedName,
                    0,
                    0,
                    croppedName.width,
                    croppedName.height,
                    useThreshold = true,
                    useGrayscale = true,
                    scale = 2.0,
                    ocrEngine = "mlkit",
                    debugName = "ShopItemNameOCR_Threshold",
                )
        }

        if (detectedText.isEmpty()) {
            if (game.debugMode && !isDisabled && !isPurchased) MessageLog.d(TAG, "[DEBUG] getShopItemName:: Threshold OCR failed for $refPoint, trying without thresholding...")
            // Fallback to OCR without thresholding.
            detectedText =
                game.imageUtils.performOCROnRegion(
                    croppedName,
                    0,
                    0,
                    croppedName.width,
                    croppedName.height,
                    useThreshold = false,
                    useGrayscale = true,
                    scale = 2.0,
                    ocrEngine = "mlkit",
                    debugName = "ShopItemNameOCR_NoThreshold",
                )
        }

        if (detectedText.isEmpty()) {
            if (game.debugMode) MessageLog.w(TAG, "[WARN] getShopItemName:: Parsed empty string for Shop Item Name at $refPoint after both OCR passes.")
            return null
        }

        // Perform fuzzy matching against known shop item names.
        val matchedName = TextUtils.matchStringInList(detectedText, shopItems.keys.toList(), threshold = 0.8)
        if (matchedName == null) {
            if (game.debugMode) MessageLog.w(TAG, "[WARN] getShopItemName:: Failed to match text \"$detectedText\" to any known item.")
        }
        return matchedName ?: detectedText
    }

    /**
     * Finds the point an item's name is placed from: its checkbox, the preserved reference point, its plus button, or its "Purchased" badge.
     *
     * @param entry The [ScrollListEntry] of the item.
     * @return The reference point and whether it is the "Purchased" badge, or null when none was found.
     */
    private fun findNameReference(entry: ScrollListEntry): Pair<Point, Boolean>? {
        val bitmap = entry.bitmap
        // Whether the row was anchored via the "Purchased" badge. Purchased names are black text on a grey background, which thresholding washes out.
        var isPurchased = false
        // Find the item's checkbox to use as a reference point.
        var refPoint = CheckboxShopItem.findImageWithBitmap(game.imageUtils, bitmap)

        if (refPoint == null) {
            if (entry.refX != null && entry.refY != null) {
                // Use preserved reference point if checkbox isn't found.
                refPoint = Point(entry.refX.toDouble(), entry.refY.toDouble())
                if (game.debugMode) MessageLog.d(TAG, "[DEBUG] findNameReference:: Using preserved reference point for item name detection: $refPoint")
            } else {
                // Fallback: Try to find the plus button if the checkbox isn't there (e.g., in the inventory dialog).
                refPoint = ButtonSkillUp.findImageWithBitmap(game.imageUtils, bitmap)
                if (refPoint != null && game.debugMode) {
                    MessageLog.d(TAG, "[DEBUG] findNameReference:: Using plus button as reference for item name detection.")
                }
                // Fallback: Try the "Purchased" badge for already-purchased items, which have neither a checkbox nor a plus button.
                if (refPoint == null) {
                    refPoint = LabelPurchased.findImageWithBitmap(game.imageUtils, bitmap)
                    if (refPoint != null) {
                        isPurchased = true
                        if (game.debugMode) MessageLog.d(TAG, "[DEBUG] findNameReference:: Using purchased badge as reference for item name detection.")
                    }
                }
            }
        }

        if (refPoint == null) {
            if (game.debugMode) MessageLog.e(TAG, "[ERROR] findNameReference:: Failed to find any reference point (checkbox, plus button, or purchased badge) for this Item.")
            return null
        }
        return refPoint to isPurchased
    }

    /**
     * The box an item's name sits in, placed from its reference point.
     *
     * @param refPoint The reference point from [findNameReference], in the entry's pixels.
     * @return The name box in the entry's pixels.
     */
    private fun nameBox(refPoint: Point): BoundingBox =
        BoundingBox(
            x = game.imageUtils.relX(refPoint.x, -850).coerceAtLeast(0),
            y = game.imageUtils.relY(refPoint.y, -75).coerceAtLeast(0),
            w = game.imageUtils.relWidth(750),
            h = game.imageUtils.relHeight(60),
        )

    /**
     * The box an item's held amount sits in, placed from its plus button.
     *
     * @param plusPoint The plus button's center, in the entry's pixels.
     * @return The amount box in the entry's pixels.
     */
    private fun amountBox(plusPoint: Point): BoundingBox =
        BoundingBox(x = game.imageUtils.relX(plusPoint.x, -585), y = game.imageUtils.relY(plusPoint.y, -15), w = game.imageUtils.relWidth(55), h = game.imageUtils.relHeight(50))

    /**
     * Reads the names of every entry on a frame with one OCR call and keeps the ones that match a known item. [getShopItemName] returns these first and reads an
     * entry on its own only when its batched read did not match. With [bAmounts], each row's held amount is read in the same call for [getItemAmount].
     *
     * Inside [buyItems], a row whose name crop matches one already read is named from [nameCrops] instead. A frame with every row matched costs no OCR.
     *
     * @param frameEntries The entries detected on the frame. Each holds its own crop.
     * @param bAmounts Whether to read the held amounts too, which only the Training Items dialog shows.
     */
    private fun readFrameNames(frameEntries: List<ScrollListEntry>, bAmounts: Boolean) {
        val memory: CropMemory<String>? = nameCrops.takeIf { !bAmounts }
        val recalled = mutableMapOf<Int, String>()
        // The grayscale name crops of the rows not recalled, kept to remember them once read.
        val unread = mutableMapOf<Int, Mat>()
        val fields = mutableMapOf<String, Pair<Bitmap, BoundingBox>>()
        for (entry in frameEntries) {
            findNameReference(entry)?.let { (refPoint, _) ->
                val box: BoundingBox = nameBox(refPoint)
                val crop: Mat? =
                    memory?.let { game.imageUtils.createSafeBitmap(entry.bitmap, box, "ShopItemNameCrop") }?.let { bitmap ->
                        toGrayMat(bitmap).also { if (bitmap !== entry.bitmap) bitmap.recycle() }
                    }
                val name: String? = crop?.let { memory.find(it) }
                if (name != null) {
                    crop.release()
                    recalled[entry.index] = name
                } else {
                    crop?.let { unread[entry.index] = it }
                    fields["name${entry.index}"] = entry.bitmap to box
                }
            }
            if (bAmounts) ButtonSkillUp.findImageWithBitmap(game.imageUtils, entry.bitmap)?.let { fields["amount${entry.index}"] = entry.bitmap to amountBox(it) }
        }
        val texts: Map<String, String> = if (fields.isEmpty()) emptyMap() else game.imageUtils.readFields(fields)
        val itemNames = shopItems.keys.toList()
        val read: Map<Int, String> =
            frameEntries.mapNotNull { entry -> texts["name${entry.index}"]?.let { TextUtils.matchStringInList(it, itemNames, threshold = 0.8) }?.let { entry.index to it } }.toMap()
        // Only a crop whose read matched a known item is remembered, so a misread is never recalled.
        for ((index, crop) in unread) {
            val name: String? = read[index]
            if (name != null && memory != null) memory.add(crop, name) else crop.release()
        }
        frameNames = recalled + read
        frameAmounts =
            frameEntries.mapNotNull { entry -> texts["amount${entry.index}"]?.replace(Regex("[^0-9]"), "")?.toIntOrNull()?.let { entry.index to it.coerceIn(0, 5) } }.toMap()
    }

    /**
     * Extract the item amount from a cropped Shop list entry bitmap.
     *
     * @param entry The [ScrollListEntry] of the item.
     * @param isDisabled Whether the item's plus button is disabled.
     * @return The detected amount of the item on-hand.
     */
    fun getItemAmount(entry: ScrollListEntry, isDisabled: Boolean): Int {
        frameAmounts[entry.index]?.let { return it }
        val bitmap = entry.bitmap
        // Use the plus button as a reference point for the amount detection.
        val refPoint = ButtonSkillUp.findImageWithBitmap(game.imageUtils, bitmap) ?: return 1

        // Perform OCR on the amount region relative to the plus button.
        val amountBBox = amountBox(refPoint)
        val amountText =
            game.imageUtils.performOCROnRegion(
                bitmap,
                amountBBox.x,
                amountBBox.y,
                amountBBox.w,
                amountBBox.h,
                useThreshold = false,
                useGrayscale = true,
                scale = 2.0,
                ocrEngine = "mlkit",
                debugName = "ItemAmountOCR",
            )

        return try {
            // Extract numeric value and clamp to valid range.
            val cleanedText = amountText.replace(Regex("[^0-9]"), "")
            if (cleanedText.isEmpty()) {
                1
            } else {
                cleanedText.toInt().coerceIn(0, 5)
            }
        } catch (e: NumberFormatException) {
            1
        }
    }

    /**
     * Extract the item price from a cropped Shop list entry bitmap.
     *
     * @param itemName The name of the item.
     * @param bitmap A bitmap of a single cropped Shop list entry.
     * @return The item price if detected, or original price otherwise.
     */
    fun getShopItemPrice(itemName: String, bitmap: Bitmap): Int {
        val originalPrice = shopItems[itemName]?.price ?: 0

        // Use the flag to handle discounted prices instead of repeated checks.
        if (!isShopOnSale) {
            return originalPrice
        }

        // Find the item's checkbox to use as a reference point.
        val checkboxPoint = CheckboxShopItem.findImageWithBitmap(game.imageUtils, bitmap) ?: return originalPrice

        // Calculate the price bounding box relative to the checkbox.
        val priceBBox =
            BoundingBox(
                x = game.imageUtils.relX(checkboxPoint.x, -405),
                y = game.imageUtils.relY(checkboxPoint.y, -15),
                w = game.imageUtils.relWidth(100),
                h = game.imageUtils.relHeight(60),
            )

        // Create a cropped bitmap of the price region.
        val croppedPrice = game.imageUtils.createSafeBitmap(bitmap, priceBBox, "ShopItemPrice") ?: return originalPrice

        // Extract the price using OCR.
        val detectedText =
            game.imageUtils.performOCROnRegion(
                croppedPrice,
                0,
                0,
                croppedPrice.width,
                croppedPrice.height,
                useThreshold = true,
                useGrayscale = true,
                scale = 2.0,
                ocrEngine = "mlkit",
                debugName = "ShopItemPriceOCR",
            )

        if (detectedText.isEmpty()) {
            MessageLog.w(TAG, "[WARN] getShopItemPrice:: Parsed empty string for Shop Item Price.")
            return originalPrice
        }

        // Remove non-numeric characters and parse.
        val cleanedText = detectedText.replace(Regex("[^0-9]"), "")
        return cleanedText.toIntOrNull() ?: originalPrice
    }

    /** Use bought items in the inventory immediately if they belong to targeted categories. */
    fun quickUseItems() {
        MessageLog.i(TAG, "[INFO] Determining if any items can be used right away.")
        var anyUsed = false

        val itemNameMapInQuickUse = mutableMapOf<Int, String>()
        processItemsWithFallback(
            keyExtractor = { entry ->
                val name = getShopItemName(entry, isEntryDisabled(entry.bitmap))
                if (name != null) itemNameMapInQuickUse[entry.index] = name
                name
            },
        ) { entry ->
            // Check if the item is eligible for quick usage.
            val isDisabled = isEntryDisabled(entry.bitmap)
            val itemName = itemNameMapInQuickUse[entry.index] ?: getShopItemName(entry, isDisabled)
            if (itemName != null && shopItems[itemName]?.isQuickUsage == true) {
                // Check if the item's plus button is enabled for usage.
                if (!isDisabled) {
                    val plusButtonPoint = ButtonSkillUp.findImageWithBitmap(game.imageUtils, entry.bitmap)
                    if (plusButtonPoint != null) {
                        MessageLog.i(TAG, "[INFO] Using item: \"$itemName\".")
                        // Tap the plus button to use the item.
                        game.tap(entry.bbox.x + plusButtonPoint.x, entry.bbox.y + plusButtonPoint.y)
                        anyUsed = true
                    }
                }
            }
            false
        }

        if (anyUsed) {
            // Confirm the usage if any items were clicked.
            ButtonConfirmUse.click(game.imageUtils)
        } else {
            // Close the dialog if no items were used.
            ButtonClose.click(game.imageUtils)
        }
    }

    /**
     * Queues specific items for usage from the Training Items dialog.
     *
     * @param itemsToUse List of item names to attempt to use.
     * @param bUseAll If true, attempts to use all available copies of each item instead of just one.
     * @param scannedItems Optional list of pre-scanned items to use instead of performing a new scan.
     * @param reason The reason why these items are being used (for logging).
     * @return A list of [Pair] containing successfully used item names and their reasons.
     */
    fun useSpecificItems(itemsToUse: List<String>, bUseAll: Boolean = false, scannedItems: List<ScannedItem>? = null, reason: String? = null): List<Pair<String, String>> {
        val successfullyUsed = mutableListOf<Pair<String, String>>()
        if (itemsToUse.isEmpty()) return successfullyUsed

        val requiredCounts = itemsToUse.groupingBy { it }.eachCount()

        if (scannedItems != null) {
            MessageLog.i(TAG, "[INFO] Using pre-scanned items for specific item usage.")
            val tempScanned = scannedItems.toMutableList()
            for (name in itemsToUse) {
                // If we already used this name and aren't using all, skip to the next name if the requirement is met.
                val currentUsed = successfullyUsed.count { it.first == name }
                val needed = requiredCounts[name] ?: 0
                if (!bUseAll && currentUsed >= needed) continue

                // If using all, find all available instances of this name in the pre-scanned list.
                while (true) {
                    val itemIndex = tempScanned.indexOfFirst { it.itemName == name && !it.isDisabled }
                    if (itemIndex != -1) {
                        val info = tempScanned[itemIndex]
                        val plusButtonPoint = ButtonSkillUp.findImageWithBitmap(game.imageUtils, info.entry.bitmap)
                        if (plusButtonPoint != null) {
                            val useReason = reason ?: shopItems[name]?.effect ?: "No reason provided"
                            MessageLog.i(TAG, "[INFO] Queuing specific item for use: \"$name\". (Reason: $useReason)")
                            // Tap the plus button based on the entry's position.
                            game.tap(info.entry.bbox.x + plusButtonPoint.x, info.entry.bbox.y + plusButtonPoint.y)
                            successfullyUsed.add(name to useReason)
                            // Remove from temp list so we don't try to use the same instance twice.
                            tempScanned.removeAt(itemIndex)

                            // Exit early if we only wanted to use as many as were in the list.
                            if (!bUseAll && successfullyUsed.count { it.first == name } >= (requiredCounts[name] ?: 0)) break
                        } else {
                            break
                        }
                    } else {
                        break
                    }
                }
                // Stop early if we satisfy every count requirement in itemsToUse.
                if (!bUseAll && requiredCounts.all { (name, count) -> successfullyUsed.count { it.first == name } >= count }) break
            }
        } else {
            val itemNameMapInUse = mutableMapOf<Int, String>()
            val handledItems = mutableSetOf<String>()
            processItemsWithFallback(
                keyExtractor = { entry ->
                    val name = getShopItemName(entry, isEntryDisabled(entry.bitmap))
                    if (name != null) itemNameMapInUse[entry.index] = name
                    name
                },
            ) { entry ->
                val isDisabled = isEntryDisabled(entry.bitmap)
                val itemName = itemNameMapInUse[entry.index] ?: getShopItemName(entry, isDisabled)

                if (itemName != null && requiredCounts.containsKey(itemName)) {
                    // Mark as handled even if disabled, so we know we've encountered this item row.
                    handledItems.add(itemName)

                    if (!isDisabled) {
                        val currentUsedTotal = successfullyUsed.count { it.first == itemName }
                        val neededTotal = requiredCounts[itemName] ?: 0

                        // If bUseAll is true, click until the button is disabled or we hit a reasonable limit.
                        var clicks = 0
                        while (true) {
                            // If bUseAll is false, stop if we reached the required count for this item.
                            if (!bUseAll && (currentUsedTotal + clicks) >= neededTotal) break

                            // Update individual log to include the item's effect or the explicit reason.
                            val useReason = reason ?: shopItems[itemName]?.effect ?: "No reason provided"
                            val logPrefix = if (bUseAll) "[TRACKBLAZER] Queuing item for use (copy ${clicks + 1}):" else "[TRACKBLAZER] Queuing specific item for use:"
                            MessageLog.i(TAG, "$logPrefix \"$itemName\". (Reason: $useReason)")

                            // Re-check for disabled state after clicks if bUseAll or multiple clicks are active.
                            val bitmapToUse: Bitmap =
                                if (clicks > 0) {
                                    val source = game.imageUtils.getSourceBitmap()
                                    game.imageUtils.createSafeBitmap(source, entry.bbox.x, entry.bbox.y, entry.bbox.w, entry.bbox.h, "recheck item")
                                } else {
                                    entry.bitmap
                                } ?: break

                            if (isEntryDisabled(bitmapToUse)) {
                                break
                            }

                            val plusPoint = ButtonSkillUp.findImageWithBitmap(game.imageUtils, bitmapToUse)
                            if (plusPoint != null) {
                                game.tap(entry.bbox.x + plusPoint.x, entry.bbox.y + plusPoint.y)
                                successfullyUsed.add(itemName to useReason)
                                clicks++
                                if (clicks >= 5) break
                                game.wait(0.2)
                            } else {
                                break
                            }
                        }
                    }
                }
                // Optimization: Stop the scan early if we found and handled (processed or saw disabled) all unique items in itemsToUse.
                // We allow early exit regardless of bUseAll, as once we've processed an item name's row,
                // there are no more instances of that item name to find in the list.
                requiredCounts.keys.all { name -> handledItems.contains(name) }
            }
        }

        // Log the summary of items queued for usage.
        printItemUsageSummary(successfullyUsed)

        return successfullyUsed.toList()
    }

    /**
     * Find and use the highest value Megaphone available in the inventory.
     *
     * @param scannedItems Optional list of pre-scanned items.
     * @return The item name of the megaphone used, or null if none were used.
     */
    fun useBestMegaphone(scannedItems: List<ScannedItem>? = null): String? {
        // Define the Megaphone priority from strongest to weakest.
        val megaphonePriority = listOf("Empowering Megaphone", "Motivating Megaphone", "Coaching Megaphone")
        val used = useSpecificItems(megaphonePriority, bUseAll = false, scannedItems = scannedItems, reason = "Best available megaphone for current turn.")
        return used.firstOrNull()?.first
    }

    /**
     * Prints a formatted summary of items queued for usage.
     *
     * @param itemsUsedWithReasons List of item names and specific reasons for usage.
     */
    fun printItemUsageSummary(
        itemsUsedWithReasons: List<Pair<String, String>>,
        stateContext: String? = null,
    ) {
        val sb = StringBuilder()
        sb.appendLine("\n============== Item Usage Summary ==============")

        // Include the inventory summary from the callback if provided.
        getInventorySummaryCallback?.invoke()?.let { summary ->
            if (summary.isNotBlank()) {
                sb.append(summary)
                sb.appendLine("\n------------------------------------------------")
            }
        }

        if (!stateContext.isNullOrBlank()) {
            sb.appendLine(stateContext)
        }

        // Add a separator space if there's a header.
        if (sb.length > 50) {
            sb.appendLine("")
        }

        if (itemsUsedWithReasons.isEmpty()) {
            sb.appendLine("No items were used this pass.")
        } else {
            sb.appendLine("Items Used:")
            sb.appendLine("")
            // Group the items used by name and reason and count them.
            val groupedItems = itemsUsedWithReasons.groupBy { it }.mapValues { it.value.size }
            groupedItems.forEach { (pair, count) ->
                val (name, reason) = pair
                sb.appendLine("- ${count}x $name: $reason")
            }
        }

        sb.appendLine("================================================")
        MessageLog.v(TAG, sb.toString())
    }

    /**
     * Use the Ankle Weights corresponding to the specified stat.
     *
     * @param stat The stat to use ankle weights for.
     * @param scannedItems Optional list of pre-scanned items.
     * @return True if ankle weights were queued.
     */
    fun useAnkleWeights(stat: StatName, scannedItems: List<ScannedItem>? = null): Boolean {
        // Map the stat to the corresponding Ankle Weight item name.
        val itemName =
            when (stat) {
                StatName.SPEED -> "Speed Ankle Weights"
                StatName.STAMINA -> "Stamina Ankle Weights"
                StatName.POWER -> "Power Ankle Weights"
                StatName.GUTS -> "Guts Ankle Weights"
                else -> return false
            }
        return useSpecificItems(listOf(itemName), scannedItems = scannedItems, reason = "Boosting $stat training gains.").isNotEmpty()
    }

    /**
     * Open the Training Items dialog from the current screen.
     *
     * @return True if the dialog was opened successfully.
     */
    fun openTrainingItemsDialog(): Boolean {
        if (ButtonTrainingItems.click(game.imageUtils)) {
            // Wait for the dialog to appear.
            game.wait(game.dialogWaitDelay, skipWaitingForLoading = true)
            return true
        }
        MessageLog.e(TAG, "[ERROR] openTrainingItemsDialog:: Failed to open Training Items dialog.")
        return false
    }

    /**
     * Use the Good-Luck Charm if available in the inventory.
     *
     * @param scannedItems Optional list of pre-scanned items.
     * @return True if the charm was queued.
     */
    fun useGoodLuckCharm(scannedItems: List<ScannedItem>? = null): Boolean {
        return useSpecificItems(listOf("Good-Luck Charm"), scannedItems = scannedItems, reason = "Setting training failure chance to 0%.").isNotEmpty()
    }

    /**
     * Process items in a dialog, handling both scrollable and non-scrollable cases.
     *
     * @param keyExtractor Optional callback to extract a unique key for each entry.
     * @param bScrollBottomToTop If true, process the list from the bottom up.
     * @param callback The callback to execute for each entry. Return true to stop.
     * @return True if the process completed successfully.
     */
    fun processItemsWithFallback(
        keyExtractor: ((ScrollListEntry) -> String?)? = null,
        bScrollBottomToTop: Boolean = false,
        callback: (ScrollListEntry) -> Boolean,
    ): Boolean {
        // Training Items dialog uses specific scroll regions if detected.
        val isTrainingItems = ButtonConfirmUse.check(game.imageUtils)
        val topLeft = if (isTrainingItems) IconDialogScrollListTopLeft else null
        val bottomRight = if (isTrainingItems) IconDialogScrollListBottomRight else null

        if (!isTrainingItems) {
            // Always check if the shop is on sale for default shop lists.
            isShopOnSale = LabelOnSale.check(game.imageUtils)
        }

        try {
            return ScrollList.processWithFallback(
                game,
                bScrollBottomToTop = bScrollBottomToTop,
                keyExtractor = keyExtractor,
                fallbackComponent = ButtonSkillUp,
                listTopLeftComponent = topLeft,
                listBottomRightComponent = bottomRight,
                entryDetectionConfig = if (isTrainingItems) null else ScrollListEntryDetectionConfig(blurSize = SHOP_LIST_BLUR_SIZE),
                onFrame = { _, frameEntries -> readFrameNames(frameEntries, bAmounts = isTrainingItems) },
                settleSeconds = if (isTrainingItems) 0.5 else SHOP_LIST_SETTLE_SECONDS,
            ) { _, entry: ScrollListEntry ->
                callback(entry)
            }
        } finally {
            // Entry indexes restart on the next pass, so reads from this one must not leak into it.
            frameNames = emptyMap()
            frameAmounts = emptyMap()
        }
    }

    /**
     * Checks if the item entry is disabled by looking for a disabled checkbox or plus button.
     *
     * @param entryBitmap The bitmap of the item entry to check.
     * @return True if the entry is disabled, false otherwise.
     */
    private fun isEntryDisabled(entryBitmap: Bitmap): Boolean {
        return CheckboxShopItem.checkDisabled(game.imageUtils, entryBitmap) == true ||
            ButtonSkillUp.checkDisabled(game.imageUtils, entryBitmap) == true
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Buy items from the Shop list based on a priority list and currency amount.
     *
     * @param priorityList An ordered list of item names to buy.
     * @param currentCoins The current amount of Shop Coins available.
     * @param inventoryLimits A map of item names to the maximum amount that can be bought for each.
     * @param bDryRun If true, only logs intentions without performing any clicks.
     * @param bForcePurchase If true, the bot will attempt to buy items even if [currentCoins] is 0, stopping once it hits a disabled item.
     * @return Each successfully purchased item with the price paid for it.
     */
    fun buyItems(priorityList: List<String>, currentCoins: Int, inventoryLimits: Map<String, Int>, bDryRun: Boolean = false, bForcePurchase: Boolean = false): List<Pair<String, Int>> {
        val memory = CropMemory<String>()
        nameCrops = memory
        try {
            return scanAndBuyItems(priorityList, currentCoins, inventoryLimits, bDryRun, bForcePurchase)
        } finally {
            nameCrops = null
            memory.clear()
        }
    }

    /**
     * Scans the Shop list, plans the purchases, and walks the list again to select and buy them. See [buyItems].
     *
     * @param priorityList An ordered list of item names to buy.
     * @param currentCoins The current amount of Shop Coins available.
     * @param inventoryLimits A map of item names to the maximum amount that can be bought for each.
     * @param bDryRun If true, only logs intentions without performing any clicks.
     * @param bForcePurchase If true, the bot will attempt to buy items even if [currentCoins] is 0, stopping once it hits a disabled item.
     * @return Each successfully purchased item with the price paid for it.
     */
    private fun scanAndBuyItems(priorityList: List<String>, currentCoins: Int, inventoryLimits: Map<String, Int>, bDryRun: Boolean, bForcePurchase: Boolean): List<Pair<String, Int>> {
        if (priorityList.isEmpty()) {
            MessageLog.i(TAG, "[INFO] Priority shopping list is empty. No items to buy.")
            return emptyList()
        }

        // Without a sale every price is the catalog price, so skip the scan when even the cheapest wanted item is out of reach.
        val cheapestPrice: Int = priorityList.minOf { shopItems[it]?.price ?: 0 }
        if (!bForcePurchase && currentCoins < cheapestPrice && !LabelOnSale.check(game.imageUtils)) {
            MessageLog.i(TAG, "[INFO] $currentCoins coins cannot buy any wanted item (cheapest is $cheapestPrice) and the Shop is not on sale. Skipping the scan.")
            return emptyList()
        }

        // Step 1: Pre-scan Phase.
        // Scan the entire shop to log each item and its price, and to identify what is available.
        MessageLog.i(TAG, "[INFO] Beginning process of scanning shop items...")
        val availableInShop = mutableListOf<Triple<String, Int, ScrollListEntry>>()
        val purchasedInShop = mutableListOf<String>()
        val itemNameMap = mutableMapOf<Int, String>()
        var totalEntriesDetected = 0
        processItemsWithFallback(
            keyExtractor = { entry ->
                val name = getShopItemName(entry, isEntryDisabled(entry.bitmap))
                if (name != null) itemNameMap[entry.index] = name
                name
            },
        ) { entry ->
            totalEntriesDetected++
            // Check if the item's plus button is disabled.
            val isDisabled = isEntryDisabled(entry.bitmap)
            val itemName = itemNameMap[entry.index] ?: getShopItemName(entry, isDisabled)
            if (itemName != null) {
                if (LabelPurchased.findImageWithBitmap(game.imageUtils, entry.bitmap) != null) {
                    // Already-purchased items can't be bought again, so track them only for the summary.
                    purchasedInShop.add(itemName)
                } else {
                    val price = getShopItemPrice(itemName, entry.bitmap)
                    MessageLog.i(TAG, "\t$itemName: $price coins at index ${entry.index}")
                    availableInShop.add(Triple(itemName, price, entry))
                }
            }
            false
        }

        // Log a warning if the scan failed to read any item names due to OCR failure.
        if (availableInShop.isEmpty() && purchasedInShop.isEmpty() && totalEntriesDetected != 0) {
            MessageLog.w(
                TAG,
                "[WARN] buyItems:: Scanned the list of items in the Shop but could not read any of the names. OCR is dependent on correct display setup configuration. User's current display setup: ${SharedData.displayWidth}x${SharedData.displayHeight}, DPI ${SharedData.displayDPI}",
            )
        }

        val excludedItems = mutableListOf<String>()
        try {
            val jsonArray = JSONArray(excludedItemsString)
            for (i in 0 until jsonArray.length()) {
                excludedItems.add(jsonArray.getString(i))
            }
        } catch (e: Exception) {
            MessageLog.e(TAG, "[ERROR] buyItems:: Failed to parse trackblazerExcludedItems: ${e.message}")
        }

        val actuallyExcludedItems = availableInShop.filter { (name, _, _) -> excludedItems.contains(name) }.map { it.first }
        val filteredAvailableInShop = availableInShop.filter { (name, _, _) -> !excludedItems.contains(name) }

        // Step 2: Calculation & Summary Phase.
        // Determine which items from the priority list are available and affordable.
        val itemsToBuy = mutableListOf<Triple<String, Int, ScrollListEntry>>()

        // If in Force Purchase mode and current coins is 0, we assume OCR failure and proceed with a high sacrificial value for calculation.
        val effectiveCoins = if (bForcePurchase && currentCoins == 0) 9999 else currentCoins
        var remainingCoinsAfterProposed = effectiveCoins

        val tempAvailable = filteredAvailableInShop.toMutableList()
        for (item in priorityList) {
            val limit = inventoryLimits[item] ?: 0
            if (limit <= 0) continue

            var boughtCount = 0
            while (boughtCount < limit) {
                val availableIndex = tempAvailable.indexOfFirst { it.first == item }
                if (availableIndex != -1) {
                    // Check if we can afford the item.
                    val foundItem = tempAvailable[availableIndex]
                    val price = foundItem.second
                    if (remainingCoinsAfterProposed >= price) {
                        itemsToBuy.add(foundItem)
                        remainingCoinsAfterProposed -= price
                        // Remove from temp list so we don't buy the same slot twice for one priority entry.
                        tempAvailable.removeAt(availableIndex)
                        boughtCount++
                    } else {
                        break
                    }
                } else {
                    break
                }
            }
        }

        // Log the summary of proposed purchases.
        val sb = StringBuilder()
        sb.appendLine("\n============== Shop Evaluation Summary ==============")

        // Include the inventory summary from the callback if provided.
        getInventorySummaryCallback?.invoke()?.let { summary ->
            if (summary.isNotBlank()) {
                sb.append(summary)
                sb.appendLine("\n------------------------------------------------")
            }
        }

        if (availableInShop.isNotEmpty() || purchasedInShop.isNotEmpty()) {
            sb.appendLine("Current coins: $currentCoins.")
            sb.appendLine("Items found:")
            sb.appendLine("")
            availableInShop.forEach { (name, _, _) ->
                sb.appendLine("  - $name")
            }
            purchasedInShop.forEach { name ->
                sb.appendLine("  - $name (Purchased)")
            }
        } else {
            if (totalEntriesDetected == 0) {
                sb.appendLine("No items were detected in the Shop. Current coins: $currentCoins.")
            } else {
                sb.appendLine("Scanned $totalEntriesDetected items in the Shop but none are available to buy. Current coins: $currentCoins.")
            }
        }

        if (actuallyExcludedItems.isNotEmpty()) {
            sb.appendLine("")
            sb.appendLine("Items excluded from purchase:")
            sb.appendLine("")
            actuallyExcludedItems.forEach { name ->
                sb.appendLine("  - $name")
            }
        }

        sb.appendLine("")

        if (bForcePurchase) {
            sb.appendLine("Force purchase mode enabled. Temporarily setting Shop Coin balance to 9999.")
            sb.appendLine("")
        }

        if (itemsToBuy.isNotEmpty()) {
            // Log each item planned to be bought.
            sb.appendLine("Items to buy:")
            sb.appendLine("")
            itemsToBuy.forEach { (name, price, _) ->
                sb.appendLine("  - $name: $price coins (Reason: ${shopItems[name]?.effect})")
            }
            val totalCost = effectiveCoins - remainingCoinsAfterProposed
            sb.appendLine("")
            sb.appendLine("TOTAL: $totalCost / $effectiveCoins coins with $remainingCoinsAfterProposed left over coins")
        }

        sb.append("==========================================")
        MessageLog.v(TAG, sb.toString())

        if (itemsToBuy.isEmpty() && !bDryRun) {
            return emptyList()
        }

        if (bDryRun) {
            // Return early for the dry run test.
            return itemsToBuy.map { it.first to it.second }
        }

        // Step 3: Purchasing Phase.
        // Re-process the list to click on the selected items. The scan ended at the bottom, so walk back up from there instead of returning to the top first.
        // Force Purchase keeps the top-down order, since it stops at the first item it can no longer afford.
        val itemsBought = mutableListOf<Pair<String, Int>>()
        val itemsRemainingToClick = itemsToBuy.toMutableList()
        val itemNameMapInPurchase = mutableMapOf<Int, String>()
        processItemsWithFallback(
            keyExtractor = { entry ->
                val name = getShopItemName(entry, isEntryDisabled(entry.bitmap))
                if (name != null) itemNameMapInPurchase[entry.index] = name
                name
            },
            bScrollBottomToTop = !bForcePurchase,
        ) { entry ->
            val isDisabled = isEntryDisabled(entry.bitmap)
            val itemName = itemNameMapInPurchase[entry.index] ?: getShopItemName(entry, isDisabled)

            if (itemName != null) {
                // A greyed-out card cannot be bought. In Force Purchase mode it means we are out of coins, so stop.
                // Otherwise skip it, since a copy already bought sits below the fresh copies and walking up from the bottom would spend the match on it.
                if (isDisabled) {
                    if (bForcePurchase) MessageLog.i(TAG, "[INFO] Force Purchase: Encountered disabled item \"$itemName\". Stopping purchasing.")
                    return@processItemsWithFallback bForcePurchase
                }

                // Find the first matching item in our purchase list.
                val targetIndex = itemsRemainingToClick.indexOfFirst { it.first == itemName }
                if (targetIndex != -1) {
                    val targetItem = itemsRemainingToClick[targetIndex]
                    MessageLog.i(TAG, "[INFO] Selecting \"$itemName\" for ${targetItem.second} coins at index ${entry.index}. (Reason: ${shopItems[itemName]?.effect})")
                    // Tap the item's entry to select it.
                    game.tap(entry.bbox.cx.toDouble(), entry.bbox.cy.toDouble())
                    itemsBought.add(itemName to targetItem.second)
                    itemsRemainingToClick.removeAt(targetIndex)
                }
            }
            // Early exit if we've bought all items in the proposed list.
            itemsRemainingToClick.isEmpty()
        }

        if (itemsBought.isNotEmpty()) {
            // Click the confirm button to finalize the selection.
            MessageLog.i(TAG, "[INFO] Successfully selected and purchased ${itemsBought.size} item(s).")
            ButtonConfirm.click(game.imageUtils)
            game.wait(game.dialogWaitDelay, skipWaitingForLoading = true)

            // Handle "Do not show again" checkbox if found.
            if (!hasClickedDoNotShowAgain) {
                if (CheckboxDoNotShowAgain.click(game.imageUtils)) {
                    MessageLog.i(TAG, "[INFO] Successfully clicked \"Do not show again\" checkbox.")
                    hasClickedDoNotShowAgain = true
                    game.wait(0.5, skipWaitingForLoading = true)
                }
            }

            // Final exchange confirmation click.
            ButtonExchange.click(game.imageUtils)
            game.wait(game.dialogWaitDelay)
            return itemsBought.toList()
        }

        return emptyList()
    }
}
