package com.seungjae.jangsu280battery

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView

class BikeModeChooserActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bike_mode_chooser)
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
        findViewById<TextView>(R.id.tvBikeModeVersion).text = "Ride Copilot v$version"
        findViewById<Button>(R.id.btnBikeModeEmtb).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        findViewById<Button>(R.id.btnBikeModeRoad).setOnClickListener {
            startActivity(Intent(this, RoadGranfondoActivity::class.java))
        }
    }
}
