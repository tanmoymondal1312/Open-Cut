package com.example.autocut

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.MediaInformation
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream

class CutEngine(private val context: Context) {

    interface Listener {
        fun onCutProgress(percent: Int, processedMs: Long, appliedCuts: Int, totalCuts: Int)
        fun onCutReady(output: File)
        fun onCutFailed()
        fun onExportProgress(percent: Int)
        fun onExportSaved(fileName: String)
        fun onExportFailed()
    }

    private val main = Handler(Looper.getMainLooper())
    private var session: FFmpegSession? = null
    @Volatile
    private var cancelled = false

    fun cut(
        uri: Uri,
        fallbackDurationMs: Long,
        cuts: LongArray,
        cutsPerSecond: Long,
        listener: Listener
    ) {
        cancelled = false
        val keyFrameInterval = cutsPerSecond.coerceAtLeast(1L)
        Thread {
            try {
                val probeInput = FFmpegKitConfig.getSafParameterForRead(context, uri)
                val probe = FFprobeKit.getMediaInformation(probeInput).mediaInformation
                val durationMs = probe?.duration?.toDoubleOrNull()
                    ?.let { (it * 1000.0).toLong() }
                    ?.takeIf { it > 0L }
                    ?: fallbackDurationMs
                val audioBitrate = pickAudioBitrate(probe)

                val output = outputFile()
                output.parentFile?.mkdirs()

                val input = FFmpegKitConfig.getSafParameterForRead(context, uri)
                val arguments = arrayOf(
                    "-y",
                    "-stats_period", "0.2",
                    "-i", input,
                    "-map_metadata", "-1",
                    "-map", "0:v:0",
                    "-map", "0:a:0?",
                    "-c:v", "libx264",
                    "-preset", "veryfast",
                    "-crf", "18",
                    "-pix_fmt", "yuv420p",
                    "-force_key_frames", "expr:gte(t,n_forced/$keyFrameInterval)",
                    "-c:a", "aac",
                    "-b:a", audioBitrate.toString(),
                    "-movflags", "+faststart",
                    "-metadata", "comment=Auto Cut",
                    output.absolutePath
                )

                session = FFmpegKit.executeWithArgumentsAsync(
                    arguments,
                    { completed ->
                        if (cancelled) return@executeWithArgumentsAsync
                        if (ReturnCode.isSuccess(completed.returnCode)) {
                            main.post {
                                if (!cancelled) listener.onCutReady(output)
                            }
                        } else {
                            Log.e(TAG, "cut failed: ${completed.failStackTrace ?: completed.output}")
                            main.post {
                                if (!cancelled) listener.onCutFailed()
                            }
                        }
                    },
                    null,
                    { stats ->
                        if (cancelled) return@executeWithArgumentsAsync
                        val processedMs = stats.time.toLong()
                        val percent = if (durationMs > 0L) {
                            ((processedMs * 100) / durationMs).toInt()
                        } else {
                            0
                        }.coerceIn(0, 100)
                        var applied = 0
                        for (cut in cuts) {
                            if (cut <= processedMs) applied++ else break
                        }
                        main.post {
                            if (!cancelled) {
                                listener.onCutProgress(percent, processedMs, applied, cuts.size)
                            }
                        }
                    }
                )
            } catch (error: Throwable) {
                Log.e(TAG, "cut error", error)
                main.post {
                    if (!cancelled) listener.onCutFailed()
                }
            }
        }.start()
    }

    fun cancelCut() {
        cancelled = true
        session?.cancel()
        session = null
    }

    fun export(fileName: String, listener: Listener) {
        cancelled = false
        Thread {
            var saved = false
            try {
                saved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    mediaStoreExport(fileName, listener)
                } else {
                    legacyExport(fileName, listener)
                }
            } catch (error: Throwable) {
                Log.e(TAG, "export error", error)
            }
            if (cancelled) return@Thread
            if (saved) {
                main.post {
                    if (!cancelled) listener.onExportSaved(fileName)
                }
            } else {
                main.post {
                    if (!cancelled) listener.onExportFailed()
                }
            }
        }.start()
    }

    fun cancelExport() {
        cancelled = true
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun mediaStoreExport(fileName: String, listener: Listener): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return false
        try {
            val stream = resolver.openOutputStream(uri) ?: return false.also {
                resolver.delete(uri, null, null)
            }
            val total = outputFile().length()
            val ok = stream.use { writeStream(it, total, listener) }
            if (!ok) {
                resolver.delete(uri, null, null)
                return false
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return true
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun legacyExport(fileName: String, listener: Listener): Boolean {
        val directory = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )
        if (!directory.exists()) directory.mkdirs()
        val target = File(directory, fileName)
        try {
            val total = outputFile().length()
            val ok = FileOutputStream(target).use { writeStream(it, total, listener) }
            if (!ok) {
                target.delete()
                return false
            }
            return true
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    private fun writeStream(out: OutputStream, total: Long, listener: Listener): Boolean {
        var copied = 0L
        var lastPercent = -1
        FileInputStream(outputFile()).use { input ->
            val buffer = ByteArray(512 * 1024)
            while (true) {
                if (cancelled) return false
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                copied += read
                val percent = if (total > 0L) {
                    ((copied * 100) / total).toInt()
                } else {
                    0
                }.coerceIn(0, 99)
                if (percent != lastPercent) {
                    lastPercent = percent
                    main.post {
                        if (!cancelled) listener.onExportProgress(percent)
                    }
                }
            }
            out.flush()
        }
        return !cancelled
    }

    private fun pickAudioBitrate(info: MediaInformation?): Long {
        val bitrate = info?.streams
            ?.firstOrNull { it.type == "audio" }
            ?.bitrate
            ?.toLongOrNull()
            ?: DEFAULT_AUDIO_BITRATE
        return bitrate.coerceIn(MIN_AUDIO_BITRATE, MAX_AUDIO_BITRATE)
    }

    private fun outputFile(): File = File(File(context.cacheDir, "cut"), "output.mp4")

    companion object {
        private const val TAG = "CutEngine"
        private const val DEFAULT_AUDIO_BITRATE = 160_000L
        private const val MIN_AUDIO_BITRATE = 64_000L
        private const val MAX_AUDIO_BITRATE = 320_000L
    }
}
