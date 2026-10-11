package com.example.instagramcooldown

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

import android.widget.EditText

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("InstaCooldownPrefs", Context.MODE_PRIVATE)
        val etUsageLimit = findViewById<EditText>(R.id.etUsageLimit)
        val etCooldownLimit = findViewById<EditText>(R.id.etCooldownLimit)
        
        etUsageLimit.setText(prefs.getInt("usage_limit_min", 5).toString())
        etCooldownLimit.setText(prefs.getInt("cooldown_limit_min", 5).toString())

        findViewById<Button>(R.id.btnPermissionUsage).setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        findViewById<Button>(R.id.btnPermissionOverlay).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                Toast.makeText(this, "Overlay permission already granted", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnStartService).setOnClickListener {
            if (!hasUsageStatsPermission()) {
                Toast.makeText(this, "Please grant Usage Stats permission", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Please grant Overlay permission", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            val usageMin = etUsageLimit.text.toString().toIntOrNull() ?: 5
            val cooldownMin = etCooldownLimit.text.toString().toIntOrNull() ?: 5
            
            prefs.edit()
                .putInt("usage_limit_min", usageMin)
                .putInt("cooldown_limit_min", cooldownMin)
                .apply()

            ServiceController.setEnabled(this, true)
            ServiceController.start(this)
            Toast.makeText(this, "Service started", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnStopService).setOnClickListener {
            ServiceController.setEnabled(this, false)
            ServiceController.stop(this)
            Toast.makeText(this, "Service stopped", Toast.LENGTH_SHORT).show()
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        ServiceController.startIfEnabled(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        ServiceController.startIfEnabled(this)
    }

    private fun hasUsageStatsPermission() = ServiceController.hasUsageStatsPermission(this)
}

