package com.steve1316.uma_android_automation

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.net.toUri
import androidx.drawerlayout.widget.DrawerLayout
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.navigation.NavigationView
import org.opencv.android.OpenCVLoader
import java.util.Locale


class MainActivity : AppCompatActivity() {
	private lateinit var appBarConfiguration: AppBarConfiguration
	
	companion object {
		const val loggerTag: String = "UAA"
	}
	
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_main)
		val toolbar: Toolbar = findViewById(R.id.toolbar)
		setSupportActionBar(toolbar)
		
		val drawerLayout: DrawerLayout = findViewById(R.id.drawer_layout)
		val navView: NavigationView = findViewById(R.id.nav_view)
		val navController = findNavController(R.id.nav_host_fragment)

		// Set application locale to combat cases where user's language uses commas instead of decimal points for floating numbers.
		val config: Configuration? = this.getResources().configuration
		val locale = Locale("en")
		Locale.setDefault(locale)
		this.getResources().updateConfiguration(config, this.getResources().displayMetrics)
		
		// Set the Link to the "Go to GitHub" button.
		val githubTextView: TextView = findViewById(R.id.github_textView)
		githubTextView.setOnClickListener {
			val newIntent = Intent(Intent.ACTION_VIEW, "https://github.com/steve1316/uma-android-automation".toUri())
			startActivity(newIntent)
		}
		
		appBarConfiguration = AppBarConfiguration(setOf(R.id.nav_home, R.id.nav_settings), drawerLayout)
		
		setupActionBarWithNavController(navController, appBarConfiguration)
		navView.setupWithNavController(navController)
		
		// Load OpenCV native library. This will throw a "E/OpenCV/StaticHelper: OpenCV error: Cannot load info library for OpenCV". It is safe to
		// ignore this error. OpenCV functionality is not impacted by this error.
		OpenCVLoader.initDebug()
	}
	
	override fun onSupportNavigateUp(): Boolean {
		val navController = findNavController(R.id.nav_host_fragment)
		return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
	}
}