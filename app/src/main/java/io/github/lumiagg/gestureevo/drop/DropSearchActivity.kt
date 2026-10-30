package io.github.lumiagg.gestureevo.drop

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.search.SearchEngineExecutor
import io.github.lumiagg.gestureevo.search.config.SearchEngineDataManager
import io.github.lumiagg.gestureevo.utils.ToastHelper
import io.github.lumiagg.gestureevo.utils.TransparentActivityLauncher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Supplies the existing search executor with an Activity without displaying a search page. */
class DropSearchActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            finish()
            return
        }
        lifecycleScope.launch {
            try {
                val tag = intent.getStringExtra(EXTRA_ENGINE)
                val engine = SearchEngineDataManager.readSearchEngineList(applicationContext)
                    .firstOrNull { it.tag == tag && it.showInSearchEngineList }
                if (engine == null) {
                    ToastHelper.showInUIThread(applicationContext, R.string.external_drop_failed)
                    return@launch
                }
                val images = intent.getStringArrayListExtra(EXTRA_IMAGES).orEmpty().map(Uri::parse)
                SearchEngineExecutor(this@DropSearchActivity, engine, false) {}.execute(
                    query = intent.getStringExtra(EXTRA_QUERY).orEmpty(),
                    imageUris = images,
                ).join()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ToastHelper.showInUIThread(applicationContext, R.string.external_drop_failed)
            } finally {
                finish()
            }
        }
    }

    companion object {
        private const val EXTRA_ENGINE = "engine"
        private const val EXTRA_QUERY = "query"
        private const val EXTRA_IMAGES = "images"

        fun start(context: Context, engineTag: String, query: String, imageUris: List<Uri>) {
            TransparentActivityLauncher.start(context, Intent(context, DropSearchActivity::class.java).apply {
                putExtra(EXTRA_ENGINE, engineTag)
                putExtra(EXTRA_QUERY, query)
                putStringArrayListExtra(EXTRA_IMAGES, ArrayList(imageUris.map(Uri::toString)))
            })
        }
    }
}
