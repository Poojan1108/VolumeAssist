package com.volumeassist

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat

class OverlayService : Service() {

    private var overlayView: OverlayView? = null
    private lateinit var preferences: SharedPreferences
    private var wakeLock: PowerManager.WakeLock? = null
    private val notificationId = 1
    private val channelId = "VolumeAssistChannel"
    private val healthCheckHandler = Handler(Looper.getMainLooper())
    private val healthCheckInterval = 5000L // Check every 5 seconds
    
    companion object {
        private var isRunning = false
        
        fun isServiceRunning(): Boolean {
            return isRunning
        }
    }

    override fun onCreate() {
        super.onCreate()
        
        // Prevent multiple instances
        if (isRunning) {
            stopSelf()
            return
        }
        
        // Check if we have overlay permission
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        
        isRunning = true
        preferences = getSharedPreferences("VolumeAssistPrefs", MODE_PRIVATE)
        
        // Mark service as running
        preferences.edit().putBoolean("service_running", true).apply()
        
        // Acquire wake lock for service stability
        acquireWakeLock()
        
        createNotificationChannel()
        startForeground(notificationId, createNotification())
        
        // Create and show overlay with try-catch
        try {
            overlayView = OverlayView(this)
            overlayView?.show()
            // Start health check
            startHealthCheck()
        } catch (e: Exception) {
            e.printStackTrace()
            // Try to recover
            recoverOverlay()
        }
    }
    
    private fun startHealthCheck() {
        healthCheckHandler.postDelayed(object : Runnable {
            override fun run() {
                try {
                    // Check if overlay exists and permissions are still granted
                    if (!Settings.canDrawOverlays(this@OverlayService)) {
                        // Permission revoked, stop service
                        stopSelf()
                        return
                    }
                    
                    // If overlay is null, try to recreate it
                    if (overlayView == null) {
                        overlayView = OverlayView(this@OverlayService)
                        overlayView?.show()
                    }
                    
                    // Schedule next check
                    healthCheckHandler.postDelayed(this, healthCheckInterval)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }, healthCheckInterval)
    }
    
    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "VolumeAssist::ServiceWakeLock"
            ).apply {
                // Acquire for 24 hours, will be released in onDestroy
                acquire(24 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun recoverOverlay() {
        // Attempt recovery after 2 seconds
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                if (overlayView == null && Settings.canDrawOverlays(this)) {
                    overlayView = OverlayView(this)
                    overlayView?.show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // If recovery fails, stop service
                stopSelf()
            }
        }, 2000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY // Restart service if killed
    }

    override fun onDestroy() {
        super.onDestroy()
        
        isRunning = false
        
        // Stop health check
        healthCheckHandler.removeCallbacksAndMessages(null)
        
        // Release wake lock
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        // Mark service as not running
        preferences.edit().putBoolean("service_running", false).apply()
        
        // Remove overlay with null check
        try {
            overlayView?.hide()
            overlayView = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            channelId,
            "VolumeAssist Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps VolumeAssist overlay active"
            setShowBadge(false)
        }
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_content))
            .setSmallIcon(R.drawable.ic_volume)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
