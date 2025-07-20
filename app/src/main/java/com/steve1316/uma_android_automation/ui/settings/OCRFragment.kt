package com.steve1316.uma_android_automation.ui.settings

import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import androidx.core.content.edit
import androidx.preference.CheckBoxPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SeekBarPreference
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.R

class OCRFragment : PreferenceFragmentCompat() {
	private val logTag: String = "[${MainActivity.loggerTag}]OCRFragment"
	
	private lateinit var sharedPreferences: SharedPreferences
	
	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		// Display the layout using the preferences xml.
		setPreferencesFromResource(R.xml.preferences_ocr, rootKey)
		
		// Get the SharedPreferences.
		sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
		
		// Grab the saved preferences from the previous time the user used the app.
		val threshold: Int = sharedPreferences.getInt("threshold", 230)
		val enableAutomaticRetry: Boolean = sharedPreferences.getBoolean("enableAutomaticRetry", true)
		val confidence: Int = sharedPreferences.getInt("confidence", 80)
		
		// Get references to the Preference components.
		val thresholdPreference = findPreference<SeekBarPreference>("threshold")!!
		val enableAutomaticRetryPreference = findPreference<CheckBoxPreference>("enableAutomaticRetry")!!
		val confidencePreference = findPreference<SeekBarPreference>("confidence")!!
		
		// Now set the following values from the SharedPreferences.
		thresholdPreference.value = threshold
		enableAutomaticRetryPreference.isChecked = enableAutomaticRetry
		confidencePreference.value = confidence
		
		Log.d(logTag, "OCR Preferences created successfully.")
	}
	
	// This listener is triggered whenever the user changes a Preference setting in the Settings Page.
	private val onSharedPreferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
		if (key != null) {
			when (key) {
				"threshold" -> {
					val thresholdPreference = findPreference<SeekBarPreference>("threshold")!!
					
					sharedPreferences.edit {
						putInt("threshold", thresholdPreference.value)
						commit()
					}
				}
				"enableAutomaticRetry" -> {
					val enableAutomaticRetryPreference = findPreference<CheckBoxPreference>("enableAutomaticRetry")!!
					
					sharedPreferences.edit {
						putBoolean("enableAutomaticRetry", enableAutomaticRetryPreference.isChecked)
						commit()
					}
				}
				"confidence" -> {
					val confidencePreference = findPreference<SeekBarPreference>("confidence")!!
					
					sharedPreferences.edit {
						putInt("confidence", confidencePreference.value)
						commit()
					}
				}
			}
		}
	}
	
	override fun onResume() {
		super.onResume()
		
		// Makes sure that OnSharedPreferenceChangeListener works properly and avoids the situation where the app suddenly stops triggering the listener.
		preferenceScreen.sharedPreferences?.registerOnSharedPreferenceChangeListener(onSharedPreferenceChangeListener)
	}
	
	override fun onPause() {
		super.onPause()
		preferenceScreen.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(onSharedPreferenceChangeListener)
	}
}