package com.volumeassist

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import android.widget.TextView

class   MainActivity : AppCompatActivity() {

    private lateinit var preferences: SharedPreferences
    private lateinit var statusText: TextView
    private lateinit var permissionStatusText: TextView
    private lateinit var permissionButton: MaterialButton
    private lateinit var toggleServiceButton: MaterialButton
    private lateinit var settingsIcon: ImageView

    // Modern permission request launchers
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay permission granted!", Toast.LENGTH_SHORT).show()
            updateUI()
        } else {
            Toast.makeText(this, "Overlay permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        preferences = getSharedPreferences("VolumeAssistPrefs", MODE_PRIVATE)
        
        // On app start, verify service state matches reality
        verifyServiceState()

        initViews()
        setupListeners()
        updateUI()
    }

    private fun initViews() {
        statusText = findViewById(R.id.statusText)
        permissionStatusText = findViewById(R.id.permissionStatusText)
        permissionButton = findViewById(R.id.permissionButton)
        toggleServiceButton = findViewById(R.id.toggleServiceButton)
        settingsIcon = findViewById(R.id.settingsIcon)
    }

    private fun setupListeners() {
        settingsIcon.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        permissionButton.setOnClickListener {
            requestOverlayPermission()
        }

        toggleServiceButton.setOnClickListener {
            toggleService()
        }
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        } else {
            checkBatteryOptimization()
        }
    }
    
    private fun checkBatteryOptimization() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val packageName = packageName
        
        // Note: Battery optimization should only be disabled for apps with legitimate background needs
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            // Show info but don't force - let user decide
            Toast.makeText(
                this,
                "For best performance, consider disabling battery optimization",
                Toast.LENGTH_LONG
            ).show()
        }
        checkNotificationPermission()
    }
    
    private fun checkNotificationPermission() {
        // Notification permission check (API 33+)
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                101
            )
        } else {
            Toast.makeText(this, "All permissions granted!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleService() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.overlay_permission_required, Toast.LENGTH_LONG).show()
            requestOverlayPermission()
            return
        }

        if (isServiceRunning()) {
            stopOverlayService()
        } else {
            startOverlayService()
        }
    }

    private fun startOverlayService() {
        val intent = Intent(this, OverlayService::class.java)
        startForegroundService(intent)
        
        // Delay UI update to allow service to start and set flag
        Handler(Looper.getMainLooper()).postDelayed({
            verifyServiceState()
            updateUI()
        }, 500)
        
        Toast.makeText(this, "Service started", Toast.LENGTH_SHORT).show()
    }

    private fun stopOverlayService() {
        val intent = Intent(this, OverlayService::class.java)
        stopService(intent)
        
        // Immediately mark as stopped to prevent UI glitch
        preferences.edit().putBoolean("service_running", false).apply()
        
        // Update UI immediately
        updateUI()
        
        // Verify again after a delay to ensure consistency
        Handler(Looper.getMainLooper()).postDelayed({
            verifyServiceState()
            updateUI()
        }, 500)
        
        Toast.makeText(this, "Service stopped", Toast.LENGTH_SHORT).show()
    }

    private fun isServiceRunning(): Boolean {
        // Use SharedPreferences as service state indicator instead of deprecated getRunningServices
        return preferences.getBoolean("service_running", false)
    }

    private fun updateUI() {
        val hasOverlayPermission = Settings.canDrawOverlays(this)
        val serviceRunning = isServiceRunning()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryOptimized = !powerManager.isIgnoringBatteryOptimizations(packageName)

        statusText.text = if (serviceRunning) {
            getString(R.string.service_running)
        } else {
            getString(R.string.service_stopped)
        }

        val permissionStatus = buildString {
            append("Overlay: ")
            append(if (hasOverlayPermission) "✓" else "✗")
            append("\nBattery Optimization: ")
            append(if (batteryOptimized) "⚠ Enabled (may affect service)" else "✓ Disabled")
        }
        permissionStatusText.text = permissionStatus

        permissionButton.isEnabled = !hasOverlayPermission || batteryOptimized
        permissionButton.text = when {
            !hasOverlayPermission -> getString(R.string.setup_permissions)
            batteryOptimized -> "Disable Battery Optimization"
            else -> "✓ Permissions Granted"
        }
        
        toggleServiceButton.text = if (serviceRunning) {
            getString(R.string.stop_service)
        } else {
            getString(R.string.start_service)
        }
        
        // Use consistent primary color for both states
        toggleServiceButton.backgroundTintList = getColorStateList(R.color.primary)
    }

    override fun onResume() {
        super.onResume()
        // Verify service state every time we resume
        verifyServiceState()
        updateUI()
    }
    
    private fun verifyServiceState() {
        // Check if service is actually running using the companion object
        val actuallyRunning = OverlayService.isServiceRunning()
        val flagSaysRunning = preferences.getBoolean("service_running", false)
        
        // If flag and reality don't match, fix the flag
        if (actuallyRunning != flagSaysRunning) {
            preferences.edit().putBoolean("service_running", actuallyRunning).apply()
        }
        
        // If service says it's running but overlay permission is gone, stop service
        if (actuallyRunning && !Settings.canDrawOverlays(this)) {
            stopOverlayService()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Notification permission granted!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Notification permission denied. Service notifications may not show.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
