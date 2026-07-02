/** Defines checkbox and radio-button control components. */

package com.steve1316.uma_android_automation.components

object Checkbox : ComponentInterface {
    override val template = Template("components/checkbox/checkbox")
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
