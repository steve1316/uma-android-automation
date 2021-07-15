package com.steve1316.uma_android_automation.ui.settings

import android.app.AlertDialog
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import androidx.core.content.edit
import androidx.preference.*
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.R

class TrainingEventFragment : PreferenceFragmentCompat() {
	private val TAG: String = "[${MainActivity.loggerTag}]TrainingEventFragment"
	
	private lateinit var sharedPreferences: SharedPreferences
	
	private lateinit var builder: AlertDialog.Builder
	
	private lateinit var items: Array<String>
	private lateinit var checkedItems: BooleanArray
	private var userSelectedOptions: ArrayList<Int> = arrayListOf()
	
	// This listener is triggered whenever the user changes a Preference setting in the Settings Page.
	private val onSharedPreferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
		val characterPicker: ListPreference = findPreference("characterPicker")!!
		val selectAllCharactersCheckBox: CheckBoxPreference = findPreference("selectAllCharactersCheckBox")!!
		val selectAllSupportCardsCheckBox: CheckBoxPreference = findPreference("selectAllSupportCardsCheckBox")!!
		
		if (key != null) {
			// Note that is no need to handle the Preference that allows multiple selection here as it is already handled in its own function.
			when (key) {
				"characterPicker" -> {
					sharedPreferences.edit {
						putString("character", characterPicker.value)
						commit()
					}
					
					characterPicker.summary = "Covers all R, SR and SSR variants into one.\n\n${characterPicker.value}"
				}
				"selectAllCharactersCheckBox" -> {
					sharedPreferences.edit {
						putBoolean("selectAllCharacters", selectAllCharactersCheckBox.isChecked)
					}
					
					characterPicker.isEnabled = !selectAllCharactersCheckBox.isChecked
					characterPicker.value = ""
					characterPicker.summary = "Covers all R, SR and SSR variants into one."
					sharedPreferences.edit {
						remove("character")
						commit()
					}
				}
				"selectAllSupportCardsCheckBox" -> {
					sharedPreferences.edit {
						putBoolean("selectAllSupportCards", selectAllSupportCardsCheckBox.isChecked)
					}
					
					// Grab the Support Card items array and then enable/disable the multi-picker.
					items = resources.getStringArray(R.array.support_list)
					val multiplePreference: Preference = findPreference("supportPicker")!!
					multiplePreference.isEnabled = !selectAllSupportCardsCheckBox.isChecked
					
					if (multiplePreference.isEnabled) {
						// Repopulate the multi-picker for Support Cards.
						createSupportCardPicker()
					} else {
						multiplePreference.summary = "Covers all R, SR and SSR variants into one."
					}
					
					// Clear the selected Support Cards and then remove the setting from SharedPreferences.
					userSelectedOptions.clear()
					sharedPreferences.edit {
						remove("supportList")
						apply()
					}
				}
			}
		}
	}
	
	override fun onResume() {
		super.onResume()
		
		// Makes sure that OnSharedPreferenceChangeListener works properly and avoids the situation where the app suddenly stops triggering the listener.
		preferenceScreen.sharedPreferences.registerOnSharedPreferenceChangeListener(onSharedPreferenceChangeListener)
	}
	
	override fun onPause() {
		super.onPause()
		preferenceScreen.sharedPreferences.unregisterOnSharedPreferenceChangeListener(onSharedPreferenceChangeListener)
	}
	
	// This function is called right after the user navigates to the SettingsFragment.
	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		// Display the layout using the preferences xml.
		setPreferencesFromResource(R.xml.preferences_training_event, rootKey)
		
		// Get the SharedPreferences.
		sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
		
		// Grab the saved preferences from the previous time the user used the app.
		val character = sharedPreferences.getString("character", "")
		val selectAllCharacters = sharedPreferences.getBoolean("selectAllCharacters", true)
		val selectAllSupportCards = sharedPreferences.getBoolean("selectAllSupportCards", true)
		
		// Get references to the Preference components.
		val characterPicker: ListPreference = findPreference("characterPicker")!!
		val multiplePreference: Preference = findPreference("supportPicker")!!
		val selectAllCharactersCheckBox: CheckBoxPreference = findPreference("selectAllCharactersCheckBox")!!
		val selectAllSupportCardsCheckBox: CheckBoxPreference = findPreference("selectAllSupportCardsCheckBox")!!
		
		// Now set the following values from the shared preferences.
		
		if (character != null && character.isNotEmpty() && character != "") {
			characterPicker.value = character
			characterPicker.summary = "Covers all R, SR and SSR variants into one.\n\n${characterPicker.value}"
		}
		
		// Populate the list in the multi-picker for Support Cards.
		if (!selectAllSupportCards) {
			multiplePreference.isEnabled = true
			createSupportCardPicker()
		} else {
			multiplePreference.isEnabled = false
		}
		
		characterPicker.isEnabled = !selectAllCharactersCheckBox.isChecked
		multiplePreference.isEnabled = !selectAllSupportCardsCheckBox.isChecked
		selectAllCharactersCheckBox.isChecked = selectAllCharacters
		selectAllSupportCardsCheckBox.isChecked = selectAllSupportCards
		
		Log.d(TAG, "Training Event Preferences created successfully.")
	}
	
	/**
	 * Build the Multi-Picker Alert Dialog for the Support Cards.
	 */
	private fun createSupportCardPicker() {
		val multiplePreference: Preference = findPreference("supportPicker")!!
		val savedOptions = sharedPreferences.getString("supportList", "")!!.split("|")
		val selectedOptions = sharedPreferences.getString("selectedOptions", "")!!.split("|")
		
		// Update the Preference's summary to reflect the order of options selected if the user did it before.
		if (savedOptions.toList().isEmpty() || savedOptions.toList()[0] == "") {
			multiplePreference.summary = "Covers all R, SR and SSR variants into one."
		} else {
			multiplePreference.summary = "Covers all R, SR and SSR variants into one.\n\n${savedOptions.toList()}"
		}
		
		multiplePreference.setOnPreferenceClickListener {
			// Create the AlertDialog that pops up after clicking on this Preference.
			builder = AlertDialog.Builder(context)
			builder.setTitle("Select Option(s)")
			
			// Grab the Support Card items array.
			items = resources.getStringArray(R.array.support_list)
			
			// Populate the list for multiple options if this is the first time.
			if (savedOptions.isEmpty() || savedOptions[0] == "") {
				checkedItems = BooleanArray(items.size)
				var index = 0
				items.forEach { _ ->
					checkedItems[index] = false
					index++
				}
			} else {
				checkedItems = BooleanArray(items.size)
				var index = 0
				items.forEach {
					// Populate the checked items BooleanArray with true or false depending on what the user selected before.
					checkedItems[index] = savedOptions.contains(it)
					index++
				}
				
				// Repopulate the user selected options according to its order selected.
				userSelectedOptions.clear()
				selectedOptions.forEach {
					userSelectedOptions.add(it.toInt())
				}
			}
			
			// Set the selectable items for this AlertDialog.
			builder.setMultiChoiceItems(items, checkedItems) { _, position, isChecked ->
				if (isChecked) {
					userSelectedOptions.add(position)
				} else {
					userSelectedOptions.remove(position)
				}
			}
			
			// Set the AlertDialog's PositiveButton.
			builder.setPositiveButton("OK") { _, _ ->
				// Grab the options using the acquired indexes. This will put them in order from the user's highest to lowest priority.
				val values: ArrayList<String> = arrayListOf()
				
				userSelectedOptions.forEach {
					values.add(items[it])
				}
				
				// Join the elements together into a String with the "|" delimiter in order to keep its order when storing into SharedPreferences.
				val newValues = values.joinToString("|")
				val newSelectedOptions = userSelectedOptions.joinToString("|")
				
				// Note: putStringSet does not support ordering or duplicate values. If you need ordering/duplicate values, either concatenate the values together as a String separated by a
				// delimiter or think of another way.
				sharedPreferences.edit {
					putString("supportList", newValues)
					putString("selectedOptions", newSelectedOptions)
					apply()
				}
				
				// Recreate the AlertDialog again to update it with the newly selected items.
				createSupportCardPicker()
				
				if (values.toList().isEmpty()) {
					multiplePreference.summary = "Covers all R, SR and SSR variants into one."
				} else {
					multiplePreference.summary = "Covers all R, SR and SSR variants into one.\n\n${values.toList()}"
				}
			}
			
			// Set the AlertDialog's NegativeButton.
			builder.setNegativeButton("Dismiss") { dialog, _ -> dialog?.dismiss() }
			
			// Set the AlertDialog's NeutralButton.
			builder.setNeutralButton("Clear all") { _, _ ->
				// Go through every checked item and set them to false.
				for (i in checkedItems.indices) {
					checkedItems[i] = false
				}
				
				// After that, clear the list of user-selected options and the one in SharedPreferences.
				userSelectedOptions.clear()
				sharedPreferences.edit {
					remove("supportList")
					apply()
				}
				
				// Recreate the AlertDialog again to update it with the newly selected items and reset its summary.
				createSupportCardPicker()
				multiplePreference.summary = "Covers all R, SR and SSR variants into one."
			}
			
			// Finally, show the AlertDialog to the user.
			builder.create().show()
			
			true
		}
	}
}