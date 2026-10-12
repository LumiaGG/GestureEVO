package io.github.lumiagg.gestureevo.utils

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.adapter
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

object MoshiUtils {
    val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    @OptIn(ExperimentalStdlibApi::class)
    inline fun <reified T : Any> getJsonAdapter(clz: T): JsonAdapter<T> {
        return moshi.adapter<T>()
    }

    @OptIn(ExperimentalStdlibApi::class)
    inline fun <reified T : Any> toJson(clz: T, format: Boolean = false): String {
        return if (format) {
            moshi.adapter<T>().indent(" ").toJson(clz)
        } else {
            moshi.adapter<T>().toJson(clz)
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    inline fun <reified T : Any> toModel(json: String, format: Boolean = false): T? {
        if (json.isEmpty()) return null
        return if (format) {
            moshi.adapter<T>().lenient().fromJson(json)
        } else {
            moshi.adapter<T>().fromJson(json)
        }
    }

    val listStringType = Types.newParameterizedType(List::class.java, String::class.java)
    val listStringAdapter: JsonAdapter<List<String>> = moshi.adapter(listStringType)
}
