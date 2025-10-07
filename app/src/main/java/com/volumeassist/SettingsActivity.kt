package com.volumeassist

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsActivity : AppCompatActivity() {

    private lateinit var preferences: SharedPreferences
    private lateinit var backButton: ImageView
    private lateinit var autoStartSwitch: SwitchMaterial
    private lateinit var systemVolumeSwitch: SwitchMaterial
    private lateinit var colorSilver: MaterialButton
    private lateinit var colorBlue: MaterialButton
    private lateinit var colorGreen: MaterialButton
    private lateinit var colorRed: MaterialButton
    private lateinit var colorOrange: MaterialButton
    private lateinit var transparencySlider: SeekBar
    private lateinit var transparencyValue: TextView
    private lateinit var sizeSlider: SeekBar
    private lateinit var sizeValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        preferences = getSharedPreferences("VolumeAssistPrefs", MODE_PRIVATE)

        initViews()
        setupListeners()
    }

    private fun initViews() {
        backButton = findViewById(R.id.backButton)
        autoStartSwitch = findViewById(R.id.autoStartSwitch)
        systemVolumeSwitch = findViewById(R.id.systemVolumeSwitch)
        colorSilver = findViewById(R.id.colorSilver)
        colorBlue = findViewById(R.id.colorBlue)
        colorGreen = findViewById(R.id.colorGreen)
        colorRed = findViewById(R.id.colorRed)
        colorOrange = findViewById(R.id.colorOrange)
        transparencySlider = findViewById(R.id.transparencySlider)
        transparencyValue = findViewById(R.id.transparencyValue)
        sizeSlider = findViewById(R.id.sizeSlider)
        sizeValue = findViewById(R.id.sizeValue)

        // Load preferences
        autoStartSwitch.isChecked = preferences.getBoolean("auto_start", false)
        systemVolumeSwitch.isChecked = preferences.getBoolean("use_system_volume", false)
        
        val overlayColor = preferences.getInt("overlay_color", 0xFFA8B0C0.toInt())
        val transparency = preferences.getInt("overlay_transparency", 90)
        val size = preferences.getInt("overlay_size", 100)
        
        transparencySlider.progress = transparency
        transparencyValue.text = "$transparency%"
        sizeSlider.progress = size
        sizeValue.text = "$size%"
        
        updateColorSelection(overlayColor)
    }

    private fun setupListeners() {
        backButton.setOnClickListener {
            finish()
        }

        autoStartSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferences.edit().putBoolean("auto_start", isChecked).apply()
            if (isChecked) {
                Toast.makeText(this, R.string.auto_start_enabled, Toast.LENGTH_SHORT).show()
            }
        }

        systemVolumeSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferences.edit().putBoolean("use_system_volume", isChecked).apply()
            val message = if (isChecked) {
                "System volume bar will be used"
            } else {
                "Custom volume controls will be used"
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
        
        // Color button listeners - using practical colors
        colorSilver.setOnClickListener { setOverlayColor(0xFFA8B0C0.toInt()) }
        colorBlue.setOnClickListener { setOverlayColor(0xFF546E7A.toInt()) }
        colorGreen.setOnClickListener { setOverlayColor(0xFF78909C.toInt()) }
        colorRed.setOnClickListener { setOverlayColor(0xFF8D6E63.toInt()) }
        colorOrange.setOnClickListener { setOverlayColor(0xFF90A4AE.toInt()) }
        
        transparencySlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                transparencyValue.text = "$progress%"
                if (fromUser) {
                    preferences.edit().putInt("overlay_transparency", progress).apply()
                    notifyOverlayUpdate()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        sizeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                sizeValue.text = "$progress%"
                if (fromUser) {
                    preferences.edit().putInt("overlay_size", progress).apply()
                    notifyOverlayUpdate()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }
    
    private fun setOverlayColor(color: Int) {
        preferences.edit().putInt("overlay_color", color).apply()
        updateColorSelection(color)
        notifyOverlayUpdate()
        Toast.makeText(this, "Overlay color updated", Toast.LENGTH_SHORT).show()
    }
    
    private fun updateColorSelection(selectedColor: Int) {
        // Reset all strokes
        colorSilver.strokeWidth = 0
        colorBlue.strokeWidth = 0
        colorGreen.strokeWidth = 0
        colorRed.strokeWidth = 0
        colorOrange.strokeWidth = 0
        
        // Highlight selected color
        val strokeWidth = resources.getDimensionPixelSize(R.dimen.stroke_width_selected)
        when (selectedColor) {
            0xFFA8B0C0.toInt() -> colorSilver.strokeWidth = strokeWidth
            0xFF546E7A.toInt() -> colorBlue.strokeWidth = strokeWidth
            0xFF78909C.toInt() -> colorGreen.strokeWidth = strokeWidth
            0xFF8D6E63.toInt() -> colorRed.strokeWidth = strokeWidth
            0xFF90A4AE.toInt() -> colorOrange.strokeWidth = strokeWidth
        }
    }
    
    private fun notifyOverlayUpdate() {
        sendBroadcast(Intent("com.volumeassist.UPDATE_OVERLAY"))
    }
}
