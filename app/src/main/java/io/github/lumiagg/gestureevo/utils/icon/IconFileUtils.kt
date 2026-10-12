package io.github.lumiagg.gestureevo.utils.icon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import io.github.lumiagg.gestureevo.utils.dp2px
import java.io.File

class IconFileUtils(private val context: Context) {
    var imgPath = ""
    private val iconDirectory get() = File(context.filesDir, "icon")
    private var source: Source? = null

    private sealed interface Source {
        data class Image(val bitmap: Bitmap) : Source
        data class Vector(val drawable: Drawable) : Source
    }

    fun saveIcon(): Boolean {
        val current = source ?: return false
        val directory = iconDirectory
        if (!directory.isDirectory && !directory.mkdirs()) return false
        if (imgPath.isEmpty()) {
            imgPath = File(directory, "saveIcon-${System.currentTimeMillis()}-${System.nanoTime()}").absolutePath
        }
        return when (current) {
            is Source.Image -> IconImageIO.savePng(current.bitmap, imgPath)
            is Source.Vector -> IconImageIO.savePng(current.drawable, imgPath, 50.dp2px.toInt())
        }
    }

    fun setGalleryIcon(uri: Uri?): IconFileUtils = apply {
        IconImageIO.read(context, uri, 512, 512)?.let { source = Source.Image(it) }
    }

    fun getImgDrawable(): Drawable? = (source as? Source.Vector)?.drawable
    fun setImgDrawable(drawable: Drawable?): IconFileUtils = apply {
        source = drawable?.let(Source::Vector)
    }

    fun getImgBitmap(): Bitmap? = (source as? Source.Image)?.bitmap
    fun setImgBitmap(bitmap: Bitmap?): IconFileUtils = apply {
        source = bitmap?.let(Source::Image)
    }

    fun clear(): IconFileUtils = apply {
        source = null
        imgPath = ""
    }
}
