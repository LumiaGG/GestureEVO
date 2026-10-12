package io.github.lumiagg.gestureevo.utils

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.github.lumiagg.gestureevo.R

class CrashShowActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crash)
        intent.getStringExtra(EXTRA_MESSAGE)?.takeIf { it.isNotEmpty() }?.let { message ->
            findViewById<TextView>(R.id.msg)?.text = message
        }
    }

    companion object {
        const val EXTRA_MESSAGE = "msg"
    }
}
