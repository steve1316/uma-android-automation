package com.steve1316.uma_android_automation.ui.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import androidx.core.content.edit
import androidx.navigation.fragment.findNavController
import androidx.preference.CheckBoxPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.R

class SettingsFragment : PreferenceFragmentCompat() {
	private val TAG: String = "[${MainActivity.loggerTag}]SettingsFragment"
	
	private lateinit var sharedPreferences: SharedPreferences
	
	companion object {
		/**
		 * Get a String value from the SharedPreferences using the provided key.
		 *
		 * @param context The context for the application.
		 * @param key The name of the preference to retrieve.
		 * @return The value that is associated with the key.
		 */
		fun getStringSharedPreference(context: Context, key: String): String {
			val sharedPreferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
			return sharedPreferences.getString(key, "not found")!!
		}
		
		/**
		 * Get a Set<String> value from the SharedPreferences using the provided key.
		 *
		 * @param context The context for the application.
		 * @param key The name of the preference to retrieve.
		 * @return The value that is associated with the key.
		 */
		fun getStringSetSharedPreference(context: Context, key: String): Set<String> {
			val sharedPreferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
			return sharedPreferences.getStringSet(key, setOf())!!
		}
		
		/**
		 * Get a Int value from the SharedPreferences using the provided key.
		 *
		 * @param context The context for the application.
		 * @param key The name of the preference to retrieve.
		 * @return The value that is associated with the key.
		 */
		fun getIntSharedPreference(context: Context, key: String): Int {
			val sharedPreferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
			return sharedPreferences.getInt(key, 230)
		}
		
		/**
		 * Get a Boolean value from the SharedPreferences using the provided key.
		 *
		 * @param context The context for the application.
		 * @param key The name of the preference to retrieve.
		 * @return The value that is associated with the key.
		 */
		fun getBooleanSharedPreference(context: Context, key: String): Boolean {
			val sharedPreferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
			return sharedPreferences.getBoolean(key, false)
		}
	}
	
	// This listener is triggered whenever the user changes a Preference setting in the Settings Page.
	private val onSharedPreferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
		if (key != null) {
			// Note that is no need to handle the Preference that allows multiple selection here as it is already handled in its own function.
			when (key) {
				"debugMode" -> {
					val debugModePreference = findPreference<CheckBoxPreference>("debugMode")!!
					
					sharedPreferences.edit {
						putBoolean("debugMode", debugModePreference.isChecked)
						commit()
					}
				}
				"hideComparisonResults" -> {
					val hideComparisonResultsPreference = findPreference<CheckBoxPreference>("hideComparisonResults")!!
					
					sharedPreferences.edit {
						putBoolean("hideComparisonResults", hideComparisonResultsPreference.isChecked)
						commit()
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
		setPreferencesFromResource(R.xml.preferences, rootKey)
		
		// Get the SharedPreferences.
		sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
		
		// Grab the saved preferences from the previous time the user used the app.
		val debugMode: Boolean = sharedPreferences.getBoolean("debugMode", false)
		val hideComparisonResults: Boolean = sharedPreferences.getBoolean("hideComparisonResults", true)
		
		// Get references to the Preference components.
		val debugModePreference = findPreference<CheckBoxPreference>("debugMode")!!
		val hideComparisonResultsPreference = findPreference<CheckBoxPreference>("hideComparisonResults")!!
		
		// Now set the following values from the shared preferences.
		debugModePreference.isChecked = debugMode
		hideComparisonResultsPreference.isChecked = hideComparisonResults
		
		// Solution courtesy of https://stackoverflow.com/a/63368599
		// In short, Fragments via the mobile_navigation.xml are children of NavHostFragment, not MainActivity's supportFragmentManager.
		// This is why using the method described in official Google docs via OnPreferenceStartFragmentCallback and using the supportFragmentManager is not correct for this instance.
		findPreference<Preference>("trainingOptions")?.setOnPreferenceClickListener {
			// Navigate to the TrainingFragment.
			findNavController().navigate(R.id.nav_training)
			true
		}
		findPreference<Preference>("ocrOptions")?.setOnPreferenceClickListener {
			// Navigate to the OCRFragment.
			findNavController().navigate(R.id.nav_ocr)
			true
		}
		
		Log.d(TAG, "Main Preferences created successfully.")
	}
}