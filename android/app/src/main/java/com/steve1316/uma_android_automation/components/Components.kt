/**
 * Defines interfaces used by various components.
 *
 * These interfaces provide functions which are used as wrappers around the `CustomImageUtils` functions. This includes functions for finding and clicking on different types of components.
 */

package com.steve1316.uma_android_automation.components

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import com.steve1316.automation_library.data.SharedData
import com.steve1316.automation_library.utils.MyAccessibilityService
import com.steve1316.uma_android_automation.bot.DialogHandler
import com.steve1316.uma_android_automation.bot.DialogHandlerResult
import com.steve1316.uma_android_automation.utils.CustomImageUtils
import org.opencv.core.Point

/**
 * Defines various screen regions.
 *
 * Used to refine search areas during OCR for performance.
 */
object Region {
    val topHalf: IntArray = intArrayOf(0, 0, SharedData.displayWidth, SharedData.displayHeight / 2)
    val topQuarter: IntArray = intArrayOf(0, 0, SharedData.displayWidth, SharedData.displayHeight / 4)
    val bottomHalf: IntArray = intArrayOf(0, SharedData.displayHeight / 2, SharedData.displayWidth, SharedData.displayHeight / 2)
    val bottomQuarter: IntArray = intArrayOf(0, SharedData.displayHeight / 4, SharedData.displayWidth, SharedData.displayHeight / 4)
    val middle: IntArray = intArrayOf(0, SharedData.displayHeight / 4, SharedData.displayWidth, SharedData.displayHeight / 2)
    val leftHalf: IntArray = intArrayOf(0, 0, SharedData.displayWidth / 2, SharedData.displayHeight)
    val rightHalf: IntArray = intArrayOf(SharedData.displayWidth / 2, 0, SharedData.displayWidth / 2, SharedData.displayHeight)
    val threeQuarterRight: IntArray = intArrayOf((SharedData.displayWidth * 0.625).toInt(), 0, (SharedData.displayWidth * 0.375).toInt(), SharedData.displayHeight)
    val topRightThird: IntArray = intArrayOf(SharedData.displayWidth - (SharedData.displayWidth / 3), 0, SharedData.displayWidth / 3, SharedData.displayHeight - (SharedData.displayHeight / 3))
}

/**
 * Defines a template image file and provides helpful functions.
 *
 * @property path The relative path to the template image file within the /assets folder.
 * @property region The screen region to search within, formatted as [x, y, width, height]. Defaults to the full screen if not specified.
 * @property confidence The threshold (0.0, 1.0] required for a match. Defaults to 0.0 which uses the default confidence value.
 */
data class Template(val path: String, val region: IntArray = intArrayOf(0, 0, 0, 0), val confidence: Double = 0.0) {
    /** The directory portion of the path, excluding the filename. */
    val dirname: String
        get() = path.substringBeforeLast('/')

    /** The filename portion of the path, excluding the directory. */
    val basename: String
        get() = path.substringAfterLast('/')

    /**
     * Returns this template's bitmap.
     *
     * @param imageUtils A reference to a CustomImageUtils instance.
     * @return The bitmap for this template, or null if it could not be loaded.
     */
    fun getBitmap(imageUtils: CustomImageUtils): Bitmap? {
        return imageUtils.getTemplateBitmap(path.substringAfterLast('/'), "images/" + path.substringBeforeLast('/'))
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Template

        if (path != other.path) return false
        if (!region.contentEquals(other.region)) return false
        if (confidence != other.confidence) return false
        return true
    }

    override fun hashCode(): Int {
        var result = path.hashCode()
        result = 31 * result + region.contentHashCode()
        result = 31 * result + confidence.hashCode()
        return result
    }
}

/**
 * Defines the most basic component.
 *
 * Contains functions used by all types of components such as finding and clicking on the component.
 */
interface BaseComponentInterface {
    /**
     * Checks if the component is on screen.
     *
     * @param imageUtils A reference to a CustomImageUtils instance.
     * @param region The screen region to search in.
     * @param sourceBitmap Optional bitmap to search. Avoids us having to capture a new screenshot which can improve performance.
     * @param tries The number of attempts when searching for this image.
     * @param confidence The threshold (0.0, 1.0] to use when performing OCR.
     * @return True if the component exists on screen.
     */
    fun check(imageUtils: CustomImageUtils, region: IntArray? = null, sourceBitmap: Bitmap? = null, tries: Int = 1, confidence: Double? = null): Boolean

