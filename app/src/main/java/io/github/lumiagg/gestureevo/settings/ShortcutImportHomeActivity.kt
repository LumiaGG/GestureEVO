package io.github.lumiagg.gestureevo.settings

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** HOME entry returns users to the import page; never changes the default home automatically. */
class ShortcutImportHomeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, DynamicShortcutSettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }
}
