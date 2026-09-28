package com.example.autocut

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.MediaInformation
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

class EditActivity : AppCompatActivity() {

    private lateinit var videoView: VideoView
    private lateinit var previewContainer: FrameLayout
    private lateinit var seekBar: SeekBar
    private lateinit var controlBar: View
    private lateinit var tvTime: TextView
    private lateinit var btnPlayPause: ImageView
    private lateinit var timeline: TimelineView
    private lateinit var btnStartCut: View
    private lateinit var btnCutExport: View
    private val handler = Handler(Looper.getMainLooper())
    private var prepared = false
    private var seeking = false
    private var controlsVisible = false
    private var videoWidth = 0
    private var videoHeight = 0

    private var videoUri: Uri? = null
    private var videoFps = 0
    private val engine by lazy { CutEngine(applicationContext) }
    private var cutState = CutState.IDLE
    private var cutTimes = LongArray(0)
    private var autoExport = false
    private var resumeAfterCut = false

    private var cutDialog: Dialog? = null
    private lateinit var tvDialogTitle: TextView
    private lateinit var tvDialogStatus: TextView
    private lateinit var tvDialogPercent: TextView
    private lateinit var progressCut: ProgressBar
    private lateinit var btnDialogCancel: View
    private lateinit var btnDialogClose: View
    private lateinit var btnDialogExport: View

    private enum class CutState { IDLE, CUTTING, CUT_DONE, EXPORTING }

    private val hideControlsRunnable = Runnable { hideControls() }

    private val progressTicker = object : Runnable {
        override fun run() {
            updateSeekBar()
            handler.postDelayed(this, 250L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_edit)

        videoView = findViewById(R.id.videoView)
        previewContainer = findViewById(R.id.previewContainer)
        seekBar = findViewById(R.id.seekBar)
        controlBar = findViewById(R.id.controlBar)
        tvTime = findViewById(R.id.tvTime)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        timeline = findViewById(R.id.timeline)

        val main = findViewById<View>(R.id.main)
        val bottomNav = findViewById<View>(R.id.bottomNav)
        val statusBarScrim = findViewById<View>(R.id.statusBarScrim)
        ViewCompat.setOnApplyWindowInsetsListener(main) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, 0)
            bottomNav.setPadding(0, 0, 0, bars.bottom)
            statusBarScrim.layoutParams = statusBarScrim.layoutParams.apply {
                height = bars.top
            }
            insets
        }