    /**
     * Whether the component is in a disabled state.
     *
     * Not all components have a disabled state, so this default reports "unknown" rather than guessing. [ComponentInterface] overrides it
     * to compare the luminance of the detected bitmap against the template, returning true when the on-screen crop is the darker of the
     * two. Anything inheriting this default, such as [ComplexComponentInterface], has no disabled state to report.
     *
     * NOTE: Not all components are just darkened when disabled. For example, in the shop, the Exchange button when disabled is not just a grayscale version of the enabled button. Thus, we are unable
     * to detect both states of this button with a single template.
     *
     * @param imageUtils A reference to a CustomImageUtils instance.
     * @param sourceBitmap The source bitmap to search within.
     * @return Whether this component is currently disabled, or null when that cannot be determined - either because the component was not
     *    found on screen, because an error occurred, or because this default is in use. Callers must treat null as "do not assume enabled".
     */
    fun checkDisabled(imageUtils: CustomImageUtils, sourceBitmap: Bitmap? = null): Boolean? {
        return null
    }

    /**
     * Gets the location of the component on screen.
     *
     * Mostly a wrapper around [CustomImageUtils.findImage].
     *
     * @param imageUtils A reference to a CustomImageUtils instance.
     * @param region The screen region to search in.
     * @param tries The number of attempts when searching for this image.
     * @param confidence The threshold (0.0, 1.0] to use when performing OCR.
     * @return If the component was detected, then the Point and the screenshot bitmap are returned. Otherwise, null and the screenshot bitmap are returned.
     */
    fun find(imageUtils: CustomImageUtils, region: IntArray? = null, tries: Int = 1, confidence: Double? = null): Pair<Point?, Bitmap>

    /**
     * Gets the location of the component within a source bitmap.
     *
     * Mostly a wrapper around [CustomImageUtils.findImageWithBitmap].
     *
     * @param imageUtils A reference to a CustomImageUtils instance.
     * @param sourceBitmap The source bitmap to search within for the component.
     * @param region The screen region to search in.
     * @param confidence The threshold (0.0, 1.0] to use when performing OCR.
     * @return If the component was detected, returns the Point. Else returns null.
     */
    fun findImageWithBitmap(imageUtils: CustomImageUtils, sourceBitmap: Bitmap, region: IntArray? = null, confidence: Double? = null): Point?

    /**
     * Attempts to click on the component.
     *
     * @param imageUtils A reference to a CustomImageUtils instance.
     * @param region The screen region to search in.
     * @param tries The number of attempts when searching for this image.
     * @param confidence The threshold (0.0, 1.0] to use when performing OCR.
     * @return True if the component was detected and clicked.
     */
    fun click(imageUtils: CustomImageUtils, region: IntArray? = null, sourceBitmap: Bitmap? = null, tries: Int = 1, taps: Int = 1, confidence: Double? = null): Boolean

    /**
     * Performs a tap on the screen at the coordinates and then will wait until the game processes the server request and gets a response back.
     *
     * @param x The x-coordinate.
     * @param y The y-coordinate.
     * @param imageName The template image name to use for tap location randomization.
     * @param taps The number of taps.
     */
    fun tap(x: Double, y: Double, imageName: String? = null, taps: Int = 1) {
        MyAccessibilityService.getInstance().tap(x, y, imageName, taps = taps)
    }
}

/**
 * This is an interface for the most common components seen throughout the game.
 *
 * These components are simple and only ever have a single design so they only require a single template image to find.
 */
interface ComponentInterface : BaseComponentInterface {
    val template: Template

    override fun find(imageUtils: CustomImageUtils, region: IntArray?, tries: Int, confidence: Double?): Pair<Point?, Bitmap> {
        return imageUtils.findImage(
            template.path,
            region = region ?: template.region,
            tries = tries,
            confidence = confidence ?: template.confidence,
            suppressError = true,
        )
    }

