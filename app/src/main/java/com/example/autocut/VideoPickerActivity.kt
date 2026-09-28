package com.example.autocut

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.Executors

class VideoPickerActivity : AppCompatActivity() {

    private lateinit var gridView: GridView
    private lateinit var emptyState: TextView
    private lateinit var permissionState: TextView
    private lateinit var adapter: VideoAdapter
    private val loader = Executors.newSingleThreadExecutor()

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (hasVideoAccess()) {
            showGrid()
            loadVideos()
        } else {
            showPermissionState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_video_picker)
        window.setDimAmount(DIM_AMOUNT)

        gridView = findViewById(R.id.gridVideos)
        emptyState = findViewById(R.id.tvEmpty)
        permissionState = findViewById(R.id.tvPermission)

        val content = findViewById<LinearLayout>(R.id.pickerContent)
        val sheet = findViewById<LinearLayout>(R.id.sheet)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.pickerRoot)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            content.setPadding(bars.left, bars.top, bars.right, 0)
            sheet.setPadding(0, 0, 0, bars.bottom)
            insets
        }

        adapter = VideoAdapter(this) { item -> openEditor(item) }
        gridView.adapter = adapter

        findViewById<ImageView>(R.id.btnClose).setOnClickListener { finish() }
        findViewById<View>(R.id.clickAway).setOnClickListener { finish() }
        permissionState.setOnClickListener { requestVideoAccess() }

        if (hasVideoAccess()) {
            showGrid()
            loadVideos()
        } else {
            requestVideoAccess()
        }
    }

    private fun openEditor(item: VideoItem) {
        startActivity(
            Intent(this, EditActivity::class.java)
                .setData(item.uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
        finish()
    }

    private fun requiredPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun hasVideoAccess(): Boolean {
        val permission = when {
            Build.VERSION.SDK_INT >= 34 -> Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            Build.VERSION.SDK_INT >= 33 -> Manifest.permission.READ_MEDIA_VIDEO
            else -> Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            return true
        }
        if (Build.VERSION.SDK_INT >= 34) {
            return ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_VIDEO
            ) == PackageManager.PERMISSION_GRANTED
        }
        return false
    }

    private fun requestVideoAccess() {
        requestPermission.launch(requiredPermissions())
    }

    private fun showGrid() {
        permissionState.visibility = View.GONE
        gridView.visibility = View.VISIBLE
    }

    private fun showPermissionState() {
        findViewById<ProgressBar>(R.id.progressPicker).visibility = View.GONE
        gridView.visibility = View.GONE
        emptyState.visibility = View.GONE
        permissionState.visibility = View.VISIBLE
    }

    private fun loadVideos() {
        val progress = findViewById<ProgressBar>(R.id.progressPicker)
        progress.visibility = View.VISIBLE
        emptyState.visibility = View.GONE
        loader.execute {
            val items = queryVideos()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                progress.visibility = View.GONE
                adapter.submit(items)
                emptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun queryVideos(): List<VideoItem> {
        val items = ArrayList<VideoItem>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE
        )
        val sort = "${MediaStore.Video.Media.DATE_ADDED} DESC"
        try {
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sort
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    items.add(
                        VideoItem(
                            id = id,
                            uri = ContentUris.withAppendedId(
                                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                                id
                            ),
                            name = cursor.getString(nameColumn) ?: "",
                            durationMs = cursor.getLong(durationColumn),
                            sizeBytes = cursor.getLong(sizeColumn)
                        )
                    )
                }
            }
        } catch (error: SecurityException) {
            return emptyList()
        }
        return items
    }

    override fun onDestroy() {
        loader.shutdownNow()
        adapter.release()
        super.onDestroy()
    }

    companion object {
        private const val DIM_AMOUNT = 0.62f
    }
}
