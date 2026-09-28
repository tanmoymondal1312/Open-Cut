package com.example.autocut

import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        applyTitleSpans()

        val main = findViewById<android.view.View>(R.id.main)
        val bottomNav = findViewById<android.view.View>(R.id.bottomNav)

        ViewCompat.setOnApplyWindowInsetsListener(main) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, 0)
            bottomNav.setPadding(0, 0, 0, bars.bottom)
            insets
        }
    }

    private fun applyTitleSpans() {
        findViewById<TextView>(R.id.tvHeaderTitle).text =
            colorize(getString(R.string.header_title), "Cut" to R.color.accent_cyan)

        findViewById<TextView>(R.id.tvTitle).text =
            colorize(
                getString(R.string.hero_title),
                "edit" to R.color.accent_cyan,
                "?" to R.color.text_secondary
            )
    }

    private fun colorize(text: String, vararg targets: Pair<String, Int>): SpannableString {
        val span = SpannableString(text)
        for ((word, color) in targets) {
            val index = text.indexOf(word)
            if (index >= 0) {
                span.setSpan(
                    ForegroundColorSpan(getColor(color)),
                    index,
                    index + word.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return span
    }
}
