package com.example.instagramcooldown

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.*
import kotlin.math.max

class UsageTrackerService : Service() {

    private val CHANNEL_ID = "InstaCooldownChannel"
    private var lastTopPackage = ""
    
    private var lastResetTime = 0L
    private var allowedUsageMs = 5 * 60 * 1000L
    private var cooldownEndTime = 0L
    
    private var isWarningShowing = false
    private var warningOverlayView: View? = null
    private var windowManager: WindowManager? = null
    
    private var lastNotifText = ""
    private var hasExtended = false
    
    private val handler = Handler(Looper.getMainLooper())
    private val checkRunnable = object : Runnable {
        override fun run() {
            checkUsage()
            handler.postDelayed(this, 1000)
        }
    }
    
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var warningJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Insta Cooldown Active")
            .setContentText("Tracking Instagram usage...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }

        handler.post(checkRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(checkRunnable)
        serviceScope.cancel()
        removeWarningOverlay()
    }

    private fun checkUsage() {
        val prefs = getSharedPreferences("InstaCooldownPrefs", Context.MODE_PRIVATE)
        val defaultUsageMin = prefs.getInt("usage_limit_min", 5)
        val defaultUsageMs = defaultUsageMin * 60 * 1000L
        val cooldownLimitMs = prefs.getInt("cooldown_limit_min", 5) * 60 * 1000L
        
        if (allowedUsageMs < defaultUsageMs && !isWarningShowing) {
            allowedUsageMs = defaultUsageMs
        }

        val topPackage = getTopPackageByEvents()
        val time = System.currentTimeMillis()
        
        if (topPackage == "com.instagram.android") {
            if (time < cooldownEndTime) {
                val remainingMs = cooldownEndTime - time
                val minutes = (remainingMs / 1000) / 60
                val seconds = (remainingMs / 1000) % 60
                Toast.makeText(this, String.format("Cooldown active. Try again in %d:%02d", minutes, seconds), Toast.LENGTH_SHORT).show()
                goHome()
                return
            }

            val windowSizeMs = defaultUsageMs * 2L
            val usageMs = getInstagramUsageSince(lastResetTime, max(10 * 60 * 1000L, windowSizeMs))
            
            // Update Notification every second with precise time
            val usageSec = usageMs / 1000
            val m = usageSec / 60
            val s = usageSec % 60
            val notifText = String.format("%d:%02d passed out of %d:00", m, s, defaultUsageMin)
            
            if (notifText != lastNotifText) {
                lastNotifText = notifText
                val notifManager = getSystemService(NotificationManager::class.java)
                val notification = Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("Insta Cooldown Active")
                    .setContentText(notifText)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setOnlyAlertOnce(true)
                    .setOngoing(true)
                    .build()
                notifManager.notify(1, notification)
            }
            
            if (usageMs >= allowedUsageMs && !isWarningShowing) {
                showWarningOverlay(defaultUsageMin, cooldownLimitMs)
            }
        } else {
            if (isWarningShowing) {
                startCooldown(cooldownLimitMs)
            } else {
                allowedUsageMs = defaultUsageMs
                hasExtended = false
            }
        }
    }

    private fun showWarningOverlay(limitMin: Int, cooldownLimitMs: Long) {
        isWarningShowing = true
        
        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        warningOverlayView = inflater.inflate(R.layout.overlay_warning, null)
        
        val tvMessage = warningOverlayView!!.findViewById<TextView>(R.id.tvMessage)
        val tvCountdown = warningOverlayView!!.findViewById<TextView>(R.id.tvCountdown)
        val btnContinue = warningOverlayView!!.findViewById<Button>(R.id.btnContinue)
        
        if (hasExtended) {
            btnContinue.visibility = View.GONE
            tvMessage.text = "Time is up! No more extensions allowed."
        } else {
            btnContinue.visibility = View.VISIBLE
            tvMessage.text = "You've used Instagram for $limitMin minutes continuously."
        }
        
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.CENTER
        
        windowManager?.addView(warningOverlayView, params)
        
        var countdown = 5
        warningJob = serviceScope.launch {
            while (countdown > 0) {
                tvCountdown.text = "Closing in $countdown..."
                delay(1000)
                countdown--
            }
            startCooldown(cooldownLimitMs)
        }
        
        btnContinue.setOnClickListener {
            warningJob?.cancel()
            allowedUsageMs += 30 * 1000L
            hasExtended = true
            removeWarningOverlay()
        }
    }

    private fun startCooldown(cooldownLimitMs: Long) {
        warningJob?.cancel()
        cooldownEndTime = System.currentTimeMillis() + cooldownLimitMs
        lastResetTime = cooldownEndTime
        allowedUsageMs = cooldownLimitMs // dummy, reset properly on next tick
        hasExtended = false
        removeWarningOverlay()
        goHome()
        val cdMin = cooldownLimitMs / 60000
        Toast.makeText(this, "Cooldown started for $cdMin minutes", Toast.LENGTH_LONG).show()
    }

    private fun removeWarningOverlay() {
        if (isWarningShowing && warningOverlayView != null) {
            windowManager?.removeView(warningOverlayView)
            warningOverlayView = null
            isWarningShowing = false
        }
    }

    private fun goHome() {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.addCategory(Intent.CATEGORY_HOME)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
    }

    private fun getTopPackageByEvents(): String {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val time = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(time - 1000 * 60, time)
        val event = UsageEvents.Event()
        var top = lastTopPackage
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                top = event.packageName
            } else if (event.eventType == UsageEvents.Event.ACTIVITY_PAUSED || event.eventType == UsageEvents.Event.ACTIVITY_STOPPED) {
                if (top == event.packageName) {
                    top = ""
                }
            }
        }
        lastTopPackage = top
        return top
    }

    private fun getInstagramUsageSince(sinceTime: Long, windowSizeMs: Long): Long {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val time = System.currentTimeMillis()
        val startTime = max(time - windowSizeMs, sinceTime)
        val events = usageStatsManager.queryEvents(startTime, time)
        val event = UsageEvents.Event()
        var totalTimeMs = 0L
        var lastResumedTime = 0L
        var isResumed = false
        var hasSeenFirstEvent = false
        
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.packageName == "com.instagram.android") {
                if (!hasSeenFirstEvent) {
                    hasSeenFirstEvent = true
                    if (event.eventType == UsageEvents.Event.ACTIVITY_PAUSED || event.eventType == UsageEvents.Event.ACTIVITY_STOPPED) {
                        totalTimeMs += (event.timeStamp - startTime)
                    }
                }
                
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    lastResumedTime = event.timeStamp
                    isResumed = true
                } else if (event.eventType == UsageEvents.Event.ACTIVITY_PAUSED || event.eventType == UsageEvents.Event.ACTIVITY_STOPPED) {
                    if (isResumed) {
                        totalTimeMs += (event.timeStamp - lastResumedTime)
                        isResumed = false
                    }
                }
            }
        }
        if (isResumed) {
            totalTimeMs += (time - lastResumedTime)
        } else if (!hasSeenFirstEvent && lastTopPackage == "com.instagram.android") {
            totalTimeMs += (time - startTime)
        }
        return totalTimeMs
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Insta Cooldown Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }
}
