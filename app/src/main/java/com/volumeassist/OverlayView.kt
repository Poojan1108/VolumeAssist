package com.volumeassist

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.volumeassist.utils.VolumeController
import kotlin.math.abs

@SuppressLint("ClickableViewAccessibility")
class OverlayView(private val context: Context) {

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val volumeController = VolumeController(context)
    private val vibrator: Vibrator = run {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    }
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val preferences: SharedPreferences = context.getSharedPreferences("VolumeAssistOverlay", Context.MODE_PRIVATE)
    private val appPreferences: SharedPreferences = context.getSharedPreferences("VolumeAssistPrefs", Context.MODE_PRIVATE)

    // Views
    private var collapsedView: View? = null
    private var expandedView: View? = null
    private var currentView: View? = null
    private var touchDetectorView: View? = null  // Transparent view to detect touches when hidden

    // State
    private var isExpanded = false
    private var isFlashlightOn = false
    private var cameraId: String? = null

    // Touch handling
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    
    // Orientation detection for auto-hide in landscape
    private var orientationListener: OrientationEventListener? = null
    private var isInLandscapeMode = false
    private var wasHiddenForLandscape = false

    // Auto-collapse
    private val autoCollapseHandler = Handler(Looper.getMainLooper())
    private val autoCollapseDelay = 3000L // 3 seconds
    private val landscapeAutoHideDelay = 4000L // 4 seconds for landscape
    
    // Hold-to-increment volume control
    private val volumeHoldHandler = Handler(Looper.getMainLooper())
    private var volumeHoldRunnable: Runnable? = null
    private var isHoldingVolumeButton = false
    private var holdDuration = 0L
    
