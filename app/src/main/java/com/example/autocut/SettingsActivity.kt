package com.example.autocut

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class SettingsActivity : AppCompatActivity() {

    private lateinit var seekCps: SeekBar
    private lateinit var tvValue: TextView
    private lateinit var previewDensity: CutDensityPreviewView
    private lateinit var tvPreviewInterval: TextView
    private lateinit var tvRangeMin: TextView
    private lateinit var tvRangeMax: TextView
    private lateinit var tvFpsChip: TextView
    private lateinit var tvFpsInfo: TextView

    private var minCps = AppSettings.MIN_CUTS_PER_SECOND
    private var maxCps = AppSettings.DEFAULT_MAX_FPS
    private var currentCps = AppSettings.DEFAULT_CUTS_PER_SECOND
    private var knownFps = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings)

        seekCps = findViewById(R.id.seekCps)
        tvValue = findViewById(R.id.tvCpsValue)
        previewDensity = findViewById(R.id.previewDensity)
        tvPreviewInterval = findViewById(R.id.tvPreviewInterval)
        tvRangeMin = findViewById(R.id.tvRangeMin)
        tvRangeMax = findViewById(R.id.tvRangeMax)
        tvFpsChip = findViewById(R.id.tvFpsChip)
        tvFpsInfo = findViewById(R.id.tvFpsInfo)

        val main = findViewById<View>(R.id.main)
        val statusBarScrim = findViewById<View>(R.id.statusBarScrim)
        val scroll = findViewById<ScrollView>(R.id.scroll)
        val bottomNav = findViewById<View>(R.id.bottomNav)
        ViewCompat.setOnApplyWindowInsetsListener(main) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, 0)
            statusBarScrim.layoutParams = statusBarScrim.layoutParams.apply {
                height = bars.top
            }
            scroll.setPadding(bars.left, 0, bars.right, 0)
            bottomNav.setPadding(0, 0, 0, bars.bottom)
            insets
        }

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.navHome).setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            )
        }
        findViewById<View>(R.id.navEdit).setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            )
            startActivity(Intent(this, VideoPickerActivity::class.java))
        }

        knownFps = AppSettings.lastVideoFps(this)
        val frameRateLimit =
            if (knownFps > 0) knownFps.coerceAtLeast(1) else AppSettings.DEFAULT_MAX_FPS
        maxCps = minOf(AppSettings.MAX_CUTS_PER_SECOND, frameRateLimit)
        currentCps = AppSettings.cutsPerSecond(this).coerceIn(minCps, maxCps)

        seekCps.max = maxCps - minCps
        seekCps.progress = currentCps - minCps
        seekCps.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                currentCps = progress + minCps
                AppSettings.setCutsPerSecond(this@SettingsActivity, currentCps)
                render()
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit

            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })

        render()
    }

    private fun render() {
        tvValue.text = currentCps.toString()
        previewDensity.setCutsPerSecond(currentCps)
        tvPreviewInterval.text = getString(R.string.preview_interval, 1.0 / currentCps)
        tvRangeMin.text = getString(R.string.range_min, minCps)
        tvRangeMax.text =
            if (knownFps > 0) {
                getString(R.string.range_max_fps, maxCps, knownFps)
            } else {
                getString(R.string.range_max_default, maxCps)
            }
        tvFpsChip.text =
            if (knownFps > 0) getString(R.string.fps_chip, knownFps)
            else getString(R.string.fps_chip_unknown)
        tvFpsInfo.text =
            if (knownFps > 0) {
                getString(R.string.fps_info_known, knownFps, maxCps)
            } else {
                getString(R.string.fps_info_unknown, AppSettings.MAX_CUTS_PER_SECOND)
            }
    }
}