        previewContainer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyPreviewSize()
        }

        findViewById<View>(R.id.navHome).setOnClickListener { finish() }
        findViewById<View>(R.id.navSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnStartCut = findViewById(R.id.btnStartCut)
        btnCutExport = findViewById(R.id.btnCutExport)
        wirePlayerControls()
        wireCutControls()

        val uri = intent.data
        if (uri == null) {
            finish()
            return
        }
        videoUri = uri
        preparePlayer(uri)
        timeline.setVideoUri(uri)
        probeFrameRate(uri)
    }

    private fun probeFrameRate(uri: Uri) {
        Thread {
            var fps = 0
            try {
                val input = FFmpegKitConfig.getSafParameterForRead(applicationContext, uri)
                fps = parseFrameRate(FFprobeKit.getMediaInformation(input).mediaInformation)
            } catch (error: Throwable) {
                fps = 0
            }
            val result = fps
            runOnUiThread {
                if (result > 0 && videoUri == uri) {
                    videoFps = result
                    AppSettings.setLastVideoFps(this, result)
                }
            }
        }.start()
    }

    private fun parseFrameRate(info: MediaInformation?): Int {
        val raw = info?.streams
            ?.firstOrNull { it.type == "video" }
            ?.averageFrameRate
            ?: return 0
        return try {
            val parts = raw.split("/")
            val numerator = parts[0].toDouble()
            val denominator = if (parts.size > 1) parts[1].toDouble() else 1.0
            if (numerator <= 0 || denominator <= 0) 0
            else Math.round(numerator / denominator).toInt()
        } catch (error: Throwable) {
            0
        }
    }

    private fun wirePlayerControls() {
        previewContainer.setOnClickListener {
            if (controlsVisible) {
                handler.removeCallbacks(hideControlsRunnable)
                hideControls()
            } else {
                showControls()
            }
        }
        btnPlayPause.setOnClickListener {
            togglePlayPause()
            showControls()
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser || !prepared) return
                val duration = videoView.duration
                if (duration <= 0) return
                val position = progress.toLong() * duration / 1000L
                videoView.seekTo(position.toInt())
                updateClock(position, duration.toLong())
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                seeking = true
                handler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                seeking = false
                updateSeekBar()
                showControls()
            }
        })
        syncPlayerControls()
    }

    private fun showControls() {
        for (control in controls()) {
            if (control.visibility != View.VISIBLE) {
                control.animate().cancel()
                control.alpha = 0f
                control.visibility = View.VISIBLE
            }
            control.animate().alpha(1f).setDuration(160L).start()
        }
        controlsVisible = true
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, CONTROLS_HIDE_MS)
    }

    private fun hideControls() {
        if (seeking) {
            handler.postDelayed(hideControlsRunnable, CONTROLS_HIDE_MS)
            return
        }
        controlsVisible = false
        for (control in controls()) {
            control.animate().cancel()
            control.animate()
                .alpha(0f)
                .setDuration(200L)
                .withEndAction {
                    if (!controlsVisible) control.visibility = View.INVISIBLE
                }
                .start()
        }
    }

    private fun controls(): List<View> = listOf(btnPlayPause, controlBar)

    private fun togglePlayPause() {
        if (!prepared) return
        if (videoView.isPlaying) {
            videoView.pause()
        } else {
            videoView.start()
        }
        syncPlayerControls()
    }

    private fun syncPlayerControls() {
        val playing = prepared && videoView.isPlaying
        btnPlayPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        btnPlayPause.contentDescription =
            getString(if (playing) R.string.pause_video else R.string.play_video)
        handler.removeCallbacks(progressTicker)
        if (playing) handler.post(progressTicker)
    }

    private fun updateSeekBar() {
        if (!prepared || seeking) return
        val duration = videoView.duration
        if (duration <= 0) return
        val position = videoView.currentPosition.toLong()
        seekBar.progress = (position * 1000L / duration).toInt()
        updateClock(position, duration.toLong())
    }

    private fun updateClock(positionMs: Long, durationMs: Long) {
        tvTime.text = getString(
            R.string.player_time,
            formatClock(positionMs),
            formatClock(durationMs)
        )
    }

    private fun formatClock(ms: Long): String {
        val totalSeconds = ms.coerceAtLeast(0L) / 1000L
        return if (totalSeconds >= 3600L) {
            String.format(
                Locale.US,
                "%d:%02d:%02d",
                totalSeconds / 3600,
                (totalSeconds % 3600) / 60,
                totalSeconds % 60
            )
        } else {
            String.format(Locale.US, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
        }
    }

    private fun preparePlayer(uri: Uri) {
        videoView.setOnPreparedListener { mediaPlayer ->
            prepared = true
            mediaPlayer.isLooping = true
            mediaPlayer.setOnVideoSizeChangedListener { _, width, height ->
                fitPreviewToVideo(width, height)
            }
            fitPreviewToVideo(mediaPlayer.videoWidth, mediaPlayer.videoHeight)
            updateClock(0L, mediaPlayer.duration.toLong())
            videoView.start()
            syncPlayerControls()
            showControls()
        }
        videoView.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, R.string.video_error, Toast.LENGTH_SHORT).show()
            true
        }
        videoView.setVideoURI(uri)
    }

    private fun fitPreviewToVideo(videoWidth: Int, videoHeight: Int) {
        if (videoWidth <= 0 || videoHeight <= 0) return
        this.videoWidth = videoWidth
        this.videoHeight = videoHeight
        previewContainer.post { applyPreviewSize() }
    }

    private fun applyPreviewSize() {
        if (videoWidth <= 0 || videoHeight <= 0) return
        val containerWidth = previewContainer.width
        val containerHeight = previewContainer.height
        if (containerWidth <= 0 || containerHeight <= 0) return
        val scale = min(
            containerWidth.toFloat() / videoWidth,
            containerHeight.toFloat() / videoHeight
        )
        val params = videoView.layoutParams as FrameLayout.LayoutParams
        params.width = (videoWidth * scale).toInt().coerceAtLeast(1)
        params.height = (videoHeight * scale).toInt().coerceAtLeast(1)
        params.gravity = Gravity.CENTER
        videoView.layoutParams = params
    }

    // ==================== CUT / EXPORT ====================

    private fun wireCutControls() {
        btnStartCut.setOnClickListener { startCut(autoExport = false) }
        btnCutExport.setOnClickListener { startCut(autoExport = true) }
    }

    private fun startCut(autoExport: Boolean) {
        if (cutState == CutState.CUTTING || cutState == CutState.EXPORTING) return
        val uri = videoUri ?: return
        val durationMs = videoView.duration
        if (!prepared || durationMs <= 0) {
            Toast.makeText(this, R.string.cut_bad_video, Toast.LENGTH_SHORT).show()
            return
        }
        val cps = effectiveCutsPerSecond()
        val cuts = buildCuts(durationMs, cps)
        cutTimes = cuts
        this.autoExport = autoExport
        cutState = CutState.CUTTING
        resumeAfterCut = prepared && videoView.isPlaying
        videoView.pause()
        syncPlayerControls()
        hideControls()
        setActionButtonsEnabled(false)
        timeline.setCutMarks(cuts, Long.MIN_VALUE)
        showCuttingDialog(cuts.size)
        engine.cut(uri, durationMs.toLong(), cuts, cps.toLong(), cutListener)
    }

    private fun effectiveCutsPerSecond(): Int {
        val preference = AppSettings.cutsPerSecond(this)
        val frameRateLimit = (videoFps.takeIf { it > 0 }
            ?: AppSettings.lastVideoFps(this).takeIf { it > 0 }
            ?: AppSettings.DEFAULT_MAX_FPS)
            .coerceAtLeast(1)
        val max = minOf(AppSettings.MAX_CUTS_PER_SECOND, frameRateLimit)
        return preference.coerceIn(AppSettings.MIN_CUTS_PER_SECOND, max)
    }

    private fun buildCuts(durationMs: Int, cutsPerSecond: Int): LongArray {
        val count = (durationMs.toLong() * cutsPerSecond) / 1000L
        if (count <= 0L) return LongArray(0)
        return LongArray(count.toInt()) { index ->
            (index + 1) * 1000L / cutsPerSecond
        }
    }

    private fun ensureCutDialog(): Dialog {
        cutDialog?.let { return it }
        val dialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(R.layout.dialog_cut_progress)
            setCanceledOnTouchOutside(false)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setDimAmount(DIALOG_DIM)
            setOnCancelListener { handleCutDialogCancel() }
        }
        tvDialogTitle = dialog.findViewById(R.id.tvDialogTitle)
        tvDialogStatus = dialog.findViewById(R.id.tvDialogStatus)
        tvDialogPercent = dialog.findViewById(R.id.tvDialogPercent)
        progressCut = dialog.findViewById(R.id.progressCut)
        btnDialogCancel = dialog.findViewById(R.id.btnDialogCancel)
        btnDialogClose = dialog.findViewById(R.id.btnDialogClose)
        btnDialogExport = dialog.findViewById(R.id.btnDialogExport)
        btnDialogCancel.setOnClickListener { dialog.cancel() }
        btnDialogClose.setOnClickListener { closeCutDialog() }
        btnDialogExport.setOnClickListener { startExport() }
        val width = (resources.displayMetrics.widthPixels * DIALOG_WIDTH_FRACTION).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        cutDialog = dialog
        return dialog
    }

    private fun showDialog() {
        val dialog = ensureCutDialog()
        if (!dialog.isShowing) dialog.show()
    }

    private fun showCuttingDialog(totalCuts: Int) {
        showDialog()
        tvDialogTitle.setText(R.string.cut_dialog_title)
        tvDialogStatus.text =
            if (totalCuts > 0) getString(R.string.cut_dialog_count, 0, totalCuts)
            else getString(R.string.cut_dialog_preparing)
        tvDialogPercent.visibility = View.VISIBLE
        tvDialogPercent.text = getString(R.string.cut_dialog_percent, 0)
        progressCut.visibility = View.VISIBLE
        progressCut.progress = 0
        btnDialogCancel.visibility = View.VISIBLE
        btnDialogClose.visibility = View.GONE
        btnDialogExport.visibility = View.GONE
    }

    private fun showCutDoneDialog() {
        tvDialogTitle.setText(R.string.cut_done_title)
        tvDialogStatus.text = getString(R.string.cut_done_status, cutTimes.size)
        tvDialogPercent.visibility = View.VISIBLE
        tvDialogPercent.text = getString(R.string.cut_dialog_percent, 100)
        progressCut.visibility = View.VISIBLE
        progressCut.progress = 100
        btnDialogCancel.visibility = View.GONE
        btnDialogClose.visibility = View.VISIBLE
        btnDialogExport.visibility = View.VISIBLE
    }

    private fun showExportingDialog() {
        showDialog()
        tvDialogTitle.setText(R.string.cut_exporting_title)
        tvDialogStatus.setText(R.string.cut_exporting_status)
        tvDialogPercent.visibility = View.VISIBLE
        tvDialogPercent.text = getString(R.string.cut_dialog_percent, 0)
        progressCut.visibility = View.VISIBLE
        progressCut.progress = 0
        btnDialogCancel.visibility = View.VISIBLE
        btnDialogClose.visibility = View.GONE
        btnDialogExport.visibility = View.GONE
    }

    private fun showErrorDialog(message: String) {
        showDialog()
        tvDialogTitle.setText(R.string.cut_error_title)
        tvDialogStatus.text = message
        tvDialogPercent.visibility = View.GONE
        progressCut.visibility = View.GONE
        btnDialogCancel.visibility = View.GONE
        btnDialogClose.visibility = View.VISIBLE
        btnDialogExport.visibility = View.GONE
    }

    private fun startExport() {
        if (cutState != CutState.CUT_DONE) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_STORAGE
            )
            return
        }
        cutState = CutState.EXPORTING
        showExportingDialog()
        val name = "AutoCut_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        engine.export(name, cutListener)
    }

    private fun closeCutDialog() {
        cutState = CutState.IDLE
        setActionButtonsEnabled(true)
        cutDialog?.dismiss()
        resumePlayback()
    }

    private fun resumePlayback() {
        if (resumeAfterCut && prepared && !videoView.isPlaying) {
            videoView.start()
            syncPlayerControls()
        }
    }

    private fun handleCutDialogCancel() {
        when (cutState) {
            CutState.CUTTING -> {
                engine.cancelCut()
                timeline.clearCutMarks()
            }

            CutState.EXPORTING -> engine.cancelExport()

            else -> Unit
        }
        cutState = CutState.IDLE
        setActionButtonsEnabled(true)
        resumePlayback()
    }

    private fun setActionButtonsEnabled(enabled: Boolean) {
        val alpha = if (enabled) 1f else 0.45f
        for (button in listOf(btnStartCut, btnCutExport)) {
            button.isEnabled = enabled
            button.alpha = alpha
        }
    }

    private val cutListener = object : CutEngine.Listener {
        override fun onCutProgress(
            percent: Int,
            processedMs: Long,
            appliedCuts: Int,
            totalCuts: Int
        ) {
            if (cutState != CutState.CUTTING) return
            tvDialogPercent.text = getString(R.string.cut_dialog_percent, percent)
            tvDialogStatus.text = getString(R.string.cut_dialog_count, appliedCuts, totalCuts)
            progressCut.progress = percent
            timeline.setCutProgress(processedMs)
        }

        override fun onCutReady(output: File) {
            if (cutState != CutState.CUTTING) return
            cutState = CutState.CUT_DONE
            timeline.setCutProgress(Long.MAX_VALUE)
            if (autoExport) {
                startExport()
            } else {
                showCutDoneDialog()
            }
        }

        override fun onCutFailed() {
            if (cutState != CutState.CUTTING) return
            timeline.clearCutMarks()
            cutState = CutState.IDLE
            setActionButtonsEnabled(true)
            showErrorDialog(getString(R.string.cut_error_cut))
        }

        override fun onExportProgress(percent: Int) {
            if (cutState != CutState.EXPORTING) return
            tvDialogPercent.text = getString(R.string.cut_dialog_percent, percent)
            progressCut.progress = percent
        }

        override fun onExportSaved(fileName: String) {
            if (cutState != CutState.EXPORTING) return
            cutState = CutState.IDLE
            setActionButtonsEnabled(true)
            cutDialog?.dismiss()
            resumePlayback()
            Toast.makeText(
                this@EditActivity,
                getString(R.string.cut_exported, fileName),
                Toast.LENGTH_LONG
            ).show()
        }

        override fun onExportFailed() {
            if (cutState != CutState.EXPORTING) return
            cutState = CutState.IDLE
            setActionButtonsEnabled(true)
            showErrorDialog(getString(R.string.cut_error_export))
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_STORAGE) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startExport()
        } else {
            Toast.makeText(this, R.string.cut_permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (prepared) {
            videoView.start()
            syncPlayerControls()
        }
    }

    override fun onPause() {
        if (prepared) videoView.pause()
        handler.removeCallbacks(progressTicker)
        handler.removeCallbacks(hideControlsRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(progressTicker)
        handler.removeCallbacks(hideControlsRunnable)
        engine.cancelCut()
        engine.cancelExport()
        cutDialog?.dismiss()
        timeline.release()
        videoView.stopPlayback()
        super.onDestroy()
    }

    companion object {
        private const val CONTROLS_HIDE_MS = 3000L
        private const val DIALOG_DIM = 0.55f
        private const val DIALOG_WIDTH_FRACTION = 0.86f
        private const val REQUEST_STORAGE = 41
    }
}
