package com.example.instagramcooldown

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
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

            val intent = Intent(this, UsageTrackerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Toast.makeText(this, "Service started", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), packageName
            )
        } else {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }
}