    override fun findImageWithBitmap(imageUtils: CustomImageUtils, sourceBitmap: Bitmap, region: IntArray?, confidence: Double?): Point? {
        // If we are searching within a cropped bitmap (like an entry bitmap), we should default to searching the entire bitmap
        // if no specific region is provided. This prevents out-of-bounds errors from default regions intended for full-screen use.
        val searchRegion =
            if (region == null && (sourceBitmap.width != SharedData.displayWidth || sourceBitmap.height != SharedData.displayHeight)) {
                intArrayOf(0, 0, 0, 0)
            } else {
                region ?: template.region
            }

        return imageUtils.findImageWithBitmap(
            templateName = template.path,
            sourceBitmap = sourceBitmap,
            region = searchRegion,
            customConfidence = confidence ?: template.confidence,
            suppressError = true,
        )
    }

    /**
     * Finds all occurrences of the component on screen.
     *
     * @param imageUtils A reference to a CustomImageUtils instance.
     * @param region The screen region to search in.
     * @param confidence The threshold (0.0, 1.0] to use when performing image matching.
     * @param ignoreDisabled Whether to drop disabled items from the list of results.
     * @return A list of Points where the component was found.
     */
    fun findAll(imageUtils: CustomImageUtils, region: IntArray? = null, sourceBitmap: Bitmap? = null, confidence: Double? = null, ignoreDisabled: Boolean = false): ArrayList<Point> {
        val bitmap: Bitmap = sourceBitmap ?: imageUtils.getSourceBitmap()

        // If we are searching within a cropped bitmap (like an entry bitmap), we should default to searching the entire bitmap
        // if no specific region is provided.
        val searchRegion =
            if (region == null && (bitmap.width != SharedData.displayWidth || bitmap.height != SharedData.displayHeight)) {
                intArrayOf(0, 0, 0, 0)
            } else {
                region ?: template.region
            }

        val points: ArrayList<Point> =
            imageUtils.findAllWithBitmap(
                template.path,
                region = searchRegion,
                sourceBitmap = bitmap,
                customConfidence = (confidence ?: template.confidence),
            )

        if (!ignoreDisabled) {
            return points
        }

        val templateBitmap: Bitmap = template.getBitmap(imageUtils)!!
        val enabledPoints: List<Point> =
            points.mapNotNull {
                val x: Int = (it.x - (templateBitmap.width / 2)).toInt()
                val y: Int = (it.y - (templateBitmap.height / 2)).toInt()
                val cropped: Bitmap? =
                    imageUtils.createSafeBitmap(
                        bitmap,
                        x,
                        y,
                        templateBitmap.width,
                        templateBitmap.height,
                        "component: findAll enabled",
                    )
                if (cropped == null) {
                    null
                } else if (checkDisabled(imageUtils, cropped) == true) {
                    null
                } else {
                    it
                }
            }.sortedBy { it.y }

        return ArrayList(enabledPoints)
    }

    /**
     * Finds all occurrences of the component within a source bitmap.
     *
     * @param imageUtils A reference to a CustomImageUtils instance.
     * @param sourceBitmap The source bitmap to search within.
     * @param region The screen region to search in.
     * @param confidence The threshold (0.0, 1.0] to use when performing image matching.
     * @return A list of Points where the component was found.
     */
    fun findAllWithBitmap(imageUtils: CustomImageUtils, sourceBitmap: Bitmap, region: IntArray? = null, confidence: Double? = null): ArrayList<Point> {
        return imageUtils.findAllWithBitmap(template.path, sourceBitmap = sourceBitmap, region = region ?: template.region, customConfidence = (confidence ?: template.confidence))
    }

    override fun check(imageUtils: CustomImageUtils, region: IntArray?, sourceBitmap: Bitmap?, tries: Int, confidence: Double?): Boolean {
        return if (sourceBitmap == null) {
            find(imageUtils = imageUtils, region = region ?: template.region, tries = tries, confidence = confidence ?: template.confidence).first != null
        } else {
            findImageWithBitmap(imageUtils = imageUtils, region = region ?: template.region, sourceBitmap = sourceBitmap, confidence = confidence ?: template.confidence) != null
        }
    }

