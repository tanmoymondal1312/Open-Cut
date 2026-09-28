package com.example.autocut

import android.content.Context

object AppSettings {

    const val MIN_CUTS_PER_SECOND = 1
    const val MAX_CUTS_PER_SECOND = 10
    const val DEFAULT_CUTS_PER_SECOND = 3
    const val DEFAULT_MAX_FPS = 30

    private const val FILE = "app_settings"
    private const val KEY_CUTS_PER_SECOND = "cuts_per_second"
    private const val KEY_VIDEO_FPS = "video_frame_rate"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun cutsPerSecond(context: Context): Int =
        prefs(context).getInt(KEY_CUTS_PER_SECOND, DEFAULT_CUTS_PER_SECOND)
            .coerceIn(MIN_CUTS_PER_SECOND, MAX_CUTS_PER_SECOND)

    fun setCutsPerSecond(context: Context, value: Int) {
        prefs(context).edit()
            .putInt(
                KEY_CUTS_PER_SECOND,
                value.coerceIn(MIN_CUTS_PER_SECOND, MAX_CUTS_PER_SECOND)
            )
            .apply()
    }

    fun lastVideoFps(context: Context): Int = prefs(context).getInt(KEY_VIDEO_FPS, 0)

    fun setLastVideoFps(context: Context, fps: Int) {
        prefs(context).edit().putInt(KEY_VIDEO_FPS, fps).apply()
    }
}
