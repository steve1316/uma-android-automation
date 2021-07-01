package com.steve1316.uma_android_automation.data

// Note that different Support Cards of different rarities of the same Character share the same events except for their 3-part progressing events.
// Their secondary names that differentiate between each Support card of the same Character is from the GameWith website, https://gamewith.jp/uma-musume/article/show/255037

class SupportData {
	companion object {
		var supports: MutableMap<String, MutableMap<String, ArrayList<String>>> = mutableMapOf()
	}
}