    // Broadcast receiver for overlay updates
    private val overlayUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.volumeassist.UPDATE_OVERLAY") {
                Log.d("OverlayView", "Received UPDATE_OVERLAY broadcast")
                applyCustomizations()
            }
        }
    }

    init {
        // Get camera ID for flashlight
        try {
            cameraId = cameraManager.cameraIdList[0]
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
        
        // Load flashlight state
        isFlashlightOn = preferences.getBoolean("flashlight_on", false)
        
        // Setup orientation listener
        setupOrientationListener()
        
        // Register broadcast receiver for overlay updates
        val filter = IntentFilter("com.volumeassist.UPDATE_OVERLAY")
        // Use RECEIVER_EXPORTED for API 33+ to allow other app components to send broadcasts
        context.registerReceiver(overlayUpdateReceiver, filter, Context.RECEIVER_EXPORTED)
    }

    fun show() {
        if (collapsedView == null) {
            createCollapsedView()
        }
        // Apply saved customizations when showing overlay
        applyCustomizations()
        showCollapsed()
        startOrientationDetection()
    }

    fun hide() {
        try {
            // Unregister broadcast receiver
            try {
                context.unregisterReceiver(overlayUpdateReceiver)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            // Clean up handlers to prevent memory leaks
            autoCollapseHandler.removeCallbacksAndMessages(null)
            stopOrientationDetection()
            
            // Turn off flashlight if on
            if (isFlashlightOn) {
                turnOffFlashlight()
            }
            
            // Save flashlight state
            preferences.edit().putBoolean("flashlight_on", isFlashlightOn).apply()
            
            // Remove views
            collapsedView?.let { 
                try {
                    windowManager.removeView(it)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            expandedView?.let { 
                try {
                    windowManager.removeView(it)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            touchDetectorView?.let {
                try {
                    windowManager.removeView(it)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            collapsedView = null
            expandedView = null
            currentView = null
            touchDetectorView = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createCollapsedView() {
        @Suppress("InflateParams")
        collapsedView = LayoutInflater.from(context)
            .inflate(R.layout.overlay_collapsed, null, false)

        collapsedView?.setOnTouchListener { view, event ->
            handleCollapsedTouch(view, event)
        }
        
        // Apply customizations
        applyCustomizations()
    }

    private fun createExpandedView() {
        // Always recreate to ensure fresh state
        @Suppress("InflateParams")
        expandedView = LayoutInflater.from(context)
            .inflate(R.layout.overlay_expanded, null, false)

        setupExpandedViewListeners()
        
        // Apply customizations
        applyCustomizations()
    }

    private fun setupExpandedViewListeners() {
        expandedView?.apply {
            // Check if system volume mode is enabled
            val useSystemVolume = appPreferences.getBoolean("use_system_volume", false)
            
            // Hide volume controls if system volume mode (should not reach here if system mode is on, but just in case)
            updateVolumeControlsVisibility(useSystemVolume)
            
            // Volume Up Button - with hold-to-increment
            findViewById<ImageView>(R.id.volumeUpButton).apply {
                setOnTouchListener { _, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            vibrate()
                            isHoldingVolumeButton = true
                            holdDuration = 0L
                            
                            // Immediate first increment
                            volumeController.increaseVolume()
                            updateVolumeDisplay()
                            
                            // Start hold-to-increment with acceleration
                            startVolumeHold(true)
                            resetAutoCollapse()
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            isHoldingVolumeButton = false
                            stopVolumeHold()
                            true
                        }
                        else -> false
                    }
                }
                // Add extra touch padding
                post { addTouchPadding(this, 16) }
            }

            // Volume Down Button - with hold-to-increment
            findViewById<ImageView>(R.id.volumeDownButton).apply {
                setOnTouchListener { _, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            vibrate()
                            isHoldingVolumeButton = true
                            holdDuration = 0L
                            
                            // Immediate first decrement
                            volumeController.decreaseVolume()
                            updateVolumeDisplay()
                            
                            // Start hold-to-decrement with acceleration
                            startVolumeHold(false)
                            resetAutoCollapse()
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            isHoldingVolumeButton = false
                            stopVolumeHold()
                            true
                        }
                        else -> false
                    }
                }
                // Add extra touch padding
                post { addTouchPadding(this, 16) }
            }

            // Lock Screen Button with larger touch area
            findViewById<ImageView>(R.id.lockScreenButton).apply {
                setOnClickListener {
                    vibrate()
                    val locked = lockScreen()
                    if (!locked) {
                        // Show feedback if lock failed
                        showToast("Lock screen requires accessibility service")
                    }
                }
                // Add extra touch padding
                post { addTouchPadding(this, 16) }
            }

            // Flashlight Button with larger touch area
            findViewById<ImageView>(R.id.flashlightButton).apply {
                setOnClickListener {
                    vibrate()
                    val success = toggleFlashlight()
                    if (!success) {
                        showToast("Flashlight not available")
                    }
                    resetAutoCollapse()
                }
                // Add extra touch padding
                post { addTouchPadding(this, 16) }
            }

            // Collapse Button with larger touch area
            findViewById<ImageView>(R.id.collapseButton).apply {
                setOnClickListener {
                    collapse()
                }
                // Add extra touch padding
                post { addTouchPadding(this, 12) }
            }
        }
    }
    
    // Helper function to add touch padding to views
    private fun addTouchPadding(view: View, paddingDp: Int) {
        val parent = view.parent as? View ?: return
        val paddingPx = (paddingDp * context.resources.displayMetrics.density).toInt()
        
        parent.post {
            val rect = android.graphics.Rect()
            view.getHitRect(rect)
            rect.inset(-paddingPx, -paddingPx)
            
            parent.touchDelegate = android.view.TouchDelegate(rect, view)
        }
    }

    private fun handleCollapsedTouch(view: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Store initial touch position
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                
                // Get current layout params
                val params = view.layoutParams as WindowManager.LayoutParams
                initialX = params.x
                initialY = params.y
                
                isDragging = false
                
                // If in landscape mode and overlay is invisible, make it visible
                if (isInLandscapeMode && view.visibility != View.VISIBLE) {
                    view.visibility = View.VISIBLE
                    wasHiddenForLandscape = false
                }
                
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = abs(event.rawX - initialTouchX)
                val deltaY = abs(event.rawY - initialTouchY)

                // Start dragging if moved more than 10px
                if (deltaX > 10 || deltaY > 10) {
                    isDragging = true
                    
                    val params = view.layoutParams as WindowManager.LayoutParams
                    
                    // Calculate new position based on finger movement
                    val newX = initialX + (event.rawX - initialTouchX).toInt()
                    val newY = initialY + (event.rawY - initialTouchY).toInt()
                    
                    // Get screen size to constrain position
                    val displayMetrics = context.resources.displayMetrics
                    val screenWidth = displayMetrics.widthPixels
                    val screenHeight = displayMetrics.heightPixels
                    
                    // Constrain X and Y to keep overlay on screen
                    val constrainedX = newX.coerceIn(0, screenWidth - view.width)
                    val constrainedY = newY.coerceIn(0, screenHeight - view.height)
                    
                    // Update position
                    params.x = constrainedX
                    params.y = constrainedY
                    
                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    // Tap detected
                    vibrate()
                    
                    // Check if system volume mode is enabled
                    val useSystemVolume = appPreferences.getBoolean("use_system_volume", false)
                    if (useSystemVolume) {
                        // Show system volume bar directly
                        volumeController.increaseVolumeWithUI()
                    } else {
                        // Expand to show custom controls
                        expand()
                    }
                } else {
                    // Save final position after dragging stopped
                    val params = view.layoutParams as WindowManager.LayoutParams
                    preferences.edit()
                        .putInt("overlay_x", params.x)
                        .putInt("overlay_y", params.y)
                        .apply()
                }
                
                // If in landscape mode, schedule auto-hide
                if (isInLandscapeMode) {
                    scheduleLandscapeAutoHide()
                }
                
                return true
            }
        }
        return false
    }

    private fun showCollapsed() {
        if (collapsedView == null) {
            createCollapsedView()
        }
        
        try {
            // If there's already a view showing, don't add another
            if (currentView != null && currentView == collapsedView) return

            val params = createCollapsedLayoutParams()
            windowManager.addView(collapsedView, params)
            currentView = collapsedView
            isExpanded = false
        } catch (e: Exception) {
            e.printStackTrace()
            // If adding fails, try to recover
            try {
                currentView = null
                if (collapsedView != null) {
                    val params = createCollapsedLayoutParams()
                    windowManager.addView(collapsedView, params)
                    currentView = collapsedView
                    isExpanded = false
                }
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
    }

    private fun expand() {
        if (isExpanded) return

        try {
            // Remove collapsed view first
            if (collapsedView != null && currentView == collapsedView) {
                try {
                    windowManager.removeView(collapsedView)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            
            // Clean up old expanded view if exists
            if (expandedView != null) {
                try {
                    windowManager.removeView(expandedView)
                } catch (e: Exception) {
                    // View might not be attached, ignore
                }
                expandedView = null
            }
            
            // Reset current view
            currentView = null
            
            // Create new expanded view
            createExpandedView()
            updateVolumeDisplay()

            // Add expanded view
            val params = createExpandedLayoutParams()
            windowManager.addView(expandedView, params)
            currentView = expandedView
            isExpanded = true

            // Start auto-collapse timer
            resetAutoCollapse()
        } catch (e: Exception) {
            e.printStackTrace()
            // If expansion fails, try to show collapsed view
            try {
                currentView = null
                isExpanded = false
                showCollapsed()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
    }

    private fun collapse() {
        if (!isExpanded) return

        try {
            // Cancel auto-collapse timer first
            autoCollapseHandler.removeCallbacksAndMessages(null)
            
            // Remove expanded view
            if (expandedView != null && currentView == expandedView) {
                try {
                    windowManager.removeView(expandedView)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            
            // Reset state
            currentView = null
            isExpanded = false

            // Show collapsed view again
            showCollapsed()
            
        } catch (e: Exception) {
            e.printStackTrace()
            // Emergency recovery - try to show collapsed view
            try {
                currentView = null
                isExpanded = false
                showCollapsed()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
    }

    private fun resetAutoCollapse() {
        autoCollapseHandler.removeCallbacksAndMessages(null)
        
        if (isInLandscapeMode) {
            // In landscape mode, hide after 4 seconds
            scheduleLandscapeAutoHide()
        } else {
            // Normal mode (portrait), collapse after 3 seconds
            autoCollapseHandler.postDelayed({
                if (isExpanded) {
                    collapse()
                }
            }, autoCollapseDelay)
        }
    }

    private fun createCollapsedLayoutParams(): WindowManager.LayoutParams {
        // Load saved position or use defaults
        val savedX = preferences.getInt("overlay_x", 800) // Default near right side
        val savedY = preferences.getInt("overlay_y", 500)

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START  // Use START for RTL support
            x = savedX
            y = savedY
        }
    }

    private fun createExpandedLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START  // Use START for RTL support
            x = 800 // Near right side
            y = 300 // Higher up when expanded
        }
    }

    private fun updateVolumeDisplay() {
        expandedView?.apply {
            val volume = volumeController.getCurrentVolume()
            findViewById<TextView>(R.id.volumeText).text = context.getString(R.string.volume_percentage, volume)
        }
    }

    /**
     * Update visibility of volume controls based on system volume preference
     * Note: Percentage text visibility is managed here
     */
    private fun updateVolumeControlsVisibility(useSystemVolume: Boolean) {
        expandedView?.apply {
            val volumeText = findViewById<TextView>(R.id.volumeText)
            
            if (useSystemVolume) {
                // Hide percentage when using system volume
                volumeText?.visibility = View.GONE
            } else {
                // Show percentage when using custom controls
                volumeText?.visibility = View.VISIBLE
            }
        }
    }

    private fun lockScreen() : Boolean {
        return try {
            // Method 1: Try using power key event (works on many devices without root)
            try {
                @Suppress("SpellCheckingInspection")
                Runtime.getRuntime().exec("input keyevent 26").waitFor()
                return true
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            // Method 2: Try admin device policy (if user has enabled)
            try {
                val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                devicePolicyManager.lockNow()
                return true
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun toggleFlashlight(): Boolean {
        if (cameraId == null) return false

        return try {
            // Check camera permission before toggling
            if (context.checkSelfPermission(android.Manifest.permission.CAMERA) 
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                // Permission revoked - turn off flashlight if it was on
                if (isFlashlightOn) {
                    isFlashlightOn = false
                    preferences.edit().putBoolean("flashlight_on", false).apply()
                }
                showToast("Camera permission required for flashlight")
                return false
            }
            
            isFlashlightOn = !isFlashlightOn
            cameraManager.setTorchMode(cameraId!!, isFlashlightOn)
            
            // Save state
            preferences.edit().putBoolean("flashlight_on", isFlashlightOn).apply()

            // Update button appearance
            expandedView?.findViewById<ImageView>(R.id.flashlightButton)?.alpha =
                if (isFlashlightOn) 1.0f else 0.6f
                
            true
        } catch (e: CameraAccessException) {
            e.printStackTrace()
            false
        }
    }
    
    private fun turnOffFlashlight() {
        if (cameraId == null || !isFlashlightOn) return
        
        try {
            cameraManager.setTorchMode(cameraId!!, false)
            isFlashlightOn = false
            preferences.edit().putBoolean("flashlight_on", false).apply()
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }
    
    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun vibrate() {
        try {
            vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Start continuous volume adjustment while button is held
     * Implements professional acceleration: slow start, then faster
     */
    private fun startVolumeHold(isIncrement: Boolean) {
        volumeHoldRunnable = object : Runnable {
            override fun run() {
                if (!isHoldingVolumeButton) return
                
                holdDuration += getVolumeHoldDelay()
                
                // Adjust volume
                if (isIncrement) {
                    volumeController.increaseVolume()
                } else {
                    volumeController.decreaseVolume()
                }
                updateVolumeDisplay()
                
                // Schedule next increment with acceleration
                volumeHoldHandler.postDelayed(this, getVolumeHoldDelay())
            }
        }
        
        // Start with initial delay before continuous adjustment
        volumeHoldHandler.postDelayed(volumeHoldRunnable!!, 500L)
    }

    /**
     * Stop continuous volume adjustment
     */
    private fun stopVolumeHold() {
        volumeHoldRunnable?.let {
            volumeHoldHandler.removeCallbacks(it)
        }
        volumeHoldRunnable = null
        holdDuration = 0L
    }

    /**
     * Get delay between volume adjustments with professional acceleration
     * Starts slow, gradually speeds up, then very fast
     */
    private fun getVolumeHoldDelay(): Long {
        return when {
            holdDuration < 1000L -> 200L   // First second: 200ms delay (5 steps/sec)
            holdDuration < 2000L -> 100L   // Second: 100ms delay (10 steps/sec)
            holdDuration < 3000L -> 50L    // Third second: 50ms delay (20 steps/sec)
            else -> 30L                     // After 3 sec: 30ms delay (33 steps/sec) - very fast
        }
    }

    private fun setupOrientationListener() {
        orientationListener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                // Determine if phone is in landscape based on orientation
                val isLandscape = when (orientation) {
                    in 45..134 -> true   // Landscape (left)
                    in 225..314 -> true  // Landscape (right)
                    else -> false        // Portrait
                }
                
                // Only act on orientation changes
                if (isLandscape != isInLandscapeMode) {
                    isInLandscapeMode = isLandscape
                    handleOrientationChange()
                }
            }
        }
    }
    
    private fun startOrientationDetection() {
        orientationListener?.enable()
        
        // Check current orientation immediately
        val currentOrientation = context.resources.configuration.orientation
        isInLandscapeMode = (currentOrientation == Configuration.ORIENTATION_LANDSCAPE)
        if (isInLandscapeMode) {
            handleOrientationChange()
        }
    }
    
    private fun stopOrientationDetection() {
        orientationListener?.disable()
    }
    
    private fun handleOrientationChange() {
        if (isInLandscapeMode) {
            // Entering landscape mode - schedule auto-hide
            scheduleLandscapeAutoHide()
        } else {
            // Entering portrait mode - show overlay if it was hidden
            if (wasHiddenForLandscape && currentView != null) {
                currentView?.visibility = View.VISIBLE
                wasHiddenForLandscape = false
            }
            // Remove touch detector in portrait mode
            removeTouchDetector()
            // Cancel landscape auto-hide timer
            autoCollapseHandler.removeCallbacksAndMessages(null)
        }
    }
    
    // Schedule auto-hide when in landscape mode
    private fun scheduleLandscapeAutoHide() {
        autoCollapseHandler.removeCallbacksAndMessages(null)
        autoCollapseHandler.postDelayed({
            if (isInLandscapeMode && currentView?.visibility == View.VISIBLE) {
                // Hide overlay after inactivity in landscape
                currentView?.visibility = View.INVISIBLE
                wasHiddenForLandscape = true
                // Show transparent touch detector so user can tap to reveal
                showTouchDetector()
            }
        }, landscapeAutoHideDelay) // Hide after 4 seconds
    }
    
    // Create a transparent full-screen touch detector
    private fun showTouchDetector() {
        if (touchDetectorView != null) return // Already showing
        
        // Create a transparent view
        touchDetectorView = View(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    // User touched screen - show overlay
                    if (isInLandscapeMode && wasHiddenForLandscape) {
                        currentView?.visibility = View.VISIBLE
                        wasHiddenForLandscape = false
                        removeTouchDetector()
                        // Restart auto-hide timer
                        scheduleLandscapeAutoHide()
                    }
                    true
                } else {
                    false
                }
            }
        }
        
        // Add transparent view covering entire screen
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        
        try {
            windowManager.addView(touchDetectorView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun removeTouchDetector() {
        touchDetectorView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        touchDetectorView = null
    }
    
    private fun applyCustomizations() {
        val overlayColor = appPreferences.getInt("overlay_color", 0xFFA8B0C0.toInt())
        val transparency = appPreferences.getInt("overlay_transparency", 90)
        val size = appPreferences.getInt("overlay_size", 100)
        
        Log.d("OverlayView", "Applying customizations - Color: ${String.format("#%08X", overlayColor)}, Transparency: $transparency%, Size: $size%")
        
        // Calculate alpha from transparency percentage (0-100% to 0-255)
        val alpha = (transparency * 255 / 100)
        val colorWithAlpha = (overlayColor and 0x00FFFFFF) or (alpha shl 24)
        
        // Apply color and alpha to collapsed view
        collapsedView?.apply {
            background?.let { drawable ->
                // Use setTint to apply color to any drawable type
                drawable.mutate().setTint(colorWithAlpha)
                Log.d("OverlayView", "Applied color to collapsed view background")
            }
            
            // Apply transparency to the icon as well
            findViewById<ImageView>(R.id.collapsedIcon)?.apply {
                // Apply alpha to the white icon
                val iconAlpha = (transparency * 255 / 100)
                imageAlpha = iconAlpha
                Log.d("OverlayView", "Applied alpha $iconAlpha to collapsed icon")
            }
            
            // Apply size scaling
            val scale = size / 100f
            scaleX = scale
            scaleY = scale
            Log.d("OverlayView", "Applied scale $scale to collapsed view")
        }
        
        // Apply color and alpha to expanded view
        expandedView?.apply {
            // Find the main container and apply color
            val mainLayout = findViewById<View>(R.id.expandedLayout)
            mainLayout?.background?.let { drawable ->
                // Create a darker version for expanded view
                val darkerColor = darkenColor(colorWithAlpha)
                drawable.mutate().setTint(darkerColor)
                Log.d("OverlayView", "Applied darker color to expanded view")
            }
            
            // Apply transparency to all icons in expanded view
            val iconAlpha = (transparency * 255 / 100)
            findViewById<ImageView>(R.id.volumeUpButton)?.imageAlpha = iconAlpha
            findViewById<ImageView>(R.id.volumeDownButton)?.imageAlpha = iconAlpha
            findViewById<ImageView>(R.id.flashlightButton)?.imageAlpha = iconAlpha
            findViewById<ImageView>(R.id.lockScreenButton)?.imageAlpha = iconAlpha
            findViewById<TextView>(R.id.volumeText)?.alpha = (transparency / 100f)
            Log.d("OverlayView", "Applied alpha to expanded view icons")
            
            // Apply size scaling
            val scale = size / 100f
            scaleX = scale
            scaleY = scale
            Log.d("OverlayView", "Applied scale $scale to expanded view")
        }
    }
    
    private fun darkenColor(color: Int): Int {
        val factor = 0.7f
        val alpha = Color.alpha(color)
        val red = (Color.red(color) * factor).toInt().coerceIn(0, 255)
        val green = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val blue = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        return Color.argb(alpha, red, green, blue)
    }
}

