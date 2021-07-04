package com.steve1316.uma_android_automation.ui.settings

import android.app.AlertDialog
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.core.content.edit
import androidx.preference.*
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.R

class TrainingFragment : PreferenceFragmentCompat() {
	private val TAG: String = "[${MainActivity.loggerTag}]TrainingFragment"
	
	private lateinit var sharedPreferences: SharedPreferences
	
	private lateinit var builder: AlertDialog.Builder
	
	private lateinit var items: Array<String>
	private lateinit var checkedItems: BooleanArray
	private var userSelectedOptions: ArrayList<Int> = arrayListOf()
	
	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		// Display the layout using the preferences xml.
		setPreferencesFromResource(R.xml.preferences_training, rootKey)
		
		// Get the SharedPreferences.
		sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
		
		// Grab the saved preferences from the previous time the user used the app.
		val trainingBlacklist = sharedPreferences.getStringSet("trainingBlacklist", setOf())
		val maximumFailureChance = sharedPreferences.getInt("maximumFailureChance", 15)
		
		// Get references to the Preference components.
		val trainingBlacklistPreference = findPreference<MultiSelectListPreference>("trainingBlacklist")!!
		val maximumFailureChancePreference = findPreference<SeekBarPreference>("maximumFailureChance")!!
		
		// Now set the following values from the SharedPreferences.
		trainingBlacklistPreference.values = trainingBlacklist
		maximumFailureChancePreference.value = maximumFailureChance
		createMultiSelectAlertDialog()
		
		// Set this Preference listener to prevent users from blacklisting all 5 Trainings.
		trainingBlacklistPreference.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _: Preference, newValue: Any? ->
			if ((newValue as Set<*>).size != 5) {
				true
			} else {
				Toast.makeText(context, "Cannot blacklist all 5 Trainings!", Toast.LENGTH_SHORT).show()
				false
			}
		}
		
		// Update the summaries of the Preference components.
		updateSummaries()
		
		Log.d(TAG, "Training Preferences created successfully.")
	}
	
	// This listener is triggered whenever the user changes a Preference setting in the Settings Page.
	private val onSharedPreferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
		if (key != null) {
			when (key) {
				"trainingBlacklist" -> {
					val trainingBlacklistPreference = findPreference<MultiSelectListPreference>("trainingBlacklist")!!
					
					val result: MutableSet<String> = mutableSetOf()
					trainingBlacklistPreference.values.forEach { training ->
						result.add(training.replace("Training", "").trim())
					}
					
					sharedPreferences.edit {
						putStringSet("trainingBlacklist", result)
						commit()
					}
				}
				"maximumFailureChance" -> {
					val maximumFailureChancePreference = findPreference<SeekBarPreference>("maximumFailureChance")!!
					
					sharedPreferences.edit {
						putInt("maximumFailureChance", maximumFailureChancePreference.value)
						commit()
					}
				}
			}
			
			updateSummaries()
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
	
	/**
	 * Update the summaries of the Preference components on this page.
	 */
	private fun updateSummaries() {
		val trainingBlacklistPreference = findPreference<MultiSelectListPreference>("trainingBlacklist")!!
		trainingBlacklistPreference.summary = if (trainingBlacklistPreference.values.size != 0) {
			"Select Training(s) to blacklist from being selected in order to narrow the focus of overall Training.\n\nBlacklisted: ${trainingBlacklistPreference.values.joinToString(", ")}"
		} else {
			"Select Training(s) to blacklist from being selected in order to narrow the focus of overall Training.\n\nNone Selected"
		}
		
		val statPrioritisationPreference = findPreference<Preference>("statPrioritisation")!!
		val statPrioritisation: List<String> = sharedPreferences.getString("statPrioritisation", "")!!.split("|")
		statPrioritisationPreference.summary = if (statPrioritisation.isNotEmpty() && statPrioritisation[0] != "") {
			var summaryBody = "Select Stat(s) to prioritise in order from highest priority to lowest. Any stat not selected will be assigned the lowest priority.\n\nOrder of Stat Prioritisation:"
			
			var count = 1
			statPrioritisation.forEach { stat ->
				summaryBody += "\n$count. $stat"
				count++
			}
			
			summaryBody
		} else {
			"Select Stat(s) to prioritise in order from highest priority to lowest. Any stat not selected will be assigned the lowest priority.\n\nFollowing Default Prioritisation Order:\n1. " +
					"Speed\n2. Stamina\n3. Power\n4. Guts\n5. Intelligence"
		}
	}
	
	/**
	 * Builds and displays a AlertDialog for multi-selection that retains its order.
	 * This also serves the purpose of populating the Preference with previously selected values from SharedPreferences.
	 */
	private fun createMultiSelectAlertDialog() {
		val multiplePreference = findPreference<Preference>("statPrioritisation")!!
		val key = "statPrioritisation"
		val savedOptions: List<String> = sharedPreferences.getString("statPrioritisation", "")!!.split("|")
		val selectedOptions: List<String> = sharedPreferences.getString("selectedOptions", "")!!.split("|")
		
		// Update the Preference's summary to reflect the order of options selected if the user did it before.
		updateSummaries()
		
		multiplePreference.setOnPreferenceClickListener {
			// Create the AlertDialog that pops up after clicking on this Preference.
			builder = AlertDialog.Builder(context)
			builder.setTitle("Select Option(s)")
			
			// Grab the Stats items array.
			items = resources.getStringArray(R.array.stats)
			
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
					Log.d(TAG, "Adding $position")
					userSelectedOptions.add(position)
				} else {
					Log.d(TAG, "Removing $position")
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
					putString(key, newValues)
					putString("selectedOptions", newSelectedOptions)
					apply()
				}
				
				// Recreate the AlertDialog again to update it with the newly selected items.
				createMultiSelectAlertDialog()
				updateSummaries()
			}
			
			// Set the AlertDialog's NegativeButton.
			builder.setNegativeButton("Dismiss") { dialog, _ -> dialog?.dismiss() }
			
			// Set the behavior of the AlertDialog canceling.
			builder.setOnCancelListener {
				it.cancel()
				
				// Clear any newly selected options.
				userSelectedOptions.clear()
			}
			
			// Set the AlertDialog's NeutralButton.
			builder.setNeutralButton("Clear all") { _, _ ->
				// Go through every checked item and set them to false.
				for (i in checkedItems.indices) {
					checkedItems[i] = false
				}
				
				// After that, clear the list of user-selected options and the one in SharedPreferences.
				userSelectedOptions.clear()
				sharedPreferences.edit {
					remove(key)
					apply()
				}
				
				// Recreate the AlertDialog again to update it with the newly selected items and reset its summary.
				createMultiSelectAlertDialog()
				updateSummaries()
			}
			
			// Finally, show the AlertDialog to the user.
			builder.create().show()
			
			true
		}
	}
}