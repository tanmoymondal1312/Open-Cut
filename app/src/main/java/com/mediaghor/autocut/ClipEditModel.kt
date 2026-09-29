package com.mediaghor.autocut

data class ClipRange(val startMs: Long, val endMs: Long)

class ClipEditModel {

    private val clips = ArrayList<ClipRange>()
    private val history = ArrayDeque<List<ClipRange>>()

    var durationMs = 0L
        private set

    val kept: List<ClipRange> get() = clips
    val count: Int get() = clips.size
    fun canUndo(): Boolean = history.isNotEmpty()

    fun reset(durationMs: Long) {
        this.durationMs = durationMs.coerceAtLeast(0L)
        clips.clear()
        history.clear()
        if (this.durationMs > 0L) clips.add(ClipRange(0L, this.durationMs))
    }

    fun coversFull(): Boolean {
        if (clips.isEmpty()) return true
        if (clips.size > 1) return false
        return clips[0].startMs == 0L && clips[0].endMs >= durationMs
    }

    fun activeIndexAt(timeMs: Long): Int {
        for (index in clips.indices) {
            val clip = clips[index]
            if (timeMs >= clip.startMs && timeMs < clip.endMs) return index
        }
        return -1
    }

    fun splitAt(timeMs: Long): Boolean {
        if (durationMs <= 0L) return false
        val index = activeIndexAt(timeMs)
        if (index < 0) return false
        val clip = clips[index]
        if (timeMs - clip.startMs < MIN_CLIP_MS || clip.endMs - timeMs < MIN_CLIP_MS) return false
        push()
        clips[index] = ClipRange(clip.startMs, timeMs)
        clips.add(index + 1, ClipRange(timeMs, clip.endMs))
        return true
    }

    fun deleteAt(timeMs: Long): Boolean {
        if (clips.size <= 1) return false
        val index = activeIndexAt(timeMs)
        if (index < 0) return false
        push()
        clips.removeAt(index)
        return true
    }

    fun undo(): Boolean {
        if (history.isEmpty()) return false
        val previous = history.removeLast()
        clips.clear()
        clips.addAll(previous)
        return true
    }

    private fun push() {
        history.addLast(clips.toList())
        if (history.size > MAX_HISTORY) history.removeFirst()
    }

    companion object {
        private const val MIN_CLIP_MS = 300L
        private const val MAX_HISTORY = 50
    }
}
