package com.example.autocut

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import java.util.Locale
import java.util.concurrent.Executors

data class VideoItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val sizeBytes: Long
)

class VideoAdapter(
    context: Context,
    private val onVideoClick: (VideoItem) -> Unit
) : BaseAdapter() {

    private val inflater = LayoutInflater.from(context)
    private val appContext = context.applicationContext
    private val items = ArrayList<VideoItem>()
    private val thumbCache = LruCache<Long, Bitmap>(THUMB_CACHE_ENTRIES)
    private val loader = Executors.newSingleThreadExecutor()

    fun submit(newItems: List<VideoItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun release() {
        loader.shutdownNow()
    }

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): VideoItem = items[position]

    override fun getItemId(position: Int): Long = items[position].id

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = convertView ?: inflater.inflate(R.layout.item_video, parent, false)
        val holder = (row.tag as? Holder) ?: Holder(row).also { row.tag = it }
        holder.bind(items[position])
        return row
    }

    private inner class Holder(row: View) {
        private val thumb: ImageView = row.findViewById(R.id.ivThumb)
        private val duration: TextView = row.findViewById(R.id.tvDuration)
        private val name: TextView = row.findViewById(R.id.tvName)
        private val meta: TextView = row.findViewById(R.id.tvMeta)
        private val card: View = row.findViewById(R.id.videoCard)

        fun bind(item: VideoItem) {
            name.text = item.name
            meta.text = formatSize(item.sizeBytes)
            duration.text = if (item.durationMs > 0L) formatDuration(item.durationMs) else ""
            duration.visibility = if (item.durationMs > 0L) View.VISIBLE else View.GONE

            card.setOnClickListener { onVideoClick(item) }

            thumb.tag = item.id
            val cached = thumbCache.get(item.id)
            if (cached != null) {
                thumb.setImageBitmap(cached)
            } else {
                thumb.setImageDrawable(null)
                loadThumbnail(item)
            }
        }

        private fun loadThumbnail(item: VideoItem) {
            loader.execute {
                if (thumb.tag != item.id) return@execute
                var bitmap = thumbCache.get(item.id)
                if (bitmap == null) {
                    bitmap = decodeThumbnail(item) ?: return@execute
                    thumbCache.put(item.id, bitmap)
                }
                val result = bitmap
                thumb.post {
                    if (thumb.tag == item.id) thumb.setImageBitmap(result)
                }
            }
        }

        private fun decodeThumbnail(item: VideoItem): Bitmap? {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(appContext, item.uri)
                val frame = retriever.getFrameAtTime(
                    1_000_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                ) ?: return null
                scaleForThumb(frame)
            } catch (error: Exception) {
                null
            } finally {
                try {
                    retriever.release()
                } catch (ignored: Exception) {
                }
            }
        }

        private fun scaleForThumb(source: Bitmap): Bitmap {
            if (source.width <= THUMB_WIDTH_PX) return source
            val ratio = THUMB_WIDTH_PX.toFloat() / source.width
            val scaled = Bitmap.createScaledBitmap(
                source,
                THUMB_WIDTH_PX,
                (source.height * ratio).toInt().coerceAtLeast(1),
                true
            )
            if (scaled !== source) source.recycle()
            return scaled
        }
    }

    companion object {
        private const val THUMB_CACHE_ENTRIES = 64
        private const val THUMB_WIDTH_PX = 320

        fun formatDuration(ms: Long): String {
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

        fun formatSize(bytes: Long): String {
            if (bytes <= 0L) return ""
            val units = arrayOf("B", "KB", "MB", "GB")
            var value = bytes.toDouble()
            var unit = 0
            while (value >= 1024.0 && unit < units.size - 1) {
                value /= 1024.0
                unit++
            }
            return if (unit == 0) {
                String.format(Locale.US, "%d %s", value.toLong(), units[unit])
            } else {
                String.format(Locale.US, "%.1f %s", value, units[unit])
            }
        }
    }
}
