package com.example.instagramcooldown

import android.app.KeyguardManager
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
import android.os.PowerManager
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.*
import kotlin.math.max

data class UsageInterval(val startTime: Long, var endTime: Long)

class UsageTrackerService : Service() {

    private val CHANNEL_ID = "InstaCooldownChannel"
    private val PREFS = "InstaCooldownPrefs"

    private var lastTopPackage = ""
    private val usageIntervals = mutableListOf<UsageInterval>()

    private var allowedUsageMs = 5 * 60 * 1000L
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

    private var cooldownEndTime: Long
        get() = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("cooldown_end_time", 0L)
        set(value) = getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("cooldown_end_time", value).apply()

    private var lastResetTime: Long
        get() = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("last_reset_time", 0L)
        set(value) = getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("last_reset_time", value).apply()

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

        initUsageHistoryFromStats()
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
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val defaultUsageMin = prefs.getInt("usage_limit_min", 5)
        val defaultUsageMs = defaultUsageMin * 60 * 1000L
        val cooldownLimitMs = prefs.getInt("cooldown_limit_min", 5) * 60 * 1000L

        if (allowedUsageMs < defaultUsageMs && !isWarningShowing) {
            allowedUsageMs = defaultUsageMs
        }

        val time = System.currentTimeMillis()

        // 1. Check if Cooldown is Active
        if (time < cooldownEndTime) {
            val remainingMs = cooldownEndTime - time
            val minutes = (remainingMs / 1000) / 60
            val seconds = (remainingMs / 1000) % 60

            val topPackage = getForegroundPackage()
            if (topPackage == "com.instagram.android") {
                Toast.makeText(
                    this,
                    String.format("Cooldown active. Try again in %d:%02d", minutes, seconds),
                    Toast.LENGTH_SHORT
                ).show()
                goHome()
            }

            val cdText = String.format("Cooldown active: %d:%02d remaining", minutes, seconds)
            updateNotification(cdText)
            return
        }

        // 2. Identify Current Foreground App
        val topPackage = getForegroundPackage()
        val isInstagram = (topPackage == "com.instagram.android")

        // 3. Update Real-time Usage Intervals
        if (isInstagram) {
            val last = usageIntervals.lastOrNull()
            if (last != null && (time - last.endTime) <= 3000L) {
                last.endTime = time
            } else {
                usageIntervals.add(UsageInterval(time, time))
            }
        }

        // Prune intervals older than 30 minutes
        val pruneThreshold = time - 30 * 60 * 1000L
        usageIntervals.removeAll { it.endTime < pruneThreshold }

        // 4. Calculate Usage in the Sliding Window
        val windowSizeMs = max(10 * 60 * 1000L, defaultUsageMs * 2L)
        val usageMs = getInstagramUsageInWindow(lastResetTime, windowSizeMs)

        val usageSec = usageMs / 1000
        val m = usageSec / 60
        val s = usageSec % 60

        // 5. Handle State & Notifications
        if (isInstagram) {
            val notifText = String.format("%d:%02d passed out of %d:00", m, s, defaultUsageMin)
            updateNotification(notifText)

            if (usageMs >= allowedUsageMs && !isWarningShowing) {
                showWarningOverlay(defaultUsageMin, cooldownLimitMs)
            }
        } else {
            if (isWarningShowing) {
                // If warning was showing and user left, enforce cooldown
                startCooldown(cooldownLimitMs)
            } else {
                if (usageMs < defaultUsageMs) {
                    allowedUsageMs = defaultUsageMs
                    hasExtended = false
                }
            }

            val notifText = if (usageMs > 0) {
                String.format("%d:%02d / %d:00 used (Idle)", m, s, defaultUsageMin)
            } else {
                String.format("Insta Cooldown Active (0:00 / %d:00)", defaultUsageMin)
            }
            updateNotification(notifText)
        }
    }

    private fun updateNotification(text: String) {
        if (text == lastNotifText) return
        lastNotifText = text
        val notifManager = getSystemService(NotificationManager::class.java)
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Insta Cooldown Active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
        notifManager.notify(1, notification)
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
        val now = System.currentTimeMillis()
        cooldownEndTime = now + cooldownLimitMs
        lastResetTime = cooldownEndTime
        hasExtended = false
        usageIntervals.clear()
        removeWarningOverlay()
        goHome()
        val cdMin = cooldownLimitMs / 60000
        Toast.makeText(this, "Cooldown started for $cdMin minutes", Toast.LENGTH_LONG).show()
    }

    private fun removeWarningOverlay() {
        warningJob?.cancel()
        if (isWarningShowing && warningOverlayView != null) {
            try {
                windowManager?.removeView(warningOverlayView)
            } catch (_: Exception) {}
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

    private fun getForegroundPackage(): String {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager != null && !powerManager.isInteractive) {
            return ""
        }

        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguardManager != null && keyguardManager.isKeyguardLocked) {
            return ""
        }

        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return lastTopPackage

        val time = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(time - 60_000, time)
        val event = UsageEvents.Event()
        var latestTime = 0L
        var foundPackage: String? = null

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                if (event.timeStamp >= latestTime) {
                    latestTime = event.timeStamp
                    foundPackage = event.packageName
                }
            }
        }

        if (foundPackage != null) {
            lastTopPackage = foundPackage
        }
        return lastTopPackage
    }

    private fun getInstagramUsageInWindow(sinceTime: Long, windowSizeMs: Long): Long {
        val time = System.currentTimeMillis()
        val windowStart = max(time - windowSizeMs, sinceTime)
        var totalMs = 0L
        for (interval in usageIntervals) {
            if (interval.endTime <= windowStart) continue
            val effectiveStart = max(interval.startTime, windowStart)
            if (interval.endTime > effectiveStart) {
                totalMs += (interval.endTime - effectiveStart)
            }
        }
        return totalMs
    }

    private fun initUsageHistoryFromStats() {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
        val time = System.currentTimeMillis()
        val startTime = max(time - 15 * 60 * 1000L, lastResetTime)
        val events = usageStatsManager.queryEvents(startTime, time)
        val event = UsageEvents.Event()

        var currentForeground = ""
        var instaStart = 0L

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                if (event.packageName == "com.instagram.android") {
                    if (currentForeground != "com.instagram.android") {
                        currentForeground = "com.instagram.android"
                        instaStart = event.timeStamp
                    }
                } else {
                    if (currentForeground == "com.instagram.android") {
                        currentForeground = event.packageName
                        if (event.timeStamp > instaStart) {
                            usageIntervals.add(UsageInterval(instaStart, event.timeStamp))
                        }
                    }
                }
            } else if (event.eventType == UsageEvents.Event.SCREEN_NON_INTERACTIVE) {
                if (currentForeground == "com.instagram.android") {
                    currentForeground = ""
                    if (event.timeStamp > instaStart) {
                        usageIntervals.add(UsageInterval(instaStart, event.timeStamp))
                    }
                }
            }
        }

        if (currentForeground == "com.instagram.android") {
            usageIntervals.add(UsageInterval(instaStart, time))
            lastTopPackage = "com.instagram.android"
        }
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