    /**
     * Checks if the component is in a disabled state.
     *
     * @param imageUtils See [BaseComponentInterface.checkDisabled]
     * @param sourceBitmap See [BaseComponentInterface.checkDisabled]
     * @return See [BaseComponentInterface.checkDisabled]
     */
    override fun checkDisabled(imageUtils: CustomImageUtils, sourceBitmap: Bitmap?): Boolean? {
        val sourceBitmap: Bitmap = sourceBitmap ?: imageUtils.getSourceBitmap()
        val templateBitmap: Bitmap = template.getBitmap(imageUtils)!!
        val point: Point = findImageWithBitmap(imageUtils, sourceBitmap = sourceBitmap) ?: return null

        val bitmap: Bitmap? =
            imageUtils.createSafeBitmap(
                sourceBitmap,
                (point.x - (templateBitmap.width / 2)).toInt(),
                (point.y - (templateBitmap.height / 2)).toInt(),
                templateBitmap.width,
                templateBitmap.height,
                "checkDisabled cropped",
            )
        if (bitmap == null) {
            return null
        }
        val res: Int = imageUtils.compareBitmapLuminance(bitmap, templateBitmap)
        // If templateBitmap is darker than the detected bitmap, we return true.
        return res > 0
    }

    override fun click(imageUtils: CustomImageUtils, region: IntArray?, sourceBitmap: Bitmap?, tries: Int, taps: Int, confidence: Double?): Boolean {
        val point =
            if (sourceBitmap == null) {
                find(imageUtils = imageUtils, region = region ?: template.region, tries = tries, confidence = confidence ?: template.confidence).first ?: return false
            } else {
                findImageWithBitmap(imageUtils = imageUtils, region = region, sourceBitmap = sourceBitmap, confidence = confidence ?: template.confidence) ?: return false
            }
        tap(point.x, point.y, template.path, taps = taps)
        return true
    }
}

interface ButtonInterface : ComponentInterface {
    /**
     * Attempts to click on the component.
     *
     * @param imageUtils A reference to a CustomImageUtils instance.
     * @param region The screen region to search in.
     * @param tries The number of attempts when searching for this image.
     * @param confidence The threshold (0.0, 1.0] to use when performing OCR.
     * @param handleDialogs An optional reference to the [DialogHandler.handleDialogs] function. Defaults to null. If specified, then the handleDialogs function will be called after the click action
     *    takes place.
     * @param bTriggersLoading Whether clicking this button triggers a connection to the game server. This argument is passed to the [handleDialogs] call. This argument is ignored if [handleDialogs]
     *    is null.
     * @return True if the component was detected and clicked.
     */
    fun click(
        imageUtils: CustomImageUtils,
        region: IntArray? = null,
        sourceBitmap: Bitmap? = null,
        tries: Int = 1,
        taps: Int = 1,
        confidence: Double? = null,
        handleDialogs: ((dialog: DialogInterface?, args: Map<String, Any>) -> DialogHandlerResult)? = null,
        bTriggersLoading: Boolean = false,
    ): Boolean {
        val point =
            if (sourceBitmap == null) {
                find(imageUtils = imageUtils, region = region ?: template.region, tries = tries, confidence = confidence ?: template.confidence).first ?: return false
            } else {
                findImageWithBitmap(imageUtils = imageUtils, region = region ?: template.region, sourceBitmap = sourceBitmap, confidence = confidence ?: template.confidence) ?: return false
            }
        tap(point.x, point.y, template.path, taps = taps)
        if (handleDialogs != null) {
            handleDialogs(
                null,
                mapOf<String, Any>(
                    "bShouldWait" to true,
                    "bShouldWaitForLoading" to bTriggersLoading,
                ),
            )
        }
        return true
    }
}

/**
 * Defines a component which has multiple templates.
 *
 * This defines components which can possibly have more than one design and thus require multiple template files in order to accurately detect them.
 *
 * For example, if there is a button whose background is an image and that image can have multiple different designs, then each of those designs would be a separate template.
 */
interface ComplexComponentInterface : BaseComponentInterface {
    val templates: List<Template>

    override fun find(imageUtils: CustomImageUtils, region: IntArray?, tries: Int, confidence: Double?): Pair<Point?, Bitmap> {
        for (template in templates) {
            val result: Pair<Point?, Bitmap> =
                imageUtils.findImage(template.path, region = region ?: template.region, tries = tries, confidence = confidence ?: template.confidence, suppressError = true)
            if (result.first != null) {
                return result
            }
        }
        return Pair(null, imageUtils.getSourceBitmap())
    }

