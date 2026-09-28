package com.example.autocut

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.Executors
import kotlin.math.min

class EditActivity : AppCompatActivity() {

    private lateinit var videoView: VideoView
    private lateinit var previewContainer: FrameLayout
    private lateinit var timeline: TimelineView
    private val executor = Executors.newSingleThreadExecutor()
    private var prepared = false
    private var videoWidth = 0
    private var videoHeight = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_edit)

        videoView = findViewById(R.id.videoView)
        previewContainer = findViewById(R.id.previewContainer)
        timeline = findViewById(R.id.timeline)

        val main = findViewById<View>(R.id.main)
        val bottomNav = findViewById<View>(R.id.bottomNav)
        ViewCompat.setOnApplyWindowInsetsListener(main) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, 0)
            bottomNav.setPadding(0, 0, 0, bars.bottom)
            insets
        }

        previewContainer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyPreviewSize()
        }

        findViewById<View>(R.id.navHome).setOnClickListener { finish() }

        val uri = intent.data
        if (uri == null) {
            finish()
            return
        }
        preparePlayer(uri)
        extractThumbnails(uri)
    }

    private fun preparePlayer(uri: Uri) {
        videoView.setOnPreparedListener { mediaPlayer ->
            prepared = true
            mediaPlayer.isLooping = true
            mediaPlayer.setOnVideoSizeChangedListener { _, width, height ->
                fitPreviewToVideo(width, height)
            }
            fitPreviewToVideo(mediaPlayer.videoWidth, mediaPlayer.videoHeight)
            videoView.start()
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

    private fun extractThumbnails(uri: Uri) {
        executor.execute {
            var retriever: MediaMetadataRetriever? = null
            try {
                retriever = MediaMetadataRetriever()
                retriever.setDataSource(this, uri)
                val duration = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L

                runOnUiThread {
                    if (!isFinishing && !isDestroyed) timeline.setDuration(duration)
                }

                if (duration <= 0L) return@execute
                val count = (duration / 800).coerceIn(6, 48).toInt()
                val targetHeight = 140

                for (index in 0 until count) {
                    if (Thread.currentThread().isInterrupted) return@execute
                    val timeMs = duration * index / count
                    val source = try {
                        retriever.getFrameAtTime(
                            timeMs,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                        )
                    } catch (error: RuntimeException) {
                        null
                    } ?: continue

                    val thumbnail = if (source.height > targetHeight) {
                        val ratio = targetHeight.toFloat() / source.height
                        val scaled = Bitmap.createScaledBitmap(
                            source,
                            maxOf(1, (source.width * ratio).toInt()),
                            targetHeight,
                            true
                        )
                        if (scaled != source) source.recycle()
                        scaled
                    } else {
                        source
                    }

                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) timeline.addFrame(timeMs, thumbnail)
                    }
                }
            } catch (error: Exception) {
                // Unsupported or revoked URI â€” timeline simply stays empty.
            } finally {
                try {
                    retriever?.release()
                } catch (ignored: Exception) {
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (prepared) videoView.start()
    }

    override fun onPause() {
        if (prepared) videoView.pause()
        super.onPause()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        videoView.stopPlayback()
        super.onDestroy()
    }
}
