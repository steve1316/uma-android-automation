/** Defines checkbox and radio-button control components. */

package com.steve1316.uma_android_automation.components

import android.graphics.Bitmap
import com.steve1316.uma_android_automation.utils.CustomImageUtils
import org.opencv.core.Point

object Checkbox : ComponentInterface {
    override val template = Template("components/checkbox/checkbox")

    /**
     * Tells whether a checkbox is ticked. A ticked box has a green checkmark and an unticked one a grey one, which grayscale matching cannot tell apart.
     *
     * @param imageUtils The bot's image utilities.
     * @param bitmap The screenshot.
     * @param checkbox The checkbox's center, as found by this template.
     * @returns True when the box is ticked.
     */
    fun isTicked(imageUtils: CustomImageUtils, bitmap: Bitmap, checkbox: Point): Boolean {
        val boxSize = imageUtils.relWidth(44)
        return imageUtils.isRegionGreen(bitmap, checkbox.x.toInt() - boxSize / 2, checkbox.y.toInt() - boxSize / 2, boxSize, boxSize)
    }
}

object CheckboxDoNotShowAgain : ComponentInterface {
    override val template = Template("components/checkbox/checkbox_do_not_show_again")
}

object CheckboxShopItem : ComponentInterface {
    override val template = Template("components/checkbox/checkbox_shop_item")
}

object RadioCareerQuickShortenAllEvents : ComponentInterface {
    override val template = Template("components/radio/radio_career_quick_shorten_all_events", region = Region.middle)
}

object RadioPortrait : ComponentInterface {
    override val template = Template("components/radio/radio_portrait", region = Region.middle)
}

object RadioLandscape : ComponentInterface {
    override val template = Template("components/radio/radio_landscape", region = Region.middle)
}

object RadioVoiceOff : ComponentInterface {
    override val template = Template("components/radio/radio_voice_off", region = Region.middle)
}
