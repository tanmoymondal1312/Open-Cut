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
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
    private val sessions = CopyOnWriteArrayList<FFmpegSession>()
    private val generationCounter = AtomicInteger(0)

    @Volatile
    private var cancelled = false

    fun cut(
        uri: Uri,
        fallbackDurationMs: Long,
        cuts: LongArray,
        cutsPerSecond: Long,
        listener: Listener
    ) {
        val generation = generationCounter.incrementAndGet()
        cancelled = false
        sessions.clear()
        Thread {
            try {
                runCut(uri, fallbackDurationMs, cuts, cutsPerSecond, listener, generation)
            } catch (error: Throwable) {
                Log.e(TAG, "cut error", error)
                if (isStale(generation)) return@Thread
                main.post {
                    if (!isStale(generation)) listener.onCutFailed()
                }
            }
        }.start()
    }

    private fun runCut(
        uri: Uri,
        fallbackDurationMs: Long,
        cuts: LongArray,
        cutsPerSecond: Long,
        listener: Listener,
        generation: Int
    ) {
        val probeInput = FFmpegKitConfig.getSafParameterForRead(context, uri)
        val probe = FFprobeKit.getMediaInformation(probeInput).mediaInformation
        val durationMs = probe?.duration?.toDoubleOrNull()
            ?.let { (it * 1000.0).toLong() }
            ?.takeIf { it > 0L }
            ?: fallbackDurationMs
        val hasAudio = probe?.streams?.any { it.type == "audio" } == true
        val audioBitrate = pickAudioBitrate(probe)

        val frameIndex = probeFrameIndex(uri)
        var workers = minOf(
            MAX_WORKERS,
            maxOf(1, Runtime.getRuntime().availableProcessors() / 2)
        )
        val startsUs = LongArray(MAX_WORKERS + 1)
        if (frameIndex == null) {
            workers = 1
            startsUs[0] = 0L
            startsUs[1] = durationMs * 1000L
        } else {
            while (true) {
                val nominalMs = durationMs.toDouble() / workers
                var aligned = true
                for (index in 0 until workers) {
                    val us = if (index == 0) {
                        0L
                    } else {
                        val gridSec =
                            Math.ceil(nominalMs * index * cutsPerSecond / 1000.0) / cutsPerSecond
                        firstFrameUsAt(frameIndex, gridSec)
                    }
                    startsUs[index] = us
                    if (index > 0 && us <= startsUs[index - 1]) {
                        aligned = false
                    }
                }
                startsUs[workers] = durationMs * 1000L
                if (startsUs[workers - 1] >= startsUs[workers]) aligned = false
                if (aligned || workers == 1) break
                workers--
            }
        }
        val boundaries = LongArray(workers + 1) { startsUs[it] / 1000L }
        val lens = LongArray(workers) { (boundaries[it + 1] - boundaries[it]).coerceAtLeast(1L) }
        val keyInt = frameIndex?.let { ptsUs ->
            val spanSec = (ptsUs[ptsUs.size - 1] - ptsUs[0]) / 1_000_000.0
            if (spanSec > 0) {
                val fps = (ptsUs.size - 1) / spanSec
                Math.ceil(fps / cutsPerSecond).toLong() + 2
            } else null
        }

        val workDir = File(context.cacheDir, "cut")
        workDir.listFiles()?.forEach { it.delete() }
        workDir.mkdirs()
        val outputs = Array(workers) { File(workDir, "video_$it.mp4") }
        val audioFile = File(workDir, "audio.m4a")
        val concatFile = File(workDir, "concat.txt")

        val local = LongArray(workers)
        val finishedVideo = AtomicInteger(0)
        val audioDone = AtomicBoolean(!hasAudio)
        val failed = AtomicBoolean(false)
        val muxStarted = AtomicBoolean(false)

        fun stale(): Boolean = cancelled || isStale(generation) || failed.get()

        fun postProgress() {
            var fractionTotal = 0.0
            for (index in 0 until workers) {
                fractionTotal += local[index].toDouble() / lens[index]
            }
            val fraction = (fractionTotal / workers).coerceIn(0.0, 1.0)
            val percent = (fraction * 100).toInt().coerceIn(0, 100)
            val processedMs = (fraction * durationMs).toLong()
            val applied = countApplied(cuts, boundaries, local)
            main.post {
                if (!stale()) {
                    listener.onCutProgress(percent, processedMs, applied, cuts.size)
                }
            }
        }

        fun fail(why: Any?) {
            if (cancelled || isStale(generation)) return
            if (!failed.compareAndSet(false, true)) return
            Log.e(TAG, "cut failed: $why")
            for (session in sessions) {
                session.cancel()
            }
            main.post {
                if (!cancelled && !isStale(generation)) listener.onCutFailed()
            }
        }

        fun checkComplete() {
            if (finishedVideo.get() != workers || !audioDone.get()) return
            if (!muxStarted.compareAndSet(false, true)) return
            Thread {
                try {
                    val windowsUs = LongArray(workers) { startsUs[it + 1] - startsUs[it] }
                    mux(workDir, outputs, windowsUs, audioFile, concatFile, hasAudio, listener, generation)
                } catch (error: Throwable) {
                    Log.e(TAG, "mux error", error)
                    fail(error)
                }
            }.start()
        }

        for (index in 0 until workers) {
            if (cancelled) return
            val startUs = startsUs[index]
            val lenUs = startsUs[index + 1] - startUs
            val tUs = if (frameIndex == null) {
                lenUs + 1000L
            } else {
                val lastIndex = if (index == workers - 1) {
                    frameIndex.size - 1
                } else {
                    firstFrameIndexAt(frameIndex, startsUs[index + 1]) - 1
                }
                val lastLocalUs = frameIndex[lastIndex] - startUs
                if (lastLocalUs >= lenUs) lastLocalUs + 100L else lenUs
            }
            val startSec = startUs / 1_000_000.0
            val offsetSeconds =
                Math.ceil(startSec * cutsPerSecond - 1e-9) / cutsPerSecond - startSec
            val arguments = mutableListOf(
                "-y",
                "-stats_period", "0.2",
                "-threads", "2",
                "-ss", micros(startUs),
                "-t", micros(tUs),
                "-i", FFmpegKitConfig.getSafParameterForRead(context, uri),
                "-map", "0:v:0",
                "-an",
                "-threads", "2",
                "-c:v", "libx264",
                "-preset", "ultrafast",
                "-crf", CRF.toString(),
                "-pix_fmt", "yuv420p",
                "-sc_threshold", "0"
            )
            if (keyInt != null) arguments += listOf("-g", keyInt.toString())
            arguments += listOf(
                "-force_key_frames",
                "expr:gte(t," + decimal(offsetSeconds) + "+n_forced/$cutsPerSecond-0.002)",
                "-avoid_negative_ts", "make_zero",
                outputs[index].absolutePath
            )
            val workerIndex = index
            val session = FFmpegKit.executeWithArgumentsAsync(
                arguments.toTypedArray(),
                { completed ->
                    if (stale()) return@executeWithArgumentsAsync
                    if (ReturnCode.isSuccess(completed.returnCode)) {
                        local[workerIndex] = lens[workerIndex]
                        main.post { postProgress() }
                        finishedVideo.incrementAndGet()
                        checkComplete()
                    } else {
                        fail(completed.failStackTrace ?: completed.output)
                    }
                },
                null,
                { stats ->
                    if (stale()) return@executeWithArgumentsAsync
                    local[workerIndex] = stats.time.toLong().coerceIn(0L, lens[workerIndex])
                    main.post { postProgress() }
                }
            )
            sessions.add(session)
        }

        if (hasAudio && !cancelled) {
            val audioArguments = arrayOf(
                "-y",
                "-stats_period", "0.5",
                "-threads", "2",
                "-i", FFmpegKitConfig.getSafParameterForRead(context, uri),
                "-map", "0:a:0",
                "-vn",
                "-c:a", "aac",
                "-b:a", audioBitrate.toString(),
                audioFile.absolutePath
            )
            val audioSession = FFmpegKit.executeWithArgumentsAsync(
                audioArguments,
                { completed ->
                    if (stale()) return@executeWithArgumentsAsync
                    if (ReturnCode.isSuccess(completed.returnCode)) {
                        audioDone.set(true)
                        checkComplete()
                    } else {
                        fail(completed.failStackTrace ?: completed.output)
                    }
                },
                null,
                null
            )
            sessions.add(audioSession)
        }
    }

    private fun mux(
        workDir: File,
        outputs: Array<File>,
        windowsUs: LongArray,
        audioFile: File,
        concatFile: File,
        hasAudio: Boolean,
        listener: Listener,
        generation: Int
    ) {
        concatFile.writeText(outputs.mapIndexed { index, file ->
            "file '" + file.absolutePath + "'\nduration " + micros(windowsUs[index])
        }.joinToString("\n"))
        val arguments = mutableListOf(
            "-y",
            "-f", "concat",
            "-safe", "0",
            "-i", concatFile.absolutePath
        )
        if (hasAudio) arguments += listOf("-i", audioFile.absolutePath)
        arguments += listOf("-map", "0:v:0")
        if (hasAudio) arguments += listOf("-map", "1:a:0", "-shortest")
        arguments += listOf(
            "-c", "copy",
            "-movflags", "+faststart",
            "-metadata", "comment=Auto Cut",
            File(workDir, OUTPUT_NAME).absolutePath
        )
        val session = FFmpegKit.executeWithArguments(arguments.toTypedArray())
        if (cancelled || isStale(generation)) return
        if (ReturnCode.isSuccess(session.returnCode)) {
            outputs.forEach { it.delete() }
            audioFile.delete()
            concatFile.delete()
            main.post {
                if (!cancelled && !isStale(generation)) listener.onCutReady(outputFile())
            }
        } else {
            Log.e(TAG, "mux failed: ${session.failStackTrace ?: session.output}")
            main.post {
                if (!cancelled && !isStale(generation)) listener.onCutFailed()
            }
        }
    }

    private fun countApplied(cuts: LongArray, boundaries: LongArray, local: LongArray): Int {
        var applied = 0
        for (cut in cuts) {
            var worker = 0
            while (worker < boundaries.size - 2 && cut >= boundaries[worker + 1]) {
                worker++
            }
            if (local[worker] >= cut - boundaries[worker]) applied++
        }
        return applied
    }

    private fun isStale(generation: Int): Boolean = generationCounter.get() != generation

    private fun micros(us: Long): String = String.format(Locale.US, "%.6f", us / 1_000_000.0)

    private fun decimal(value: Double): String = String.format(Locale.US, "%.9f", value)

    private fun firstFrameIndexAt(ptsUs: LongArray, targetUs: Long): Int {
        var low = 0
        var high = ptsUs.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (ptsUs[mid] < targetUs) low = mid + 1 else high = mid
        }
        return low
    }

    private fun firstFrameUsAt(ptsUs: LongArray, gridSec: Double): Long {
        val index = firstFrameIndexAt(ptsUs, (gridSec * 1_000_000.0).toLong())
        return ptsUs[minOf(index, ptsUs.size - 1)]
    }

    private fun probeFrameIndex(uri: Uri): LongArray? {
        return probeFrameIndexCore { FFmpegKitConfig.getSafParameterForRead(context, uri) }
    }

    private fun probeFrameIndexCore(input: () -> String): LongArray? {
        return try {
            val tbSession = FFprobeKit.executeWithArguments(
                arrayOf(
                    "-v", "error",
                    "-show_streams",
                    "-of", "default=noprint_wrappers=1",
                    input()
                )
            )
            val tbText = tbSession.output ?: return null
            val videoBlock = tbText.split("index=").firstOrNull { it.contains("codec_type=video") }
                ?: return null
            val tbLine = videoBlock.lineSequence().firstOrNull { it.startsWith("time_base=") }
                ?: return null
            val parts = tbLine.removePrefix("time_base=").trim().split("/")
            if (parts.size != 2) return null
            val tbNum = parts[0].toLongOrNull() ?: return null
            val tbDen = parts[1].toLongOrNull() ?: return null
            if (tbNum <= 0L || tbDen <= 0L) return null
            val ptsSession = FFprobeKit.executeWithArguments(
                arrayOf(
                    "-v", "error",
                    "-select_streams", "v:0",
                    "-show_entries", "packet=pts",
                    "-of", "csv=p=0",
                    input()
                )
            )
            val output = ptsSession.output ?: return null
            val rawList = ArrayList<Long>()
            for (line in output.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed == "N/A") continue
                val ticks = trimmed.toLongOrNull() ?: continue
                rawList.add(ticks)
            }
            val ptsList = ArrayList<Long>()
            for (ticks in rawList) {
                val us = BigDecimal(ticks)
                    .multiply(BigDecimal(tbNum))
                    .multiply(BigDecimal(1_000_000L))
                    .divide(BigDecimal(tbDen), 0, RoundingMode.FLOOR)
                    .toLong()
                ptsList.add(us)
            }
            if (ptsList.size < 2) return null
            ptsList.sort()
            ptsList.toLongArray()
        } catch (error: Throwable) {
            Log.e(TAG, "frame index probe failed", error)
            null
        }
    }

    fun cancelCut() {
        cancelled = true
        for (session in sessions) {
            session.cancel()
        }
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
                    (copied * 100 / total).toInt()
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

    private fun outputFile(): File = File(File(context.cacheDir, "cut"), OUTPUT_NAME)

    companion object {
        private const val TAG = "CutEngine"
        private const val OUTPUT_NAME = "output.mp4"
        private const val MAX_WORKERS = 6
        private const val CRF = 18
        private const val DEFAULT_AUDIO_BITRATE = 160_000L
        private const val MIN_AUDIO_BITRATE = 64_000L
        private const val MAX_AUDIO_BITRATE = 320_000L
    }
}