    override fun findImageWithBitmap(imageUtils: CustomImageUtils, sourceBitmap: Bitmap, region: IntArray?, confidence: Double?): Point? {
        val searchRegion =
            if (region == null && (sourceBitmap.width != SharedData.displayWidth || sourceBitmap.height != SharedData.displayHeight)) {
                intArrayOf(0, 0, 0, 0)
            } else {
                region
            }

        for (template in templates) {
            val result: Point? =
                imageUtils.findImageWithBitmap(
                    template.path,
                    sourceBitmap,
                    searchRegion ?: template.region,
                    customConfidence = confidence ?: template.confidence,
                    suppressError = true,
                )
            if (result != null) {
                return result
            }
        }
        return null
    }

    /**
     * Finds all occurrences of the components on screen.
     *
     * A complex component's states are alternatives rather than enabled / disabled variants (a menu bar button is "selected" or
     * "unselected", both valid), so there is no disabled state to filter out here. [ComponentInterface.findAll] is the one that takes
     * `ignoreDisabled`, since a single-template component can be compared against its own template by luminance.
     *
     * @param imageUtils A reference to a CustomImageUtils instance.
     * @param region The screen region to search in.
     * @param sourceBitmap The source bitmap to search within.
     * @param confidence The threshold (0.0, 1.0] to use when performing image matching.
     * @return A list of Points where the component was found.
     */
    fun findAll(imageUtils: CustomImageUtils, region: IntArray? = null, sourceBitmap: Bitmap? = null, confidence: Double? = null): ArrayList<Point> {
        val res: MutableList<Point> = mutableListOf()
        val bitmap: Bitmap = sourceBitmap ?: imageUtils.getSourceBitmap()

        for (template in templates) {
            res.addAll(
                imageUtils.findAllWithBitmap(
                    template.path,
                    region = region ?: template.region,
                    sourceBitmap = bitmap,
                    customConfidence = (confidence ?: template.confidence),
                ),
            )
        }

        return ArrayList(res.sortedBy { it.y })
    }

    override fun check(imageUtils: CustomImageUtils, region: IntArray?, sourceBitmap: Bitmap?, tries: Int, confidence: Double?): Boolean {
        for (template in templates) {
            val found: Boolean =
                if (sourceBitmap == null) {
                    find(imageUtils = imageUtils, region = region ?: template.region, tries = tries, confidence = confidence ?: template.confidence).first != null
                } else {
                    findImageWithBitmap(imageUtils = imageUtils, region = region ?: template.region, sourceBitmap = sourceBitmap, confidence = confidence ?: template.confidence) != null
                }
            if (!found) {
                return false
            }
        }

        return true
    }

    override fun click(imageUtils: CustomImageUtils, region: IntArray?, sourceBitmap: Bitmap?, tries: Int, taps: Int, confidence: Double?): Boolean {
        var resultTemplate: Template? = null
        var resultPoint: Point? = null
        for (template in templates) {
            resultPoint =
                if (sourceBitmap == null) {
                    imageUtils.findImage(
                        templateName = template.path,
                        region = region ?: template.region,
                        tries = tries,
                        confidence = confidence ?: template.confidence,
                        suppressError = true,
                    ).first
                } else {
                    imageUtils.findImageWithBitmap(
                        templateName = template.path,
                        region = region ?: template.region,
                        sourceBitmap = sourceBitmap,
                        customConfidence = confidence ?: template.confidence,
                        suppressError = true,
                    )
                }
            if (resultPoint != null) {
                resultTemplate = template
                break
            }
        }

        // Failed to find any of the templates on the screen.
        if (resultPoint == null || resultTemplate == null) {
            return false
        }

        tap(resultPoint.x, resultPoint.y, resultTemplate.path, taps = taps)
        return true
    }
}

/**
 * Defines a multi-state button component.
 *
 * Very similar to `ComplexComponentInterface` but exclusively used for buttons with multiple different states.
 *
 * This interface handles this by finding buttons where one state is active at a time. Logical OR between templates, essentially.
 */
interface MultiStateButtonInterface : ComplexComponentInterface {
    override fun find(imageUtils: CustomImageUtils, region: IntArray?, tries: Int, confidence: Double?): Pair<Point?, Bitmap> {
        for (template in templates) {
            val (point, bitmap) =
                imageUtils.findImage(template.path, region = region ?: template.region, tries = tries, confidence = confidence ?: template.confidence, suppressError = true)
            if (point != null) {
                return Pair(point, bitmap)
            }
        }
        return Pair(null, createBitmap(1, 1))
    }
}
